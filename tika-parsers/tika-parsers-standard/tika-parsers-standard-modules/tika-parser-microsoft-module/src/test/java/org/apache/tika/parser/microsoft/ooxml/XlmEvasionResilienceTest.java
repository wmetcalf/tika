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
    private static void assertSubQuadratic(String what, int baseN,
                                           java.util.function.IntConsumer body) {
        body.accept(baseN);        // warm up JIT on the real shape
        long[] cost = new long[3];
        int[] sizes = {baseN, baseN * 2, baseN * 4};
        for (int i = 0; i < 3; i++) {
            long best = Long.MAX_VALUE;
            for (int rep = 0; rep < 3; rep++) {
                long t0 = System.nanoTime();
                body.accept(sizes[i]);
                best = Math.min(best, System.nanoTime() - t0);
            }
            cost[i] = best;
        }
        // If the base is too fast to measure, the ratios are noise -- say so rather than pass.
        assertTrue(cost[0] > 1_000_000L,
                what + ": base cost " + cost[0] / 1_000_000 + " ms is too small to measure a "
                        + "cost shape; raise baseN");
        double r1 = (double) cost[1] / cost[0];
        double r2 = (double) cost[2] / cost[1];
        assertTrue(r1 < 3.0 && r2 < 3.0,
                what + ": cost grows ~quadratically. n=" + sizes[0] + "," + sizes[1] + ","
                        + sizes[2] + " -> " + cost[0] / 1_000_000 + "," + cost[1] / 1_000_000
                        + "," + cost[2] / 1_000_000 + " ms; ratios per doubling "
                        + String.format(java.util.Locale.ROOT, "%.2f, %.2f", r1, r2)
                        + " (linear ~2.0, quadratic ~4.0)");
    }

    /** Duplicated {@code <c r="A1">} -- the dimension whose uniquify loop was O(N^2). */
    @Test
    void testDuplicateCellRefCostIsSubQuadratic() {
        assertSubQuadratic("duplicate <c r> per row", 6000, n -> {
            StringBuilder row = new StringBuilder();
            for (int i = 0; i < n; i++) {
                row.append("<c r=\"A1\"><f>=1</f></c>");
            }
            try {
                parseRowXml(row.toString());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    /** Duplicated {@code <f>} in one cell -- the dimension whose buffer seeding was O(N^2). */
    @Test
    void testDuplicateFormulaElementCostIsSubQuadratic() {
        assertSubQuadratic("duplicate <f> per cell", 4000, n -> {
            StringBuilder cell = new StringBuilder("<c r=\"A1\">");
            for (int i = 0; i < n; i++) {
                cell.append("<f>=1</f>");
            }
            cell.append("</c>");
            try {
                parseRowXml(cell.toString());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    /** Duplicated {@code <is>} in one cell -- the value-side join. */
    @Test
    void testDuplicateInlineStringCostIsSubQuadratic() {
        assertSubQuadratic("duplicate <is> per cell", 3000, n -> {
            StringBuilder cell = new StringBuilder("<c r=\"A1\" t=\"inlineStr\">");
            for (int i = 0; i < n; i++) {
                cell.append("<is><t>x</t></is>");
            }
            cell.append("</c>");
            try {
                parseRowXml(cell.toString());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
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
