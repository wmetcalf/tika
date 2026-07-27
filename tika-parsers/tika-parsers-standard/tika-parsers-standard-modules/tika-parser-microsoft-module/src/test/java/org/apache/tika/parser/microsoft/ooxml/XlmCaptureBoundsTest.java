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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.xssf.binary.XSSFBStylesTable;
import org.apache.poi.xssf.usermodel.XSSFRelation;
import org.junit.jupiter.api.Test;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.exception.RuntimeSAXException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.microsoft.OfficeParserConfig;
import org.apache.tika.sax.ToXMLContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;

class XlmCaptureBoundsTest {

    private static final int INPUT_BUDGET_BYTES = 32 * 1_024 * 1_024;
    private static final int INPUT_PART_BYTES = 8 * 1_024 * 1_024;

    @Test
    void testXmlMacrosheetFormulaLengthIsBounded() throws Exception {
        String formula = "A".repeat(
                XSSFExcelExtractorDecorator.WORKBOOK_VALUE_MAX_LEN + 1);
        String xml = String.format(Locale.ROOT, """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData><row r="1"><c r="A1"><f>%s</f></c></row></sheetData>
                </worksheet>
                """, formula);
        Metadata metadata = new Metadata();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                new ToXMLContentHandler(), metadata, new ParseContext());
        XlmXmlMacrosheetParser parser = new XlmXmlMacrosheetParser(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                xhtml, "Macro1", null);

        xhtml.startDocument();
        parser.parse();
        xhtml.endDocument();

        assertTrue(parser.getFormulas().get("Macro1:1:A1").length()
                <= XSSFExcelExtractorDecorator.WORKBOOK_VALUE_MAX_LEN);
        assertTrue(parser.isTruncated());
    }

    @Test
    void testXlsbWorksheetCaptureEntryCountIsBounded() throws Exception {
        Map<String, Double> values = new HashMap<>();
        XlmWorksheetCellCapture capture = new XlmWorksheetCellCapture(
                new ByteArrayInputStream(new byte[0]), "Sheet1", values);
        for (int i = 0;
             i <= XSSFExcelExtractorDecorator.WORKBOOK_VALUES_MAX_ENTRIES; i++) {
            byte[] record = ByteBuffer.allocate(16)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(i)
                    .putInt(0)
                    .putDouble(i)
                    .array();
            capture.handleRecord(0x0005, record);
        }

        assertEquals(XSSFExcelExtractorDecorator.WORKBOOK_VALUES_MAX_ENTRIES,
                values.size());
        assertTrue(capture.isLimitReached());
    }

    @Test
    void testXmlWorksheetValueTruncationSignalsCaptureLimit() throws Exception {
        Metadata metadata = new Metadata();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                new ToXMLContentHandler(), metadata, new ParseContext());
        XSSFExcelExtractorDecorator.SheetTextAsHTML sheet =
                new XSSFExcelExtractorDecorator.SheetTextAsHTML(
                        new OfficeParserConfig(), xhtml);
        Map<String, String> values = new HashMap<>();
        AtomicInteger limitSignals = new AtomicInteger();
        sheet.setCellValueCapture(
                values, "Sheet1", limitSignals::incrementAndGet);

        xhtml.startDocument();
        sheet.startRow(0);
        sheet.cell("A1", "A".repeat(
                        XSSFExcelExtractorDecorator.WORKBOOK_VALUE_MAX_LEN + 1),
                (XSSFCommentsShim.CommentData) null);
        sheet.endRow(0);
        xhtml.endDocument();

        assertEquals(XSSFExcelExtractorDecorator.WORKBOOK_VALUE_MAX_LEN,
                values.get("Sheet1:0:A1").length());
        assertEquals(1, limitSignals.get(),
                "truncating an XLM cross-sheet value must signal incomplete analysis");
    }

    @Test
    void testXmlWorksheetValueDropSignalsCaptureLimit() throws Exception {
        Metadata metadata = new Metadata();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                new ToXMLContentHandler(), metadata, new ParseContext());
        XSSFExcelExtractorDecorator.SheetTextAsHTML sheet =
                new XSSFExcelExtractorDecorator.SheetTextAsHTML(
                        new OfficeParserConfig(), xhtml);
        Map<String, String> values = new HashMap<>();
        for (int i = 0;
             i < XSSFExcelExtractorDecorator.WORKBOOK_VALUES_MAX_ENTRIES; i++) {
            values.put("existing:" + i, "value");
        }
        AtomicInteger limitSignals = new AtomicInteger();
        sheet.setCellValueCapture(
                values, "Sheet1", limitSignals::incrementAndGet);

        xhtml.startDocument();
        sheet.startRow(0);
        sheet.cell("A1", "hidden-payload", (XSSFCommentsShim.CommentData) null);
        sheet.endRow(0);
        xhtml.endDocument();

        assertEquals(XSSFExcelExtractorDecorator.WORKBOOK_VALUES_MAX_ENTRIES,
                values.size());
        assertEquals(1, limitSignals.get(),
                "dropping an XLM cross-sheet value must signal incomplete analysis");
    }

    @Test
    void testXlsbMacroCellCountIsBounded() throws Exception {
        XlmMacroEmulator emulator = emulatorWithLimits(
                new XlmMacroEmulator.Limits(1, 1_024, 10, 1_024, 100, 1_024));
        Biff12XlmMacrosheetParser parser =
                parserFor(emulator, new ToXMLContentHandler());
        byte[] record = formulaRecord(execFormula("payload"));

        parser.handleRecord(0x0009, record);
        parser.handleRecord(0x0009, record);
        emulator.emulate();

        assertEquals(1, emulator.iocs.size());
        assertTrue(emulator.isLimitReached());
        assertNotNull(emulator.getLimitWarning());
    }

    @Test
    void testXlsbMacroFormulaBytesAreBounded() throws Exception {
        byte[] formula = execFormula("payload");
        XlmMacroEmulator emulator = emulatorWithLimits(
                new XlmMacroEmulator.Limits(
                        10, formula.length, 10, 1_024, 100, 1_024));
        Biff12XlmMacrosheetParser parser =
                parserFor(emulator, new ToXMLContentHandler());
        byte[] record = formulaRecord(formula);

        parser.handleRecord(0x0009, record);
        parser.handleRecord(0x0009, record);
        emulator.emulate();

        assertEquals(1, emulator.iocs.size());
        assertTrue(emulator.isLimitReached());
    }

    @Test
    void testXlsbMacroFormulaBudgetIsSharedAcrossSheets() {
        byte[] formula = execFormula("payload");
        XlmMacroEmulator.Limits limits = new XlmMacroEmulator.Limits(
                1, formula.length, 10, 1_024, 100, 1_024);
        XlmMacroEmulator.DocumentBudget documentBudget =
                new XlmMacroEmulator.DocumentBudget(limits);
        XlmMacroEmulator first = new XlmMacroEmulator(
                new HashMap<>(), XlmWorkbookSheetMap.empty(), limits, documentBudget);
        XlmMacroEmulator second = new XlmMacroEmulator(
                new HashMap<>(), XlmWorkbookSheetMap.empty(), limits, documentBudget);

        assertTrue(first.addMacroCell(0, formula));
        assertFalse(second.addMacroCell(0, formula),
                "a second macro sheet must not reset the document formula budget");
        assertTrue(second.isLimitReached(),
                "rejected cross-sheet work must signal incomplete analysis");
    }

    @Test
    void testXlsbMacroOperationBudgetIsSharedAcrossSheets() {
        XlmMacroEmulator.Limits limits =
                new XlmMacroEmulator.Limits(10, 4_096, 10, 1_024, 1, 1_024);
        XlmMacroEmulator.DocumentBudget documentBudget =
                new XlmMacroEmulator.DocumentBudget(limits);
        XlmMacroEmulator first = new XlmMacroEmulator(
                new HashMap<>(), XlmWorkbookSheetMap.empty(), limits, documentBudget);
        XlmMacroEmulator second = new XlmMacroEmulator(
                new HashMap<>(), XlmWorkbookSheetMap.empty(), limits, documentBudget);
        first.addMacroCell(0, execFormula("first"));
        second.addMacroCell(0, execFormula("second"));

        first.emulate();
        second.emulate();

        assertEquals(1, first.iocs.size());
        assertTrue(second.iocs.isEmpty(),
                "a second macro sheet must not reset the document operation budget");
        assertTrue(second.isLimitReached(),
                "rejected cross-sheet work must signal incomplete analysis");
    }

    @Test
    void testXlsbMacroIocBudgetIsSharedAcrossSheets() {
        XlmMacroEmulator.Limits limits =
                new XlmMacroEmulator.Limits(10, 4_096, 1, 1_024, 100, 1_024);
        XlmMacroEmulator.DocumentBudget documentBudget =
                new XlmMacroEmulator.DocumentBudget(limits);
        XlmMacroEmulator first = new XlmMacroEmulator(
                new HashMap<>(), XlmWorkbookSheetMap.empty(), limits, documentBudget);
        XlmMacroEmulator second = new XlmMacroEmulator(
                new HashMap<>(), XlmWorkbookSheetMap.empty(), limits, documentBudget);
        first.addMacroCell(0, execFormula("first"));
        second.addMacroCell(0, execFormula("second"));

        first.emulate();
        second.emulate();

        assertEquals(1, first.iocs.size());
        assertTrue(second.iocs.isEmpty(),
                "a second macro sheet must not reset the document IOC budget");
        assertTrue(second.isLimitReached(),
                "rejected cross-sheet output must signal incomplete analysis");
    }

    @Test
    void testXlsbMacroFileContentBudgetIsSharedAcrossSheets() {
        XlmMacroEmulator.Limits limits =
                new XlmMacroEmulator.Limits(10, 4_096, 10, 1_024, 100, 2);
        XlmMacroEmulator.DocumentBudget documentBudget =
                new XlmMacroEmulator.DocumentBudget(limits);
        XlmMacroEmulator first = new XlmMacroEmulator(
                new HashMap<>(), XlmWorkbookSheetMap.empty(), limits, documentBudget);
        XlmMacroEmulator second = new XlmMacroEmulator(
                new HashMap<>(), XlmWorkbookSheetMap.empty(), limits, documentBudget);
        first.addMacroCell(0, fopenFormula("first.bin"));
        first.addMacroCell(1, fwriteFormula(0, "AB"));
        second.addMacroCell(0, fopenFormula("second.bin"));
        second.addMacroCell(1, fwriteFormula(0, "CD"));

        first.emulate();
        second.emulate();

        assertTrue(second.isLimitReached(),
                "a second macro sheet must not reset reconstructed-content retention");
    }

    @Test
    void testXlsbMacroIocsAreBounded() {
        XlmMacroEmulator emulator = emulatorWithLimits(
                new XlmMacroEmulator.Limits(10, 1_024, 1, 1_024, 100, 1_024));
        emulator.addMacroCell(0, execFormula("first"));
        emulator.addMacroCell(1, execFormula("second"));

        emulator.emulate();

        assertEquals(1, emulator.iocs.size());
        assertTrue(emulator.isLimitReached());
    }

    @Test
    void testXlsbRejectedFopenDoesNotRetainAnotherFileHandle() {
        Biff12XlmFormulaDecoder.EvalContext context =
                new Biff12XlmFormulaDecoder.EvalContext(
                        new HashMap<>(), new HashMap<>(), 1, 1_024, 1_024);

        Biff12XlmFormulaDecoder.evaluateFormula(fopenFormula("first.bin"), context);
        Biff12XlmFormulaDecoder.evaluateFormula(fopenFormula("second.bin"), context);

        assertEquals(1, context.filePaths.size());
        assertEquals(1, context.fileContents.size());
        assertTrue(context.isLimitReached());
    }

    @Test
    void testXlsbUnmatchedForCellUsesOperationBudget() {
        XlmMacroEmulator emulator = emulatorWithLimits(
                new XlmMacroEmulator.Limits(10, 4_096, 10, 1_024, 5, 1_024));
        for (int i = 0; i < 4; i++) {
            emulator.addMacroCell(i, forCellFormula());
        }

        assertTimeoutPreemptively(Duration.ofSeconds(1), emulator::emulate);

        assertTrue(emulator.isLimitReached(),
                "unmatched FOR.CELL scans must stop at the operation budget");
    }

    @Test
    void testXlsbMacroWriteLimitIsNotSwallowed() throws Exception {
        XlmMacroEmulator emulator = emulatorWithLimits(
                new XlmMacroEmulator.Limits(10, 1_024, 10, 1_024, 100, 1_024));
        Biff12XlmMacrosheetParser parser =
                parserFor(emulator, new WriteLimitHandler());

        RuntimeSAXException thrown = assertThrows(RuntimeSAXException.class,
                () -> parser.handleRecord(0x0009, formulaRecord(execFormula("payload"))));

        assertTrue(WriteLimitReachedException.isWriteLimitReached(thrown));
    }

    @Test
    void testXmlMacrosheetWriteLimitEscapesOpportunisticCatch() throws Exception {
        String xml = """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData><row r="1"><c r="A1"><f>EXEC("payload")</f></c></row></sheetData>
                </worksheet>
                """;
        Metadata metadata = new Metadata();
        ParseContext parseContext = new ParseContext();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                new FormulaWriteLimitHandler(), metadata, parseContext);
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            PackagePart macroPart = opcPackage.createPart(
                    PackagingURIHelper.createPartName("/xl/macrosheets/sheet1.xml"),
                    XSSFRelation.MACRO_SHEET_XML.getContentType());
            try (OutputStream stream = macroPart.getOutputStream()) {
                stream.write(xml.getBytes(StandardCharsets.UTF_8));
            }
            XSSFExcelExtractorDecorator decorator =
                    new XSSFExcelExtractorDecorator(parseContext, opcPackage, Locale.ROOT);
            decorator.metadata = metadata;
            Method process = XSSFExcelExtractorDecorator.class.getDeclaredMethod(
                    "processXlmXmlMacroSheets", OPCPackage.class,
                    XHTMLContentHandler.class, XSSFSharedStringsShim.class);
            process.setAccessible(true);

            xhtml.startDocument();
            InvocationTargetException thrown =
                    assertThrows(InvocationTargetException.class,
                            () -> process.invoke(decorator, opcPackage, xhtml, null));

            assertTrue(WriteLimitReachedException.isWriteLimitReached(thrown));
        }
    }

    @Test
    void testXmlMacrosheetSecurityExceptionEscapesOpportunisticCatch()
            throws Exception {
        String xml = """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData><row r="1"><c r="A1"><f>EXEC("payload")</f></c></row></sheetData>
                </worksheet>
                """;
        Metadata metadata = new Metadata();
        ParseContext parseContext = new ParseContext();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                new FormulaSecurityExceptionHandler(), metadata, parseContext);
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            PackagePart macroPart = opcPackage.createPart(
                    PackagingURIHelper.createPartName(
                            "/xl/macrosheets/sheet1.xml"),
                    XSSFRelation.MACRO_SHEET_XML.getContentType());
            try (OutputStream stream = macroPart.getOutputStream()) {
                stream.write(xml.getBytes(StandardCharsets.UTF_8));
            }
            XSSFExcelExtractorDecorator decorator =
                    new XSSFExcelExtractorDecorator(
                            parseContext, opcPackage, Locale.ROOT);
            decorator.metadata = metadata;
            Method process = XSSFExcelExtractorDecorator.class.getDeclaredMethod(
                    "processXlmXmlMacroSheets", OPCPackage.class,
                    XHTMLContentHandler.class, XSSFSharedStringsShim.class);
            process.setAccessible(true);

            xhtml.startDocument();
            InvocationTargetException thrown =
                    assertThrows(InvocationTargetException.class,
                            () -> process.invoke(
                                    decorator, opcPackage, xhtml, null));

            assertTrue(thrown.getCause() instanceof SecurityException);
        }
    }

    @Test
    void testXmlMacrosheetHandlerAbortEscapesOpportunisticCatch()
            throws Exception {
        String xml = """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData><row r="1"><c r="A1"><f>EXEC("payload")</f></c></row></sheetData>
                </worksheet>
                """;
        SAXException denial =
                new SAXException("simulated XLM output policy abort");
        Metadata metadata = new Metadata();
        ParseContext parseContext = new ParseContext();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                new FormulaOneShotSaxExceptionHandler(denial),
                metadata, parseContext);
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            PackagePart macroPart = opcPackage.createPart(
                    PackagingURIHelper.createPartName(
                            "/xl/macrosheets/sheet1.xml"),
                    XSSFRelation.MACRO_SHEET_XML.getContentType());
            try (OutputStream stream = macroPart.getOutputStream()) {
                stream.write(xml.getBytes(StandardCharsets.UTF_8));
            }
            XSSFExcelExtractorDecorator decorator =
                    new XSSFExcelExtractorDecorator(
                            parseContext, opcPackage, Locale.ROOT);
            decorator.metadata = metadata;
            Method process = XSSFExcelExtractorDecorator.class.getDeclaredMethod(
                    "processXlmXmlMacroSheets", OPCPackage.class,
                    XHTMLContentHandler.class, XSSFSharedStringsShim.class);
            process.setAccessible(true);

            xhtml.startDocument();
            InvocationTargetException thrown =
                    assertThrows(InvocationTargetException.class,
                            () -> process.invoke(
                                    decorator, opcPackage, xhtml, null));

            assertEquals(denial, thrown.getCause());
        }
    }

    @Test
    void testXlsbSheetSecurityExceptionEscapesRecoveryCatch()
            throws Exception {
        ParseContext context = new ParseContext();
        context.set(Locale.class, Locale.US);
        Metadata metadata = new Metadata();
        metadata.set(Metadata.CONTENT_TYPE,
                "application/vnd.ms-excel.sheet.binary.macroenabled.12");
        try (InputStream input = XlmCaptureBoundsTest.class.getResourceAsStream(
                "/test-documents/testEXCEL.xlsb");
             TikaInputStream stream = TikaInputStream.get(input)) {
            assertThrows(SecurityException.class,
                    () -> new OOXMLParser().parse(
                            stream, new TableCellSecurityExceptionHandler(),
                            metadata, context));
        }
    }

    @Test
    void testXlsbRuntimeSaxDenialPropagates() throws Exception {
        SAXException denial =
                new SAXException("simulated XLSB output policy denial");
        XlsbFailStopSaxHandler handler =
                new XlsbFailStopSaxHandler(denial);
        ParseContext context = new ParseContext();
        context.set(Locale.class, Locale.US);
        Metadata metadata = new Metadata();
        metadata.set(Metadata.CONTENT_TYPE,
                "application/vnd.ms-excel.sheet.binary.macroenabled.12");

        SAXException thrown;
        try (InputStream input = XlmCaptureBoundsTest.class.getResourceAsStream(
                "/test-documents/testEXCEL.xlsb");
             TikaInputStream stream = TikaInputStream.get(input)) {
            thrown = assertThrows(SAXException.class,
                    () -> new OOXMLParser().parse(
                            stream, handler, metadata, context));
        }

        assertSame(denial, thrown);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    void testXmlMacroPartCountIsBoundedAndSignaled() throws Exception {
        String xml = """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData/>
                </worksheet>
                """;
        Metadata metadata = new Metadata();
        ParseContext parseContext = new ParseContext();
        ToXMLContentHandler output = new ToXMLContentHandler();
        XHTMLContentHandler xhtml =
                new XHTMLContentHandler(output, metadata, parseContext);
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            for (int i = 0; i < 129; i++) {
                PackagePart macroPart = opcPackage.createPart(
                        PackagingURIHelper.createPartName(
                                "/xl/macrosheets/sheet" + i + ".xml"),
                        XSSFRelation.MACRO_SHEET_XML.getContentType());
                try (OutputStream stream = macroPart.getOutputStream()) {
                    stream.write(xml.getBytes(StandardCharsets.UTF_8));
                }
            }
            XSSFExcelExtractorDecorator decorator =
                    new XSSFExcelExtractorDecorator(
                            parseContext, opcPackage, Locale.ROOT);
            decorator.metadata = metadata;
            Method process = XSSFExcelExtractorDecorator.class.getDeclaredMethod(
                    "processXlmXmlMacroSheets", OPCPackage.class,
                    XHTMLContentHandler.class, XSSFSharedStringsShim.class);
            process.setAccessible(true);

            xhtml.startDocument();
            process.invoke(decorator, opcPackage, xhtml, null);
            xhtml.endDocument();
        }

        assertEquals("true",
                metadata.get("msoffice:xlm-capture-limit-reached"));
        assertEquals("true",
                metadata.get(TikaCoreProperties.TRUNCATED_METADATA));
        assertEquals(128,
                output.toString().split("class=\"xlm-macrosheet\"", -1).length - 1,
                "the parser must stop at the workbook-wide macro-part budget");
    }

    @Test
    void testXlsbMacroPartCountIsBoundedAndSignaled() throws Exception {
        Metadata metadata = new Metadata();
        ParseContext parseContext = new ParseContext();
        ToXMLContentHandler output = new ToXMLContentHandler();
        XHTMLContentHandler xhtml =
                new XHTMLContentHandler(output, metadata, parseContext);
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            for (int i = 0; i < 129; i++) {
                opcPackage.createPart(
                        PackagingURIHelper.createPartName(
                                "/xl/macrosheets/sheet" + i + ".bin"),
                        XSSFRelation.MACRO_SHEET_BIN.getContentType());
            }
            XSSFBExcelExtractorDecorator decorator =
                    new XSSFBExcelExtractorDecorator(
                            parseContext, opcPackage, Locale.ROOT);
            decorator.metadata = metadata;
            Method process = XSSFBExcelExtractorDecorator.class.getDeclaredMethod(
                    "processXlmBinaryMacroSheets", OPCPackage.class,
                    XSSFBStylesTable.class, TikaXSSFBSharedStringsTable.class,
                    XHTMLContentHandler.class, Map.class, XlmWorkbookSheetMap.class);
            process.setAccessible(true);

            xhtml.startDocument();
            process.invoke(decorator, opcPackage, null, null, xhtml,
                    new HashMap<>(), XlmWorkbookSheetMap.empty());
            xhtml.endDocument();

            assertEquals("true",
                    metadata.get("msoffice:xlm-capture-limit-reached"));
            assertEquals("true",
                    metadata.get(TikaCoreProperties.TRUNCATED_METADATA));
            assertTrue(output.toString().contains(">sheet0<"));
            assertFalse(output.toString().contains(">sheet128<"),
                    "the parser must stop before processing every attacker-controlled part");
        }
    }

    @Test
    void testXlmInputBudgetStopsBeforeOverCapMacroPart()
            throws Exception {
        Metadata metadata = new Metadata();
        String output = processPaddedXmlMacroParts(
                metadata, INPUT_BUDGET_BYTES, true);

        assertTrue(output.contains("exact-cap-4"),
                "content ending exactly at the input cap must be captured");
        assertFalse(output.contains("over-cap-value"),
                "content beyond the shared input cap must not be captured");
        assertEquals("true",
                metadata.get("msoffice:xlm-capture-limit-reached"));
        assertEquals("true",
                metadata.get(TikaCoreProperties.TRUNCATED_METADATA));
        assertNotNull(metadata.get(
                TikaCoreProperties.TIKA_META_EXCEPTION_WARNING));
    }

    @Test
    void testXlmInputBudgetAcceptsExactCap() throws Exception {
        Metadata metadata = new Metadata();
        String output = processPaddedXmlMacroParts(
                metadata, INPUT_BUDGET_BYTES, false);

        assertTrue(output.contains("exact-cap-4"));
        assertEquals(null,
                metadata.get("msoffice:xlm-capture-limit-reached"));
        assertEquals(null, metadata.get(TikaCoreProperties.TRUNCATED_METADATA));
    }

    @Test
    void testXlsbInputBudgetIsSharedFromWorksheetsToExactCapMacro()
            throws Exception {
        Metadata metadata = new Metadata();
        String output = processPaddedBinaryWorksheetAndMacroParts(
                metadata, false);

        assertTrue(output.contains("exact-binary-cap"),
                "the macro record ending at the shared cap must be captured");
        assertEquals(null,
                metadata.get("msoffice:xlm-capture-limit-reached"));
        assertEquals(null, metadata.get(TikaCoreProperties.TRUNCATED_METADATA));
    }

    @Test
    void testXlsbInputBudgetStopsAfterSharedWorksheetAndMacroCap()
            throws Exception {
        Metadata metadata = new Metadata();
        String output = processPaddedBinaryWorksheetAndMacroParts(
                metadata, true);

        assertTrue(output.contains("exact-binary-cap"));
        assertFalse(output.contains("over-binary-cap"),
                "a later binary macro part must not reset the worksheet budget");
        assertEquals("true",
                metadata.get("msoffice:xlm-capture-limit-reached"));
        assertEquals("true",
                metadata.get(TikaCoreProperties.TRUNCATED_METADATA));
        assertNotNull(metadata.get(
                TikaCoreProperties.TIKA_META_EXCEPTION_WARNING));
    }

    private static String processPaddedXmlMacroParts(
            Metadata metadata, int totalBytes, boolean addOverCapPart)
            throws Exception {
        ParseContext parseContext = new ParseContext();
        ToXMLContentHandler output = new ToXMLContentHandler();
        XHTMLContentHandler xhtml =
                new XHTMLContentHandler(output, metadata, parseContext);
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            int fullParts = totalBytes / INPUT_PART_BYTES;
            for (int i = 1; i <= fullParts; i++) {
                createXmlMacroPart(
                        opcPackage, "sheet" + i,
                        paddedMacrosheetXml(
                                "exact-cap-" + i, INPUT_PART_BYTES));
            }
            if (addOverCapPart) {
                createXmlMacroPart(
                        opcPackage, "zz-over-cap",
                        paddedMacrosheetXml("over-cap-value", 1_024));
            }
            XSSFExcelExtractorDecorator decorator =
                    new XSSFExcelExtractorDecorator(
                            parseContext, opcPackage, Locale.ROOT);
            decorator.metadata = metadata;
            Method process = XSSFExcelExtractorDecorator.class.getDeclaredMethod(
                    "processXlmXmlMacroSheets", OPCPackage.class,
                    XHTMLContentHandler.class, XSSFSharedStringsShim.class);
            process.setAccessible(true);

            xhtml.startDocument();
            process.invoke(decorator, opcPackage, xhtml, null);
            xhtml.endDocument();
        }
        return output.toString();
    }

    @SuppressWarnings("unchecked")
    private static String processPaddedBinaryWorksheetAndMacroParts(
            Metadata metadata, boolean addOverCapPart) throws Exception {
        ParseContext parseContext = new ParseContext();
        ToXMLContentHandler output = new ToXMLContentHandler();
        XHTMLContentHandler xhtml =
                new XHTMLContentHandler(output, metadata, parseContext);
        try (InputStream template = XlmCaptureBoundsTest.class.getResourceAsStream(
                "/test-documents/testEXCEL.xlsb");
             OPCPackage opcPackage = OPCPackage.open(template)) {
            boolean firstWorksheet = true;
            for (PackagePart worksheet : opcPackage.getPartsByContentType(
                    "application/vnd.ms-excel.worksheet")) {
                try (OutputStream stream = worksheet.getOutputStream()) {
                    writePaddedBiffStream(
                            stream,
                            firstWorksheet ? 3 * INPUT_PART_BYTES : 0,
                            -1, null);
                }
                firstWorksheet = false;
            }
            assertFalse(firstWorksheet,
                    "the XLSB template must contain a worksheet");

            createBinaryMacroPart(
                    opcPackage, "sheet-exact", INPUT_PART_BYTES,
                    "exact-binary-cap");
            if (addOverCapPart) {
                createBinaryMacroPart(
                        opcPackage, "zz-over-cap", 1_024,
                        "over-binary-cap");
            }

            XSSFBExcelExtractorDecorator decorator =
                    new XSSFBExcelExtractorDecorator(
                            parseContext, opcPackage, Locale.ROOT);
            decorator.metadata = metadata;
            Method capture = XSSFBExcelExtractorDecorator.class
                    .getDeclaredMethod(
                            "captureWorksheetValues", OPCPackage.class,
                            Metadata.class,
                            XSSFExcelExtractorDecorator.XlmInputBudget.class);
            capture.setAccessible(true);
            Map<String, Double> values = (Map<String, Double>) capture.invoke(
                    null, opcPackage, metadata, decorator.xlmInputBudget);

            Method process = XSSFBExcelExtractorDecorator.class
                    .getDeclaredMethod(
                            "processXlmBinaryMacroSheets", OPCPackage.class,
                            XSSFBStylesTable.class,
                            TikaXSSFBSharedStringsTable.class,
                            XHTMLContentHandler.class, Map.class,
                            XlmWorkbookSheetMap.class);
            process.setAccessible(true);

            xhtml.startDocument();
            process.invoke(
                    decorator, opcPackage, null, null, xhtml, values,
                    XlmWorkbookSheetMap.empty());
            xhtml.endDocument();
        }
        return output.toString();
    }

    private static void createXmlMacroPart(
            OPCPackage opcPackage, String sheetName, String xml)
            throws Exception {
        PackagePart macroPart = opcPackage.createPart(
                PackagingURIHelper.createPartName(
                        "/xl/macrosheets/" + sheetName + ".xml"),
                XSSFRelation.MACRO_SHEET_XML.getContentType());
        try (OutputStream stream = macroPart.getOutputStream()) {
            stream.write(xml.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void createBinaryMacroPart(
            OPCPackage opcPackage, String sheetName, int byteLength,
            String formulaValue) throws Exception {
        PackagePart macroPart = opcPackage.createPart(
                PackagingURIHelper.createPartName(
                        "/xl/macrosheets/" + sheetName + ".bin"),
                XSSFRelation.MACRO_SHEET_BIN.getContentType());
        try (OutputStream stream = macroPart.getOutputStream()) {
            writePaddedBiffStream(
                    stream, byteLength, 0x0009,
                    formulaRecord(execFormula(formulaValue)));
        }
    }

    private static void writePaddedBiffStream(
            OutputStream stream, int byteLength, int terminalRecordType,
            byte[] terminalRecord) throws Exception {
        int terminalLength = terminalRecord == null
                ? 0 : biffRecordLength(terminalRecordType, terminalRecord.length);
        int paddingLength = byteLength - terminalLength;
        if (paddingLength < 0) {
            throw new IllegalArgumentException("part length is too small");
        }
        byte[] zeroBuffer = new byte[8_192];
        while (paddingLength > 0) {
            int payloadLength = Math.min(900_000, Math.max(0, paddingLength - 2));
            while (biffRecordLength(0x007f, payloadLength) > paddingLength) {
                payloadLength--;
            }
            int recordLength = biffRecordLength(0x007f, payloadLength);
            if (paddingLength - recordLength == 1) {
                payloadLength--;
                recordLength = biffRecordLength(0x007f, payloadLength);
            }
            writeBiffRecordHeader(stream, 0x007f, payloadLength);
            int remainingPayload = payloadLength;
            while (remainingPayload > 0) {
                int chunk = Math.min(remainingPayload, zeroBuffer.length);
                stream.write(zeroBuffer, 0, chunk);
                remainingPayload -= chunk;
            }
            paddingLength -= recordLength;
        }
        if (terminalRecord != null) {
            writeBiffRecordHeader(
                    stream, terminalRecordType, terminalRecord.length);
            stream.write(terminalRecord);
        }
    }

    private static int biffRecordLength(int type, int payloadLength) {
        return biffVarIntLength(type)
                + biffVarIntLength(payloadLength) + payloadLength;
    }

    private static int biffVarIntLength(int value) {
        int length = 1;
        while ((value >>>= 7) != 0) {
            length++;
        }
        return length;
    }

    private static void writeBiffRecordHeader(
            OutputStream stream, int type, int payloadLength) throws Exception {
        writeBiffVarInt(stream, type);
        writeBiffVarInt(stream, payloadLength);
    }

    private static void writeBiffVarInt(OutputStream stream, int value)
            throws Exception {
        do {
            int current = value & 0x7f;
            value >>>= 7;
            stream.write(value == 0 ? current : current | 0x80);
        } while (value != 0);
    }

    private static String paddedMacrosheetXml(
            String value, int byteLength) {
        String prefix = "<worksheet xmlns=\""
                + "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                + "\"><sheetData>";
        String suffix = "<row r=\"1\"><c r=\"A1\"><f>" + value
                + "</f></c></row></sheetData></worksheet>";
        int paddingLength = byteLength - prefix.length() - suffix.length();
        if (paddingLength < 0) {
            throw new IllegalArgumentException("part length is too small");
        }
        return prefix + " ".repeat(paddingLength) + suffix;
    }

    private static XlmMacroEmulator emulatorWithLimits(XlmMacroEmulator.Limits limits) {
        return new XlmMacroEmulator(new HashMap<>(), XlmWorkbookSheetMap.empty(), limits);
    }

    private static Biff12XlmMacrosheetParser parserFor(
            XlmMacroEmulator emulator, DefaultHandler handler) throws SAXException {
        Metadata metadata = new Metadata();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                handler, metadata, new ParseContext());
        xhtml.startDocument();
        return new Biff12XlmMacrosheetParser(
                new ByteArrayInputStream(new byte[0]), xhtml, emulator);
    }

    private static byte[] formulaRecord(byte[] formula) {
        return ByteBuffer.allocate(22 + formula.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0)
                .putInt(0)
                .putDouble(0)
                .putShort((short) 0)
                .putInt(formula.length)
                .put(formula)
                .array();
    }

    private static byte[] execFormula(String command) {
        byte[] commandBytes = command.getBytes(StandardCharsets.UTF_16LE);
        return ByteBuffer.allocate(1 + 2 + commandBytes.length + 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 0x17)
                .putShort((short) command.length())
                .put(commandBytes)
                .put((byte) 0x22)
                .put((byte) 1)
                .putShort((short) 0x006e)
                .array();
    }

    private static byte[] fopenFormula(String path) {
        byte[] pathBytes = path.getBytes(StandardCharsets.UTF_16LE);
        return ByteBuffer.allocate(1 + 2 + pathBytes.length + 3 + 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 0x17)
                .putShort((short) path.length())
                .put(pathBytes)
                .put((byte) 0x1e)
                .putShort((short) 3)
                .put((byte) 0x22)
                .put((byte) 2)
                .putShort((short) 0x0084)
                .array();
    }

    private static byte[] fwriteFormula(int handle, String text) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_16LE);
        return ByteBuffer.allocate(3 + 1 + 2 + textBytes.length + 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 0x1e)
                .putShort((short) handle)
                .put((byte) 0x17)
                .putShort((short) text.length())
                .put(textBytes)
                .put((byte) 0x22)
                .put((byte) 2)
                .putShort((short) 0x008a)
                .array();
    }

    private static byte[] forCellFormula() {
        byte[] variable = "i".getBytes(StandardCharsets.UTF_16LE);
        byte[] range = "A1:A1".getBytes(StandardCharsets.UTF_16LE);
        return ByteBuffer.allocate(1 + 2 + variable.length
                        + 1 + 2 + range.length + 3 + 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 0x17)
                .putShort((short) 1)
                .put(variable)
                .put((byte) 0x17)
                .putShort((short) 5)
                .put(range)
                .put((byte) 0x1e)
                .putShort((short) 1)
                .put((byte) 0x22)
                .put((byte) 3)
                .putShort((short) 0x00e2)
                .array();
    }

    private static final class WriteLimitHandler extends DefaultHandler {
        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            throw new WriteLimitReachedException(0);
        }
    }

    private static final class FormulaWriteLimitHandler extends DefaultHandler {
        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            String text = new String(ch, start, length);
            if (text.contains("EXEC")) {
                throw new WriteLimitReachedException(0);
            }
        }
    }

    private static final class FormulaSecurityExceptionHandler
            extends DefaultHandler {

        @Override
        public void characters(char[] ch, int start, int length) {
            if (new String(ch, start, length).contains("EXEC")) {
                throw new SecurityException(
                        "simulated XLM formula policy denial");
            }
        }
    }

    private static final class FormulaOneShotSaxExceptionHandler
            extends DefaultHandler {

        private final SAXException denial;
        private boolean denied;

        private FormulaOneShotSaxExceptionHandler(SAXException denial) {
            this.denial = denial;
        }

        @Override
        public void characters(char[] ch, int start, int length)
                throws SAXException {
            if (!denied
                    && new String(ch, start, length).contains("EXEC")) {
                denied = true;
                throw denial;
            }
        }
    }

    private static final class TableCellSecurityExceptionHandler
            extends DefaultHandler {

        private boolean tableCell;

        @Override
        public void startElement(String uri, String localName, String qName,
                                 Attributes attributes) {
            tableCell = "td".equals(localName) || "td".equals(qName);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if ("td".equals(localName) || "td".equals(qName)) {
                tableCell = false;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (tableCell && length > 0) {
                throw new SecurityException(
                        "simulated XLSB sheet policy denial");
            }
        }
    }

    private static final class XlsbFailStopSaxHandler
            extends DefaultHandler {

        private final SAXException denial;
        private boolean tableCell;
        private boolean denied;
        private int callbacksAfterDenial;

        private XlsbFailStopSaxHandler(SAXException denial) {
            this.denial = denial;
        }

        @Override
        public void startDocument() throws SAXException {
            rejectAfterDenial();
        }

        @Override
        public void startPrefixMapping(String prefix, String uri)
                throws SAXException {
            rejectAfterDenial();
        }

        @Override
        public void startElement(
                String uri, String localName, String qName,
                Attributes attributes) throws SAXException {
            rejectAfterDenial();
            tableCell = "td".equals(localName) || "td".equals(qName);
        }

        @Override
        public void characters(char[] ch, int start, int length)
                throws SAXException {
            rejectAfterDenial();
            if (tableCell && length > 0) {
                denied = true;
                throw denial;
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName)
                throws SAXException {
            rejectAfterDenial();
            if ("td".equals(localName) || "td".equals(qName)) {
                tableCell = false;
            }
        }

        @Override
        public void endPrefixMapping(String prefix) throws SAXException {
            rejectAfterDenial();
        }

        @Override
        public void endDocument() throws SAXException {
            rejectAfterDenial();
        }

        private void rejectAfterDenial() throws SAXException {
            if (denied) {
                callbacksAfterDenial++;
                throw new SAXException("callback delivered after denial");
            }
        }
    }
}
