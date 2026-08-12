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

    /**
     * A module whose declared offset lands on a byte that is NOT a container signature sends POI
     * down {@code findCompressedStreamWBruteForce}, which scans every byte position for 0x01
     * followed by a valid chunk header and decompresses at each one with an unbounded
     * {@code IOUtils.toByteArray}. A projection that only walks the declared offset does not bound
     * that, and the gap was a measured bypass of the whole budget: this exact shape at 16 bombs
     * projected at 393 KB, cleared the 32 MB budget, and then produced 16,769,024 chars from POI.
     */
    @Test
    void testBruteForceCandidatesAreProjected() throws Exception {
        long budget = 32L * 1024 * 1024;

        byte[] loaded = offsetOnFillerWithBombs(16, 4096);
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(loaded))) {
            long projected = LenientVBAReader.projectDecompressedBytes(fs, budget);
            assertTrue(projected > budget,
                    "bomb containers reachable only by POI's brute-force search must count "
                            + "towards the projection; got " + projected + " for a document whose "
                            + "streams decompress to far more");
        }
        // The same shape is what POI would actually expand, so confirm the fixture is not a paper
        // tiger: POI must really produce far more than the document's size.
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(loaded));
             VBAMacroReader reader = new VBAMacroReader(fs)) {
            assertTrue(totalChars(reader.readMacros()) > 4L * 1024 * 1024,
                    "fixture sanity: POI must expand this document, else the test proves nothing");
        }

        // NEGATIVE CONTROL: a malformed offset with no bomb behind it must NOT be pushed over the
        // ceiling. Charging every odd offset as if it were a bomb would redirect real documents.
        byte[] harmless = offsetOnFillerWithBombs(1, 2);
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(harmless))) {
            long projected = LenientVBAReader.projectDecompressedBytes(fs, budget);
            assertTrue(projected <= budget,
                    "a malformed offset with nothing large behind it must still clear the "
                            + "budget; got " + projected);
        }
    }

    /**
     * A project whose single module declares an offset landing on filler (not 0x01), with
     * {@code copies} bomb containers further along the stream -- the shape that reaches POI's
     * brute-force search.
     */
    private static byte[] offsetOnFillerWithBombs(int copies, int chunksEach) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (int i = 0; i < 40; i++) {
            payload.write(0xAA); // the declared offset lands here
        }
        byte[] bomb = VbaProjectBuilder.ratioBombContainer(chunksEach);
        for (int c = 0; c < copies; c++) {
            payload.write(bomb, 0, bomb.length);
            payload.write(0xAA); // separator, so each container start is distinct
        }
        return new VbaProjectBuilder().rawModule("Module1", payload.toByteArray()).build();
    }

    /**
     * A stream sitting in the VBA storage that the dir stream never DESCRIBES is still read by POI:
     * {@code readModuleFromDocumentStream} finds no module entry for it, stores its RAW bytes, and
     * {@code getContent()} hands them back as macro source. The projection walked only the refs
     * parsed out of the dir stream, so it counted such a stream as ZERO -- and the gate's guarantee,
     * that POI runs only when the projection clears the document budget, was then false.
     *
     * <p>Measured before the fix: a 41 MB document projected at 4,096 bytes, cleared the 32 MB
     * budget, and POI returned 41,943,056 chars. Unlike the brute-force bypass this one is 1:1
     * rather than amplified -- the input has to be as large as the output -- but the document
     * ceiling is simply not applied, which is the one thing the gate exists to do.
     */
    @Test
    void testStreamsAbsentFromTheDirStreamAreProjected() throws Exception {
        byte[] base = new VbaProjectBuilder().module("Module1", "Sub S()\nEnd Sub\n").build();
        byte[] filler = new byte[1024 * 1024];
        java.util.Arrays.fill(filler, (byte) 'Q');
        long budget = 32L * 1024 * 1024;

        byte[] doc = withExtraVbaStreams(base, filler, "undesc", 40);
        // Fixture validity: POI really does hand these back as macro source.
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(doc));
             VBAMacroReader reader = new VBAMacroReader(fs)) {
            assertTrue(totalChars(reader.readMacros()) > budget,
                    "fixture sanity: POI must return more than the budget for this document");
        }
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(doc))) {
            long projected = LenientVBAReader.projectDecompressedBytes(fs, budget);
            assertTrue(projected > budget,
                    "streams POI reads but the dir stream never describes must count towards the "
                            + "projection; got " + projected + " for a document POI expands past "
                            + "the budget");
        }

        // NEGATIVE CONTROL: the streams POI deliberately SKIPS must not be charged, or a real
        // project gets redirected by its own performance caches.
        byte[] skipped = withExtraVbaStreams(base, filler, "__SRP_", 8);
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(skipped))) {
            assertTrue(LenientVBAReader.projectDecompressedBytes(fs, budget) <= budget,
                    "__SRP_* is skipped by POI, so charging it would redirect ordinary documents");
        }
    }

    /** Add {@code n} streams named {@code prefix}{@code i} to the project's VBA storage. */
    private static byte[] withExtraVbaStreams(byte[] project, byte[] content, String prefix, int n)
            throws Exception {
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
            org.apache.poi.poifs.filesystem.DirectoryNode vba =
                    (org.apache.poi.poifs.filesystem.DirectoryNode) fs.getRoot().getEntry("VBA");
            for (int i = 0; i < n; i++) {
                vba.createDocument(prefix + i, new ByteArrayInputStream(content));
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            fs.writeFilesystem(bos);
            return bos.toByteArray();
        }
    }

    /**
     * When the projection's OWN caps truncate its walk it must fail closed, because POI has no
     * counterpart to them.
     *
     * <p>{@code MAX_MODULE_REFS} was added to bound POI's per-stream quadratic, and the projection
     * reuses the same {@code parseDir}. POI's {@code processDirStream} registers every MODULEOFFSET
     * with no count limit, so a dir stream describing 4,096 trivial modules and then a bomb projects
     * only the first 4,096 -- under the ceiling -- and clears POI to decompress the bomb. Measured by
     * an external reviewer: ~18 MB projected against a 32 MB ceiling for a project whose last module
     * expands to ~819 MB, OOM on a 512 MB heap. A cap on what we READ is a deliberate loss with a
     * mark; a cap on what we PROJECT silently destroys the upper-bound property the gate depends on.
     */
    @Test
    void testProjectionFailsClosedWhenItsOwnCapTruncatesTheWalk() throws Exception {
        // 4,100 refs all pointing at one small stream, so the dir stream exceeds MAX_MODULE_REFS
        // while the document stays tiny. The projection must refuse to vouch for it.
        byte[] project = new VbaProjectBuilder()
                .refsToOneStream("M", 4100, "Sub S()\n  Shell \"calc\"\nEnd Sub\n")
                .build();
        long budget = 32L * 1024 * 1024;
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
            long projected = LenientVBAReader.projectDecompressedBytes(fs, budget);
            assertTrue(projected > budget,
                    "a dir stream past MAX_MODULE_REFS cannot be projected, so the gate must fail "
                            + "CLOSED rather than report the partial sum; got " + projected);
        }

        // NEGATIVE CONTROL: a dir stream comfortably under the cap must still be projected
        // truthfully, or every ordinary document is routed away from POI.
        byte[] ordinary = new VbaProjectBuilder()
                .refsToOneStream("M", 100, "Sub S()\nEnd Sub\n")
                .build();
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(ordinary))) {
            assertTrue(LenientVBAReader.projectDecompressedBytes(fs, budget) <= budget,
                    "100 refs is far under the cap; this document must still clear the budget");
        }
    }

    /**
     * The projection's fail-closed paths, actually exercised.
     *
     * <p>The previous version of this test was named FailsClosed and asserted
     * {@code assertEquals(0, ...)} on a fixture that parses cleanly to zero modules -- the OPPOSITE
     * of the property, and all three fail-closed returns had zero coverage: mutating them to
     * {@code return 0} left the whole suite green while clearing POI's unbounded reader on documents
     * the projection could not vouch for. Two real fail-closed triggers are covered here.
     */
    @Test
    void testProjectionFailsClosedOnWhatItCannotVouchFor() throws Exception {
        long ceiling = 1024 * 1024;

        // (a) More brute-force candidate container starts than we are willing to enumerate. Every
        // 0x01 followed by a valid chunk header is a candidate, so a run of them trips the cap.
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (int i = 0; i < 40; i++) {
            payload.write(0xAA); // the declared offset lands on filler, so POI brute-forces
        }
        for (int i = 0; i < LenientVBAReader.MAX_BRUTE_FORCE_CANDIDATES + 50; i++) {
            payload.write(0x01);
            payload.write(0x01);
            payload.write(0x30); // header 0x3001: valid signature, 2 data bytes
            payload.write(0x00);
            payload.write(0x00);
        }
        byte[] manyCandidates =
                new VbaProjectBuilder().rawModule("Module1", payload.toByteArray()).build();
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(manyCandidates))) {
            long projected = LenientVBAReader.projectDecompressedBytes(fs, ceiling);
            assertTrue(projected > ceiling,
                    "more than MAX_BRUTE_FORCE_CANDIDATES container starts cannot be enumerated, so "
                            + "the projection must fail CLOSED; got " + projected);
        }

        // (b) A dir stream we cannot parse at all must not read as "nothing to decompress".
        byte[] unparsable;
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            org.apache.poi.poifs.filesystem.DirectoryNode vba =
                    (org.apache.poi.poifs.filesystem.DirectoryNode) fs.getRoot()
                            .createDirectory("VBA");
            vba.createDocument("dir", new ByteArrayInputStream(new byte[] {0x01, 0x00}));
            // A module stream POI will read (raw, since the dir describes nothing) -- so a
            // projection of 0 would be a lie about what POI retains.
            byte[] big = new byte[2 * 1024 * 1024];
            java.util.Arrays.fill(big, (byte) 'Z');
            vba.createDocument("Module1", new ByteArrayInputStream(big));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            fs.writeFilesystem(bos);
            unparsable = bos.toByteArray();
        }
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(unparsable))) {
            long projected = LenientVBAReader.projectDecompressedBytes(fs, ceiling);
            assertTrue(projected > ceiling,
                    "a document whose streams POI will read raw must be projected at their size, "
                            + "not at zero; got " + projected);
        }
    }

    // ── the document budget ─────    // ── the document budget ─────────────────────────────────────────────────

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
     * STARVATION: when the budget runs out, the modules that get dropped must not be decided by
     * the attacker-controlled ORDER of the dir stream.
     *
     * <p>Measured before this was fixed: identical bytes and an identical budget, with twenty
     * filler modules and one payload module, recovered the payload when it was listed FIRST and
     * lost it entirely when listed LAST. The flag fired either way, so it was not silent -- but
     * "some macro source was truncated" is a very different statement from "the module containing
     * the Shell call is the part we dropped", and the analyst cannot tell them apart.
     *
     * <p>What this case pins is that the scan CONTINUES past a module too big to fit rather than
     * stopping there, so a later small payload is still reached. The separate mechanism -- keeping a
     * too-big payload as indicator lines out of a reserve -- is pinned by
     * {@link #testOversizePayloadIsKeptAsIndicatorLinesFromTheReserve}. Keeping them apart matters:
     * an earlier version of this test passed with the reserve removed entirely, because in this
     * fixture the payload is small enough to fit in what the filler modules leave behind.
     */
    @Test
    void testPayloadSurvivesTheBudgetRegardlessOfModuleOrder() throws Exception {
        String junk = "' harmless filler line\n".repeat(500);
        String payload = "Sub AutoOpen()\n  Shell \"powershell -enc EVIL\"\nEnd Sub\n";

        for (boolean payloadLast : new boolean[] {false, true}) {
            VbaProjectBuilder b = new VbaProjectBuilder();
            if (!payloadLast) {
                b.module("Payload", payload);
            }
            for (int i = 0; i < 20; i++) {
                b.module("Junk" + i, junk);
            }
            if (payloadLast) {
                b.module("Payload", payload);
            }
            byte[] project = b.build();

            try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
                LenientVBAReader.Bounds bounds =
                        new LenientVBAReader.Bounds(64 * 1024 * 1024, 50_000);
                Map<String, String> macros = LenientVBAReader.readMacros(fs, bounds);
                boolean found = macros.values().stream().anyMatch(v -> v.contains("EVIL"));
                assertTrue(found,
                        "payload listed " + (payloadLast ? "LAST" : "FIRST")
                                + ": the Shell line must survive the budget -- otherwise the "
                                + "dir stream's ordering, which the author chooses, decides "
                                + "whether the payload is reported. Kept: " + macros.keySet());
                assertTrue(bounds.isLimitReached(),
                        "truncation still has to be reported");
                assertTrue(totalChars(macros) <= 50_000,
                        "the reserve must be carved OUT of the budget, not added to it; got "
                                + totalChars(macros));
                assertEquals(totalChars(macros), bounds.retainedBytes(),
                        "charged bytes must still equal stored bytes in the reserve phase");
            }
        }
    }

    /**
     * A payload module too big to fit in what is left must still yield its indicator lines, out of
     * the reserve -- otherwise "too big to fit" is a way to be dropped entirely, and an author who
     * pads the payload module gets the same silence as before.
     */
    @Test
    void testOversizePayloadIsKeptAsIndicatorLinesFromTheReserve() throws Exception {
        // Every module is far too big to fit whole in the budget below, and each carries MANY
        // indicator lines -- enough that the reserve and the per-module cap both bind. With one
        // indicator line per module they would not, and two mutations (reserve added instead of
        // carved out, per-module cap removed) survived against that weaker fixture.
        StringBuilder body = new StringBuilder();
        for (int line = 0; line < 600; line++) {
            body.append("' filler line inside the payload module\n");
            body.append("  Shell \"powershell -enc EVIL").append(line).append("\"\n");
        }
        VbaProjectBuilder b = new VbaProjectBuilder();
        for (int i = 0; i < 12; i++) {
            b.module("Mod" + i, body.toString());
        }
        byte[] project = b.build();

        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
            LenientVBAReader.Bounds bounds = new LenientVBAReader.Bounds(64 * 1024 * 1024, 60_000);
            Map<String, String> macros = LenientVBAReader.readMacros(fs, bounds);

            long indicatorEntries = macros.values().stream()
                    .filter(v -> v.contains(LenientVBAReader.INDICATORS_ONLY_MARKER)).count();
            assertTrue(indicatorEntries > 0,
                    "modules that cannot be kept whole must still contribute their indicator "
                            + "lines; kept " + macros.keySet());
            assertTrue(macros.values().stream().anyMatch(v -> v.contains("EVIL")),
                    "the Shell line is the whole point; it must appear somewhere");
            assertTrue(indicatorEntries >= 3,
                    "the per-module cap must let SEVERAL modules share the reserve rather than "
                            + "letting the first one take it all; got " + indicatorEntries
                            + " indicator entries");
            assertTrue(totalChars(macros) <= 60_000,
                    "the reserve must be carved OUT of the budget, not added to it; got "
                            + totalChars(macros));
            assertEquals(totalChars(macros), bounds.retainedBytes(),
                    "reserve entries must be charged exactly like ordinary ones");
            assertTrue(bounds.isLimitReached(), "the truncation must still be reported");
        }
    }

    /**
     * A module that does not fit must yield SOMETHING -- indicator lines when it has them, a bounded
     * prefix when it does not.
     *
     * <p>This test previously asserted the opposite (that a module with no indicator lines gets no
     * reserve entry at all), and that rule turned the budget into a total-loss switch: a bomb carrier
     * whose every module exceeds the budget and whose bodies contain no newline and no indicator
     * yielded 21 characters in total. Withholding a module is exactly when the analyst most needs a
     * fragment of it, so "nothing worth keeping" is not a judgement this layer can make.
     */
    @Test
    void testAModuleThatDoesNotFitStillYieldsAFragment() throws Exception {
        VbaProjectBuilder b = new VbaProjectBuilder();
        String junk = "' harmless filler line\n".repeat(500);
        for (int i = 0; i < 20; i++) {
            b.module("Junk" + i, junk);
        }
        byte[] project = b.build();
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
            LenientVBAReader.Bounds bounds = new LenientVBAReader.Bounds(64 * 1024 * 1024, 50_000);
            Map<String, String> macros = LenientVBAReader.readMacros(fs, bounds);

            long fragments = macros.values().stream()
                    .filter(v -> v.contains(LenientVBAReader.INDICATORS_ONLY_MARKER)).count();
            assertTrue(fragments > 0,
                    "modules that did not fit must appear as bounded fragments, not vanish; kept "
                            + macros.keySet());
            assertTrue(bounds.isLimitReached(), "and the truncation must be reported");
            assertTrue(totalChars(macros) <= 50_000,
                    "the fragments must still respect the budget; got " + totalChars(macros));
            assertEquals(totalChars(macros), bounds.retainedBytes(),
                    "charged bytes must equal stored bytes for fragments too");
        }
    }

    /**
     * A module the tree walk and the orphan scan BOTH reach must be charged once and must never
     * report a truncation.
     *
     * <p>Both routes fill one sink and {@link LenientVBAReader} collapses the second sighting, so it
     * costs nothing -- but the budget was consulted BEFORE the duplicate check, so the second
     * sighting was tested against the ceiling as though it were new. On a document whose retained
     * total plus one module exceeds the ceiling that raises the truncation flag with nothing
     * withheld, and can spend the indicator reserve on a body already stored in full.
     *
     * <p>Found by an external reviewer as "double-charging on the POI-exception fallback"; the
     * mechanism is not two reader calls (those three call sites are mutually exclusive) but the
     * order of the duplicate check against the budget check.
     */
    @Test
    void testAModuleReachedByBothRoutesIsNeitherChargedTwiceNorFlagged() throws Exception {
        // One module of 10,000 chars, and a budget whose main phase has room for it once
        // (10,000 <= 20,000 - 2,500 reserve) but not twice.
        StringBuilder src = new StringBuilder();
        while (src.length() < 10_000) {
            src.append("' filler line of macro source\n");
        }
        String body = src.substring(0, 10_000);
        byte[] project = new VbaProjectBuilder().module("Module1", body).build();

        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
            LenientVBAReader.Bounds bounds = new LenientVBAReader.Bounds(1024 * 1024, 20_000);
            Map<String, String> macros = LenientVBAReader.readMacros(fs, bounds);

            assertEquals(1, macros.size(),
                    "the module must appear exactly once; got " + macros.keySet());
            assertEquals(10_000, totalChars(macros), "the whole module must be retained");
            assertEquals(10_000, bounds.retainedBytes(),
                    "a module both routes reach must be charged ONCE");
            assertFalse(bounds.isLimitReached(),
                    "nothing was withheld -- the second sighting is a duplicate the reader "
                            + "collapses -- so the truncation flag must stay clear; detail was: "
                            + bounds.getLimitDetail());
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
            // Count MODULES, not a generic substring. "contains Sub" survives losing all but one
            // module: mutating the emission loop to emit only the first module of every document
            // left this test green, which is exactly the recall regression it exists to catch.
            int modules = text.split("Attribute VB_Name", -1).length - 1;
            assertTrue(modules >= 2,
                    name + ": every macro module must still be extracted; found " + modules
                            + " module headers in " + text.length() + " chars");
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
        // BOTH bounds. The earlier version asserted only "< 2,000,000" -- ten times the budget and
        // about 10^5 times the observed 21 characters -- so it could not tell "bounded to 200 KB"
        // from "extracted nothing at all", and mutating the over-budget branch to yield no macros
        // whatsoever left it green. A bound with no floor is not a bound.
        assertTrue(text.length() < 400_000,
                "the bomb's output must be bounded near the 200 KB budget; got " + text.length());
        assertTrue(text.contains("Bomb") || text.contains("X"),
                "an over-budget document must still yield the macro content that FITS -- a gate "
                        + "that turns into a total-loss switch is worse than no gate; got "
                        + text.length() + " chars");
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
