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
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.image.ZXingCPPConfig;
import org.apache.tika.parser.image.ZXingCPPScanner;
import org.apache.tika.sax.ToXMLContentHandler;

public class PDF2XHTMLColorAwareBudgetTest {

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
        ZXingCPPScanner scanner = new ZXingCPPScanner(zxingConfig) {
            @Override
            public boolean hasZXingCPP() {
                return true;
            }

            @Override
            public List<Result> scan(Path imagePath, ZXingCPPConfig config,
                                     ParseContext context) {
                throw denial;
            }
        };

        try (PDDocument document = new PDDocument()) {
            PDF2XHTMLColorAware extractor = new PDF2XHTMLColorAware(
                    document, new ToXMLContentHandler(), new ParseContext(),
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
}
