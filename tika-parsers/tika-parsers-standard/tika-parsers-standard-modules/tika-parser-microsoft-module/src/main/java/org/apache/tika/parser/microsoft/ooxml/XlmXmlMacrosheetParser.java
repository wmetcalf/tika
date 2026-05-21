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

import org.apache.tika.sax.XHTMLContentHandler;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 */
final class XlmXmlMacrosheetParser {

    private final InputStream stream;
    private final XHTMLContentHandler xhtml;
    private final String sheetName;

    /** Formula text per cell, keyed "{sheet}:{row}:{col}". */
    private final Map<String, String> formulas = new LinkedHashMap<>();
    /** Constant value per cell (numeric strings preserved verbatim), keyed identically. */
    private final Map<String, String> values = new LinkedHashMap<>();

    XlmXmlMacrosheetParser(InputStream stream, XHTMLContentHandler xhtml, String sheetName) {
        this.stream = stream;
        this.xhtml = xhtml;
        this.sheetName = sheetName;
    }

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

    private final class Handler extends DefaultHandler {
        private static final String NS_SHEETML = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

        // Current cell reference (e.g. "A1") — preserved between <c> start and end.
        private String currentCellRef;
        // Current row index, derived from <row r="..."> or incremented if absent.
        private int currentRow;
        // Buffers for the inner text of <f> and <v> elements.
        private final StringBuilder buf = new StringBuilder();
        private boolean inFormula;
        private boolean inValue;
        // Tracks the formula text written for the current cell so we can attach
        // it to the cell ref on </c>.
        private String currentFormulaText;
        private String currentValueText;

        @Override
        public void startElement(String uri, String local, String qName, Attributes atts)
                throws SAXException {
            // The XLM XML schema uses the SpreadsheetML namespace. Most workbooks
            // declare it as the default namespace; tolerate missing namespace too.
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
                default:
                    // ignore — other elements (is, t, sheetData, worksheet, etc.) are passed through
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inFormula || inValue) {
                buf.append(ch, start, length);
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
                case "c":
                    flushCell();
                    break;
                default:
                    // nothing
            }
        }

        private void flushCell() throws SAXException {
            if (currentCellRef == null) return;
            String key = sheetName + ":" + currentRow + ":" + currentCellRef;
            if (currentFormulaText != null && !currentFormulaText.isEmpty()) {
                formulas.put(key, currentFormulaText);
                // Emit the raw formula so downstream text-based scanners (URL
                // detection, custom YARA-style rules) see it in the entry text.
                xhtml.element("p", currentCellRef + ": " + currentFormulaText);
            }
            if (currentValueText != null && !currentValueText.isEmpty()) {
                values.put(key, currentValueText);
            }
            currentCellRef = null;
            currentFormulaText = null;
            currentValueText = null;
        }

        private static String stripPrefix(String qName) {
            int colon = qName.indexOf(':');
            return colon < 0 ? qName : qName.substring(colon + 1);
        }
    }
}
