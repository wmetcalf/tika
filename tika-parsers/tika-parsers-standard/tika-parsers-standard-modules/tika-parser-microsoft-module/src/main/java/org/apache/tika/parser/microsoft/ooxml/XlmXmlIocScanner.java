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
    /** High-value slots each cell is guaranteed in the fair pass before volume wins. */
    /** Floor below which no indicator worth emitting can fit; see IocSink.isFull(). */
    private static final int MIN_USEFUL_IOC_CHARS = 16;
    /** Minimum high-value slots each cell is guaranteed; the quota scales up when there is room. */
    private static final int MIN_HIGH_VALUE_PER_CELL = 8;

    // ── Patterns ─────────────────────────────────────────────────────────────
    // Function-name match is case-insensitive — XLM is itself case-insensitive
    // and obfuscators sometimes randomize case to evade naive YARA rules.
    // Fullwidth-letter obfuscation (EXEC vs ＥＸＥＣ U+FF25 U+FF38…) is handled
    // by NFKC-normalizing the formula text at scan entry — Java's UNICODE_CASE
    // flag doesn't help (it folds ＥＸＥＣ → ｅｘｅｃ, not to ASCII).
    private static final int CI = Pattern.CASE_INSENSITIVE;

    // Quoted XLM string argument, doubled-quote escaped. Written as `[^"]*+(?:""[^"]*+)*` and NOT as
    // `(?:[^"]|"")*`: the latter is an alternation inside a star, which java.util.regex implements by
    // RECURSING once per iteration, so a long argument blows the stack. Measured -- a single
    // =EXEC("<4000 chars>") raised StackOverflowError inside scan(), which is an Error and so escapes
    // the parser's per-part recovery entirely. The possessive quantifiers also stop the engine
    // exploring the exponential number of ways to split a run of non-quote characters. Same
    // language, no recursion, no backtracking.

    private static final Pattern EXEC_STR = Pattern.compile(
            "\\bEXEC\\(\\s*\"([^\"]*+(?:\"\"[^\"]*+)*)\"", CI);
    private static final Pattern EXEC_REF = Pattern.compile(
            "\\bEXEC\\(\\s*([A-Z][A-Z0-9_]*!)?(\\$?[A-Z]+\\$?\\d+)\\s*[,)]", CI);
    private static final Pattern EXECUTE_STR = Pattern.compile(
            "\\bEXECUTE\\(\\s*\"([^\"]*+(?:\"\"[^\"]*+)*)\"", CI);
    private static final Pattern FOPEN = Pattern.compile(
            "\\bFOPEN\\(\\s*\"([^\"]*+(?:\"\"[^\"]*+)*)\"\\s*(?:,\\s*(\\d+))?", CI);
    private static final Pattern FWRITE = Pattern.compile(
            "\\bFWRITE(?:LN)?\\(\\s*\\d+\\s*,\\s*\"([^\"]*+(?:\"\"[^\"]*+)*)\"", CI);
    private static final Pattern CALL = Pattern.compile(
            "\\bCALL\\(\\s*\"([^\"]*+(?:\"\"[^\"]*+)*)\"\\s*,\\s*\"([^\"]*+(?:\"\"[^\"]*+)*)\"", CI);
    private static final Pattern ALERT = Pattern.compile(
            "\\bALERT\\(\\s*\"([^\"]*+(?:\"\"[^\"]*+)*)\"", CI);
    private static final Pattern REGISTER = Pattern.compile(
            "\\bREGISTER\\(\\s*\"([^\"]*+(?:\"\"[^\"]*+)*)\"", CI);
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
        return scan(formulas, cellValues, MAX_FORMULA_SCAN_LEN, null, 0, 0, null);
    }

    static List<String> scan(Map<String, String> formulas, Map<String, String> cellValues,
                             int maxScanLen, Runnable onScanTruncated) {
        return scan(formulas, cellValues, maxScanLen, onScanTruncated, 0, 0, null);
    }

    /**
     * Bounded IOC accumulator.
     *
     * <p>The XML path had NO output bound of any kind: the {@code xlmMaxIocEntries} /
     * {@code xlmMaxIocChars} knobs were wired only into the XLSB emulator. Every indicator was
     * materialised into an unbounded list before the caller could emit any of it, and
     * {@code EXEC(cellref)} resolution amplifies hard -- an 8-char {@code EXEC(A1)} match yields
     * an IOC as long as the referenced cell value (up to {@code WORKBOOK_VALUE_MAX_LEN}), so a
     * ~10 MB crafted workbook whose retention stays entirely within every documented cap could
     * still build gigabytes of strings and OOM a triage worker. Bounding the OUTPUT is the fix;
     * bounding retention never could be, because the amplification happens after retention.
     */
    private static final class IocSink {
        private final List<String> out = new ArrayList<>();
        /**
         * Every indicator already emitted, for O(1) duplicate rejection.
         *
         * <p>Without this a leftovers pass is impossible: the previous attempt at one re-emitted
         * everything the fair pass had already emitted -- measured on the corpus as EXEC 239 -> 478
         * and FOPEN 22 -> 44, exactly 2x, all duplicates -- and was reverted. The failure was
         * DUPLICATION, not the idea, so removing duplication is what makes the idea work.
         *
         * <p>Deduplicating is also right on its own terms: two cells holding the same EXEC are one
         * piece of evidence, which is the same rule the cell-value index above already applies. It
         * frees budget for DISTINCT indicators, which is the only kind worth spending it on.
         */
        private final java.util.Set<String> seen = new java.util.HashSet<>();
        final int maxEntries;
        private final int maxChars;
        private long chars;
        private boolean limited;

        IocSink(int maxEntries, int maxChars) {
            this.maxEntries = maxEntries > 0
                    ? maxEntries : XlmMacroEmulator.Limits.DEFAULT.maxIocEntries;
            this.maxChars = maxChars > 0
                    ? maxChars : XlmMacroEmulator.Limits.DEFAULT.maxIocChars;
        }

        /**
         * @return false when the entry did not fit. Callers continue rather than break: a long
         *         entry must not suppress the shorter, often higher-value ones behind it.
         */
        boolean add(String ioc) {
            if (ioc == null) {
                return false;
            }
            if (quotaSpent()) {
                // NOT `limited`: this cell's share is spent for THIS pass, and a later pass picks
                // the remainder up. Marking a shortfall here would report loss that has not
                // happened yet.
                return false;
            }
            if (!seen.add(ioc)) {
                // A duplicate is NOT a refusal: nothing was lost, so `limited` must stay clear.
                return false;
            }
            if (out.size() >= maxEntries || chars + ioc.length() > maxChars) {
                // Un-record it. `seen` must hold only what was ACCEPTED, for two reasons: a
                // refused entry must not block a later attempt that might fit, and the set has to
                // stay bounded by maxEntries. Retaining rejects instead was measured growing
                // `seen` to 200,000 strings with maxIocChars=1 -- a rejection on the CHAR budget
                // leaves out.size() below its cap, so isFull() stays false and scanning continues,
                // and the set ends up mirroring the document's whole value corpus.
                seen.remove(ioc);
                limited = true;
                return false;
            }
            emittedThisCell++;
            chars += ioc.length();
            out.add(ioc);
            return true;
        }

        /**
         * Record that a real match was found and could not be emitted.
         *
         * <p>The distinction this exists to keep: {@code limited} means something was REFUSED, and
         * a saturation test alone cannot tell refusal from "the input simply ended". Callers that
         * stop matching because the sink is full DO know a match existed -- {@code m.find()} already
         * returned true -- so they say so here rather than leaving the loss silent.
         */
        /**
         * Offer an indicator; @return false when scanning should stop.
         *
         * <p>The distinction that makes a leftovers pass possible: a match ALREADY EMITTED is not a
         * loss. The leftovers pass necessarily re-finds everything the fair pass emitted, so testing
         * "sink full + a match exists" would report those re-finds as dropped evidence and put
         * TRUNCATED_METADATA on a complete extraction -- which is exactly what happened the first
         * time this was wired up.
         */
        boolean offer(String ioc) {
            if (ioc != null && seen.contains(ioc)) {
                return true;   // already emitted; nothing lost, keep going
            }
            if (isFull()) {
                markLimited();  // a NEW match that cannot fit: that is the loss
                return false;
            }
            add(ioc);
            return true;
        }

        void markLimited() {
            limited = true;
        }

        boolean isLimited() {
            return limited;
        }

        /**
         * True once nothing further can be accepted. Lets callers STOP SCANNING rather than keep
         * building strings the sink will refuse -- the difference between bounding emission and
         * bounding allocation, and the whole point of this class.
         */
        /** Per-cell emission quota for the fair pass; 0 = unlimited. */
        private int cellQuota;
        private int emittedThisCell;

        void startCell(int quota) {
            cellQuota = quota;
            emittedThisCell = 0;
        }

        /** True when this cell hit its quota, so a later pass must revisit it. */
        boolean quotaWasSpent() {
            return quotaSpent();
        }

        private boolean quotaSpent() {
            return cellQuota > 0 && emittedThisCell >= cellQuota;
        }

        boolean isFull() {
            // "Nothing more can fit", NOT "something was refused".
            //
            // Treating any refusal as full meant ONE oversized indicator stopped the scan and
            // suppressed every shorter, often higher-value one behind it -- flagged independently by
            // two reviewer families. But the opposite error is the OOM: add() refuses on the char
            // bound WITHOUT advancing `chars`, so a plain `chars >= maxChars` test never becomes
            // true and the walk continues forever. The resolution is a floor: once fewer than
            // MIN_USEFUL_IOC_CHARS remain, no indicator worth emitting can fit, so stop. Above that
            // floor, keep going -- a refused giant does not speak for the entries after it.
            return out.size() >= maxEntries || (maxChars - chars) < MIN_USEFUL_IOC_CHARS;
        }
    }

    /**
     * @param maxScanLen       per-formula scan ceiling. Pass the EFFECTIVE formula capture cap
     *                         so scanning tracks capture: a formula we bothered to retain in
     *                         full must be inspected in full, or raising the capture knob
     *                         silently degrades detection.
     * @param onScanTruncated  invoked when a formula was too long to scan entirely. Truncating
     *                         the scan input means IOCs may exist in the unscanned tail, so
     *                         this must never be silent.
     * @param maxIocEntries    output entry bound; 0 selects the same default the XLSB path uses.
     * @param maxIocChars      output char bound; 0 selects the XLSB default.
     * @param onIocLimit       invoked when any indicator was dropped for want of budget.
     */
    static List<String> scan(Map<String, String> formulas, Map<String, String> cellValues,
                             int maxScanLen, Runnable onScanTruncated,
                             int maxIocEntries, int maxIocChars, Runnable onIocLimit) {
        int scanLen = maxScanLen > 0 ? maxScanLen : MAX_FORMULA_SCAN_LEN;
        IocSink sink = new IocSink(maxIocEntries, maxIocChars);
        List<String> iocs = sink.out;
        if (formulas == null || formulas.isEmpty()) return iocs;
        // Build a "{cellRef}" → value index so cellref-only lookups work
        // without sheet:row prefix when the formula is in the same workbook
        // (the most common dropper pattern). Also keep the fully-qualified
        // form for sheet-aware resolution when both are available.
        // ALL candidate values per cell ref, not one.
        //
        // Duplicated cell refs are retained under distinct `A1#2`, `A1#3` keys so a decoy cannot
        // delete a payload. But this index keyed on the RAW trailing segment, so `A1#2` indexed
        // under "A1#2" and EXEC(A1) resolved only the unsuffixed entry: measured, A1="0" followed
        // by A1="powershell -enc PAYLOAD" with B1==EXEC(A1) emitted ONLY `EXEC: 0`. The payload was
        // retained somewhere the resolver never looked, so preserving it did nothing for detection.
        // Strip the suffix and keep EVERY candidate -- we cannot know which one Excel would honour,
        // and an analyst needs to see both.
        // LinkedHashSet, not ArrayList: insertion-ordered like before, but membership is O(1).
        // A linear contains() per insertion is quadratic in the retained value count, which the
        // configuration lets an author drive into the hundreds of thousands -- a CPU denial of
        // service reachable from a small crafted sheet full of duplicate cell references. Reported
        // by an external reviewer on PR #18.
        Map<String, java.util.LinkedHashSet<String>> shortRef = new LinkedHashMap<>();
        if (cellValues != null) {
            for (Map.Entry<String, String> e : cellValues.entrySet()) {
                String k = e.getKey();
                int last = k.lastIndexOf(':');
                if (last >= 0 && last < k.length() - 1) {
                    String ref = k.substring(last + 1).toUpperCase(java.util.Locale.ROOT);
                    int hash = ref.indexOf('#');
                    if (hash > 0) {
                        ref = ref.substring(0, hash);
                    }
                    // Distinct values only. Two duplicate cells holding the SAME text are one
                    // piece of evidence; emitting an indicator per occurrence just doubled
                    // identical lines (measured +2 EXEC lines corpus-wide with no new distinct
                    // indicator). Different values are all kept -- that is the point.
                    shortRef.computeIfAbsent(ref, x -> new java.util.LinkedHashSet<>())
                            .add(e.getValue());
                }
            }
        }

        // TWO PASSES over the formulas, high-value categories first.
        //
        // Priority ordering previously existed only WITHIN one formula (EXEC before URL), so a
        // bulk emitter in an earlier-iterated cell could exhaust the whole entry budget before the
        // cell holding EXEC was reached -- and `allFormulas` is a HashMap keyed on
        // attacker-chosen sheet/row/ref, so the attacker picks which cell is scanned last.
        // Measured: 47 KB of formulas across 4 cells, three packed with short URLs, one holding
        // =EXEC("powershell -enc ..."), gave EXEC_recovered=0. That was the THIRD instance of the
        // starvation defect the XLSB side already fixed twice by priority-ordering; the fix is the
        // same one -- name what the macro DOES before the bulk it references.
        // THREE passes. Pass 0 gives every cell a small QUOTA of high-value slots, so one cell
        // packed with volume cannot spend the budget before other cells are looked at -- measured,
        // a cell with 6,000 =EXEC("jN") calls emitted j0..j4095 and a second cell's real payload
        // was never recovered even after the emitters learned to stop on a full sink, because the
        // passes separate CATEGORIES, not cells. Pass 1 then takes the high-value remainder, and
        // pass 2 the bulk. Breadth before depth, depth before volume.
        // TWO passes: high-value across every cell, then bulk. The quota is ADAPTIVE -- each cell
        // is guaranteed an equal share of the entry budget, never fewer than MIN_HIGH_VALUE_PER_CELL
        // -- so with a handful of cells and a 4,096-entry budget the quota is thousands and nothing
        // is deferred, while with 100,000 cells it is 8 and no single cell can starve the rest.
        //
        // A previous version used a FIXED quota plus a third "leftovers" pass, and that pass
        // re-emitted everything the first had already emitted: measured on the corpus as EXEC
        // 239 -> 478 and FOPEN 22 -> 44, exactly 2x, all duplicates. Restricting it to quota-spent
        // cells only narrowed the duplication rather than removing it -- flagged by three
        // independent reviewer families. An adaptive quota removes the need for the pass at all,
        // which removes the defect by construction instead of by another guard.
        // One slot per cell is the floor, NOT eight: with more cells than budget a floor of 8 let
        // the first budget/8 cells consume everything, so only a twenty-fifth of the cells were
        // represented. A floor of 1 spreads across as many distinct cells as the budget allows,
        // which is the most fairness arithmetic permits. When there IS room the share scales up, so
        // an ordinary document defers nothing.
        // No formulas.isEmpty() branch: an empty map returned early above, so that arm was dead
        // and made MIN_HIGH_VALUE_PER_CELL look like it governed something here. Reported by an
        // external reviewer on PR #18.
        int quotaShare = Math.max(1, sink.maxEntries / formulas.size());
        // THREE passes: high-value under the fair quota, high-value with the quota lifted, then
        // bulk. The middle one is the leftovers pass, and it is back because the adaptive quota
        // guarantees each cell a share without ever RECLAIMING the shares nobody used. Reported by
        // an external reviewer on PR #18: with 100 cells and a 4,096-entry budget the quota is 40,
        // so a cell holding 100 EXEC calls emits 40 and drops 60 -- while the other 99 cells
        // contribute nothing and roughly 4,000 slots go unspent. Worse, a quota refusal deliberately
        // does not set `limited` (it is a deferral, not a loss), so those 60 vanished in silence.
        //
        // The pass is only safe now because add() deduplicates; see IocSink.seen for what happened
        // the last time it existed without that.
        for (int pass = 0; pass < 3; pass++) {
            boolean highValuePass = pass <= 1;
            // Report side-effects ONCE, on the first pass only: the later passes re-walk the
            // same formulas, so reporting in each would double-count and spend a warning slot
            // per pass.
            boolean reportingPass = pass == 0;
            int quota = pass == 0 ? quotaShare : 0;
        for (Map.Entry<String, String> entry : formulas.entrySet()) {
            sink.startCell(quota);
            // Deliberately NO early break on a full sink, and no `limited` set from saturation.
            //
            // Both were wrong, in opposite directions, and the file carried both at once: the line
            // setting `limited` from saturation over-reported (two EXEC calls against
            // maxIocEntries=2 emit both, refuse nothing, and still reported a shortfall, putting
            // TRUNCATED_METADATA on a complete extraction), while breaking out under-reported (the
            // third EXEC of three was never attempted, so add() never refused and the loss was
            // silent). An external reviewer on PR #18 caught the contradiction -- the comment
            // forbidding the line sat directly beneath the line.
            //
            // The resolution is to keep walking and let the matchers decide: a loss is exactly "a
            // match existed that could not be emitted", which only the matcher knows. The cost is
            // scanning formulas that will emit nothing, linear in a formula count the capture caps
            // already bound. XlmIocLimitSemanticsTest pins both directions.
            String formula = entry.getValue();
            if (formula == null || formula.isEmpty()) continue;
            if (formula.length() > scanLen) {
                // Truncate the scan input for this cell. The formula text was already
                // emitted in full to the XHTML stream by XlmXmlMacrosheetParser, but any
                // IOC living past this point will NOT be extracted -- and an IOC that was
                // never extracted is invisible to everything downstream that consumes the
                // IOC list rather than re-reading the formula. So report it.
                formula = formula.substring(0, scanLen);
                // Report ONCE: the second pass re-walks the same formulas, so reporting in both
                // would double-count and, worse, spend a warning slot per pass.
                if (reportingPass && onScanTruncated != null) {
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

            // HIGH-VALUE: what the macro DOES. Emitted for every cell before any bulk.
            if (highValuePass) {
            // EXEC("cmd …")
            addAll(sink, EXEC_STR.matcher(formula), m -> "EXEC: " + unq(m.group(1)));
            // EXECUTE("…") — used by Excel for DDE-style command exec
            addAll(sink, EXECUTE_STR.matcher(formula), m -> "EXEC: " + unq(m.group(1)));
            // EXEC(A1) — resolve through cellValues if known
            for (Matcher m = EXEC_REF.matcher(formula); m.find(); ) {
                String ref = m.group(2).toUpperCase(java.util.Locale.ROOT);
                java.util.Collection<String> candidates = shortRef.get(ref);
                if (candidates == null || candidates.isEmpty()) {
                    if (!sink.offer("EXEC: <ref " + ref + ">")) {
                        break;
                    }
                } else {
                    boolean room = true;
                    for (String resolved : candidates) {
                        room = sink.offer("EXEC: " + unq(resolved));
                        if (!room) {
                            break;
                        }
                    }
                    if (!room) {
                        break;
                    }
                }
            }
            // CALL("dll", "fn", …)
            addAll(sink, CALL.matcher(formula), m -> "CALL: " + unq(m.group(1)) + "!" + unq(m.group(2)));
            // REGISTER("dll", ...) — DLL function binding
            addAll(sink, REGISTER.matcher(formula), m -> "REGISTER: " + unq(m.group(1)));
            // FOPEN("path", mode)
            for (Matcher m = FOPEN.matcher(formula); m.find(); ) {
                String path = unq(m.group(1));
                String mode = m.group(2) != null ? m.group(2) : "?";
                if (!sink.offer("FOPEN: " + path + " (mode " + mode + ")")) {
                    break;
                }
            }
            }
            if (highValuePass) {
                continue;
            }
            // BULK: volume, and only meaningful once the above are secured.
            // FWRITE / FWRITELN (numeric-handle form)
            addAll(sink, FWRITE.matcher(formula), m -> "FWRITE: " + unq(m.group(1)));
            // ALERT("msg") — sometimes the only sign of a live macro
            addAll(sink, ALERT.matcher(formula), m -> "ALERT: " + unq(m.group(1)));
            // GOTO(A1) — control flow, useful for tracing macro layout
            for (Matcher m = GOTO_REF.matcher(formula); m.find() && !sink.isFull(); ) {
                sink.add("GOTO: " + m.group(2).toUpperCase(java.util.Locale.ROOT));
            }
            // Bare URLs anywhere in the formula text — catches obfuscations the
            // function-name matchers missed (e.g. URL concatenated from cell refs
            // but still embedded as a literal somewhere).
            addAll(sink, URL.matcher(formula), m -> "URL: " + m.group(0));
            // Time-gated logic: flag formulas referencing volatile clock funcs.
            // De-duped via `seen` outside the loop would noise-up the IOC list,
            // so emit one TIME_GATE per cell rather than per match. Sanitize
            // the formula text first — it can contain control bytes, RTL
            // overrides, NUL, or be megabytes long; downstream metadata
            // consumers (JSON encoders, log aggregators) misbehave on those.
            if (TIME_GATE.matcher(formula).find()) {
                sink.add("TIME_GATE: " + sanitizeForIoc(formula));
            }
        }
        }

        // Also surface URL / IPv4 host / drop-path fragments that appear in
        // *cell values* (constants) — XLM droppers commonly split a URL
        // across cells so no formula contains the literal string. We can't
        // statically reconstruct the joined URL without a formula evaluator
        // but the fragments themselves are forensically actionable.
        if (cellValues != null && !cellValues.isEmpty()) {
            // Dedupe set bounded by the sink: once the sink is full we stop scanning entirely.
            // Previously every URL/IPV4/DROP_PATH match across ALL cell values accumulated into an
            // unbounded LinkedHashSet and was only offered to the sink AFTER the loop, so the
            // bound applied to EMISSION but not to PEAK HEAP -- measured OOM at -Xmx256m from
            // 31 MiB of legal cell values even with maxIocEntries=1, which defeated the entire
            // purpose of this budget.
            // Feed the sink DURING iteration and stop as soon as it is full.
            //
            // The previous version accumulated every URL/IPV4/DROP_PATH match across ALL cell
            // values into an unbounded LinkedHashSet and only offered it to the sink AFTER the
            // loop. That bounded EMISSION but not PEAK HEAP: measured OOM at -Xmx256m from 31 MiB
            // of entirely legal cell values (200k entries, 1 KB each -- every documented cap
            // respected) even with maxIocEntries=1, which defeated the whole purpose of this
            // budget. The dedupe set is now bounded by the sink, because we stop scanning once
            // the sink cannot accept anything further.
            // No local dedupe set any more: IocSink.add() deduplicates at the single choke point
            // every emission path goes through, and bounds itself by what was ACCEPTED. A second
            // set here would be a parallel mechanism to keep in step with it.
            for (String val : cellValues.values()) {
                if (sink.isFull()) {
                    // Unlike the formula loop this one DOES stop, and does report: the value corpus
                    // runs to tens of thousands of entries, and testUnfittableBudgetStopsScanningTheValueCorpus
                    // pins that we must not walk it all building rejects. Abandoning input we have
                    // not examined is a possible loss we cannot rule out, so it is reported.
                    sink.markLimited();
                    break;
                }
                if (val == null || val.isEmpty()) continue;
                Matcher m;
                for (m = URL.matcher(val); m.find(); ) {
                    if (!sink.offer("URL: " + m.group(0))) {
                        break;
                    }
                }
                // IP_HOST already requires 4 dotted octets in its main pattern, so every match is
                // shape-valid by construction; no second-pass filter needed.
                for (m = IP_HOST.matcher(val); m.find(); ) {
                    if (!sink.offer("IPV4: " + m.group(0))) {
                        break;
                    }
                }
                for (m = DROP_PATH.matcher(val); m.find(); ) {
                    if (!sink.offer("DROP_PATH: " + m.group(0))) {
                        break;
                    }
                }
            }
        }
        if (sink.isLimited() && onIocLimit != null) {
            onIocLimit.run();
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

    private static void addAll(IocSink sink, Matcher m, Fmt fmt) {
        // Stop on a full sink. Without this, ONE cell packed with high-value matches consumed the
        // whole entry budget inside pass 0 and later cells' EXEC never got a slot: measured, a cell
        // with 6,000 =EXEC("jN") calls emitted j0..j4095 and a second cell's
        // =EXEC("powershell -enc REALPAYLOAD") was never recovered. The two passes separate
        // CATEGORIES, not cells, so the per-cell check at the top of the loop was not enough --
        // fourth instance of this starvation class in this file.
        while (m.find()) {
            if (!sink.offer(fmt.apply(m))) {
                break;
            }
        }
    }

}
