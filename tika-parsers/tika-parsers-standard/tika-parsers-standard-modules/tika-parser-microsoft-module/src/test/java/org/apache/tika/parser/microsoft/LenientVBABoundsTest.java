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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;

/**
 * Bounds on VBA extraction must be REPORTED, not silent.
 *
 * <p>Every size guard in {@link LenientVBAReader} used to fail quietly: an over-cap stream
 * returned {@code null} and the module's entire source vanished, and the decompressor
 * {@code break}s mid-stream leaving a partial macro body. Either way a triage consumer
 * received a short-but-plausible macro with nothing indicating evidence had been withheld
 * -- the same defect class as the XLM capture caps, on the VBA path. These tests pin the
 * signal, and that the bound is configurable.
 */
public class LenientVBABoundsTest {

    /**
     * Build an MS-OVBA container of {@code chunks} UNCOMPRESSED chunks, each decompressing
     * to 4096 bytes. Header 0x3FFD: signature bits (>>12)&0x07 == 0b011 as the format
     * requires, compressed-flag (0x8000) clear, size field 4093 (+3 == 4096).
     */
    private static byte[] rawChunks(int chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x01); // compressed-container signature
        for (int c = 0; c < chunks; c++) {
            out.write(0xFD);
            out.write(0x3F);
            for (int i = 0; i < 4096; i++) {
                out.write('A' + (c % 26));
            }
        }
        return out.toByteArray();
    }

    @Test
    void testDecompressTruncationIsReported() throws Exception {
        byte[] container = rawChunks(3); // decompresses to 12,288 bytes

        LenientVBAReader.Bounds tight = new LenientVBAReader.Bounds(4096);
        byte[] cut = LenientVBAReader.decompress(container, 0, tight);
        assertTrue(tight.isLimitReached(),
                "truncating a macro body mid-stream must be reported -- a partial body still "
                        + "reads as a complete macro to everything downstream");
        assertTrue(cut.length < 12_288,
                "fixture must actually trip the bound, else this test is vacuous");

        LenientVBAReader.Bounds roomy = new LenientVBAReader.Bounds(1024 * 1024);
        byte[] whole = LenientVBAReader.decompress(container, 0, roomy);
        assertFalse(roomy.isLimitReached(),
                "a body that fits must NOT be reported as truncated");
        assertEquals(12_288, whole.length, "the whole body must survive when it fits");
    }

    @Test
    void testOversizeModuleStreamDropIsReported() throws Exception {
        byte[] big = new byte[8192];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) ('A' + (i % 26));
        }
        byte[] poifsBytes;
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            DirectoryNode vba = (DirectoryNode) fs.getRoot().createDirectory("VBA");
            vba.createDocument("dir", new ByteArrayInputStream(big));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            fs.writeFilesystem(bos);
            poifsBytes = bos.toByteArray();
        }

        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(poifsBytes))) {
            LenientVBAReader.Bounds tight = new LenientVBAReader.Bounds(64);
            LenientVBAReader.readMacros(fs.getRoot(), tight);
            assertTrue(tight.isLimitReached(),
                    "dropping an over-cap VBA stream discards the WHOLE module source; "
                            + "doing so silently is indistinguishable from 'no macros'");
            assertTrue(tight.getLimitDetail() != null
                            && tight.getLimitDetail().contains("dir"),
                    "the report should name the stream that was dropped; got: "
                            + tight.getLimitDetail());
        }
    }

    @Test
    void testBoundIsConfigurableAndZeroMeansDefault() {
        assertEquals(LenientVBAReader.MAX_STREAM_BYTES,
                new LenientVBAReader.Bounds(0).max(),
                "0 must select the built-in default, matching the other XLM/VBA knobs");
        assertEquals(LenientVBAReader.MAX_STREAM_BYTES,
                new LenientVBAReader.Bounds(-1).max(),
                "a negative value must not disable or invert the bound");
        assertEquals(4096, new LenientVBAReader.Bounds(4096).max());

        OfficeParserConfig cfg = new OfficeParserConfig();
        assertEquals(LenientVBAReader.MAX_STREAM_BYTES,
                LenientVBAReader.Bounds.fromConfig(cfg).max(),
                "an unset config must yield the default");
        cfg.setVbaMaxStreamBytes(32 * 1024 * 1024);
        assertEquals(32 * 1024 * 1024, LenientVBAReader.Bounds.fromConfig(cfg).max(),
                "a forensics deployment must be able to raise the VBA stream bound");
        assertEquals(LenientVBAReader.MAX_STREAM_BYTES,
                LenientVBAReader.Bounds.fromConfig(null).max(),
                "a null config must yield the default, not an unbounded read");
    }

    /** The no-Bounds overloads must still work for existing callers. */
    @Test
    void testLegacyOverloadsStillBehave() throws Exception {
        byte[] container = rawChunks(1);
        assertEquals(4096, LenientVBAReader.decompress(container).length);
        assertEquals("A".repeat(4096),
                new String(LenientVBAReader.decompress(container), StandardCharsets.US_ASCII));
    }
}
