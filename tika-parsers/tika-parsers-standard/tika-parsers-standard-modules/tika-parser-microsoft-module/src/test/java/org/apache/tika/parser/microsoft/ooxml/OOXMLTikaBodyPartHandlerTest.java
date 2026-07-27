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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.apache.tika.metadata.TikaCoreProperties;
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
    void testRecoveryFlushesPendingExternalHyperlink() throws Exception {
        Metadata metadata = new Metadata();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                new ToXMLContentHandler(), metadata, new ParseContext());
        OOXMLTikaBodyPartHandler handler =
                new OOXMLTikaBodyPartHandler(xhtml, metadata);

        xhtml.startDocument();
        handler.hyperlinkStart("https://payload.invalid/launch");
        handler.run(new RunProperties(), "click me");
        handler.closeAnyPending();
        xhtml.endDocument();

        assertEquals(1, metadata.getValues(Office.OFFICE_LINK_URL).length);
        assertEquals("https://payload.invalid/launch",
                metadata.get(Office.OFFICE_LINK_URL));
        assertEquals("click me", metadata.get(Office.OFFICE_LINK_TEXT));
        assertEquals(1, metadata.getValues(Office.OFFICE_LINK_RECORD).length);
    }

    @Test
    void testPendingHyperlinkTextIsBoundedAndSignaled() throws Exception {
        Metadata metadata = new Metadata();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                new ToXMLContentHandler(), metadata, new ParseContext());
        OOXMLTikaBodyPartHandler handler =
                new OOXMLTikaBodyPartHandler(xhtml, metadata);

        xhtml.startDocument();
        handler.hyperlinkStart("https://payload.invalid/large-anchor");
        handler.run(new RunProperties(), "x".repeat(1_000_000));
        handler.closeAnyPending();
        xhtml.endDocument();

        assertTrue(metadata.get(Office.OFFICE_LINK_TEXT).length() <= 64 * 1024);
        assertEquals("true", metadata.get(TikaCoreProperties.TRUNCATED_METADATA));
        assertTrue(java.util.Arrays.stream(metadata.getValues(
                        TikaCoreProperties.TIKA_META_EXCEPTION_WARNING))
                .anyMatch(v -> v.contains("Office link")));
        assertTrue(metadata.get("ExploitClass").contains("link"));
    }

    @Test
    void testRecoveryAbandonsPendingColorRowAndSignalsTruncation() throws Exception {
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();
        context.set(ColorAwareConfig.class, new ColorAwareConfig().setEnabled(true));
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                new ToXMLContentHandler(), metadata, context);
        OOXMLTikaBodyPartHandler handler =
                new OOXMLTikaBodyPartHandler(xhtml, metadata);
        handler.setInlineBodyPartMap(OOXMLInlineBodyPartMap.EMPTY, context);

        xhtml.startDocument();
        handler.startParagraph(new ParagraphProperties());
        handler.run(new RunProperties(), "XXXXXX");
        handler.closeAnyPending();
        xhtml.endDocument();

        assertEquals(0, handler.getColorRows().size());
        assertEquals(0, handler.getColorCollector().getCellCount());
        assertTrue(handler.getColorCollector().isTruncated());
    }

    @Test
    void testWorksheetRecoveryAbandonsPendingColorRowAndSignalsTruncation()
            throws Exception {
        Metadata metadata = new Metadata();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(
                new ToXMLContentHandler(), metadata, new ParseContext());
        XSSFExcelExtractorDecorator.SheetTextAsHTML handler =
                new XSSFExcelExtractorDecorator.SheetTextAsHTML(
                        new org.apache.tika.parser.microsoft.OfficeParserConfig(), xhtml);
        handler.colorAwareEnabled = true;

        xhtml.startDocument();
        handler.startRow(0);
        for (int col = 0; col < 6; col++) {
            handler.cell(null, "X", (XSSFCommentsShim.CommentData) null);
        }
        handler.closeAnyPending();
        xhtml.endDocument();

        assertEquals(0, handler.colorCollector.getRows().size());
        assertEquals(0, handler.colorCollector.getCellCount());
        assertTrue(handler.colorCollector.isTruncated());
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
    void testInlinePartRelationshipsRemainPartLocal() throws Exception {
        Metadata metadata = new Metadata();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(new ToXMLContentHandler(),
                metadata, new ParseContext());
        OOXMLTikaBodyPartHandler handler = new OOXMLTikaBodyPartHandler(xhtml, metadata);
        byte[] footnote = ("<w:footnote "
                + "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<w:p><w:hyperlink r:id=\"rId1\"><w:r><w:t>footnote target</w:t></w:r>"
                + "</w:hyperlink></w:p></w:footnote>").getBytes(StandardCharsets.UTF_8);
        byte[] comment = ("<w:comment "
                + "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<w:p><w:hyperlink r:id=\"rId1\"><w:r><w:t>comment target</w:t></w:r>"
                + "</w:hyperlink></w:p></w:comment>").getBytes(StandardCharsets.UTF_8);
        Map<String, OOXMLInlineBodyPartMap.InlineBodyPart> footnotes = new HashMap<>();
        footnotes.put("1", OOXMLInlineBodyPartMap.part(footnote,
                Map.of("rId1", "https://attacker.example/footnote")));
        Map<String, OOXMLInlineBodyPartMap.InlineBodyPart> comments = new HashMap<>();
        comments.put("2", OOXMLInlineBodyPartMap.part(comment,
                Map.of("rId1", "https://decoy.example/comment")));
        handler.setInlineBodyPartMap(new OOXMLInlineBodyPartMap(
                footnotes, Collections.emptyMap(), comments), new ParseContext());

        xhtml.startDocument();
        handler.footnoteReference("1");
        xhtml.endDocument();

        assertTrue(List.of(metadata.getValues(Office.OFFICE_LINK_URL))
                .contains("https://attacker.example/footnote"));
        assertFalse(List.of(metadata.getValues(Office.OFFICE_LINK_URL))
                .contains("https://decoy.example/comment"));
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
    void testRepeatedInlineNoteExpansionIsBounded() throws Exception {
        Metadata metadata = new Metadata();
        ToXMLContentHandler output = new ToXMLContentHandler();
        XHTMLContentHandler xhtml =
                new XHTMLContentHandler(output, metadata, new ParseContext());
        OOXMLTikaBodyPartHandler handler =
                new OOXMLTikaBodyPartHandler(xhtml, metadata);
        Map<String, byte[]> footnotes = Map.of("1", """
                <w:footnote xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:p><w:r><w:t>bounded-note-content</w:t></w:r></w:p>
                </w:footnote>
                """.getBytes(StandardCharsets.UTF_8));
        handler.setInlineBodyPartMap(new OOXMLInlineBodyPartMap(
                footnotes, Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap()), new ParseContext());

        xhtml.startDocument();
        for (int i = 0; i < 1_100; i++) {
            handler.footnoteReference("1");
        }
        xhtml.endDocument();

        assertNotNull(metadata.get("ExploitClass"));
        assertTrue(countOccurrences(output.toString(), "bounded-note-content") <= 1_024);
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
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
