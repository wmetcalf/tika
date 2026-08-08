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
        try {
            SAXParserFactory spf = SAXParserFactory.newInstance();
            spf.setNamespaceAware(true);
            spf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            SAXParser sp = spf.newSAXParser();
            XMLReader reader = sp.getXMLReader();
            reader.setContentHandler(new Handler());
            reader.parse(new InputSource(stream));
        } catch (javax.xml.parsers.ParserConfigurationException e) {
            throw new SAXException(e);
        }
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
                    inFormula = true;
                    buf.setLength(0);
                    break;
                case "v":
                    inValue = true;
                    buf.setLength(0);
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
                        inText = true;
                        buf.setLength(0);
                    }
                    break;
                default:
                    // ignore
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inFormula || inValue || inText) {
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
                boolean roomByChars =
                        retainedFormulaChars + projected <= formulaTotalMaxChars;
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
                    String recorded = cellFormulaTruncated
                            ? currentFormulaText + " " + XLM_TRUNCATION_MARKER
                            : currentFormulaText;
                    retainedFormulaChars += recorded.length();
                    handlerRetainedFormulaChars = (int) Math.min(
                            Integer.MAX_VALUE, retainedFormulaChars);
                    formulas.put(key, recorded);
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
                    if (valueCharBudget != null
                            && !valueCharBudget.tryRetain(resolved.length())) {
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
