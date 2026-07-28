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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.xml.parsers.SAXParser;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.junit.jupiter.api.Test;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Parser;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.XMLFilterImpl;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.microsoft.OfficeParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.utils.XMLReaderUtils;

class XSSFExcelExtractorSecurityTest {

    @Test
    void optionalStylesSecurityExceptionPropagates() throws Exception {
        SecurityException denial =
                new SecurityException("simulated styles XML policy denial");
        ParseContext context = configuredContext();
        context.set(SAXParser.class, new OneShotSecuritySaxParser(denial));

        SecurityException thrown;
        try (InputStream input = getClass().getResourceAsStream(
                     "/test-documents/testEXCEL.xlsx");
             OPCPackage pkg = OPCPackage.open(input)) {
            XSSFExcelExtractorDecorator extractor =
                    new XSSFExcelExtractorDecorator(context, pkg, Locale.ROOT);
            thrown = assertThrows(SecurityException.class,
                    () -> extractor.getXHTML(
                            new BodyContentHandler(-1),
                            new Metadata(), context));
        }

        assertSame(denial, thrown);
    }

    @Test
    void externalReferenceHandlerAbortPropagates() throws Exception {
        SAXException denial =
                new SAXException("simulated external-reference policy abort");
        ParseContext context = configuredContext();

        SAXException thrown;
        try (InputStream input = getClass().getResourceAsStream(
                     "/test-documents/testDataConnections.xlsx");
             OPCPackage pkg = OPCPackage.open(input)) {
            XSSFExcelExtractorDecorator extractor =
                    new XSSFExcelExtractorDecorator(context, pkg, Locale.ROOT);
            thrown = assertThrows(SAXException.class,
                    () -> extractor.getXHTML(
                            new ExternalReferenceDenyingHandler(denial),
                            new Metadata(), context));
        }

        assertSame(denial, thrown);
    }

    private static ParseContext configuredContext() {
        ParseContext context = new ParseContext();
        context.set(OfficeParserConfig.class, new OfficeParserConfig());
        return context;
    }

    private static final class ExternalReferenceDenyingHandler
            extends DefaultHandler {

        private final SAXException denial;

        private ExternalReferenceDenyingHandler(SAXException denial) {
            this.denial = denial;
        }

        @Override
        public void startElement(
                String uri, String localName, String qName, Attributes attributes)
                throws SAXException {
            String cssClass = attributes.getValue("class");
            if (cssClass != null && cssClass.startsWith("external-ref-")) {
                throw denial;
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static final class OneShotSecuritySaxParser extends SAXParser {

        private final SecurityException denial;
        private final AtomicBoolean first = new AtomicBoolean(true);
        private final SAXParser delegate;

        private OneShotSecuritySaxParser(SecurityException denial)
                throws TikaException {
            this.denial = denial;
            this.delegate = XMLReaderUtils.getSAXParser();
        }

        @Override
        @SuppressForbidden
        public Parser getParser() throws SAXException {
            return delegate.getParser();
        }

        @Override
        public XMLReader getXMLReader() throws SAXException {
            if (!first.compareAndSet(true, false)) {
                return delegate.getXMLReader();
            }
            return new XMLFilterImpl() {
                @Override
                public void parse(InputSource input) {
                    throw denial;
                }

                @Override
                public void parse(String systemId) {
                    throw denial;
                }
            };
        }

        @Override
        public boolean isNamespaceAware() {
            return delegate.isNamespaceAware();
        }

        @Override
        public boolean isValidating() {
            return delegate.isValidating();
        }

        @Override
        public void setProperty(String name, Object value) {
            // no-op
        }

        @Override
        public Object getProperty(String name) {
            return null;
        }
    }
}
