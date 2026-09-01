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
package org.apache.tika.parser.strings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

/**
 * {@link Latin1StringsParser#parse} delegates to a freshly constructed instance, which is what
 * keeps its mutable decode buffers safe on a shared parser. That fresh instance must still carry
 * the configuration the caller set.
 */
public class Latin1StringsParserConfigTest {

    /** Two runs of 8 printable chars separated by a byte that is not a valid Latin-1 char. */
    private static byte[] input() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write("ABCDEFGH".getBytes(StandardCharsets.ISO_8859_1));
        bos.write(0x01);
        bos.write("IJKLMNOP".getBytes(StandardCharsets.ISO_8859_1));
        return bos.toByteArray();
    }

    private static String parseWithMinSize(int minSize) throws Exception {
        Latin1StringsParser parser = new Latin1StringsParser();
        parser.setMinSize(minSize);
        BodyContentHandler handler = new BodyContentHandler();
        try (TikaInputStream tis = TikaInputStream.get(input())) {
            parser.parse(tis, handler, new Metadata(), new ParseContext());
        }
        return handler.toString();
    }

    @Test
    public void theDefaultMinSizeKeepsShortRuns() throws Exception {
        String out = parseWithMinSize(4);
        assertTrue(out.contains("ABCDEFGH"),
                "an 8-char run should survive the default 4-char floor: " + out);
    }

    /**
     * The configured floor must reach the per-document instance. It did not: parse() constructed
     * the delegate with {@code new Latin1StringsParser()} and never copied minSize, so every
     * parse ran at the default of 4 no matter what the caller configured.
     */
    @Test
    public void aConfiguredMinSizeIsHonoured() throws Exception {
        String out = parseWithMinSize(64);
        assertFalse(out.contains("ABCDEFGH"),
                "setMinSize(64) was discarded -- an 8-char run came back anyway, so the parse ran "
                        + "at the default floor of 4: " + out);
        assertFalse(out.contains("IJKLMNOP"),
                "setMinSize(64) was discarded for the second run too: " + out);
    }

    /** More than one 64 KB buffer of contiguous printable bytes. */
    private static byte[] longRun(int len) {
        byte[] b = new byte[len];
        java.util.Arrays.fill(b, (byte) 'A');
        return b;
    }

    /**
     * Floors at or above the fixed 64 KB buffer must decode, not crash.
     *
     * <p>Honouring setMinSize() made these reachable for the first time. Against a fixed buffer,
     * minSize == 65536 left the flush freeing nothing -- outPos stayed 0, tmpPos stayed at the
     * buffer length -- and the next printable byte ran output[tmpPos++] off the end:
     * {@code ArrayIndexOutOfBoundsException: Index 65536 out of bounds for length 65536}.
     * The buffer is now sized from the floor, so these simply work.
     */
    @Test
    public void floorsAtAndAboveTheDefaultBufferDecodeCleanly() throws Exception {
        for (int minSize : new int[] {40_000, 65_535, 65_536, 200_000}) {
            Latin1StringsParser parser = new Latin1StringsParser();
            parser.setMinSize(minSize);
            BodyContentHandler handler = new BodyContentHandler(-1);
            try (TikaInputStream tis = TikaInputStream.get(longRun(minSize * 3))) {
                parser.parse(tis, handler, new Metadata(), new ParseContext());
            }
            assertTrue(handler.toString().length() > minSize,
                    "minSize=" + minSize + ": a run three times the floor should come back, got "
                            + handler.toString().length() + " chars");
        }
    }

    /** Only values that are meaningless or unbounded allocations are refused. */
    @Test
    public void onlyGenuinelyUnusableFloorsAreRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new Latin1StringsParser().setMinSize(0),
                "a floor below 1 is meaningless");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Latin1StringsParser().setMinSize(64 * 1024 * 1024));
        assertTrue(e.getMessage().contains("allocation"),
                "the message should explain that the buffers scale with the floor: "
                        + e.getMessage());

        // 40000 decodes perfectly well against a resized buffer and must NOT be refused: a
        // deployment whose XML config carried it previously started fine (the value was ignored),
        // and turning that into a construction-time throw would be a worse regression than the
        // silent discard it replaced.
        new Latin1StringsParser().setMinSize(40_000);
    }

    /**
     * The largest ACCEPTED floor must survive input several buffers long. This is the case that
     * would crash if the bound were set at BUF_SIZE - 1 instead of BUF_SIZE / 2.
     */
    @Test
    public void theLargestAllowedMinSizeParsesInputSeveralBuffersLong() throws Exception {
        Latin1StringsParser parser = new Latin1StringsParser();
        parser.setMinSize(32768);
        BodyContentHandler handler = new BodyContentHandler(-1);
        try (TikaInputStream tis = TikaInputStream.get(longRun(400_000))) {
            parser.parse(tis, handler, new Metadata(), new ParseContext());
        }
        assertTrue(handler.toString().length() > 100_000,
                "a 400 KB run of printable bytes should come back largely intact, got "
                        + handler.toString().length() + " chars");
    }
}
