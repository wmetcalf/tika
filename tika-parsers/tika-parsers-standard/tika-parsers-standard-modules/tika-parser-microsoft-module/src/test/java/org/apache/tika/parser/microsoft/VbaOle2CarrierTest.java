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

import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;

import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

/**
 * Crafted OLE2 documents parsed END TO END through {@link AutoDetectParser}.
 *
 * <p>Every synthetic VBA case so far reached the parser through a {@code .docm} (OOXML) carrier, so
 * the OLE2 route -- {@code .doc}, {@code .xls}, {@code .ppt} -- was exercised only by unmodified real
 * fixtures, which cannot carry an evasion. OLE2 is 51,350 of the 52,920 VBA-bearing documents in the
 * local corpus, i.e. the format family that actually matters, and it reaches
 * {@link OfficeParser#extractMacros} by a different call path than OOXML does. A fix verified only
 * at the reader level and only on OOXML end-to-end is a fix with an untested wire on the majority
 * format.
 *
 * <p>The carrier is a real {@code testWORD_macros.doc} with its {@code Macros/VBA} storage replaced,
 * so the document still detects as Word and still walks the ordinary OLE2 code path.
 */
public class VbaOle2CarrierTest {

    private static final String PAYLOAD = "Sub AutoOpen()\n"
            + "  Shell \"powershell -enc SQBFAFgA\"  ' ole2-carrier-marker\n"
            + "End Sub\n";
    private static final String DECOY = "Sub Harmless()\n  MsgBox \"hi\"\nEnd Sub\n";

    /**
     * FIXTURE VALIDITY, and the reason the cases below use a POI-hostile project.
     *
     * <p>A well-formed project is read by POI, so {@link LenientVBAReader} never runs and an
     * end-to-end test on one measures POI instead of this code -- proven, not assumed: mutating the
     * chunk length, the multi-storage walk and the MODULESTREAMNAME parse all left the well-formed
     * cases GREEN. The lenient reader only serves projects POI rejects, so that is what an
     * end-to-end test of it has to carry.
     */
    @Test
    void testPoiRejectsTheHostileProjectButExtractionStillSucceeds() throws Exception {
        byte[] project = new VbaProjectBuilder().poiHostile()
                .module("Module1", PAYLOAD).build();
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project));
             org.apache.poi.poifs.macros.VBAMacroReader reader =
                     new org.apache.poi.poifs.macros.VBAMacroReader(fs)) {
            Throwable thrown = null;
            try {
                reader.readMacros();
            } catch (Throwable t) {
                thrown = t;
            }
            assertNotNull(thrown,
                    "the fixture must actually be POI-hostile, else the cases below silently "
                            + "measure POI rather than the lenient reader");
        }
        // ...and the whole document must still come through, via the lenient fallback.
        String text = parse(ole2Carrier(project), new Metadata(), null);
        assertTrue(text.contains("ole2-carrier-marker"),
                "a project POI rejects must still yield its macros; got " + text.length()
                        + " chars");
    }

    /**
     * The module the dir stream LABELS "ThisDocument" actually lives in a stream called "m0". The
     * OOXML route already covers this; here it must survive the OLE2 wire too -- on a project POI
     * rejects, so the assertion is about the lenient reader rather than about POI.
     */
    @Test
    void testModuleStreamNameMismatchSurvivesEndToEndOnOle2() throws Exception {
        byte[] doc = ole2Carrier(new VbaProjectBuilder().poiHostile()
                .module("ThisDocument", "m0", "m0", PAYLOAD)
                .build());
        Metadata md = new Metadata();
        String text = parse(doc, md, null);
        assertTrue(text.contains("ole2-carrier-marker"),
                "the payload module must reach the consumer through the OLE2 path; got "
                        + text.length() + " chars");
        assertNull(md.get("msoffice:vba-capture-limit-reached"),
                "nothing was withheld, so no flag");
    }

    /**
     * A decoy storage must not hide a second VBA project on the OLE2 route either.
     *
     * <p>This pins the OUTCOME, not the mechanism: removing the multi-storage tree walk leaves it
     * green, because the orphan property scan reaches the second storage anyway. Which mechanism
     * delivers the payload is pinned separately, by
     * {@code LenientVBADirStreamTest#testTreeWalkAloneReadsEveryStorageAndFoldsCase}. Both are worth
     * having -- one says the user gets the macro, the other says the primary path is what supplies
     * it rather than a reflective fallback that goes dark on a POI upgrade.
     */
    @Test
    void testSecondVbaStorageIsReadEndToEndOnOle2() throws Exception {
        byte[] decoyProject = new VbaProjectBuilder().poiHostile().module("Decoy", DECOY).build();
        byte[] payloadProject =
                new VbaProjectBuilder().poiHostile().module("Payload", PAYLOAD).build();
        byte[] doc = ole2Carrier(decoyProject, payloadProject);

        String text = parse(doc, new Metadata(), null);
        assertTrue(text.contains("Harmless"), "the first storage must still be read");
        assertTrue(text.contains("ole2-carrier-marker"),
                "a payload in a second VBA storage must not be hidden on the OLE2 path; got "
                        + text.length() + " chars");
    }

    /** A module spanning several chunks -- the truncation bug -- end to end on OLE2. */
    @Test
    void testMultiChunkModuleSurvivesEndToEndOnOle2() throws Exception {
        String big = "' filler line to push this module past one chunk\n".repeat(300)
                + PAYLOAD;
        byte[] doc = ole2Carrier(
                new VbaProjectBuilder().poiHostile().module("Module1", big).build());
        String text = parse(doc, new Metadata(), null);
        assertTrue(big.length() > 4096, "fixture must span several chunks");
        assertTrue(text.contains("ole2-carrier-marker"),
                "text after the first 4 KB chunk must survive on the OLE2 path -- this is the "
                        + "shape that was silently truncated; got " + text.length() + " chars");
    }

    /** The document budget and its flag must work through the OLE2 wire, not just OOXML. */
    @Test
    void testBudgetAndFlagReachTopLevelMetadataOnOle2() throws Exception {
        VbaProjectBuilder b = new VbaProjectBuilder().poiHostile();
        String body = "' forty kilobytes of macro source\n".repeat(1200);
        for (int i = 0; i < 12; i++) {
            b.module("Module" + i, "'" + i + "\n" + body);
        }
        byte[] doc = ole2Carrier(b.build());

        // Control: default budget keeps everything, flag stays clear.
        Metadata roomy = new Metadata();
        String all = parse(doc, roomy, null);
        assertNull(roomy.get("msoffice:vba-capture-limit-reached"),
                "12 ordinary modules must not trip the default budget");

        OfficeParserConfig cfg = new OfficeParserConfig();
        cfg.setVbaMaxTotalBytes(100_000);
        Metadata tight = new Metadata();
        String cut = parse(doc, tight, cfg);
        assertTrue(cut.length() < all.length(),
                "the budget must actually cut output on the OLE2 path: " + cut.length()
                        + " vs " + all.length());
        assertEquals("true", tight.get("msoffice:vba-capture-limit-reached"),
                "the loss must be reported on the DOCUMENT's metadata, not on an embedded child");
        assertNotNull(tight.get(TikaCoreProperties.TRUNCATED_METADATA));
    }

    /** The real carrier, unmodified, must remain unflagged -- the negative control for the wire. */
    @Test
    void testUnmodifiedCarrierIsUnflagged() throws Exception {
        Metadata md = new Metadata();
        String text = parse(readResource("/test-documents/testWORD_macros.doc"), md, null);
        assertTrue(text.contains("Attribute VB_Name") || text.contains("Sub "),
                "control: the real document's macros must still be extracted");
        assertNull(md.get("msoffice:vba-capture-limit-reached"));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static byte[] ole2Carrier(byte[]... vbaProjects) throws Exception {
        byte[] carrier = readResource("/test-documents/testWORD_macros.doc");
        try (POIFSFileSystem src = new POIFSFileSystem(new ByteArrayInputStream(carrier));
             POIFSFileSystem out = new POIFSFileSystem()) {
            // Everything except the macro storage, so the document still detects as Word.
            for (Entry e : src.getRoot()) {
                if ("Macros".equals(e.getName())) {
                    continue;
                }
                copy(e, out.getRoot());
            }
            DirectoryEntry macros = out.getRoot().createDirectory("Macros");
            DirectoryEntry srcMacros = (DirectoryEntry) src.getRoot().getEntry("Macros");
            for (Entry e : srcMacros) {
                if (!"VBA".equals(e.getName())) {
                    copy(e, macros); // PROJECT / PROJECTwm, which POI consults for name mapping
                }
            }
            for (int i = 0; i < vbaProjects.length; i++) {
                // The first project goes in the storage POI expects; any others go alongside it,
                // which is the "second VBA project" shape.
                DirectoryEntry target = i == 0
                        ? macros.createDirectory("VBA")
                        : out.getRoot().createDirectory("Extra" + i).createDirectory("VBA");
                try (POIFSFileSystem project =
                             new POIFSFileSystem(new ByteArrayInputStream(vbaProjects[i]))) {
                    DirectoryEntry from = (DirectoryEntry) project.getRoot().getEntry("VBA");
                    for (Entry e : from) {
                        copy(e, target);
                    }
                }
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            out.writeFilesystem(bos);
            return bos.toByteArray();
        }
    }

    /** Copy one entry (recursively, for storages) into {@code dest}, name preserved verbatim. */
    private static void copy(Entry e, DirectoryEntry dest) throws Exception {
        if (e instanceof DirectoryEntry) {
            DirectoryEntry sub = dest.createDirectory(e.getName());
            for (Entry child : (DirectoryEntry) e) {
                copy(child, sub);
            }
        } else {
            try (InputStream in = new DocumentInputStream((DocumentEntry) e)) {
                dest.createDocument(e.getName(), in);
            }
        }
    }

    private static String parse(byte[] bytes, Metadata md, OfficeParserConfig cfg)
            throws Exception {
        ParseContext context = new ParseContext();
        if (cfg != null) {
            context.set(OfficeParserConfig.class, cfg);
        }
        BodyContentHandler handler = new BodyContentHandler(-1);
        try (TikaInputStream tis = TikaInputStream.get(bytes)) {
            new AutoDetectParser().parse(tis, handler, md, context);
        }
        return handler.toString();
    }

    private static byte[] readResource(String name) throws Exception {
        try (InputStream in = VbaOle2CarrierTest.class.getResourceAsStream(name)) {
            assertNotNull(in, "missing test resource " + name);
            return in.readAllBytes();
        }
    }
}
