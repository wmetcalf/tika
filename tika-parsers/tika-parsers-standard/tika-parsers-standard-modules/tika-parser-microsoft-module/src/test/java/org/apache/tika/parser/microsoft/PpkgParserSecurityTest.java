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

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

/**
 * Adversarial-input regression tests for {@link PpkgParser} (fork-authored WIM/PPKG
 * parser). Each test feeds a crafted malformed WIM and asserts the Tika parser
 * contract: on hostile input the parser must either extract what it can or throw a
 * {@link TikaException} — it must NEVER escape a raw unchecked {@link RuntimeException}
 * to the caller, and must NEVER hang. Pre-fix these reproduce real defects
 * (uncaught ArrayIndexOutOfBoundsException / NegativeArraySizeException, and an
 * exponential directory-walk hang).
 */
public class PpkgParserSecurityTest {

    private static void put7(byte[] b, int off, long v) {
        for (int i = 0; i < 7; i++) {
            b[off + i] = (byte) ((v >>> (8 * i)) & 0xff);
        }
    }

    private static void parse(byte[] wim) throws Exception {
        new PpkgParser().parse(TikaInputStream.get(wim), new BodyContentHandler(-1),
                new Metadata(), new ParseContext());
    }

    /** Run parse(); pass if it completes or throws TikaException; fail on a raw RuntimeException. */
    private static void assertContractHeld(byte[] wim, String label) {
        try {
            parse(wim);
        } catch (TikaException e) {
            // acceptable: graceful failure
        } catch (RuntimeException e) {
            fail(label + ": parser leaked a raw " + e.getClass().getName()
                    + " (Tika contract requires TikaException): " + e);
        } catch (Exception e) {
            // IOException/SAXException are declared/acceptable
        }
    }

    private static byte[] header(int size, int chunkSize) {
        byte[] b = new byte[Math.max(size, 208)];
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        // WIM magic "MSWIM\0\0\0"
        byte[] magic = {0x4d, 0x53, 0x57, 0x49, 0x4d, 0x00, 0x00, 0x00};
        System.arraycopy(magic, 0, b, 0, magic.length);
        buf.putInt(16, 0x00020000);   // XPRESS flag
        buf.putInt(20, chunkSize);
        return b;
    }

    // ── Finding: line 148 — xmlHdr.offset never bounds-checked ────────────────
    @Test
    public void xmlDescriptorOffsetOutOfRange() {
        byte[] b = header(208, 0);
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        put7(b, 72, 100);              // xmlHdr.size = 100 (passes 0<size<4MB)
        buf.putLong(80, 100_000L);     // xmlHdr.offset = 100000, far past the 208-byte file
        assertContractHeld(b, "xml-descriptor-offset");
    }

    // ── Finding: line 298 — XPRESS nChunks integer overflow ───────────────────
    @Test
    public void chunkCountIntegerOverflow() {
        // chunkSize = Integer.MAX_VALUE, resource uncompressed = 2 ->
        // nChunks = (2 + MAX - 1)/MAX overflows to a negative value.
        int fileLen = 300;
        byte[] b = header(fileLen, Integer.MAX_VALUE);
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        // lookupHdr @48: size=50 (1 entry), offset=208, uncompressed=50
        put7(b, 48, 50);
        buf.putLong(48 + 8, 208);
        buf.putLong(48 + 16, 50);
        // lookup entry @208: size=10 (compressed on disk), flags=0x06 (METADATA|COMPRESSED),
        // offset=258, uncompressed=2
        put7(b, 208, 10);
        b[208 + 7] = 0x06;
        buf.putLong(208 + 8, 258);
        buf.putLong(208 + 16, 2);
        assertContractHeld(b, "nchunks-overflow");
    }

    // ── Finding: line 585 — walkDirectory exponential fan-out (no visited-set) ─
    @Test
    public void selfReferentialDirectoryWalkTerminates() {
        int metaOff = 258;
        int metaLen = 256;
        int fileLen = metaOff + metaLen;
        byte[] b = header(fileLen, 0);
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        // lookupHdr @48: one uncompressed METADATA entry
        put7(b, 48, 50);
        buf.putLong(48 + 8, 208);
        buf.putLong(48 + 16, 50);
        // lookup entry @208: size==uncompressed (uncompressed metadata), flags=0x04
        put7(b, 208, metaLen);
        b[208 + 7] = 0x04;
        buf.putLong(208 + 8, metaOff);
        buf.putLong(208 + 16, metaLen);
        // metadata resource @258: sdLen=8 -> rootOff=8; root dentry childOff @ rootOff+16 = 32
        int m = metaOff;
        buf.putInt(m + 0, 8);          // sdLen
        buf.putLong(m + 8 + 16, 32);   // root dentry (@rootOff=8) subdir_offset -> listing @32
        // listing @32: two directory dentries both pointing subdir_offset back to 32
        int d1 = m + 32;
        buf.putLong(d1, 104);          // entryLen
        buf.putInt(d1 + 8, 0x10);      // attrs = directory
        buf.putLong(d1 + 16, 32);      // subdir_offset -> back to this listing
        int d2 = m + 136;
        buf.putLong(d2, 104);
        buf.putInt(d2 + 8, 0x10);
        buf.putLong(d2 + 16, 32);
        // terminator @240: entryLen 0 (already zero-filled)
        assertTimeoutPreemptively(Duration.ofSeconds(15),
                () -> assertContractHeld(b, "self-referential-walk"),
                "PpkgParser.walkDirectory did not terminate on a self-referential "
                        + "directory listing (exponential fan-out DoS)");
    }
}
