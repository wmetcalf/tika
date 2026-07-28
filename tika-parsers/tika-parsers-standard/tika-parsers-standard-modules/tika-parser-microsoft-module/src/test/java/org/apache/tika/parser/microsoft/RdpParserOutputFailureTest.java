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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;

public class RdpParserOutputFailureTest {

    private static final String RDP_CERTIFICATE = "pcb:b:QUJD\n";

    @Test
    public void testWrappedCertificateOutputDenialPropagates() throws Exception {
        String rejectedText = "blocked wrapped RDP certificate output";
        SAXException denial =
                new SAXException("simulated wrapped RDP output policy denial");
        Metadata metadata = new Metadata();

        SAXException thrown =
                assertThrows(SAXException.class,
                        () -> parse(
                                wrappedEmbeddedOutputContext(rejectedText),
                                new TextRejectingHandler(rejectedText, denial),
                                metadata));

        assertSame(denial, thrown);
        assertTrue(Arrays.stream(metadata.getValues("rdp:pcb_warning"))
                .noneMatch(value -> value.startsWith("embedded parse error")));
    }

    @Test
    public void testUncheckedCertificateOutputDenialPropagates() throws Exception {
        String rejectedText = "blocked unchecked RDP certificate output";
        IllegalStateException denial =
                new IllegalStateException("simulated unchecked RDP output policy denial");
        Metadata metadata = new Metadata();

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class,
                        () -> parse(
                                embeddedOutputContext(rejectedText),
                                new UncheckedTextRejectingHandler(
                                        rejectedText, denial),
                                metadata));

        assertSame(denial, thrown);
        assertTrue(Arrays.stream(metadata.getValues("rdp:pcb_warning"))
                .noneMatch(value -> value.startsWith("embedded parse error")));
    }

    @Test
    public void testSwallowedUncheckedCertificateOutputDenialPropagates()
            throws Exception {
        String rejectedText = "blocked swallowed RDP certificate output";
        IllegalStateException denial =
                new IllegalStateException("simulated swallowed RDP output policy denial");

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class,
                        () -> parse(
                                swallowingUncheckedEmbeddedOutputContext(
                                        rejectedText),
                                new UncheckedTextRejectingHandler(
                                        rejectedText, denial),
                                new Metadata()));

        assertSame(denial, thrown);
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

    private static ParseContext wrappedEmbeddedOutputContext(String output) {
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
                char[] chars = output.toCharArray();
                try {
                    handler.characters(chars, 0, chars.length);
                } catch (SAXException outputFailure) {
                    throw new IOException("wrapped RDP output failure", outputFailure);
                }
            }
        });
        return context;
    }

    private static ParseContext swallowingUncheckedEmbeddedOutputContext(
            String output) {
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

    private static void parse(ParseContext context, ContentHandler handler,
                              Metadata metadata)
            throws Exception {
        try (TikaInputStream stream = TikaInputStream.get(
                RDP_CERTIFICATE.getBytes(StandardCharsets.UTF_8))) {
            new RdpParser().parse(
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
