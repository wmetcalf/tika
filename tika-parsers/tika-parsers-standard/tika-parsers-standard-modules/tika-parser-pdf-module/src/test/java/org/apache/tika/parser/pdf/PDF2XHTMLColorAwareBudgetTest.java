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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.image.ZXingCPPConfig;
import org.apache.tika.parser.image.ZXingCPPScanner;
import org.apache.tika.sax.ToXMLContentHandler;

public class PDF2XHTMLColorAwareBudgetTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    public void glyphsAreRejectedBeforeAnOversizedPageBufferIsBuilt() {
        PDF2XHTMLColorAware.GlyphBuffer buffer =
                new PDF2XHTMLColorAware.GlyphBuffer(2);

        buffer.add(1.0f, 1.0f, 10);
        buffer.add(2.0f, 1.0f, 20);
        buffer.add(3.0f, 1.0f, 30);

        assertEquals(2, buffer.glyphs().size());
        assertTrue(buffer.isTruncated());
    }

    @Test
    public void glyphColorClassificationFailuresAreRetainedUntilPageEnd() {
        PDF2XHTMLColorAware.GlyphBuffer buffer =
                new PDF2XHTMLColorAware.GlyphBuffer(2);

        buffer.markClassificationFailure();

        assertTrue(buffer.isClassificationFailed());
        buffer.clear();
        assertFalse(buffer.isClassificationFailed());
    }

    @Test
    public void scannerSecurityExceptionPropagatesAcrossColorQrBoundary()
            throws Exception {
        SecurityException denial =
                new SecurityException("simulated process policy denial");
        ZXingCPPConfig zxingConfig = new ZXingCPPConfig();
        zxingConfig.setEnabled(true);
        ParseContext context = new ParseContext();
        ZXingCPPScanner scanner = new ZXingCPPScanner(zxingConfig) {
            @Override
            public boolean hasZXingCPP() {
                throw new AssertionError("budgeted scans must not probe availability");
            }

            @Override
            public List<Result> scan(Path imagePath, ZXingCPPConfig config,
                                     ParseContext context) {
                throw new AssertionError("PDF must use the budget-aware scan overload");
            }

            @Override
            public List<Result> scan(Path imagePath, ZXingCPPConfig config,
                                     ParseContext actualContext, ScanBudget budget) {
                assertSame(context, actualContext);
                throw denial;
            }
        };

        try (PDDocument document = new PDDocument()) {
            PDF2XHTMLColorAware extractor = new PDF2XHTMLColorAware(
                    document, new ToXMLContentHandler(), context,
                    new Metadata(), new PDFParserConfig(), null,
                    scanner, zxingConfig);
            Field glyphBufferField =
                    PDF2XHTMLColorAware.class.getDeclaredField("pageGlyphs");
            glyphBufferField.setAccessible(true);
            PDF2XHTMLColorAware.GlyphBuffer buffer =
                    (PDF2XHTMLColorAware.GlyphBuffer)
                            glyphBufferField.get(extractor);
            for (int row = 0; row < 8; row++) {
                for (int column = 0; column < 8; column++) {
                    buffer.add(column * 4.0f, row * 4.0f,
                            (row + column) % 2 == 0 ? 0 : 255);
                }
            }

            Method scan =
                    PDF2XHTMLColorAware.class.getDeclaredMethod(
                            "scanPageForColorQR");
            scan.setAccessible(true);
            InvocationTargetException thrown =
                    assertThrows(InvocationTargetException.class,
                            () -> scan.invoke(extractor));

            assertSame(denial, thrown.getCause());
        }
    }

    @Test
    public void pagesShareOneColorQrProcessBudgetWithoutVersionProbes()
            throws Exception {
        Path invocationLog = temporaryDirectory.resolve("pdf-zxing-invocations.log");
        Path fakeScanner = temporaryDirectory.resolve("fake-zxing-reader");
        Files.writeString(fakeScanner, """
                #!/bin/sh
                if [ "$1" = "-version" ]; then
                    printf '%%s\\n' probe >> '%s'
                    exit 0
                fi
                if [ "$1" = "-json" ]; then
                    printf '%%s\\n' scan >> '%s'
                    exit 0
                fi
                exit 2
                """.formatted(invocationLog, invocationLog),
                StandardCharsets.UTF_8);
        fakeScanner.toFile().setExecutable(true);

        ZXingCPPConfig zxingConfig = new ZXingCPPConfig();
        zxingConfig.setEnabled(true);
        zxingConfig.setZxingPath(fakeScanner.toString());
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();

        try (PDDocument document = new PDDocument()) {
            PDF2XHTMLColorAware extractor = new PDF2XHTMLColorAware(
                    document, new ToXMLContentHandler(), context,
                    metadata, new PDFParserConfig(), null,
                    new ZXingCPPScanner(zxingConfig), zxingConfig);
            Field glyphBufferField =
                    PDF2XHTMLColorAware.class.getDeclaredField("pageGlyphs");
            glyphBufferField.setAccessible(true);
            PDF2XHTMLColorAware.GlyphBuffer buffer =
                    (PDF2XHTMLColorAware.GlyphBuffer)
                            glyphBufferField.get(extractor);
            Method scan =
                    PDF2XHTMLColorAware.class.getDeclaredMethod(
                            "scanPageForColorQR");
            scan.setAccessible(true);

            for (int page = 0; page < 5; page++) {
                addQualifyingGrid(buffer);
                scan.invoke(extractor);
                buffer.clear();
            }
        }

        List<String> invocations = Files.readAllLines(invocationLog);
        assertEquals(4, invocations.stream().filter("scan"::equals).count());
        assertEquals(0, invocations.stream().filter("probe"::equals).count());
        assertTrue(metadata
                .getValues(org.apache.tika.metadata.TikaCoreProperties
                        .TIKA_META_EXCEPTION_WARNING).length > 0);
    }

    private static void addQualifyingGrid(PDF2XHTMLColorAware.GlyphBuffer buffer) {
        for (int row = 0; row < 8; row++) {
            for (int column = 0; column < 8; column++) {
                buffer.add(column * 4.0f, row * 4.0f,
                        (row + column) % 2 == 0 ? 0 : 255);
            }
        }
    }
}
