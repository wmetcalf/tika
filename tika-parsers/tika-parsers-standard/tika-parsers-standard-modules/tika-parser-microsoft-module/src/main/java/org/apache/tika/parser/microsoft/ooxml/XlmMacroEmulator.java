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
        private static final Limits DEFAULT = new Limits(
                65_536, 16L * 1024 * 1024, 4_096, 1024 * 1024,
                1_000_000, 1024 * 1024);

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
    private static final int MAX_FILE_CONTENT    = 8192;

    private final Map<String, Double> cellValues;
    private final XlmWorkbookSheetMap sheetMap;
    private final Limits limits;
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
        this.cellValues = cellValues;
        this.sheetMap = sheetMap;
        this.limits = limits;
    }

    boolean addMacroCell(int row, byte[] formulaBytes) {
        if (formulaBytes == null
                || cells.size() >= limits.maxMacroCells
                || formulaBytes.length > limits.maxFormulaBytes - retainedFormulaBytes) {
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
        Biff12XlmFormulaDecoder.EvalContext ctx =
                new Biff12XlmFormulaDecoder.EvalContext(
                        cellValues, new HashMap<>(),
                        limits.maxIocEntries, limits.maxIocChars,
                        limits.maxFileContentChars);

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

        // Emit any still-open file contents
        for (Map.Entry<Integer, StringBuilder> entry : ctx.fileContents.entrySet()) {
            String content = entry.getValue().toString();
            if (!content.isEmpty()) {
                String path = ctx.getFilePath(entry.getKey());
                retainIoc("FILE_CONTENT[" + path + "]: "
                        + content.substring(0, Math.min(MAX_FILE_CONTENT, content.length()))
                        + (content.length() > MAX_FILE_CONTENT ? "…" : ""));
            }
        }

        for (String ioc : ctx.iocs) {
            if (!retainIoc(ioc)) {
                break;
            }
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
        if (operations >= limits.maxOperations) {
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

    private boolean retainIoc(String ioc) {
        if (ioc == null) {
            return true;
        }
        if (iocs.size() >= limits.maxIocEntries
                || ioc.length() > limits.maxIocChars - retainedIocChars) {
            markLimit("XLSB XLM IOC retention limit reached");
            return false;
        }
        iocs.add(ioc);
        retainedIocChars += ioc.length();
        return true;
    }

    private void markLimit(String warning) {
        if (limitWarning == null) {
            limitWarning = warning;
        }
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
