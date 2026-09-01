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
     * A floor at or above the decode buffer cannot make progress, and must be refused where the
     * caller can see it.
     *
     * <p>Honouring setMinSize() made this configuration reachable for the first time. At
     * minSize == BUF_SIZE the flush frees nothing -- outPos stays 0, tmpPos stays at BUF_SIZE --
     * and the next printable byte runs output[tmpPos++] off the end:
     * {@code ArrayIndexOutOfBoundsException: Index 65536 out of bounds for length 65536},
     * observed on 200 KB of contiguous printable bytes.
     */
    @Test
    public void aMinSizeThatCannotMakeProgressIsRefusedAtConfigurationTime() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Latin1StringsParser().setMinSize(65536));
        assertTrue(e.getMessage().contains("32768"),
                "the message should name the largest usable floor: " + e.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> new Latin1StringsParser().setMinSize(0),
                "a floor below 1 is meaningless");
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
