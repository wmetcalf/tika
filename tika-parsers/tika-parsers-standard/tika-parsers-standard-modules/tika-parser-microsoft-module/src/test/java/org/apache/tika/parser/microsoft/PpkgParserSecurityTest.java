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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    private static final String COMMAND = "powershell.exe -NoProfile";

    @TempDir
    Path temporaryDirectory;

    private static void put7(byte[] b, int off, long v) {
        for (int i = 0; i < 7; i++) {
            b[off + i] = (byte) ((v >>> (8 * i)) & 0xff);
        }
    }

    private static void parse(byte[] wim) throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(wim)) {
            new PpkgParser().parse(tis, new BodyContentHandler(-1),
                    new Metadata(), new ParseContext());
        }
    }

    private static Metadata parseMetadata(byte[] wim) throws Exception {
        return parseResult(wim).metadata;
    }

    private static ParseResult parseResult(byte[] wim) throws Exception {
        Metadata metadata = new Metadata();
        BodyContentHandler body = new BodyContentHandler(-1);
        try (TikaInputStream tis = TikaInputStream.get(wim)) {
            new PpkgParser().parse(tis, body, metadata, new ParseContext());
        }
        return new ParseResult(body.toString(), metadata);
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

    @Test
    public void singleQuotedCommandAttributesAreCanonical() throws Exception {
        String xml = """
                <wap-provisioningdoc xmlns:p="urn:test">
                  <p:characteristic>
                    <p:parm name='CommandLine' value='powershell.exe -NoProfile'/>
                  </p:characteristic>
                </wap-provisioningdoc>
                """;

        Metadata metadata = parseMetadata(buildWim(xml));
        assertEquals(COMMAND, metadata.get("ppkg:command"));
        assertNotNull(metadata.get("ExploitClass"));
    }

    @Test
    public void externalXmlEntitiesAreNotResolved() throws Exception {
        String secret = "PPKG_XXE_SECRET_SHOULD_NOT_LEAK";
        Path secretFile = temporaryDirectory.resolve("secret.txt");
        Files.writeString(secretFile, secret, StandardCharsets.UTF_8);
        String xml = """
                <!DOCTYPE provisioning [
                  <!ENTITY xxe SYSTEM "%s">
                ]>
                <provisioning><CommandLine>&xxe;</CommandLine></provisioning>
                """.formatted(secretFile.toUri());

        ParseResult result = parseResult(buildWim(xml));
        assertFalse(result.body.contains(secret));
        assertEquals(0, result.metadata.getValues("ppkg:command").length);
    }

    private static byte[] buildWim(String xml) {
        byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);
        int lookupOffset = 208;
        int lookupLength = 100;
        int metadataOffset = lookupOffset + lookupLength;
        int metadataLength = 256;
        int xmlOffset = metadataOffset + metadataLength;
        byte[] wim = new byte[xmlOffset + xmlBytes.length];
        ByteBuffer buffer = ByteBuffer.wrap(wim).order(ByteOrder.LITTLE_ENDIAN);

        System.arraycopy(PpkgParser.WIM_MAGIC, 0, wim, 0, PpkgParser.WIM_MAGIC.length);
        buffer.putInt(16, 0x00020000);
        buffer.putInt(20, 32768);
        buffer.putInt(44, 1);
        putResourceHeader(wim, buffer, 48, lookupLength, 0,
                lookupOffset, lookupLength);

        byte[] metadataHash = repeated((byte) 0x11);
        byte[] xmlHash = repeated((byte) 0x42);
        putLookupEntry(wim, buffer, lookupOffset, metadataLength, 0x04,
                metadataOffset, metadataLength, metadataHash);
        putLookupEntry(wim, buffer, lookupOffset + 50, xmlBytes.length, 0,
                xmlOffset, xmlBytes.length, xmlHash);

        buffer.putInt(metadataOffset, 8);
        buffer.putLong(metadataOffset + 8 + 16, 32);

        byte[] nameBytes = "payload.provxml".getBytes(StandardCharsets.UTF_16LE);
        int dentry = metadataOffset + 32;
        buffer.putLong(dentry, 102L + nameBytes.length);
        System.arraycopy(xmlHash, 0, wim, dentry + 64, xmlHash.length);
        buffer.putShort(dentry + 100, (short) nameBytes.length);
        System.arraycopy(nameBytes, 0, wim, dentry + 102, nameBytes.length);
        System.arraycopy(xmlBytes, 0, wim, xmlOffset, xmlBytes.length);
        return wim;
    }

    private static void putResourceHeader(byte[] bytes, ByteBuffer buffer, int offset,
                                          long size, int flags, long dataOffset,
                                          long uncompressed) {
        put7(bytes, offset, size);
        bytes[offset + 7] = (byte) flags;
        buffer.putLong(offset + 8, dataOffset);
        buffer.putLong(offset + 16, uncompressed);
    }

    private static void putLookupEntry(byte[] bytes, ByteBuffer buffer, int offset,
                                       long size, int flags, long dataOffset,
                                       long uncompressed, byte[] sha1) {
        putResourceHeader(bytes, buffer, offset, size, flags, dataOffset, uncompressed);
        System.arraycopy(sha1, 0, bytes, offset + 30, sha1.length);
    }

    private static byte[] repeated(byte value) {
        byte[] bytes = new byte[20];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private record ParseResult(String body, Metadata metadata) {
    }
}
