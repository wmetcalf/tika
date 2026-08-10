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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;

import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

/**
 * Regression tests for the per-document image-processing budget
 * ({@link PDFParserConfig#setMaxImagesPerDocument(int)}).
 *
 * <p>Motivation: with {@code extractUniqueInlineImagesOnly=false} (the
 * deployed configuration), EVERY draw of an image XObject -- not just each
 * distinct image -- is fully rasterized, color-converted, QR-scanned and
 * perceptually hashed. That work was previously unbounded per document, so a
 * PDF containing many image draws could run arbitrarily long. Because a
 * repeated {@code Do} operator referencing one already-defined XObject costs
 * only a few bytes, the number of draws is cheaply attacker-controlled and is
 * NOT bounded by the separate {@code maxPages} cap.
 */
public class PDFImageProcessingBudgetTest {

    /**
     * Per-image cost charged by the stand-in extractor below, standing in for
     * the real rasterize/CMYK-convert/QR/hash pipeline. Using a fixed, small
     * synthetic cost keeps the test fast and deterministic instead of
     * depending on real image-decode timing (which varies wildly by hardware
     * -- the very thing that made the original default miscalibrated).
     */
    private static final long SYNTHETIC_COST_PER_IMAGE_MS = 10L;

    /**
     * Builds a single-page PDF that draws one tiny in-memory image
     * {@code drawCount} times. One image object, many draws -- the shape a
     * count-based budget has to bound.
     */
    private static byte[] buildPdfWithRepeatedImageDraws(int drawCount) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, 0xFF0000);
            img.setRGB(1, 0, 0x00FF00);
            img.setRGB(0, 1, 0x0000FF);
            img.setRGB(1, 1, 0xFFFFFF);
            PDImageXObject xobject = LosslessFactory.createFromImage(doc, img);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                for (int i = 0; i < drawCount; i++) {
                    cs.drawImage(xobject, 10 + (i % 50), 10 + (i % 50), 4, 4);
                }
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    /** Counts processed inline images and charges a fixed cost for each. */
    private static class CountingEmbeddedDocumentExtractor implements EmbeddedDocumentExtractor {
        private final AtomicInteger processed = new AtomicInteger();
        private final long costMs;

        CountingEmbeddedDocumentExtractor(long costMs) {
            this.costMs = costMs;
        }

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return true;
        }

        @Override
        public void parseEmbedded(TikaInputStream stream, ContentHandler handler, Metadata metadata,
                                  ParseContext ctx, boolean outputHtml) throws IOException {
            processed.incrementAndGet();
            if (costMs > 0) {
                try {
                    Thread.sleep(costMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
        }

        int processedCount() {
            return processed.get();
        }
    }

    private static ParseContext contextFor(int maxImagesPerDocument,
                                           CountingEmbeddedDocumentExtractor extractor) {
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setExtractInlineImages(true);
        // The deployed configuration: every draw counts, not just unique images.
        pdfConfig.setExtractUniqueInlineImagesOnly(false);
        pdfConfig.setMaxImagesPerDocument(maxImagesPerDocument);
        // The OCR module is not on this module's test classpath; without this the
        // page-end OCR hook NPEs on a null ocrParser.
        OcrConfig ocrConfig = new OcrConfig();
        ocrConfig.setStrategy(OcrConfig.Strategy.NO_OCR);
        pdfConfig.setOcr(ocrConfig);

        ParseContext context = new ParseContext();
        context.set(PDFParserConfig.class, pdfConfig);
        context.set(EmbeddedDocumentExtractor.class, extractor);
        return context;
    }

    /** Collects the RESOURCE_NAME_KEY of every extracted image, in order. */
    private static class NameCollectingExtractor implements EmbeddedDocumentExtractor {
        private final java.util.List<String> names = new java.util.ArrayList<>();

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return true;
        }

        @Override
        public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                  Metadata metadata, ParseContext ctx, boolean outputHtml) {
            names.add(metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY));
        }
    }

    private static byte[] buildPdfWithDistinctImagesEachDrawnTwice(int distinct)
            throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            java.util.List<PDImageXObject> xs = new java.util.ArrayList<>();
            for (int i = 0; i < distinct; i++) {
                BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
                img.setRGB(0, 0, 0x110000 * (i + 1));
                xs.add(LosslessFactory.createFromImage(doc, img));
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                for (int i = 0; i < distinct; i++) {
                    cs.drawImage(xs.get(i), 10 + i * 6, 10, 4, 4);
                    cs.drawImage(xs.get(i), 10 + i * 6, 20, 4, 4);
                }
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    /**
     * The opt-in budget must not renumber extracted images on the DEFAULT (-1) path.
     *
     * <p>Regression guard: the budget was charged to {@code imageCounter}, which is also
     * what assigns IMAGE-&lt;n&gt; names, so repeat draws consumed names. Measured before
     * the fix with 3 images x 3 draws: image-0/image-3/image-6 instead of
     * image-0/image-1/image-2 -- with the budget DISABLED, i.e. an opt-in DoS backstop
     * silently changed default output. Budget accounting now has its own counter.
     */
    @Test
    public void testDisabledBudgetDoesNotRenumberExtractedImages() throws Exception {
        NameCollectingExtractor extractor = new NameCollectingExtractor();
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setExtractInlineImages(true);
        pdfConfig.setExtractUniqueInlineImagesOnly(false);
        pdfConfig.setMaxImagesPerDocument(-1); // the shipped default: unlimited
        OcrConfig ocrConfig = new OcrConfig();
        ocrConfig.setStrategy(OcrConfig.Strategy.NO_OCR);
        pdfConfig.setOcr(ocrConfig);
        ParseContext context = new ParseContext();
        context.set(PDFParserConfig.class, pdfConfig);
        context.set(EmbeddedDocumentExtractor.class, extractor);

        byte[] pdf = buildPdfWithDistinctImagesEachDrawnTwice(3);
        try (TikaInputStream tis = TikaInputStream.get(new ByteArrayInputStream(pdf))) {
            new AutoDetectParser().parse(tis, new BodyContentHandler(-1), new Metadata(),
                    context);
        }

        java.util.Set<String> distinctNames = new java.util.HashSet<>(extractor.names);
        assertEquals(3, distinctNames.size(),
                "3 distinct image objects must yield 3 distinct resource names; got "
                        + extractor.names);
        assertTrue(distinctNames.contains("image-0.png") && distinctNames.contains("image-1.png")
                        && distinctNames.contains("image-2.png"),
                "names must stay DENSE (image-0/1/2) on the default unlimited path -- a "
                        + "sparse sequence means the budget counter is naming images; got "
                        + extractor.names);
    }

    /**
     * A repeat draw must reuse the name the object was first published under.
     *
     * <p>Regression guard for cross-labelling: {@code imageNumber} stayed 0 on the
     * repeat-draw path, so every redraw was published as image-0 -- meaning
     * {@code <img src="embedded:image-0.png">} resolved to a DIFFERENT image's bytes and
     * multiple attachments claimed one resource name. (Pre-existing upstream; the fix is
     * one line in a branch that already edits this block.)
     */
    @Test
    public void testRepeatDrawReusesItsOwnNameRatherThanImageZero() throws Exception {
        NameCollectingExtractor extractor = new NameCollectingExtractor();
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setExtractInlineImages(true);
        pdfConfig.setExtractUniqueInlineImagesOnly(false);
        pdfConfig.setMaxImagesPerDocument(-1);
        OcrConfig ocrConfig = new OcrConfig();
        ocrConfig.setStrategy(OcrConfig.Strategy.NO_OCR);
        pdfConfig.setOcr(ocrConfig);
        ParseContext context = new ParseContext();
        context.set(PDFParserConfig.class, pdfConfig);
        context.set(EmbeddedDocumentExtractor.class, extractor);

        byte[] pdf = buildPdfWithDistinctImagesEachDrawnTwice(2);
        try (TikaInputStream tis = TikaInputStream.get(new ByteArrayInputStream(pdf))) {
            new AutoDetectParser().parse(tis, new BodyContentHandler(-1), new Metadata(),
                    context);
        }

        long imageZero = extractor.names.stream()
                .filter(n -> "image-0.png".equals(n)).count();
        assertEquals(2, imageZero,
                "only the FIRST object's two draws may be named image-0.png; more means "
                        + "the second object's redraw was cross-labelled as image-0; got "
                        + extractor.names);
    }

    @Test
    public void testRepeatedImageDrawsAreBoundedAndFast() throws Exception {
        final int drawCount = 4000;
        final int cap = 50;
        byte[] pdf = buildPdfWithRepeatedImageDraws(drawCount);

        CountingEmbeddedDocumentExtractor extractor =
                new CountingEmbeddedDocumentExtractor(SYNTHETIC_COST_PER_IMAGE_MS);
        Metadata metadata = new Metadata();

        // Unbounded, this is 4000 * 10ms = ~40s of work; bounded it is ~0.5s.
        // The timeout is the actual regression assertion.
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            try (TikaInputStream is = TikaInputStream.get(new ByteArrayInputStream(pdf))) {
                new AutoDetectParser().parse(is, new BodyContentHandler(-1), metadata,
                        contextFor(cap, extractor));
            }
        }, "parse should be bounded by maxImagesPerDocument, not by the draw count");

        assertTrue(extractor.processedCount() <= cap,
                "expected at most " + cap + " images processed, got "
                        + extractor.processedCount() + " (budget not enforced)");
        assertEquals("true", metadata.get(TikaCoreProperties.TRUNCATED_METADATA),
                "hitting the image budget must surface a truncation signal");
    }

    @Test
    public void testUnlimitedImageBudgetIsAnExplicitOptIn() throws Exception {
        final int drawCount = 30;
        byte[] pdf = buildPdfWithRepeatedImageDraws(drawCount);

        // No synthetic cost here -- this test is about the opt-out being honored,
        // not about timing.
        CountingEmbeddedDocumentExtractor extractor = new CountingEmbeddedDocumentExtractor(0);
        Metadata metadata = new Metadata();

        try (TikaInputStream is = TikaInputStream.get(new ByteArrayInputStream(pdf))) {
            new AutoDetectParser().parse(is, new BodyContentHandler(-1), metadata,
                    contextFor(-1, extractor));
        }

        assertEquals(drawCount, extractor.processedCount(),
                "-1 must mean genuinely unbounded, processing every draw");
        assertEquals(null, metadata.get(TikaCoreProperties.TRUNCATED_METADATA),
                "an explicit opt-out must not report truncation");
    }
}
