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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;

import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.writefilter.MetadataWriteLimiterFactory;
import org.apache.tika.metadata.writefilter.StandardMetadataLimiterFactory;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

public class WinShortcutParserTest {

    private static final int HEADER_SIZE = 76;
    private static final int SIG_CONSOLE_FE = 0xA0000004;
    private static final int SIG_PROPERTY_STORE = 0xA0000009;
    private static final int SIG_VISTA_IDLIST = 0xA000000C;
    private static final int SIG_TOLERATED_UNKNOWN = 0xA0001337;
    private static final int UNKNOWN_BLOCK_SIZE = 70000;
    private static final byte[] CONTROL_PANEL_GUID = new byte[]{
            0x20, 0x20, (byte) 0xec, 0x21,
            (byte) 0xea, 0x3a,
            0x69, 0x10,
            (byte) 0xa2, (byte) 0xdd, 0x08, 0x00,
            0x2b, 0x30, 0x30, (byte) 0x9d
    };
    private static final byte[] HTML = """
            <!doctype html><html><body>appended-html-payload</body></html>
            """.getBytes(StandardCharsets.US_ASCII);

    @Test
    public void testExtraDataIsNotIncludedInAppendedPayload() throws Exception {
        ParseResult result = parse(buildLnk());

        assertEquals("1252", result.metadata.get("lnk:ConsoleCodePage"));
        assertEquals(Integer.toString(HTML.length),
                result.metadata.get("lnk:AppendedDataSize"));
        assertEquals("text/html",
                result.metadata.get("lnk:AppendedDataMimeType"));
        assertEquals(1, result.embedded.size());
        assertArrayEquals(HTML, result.embedded.get(0));
    }

    @Test
    public void testAppendedMetadataUsesContextLimiter() throws Exception {
        ParseContext context = new ParseContext();
        StandardMetadataLimiterFactory factory = new StandardMetadataLimiterFactory();
        factory.setIncludeFields(Set.of("allowed"));
        context.set(MetadataWriteLimiterFactory.class, factory);
        AtomicBoolean limiterApplied = new AtomicBoolean();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                metadata.set("not-allowed", "probe");
                limiterApplied.set(metadata.get("not-allowed") == null);
                return false;
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext parseContext,
                                      boolean outputHtml) {
                throw new AssertionError("embedded parsing should be disabled");
            }
        });

        try (TikaInputStream stream = TikaInputStream.get(buildLnk())) {
            new WinShortcutParser().parse(
                    stream, new BodyContentHandler(-1), new Metadata(), context);
        }

        assertTrue(limiterApplied.get(),
                "fork-created appended metadata must inherit the context limiter");
    }

    @Test
    public void testOversizedExtraDataLengthDoesNotOverflowCursor() throws Exception {
        byte[] lnk = buildOverflowingExtraDataLnk();
        ParseResult result = parse(lnk);

        assertEquals(Integer.toString(8 + HTML.length),
                result.metadata.get("lnk:AppendedDataSize"));
        assertEquals(1, result.embedded.size());
        assertArrayEquals(
                java.util.Arrays.copyOfRange(lnk, HEADER_SIZE, lnk.length),
                result.embedded.get(0));
    }

    @Test
    public void testLateAppendedExploitIndicatorIsClassified() throws Exception {
        ParseResult result = parse(buildLateIndicatorLnk());

        assertNotNull(result.metadata.get("lnk:ExploitClass"),
                "exploit signatures after the first 64 KiB must not evade classification");
        assertNotNull(result.metadata.get("ExploitClass"),
                "LNK security classifications must use the common metadata channel");
    }

    @Test
    public void testUtf16LittleEndianExploitIndicatorIsClassified() throws Exception {
        ParseResult result = parse(buildUtf16IndicatorLnk(
                StandardCharsets.UTF_16LE, new byte[]{(byte) 0xff, (byte) 0xfe}));

        assertNotNull(result.metadata.get("lnk:ExploitClass"),
                "UTF-16LE appended HTML must not hide execution indicators");
    }

    @Test
    public void testUtf16BigEndianExploitIndicatorIsClassified() throws Exception {
        ParseResult result = parse(buildUtf16IndicatorLnk(
                StandardCharsets.UTF_16BE, new byte[]{(byte) 0xfe, (byte) 0xff}));

        assertNotNull(result.metadata.get("lnk:ExploitClass"),
                "UTF-16BE appended HTML must not hide execution indicators");
    }

    @Test
    public void testBomlessUtf16ExploitIndicatorIsClassified() throws Exception {
        ParseResult result = parse(buildUtf16IndicatorLnk(
                StandardCharsets.UTF_16LE, new byte[0]));

        assertNotNull(result.metadata.get("lnk:ExploitClass"),
                "byte-order heuristics must cover UTF-16 payloads without a BOM");
    }

    @Test
    public void testBomlessUtf16LittleEndianIndicatorAfterNonAsciiPrefixIsClassified()
            throws Exception {
        ParseResult result = parse(buildUtf16IndicatorLnk(
                StandardCharsets.UTF_16LE, new byte[0], "\u4e01".repeat(3_000)));

        assertNotNull(result.metadata.get("lnk:ExploitClass"),
                "a non-ASCII prefix must not hide a later UTF-16LE indicator");
    }

    @Test
    public void testBomlessUtf16BigEndianIndicatorAfterNonAsciiPrefixIsClassified()
            throws Exception {
        ParseResult result = parse(buildUtf16IndicatorLnk(
                StandardCharsets.UTF_16BE, new byte[0], "\u4e01".repeat(3_000)));

        assertNotNull(result.metadata.get("lnk:ExploitClass"),
                "a non-ASCII prefix must not hide a later UTF-16BE indicator");
    }

    @Test
    public void testJavaScriptEscapedExploitIndicatorIsClassified() throws Exception {
        for (String script : List.of(
                "new \\u0041ctiveXObject('WScript.Shell')",
                "new window['\\x41ctiveXObject']('WScript.Shell')",
                "new window['\\101ctiveXObject']('WScript.Shell')")) {
            for (Charset charset : List.of(
                    StandardCharsets.US_ASCII,
                    StandardCharsets.UTF_16LE,
                    StandardCharsets.UTF_16BE)) {
                ParseResult result = parse(buildIndicatorLnk(
                        charset, new byte[0], "", script));

                assertNotNull(result.metadata.get("lnk:ExploitClass"),
                        "JavaScript escapes must not hide indicators in " + charset);
            }
        }
    }

    @Test
    public void testHtmlEntityEncodedExploitIndicatorIsClassified() throws Exception {
        for (String script : List.of(
                "new &#x41;ctiveXObject('WScript.Shell')",
                "new &#65;ctiveXObject('WScript.Shell')",
                "new &bsol;u0041ctiveXObject('WScript.Shell')")) {
            for (Charset charset : List.of(
                    StandardCharsets.US_ASCII,
                    StandardCharsets.UTF_16LE,
                    StandardCharsets.UTF_16BE)) {
                ParseResult result = parse(buildIndicatorLnk(
                        charset, new byte[0], "", script));

                assertNotNull(result.metadata.get("lnk:ExploitClass"),
                        "HTML entities must not hide indicators in " + charset);
            }
        }
    }

    @Test
    public void testJScriptStringNormalizationIndicatorsAreClassified() throws Exception {
        for (String script : List.of(
                "new window['Active\\\nXObject']('WScript.Shell')",
                "new window['Active\\XObject']('WScript.Shell')")) {
            for (Charset charset : List.of(
                    StandardCharsets.US_ASCII,
                    StandardCharsets.UTF_16LE,
                    StandardCharsets.UTF_16BE)) {
                ParseResult result = parse(buildIndicatorLnk(
                        charset, new byte[0], "", script));

                assertNotNull(result.metadata.get("lnk:ExploitClass"),
                        "JScript normalization must not hide indicators in " + charset);
            }
        }
    }

    @Test
    public void testJScriptConstantConcatenationIndicatorsAreClassified() throws Exception {
        for (String script : List.of(
                "new window['Active'+'XObject']('WScript.Shell')",
                "new window['Active'+('XObject')]('WScript.Shell')",
                "new window['Act'+(('ive'))+(('XObject'))]('WScript.Shell')",
                "new window['Act' /* split */ + 'ive' +\n 'XObject']"
                        + "('WScript.Shell')")) {
            for (Charset charset : List.of(
                    StandardCharsets.US_ASCII,
                    StandardCharsets.UTF_16LE,
                    StandardCharsets.UTF_16BE)) {
                ParseResult result = parse(buildIndicatorLnk(
                        charset, new byte[0], "", script));

                assertNotNull(result.metadata.get("lnk:ExploitClass"),
                        "constant string joins must not hide indicators in " + charset);
            }
        }
    }

    @Test
    public void testUnterminatedJoinCommentFailsClosedWithoutRepeatedSuffixScans()
            throws Exception {
        ParseResult result = parse(buildIndicatorLnk(
                StandardCharsets.US_ASCII, new byte[0], "",
                "active'/*".repeat(2_000)));

        assertNotNull(result.metadata.get("lnk:warning"),
                "incomplete JScript join analysis must be signaled");
        assertNotNull(result.metadata.get("lnk:ExploitClass"),
                "an unterminated join comment after an indicator prefix must fail closed");
    }

    @Test
    public void testOverDepthJScriptGroupingFailsClosed() throws Exception {
        ParseResult result = parse(buildIndicatorLnk(
                StandardCharsets.US_ASCII, new byte[0], "",
                "new window['Active' + " + "(".repeat(9)
                        + "'XObject'" + ")".repeat(9) + "]('WScript.Shell')"));

        assertNotNull(result.metadata.get("lnk:warning"),
                "over-depth constant grouping must be reported as incomplete");
        assertNotNull(result.metadata.get("lnk:ExploitClass"),
                "over-depth grouping must not silently reset indicator matching");
    }

    @Test
    public void testUtf16StructuralHtmlExploitIsClassified() throws Exception {
        for (Charset charset : List.of(
                StandardCharsets.US_ASCII,
                StandardCharsets.UTF_16LE,
                StandardCharsets.UTF_16BE)) {
            byte[] bom = charset == StandardCharsets.UTF_16LE
                    ? new byte[]{(byte) 0xff, (byte) 0xfe}
                    : charset == StandardCharsets.UTF_16BE
                            ? new byte[]{(byte) 0xfe, (byte) 0xff} : new byte[0];
            ParseResult result = parse(buildVirtualFolderHtmlLnk(charset, bom));

            assertNotNull(result.metadata.get("lnk:ExploitClass"),
                    "encoded HTML must not hide the structural LNK exploit in " + charset);
        }
    }

    @Test
    public void testOversizedInputIsTruncatedAndSignaled() throws Exception {
        byte[] oversized = new byte[16 * 1024 * 1024 + 1];
        System.arraycopy(buildLnk(), 0, oversized, 0, HEADER_SIZE);

        ParseResult result = parse(oversized);

        assertNotNull(result.metadata.get("lnk:warning"),
                "bounded input must be reported as incomplete");
        assertNotNull(result.metadata.get("lnk:ExploitClass"),
                "truncation must fail closed because late indicators may be hidden");
        assertNotNull(result.metadata.get("ExploitClass"),
                "incomplete LNK analysis must use the common metadata channel");
    }

    @Test
    public void testEncodingHeuristicDoesNotConsumeFollowingPayload() throws Exception {
        ParseResult result = parse(buildStringDataCursorConfusionLnk());

        assertEquals("2044", result.metadata.get("lnk:AppendedDataSize"),
                "alternate decoding must not claim bytes outside the flag-owned field");
        assertNotNull(result.metadata.get("lnk:ExploitClass"),
                "the appended ActiveX payload must remain classifiable");
        assertNotNull(result.metadata.get("ExploitClass"));
    }

    @Test
    public void testPropertyStoreVectorCardinalityIsBoundedAndSignaled() throws Exception {
        ParseResult result = parse(buildPropertyVectorLnk(10_000, 10_000));

        assertNotNull(result.metadata.get("lnk:warning"),
                "large typed vectors must be bounded before object amplification");
        assertNotNull(result.metadata.get("lnk:ExploitClass"),
                "skipped PropertyStore values must fail closed");
        assertNotNull(result.metadata.get("ExploitClass"));
    }

    @Test
    public void testPropertyStoreWideVectorIsBoundedBeforeDecode() throws Exception {
        ParseResult result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> parse(buildPropertyWideStringVectorLnk(100_000)));

        assertNotNull(result.metadata.get("lnk:warning"),
                "wide vector strings must be bounded before allocating their declared size");
        assertNotNull(result.metadata.get("lnk:ExploitClass"));
        assertNotNull(result.metadata.get("ExploitClass"));
    }

    @Test
    public void testPropertyStoreRecordCardinalityIsBoundedAndSignaled() throws Exception {
        ParseResult result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> parse(buildPropertyRecordCardinalityLnk(5_000)));

        assertNotNull(result.metadata.get("lnk:warning"),
                "distinct PropertyStore records must have a parse-wide retention cap");
        assertNotNull(result.metadata.get("lnk:ExploitClass"));
        assertNotNull(result.metadata.get("ExploitClass"));
    }

    @Test
    public void testVistaIdListComponentCardinalityIsBoundedAndSignaled() throws Exception {
        ParseResult result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> parse(buildVistaIdListCardinalityLnk(5_000)));

        assertNotNull(result.metadata.get("lnk:warning"),
                "Vista ID-list path components must have a retained-item cap");
        assertNotNull(result.metadata.get("lnk:ExploitClass"));
        assertNotNull(result.metadata.get("ExploitClass"));
    }

    @Test
    public void testMalformedSectionLengthsFailClosed() throws Exception {
        for (byte[] lnk : List.of(
                buildMalformedIdListLnk(),
                buildMalformedLinkInfoLnk(),
                buildMalformedStringDataLnk(),
                buildOverflowingExtraDataLnk())) {
            ParseResult result = parse(lnk);

            assertNotNull(result.metadata.get("lnk:warning"),
                    "an out-of-bounds declared section must be reported as incomplete");
            assertNotNull(result.metadata.get("lnk:ExploitClass"),
                    "a malformed section must not hide appended execution indicators");
        }
    }

    private static ParseResult parse(byte[] lnk) throws Exception {
        List<byte[]> embedded = new ArrayList<>();
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return true;
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext parseContext,
                                      boolean outputHtml) throws IOException {
                embedded.add(stream.readAllBytes());
            }
        });

        Metadata metadata = new Metadata();
        try (TikaInputStream stream = TikaInputStream.get(lnk)) {
            new WinShortcutParser().parse(stream, new BodyContentHandler(-1),
                    metadata, context);
        }
        return new ParseResult(metadata, embedded);
    }

    private static byte[] buildLnk() throws IOException {
        byte[] header = new byte[HEADER_SIZE];
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0, HEADER_SIZE);
        byte[] shellLinkClsid = new byte[] {
                0x01, 0x14, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00,
                (byte) 0xc0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46
        };
        System.arraycopy(shellLinkClsid, 0, header, 4, shellLinkClsid.length);
        buffer.putInt(60, 1);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header);
        out.write(ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(12)
                .putInt(SIG_CONSOLE_FE)
                .putInt(1252)
                .array());
        byte[] unknownBlock = new byte[UNKNOWN_BLOCK_SIZE];
        ByteBuffer.wrap(unknownBlock).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(UNKNOWN_BLOCK_SIZE)
                .putInt(SIG_TOLERATED_UNKNOWN);
        out.write(unknownBlock);
        out.write(new byte[4]);
        out.write(HTML);
        return out.toByteArray();
    }

    private static byte[] buildOverflowingExtraDataLnk() throws IOException {
        byte[] base = buildLnk();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(base, 0, HEADER_SIZE);
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(Integer.MAX_VALUE)
                .putInt(SIG_TOLERATED_UNKNOWN)
                .array());
        out.write(HTML);
        return out.toByteArray();
    }

    private static byte[] buildLateIndicatorLnk() throws IOException {
        byte[] base = buildLnk();
        byte[] header = java.util.Arrays.copyOf(base, HEADER_SIZE);
        byte[] payload = new byte[70_000];
        java.util.Arrays.fill(payload, (byte) 'A');
        byte[] indicator =
                "<script>new ActiveXObject('WScript.Shell')</script>"
                        .getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(indicator, 0, payload, 68_000, indicator.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header);
        out.write(new byte[4]);
        out.write(payload);
        return out.toByteArray();
    }

    private static byte[] buildMalformedIdListLnk() throws IOException {
        byte[] header = java.util.Arrays.copyOf(buildLnk(), HEADER_SIZE);
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(20, 1);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header);
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) 4096).array());
        out.write("<script>new ActiveXObject('WScript.Shell')</script>"
                .getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static byte[] buildMalformedLinkInfoLnk() throws IOException {
        byte[] header = java.util.Arrays.copyOf(buildLnk(), HEADER_SIZE);
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(20, 2);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header);
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(4096).array());
        out.write("<script>new ActiveXObject('WScript.Shell')</script>"
                .getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static byte[] buildMalformedStringDataLnk() throws IOException {
        byte[] header = java.util.Arrays.copyOf(buildLnk(), HEADER_SIZE);
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(20, 32);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header);
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) 4096).array());
        out.write("<script>new ActiveXObject('WScript.Shell')</script>"
                .getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static byte[] buildStringDataCursorConfusionLnk() throws IOException {
        int segmentBytes = 2_048;
        byte[] header = java.util.Arrays.copyOf(buildLnk(), HEADER_SIZE);
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(20, 0x20);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header);
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) segmentBytes).array());
        out.write("A".repeat(segmentBytes / 2).getBytes(StandardCharsets.UTF_16LE));
        out.write(new byte[4]);

        String script = "<!doctype html><script>"
                + "new ActiveXObject('WScript.Shell')"
                + "</script>";
        byte[] scriptBytes = script.getBytes(StandardCharsets.UTF_16LE);
        byte[] payload = new byte[segmentBytes - 4];
        System.arraycopy(scriptBytes, 0, payload, 0, scriptBytes.length);
        for (int i = scriptBytes.length; i + 1 < payload.length; i += 2) {
            payload[i] = 0x20;
        }
        out.write(payload);
        return out.toByteArray();
    }

    private static byte[] buildPropertyVectorLnk(int payloadBytes, int declaredElements) {
        int valueSize = 17 + payloadBytes;
        int storageSize = 24 + valueSize;
        int blockSize = 8 + storageSize;
        byte[] bytes = new byte[HEADER_SIZE + blockSize + 4];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(0, HEADER_SIZE);
        buffer.putInt(60, 1);
        int block = HEADER_SIZE;
        buffer.putInt(block, blockSize);
        buffer.putInt(block + 4, 0xA0000009);
        int storage = block + 8;
        buffer.putInt(storage, storageSize);
        buffer.putInt(storage + 4, 0x53505331);
        int value = storage + 24;
        buffer.putInt(value, valueSize);
        buffer.putInt(value + 4, 2);
        buffer.put(value + 8, (byte) 0);
        buffer.putShort(value + 9, (short) 0x1011);
        buffer.putShort(value + 11, (short) 0);
        buffer.putInt(value + 13, declaredElements);
        for (int i = 0; i < payloadBytes; i++) {
            buffer.put(value + 17 + i, (byte) (i & 0xff));
        }
        return bytes;
    }

    private static byte[] buildPropertyWideStringVectorLnk(int chars) {
        int payloadBytes = chars * 2;
        int valueSize = 21 + payloadBytes;
        int storageSize = 24 + valueSize;
        int blockSize = 8 + storageSize;
        byte[] bytes = new byte[HEADER_SIZE + blockSize + 4];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(0, HEADER_SIZE);
        buffer.putInt(60, 1);
        int block = HEADER_SIZE;
        buffer.putInt(block, blockSize);
        buffer.putInt(block + 4, 0xA0000009);
        int storage = block + 8;
        buffer.putInt(storage, storageSize);
        buffer.putInt(storage + 4, 0x53505331);
        int value = storage + 24;
        buffer.putInt(value, valueSize);
        buffer.putInt(value + 4, 2);
        buffer.put(value + 8, (byte) 0);
        buffer.putShort(value + 9, (short) 0x101f);
        buffer.putShort(value + 11, (short) 0);
        buffer.putInt(value + 13, 1);
        buffer.putInt(value + 17, chars);
        for (int i = 0; i < chars; i++) {
            buffer.putChar(value + 21 + i * 2, 'A');
        }
        return bytes;
    }

    private static byte[] buildPropertyRecordCardinalityLnk(int records) {
        int valueSize = 14;
        int storageSize = 24 + records * valueSize;
        int blockSize = 8 + storageSize;
        byte[] bytes = new byte[HEADER_SIZE + blockSize + 4];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(0, HEADER_SIZE);
        buffer.putInt(60, 1);
        int block = HEADER_SIZE;
        buffer.putInt(block, blockSize);
        buffer.putInt(block + 4, SIG_PROPERTY_STORE);
        int storage = block + 8;
        buffer.putInt(storage, storageSize);
        buffer.putInt(storage + 4, 0x53505331);
        int value = storage + 24;
        for (int i = 0; i < records; i++, value += valueSize) {
            buffer.putInt(value, valueSize);
            buffer.putInt(value + 4, i);
            buffer.put(value + 8, (byte) 0);
            buffer.putShort(value + 9, (short) 0x0011);
            buffer.putShort(value + 11, (short) 0);
            buffer.put(value + 13, (byte) 1);
        }
        return bytes;
    }

    private static byte[] buildVistaIdListCardinalityLnk(int components) {
        int blockSize = 8 + components * 4;
        byte[] bytes = new byte[HEADER_SIZE + blockSize + 4];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(0, HEADER_SIZE);
        buffer.putInt(60, 1);
        buffer.putInt(HEADER_SIZE, blockSize);
        buffer.putInt(HEADER_SIZE + 4, SIG_VISTA_IDLIST);
        int item = HEADER_SIZE + 8;
        for (int i = 0; i < components; i++, item += 4) {
            buffer.putShort(item, (short) 4);
            buffer.put(item + 2, (byte) 0x2f);
            buffer.put(item + 3, (byte) 'A');
        }
        return bytes;
    }

    private static byte[] buildUtf16IndicatorLnk(Charset charset, byte[] bom)
            throws IOException {
        return buildUtf16IndicatorLnk(charset, bom, "");
    }

    private static byte[] buildUtf16IndicatorLnk(Charset charset, byte[] bom,
                                                  String prefix)
            throws IOException {
        return buildIndicatorLnk(charset, bom, prefix,
                "new ActiveXObject('WScript.Shell')");
    }

    private static byte[] buildIndicatorLnk(Charset charset, byte[] bom,
                                            String prefix, String script)
            throws IOException {
        byte[] header = java.util.Arrays.copyOf(buildLnk(), HEADER_SIZE);
        byte[] payload = ("<!doctype html>" + prefix + "<script>"
                + script
                + "</script>").getBytes(charset);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header);
        out.write(new byte[4]);
        out.write(bom);
        out.write(payload);
        return out.toByteArray();
    }

    private static byte[] buildVirtualFolderHtmlLnk(Charset charset, byte[] bom)
            throws IOException {
        byte[] header = new byte[HEADER_SIZE];
        ByteBuffer fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        fields.putInt(0, HEADER_SIZE);
        fields.position(4);
        fields.put(new byte[]{
                0x01, 0x14, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00,
                (byte) 0xc0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46
        });
        fields.putInt(20, 1);
        fields.putInt(60, 1);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header);
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) 22).array());
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) 20).put((byte) 0x1f).put((byte) 0).array());
        out.write(CONTROL_PANEL_GUID);
        out.write(new byte[2]);
        out.write(new byte[4]);
        out.write(bom);
        out.write("<!doctype html><script>alert(1)</script>".getBytes(charset));
        return out.toByteArray();
    }

    private record ParseResult(Metadata metadata, List<byte[]> embedded) {
    }
}
