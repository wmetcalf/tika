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
package org.apache.tika.parser.microsoft.chm;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.TikaTest;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.metadata.writefilter.MetadataWriteLimiterFactory;
import org.apache.tika.metadata.writefilter.StandardMetadataLimiterFactory;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

public class ChmParserSecurityTest extends TikaTest {

    private static final String CHM_FIXTURE = "/test-documents/testChm.chm";

    @Test
    public void testEmbeddedSecurityExceptionPropagates() {
        SecurityException failure =
                new SecurityException("simulated CHM embedded security boundary");

        SecurityException thrown = assertThrows(SecurityException.class,
                () -> parseWithEmbeddedException(failure));

        assertSame(failure, thrown);
    }

    @Test
    public void testEmbeddedWriteLimitPropagates() {
        WriteLimitReachedException failure = new WriteLimitReachedException(7);

        WriteLimitReachedException thrown = assertThrows(WriteLimitReachedException.class,
                () -> parseWithEmbeddedException(failure));

        assertSame(failure, thrown);
    }

    @Test
    public void testEmbeddedDownstreamSaxDenialPropagates() throws Exception {
        String rejectedText = "blocked CHM embedded output";
        SAXException denial =
                new SAXException("simulated CHM output policy denial");
        ParseContext context = new ParseContext();
        AtomicBoolean pendingOutput = new AtomicBoolean(true);
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return pendingOutput.get();
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext parseContext,
                                      boolean outputHtml) throws SAXException {
                if (!pendingOutput.compareAndSet(true, false)) {
                    return;
                }
                char[] chars = rejectedText.toCharArray();
                handler.characters(chars, 0, chars.length);
            }
        });

        SAXException thrown =
                assertThrows(SAXException.class,
                        () -> parse(
                                context,
                                new TextRejectingHandler(rejectedText, denial)));

        assertSame(denial, thrown);
    }

    @Test
    public void testWrappedEmbeddedDownstreamSaxDenialPropagates() throws Exception {
        String rejectedText = "blocked wrapped CHM embedded output";
        SAXException denial =
                new SAXException("simulated wrapped CHM output policy denial");
        ParseContext context = wrappedEmbeddedOutputContext(rejectedText);
        Metadata metadata = new Metadata();

        SAXException thrown =
                assertThrows(SAXException.class,
                        () -> parse(
                                context,
                                new TextRejectingHandler(rejectedText, denial),
                                metadata));

        assertSame(denial, thrown);
        assertTrue(Arrays.stream(metadata.getValues(
                        TikaCoreProperties.TIKA_META_EXCEPTION_WARNING))
                .noneMatch(value -> value.startsWith(
                        "CHM entry analysis incomplete")));
    }

    @Test
    public void testEmbeddedDownstreamUncheckedDenialPropagates() throws Exception {
        String rejectedText = "blocked unchecked CHM embedded output";
        IllegalStateException denial =
                new IllegalStateException("simulated unchecked CHM output policy denial");
        ParseContext context = embeddedOutputContext(rejectedText);
        Metadata metadata = new Metadata();

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class,
                        () -> parse(
                                context,
                                new UncheckedTextRejectingHandler(
                                        rejectedText, denial),
                                metadata));

        assertSame(denial, thrown);
        assertTrue(Arrays.stream(metadata.getValues(
                        TikaCoreProperties.TIKA_META_EXCEPTION_WARNING))
                .noneMatch(value -> value.startsWith(
                        "CHM entry analysis incomplete")));
    }

    @Test
    public void testSwallowedEmbeddedDownstreamUncheckedDenialPropagates()
            throws Exception {
        String rejectedText = "blocked swallowed CHM embedded output";
        IllegalStateException denial =
                new IllegalStateException("simulated swallowed CHM output policy denial");
        ParseContext context =
                swallowingUncheckedEmbeddedOutputContext(rejectedText);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class,
                        () -> parse(
                                context,
                                new UncheckedTextRejectingHandler(
                                        rejectedText, denial)));

        assertSame(denial, thrown);
    }

    @Test
    public void testWrappedEmbeddedDownstreamSecurityDenialPropagatesByIdentity()
            throws Exception {
        String rejectedText = "blocked wrapped CHM security output";
        SecurityException denial =
                new SecurityException("simulated CHM security output policy denial");
        ParseContext context =
                securityWrappingEmbeddedOutputContext(rejectedText);

        SecurityException thrown =
                assertThrows(SecurityException.class,
                        () -> parse(
                                context,
                                new UncheckedTextRejectingHandler(
                                        rejectedText, denial)));

        assertSame(denial, thrown);
    }

    @Test
    public void testOrdinaryEmbeddedFailureMarksAnalysisIncomplete() throws Exception {
        Metadata metadata = parseWithEmbeddedException(
                new IOException("simulated ordinary CHM embedded failure"));

        assertTrue(Arrays.stream(metadata.getValues(
                        TikaCoreProperties.TIKA_META_EXCEPTION_WARNING))
                .anyMatch(value -> value.startsWith("CHM entry analysis incomplete") &&
                        value.endsWith("IOException")));
        assertNotNull(metadata.get("ExploitClass"));
    }

    @Test
    public void testEmbeddedMetadataUsesContextLimiter() throws Exception {
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

        parse(context);

        assertTrue(limiterApplied.get(),
                "fork-created CHM embedded metadata must inherit the context limiter");
    }

    private Metadata parseWithEmbeddedException(Exception failure) throws Exception {
        ParseContext context = new ParseContext();
        AtomicBoolean pendingFailure = new AtomicBoolean(true);
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return pendingFailure.get();
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext parseContext,
                                      boolean outputHtml) throws IOException, SAXException {
                if (!pendingFailure.compareAndSet(true, false)) {
                    throw new AssertionError("test failure must be thrown exactly once");
                }
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
        return parse(context);
    }

    private static ParseContext embeddedOutputContext(String output) {
        ParseContext context = new ParseContext();
        AtomicBoolean pendingOutput = new AtomicBoolean(true);
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return pendingOutput.get();
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext parseContext,
                                      boolean outputHtml) throws SAXException {
                if (!pendingOutput.compareAndSet(true, false)) {
                    return;
                }
                char[] chars = output.toCharArray();
                handler.characters(chars, 0, chars.length);
            }
        });
        return context;
    }

    private static ParseContext wrappedEmbeddedOutputContext(String output) {
        ParseContext context = new ParseContext();
        AtomicBoolean pendingOutput = new AtomicBoolean(true);
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return pendingOutput.get();
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext parseContext,
                                      boolean outputHtml) throws IOException {
                if (!pendingOutput.compareAndSet(true, false)) {
                    return;
                }
                char[] chars = output.toCharArray();
                try {
                    handler.characters(chars, 0, chars.length);
                } catch (SAXException outputFailure) {
                    throw new IOException("wrapped CHM output failure", outputFailure);
                }
            }
        });
        return context;
    }

    private static ParseContext swallowingUncheckedEmbeddedOutputContext(
            String output) {
        ParseContext context = new ParseContext();
        AtomicBoolean pendingOutput = new AtomicBoolean(true);
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return pendingOutput.get();
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext parseContext,
                                      boolean outputHtml) {
                if (!pendingOutput.compareAndSet(true, false)) {
                    return;
                }
                char[] chars = output.toCharArray();
                try {
                    handler.characters(chars, 0, chars.length);
                } catch (SAXException | RuntimeException ignored) {
                    // Simulate an embedded parser swallowing downstream refusal.
                }
            }
        });
        return context;
    }

    private static ParseContext securityWrappingEmbeddedOutputContext(
            String output) {
        ParseContext context = new ParseContext();
        AtomicBoolean pendingOutput = new AtomicBoolean(true);
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return pendingOutput.get();
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext parseContext,
                                      boolean outputHtml) throws SAXException {
                if (!pendingOutput.compareAndSet(true, false)) {
                    return;
                }
                char[] chars = output.toCharArray();
                try {
                    handler.characters(chars, 0, chars.length);
                } catch (SecurityException outputFailure) {
                    throw new SecurityException(
                            "wrapped CHM security output failure", outputFailure);
                }
            }
        });
        return context;
    }

    private Metadata parse(ParseContext context) throws Exception {
        return parse(context, new BodyContentHandler(-1));
    }

    private Metadata parse(ParseContext context, ContentHandler handler)
            throws Exception {
        Metadata metadata = new Metadata();
        parse(context, handler, metadata);
        return metadata;
    }

    private void parse(ParseContext context, ContentHandler handler,
                       Metadata metadata) throws Exception {
        try (TikaInputStream stream = getResourceAsStream(CHM_FIXTURE)) {
            new ChmParser().parse(
                    stream, handler, metadata, context);
        }
    }

    private static final class TextRejectingHandler extends DefaultHandler {

        private final String rejectedText;
        private final SAXException denial;

        private TextRejectingHandler(String rejectedText, SAXException denial) {
            this.rejectedText = rejectedText;
            this.denial = denial;
        }

        @Override
        public void characters(char[] ch, int start, int length)
                throws SAXException {
            if (new String(ch, start, length).contains(rejectedText)) {
                throw denial;
            }
        }
    }

    private static final class UncheckedTextRejectingHandler
            extends DefaultHandler {

        private final String rejectedText;
        private final RuntimeException denial;

        private UncheckedTextRejectingHandler(
                String rejectedText, RuntimeException denial) {
            this.rejectedText = rejectedText;
            this.denial = denial;
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (new String(ch, start, length).contains(rejectedText)) {
                throw denial;
            }
        }
    }
}
