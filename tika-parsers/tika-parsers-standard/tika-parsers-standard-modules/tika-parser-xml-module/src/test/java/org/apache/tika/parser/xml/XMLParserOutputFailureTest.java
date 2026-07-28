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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import javax.xml.parsers.SAXParser;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import org.junit.jupiter.api.Test;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.config.EmbeddedLimits;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.XHTMLContentHandler;

class XMLParserOutputFailureTest {

    private static final String XML =
            "<root><value>MANTIS_XML_BODY</value></root>";
    private static final String SVG = """
            <svg xmlns="http://www.w3.org/2000/svg" width="64" height="64">
              <rect width="64" height="64" fill="red"/>
            </svg>
            """;

    @Test
    void primaryCallerDenialIsNotReplacedByCleanupDenial() {
        SAXException primary = new SAXException("XML caller denial A");
        SAXException cleanup = new SAXException("XML cleanup denial B");
        DenyingHandler handler =
                new DenyingHandler("MANTIS_XML_BODY", primary, cleanup);

        SAXException thrown = assertThrows(SAXException.class,
                () -> parse(XML, "application/xml", handler,
                        new ParseContext()));

        assertSame(primary, thrown,
                "cleanup must not replace the original caller-owned denial");
    }

    @Test
    void noCallbacksOccurAfterPrimaryCallerDenial() {
        SAXException primary = new SAXException("XML caller denial A");
        DenyingHandler handler =
                new DenyingHandler("MANTIS_XML_BODY", primary, null);

        SAXException thrown = assertThrows(SAXException.class,
                () -> parse(XML, "application/xml", handler,
                        new ParseContext()));

        assertSame(primary, thrown);
        assertEquals(0, handler.callbacksAfterDenial,
                "the parser must fail-stop instead of issuing cleanup callbacks");
    }

    @Test
    void runtimeCallerDenialPreservesIdentityAndStopsCallbacks() {
        IllegalStateException primary = new IllegalStateException("XML runtime denial");
        IllegalArgumentException cleanup =
                new IllegalArgumentException("XML runtime cleanup denial");
        UncheckedDenyingHandler handler =
                new UncheckedDenyingHandler("MANTIS_XML_BODY", primary, cleanup);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> parse(XML, "application/xml", handler, new ParseContext()));

        assertSame(primary, thrown);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    void causeWrappedRuntimeCallerDenialPreservesIdentity() {
        IllegalStateException primary =
                new IllegalStateException("XML wrapped runtime denial");
        UncheckedDenyingHandler handler =
                new UncheckedDenyingHandler("MANTIS_XML_BODY", primary, null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> parse(XML, "application/xml", handler,
                        saxParserContext(new WrappingOutputSaxParser())));

        assertSame(primary, thrown);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    void errorCallerDenialPreservesIdentityAndStopsCallbacks() {
        AssertionError primary = new AssertionError("XML error denial");
        AssertionError cleanup = new AssertionError("XML error cleanup denial");
        UncheckedDenyingHandler handler =
                new UncheckedDenyingHandler("MANTIS_XML_BODY", primary, cleanup);

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> parse(XML, "application/xml", handler, new ParseContext()));

        assertSame(primary, thrown);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    void causeWrappedErrorCallerDenialPreservesIdentity() {
        AssertionError primary = new AssertionError("XML wrapped error denial");
        UncheckedDenyingHandler handler =
                new UncheckedDenyingHandler("MANTIS_XML_BODY", primary, null);

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> parse(XML, "application/xml", handler,
                        saxParserContext(new WrappingOutputSaxParser())));

        assertSame(primary, thrown);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    void parserRuntimeFailureRetainsNormalCleanup() {
        IllegalStateException primary =
                new IllegalStateException("XML parser runtime");
        CountingHandler handler = new CountingHandler();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> parse(XML, "application/xml", handler,
                        saxParserContext(new FailingSaxParser(primary))));

        assertSame(primary, thrown);
        assertEquals(1, handler.endDocumentCalls);
    }

    @Test
    void fatalParserErrorPreservesIdentityWithoutOutputCleanup() {
        AssertionError primary = new AssertionError("XML fatal parser error");
        CountingHandler handler = new CountingHandler();

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> parse(XML, "application/xml", handler,
                        saxParserContext(new FailingSaxParser(primary))));

        assertSame(primary, thrown);
        assertEquals(0, handler.endDocumentCalls);
    }

    @Test
    void fatalCleanupErrorSupersedesRecoverableParserFailure() {
        IllegalStateException primary =
                new IllegalStateException("XML recoverable parser runtime");
        AssertionError cleanup =
                new AssertionError("XML fatal cleanup error");
        EndDocumentDenyingHandler handler =
                new EndDocumentDenyingHandler(cleanup);

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> parse(XML, "application/xml", handler,
                        saxParserContext(new FailingSaxParser(primary))));

        assertSame(cleanup, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(primary, thrown.getSuppressed()[0]);
    }

    @Test
    void suppressedSaxCallerDenialPreservesIdentity() {
        SAXException primary = new SAXException("XML suppressed SAX denial");
        DenyingHandler handler =
                new DenyingHandler("MANTIS_XML_BODY", primary, null);

        SAXException thrown = assertThrows(SAXException.class,
                () -> parse(XML, "application/xml", handler,
                        saxParserContext(new SuppressingOutputSaxParser())));

        assertSame(primary, thrown);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    void errorCauseUnwrappedSaxCallerDenialPreservesIdentity() {
        assertRawUnwrappedSaxDenialPreservesIdentity(
                RawSaxWrapper.ERROR_CAUSE);
    }

    @Test
    void errorSuppressedUnwrappedSaxCallerDenialPreservesIdentity() {
        assertRawUnwrappedSaxDenialPreservesIdentity(
                RawSaxWrapper.ERROR_SUPPRESSED);
    }

    @Test
    void exactRecordedRawSaxOutranksCompetingTaggedSaxCycle() {
        SAXException firstDenial =
                new SAXException("first recorded raw XML SAX denial");
        SAXException laterDenial =
                new SAXException("later tagged XML SAX denial");
        DenyingHandler handler =
                new DenyingHandler(
                        "MANTIS_XML_BODY", firstDenial, null);

        SAXException thrown = assertThrows(SAXException.class,
                () -> parse(XML, "application/xml", handler,
                        saxParserContext(
                                new CompetingTaggedSaxCycleParser(
                                        laterDenial))));

        assertSame(firstDenial, thrown);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    void unrelatedErrorRemainsAuthoritativeAfterSaxDenial() {
        SAXException denial =
                new SAXException("unrelated XML SAX output denial");
        AssertionError parserFailure =
                new AssertionError("unrelated XML parser Error");
        DenyingHandler handler =
                new DenyingHandler("MANTIS_XML_BODY", denial, null);

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> parse(XML, "application/xml", handler,
                        saxParserContext(
                                new UnrelatedErrorAfterOutputSaxParser(
                                        parserFailure))));

        assertSame(parserFailure, thrown);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    void svgOcrCallerDenialPropagatesWithExactIdentity() {
        SAXException primary = new SAXException("SVG OCR caller denial");
        DenyingHandler handler =
                new DenyingHandler("MANTIS_OCR_OUTPUT", primary, null);
        OutputWritingOcrParser ocrParser = new OutputWritingOcrParser();

        SAXException thrown = assertThrows(SAXException.class,
                () -> parse(SVG, "image/svg+xml", handler,
                        svgOcrContext(ocrParser)));

        assertTrue(ocrParser.invoked, "the OCR parser sink must be reached");
        assertSame(primary, thrown,
                "caller-owned generic SAX denial must escape unchanged");
    }

    @Test
    void svgOcrStopsCallbacksAfterCallerDenial() {
        SAXException primary = new SAXException("SVG OCR caller denial");
        DenyingHandler handler =
                new DenyingHandler("MANTIS_OCR_OUTPUT", primary, null);
        OutputWritingOcrParser ocrParser = new OutputWritingOcrParser();

        SAXException thrown = assertThrows(SAXException.class,
                () -> parse(SVG, "image/svg+xml", handler,
                        svgOcrContext(ocrParser)));

        assertTrue(ocrParser.invoked, "the OCR parser sink must be reached");
        assertSame(primary, thrown);
        assertEquals(0, handler.callbacksAfterDenial,
                "SVG OCR must fail-stop instead of continuing outer XHTML cleanup");
    }

    @Test
    void svgOcrDirectRuntimeDenialPreservesIdentity() {
        IllegalStateException primary =
                new IllegalStateException("SVG direct runtime denial");
        assertSvgUncheckedDenial(primary, UncheckedOcrMode.DIRECT);
    }

    @Test
    void svgOcrWrappedRuntimeDenialPreservesIdentity() {
        IllegalStateException primary =
                new IllegalStateException("SVG wrapped runtime denial");
        assertSvgUncheckedDenial(primary, UncheckedOcrMode.WRAP);
    }

    @Test
    void svgOcrSwallowedRuntimeDenialPreservesIdentity() {
        IllegalStateException primary =
                new IllegalStateException("SVG swallowed runtime denial");
        assertSvgUncheckedDenial(primary, UncheckedOcrMode.SWALLOW);
    }

    @Test
    void svgOcrDirectErrorDenialPreservesIdentity() {
        AssertionError primary = new AssertionError("SVG direct error denial");
        assertSvgUncheckedDenial(primary, UncheckedOcrMode.DIRECT);
    }

    @Test
    void svgOcrWrappedErrorDenialPreservesIdentity() {
        AssertionError primary = new AssertionError("SVG wrapped error denial");
        assertSvgUncheckedDenial(primary, UncheckedOcrMode.WRAP);
    }

    @Test
    void svgOcrSwallowedErrorDenialPreservesIdentity() {
        AssertionError primary = new AssertionError("SVG swallowed error denial");
        assertSvgUncheckedDenial(primary, UncheckedOcrMode.SWALLOW);
    }

    @Test
    void parserOriginatedOcrFailureRemainsBestEffort() throws Exception {
        BackendFailingOcrParser ocrParser = new BackendFailingOcrParser();
        CountingHandler handler = new CountingHandler();
        Metadata metadata = parse(
                SVG, "image/svg+xml", handler, svgOcrContext(ocrParser));

        assertTrue(ocrParser.invoked, "the OCR parser control must be reached");
        assertNotNull(metadata.get(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING),
                "parser-originated OCR failure should remain visible but best-effort");
        assertEquals(1, handler.endDocumentCalls,
                "best-effort parser failures must retain normal cleanup");
    }

    @Test
    void xmlParserFailureRetainsNormalCleanup() {
        CountingHandler handler = new CountingHandler();

        assertThrows(TikaException.class,
                () -> parse("<root>", "application/xml", handler,
                        new ParseContext()));

        assertEquals(1, handler.endDocumentCalls);
    }

    @Test
    void cleanupDenialSuppressesXmlParserFailure() {
        SAXException cleanup = new SAXException("XML cleanup denial");
        CleanupDenyingHandler handler = new CleanupDenyingHandler(cleanup);

        SAXException thrown = assertThrows(SAXException.class,
                () -> parse("<root>", "application/xml", handler,
                        new ParseContext()));

        assertSame(cleanup, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertTrue(thrown.getSuppressed()[0] instanceof TikaException);
    }

    private static ParseContext svgOcrContext(Parser parser) {
        ParseContext context = new ParseContext();
        context.set(EmbeddedLimits.class, new EmbeddedLimits());
        context.set(Parser.class, parser);
        return context;
    }

    private static ParseContext saxParserContext(SAXParser parser) {
        ParseContext context = new ParseContext();
        context.set(SAXParser.class, parser);
        return context;
    }

    private static void assertSvgUncheckedDenial(
            Throwable primary, UncheckedOcrMode mode) {
        UncheckedDenyingHandler handler =
                new UncheckedDenyingHandler("MANTIS_OCR_OUTPUT", primary, null);
        UncheckedOutputOcrParser ocrParser = new UncheckedOutputOcrParser(mode);

        Throwable thrown;
        if (primary instanceof RuntimeException) {
            thrown = assertThrows(RuntimeException.class,
                    () -> parse(SVG, "image/svg+xml", handler,
                            svgOcrContext(ocrParser)));
        } else {
            thrown = assertThrows(Error.class,
                    () -> parse(SVG, "image/svg+xml", handler,
                            svgOcrContext(ocrParser)));
        }

        assertTrue(ocrParser.invoked, "the OCR parser sink must be reached");
        assertSame(primary, thrown);
        assertEquals(0, handler.callbacksAfterDenial,
                "SVG OCR must not emit callbacks after unchecked denial");
    }

    private static void assertRawUnwrappedSaxDenialPreservesIdentity(
            RawSaxWrapper wrapper) {
        SAXException primary =
                new SAXException(wrapper + " raw XML SAX output denial");
        DenyingHandler handler =
                new DenyingHandler("MANTIS_XML_BODY", primary, null);

        SAXException thrown = assertThrows(SAXException.class,
                () -> parse(XML, "application/xml", handler,
                        saxParserContext(
                                new RawUnwrappedOutputSaxParser(wrapper))));

        assertSame(primary, thrown);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    private static Metadata parse(
            String input, String contentType, ContentHandler handler,
            ParseContext context) throws Exception {
        Metadata metadata = new Metadata();
        metadata.set(Metadata.CONTENT_TYPE, contentType);
        try (TikaInputStream stream =
                     TikaInputStream.get(input.getBytes(StandardCharsets.UTF_8))) {
            new XMLParser().parse(stream, handler, metadata, context);
        }
        return metadata;
    }

    private static final class DenyingHandler extends DefaultHandler {
        private final String denialText;
        private final SAXException primary;
        private final SAXException cleanup;
        private boolean denied;
        private boolean cleanupThrown;
        private int callbacksAfterDenial;

        private DenyingHandler(String denialText, SAXException primary,
                               SAXException cleanup) {
            this.denialText = denialText;
            this.primary = primary;
            this.cleanup = cleanup;
        }

        @Override
        public void startElement(String uri, String localName, String qName,
                                 Attributes attributes) throws SAXException {
            afterDenial();
        }

        @Override
        public void endElement(String uri, String localName, String qName)
                throws SAXException {
            afterDenial();
        }

        @Override
        public void characters(char[] ch, int start, int length)
                throws SAXException {
            String text = new String(ch, start, length);
            if (!denied && text.contains(denialText)) {
                denied = true;
                throw primary;
            }
            afterDenial();
        }

        @Override
        public void endDocument() throws SAXException {
            afterDenial();
        }

        private void afterDenial() throws SAXException {
            if (!denied) {
                return;
            }
            callbacksAfterDenial++;
            if (cleanup != null && !cleanupThrown) {
                cleanupThrown = true;
                throw cleanup;
            }
        }
    }

    private static final class CountingHandler extends DefaultHandler {
        private int endDocumentCalls;

        @Override
        public void endDocument() {
            endDocumentCalls++;
        }
    }

    private static final class EndDocumentDenyingHandler extends DefaultHandler {
        private final Error failure;

        private EndDocumentDenyingHandler(Error failure) {
            this.failure = failure;
        }

        @Override
        public void endDocument() {
            throw failure;
        }
    }

    private static final class UncheckedDenyingHandler extends DefaultHandler {
        private final String denialText;
        private final Throwable primary;
        private final Throwable cleanup;
        private boolean denied;
        private boolean cleanupThrown;
        private int callbacksAfterDenial;

        private UncheckedDenyingHandler(
                String denialText, Throwable primary, Throwable cleanup) {
            this.denialText = denialText;
            this.primary = primary;
            this.cleanup = cleanup;
        }

        @Override
        public void startElement(String uri, String localName, String qName,
                                 Attributes attributes) {
            afterDenial();
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            afterDenial();
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            String text = new String(ch, start, length);
            if (!denied && text.contains(denialText)) {
                denied = true;
                throwUnchecked(primary);
            }
            afterDenial();
        }

        @Override
        public void endDocument() {
            afterDenial();
        }

        private void afterDenial() {
            if (!denied) {
                return;
            }
            callbacksAfterDenial++;
            if (cleanup != null && !cleanupThrown) {
                cleanupThrown = true;
                throwUnchecked(cleanup);
            }
        }
    }

    private static final class CleanupDenyingHandler extends DefaultHandler {
        private final SAXException cleanup;

        private CleanupDenyingHandler(SAXException cleanup) {
            this.cleanup = cleanup;
        }

        @Override
        public void endElement(String uri, String localName, String qName)
                throws SAXException {
            if ("p".equals(localName)) {
                throw cleanup;
            }
        }
    }

    private static final class OutputWritingOcrParser implements Parser {
        private static final long serialVersionUID = 1L;
        private boolean invoked;

        @Override
        public Set<MediaType> getSupportedTypes(ParseContext context) {
            return Set.of(MediaType.image("ocr-png"));
        }

        @Override
        public void parse(TikaInputStream stream, ContentHandler handler,
                          Metadata metadata, ParseContext context)
                throws SAXException {
            invoked = true;
            XHTMLContentHandler xhtml =
                    new XHTMLContentHandler(handler, metadata, context);
            xhtml.startDocument();
            xhtml.element("p", "MANTIS_OCR_OUTPUT");
            xhtml.endDocument();
        }
    }

    private static final class BackendFailingOcrParser implements Parser {
        private static final long serialVersionUID = 1L;
        private boolean invoked;

        @Override
        public Set<MediaType> getSupportedTypes(ParseContext context) {
            return Set.of(MediaType.image("ocr-png"));
        }

        @Override
        public void parse(TikaInputStream stream, ContentHandler handler,
                          Metadata metadata, ParseContext context)
                throws TikaException {
            invoked = true;
            throw new TikaException("simulated parser-origin OCR failure");
        }
    }

    private enum UncheckedOcrMode {
        DIRECT,
        WRAP,
        SWALLOW
    }

    private static final class UncheckedOutputOcrParser implements Parser {
        private static final long serialVersionUID = 1L;
        private final UncheckedOcrMode mode;
        private boolean invoked;

        private UncheckedOutputOcrParser(UncheckedOcrMode mode) {
            this.mode = mode;
        }

        @Override
        public Set<MediaType> getSupportedTypes(ParseContext context) {
            return Set.of(MediaType.image("ocr-png"));
        }

        @Override
        public void parse(TikaInputStream stream, ContentHandler handler,
                          Metadata metadata, ParseContext context)
                throws SAXException, TikaException {
            invoked = true;
            try {
                XHTMLContentHandler xhtml =
                        new XHTMLContentHandler(handler, metadata, context);
                xhtml.startDocument();
                xhtml.element("p", "MANTIS_OCR_OUTPUT");
                xhtml.endDocument();
            } catch (RuntimeException | Error failure) {
                if (mode == UncheckedOcrMode.WRAP) {
                    throw new TikaException("wrapped OCR output denial", failure);
                }
                if (mode == UncheckedOcrMode.SWALLOW) {
                    return;
                }
                throw failure;
            }
        }
    }

    private static class FailingSaxParser extends SAXParser {
        private final Throwable failure;

        private FailingSaxParser(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public void parse(InputStream input, DefaultHandler handler)
                throws SAXException, IOException {
            throwUnchecked(failure);
        }

        @Override
        @SuppressForbidden
        public org.xml.sax.Parser getParser() {
            throw new UnsupportedOperationException();
        }

        @Override
        public XMLReader getXMLReader() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isNamespaceAware() {
            return true;
        }

        @Override
        public boolean isValidating() {
            return false;
        }

        @Override
        public void setProperty(String name, Object value)
                throws SAXNotRecognizedException, SAXNotSupportedException {
            throw new SAXNotRecognizedException(name);
        }

        @Override
        public Object getProperty(String name)
                throws SAXNotRecognizedException, SAXNotSupportedException {
            throw new SAXNotRecognizedException(name);
        }
    }

    private static final class WrappingOutputSaxParser extends FailingSaxParser {
        private WrappingOutputSaxParser() {
            super(null);
        }

        @Override
        public void parse(InputStream input, DefaultHandler handler)
                throws SAXException, IOException {
            char[] chars = "MANTIS_XML_BODY".toCharArray();
            try {
                handler.characters(chars, 0, chars.length);
            } catch (RuntimeException | Error failure) {
                throw new IllegalStateException("wrapped XML output denial", failure);
            }
        }
    }

    private static final class SuppressingOutputSaxParser extends FailingSaxParser {
        private SuppressingOutputSaxParser() {
            super(null);
        }

        @Override
        public void parse(InputStream input, DefaultHandler handler)
                throws SAXException, IOException {
            char[] chars = "MANTIS_XML_BODY".toCharArray();
            try {
                handler.characters(chars, 0, chars.length);
            } catch (SAXException failure) {
                IllegalStateException wrapper =
                        new IllegalStateException("suppressed XML output denial");
                wrapper.addSuppressed(failure);
                throw wrapper;
            }
        }
    }

    private static final class RawUnwrappedOutputSaxParser
            extends FailingSaxParser {

        private final RawSaxWrapper wrapper;

        private RawUnwrappedOutputSaxParser(
                RawSaxWrapper wrapper) {
            super(null);
            this.wrapper = wrapper;
        }

        @Override
        public void parse(InputStream input, DefaultHandler handler)
                throws SAXException, IOException {
            char[] chars = "MANTIS_XML_BODY".toCharArray();
            try {
                handler.characters(chars, 0, chars.length);
            } catch (SAXException outputFailure) {
                Throwable rawFailure = unwrapFailure(outputFailure);
                Error parserFailure =
                        new Error(
                                "Error-wrapped raw XML SAX output denial",
                                wrapper == RawSaxWrapper.ERROR_CAUSE
                                        ? rawFailure : null);
                if (wrapper == RawSaxWrapper.ERROR_SUPPRESSED) {
                    parserFailure.addSuppressed(rawFailure);
                }
                throw parserFailure;
            }
        }
    }

    private static final class UnrelatedErrorAfterOutputSaxParser
            extends FailingSaxParser {

        private final AssertionError parserFailure;

        private UnrelatedErrorAfterOutputSaxParser(
                AssertionError parserFailure) {
            super(null);
            this.parserFailure = parserFailure;
        }

        @Override
        public void parse(InputStream input, DefaultHandler handler)
                throws SAXException, IOException {
            char[] chars = "MANTIS_XML_BODY".toCharArray();
            try {
                handler.characters(chars, 0, chars.length);
            } catch (SAXException expected) {
                throw parserFailure;
            }
        }
    }

    private static final class CompetingTaggedSaxCycleParser
            extends FailingSaxParser {

        private static final char[] OUTPUT =
                "MANTIS_XML_BODY".toCharArray();

        private final SAXException laterRawFailure;

        private CompetingTaggedSaxCycleParser(
                SAXException laterRawFailure) {
            super(null);
            this.laterRawFailure = laterRawFailure;
        }

        @Override
        public void parse(InputStream input, DefaultHandler handler)
                throws SAXException, IOException {
            try {
                handler.characters(OUTPUT, 0, OUTPUT.length);
                throw new AssertionError(
                        "expected first XML output denial");
            } catch (SAXException firstTaggedFailure) {
                Throwable firstRawFailure =
                        unwrapFailure(firstTaggedFailure);
                Object outputTag =
                        findTaggedSaxTag(firstTaggedFailure);
                SAXException competingTaggedFailure =
                        new org.apache.tika.sax.TaggedSAXException(
                                laterRawFailure, outputTag);
                org.apache.tika.sax.TaggedContentHandler taggedOutput =
                        (org.apache.tika.sax.TaggedContentHandler) outputTag;
                if (taggedOutput.getSaxFailure()
                                != firstRawFailure
                        || !taggedOutput.isCauseOf(
                                competingTaggedFailure)) {
                    throw new AssertionError(
                            "invalid competing XML tagged failure fixture");
                }
                Error parserFailure =
                        new Error(
                                "competing tagged XML SAX branches");
                RuntimeException cycle =
                        new RuntimeException(
                                "competing XML failure graph cycle");
                parserFailure.addSuppressed(
                        competingTaggedFailure);
                parserFailure.addSuppressed(firstRawFailure);
                parserFailure.addSuppressed(cycle);
                cycle.addSuppressed(parserFailure);
                throw parserFailure;
            }
        }
    }

    private static Throwable unwrapFailure(Throwable failure) {
        java.util.Set<Throwable> seen =
                java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && seen.add(current)) {
            Throwable cause = current.getCause();
            if (cause == null || cause == current) {
                return current;
            }
            current = cause;
        }
        return current;
    }

    private static Object findTaggedSaxTag(Throwable failure) {
        java.util.Set<Throwable> seen =
                java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<>());
        java.util.Deque<Throwable> pending =
                new java.util.ArrayDeque<>();
        pending.push(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.pop();
            if (!seen.add(current)) {
                continue;
            }
            if (current instanceof org.apache.tika.sax.TaggedSAXException tagged) {
                return tagged.getTag();
            }
            Throwable cause = current.getCause();
            if (cause != null && cause != current) {
                pending.push(cause);
            }
            for (Throwable suppressed : current.getSuppressed()) {
                if (suppressed != null && suppressed != current) {
                    pending.push(suppressed);
                }
            }
        }
        throw new AssertionError(
                "expected a tagged XML output failure");
    }

    private enum RawSaxWrapper {
        ERROR_CAUSE,
        ERROR_SUPPRESSED
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        throw (Error) failure;
    }
}
