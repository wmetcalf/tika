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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
