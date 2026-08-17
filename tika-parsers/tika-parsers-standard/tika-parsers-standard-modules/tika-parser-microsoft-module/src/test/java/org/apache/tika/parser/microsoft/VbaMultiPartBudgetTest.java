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
package org.apache.tika.parser.microsoft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

/**
 * The VBA document budget across SEVERAL macro parts in one container.
 *
 * <p>An OOXML package may carry any number of {@code vbaProject} parts: every part a main document
 * part declares a {@code vbaProject} relationship to is walked by
 * {@code AbstractOOXMLExtractor.handleMacrosEarly}, deduplicated only by target part name. The
 * budget was built INSIDE {@code OfficeParser.extractMacros}, so each of those calls got a fresh
 * one and N parts got N times the documented per-document ceiling -- with the ceiling's own
 * javadoc, its metadata message and the commit that added it all claiming a document scope it did
 * not have. The parts are cheap: a zip entry of a few kilobytes per part.
 *
 * <p>Sharing the accumulator is necessary but NOT sufficient, and this class pins both halves.
 * The gate in front of POI's unbounded reader compares a per-part projection against the FULL
 * ceiling, and POI's own output was never charged, so N parts each projecting just under the
 * ceiling cleared the gate N times over regardless of what the accumulator held.
 */
public class VbaMultiPartBudgetTest {

    private static final String VBA_REL =
            "http://schemas.microsoft.com/office/2006/relationships/vbaProject";

    private static final int PARTS = 6;

    /** ~480 KB of ordinary macro source, tagged so each part's contribution is identifiable. */
    private static byte[] fillerProject(int part) throws Exception {
        String body = ("' filler line of macro source in part " + part + "\n").repeat(1000);
        VbaProjectBuilder b = new VbaProjectBuilder();
        for (int i = 0; i < 12; i++) {
            b.module("P" + part + "M" + i, "' PARTMARK" + part + "\n" + body);
        }
        return b.build();
    }

    /** One small module carrying an indicator line -- the thing an analyst must not lose. */
    private static byte[] payloadProject() throws Exception {
        return new VbaProjectBuilder()
                .module("Payload", "Sub AutoOpen()\n"
                        + "  Shell \"powershell -w hidden -enc EVIL\"\n"
                        + "End Sub\n")
                .build();
    }

    // ── fixture validity ────────────────────────────────────────────────────

    /**
     * FIXTURE VALIDITY, and the negative control for everything below: with a budget that fits,
     * every one of the {@value #PARTS} macro parts must be extracted in full.
     *
     * <p>Without this arm the bounded assertions below would pass just as happily against a build
     * that never walked the second part at all -- which is the failure mode a budget test cannot
     * distinguish from a working budget by looking at a total.
     */
    @Test
    void testEveryMacroPartIsExtractedWhenTheBudgetFits() throws Exception {
        List<byte[]> projects = new ArrayList<>();
        for (int i = 0; i < PARTS; i++) {
            projects.add(fillerProject(i));
        }
        Metadata md = new Metadata();
        String text = parse(carrierWithMacroParts(projects), md, 0);

        for (int i = 0; i < PARTS; i++) {
            assertTrue(text.contains("PARTMARK" + i),
                    "part " + i + " must be extracted; without it this class proves nothing. "
                            + "Got " + text.length() + " chars");
        }
        // Count MODULES, not just the presence of each part: one PARTMARK line per module, and no
        // other line contains the token. "part i appears" survives losing 11 of that part's 12
        // modules, which is most of the recall a budget bug would cost.
        int modules = text.split("PARTMARK", -1).length - 1;
        assertEquals(PARTS * 12, modules,
                "every module of every part must be extracted under the default budget; got "
                        + modules);
        assertNull(md.get("msoffice:vba-capture-limit-reached"),
                "nothing was withheld, so no flag may fire");
    }

    // ── the budget is per document, not per part ─────────────────────────────

    /**
     * The documented ceiling must bound the TOTAL over all macro parts.
     *
     * <p>{@value #PARTS} parts of ~480 KB each against a 200 KB ceiling: a per-part budget yields
     * ~6 x 200 KB, a document budget ~200 KB. The upper assertion separates those; the lower one
     * keeps a build that simply drops everything from passing -- a total-loss switch is not a
     * budget, and that exact regression was shipped once already on this branch.
     */
    @Test
    void testDocumentBudgetIsSharedAcrossMacroParts() throws Exception {
        List<byte[]> projects = new ArrayList<>();
        for (int i = 0; i < PARTS; i++) {
            projects.add(fillerProject(i));
        }
        Metadata md = new Metadata();
        String text = parse(carrierWithMacroParts(projects), md, 200_000);

        assertTrue(text.length() < 400_000,
                "the ceiling is per DOCUMENT: " + PARTS + " macro parts must not each get their "
                        + "own 200 KB allowance; got " + text.length() + " chars");
        assertTrue(text.length() > 50_000,
                "what fits must still be extracted; got " + text.length() + " chars");
        // One shared accumulator is reported from EVERY part's finally and the detail is
        // first-wins, so the same sentence used to land on the metadata once per macro part.
        String[] warnings = md.getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING);
        assertEquals(warnings.length, new HashSet<>(Arrays.asList(warnings)).size(),
                "a shared bound must be surfaced ONCE, not once per macro part: "
                        + Arrays.toString(warnings));
        assertEquals("true", md.get("msoffice:vba-capture-limit-reached"),
                "macro source was cut, so the document must say so");
        assertNotNull(md.get(TikaCoreProperties.TRUNCATED_METADATA));
    }

    /**
     * The same property when each part on its OWN clears the gate in front of POI's unbounded
     * reader. This is the arm that sharing the accumulator alone does not fix: the projection is
     * compared against the ceiling rather than against what is left of it, and POI's output is
     * charged nowhere, so five parts of 150 KB each ran POI five times for 750 KB against a
     * 200 KB ceiling with the accumulator sitting at zero the whole time.
     */
    @Test
    void testPartsThatIndividuallyClearTheGateStillShareTheBudget() throws Exception {
        // ~150 KB per part: comfortably under the 200 KB ceiling, so every part's projection
        // clears on its own and POI -- not the bounded reader -- does the reading.
        List<byte[]> projects = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String body = ("' under-ceiling filler in part " + i + "\n").repeat(1200);
            projects.add(new VbaProjectBuilder()
                    .module("P" + i + "M0", "' PARTMARK" + i + "\n" + body)
                    .module("P" + i + "M1", "' PARTMARK" + i + "\n" + body)
                    .module("P" + i + "M2", "' PARTMARK" + i + "\n" + body)
                    .build());
        }
        Metadata md = new Metadata();
        String text = parse(carrierWithMacroParts(projects), md, 200_000);

        assertTrue(text.length() < 400_000,
                "parts that clear the projection individually must still be charged against the "
                        + "document ceiling; got " + text.length() + " chars");
        assertTrue(text.contains("PARTMARK0"),
                "the first part must still be extracted in full; got " + text.length() + " chars");
    }

    // ── starvation: the ORDER of the parts must not decide what is reported ──

    /**
     * STARVATION at part scope. A shared budget hands the author a new lever: pad the FIRST macro
     * part and the payload in the last one is dropped, with only a generic truncation flag to show
     * for it. The indicator reserve already defends this within one project; it has to survive
     * being spread over several parts, in either order.
     */
    @Test
    void testPayloadSurvivesTheSharedBudgetRegardlessOfPartOrder() throws Exception {
        for (boolean payloadLast : new boolean[] {false, true}) {
            List<byte[]> projects = new ArrayList<>();
            if (!payloadLast) {
                projects.add(payloadProject());
            }
            for (int i = 0; i < 5; i++) {
                projects.add(fillerProject(i));
            }
            if (payloadLast) {
                projects.add(payloadProject());
            }

            Metadata md = new Metadata();
            String text = parse(carrierWithMacroParts(projects), md, 200_000);
            assertTrue(text.contains("EVIL"),
                    "payload part " + (payloadLast ? "LAST" : "FIRST") + ": the Shell line must "
                            + "survive the shared budget, or the ORDER of the macro parts -- which "
                            + "the author chooses -- decides whether the payload is reported at "
                            + "all. Got " + text.length() + " chars");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String parse(byte[] bytes, Metadata md, long vbaTotalBytes) throws Exception {
        ParseContext context = new ParseContext();
        if (vbaTotalBytes > 0) {
            OfficeParserConfig cfg = new OfficeParserConfig();
            cfg.setVbaMaxTotalBytes(vbaTotalBytes);
            context.set(OfficeParserConfig.class, cfg);
        }
        BodyContentHandler handler = new BodyContentHandler(-1);
        try (TikaInputStream tis = TikaInputStream.get(bytes)) {
            new AutoDetectParser().parse(tis, handler, md, context);
        }
        return handler.toString();
    }

    /**
     * A .docm carrying one {@code vbaProject} part per entry in {@code projects}, each declared
     * from {@code word/document.xml} with its own relationship. The {@code bin} content type is
     * already a package-wide Default, so no {@code [Content_Types].xml} change is needed.
     */
    private static byte[] carrierWithMacroParts(List<byte[]> projects) throws Exception {
        byte[] docm = readResource("/test-documents/testWORD_macros.docm");
        StringBuilder extraRels = new StringBuilder();
        for (int i = 1; i < projects.size(); i++) {
            extraRels.append("<Relationship Id=\"rIdVba").append(i)
                    .append("\" Type=\"").append(VBA_REL)
                    .append("\" Target=\"vbaProject").append(i).append(".bin\"/>");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean replacedProject = false;
        boolean rewroteRels = false;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(docm));
             ZipOutputStream zout = new ZipOutputStream(out)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                byte[] data = zin.readAllBytes();
                String name = e.getName();
                if (name.endsWith("word/vbaProject.bin") || name.equals("vbaProject.bin")) {
                    data = projects.get(0);
                    replacedProject = true;
                } else if (name.equals("word/_rels/document.xml.rels")) {
                    String rels = new String(data, StandardCharsets.UTF_8);
                    int close = rels.lastIndexOf("</Relationships>");
                    assertTrue(close > 0, "carrier rels must be well formed");
                    rels = rels.substring(0, close) + extraRels + rels.substring(close);
                    data = rels.getBytes(StandardCharsets.UTF_8);
                    rewroteRels = true;
                }
                zout.putNextEntry(new ZipEntry(name));
                zout.write(data);
                zout.closeEntry();
            }
            for (int i = 1; i < projects.size(); i++) {
                zout.putNextEntry(new ZipEntry("word/vbaProject" + i + ".bin"));
                zout.write(projects.get(i));
                zout.closeEntry();
            }
        }
        assertTrue(replacedProject, "carrier must already contain a vbaProject.bin to replace");
        assertTrue(rewroteRels, "carrier must declare its vbaProject from document.xml.rels");
        return out.toByteArray();
    }

    private static byte[] readResource(String name) throws Exception {
        try (InputStream in = VbaMultiPartBudgetTest.class.getResourceAsStream(name)) {
            assertNotNull(in, "missing test resource " + name);
            return in.readAllBytes();
        }
    }
}
