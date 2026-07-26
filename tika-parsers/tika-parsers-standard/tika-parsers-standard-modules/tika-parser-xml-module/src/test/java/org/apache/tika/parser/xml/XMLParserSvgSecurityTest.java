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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.apache.tika.config.EmbeddedLimits;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.ImageHash;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

class XMLParserSvgSecurityTest {

    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk"
                    + "YAAAAAYAAjCB0C8AAAAASUVORK5CYII=");

    @TempDir
    Path temporaryDirectory;

    @Test
    void testSvgNormalizationRemovesExternalRefsAcrossQuoteStyles() throws Exception {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\">" +
                "<defs><g id=\"local\"><rect width=\"10\" height=\"10\"/></g></defs>" +
                "<use href='#local'/>" +
                "<use href='http://example.com/external.svg#x'/>" +
                "<image href='data:image/png;base64,AAAA'/>" +
                "<image xlink:href=\"file:///etc/passwd\" xmlns:xlink=\"http://www.w3.org/1999/xlink\"/>" +
                "</svg>";
        Path input = Files.createTempFile("tika-svg-test-", ".svg");
        Files.write(input, svg.getBytes(StandardCharsets.UTF_8));
        Path normalized = XMLParser.normalizeSvgHrefs(input);
        String normalizedXml = Files.readString(normalized, StandardCharsets.UTF_8);

        assertTrue(normalizedXml.contains("#local"));
        assertFalse(normalizedXml.contains("http://example.com"));
        assertFalse(normalizedXml.contains("data:image/png"));
        assertFalse(normalizedXml.contains("file:///etc/passwd"));

        Files.deleteIfExists(input);
        Files.deleteIfExists(normalized);
    }

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
            String svg = """
                    <svg xmlns="http://www.w3.org/2000/svg" width="64" height="64">
                      <rect width="64" height="64" fill="red"/>
                      <image href='http://127.0.0.1:%d/pixel.png'
                             width="64" height="64"/>
                    </svg>
                    """.formatted(server.getLocalPort());

            ParseResult result = parse(svg);
            server.close();
            responder.join(2000);

            assertFalse(requested.get());
            assertNotNull(result.metadata.get(ImageHash.PHASH), warnings(result.metadata));
        }
    }

    @Test
    void testRasterizationDoesNotResolveExternalXmlEntities() throws Exception {
        String secret = "SVG_XXE_SECRET_SHOULD_NOT_LEAK";
        Path secretFile = temporaryDirectory.resolve("secret.txt");
        Files.writeString(secretFile, secret, StandardCharsets.UTF_8);
        String svg = """
                <!DOCTYPE svg [
                  <!ENTITY xxe SYSTEM "%s">
                ]>
                <svg xmlns="http://www.w3.org/2000/svg" width="64" height="64">
                  <rect width="64" height="64" fill="red"/>
                  <text x="2" y="32">&xxe;</text>
                </svg>
                """.formatted(secretFile.toUri());

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

    private static ParseResult parse(String svg) throws Exception {
        Metadata metadata = new Metadata();
        metadata.set(Metadata.CONTENT_TYPE, "image/svg+xml");
        ParseContext context = new ParseContext();
        context.set(EmbeddedLimits.class, new EmbeddedLimits());
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

    private record ParseResult(String body, Metadata metadata) {
    }
}
