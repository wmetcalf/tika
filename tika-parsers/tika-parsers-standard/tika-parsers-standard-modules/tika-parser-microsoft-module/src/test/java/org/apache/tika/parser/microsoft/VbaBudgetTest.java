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
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.poifs.macros.VBAMacroReader;
import org.junit.jupiter.api.Test;

import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

/**
 * The VBA size bounds at DOCUMENT scope, and the gate in front of POI's unbounded reader.
 *
 * <p>Two defects motivate this class. First, the per-stream bound bounded nothing at document
 * scope: a project may hold any number of modules, so N modules each just under the cap cost N
 * times the cap -- the same per-part-vs-per-document scope slip the XLM caps had. Second, and
 * worse, the bound was never consulted on the PRIMARY path at all: POI's
 * {@link VBAMacroReader} decompresses every module into memory with an unbounded
 * {@code IOUtils.toByteArray}, and nothing checked a size before handing it the document. A bound
 * applied after that read cannot prevent the heap exhaustion, so the check has to come first.
 */
public class VbaBudgetTest {

    private static final String SRC = "Sub AutoOpen()\n"
            + "  Shell \"powershell -w hidden -enc SQBFAFgA\"\n"
            + "End Sub\n";

    /** A project whose modules decompress to ~{@code chunks * 4094} bytes each, from ~7 b/chunk. */
    private static byte[] bombProject(int modules, int chunksPerModule) throws Exception {
        VbaProjectBuilder b = new VbaProjectBuilder();
        for (int i = 0; i < modules; i++) {
            b.rawModule("Bomb" + i, VbaProjectBuilder.ratioBombContainer(chunksPerModule));
        }
        return b.build();
    }

    private static long totalChars(Map<String, String> macros) {
        long n = 0;
        for (String v : macros.values()) {
            n += v.length();
        }
        return n;
    }

    // ── the danger being defended against ───────────────────────────────────

    /**
     * Establishes that POI's reader really is unbounded, so the gate is not defending against a
     * hypothetical. Kept deliberately small (~8 MB) so this test cannot itself exhaust the heap;
     * the ratio, not the size, is the point -- 4 modules of ~4 KB yield ~8 MB, and nothing in that
     * path consults any limit, so the same shape scaled up takes the worker with it.
     */
    @Test
    void testPoiReaderHonoursNoSizeBound() throws Exception {
        byte[] project = bombProject(4, 512);
        assertTrue(project.length < 64 * 1024,
                "the bomb must be small on disk, else it proves nothing; got " + project.length);
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project));
             VBAMacroReader reader = new VBAMacroReader(fs)) {
            long got = totalChars(reader.readMacros());
            assertTrue(got > 4L * 1024 * 1024,
                    "POI must be shown returning millions of bytes from a few KB, or the "
                            + "projection gate is guarding nothing; got " + got);
        }
    }

    // ── the projection ──────────────────────────────────────────────────────

    /**
     * The projection must be an UPPER bound (never under-reports, or the gate lets a bomb
     * through) and must be tight on real-shaped input (never wildly over, or the gate redirects
     * documents that were fine).
     */
    @Test
    void testProjectionBoundsTheActualDecompressedSize() throws Exception {
        String big = "' filler line to push this module past one chunk\n".repeat(300);
        byte[] project = new VbaProjectBuilder()
                .module("Module1", SRC)
                .module("Module2", big)
                .build();

        long actual;
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
            actual = totalChars(LenientVBAReader.readMacros(fs, new LenientVBAReader.Bounds()));
        }
        long projected;
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
            projected = LenientVBAReader.projectDecompressedBytes(fs, Long.MAX_VALUE / 4);
        }
        assertTrue(projected >= actual,
                "the projection must never under-report: projected " + projected
                        + " < actual " + actual);
        // Each chunk yields at most 4096, so the slack is at most one chunk per module (2 here)
        // plus the module-name prefix. Anything looser would redirect real documents.
        assertTrue(projected <= actual + 3 * 4096,
                "the projection must stay tight on real-shaped input: projected " + projected
                        + " vs actual " + actual);
    }

    /**
     * A module stream that is NOT a compressed container (no 0x01 signature) is returned verbatim
     * from the offset onward. The projection must cover that branch too -- an upper bound that is
     * short by even one byte is not an upper bound.
     */
    @Test
    void testProjectionCoversUncompressedModuleStreams() throws Exception {
        byte[] plain = "Sub Plain()\n  MsgBox 1\nEnd Sub\n".repeat(50)
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] project = new VbaProjectBuilder().rawModule("Module1", plain).build();

        long actual;
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
            actual = totalChars(LenientVBAReader.readMacros(fs, new LenientVBAReader.Bounds()));
        }
        assertTrue(actual > 0, "control: the plain stream must be extracted; got " + actual);
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
            long projected = LenientVBAReader.projectDecompressedBytes(fs, Long.MAX_VALUE / 4);
            assertTrue(projected >= actual,
                    "the projection must not under-report an uncompressed stream: projected "
                            + projected + " < actual " + actual);
        }
    }

    @Test
    void testProjectionCatchesTheBombAndClearsARealProject() throws Exception {
        byte[] bomb = bombProject(4, 512);
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(bomb))) {
            long projected = LenientVBAReader.projectDecompressedBytes(fs, 1024 * 1024);
            assertTrue(projected > 1024 * 1024,
                    "a project that decompresses to ~8 MB must not clear a 1 MB ceiling; got "
                            + projected);
        }
        byte[] real = new VbaProjectBuilder().module("Module1", SRC).build();
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(real))) {
            long projected = LenientVBAReader.projectDecompressedBytes(fs,
                    LenientVBAReader.MAX_TOTAL_BYTES);
            assertTrue(projected <= LenientVBAReader.MAX_TOTAL_BYTES,
                    "an ordinary project must clear the default ceiling; got " + projected);
        }
    }

    /** A project the projection cannot walk must be treated as over the ceiling, not under it. */
    @Test
    void testUnwalkableProjectFailsClosed() throws Exception {
        byte[] garbage;
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            fs.getRoot().createDirectory("VBA")
                    .createDocument("dir", new ByteArrayInputStream(new byte[] {0x01, 0x00}));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            fs.writeFilesystem(bos);
            garbage = bos.toByteArray();
        }
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(garbage))) {
            // No modules are described, so the projection is 0 -- that is a genuine "nothing to
            // decompress", not a failure, and POI is safe to run on it.
            assertEquals(0, LenientVBAReader.projectDecompressedBytes(fs, 1024));
        }
    }

    // ── the document budget ─────────────────────────────────────────────────

    @Test
    void testDocumentBudgetCapsTheTotalAcrossModules() throws Exception {
        // 12 modules of ~40 KB each: every one is far under any per-stream cap, so only a
        // document-scope bound can hold the total down.
        String body = "' forty kilobytes of macro source\n".repeat(1200);
        VbaProjectBuilder b = new VbaProjectBuilder();
        for (int i = 0; i < 12; i++) {
            b.module("Module" + i, "'" + i + "\n" + body);
        }
        byte[] project = b.build();

        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
            LenientVBAReader.Bounds roomy = new LenientVBAReader.Bounds();
            Map<String, String> all = LenientVBAReader.readMacros(fs, roomy);
            assertEquals(12, all.size(), "control: all 12 modules fit the default budget");
            assertFalse(roomy.isLimitReached(),
                    "nothing was withheld, so the flag must NOT fire");
            assertTrue(totalChars(all) > 400_000,
                    "fixture must be big enough to test a budget; got " + totalChars(all));
        }

        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
            LenientVBAReader.Bounds tight = new LenientVBAReader.Bounds(1024 * 1024, 100_000);
            Map<String, String> some = LenientVBAReader.readMacros(fs, tight);
            assertTrue(tight.isLimitReached(),
                    "dropping later modules is evidence loss and must be reported");
            assertTrue(totalChars(some) <= 100_000,
                    "the document budget must actually bound the total; got "
                            + totalChars(some));
            assertTrue(some.size() < 12 && !some.isEmpty(),
                    "some modules must survive and some must be dropped; got " + some.size());
        }
    }

    /**
     * The accumulator must equal what a consumer can see. Charging what was READ rather than what
     * was KEPT lets a module that gets collapsed as a duplicate spend budget belonging to the
     * modules after it -- and the ones after it are where a payload hides.
     */
    @Test
    void testChargedBytesEqualStoredBytesOnEveryShape() throws Exception {
        String big = "' multi-chunk body\n".repeat(400);
        byte[][] shapes = new byte[][] {
            new VbaProjectBuilder().module("Module1", SRC).build(),
            new VbaProjectBuilder().module("Module1", SRC).module("Module2", big).build(),
            // duplicate MODULENAME, different bodies -- both retained
            new VbaProjectBuilder().module("Module1", "a", "a", SRC)
                    .module("Module1", "b", "b", big).build(),
            // duplicate MODULENAME, IDENTICAL bodies -- collapsed, so charged once
            new VbaProjectBuilder().module("Module1", "a", "a", SRC)
                    .module("Module1", "b", "b", SRC).build(),
            new VbaProjectBuilder().nestedUnder("Macros").module("Module1", SRC).build(),
            bombProject(2, 8),
        };
        for (int i = 0; i < shapes.length; i++) {
            try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(shapes[i]))) {
                LenientVBAReader.Bounds bounds = new LenientVBAReader.Bounds();
                Map<String, String> macros = LenientVBAReader.readMacros(fs, bounds);
                assertEquals(totalChars(macros), bounds.retainedBytes(),
                        "shape " + i + ": charged bytes must equal stored bytes; stored keys "
                                + macros.keySet());
            }
        }
    }

    // ── config plumbing ─────────────────────────────────────────────────────

    @Test
    void testTotalBoundIsConfigurableAndZeroMeansDefault() {
        assertEquals(LenientVBAReader.MAX_TOTAL_BYTES,
                new LenientVBAReader.Bounds(0, 0).totalMax(),
                "0 must select the built-in default, matching the other knobs");
        assertEquals(LenientVBAReader.MAX_TOTAL_BYTES,
                new LenientVBAReader.Bounds(0, -1).totalMax(),
                "a negative value must not disable or invert the bound");
        assertEquals(64L * 1024 * 1024, new LenientVBAReader.Bounds(0, 64L * 1024 * 1024)
                .totalMax());

        // The two knobs are independent and the tighter one wins: a requested document total
        // below the per-stream cap must be honoured, not clamped up to it.
        assertEquals(1024, new LenientVBAReader.Bounds(64 * 1024 * 1024, 1024).totalMax(),
                "a document ceiling below the per-stream cap must still be honoured");

        OfficeParserConfig cfg = new OfficeParserConfig();
        assertEquals(LenientVBAReader.MAX_TOTAL_BYTES,
                LenientVBAReader.Bounds.fromConfig(cfg).totalMax(),
                "an unset config must yield the default");
        cfg.setVbaMaxTotalBytes(7L * 1024 * 1024);
        assertEquals(7L * 1024 * 1024, LenientVBAReader.Bounds.fromConfig(cfg).totalMax(),
                "a forensics deployment must be able to raise or lower the document bound");
        assertEquals(LenientVBAReader.MAX_TOTAL_BYTES,
                LenientVBAReader.Bounds.fromConfig(null).totalMax(),
                "a null config must yield the default, not an unbounded read");
    }

    // ── end to end, through AutoDetectParser ────────────────────────────────

    /**
     * A real macro document must be unaffected: same macro source, no flag. This is the negative
     * control for the whole gate -- a bound that fires on ordinary documents is worse than none,
     * because "truncated" on 14,000 clean files is indistinguishable from noise.
     */
    @Test
    void testRealMacroDocumentIsUntouched() throws Exception {
        for (String name : new String[] {"/test-documents/testWORD_macros.docm",
                "/test-documents/testWORD_macros.doc",
                "/test-documents/testEXCEL_macro.xls"}) {
            Metadata md = new Metadata();
            String text = parse(readResource(name), md);
            assertTrue(text.contains("Attribute VB_Name") || text.contains("Sub "),
                    name + ": macro source must still be extracted; got "
                            + text.length() + " chars");
            assertNull(md.get("msoffice:vba-capture-limit-reached"),
                    name + ": no bound fired, so no flag may be set");
            assertNull(md.get(TikaCoreProperties.TRUNCATED_METADATA),
                    name + ": nothing was truncated");
        }
    }

    /**
     * The bomb, end to end through {@link AutoDetectParser} in a real .docm carrier: extraction
     * must complete, the total macro text must stay inside the budget, and the loss must be
     * reported on the document's metadata -- not on some embedded child nobody looks at.
     */
    @Test
    void testBombInADocmCarrierIsBoundedAndReported() throws Exception {
        byte[] carrier = replaceVbaProject(readResource("/test-documents/testWORD_macros.docm"),
                bombProject(4, 512));

        OfficeParserConfig cfg = new OfficeParserConfig();
        cfg.setVbaMaxTotalBytes(200_000);
        ParseContext context = new ParseContext();
        context.set(OfficeParserConfig.class, cfg);

        Metadata md = new Metadata();
        BodyContentHandler handler = new BodyContentHandler(-1);
        try (TikaInputStream tis = TikaInputStream.get(carrier)) {
            new AutoDetectParser().parse(tis, handler, md, context);
        }
        String text = handler.toString();
        assertTrue(text.length() < 2_000_000,
                "the bomb's output must be bounded, not merely survived; got " + text.length());
        assertEquals("true", md.get("msoffice:vba-capture-limit-reached"),
                "a document whose macro source was cut must say so on its own metadata");
        assertNotNull(md.get(TikaCoreProperties.TRUNCATED_METADATA));
    }

    /** With a budget that fits, the same carrier must come through with no flag. */
    @Test
    void testCarrierWithinBudgetIsNotFlagged() throws Exception {
        byte[] carrier = replaceVbaProject(readResource("/test-documents/testWORD_macros.docm"),
                new VbaProjectBuilder().module("Module1", SRC).build());
        Metadata md = new Metadata();
        String text = parse(carrier, md);
        assertTrue(text.contains("powershell"),
                "the injected macro must be extracted; got " + text.length() + " chars");
        assertNull(md.get("msoffice:vba-capture-limit-reached"),
                "nothing was withheld, so the flag must not fire");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String parse(byte[] bytes, Metadata md) throws Exception {
        BodyContentHandler handler = new BodyContentHandler(-1);
        try (TikaInputStream tis = TikaInputStream.get(bytes)) {
            new AutoDetectParser().parse(tis, handler, md, new ParseContext());
        }
        return handler.toString();
    }

    private static byte[] readResource(String name) throws Exception {
        try (InputStream in = VbaBudgetTest.class.getResourceAsStream(name)) {
            assertNotNull(in, "missing test resource " + name);
            return in.readAllBytes();
        }
    }

    /** Rebuild an OOXML package with {@code vbaProject} in place of its existing one. */
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
