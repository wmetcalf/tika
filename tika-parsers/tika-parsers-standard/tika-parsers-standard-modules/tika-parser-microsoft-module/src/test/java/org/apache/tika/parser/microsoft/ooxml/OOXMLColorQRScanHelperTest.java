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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.apache.tika.metadata.Barcode;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ColorAwareConfig;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.image.BoundedColorGridCollector;
import org.apache.tika.parser.image.ZXingCPPConfig;
import org.apache.tika.parser.image.ZXingCPPScanner;

public class OOXMLColorQRScanHelperTest {

    @TempDir
    Path tempDir;

    @Test
    public void truncatedCollectorIsSecurityVisibleBeforeDecode() {
        BoundedColorGridCollector collector =
                new BoundedColorGridCollector(1, 1);
        collector.startRow();
        collector.addCell(0);
        collector.addCell(255);
        collector.finishRow();
        Metadata metadata = new Metadata();

        OOXMLColorQRScanHelper.scan(
                collector, new ParseContext(), metadata, "test_color_qr", "TEST");

        assertTrue(Arrays.stream(metadata.getValues(
                        TikaCoreProperties.TIKA_META_EXCEPTION_WARNING))
                .anyMatch(value -> value.contains("TEST color-QR analysis limit")));
        assertNotNull(metadata.get("ExploitClass"));
    }

    @Test
    public void shortRowsFromOtherDocumentPartsDoNotSuppressGrid()
            throws Exception {
        // The scanner test double is a POSIX shell script. Windows can neither mark it
        // executable in the POSIX sense nor run it, so the scan silently yields nothing
        // and the assertion fails on a confusing null. Skip with a stated reason instead.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.FileSystems.getDefault().supportedFileAttributeViews()
                        .contains("posix"),
                "scanner test double is a POSIX shell script; not runnable on this filesystem");
        Path scanner = tempDir.resolve("ZXingReader");
        Files.writeString(scanner, """
                #!/bin/sh
                printf '%s\\n' '{"FilePath":"/tmp/code.png","Text":"body-qr",\
"Format":"QR Code"}'
                """, StandardCharsets.UTF_8);
        assertTrue(scanner.toFile().setExecutable(true));

        ParseContext context = new ParseContext();
        context.set(ColorAwareConfig.class,
                new ColorAwareConfig().setEnabled(true));
        ZXingCPPConfig zxing = new ZXingCPPConfig();
        zxing.setEnabled(true);
        zxing.setZxingPath(scanner.toString());
        context.set(ZXingCPPConfig.class, zxing);

        BoundedColorGridCollector collector =
                new BoundedColorGridCollector();
        for (int row = 0; row < 10; row++) {
            collector.startRow();
            collector.addCell(0);
            collector.finishRow();
        }
        for (int row = 0; row < 21; row++) {
            collector.startRow();
            for (int col = 0; col < 21; col++) {
                collector.addCell((row + col) % 2 == 0 ? 0 : 255);
            }
            collector.finishRow();
        }

        Metadata metadata = new Metadata();
        OOXMLColorQRScanHelper.scan(
                collector, context, metadata, "docx_color_qr", "DOCX");

        assertEquals("body-qr", metadata.get(Barcode.BARCODE_VALUE));
        assertEquals("1", metadata.get("docx_color_qr:decode_count"));
    }

    @Test
    public void scannerSecurityExceptionPropagates() {
        ParseContext context = new ParseContext();
        context.set(ColorAwareConfig.class,
                new ColorAwareConfig().setEnabled(true));
        ZXingCPPConfig zxing = new ZXingCPPConfig();
        zxing.setEnabled(true);
        context.set(ZXingCPPConfig.class, zxing);

        List<List<Integer>> rows = new ArrayList<>();
        for (int row = 0; row < 21; row++) {
            rows.add(Collections.nCopies(21, row % 2 == 0 ? 0 : 255));
        }
        SecurityException failure =
                new SecurityException("simulated scanner security boundary");
        ZXingCPPScanner scanner = new ZXingCPPScanner(zxing) {
            @Override
            public boolean hasZXingCPP() {
                throw new AssertionError("budgeted scans must not probe availability");
            }

            @Override
            public List<Result> scan(Path imagePath, ZXingCPPConfig config,
                                     ParseContext parseContext) {
                throw new AssertionError("OOXML must use the budget-aware scan overload");
            }

            @Override
            public List<Result> scan(Path imagePath, ZXingCPPConfig config,
                                     ParseContext parseContext, ScanBudget budget) {
                assertSame(context, parseContext);
                throw failure;
            }
        };

        SecurityException thrown = assertThrows(SecurityException.class,
                () -> OOXMLColorQRScanHelper.scan(
                        rows, context, new Metadata(), "test_color_qr",
                        "TEST", scanner));

        assertSame(failure, thrown);
    }
}
