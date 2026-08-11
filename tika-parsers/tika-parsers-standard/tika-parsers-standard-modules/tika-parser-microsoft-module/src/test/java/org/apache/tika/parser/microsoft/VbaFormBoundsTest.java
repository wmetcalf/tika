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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;

import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

/**
 * The UserForm path must be bounded and must report what it drops.
 *
 * <p>It was neither. {@link VbaFormParser} took no bounds at all, so its output was charged against
 * nothing -- the per-document ceiling that governs macro source did not cover form text, which is
 * the same per-part-vs-per-document scope slip the module path had. And every failure went to
 * {@code LOG.fine} only: a form whose parse blew up, a stream refused for exceeding the size cap,
 * a site count over the cap, all produced exactly the same observable result as a form with no
 * controls -- no flag, no warning, nothing.
 *
 * <p>That mattered more after OLE2 UserForm discovery was fixed: a surface that previously produced
 * nothing for {@code .doc}/{@code .xls} now produces up to hundreds of kilobytes per document.
 */
public class VbaFormBoundsTest {

    private static final String LONG_TAG = "http://evil.example/".concat("a".repeat(400));

    /** A vbaProject.bin holding {@code n} forms, each with one long-tagged control. */
    private static byte[] formProject(int n) throws Exception {
        VbaFormBuilder b = new VbaFormBuilder().control("Btn", LONG_TAG, "tip");
        return b.poifs(new String[] {"UserForm"}, n);
    }

    // ── the document budget must cover form text ─────────────────────────────

    @Test
    void testFormTextIsChargedAgainstTheDocumentBudget() throws Exception {
        byte[] carrier = replaceVbaProject(
                readResource("/test-documents/testWORD_macros.docm"), formProject(40));

        // Control: a roomy budget keeps everything and reports nothing.
        Metadata roomy = new Metadata();
        String allText = parse(carrier, roomy, null);
        int occurrencesAll = countOccurrences(allText, "evil.example");
        assertTrue(occurrencesAll >= 40,
                "control: all 40 forms must be emitted; found " + occurrencesAll);
        assertNull(roomy.get("msoffice:vba-capture-limit-reached"),
                "nothing was withheld, so the flag must not fire");

        // A tight document budget must cut it AND say so.
        OfficeParserConfig cfg = new OfficeParserConfig();
        cfg.setVbaMaxTotalBytes(3000);
        Metadata tight = new Metadata();
        String cutText = parse(carrier, tight, cfg);
        int occurrencesCut = countOccurrences(cutText, "evil.example");
        assertTrue(occurrencesCut < occurrencesAll,
                "the budget must actually cut form output: " + occurrencesCut + " vs "
                        + occurrencesAll);
        assertTrue(occurrencesCut > 0,
                "the budget must not be all-or-nothing; some forms must survive");
        assertEquals("true", tight.get("msoffice:vba-capture-limit-reached"),
                "dropping forms is evidence loss and must be reported on the document");
        assertNotNull(tight.get(TikaCoreProperties.TRUNCATED_METADATA));
    }

    /**
     * The budget is shared with macro source, not a second allowance. Two ceilings that each admit
     * the full amount are not a document ceiling.
     */
    @Test
    void testFormsAndModulesShareOneBudget() throws Exception {
        LenientVBAReader.Bounds bounds = new LenientVBAReader.Bounds(1024 * 1024, 5000);
        byte[] project = formProject(40);
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
            long emitted = 0;
            for (VbaFormParser.FormModuleResult form
                    : VbaFormParser.extractFormVariables(fs, bounds)) {
                String text = form.toText();
                if (!bounds.hasRoomFor(text.length())) {
                    break;
                }
                bounds.charge(text.length());
                emitted += text.length();
            }
            assertTrue(emitted <= 5000,
                    "retained form text must fit the document budget; got " + emitted);
            assertEquals(emitted, bounds.retainedBytes(),
                    "charged bytes must equal retained bytes");
        }
    }

    // ── losses must be reported, not logged ──────────────────────────────────

    @Test
    void testUnparsableFormIsReported() throws Exception {
        byte[] poifs = poifsWithFStream("Macros/UserForm1/f",
                new byte[] {(byte) 0xFF, (byte) 0xFF, 0x10, 0x27, 0x00, 0x00});
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(poifs))) {
            LenientVBAReader.Bounds bounds = new LenientVBAReader.Bounds();
            VbaFormParser.extractFormVariables(fs, bounds);
            assertTrue(bounds.isLimitReached(),
                    "a UserForm we could not parse may hide control properties; reporting it as "
                            + "'no controls' is indistinguishable from a clean form");
            assertTrue(bounds.getLimitDetail() != null
                            && bounds.getLimitDetail().contains("UserForm1"),
                    "the report should name the form; got " + bounds.getLimitDetail());
        }
    }

    @Test
    void testOversizeFormStreamIsReported() throws Exception {
        byte[] poifs = new VbaFormBuilder().control("Btn", LONG_TAG, "tip").poifs("UserForm1");
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(poifs))) {
            // A per-stream cap below the form stream's size: the whole form is dropped.
            LenientVBAReader.Bounds tiny = new LenientVBAReader.Bounds(16);
            assertTrue(VbaFormParser.extractFormVariables(fs, tiny).isEmpty()
                            || VbaFormParser.extractFormVariables(fs, tiny).get(0)
                                    .controls.isEmpty(),
                    "the fixture must actually be refused, else this test is vacuous");
            assertTrue(tiny.isLimitReached(),
                    "dropping a whole form stream for size must be reported");
        }
    }

    // ── negative controls: no loss, no flag ──────────────────────────────────

    @Test
    void testCleanFormsDoNotSetTheFlag() throws Exception {
        try (POIFSFileSystem fs = new POIFSFileSystem(
                new ByteArrayInputStream(formProject(5)))) {
            LenientVBAReader.Bounds bounds = new LenientVBAReader.Bounds();
            assertEquals(5, VbaFormParser.extractFormVariables(fs, bounds).size());
            assertFalse(bounds.isLimitReached(),
                    "five well-formed forms withhold nothing; the flag must stay clear");
        }
    }

    @Test
    void testDocumentWithNoFormsDoesNotSetTheFlag() throws Exception {
        byte[] project = new VbaProjectBuilder()
                .module("Module1", "Sub Foo()\nEnd Sub\n").build();
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
            LenientVBAReader.Bounds bounds = new LenientVBAReader.Bounds();
            assertTrue(VbaFormParser.extractFormVariables(fs, bounds).isEmpty());
            assertFalse(bounds.isLimitReached(),
                    "a project with no UserForms at all must not be flagged");
        }
    }

    /** Real macro documents must stay unflagged -- a flag on healthy files is noise. */
    @Test
    void testRealDocumentsAreNotFlagged() throws Exception {
        for (String name : new String[] {"/test-documents/testWORD_macros.doc",
                "/test-documents/testWORD_macros.docm",
                "/test-documents/testEXCEL_macro.xls"}) {
            Metadata md = new Metadata();
            parse(readResource(name), md, null);
            assertNull(md.get("msoffice:vba-capture-limit-reached"),
                    name + ": no bound fired, so no flag may be set");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }

    private static byte[] poifsWithFStream(String path, byte[] content) throws Exception {
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            String[] parts = path.split("/");
            DirectoryEntry dir = fs.getRoot();
            for (int i = 0; i < parts.length - 1; i++) {
                dir = dir.createDirectory(parts[i]);
            }
            dir.createDocument(parts[parts.length - 1], new ByteArrayInputStream(content));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            fs.writeFilesystem(bos);
            return bos.toByteArray();
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
        try (InputStream in = VbaFormBoundsTest.class.getResourceAsStream(name)) {
            assertNotNull(in, "missing test resource " + name);
            return in.readAllBytes();
        }
    }

    private static byte[] replaceVbaProject(byte[] ooxml, byte[] vbaProject) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean replaced = false;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(ooxml));
             ZipOutputStream zout = new ZipOutputStream(out)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                byte[] data = zin.readAllBytes();
                if (e.getName().endsWith("vbaProject.bin")) {
                    data = vbaProject;
                    replaced = true;
                }
                zout.putNextEntry(new ZipEntry(e.getName()));
                zout.write(data);
                zout.closeEntry();
            }
        }
        assertTrue(replaced, "carrier must already contain a vbaProject.bin to replace");
        return out.toByteArray();
    }
}
