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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.xssf.usermodel.XSSFRelation;
import org.junit.jupiter.api.Test;

import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

/**
 * Synthetic corpus: crafted workbooks parsed END-TO-END through {@link AutoDetectParser}.
 *
 * <p>WHY THIS EXISTS. Every other XLM test reaches the code by constructing
 * {@link XlmXmlMacrosheetParser} directly or by reflecting into the decorator's
 * {@code processXlmXmlMacroSheets}. That leaves the entire wiring untested: content-type detection,
 * the extractor factory, the decorator's own entry path, and any metadata filter between the parser
 * and the caller. A macro part that stops being recognised, or a metadata key that gets filtered
 * before a consumer sees it, would leave ~70 unit tests green and every one of these findings
 * silently unfixed in production.
 *
 * <p>It also gives DOCUMENT-level regression coverage to the evasions. The real 3,084-document
 * corpus reaches none of them -- the structural-anomaly flag fires on 0 of those documents -- so
 * without this file the only thing standing between a refactor and a reintroduced total-loss
 * evasion is a unit test on an internal class.
 *
 * <p>Documents are BUILT here rather than checked in as binaries: the builder is reviewable, the
 * intent of each case is readable, and there are no opaque blobs in the tree. Nothing is executed;
 * the bytes are only ever fed to the parser.
 */
class XlmSyntheticCorpusTest {

    private static final String PAYLOAD = "powershell -enc SYNTHETIC";

    /** A carrier workbook, so detection and the extractor factory run for real. */
    private static final String CARRIER = "/test-documents/testEXCEL.xlsx";

    // ── The control. If this fails, the harness is wrong, not the code. ──────

    /**
     * POSITIVE CONTROL: an ordinary macrosheet must produce its EXEC indicator end-to-end.
     *
     * <p>Read this first when anything else in the file fails. If the control is red, the crafted
     * package is not reaching the XLM path at all and every other assertion here is meaningless.
     */
    @Test
    void controlPlainMacrosheetProducesItsIndicatorEndToEnd() throws Exception {
        Parsed p = parse(craft(macroSheet("<c r=\"A1\"><f>=EXEC(\"" + PAYLOAD + "\")</f></c>")));
        assertTrue(p.text.contains("EXEC: " + PAYLOAD),
                "CONTROL FAILED -- the crafted workbook is not reaching the XLM path, so nothing "
                        + "else in this class means anything. Text was:\n" + head(p.text));
    }

    /** And a workbook with NO macrosheet must produce none of the XLM signals. */
    @Test
    void controlWorkbookWithoutMacrosheetSetsNoXlmSignals() throws Exception {
        Parsed p = parse(carrierBytes());
        assertNull(p.metadata.get("msoffice:xlm-structural-anomaly"));
        assertNull(p.metadata.get("msoffice:xlm-capture-limit-reached"));
        assertNull(p.metadata.get(TikaCoreProperties.TRUNCATED_METADATA));
    }

    // ── The evasions, at document level ─────────────────────────────────────

    /** A nested {@code <c>} used to erase the enclosing cell, with metadata identical to clean. */
    @Test
    void nestedCellCannotEraseThePayloadEndToEnd() throws Exception {
        Parsed p = parse(craft(macroSheet(
                "<c r=\"A1\"><f>=EXEC(\"" + PAYLOAD + "\")</f><c/></c>")));
        assertTrue(p.text.contains("EXEC: " + PAYLOAD),
                "payload must survive a nested <c>; text:\n" + head(p.text));
        assertTrue(Boolean.parseBoolean(p.metadata.get("msoffice:xlm-structural-anomaly")),
                "and the anomaly must reach METADATA, not just the parser");
    }

    /** {@code @r} is optional per ECMA-376, so this document is LEGAL and must not be flagged. */
    @Test
    void cellWithoutRefIsCapturedAndNotFlaggedEndToEnd() throws Exception {
        Parsed p = parse(craft(macroSheet("<c><f>=EXEC(\"" + PAYLOAD + "\")</f></c>")));
        assertTrue(p.text.contains("EXEC: " + PAYLOAD),
                "a legal @r-less cell must still be captured; text:\n" + head(p.text));
        assertFalse(Boolean.parseBoolean(p.metadata.get("msoffice:xlm-structural-anomaly")),
                "omitting @r is legal, so flagging it would fire on ordinary workbooks");
    }

    /** A decoy at the same cell ref must not delete the payload. */
    @Test
    void duplicateCellRefKeepsThePayloadEndToEnd() throws Exception {
        Parsed p = parse(craft(macroSheet(
                "<c r=\"A1\"><f>=EXEC(\"" + PAYLOAD + "\")</f></c>"
                        + "<c r=\"A1\"><f>=1+1</f></c>")));
        assertTrue(p.text.contains("EXEC: " + PAYLOAD),
                "a later decoy must not delete the payload; text:\n" + head(p.text));
    }

    /** A duplicated VALUE must not delete the payload value that {@code EXEC(A1)} resolves. */
    @Test
    void duplicateCellValueStillResolvesThePayloadEndToEnd() throws Exception {
        Parsed p = parse(craft(macroSheet(
                "<c r=\"A1\" t=\"str\"><v>" + PAYLOAD + "</v></c>"
                        + "<c r=\"A1\" t=\"str\"><v>0</v></c>"
                        + "<c r=\"B1\"><f>=EXEC(A1)</f></c>")));
        assertTrue(p.text.contains(PAYLOAD),
                "EXEC(A1) must resolve the payload candidate, not only the decoy; text:\n"
                        + head(p.text));
    }

    /** A colon in {@code @r} must not let one cell impersonate another in the IOC output. */
    @Test
    void colonInCellRefCannotImpersonateAnotherCellEndToEnd() throws Exception {
        Parsed p = parse(craft(macroSheet(
                "<c r=\"A1\" t=\"str\"><v>calc.exe</v></c>"
                        + "<c r=\"Z:A1\" t=\"str\"><v>" + PAYLOAD + "</v></c>"
                        + "<c r=\"B1\"><f>=EXEC(A1)</f></c>")));
        assertTrue(p.text.contains("EXEC: calc.exe"),
                "A1's REAL content must be what EXEC(A1) reports; text:\n" + head(p.text));
    }

    /** One unreadable macro part must not cost the other part's indicators. */
    @Test
    void oneMalformedPartDoesNotCostTheOtherPartsIndicatorsEndToEnd() throws Exception {
        Parsed p = parse(craft(
                "   not xml at all",
                macroSheet("<c r=\"A1\"><f>=EXEC(\"" + PAYLOAD + "\")</f></c>")));
        assertTrue(p.text.contains("EXEC: " + PAYLOAD),
                "the readable part's indicator must survive; text:\n" + head(p.text));
        assertTrue(Boolean.parseBoolean(p.metadata.get("msoffice:xlm-capture-limit-reached")),
                "and an unreadable part must be flagged, since 'no IOCs' would otherwise read "
                        + "as a clean verdict");
    }

    /** A nested {@code <f>} must not replace the formula with the inner element's text. */
    @Test
    void nestedValueElementCannotReplaceTheFormulaEndToEnd() throws Exception {
        Parsed p = parse(craft(macroSheet(
                "<c r=\"A1\"><f>=EXEC(\"" + PAYLOAD + "\")<v>0</v></f></c>")));
        assertTrue(p.text.contains("EXEC: " + PAYLOAD),
                "the inner <v> must not become the formula; text:\n" + head(p.text));
    }

    /** Two macro parts sharing a basename must both survive. */
    @Test
    void twoMacroPartsSharingABasenameBothSurviveEndToEnd() throws Exception {
        byte[] doc = craftAtPaths(
                new String[] {"/xl/macrosheets/m.xml", "/xl/other/m.xml"},
                new String[] {
                    macroSheet("<c r=\"A1\"><f>=EXEC(\"" + PAYLOAD + "\")</f></c>"),
                    macroSheet("<c r=\"A1\"><f>=EXEC(\"second-part\")</f></c>"),
                });
        Parsed p = parse(doc);
        assertTrue(p.text.contains("EXEC: " + PAYLOAD) && p.text.contains("EXEC: second-part"),
                "neither part's cells may be overwritten by the other; text:\n" + head(p.text));
    }

    /** An ordinary macro-bearing workbook must set NO failure signal. */
    @Test
    void ordinaryMacroWorkbookSetsNoFailureSignalEndToEnd() throws Exception {
        Parsed p = parse(craft(macroSheet(
                "<c r=\"A1\"><f>=EXEC(\"" + PAYLOAD + "\")</f><v>0</v></c>"
                        + "<c r=\"B1\" t=\"inlineStr\"><is><t>plain</t></is></c>")));
        assertTrue(p.text.contains("EXEC: " + PAYLOAD), "sanity: the indicator is found");
        assertFalse(Boolean.parseBoolean(p.metadata.get("msoffice:xlm-structural-anomaly")),
                "no anomaly on an ordinary workbook");
        assertFalse(Boolean.parseBoolean(p.metadata.get("msoffice:xlm-capture-limit-reached")),
                "no capture limit on an ordinary workbook");
        assertNull(p.metadata.get(TikaCoreProperties.TRUNCATED_METADATA),
                "and no truncation claim");
    }

    /**
     * Extraction must be REPRODUCIBLE -- on the XLSB path, which is where the clock lives.
     *
     * <p>Deliberately an XLSB document. {@code NOW()} is EVALUATED only by the XLSB emulator; the
     * XML path's scanner matches it as a TIME_GATE pattern and never computes a value, so an xlsm
     * case cannot reach the defect. A first version of this test used an xlsm carrier and was
     * vacuous -- mutation proved it, staying green with the wall-clock read restored.
     *
     * <p>The defect: emulated NOW()/TODAY() read the wall clock, so the parse timestamp landed in
     * the extracted text. Two runs of the same binary over the real corpus disagreed on 47
     * documents.
     */
    @Test
    void extractionIsReproducibleOnTheXlsbPathEndToEnd() throws Exception {
        byte[] doc = craftXlsb(formulaRecord(nowRegisterFormula()));
        String first = parse(doc).text;
        String second = parse(doc).text;
        assertEquals(first, second,
                "the same bytes must extract to identical text; a wall-clock read in the emulator "
                        + "put the parse timestamp into the output. First run:\n" + head(first));
        assertTrue(first.contains("REGISTER") || first.contains("TIME_GATE"),
                "sanity: the crafted NOW() record must actually be emulated, else this test is "
                        + "vacuous. Text was:\n" + head(first));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static final class Parsed {
        final String text;
        final Metadata metadata;

        Parsed(String text, Metadata metadata) {
            this.text = text;
            this.metadata = metadata;
        }
    }

    /** Full pipeline: detection, extractor factory, decorator, metadata filters. */
    private static Parsed parse(byte[] doc) throws Exception {
        Metadata metadata = new Metadata();
        BodyContentHandler handler = new BodyContentHandler(-1);
        try (TikaInputStream is = TikaInputStream.get(new ByteArrayInputStream(doc))) {
            new AutoDetectParser().parse(is, handler, metadata, new ParseContext());
        }
        return new Parsed(handler.toString(), metadata);
    }

    private static String macroSheet(String rowChildren) {
        return String.format(Locale.ROOT, """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData><row r="1">%s</row></sheetData>
                </worksheet>
                """, rowChildren);
    }

    private static byte[] carrierBytes() throws Exception {
        try (InputStream in = XlmSyntheticCorpusTest.class.getResourceAsStream(CARRIER);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    /** Add macrosheet parts to the carrier at conventional paths. */
    private static byte[] craft(String... macroSheetXml) throws Exception {
        String[] paths = new String[macroSheetXml.length];
        for (int i = 0; i < paths.length; i++) {
            paths[i] = "/xl/macrosheets/sheet" + (i + 1) + ".xml";
        }
        return craftAtPaths(paths, macroSheetXml);
    }

    private static byte[] craftAtPaths(String[] paths, String[] macroSheetXml) throws Exception {
        ByteArrayOutputStream saved = new ByteArrayOutputStream();
        try (InputStream carrier = XlmSyntheticCorpusTest.class.getResourceAsStream(CARRIER);
             OPCPackage pkg = OPCPackage.open(carrier)) {
            for (int i = 0; i < paths.length; i++) {
                PackagePart part = pkg.createPart(
                        PackagingURIHelper.createPartName(paths[i]),
                        XSSFRelation.MACRO_SHEET_XML.getContentType());
                try (OutputStream os = part.getOutputStream()) {
                    os.write(macroSheetXml[i].getBytes(StandardCharsets.UTF_8));
                }
            }
            pkg.save(saved);
        }
        return saved.toByteArray();
    }


    // ── XLSB arm ────────────────────────────────────────────────────────────

    private static final String CARRIER_XLSB = "/test-documents/testEXCEL.xlsb";

    /** Wrap formula bytes in a BRT_FMLA_NUM cell record. Mirrors XlmCaptureBoundsTest. */
    private static byte[] formulaRecord(byte[] formula) {
        return java.nio.ByteBuffer.allocate(22 + formula.length)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(0).putInt(0).putDouble(0).putShort((short) 0)
                .putInt(formula.length).put(formula).array();
    }

    /** {@code =REGISTER(NOW())}: PtgFuncVar(argc=0, NOW) then PtgFuncVar(argc=1, REGISTER). */
    private static byte[] nowRegisterFormula() {
        return java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .put((byte) 0x22).put((byte) 0).putShort((short) 0x004A)
                .put((byte) 0x22).put((byte) 1).putShort((short) 0x0095)
                .array();
    }

    /** Add a binary macrosheet part carrying one formula record to the XLSB carrier. */
    private static byte[] craftXlsb(byte[] cellRecord) throws Exception {
        ByteArrayOutputStream saved = new ByteArrayOutputStream();
        try (InputStream carrier = XlmSyntheticCorpusTest.class.getResourceAsStream(CARRIER_XLSB);
             OPCPackage pkg = OPCPackage.open(carrier)) {
            PackagePart part = pkg.createPart(
                    PackagingURIHelper.createPartName("/xl/macrosheets/sheet1.bin"),
                    XSSFRelation.MACRO_SHEET_BIN.getContentType());
            try (OutputStream os = part.getOutputStream()) {
                writeBiffRecord(os, 0x0009, cellRecord);
            }
            pkg.save(saved);
        }
        return saved.toByteArray();
    }

    /** BIFF12 record: varint type, varint length, payload. */
    private static void writeBiffRecord(OutputStream os, int type, byte[] payload)
            throws java.io.IOException {
        writeBiffVarInt(os, type);
        writeBiffVarInt(os, payload.length);
        os.write(payload);
    }

    private static void writeBiffVarInt(OutputStream os, int value) throws java.io.IOException {
        int v = value;
        while (true) {
            int b = v & 0x7F;
            v >>>= 7;
            if (v == 0) {
                os.write(b);
                return;
            }
            os.write(b | 0x80);
        }
    }

    private static String head(String s) {
        return s.length() > 1600 ? s.substring(0, 1600) + " …[truncated]" : s;
    }
}
