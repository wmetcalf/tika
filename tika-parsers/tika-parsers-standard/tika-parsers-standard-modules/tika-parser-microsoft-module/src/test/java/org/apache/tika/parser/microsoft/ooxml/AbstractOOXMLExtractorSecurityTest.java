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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Office;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.microsoft.OfficeParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;

public class AbstractOOXMLExtractorSecurityTest {

    @Test
    public void testExternalRelationshipCapIsSignaled() throws Exception {
        ParseContext context = new ParseContext();
        context.set(OfficeParserConfig.class, new OfficeParserConfig());
        Metadata metadata = new Metadata();
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            for (int i = 0; i < 1_025; i++) {
                opcPackage.addExternalRelationship(
                        "https://example.invalid/external-" + i,
                        "http://schemas.openxmlformats.org/officeDocument/"
                                + "2006/relationships/hyperlink");
            }

            new EmptyExtractor(context, opcPackage).getXHTML(
                    new BodyContentHandler(-1), metadata, context);
        }

        assertEquals(1_024,
                metadata.getValues(Office.OFFICE_LINK_RECORD).length);
        assertEquals("true",
                metadata.get(TikaCoreProperties.TRUNCATED_METADATA));
        assertNotNull(metadata.get(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING),
                "skipping external relationships must not be silent");
        assertNotNull(metadata.get("ExploitClass"),
                "a skipped relationship can hide an executable reference");
    }

    @Test
    public void testExactExternalRelationshipCapIsNotReportedAsTruncated()
            throws Exception {
        ParseContext context = new ParseContext();
        context.set(OfficeParserConfig.class, new OfficeParserConfig());
        Metadata metadata = new Metadata();
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            for (int i = 0; i < 1_024; i++) {
                opcPackage.addExternalRelationship(
                        "https://example.invalid/external-" + i,
                        "http://schemas.openxmlformats.org/officeDocument/"
                                + "2006/relationships/hyperlink");
            }
            opcPackage.createPart(
                    PackagingURIHelper.createPartName("/word/document.xml"),
                    "application/xml");

            new EmptyExtractor(context, opcPackage).getXHTML(
                    new BodyContentHandler(-1), metadata, context);
        }

        assertEquals(1_024,
                metadata.getValues(Office.OFFICE_LINK_RECORD).length);
        assertNull(metadata.get(TikaCoreProperties.TRUNCATED_METADATA));
        assertNull(metadata.get(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING),
                "reaching the exact cap without dropping a link is complete");
    }

    private static final class EmptyExtractor extends AbstractOOXMLExtractor {

        private EmptyExtractor(ParseContext context, OPCPackage opcPackage) {
            super(context, opcPackage);
        }

        @Override
        protected void buildXHTML(XHTMLContentHandler xhtml)
                throws SAXException, IOException {
        }

        @Override
        protected List<PackagePart> getMainDocumentParts() {
            return Collections.emptyList();
        }
    }
}
