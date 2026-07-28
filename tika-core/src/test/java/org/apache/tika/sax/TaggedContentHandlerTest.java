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
package org.apache.tika.sax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

public class TaggedContentHandlerTest {

    @Test
    public void testRepeatedSameTagWrappersUnwrapToOriginal() {
        TaggedContentHandler handler =
                new TaggedContentHandler(new DefaultHandler());
        SAXException original = new SAXException("original downstream failure");
        TaggedSAXException first = new TaggedSAXException(original, handler);
        TaggedSAXException second = new TaggedSAXException(first, handler);

        SAXException thrown = assertThrows(SAXException.class,
                () -> handler.throwIfCauseOf(second));

        assertSame(original, thrown);
    }

    @Test
    public void testUnwrappingStopsAtDifferentTagBoundary() {
        TaggedContentHandler handler =
                new TaggedContentHandler(new DefaultHandler());
        TaggedContentHandler other =
                new TaggedContentHandler(new DefaultHandler());
        SAXException original = new SAXException("other handler failure");
        TaggedSAXException boundary = new TaggedSAXException(original, other);
        TaggedSAXException outer = new TaggedSAXException(boundary, handler);

        SAXException thrown = assertThrows(SAXException.class,
                () -> handler.throwIfCauseOf(outer));

        assertSame(boundary, thrown);
    }

    @Test
    public void testCheckedSaxFailureIsRecordedByIdentity() {
        SAXException denial =
                new SAXException("checked downstream denial");
        TaggedContentHandler handler =
                new TaggedContentHandler(new DefaultHandler() {
                    @Override
                    public void characters(char[] ch, int start, int length)
                            throws SAXException {
                        throw denial;
                    }
                });

        assertThrows(TaggedSAXException.class,
                () -> handler.characters(new char[]{'x'}, 0, 1));

        assertSame(denial, handler.getSaxFailure());
    }

    @Test
    public void testWrappedCheckedFailureIsUnwrappedFromCauseGraph() {
        SAXException denial =
                new SAXException("checked downstream denial");
        TaggedContentHandler handler =
                new TaggedContentHandler(new DefaultHandler() {
                    @Override
                    public void characters(char[] ch, int start, int length)
                            throws SAXException {
                        throw denial;
                    }
                });
        SAXException tagged = assertThrows(SAXException.class,
                () -> handler.characters(new char[]{'x'}, 0, 1));
        IOException wrapped =
                new IOException("embedded parser wrapper", tagged);

        SAXException thrown = assertThrows(SAXException.class,
                () -> handler.throwIfCauseOf(wrapped));

        assertSame(denial, thrown);
    }

    @Test
    public void testRawRecordedCheckedFailureIsFoundInCauseGraph() {
        SAXException denial =
                new SAXException("raw checked downstream denial");
        TaggedContentHandler handler =
                new TaggedContentHandler(new DefaultHandler() {
                    @Override
                    public void characters(char[] ch, int start, int length)
                            throws SAXException {
                        throw denial;
                    }
                });
        assertThrows(SAXException.class,
                () -> handler.characters(new char[]{'x'}, 0, 1));
        IOException wrapped =
                new IOException("raw embedded parser wrapper", denial);

        SAXException thrown = assertThrows(SAXException.class,
                () -> handler.throwIfCauseOf(wrapped));

        assertSame(denial, thrown);
    }

    @Test
    public void testRawFirstCheckedFailureOutranksLaterTaggedCandidate() {
        SAXException first =
                new SAXException("first checked downstream denial");
        SAXException later =
                new SAXException("later checked downstream denial");
        TaggedContentHandler handler =
                new TaggedContentHandler(new DefaultHandler() {
                    @Override
                    public void characters(char[] ch, int start, int length)
                            throws SAXException {
                        throw first;
                    }
                });
        assertThrows(SAXException.class,
                () -> handler.characters(new char[]{'x'}, 0, 1));

        IOException combined =
                new IOException("combined embedded parser failures");
        combined.addSuppressed(new TaggedSAXException(later, handler));
        combined.addSuppressed(first);

        SAXException thrown = assertThrows(SAXException.class,
                () -> handler.throwIfCauseOf(combined));

        assertSame(first, thrown);
    }

    @Test
    public void testUncheckedRuntimeIsRecordedByIdentity() {
        RuntimeException denial =
                new RuntimeException("unchecked downstream denial");
        TaggedContentHandler handler =
                new TaggedContentHandler(new DefaultHandler() {
                    @Override
                    public void characters(char[] ch, int start, int length) {
                        throw denial;
                    }
                });

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> handler.characters(new char[]{'x'}, 0, 1));

        assertSame(denial, thrown);
        assertSame(denial, handler.getUncheckedFailure());
        assertSame(denial, handler.findUncheckedCause(
                new RuntimeException("wrapped denial", denial)));
    }

    @Test
    public void testWrappedUncheckedFailureIsRethrownFromCauseGraph() {
        RuntimeException denial =
                new RuntimeException("unchecked downstream denial");
        TaggedContentHandler handler =
                new TaggedContentHandler(new DefaultHandler() {
                    @Override
                    public void characters(char[] ch, int start, int length) {
                        throw denial;
                    }
                });
        assertThrows(RuntimeException.class,
                () -> handler.characters(new char[]{'x'}, 0, 1));
        IOException wrapped =
                new IOException("embedded parser wrapper", denial);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> handler.throwIfCauseOf(wrapped));

        assertSame(denial, thrown);
    }

    @Test
    public void testFindUncheckedCauseTraversesSuppressedAndCycles() {
        RuntimeException denial =
                new RuntimeException("unchecked downstream denial");
        TaggedContentHandler handler =
                new TaggedContentHandler(new DefaultHandler() {
                    @Override
                    public void startDocument() {
                        throw denial;
                    }
                });
        assertThrows(RuntimeException.class, handler::startDocument);

        Exception first = new Exception("first");
        Exception second = new Exception("second");
        first.initCause(second);
        second.initCause(first);
        second.addSuppressed(denial);

        assertSame(denial, handler.findUncheckedCause(first));
    }

    @Test
    public void testSetDocumentLocatorErrorIsRecordedByIdentity() {
        AssertionError denial =
                new AssertionError("locator output denial");
        TaggedContentHandler handler =
                new TaggedContentHandler(new DefaultHandler() {
                    @Override
                    public void setDocumentLocator(Locator locator) {
                        throw denial;
                    }
                });

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> handler.setDocumentLocator(null));

        assertSame(denial, thrown);
        assertSame(denial, handler.getUncheckedFailure());
    }

    @Test
    public void testUncheckedFailureHistoryIsBounded() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<RuntimeException> mostRecent =
                new AtomicReference<>();
        RuntimeException first =
                new RuntimeException("first unchecked downstream denial");
        TaggedContentHandler handler =
                new TaggedContentHandler(new DefaultHandler() {
                    @Override
                    public void characters(char[] ch, int start, int length) {
                        int callback = callbacks.incrementAndGet();
                        if (callback == 1) {
                            mostRecent.set(first);
                            throw first;
                        }
                        RuntimeException failure = new RuntimeException(
                                "later unchecked downstream denial " + callback);
                        mostRecent.set(failure);
                        throw failure;
                    }
                });

        for (int i = 0; i < 1000; i++) {
            assertThrows(RuntimeException.class,
                    () -> handler.characters(new char[]{'x'}, 0, 1));
        }

        java.lang.reflect.Field failures =
                TaggedContentHandler.class.getDeclaredField("uncheckedFailures");
        failures.setAccessible(true);
        java.util.Set<?> retained = (java.util.Set<?>) failures.get(handler);

        assertEquals(1000, callbacks.get());
        assertEquals(64, retained.size());
        assertSame(first, handler.getUncheckedFailure());
        assertSame(mostRecent.get(), handler.findUncheckedCause(
                new IOException("wrapped most recent denial", mostRecent.get())));
    }

    @Test
    public void testFindUncheckedCausePrefersFirstRecordedFailure() {
        RuntimeException first =
                new RuntimeException("first unchecked downstream denial");
        RuntimeException later =
                new RuntimeException("later unchecked downstream denial");
        AtomicInteger callbacks = new AtomicInteger();
        TaggedContentHandler handler =
                new TaggedContentHandler(new DefaultHandler() {
                    @Override
                    public void characters(char[] ch, int start, int length) {
                        if (callbacks.getAndIncrement() == 0) {
                            throw first;
                        }
                        throw later;
                    }
                });

        assertSame(first, assertThrows(RuntimeException.class,
                () -> handler.characters(new char[]{'x'}, 0, 1)));
        assertThrows(RuntimeException.class,
                () -> handler.characters(new char[]{'y'}, 0, 1));

        RuntimeException graph = new RuntimeException("combined", first);
        graph.addSuppressed(later);
        assertSame(first, handler.findUncheckedCause(graph));
    }
}
