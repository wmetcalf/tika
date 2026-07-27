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
package org.apache.tika.parser.microsoft.rtf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.poi.poifs.filesystem.Ole10Native;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.TikaMemoryLimitException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Office;
import org.apache.tika.metadata.RTFMetadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

public class RTFObjDataParserTest {

    private static final byte[] PAYLOAD =
            "RTF_OLE_PAYLOAD".getBytes(StandardCharsets.US_ASCII);
    private static final String SOURCE_PATH = "C:\\source\\payload.bin";
    private static final String DISTINCT_COMMAND = "C:\\temp\\renamed-payload.bin";

    @Test
    public void testDistinctOleCommandDoesNotSuppressPayload() throws Exception {
        List<ExtractedDocument> extracted = parse(buildRtf(DISTINCT_COMMAND));

        assertEquals(1, extracted.size());
        assertArrayEquals(PAYLOAD, extracted.get(0).bytes);
        assertEquals(SOURCE_PATH,
                extracted.get(0).metadata.get(RTFMetadata.EMB_SOURCE_PATH));
        assertEquals(DISTINCT_COMMAND,
                extracted.get(0).metadata.get(RTFMetadata.EMB_COMMAND));
    }

    @Test
    public void testLinkedOle1ObjectIsSurfacedOnParentMetadata() throws Exception {
        String networkName = "\\\\server\\share\\linked-document.doc";
        Metadata metadata = parseMetadata(buildLinkedRtf(
                "Z:\\linked-document.doc", "Sheet1!R1C1", networkName));

        assertArrayEquals(
                new String[]{networkName, "Z:\\linked-document.doc"},
                metadata.getValues(Office.OFFICE_LINK_URL));
        assertEquals("linked_ole_object",
                metadata.get(Office.OFFICE_LINK_TYPE));
        assertEquals("Sheet1!R1C1",
                metadata.get(Office.OFFICE_LINK_TEXT));
    }

    @Test
    public void testOversizedLinkedNetworkNamePreservesTopicAndSignals()
            throws Exception {
        String topic = "\\\\server\\share\\topic-document.doc";

        Metadata metadata = parseMetadata(
                buildLinkedRtfWithNetworkLength(topic, "Sheet1!R1C1", 4_097));

        assertEquals(topic, metadata.get(Office.OFFICE_LINK_URL));
        assertNotNull(metadata.get(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING));
        assertNotNull(metadata.get("ExploitClass"));
    }

    @Test
    public void testTruncatedLinkedNetworkNameLengthPreservesTopicAndSignals()
            throws Exception {
        String topic = "\\\\server\\share\\topic-document.doc";

        for (int trailingBytes = 0; trailingBytes < Integer.BYTES; trailingBytes++) {
            Metadata metadata = parseMetadata(
                    buildLinkedRtfWithTruncatedNetworkLength(
                            topic, "Sheet1!R1C1", trailingBytes));

            assertEquals(topic, metadata.get(Office.OFFICE_LINK_URL));
            assertNotNull(metadata.get(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING));
            assertNotNull(metadata.get("ExploitClass"));
        }
    }

    @Test
    public void testFinalObjDataSecurityExceptionPropagates() throws Exception {
        SecurityException failure =
                new SecurityException("simulated RTF embedded security boundary");

        SecurityException thrown = assertThrows(SecurityException.class,
                () -> parseWithEmbeddedFailure(buildRtf(DISTINCT_COMMAND), failure));

        assertSame(failure, thrown);
    }

    @Test
    public void testFinalObjDataWriteLimitPropagates() throws Exception {
        WriteLimitReachedException failure = new WriteLimitReachedException(7);

        WriteLimitReachedException thrown =
                assertThrows(WriteLimitReachedException.class,
                        () -> parseWithEmbeddedFailure(
                                buildRtf(DISTINCT_COMMAND), failure));

        assertSame(failure, thrown);
    }

    @Test
    public void testFinalObjDataDownstreamSaxExceptionPropagates()
            throws Exception {
        SAXException failure =
                new SAXException("simulated RTF output policy denial");

        SAXException thrown = assertThrows(SAXException.class,
                () -> parseWithEmbeddedOutput(
                        buildRtf(DISTINCT_COMMAND),
                        "blocked RTF embedded output",
                        new TextRejectingHandler(
                                "blocked RTF embedded output", failure)));

        assertSame(failure, thrown);
    }

    @Test
    public void testFinalObjDataDownstreamSaxExceptionSurvivesCleanup()
            throws Exception {
        SAXException failure =
                new SAXException("simulated RTF output policy denial");
        FailStopTextRejectingHandler handler =
                new FailStopTextRejectingHandler(
                        "blocked RTF embedded output", failure);

        SAXException thrown = assertThrows(SAXException.class,
                () -> parseWithEmbeddedOutput(
                        buildRtf(DISTINCT_COMMAND),
                        "blocked RTF embedded output", handler));

        assertSame(failure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    public void testEndDocumentWriteLimitPropagatesByIdentity() throws Exception {
        WriteLimitReachedException failure = new WriteLimitReachedException(7);
        ContentHandler handler = new DefaultHandler() {
            @Override
            public void endDocument() throws SAXException {
                throw failure;
            }
        };

        WriteLimitReachedException thrown =
                assertThrows(WriteLimitReachedException.class, () -> {
                    try (TikaInputStream stream =
                                 TikaInputStream.get("{\\rtf1 test}"
                                         .getBytes(StandardCharsets.US_ASCII))) {
                        new RTFParser().parse(
                                stream, handler, new Metadata(), new ParseContext());
                    }
                });

        assertSame(failure, thrown);
    }

    @Test
    public void testStartDocumentWriteLimitPropagatesByIdentity() throws Exception {
        WriteLimitReachedException failure = new WriteLimitReachedException(7);
        ContentHandler handler = new DefaultHandler() {
            @Override
            public void startDocument() throws SAXException {
                throw failure;
            }
        };

        WriteLimitReachedException thrown =
                assertThrows(WriteLimitReachedException.class, () -> {
                    try (TikaInputStream stream =
                                 TikaInputStream.get("{\\rtf1 test}"
                                         .getBytes(StandardCharsets.US_ASCII))) {
                        new RTFParser().parse(
                                stream, handler, new Metadata(), new ParseContext());
                    }
                });

        assertSame(failure, thrown);
    }

    @Test
    public void testSecurityDenialStopsCleanupCallbacks() throws Exception {
        SecurityException failure =
                new SecurityException("simulated RTF output security denial");
        FailStopSecurityHandler handler =
                new FailStopSecurityHandler("blocked RTF text", failure);

        SecurityException thrown =
                assertThrows(SecurityException.class, () -> {
                    try (TikaInputStream stream =
                                 TikaInputStream.get(
                                         "{\\rtf1 blocked RTF text}"
                                                 .getBytes(StandardCharsets.US_ASCII))) {
                        new RTFParser().parse(
                                stream, handler, new Metadata(), new ParseContext());
                    }
                });

        assertSame(failure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    public void testCleanupDenialSupersedesRtfParserFailure()
            throws Exception {
        SAXException denial =
                new SAXException("simulated RTF cleanup output denial");
        AtomicBoolean parserFailed = new AtomicBoolean();
        DrainRejectingHandler handler =
                new DrainRejectingHandler(parserFailed, denial);
        byte[] rtf =
                "{\\rtf1\\b parser failure"
                        .getBytes(StandardCharsets.US_ASCII);

        SAXException thrown = assertThrows(SAXException.class, () -> {
            try (InputStream failing =
                         new FailingInputStream(rtf, parserFailed);
                 TikaInputStream stream = TikaInputStream.get(failing)) {
                new RTFParser().parse(
                        stream, handler, new Metadata(), new ParseContext());
            }
        });

        assertSame(denial, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertTrue(thrown.getSuppressed()[0] instanceof TikaException);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    public void testCleanupSecurityDenialSupersedesRtfParserFailure()
            throws Exception {
        SecurityException denial =
                new SecurityException(
                        "simulated RTF cleanup security denial");
        AtomicBoolean parserFailed = new AtomicBoolean();
        DrainRejectingHandler handler =
                new DrainRejectingHandler(parserFailed, denial);
        byte[] rtf =
                "{\\rtf1\\b parser failure"
                        .getBytes(StandardCharsets.US_ASCII);

        SecurityException thrown =
                assertThrows(SecurityException.class, () -> {
                    try (InputStream failing =
                                 new FailingInputStream(rtf, parserFailed);
                         TikaInputStream stream =
                                 TikaInputStream.get(failing)) {
                        new RTFParser().parse(
                                stream, handler, new Metadata(),
                                new ParseContext());
                    }
                });

        assertSame(denial, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertTrue(thrown.getSuppressed()[0] instanceof TikaException);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    public void testCleanupErrorSupersedesRtfParserFailure()
            throws Exception {
        AssertionError denial =
                new AssertionError("simulated RTF cleanup error denial");
        AtomicBoolean parserFailed = new AtomicBoolean();
        DrainRejectingHandler handler =
                new DrainRejectingHandler(parserFailed, denial);
        byte[] rtf =
                "{\\rtf1\\b parser failure"
                        .getBytes(StandardCharsets.US_ASCII);

        AssertionError thrown =
                assertThrows(AssertionError.class, () -> {
                    try (InputStream failing =
                                 new FailingInputStream(rtf, parserFailed);
                         TikaInputStream stream =
                                 TikaInputStream.get(failing)) {
                        new RTFParser().parse(
                                stream, handler, new Metadata(),
                                new ParseContext());
                    }
                });

        assertSame(denial, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertTrue(thrown.getSuppressed()[0] instanceof TikaException);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    public void testPictBinaryChunksShareOneMemoryLimit() throws Exception {
        RTFEmbObjHandler handler =
                new RTFEmbObjHandler(
                        new DefaultHandler(), new Metadata(), new ParseContext(), 1);
        handler.startPict();
        handler.writeBytes(new ByteArrayInputStream(new byte[600]), 600);
        ByteArrayInputStream rejectedChunk =
                new ByteArrayInputStream(new byte[600]);

        TikaMemoryLimitException thrown =
                assertThrows(TikaMemoryLimitException.class,
                        () -> handler.writeBytes(
                                rejectedChunk, 600));

        assertTrue(thrown.getMessage().contains("allocate 1200 bytes"));
        assertTrue(thrown.getMessage().contains("1024 is the maximum"));
        assertEquals(0, rejectedChunk.available(),
                "rejected binary bytes must still be consumed as opaque RTF data");
    }

    @Test
    public void testFinalObjDataParserSaxFailureRemainsBestEffort() {
        assertDoesNotThrow(
                () -> parseWithEmbeddedFailure(
                        buildRtf(DISTINCT_COMMAND),
                        new SAXException("simulated malformed embedded object")));
    }

    private static void parseWithEmbeddedFailure(byte[] rtf, Exception failure)
            throws Exception {
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return true;
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext parseContext,
                                      boolean outputHtml) throws IOException, SAXException {
                if (failure instanceof IOException ioException) {
                    throw ioException;
                }
                if (failure instanceof SAXException saxException) {
                    throw saxException;
                }
                if (failure instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new AssertionError("unsupported test exception", failure);
            }
        });

        try (TikaInputStream stream = TikaInputStream.get(rtf)) {
            new RTFParser().parse(
                    stream, new BodyContentHandler(-1), new Metadata(), context);
        }
    }

    private static void parseWithEmbeddedOutput(
            byte[] rtf, String output, ContentHandler outputHandler)
            throws Exception {
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return true;
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext parseContext,
                                      boolean outputHtml) throws SAXException {
                char[] chars = output.toCharArray();
                handler.characters(chars, 0, chars.length);
            }
        });

        try (TikaInputStream stream = TikaInputStream.get(rtf)) {
            new RTFParser().parse(
                    stream, outputHandler, new Metadata(), context);
        }
    }

    private static List<ExtractedDocument> parse(byte[] rtf) throws Exception {
        List<ExtractedDocument> extracted = new ArrayList<>();
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
                extracted.add(new ExtractedDocument(stream.readAllBytes(), metadata));
            }
        });

        try (TikaInputStream stream = TikaInputStream.get(rtf)) {
            new RTFParser().parse(stream, new BodyContentHandler(-1), new Metadata(), context);
        }
        return extracted;
    }

    private static Metadata parseMetadata(byte[] rtf) throws Exception {
        Metadata metadata = new Metadata();
        try (TikaInputStream stream = TikaInputStream.get(rtf)) {
            new RTFParser().parse(
                    stream, new BodyContentHandler(-1), metadata, new ParseContext());
        }
        return metadata;
    }

    private static byte[] buildRtf(String command) throws IOException {
        byte[] poifs = buildOleCompoundDocument(command);
        ByteArrayOutputStream objData = new ByteArrayOutputStream();
        writeUInt32LE(objData, 0x00000501);
        writeUInt32LE(objData, 2);
        writeLengthPrefixedAnsi(objData, "Package.1");
        writeUInt32LE(objData, 0);
        writeUInt32LE(objData, 0);
        writeUInt32LE(objData, poifs.length);
        objData.write(poifs);

        String rtf = "{\\rtf1\\ansi"
                + "{\\object\\objemb"
                + "{\\*\\objclass Package}"
                + "{\\*\\objdata " + HexFormat.of().formatHex(objData.toByteArray()) + "}"
                + "}"
                + "}";
        return rtf.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] buildLinkedRtf(
            String topic, String item, String networkName) throws IOException {
        ByteArrayOutputStream objData = new ByteArrayOutputStream();
        writeUInt32LE(objData, 0x00000501);
        writeUInt32LE(objData, 1);
        writeLengthPrefixedAnsi(objData, "Excel.Sheet.12");
        writeLengthPrefixedAnsi(objData, topic);
        writeLengthPrefixedAnsi(objData, item);
        writeLengthPrefixedAnsi(objData, networkName);

        String rtf = "{\\rtf1\\ansi"
                + "{\\object\\objlink"
                + "{\\*\\objclass Excel.Sheet.12}"
                + "{\\*\\objdata " + HexFormat.of().formatHex(objData.toByteArray()) + "}"
                + "}"
                + "}";
        return rtf.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] buildLinkedRtfWithNetworkLength(
            String topic, String item, int networkLength) throws IOException {
        ByteArrayOutputStream objData = new ByteArrayOutputStream();
        writeUInt32LE(objData, 0x00000501);
        writeUInt32LE(objData, 1);
        writeLengthPrefixedAnsi(objData, "Excel.Sheet.12");
        writeLengthPrefixedAnsi(objData, topic);
        writeLengthPrefixedAnsi(objData, item);
        writeUInt32LE(objData, networkLength);

        String rtf = "{\\rtf1\\ansi"
                + "{\\object\\objlink"
                + "{\\*\\objclass Excel.Sheet.12}"
                + "{\\*\\objdata " + HexFormat.of().formatHex(objData.toByteArray()) + "}"
                + "}"
                + "}";
        return rtf.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] buildLinkedRtfWithTruncatedNetworkLength(
            String topic, String item, int trailingBytes) throws IOException {
        ByteArrayOutputStream objData = new ByteArrayOutputStream();
        writeUInt32LE(objData, 0x00000501);
        writeUInt32LE(objData, 1);
        writeLengthPrefixedAnsi(objData, "Excel.Sheet.12");
        writeLengthPrefixedAnsi(objData, topic);
        writeLengthPrefixedAnsi(objData, item);
        for (int i = 0; i < trailingBytes; i++) {
            objData.write(0x41);
        }

        String rtf = "{\\rtf1\\ansi"
                + "{\\object\\objlink"
                + "{\\*\\objclass Excel.Sheet.12}"
                + "{\\*\\objdata " + HexFormat.of().formatHex(objData.toByteArray()) + "}"
                + "}"
                + "}";
        return rtf.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] buildOleCompoundDocument(String command) throws IOException {
        Ole10Native nativeRecord =
                new Ole10Native("payload.bin", SOURCE_PATH, command, PAYLOAD);
        ByteArrayOutputStream nativeBytes = new ByteArrayOutputStream();
        nativeRecord.writeOut(nativeBytes);

        try (POIFSFileSystem fs = new POIFSFileSystem();
             ByteArrayOutputStream compoundBytes = new ByteArrayOutputStream()) {
            fs.getRoot().createDocument(Ole10Native.OLE10_NATIVE,
                    new ByteArrayInputStream(nativeBytes.toByteArray()));
            Ole10Native.createOleMarkerEntry(fs);
            fs.writeFilesystem(compoundBytes);
            return compoundBytes.toByteArray();
        }
    }

    private static void writeLengthPrefixedAnsi(ByteArrayOutputStream out, String value)
            throws IOException {
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

    private record ExtractedDocument(byte[] bytes, Metadata metadata) {
    }

    private static final class TextRejectingHandler extends DefaultHandler {

        private final String rejectedText;
        private final SAXException failure;

        private TextRejectingHandler(String rejectedText, SAXException failure) {
            this.rejectedText = rejectedText;
            this.failure = failure;
        }

        @Override
        public void characters(char[] ch, int start, int length)
                throws SAXException {
            if (new String(ch, start, length).contains(rejectedText)) {
                throw failure;
            }
        }
    }

    private static final class FailStopTextRejectingHandler
            extends DefaultHandler {

        private final String rejectedText;
        private final SAXException failure;
        private final SAXException cleanupFailure =
                new SAXException("SAX event delivered after policy denial");
        private boolean denied;
        private int callbacksAfterDenial;

        private FailStopTextRejectingHandler(
                String rejectedText, SAXException failure) {
            this.rejectedText = rejectedText;
            this.failure = failure;
        }

        @Override
        public void characters(char[] ch, int start, int length)
                throws SAXException {
            rejectAfterDenial();
            if (new String(ch, start, length).contains(rejectedText)) {
                denied = true;
                throw failure;
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName)
                throws SAXException {
            rejectAfterDenial();
        }

        @Override
        public void endPrefixMapping(String prefix) throws SAXException {
            rejectAfterDenial();
        }

        @Override
        public void endDocument() throws SAXException {
            rejectAfterDenial();
        }

        private void rejectAfterDenial() throws SAXException {
            if (denied) {
                callbacksAfterDenial++;
                throw cleanupFailure;
            }
        }
    }

    private static final class FailStopSecurityHandler extends DefaultHandler {

        private final String rejectedText;
        private final SecurityException failure;
        private final SecurityException cleanupFailure =
                new SecurityException("event delivered after security denial");
        private boolean denied;
        private int callbacksAfterDenial;

        private FailStopSecurityHandler(
                String rejectedText, SecurityException failure) {
            this.rejectedText = rejectedText;
            this.failure = failure;
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            rejectAfterDenial();
            if (new String(ch, start, length).contains(rejectedText)) {
                denied = true;
                throw failure;
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            rejectAfterDenial();
        }

        @Override
        public void endPrefixMapping(String prefix) {
            rejectAfterDenial();
        }

        @Override
        public void endDocument() {
            rejectAfterDenial();
        }

        private void rejectAfterDenial() {
            if (denied) {
                callbacksAfterDenial++;
                throw cleanupFailure;
            }
        }
    }

    private static final class FailingInputStream extends InputStream {

        private final byte[] data;
        private final AtomicBoolean parserFailed;
        private int position;

        private FailingInputStream(
                byte[] data, AtomicBoolean parserFailed) {
            this.data = data;
            this.parserFailed = parserFailed;
        }

        @Override
        public int read() throws IOException {
            if (position < data.length) {
                return Byte.toUnsignedInt(data[position++]);
            }
            parserFailed.set(true);
            throw new IOException("simulated RTF parser input failure");
        }

        @Override
        public int read(byte[] bytes, int offset, int length)
                throws IOException {
            if (position >= data.length) {
                parserFailed.set(true);
                throw new IOException("simulated RTF parser input failure");
            }
            int count = Math.min(length, data.length - position);
            System.arraycopy(data, position, bytes, offset, count);
            position += count;
            return count;
        }
    }

    private static final class DrainRejectingHandler extends DefaultHandler {

        private final AtomicBoolean parserFailed;
        private final Throwable denial;
        private boolean denied;
        private int callbacksAfterDenial;

        private DrainRejectingHandler(
                AtomicBoolean parserFailed, Throwable denial) {
            this.parserFailed = parserFailed;
            this.denial = denial;
        }

        @Override
        public void startPrefixMapping(String prefix, String uri)
                throws SAXException {
            rejectAfterDenial();
        }

        @Override
        public void startElement(
                String uri, String localName, String qName,
                org.xml.sax.Attributes attributes) throws SAXException {
            rejectAfterDenial();
        }

        @Override
        public void characters(char[] ch, int start, int length)
                throws SAXException {
            rejectAfterDenial();
        }

        @Override
        public void endElement(String uri, String localName, String qName)
                throws SAXException {
            rejectAfterDenial();
            if (parserFailed.get()) {
                denied = true;
                throwDenial();
            }
        }

        @Override
        public void endPrefixMapping(String prefix) throws SAXException {
            rejectAfterDenial();
        }

        @Override
        public void endDocument() throws SAXException {
            rejectAfterDenial();
        }

        private void rejectAfterDenial() throws SAXException {
            if (denied) {
                callbacksAfterDenial++;
                if (denial instanceof SAXException) {
                    throw new SAXException(
                            "callback delivered after denial");
                }
                if (denial instanceof SecurityException) {
                    throw new SecurityException(
                            "callback delivered after security denial");
                }
                throw new AssertionError(
                        "callback delivered after error denial");
            }
        }

        private void throwDenial() throws SAXException {
            if (denial instanceof SAXException saxException) {
                throw saxException;
            }
            if (denial instanceof SecurityException securityException) {
                throw securityException;
            }
            if (denial instanceof Error error) {
                throw error;
            }
            throw new AssertionError(
                    "unsupported cleanup denial", denial);
        }
    }
}
