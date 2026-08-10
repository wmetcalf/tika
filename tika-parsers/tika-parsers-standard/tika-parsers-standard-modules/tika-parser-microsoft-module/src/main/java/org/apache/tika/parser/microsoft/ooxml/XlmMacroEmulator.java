/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tika.parser.microsoft.ooxml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * XLM macro emulator for XLSB (BIFF12) macro sheets.
 *
 * Executes macro cells in row order, evaluating XLM formulas using
 * {@link Biff12XlmFormulaDecoder#evaluateFormula}.  Handles the
 * {@code FOR.CELL / FWRITE / NEXT} dropper pattern, resolving
 * CHAR()-encoded payloads stored as number arrays in worksheets.
 *
 * IOCs (FOPEN paths, EXEC commands, CALL args, reconstructed file
 * contents) are accumulated in {@link #iocs} for the caller to emit.
 *
 * This is a static emulator — it evaluates expressions with known
 * constant values and does not model runtime Excel state.  Obfuscation
 * that depends on volatile functions (NOW, RAND) or live cell
 * computation is not resolved, but dangerous function names and their
 * statically-known arguments are always surfaced.
 */
class XlmMacroEmulator {

    static final class Limits {
        // maxMacroCells, maxFormulaBytes, maxIocEntries, maxIocChars,
        // maxOperations, maxFileContentChars
        //
        // maxFileContentChars raised 1 MB -> 10 MB: it bounds RECONSTRUCTED FILE CONTENT,
        // i.e. the payload a dropper assembles and writes. Silently cutting that is the
        // same class of evidence loss as the per-formula cap that amputated 22% of
        // macro-bearing documents. maxIocChars stays at 1 MB -- IOCs are URL/IP/path
        // strings, and 1 MB across 4096 entries is already generous for those.
        static final Limits DEFAULT = new Limits(
                65_536, 16L * 1024 * 1024, 4_096, 1024 * 1024,
                1_000_000, 10 * 1024 * 1024);

        /**
         * Build limits from {@link org.apache.tika.parser.microsoft.OfficeParserConfig},
         * falling back to {@link #DEFAULT} for anything left at 0/unset. Keeps these bounds
         * tunable per deployment instead of requiring a Tika recompile.
         */
        static Limits fromConfig(
                org.apache.tika.parser.microsoft.OfficeParserConfig cfg) {
            if (cfg == null) {
                return DEFAULT;
            }
            return new Limits(
                    cfg.getXlmMaxMacroCells() > 0
                            ? cfg.getXlmMaxMacroCells() : DEFAULT.maxMacroCells,
                    cfg.getXlmMaxFormulaBytes() > 0L
                            ? cfg.getXlmMaxFormulaBytes() : DEFAULT.maxFormulaBytes,
                    cfg.getXlmMaxIocEntries() > 0
                            ? cfg.getXlmMaxIocEntries() : DEFAULT.maxIocEntries,
                    cfg.getXlmMaxIocChars() > 0
                            ? cfg.getXlmMaxIocChars() : DEFAULT.maxIocChars,
                    cfg.getXlmMaxOperations() > 0L
                            ? cfg.getXlmMaxOperations() : DEFAULT.maxOperations,
                    cfg.getXlmMaxFileContentChars() > 0
                            ? cfg.getXlmMaxFileContentChars() : DEFAULT.maxFileContentChars);
        }

        final int maxMacroCells;
        final long maxFormulaBytes;
        final int maxIocEntries;
        final int maxIocChars;
        final long maxOperations;
        final int maxFileContentChars;

        Limits(int maxMacroCells, long maxFormulaBytes,
               int maxIocEntries, int maxIocChars,
               long maxOperations, int maxFileContentChars) {
            this.maxMacroCells = Math.max(0, maxMacroCells);
            this.maxFormulaBytes = Math.max(0, maxFormulaBytes);
            this.maxIocEntries = Math.max(0, maxIocEntries);
            this.maxIocChars = Math.max(0, maxIocChars);
            this.maxOperations = Math.max(0, maxOperations);
            this.maxFileContentChars = Math.max(0, maxFileContentChars);
        }
    }

    static final class DocumentBudget {
        private int remainingMacroCells;
        private long remainingFormulaBytes;
        private int remainingIocEntries;
        private int remainingIocChars;
        private long remainingOperations;
        private int remainingFileContentChars;

        DocumentBudget(Limits limits) {
            remainingMacroCells = limits.maxMacroCells;
            remainingFormulaBytes = limits.maxFormulaBytes;
            remainingIocEntries = limits.maxIocEntries;
            remainingIocChars = limits.maxIocChars;
            remainingOperations = limits.maxOperations;
            remainingFileContentChars = limits.maxFileContentChars;
        }

        synchronized boolean tryReserveMacroCell(int formulaBytes) {
            if (remainingMacroCells <= 0 || formulaBytes > remainingFormulaBytes) {
                return false;
            }
            remainingMacroCells--;
            remainingFormulaBytes -= formulaBytes;
            return true;
        }

        synchronized boolean tryConsumeOperation() {
            if (remainingOperations <= 0) {
                return false;
            }
            remainingOperations--;
            return true;
        }

        synchronized int remainingIocEntries() {
            return remainingIocEntries;
        }

        synchronized int remainingIocChars() {
            return remainingIocChars;
        }

        synchronized boolean tryRetainIoc(int chars) {
            if (remainingIocEntries <= 0 || chars > remainingIocChars) {
                return false;
            }
            remainingIocEntries--;
            remainingIocChars -= chars;
            return true;
        }

        synchronized int remainingFileContentChars() {
            return remainingFileContentChars;
        }

        synchronized void consumeFileContentChars(int chars) {
            remainingFileContentChars =
                    Math.max(0, remainingFileContentChars - Math.max(0, chars));
        }
    }

    /** A single macro cell: its row index and raw BIFF12 formula bytes. */
    static final class MacroCell {
        final int row;
        final byte[] formulaBytes;

        MacroCell(int row, byte[] formulaBytes) {
            this.row = row;
            this.formulaBytes = formulaBytes;
        }
    }

    private static final int MAX_LOOP_ITERATIONS = 65536;
    // Retained as the floor for the emission cap. Previously this WAS the emission cap,
    // which silently bounded reconstructed payload far below maxFileContentChars and made
    // that budget (and its config knob) unobservable.
    private static final int MAX_FILE_CONTENT    = 8192;

    private final Map<String, Double> cellValues;
    private final XlmWorkbookSheetMap sheetMap;
    private final Limits limits;
    private final DocumentBudget documentBudget;
    private final List<MacroCell> cells = new ArrayList<>();
    private long retainedFormulaBytes;
    private long operations;
    private int retainedIocChars;
    private boolean emulationAborted;
    private String limitWarning;

    /** Collected IOC strings — populated by {@link #emulate()}. */
    final List<String> iocs = new ArrayList<>();

    XlmMacroEmulator(Map<String, Double> cellValues, XlmWorkbookSheetMap sheetMap) {
        this(cellValues, sheetMap, Limits.DEFAULT);
    }

    XlmMacroEmulator(Map<String, Double> cellValues, XlmWorkbookSheetMap sheetMap,
                     Limits limits) {
        this(cellValues, sheetMap, limits, null);
    }

    XlmMacroEmulator(Map<String, Double> cellValues, XlmWorkbookSheetMap sheetMap,
                     Limits limits, DocumentBudget documentBudget) {
        this.cellValues = cellValues;
        this.sheetMap = sheetMap;
        this.limits = limits;
        this.documentBudget = documentBudget;
    }

    boolean addMacroCell(int row, byte[] formulaBytes) {
        if (formulaBytes == null
                || cells.size() >= limits.maxMacroCells
                || formulaBytes.length > limits.maxFormulaBytes - retainedFormulaBytes
                || (documentBudget != null
                        && !documentBudget.tryReserveMacroCell(formulaBytes.length))) {
            markLimit("XLSB XLM formula retention limit reached");
            return false;
        }
        cells.add(new MacroCell(row, formulaBytes));
        retainedFormulaBytes += formulaBytes.length;
        return true;
    }

    boolean isLimitReached() {
        return limitWarning != null;
    }

    String getLimitWarning() {
        return limitWarning;
    }

    // ── Main entry point ────────────────────────────────────────────────────

    /**
     * Execute all collected macro cells and populate {@link #iocs}.
     * Safe to call multiple times (idempotent on the cell list).
     */
    void emulate() {
        iocs.clear();
        operations = 0;
        retainedIocChars = 0;
        emulationAborted = false;
        int maxIocEntries = limits.maxIocEntries;
        int maxIocChars = limits.maxIocChars;
        int maxFileContentChars = limits.maxFileContentChars;
        if (documentBudget != null) {
            maxIocEntries = Math.min(maxIocEntries, documentBudget.remainingIocEntries());
            maxIocChars = Math.min(maxIocChars, documentBudget.remainingIocChars());
            maxFileContentChars = Math.min(
                    maxFileContentChars, documentBudget.remainingFileContentChars());
        }
        Biff12XlmFormulaDecoder.EvalContext ctx =
                new Biff12XlmFormulaDecoder.EvalContext(
                        cellValues, new HashMap<>(),
                        maxIocEntries, maxIocChars, maxFileContentChars);

        int i = 0;
        while (i < cells.size() && !emulationAborted) {
            if (!consumeOperation()) {
                break;
            }
            MacroCell cell = cells.get(i);
            Object result = evalCell(cell, ctx);
            stopOnContextLimit(ctx);

            if (result instanceof Biff12XlmFormulaDecoder.ForCellSignal) {
                Biff12XlmFormulaDecoder.ForCellSignal signal =
                        (Biff12XlmFormulaDecoder.ForCellSignal) result;
                int nextIdx = findNext(i + 1);
                if (emulationAborted) {
                    break;
                } else if (nextIdx >= 0) {
                    executeForCellLoop(signal, i + 1, nextIdx, ctx);
                    i = nextIdx + 1;
                } else {
                    i++;
                }
            } else {
                i++;
            }
        }

        // Order matters. EXEC/FOPEN/CALL are tens of chars and name what the macro DOES;
        // reconstructed file content is up to megabytes of obfuscated payload. Draining
        // file content first let one blob consume the whole IOC allowance and starve the
        // EXEC that identifies the execution -- observed on a real sample. Emit the
        // high-value, low-cost indicators first and give the bulk payload the remainder.
        for (String ioc : ctx.iocs) {
            // continue, not break: one over-budget entry must not suppress every smaller
            // indicator behind it.
            retainIoc(ioc);
        }

        // Per-cell problems, reported AFTER the loop so they can never abort it -- that abort
        // was the regression this replaces.
        //
        // Each distinct reason is retained (see markLimit) and the decorator publishes all of
        // them, so a per-cell diagnosis is no longer hidden behind an earlier budget warning.
        for (String warning : ctx.getNonFatalWarnings()) {
            markLimit(warning);
        }

        // Emit any still-open file contents. The cap is driven by the configured budget
        // (floored at the historical 8192) rather than being hardcoded to it -- otherwise
        // raising maxFileContentChars retains payload that is never emitted.
        int fileContentEmitCap = Math.max(MAX_FILE_CONTENT, maxFileContentChars);
        for (Map.Entry<Integer, StringBuilder> entry : ctx.fileContents.entrySet()) {
            String content = entry.getValue().toString();
            if (!content.isEmpty()) {
                String path = ctx.getFilePath(entry.getKey());
                // retainIoc() rejects an over-budget entry WHOLE, so cut the preview to
                // what will actually be kept instead of losing the entry outright.
                String head = "FILE_CONTENT[" + path + "]: ";
                int budget = remainingIocChars() - head.length() - 1;
                int cut = Math.min(Math.min(fileContentEmitCap, content.length()),
                        Math.max(0, budget));
                if (cut > 0) {
                    retainIoc(head + content.substring(0, cut)
                            + (content.length() > cut ? "…" : ""));
                } else {
                    // Report the drop WITHOUT emitting a content-free entry. Skipping the call
                    // entirely made the loss silent; emitting  instead reported it but
                    // consumed budget for zero evidence and starved later entries -- measured at 28
                    // extracted URLs across the 1,043-document XLSB corpus. Mark and move on.
                    markLimit("XLSB XLM reconstructed file content dropped: IOC budget exhausted");
                }
            }
        }
        if (documentBudget != null) {
            documentBudget.consumeFileContentChars(ctx.getRetainedFileContentChars());
        }
    }

    // ── Loop execution ───────────────────────────────────────────────────────

    private void executeForCellLoop(Biff12XlmFormulaDecoder.ForCellSignal signal,
                                    int bodyStart, int nextIdx,
                                    Biff12XlmFormulaDecoder.EvalContext ctx) {
        List<Double> rangeValues = getRangeValues(signal.rangeRef, signal.sheetIdx);
        if (rangeValues.isEmpty() || emulationAborted) {
            return;
        }

        int iterations = Math.min(rangeValues.size(), MAX_LOOP_ITERATIONS);
        for (int vi = 0; vi < iterations && !emulationAborted; vi++) {
            ctx.variables.put(signal.varName, rangeValues.get(vi));
            for (int ci = bodyStart; ci < nextIdx; ci++) {
                if (!consumeOperation()) {
                    break;
                }
                evalCell(cells.get(ci), ctx);
                stopOnContextLimit(ctx);
                if (emulationAborted) {
                    break;
                }
            }
        }
        ctx.variables.remove(signal.varName);
    }

    // ── Cell evaluation ──────────────────────────────────────────────────────

    private static Object evalCell(MacroCell cell,
                                   Biff12XlmFormulaDecoder.EvalContext ctx) {
        try {
            return Biff12XlmFormulaDecoder.evaluateFormula(cell.formulaBytes, ctx);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private int findNext(int startIdx) {
        for (int i = startIdx; i < cells.size(); i++) {
            if (!consumeOperation()) {
                return -1;
            }
            String formula = Biff12XlmFormulaDecoder.decode(cells.get(i).formulaBytes);
            if ("NEXT()".equals(formula)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Return all numeric cell values from the given range in row-then-column order.
     * rangeRef format: "A1:B2" (A1-style, 1-based).
     * sheetXtiIdx is the xtiIndex from the Area3dPtg, resolved through the
     * workbook's BrtExternSheet table to the actual sheet name.
     */
    private List<Double> getRangeValues(String rangeRef, int sheetXtiIdx) {
        int colon = rangeRef.indexOf(':');
        if (colon < 0) {
            return Collections.emptyList();
        }
        int[] start = parseA1(rangeRef.substring(0, colon));
        int[] end   = parseA1(rangeRef.substring(colon + 1));
        if (start == null || end == null) {
            return Collections.emptyList();
        }

        // Resolve xtiIndex → sheet name via the workbook extern-sheet table
        String sheetName = sheetMap.xtiToSheetName.getOrDefault(sheetXtiIdx,
                String.valueOf(sheetXtiIdx));

        List<Double> values = new ArrayList<>();
        // Iterate the sparse populated-cell map (bounded by the number of cells that
        // actually hold values, i.e. the workbook size) rather than the full rectangle.
        // A crafted FOR.CELL range like "A1:XFD1048576" would otherwise drive ~1.7e10
        // empty-cell probes (CPU-hang DoS); and a plain coordinate cap would silently
        // drop values that lie beyond the cutoff. Collect the in-range (row,col,value)
        // triples and return them row-major to match the original scan order.
        String prefix = sheetName + ":";
        List<double[]> matched = new ArrayList<>();
        for (Map.Entry<String, Double> e : cellValues.entrySet()) {
            if (!consumeOperation()) {
                return Collections.emptyList();
            }
            String key = e.getKey();
            if (key == null || !key.startsWith(prefix)) {
                continue;
            }
            String rc = key.substring(prefix.length());
            int sep = rc.lastIndexOf(':');
            if (sep <= 0) {
                continue;
            }
            try {
                int row = Integer.parseInt(rc.substring(0, sep));
                int col = Integer.parseInt(rc.substring(sep + 1));
                if (row >= start[0] && row <= end[0]
                        && col >= start[1] && col <= end[1]) {
                    matched.add(new double[]{row, col, e.getValue()});
                }
            } catch (NumberFormatException nfe) {
                // key suffix wasn't "row:col" — skip
            }
        }
        matched.sort((a, b) -> a[0] != b[0]
                ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        for (double[] t : matched) {
            values.add(t[2]);
        }
        return values;
    }

    private boolean consumeOperation() {
        if (operations >= limits.maxOperations
                || (documentBudget != null && !documentBudget.tryConsumeOperation())) {
            markLimit("XLSB XLM emulation operation limit reached");
            emulationAborted = true;
            return false;
        }
        operations++;
        return true;
    }

    private void stopOnContextLimit(Biff12XlmFormulaDecoder.EvalContext ctx) {
        if (!ctx.isLimitReached()) {
            return;
        }
        markLimit(ctx.getLimitWarning());
        emulationAborted = true;
    }

    /**
     * Chars still retainable as IOC text, across BOTH the per-emulator and document
     * budgets. retainIoc() rejects an over-budget entry whole, so previews are sized
     * against this rather than emitted and dropped.
     */
    private int remainingIocChars() {
        int remaining = Math.max(0, limits.maxIocChars - retainedIocChars);
        if (documentBudget != null) {
            remaining = Math.min(remaining, documentBudget.remainingIocChars());
        }
        return remaining;
    }

    private boolean retainIoc(String ioc) {
        if (ioc == null) {
            return true;
        }
        if (iocs.size() >= limits.maxIocEntries
                || ioc.length() > limits.maxIocChars - retainedIocChars
                || (documentBudget != null
                        && !documentBudget.tryRetainIoc(ioc.length()))) {
            markLimit("XLSB XLM IOC retention limit reached");
            return false;
        }
        iocs.add(ioc);
        retainedIocChars += ioc.length();
        return true;
    }

    /**
     * Record a reason. The FIRST becomes {@link #getLimitWarning()} (single-valued, for callers
     * that want one line); every DISTINCT reason is also kept in {@link #getLimitWarnings()}.
     *
     * <p>Keeping only the first meant the per-cell "formula only partially decodable / unknown
     * Ptg" diagnosis was discarded whenever any budget warning had already fired -- the same
     * attacker-picks-the-diagnosis suppression that was fixed in the decorators, still live on
     * the binary path. Bounded and deduped so a per-cell condition cannot repeat into metadata.
     */
    private void markLimit(String warning) {
        if (warning == null) {
            return;
        }
        if (limitWarning == null) {
            limitWarning = warning;
        }
        if (limitWarnings.size() < 16) {
            limitWarnings.add(warning);
        }
    }

    private final java.util.LinkedHashSet<String> limitWarnings = new java.util.LinkedHashSet<>();

    /** Every distinct reason recorded, in order. */
    java.util.Collection<String> getLimitWarnings() {
        return limitWarnings;
    }

    /**
     * Parse an A1-style cell reference ("BZ169") into a 0-based [row, col] pair.
     * Returns null if the reference cannot be parsed.
     */
    static int[] parseA1(String ref) {
        if (ref == null || ref.isEmpty()) {
            return null;
        }
        int i = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            i++;
        }
        if (i == 0 || i >= ref.length()) {
            return null;
        }
        String colStr = ref.substring(0, i).toUpperCase(java.util.Locale.ROOT);
        String rowStr = ref.substring(i);

        int col = 0;
        for (char c : colStr.toCharArray()) {
            col = col * 26 + (c - 'A' + 1);
        }
        col--; // 0-based

        int row;
        try {
            row = Integer.parseInt(rowStr) - 1; // 0-based
        } catch (NumberFormatException e) {
            return null;
        }
        if (row < 0 || col < 0) {
            return null;
        }
        return new int[]{row, col};
    }
}
