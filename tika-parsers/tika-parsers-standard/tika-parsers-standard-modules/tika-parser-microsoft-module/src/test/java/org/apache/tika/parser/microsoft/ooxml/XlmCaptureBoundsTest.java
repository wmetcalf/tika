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
import java.util.List;
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

    private static XlmXmlMacrosheetParser parseOneFormula(String formula) throws Exception {
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
        return parser;
    }

    /**
     * A macrosheet FORMULA must not be bound by the data-sheet cell-VALUE cap.
     *
     * <p>Regression guard: formulas once shared {@code WORKBOOK_VALUE_MAX_LEN} (1 KB, sized
     * for URL/IP IOC fragments) with cell values. Obfuscated XLM droppers concatenate the
     * entire payload into ONE formula -- measured p90=2748, p95=5099 chars over the
     * malicious-document corpus -- so that cap silently amputated 22% of macro-bearing
     * documents mid-formula, leaving a prefix that still read as a complete formula.
     */
    @Test
    void testFormulaLongerThanTheCellValueCapIsRetainedWhole() throws Exception {
        int len = XSSFExcelExtractorDecorator.WORKBOOK_VALUE_MAX_LEN * 4;
        assertTrue(len < XSSFExcelExtractorDecorator.XLM_FORMULA_MAX_LEN,
                "test fixture must sit above the value cap but below the formula cap");
        XlmXmlMacrosheetParser parser = parseOneFormula("A".repeat(len));

        assertEquals(len, parser.getFormulas().get("Macro1:1:A1").length(),
                "a formula between the value cap and the formula cap must be kept WHOLE -- "
                        + "truncating it here is the macro-payload-loss regression");
        assertFalse(parser.isTruncated(),
                "keeping a legitimate formula must not report truncation");
    }

    /**
     * The truncation marker must NOT leak from an over-cap cell onto the NEXT cell's
     * intact formula.
     *
     * <p>Regression guard for an evidence-FABRICATION bug: {@code flushCell()} reset the
     * per-cell truncation flag AFTER an {@code if (currentCellRef == null) return;} early
     * return. The {@code r} attribute on {@code <c>} is optional in ECMA-376 and fully
     * attacker-controlled, so a dropper could put its over-cap formula in an r-less cell
     * and leave the flag set -- causing the following cell's COMPLETE formula to be
     * recorded with a truncation marker it never earned. An analyst then sees fabricated
     * evidence loss on a formula that was captured whole.
     */
    @Test
    void testTruncationMarkerDoesNotLeakFromAnRlessCellOntoTheNextFormula() throws Exception {
        String overCap = "A".repeat(XSSFExcelExtractorDecorator.XLM_FORMULA_MAX_LEN + 1);
        String intact = "=EXEC(\"calc.exe\")";
        // first <c> deliberately has NO r attribute -- the attacker-controlled early-return path
        String xml = String.format(Locale.ROOT, """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData><row r="1">
                    <c><f>%s</f></c>
                    <c r="B1"><f>%s</f></c>
                  </row></sheetData>
                </worksheet>
                """, overCap, intact);
        Metadata metadata = new Metadata();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                new ToXMLContentHandler(), metadata, new ParseContext());
        XlmXmlMacrosheetParser parser = new XlmXmlMacrosheetParser(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                xhtml, "Macro1", null);
        xhtml.startDocument();
        parser.parse();
        xhtml.endDocument();

        String recorded = parser.getFormulas().get("Macro1:1:B1");
        assertNotNull(recorded, "the second cell's formula must still be captured");
        assertEquals(intact, recorded,
                "B1 was captured WHOLE -- marking it truncated fabricates evidence loss "
                        + "that never happened");
    }

    /**
     * The truncation marker must not corrupt indicators extracted from a marked formula.
     *
     * <p>Regression guard: the marker was appended with no separator, and the IOC URL
     * pattern excludes whitespace but not {@code '['} -- so a URL sitting at the cut point
     * came out as {@code http://evil.example.com/pay[...TIKA-XLM-FORMULA-TRUNCATED]}. A
     * blocklist or threat-intel pipeline built from that indicator can never match the
     * real C2, and the corruption is invisible downstream.
     */
    @Test
    void testTruncationMarkerIsNotAbsorbedIntoAnExtractedIndicator() throws Exception {
        String url = "http://evil.example.com/payload.exe";
        // The URL pattern carries a (?<![\w.]) lookbehind, so the character preceding the
        // scheme must be a non-word one -- padding straight up against "http" matches
        // nothing and would make the assertion below vacuous.
        String lead = "'";
        int pad = XSSFExcelExtractorDecorator.XLM_FORMULA_MAX_LEN
                - url.length() - lead.length();
        XlmXmlMacrosheetParser parser =
                parseOneFormula("A".repeat(pad) + lead + url + "TRAILING");

        String recorded = parser.getFormulas().get("Macro1:1:A1");
        assertTrue(recorded.contains("[...TIKA-XLM-FORMULA-TRUNCATED]"),
                "fixture must actually trip the formula cap");
        List<String> iocs =
                XlmXmlIocScanner.scan(parser.getFormulas(), parser.getValues());
        assertTrue(iocs.stream().anyMatch(i -> i.contains("evil.example.com")),
                "fixture must actually yield the indicator, else this test is vacuous; "
                        + "got: " + iocs);
        for (String ioc : iocs) {
            assertFalse(ioc.contains("TIKA-XLM-FORMULA-TRUNCATED"),
                    "the truncation marker must never be absorbed into an extracted "
                            + "indicator -- got: " + ioc);
        }
    }

    /**
     * The aggregate formula budget must account for the truncation marker it appends.
     *
     * <p>Regression guard: {@code projected} read the {@code formulaWasTruncated} FIELD,
     * which flushCell() clears at its top, so it was always false by then. The marker's
     * length therefore escaped the budget check and an operator-configured aggregate cap
     * could be overrun by (marker + separator) on every truncated formula.
     */
    @Test
    void testAggregateBudgetAccountsForTheAppendedTruncationMarker() throws Exception {
        int formulaCap = XSSFExcelExtractorDecorator.XLM_FORMULA_MAX_LEN;
        // budget admits the cut formula but NOT the marker the cut forces us to append
        int aggregate = formulaCap + 4;
        String xml = String.format(Locale.ROOT, """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData><row r="1"><c r="A1"><f>%s</f></c></row></sheetData>
                </worksheet>
                """, "A".repeat(formulaCap + 1));
        Metadata metadata = new Metadata();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                new ToXMLContentHandler(), metadata, new ParseContext());
        XlmXmlMacrosheetParser parser = new XlmXmlMacrosheetParser(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                xhtml, "Macro1", null, 1_000, 1_000, 0, 0, aggregate);
        xhtml.startDocument();
        parser.parse();
        xhtml.endDocument();

        int retained = parser.getFormulas().values().stream().mapToInt(String::length).sum();
        assertTrue(retained <= aggregate,
                "retained formula text (" + retained + ") must not exceed the configured "
                        + "aggregate budget (" + aggregate + ") -- the appended marker has "
                        + "to be counted by the check that admits the formula");
    }

    /**
     * A reconstructed-file-content preview must be cut to the IOC budget, not emitted
     * oversized and dropped.
     *
     * <p>Regression guard: {@code retainIoc}/{@code addIoc} reject an over-budget entry
     * WHOLE rather than keeping a prefix. Once maxFileContentChars (10 MB) rose above
     * maxIocChars (1 MB), an unbounded preview lost the entire FILE_CONTENT entry --
     * strictly LESS evidence than the hardcoded 300/8192-char cut it replaced. The point
     * of raising the cap was more payload reaching the analyst, not none.
     */
    @Test
    void testFileContentPreviewIsCutToTheIocBudgetRatherThanDropped() {
        // file-content cap far above the IOC allowance -- the mismatch under test
        // maxIocChars = 512 sits far below maxFileContentChars = 1_000_000 -- the mismatch
        // under test. Payload is written through real FOPEN/FWRITE emulation.
        XlmMacroEmulator emulator = emulatorWithLimits(
                new XlmMacroEmulator.Limits(100, 100_000, 100, 512, 100_000, 1_000_000));
        emulator.addMacroCell(0, fopenFormula("dropper.bin"));
        emulator.addMacroCell(1,
                fwriteFormula(0, "http://evil.example.com/payload.exe" + "B".repeat(2_000)));
        emulator.emulate();

        String fileContent = emulator.iocs.stream()
                .filter(i -> i.startsWith("FILE_CONTENT"))
                .findFirst().orElse(null);
        assertNotNull(fileContent,
                "an oversized reconstructed payload must still yield a bounded FILE_CONTENT "
                        + "entry -- dropping it whole loses the dropper's URL entirely");
        assertTrue(fileContent.contains("evil.example.com"),
                "the retained prefix must carry the indicator at the head of the payload");
    }

    /** The formula cap still bounds a pathological formula -- and says so unmistakably. */
    @Test
    void testFormulaOverTheFormulaCapIsBoundedAndExplicitlyMarked() throws Exception {
        XlmXmlMacrosheetParser parser = parseOneFormula(
                "A".repeat(XSSFExcelExtractorDecorator.XLM_FORMULA_MAX_LEN + 1));

        String recorded = parser.getFormulas().get("Macro1:1:A1");
        // +1 for the space that separates the marker from the payload -- without it the
        // marker is absorbed into any indicator sitting at the cut point.
        assertTrue(recorded.length()
                        <= XSSFExcelExtractorDecorator.XLM_FORMULA_MAX_LEN
                        + 1 + "[...TIKA-XLM-FORMULA-TRUNCATED]".length(),
                "the formula cap must still bound a pathological formula");
        assertTrue(recorded.endsWith("[...TIKA-XLM-FORMULA-TRUNCATED]"),
                "a cut formula is no longer valid syntax and a bare prefix reads as complete "
                        + "downstream -- it MUST carry an explicit truncation marker");
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

    /**
     * An XLSB formula record dropped for exceeding the size bound must SIGNAL the drop.
     *
     * <p>Regression guard: the oversize branch was a bare {@code return} against a
     * hardcoded 65536. An entire macro formula vanished with no metadata flag, no warning
     * and no XHTML trace -- the analyst sees a short, clean macro and has no way to know
     * the payload-bearing record was withheld. Silent evidence loss is the same defect
     * class as silent truncation, just total rather than partial.
     */
    @Test
    void testXlsbOversizeFormulaRecordDropIsReportedNotSilent() throws Exception {
        AtomicInteger dropped = new AtomicInteger();
        Metadata metadata = new Metadata();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                new ToXMLContentHandler(), metadata, new ParseContext());
        xhtml.startDocument();
        // bound of 64 bytes so the fixture stays small; the mechanism is size-independent
        Biff12XlmMacrosheetParser parser = new Biff12XlmMacrosheetParser(
                new ByteArrayInputStream(new byte[0]), xhtml, null, 64,
                dropped::incrementAndGet);

        parser.handleRecord(0x0009, formulaRecord(execFormula("A".repeat(256))));

        assertEquals(1, dropped.get(),
                "an oversize formula record must report the drop -- dropping a whole "
                        + "macro formula silently hides evidence from the analyst");
    }

    /** A record within the bound must NOT report a drop (guards against always-firing). */
    @Test
    void testXlsbInBoundFormulaRecordDoesNotReportADrop() throws Exception {
        AtomicInteger dropped = new AtomicInteger();
        Metadata metadata = new Metadata();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                new ToXMLContentHandler(), metadata, new ParseContext());
        xhtml.startDocument();
        Biff12XlmMacrosheetParser parser = new Biff12XlmMacrosheetParser(
                new ByteArrayInputStream(new byte[0]), xhtml, null, 65536,
                dropped::incrementAndGet);

        parser.handleRecord(0x0009, formulaRecord(execFormula("calc.exe")));

        assertEquals(0, dropped.get(), "an in-bound record must not be reported as dropped");
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
