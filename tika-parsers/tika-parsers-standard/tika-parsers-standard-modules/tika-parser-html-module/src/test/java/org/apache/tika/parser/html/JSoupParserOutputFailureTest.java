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
package org.apache.tika.parser.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;

class JSoupParserOutputFailureTest {

    @Test
    void primaryCallerDenialIsNotReplacedByCleanupDenial() {
        SAXException primary = new SAXException("HTML caller denial A");
        SAXException cleanup = new SAXException("HTML cleanup denial B");
        SaxDenyingHandler handler = new SaxDenyingHandler(primary, cleanup);

        SAXException thrown = assertThrows(SAXException.class,
                () -> parse(handler));

        assertSame(primary, thrown,
                "cleanup must not replace the original caller-owned denial");
    }

    @Test
    void noCallbacksOccurAfterPrimaryCallerDenial() {
        SAXException primary = new SAXException("HTML caller denial A");
        SaxDenyingHandler handler = new SaxDenyingHandler(primary, null);

        SAXException thrown = assertThrows(SAXException.class,
                () -> parse(handler));

        assertSame(primary, thrown);
        assertEquals(0, handler.callbacksAfterDenial,
                "the parser must fail-stop instead of issuing cleanup callbacks");
    }

    @Test
    void runtimeCallerDenialPreservesIdentityAndStopsCallbacks() {
        IllegalStateException primary = new IllegalStateException("HTML runtime denial");
        IllegalArgumentException cleanup =
                new IllegalArgumentException("HTML runtime cleanup denial");
        UncheckedDenyingHandler handler = new UncheckedDenyingHandler(primary, cleanup);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> parse(handler));

        assertSame(primary, thrown);
        assertEquals(0, handler.callbacksAfterDenial,
                "unchecked caller denial must fail-stop without cleanup callbacks");
    }

    @Test
    void causeBearingRuntimeCallerDenialPreservesIdentity() {
        IllegalStateException primary = new IllegalStateException(
                "HTML cause-bearing runtime denial",
                new IllegalArgumentException("HTML nested runtime cause"));
        UncheckedDenyingHandler handler = new UncheckedDenyingHandler(primary, null);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> parse(handler));

        assertSame(primary, thrown);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    void errorCallerDenialPreservesIdentityAndStopsCallbacks() {
        AssertionError primary = new AssertionError("HTML error denial");
        AssertionError cleanup = new AssertionError("HTML error cleanup denial");
        UncheckedDenyingHandler handler = new UncheckedDenyingHandler(primary, cleanup);

        AssertionError thrown = assertThrows(AssertionError.class, () -> parse(handler));

        assertSame(primary, thrown);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    void causeBearingErrorCallerDenialPreservesIdentity() {
        AssertionError primary = new AssertionError("HTML cause-bearing error denial");
        primary.initCause(new IllegalStateException("HTML nested error cause"));
        UncheckedDenyingHandler handler = new UncheckedDenyingHandler(primary, null);

        AssertionError thrown = assertThrows(AssertionError.class, () -> parse(handler));

        assertSame(primary, thrown);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    void parserRuntimeFailureStillRunsNormalCleanup() {
        IllegalStateException primary =
                new IllegalStateException("HTML parser runtime");
        CountingHandler handler = new CountingHandler();

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> parse(handler, throwingMapperContext(primary)));

        assertSame(primary, thrown);
        assertEquals(1, handler.endDocumentCalls);
    }

    @Test
    void fatalParserErrorPreservesIdentityWithoutOutputCleanup() {
        AssertionError primary = new AssertionError("HTML fatal parser error");
        CountingHandler handler = new CountingHandler();

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> parse(handler, throwingMapperContext(primary)));

        assertSame(primary, thrown);
        assertEquals(0, handler.endDocumentCalls,
                "fatal parser errors must not trigger output cleanup");
    }

    @Test
    void fatalCleanupErrorSupersedesRecoverableParserFailure() {
        IllegalStateException primary =
                new IllegalStateException("HTML recoverable parser runtime");
        AssertionError cleanup =
                new AssertionError("HTML fatal cleanup error");
        EndDocumentDenyingHandler handler =
                new EndDocumentDenyingHandler(cleanup);

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> parse(handler, throwingMapperContext(primary)));

        assertSame(cleanup, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(primary, thrown.getSuppressed()[0]);
    }

    private static void parse(DefaultHandler handler) throws SAXException {
        parse(handler, new ParseContext());
    }

    private static void parse(DefaultHandler handler, ParseContext context)
            throws SAXException {
        new JSoupParser().parseString(
                "<html><body><p>MANTIS_HTML_BODY</p></body></html>",
                handler, new Metadata(), context);
    }

    private static ParseContext throwingMapperContext(Throwable failure) {
        ParseContext context = new ParseContext();
        context.set(HtmlMapper.class, new ThrowingHtmlMapper(failure));
        return context;
    }

    private static final class SaxDenyingHandler extends DefaultHandler {
        private final SAXException primary;
        private final SAXException cleanup;
        private boolean denied;
        private boolean cleanupThrown;
        private int callbacksAfterDenial;

        private SaxDenyingHandler(SAXException primary, SAXException cleanup) {
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
            if (!denied && text.contains("MANTIS_HTML_BODY")) {
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

    private static final class UncheckedDenyingHandler extends DefaultHandler {
        private final Throwable primary;
        private final Throwable cleanup;
        private boolean denied;
        private boolean cleanupThrown;
        private int callbacksAfterDenial;

        private UncheckedDenyingHandler(Throwable primary, Throwable cleanup) {
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
            if (!denied && text.contains("MANTIS_HTML_BODY")) {
                denied = true;
                throwUnchecked(primary);
            }
            afterDenial();
        }

        @Override
        public void endDocument() throws SAXException {
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

    private static final class ThrowingHtmlMapper implements HtmlMapper {
        private final DefaultHtmlMapper delegate = new DefaultHtmlMapper();
        private final Throwable failure;

        private ThrowingHtmlMapper(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public String mapSafeElement(String name) {
            throwUnchecked(failure);
            return null;
        }

        @Override
        public boolean isDiscardElement(String name) {
            return delegate.isDiscardElement(name);
        }

        @Override
        public String mapSafeAttribute(String elementName, String attributeName) {
            return delegate.mapSafeAttribute(elementName, attributeName);
        }
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        throw (Error) failure;
    }
}
