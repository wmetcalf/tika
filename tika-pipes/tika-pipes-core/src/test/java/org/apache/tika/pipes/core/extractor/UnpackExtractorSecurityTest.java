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
package org.apache.tika.pipes.core.extractor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedStreamTranslator;
import org.apache.tika.extractor.UnpackHandler;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ParseRecord;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.XHTMLContentHandler;

public class UnpackExtractorSecurityTest {

    @Test
    public void testWrappedOutputDenialPropagatesWhenOutputHtmlIsEnabled()
            throws Exception {
        assertWrappedOutputDenialPropagates(true);
    }

    @Test
    public void testWrappedOutputDenialPropagatesWhenOutputHtmlIsDisabled()
            throws Exception {
        assertWrappedOutputDenialPropagates(false);
    }

    @Test
    public void testTranslationFailureConsumesEmbeddedCountLimit()
            throws Exception {
        ParseContext context = new ParseContext();
        ParseRecord parseRecord = new ParseRecord();
        parseRecord.setMaxEmbeddedCount(1);
        context.set(ParseRecord.class, parseRecord);
        RecordingUnpackHandler unpackHandler = new RecordingUnpackHandler();
        context.set(UnpackHandler.class, unpackHandler);
        IOException translationFailure =
                new IOException("simulated embedded translation failure");
        FailingEmbeddedStreamTranslator translator =
                new FailingEmbeddedStreamTranslator(translationFailure);
        UnpackExtractor extractor = new UnpackExtractor(context);
        setEmbeddedStreamTranslator(extractor, translator);
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.EMBEDDED_ID, 1);

        IOException thrown;
        try (TikaInputStream stream =
                     TikaInputStream.get(new byte[]{0x00, 0x01})) {
            thrown = assertThrows(IOException.class,
                    () -> extractor.parseEmbedded(
                            stream, new DefaultHandler(), metadata, context,
                            false));
        }

        assertSame(translationFailure, thrown);
        assertEquals(1, parseRecord.getEmbeddedCount());
        assertArrayEquals(
                FailingEmbeddedStreamTranslator.PARTIAL_TRANSLATION,
                unpackHandler.storedBytes);

        try (TikaInputStream stream =
                     TikaInputStream.get(new byte[]{0x02, 0x03})) {
            extractor.parseEmbedded(
                    stream, new DefaultHandler(), metadata, context, false);
        }

        assertEquals(1, translator.translateCalls);
        assertEquals(1, parseRecord.getEmbeddedCount());
    }

    private static void setEmbeddedStreamTranslator(
            UnpackExtractor extractor, EmbeddedStreamTranslator translator)
            throws ReflectiveOperationException {
        Field field =
                UnpackExtractor.class.getDeclaredField(
                        "embeddedStreamTranslator");
        field.setAccessible(true);
        field.set(extractor, translator);
    }

    private void assertWrappedOutputDenialPropagates(boolean outputHtml)
            throws Exception {
        ParseContext context = new ParseContext();
        context.set(Parser.class, new WrappingParser());
        RecordingUnpackHandler unpackHandler = new RecordingUnpackHandler();
        context.set(UnpackHandler.class, unpackHandler);
        UnpackExtractor extractor = new UnpackExtractor(context);
        SAXException denial =
                new SAXException("simulated wrapped UNPACK output denial");
        FailStopHandler handler = new FailStopHandler(denial);
        byte[] input = new byte[]{0x00, 0x01, 0x02, 0x03};
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.EMBEDDED_ID, 1);

        SAXException thrown;
        try (TikaInputStream stream = TikaInputStream.get(input)) {
            thrown = assertThrows(SAXException.class,
                    () -> extractor.parseEmbedded(
                            stream, handler, metadata, context,
                            outputHtml));
        }

        assertSame(denial, thrown);
        assertEquals(0, handler.callbacksAfterDenial);
        assertArrayEquals(input, unpackHandler.storedBytes);
    }

    private static final class WrappingParser implements Parser {

        private static final long serialVersionUID = 1L;

        @Override
        public Set<MediaType> getSupportedTypes(ParseContext context) {
            return Set.of(MediaType.OCTET_STREAM);
        }

        @Override
        public void parse(
                TikaInputStream stream, ContentHandler handler,
                Metadata metadata, ParseContext context)
                throws SAXException, TikaException {
            XHTMLContentHandler xhtml =
                    new XHTMLContentHandler(handler, metadata, context);
            xhtml.startDocument();
            xhtml.startElement("p", new AttributesImpl());
            char[] chars = "blocked output".toCharArray();
            try {
                xhtml.characters(chars, 0, chars.length);
            } catch (SAXException e) {
                throw new TikaException("wrapped downstream failure", e);
            }
        }
    }

    private static final class FailStopHandler extends DefaultHandler {

        private final SAXException denial;
        private boolean denied;
        private int callbacksAfterDenial;

        private FailStopHandler(SAXException denial) {
            this.denial = denial;
        }

        @Override
        public void characters(char[] ch, int start, int length)
                throws SAXException {
            rejectAfterDenial();
            if (new String(ch, start, length).contains("blocked output")) {
                denied = true;
                throw denial;
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName)
                throws SAXException {
            rejectAfterDenial();
        }

        private void rejectAfterDenial() throws SAXException {
            if (denied) {
                callbacksAfterDenial++;
                throw new SAXException(
                        "SAX callback delivered after policy denial");
            }
        }
    }

    private static final class RecordingUnpackHandler
            implements UnpackHandler {

        private byte[] storedBytes;

        @Override
        public void add(int id, Metadata metadata, InputStream inputStream)
                throws IOException {
            storedBytes = inputStream.readAllBytes();
        }

        @Override
        public List<Integer> getIds() {
            return List.of();
        }

        @Override
        public void close() {
        }
    }

    private static final class FailingEmbeddedStreamTranslator
            implements EmbeddedStreamTranslator {

        private static final byte[] PARTIAL_TRANSLATION =
                new byte[]{0x55};

        private final IOException failure;
        private int translateCalls;

        private FailingEmbeddedStreamTranslator(IOException failure) {
            this.failure = failure;
        }

        @Override
        public boolean shouldTranslate(
                TikaInputStream inputStream, Metadata metadata) {
            return true;
        }

        @Override
        public void translate(
                TikaInputStream inputStream, Metadata metadata,
                OutputStream outputStream) throws IOException {
            translateCalls++;
            outputStream.write(PARTIAL_TRANSLATION);
            throw failure;
        }
    }
}
