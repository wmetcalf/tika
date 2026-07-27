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
package org.apache.tika.parser.xml;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.config.EmbeddedLimits;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.ImageHash;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;

class XMLParserSvgSecurityTest {

    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk"
                    + "YAAAAAYAAjCB0C8AAAAASUVORK5CYII=");

    @TempDir
    Path temporaryDirectory;

    @Test
    void testOrdinarySvgProducesRasterHashes() throws Exception {
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="64" height="64">
                  <rect width="64" height="64" fill="red"/>
                  <text x="2" y="32">visible-svg-text</text>
                </svg>
                """;

        ParseResult result = parse(svg);

        assertTrue(result.body.contains("visible-svg-text"));
        assertNotNull(result.metadata.get(ImageHash.PHASH), warnings(result.metadata));
        assertNotNull(result.metadata.get(ImageHash.DHASH));
        assertNotNull(result.metadata.get(ImageHash.AHASH));
    }

    @Test
    void testRasterizationDoesNotFetchExternalImages() throws Exception {
        AtomicBoolean requested = new AtomicBoolean();
        try (ServerSocket server = new ServerSocket(
                0, 1, InetAddress.getLoopbackAddress())) {
            Thread responder = new Thread(() -> respondOnce(server, requested));
            responder.setDaemon(true);
            responder.start();
            String svg = String.format(Locale.ROOT, """
                    <svg xmlns="http://www.w3.org/2000/svg" width="64" height="64">
                      <rect width="64" height="64" fill="red"/>
                      <image href='http://127.0.0.1:%d/pixel.png'
                             width="64" height="64"/>
                    </svg>
                    """, server.getLocalPort());

            ParseResult result = parse(svg);
            server.close();
            responder.join(2000);

            assertFalse(requested.get());
            assertNotNull(result.metadata.get(ImageHash.PHASH), warnings(result.metadata));
            assertTrue(result.metadata
                    .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0,
                    "a raster that omits image elements must be marked incomplete");
            assertNotNull(result.metadata.get("ExploitClass"));
        }
    }

    @Test
    void testRasterizationDoesNotResolveExternalXmlEntities() throws Exception {
        String secret = "SVG_XXE_SECRET_SHOULD_NOT_LEAK";
        Path secretFile = temporaryDirectory.resolve("secret.txt");
        Files.writeString(secretFile, secret, StandardCharsets.UTF_8);
        String svg = String.format(Locale.ROOT, """
                <!DOCTYPE svg [
                  <!ENTITY xxe SYSTEM "%s">
                ]>
                <svg xmlns="http://www.w3.org/2000/svg" width="64" height="64">
                  <rect width="64" height="64" fill="red"/>
                  <text x="2" y="32">&xxe;</text>
                </svg>
                """, secretFile.toUri());

        ParseResult result = parse(svg);

        assertFalse(result.body.contains(secret));
        assertNotNull(result.metadata.get(ImageHash.PHASH), warnings(result.metadata));
    }

    @Test
    void testRasterFailureIsVisibleInWarningMetadata() throws Exception {
        ParseResult result = parse("<not-svg/>");

        assertNull(result.metadata.get(ImageHash.PHASH));
        assertTrue(result.metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0);
    }

    @Test
    void testOversizedSvgRasterSkipIsSecurityVisible() throws Exception {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" "
                + "width=\"64\" height=\"64\"><rect width=\"64\" height=\"64\" "
                + "fill=\"red\"/><text x=\"2\" y=\"32\">visible-svg-text</text><!--"
                + "A".repeat(10 * 1024 * 1024 + 256)
                + "--></svg>";

        ParseResult result = parse(svg);

        assertNull(result.metadata.get(ImageHash.PHASH));
        assertTrue(result.metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0);
        assertNotNull(result.metadata.get("ExploitClass"),
                "a configured enrichment limit must not look like a clean negative");
    }

    @Test
    void testSvgOcrFailureIsSecurityVisibleEvenWhenHashingSucceeds() throws Exception {
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="64" height="64">
                  <rect width="64" height="64" fill="red"/>
                  <text x="2" y="32">visible-svg-text</text>
                </svg>
                """;
        ThrowingOcrParser ocrParser = new ThrowingOcrParser();
        ParseContext context = new ParseContext();
        context.set(EmbeddedLimits.class, new EmbeddedLimits());
        context.set(Parser.class, ocrParser);

        ParseResult result = parse(svg, context);

        assertTrue(ocrParser.invoked);
        assertNotNull(result.metadata.get(ImageHash.PHASH), warnings(result.metadata));
        assertTrue(result.metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0);
        assertNotNull(result.metadata.get("ExploitClass"),
                "a configured OCR backend failure must not look like a clean negative");
    }

    @Test
    void testDirectParserContextStillRunsSvgRasterEnrichment() throws Exception {
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="64" height="64">
                  <rect width="64" height="64" fill="red"/>
                  <text x="2" y="32">visible-svg-text</text>
                </svg>
                """;

        ParseResult result = parse(svg, new ParseContext());

        assertNotNull(result.metadata.get(ImageHash.PHASH),
                "ordinary direct Parser API calls must not silently disable SVG hashing");
    }

    @Test
    void testDeepUseChainDoesNotEscapeRasterEnrichment() throws Exception {
        StringBuilder svg = new StringBuilder(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"32\" height=\"32\">"
                        + "<defs>");
        for (int i = 0; i < 5_000; i++) {
            svg.append("<g id=\"g").append(i).append("\"><use href=\"#g")
                    .append(i + 1).append("\"/></g>");
        }
        svg.append("<g id=\"g5000\"><rect width=\"32\" height=\"32\"/></g>")
                .append("</defs><use href=\"#g0\"/></svg>");

        ParseResult result = parse(svg.toString());

        assertNull(result.metadata.get(ImageHash.PHASH));
        assertTrue(result.metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0);
    }

    @Test
    void testExponentialUseGraphIsRejectedBeforeBatikExpansion() throws Exception {
        StringBuilder svg = new StringBuilder(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"32\" height=\"32\">"
                        + "<defs><g id=\"g0\"><rect width=\"1\" height=\"1\"/></g>");
        for (int i = 1; i <= 13; i++) {
            svg.append("<g id=\"g").append(i).append("\"><use href=\"#g")
                    .append(i - 1).append("\"/><use href=\"#g")
                    .append(i - 1).append("\"/></g>");
        }
        svg.append("</defs><use href=\"#g13\"/></svg>");

        ParseResult result = parse(svg.toString());

        assertNull(result.metadata.get(ImageHash.PHASH));
        assertTrue(result.metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0);
        assertNotNull(result.metadata.get("ExploitClass"),
                "unsafe internal use expansion must not reach Batik");
    }

    @Test
    void testSharedUseTargetsHaveCumulativeExpansionBudget() throws Exception {
        StringBuilder svg = new StringBuilder(
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><g id=\"target\">");
        for (int i = 0; i < 20_000; i++) {
            svg.append("<rect/>");
        }
        svg.append("</g>");
        for (int i = 0; i < 100; i++) {
            svg.append("<use href=\"#target\"/>");
        }
        svg.append("</svg>");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(
                new ByteArrayInputStream(
                        svg.toString().getBytes(StandardCharsets.UTF_8)));
        NodeList uses = document.getElementsByTagNameNS(
                "http://www.w3.org/2000/svg", "use");
        Method validate = XMLParser.class.getDeclaredMethod(
                "validateSvgUseGraph", Document.class, NodeList.class);
        validate.setAccessible(true);

        InvocationTargetException thrown = assertThrows(
                InvocationTargetException.class,
                () -> validate.invoke(null, document, uses));
        assertTrue(thrown.getCause() instanceof IOException,
                "repeated traversal of one shared subtree must exhaust a "
                        + "cumulative expansion-node budget");
    }

    @Test
    void testSelfReferentialUseIsRejectedBeforeBatikExpansion()
            throws Exception {
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="32" height="32">
                  <use id="cycle" href="#cycle"/>
                </svg>
                """;

        ParseResult result = parse(svg);

        assertTrue(warnings(result.metadata).contains(
                        "safe reference depth or expansion limits"),
                "a use that expands itself must be rejected by the graph "
                        + "validator, before Batik processes the cycle");
    }

    @Test
    void testExternalReferenceCardinalityIsBoundedAndSignaled() throws Exception {
        StringBuilder svg = new StringBuilder(
                "<svg xmlns=\"http://www.w3.org/2000/svg\">");
        for (int i = 0; i < 5_000; i++) {
            svg.append("<a href=\"https://example.invalid/")
                    .append(i).append("\"/>");
        }
        svg.append("</svg>");

        ParseResult result = parse(svg.toString());

        assertTrue(result.metadata.getValues("svg:link").length <= 4_096);
        assertTrue(result.metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0);
        assertNotNull(result.metadata.get("ExploitClass"),
                "truncated external-script/reference extraction must fail closed");
    }

    @Test
    void testElementFloodSkipsDomRasterizationAndFailsClosed() throws Exception {
        StringBuilder svg = new StringBuilder(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\">");
        for (int i = 0; i < 50_001; i++) {
            svg.append("<g/>");
        }
        svg.append("</svg>");

        ParseResult result = assertTimeoutPreemptively(
                Duration.ofSeconds(5), () -> parse(svg.toString()));

        assertNull(result.metadata.get(ImageHash.PHASH));
        assertTrue(warnings(result.metadata).contains("element limit"));
        assertNotNull(result.metadata.get("ExploitClass"));
    }

    @Test
    void testExternalScriptsUseStandardSvgReferenceAttributes() throws Exception {
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg"
                     xmlns:xlink="http://www.w3.org/1999/xlink">
                  <script href="https://example.invalid/standard.js"/>
                  <script xlink:href="https://example.invalid/legacy.js"/>
                  <script src="https://example.invalid/src.js"/>
                </svg>
                """;

        ParseResult result = parse(svg);

        assertTrue(java.util.Arrays.asList(
                        result.metadata.getValues("svg:externalScript"))
                .containsAll(java.util.List.of(
                        "https://example.invalid/standard.js",
                        "https://example.invalid/legacy.js",
                        "https://example.invalid/src.js")));
    }

    @Test
    void testParameterizedSvgMediaTypeRetainsSecurityEnrichment() throws Exception {
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg">
                  <script href="https://example.invalid/parameterized.js"/>
                </svg>
                """;

        ParseResult result = parse(svg, "image/svg+xml; charset=UTF-8");

        assertTrue(java.util.Arrays.asList(
                        result.metadata.getValues("svg:externalScript"))
                .contains("https://example.invalid/parameterized.js"));
    }

    @Test
    void testMalformedSvgDoesNotEnterQuadraticRasterNormalization() {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\">"
                + "<image ".repeat(20_000);

        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertThrows(Exception.class, () -> parse(svg)));
    }

    private static ParseResult parse(String svg) throws Exception {
        ParseContext context = new ParseContext();
        context.set(EmbeddedLimits.class, new EmbeddedLimits());
        return parse(svg, context);
    }

    private static ParseResult parse(String svg, ParseContext context) throws Exception {
        return parse(svg, context, "image/svg+xml");
    }

    private static ParseResult parse(String svg, String contentType) throws Exception {
        ParseContext context = new ParseContext();
        context.set(EmbeddedLimits.class, new EmbeddedLimits());
        return parse(svg, context, contentType);
    }

    private static ParseResult parse(String svg, ParseContext context, String contentType)
            throws Exception {
        Metadata metadata = new Metadata();
        metadata.set(Metadata.CONTENT_TYPE, contentType);
        BodyContentHandler body = new BodyContentHandler(-1);
        try (TikaInputStream stream = TikaInputStream.get(
                svg.getBytes(StandardCharsets.UTF_8))) {
            new XMLParser().parse(stream, body, metadata, context);
        }
        return new ParseResult(body.toString(), metadata);
    }

    private static void respondOnce(ServerSocket server, AtomicBoolean requested) {
        try (Socket socket = server.accept()) {
            requested.set(true);
            socket.getOutputStream().write((
                    "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: "
                            + ONE_PIXEL_PNG.length + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(ONE_PIXEL_PNG);
            socket.getOutputStream().flush();
        } catch (IOException ignored) {
        }
    }

    private static String warnings(Metadata metadata) {
        return String.join("\n",
                metadata.getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING));
    }

    private static final class ThrowingOcrParser implements Parser {
        private static final long serialVersionUID = 1L;
        private boolean invoked;

        @Override
        public Set<MediaType> getSupportedTypes(ParseContext context) {
            return Set.of(MediaType.image("ocr-png"));
        }

        @Override
        public void parse(TikaInputStream stream, ContentHandler handler,
                          Metadata metadata, ParseContext context)
                throws IOException, SAXException, TikaException {
            invoked = true;
            throw new TikaException("simulated OCR backend failure");
        }
    }

    private record ParseResult(String body, Metadata metadata) {
    }
}
