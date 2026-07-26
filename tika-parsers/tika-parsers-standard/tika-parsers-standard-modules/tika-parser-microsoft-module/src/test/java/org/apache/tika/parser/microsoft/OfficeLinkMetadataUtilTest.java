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
package org.apache.tika.parser.microsoft;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Office;
import org.apache.tika.metadata.writefilter.StandardMetadataLimiterFactory;

public class OfficeLinkMetadataUtilTest {

    @Test
    public void testAddLinkKeepsMultiValueFieldsAligned() {
        Metadata metadata = new Metadata();

        OfficeLinkMetadataUtil.addLink(metadata, "hyperlink", "https://example.com",
                "Example", "Example OCR", "document.xml", "shape 1",
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink",
                "rId4", "click", "external_url");
        OfficeLinkMetadataUtil.addLink(metadata, "attached_template", "file:///tmp/a.dotm",
                null, null, "settings.xml", null, "attachedTemplate", "rId5");

        assertArrayEquals(new String[]{"hyperlink", "attached_template"},
                metadata.getValues(Office.OFFICE_LINK_TYPE));
        assertArrayEquals(new String[]{"https://example.com", "file:///tmp/a.dotm"},
                metadata.getValues(Office.OFFICE_LINK_URL));
        assertArrayEquals(new String[]{"Example", ""},
                metadata.getValues(Office.OFFICE_LINK_TEXT));
        assertArrayEquals(new String[]{"click", ""},
                metadata.getValues(Office.OFFICE_LINK_TRIGGER));
    }

    @Test
    public void testNormalizeRelationshipTypes() {
        assertEquals("attached_template",
                OfficeLinkMetadataUtil.normalizeType("attachedTemplate"));
        assertEquals("hover_hyperlink", OfficeLinkMetadataUtil.normalizeType("hlinkHover"));
        assertEquals("click", OfficeLinkMetadataUtil.normalizeTrigger("field_hyperlink"));
        assertEquals("internal_anchor",
                OfficeLinkMetadataUtil.normalizeActionType("hyperlink", "#slide1"));
    }

    @Test
    public void testDefaultLimiterPreservesCanonicalLinkRecords() {
        StandardMetadataLimiterFactory factory = new StandardMetadataLimiterFactory();
        Metadata metadata = new Metadata(factory.newInstance());

        OfficeLinkMetadataUtil.addLink(metadata, "hyperlink",
                "https://first.invalid", null, null, "slide1.xml",
                "shape-1", "hlinkClick", null, "click", "external_url");
        OfficeLinkMetadataUtil.addLink(metadata, "hyperlink",
                "https://second.invalid", "second-visible-text", "second-ocr",
                "slide2.xml", "shape-2", "hlinkClick", "rId2",
                "click", "external_url");

        assertArrayEquals(new String[]{
                "{\"type\":\"hyperlink\",\"url\":\"https://first.invalid\","
                        + "\"text\":\"\",\"ocrText\":\"\",\"source\":\"slide1.xml\","
                        + "\"context\":\"shape-1\",\"relationshipType\":\"hlinkClick\","
                        + "\"id\":\"\",\"trigger\":\"click\",\"actionType\":\"external_url\"}",
                "{\"type\":\"hyperlink\",\"url\":\"https://second.invalid\","
                        + "\"text\":\"second-visible-text\",\"ocrText\":\"second-ocr\","
                        + "\"source\":\"slide2.xml\",\"context\":\"shape-2\","
                        + "\"relationshipType\":\"hlinkClick\",\"id\":\"rId2\","
                        + "\"trigger\":\"click\",\"actionType\":\"external_url\"}"
        }, metadata.getValues(Office.OFFICE_LINK_RECORD));
        assertArrayEquals(new String[]{"", "second-visible-text"},
                metadata.getValues(Office.OFFICE_LINK_TEXT));
        assertArrayEquals(new String[]{"", "rId2"},
                metadata.getValues(Office.OFFICE_LINK_ID));
    }

    @Test
    public void testCompatibilityFieldsHavePriorityUnderTightBudget() {
        StandardMetadataLimiterFactory factory = new StandardMetadataLimiterFactory();
        factory.setMaxFieldSize(10_000);
        factory.setMaxTotalBytes(500);
        Metadata metadata = new Metadata(factory.newInstance());

        OfficeLinkMetadataUtil.addLink(metadata, "hyperlink",
                "https://compatibility.invalid/payload", "visible text", "ocr text",
                "slide1.xml", "shape-1", "hlinkClick", "rId2",
                "click", "external_url");

        assertEquals("https://compatibility.invalid/payload",
                metadata.get(Office.OFFICE_LINK_URL));
    }
}
