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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDPageAdditionalActions;
import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.exception.AccessPermissionException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.PagedText;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;

public class PDFParserSlicingSecurityTest {

    private static final String ATTACHMENT_NAME = "security-review.txt";
    private static final String FINAL_PAGE_MARKER = "SECURITY_FINAL_PAGE_MARKER";

    @Test
    public void testSixPageOutputIsSlicedButWholeDocumentIsAnalyzed() throws Exception {
        ParseResult result = parse(buildPdf(6, false), null);

        assertTrue(result.text.contains("page-1"));
        assertTrue(result.text.contains("page-2"));
        assertFalse(result.text.contains("page-3"));
        assertFalse(result.text.contains(FINAL_PAGE_MARKER));
        assertEquals("6", result.metadata.get(PagedText.N_PAGES));
        assertTrue(result.embeddedNames.contains(ATTACHMENT_NAME));
        assertEquals(6, result.metadata.getValues(
                org.apache.tika.metadata.PDF.CHARACTERS_PER_PAGE).length);
        assertTrue(result.embeddedTypes.contains("application/javascript"),
                "the final-page JavaScript action must still reach embedded analysis: "
                        + result.embeddedTypes);
    }

    @Test
    public void testSixPageDocumentStillEnforcesOriginalAccessPolicy() throws Exception {
        byte[] restricted = buildPdf(6, true);

        assertThrows(AccessPermissionException.class,
                () -> parse(restricted,
                        PDFParserConfig.AccessCheckMode.IGNORE_ACCESSIBILITY_ALLOWANCE));
    }

    @Test
    public void testFivePageDocumentIsNotOutputLimited() throws Exception {
        ParseResult result = parse(buildPdf(5, false), null);

        assertTrue(result.text.contains("page-1"));
        assertTrue(result.text.contains("page-4"));
        assertTrue(result.text.contains(FINAL_PAGE_MARKER));
        assertEquals("5", result.metadata.get(PagedText.N_PAGES));
    }

    @Test
    public void testDefaultAnalysisPageLimitIsSecurityVisible() throws Exception {
        ParseResult result = parse(buildPdf(101, false), null);

        assertTrue(java.util.Arrays.stream(result.metadata.getValues(
                        TikaCoreProperties.TIKA_META_EXCEPTION_WARNING))
                .anyMatch(value -> value.contains("PDF analysis page limit")));
        assertNotNull(result.metadata.get("ExploitClass"),
                "skipped later pages must not look fully analyzed");
        assertFalse(result.embeddedTypes.contains("application/javascript"),
                "the page-101 action should be beyond the bounded analysis window");
    }

    @Test
    public void testPageXmpExtractionHonorsAnalysisLimit() throws Exception {
        AtomicInteger parsedXmpStreams = new AtomicInteger();
        ParseContext context = new ParseContext();
        PDFParserConfig config = new PDFParserConfig();
        config.setMaxPages(1);
        config.getOcr().setStrategy(OcrConfig.Strategy.NO_OCR);
        context.set(PDFParserConfig.class, config);
        context.set(Parser.class, new XmpSupportingParser());
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return true;
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext parseContext,
                                      boolean outputHtml) {
                if ("application/rdf+xml".equals(metadata.get(Metadata.CONTENT_TYPE))) {
                    parsedXmpStreams.incrementAndGet();
                }
            }
        });

        try (TikaInputStream stream = TikaInputStream.get(buildPdfWithPageXmp(3))) {
            new PDFParser().parse(stream, new BodyContentHandler(-1),
                    new Metadata(), context);
        }

        assertEquals(1, parsedXmpStreams.get(),
                "page-level XMP parsing must stop at the PDF analysis page limit");
    }

    @Test
    public void testUnlimitedAnalysisRemainsAnExplicitOptIn() throws Exception {
        ParseResult result = parse(buildPdf(101, false), null, -1);

        assertTrue(result.embeddedTypes.contains("application/javascript"),
                "maxPages=-1 must preserve explicit unlimited whole-document analysis");
    }

    @Test
    public void testLegacySerializedConfigRestoresSecurePageDefault() throws Exception {
        PDFParserConfig config = new PDFParserConfig();
        Field maxPages = PDFParserConfig.class.getDeclaredField("maxPages");
        maxPages.setAccessible(true);
        maxPages.setInt(config, 0);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(config);
        }
        PDFParserConfig restored;
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (PDFParserConfig) input.readObject();
        }

        assertEquals(PDFParserConfig.DEFAULT_MAX_PAGES, restored.getMaxPages(),
                "streams created before maxPages existed must not deserialize as unlimited");
    }

    private static ParseResult parse(byte[] pdf,
                                     PDFParserConfig.AccessCheckMode accessCheckMode)
            throws Exception {
        return parse(pdf, accessCheckMode, null);
    }

    private static ParseResult parse(byte[] pdf,
                                     PDFParserConfig.AccessCheckMode accessCheckMode,
                                     Integer maxPages)
            throws Exception {
        Metadata metadata = new Metadata();
        BodyContentHandler handler = new BodyContentHandler(-1);
        RecordingEmbeddedExtractor embedded = new RecordingEmbeddedExtractor();
        ParseContext context = new ParseContext();
        PDFParserConfig config = new PDFParserConfig();
        config.getOcr().setStrategy(OcrConfig.Strategy.NO_OCR);
        config.setExtractActions(true);
        if (maxPages != null) {
            config.setMaxPages(maxPages);
        }
        if (accessCheckMode != null) {
            config.setAccessCheckMode(accessCheckMode);
        }
        context.set(PDFParserConfig.class, config);
        context.set(EmbeddedDocumentExtractor.class, embedded);
        try (TikaInputStream stream = TikaInputStream.get(pdf)) {
            new PDFParser().parse(stream, handler, metadata, context);
        }
        return new ParseResult(handler.toString(), metadata,
                embedded.names, embedded.contentTypes);
    }

    private static byte[] buildPdf(int pageCount, boolean restrictExtraction) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
                PDPage page = new PDPage();
                page.setResources(new PDResources());
                document.addPage(page);
                try (PDPageContentStream contents = new PDPageContentStream(document, page)) {
                    contents.beginText();
                    contents.setFont(font, 12);
                    contents.newLineAtOffset(72, 720);
                    contents.showText(pageNumber == pageCount
                            ? FINAL_PAGE_MARKER : "page-" + pageNumber);
                    contents.endText();
                }
                if (pageNumber == pageCount) {
                    PDPageAdditionalActions actions = new PDPageAdditionalActions();
                    actions.setO(new PDActionJavaScript(
                            "app.alert('security-analysis-reached-page-six')"));
                    page.setActions(actions);
                }
            }

            addAttachment(document);
            if (restrictExtraction) {
                AccessPermission permission = new AccessPermission();
                permission.setCanExtractContent(false);
                permission.setCanExtractForAccessibility(false);
                StandardProtectionPolicy policy =
                        new StandardProtectionPolicy("security-owner", "", permission);
                policy.setEncryptionKeyLength(128);
                document.protect(policy);
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] buildPdfWithPageXmp(int pageCount) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
                PDPage page = new PDPage();
                String xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">page-"
                        + pageNumber + "</x:xmpmeta>";
                page.setMetadata(new PDMetadata(document, new ByteArrayInputStream(
                        xmp.getBytes(StandardCharsets.UTF_8))));
                document.addPage(page);
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static void addAttachment(PDDocument document) throws IOException {
        byte[] bytes = "security-review-attachment".getBytes(StandardCharsets.UTF_8);
        PDEmbeddedFile embeddedFile = new PDEmbeddedFile(
                document, new ByteArrayInputStream(bytes));
        embeddedFile.setSubtype("text/plain");
        embeddedFile.setSize(bytes.length);
        PDComplexFileSpecification specification = new PDComplexFileSpecification();
        specification.setFile(ATTACHMENT_NAME);
        specification.setEmbeddedFile(embeddedFile);
        PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
        Map<String, PDComplexFileSpecification> names = new LinkedHashMap<>();
        names.put(ATTACHMENT_NAME, specification);
        tree.setNames(names);
        PDDocumentNameDictionary dictionary =
                new PDDocumentNameDictionary(document.getDocumentCatalog());
        dictionary.setEmbeddedFiles(tree);
        document.getDocumentCatalog().setNames(dictionary);
    }

    private record ParseResult(String text, Metadata metadata,
                               List<String> embeddedNames,
                               List<String> embeddedTypes) {
    }

    private static class RecordingEmbeddedExtractor implements EmbeddedDocumentExtractor {
        private final List<String> names = new ArrayList<>();
        private final List<String> contentTypes = new ArrayList<>();

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return true;
        }

        @Override
        public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                  Metadata metadata, ParseContext context,
                                  boolean outputHtml) throws SAXException, IOException {
            names.add(metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY));
            contentTypes.add(metadata.get(Metadata.CONTENT_TYPE));
        }
    }

    private static class XmpSupportingParser implements Parser {
        private static final long serialVersionUID = 1L;

        @Override
        public Set<MediaType> getSupportedTypes(ParseContext context) {
            return Set.of(MediaType.application("rdf+xml"));
        }

        @Override
        public void parse(TikaInputStream stream, ContentHandler handler,
                          Metadata metadata, ParseContext context) {
        }
    }
}
