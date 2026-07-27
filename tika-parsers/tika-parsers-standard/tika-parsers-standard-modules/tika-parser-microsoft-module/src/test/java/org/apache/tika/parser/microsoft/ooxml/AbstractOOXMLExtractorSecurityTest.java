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
package org.apache.tika.parser.microsoft.ooxml;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.poifs.filesystem.Ole10Native;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xssf.usermodel.XSSFRelation;
import org.junit.jupiter.api.Test;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Office;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.microsoft.OfficeParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;

public class AbstractOOXMLExtractorSecurityTest {

    @Test
    public void testDuplicateMacroRelationshipsParseActualPartOnce()
            throws Exception {
        ParseContext context = macroParseContext();
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            PackagePart document = createXmlPart(
                    opcPackage, "/word/document.xml");
            PackagePart macro = createMacroPart(
                    opcPackage, "/word/vbaProject.bin");
            document.addRelationship(
                    macro.getPartName(), TargetMode.INTERNAL,
                    XSSFRelation.VBA_MACROS.getRelation());
            document.addRelationship(
                    macro.getPartName(), TargetMode.INTERNAL,
                    XSSFRelation.VBA_MACROS.getRelation());

            MacroTrackingExtractor extractor = new MacroTrackingExtractor(
                    context, opcPackage, List.of(document), List.of(document));
            extractor.getXHTML(
                    new BodyContentHandler(-1), new Metadata(), context);

            assertEquals(
                    List.of("/word/vbaProject.bin"),
                    extractor.macroPartNames);
        }
    }

    @Test
    public void testSameRelativeMacroUriFromDifferentSourcesUsesActualPartIdentity()
            throws Exception {
        ParseContext context = macroParseContext();
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            PackagePart firstDocument = createXmlPart(
                    opcPackage, "/word/first/document.xml");
            PackagePart firstMacro = createMacroPart(
                    opcPackage, "/word/first/vbaProject.bin");
            firstDocument.addRelationship(
                    URI.create("vbaProject.bin"), TargetMode.INTERNAL,
                    XSSFRelation.VBA_MACROS.getRelation());

            PackagePart secondDocument = createXmlPart(
                    opcPackage, "/word/second/document.xml");
            PackagePart secondMacro = createMacroPart(
                    opcPackage, "/word/second/vbaProject.bin");
            secondDocument.addRelationship(
                    URI.create("vbaProject.bin"), TargetMode.INTERNAL,
                    XSSFRelation.VBA_MACROS.getRelation());

            MacroTrackingExtractor extractor = new MacroTrackingExtractor(
                    context, opcPackage, List.of(firstDocument),
                    List.of(firstDocument, secondDocument));
            extractor.getXHTML(
                    new BodyContentHandler(-1), new Metadata(), context);

            assertEquals(
                    List.of(
                            "/word/first/vbaProject.bin",
                            "/word/second/vbaProject.bin"),
                    extractor.macroPartNames);
        }
    }

    @Test
    public void testExternalRelationshipCapIsSignaled() throws Exception {
        ParseContext context = new ParseContext();
        context.set(OfficeParserConfig.class, new OfficeParserConfig());
        Metadata metadata = new Metadata();
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            for (int i = 0; i < 1_025; i++) {
                opcPackage.addExternalRelationship(
                        "https://example.invalid/external-" + i,
                        "http://schemas.openxmlformats.org/officeDocument/"
                                + "2006/relationships/hyperlink");
            }

            new EmptyExtractor(context, opcPackage).getXHTML(
                    new BodyContentHandler(-1), metadata, context);
        }

        assertEquals(1_024,
                metadata.getValues(Office.OFFICE_LINK_RECORD).length);
        assertEquals("true",
                metadata.get(TikaCoreProperties.TRUNCATED_METADATA));
        assertNotNull(metadata.get(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING),
                "skipping external relationships must not be silent");
        assertNotNull(metadata.get("ExploitClass"),
                "a skipped relationship can hide an executable reference");
    }

    @Test
    public void testExactExternalRelationshipCapIsNotReportedAsTruncated()
            throws Exception {
        ParseContext context = new ParseContext();
        context.set(OfficeParserConfig.class, new OfficeParserConfig());
        Metadata metadata = new Metadata();
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            for (int i = 0; i < 1_024; i++) {
                opcPackage.addExternalRelationship(
                        "https://example.invalid/external-" + i,
                        "http://schemas.openxmlformats.org/officeDocument/"
                                + "2006/relationships/hyperlink");
            }
            opcPackage.createPart(
                    PackagingURIHelper.createPartName("/word/document.xml"),
                    "application/xml");

            new EmptyExtractor(context, opcPackage).getXHTML(
                    new BodyContentHandler(-1), metadata, context);
        }

        assertEquals(1_024,
                metadata.getValues(Office.OFFICE_LINK_RECORD).length);
        assertNull(metadata.get(TikaCoreProperties.TRUNCATED_METADATA));
        assertNull(metadata.get(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING),
                "reaching the exact cap without dropping a link is complete");
    }

    @Test
    public void testExternalRelationshipWriteLimitPropagates() throws Exception {
        ParseContext context = new ParseContext();
        context.set(OfficeParserConfig.class, new OfficeParserConfig());
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            opcPackage.addExternalRelationship(
                    "https://example.invalid/external",
                    "http://schemas.openxmlformats.org/officeDocument/"
                            + "2006/relationships/hyperlink");

            assertThrows(WriteLimitReachedException.class,
                    () -> new EmptyExtractor(context, opcPackage).getXHTML(
                            new LinkWriteLimitHandler(), new Metadata(), context));
        }
    }

    @Test
    public void testOrdinarySaxFailureWhileSurfacingRelationshipIsBestEffort()
            throws Exception {
        ParseContext context = new ParseContext();
        context.set(OfficeParserConfig.class, new OfficeParserConfig());
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            opcPackage.addExternalRelationship(
                    "https://example.invalid/external",
                    "http://schemas.openxmlformats.org/officeDocument/"
                            + "2006/relationships/hyperlink");

            assertDoesNotThrow(() -> new EmptyExtractor(context, opcPackage).getXHTML(
                    new LinkRejectingHandler(), new Metadata(), context));
        }
    }

    @Test
    public void testSameUrlWithExecutableRelationshipKeepsBothSemantics()
            throws Exception {
        ParseContext context = new ParseContext();
        context.set(OfficeParserConfig.class, new OfficeParserConfig());
        Metadata metadata = new Metadata();
        String url = "https://example.invalid/shared-target";
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            opcPackage.addExternalRelationship(
                    url,
                    "http://schemas.openxmlformats.org/officeDocument/"
                            + "2006/relationships/hyperlink");
            PackagePart document = opcPackage.createPart(
                    PackagingURIHelper.createPartName("/word/document.xml"),
                    "application/xml");
            document.addExternalRelationship(
                    url,
                    "http://schemas.openxmlformats.org/officeDocument/"
                            + "2006/relationships/attachedTemplate");

            new EmptyExtractor(context, opcPackage).getXHTML(
                    new BodyContentHandler(-1), metadata, context);
        }

        assertEquals(2, metadata.getValues(Office.OFFICE_LINK_RECORD).length);
        assertEquals("true", metadata.get(Office.HAS_ATTACHED_TEMPLATE));
    }

    @Test
    public void testHandledMainRelationshipsDoNotStarveNonMainExecutableLink()
            throws Exception {
        ParseContext context = new ParseContext();
        context.set(OfficeParserConfig.class, new OfficeParserConfig());
        Metadata metadata = new Metadata();
        String executableUrl =
                "https://attacker.invalid/non-main-template.dotm";
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            PackagePart document = opcPackage.createPart(
                    PackagingURIHelper.createPartName("/word/document.xml"),
                    "application/xml");
            for (int i = 0; i < 1_024; i++) {
                document.addExternalRelationship(
                        "https://decoy.invalid/main-" + i,
                        "http://schemas.openxmlformats.org/officeDocument/"
                                + "2006/relationships/hyperlink");
            }
            PackagePart nonMainPart = opcPackage.createPart(
                    PackagingURIHelper.createPartName("/word/z-settings.xml"),
                    "application/xml");
            nonMainPart.addExternalRelationship(
                    executableUrl,
                    "http://schemas.openxmlformats.org/officeDocument/"
                            + "2006/relationships/attachedTemplate");

            new EmptyExtractor(context, opcPackage, List.of(document))
                    .getXHTML(new BodyContentHandler(-1), metadata, context);
        }

        assertEquals(1L,
                List.of(metadata.getValues(Office.OFFICE_LINK_URL))
                        .stream()
                        .filter(executableUrl::equals)
                        .count(),
                "a non-main executable link must survive main-part decoys");
        assertEquals(1_025,
                metadata.getValues(Office.OFFICE_LINK_RECORD).length,
                "already-handled main relationships must not be emitted twice");
        assertNull(metadata.get(TikaCoreProperties.TRUNCATED_METADATA),
                "handled main relationships must not consume the catch-all quota");
    }

    @Test
    public void testObfuscatedOleWriteLimitPropagates() throws Exception {
        ParseContext context = new ParseContext();
        context.set(OfficeParserConfig.class, new OfficeParserConfig());
        context.set(EmbeddedDocumentExtractor.class,
                new WriteLimitEmbeddedExtractor());
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            PackagePart document = addObfuscatedOle(opcPackage);

            assertThrows(WriteLimitReachedException.class,
                    () -> new EmptyExtractor(context, opcPackage, List.of(document))
                            .getXHTML(new BodyContentHandler(-1),
                                    new Metadata(), context));
        }
    }

    @Test
    public void testObfuscatedOlePayloadIsStreamedExactly() throws Exception {
        ParseContext context = new ParseContext();
        context.set(OfficeParserConfig.class, new OfficeParserConfig());
        CapturingEmbeddedExtractor embeddedExtractor =
                new CapturingEmbeddedExtractor();
        context.set(EmbeddedDocumentExtractor.class, embeddedExtractor);
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            PackagePart document = addObfuscatedOle(opcPackage);

            new EmptyExtractor(context, opcPackage, List.of(document))
                    .getXHTML(new BodyContentHandler(-1),
                            new Metadata(), context);
        }

        assertArrayEquals(obfuscatedPayload(),
                embeddedExtractor.payload);
        assertEquals("7", embeddedExtractor.contentLength);
    }

    @Test
    public void testValidObfuscatedOlePayloadExcludesNativeWrapper()
            throws Exception {
        byte[] expected = new byte[]{'P', 'A', 'Y', 'L', 'O', 'A', 'D'};
        ParseContext context = new ParseContext();
        context.set(OfficeParserConfig.class, new OfficeParserConfig());
        CapturingEmbeddedExtractor embeddedExtractor =
                new CapturingEmbeddedExtractor();
        context.set(EmbeddedDocumentExtractor.class, embeddedExtractor);
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            ByteArrayOutputStream nativeStream = new ByteArrayOutputStream();
            new Ole10Native("payload.bin", "C:\\payload.bin",
                    "C:\\payload.bin", expected).writeOut(nativeStream);
            PackagePart document =
                    addObfuscatedOle(opcPackage, nativeStream.toByteArray());

            new EmptyExtractor(context, opcPackage, List.of(document))
                    .getXHTML(new BodyContentHandler(-1),
                            new Metadata(), context);
        }

        assertArrayEquals(expected, embeddedExtractor.payload);
        assertEquals(Integer.toString(expected.length),
                embeddedExtractor.contentLength);
    }

    @Test
    public void testObfuscatedOleHighBitLabelExposesActualPayload()
            throws Exception {
        byte[] expected = new byte[]{'P', 'A', 'Y', 'L', 'O', 'A', 'D'};
        for (int firstLabelByte : new int[]{0x80, 0x9f}) {
            ParseContext context = new ParseContext();
            context.set(OfficeParserConfig.class,
                    new OfficeParserConfig());
            CapturingEmbeddedExtractor embeddedExtractor =
                    new CapturingEmbeddedExtractor();
            context.set(EmbeddedDocumentExtractor.class,
                    embeddedExtractor);
            try (ByteArrayOutputStream packageBytes =
                         new ByteArrayOutputStream();
                 OPCPackage opcPackage =
                         OPCPackage.create(packageBytes)) {
                PackagePart document = addObfuscatedOle(
                        opcPackage,
                        parsedOle10Native(firstLabelByte, expected));

                new EmptyExtractor(context, opcPackage, List.of(document))
                        .getXHTML(new BodyContentHandler(-1),
                                new Metadata(), context);
            }

            assertArrayEquals(expected, embeddedExtractor.payload,
                    "label byte 0x"
                            + Integer.toHexString(firstLabelByte));
            assertEquals(Integer.toString(expected.length),
                    embeddedExtractor.contentLength);
        }
    }

    @Test
    public void testObfuscatedOleHonorsPoiRecordLengthLimit()
            throws Exception {
        int previousMaxRecordLength = Ole10Native.getMaxRecordLength();
        try {
            Ole10Native.setMaxRecordLength(32);
            ParseContext context = new ParseContext();
            context.set(OfficeParserConfig.class, new OfficeParserConfig());
            CapturingEmbeddedExtractor embeddedExtractor =
                    new CapturingEmbeddedExtractor();
            context.set(EmbeddedDocumentExtractor.class, embeddedExtractor);
            try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
                 OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
                ByteArrayOutputStream nativeStream = new ByteArrayOutputStream();
                new Ole10Native("payload.bin", "C:\\payload.bin",
                        "C:\\payload.bin",
                        new byte[]{'P', 'A', 'Y', 'L', 'O', 'A', 'D'})
                        .writeOut(nativeStream);
                PackagePart document =
                        addObfuscatedOle(opcPackage, nativeStream.toByteArray());

                new EmptyExtractor(context, opcPackage, List.of(document))
                        .getXHTML(new BodyContentHandler(-1),
                                new Metadata(), context);
            }

            assertNull(embeddedExtractor.payload,
                    "records over POI's configured cap must not reach downstream parsers");
        } finally {
            Ole10Native.setMaxRecordLength(previousMaxRecordLength);
        }
    }

    @Test
    public void testObfuscatedOleOrdinarySaxFailurePropagates()
            throws Exception {
        ParseContext context = new ParseContext();
        context.set(OfficeParserConfig.class, new OfficeParserConfig());
        context.set(EmbeddedDocumentExtractor.class,
                new SaxRejectingEmbeddedExtractor());
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            PackagePart document = addObfuscatedOle(opcPackage);

            SAXException exception = assertThrows(SAXException.class,
                    () -> new EmptyExtractor(context, opcPackage, List.of(document))
                            .getXHTML(new BodyContentHandler(-1),
                                    new Metadata(), context));
            assertEquals("simulated embedded SAX failure",
                    exception.getMessage());
        }
    }

    private static PackagePart addObfuscatedOle(OPCPackage opcPackage)
            throws Exception {
        byte[] payload = obfuscatedPayload();
        byte[] nativeStream = ByteBuffer.allocate(4 + payload.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(payload.length)
                .put(payload)
                .array();
        return addObfuscatedOle(opcPackage, nativeStream);
    }

    private static PackagePart addObfuscatedOle(OPCPackage opcPackage,
                                                byte[] nativeStream)
            throws Exception {
        PackagePart document = opcPackage.createPart(
                PackagingURIHelper.createPartName("/word/document.xml"),
                "application/xml");
        PackagePart ole = opcPackage.createPart(
                PackagingURIHelper.createPartName(
                        "/word/embeddings/oleObject1.bin"),
                "application/vnd.openxmlformats-officedocument.oleObject");
        try (POIFSFileSystem fs = new POIFSFileSystem();
             ByteArrayInputStream input =
                     new ByteArrayInputStream(nativeStream);
             java.io.OutputStream output = ole.getOutputStream()) {
            fs.createDocument(input, "\u0001oLE10nAtiVe");
            fs.writeFilesystem(output);
        }
        document.addRelationship(
                ole.getPartName(), TargetMode.INTERNAL,
                "http://schemas.openxmlformats.org/officeDocument/"
                        + "2006/relationships/oleObject");
        return document;
    }

    private static byte[] obfuscatedPayload() {
        // flags1=2 asks POI to parse null-terminated strings, but none follow.
        // POI rejects the malformed Ole10Native record; Tika's bounded
        // best-effort fallback must still recover these exact payload bytes.
        return new byte[]{2, 0, 'A', 'A', 'A', 'A', 'A'};
    }

    private static byte[] parsedOle10Native(
            int firstLabelByte, byte[] payload) {
        byte[] label = new byte[]{(byte) firstLabelByte, 0};
        byte[] fileName = new byte[]{'f', 0};
        byte[] command = new byte[]{'c', 0};
        int totalSize = Short.BYTES
                + label.length
                + fileName.length
                + 2 * Short.BYTES
                + Integer.BYTES
                + command.length
                + Integer.BYTES
                + payload.length
                + Short.BYTES;
        return ByteBuffer.allocate(Integer.BYTES + totalSize)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(totalSize)
                .putShort((short) 2)
                .put(label)
                .put(fileName)
                .putShort((short) 0)
                .putShort((short) 3)
                .putInt(command.length)
                .put(command)
                .putInt(payload.length)
                .put(payload)
                .putShort((short) 0)
                .array();
    }

    private static ParseContext macroParseContext() {
        OfficeParserConfig config = new OfficeParserConfig();
        config.setExtractMacros(true);
        ParseContext context = new ParseContext();
        context.set(OfficeParserConfig.class, config);
        return context;
    }

    private static PackagePart createXmlPart(
            OPCPackage opcPackage, String partName) throws Exception {
        return opcPackage.createPart(
                PackagingURIHelper.createPartName(partName),
                "application/xml");
    }

    private static PackagePart createMacroPart(
            OPCPackage opcPackage, String partName) throws Exception {
        return opcPackage.createPart(
                PackagingURIHelper.createPartName(partName),
                "application/vnd.ms-office.vbaProject");
    }

    private static final class LinkWriteLimitHandler extends DefaultHandler {
        @Override
        public void startElement(String uri, String localName, String qName,
                                 Attributes attributes) throws SAXException {
            if ("a".equals(localName) || "a".equals(qName)) {
                throw new WriteLimitReachedException(0);
            }
        }
    }

    private static final class LinkRejectingHandler extends DefaultHandler {
        @Override
        public void startElement(String uri, String localName, String qName,
                                 Attributes attributes) throws SAXException {
            if ("a".equals(localName) || "a".equals(qName)) {
                throw new SAXException("simulated strict content handler");
            }
        }
    }

    private static final class WriteLimitEmbeddedExtractor
            implements EmbeddedDocumentExtractor {

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return true;
        }

        @Override
        public void parseEmbedded(TikaInputStream stream,
                                  ContentHandler handler,
                                  Metadata metadata,
                                  ParseContext context,
                                  boolean outputHtml)
                throws SAXException {
            throw new WriteLimitReachedException(0);
        }
    }

    private static final class SaxRejectingEmbeddedExtractor
            implements EmbeddedDocumentExtractor {

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return true;
        }

        @Override
        public void parseEmbedded(TikaInputStream stream,
                                  ContentHandler handler,
                                  Metadata metadata,
                                  ParseContext context,
                                  boolean outputHtml)
                throws SAXException {
            throw new SAXException("simulated embedded SAX failure");
        }
    }

    private static final class CapturingEmbeddedExtractor
            implements EmbeddedDocumentExtractor {

        private byte[] payload;
        private String contentLength;

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return true;
        }

        @Override
        public void parseEmbedded(TikaInputStream stream,
                                  ContentHandler handler,
                                  Metadata metadata,
                                  ParseContext context,
                                  boolean outputHtml)
                throws IOException {
            payload = stream.readAllBytes();
            contentLength =
                    metadata.get(org.apache.tika.metadata.HttpHeaders.CONTENT_LENGTH);
        }
    }

    private static final class EmptyExtractor extends AbstractOOXMLExtractor {

        private final List<PackagePart> mainParts;

        private EmptyExtractor(ParseContext context, OPCPackage opcPackage) {
            this(context, opcPackage, Collections.emptyList());
        }

        private EmptyExtractor(ParseContext context, OPCPackage opcPackage,
                               List<PackagePart> mainParts) {
            super(context, opcPackage);
            this.mainParts = mainParts;
        }

        @Override
        protected void buildXHTML(XHTMLContentHandler xhtml)
                throws SAXException, IOException {
        }

        @Override
        protected List<PackagePart> getMainDocumentParts() {
            return mainParts;
        }
    }

    private static final class MacroTrackingExtractor
            extends AbstractOOXMLExtractor {

        private final List<PackagePart> earlyMainParts;
        private final List<PackagePart> normalMainParts;
        private final List<String> macroPartNames = new ArrayList<>();
        private int mainPartsCalls;

        private MacroTrackingExtractor(
                ParseContext context, OPCPackage opcPackage,
                List<PackagePart> earlyMainParts,
                List<PackagePart> normalMainParts) {
            super(context, opcPackage);
            this.earlyMainParts = earlyMainParts;
            this.normalMainParts = normalMainParts;
        }

        @Override
        protected void buildXHTML(XHTMLContentHandler xhtml) {
        }

        @Override
        protected List<PackagePart> getMainDocumentParts() {
            return mainPartsCalls++ == 0
                    ? earlyMainParts : normalMainParts;
        }

        @Override
        void handleMacros(
                PackagePart macroPart, ContentHandler handler) {
            macroPartNames.add(macroPart.getPartName().getName());
        }
    }
}
