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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;

import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

public class WinShortcutParserTest {

    private static final int HEADER_SIZE = 76;
    private static final int SIG_CONSOLE_FE = 0xA0000004;
    private static final int SIG_TOLERATED_UNKNOWN = 0xA0001337;
    private static final int UNKNOWN_BLOCK_SIZE = 70000;
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
                "new window['\\x41ctiveXObject']('WScript.Shell')")) {
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
    public void testOversizedInputIsTruncatedAndSignaled() throws Exception {
        byte[] oversized = new byte[16 * 1024 * 1024 + 1];
        System.arraycopy(buildLnk(), 0, oversized, 0, HEADER_SIZE);

        ParseResult result = parse(oversized);

        assertNotNull(result.metadata.get("lnk:warning"),
                "bounded input must be reported as incomplete");
        assertNotNull(result.metadata.get("lnk:ExploitClass"),
                "truncation must fail closed because late indicators may be hidden");
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

    private record ParseResult(Metadata metadata, List<byte[]> embedded) {
    }
}
