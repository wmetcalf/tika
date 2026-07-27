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
package org.apache.tika.parser.microsoft.rtf.jflex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Office;
import org.apache.tika.metadata.RTFMetadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;

/**
 * Tests for {@link RTFEmbeddedHandler} driven by the JFlex tokenizer,
 * both standalone and integrated into the decapsulator.
 */
public class RTFEmbeddedHandlerTest {

    private static ParseContext buildContext(List<Metadata> extracted) {
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return true;
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext parseContext,
                                      boolean outputHtml) {
                Metadata copy = new Metadata();
                for (String name : metadata.names()) {
                    for (String val : metadata.getValues(name)) {
                        copy.reconstruct(name, val, true);
                    }
                }
                extracted.add(copy);
            }
        });
        return context;
    }

    /**
     * Process an RTF file through the tokenizer + state + embedded handler directly.
     */
    private List<Metadata> extractEmbeddedDirect(String resourceName)
            throws IOException, SAXException, TikaException {
        List<Metadata> extracted = new ArrayList<>();
        ParseContext context = buildContext(extracted);
        ContentHandler handler = new DefaultHandler();
        RTFEmbeddedHandler embHandler = new RTFEmbeddedHandler(handler, context, 20 * 1024);
        RTFState state = new RTFState();

        try (InputStream is = getClass().getResourceAsStream("/test-documents/" + resourceName);
             Reader reader = new InputStreamReader(is, StandardCharsets.US_ASCII)) {

            RTFTokenizer tokenizer = new RTFTokenizer(reader);
            RTFToken tok;

            while ((tok = tokenizer.yylex()) != null) {
                if (tok.getType() == RTFTokenType.EOF) {
                    break;
                }
                boolean consumed = state.processToken(tok);
                if (!consumed) {
                    RTFGroupState closingGroup =
                            (tok.getType() == RTFTokenType.GROUP_CLOSE)
                                    ? state.getLastClosedGroup() : null;
                    embHandler.processToken(tok, state, closingGroup);
                }
            }
        }
        return extracted;
    }

    @Test
    public void testEmbeddedFiles() throws Exception {
        List<Metadata> embedded = extractEmbeddedDirect("testRTFEmbeddedFiles.rtf");
        assertTrue(embedded.size() > 0,
                "should extract at least one embedded object from testRTFEmbeddedFiles.rtf");
    }

    @Test
    public void testPictExtraction() throws Exception {
        // Verifies the handler doesn't crash on a typical RTF file
        extractEmbeddedDirect("testRTF.rtf");
    }

    @Test
    public void testEmbeddedObjectMetadata() throws Exception {
        List<Metadata> embedded = extractEmbeddedDirect("testRTFEmbeddedFiles.rtf");
        if (embedded.size() > 0) {
            boolean hasName = false;
            for (Metadata m : embedded) {
                String name = m.get(TikaCoreProperties.RESOURCE_NAME_KEY);
                if (name != null && !name.isEmpty()) {
                    hasName = true;
                    break;
                }
            }
            assertTrue(hasName, "at least one embedded should have a resource name");
        }
    }

    @Test
    public void testLinkedOle1ObjectIsSurfacedOnParentMetadata() throws Exception {
        Metadata parentMetadata = new Metadata();
        RTFEmbeddedHandler embHandler = new RTFEmbeddedHandler(
                new DefaultHandler(), parentMetadata, new ParseContext(), 20 * 1024);
        processRtf(buildLinkedRtf(
                "\\\\server\\share\\linked-document.doc", "Section1", ""), embHandler);

        assertEquals("\\\\server\\share\\linked-document.doc",
                parentMetadata.get(Office.OFFICE_LINK_URL));
        assertEquals("linked_ole_object",
                parentMetadata.get(Office.OFFICE_LINK_TYPE));
        assertEquals("Section1",
                parentMetadata.get(Office.OFFICE_LINK_TEXT));
    }

    @Test
    public void testLinkedOle1NetworkNameIsSurfaced() throws Exception {
        Metadata parentMetadata = new Metadata();
        RTFEmbeddedHandler embHandler = new RTFEmbeddedHandler(
                new DefaultHandler(), parentMetadata, new ParseContext(), 20 * 1024);
        String networkName = "\\\\server\\share\\network-document.doc";
        processRtf(buildLinkedRtf(
                "Z:\\network-document.doc", "Section1", networkName), embHandler);

        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new String[]{networkName, "Z:\\network-document.doc"},
                parentMetadata.getValues(Office.OFFICE_LINK_URL));
    }

    @Test
    public void testHexEscapedObjdataByteMatchesRawHex() throws Exception {
        Metadata parentMetadata = new Metadata();
        RTFEmbeddedHandler embHandler = new RTFEmbeddedHandler(
                new DefaultHandler(), parentMetadata, new ParseContext(), 20 * 1024);
        String target = "\\\\server\\share\\hex-escaped-linked-document.doc";
        String rtf = buildLinkedRtf(target, "Section1", "");
        int objdataStart = rtf.indexOf("\\objdata ") + "\\objdata ".length();
        String escaped = rtf.substring(0, objdataStart) + "\\'"
                + rtf.substring(objdataStart, objdataStart + 2)
                + rtf.substring(objdataStart + 2);

        processRtf(escaped, embHandler);

        assertEquals(target, parentMetadata.get(Office.OFFICE_LINK_URL));
    }

    @Test
    public void testDecapsulatorSignalsEmbeddedObjectLimitFailure() throws Exception {
        Metadata metadata = new Metadata();
        RTFHtmlDecapsulator decapsulator = new RTFHtmlDecapsulator(
                new DefaultHandler(), metadata, new ParseContext(), 1);

        decapsulator.extract(buildOversizedEmbeddedRtf());

        assertNotNull(metadata.get(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING));
        assertNotNull(metadata.get("ExploitClass"));
        assertEquals(1, metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length,
                "one rejected object must not append a warning for every excess byte");
    }

    @Test
    public void testOversizedLinkedNetworkNamePreservesTopicAndSignals()
            throws Exception {
        Metadata parentMetadata = new Metadata();
        RTFEmbeddedHandler embHandler = new RTFEmbeddedHandler(
                new DefaultHandler(), parentMetadata, new ParseContext(), 20 * 1024);

        processRtf(buildLinkedRtfWithNetworkLength(
                "\\\\server\\share\\topic-document.doc", "Section1", 4_097),
                embHandler);

        assertEquals("\\\\server\\share\\topic-document.doc",
                parentMetadata.get(Office.OFFICE_LINK_URL));
        assertNotNull(parentMetadata.get(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING));
        assertNotNull(parentMetadata.get("ExploitClass"));
    }

    @Test
    public void testTruncatedLinkedNetworkNamePreservesTopicAndSignals()
            throws Exception {
        Metadata parentMetadata = new Metadata();
        RTFEmbeddedHandler embHandler = new RTFEmbeddedHandler(
                new DefaultHandler(), parentMetadata, new ParseContext(), 20 * 1024);

        processRtf(buildLinkedRtfWithNetworkLength(
                "\\\\server\\share\\topic-document.doc", "Section1", 32),
                embHandler);

        assertEquals("\\\\server\\share\\topic-document.doc",
                parentMetadata.get(Office.OFFICE_LINK_URL));
        assertNotNull(parentMetadata.get(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING));
        assertNotNull(parentMetadata.get("ExploitClass"));
    }

    @Test
    public void testPictHexEscapeDoesNotClaimObjdataObfuscation() throws Exception {
        List<Metadata> extracted = new ArrayList<>();
        ParseContext context = buildContext(extracted);
        RTFEmbeddedHandler embHandler = new RTFEmbeddedHandler(
                new DefaultHandler(), new Metadata(), context, 20 * 1024);

        processRtf("{\\rtf1{\\pict\\pngblip \\'89504e470d0a1a0a}}", embHandler);

        assertEquals(1, extracted.size());
        assertNull(extracted.get(0).get(RTFMetadata.EMB_HEX_ESCAPE_IN_OBJDATA));
    }

    private static void processRtf(String rtf, RTFEmbeddedHandler embHandler)
            throws Exception {
        RTFState state = new RTFState();
        RTFTokenizer tokenizer = new RTFTokenizer(
                new java.io.StringReader(rtf));
        RTFToken tok;
        while ((tok = tokenizer.yylex()) != null) {
            if (tok.getType() == RTFTokenType.EOF) {
                break;
            }
            boolean consumed = state.processToken(tok);
            if (!consumed) {
                RTFGroupState closingGroup =
                        tok.getType() == RTFTokenType.GROUP_CLOSE
                                ? state.getLastClosedGroup() : null;
                embHandler.processToken(tok, state, closingGroup);
            }
        }
    }

    private static String buildLinkedRtf(
            String topic, String item, String networkName) throws IOException {
        ByteArrayOutputStream objData = new ByteArrayOutputStream();
        writeUInt32LE(objData, 0x00000501);
        writeUInt32LE(objData, 1);
        writeLengthPrefixedAnsi(objData, "Word.Document.12");
        writeLengthPrefixedAnsi(objData, topic);
        writeLengthPrefixedAnsi(objData, item);
        if (!networkName.isEmpty()) {
            writeLengthPrefixedAnsi(objData, networkName);
        }
        return "{\\rtf1\\ansi{\\object\\objlink{\\*\\objdata "
                + HexFormat.of().formatHex(objData.toByteArray()) + "}}}";
    }

    private static String buildLinkedRtfWithNetworkLength(
            String topic, String item, int networkLength) throws IOException {
        ByteArrayOutputStream objData = new ByteArrayOutputStream();
        writeUInt32LE(objData, 0x00000501);
        writeUInt32LE(objData, 1);
        writeLengthPrefixedAnsi(objData, "Word.Document.12");
        writeLengthPrefixedAnsi(objData, topic);
        writeLengthPrefixedAnsi(objData, item);
        writeUInt32LE(objData, networkLength);
        return "{\\rtf1\\ansi{\\object\\objlink{\\*\\objdata "
                + HexFormat.of().formatHex(objData.toByteArray()) + "}}}";
    }

    private static byte[] buildOversizedEmbeddedRtf() throws IOException {
        ByteArrayOutputStream objData = new ByteArrayOutputStream();
        writeUInt32LE(objData, 0x00000501);
        writeUInt32LE(objData, 2);
        writeLengthPrefixedAnsi(objData, "PBrush");
        writeUInt32LE(objData, 0);
        writeUInt32LE(objData, 0);
        writeUInt32LE(objData, 2 * 1024);
        objData.write(new byte[2 * 1024]);
        return ("{\\rtf1\\ansi\\fromhtml1{\\object\\objemb{\\*\\objdata "
                + HexFormat.of().formatHex(objData.toByteArray()) + "}}}")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static void writeLengthPrefixedAnsi(
            ByteArrayOutputStream out, String value) throws IOException {
        byte[] bytes = (value + "\0").getBytes(StandardCharsets.US_ASCII);
        writeUInt32LE(out, bytes.length);
        out.write(bytes);
    }

    private static void writeUInt32LE(ByteArrayOutputStream out, long value)
            throws IOException {
        out.write(ByteBuffer.allocate(Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt((int) value)
                .array());
    }
}
