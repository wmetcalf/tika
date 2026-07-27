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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

import org.junit.jupiter.api.Test;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.TaggedContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;

class RTFRuntimeCleanupTest {

    @Test
    void ordinaryParserRuntimeRemainsPrimaryAndBalancesOutput()
            throws Exception {
        RuntimeException parserFailure =
                new IllegalStateException("ordinary parser runtime");
        StrictRecordingHandler output = new StrictRecordingHandler();
        RTFParser parser = new RuntimeFailingRTFParser(parserFailure);
        Throwable thrown = null;

        try (TikaInputStream stream =
                     TikaInputStream.get(
                             "{\\rtf1 test}".getBytes(StandardCharsets.US_ASCII))) {
            try {
                parser.parse(
                        stream, output, new Metadata(), new ParseContext());
            } catch (Throwable failure) {
                thrown = failure;
            }
        }

        Throwable observed = thrown;
        assertAll(
                () -> assertSame(
                        parserFailure,
                        observed,
                        "cleanup masked the ordinary parser RuntimeException"),
                () -> assertEquals(
                        0,
                        output.openElements.size(),
                        "ordinary parser RuntimeException left XHTML elements open: "
                                + output.openElements),
                () -> assertTrue(
                        output.documentEnded,
                        "ordinary parser RuntimeException skipped endDocument"));
    }

    @Test
    void downstreamErrorFailsStopByIdentity() throws Exception {
        AssertionError denial =
                new AssertionError("downstream output error denial");
        FailStopErrorHandler handler =
                new FailStopErrorHandler("blocked RTF text", denial);

        AssertionError thrown =
                assertThrows(AssertionError.class, () -> {
                    try (TikaInputStream stream =
                                 TikaInputStream.get(
                                         "{\\rtf1 blocked RTF text}"
                                                 .getBytes(StandardCharsets.US_ASCII))) {
                        new RTFParser().parse(
                                stream, handler, new Metadata(),
                                new ParseContext());
                    }
                });

        assertSame(denial, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    void swallowedDownstreamRuntimeFailsStopByIdentity() throws Exception {
        RuntimeException denial =
                new IllegalStateException("swallowed downstream runtime denial");
        FailStopRuntimeHandler handler =
                new FailStopRuntimeHandler("blocked swallowed output", denial);
        RTFParser parser = new SwallowingOutputFailureRTFParser();

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> parse(
                        parser, handler, "blocked swallowed output"));

        assertSame(denial, thrown);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    void ioWrappedDownstreamRuntimeFailsStopByIdentity() throws Exception {
        assertWrappedDownstreamRuntimeFailsStop(Wrapper.IO);
    }

    @Test
    void saxWrappedDownstreamRuntimeFailsStopByIdentity() throws Exception {
        assertWrappedDownstreamRuntimeFailsStop(Wrapper.SAX);
    }

    @Test
    void tikaWrappedDownstreamRuntimeFailsStopByIdentity() throws Exception {
        assertWrappedDownstreamRuntimeFailsStop(Wrapper.TIKA);
    }

    @Test
    void parserErrorRemainsPrimaryAndSkipsCleanup() throws Exception {
        AssertionError parserFailure =
                new AssertionError("parser-origin error");
        StrictRecordingHandler output = new StrictRecordingHandler();
        RTFParser parser = new ErrorFailingRTFParser(parserFailure);

        AssertionError thrown =
                assertThrows(AssertionError.class, () -> parse(
                        parser, output, "parser error"));

        assertSame(parserFailure, thrown);
        assertFalse(output.openElements.isEmpty(),
                "parser-origin Error must not trigger balancing callbacks");
        assertFalse(output.documentEnded,
                "parser-origin Error must not trigger endDocument");
    }

    @Test
    void suppressedCycleWrappedSaxDenialFailsStopByIdentity()
            throws Exception {
        SAXException denial =
                new SAXException("suppressed-cycle SAX output denial");
        FailStopSaxHandler handler =
                new FailStopSaxHandler("blocked SAX output", denial);
        RTFParser parser = new SuppressedCycleWrappingRTFParser();

        SAXException thrown =
                assertThrows(SAXException.class, () -> parse(
                        parser, handler, "blocked SAX output"));

        assertSame(denial, thrown);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    void runtimeWrappedSaxDenialFailsStopByIdentity() throws Exception {
        assertUncheckedWrappedSaxDenialFailsStop(
                UncheckedSaxWrapper.RUNTIME);
    }

    @Test
    void errorSuppressedSaxDenialFailsStopByIdentity() throws Exception {
        assertUncheckedWrappedSaxDenialFailsStop(
                UncheckedSaxWrapper.ERROR_SUPPRESSED);
    }

    @Test
    void untrackedFatalCleanupErrorSupersedesRecoverablePrimary() {
        RuntimeException primary =
                new IllegalStateException("recoverable parser failure");
        AssertionError fatalCleanup =
                new AssertionError("untracked fatal cleanup failure");
        TaggedContentHandler taggedOutput =
                new TaggedContentHandler(new DefaultHandler());

        AssertionError thrown =
                assertThrows(
                        AssertionError.class,
                        () -> RTFParser.handleCleanupFailure(
                                taggedOutput, primary, fatalCleanup));

        assertSame(fatalCleanup, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(primary, thrown.getSuppressed()[0]);
    }

    private void assertWrappedDownstreamRuntimeFailsStop(Wrapper wrapper)
            throws Exception {
        RuntimeException denial =
                new IllegalStateException(
                        wrapper + " wrapped downstream runtime denial");
        FailStopRuntimeHandler handler =
                new FailStopRuntimeHandler("blocked wrapped output", denial);
        RTFParser parser = new WrappingOutputFailureRTFParser(wrapper);

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> parse(
                        parser, handler, "blocked wrapped output"));

        assertSame(denial, thrown);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    private void assertUncheckedWrappedSaxDenialFailsStop(
            UncheckedSaxWrapper wrapper) throws Exception {
        SAXException denial =
                new SAXException(wrapper + " SAX output denial");
        FailStopSaxHandler handler =
                new FailStopSaxHandler("blocked unchecked SAX output", denial);
        RTFParser parser =
                new UncheckedWrappingSaxFailureRTFParser(wrapper);

        SAXException thrown =
                assertThrows(SAXException.class, () -> parse(
                        parser, handler, "blocked unchecked SAX output"));

        assertSame(denial, thrown);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    private static void parse(
            RTFParser parser, ContentHandler handler, String text)
            throws Exception {
        try (TikaInputStream stream =
                     TikaInputStream.get(
                             ("{\\rtf1 " + text + "}")
                                     .getBytes(StandardCharsets.US_ASCII))) {
            parser.parse(
                    stream, handler, new Metadata(), new ParseContext());
        }
    }

    private static final class RuntimeFailingRTFParser extends RTFParser {

        private final RuntimeException failure;

        private RuntimeFailingRTFParser(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public void parseInline(
                InputStream stream,
                ContentHandler handler,
                Metadata metadata,
                ParseContext context)
                throws TikaException, IOException, SAXException {
            handler.startElement(
                    XHTMLContentHandler.XHTML,
                    "p",
                    "p",
                    new AttributesImpl());
            throw failure;
        }
    }

    private static final class ErrorFailingRTFParser extends RTFParser {

        private final AssertionError failure;

        private ErrorFailingRTFParser(AssertionError failure) {
            this.failure = failure;
        }

        @Override
        public void parseInline(
                InputStream stream,
                ContentHandler handler,
                Metadata metadata,
                ParseContext context)
                throws SAXException {
            handler.startElement(
                    XHTMLContentHandler.XHTML,
                    "p",
                    "p",
                    new AttributesImpl());
            throw failure;
        }
    }

    private static final class SwallowingOutputFailureRTFParser
            extends RTFParser {

        @Override
        public void parseInline(
                InputStream stream,
                ContentHandler handler,
                Metadata metadata,
                ParseContext context)
                throws SAXException {
            try {
                handler.characters(
                        "blocked swallowed output".toCharArray(),
                        0,
                        "blocked swallowed output".length());
            } catch (RuntimeException swallowed) {
                // Models an inner best-effort RTF/embedded path that catches
                // an unchecked downstream output refusal and returns.
            }
        }
    }

    private static final class WrappingOutputFailureRTFParser
            extends RTFParser {

        private final Wrapper wrapper;

        private WrappingOutputFailureRTFParser(Wrapper wrapper) {
            this.wrapper = wrapper;
        }

        @Override
        public void parseInline(
                InputStream stream,
                ContentHandler handler,
                Metadata metadata,
                ParseContext context)
                throws TikaException, IOException, SAXException {
            try {
                handler.characters(
                        "blocked wrapped output".toCharArray(),
                        0,
                        "blocked wrapped output".length());
            } catch (RuntimeException failure) {
                switch (wrapper) {
                    case IO:
                        throw new IOException("wrapped output refusal", failure);
                    case SAX:
                        throw new SAXException("wrapped output refusal", failure);
                    case TIKA:
                        throw new TikaException("wrapped output refusal", failure);
                }
            }
        }
    }

    private static final class UncheckedWrappingSaxFailureRTFParser
            extends RTFParser {

        private final UncheckedSaxWrapper wrapper;

        private UncheckedWrappingSaxFailureRTFParser(
                UncheckedSaxWrapper wrapper) {
            this.wrapper = wrapper;
        }

        @Override
        public void parseInline(
                InputStream stream,
                ContentHandler handler,
                Metadata metadata,
                ParseContext context)
                throws SAXException {
            try {
                handler.characters(
                        "blocked unchecked SAX output".toCharArray(),
                        0,
                        "blocked unchecked SAX output".length());
            } catch (SAXException outputFailure) {
                if (wrapper == UncheckedSaxWrapper.RUNTIME) {
                    throw new IllegalStateException(
                            "runtime-wrapped SAX output denial",
                            outputFailure);
                }
                AssertionError error =
                        new AssertionError(
                                "error-suppressed SAX output denial");
                error.addSuppressed(outputFailure);
                throw error;
            }
        }
    }

    private static final class SuppressedCycleWrappingRTFParser
            extends RTFParser {

        @Override
        public void parseInline(
                InputStream stream,
                ContentHandler handler,
                Metadata metadata,
                ParseContext context)
                throws TikaException, SAXException {
            try {
                handler.characters(
                        "blocked SAX output".toCharArray(),
                        0,
                        "blocked SAX output".length());
            } catch (SAXException outputFailure) {
                TikaException wrapper =
                        new TikaException("suppressed output refusal");
                RuntimeException cycle =
                        new RuntimeException("suppressed cycle");
                wrapper.addSuppressed(cycle);
                cycle.addSuppressed(wrapper);
                cycle.addSuppressed(outputFailure);
                throw wrapper;
            }
        }
    }

    private enum Wrapper {
        IO,
        SAX,
        TIKA
    }

    private enum UncheckedSaxWrapper {
        RUNTIME,
        ERROR_SUPPRESSED
    }

    private static final class StrictRecordingHandler extends DefaultHandler {

        private final Deque<String> openElements = new ArrayDeque<>();
        private boolean documentEnded;

        @Override
        public void startElement(
                String uri, String localName, String qName, Attributes attributes) {
            openElements.push(elementName(localName, qName));
        }

        @Override
        public void endElement(String uri, String localName, String qName)
                throws SAXException {
            String closing = elementName(localName, qName);
            if (openElements.isEmpty()
                    || !closing.equals(openElements.peek())) {
                throw new SAXException(
                        "strict output mismatch: closing "
                                + closing + " while open=" + openElements);
            }
            openElements.pop();
        }

        @Override
        public void endDocument() throws SAXException {
            if (!openElements.isEmpty()) {
                throw new SAXException(
                        "endDocument with open elements: " + openElements);
            }
            documentEnded = true;
        }

        private static String elementName(String localName, String qName) {
            return localName == null || localName.isEmpty()
                    ? qName : localName;
        }
    }

    private static final class FailStopErrorHandler extends DefaultHandler {

        private final String rejectedText;
        private final AssertionError denial;
        private boolean denied;
        private int callbacksAfterDenial;

        private FailStopErrorHandler(
                String rejectedText, AssertionError denial) {
            this.rejectedText = rejectedText;
            this.denial = denial;
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            rejectAfterDenial();
            if (new String(ch, start, length).contains(rejectedText)) {
                denied = true;
                throw denial;
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
                throw new AssertionError("callback delivered after error denial");
            }
        }
    }

    private static final class FailStopRuntimeHandler
            extends DefaultHandler {

        private final String rejectedText;
        private final RuntimeException denial;
        private boolean denied;
        private int callbacksAfterDenial;

        private FailStopRuntimeHandler(
                String rejectedText, RuntimeException denial) {
            this.rejectedText = rejectedText;
            this.denial = denial;
        }

        private void noteCallback() {
            if (denied) {
                callbacksAfterDenial++;
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            noteCallback();
        }

        @Override
        public void endPrefixMapping(String prefix) {
            noteCallback();
        }

        @Override
        public void endDocument() {
            noteCallback();
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            noteCallback();
            if (!denied
                    && new String(ch, start, length).contains(rejectedText)) {
                denied = true;
                throw denial;
            }
        }
    }

    private static final class FailStopSaxHandler extends DefaultHandler {

        private final String rejectedText;
        private final SAXException denial;
        private boolean denied;
        private int callbacksAfterDenial;

        private FailStopSaxHandler(
                String rejectedText, SAXException denial) {
            this.rejectedText = rejectedText;
            this.denial = denial;
        }

        private void noteCallback() {
            if (denied) {
                callbacksAfterDenial++;
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            noteCallback();
        }

        @Override
        public void endPrefixMapping(String prefix) {
            noteCallback();
        }

        @Override
        public void endDocument() {
            noteCallback();
        }

        @Override
        public void characters(char[] ch, int start, int length)
                throws SAXException {
            noteCallback();
            if (!denied
                    && new String(ch, start, length).contains(rejectedText)) {
                denied = true;
                throw denial;
            }
        }
    }
}
