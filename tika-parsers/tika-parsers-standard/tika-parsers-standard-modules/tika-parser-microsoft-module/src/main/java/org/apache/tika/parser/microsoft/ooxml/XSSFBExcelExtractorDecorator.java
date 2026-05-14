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

import org.apache.tika.exception.TikaException;
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
        }

        while (iter.hasNext()) {
            InputStream stream = iter.next();
            PackagePart sheetPart = iter.getSheetPart();
            addDrawingHyperLinks(sheetPart);
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
        }

        // Process XLM binary macro sheets missed by POI's SheetIterator.
        // POI's WORKSHEET_RELS set covers MACRO_SHEET_XML but not MACRO_SHEET_BIN /
        // INTL_MACRO_SHEET_BIN (different relationship namespace).  We walk the
        // workbook part's relationships directly and feed each macrosheet through
        // the same XSSFBSheetHandler used for regular sheets.
        processXlmBinaryMacroSheets(container, styles, strings, xhtml);
    }

    private void processXlmBinaryMacroSheets(OPCPackage container,
                                              XSSFBStylesTable styles,
                                              TikaXSSFBSharedStringsTable strings,
                                              XHTMLContentHandler xhtml)
            throws SAXException, IOException {

        // Query the package directly for binary macrosheet parts — simpler and more
        // reliable than walking workbook relationships.  XLSB uses an Override content
        // type (application/vnd.ms-excel.macrosheet) for each macrosheet .bin part.
        // The Default element gives every .bin the workbook content type, so workbook-
        // based lookup returns too many parts; content-type Override lookup is exact.
        List<PackagePart> macroParts = new ArrayList<>();
        macroParts.addAll(
                container.getPartsByContentType(
                        XSSFRelation.MACRO_SHEET_BIN.getContentType()));
        macroParts.addAll(
                container.getPartsByContentType(
                        XSSFRelation.INTL_MACRO_SHEET_BIN.getContentType()));

        if (macroParts.isEmpty()) {
            return;
        }

        metadata.set("msoffice:xlsb:has-xlm-macros", "true");

        for (PackagePart macroPart : macroParts) {
            String sheetName = sheetNameFromPart(macroPart);
            xhtml.startElement("div");
            xhtml.element("h1", sheetName);
            xhtml.startElement("table");
            xhtml.startElement("tbody");

            SheetTextAsHTML sheetExtractor = new SheetTextAsHTML(config, xhtml);
            try (InputStream is = macroPart.getInputStream()) {
                // formulasNotResults=true: emit formula text where POI can decode it.
                // XLM Ptg tokens are often decodable (string literals, cell refs, known
                // function names); binary-only opcodes fall back to evaluated values.
                XSSFBSheetHandler handler =
                        new XSSFBSheetHandler(is, styles, null, strings,
                                sheetExtractor, formatter, true);
                handler.parse();
            } catch (Exception e) {
                // Non-fatal: record sheet name, continue with remaining sheets.
                xhtml.element("p", "xlm-parse-error: " + e.getMessage());
            }

            xhtml.endElement("tbody");
            xhtml.endElement("table");
            xhtml.endElement("div");
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
