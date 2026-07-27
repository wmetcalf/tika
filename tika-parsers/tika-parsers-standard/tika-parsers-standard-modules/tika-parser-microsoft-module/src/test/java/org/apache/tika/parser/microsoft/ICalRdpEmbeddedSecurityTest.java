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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.writefilter.MetadataWriteLimiterFactory;
import org.apache.tika.metadata.writefilter.StandardMetadataLimiterFactory;
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

    private static void parse(Parser parser, String input, ParseContext context)
            throws Exception {
        try (TikaInputStream stream = TikaInputStream.get(
                input.getBytes(StandardCharsets.UTF_8))) {
            parser.parse(stream, new BodyContentHandler(-1), new Metadata(), context);
        }
    }
}
