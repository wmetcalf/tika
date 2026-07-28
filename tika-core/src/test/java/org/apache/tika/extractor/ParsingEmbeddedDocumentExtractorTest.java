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
package org.apache.tika.extractor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.exception.CorruptedFileException;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.XHTMLContentHandler;

public class ParsingEmbeddedDocumentExtractorTest {

    @Test
    public void testDownstreamSaxDenialIsNotReplacedByCleanup()
            throws Exception {
        ParseContext context = new ParseContext();
        context.set(Parser.class, new AbortingParser());
        ParsingEmbeddedDocumentExtractor extractor =
                new ParsingEmbeddedDocumentExtractor(context);
        SAXException denial =
                new SAXException("simulated embedded output policy denial");
        FailStopHandler handler = new FailStopHandler("blocked output", denial);

        SAXException thrown;
        try (TikaInputStream stream = TikaInputStream.get(new byte[0])) {
            thrown = assertThrows(SAXException.class,
                    () -> extractor.parseEmbedded(
                            stream, handler, new Metadata(), context, true));
        }

        assertSame(denial, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    public void testSwallowedDownstreamSaxDenialPropagates()
            throws Exception {
        assertWrappedDownstreamDenialPropagates(FailureWrapper.SWALLOWED);
    }

    @Test
    public void testTikaExceptionWrappedDownstreamSaxDenialPropagates()
            throws Exception {
        assertWrappedDownstreamDenialPropagates(FailureWrapper.TIKA);
    }

    @Test
    public void testSuppressedDownstreamSaxDenialPropagates()
            throws Exception {
        assertWrappedDownstreamDenialPropagates(
                FailureWrapper.TIKA_SUPPRESSED);
    }

    @Test
    public void testRuntimeExceptionWrappedDownstreamSaxDenialPropagates()
            throws Exception {
        assertWrappedDownstreamDenialPropagates(FailureWrapper.RUNTIME);
    }

    @Test
    public void testEncryptedDocumentExceptionWrappedDownstreamSaxDenialPropagates()
            throws Exception {
        assertWrappedDownstreamDenialPropagates(FailureWrapper.ENCRYPTED);
    }

    @Test
    public void testCorruptedFileExceptionWrappedDownstreamSaxDenialPropagates()
            throws Exception {
        assertWrappedDownstreamDenialPropagates(FailureWrapper.CORRUPTED);
    }

    @Test
    public void testSecurityExceptionWrappedDownstreamSaxDenialPropagates()
            throws Exception {
        assertWrappedDownstreamDenialPropagates(FailureWrapper.SECURITY);
    }

    @Test
    public void testErrorWrappedDownstreamSaxDenialPropagates()
            throws Exception {
        assertWrappedDownstreamDenialPropagates(FailureWrapper.ERROR);
    }

    @Test
    public void testUnrelatedErrorAfterSwallowedDownstreamSaxDenialRemainsAuthoritative()
            throws Exception {
        AssertionError parserFailure =
                new AssertionError("simulated unrelated parser fatal failure");
        ParseContext context = new ParseContext();
        context.set(Parser.class, new SaxThenErrorParser(
                SaxThenErrorMode.UNRELATED, parserFailure));
        ParsingEmbeddedDocumentExtractor extractor =
                new ParsingEmbeddedDocumentExtractor(context);
        SAXException denial =
                new SAXException("simulated swallowed output policy denial");
        FailStopHandler handler = new FailStopHandler("blocked output", denial);

        AssertionError thrown;
        try (TikaInputStream stream = TikaInputStream.get(new byte[0])) {
            thrown = assertThrows(AssertionError.class,
                    () -> extractor.parseEmbedded(
                            stream, handler, new Metadata(), context, true));
        }

        assertSame(parserFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    public void testErrorCauseWithExactRawDownstreamSaxDenialPropagates()
            throws Exception {
        assertRawDownstreamDenialInErrorGraphPropagates(
                SaxThenErrorMode.RAW_CAUSE);
    }

    @Test
    public void testErrorSuppressedWithExactRawDownstreamSaxDenialPropagates()
            throws Exception {
        assertRawDownstreamDenialInErrorGraphPropagates(
                SaxThenErrorMode.RAW_SUPPRESSED);
    }

    @Test
    public void testExactRecordedSaxWinsOverEarlierTaggedBranchInCyclicErrorGraph()
            throws Exception {
        ParseContext context = new ParseContext();
        context.set(Parser.class, new CompetingTaggedSaxBranchesParser());
        ParsingEmbeddedDocumentExtractor extractor =
                new ParsingEmbeddedDocumentExtractor(context);
        SAXException firstDenial =
                new SAXException("simulated first downstream output denial");
        FailStopHandler handler =
                new FailStopHandler("blocked output", firstDenial);

        SAXException thrown;
        try (TikaInputStream stream = TikaInputStream.get(new byte[0])) {
            thrown = assertThrows(SAXException.class,
                    () -> extractor.parseEmbedded(
                            stream, handler, new Metadata(), context, true));
        }

        assertSame(firstDenial, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(1, handler.callbacksAfterDenial);
    }

    @Test
    public void testWrappedOutputDenialPropagatesWhenOutputHtmlIsFalse()
            throws Exception {
        ParseContext context = new ParseContext();
        context.set(Parser.class, new AbortingParser(FailureWrapper.TIKA));
        ParsingEmbeddedDocumentExtractor extractor =
                new ParsingEmbeddedDocumentExtractor(context);
        SAXException denial =
                new SAXException("simulated wrapped embedded output denial");
        FailStopHandler handler = new FailStopHandler("blocked output", denial);

        SAXException thrown;
        try (TikaInputStream stream = TikaInputStream.get(new byte[0])) {
            thrown = assertThrows(SAXException.class,
                    () -> extractor.parseEmbedded(
                            stream, handler, new Metadata(), context, false));
        }

        assertSame(denial, thrown);
    }

    @Test
    public void testIOExceptionWrappedDownstreamSaxDenialPropagates()
            throws Exception {
        assertWrappedDownstreamDenialPropagates(FailureWrapper.IO);
    }

    @Test
    public void testDownstreamSecurityExceptionIsNotReplacedByCleanup()
            throws Exception {
        ParseContext context = new ParseContext();
        context.set(Parser.class, new AbortingParser(FailureWrapper.NONE));
        ParsingEmbeddedDocumentExtractor extractor =
                new ParsingEmbeddedDocumentExtractor(context);
        SecurityException denial =
                new SecurityException("simulated embedded security policy denial");
        FailStopSecurityHandler handler =
                new FailStopSecurityHandler("blocked output", denial);

        SecurityException thrown;
        try (TikaInputStream stream = TikaInputStream.get(new byte[0])) {
            thrown = assertThrows(SecurityException.class,
                    () -> extractor.parseEmbedded(
                            stream, handler, new Metadata(), context, true));
        }

        assertSame(denial, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    public void testDownstreamRuntimeExceptionIsNotReplacedByCleanup()
            throws Exception {
        assertUncheckedRuntimeDenialPropagates(
                UncheckedFailureMode.DIRECT);
    }

    @Test
    public void testCauseWrappedDownstreamRuntimeExceptionPropagates()
            throws Exception {
        assertUncheckedRuntimeDenialPropagates(
                UncheckedFailureMode.WRAPPED_CAUSE);
    }

    @Test
    public void testSuppressedDownstreamRuntimeExceptionPropagates()
            throws Exception {
        assertUncheckedRuntimeDenialPropagates(
                UncheckedFailureMode.WRAPPED_SUPPRESSED);
    }

    @Test
    public void testSwallowedDownstreamRuntimeExceptionPropagates()
            throws Exception {
        assertUncheckedRuntimeDenialPropagates(
                UncheckedFailureMode.SWALLOWED);
    }

    @Test
    public void testDownstreamErrorIsNotReplacedByCleanup()
            throws Exception {
        assertUncheckedErrorDenialPropagates(
                UncheckedFailureMode.DIRECT);
    }

    @Test
    public void testCauseWrappedDownstreamErrorPropagates()
            throws Exception {
        assertUncheckedErrorDenialPropagates(
                UncheckedFailureMode.WRAPPED_CAUSE);
    }

    @Test
    public void testSuppressedDownstreamErrorPropagates()
            throws Exception {
        assertUncheckedErrorDenialPropagates(
                UncheckedFailureMode.WRAPPED_SUPPRESSED);
    }

    @Test
    public void testSwallowedDownstreamErrorPropagates()
            throws Exception {
        assertUncheckedErrorDenialPropagates(
                UncheckedFailureMode.SWALLOWED);
    }

    @Test
    public void testParserRuntimeBalancesOwnedMarkupBeforeRethrow()
            throws Exception {
        RuntimeException parserFailure =
                new RuntimeException("simulated parser runtime failure");
        ParseContext context = new ParseContext();
        context.set(Parser.class, new RuntimeFailureParser(parserFailure));
        ParsingEmbeddedDocumentExtractor extractor =
                new ParsingEmbeddedDocumentExtractor(context);
        EndElementRecordingHandler handler =
                new EndElementRecordingHandler();

        RuntimeException thrown;
        try (TikaInputStream stream = TikaInputStream.get(new byte[0])) {
            thrown = assertThrows(RuntimeException.class,
                    () -> extractor.parseEmbedded(
                            stream, handler, new Metadata(), context, true));
        }

        assertSame(parserFailure, thrown);
        assertEquals(List.of("p", "div"), handler.endedElements);
    }

    @Test
    public void testParserErrorSkipsCleanupAndRethrowsByIdentity()
            throws Exception {
        AssertionError parserFailure =
                new AssertionError("simulated parser fatal failure");
        ParseContext context = new ParseContext();
        context.set(Parser.class, new ErrorFailureParser(parserFailure));
        ParsingEmbeddedDocumentExtractor extractor =
                new ParsingEmbeddedDocumentExtractor(context);
        EndElementRecordingHandler handler =
                new EndElementRecordingHandler();

        AssertionError thrown;
        try (TikaInputStream stream = TikaInputStream.get(new byte[0])) {
            thrown = assertThrows(AssertionError.class,
                    () -> extractor.parseEmbedded(
                            stream, handler, new Metadata(), context, true));
        }

        assertSame(parserFailure, thrown);
        assertEquals(List.of(), handler.endedElements);
    }

    @Test
    public void testCleanupDenialSupersedesParserFailureAndStopsCallbacks()
            throws Exception {
        RuntimeException parserFailure =
                new RuntimeException("simulated parser runtime failure");
        SAXException cleanupDenial =
                new SAXException("simulated cleanup output denial");
        ParseContext context = new ParseContext();
        context.set(Parser.class, new RuntimeFailureParser(parserFailure));
        ParsingEmbeddedDocumentExtractor extractor =
                new ParsingEmbeddedDocumentExtractor(context);
        CleanupDenyingHandler handler =
                new CleanupDenyingHandler(cleanupDenial);

        SAXException thrown;
        try (TikaInputStream stream = TikaInputStream.get(new byte[0])) {
            thrown = assertThrows(SAXException.class,
                    () -> extractor.parseEmbedded(
                            stream, handler, new Metadata(), context, true));
        }

        assertSame(cleanupDenial, thrown);
        assertSame(parserFailure, thrown.getSuppressed()[0]);
        assertEquals(0, handler.getCallbacksAfterDenial());
    }

    @Test
    public void testRuntimeCleanupDenialSupersedesParserFailureWithSuppression()
            throws Exception {
        RuntimeException parserFailure =
                new RuntimeException("simulated parser runtime failure");
        RuntimeException cleanupDenial =
                new RuntimeException("simulated runtime cleanup output denial");
        ParseContext context = new ParseContext();
        context.set(Parser.class, new RuntimeFailureParser(parserFailure));
        ParsingEmbeddedDocumentExtractor extractor =
                new ParsingEmbeddedDocumentExtractor(context);
        RuntimeCleanupDenyingHandler handler =
                new RuntimeCleanupDenyingHandler(cleanupDenial);

        RuntimeException thrown;
        try (TikaInputStream stream = TikaInputStream.get(new byte[0])) {
            thrown = assertThrows(RuntimeException.class,
                    () -> extractor.parseEmbedded(
                            stream, handler, new Metadata(), context, true));
        }

        assertSame(cleanupDenial, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(parserFailure, thrown.getSuppressed()[0]);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    public void testErrorCleanupDenialSupersedesParserFailureWithSuppression()
            throws Exception {
        RuntimeException parserFailure =
                new RuntimeException("simulated parser runtime failure");
        AssertionError cleanupDenial =
                new AssertionError("simulated fatal cleanup output denial");
        ParseContext context = new ParseContext();
        context.set(Parser.class, new RuntimeFailureParser(parserFailure));
        ParsingEmbeddedDocumentExtractor extractor =
                new ParsingEmbeddedDocumentExtractor(context);
        ErrorCleanupDenyingHandler handler =
                new ErrorCleanupDenyingHandler(cleanupDenial);

        AssertionError thrown;
        try (TikaInputStream stream = TikaInputStream.get(new byte[0])) {
            thrown = assertThrows(AssertionError.class,
                    () -> extractor.parseEmbedded(
                            stream, handler, new Metadata(), context, true));
        }

        assertSame(cleanupDenial, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(parserFailure, thrown.getSuppressed()[0]);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    public void testCyclicParserCauseChainDoesNotHang() {
        ParseContext context = new ParseContext();
        context.set(Parser.class, new CyclicFailureParser());
        ParsingEmbeddedDocumentExtractor extractor =
                new ParsingEmbeddedDocumentExtractor(context);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            try (TikaInputStream stream = TikaInputStream.get(new byte[0])) {
                extractor.parseEmbedded(
                        stream, new DefaultHandler(), new Metadata(),
                        context, true);
            }
        });
    }

    private void assertWrappedDownstreamDenialPropagates(
            FailureWrapper wrapper) throws Exception {
        ParseContext context = new ParseContext();
        context.set(Parser.class, new AbortingParser(wrapper));
        ParsingEmbeddedDocumentExtractor extractor =
                new ParsingEmbeddedDocumentExtractor(context);
        SAXException denial =
                new SAXException("simulated wrapped embedded output denial");
        FailStopHandler handler = new FailStopHandler("blocked output", denial);

        SAXException thrown;
        try (TikaInputStream stream = TikaInputStream.get(new byte[0])) {
            thrown = assertThrows(SAXException.class,
                    () -> extractor.parseEmbedded(
                            stream, handler, new Metadata(), context, true));
        }

        assertSame(denial, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    private void assertUncheckedRuntimeDenialPropagates(
            UncheckedFailureMode mode) throws Exception {
        ParseContext context = new ParseContext();
        context.set(Parser.class, new UncheckedAbortingParser(mode));
        ParsingEmbeddedDocumentExtractor extractor =
                new ParsingEmbeddedDocumentExtractor(context);
        RuntimeException denial =
                new RuntimeException("simulated unchecked output denial");
        FailStopRuntimeHandler handler =
                new FailStopRuntimeHandler("blocked output", denial);

        RuntimeException thrown;
        try (TikaInputStream stream = TikaInputStream.get(new byte[0])) {
            thrown = assertThrows(RuntimeException.class,
                    () -> extractor.parseEmbedded(
                            stream, handler, new Metadata(), context, true));
        }

        assertSame(denial, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    private void assertUncheckedErrorDenialPropagates(
            UncheckedFailureMode mode) throws Exception {
        ParseContext context = new ParseContext();
        context.set(Parser.class, new ErrorAbortingParser(mode));
        ParsingEmbeddedDocumentExtractor extractor =
                new ParsingEmbeddedDocumentExtractor(context);
        AssertionError denial =
                new AssertionError("simulated fatal output denial");
        FailStopErrorHandler handler =
                new FailStopErrorHandler("blocked output", denial);

        AssertionError thrown;
        try (TikaInputStream stream = TikaInputStream.get(new byte[0])) {
            thrown = assertThrows(AssertionError.class,
                    () -> extractor.parseEmbedded(
                            stream, handler, new Metadata(), context, true));
        }

        assertSame(denial, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    private void assertRawDownstreamDenialInErrorGraphPropagates(
            SaxThenErrorMode mode) throws Exception {
        ParseContext context = new ParseContext();
        context.set(Parser.class, new SaxThenErrorParser(mode, null));
        ParsingEmbeddedDocumentExtractor extractor =
                new ParsingEmbeddedDocumentExtractor(context);
        SAXException denial =
                new SAXException("simulated raw downstream output denial");
        FailStopHandler handler = new FailStopHandler("blocked output", denial);

        SAXException thrown;
        try (TikaInputStream stream = TikaInputStream.get(new byte[0])) {
            thrown = assertThrows(SAXException.class,
                    () -> extractor.parseEmbedded(
                            stream, handler, new Metadata(), context, true));
        }

        assertSame(denial, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    private static final class AbortingParser implements Parser {

        private static final long serialVersionUID = 1L;
        private final FailureWrapper wrapper;

        private AbortingParser() {
            this(FailureWrapper.NONE);
        }

        private AbortingParser(FailureWrapper wrapper) {
            this.wrapper = wrapper;
        }

        @Override
        public Set<MediaType> getSupportedTypes(ParseContext context) {
            return Set.of(MediaType.OCTET_STREAM);
        }

        @Override
        public void parse(
                TikaInputStream stream, ContentHandler handler,
                Metadata metadata, ParseContext context)
                throws IOException, SAXException, TikaException {
            XHTMLContentHandler xhtml =
                    new XHTMLContentHandler(handler, metadata, context);
            xhtml.startDocument();
            AttributesImpl attributes = new AttributesImpl();
            xhtml.startElement("p", attributes);
            char[] chars = "blocked output".toCharArray();
            try {
                xhtml.characters(chars, 0, chars.length);
            } catch (SAXException e) {
                if (wrapper == FailureWrapper.TIKA) {
                    throw new TikaException("wrapped downstream failure", e);
                }
                if (wrapper == FailureWrapper.TIKA_SUPPRESSED) {
                    TikaException failure =
                            new TikaException("suppressed downstream failure");
                    failure.addSuppressed(e);
                    throw failure;
                }
                if (wrapper == FailureWrapper.IO) {
                    throw new IOException("wrapped downstream failure", e);
                }
                if (wrapper == FailureWrapper.RUNTIME) {
                    throw new RuntimeException("wrapped downstream failure", e);
                }
                if (wrapper == FailureWrapper.ENCRYPTED) {
                    throw new EncryptedDocumentException(
                            "wrapped downstream failure", e);
                }
                if (wrapper == FailureWrapper.CORRUPTED) {
                    throw new CorruptedFileException(
                            "wrapped downstream failure", e);
                }
                if (wrapper == FailureWrapper.SECURITY) {
                    throw new SecurityException("wrapped downstream failure", e);
                }
                if (wrapper == FailureWrapper.ERROR) {
                    throw new AssertionError("wrapped downstream failure", e);
                }
                if (wrapper == FailureWrapper.SWALLOWED) {
                    return;
                }
                throw e;
            }
        }
    }

    private enum FailureWrapper {
        NONE,
        TIKA,
        TIKA_SUPPRESSED,
        IO,
        RUNTIME,
        ENCRYPTED,
        CORRUPTED,
        SECURITY,
        ERROR,
        SWALLOWED
    }

    private static final class SaxThenErrorParser implements Parser {

        private static final long serialVersionUID = 1L;
        private final SaxThenErrorMode mode;
        private final Error unrelatedFailure;

        private SaxThenErrorParser(
                SaxThenErrorMode mode, Error unrelatedFailure) {
            this.mode = mode;
            this.unrelatedFailure = unrelatedFailure;
        }

        @Override
        public Set<MediaType> getSupportedTypes(ParseContext context) {
            return Set.of(MediaType.OCTET_STREAM);
        }

        @Override
        public void parse(
                TikaInputStream stream, ContentHandler handler,
                Metadata metadata, ParseContext context)
                throws SAXException {
            XHTMLContentHandler xhtml =
                    new XHTMLContentHandler(handler, metadata, context);
            xhtml.startDocument();
            xhtml.startElement("p", new AttributesImpl());
            char[] chars = "blocked output".toCharArray();
            try {
                xhtml.characters(chars, 0, chars.length);
            } catch (SAXException taggedFailure) {
                SAXException rawFailure = taggedFailure;
                while (rawFailure.getCause() instanceof SAXException cause) {
                    rawFailure = cause;
                }
                if (mode == SaxThenErrorMode.UNRELATED) {
                    throw unrelatedFailure;
                }
                if (mode == SaxThenErrorMode.RAW_CAUSE) {
                    throw new AssertionError(
                            "fatal parser failure with raw output cause",
                            rawFailure);
                }
                AssertionError failure =
                        new AssertionError(
                                "fatal parser failure with raw output suppressed");
                failure.addSuppressed(rawFailure);
                throw failure;
            }
        }
    }

    private enum SaxThenErrorMode {
        UNRELATED,
        RAW_CAUSE,
        RAW_SUPPRESSED
    }

    private static final class CompetingTaggedSaxBranchesParser
            implements Parser {

        private static final long serialVersionUID = 1L;

        @Override
        public Set<MediaType> getSupportedTypes(ParseContext context) {
            return Set.of(MediaType.OCTET_STREAM);
        }

        @Override
        public void parse(
                TikaInputStream stream, ContentHandler handler,
                Metadata metadata, ParseContext context)
                throws SAXException {
            XHTMLContentHandler xhtml =
                    new XHTMLContentHandler(handler, metadata, context);
            xhtml.startDocument();
            xhtml.startElement("p", new AttributesImpl());
            char[] chars = "blocked output".toCharArray();

            SAXException firstTaggedFailure;
            try {
                xhtml.characters(chars, 0, chars.length);
                throw new AssertionError("expected first output denial");
            } catch (SAXException e) {
                firstTaggedFailure = e;
            }

            SAXException laterTaggedFailure;
            try {
                xhtml.characters(chars, 0, chars.length);
                throw new AssertionError("expected later output denial");
            } catch (SAXException e) {
                laterTaggedFailure = e;
            }

            SAXException firstRawFailure = firstTaggedFailure;
            while (firstRawFailure.getCause() instanceof SAXException cause) {
                firstRawFailure = cause;
            }

            RuntimeException cycleFirst =
                    new RuntimeException("simulated cyclic branch");
            RuntimeException cycleSecond =
                    new RuntimeException(
                            "simulated cyclic branch peer", cycleFirst);
            cycleFirst.initCause(cycleSecond);

            AssertionError parserFailure =
                    new AssertionError(
                            "fatal parser failure with competing SAX branches");
            parserFailure.addSuppressed(laterTaggedFailure);
            parserFailure.addSuppressed(cycleFirst);
            parserFailure.addSuppressed(firstRawFailure);
            throw parserFailure;
        }
    }

    private static final class UncheckedAbortingParser implements Parser {

        private static final long serialVersionUID = 1L;
        private final UncheckedFailureMode mode;

        private UncheckedAbortingParser(UncheckedFailureMode mode) {
            this.mode = mode;
        }

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
            } catch (RuntimeException outputFailure) {
                if (mode == UncheckedFailureMode.DIRECT) {
                    throw outputFailure;
                }
                if (mode == UncheckedFailureMode.WRAPPED_CAUSE) {
                    throw new TikaException(
                            "cause-wrapped unchecked output failure",
                            outputFailure);
                }
                if (mode == UncheckedFailureMode.WRAPPED_SUPPRESSED) {
                    TikaException wrapper = new TikaException(
                            "suppressed unchecked output failure");
                    wrapper.addSuppressed(outputFailure);
                    throw wrapper;
                }
                if (mode == UncheckedFailureMode.SWALLOWED) {
                    return;
                }
                throw new AssertionError("Unhandled mode " + mode);
            }
        }
    }

    private enum UncheckedFailureMode {
        DIRECT,
        WRAPPED_CAUSE,
        WRAPPED_SUPPRESSED,
        SWALLOWED
    }

    private static final class ErrorAbortingParser implements Parser {

        private static final long serialVersionUID = 1L;
        private final UncheckedFailureMode mode;

        private ErrorAbortingParser(UncheckedFailureMode mode) {
            this.mode = mode;
        }

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
            } catch (Error outputFailure) {
                if (mode == UncheckedFailureMode.DIRECT) {
                    throw outputFailure;
                }
                if (mode == UncheckedFailureMode.WRAPPED_CAUSE) {
                    throw new AssertionError(
                            "cause-wrapped fatal output failure",
                            outputFailure);
                }
                if (mode == UncheckedFailureMode.WRAPPED_SUPPRESSED) {
                    AssertionError wrapper =
                            new AssertionError("suppressed fatal output failure");
                    wrapper.addSuppressed(outputFailure);
                    throw wrapper;
                }
                if (mode == UncheckedFailureMode.SWALLOWED) {
                    return;
                }
                throw new AssertionError("Unhandled mode " + mode);
            }
        }
    }

    private static final class RuntimeFailureParser implements Parser {

        private static final long serialVersionUID = 1L;
        private final RuntimeException failure;

        private RuntimeFailureParser(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public Set<MediaType> getSupportedTypes(ParseContext context) {
            return Set.of(MediaType.OCTET_STREAM);
        }

        @Override
        public void parse(
                TikaInputStream stream, ContentHandler handler,
                Metadata metadata, ParseContext context)
                throws SAXException {
            XHTMLContentHandler xhtml =
                    new XHTMLContentHandler(handler, metadata, context);
            xhtml.startDocument();
            xhtml.startElement("p", new AttributesImpl());
            throw failure;
        }
    }

    private static final class ErrorFailureParser implements Parser {

        private static final long serialVersionUID = 1L;
        private final Error failure;

        private ErrorFailureParser(Error failure) {
            this.failure = failure;
        }

        @Override
        public Set<MediaType> getSupportedTypes(ParseContext context) {
            return Set.of(MediaType.OCTET_STREAM);
        }

        @Override
        public void parse(
                TikaInputStream stream, ContentHandler handler,
                Metadata metadata, ParseContext context)
                throws SAXException {
            XHTMLContentHandler xhtml =
                    new XHTMLContentHandler(handler, metadata, context);
            xhtml.startDocument();
            xhtml.startElement("p", new AttributesImpl());
            throw failure;
        }
    }

    private static final class CyclicFailureParser implements Parser {

        private static final long serialVersionUID = 1L;

        @Override
        public Set<MediaType> getSupportedTypes(ParseContext context) {
            return Set.of(MediaType.OCTET_STREAM);
        }

        @Override
        public void parse(
                TikaInputStream stream, ContentHandler handler,
                Metadata metadata, ParseContext context)
                throws TikaException {
            TikaException failure = new TikaException("cyclic parser failure");
            IOException cause = new IOException("cyclic parser cause");
            failure.initCause(cause);
            cause.initCause(failure);
            throw failure;
        }
    }

    private static final class CleanupDenyingHandler extends DefaultHandler {

        private final SAXException denial;
        private boolean denied;
        private int callbacksAfterDenial;

        private CleanupDenyingHandler(SAXException denial) {
            this.denial = denial;
        }

        @Override
        public void endElement(String uri, String localName, String qName)
                throws SAXException {
            if (!denied) {
                denied = true;
                throw denial;
            }
            callbacksAfterDenial++;
        }

        private int getCallbacksAfterDenial() {
            return callbacksAfterDenial;
        }
    }

    private static final class RuntimeCleanupDenyingHandler
            extends DefaultHandler {

        private final RuntimeException denial;
        private boolean denied;
        private int callbacksAfterDenial;

        private RuntimeCleanupDenyingHandler(RuntimeException denial) {
            this.denial = denial;
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (!denied) {
                denied = true;
                throw denial;
            }
            callbacksAfterDenial++;
            throw new RuntimeException(
                    "callback delivered after runtime cleanup denial");
        }
    }

    private static final class ErrorCleanupDenyingHandler
            extends DefaultHandler {

        private final Error denial;
        private boolean denied;
        private int callbacksAfterDenial;

        private ErrorCleanupDenyingHandler(Error denial) {
            this.denial = denial;
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (!denied) {
                denied = true;
                throw denial;
            }
            callbacksAfterDenial++;
            throw new AssertionError(
                    "callback delivered after fatal cleanup denial");
        }
    }

    private static final class EndElementRecordingHandler
            extends DefaultHandler {

        private final List<String> endedElements = new ArrayList<>();

        @Override
        public void endElement(String uri, String localName, String qName) {
            endedElements.add(localName);
        }
    }

    private static final class FailStopHandler extends DefaultHandler {

        private final String rejectedText;
        private final SAXException denial;
        private final SAXException cleanupFailure =
                new SAXException("SAX callback delivered after policy denial");
        private boolean denied;
        private int callbacksAfterDenial;

        private FailStopHandler(String rejectedText, SAXException denial) {
            this.rejectedText = rejectedText;
            this.denial = denial;
        }

        @Override
        public void characters(char[] ch, int start, int length)
                throws SAXException {
            rejectAfterDenial();
            if (new String(ch, start, length).contains(rejectedText)) {
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
                throw cleanupFailure;
            }
        }
    }

    private static final class FailStopSecurityHandler extends DefaultHandler {

        private final String rejectedText;
        private final SecurityException denial;
        private final SecurityException cleanupFailure =
                new SecurityException("callback delivered after security policy denial");
        private boolean denied;
        private int callbacksAfterDenial;

        private FailStopSecurityHandler(
                String rejectedText, SecurityException denial) {
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

        private void rejectAfterDenial() {
            if (denied) {
                callbacksAfterDenial++;
                throw cleanupFailure;
            }
        }
    }

    private static final class FailStopRuntimeHandler extends DefaultHandler {

        private final String rejectedText;
        private final RuntimeException denial;
        private boolean denied;
        private int callbacksAfterDenial;

        private FailStopRuntimeHandler(
                String rejectedText, RuntimeException denial) {
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

        private void rejectAfterDenial() {
            if (denied) {
                callbacksAfterDenial++;
                throw new RuntimeException(
                        "callback delivered after unchecked policy denial");
            }
        }
    }

    private static final class FailStopErrorHandler extends DefaultHandler {

        private final String rejectedText;
        private final Error denial;
        private boolean denied;
        private int callbacksAfterDenial;

        private FailStopErrorHandler(
                String rejectedText, Error denial) {
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

        private void rejectAfterDenial() {
            if (denied) {
                callbacksAfterDenial++;
                throw new AssertionError(
                        "callback delivered after fatal policy denial");
            }
        }
    }
}
