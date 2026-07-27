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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.xml.sax.helpers.AttributesImpl;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Office;
import org.apache.tika.parser.ColorAwareConfig;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToXMLContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;

class OOXMLTikaBodyPartHandlerTest {

    @Test
    void testNbspIsInterModuleSpacingNotAColorCell() throws Exception {
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();
        ColorAwareConfig colorAwareConfig = new ColorAwareConfig();
        colorAwareConfig.setEnabled(true);
        context.set(ColorAwareConfig.class, colorAwareConfig);
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                new ToXMLContentHandler(), metadata, context);
        OOXMLTikaBodyPartHandler handler =
                new OOXMLTikaBodyPartHandler(xhtml, metadata);
        handler.setInlineBodyPartMap(OOXMLInlineBodyPartMap.EMPTY, context);

        xhtml.startDocument();
        handler.startParagraph(new ParagraphProperties());
        handler.run(new RunProperties(), "A\u00a0B");
        handler.endParagraph();
        xhtml.endDocument();

        assertEquals(2, handler.getColorCollector().getCellCount());
    }

    @Test
    void testInlineFootnoteHyperlinkAddsMetadata() throws Exception {
        Metadata metadata = new Metadata();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(new ToXMLContentHandler(),
                metadata, new ParseContext());
        OOXMLTikaBodyPartHandler handler = new OOXMLTikaBodyPartHandler(xhtml, metadata);

        Map<String, byte[]> footnotes = new HashMap<>();
        footnotes.put("1", ("<w:footnote xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<w:p><w:hyperlink r:id=\"rLink\"><w:r><w:t>note link</w:t></w:r></w:hyperlink></w:p>" +
                "</w:footnote>").getBytes(StandardCharsets.UTF_8));
        Map<String, String> relationships = new HashMap<>();
        relationships.put("rLink", "https://example.com/footnote");

        handler.setInlineBodyPartMap(new OOXMLInlineBodyPartMap(footnotes,
                Collections.emptyMap(), Collections.emptyMap(), relationships), new ParseContext());
        xhtml.startDocument();
        handler.footnoteReference("1");
        xhtml.endDocument();

        assertTrue(List.of(metadata.getValues(Office.OFFICE_LINK_URL))
                .contains("https://example.com/footnote"));
        assertTrue(List.of(metadata.getValues(Office.OFFICE_LINK_TEXT)).contains("note link"));
    }

    @Test
    void testInlineFootnoteContributesToDocumentColorGrid() throws Exception {
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();
        ColorAwareConfig colorAwareConfig = new ColorAwareConfig();
        colorAwareConfig.setEnabled(true);
        context.set(ColorAwareConfig.class, colorAwareConfig);
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                new ToXMLContentHandler(), metadata, context);
        OOXMLTikaBodyPartHandler handler =
                new OOXMLTikaBodyPartHandler(xhtml, metadata);
        Map<String, byte[]> footnotes = new HashMap<>();
        footnotes.put("1", """
                <w:footnote xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:p><w:r><w:rPr><w:color w:val="000000"/></w:rPr><w:t>X</w:t></w:r></w:p>
                </w:footnote>
                """.getBytes(StandardCharsets.UTF_8));
        handler.setInlineBodyPartMap(new OOXMLInlineBodyPartMap(
                footnotes, Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap()), context);

        xhtml.startDocument();
        handler.footnoteReference("1");
        xhtml.endDocument();

        assertEquals(1, handler.getColorCollector().getCellCount());
    }

    @Test
    void testLinkedOleResolvesRelationshipUrl() throws Exception {
        Metadata metadata = new Metadata();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(new ToXMLContentHandler(),
                metadata, new ParseContext());
        OOXMLTikaBodyPartHandler bodyHandler = new OOXMLTikaBodyPartHandler(xhtml, metadata);
        Map<String, String> relationships = new HashMap<>();
        relationships.put("rOle", "file:///tmp/linked-object.xls");
        OOXMLWordAndPowerPointTextHandler handler =
                new OOXMLWordAndPowerPointTextHandler(bodyHandler, relationships);

        AttributesImpl attrs = new AttributesImpl();
        attrs.addAttribute("", "Type", "Type", "CDATA", "Link");
        attrs.addAttribute("http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                "id", "r:id", "CDATA", "rOle");

        xhtml.startDocument();
        handler.startElement("urn:schemas-microsoft-com:office:office", "OLEObject",
                "o:OLEObject", attrs);
        xhtml.endDocument();

        assertEquals("true", metadata.get(Office.HAS_LINKED_OLE_OBJECTS));
        assertTrue(List.of(metadata.getValues(Office.OFFICE_LINK_URL))
                .contains("file:///tmp/linked-object.xls"));
        assertTrue(List.of(metadata.getValues(Office.OFFICE_LINK_TYPE)).contains("linked_ole"));
    }
}
