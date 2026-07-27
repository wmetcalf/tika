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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.detect.Detector;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.metadata.writefilter.MetadataWriteLimiterFactory;
import org.apache.tika.metadata.writefilter.StandardMetadataLimiterFactory;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;

public class ICalRdpEmbeddedSecurityTest {

    private static final String ICAL_ALT_DESC = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:test-alt-desc
            X-ALT-DESC;FMTTYPE=text/html:<html><body>embedded html</body></html>
            END:VEVENT
            END:VCALENDAR
            """;

    private static final String ICAL_ATTACHMENT = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:test-attachment
            ATTACH;ENCODING=BASE64;VALUE=BINARY:QUJD
            END:VEVENT
            END:VCALENDAR
            """;

    private static final String RDP_CERTIFICATE = "pcb:b:QUJD\n";

    @Test
    public void testForkEmbeddedMetadataUsesContextLimiter() throws Exception {
        ParseContext context = new ParseContext();
        StandardMetadataLimiterFactory factory = new StandardMetadataLimiterFactory();
        factory.setIncludeFields(Set.of("allowed"));
        context.set(MetadataWriteLimiterFactory.class, factory);
        AtomicInteger limitedMetadataObjects = new AtomicInteger();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                metadata.set("not-allowed", "probe");
                if (metadata.get("not-allowed") == null) {
                    limitedMetadataObjects.incrementAndGet();
                }
                return false;
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext parseContext,
                                      boolean outputHtml) {
                throw new AssertionError("embedded parsing should be disabled");
            }
        });

        parse(new ICalParser(), ICAL_ALT_DESC, context);
        parse(new ICalParser(), ICAL_ATTACHMENT, context);
        parse(new RdpParser(), RDP_CERTIFICATE, context);

        assertEquals(3, limitedMetadataObjects.get(),
                "every fork-created embedded Metadata must inherit the context limiter");
    }

    @Test
    public void testIcalAltDescWriteLimitPropagates() {
        assertThrows(WriteLimitReachedException.class,
                () -> parseWithFailure(new ICalParser(), ICAL_ALT_DESC,
                        new WriteLimitReachedException(7)));
    }

    @Test
    public void testIcalAltDescSecurityExceptionPropagates() {
        assertThrows(SecurityException.class,
                () -> parseWithFailure(new ICalParser(), ICAL_ALT_DESC,
                        new SecurityException("simulated HTML security boundary")));
    }

    @Test
    public void testIcalAttachmentWriteLimitPropagates() {
        assertThrows(WriteLimitReachedException.class,
                () -> parseWithFailure(new ICalParser(), ICAL_ATTACHMENT,
                        new WriteLimitReachedException(7)));
    }

    @Test
    public void testIcalAttachmentSecurityExceptionPropagates() {
        assertThrows(SecurityException.class,
                () -> parseWithFailure(new ICalParser(), ICAL_ATTACHMENT,
                        new SecurityException("simulated attachment security boundary")));
    }

    @Test
    public void testIcalAttachmentDownstreamSaxExceptionPropagates() {
        SAXException denial =
                new SAXException("simulated attachment output policy denial");
        ParseContext context = embeddedOutputContext("blocked attachment output");

        SAXException thrown = assertThrows(SAXException.class,
                () -> parse(new ICalParser(), ICAL_ATTACHMENT, context,
                        new TextRejectingHandler(
                                "blocked attachment output", denial)));

        assertSame(denial, thrown);
    }

    @Test
    public void testIcalAltDescDownstreamSaxExceptionPropagates() {
        SAXException denial =
                new SAXException("simulated alternate-description output policy denial");
        ParseContext context = embeddedOutputContext("blocked alternate description");

        SAXException thrown = assertThrows(SAXException.class,
                () -> parse(new ICalParser(), ICAL_ALT_DESC, context,
                        new TextRejectingHandler(
                                "blocked alternate description", denial)));

        assertSame(denial, thrown);
    }

    @Test
    public void testIcalAttachmentParserSaxFailureRemainsBestEffort() {
        assertDoesNotThrow(
                () -> parseWithFailure(new ICalParser(), ICAL_ATTACHMENT,
                        new SAXException("simulated malformed attachment")));
    }

    @Test
    public void testIcalAttachmentMimeDetectionSecurityExceptionPropagates() {
        SecurityException denial =
                new SecurityException("simulated attachment MIME policy denial");
        ParseContext context = new ParseContext();
        context.set(Detector.class, (stream, metadata, parseContext) -> {
            throw denial;
        });
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return false;
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext parseContext,
                                      boolean outputHtml) {
                throw new AssertionError("embedded parsing should be disabled");
            }
        });

        SecurityException thrown = assertThrows(SecurityException.class,
                () -> parse(new ICalParser(), ICAL_ATTACHMENT, context));

        assertSame(denial, thrown);
    }

    @Test
    public void testIcalRepeatedAttachmentsAreAllProcessed() throws Exception {
        String ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:test-repeated-attachments
                ATTACH;ENCODING=BASE64;VALUE=BINARY:QUJD
                ATTACH;ENCODING=BASE64;VALUE=BINARY:REVG
                END:VEVENT
                END:VCALENDAR
                """;

        Metadata metadata = parse(new ICalParser(), ical, noEmbeddedContext());

        assertArrayEquals(new String[]{
                "b5d4045c3f466fa91fe2cc6abe79232a1a57cdf104f7a26e716e0a1e2789df78",
                "967c5a5b7e2fbbe3080a0c5cefea7c279570b16ae8465525538bc3b115267a45"
        }, metadata.getValues("ical:attach_sha256"));
    }

    @Test
    public void testIcalAttachmentHttpSchemeIsCaseInsensitive() throws Exception {
        String ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:test-uppercase-attachment-url
                ATTACH:HTTPS://attacker.example/payload.html
                END:VEVENT
                END:VCALENDAR
                """;

        Metadata metadata = parse(new ICalParser(), ical, noEmbeddedContext());

        assertArrayEquals(new String[]{"HTTPS://attacker.example/payload.html"},
                metadata.getValues("ical:attach_url"),
                "the bare compatibility alias must not duplicate attachment processing");
    }

    @Test
    public void testIcalAbsoluteAttachmentUriSchemesAreRetained()
            throws Exception {
        String ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:test-absolute-attachment-uri
                ATTACH;VALUE=URI:ftp://example.invalid/attachment.bin
                END:VEVENT
                END:VCALENDAR
                """;

        Metadata metadata = parse(new ICalParser(), ical, noEmbeddedContext());

        assertArrayEquals(
                new String[]{"ftp://example.invalid/attachment.bin"},
                metadata.getValues("ical:attach_url"));
    }

    @Test
    public void testIcalExtensionParameterDoesNotForceUriIntoBase64()
            throws Exception {
        String ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:test-extension-attachment-parameter
                ATTACH;VALUE=URI;X-ENCODING=BETA:ftp://example.invalid/payload.bin
                END:VEVENT
                END:VCALENDAR
                """;

        Metadata metadata = parse(new ICalParser(), ical, noEmbeddedContext());

        assertArrayEquals(
                new String[]{"ftp://example.invalid/payload.bin"},
                metadata.getValues("ical:attach_url"));
        assertEquals(0, metadata.getValues("ical:attach_sha256").length);
    }

    @Test
    public void testIcalShortBase64EncodingRemainsSupported()
            throws Exception {
        String ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:test-short-base64-encoding
                ATTACH;ENCODING=B;VALUE=BINARY:QUJD
                END:VEVENT
                END:VCALENDAR
                """;

        Metadata metadata = parse(new ICalParser(), ical, noEmbeddedContext());

        assertArrayEquals(
                new String[]{
                        "b5d4045c3f466fa91fe2cc6abe79232a1a57cdf104f7a26e716e0a1e2789df78"
                },
                metadata.getValues("ical:attach_sha256"));
        assertEquals(0, metadata.getValues("ical:attach_url").length);
    }

    @Test
    public void testIcalQuotedExtensionParameterCannotHideBase64Encoding()
            throws Exception {
        String ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:test-quoted-extension-parameter
                ATTACH;X-NOTE=";encoding=none|tika-index=777";ENCODING=BASE64;VALUE=BINARY:QUJD
                END:VEVENT
                END:VCALENDAR
                """;

        Metadata metadata = parse(new ICalParser(), ical, noEmbeddedContext());

        assertArrayEquals(
                new String[]{
                        "b5d4045c3f466fa91fe2cc6abe79232a1a57cdf104f7a26e716e0a1e2789df78"
                },
                metadata.getValues("ical:attach_sha256"));
    }

    @Test
    public void testIcalMalformedAttachmentValueIsNotReportedAsUri()
            throws Exception {
        String ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:test-malformed-attachment-uri
                ATTACH;VALUE=URI:not a valid URI
                END:VEVENT
                END:VCALENDAR
                """;

        Metadata metadata = parse(new ICalParser(), ical, noEmbeddedContext());

        assertEquals(0, metadata.getValues("ical:attach_url").length);
    }

    @Test
    public void testIcalAttachmentCountIsBoundedAndSignaled() throws Exception {
        StringBuilder ical = new StringBuilder("""
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:test-attachment-count-limit
                """);
        for (int i = 0; i < 1_001; i++) {
            ical.append("ATTACH:https://attacker.example/").append(i).append('\n');
        }
        ical.append("""
                END:VEVENT
                END:VCALENDAR
                """);

        Metadata metadata = parse(
                new ICalParser(), ical.toString(), noEmbeddedContext());

        assertEquals(1_000, metadata.getValues("ical:attach_url").length);
        assertEquals("true",
                metadata.get(TikaCoreProperties.TRUNCATED_METADATA));
        assertNotNull(metadata.get(
                TikaCoreProperties.TIKA_META_EXCEPTION_WARNING));
        assertTrue(metadata.get("ExploitClass").contains(
                "ATTACH analysis incomplete"));
    }

    @Test
    public void testDataUriAttachmentUsesLocalEmbeddedRoute() throws Exception {
        String ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:test-data-uri-attachment
                ATTACH;VALUE=URI:data:text/html;base64,PGh0bWw+PGJvZHk+ZGF0YSB1cmk8L2JvZHk+PC9odG1sPg==
                END:VEVENT
                END:VCALENDAR
                """;
        byte[] expected =
                "<html><body>data uri</body></html>"
                        .getBytes(StandardCharsets.UTF_8);
        AtomicInteger embeddedParses = new AtomicInteger();
        ParseContext context = new ParseContext();
        context.set(Detector.class,
                (stream, metadata, parseContext) -> MediaType.TEXT_HTML);
        context.set(EmbeddedDocumentExtractor.class,
                new EmbeddedDocumentExtractor() {
                    @Override
                    public boolean shouldParseEmbedded(Metadata metadata) {
                        return true;
                    }

                    @Override
                    public void parseEmbedded(
                            TikaInputStream stream, ContentHandler handler,
                            Metadata metadata, ParseContext parseContext,
                            boolean outputHtml) throws IOException {
                        embeddedParses.incrementAndGet();
                        assertArrayEquals(expected, stream.readAllBytes());
                    }
                });

        Metadata metadata = parse(new ICalParser(), ical, context);

        assertArrayEquals(
                new String[]{
                        "9e7bea75842350d9e58a4d67d26a2e0c0f8986d17289655239684cfa5c4bd396"
                },
                metadata.getValues("ical:attach_sha256"));
        assertArrayEquals(
                new String[]{"text/html"},
                metadata.getValues("ical:attach_mime"));
        assertEquals("true", metadata.get("ical:attach_html"));
        assertTrue(metadata.get("ExploitClass").contains(
                "Inline ATTACH is an HTML file"));
        assertEquals(0, metadata.getValues("ical:attach_url").length,
                "data URIs must never enter the remote attachment route");
        assertEquals(1, embeddedParses.get());
    }

    @Test
    public void testRdpCertificateWriteLimitPropagates() {
        assertThrows(WriteLimitReachedException.class,
                () -> parseWithFailure(new RdpParser(), RDP_CERTIFICATE,
                        new WriteLimitReachedException(7)));
    }

    @Test
    public void testRdpCertificateSecurityExceptionPropagates() {
        assertThrows(SecurityException.class,
                () -> parseWithFailure(new RdpParser(), RDP_CERTIFICATE,
                        new SecurityException("simulated certificate security boundary")));
    }

    @Test
    public void testRdpCertificateDownstreamSaxDenialPropagates() {
        String rejectedText = "blocked RDP certificate output";
        SAXException denial =
                new SAXException("simulated RDP output policy denial");
        ParseContext context = embeddedOutputContext(rejectedText);

        SAXException thrown = assertThrows(SAXException.class,
                () -> parse(new RdpParser(), RDP_CERTIFICATE, context,
                        new TextRejectingHandler(rejectedText, denial)));

        assertSame(denial, thrown);
    }

    private static void parseWithFailure(Parser parser, String input, Exception failure)
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
        parse(parser, input, context);
    }

    private static ParseContext embeddedOutputContext(String output) {
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
        return context;
    }

    private static ParseContext noEmbeddedContext() {
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return false;
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext parseContext,
                                      boolean outputHtml) {
                throw new AssertionError("embedded parsing should be disabled");
            }
        });
        return context;
    }

    private static Metadata parse(Parser parser, String input, ParseContext context)
            throws Exception {
        return parse(parser, input, context, new BodyContentHandler(-1));
    }

    private static Metadata parse(Parser parser, String input, ParseContext context,
                                  ContentHandler handler)
            throws Exception {
        Metadata metadata = new Metadata();
        try (TikaInputStream stream = TikaInputStream.get(
                input.getBytes(StandardCharsets.UTF_8))) {
            parser.parse(stream, handler, metadata, context);
        }
        return metadata;
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
}
