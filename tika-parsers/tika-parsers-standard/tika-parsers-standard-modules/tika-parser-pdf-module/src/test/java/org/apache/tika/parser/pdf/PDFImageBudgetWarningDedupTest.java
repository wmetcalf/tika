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

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;

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
 * Regression tests for the exactly-once truncation warning emitted when the
 * per-document image budget is exhausted.
 *
 * <p>Motivation -- a fix that was empirically WORSE than no fix. The first
 * version of this dedup gated on
 * {@code parentMetadata.get("X-TIKA:PDF:image-budget-exceeded") == null} after
 * writing that same raw String key. Because the key carries the reserved
 * {@code X-TIKA:} prefix, {@code Metadata.set(String,String)} silently drops
 * the write, so the read always returned null and the warning was appended on
 * EVERY draw past the cap rather than once. Metadata's value-array append is
 * O(N) per add, making the supposedly-protected path O(N^2): measured at
 * 320,000 excess draws it took ~17-57s bounded versus ~13s completely
 * unbounded.
 *
 * <p>The two properties that must hold, and that these tests pin:
 * <ol>
 *   <li>the signal is recorded exactly once, no matter how many draws are
 *       skipped; and</li>
 *   <li>per-skipped-draw bookkeeping is O(1), so total cost stays linear.</li>
 * </ol>
 */
public class PDFImageBudgetWarningDedupTest {

    private static byte[] buildPdfWithRepeatedImageDraws(int drawCount) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, 0xFF0000);
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

    /** Zero-cost extractor: isolates bookkeeping cost from real image work. */
    private static class NoOpEmbeddedDocumentExtractor implements EmbeddedDocumentExtractor {
        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return true;
        }

        @Override
        public void parseEmbedded(TikaInputStream stream, ContentHandler handler, Metadata metadata,
                                  ParseContext ctx, boolean outputHtml) {
            // intentionally free
        }
    }

    private static ParseContext contextFor(int maxImagesPerDocument) {
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setExtractInlineImages(true);
        pdfConfig.setExtractUniqueInlineImagesOnly(false);
        pdfConfig.setMaxImagesPerDocument(maxImagesPerDocument);
        // The OCR module is not on this module's test classpath; without this the
        // page-end OCR hook NPEs on a null ocrParser.
        OcrConfig ocrConfig = new OcrConfig();
        ocrConfig.setStrategy(OcrConfig.Strategy.NO_OCR);
        pdfConfig.setOcr(ocrConfig);

        ParseContext context = new ParseContext();
        context.set(PDFParserConfig.class, pdfConfig);
        context.set(EmbeddedDocumentExtractor.class, new NoOpEmbeddedDocumentExtractor());
        return context;
    }

    private static Metadata parse(byte[] pdf, int cap) throws Exception {
        Metadata metadata = new Metadata();
        try (TikaInputStream is = TikaInputStream.get(new ByteArrayInputStream(pdf))) {
            new AutoDetectParser().parse(is, new BodyContentHandler(-1), metadata,
                    contextFor(cap));
        }
        return metadata;
    }

    @Test
    public void testWarningAndTruncatedFlagAreRecordedExactlyOnce() throws Exception {
        // 3000 draws with a cap of 25 => 2975 draws skipped past the budget.
        // Pre-fix this produced 2975 duplicate warnings; it must produce one.
        byte[] pdf = buildPdfWithRepeatedImageDraws(3000);
        Metadata metadata = parse(pdf, 25);

        assertEquals("true", metadata.get(TikaCoreProperties.TRUNCATED_METADATA),
                "truncation flag must be set once the image budget is exhausted "
                        + "(a reserved-prefix String key would be silently dropped here)");

        String[] warnings = metadata.getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING);
        long imageBudgetWarnings = java.util.Arrays.stream(warnings)
                .filter(w -> w != null && w.toLowerCase(java.util.Locale.ROOT).contains("image"))
                .count();

        assertEquals(1, imageBudgetWarnings,
                "the image-budget warning must be emitted exactly once, not once per "
                        + "skipped draw (got " + imageBudgetWarnings + " of "
                        + warnings.length + " total warnings)");
    }

    /**
     * Pins the O(1)-per-skipped-draw property at a scale where the O(N^2) bug
     * was unmistakable.
     *
     * <p>Deliberately NOT a wall-clock ratio assertion. An earlier version of
     * this test compared elapsed time at 80k vs 320k draws and demanded the
     * ratio stay under ~4x-7x; it passed in isolation but failed at 11.3x
     * inside the full module suite. The reason is that PDFBox's own parsing of
     * a 320,000-operator content stream is itself somewhat superlinear, so a
     * ratio conflates "our bookkeeping regressed" with "PDFBox costs more on a
     * bigger stream" -- and the threshold separating them turned out to depend
     * on machine load. Calibrating a timing threshold is exactly the mistake
     * that produced the miscalibrated image cap this class exists to guard.
     *
     * <p>Instead this asserts the thing that is actually true by construction
     * and is fully deterministic: the number of recorded warnings must not
     * grow with the number of skipped draws. The O(N^2) blowup was caused by
     * appending one warning per skipped draw into Tika's Metadata (whose
     * value-array append is O(N)); if the count stays at 1 across a 4x
     * increase in draws, there is no per-draw accumulation and the cost is
     * O(1) per draw by definition. A generous absolute timeout is retained as
     * a coarse backstop -- the buggy build took ~55s on this input, the fixed
     * build a few seconds.
     */
    @Test
    public void testExcessDrawBookkeepingDoesNotGrowWithSkippedDrawCount() throws Exception {
        final int cap = 10;
        final int smallDraws = 80_000;
        final int largeDraws = 320_000;   // 4x

        byte[] smallPdf = buildPdfWithRepeatedImageDraws(smallDraws);
        byte[] largePdf = buildPdfWithRepeatedImageDraws(largeDraws);

        int[] counts = new int[2];
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            counts[0] = imageWarningCount(parse(smallPdf, cap));
            counts[1] = imageWarningCount(parse(largePdf, cap));
        }, "bounded image processing must not take anywhere near the buggy build's "
                + "~55s at this scale");

        assertEquals(1, counts[0],
                smallDraws + " draws (" + (smallDraws - cap) + " skipped) must record "
                        + "exactly one warning");
        assertEquals(counts[0], counts[1],
                "warning count must not grow with skipped-draw count: " + smallDraws
                        + " draws -> " + counts[0] + " warning(s), but " + largeDraws
                        + " draws (4x) -> " + counts[1] + " warning(s). Growth here means "
                        + "one Metadata append per skipped draw, i.e. the O(N^2) regression.");
    }

    private static int imageWarningCount(Metadata metadata) {
        String[] warnings = metadata.getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING);
        return (int) java.util.Arrays.stream(warnings)
                .filter(w -> w != null && w.toLowerCase(java.util.Locale.ROOT).contains("image"))
                .count();
    }
}
