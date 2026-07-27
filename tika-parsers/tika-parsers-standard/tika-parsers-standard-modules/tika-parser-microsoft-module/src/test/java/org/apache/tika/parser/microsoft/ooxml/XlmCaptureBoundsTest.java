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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.apache.poi.xssf.usermodel.XSSFRelation;
import org.junit.jupiter.api.Test;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.exception.RuntimeSAXException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.microsoft.OfficeParserConfig;
import org.apache.tika.sax.ToXMLContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;

class XlmCaptureBoundsTest {

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
}
