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

    // ── Bounds ───────────────────────────────────────────────────────────────
    // Per-cell scan length cap: even with linear-time regex, a multi-MB formula
    // would still be measurable scan cost. 64 KB is far past any legitimate XLM
    // formula size while bounding pathological worst case.
    //
    // DEFAULT ONLY -- callers should pass the effective formula CAPTURE cap instead. This
    // constant used to be the sole ceiling, decoupled from the operator-settable
    // xlmFormulaMaxLen. At the 16 KB default capture cap it is unreachable, so it looked
    // harmless; but an operator who raised xlmFormulaMaxLen to 1 MB for forensics still had
    // only the first 64 KB of each formula SCANNED, silently. Raising the knob to see more
    // therefore made detection strictly worse for the payloads that motivated raising it,
    // with nothing in the output saying so.
    static final int MAX_FORMULA_SCAN_LEN = 64 * 1024;
    // TIME_GATE IOC carries the formula text. Cap so a megabyte-long crafted
    // formula doesn't bloat the link-metadata index / extracted text.
    private static final int MAX_TIME_GATE_LEN = 4096;

    // ── Patterns ─────────────────────────────────────────────────────────────
    // Function-name match is case-insensitive — XLM is itself case-insensitive
    // and obfuscators sometimes randomize case to evade naive YARA rules.
    // Fullwidth-letter obfuscation (EXEC vs ＥＸＥＣ U+FF25 U+FF38…) is handled
    // by NFKC-normalizing the formula text at scan entry — Java's UNICODE_CASE
    // flag doesn't help (it folds ＥＸＥＣ → ｅｘｅｃ, not to ASCII).
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
        return scan(formulas, cellValues, MAX_FORMULA_SCAN_LEN, null);
    }

    /**
     * @param maxScanLen       per-formula scan ceiling. Pass the EFFECTIVE formula capture cap
     *                         so scanning tracks capture: a formula we bothered to retain in
     *                         full must be inspected in full, or raising the capture knob
     *                         silently degrades detection.
     * @param onScanTruncated  invoked when a formula was too long to scan entirely. Truncating
     *                         the scan input means IOCs may exist in the unscanned tail, so
     *                         this must never be silent.
     */
    static List<String> scan(Map<String, String> formulas, Map<String, String> cellValues,
                             int maxScanLen, Runnable onScanTruncated) {
        int scanLen = maxScanLen > 0 ? maxScanLen : MAX_FORMULA_SCAN_LEN;
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
            if (formula.length() > scanLen) {
                // Truncate the scan input for this cell. The formula text was already
                // emitted in full to the XHTML stream by XlmXmlMacrosheetParser, but any
                // IOC living past this point will NOT be extracted -- and an IOC that was
                // never extracted is invisible to everything downstream that consumes the
                // IOC list rather than re-reading the formula. So report it.
                formula = formula.substring(0, scanLen);
                if (onScanTruncated != null) {
                    onScanTruncated.run();
                }
            }
            // NFKC-normalize so fullwidth-letter obfuscation (ＥＸＥＣ → EXEC),
            // ligatures, and other compatibility variants collapse to the ASCII
            // forms the patterns target. NFKC is the right normalization here:
            // NFC alone wouldn't fold fullwidth; NFKD/NFKC do. The compatibility
            // decomposition tables map fullwidth Latin → ASCII as a documented
            // round-trip-lossy fold.
            formula = java.text.Normalizer.normalize(formula, java.text.Normalizer.Form.NFKC);

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
            // so emit one TIME_GATE per cell rather than per match. Sanitize
            // the formula text first — it can contain control bytes, RTL
            // overrides, NUL, or be megabytes long; downstream metadata
            // consumers (JSON encoders, log aggregators) misbehave on those.
            if (TIME_GATE.matcher(formula).find()) {
                iocs.add("TIME_GATE: " + sanitizeForIoc(formula));
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
                // IP_HOST already requires 4 dotted octets in its main pattern,
                // so every match is shape-valid by construction; no second-pass
                // filter needed (the prior "skip 3-octet versions" check was
                // tautological — the regex never produced 3-octet hits).
                for (m = IP_HOST.matcher(val); m.find(); ) {
                    seen.add("IPV4: " + m.group(0));
                }
                for (m = DROP_PATH.matcher(val); m.find(); ) seen.add("DROP_PATH: " + m.group(0));
            }
            iocs.addAll(seen);
        }
        return iocs;
    }

    /**
     * Defang IOC strings that get embedded in metadata + XHTML emission.
     * Caps length, replaces ASCII control bytes (0x00–0x1F except tab/newline)
     * and the bidirectional-override controls (U+202A..U+202E, U+2066..U+2069)
     * with U+FFFD so downstream JSON encoders / log aggregators don't choke
     * and so RTL-override obfuscation can't visually hide content from analysts.
     */
    private static String sanitizeForIoc(String s) {
        if (s == null) return "";
        int n = Math.min(s.length(), MAX_TIME_GATE_LEN);
        // If the cap landed exactly between a valid surrogate pair, extend by
        // one char so the pair stays intact. Without this, the high surrogate
        // at n-1 has no low surrogate at n (out of range), gets defanged to
        // U+FFFD, and we lose a legitimate code point at the truncation edge.
        if (n > 0 && n < s.length()
                && Character.isHighSurrogate(s.charAt(n - 1))
                && Character.isLowSurrogate(s.charAt(n))) {
            n++;
        }
        StringBuilder out = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') {
                out.append(' ');
            } else if (c < 0x20 || c == 0x7f) {
                out.append('�');
            } else if (c >= 0x202A && c <= 0x202E) {
                out.append('�');  // LRE/RLE/PDF/LRO/RLO bidi overrides
            } else if (c >= 0x2066 && c <= 0x2069) {
                out.append('�');  // LRI/RLI/FSI/PDI bidi isolates
            } else if (Character.isHighSurrogate(c)) {
                // Strict downstream JSON encoders (orjson, some Jackson configs)
                // reject lone surrogates outright. Only emit the high half if a
                // valid low surrogate follows; otherwise defang both halves so a
                // truncated emoji or a forged orphan doesn't break the index.
                if (i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                    out.append(c);
                    out.append(s.charAt(++i));
                } else {
                    out.append('�');
                }
            } else if (Character.isLowSurrogate(c)) {
                out.append('�');  // unpaired low — by construction (high path above advances i)
            } else {
                out.append(c);
            }
        }
        if (s.length() > MAX_TIME_GATE_LEN) out.append("…");
        return out.toString();
    }

    @FunctionalInterface
    private interface Fmt { String apply(Matcher m); }

    private static void addAll(List<String> sink, Matcher m, Fmt fmt) {
        while (m.find()) {
            sink.add(fmt.apply(m));
        }
    }

}
