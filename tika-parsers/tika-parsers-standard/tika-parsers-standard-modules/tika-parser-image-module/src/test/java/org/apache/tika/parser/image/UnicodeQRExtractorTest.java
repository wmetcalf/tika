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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import org.apache.tika.parser.ParseContext;
import org.apache.tika.utils.FileProcessResult;

class UnicodeQRExtractorTest {

    @Test
    void ordinarySpacingDoesNotBecomeQrSignal() {
        String prose = String.join("\n",
                "ordinary prose has many spaces between its harmless words",
                "another ordinary sentence has enough spaces to pass density",
                "this line is text and not a rendered quick response code",
                "spaces must remain renderable without becoming dark signal",
                "the detector should reject this block before running zxing");

        assertEquals(0, UnicodeQRExtractor.countQrGlyphs(prose));
        assertTrue(UnicodeQRExtractor.findClusters(prose).isEmpty());
    }

    @Test
    void lightModulesRemainRenderableButDoNotCountAsSignal() {
        assertTrue(UnicodeQRExtractor.isBlockQrGlyph(' '));
        assertTrue(UnicodeQRExtractor.isBlockQrGlyph('\u00a0'));
        assertEquals(0, UnicodeQRExtractor.countQrGlyphs(" \u00a0"));
        assertEquals(1, UnicodeQRExtractor.countQrGlyphs("█"));
    }

    @Test
    void propertyLabelIsStrippedBeforeLeadingLightModule() {
        String art = String.join("\n",
                "Description: □██████",
                "□██████",
                "□██████",
                "□██████",
                "□██████");

        UnicodeQRExtractor.Cluster cluster =
                UnicodeQRExtractor.findClusters(art).get(0);

        assertEquals("□██████", cluster.lines[0]);
    }

    @Test
    void budgetedExtractionDoesNotProbeScannerAvailability() {
        String art = String.join("\n",
                "████████", "████████", "████████", "████████",
                "████████", "████████", "████████", "████████");
        ZXingCPPConfig config = new ZXingCPPConfig();
        config.setEnabled(true);
        ProbeRejectingScanner scanner = new ProbeRejectingScanner();

        UnicodeQRExtractor.extractAndDecode(
                art, scanner, config, new ParseContext(),
                new ZXingCPPScanner.ScanBudget(1, 1_000));

        assertEquals(1, scanner.executions);
    }

    @Test
    void localClusterCapSignalsIncompleteBudgetedExtraction() {
        String grid = String.join("\n",
                "████████", "████████", "████████", "████████",
                "████████", "████████", "████████", "████████");
        String art = String.join(
                "\n\n\n\n", Collections.nCopies(5, grid));
        ZXingCPPConfig config = new ZXingCPPConfig();
        config.setEnabled(true);
        ProbeRejectingScanner scanner = new ProbeRejectingScanner();
        ZXingCPPScanner.ScanBudget budget =
                new ZXingCPPScanner.ScanBudget(10, 10_000);

        assertThrows(
                ZXingCPPScanner.ScanBudgetExceededException.class,
                () -> UnicodeQRExtractor.extractAndDecode(
                        art, scanner, config, new ParseContext(), budget));

        assertEquals(4, scanner.executions);
        assertTrue(budget.hasRejectedScan());
    }

    private static final class ProbeRejectingScanner extends ZXingCPPScanner {

        private int executions;

        @Override
        boolean checkCommand(String[] command) {
            throw new AssertionError("budgeted scans must not launch a version probe");
        }

        @Override
        FileProcessResult execute(ProcessBuilder processBuilder, long timeoutMillis)
                throws IOException {
            executions++;
            FileProcessResult result = new FileProcessResult();
            result.setExitValue(0);
            result.setStdout("");
            return result;
        }
    }
}
