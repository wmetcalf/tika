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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.sax.XHTMLContentHandler;

/**
 * SAX parser for XML-based XLM macro sheets — the OOXML equivalent of
 * {@link Biff12XlmMacrosheetParser} for {@code MACRO_SHEET_XML} /
 * {@code INTL_MACRO_SHEET_XML} package parts (i.e. {@code .xlsm} / {@code .xltm}
 * workbooks).
 *
 * <p>The XML schema uses {@code <sheetData>} containing {@code <row>} elements
 * with {@code <c>} cells; each cell may carry a {@code <f>} formula child
 * (formula text) and/or a {@code <v>} value child (constant value). This
 * parser extracts:</p>
 * <ul>
 *   <li>Each cell's formula text (the macro code itself), keyed by sheet:row:col.</li>
 *   <li>Each cell's constant value (numeric or string), keyed similarly, for
 *       later use by the IOC scanner when resolving cell references.</li>
 * </ul>
 *
 * <p>Formulas and values are also emitted to the XHTML stream so they surface
 * in Tika's per-entry {@code text} field — this guarantees visibility even when
 * the downstream IOC scanner doesn't match a particular obfuscation.</p>
 *
 * <p>Cell value capture handles three Excel value-storage shapes:</p>
 * <ul>
 *   <li>Numeric / formula / error: {@code <c><v>123</v></c>} → value="123"</li>
 *   <li>Inline string: {@code <c t="inlineStr"><is><t>foo</t></is></c>} →
 *       value="foo". Rich-text (multi-run) inline strings
 *       {@code <c t="inlineStr"><is><r><t>foo</t></r><r><t>bar</t></r></is></c>}
 *       concatenate to "foobar" — droppers use rich-text formatting to split
 *       payload fragments across runs that a naive last-run-wins parser would
 *       miss.</li>
 *   <li>Shared-string-indexed: {@code <c t="s"><v>42</v></c>} → resolved via
 *       {@link XSSFSharedStringsShim#getItemAt(int)}. Without resolution we'd
 *       capture the literal index "42" — useless for IOC scanning, and a
 *       common XLM-payload stash spot.</li>
 * </ul>
 */
final class XlmXmlMacrosheetParser {

    private final InputStream stream;
    private final XHTMLContentHandler xhtml;
    private final String sheetName;
    private final int maxFormulaEntries;
    private final int maxValueEntries;
    /** Workbook shared-strings table; null when the macrosheet's parent workbook
     *  has no sharedStrings.xml part. Cells with {@code t="s"} fall back to the
     *  literal index string when null. */
    private final XSSFSharedStringsShim sharedStrings;

    /** Formula text per cell, keyed "{sheet}:{row}:{col}". */
    private final Map<String, String> formulas = new LinkedHashMap<>();
    /** Constant value per cell, keyed identically. */
    private final Map<String, String> values = new LinkedHashMap<>();
    private boolean truncated;
    /** Effective per-formula / per-value / aggregate caps (see OfficeParserConfig). */
    private final int formulaMaxLen;
    private final int valueMaxLen;
    private final int formulaTotalMaxChars;

    XlmXmlMacrosheetParser(InputStream stream, XHTMLContentHandler xhtml,
                           String sheetName, XSSFSharedStringsShim sharedStrings) {
        this(stream, xhtml, sheetName, sharedStrings,
                XSSFExcelExtractorDecorator.WORKBOOK_VALUES_MAX_ENTRIES,
                XSSFExcelExtractorDecorator.WORKBOOK_VALUES_MAX_ENTRIES);
    }

    XlmXmlMacrosheetParser(InputStream stream, XHTMLContentHandler xhtml,
                           String sheetName, XSSFSharedStringsShim sharedStrings,
                           int maxFormulaEntries, int maxValueEntries) {
        this(stream, xhtml, sheetName, sharedStrings, maxFormulaEntries, maxValueEntries,
                XSSFExcelExtractorDecorator.XLM_FORMULA_MAX_LEN,
                XSSFExcelExtractorDecorator.WORKBOOK_VALUE_MAX_LEN,
                XSSFExcelExtractorDecorator.MAX_XLM_FORMULA_TOTAL_CHARS);
    }

    XlmXmlMacrosheetParser(InputStream stream, XHTMLContentHandler xhtml,
                           String sheetName, XSSFSharedStringsShim sharedStrings,
                           int maxFormulaEntries, int maxValueEntries,
                           int formulaMaxLen, int valueMaxLen, int formulaTotalMaxChars) {
        this(stream, xhtml, sheetName, sharedStrings, maxFormulaEntries, maxValueEntries,
                formulaMaxLen, valueMaxLen, formulaTotalMaxChars, null);
    }

    XlmXmlMacrosheetParser(InputStream stream, XHTMLContentHandler xhtml,
                           String sheetName, XSSFSharedStringsShim sharedStrings,
                           int maxFormulaEntries, int maxValueEntries,
                           int formulaMaxLen, int valueMaxLen, int formulaTotalMaxChars,
                           XSSFExcelExtractorDecorator.ValueCharBudget valueCharBudget) {
        this.valueCharBudget = valueCharBudget;
        this.formulaMaxLen = formulaMaxLen > 0
                ? formulaMaxLen : XSSFExcelExtractorDecorator.XLM_FORMULA_MAX_LEN;
        this.valueMaxLen = valueMaxLen > 0
                ? valueMaxLen : XSSFExcelExtractorDecorator.WORKBOOK_VALUE_MAX_LEN;
        this.formulaTotalMaxChars = formulaTotalMaxChars > 0
                ? formulaTotalMaxChars
                : XSSFExcelExtractorDecorator.MAX_XLM_FORMULA_TOTAL_CHARS;
        this.stream = stream;
        this.xhtml = xhtml;
        this.sheetName = sheetName;
        this.sharedStrings = sharedStrings;
        this.maxFormulaEntries = Math.max(0, maxFormulaEntries);
        this.maxValueEntries = Math.max(0, maxValueEntries);
    }

    /** Mirrors the inner Handler's accumulator so the caller can carry it across parts. */
    private int handlerRetainedFormulaChars;
    /** Document-scoped; shared with the worksheet capture path. May be null in unit tests. */
    private final XSSFExcelExtractorDecorator.ValueCharBudget valueCharBudget;

    /**
     * Parse the macrosheet XML. Failures are propagated as SAXException so the
     * caller can record them on the parent metadata as warnings — same contract
     * as {@link Biff12XlmMacrosheetParser#parse()}.
     */
    void parse() throws SAXException, IOException {
        // Direct JAXP SAX parse — avoids XMLReaderUtils' requirement for a
        // non-null ParseContext, which we don't need for a self-contained
        // SAX walk that doesn't recurse into Tika sub-parsers. Disabling
        // external entity resolution keeps this safe against XXE on the
        // (untrusted) macrosheet XML.
        XMLReader reader;
        try {
            SAXParserFactory spf = SAXParserFactory.newInstance();
            spf.setNamespaceAware(true);
            spf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            SAXParser sp = spf.newSAXParser();
            reader = sp.getXMLReader();
        } catch (javax.xml.parsers.ParserConfigurationException | SAXException e) {
            // Factory SETUP failure, reported as IOException rather than SAXException on
            // purpose. It used to surface as a plain SAXException, indistinguishable from one
            // thrown by our own ContentHandler -- and the caller must treat those oppositely: a
            // handler abort has to propagate (a write limit or a refusing consumer), while a
            // JAXP that cannot be configured is a per-part failure the caller should recover
            // from and carry on. Conflating them meant that on a JAXP not recognising
            // disallow-doctype-decl, EVERY macro-bearing document in the deployment silently
            // lost its whole cross-sheet IOC scan. Nothing has been read at this point, so
            // there is no handler state to preserve.
            throw new IOException("XLM macrosheet SAX parser could not be configured", e);
        }
        // OUTSIDE the try: a SAXException from here is the ContentHandler aborting, and it must
        // reach the caller unchanged rather than being folded into the setup case above.
        reader.setContentHandler(new Handler());
        reader.parse(new InputSource(stream));
    }

    /** Formula text per cell, keyed "{sheet}:{row}:{col}". Iteration order matches sheet order. */
    /**
     * Formula chars retained by THIS macrosheet. The caller subtracts this from the
     * document-wide budget before constructing the next part's parser -- the accumulator
     * is per-Handler, so without that the cap would reset on every one of the up to
     * {@link XSSFExcelExtractorDecorator#MAX_XLM_MACRO_PARTS} parts.
     */
    int getRetainedFormulaChars() {
        return handlerRetainedFormulaChars;
    }

    Map<String, String> getFormulas() {
        return formulas;
    }

    /** Constant values per cell, keyed "{sheet}:{row}:{col}". */
    Map<String, String> getValues() {
        return values;
    }

    /** Ordered list of formula strings — convenience for callers that don't need cell coords. */
    List<String> getFormulaList() {
        return new ArrayList<>(formulas.values());
    }

    boolean isTruncated() {
        return truncated;
    }

    /**
     * True when the macrosheet XML nested or duplicated an {@code <f>}/{@code <v>}/{@code <t>}
     * element in a way SpreadsheetML never produces. Distinct from {@link #isTruncated()}:
     * nothing was amputated by a budget, the DOCUMENT was malformed — which in this corpus
     * means hand-crafted, i.e. a deliberate attempt to steer the capture. The payload is
     * preserved either way; this only says the input was anomalous.
     */
    boolean hasStructuralAnomaly() {
        return structuralAnomaly;
    }

    private boolean structuralAnomaly;

    private final class Handler extends DefaultHandler {
        private static final String NS_SHEETML = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

        // Current cell reference (e.g. "A1") and OOXML cell type ("s" for shared
        // string, "inlineStr", "str", "b", or null for numeric). Preserved across
        // the <c>...</c> span.
        private String currentCellRef;
        private String currentCellType;
        private int currentRow;
        // Buffer for the most recent <f> or <v> element's text.
        private final StringBuilder buf = new StringBuilder();
        private boolean inFormula;
        private boolean inValue;
        // Inline-string state. Rich-text inline strings nest <r><t>...</t></r>
        // multiple times inside one <is>; legitimate single-text inline strings
        // skip the <r> wrapper. Either way we want the full concatenated text,
        // not last-run-wins. inlineAcc accumulates across every <t> seen within
        // the current <is>; flushed to currentValueText on </is>.
        private boolean inInlineString;
        private boolean inText;
        // True while inside a <rPh> phonetic-run element. Phonetic runs are
        // furigana hints (Japanese / CJK pronunciation aids); they sit inside
        // <is> alongside the real <t> runs but are NOT part of the cell value.
        // Mixing them into inlineAcc would noise up the IOC scanner with
        // pronunciation glyphs that look like split-payload fragments.
        private boolean inPhoneticRun;
        private final StringBuilder inlineAcc = new StringBuilder();

        private String currentFormulaText;
        private String currentValueText;

        @Override
        public void startElement(String uri, String local, String qName, Attributes atts)
                throws SAXException {
            // ANY child element inside an open <f>/<v>/<t> is malformed: all three are
            // TEXT-ONLY in SpreadsheetML, so a nested element never occurs legitimately.
            // Suppress the whole subtree, whatever it is called and whatever namespace it is
            // in, and count EVERY element so the depth is symmetric with endElement below.
            //
            // The previous version keyed suppression on the element NAME (only f/v/t could
            // enter it) and that was doubly broken, both measured:
            //   <f>=EXEC("...")<t>DECOY</t></f>   -- a <t> outside <is> never entered
            //     suppression at all, so DECOY was appended to the formula and no anomaly
            //     was flagged.
            //   <f>=EXEC("...")<v><t/>DECOY2</v></f>  -- endElement decremented on a </t>
            //     whose start had not incremented, lifting suppression of the still-open <v>
            //     so DECOY2 landed in the formula.
            // Counting every element makes the invariant "suppressDepth == depth below the
            // capture element" actually true, which is what the guard's correctness rests on.
            if (suppressDepth > 0 || isCapturing()) {
                enterSuppressed();
                return;
            }
            if (!NS_SHEETML.equals(uri) && !uri.isEmpty()) return;
            String name = !local.isEmpty() ? local : stripPrefix(qName);
            switch (name) {
                case "row":
                    String r = atts.getValue("r");
                    if (r != null) {
                        try { currentRow = Integer.parseInt(r); }
                        catch (NumberFormatException e) { currentRow++; }
                    } else {
                        currentRow++;
                    }
                    break;
                case "c":
                    currentCellRef = atts.getValue("r");
                    currentCellType = atts.getValue("t");
                    currentFormulaText = null;
                    currentValueText = null;
                    break;
                case "f":
                    if (isCapturing()) {
                        // MALFORMED: <f>/<v>/<t> nested inside another. SpreadsheetML makes
                        // them siblings inside <c>, never nested. Unguarded, the inner
                        // element's buf.setLength(0) WIPED the outer element's text, so
                        //   <f>=EXEC("powershell -enc AAAA")<v>0</v></f>
                        // yielded formula="0", isTruncated()=false, IOCs=[] -- a one-element
                        // evasion that made a live dropper triage as a clean macro workbook.
                        // Suppress the inner element entirely and keep capturing the outer.
                        enterSuppressed();
                    } else {
                        inFormula = true;
                        beginCapture(currentFormulaText);
                    }
                    break;
                case "v":
                    if (isCapturing()) {
                        enterSuppressed();
                    } else {
                        inValue = true;
                        beginCapture(currentValueText);
                    }
                    break;
                case "is":
                    // Guard against malformed XML with nested <is> — only the
                    // outermost <is> resets the accumulator. Without the guard,
                    // crafted XML like <is><t>payload</t><is/></is> would wipe
                    // payload before flush and silently suppress the IOC.
                    if (!inInlineString) {
                        inlineAcc.setLength(0);
                    }
                    inInlineString = true;
                    break;
                case "rPh":
                    // Phonetic-run wrapper: any <t> within is furigana, not value.
                    inPhoneticRun = true;
                    break;
                case "t":
                    // Only treat <t> as inline-string text when we're inside <is>
                    // and NOT in a phonetic-run subtree. <t> also appears inside
                    // formula-result string elements — those aren't payload either.
                    if (inInlineString && !inPhoneticRun) {
                        if (isCapturing()) {
                            enterSuppressed();
                        } else {
                            inText = true;
                            // No dedupe seed: sibling <t> runs inside one <is> legitimately
                            // repeat (rich text) and are concatenated into inlineAcc on </t>.
                            buf.setLength(0);
                        }
                    }
                    break;
                default:
                    // ignore
            }
        }

        /** Nesting depth of malformed-nested capture elements whose content we discard. */
        private int suppressDepth;

        private boolean isCapturing() {
            return inFormula || inValue || inText;
        }

        /**
         * Enter a malformed nested capture element: record the anomaly and swallow its
         * content so it cannot contaminate the enclosing element's text.
         */
        private void enterSuppressed() {
            structuralAnomaly = true;
            suppressDepth++;
        }

        /**
         * Start capturing into {@code buf}. When {@code priorText} is non-null this is a
         * SECOND <f> (or <v>) inside one <c> — also malformed. Last-wins silently dropped
         * the first one, so a dropper could hide the payload behind a benign decoy (in
         * either order). Seeding preserves both; the per-element cap in characters() still
         * applies to the combined length, so this cannot escape the budget.
         */
        private void beginCapture(String priorText) {
            buf.setLength(0);
            if (priorText != null) {
                structuralAnomaly = true;
                buf.append(priorText).append(' ');
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (suppressDepth > 0) {
                return;
            }
            if (isCapturing()) {
                // A formula is the payload, not an IOC fragment: it gets its own much
                // larger budget. Sharing WORKBOOK_VALUE_MAX_LEN (1 KB) here silently
                // amputated ~22% of macro-bearing documents mid-formula.
                int cap = inFormula ? formulaMaxLen : valueMaxLen;
                int remaining = cap - buf.length();
                int retained = Math.min(length, Math.max(0, remaining));
                if (retained > 0) {
                    buf.append(ch, start, retained);
                }
                if (retained < length) {
                    truncated = true;
                    if (inFormula) {
                        formulaWasTruncated = true;
                    }
                }
            }
        }

        @Override
        public void endElement(String uri, String local, String qName) throws SAXException {
            // NOTE: the namespace check must come AFTER the suppressDepth decrement, not before.
            // startElement increments for a foreign-namespace child too (it has to -- such a
            // child still contaminates the enclosing text), so if the close were filtered out
            // by namespace first, the depth would never come back down and every remaining
            // element on the sheet would be silently swallowed.
            // Close out a suppressed element without flushing anything: the enclosing capture
            // element is still mid-capture and owns buf. Decrement for EVERY element, matching
            // startElement's increment exactly -- the old name-gated version decremented on
            // f/v/t closes that had never incremented, which let a <t/> lift the suppression of
            // an enclosing <v>. Placed before the namespace check for the same reason: the
            // increment above ignores namespace, so the decrement must too.
            if (suppressDepth > 0) {
                suppressDepth--;
                return;
            }
            if (!NS_SHEETML.equals(uri) && !uri.isEmpty()) return;
            String name = !local.isEmpty() ? local : stripPrefix(qName);
            switch (name) {
                case "f":
                    if (inFormula) {
                        currentFormulaText = buf.toString();
                        inFormula = false;
                    }
                    break;
                case "v":
                    if (inValue) {
                        currentValueText = buf.toString();
                        inValue = false;
                    }
                    break;
                case "t":
                    if (inText) {
                        // Append this run's text to the inline-string accumulator
                        // rather than overwriting currentValueText. Rich-text
                        // inline strings with multiple <r><t> runs concatenate.
                        int remaining = valueMaxLen - inlineAcc.length();
                        int retained = Math.min(buf.length(), Math.max(0, remaining));
                        if (retained > 0) {
                            inlineAcc.append(buf, 0, retained);
                        }
                        if (retained < buf.length()) {
                            truncated = true;
                        }
                        inText = false;
                    }
                    break;
                case "is":
                    if (inInlineString) {
                        if (inlineAcc.length() > 0) {
                            currentValueText = inlineAcc.toString();
                        }
                        inInlineString = false;
                    }
                    break;
                case "rPh":
                    inPhoneticRun = false;
                    break;
                case "c":
                    flushCell();
                    break;
                default:
                    // nothing
            }
        }

        /** Appended to any formula that hit XLM_FORMULA_MAX_LEN, so a cut payload is obvious. */
        private static final String XLM_TRUNCATION_MARKER = "[...TIKA-XLM-FORMULA-TRUNCATED]";

        /** Running total of retained formula characters; bounds heap independently of count. */
        // long, not int: the cap is operator-raisable, and at values near Integer.MAX_VALUE
        // an int sum wraps negative and silently defeats the bound entirely.
        private long retainedFormulaChars;
        /** Set when the current cell's formula hit the per-formula cap. */
        private boolean formulaWasTruncated;

        private void flushCell() throws SAXException {
            // Consume the per-cell truncation flag FIRST: `r` on <c> is optional in
            // ECMA-376 and attacker-controlled, so the early return below is reachable.
            // Leaving the flag set there carried it onto the NEXT cell and appended a
            // truncation marker to an intact formula -- fabricated evidence loss.
            boolean cellFormulaTruncated = formulaWasTruncated;
            formulaWasTruncated = false;
            if (currentCellRef == null) return;
            String key = sheetName + ":" + currentRow + ":" + currentCellRef;
            if (currentFormulaText != null && !currentFormulaText.isEmpty()) {
                boolean roomByCount =
                        formulas.size() < maxFormulaEntries || formulas.containsKey(key);
                // Gate on what will ACTUALLY be retained, marker included -- otherwise the
                // aggregate cap is exceeded by the marker length on every truncated formula.
                // cellFormulaTruncated, NOT formulaWasTruncated: the field was cleared at
                // the top of this method, so reading it here always yielded false and the
                // marker's length escaped the aggregate budget on every truncated formula.
                int projected = currentFormulaText.length()
                        + (cellFormulaTruncated ? XLM_TRUNCATION_MARKER.length() + 1 : 0);
                // Credit back the entry this one REPLACES, exactly as the charge below does.
                // The charge was changed to net but this gate was left gross, so a replacement
                // was tested against a total that already counted the old value -- the same
                // double-count, moved from the accumulator into the admission check. A
                // replacement that genuinely fits was then REFUSED and truncated=true set,
                // fabricating an evidence loss. Reachable on purpose: put a benign short decoy
                // at a cell ref, then the payload at the SAME ref once the budget is nearly
                // spent, and the payload is the one that gets dropped.
                // roomByCount already credits the replacement (`|| containsKey`); these two
                // gates must agree.
                String existing = formulas.get(key);
                long creditedTotal =
                        retainedFormulaChars - (existing == null ? 0 : existing.length());
                // A REPEATED cell key means a duplicated <c r="..."> or <row r="...">, which
                // SpreadsheetML never emits. put() replaced the earlier entry, so a benign decoy
                // at the same ref DELETED the payload from `formulas` -- and `formulas` is what
                // feeds the IOC scanner and the MACRO entry, so the payload vanished from every
                // structured output with no flag. Exactly the last-wins evasion already fixed
                // one level down for a duplicated <f> inside one <c>, left live at the cell and
                // row level. Keep BOTH, in either order, since we cannot know which Excel would
                // honour; the aggregate gate below still bounds the combined length.
                int combinedExtra = existing == null ? 0 : existing.length() + 1;
                boolean roomByChars =
                        creditedTotal + combinedExtra + projected <= formulaTotalMaxChars;
                if (existing != null) {
                    structuralAnomaly = true;
                }
                if (roomByCount && roomByChars) {
                    // A formula cut at XLM_FORMULA_MAX_LEN is no longer valid syntax, and a
                    // bare prefix reads as a complete formula to anything downstream. Mark it
                    // explicitly so a partial payload can never be mistaken for a whole one.
                    //
                    // The marker is SPACE-separated deliberately: XlmXmlIocScanner's URL
                    // pattern excludes whitespace but not '[', so appending it directly
                    // absorbed the marker into the extracted indicator
                    // ("http://evil/x[...TIKA-XLM-FORMULA-TRUNCATED]"), yielding an IOC that
                    // can never match the real C2. The space terminates the URL match.
                    // NOTE: a document CAN embed a copy of XLM_TRUNCATION_MARKER in its own
                    // formula text, so a consumer must not treat the inline marker as proof of
                    // truncation -- the authoritative signal is
                    // msoffice:xlm-capture-limit-reached, which a document cannot set.
                    //
                    // A defangMarker() rewrite was tried here and REVERTED: rewriting the
                    // 9-char sentinel to a 16-char one expanded the text AFTER `projected`
                    // above had gated on the pre-rewrite length, so the document-wide formula
                    // cap was overshot by up to 78% (measured: 641 chars retained against a
                    // 400-char cap, isTruncated() false). It also covered only this one path,
                    // not cell values or the IOC scanner's output, so the guarantee it appeared
                    // to give was not one it delivered. Trading a real budget escape for a
                    // cosmetic defence of a non-authoritative marker is the wrong trade.
                    String base = cellFormulaTruncated
                            ? currentFormulaText + " " + XLM_TRUNCATION_MARKER
                            : currentFormulaText;
                    String recorded = existing == null ? base : existing + " " + base;
                    // Charge only the NET change. `formulas` is keyed by cell ref, so a
                    // repeated ref REPLACES the previous entry -- but the budget used to be
                    // charged the full length every time, so a sheet repeating one cell ref
                    // burned the document-wide formula budget on content that was never
                    // retained, cutting capture short for later sheets that had real payload.
                    formulas.put(key, recorded);
                    retainedFormulaChars = creditedTotal + recorded.length();
                    handlerRetainedFormulaChars = (int) Math.min(
                            Integer.MAX_VALUE, retainedFormulaChars);
                    xhtml.element("p", currentCellRef + ": " + recorded);
                } else {
                    truncated = true;
                }
            }
            formulaWasTruncated = false;
            // Resolve shared-string-indexed cells (<c t="s"><v>N</v></c>) before
            // recording. Without this, droppers stashing payload fragments via
            // sharedStrings.xml in the macrosheet itself bypass the IOC scanner.
            String resolved = resolveValue(currentCellType, currentValueText);
            if (resolved != null && !resolved.isEmpty()) {
                if (resolved.length() > valueMaxLen) {
                    resolved = resolved.substring(0, valueMaxLen);
                    truncated = true;
                }
                if (values.size() < maxValueEntries || values.containsKey(key)) {
                    // Aggregate guard, shared document-wide with the worksheet path.
                    // Credit the entry this replaces: values.put() overwrites on a repeated
                    // cell ref, so charging gross billed the document budget per repetition.
                    String priorValue = values.get(key);
                    if (valueCharBudget != null
                            && !valueCharBudget.tryRetain(resolved.length(),
                                    priorValue == null ? 0 : priorValue.length())) {
                        truncated = true;
                    } else {
                        values.put(key, resolved);
                    }
                } else {
                    truncated = true;
                }
            }
            currentCellRef = null;
            currentCellType = null;
            currentFormulaText = null;
            currentValueText = null;
            // Defensive: reset ALL element-state flags + accumulator at cell
            // boundary so a malformed cell with unclosed <rPh>, <is>, <v>, <t>,
            // or <f> can't leak its open state into the next cell and suppress
            // legitimate IOC capture (the prior bug was inPhoneticRun staying
            // true after an unclosed </rPh>, killing every subsequent <t> on
            // the sheet). Cell boundaries are well-defined; intra-cell open
            // state never legitimately survives </c>.
            inPhoneticRun = false;
            inInlineString = false;
            inText = false;
            inFormula = false;
            inValue = false;
            suppressDepth = 0;
            inlineAcc.setLength(0);
            buf.setLength(0);
        }

        /**
         * Resolve a cell value according to its OOXML type. The {@code t="s"}
         * case looks up the integer index in the shared strings table; all
         * other types (numeric, inlineStr, str-from-formula, boolean) pass
         * through unchanged because the SAX parser already captured the literal.
         */
        private String resolveValue(String cellType, String raw) {
            if (raw == null || raw.isEmpty()) return raw;
            if ("s".equals(cellType) && sharedStrings != null) {
                try {
                    int idx = Integer.parseInt(raw.trim());
                    String s = sharedStrings.getItemAt(idx);
                    if (s != null) return s;
                } catch (NumberFormatException | IndexOutOfBoundsException ignored) {
                    // Malformed index or out-of-range → fall through to raw.
                }
            }
            return raw;
        }

        private static String stripPrefix(String qName) {
            int colon = qName.indexOf(':');
            return colon < 0 ? qName : qName.substring(colon + 1);
        }
    }
}
