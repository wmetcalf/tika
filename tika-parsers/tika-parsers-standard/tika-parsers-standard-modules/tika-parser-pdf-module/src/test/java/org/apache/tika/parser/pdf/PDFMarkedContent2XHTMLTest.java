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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ColorAwareConfig;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.image.ZXingCPPConfig;
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
    public void testColorAwareAnalysisTakesPriorityOverMarkedContentExtraction()
            throws Exception {
        ParseContext context = new ParseContext();
        PDFParserConfig config = new PDFParserConfig();
        config.setExtractMarkedContent(true);
        context.set(PDFParserConfig.class, config);
        context.set(ColorAwareConfig.class, new ColorAwareConfig().setEnabled(true));
        context.set(ZXingCPPConfig.class, new ZXingCPPConfig());

        XMLResult result = getXML("testJournalParser.pdf", context);

        assertEquals("true", result.metadata.get(
                org.apache.tika.metadata.PDF.HAS_MARKED_CONTENT));
        assertNotNull(result.metadata.get("pdf_color_qr:glyphs"),
                "tagged PDFs must still run color-aware glyph analysis");
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
    public void testMarkedContentPostProcessingHonorsAnalysisPageLimit() throws Exception {
        try (PDDocument document = buildPageOverrideDocument()) {
            ToXMLContentHandler handler = new ToXMLContentHandler();
            PDFParserConfig config = new PDFParserConfig();
            config.setExtractMarkedContent(true);
            config.setMaxPages(1);
            Metadata metadata = new Metadata();

            PDFMarkedContent2XHTML.process(document, handler, new ParseContext(),
                    metadata, config, null, 2);

            assertEquals(1, metadata.getValues(
                    org.apache.tika.metadata.PDF.CHARACTERS_PER_PAGE).length,
                    "marked-content post-processing must not revisit pages "
                            + "outside the analysis budget");
        }
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
    public void testExcludedNonLinkActionDoesNotReplaceAllowedLinkUri() throws Exception {
        try (PDDocument document = buildPageOverrideDocument()) {
            addExcludedNonLinkActionStructure(document);
            ToXMLContentHandler handler = new ToXMLContentHandler();
            PDFParserConfig config = new PDFParserConfig();
            config.setExtractMarkedContent(true);

            PDFMarkedContent2XHTML.process(document, handler, new ParseContext(),
                    new Metadata(), config, null, 2);

            String xml = handler.toString();
            assertContains(
                    "<a href=\"https://allowed-parent.invalid/\">ALLOWED_CHILD</a>",
                    xml);
            assertFalse(xml.contains("https://excluded-child.invalid/"));
        }
    }

    @Test
    public void testSamePageExcludedNonLinkActionDoesNotReplaceLinkOwnedUri()
            throws Exception {
        try (PDDocument document = buildPageOverrideDocument()) {
            addSamePageExcludedNonLinkActionStructure(document);
            ToXMLContentHandler handler = new ToXMLContentHandler();
            PDFParserConfig config = new PDFParserConfig();
            config.setExtractMarkedContent(true);

            PDFMarkedContent2XHTML.process(document, handler, new ParseContext(),
                    new Metadata(), config, null, 2);

            String xml = handler.toString();
            assertContains(
                    "<a href=\"https://allowed-parent.invalid/\">ALLOWED_CHILD</a>",
                    xml);
            assertFalse(xml.contains("https://excluded-same-page-child.invalid/"));
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

    @Test
    public void testAllowedPageLinkWithoutTextRetainsUri() throws Exception {
        try (PDDocument document = buildPageOverrideDocument()) {
            addAllowedEmptyLinkStructure(document);
            ToXMLContentHandler handler = new ToXMLContentHandler();
            PDFParserConfig config = new PDFParserConfig();
            config.setExtractMarkedContent(true);

            PDFMarkedContent2XHTML.process(document, handler, new ParseContext(),
                    new Metadata(), config, null, 2);

            assertContains("href=\"https://empty-allowed.invalid/\"",
                    handler.toString());
        }
    }

    @Test
    public void testExcludedAnnotationPageSuppressesUriOnlyLink() throws Exception {
        try (PDDocument document = buildPageOverrideDocument()) {
            addObjectReferenceLinkStructure(
                    document, 5, "https://excluded-annotation.invalid/");
            ToXMLContentHandler handler = new ToXMLContentHandler();
            PDFParserConfig config = new PDFParserConfig();
            config.setExtractMarkedContent(true);

            PDFMarkedContent2XHTML.process(document, handler, new ParseContext(),
                    new Metadata(), config, null, 2);

            assertFalse(handler.toString().contains(
                    "https://excluded-annotation.invalid/"));
        }
    }

    @Test
    public void testAnalysisPageLimitSuppressesDirectUriOnlyLink() throws Exception {
        try (PDDocument document = buildPageOverrideDocument()) {
            addDirectPageLinkStructure(
                    document, 5, "https://excluded-analysis-page.invalid/");
            ToXMLContentHandler handler = new ToXMLContentHandler();
            PDFParserConfig config = new PDFParserConfig();
            config.setExtractMarkedContent(true);
            config.setMaxPages(1);

            PDFMarkedContent2XHTML.process(document, handler, new ParseContext(),
                    new Metadata(), config, null, -1);

            assertFalse(handler.toString().contains(
                    "https://excluded-analysis-page.invalid/"));
        }
    }

    @Test
    public void testAllowedAnnotationPageRetainsUriOnlyLink() throws Exception {
        try (PDDocument document = buildPageOverrideDocument()) {
            addObjectReferenceLinkStructure(
                    document, 0, "https://allowed-annotation.invalid/");
            ToXMLContentHandler handler = new ToXMLContentHandler();
            PDFParserConfig config = new PDFParserConfig();
            config.setExtractMarkedContent(true);

            PDFMarkedContent2XHTML.process(document, handler, new ParseContext(),
                    new Metadata(), config, null, 2);

            assertContains("href=\"https://allowed-annotation.invalid/\"",
                    handler.toString());
        }
    }

    @Test
    public void testCyclicPageTreeFailsClosed() throws Exception {
        try (PDDocument document = Loader.loadPDF(buildCyclicPageTreePdf())) {
            PDFParserConfig config = new PDFParserConfig();
            config.setExtractMarkedContent(true);

            assertThrows(TikaException.class,
                    () -> PDFMarkedContent2XHTML.process(
                            document, new ToXMLContentHandler(), new ParseContext(),
                            new Metadata(), config, null));
        }
    }

    @Test
    public void testRepeatedStructureObjectFailsClosed() throws Exception {
        try (PDDocument document = Loader.loadPDF(buildRepeatedStructureObjectPdf())) {
            PDFParserConfig config = new PDFParserConfig();
            config.setExtractMarkedContent(true);

            assertThrows(TikaException.class,
                    () -> PDFMarkedContent2XHTML.process(
                            document, new ToXMLContentHandler(), new ParseContext(),
                            new Metadata(), config, null));
        }
    }

    private static byte[] buildCyclicPageTreePdf() {
        StringBuilder pdf = new StringBuilder();
        pdf.append("%PDF-1.4\n");
        int[] offsets = new int[5];
        offsets[1] = pdf.length();
        pdf.append("1 0 obj\n")
                .append("<< /Type /Catalog /Pages 2 0 R /StructTreeRoot 4 0 R >>\n")
                .append("endobj\n");
        offsets[2] = pdf.length();
        pdf.append("2 0 obj\n")
                .append("<< /Type /Pages /Kids [2 0 R] /Count 1 >>\n")
                .append("endobj\n");
        offsets[3] = pdf.length();
        pdf.append("3 0 obj\n")
                .append("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] ")
                .append("/Resources << >> >>\n")
                .append("endobj\n");
        offsets[4] = pdf.length();
        pdf.append("4 0 obj\n<< /Type /StructTreeRoot /K [0] >>\nendobj\n");

        int xref = pdf.length();
        pdf.append("xref\n0 5\n")
                .append("0000000000 65535 f \n");
        for (int i = 1; i <= 4; i++) {
            pdf.append(String.format(
                    Locale.ROOT, "%010d 00000 n \n", offsets[i]));
        }
        pdf.append("trailer\n<< /Size 5 /Root 1 0 R >>\n")
                .append("startxref\n").append(xref).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] buildRepeatedStructureObjectPdf() {
        StringBuilder pdf = new StringBuilder();
        pdf.append("%PDF-1.7\n");
        int[] offsets = new int[7];
        offsets[1] = pdf.length();
        pdf.append("1 0 obj\n")
                .append("<< /Type /Catalog /Pages 2 0 R /StructTreeRoot 4 0 R >>\n")
                .append("endobj\n");
        offsets[2] = pdf.length();
        pdf.append("2 0 obj\n")
                .append("<< /Type /Pages /Kids [3 0 R] /Count 1 >>\n")
                .append("endobj\n");
        offsets[3] = pdf.length();
        pdf.append("3 0 obj\n")
                .append("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] ")
                .append("/Resources << >> /StructParents 0 >>\n")
                .append("endobj\n");
        offsets[4] = pdf.length();
        pdf.append("4 0 obj\n<< /Type /StructTreeRoot /K 5 0 R >>\nendobj\n");
        offsets[5] = pdf.length();
        pdf.append("5 0 obj\n")
                .append("<< /Type /StructElem /S /Div /Pg 3 0 R ")
                .append("/K [6 0 R 6 0 R] >>\n")
                .append("endobj\n");
        offsets[6] = pdf.length();
        pdf.append("6 0 obj\n")
                .append("<< /Type /StructElem /S /Div /Pg 3 0 R /K 0 >>\n")
                .append("endobj\n");

        int xref = pdf.length();
        pdf.append("xref\n0 7\n")
                .append("0000000000 65535 f \n");
        for (int i = 1; i <= 6; i++) {
            pdf.append(String.format(
                    Locale.ROOT, "%010d 00000 n \n", offsets[i]));
        }
        pdf.append("trailer\n<< /Size 7 /Root 1 0 R >>\n")
                .append("startxref\n").append(xref).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
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

    private static void addAllowedEmptyLinkStructure(PDDocument document) {
        COSArray pageRefs = (COSArray) document.getPages().getCOSObject()
                .getDictionaryObject(COSName.KIDS);

        COSDictionary action = new COSDictionary();
        action.setItem(COSName.S, COSName.URI);
        action.setString(COSName.URI, "https://empty-allowed.invalid/");
        COSDictionary linkTarget = new COSDictionary();
        linkTarget.setItem(COSName.A, action);

        COSDictionary allowedLink = new COSDictionary();
        allowedLink.setItem(COSName.S, COSName.getPDFName("Link"));
        allowedLink.setItem(COSName.PG, pageRefs.get(0));
        allowedLink.setItem(COSName.K, linkTarget);

        PDStructureTreeRoot root = new PDStructureTreeRoot();
        COSArray rootKids = new COSArray();
        rootKids.add(new COSObject(allowedLink));
        root.setK(rootKids);
        document.getDocumentCatalog().setStructureTreeRoot(root);
    }

    private static void addObjectReferenceLinkStructure(PDDocument document,
                                                        int pageIndex, String uri) {
        COSArray pageRefs = (COSArray) document.getPages().getCOSObject()
                .getDictionaryObject(COSName.KIDS);

        COSDictionary action = new COSDictionary();
        action.setItem(COSName.S, COSName.URI);
        action.setString(COSName.URI, uri);
        COSDictionary annotation = new COSDictionary();
        annotation.setItem(COSName.P, pageRefs.get(pageIndex));
        annotation.setItem(COSName.A, action);

        COSDictionary objectReference = new COSDictionary();
        objectReference.setItem(COSName.TYPE, COSName.OBJR);
        objectReference.setItem(COSName.OBJ, annotation);

        COSDictionary link = new COSDictionary();
        link.setItem(COSName.S, COSName.getPDFName("Link"));
        link.setItem(COSName.K, objectReference);

        PDStructureTreeRoot root = new PDStructureTreeRoot();
        COSArray rootKids = new COSArray();
        rootKids.add(new COSObject(link));
        root.setK(rootKids);
        document.getDocumentCatalog().setStructureTreeRoot(root);
    }

    private static void addDirectPageLinkStructure(PDDocument document,
                                                   int pageIndex, String uri) {
        COSArray pageRefs = (COSArray) document.getPages().getCOSObject()
                .getDictionaryObject(COSName.KIDS);

        COSDictionary action = new COSDictionary();
        action.setItem(COSName.S, COSName.URI);
        action.setString(COSName.URI, uri);
        COSDictionary linkTarget = new COSDictionary();
        linkTarget.setItem(COSName.A, action);

        COSDictionary link = new COSDictionary();
        link.setItem(COSName.S, COSName.getPDFName("Link"));
        link.setItem(COSName.PG, pageRefs.get(pageIndex));
        link.setItem(COSName.K, linkTarget);

        PDStructureTreeRoot root = new PDStructureTreeRoot();
        COSArray rootKids = new COSArray();
        rootKids.add(new COSObject(link));
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

    private static void addExcludedNonLinkActionStructure(PDDocument document) {
        COSArray pageRefs = (COSArray) document.getPages().getCOSObject()
                .getDictionaryObject(COSName.KIDS);

        COSDictionary allowedAction = new COSDictionary();
        allowedAction.setItem(COSName.S, COSName.URI);
        allowedAction.setString(COSName.URI, "https://allowed-parent.invalid/");
        COSDictionary allowedTarget = new COSDictionary();
        allowedTarget.setItem(COSName.A, allowedAction);

        COSDictionary excludedAction = new COSDictionary();
        excludedAction.setItem(COSName.S, COSName.URI);
        excludedAction.setString(COSName.URI, "https://excluded-child.invalid/");
        COSDictionary excludedTarget = new COSDictionary();
        excludedTarget.setItem(COSName.A, excludedAction);
        COSDictionary excludedChild = new COSDictionary();
        excludedChild.setItem(COSName.S, COSName.getPDFName("Div"));
        excludedChild.setItem(COSName.PG, pageRefs.get(5));
        excludedChild.setItem(COSName.K, excludedTarget);

        COSArray linkKids = new COSArray();
        linkKids.add(COSInteger.ZERO);
        linkKids.add(allowedTarget);
        linkKids.add(new COSObject(excludedChild));
        COSDictionary allowedLink = new COSDictionary();
        allowedLink.setItem(COSName.S, COSName.getPDFName("Link"));
        allowedLink.setItem(COSName.PG, pageRefs.get(0));
        allowedLink.setItem(COSName.K, linkKids);

        PDStructureTreeRoot root = new PDStructureTreeRoot();
        COSArray rootKids = new COSArray();
        rootKids.add(new COSObject(allowedLink));
        root.setK(rootKids);
        document.getDocumentCatalog().setStructureTreeRoot(root);
    }

    private static void addSamePageExcludedNonLinkActionStructure(PDDocument document) {
        COSArray pageRefs = (COSArray) document.getPages().getCOSObject()
                .getDictionaryObject(COSName.KIDS);

        COSDictionary allowedChild = new COSDictionary();
        allowedChild.setItem(COSName.S, COSName.P);
        allowedChild.setItem(COSName.PG, pageRefs.get(0));
        allowedChild.setItem(COSName.K, COSInteger.ZERO);

        COSDictionary allowedAction = new COSDictionary();
        allowedAction.setItem(COSName.S, COSName.URI);
        allowedAction.setString(COSName.URI, "https://allowed-parent.invalid/");
        COSDictionary allowedTarget = new COSDictionary();
        allowedTarget.setItem(COSName.A, allowedAction);

        COSDictionary excludedAction = new COSDictionary();
        excludedAction.setItem(COSName.S, COSName.URI);
        excludedAction.setString(
                COSName.URI, "https://excluded-same-page-child.invalid/");
        COSDictionary excludedTarget = new COSDictionary();
        excludedTarget.setItem(COSName.A, excludedAction);
        COSDictionary excludedChild = new COSDictionary();
        excludedChild.setItem(COSName.S, COSName.getPDFName("Div"));
        excludedChild.setItem(COSName.PG, pageRefs.get(5));
        excludedChild.setItem(COSName.K, excludedTarget);

        COSArray linkKids = new COSArray();
        linkKids.add(new COSObject(allowedChild));
        linkKids.add(allowedTarget);
        linkKids.add(new COSObject(excludedChild));
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
