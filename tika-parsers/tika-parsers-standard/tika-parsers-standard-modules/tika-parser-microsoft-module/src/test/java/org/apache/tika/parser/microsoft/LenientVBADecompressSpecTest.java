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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.poi.util.RLEDecompressingInputStream;
import org.junit.jupiter.api.Test;

/**
 * {@link LenientVBAReader#decompress} against the MS-OVBA container format, with POI's own
 * {@link RLEDecompressingInputStream} as an INDEPENDENT oracle.
 *
 * <p>Why an oracle rather than hand-written expectations: the lenient decompressor's chunk
 * arithmetic disagreed with the specification and with POI, and hand-written expectations
 * written from the same misreading would have agreed with the bug. MS-OVBA §2.4.1.1.5 defines
 * the stored {@code CompressedChunkSize} as the chunk's TOTAL byte count (including its own
 * 2-byte header) minus 3 -- so the data following the header is {@code field + 1} bytes, which
 * is exactly what POI reads. The lenient reader advanced by {@code field + 3}, overshooting
 * every chunk by 2 bytes, which landed the next read 2 bytes past the following chunk header,
 * failed its signature check, and abandoned the rest of the stream. Net effect: any VBA module
 * whose source needs more than one compressed chunk (i.e. more than ~4 KB) was silently
 * truncated to its first chunk -- on the two paths that exist precisely FOR malware
 * (POI-rejecting projects and orphaned VBA storages).
 */
public class LenientVBADecompressSpecTest {

    private static final int ROOMY = 64 * 1024 * 1024;

    /**
     * One spec-formed UNCOMPRESSED chunk: 4096 data bytes, so the stored size field is
     * 0x0FFF (4096 data + 2 header + ... = 4098 total, stored as 4098 - 3 = 4095).
     */
    private static void rawChunk(ByteArrayOutputStream out, char fill) {
        writeHeader(out, 0x0FFF, false);
        for (int i = 0; i < 4096; i++) {
            out.write(fill);
        }
    }

    /**
     * One spec-formed COMPRESSED chunk holding {@code n} literal bytes and no copy tokens.
     * {@code n} must be small enough that the encoded data (a flag byte per 8 literals) fits
     * in the 4095-byte maximum chunk data length.
     */
    private static void litChunk(ByteArrayOutputStream out, char fill, int n) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int written = 0;
        while (written < n) {
            int group = Math.min(8, n - written);
            data.write(0x00); // flag byte: all eight tokens in this group are literals
            for (int i = 0; i < group; i++) {
                data.write(fill);
            }
            written += group;
        }
        byte[] d = data.toByteArray();
        if (d.length > 4095) {
            throw new IllegalArgumentException("literal run too large for one chunk: " + d.length);
        }
        writeHeader(out, d.length - 1, true);
        out.write(d, 0, d.length);
    }

    /** CompressedChunkHeader, little-endian: size field, signature 0b011, compressed flag. */
    private static void writeHeader(ByteArrayOutputStream out, int sizeField, boolean compressed) {
        int header = 0x3000 | (sizeField & 0x0FFF) | (compressed ? 0x8000 : 0);
        out.write(header & 0xFF);
        out.write((header >> 8) & 0xFF);
    }

    private static byte[] container(boolean compressed, int chunks, int litsPerChunk) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x01); // CompressedContainer signature
        for (int c = 0; c < chunks; c++) {
            if (compressed) {
                litChunk(out, (char) ('A' + c), litsPerChunk);
            } else {
                rawChunk(out, (char) ('A' + c));
            }
        }
        return out.toByteArray();
    }

    /** POI reads the same container format; where we disagree with it, we are wrong. */
    private static void assertMatchesPoi(String what, byte[] compressed) throws Exception {
        byte[] expected = RLEDecompressingInputStream.decompress(compressed);
        byte[] actual = LenientVBAReader.decompress(compressed, 0,
                new LenientVBAReader.Bounds(ROOMY));
        assertEquals(expected.length, actual.length,
                what + ": length disagrees with POI's decompressor -- the shorter one is "
                        + "withholding macro source");
        assertArrayEquals(expected, actual, what + ": bytes disagree with POI's decompressor");
    }

    @Test
    void testMultiChunkCompressedContainerIsNotTruncated() throws Exception {
        // The regression: chunk 2 onward vanished, and the returned body was 2 bytes long
        // in the middle -- a corrupt boundary on top of the loss.
        for (int chunks : new int[] {1, 2, 3, 8}) {
            byte[] c = container(true, chunks, 100);
            byte[] got = LenientVBAReader.decompress(c, 0, new LenientVBAReader.Bounds(ROOMY));
            assertEquals(chunks * 100, got.length,
                    chunks + " compressed chunk(s) of 100 literals must all survive");
            StringBuilder expect = new StringBuilder();
            for (int i = 0; i < chunks; i++) {
                expect.append(String.valueOf((char) ('A' + i)).repeat(100));
            }
            assertEquals(expect.toString(), new String(got, StandardCharsets.ISO_8859_1),
                    "every chunk's payload must appear, in order");
            assertMatchesPoi(chunks + " compressed chunks", c);
        }
    }

    @Test
    void testMultiChunkUncompressedContainerMatchesPoi() throws Exception {
        for (int chunks : new int[] {1, 2, 3}) {
            byte[] c = container(false, chunks, 0);
            assertEquals(chunks * 4096,
                    LenientVBAReader.decompress(c, 0, new LenientVBAReader.Bounds(ROOMY)).length,
                    chunks + " uncompressed chunk(s) must yield 4096 bytes each");
            assertMatchesPoi(chunks + " uncompressed chunks", c);
        }
    }

    /** A container mixing chunk kinds is legal; chunk-length arithmetic must survive it. */
    @Test
    void testMixedChunkKindsMatchPoi() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x01);
        litChunk(out, 'A', 50);
        rawChunk(out, 'B');
        // 3600 literals encode to 3600 + 450 flag bytes = 4050, just under the 4095 max
        litChunk(out, 'C', 3600);
        rawChunk(out, 'D');
        byte[] c = out.toByteArray();
        assertMatchesPoi("mixed raw/compressed chunks", c);
        assertEquals(50 + 4096 + 3600 + 4096,
                LenientVBAReader.decompress(c, 0, new LenientVBAReader.Bounds(ROOMY)).length);
    }

    /**
     * A raw chunk that declares a length other than the well-formed 4096 -- craftable, and
     * the reason the raw branch must honour the DECLARED length rather than assuming 4096.
     * Assuming 4096 reads 3096 bytes past this chunk's end, swallowing the next chunk's header
     * and everything after it.
     */
    @Test
    void testShortRawChunkKeepsTheStreamAlignedWithPoi() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x01);
        writeHeader(out, 1000 - 1, false);   // 1000 data bytes, not 4096
        for (int i = 0; i < 1000; i++) {
            out.write('A');
        }
        litChunk(out, 'B', 300);             // must still be found after the short chunk
        rawChunk(out, 'C');
        byte[] c = out.toByteArray();

        byte[] poi = RLEDecompressingInputStream.decompress(c);
        assertEquals(1000 + 300 + 4096, poi.length,
                "oracle sanity: POI honours the declared chunk length");
        assertMatchesPoi("short raw chunk followed by more chunks", c);
        String text = new String(LenientVBAReader.decompress(c, 0,
                new LenientVBAReader.Bounds(ROOMY)), StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("B".repeat(300)),
                "the chunk after a short raw chunk must not be swallowed");
        assertTrue(text.contains("C".repeat(4096)),
                "the stream must stay aligned all the way to the last chunk");
    }

    /** Copy tokens (back-references) are the format's actual compression; verify against POI. */
    @Test
    void testCopyTokensMatchPoi() throws Exception {
        // 8 literals, then a flag byte whose tokens are all copy tokens repeating them.
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(0x00);
        for (int i = 0; i < 8; i++) {
            data.write('A' + i);
        }
        for (int g = 0; g < 4; g++) {
            data.write(0xFF); // eight copy tokens
            for (int t = 0; t < 8; t++) {
                // offset 8, length 3: bit_count 4 -> offset bits high, length bits low
                int token = ((8 - 1) << 4) | (3 - 3);
                data.write(token & 0xFF);
                data.write((token >> 8) & 0xFF);
            }
        }
        byte[] d = data.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x01);
        writeHeader(out, d.length - 1, true);
        out.write(d, 0, d.length);
        byte[] c = out.toByteArray();
        byte[] poi = RLEDecompressingInputStream.decompress(c);
        assertTrue(poi.length > 8,
                "fixture must actually exercise copy tokens, else this test is vacuous; got "
                        + poi.length);
        assertMatchesPoi("copy tokens", c);
    }

    /**
     * A chunk whose signature bits are wrong is skipped, which silently discards up to 4096 bytes
     * of macro source. POI throws on this input, so it at least tells the caller; being lenient is
     * right for triage, but the loss has to be reported or a short macro looks complete.
     */
    @Test
    void testInvalidChunkSignatureIsReported() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x01);
        litChunk(out, 'A', 100);
        // signature bits 0b010 instead of 0b011 -- the chunk cannot be interpreted
        int bad = 0x8000 | 0x2000 | 50;
        out.write(bad & 0xFF);
        out.write((bad >> 8) & 0xFF);
        for (int i = 0; i < 51; i++) {
            out.write('B');
        }
        litChunk(out, 'C', 100);
        byte[] c = out.toByteArray();

        LenientVBAReader.Bounds bounds = new LenientVBAReader.Bounds(ROOMY);
        String text = new String(LenientVBAReader.decompress(c, 0, bounds),
                StandardCharsets.ISO_8859_1);
        assertTrue(bounds.isLimitReached(),
                "a skipped chunk drops macro source and must be reported");
        assertTrue(text.contains("A".repeat(100)) && text.contains("C".repeat(100)),
                "the chunks around the bad one must still be recovered; got " + text.length()
                        + " chars");
    }

    /**
     * ...but trailing slack after the last chunk is NOT evidence loss. A module stream commonly
     * carries bytes after its final chunk; reading them as a chunk header fails the signature
     * check with nothing withheld. Marking that flagged ~1.3% of a 6,574-document macro corpus --
     * a truncation flag on healthy documents an analyst then learns to ignore.
     */
    @Test
    void testTrailingSlackIsNotReportedAsLoss() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x01);
        litChunk(out, 'A', 100);
        for (int i = 0; i < 40; i++) {
            out.write(0x00); // slack: parses as a chunk header with signature bits 0b000
        }
        byte[] c = out.toByteArray();

        LenientVBAReader.Bounds bounds = new LenientVBAReader.Bounds(ROOMY);
        byte[] got = LenientVBAReader.decompress(c, 0, bounds);
        assertEquals(100, got.length, "the real chunk must be recovered");
        assertFalse(bounds.isLimitReached(),
                "slack past the last chunk withholds nothing, so the flag must stay clear");
    }

    /**
     * The truncation flag must not fire when the LAST chunk is what crossed the bound: nothing was
     * withheld, and a truncation flag on clean documents is as harmful as a missing one -- an
     * analyst who sees it on healthy files learns to ignore it everywhere.
     */
    @Test
    void testBoundDoesNotClaimLossWhenNothingRemained() throws Exception {
        byte[] c = container(true, 2, 100); // 200 bytes of payload, in two chunks
        LenientVBAReader.Bounds justUnder = new LenientVBAReader.Bounds(150);
        byte[] got = LenientVBAReader.decompress(c, 0, justUnder);
        assertEquals(200, got.length,
                "both chunks were already read, so both must be returned");
        assertFalse(justUnder.isLimitReached(),
                "the stream ended at the same point the bound was crossed, so nothing was "
                        + "withheld and the flag must stay clear");

        // ...but with a third chunk waiting, the loss is real and must be reported.
        byte[] longer = container(true, 3, 100);
        LenientVBAReader.Bounds cutting = new LenientVBAReader.Bounds(150);
        assertTrue(LenientVBAReader.decompress(longer, 0, cutting).length < 300);
        assertTrue(cutting.isLimitReached(),
                "a chunk that never got read IS withheld evidence and must be reported");
    }

    /**
     * The bound must still fire on a multi-chunk container -- fixing the chunk arithmetic
     * must not have quietly widened what a bound admits.
     */
    @Test
    void testBoundStillTruncatesAndReportsAcrossChunks() throws Exception {
        byte[] c = container(true, 8, 100); // 800 bytes of payload
        LenientVBAReader.Bounds tight = new LenientVBAReader.Bounds(200);
        byte[] cut = LenientVBAReader.decompress(c, 0, tight);
        assertTrue(tight.isLimitReached(), "a truncated macro body must be reported");
        assertTrue(cut.length < 800, "fixture must actually trip the bound");

        LenientVBAReader.Bounds roomy = new LenientVBAReader.Bounds(ROOMY);
        assertEquals(800, LenientVBAReader.decompress(c, 0, roomy).length);
        assertFalse(roomy.isLimitReached(),
                "a body that fits must NOT be reported as truncated");
    }
}
