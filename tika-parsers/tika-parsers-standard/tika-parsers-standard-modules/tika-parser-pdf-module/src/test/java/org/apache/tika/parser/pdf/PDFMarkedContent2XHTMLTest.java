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
package org.apache.tika.parser.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.apache.tika.TikaTest;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToXMLContentHandler;


public class PDFMarkedContent2XHTMLTest extends TikaTest {

    static ParseContext MARKUP_CONTEXT = new ParseContext();

    @BeforeAll
    public static void setUp() {
        PDFParserConfig config = new PDFParserConfig();
        config.setExtractMarkedContent(true);

        MARKUP_CONTEXT.set(PDFParserConfig.class, config);
    }

    @Test
    public void testJournal() throws Exception {
        String xml = getXML("testJournalParser.pdf", MARKUP_CONTEXT).xml;
        assertContains("<h1>I. INTRODUCTION</h1>", xml);
    }

    @Test
    public void testVarious() throws Exception {
        String xml = getXML("testPDFVarious.pdf", MARKUP_CONTEXT).xml;
        assertContains("<div class=\"textbox\"><p>Here is a text box</p>", xml);
        assertContains("<div class=\"footnote\"><p>1 This is a footnote.</p>", xml);
        assertContains("<ul>\t<li>Bullet 1</li>", xml);
        assertContains("<table><tr>\t<td><p>Row 1 Col 1</p>", xml);
        assertContains("<p>Here is a citation:</p>", xml);
        assertContains("a href=\"http://tika.apache.org/\">This is a hyperlink</a>", xml);
        assertContains("This is the header text.", xml);
        assertContains("This is the footer text.", xml);
    }

    @Test
    public void testChildAttachments() throws Exception {
        List<Metadata> metadataList =
                getRecursiveMetadata("testPDF_childAttachments.pdf", MARKUP_CONTEXT);

        // Full-document analysis still extracts annotations beyond the visible
        // two-page output window.
        assertEquals(5, metadataList.size());

        String xml = metadataList.get(0).get(TikaCoreProperties.TIKA_CONTENT);
        //the point here is that in the annotations (that we
        // were grabbing by the classic PDF2XHTML),
        //the <a> content is identical to the href.  Here, they are not, which we only get from
        //marked up content...victory!!!
        assertContains("<a href=\"http://www.irs.gov\">IRS.gov</a>", xml);
        assertContains("<a href=\"http://www.irs.gov/pub15\">www.irs.gov/pub15</a>", xml);
    }

    @Test
    public void testMarkedContentHonorsLegacyVisiblePageLimit() throws Exception {
        String xml = getXML("testJournalParser.pdf", MARKUP_CONTEXT).xml;

        assertContains("I. INTRODUCTION", xml);
        assertFalse(xml.contains("We now construct the control primitives"),
                "marked-content text from page six must not bypass the two-page output limit");
        assertFalse(xml.contains("<table>"),
                "marked-content structure from later pages must not bypass the output limit");
    }

    @Test
    public void testAllowedPageLinkBelowExcludedPageParentIsVisited() throws Exception {
        try (PDDocument document = buildPageOverrideDocument()) {
            ToXMLContentHandler handler = new ToXMLContentHandler();
            PDFParserConfig config = new PDFParserConfig();
            config.setExtractMarkedContent(true);

            PDFMarkedContent2XHTML.process(document, handler, new ParseContext(),
                    new Metadata(), config, null, 2);

            assertContains("<a href=\"https://allowed.invalid/\">ALLOWED_CHILD</a>",
                    handler.toString());
        }
    }

    @Test
    public void testExcludedLinkAncestorRetainsAllowedDescendantUri() throws Exception {
        try (PDDocument document = buildPageOverrideDocument()) {
            addExcludedLinkAncestorStructure(document);
            ToXMLContentHandler handler = new ToXMLContentHandler();
            PDFParserConfig config = new PDFParserConfig();
            config.setExtractMarkedContent(true);

            PDFMarkedContent2XHTML.process(document, handler, new ParseContext(),
                    new Metadata(), config, null, 2);

            assertContains(
                    "<a href=\"https://allowed-descendant.invalid/\">ALLOWED_CHILD</a>",
                    handler.toString());
        }
    }

    @Test
    public void testExcludedNestedLinkDoesNotResetAllowedOuterLink() throws Exception {
        try (PDDocument document = buildNestedExcludedLinkDocument()) {
            ToXMLContentHandler handler = new ToXMLContentHandler();
            PDFParserConfig config = new PDFParserConfig();
            config.setExtractMarkedContent(true);

            PDFMarkedContent2XHTML.process(document, handler, new ParseContext(),
                    new Metadata(), config, null, 2);

            assertContains(
                    "<a href=\"https://outer.invalid/\">OUTER_BEFOREOUTER_AFTER</a>",
                    handler.toString());
        }
    }

    @Test
    public void testNestedAllowedLinksRetainDistinctUris() throws Exception {
        try (PDDocument document = buildNestedAllowedLinkDocument()) {
            ToXMLContentHandler handler = new ToXMLContentHandler();
            PDFParserConfig config = new PDFParserConfig();
            config.setExtractMarkedContent(true);

            PDFMarkedContent2XHTML.process(document, handler, new ParseContext(),
                    new Metadata(), config, null, 2);

            String xml = handler.toString();
            assertContains("<a href=\"https://outer.invalid/\">OUTER</a>", xml);
            assertContains("<a href=\"https://inner.invalid/\">INNER</a>", xml);
        }
    }

    private static PDDocument buildPageOverrideDocument() throws IOException {
        PDDocument document = new PDDocument();
        List<PDPage> pages = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            PDPage page = new PDPage();
            page.setResources(new PDResources());
            document.addPage(page);
            pages.add(page);
        }

        COSDictionary markedProperties = new COSDictionary();
        markedProperties.setInt(COSName.MCID, 0);
        try (PDPageContentStream contents =
                     new PDPageContentStream(document, pages.get(0))) {
            contents.beginMarkedContent(COSName.P, PDPropertyList.create(markedProperties));
            contents.beginText();
            contents.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            contents.newLineAtOffset(72, 720);
            contents.showText("ALLOWED_CHILD");
            contents.endText();
            contents.endMarkedContent();
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.save(output);
        document.close();
        PDDocument loaded = Loader.loadPDF(output.toByteArray());
        addPageOverrideStructure(loaded);
        return loaded;
    }

    private static PDDocument buildNestedExcludedLinkDocument() throws IOException {
        PDDocument document = new PDDocument();
        List<PDPage> pages = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            PDPage page = new PDPage();
            page.setResources(new PDResources());
            document.addPage(page);
            pages.add(page);
        }

        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (PDPageContentStream contents =
                     new PDPageContentStream(document, pages.get(0))) {
            writeMarkedText(contents, font, 0, 720, "OUTER_BEFORE");
            writeMarkedText(contents, font, 1, 700, "OUTER_AFTER");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.save(output);
        document.close();
        PDDocument loaded = Loader.loadPDF(output.toByteArray());
        addNestedExcludedLinkStructure(loaded);
        return loaded;
    }

    private static PDDocument buildNestedAllowedLinkDocument() throws IOException {
        PDDocument document = new PDDocument();
        PDPage page = new PDPage();
        page.setResources(new PDResources());
        document.addPage(page);

        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (PDPageContentStream contents = new PDPageContentStream(document, page)) {
            writeMarkedText(contents, font, 0, 720, "OUTER");
            writeMarkedText(contents, font, 1, 700, "INNER");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.save(output);
        document.close();
        PDDocument loaded = Loader.loadPDF(output.toByteArray());
        addNestedAllowedLinkStructure(loaded);
        return loaded;
    }

    private static void writeMarkedText(PDPageContentStream contents, PDType1Font font, int mcid,
                                        float y, String text) throws IOException {
        COSDictionary markedProperties = new COSDictionary();
        markedProperties.setInt(COSName.MCID, mcid);
        contents.beginMarkedContent(COSName.P, PDPropertyList.create(markedProperties));
        contents.beginText();
        contents.setFont(font, 12);
        contents.newLineAtOffset(72, y);
        contents.showText(text);
        contents.endText();
        contents.endMarkedContent();
    }

    private static void addPageOverrideStructure(PDDocument document) {
        COSArray pageRefs = (COSArray) document.getPages().getCOSObject()
                .getDictionaryObject(COSName.KIDS);

        COSDictionary action = new COSDictionary();
        action.setItem(COSName.S, COSName.URI);
        action.setString(COSName.URI, "https://allowed.invalid/");
        COSDictionary linkTarget = new COSDictionary();
        linkTarget.setItem(COSName.A, action);

        COSArray linkKids = new COSArray();
        linkKids.add(COSInteger.ZERO);
        linkKids.add(linkTarget);
        COSDictionary allowedLink = new COSDictionary();
        allowedLink.setItem(COSName.S, COSName.getPDFName("Link"));
        allowedLink.setItem(COSName.PG, pageRefs.get(0));
        allowedLink.setItem(COSName.K, linkKids);

        COSDictionary excludedParent = new COSDictionary();
        excludedParent.setItem(COSName.S, COSName.getPDFName("Div"));
        excludedParent.setItem(COSName.PG, pageRefs.get(5));
        excludedParent.setItem(COSName.K, new COSObject(allowedLink));

        PDStructureTreeRoot root = new PDStructureTreeRoot();
        COSArray rootKids = new COSArray();
        rootKids.add(new COSObject(excludedParent));
        root.setK(rootKids);
        document.getDocumentCatalog().setStructureTreeRoot(root);
    }

    private static void addExcludedLinkAncestorStructure(PDDocument document) {
        COSArray pageRefs = (COSArray) document.getPages().getCOSObject()
                .getDictionaryObject(COSName.KIDS);

        COSDictionary allowedChild = new COSDictionary();
        allowedChild.setItem(COSName.S, COSName.P);
        allowedChild.setItem(COSName.PG, pageRefs.get(0));
        allowedChild.setItem(COSName.K, COSInteger.ZERO);

        COSDictionary action = new COSDictionary();
        action.setItem(COSName.S, COSName.URI);
        action.setString(COSName.URI, "https://allowed-descendant.invalid/");
        COSDictionary linkTarget = new COSDictionary();
        linkTarget.setItem(COSName.A, action);

        COSArray linkKids = new COSArray();
        linkKids.add(new COSObject(allowedChild));
        linkKids.add(linkTarget);
        COSDictionary excludedLink = new COSDictionary();
        excludedLink.setItem(COSName.S, COSName.getPDFName("Link"));
        excludedLink.setItem(COSName.PG, pageRefs.get(5));
        excludedLink.setItem(COSName.K, linkKids);

        PDStructureTreeRoot root = new PDStructureTreeRoot();
        COSArray rootKids = new COSArray();
        rootKids.add(new COSObject(excludedLink));
        root.setK(rootKids);
        document.getDocumentCatalog().setStructureTreeRoot(root);
    }

    private static void addNestedExcludedLinkStructure(PDDocument document) {
        COSArray pageRefs = (COSArray) document.getPages().getCOSObject()
                .getDictionaryObject(COSName.KIDS);

        COSDictionary innerAction = new COSDictionary();
        innerAction.setItem(COSName.S, COSName.URI);
        innerAction.setString(COSName.URI, "https://excluded-inner.invalid/");
        COSDictionary innerTarget = new COSDictionary();
        innerTarget.setItem(COSName.A, innerAction);
        COSDictionary excludedInnerLink = new COSDictionary();
        excludedInnerLink.setItem(COSName.S, COSName.getPDFName("Link"));
        excludedInnerLink.setItem(COSName.PG, pageRefs.get(5));
        excludedInnerLink.setItem(COSName.K, innerTarget);

        COSDictionary outerAction = new COSDictionary();
        outerAction.setItem(COSName.S, COSName.URI);
        outerAction.setString(COSName.URI, "https://outer.invalid/");
        COSDictionary outerTarget = new COSDictionary();
        outerTarget.setItem(COSName.A, outerAction);
        COSArray outerKids = new COSArray();
        outerKids.add(COSInteger.ZERO);
        outerKids.add(new COSObject(excludedInnerLink));
        outerKids.add(COSInteger.ONE);
        outerKids.add(outerTarget);
        COSDictionary allowedOuterLink = new COSDictionary();
        allowedOuterLink.setItem(COSName.S, COSName.getPDFName("Link"));
        allowedOuterLink.setItem(COSName.PG, pageRefs.get(0));
        allowedOuterLink.setItem(COSName.K, outerKids);

        PDStructureTreeRoot root = new PDStructureTreeRoot();
        COSArray rootKids = new COSArray();
        rootKids.add(new COSObject(allowedOuterLink));
        root.setK(rootKids);
        document.getDocumentCatalog().setStructureTreeRoot(root);
    }

    private static void addNestedAllowedLinkStructure(PDDocument document) {
        COSArray pageRefs = (COSArray) document.getPages().getCOSObject()
                .getDictionaryObject(COSName.KIDS);

        COSDictionary innerAction = new COSDictionary();
        innerAction.setItem(COSName.S, COSName.URI);
        innerAction.setString(COSName.URI, "https://inner.invalid/");
        COSDictionary innerTarget = new COSDictionary();
        innerTarget.setItem(COSName.A, innerAction);
        COSArray innerKids = new COSArray();
        innerKids.add(COSInteger.ONE);
        innerKids.add(innerTarget);
        COSDictionary innerLink = new COSDictionary();
        innerLink.setItem(COSName.S, COSName.getPDFName("Link"));
        innerLink.setItem(COSName.PG, pageRefs.get(0));
        innerLink.setItem(COSName.K, innerKids);

        COSDictionary outerAction = new COSDictionary();
        outerAction.setItem(COSName.S, COSName.URI);
        outerAction.setString(COSName.URI, "https://outer.invalid/");
        COSDictionary outerTarget = new COSDictionary();
        outerTarget.setItem(COSName.A, outerAction);
        COSArray outerKids = new COSArray();
        outerKids.add(COSInteger.ZERO);
        outerKids.add(outerTarget);
        outerKids.add(new COSObject(innerLink));
        COSDictionary outerLink = new COSDictionary();
        outerLink.setItem(COSName.S, COSName.getPDFName("Link"));
        outerLink.setItem(COSName.PG, pageRefs.get(0));
        outerLink.setItem(COSName.K, outerKids);

        PDStructureTreeRoot root = new PDStructureTreeRoot();
        COSArray rootKids = new COSArray();
        rootKids.add(new COSObject(outerLink));
        root.setK(rootKids);
        document.getDocumentCatalog().setStructureTreeRoot(root);
    }

}
