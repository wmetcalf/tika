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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pattern-based IOC scanner for XML-formula XLM macros — companion to the
 * BIFF12 emulator in {@link XlmMacroEmulator} for cases where the macrosheet
 * is XML rather than binary. The BIFF12 emulator can't run on text formulas
 * (its decoder operates on tokenized byte streams), so we fall back to
 * regex matching against the well-known dangerous XLM function vocabulary.
 *
 * <p>What this catches:</p>
 * <ul>
 *   <li>{@code EXEC("...")} → "EXEC: ..."</li>
 *   <li>{@code FOPEN("...", mode)} → "FOPEN: path (mode N)"</li>
 *   <li>{@code FWRITE(handle, "...")} / {@code FWRITELN(...)} → "FWRITE: content"</li>
 *   <li>{@code CALL("dll", "fn", ...)} → "CALL: dll!fn(...)"</li>
 *   <li>{@code ALERT("msg")} / {@code REGISTER("...")} → labeled IOC</li>
 *   <li>URLs appearing inside any formula (defense in depth)</li>
 * </ul>
 *
 * <p>What this misses on purpose:</p>
 * <ul>
 *   <li>Cell-reference resolution (e.g. {@code EXEC(A1)} where A1 holds the
 *       payload as a constant string) — handled at a best-effort level via
 *       the cellValues map; deeply chained refs fall through.</li>
 *   <li>CHAR()-encoded payload reconstruction — would need a real evaluator.
 *       The XLSB-side {@link XlmMacroEmulator} resolves these via FOR.CELL /
 *       FWRITE / NEXT loop emulation; deferred for the XML path.</li>
 * </ul>
 *
 * <p>The output format mirrors {@code XlmMacroEmulator.iocs} so callers can
 * dump the two side-by-side without per-source formatting differences.</p>
 */
final class XlmXmlIocScanner {

    private XlmXmlIocScanner() { /* utility */ }

    // ── Patterns ─────────────────────────────────────────────────────────────
    // Function-name match is case-insensitive — XLM is itself case-insensitive
    // and obfuscators sometimes randomize case to evade naive YARA rules.
    private static final int CI = Pattern.CASE_INSENSITIVE;

    private static final Pattern EXEC_STR = Pattern.compile(
            "\\bEXEC\\(\\s*\"((?:[^\"]|\"\")*)\"", CI);
    private static final Pattern EXEC_REF = Pattern.compile(
            "\\bEXEC\\(\\s*([A-Z][A-Z0-9_]*!)?(\\$?[A-Z]+\\$?\\d+)\\s*[,)]", CI);
    private static final Pattern EXECUTE_STR = Pattern.compile(
            "\\bEXECUTE\\(\\s*\"((?:[^\"]|\"\")*)\"", CI);
    private static final Pattern FOPEN = Pattern.compile(
            "\\bFOPEN\\(\\s*\"((?:[^\"]|\"\")*)\"\\s*(?:,\\s*(\\d+))?", CI);
    private static final Pattern FWRITE = Pattern.compile(
            "\\bFWRITE(?:LN)?\\(\\s*\\d+\\s*,\\s*\"((?:[^\"]|\"\")*)\"", CI);
    private static final Pattern CALL = Pattern.compile(
            "\\bCALL\\(\\s*\"((?:[^\"]|\"\")*)\"\\s*,\\s*\"((?:[^\"]|\"\")*)\"", CI);
    private static final Pattern ALERT = Pattern.compile(
            "\\bALERT\\(\\s*\"((?:[^\"]|\"\")*)\"", CI);
    private static final Pattern REGISTER = Pattern.compile(
            "\\bREGISTER\\(\\s*\"((?:[^\"]|\"\")*)\"", CI);
    private static final Pattern GOTO_REF = Pattern.compile(
            "\\bGOTO\\(\\s*([A-Z][A-Z0-9_]*!)?(\\$?[A-Z]+\\$?\\d+)\\s*\\)", CI);
    private static final Pattern URL = Pattern.compile(
            "(?<![\\w.])(?:https?|ftp)://[^\\s\"<>()]+", CI);

    // Volatile date/time functions used by XLM droppers for time-gated payloads
    // (sample evasion: `IF(NOW()>DATE(2024,1,1), EXEC(...), GOTO(A1))`). We
    // can't statically resolve the comparison from a regex, but flagging the
    // pattern itself is forensically useful — analysts know the macro behaves
    // differently before/after a date.
    private static final Pattern TIME_GATE = Pattern.compile(
            "\\b(NOW|TODAY)\\(\\s*\\)|\\bDATE\\(\\s*\\d", CI);

    // Bare IPv4 host with trailing slash — common XLM dropper fragment where
    // the scheme and host are split across cells (e.g. cell A1 holds "http://"
    // and cell B1 holds "1.2.3.4/foo"). Surface these even when the formula
    // concatenation can't be statically resolved.
    private static final Pattern IP_HOST = Pattern.compile(
            "(?<![\\d.])\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?:[:/][^\\s\"<>()]*)?");

    // Suspicious filename / drop path markers found in cell values: a path-like
    // prefix that ends in a Windows binary extension. Conservative — pads false
    // positives down by requiring a slash/backslash separator.
    private static final Pattern DROP_PATH = Pattern.compile(
            "[a-zA-Z]:\\\\[^\\s\"<>()]+\\.(?:exe|dll|scr|bat|cmd|ps1|vbs|js|hta|wsf|msi)",
            CI);

    // ── Public scan entry points ─────────────────────────────────────────────

    // Doubled-quote unescape: XLM string literals double the embedded quote ("" → ").
    private static String unq(String s) {
        return s.replace("\"\"", "\"");
    }

    /**
     * Scan a set of XML-formula macro cells and return human-readable IOC
     * strings in the same format {@link XlmMacroEmulator} produces.
     *
     * @param formulas    formula text by cell key — values mutated to nothing,
     *                    safe to pass {@link XlmXmlMacrosheetParser#getFormulas()}
     *                    directly.
     * @param cellValues  constant cell values from the same workbook (any sheet);
     *                    used to resolve simple {@code EXEC(A1)} cell-references.
     *                    Pass an empty map if none are available.
     */
    static List<String> scan(Map<String, String> formulas, Map<String, String> cellValues) {
        List<String> iocs = new ArrayList<>();
        if (formulas == null || formulas.isEmpty()) return iocs;
        // Build a "{cellRef}" → value index so cellref-only lookups work
        // without sheet:row prefix when the formula is in the same workbook
        // (the most common dropper pattern). Also keep the fully-qualified
        // form for sheet-aware resolution when both are available.
        Map<String, String> shortRef = new LinkedHashMap<>();
        if (cellValues != null) {
            for (Map.Entry<String, String> e : cellValues.entrySet()) {
                String k = e.getKey();
                int last = k.lastIndexOf(':');
                if (last >= 0 && last < k.length() - 1) {
                    shortRef.put(k.substring(last + 1).toUpperCase(java.util.Locale.ROOT),
                                 e.getValue());
                }
            }
        }

        for (Map.Entry<String, String> entry : formulas.entrySet()) {
            String formula = entry.getValue();
            if (formula == null || formula.isEmpty()) continue;

            // EXEC("cmd …")
            addAll(iocs, EXEC_STR.matcher(formula), m -> "EXEC: " + unq(m.group(1)));
            // EXECUTE("…") — used by Excel for DDE-style command exec
            addAll(iocs, EXECUTE_STR.matcher(formula), m -> "EXEC: " + unq(m.group(1)));
            // EXEC(A1) — resolve through cellValues if known
            for (Matcher m = EXEC_REF.matcher(formula); m.find(); ) {
                String ref = m.group(2).toUpperCase(java.util.Locale.ROOT);
                String resolved = shortRef.get(ref);
                iocs.add("EXEC: " + (resolved != null ? unq(resolved) : "<ref " + ref + ">"));
            }
            // FOPEN("path", mode)
            for (Matcher m = FOPEN.matcher(formula); m.find(); ) {
                String path = unq(m.group(1));
                String mode = m.group(2) != null ? m.group(2) : "?";
                iocs.add("FOPEN: " + path + " (mode " + mode + ")");
            }
            // FWRITE / FWRITELN (numeric-handle form)
            addAll(iocs, FWRITE.matcher(formula), m -> "FWRITE: " + unq(m.group(1)));
            // CALL("dll", "fn", …)
            addAll(iocs, CALL.matcher(formula), m -> "CALL: " + unq(m.group(1)) + "!" + unq(m.group(2)));
            // ALERT("msg") — sometimes the only sign of a live macro
            addAll(iocs, ALERT.matcher(formula), m -> "ALERT: " + unq(m.group(1)));
            // REGISTER("dll", ...) — DLL function binding
            addAll(iocs, REGISTER.matcher(formula), m -> "REGISTER: " + unq(m.group(1)));
            // GOTO(A1) — control flow, useful for tracing macro layout
            for (Matcher m = GOTO_REF.matcher(formula); m.find(); ) {
                iocs.add("GOTO: " + m.group(2).toUpperCase(java.util.Locale.ROOT));
            }
            // Bare URLs anywhere in the formula text — catches obfuscations the
            // function-name matchers missed (e.g. URL concatenated from cell refs
            // but still embedded as a literal somewhere).
            addAll(iocs, URL.matcher(formula), m -> "URL: " + m.group(0));
            // Time-gated logic: flag formulas referencing volatile clock funcs.
            // De-duped via `seen` outside the loop would noise-up the IOC list,
            // so emit one TIME_GATE per cell rather than per match.
            if (TIME_GATE.matcher(formula).find()) {
                iocs.add("TIME_GATE: " + formula);
            }
        }

        // Also surface URL / IPv4 host / drop-path fragments that appear in
        // *cell values* (constants) — XLM droppers commonly split a URL
        // across cells so no formula contains the literal string. We can't
        // statically reconstruct the joined URL without a formula evaluator
        // but the fragments themselves are forensically actionable.
        if (cellValues != null && !cellValues.isEmpty()) {
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            for (String val : cellValues.values()) {
                if (val == null || val.isEmpty()) continue;
                Matcher m;
                for (m = URL.matcher(val); m.find(); ) seen.add("URL: " + m.group(0));
                for (m = IP_HOST.matcher(val); m.find(); ) {
                    // Skip pure version-string-looking IPs (3.5.7 etc.) and
                    // bare 0-prefixed octets that aren't routable.
                    String hit = m.group(0);
                    if (hit.matches("\\d{1,3}(\\.\\d{1,3}){3}.*")) {
                        seen.add("IPV4: " + hit);
                    }
                }
                for (m = DROP_PATH.matcher(val); m.find(); ) seen.add("DROP_PATH: " + m.group(0));
            }
            iocs.addAll(seen);
        }
        return iocs;
    }

    @FunctionalInterface
    private interface Fmt { String apply(Matcher m); }

    private static void addAll(List<String> sink, Matcher m, Fmt fmt) {
        while (m.find()) {
            sink.add(fmt.apply(m));
        }
    }

    /** Resolve a cell-reference iterator into its value when known; returns null when unresolved. */
    static String resolveRef(String ref, Map<String, String> shortRefIndex) {
        if (ref == null || shortRefIndex == null) return null;
        String key = ref.toUpperCase(java.util.Locale.ROOT);
        return shortRefIndex.get(key);
    }

    /** Build a sheet-stripped value index of the form {cellRef → value} from a workbook-wide map. */
    static Map<String, String> shortRefIndex(Map<String, String> cellValues) {
        Map<String, String> out = new LinkedHashMap<>();
        if (cellValues == null) return out;
        for (Iterator<Map.Entry<String, String>> it = cellValues.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, String> e = it.next();
            String k = e.getKey();
            int last = k.lastIndexOf(':');
            if (last >= 0 && last < k.length() - 1) {
                out.put(k.substring(last + 1).toUpperCase(java.util.Locale.ROOT), e.getValue());
            }
        }
        return out;
    }
}
