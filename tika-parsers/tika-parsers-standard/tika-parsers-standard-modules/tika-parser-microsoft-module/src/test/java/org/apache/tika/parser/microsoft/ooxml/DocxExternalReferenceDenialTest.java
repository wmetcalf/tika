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
package org.apache.tika.parser.microsoft.ooxml;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.InputStream;

import org.junit.jupiter.api.Test;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;

class DocxExternalReferenceDenialTest {

    @Test
    void attachedTemplateGenericSaxDenialFailsStopByIdentity()
            throws Exception {
        assertExternalReferenceDenial(
                "/test-documents/testAttachedTemplate.docx",
                "external-ref-attachedTemplate");
    }

    @Test
    void subDocumentGenericSaxDenialFailsStopByIdentity()
            throws Exception {
        assertExternalReferenceDenial(
                "/test-documents/testSubdocument.docx",
                "external-ref-subDocument");
    }

    private void assertExternalReferenceDenial(
            String fixture, String rejectedClass) throws Exception {
        SAXException denial =
                new SAXException("generic SAX denial: " + rejectedClass);
        FailStopExternalReferenceHandler handler =
                new FailStopExternalReferenceHandler(rejectedClass, denial);
        SAXException thrown = null;

        try (InputStream resource = getClass().getResourceAsStream(fixture)) {
            assertNotNull(resource, "missing fixture " + fixture);
            try (TikaInputStream stream = TikaInputStream.get(resource)) {
                try {
                    new OOXMLParser().parse(
                            stream, handler, new Metadata(), new ParseContext());
                } catch (SAXException failure) {
                    thrown = failure;
                }
            }
        }

        SAXException observed = thrown;
        assertAll(
                () -> assertSame(
                        denial,
                        observed,
                        "generic external-reference SAX denial was swallowed or masked"),
                () -> assertEquals(
                        0,
                        handler.callbacksAfterDenial,
                        "parser emitted callbacks after downstream denial"));
    }

    private static final class FailStopExternalReferenceHandler
            extends DefaultHandler {

        private final String rejectedClass;
        private final SAXException denial;
        private boolean denied;
        private int callbacksAfterDenial;

        private FailStopExternalReferenceHandler(
                String rejectedClass, SAXException denial) {
            this.rejectedClass = rejectedClass;
            this.denial = denial;
        }

        private void noteCallback() {
            if (denied) {
                callbacksAfterDenial++;
            }
        }

        @Override
        public void startDocument() {
            noteCallback();
        }

        @Override
        public void endDocument() {
            noteCallback();
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) {
            noteCallback();
        }

        @Override
        public void endPrefixMapping(String prefix) {
            noteCallback();
        }

        @Override
        public void startElement(
                String uri, String localName, String qName, Attributes attributes)
                throws SAXException {
            noteCallback();
            if (!denied && rejectedClass.equals(attributes.getValue("class"))) {
                denied = true;
                throw denial;
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            noteCallback();
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            noteCallback();
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) {
            noteCallback();
        }

        @Override
        public void processingInstruction(String target, String data) {
            noteCallback();
        }

        @Override
        public void skippedEntity(String name) {
            noteCallback();
        }
    }
}
