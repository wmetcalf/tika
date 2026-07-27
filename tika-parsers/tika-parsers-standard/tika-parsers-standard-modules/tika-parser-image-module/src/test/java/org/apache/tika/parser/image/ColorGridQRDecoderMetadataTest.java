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
package org.apache.tika.parser.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.apache.tika.metadata.Barcode;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.metadata.writefilter.StandardMetadataLimiterFactory;
import org.apache.tika.parser.ParseContext;

public class ColorGridQRDecoderMetadataTest {

    private static final List<ZXingCPPScanner.Result> RESULTS = List.of(
            new ZXingCPPScanner.Result("/tmp/first.png", "first", null,
                    null, null, null, false),
            new ZXingCPPScanner.Result("/tmp/second.png", "second", "Code 128",
                    "7365636f6e64", "20x20 30x20 30x30 20x30", "H", true));

    @Test
    public void testLegacyFieldsAreAlignedWithoutLimiter() {
        Metadata metadata = new Metadata();

        ColorGridQRDecoder.emitBarcodes(RESULTS, metadata);

        assertArrayEquals(new String[]{"", "7365636f6e64"},
                metadata.getValues(Barcode.BARCODE_RAW_BYTES));
        assertArrayEquals(new String[]{"", "20x20 30x20 30x30 20x30"},
                metadata.getValues(Barcode.BARCODE_POSITION));
        assertArrayEquals(new String[]{"", "H"},
                metadata.getValues(Barcode.BARCODE_ERROR_CORRECTION_LEVEL));
    }

    @Test
    public void testCanonicalRecordsStayAlignedWithDefaultLimiter() {
        StandardMetadataLimiterFactory factory = new StandardMetadataLimiterFactory();
        Metadata metadata = new Metadata(factory.newInstance());

        ColorGridQRDecoder.emitBarcodes(RESULTS, metadata);

        assertArrayEquals(new String[]{
                "{\"value\":\"first\",\"format\":\"qrcode\",\"rawBytes\":\"\","
                        + "\"position\":\"\",\"errorCorrectionLevel\":\"\","
                        + "\"mirrored\":\"false\"}",
                "{\"value\":\"second\",\"format\":\"code_128\","
                        + "\"rawBytes\":\"7365636f6e64\","
                        + "\"position\":\"20x20 30x20 30x30 20x30\","
                        + "\"errorCorrectionLevel\":\"H\",\"mirrored\":\"true\"}"
        }, metadata.getValues(Barcode.BARCODE_RECORD));
        assertArrayEquals(new String[]{"", "7365636f6e64"},
                metadata.getValues(Barcode.BARCODE_RAW_BYTES));
        assertArrayEquals(new String[]{"", "20x20 30x20 30x30 20x30"},
                metadata.getValues(Barcode.BARCODE_POSITION));
    }

    @Test
    public void testScannerFailuresPropagateToFormatCaller() {
        List<ColorGridQRDecoder.Cell> row = new ArrayList<>();
        for (int i = 0; i < ColorGridQRDecoder.MIN_COLS; i++) {
            row.add(new ColorGridQRDecoder.Cell((i & 1) == 0));
        }
        List<List<ColorGridQRDecoder.Cell>> grid = new ArrayList<>();
        for (int i = 0; i < ColorGridQRDecoder.MIN_LINES; i++) {
            grid.add(row);
        }
        ZXingCPPConfig config = new ZXingCPPConfig();
        config.setEnabled(true);

        assertThrows(RuntimeException.class, () -> ColorGridQRDecoder.decode(
                List.of(grid), new ThrowingScanner(), config, new ParseContext()));
    }

    @Test
    public void testBarcodeMetadataResultCountIsBounded() {
        List<ZXingCPPScanner.Result> results = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            results.add(new ZXingCPPScanner.Result(
                    "/tmp/code.png", "value-" + i, "QR Code",
                    "", "", "", false));
        }
        Metadata metadata = new Metadata();

        ColorGridQRDecoder.emitBarcodes(results, metadata);

        assertEquals(256, metadata.getValues(Barcode.BARCODE_RECORD).length);
        assertEquals(256, metadata.getValues(Barcode.BARCODE_VALUE).length);
        assertEquals("Barcode metadata retention limit reached; additional "
                        + "barcode results were skipped",
                metadata.get(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING));
        assertNotNull(metadata.get("ExploitClass"));
    }

    @Test
    public void testStoppedBarcodeMetadataDoesNotInspectLaterResults() {
        Metadata metadata = new Metadata();
        metadata.set(Barcode.BARCODE_LIMIT_REACHED, true);

        assertDoesNotThrow(() -> BarcodeMetadataUtil.addResult(
                metadata, new GetterTrapResult(), "qrcode"));
    }

    private static final class GetterTrapResult extends ZXingCPPScanner.Result {
        private GetterTrapResult() {
            super("/tmp/trap.png", "unused", "QR Code", "", "", "", false);
        }

        @Override
        public String getText() {
            throw new AssertionError("result fields must not be read after the cap");
        }
    }

    private static final class ThrowingScanner extends ZXingCPPScanner {
        @Override
        public boolean hasZXingCPP() {
            return true;
        }

        @Override
        public List<Result> scan(Path imagePath, ZXingCPPConfig config,
                                 ParseContext context) {
            throw new ScanException("simulated scanner failure");
        }
    }
}
