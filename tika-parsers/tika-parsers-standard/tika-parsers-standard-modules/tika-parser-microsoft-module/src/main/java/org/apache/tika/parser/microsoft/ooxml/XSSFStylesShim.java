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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.BuiltinFormats;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.exception.TikaException;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.utils.XMLReaderUtils;

/**
 * SAX-based shim that replaces POI's {@code StylesTable} for XLSX event-based parsing.
 * <p>
 * Parses {@code xl/styles.xml} and extracts only the information needed for text
 * extraction: the number format resolution chain (cellXfs index to format string).
 * This avoids the XMLBeans dependency that {@code StylesTable} requires.
 */
class XSSFStylesShim {

    private final Map<Short, String> numberFormats = new HashMap<>();
    private final List<Short> cellXfFormatIds = new ArrayList<>();
    // Font color resolution chain: each cellXf points at a font by index,
    // and each font has an optional <color rgb="AARRGGBB"/> (or theme/index).
    // Color is captured as a 6-char uppercase RGB hex (alpha dropped); null
    // when no explicit RGB is set.
    private final List<Integer> cellXfFontIds = new ArrayList<>();
    private final List<String> fontColors = new ArrayList<>();

    XSSFStylesShim(InputStream stylesData, ParseContext parseContext)
            throws IOException, SAXException, TikaException {
        if (stylesData != null) {
            try {
                XMLReaderUtils.parseSAX(stylesData, new StylesHandler(), parseContext);
            } finally {
                stylesData.close();
            }
        }
    }

    int getNumCellStyles() {
        return cellXfFormatIds.size();
    }

    short getFormatIndex(int styleIndex) {
        if (styleIndex < 0 || styleIndex >= cellXfFormatIds.size()) {
            return -1;
        }
        return cellXfFormatIds.get(styleIndex);
    }

    String getFormatString(int styleIndex) {
        short fmtId = getFormatIndex(styleIndex);
        if (fmtId == -1) {
            return null;
        }
        String fmt = numberFormats.get(fmtId);
        if (fmt == null) {
            fmt = BuiltinFormats.getBuiltinFormat(fmtId);
        }
        return fmt;
    }

    /**
     * Returns the font color (6-char uppercase RGB hex) for a cell style
     * index, or {@code null} when no explicit RGB color is set on the
     * resolved font.
     */
    String getFontColor(int styleIndex) {
        if (styleIndex < 0 || styleIndex >= cellXfFontIds.size()) {
            return null;
        }
        int fontId = cellXfFontIds.get(styleIndex);
        if (fontId < 0 || fontId >= fontColors.size()) {
            return null;
        }
        return fontColors.get(fontId);
    }

    private class StylesHandler extends DefaultHandler {

        private static final String NS =
                "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

        private boolean inCellXfs;
        private boolean inNumFmts;
        private boolean inFonts;
        private boolean inFont;
        private String currentFontColor;

        @Override
        public void startElement(String uri, String localName, String qName,
                                 Attributes attributes) {
            if (!NS.equals(uri)) {
                return;
            }
            switch (localName) {
                case "numFmts":
                    inNumFmts = true;
                    break;
                case "numFmt":
                    if (inNumFmts) {
                        String idStr = attributes.getValue("numFmtId");
                        String code = attributes.getValue("formatCode");
                        if (idStr != null && code != null) {
                            try {
                                numberFormats.put(Short.parseShort(idStr), code);
                            } catch (NumberFormatException e) {
                                // skip malformed
                            }
                        }
                    }
                    break;
                case "cellXfs":
                    inCellXfs = true;
                    break;
                case "xf":
                    if (inCellXfs) {
                        String numFmtIdStr = attributes.getValue("numFmtId");
                        short numFmtId = 0;
                        if (numFmtIdStr != null) {
                            try {
                                numFmtId = Short.parseShort(numFmtIdStr);
                            } catch (NumberFormatException e) {
                                // default to 0 (General)
                            }
                        }
                        cellXfFormatIds.add(numFmtId);
                        String fontIdStr = attributes.getValue("fontId");
                        int fontId = 0;
                        if (fontIdStr != null) {
                            try {
                                fontId = Integer.parseInt(fontIdStr);
                            } catch (NumberFormatException e) {
                                // default to 0
                            }
                        }
                        cellXfFontIds.add(fontId);
                    }
                    break;
                case "fonts":
                    inFonts = true;
                    break;
                case "font":
                    if (inFonts) {
                        inFont = true;
                        currentFontColor = null;
                    }
                    break;
                case "color":
                    if (inFont) {
                        // <color rgb="FF000000"/> — XLSX stores ARGB; drop alpha.
                        String rgb = attributes.getValue("rgb");
                        if (rgb != null && rgb.length() == 8) {
                            currentFontColor = rgb.substring(2).toUpperCase(
                                    java.util.Locale.ROOT);
                        } else if (rgb != null && rgb.length() == 6) {
                            currentFontColor = rgb.toUpperCase(
                                    java.util.Locale.ROOT);
                        }
                    }
                    break;
                default:
                    break;
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (!NS.equals(uri)) {
                return;
            }
            if ("numFmts".equals(localName)) {
                inNumFmts = false;
            } else if ("cellXfs".equals(localName)) {
                inCellXfs = false;
            } else if ("fonts".equals(localName)) {
                inFonts = false;
            } else if ("font".equals(localName)) {
                if (inFont) {
                    fontColors.add(currentFontColor);
                    inFont = false;
                }
            }
        }
    }
}
