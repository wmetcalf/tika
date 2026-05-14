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
    private final List<MacroCell> cells = new ArrayList<>();

    /** Collected IOC strings — populated by {@link #emulate()}. */
    final List<String> iocs = new ArrayList<>();

    XlmMacroEmulator(Map<String, Double> cellValues) {
        this.cellValues = cellValues;
    }

    void addMacroCell(int row, byte[] formulaBytes) {
        cells.add(new MacroCell(row, formulaBytes));
    }

    // ── Main entry point ────────────────────────────────────────────────────

    /**
     * Execute all collected macro cells and populate {@link #iocs}.
     * Safe to call multiple times (idempotent on the cell list).
     */
    void emulate() {
        iocs.clear();
        Biff12XlmFormulaDecoder.EvalContext ctx =
                new Biff12XlmFormulaDecoder.EvalContext(cellValues, new HashMap<>());

        int i = 0;
        int loopsExecuted = 0;
        while (i < cells.size()) {
            MacroCell cell = cells.get(i);
            Object result = evalCell(cell, ctx);

            if (result instanceof Biff12XlmFormulaDecoder.ForCellSignal) {
                Biff12XlmFormulaDecoder.ForCellSignal signal =
                        (Biff12XlmFormulaDecoder.ForCellSignal) result;
                int nextIdx = findNext(i + 1);
                if (nextIdx >= 0) {
                    executeForCellLoop(signal, i + 1, nextIdx, ctx);
                    loopsExecuted++;
                    i = nextIdx + 1;
                } else {
                    i++;
                }
            } else {
                i++;
            }
        }
        // Diagnostic: report handle state
        StringBuilder handleDiag = new StringBuilder();
        for (Map.Entry<Integer, StringBuilder> e : ctx.fileContents.entrySet()) {
            handleDiag.append(" h").append(e.getKey()).append("=").append(e.getValue().length());
        }
        ctx.iocs.add(0, "XLM_STATS: cells=" + cells.size()
                + " cellValues=" + cellValues.size()
                + " loops=" + loopsExecuted
                + " handles=[" + handleDiag + "]");

        // Emit any still-open file contents
        for (Map.Entry<Integer, StringBuilder> entry : ctx.fileContents.entrySet()) {
            String content = entry.getValue().toString();
            if (!content.isEmpty()) {
                String path = ctx.getFilePath(entry.getKey());
                iocs.add("FILE_CONTENT[" + path + "]: "
                        + content.substring(0, Math.min(MAX_FILE_CONTENT, content.length()))
                        + (content.length() > MAX_FILE_CONTENT ? "…" : ""));
            }
        }

        iocs.addAll(ctx.iocs);
    }

    // ── Loop execution ───────────────────────────────────────────────────────

    private void executeForCellLoop(Biff12XlmFormulaDecoder.ForCellSignal signal,
                                    int bodyStart, int nextIdx,
                                    Biff12XlmFormulaDecoder.EvalContext ctx) {
        List<Double> rangeValues = getRangeValues(signal.rangeRef, signal.sheetIdx);
        // Show first 3 keys of cellValues for diagnosis
        String sampleKeys = cellValues.keySet().stream()
                .limit(3).collect(java.util.stream.Collectors.joining("|"));
        ctx.iocs.add("LOOP_DEBUG: var=" + signal.varName
                + " range=" + signal.rangeRef
                + " sheetIdx=" + signal.sheetIdx
                + " rangeSize=" + rangeValues.size()
                + " bodyLen=" + (nextIdx - bodyStart)
                + " cvSize=" + cellValues.size()
                + " sampleKeys=[" + sampleKeys + "]");
        if (rangeValues.isEmpty()) {
            return;
        }

        int iterations = Math.min(rangeValues.size(), MAX_LOOP_ITERATIONS);
        for (int vi = 0; vi < iterations; vi++) {
            ctx.variables.put(signal.varName, rangeValues.get(vi));
            for (int ci = bodyStart; ci < nextIdx; ci++) {
                evalCell(cells.get(ci), ctx);
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
     */
    private List<Double> getRangeValues(String rangeRef, int sheetIdx) {
        int colon = rangeRef.indexOf(':');
        if (colon < 0) {
            return Collections.emptyList();
        }
        int[] start = parseA1(rangeRef.substring(0, colon));
        int[] end   = parseA1(rangeRef.substring(colon + 1));
        if (start == null || end == null) {
            return Collections.emptyList();
        }

        List<Double> values = new ArrayList<>();
        for (int row = start[0]; row <= end[0]; row++) {
            for (int col = start[1]; col <= end[1]; col++) {
                Double v = cellValues.get(sheetIdx + ":" + row + ":" + col);
                if (v != null) {
                    values.add(v);
                }
            }
        }
        return values;
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
