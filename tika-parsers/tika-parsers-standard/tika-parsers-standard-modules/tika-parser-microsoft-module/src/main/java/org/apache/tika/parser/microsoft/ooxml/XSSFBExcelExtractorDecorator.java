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
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagePartName;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.PackageRelationshipCollection;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.xssf.binary.XSSFBSheetHandler;
import org.apache.poi.xssf.binary.XSSFBStylesTable;
import org.apache.poi.xssf.eventusermodel.XSSFBReader;
import org.apache.poi.xssf.usermodel.XSSFRelation;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.exception.RuntimeSAXException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Office;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.XHTMLContentHandler;

public class XSSFBExcelExtractorDecorator extends XSSFExcelExtractorDecorator {

    public XSSFBExcelExtractorDecorator(ParseContext context, OPCPackage pkg,
                                        Locale locale) {
        super(context, pkg, locale);
    }

    @Override
    public void getXHTML(ContentHandler handler, Metadata metadata, ParseContext context)
            throws SAXException, IOException, TikaException {

        this.metadata = metadata;
        this.parseContext = context;
        metadata.set(Office.PROTECTED_WORKSHEET, false);

        super.getXHTML(handler, metadata, context);
    }

    @Override
    protected void buildXHTML(XHTMLContentHandler xhtml)
            throws SAXException, IOException {
        OPCPackage container = opcPackage;

        TikaXSSFBSharedStringsTable strings;
        XSSFBReader.SheetIterator iter;
        XSSFBReader xssfReader;
        XSSFBStylesTable styles;
        try {
            xssfReader = new XSSFBReader(container);
            String originalPath = xssfReader.getAbsPathMetadata();
            if (originalPath != null) {
                metadata.set(TikaCoreProperties.ORIGINAL_RESOURCE_NAME, originalPath);
            }
            styles = xssfReader.getXSSFBStylesTable();
            iter = (XSSFBReader.SheetIterator) xssfReader.getSheetsData();
            strings = new TikaXSSFBSharedStringsTable(container);
        } catch (OpenXML4JException e) {
            throw new IOException(e);
        } catch (RuntimeException e) {
            // POI's XSSFBUtils.readXLWideString throws RuntimeException
            // (StringIndexOutOfBoundsException) on malformed XLSB shared-
            // strings / styles tables — common in attacker-crafted XLSB
            // macro carriers. Without this catch the whole workbook
            // aborts with "Unexpected RuntimeException from OOXMLParser"
            // and we lose the entire file. Record the gap, surface
            // whatever metadata is already set, and return; the macros
            // path (processXlmBinaryMacroSheets) runs against the raw
            // container parts and isn't affected by the table parse.
            metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                    "XLSB shared-strings/styles parse aborted at " +
                            e.getClass().getSimpleName() + ": " +
                            (e.getMessage() == null ? "" : e.getMessage()));
            // Still try the macro-sheet path — it doesn't need strings/styles.
            processXlmBinaryMacroSheets(container, null, null, xhtml,
                    java.util.Collections.emptyMap(),
                    XlmWorkbookSheetMap.build(container));
            return;
        }

        // Capture numeric cell values from every worksheet for XLM emulation.
        // This runs a lightweight second pass on each sheet binary; the main
        // XHTML pass below is unaffected.
        XlmWorkbookSheetMap xlmSheetMap = XlmWorkbookSheetMap.build(container);
        boolean hasXlmMacroParts = !container.getPartsByContentType(
                XSSFRelation.MACRO_SHEET_BIN.getContentType()).isEmpty()
                || !container.getPartsByContentType(
                        XSSFRelation.INTL_MACRO_SHEET_BIN.getContentType()).isEmpty();
        Map<String, Double> xlmCellValues = hasXlmMacroParts
                ? captureWorksheetValues(container, metadata, xlmInputBudget)
                : java.util.Collections.emptyMap();

        int sheetIdx = 0;
        while (iter.hasNext()) {
            // Wrap each sheet's parse so a POI XSSFBUtils.readXLWideString
            // "Range out of bounds" / IllegalStateException on a malformed
            // sheet binary doesn't take down the whole workbook. Surfaced
            // by the mbzdls XLSB sample where one sheet's string descriptor
            // had a negative width and aborted every subsequent sheet.
            try {
                InputStream stream = iter.next();
                PackagePart sheetPart = iter.getSheetPart();
                sheetParts.add(sheetPart);

                SheetTextAsHTML sheetExtractor = new SheetTextAsHTML(config, xhtml);

                // Parse comments with our own binary parser that avoids xmlbeans
                TikaXSSFBCommentsTable tikaComments = parseBinaryComments(sheetPart);
                if (tikaComments != null && tikaComments.hasComments()) {
                    metadata.set(Office.HAS_COMMENTS, true);
                }

                xhtml.startElement("div");
                xhtml.element("h1", iter.getSheetName());

                xhtml.startElement("table");
                xhtml.startElement("tbody");

                // Pass null for POI's comments table to avoid xmlbeans dependency.
                // Comments are emitted separately after sheet processing.
                XSSFBSheetHandler xssfbSheetHandler =
                        new XSSFBSheetHandler(stream, styles, null, strings,
                                sheetExtractor, formatter, false);
                xssfbSheetHandler.parse();

                xhtml.endElement("tbody");
                xhtml.endElement("table");

                // Emit comments after the table (since we bypass POI's inline
                // comment handling to avoid xmlbeans dependency)
                if (tikaComments != null) {
                    tikaComments.emitAllComments(xhtml);
                }

                for (String header : sheetExtractor.headers) {
                    extractHeaderFooter(header, xhtml);
                }
                for (String footer : sheetExtractor.footers) {
                    extractHeaderFooter(footer, xhtml);
                }
                processDrawings(sheetPart, xhtml);
                extractHyperLinks(sheetPart, xhtml);
                xhtml.endElement("div");
            } catch (SecurityException e) {
                throw e;
            } catch (RuntimeSAXException e) {
                throw e;
            } catch (RuntimeException e) {
                WriteLimitReachedException.throwIfWriteLimitReached(e);
                metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                        "XLSB sheet " + sheetIdx + " aborted at " +
                                e.getClass().getSimpleName() + ": " +
                                (e.getMessage() == null ? "" : e.getMessage()));
            }
            sheetIdx++;
        }

        // Process XLM binary macro sheets missed by POI's SheetIterator.
        // POI's WORKSHEET_RELS set covers MACRO_SHEET_XML but not MACRO_SHEET_BIN /
        // INTL_MACRO_SHEET_BIN (different relationship namespace).  We walk the
        // workbook part's relationships directly and feed each macrosheet through
        // Biff12XlmMacrosheetParser (static text) + XlmMacroEmulator (evaluation).
        processXlmBinaryMacroSheets(container, styles, strings, xhtml, xlmCellValues, xlmSheetMap);
    }

    private void processXlmBinaryMacroSheets(OPCPackage container,
                                              XSSFBStylesTable styles,
                                              TikaXSSFBSharedStringsTable strings,
                                              XHTMLContentHandler xhtml,
                                              Map<String, Double> cellValues,
                                              XlmWorkbookSheetMap sheetMap)
            throws SAXException, IOException {

        List<PackagePart> macroParts = new ArrayList<>();
        macroParts.addAll(container.getPartsByContentType(
                XSSFRelation.MACRO_SHEET_BIN.getContentType()));
        macroParts.addAll(container.getPartsByContentType(
                XSSFRelation.INTL_MACRO_SHEET_BIN.getContentType()));

        if (macroParts.isEmpty()) {
            return;
        }

        metadata.set("msoffice:xlsb:has-xlm-macros", "true");
        org.apache.tika.parser.microsoft.OfficeParserConfig xlmCfg =
                parseContext == null
                        ? null
                        : parseContext.get(
                                org.apache.tika.parser.microsoft.OfficeParserConfig.class);
        XlmMacroEmulator.Limits xlmLimits = XlmMacroEmulator.Limits.fromConfig(xlmCfg);
        XlmMacroEmulator.DocumentBudget documentBudget =
                new XlmMacroEmulator.DocumentBudget(xlmLimits);

        int processedMacroParts = 0;
        for (PackagePart macroPart : macroParts) {
            if (xlmInputBudget.isLimitReached()) {
                markXlmCaptureLimit(metadata, "XLSB XLM input capture limit reached");
                break;
            }
            if (processedMacroParts >= MAX_XLM_MACRO_PARTS) {
                markXlmCaptureLimit(metadata, "XLSB XLM macro-part limit reached");
                break;
            }
            processedMacroParts++;
            String sheetName = sheetNameFromPart(macroPart);
            xhtml.startElement("div");
            xhtml.element("h1", sheetName);

            XlmMacroEmulator emulator = new XlmMacroEmulator(
                    cellValues, sheetMap, xlmLimits, documentBudget);

            try (InputStream is = xlmInputBudget.limit(
                    macroPart.getInputStream(),
                    () -> markXlmCaptureLimit(
                            metadata, "XLSB XLM input capture limit reached"))) {
                Biff12XlmMacrosheetParser parser =
                        new Biff12XlmMacrosheetParser(is, xhtml, emulator,
                                xlmCfg == null ? 0 : xlmCfg.getXlmMaxFormulaRecordBytes(),
                                () -> markXlmCaptureLimit(metadata,
                                        "XLSB XLM formula record exceeded the size bound "
                                                + "and was dropped"),
                                () -> markXlmCaptureLimit(metadata,
                                        "XLSB XLM formula only partially decoded: unknown "
                                                + "Ptg opcode or truncated operand, so the "
                                                + "emitted formula is a PREFIX. A spliced "
                                                + "unknown opcode can strip the function "
                                                + "call and leave only its argument"));
                parser.parse();
            } catch (SecurityException e) {
                throw e;
            } catch (Exception e) {
                WriteLimitReachedException.throwIfWriteLimitReached(e);
                // Flag it. Emitting the note into the TEXT and nothing else meant a consumer
                // reading metadata -- which is where every other capture shortfall is
                // reported -- saw a clean parse. A macro part we failed to read is precisely
                // the case where "no XLM IOCs found" must not be trusted, and a truncated
                // .bin part is trivially attacker-supplied.
                xhtml.element("p", "xlm-parse-error: " + e.getMessage());
                markXlmCaptureLimit(metadata, "XLSB XLM macrosheet parse error: "
                        + e.getMessage());
            }
            if (emulator.isLimitReached()) {
                markXlmCaptureLimit(metadata, emulator.getLimitWarning());
            }

            // Run emulation and emit resolved IOCs
            try {
                emulator.emulate();
                if (emulator.isLimitReached()) {
                    markXlmCaptureLimit(metadata, emulator.getLimitWarning());
                }
                if (!emulator.iocs.isEmpty()) {
                    xhtml.startElement("div");
                    xhtml.element("h2", "XLM Emulation");
                    for (String ioc : emulator.iocs) {
                        xhtml.element("p", ioc);
                    }
                    xhtml.endElement("div");
                }
            } catch (SecurityException e) {
                throw e;
            } catch (Exception e) {
                WriteLimitReachedException.throwIfWriteLimitReached(e);
                // Non-fatal — static text output already emitted above
            }

            xhtml.endElement("div");
            if (xlmInputBudget.isLimitReached()) {
                break;
            }
        }
    }

    private static Map<String, Double> captureWorksheetValues(
            OPCPackage container, Metadata metadata,
            XlmInputBudget inputBudget) {
        Map<String, Double> values = new java.util.HashMap<>();
        // Capture numeric cell values keyed by "{sheetName}:{row}:{col}".
        // Using the sheet name (from the iterator) matches the xtiIndex→name resolution
        // done by XlmWorkbookSheetMap, so Area3d range lookups find the right cells.
        try {
            XSSFBReader reader = new XSSFBReader(container);
            XSSFBReader.SheetIterator iter =
                    (XSSFBReader.SheetIterator) reader.getSheetsData();
            while (iter.hasNext()) {
                if (inputBudget.isLimitReached()) {
                    break;
                }
                try (InputStream stream = inputBudget.limit(
                        iter.next(),
                        () -> markXlmCaptureLimit(
                                metadata,
                                "XLSB XLM input capture limit reached"))) {
                    String sheetName = iter.getSheetName(); // called after next()
                    XlmWorksheetCellCapture capture =
                            new XlmWorksheetCellCapture(stream, sheetName, values);
                    capture.parse();
                    if (capture.isLimitReached()) {
                        markXlmCaptureLimit(metadata,
                                "XLSB worksheet-value capture limit reached");
                        break;
                    }
                    if (inputBudget.isLimitReached()) {
                        break;
                    }
                } catch (Exception ignored) {
                    // Skip unreadable sheets; other sheets still captured
                }
            }
        } catch (Exception ignored) {
            // If the workbook can't be re-read, emulation proceeds with empty map
        }
        return values;
    }

    private static void markXlmCaptureLimit(Metadata metadata, String warning) {
        if (metadata == null
                || Boolean.parseBoolean(
                        metadata.get("msoffice:xlm-capture-limit-reached"))) {
            return;
        }
        metadata.set("msoffice:xlm-capture-limit-reached", "true");
        metadata.set(TikaCoreProperties.TRUNCATED_METADATA, true);
        metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING, warning);
        if (metadata.get("ExploitClass") == null) {
            metadata.set("ExploitClass",
                    "XLM analysis incomplete; workbook values may not have been analyzed");
        }
    }

    private static String sheetNameFromPart(PackagePart part) {
        String name = part.getPartName().getName();
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name;
    }

    private static final String RELATION_COMMENTS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments";

    private TikaXSSFBCommentsTable parseBinaryComments(PackagePart sheetPart) {
        try {
            PackageRelationshipCollection rels =
                    sheetPart.getRelationshipsByType(RELATION_COMMENTS);
            if (rels.isEmpty()) {
                return null;
            }
            PackageRelationship rel = rels.getRelationship(0);
            PackagePartName partName =
                    PackagingURIHelper.createPartName(rel.getTargetURI());
            PackagePart commentsPart = rel.getPackage().getPart(partName);
            if (commentsPart == null) {
                return null;
            }
            try (InputStream is = commentsPart.getInputStream()) {
                return new TikaXSSFBCommentsTable(is);
            }
        } catch (InvalidFormatException | IOException e) {
            return null;
        }
    }

    @Override
    protected void extractHeaderFooter(String hf, XHTMLContentHandler xhtml) throws SAXException {
        if (hf.length() > 0) {
            xhtml.element("p", hf);
        }
    }
}
