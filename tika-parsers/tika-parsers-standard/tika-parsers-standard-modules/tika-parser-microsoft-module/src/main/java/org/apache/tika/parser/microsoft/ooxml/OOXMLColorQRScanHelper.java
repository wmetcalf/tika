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

import java.util.ArrayList;
import java.util.List;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ColorAwareConfig;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.image.BarcodeMetadataUtil;
import org.apache.tika.parser.image.ColorGridQRDecoder;
import org.apache.tika.parser.image.ZXingCPPConfig;
import org.apache.tika.parser.image.ZXingCPPScanner;

/**
 * Shared helper for decoding color-encoded QR codes captured from the
 * streaming OOXML body part handler ({@link OOXMLTikaBodyPartHandler}).
 *
 * <p>Each row is a list of luma values (one per non-whitespace glyph in the
 * paragraph or cell). The helper applies a 70% grid-shape filter to drop
 * body-text rows, then renders the dark/light grid and runs ZXing-CPP.</p>
 *
 * <p>Use a different {@code keyPrefix} per format so metadata keys don't
 * collide across DOCX/PPTX/XLSX entries.</p>
 */
public final class OOXMLColorQRScanHelper {

    private OOXMLColorQRScanHelper() {
    }

    public static void scan(List<List<Integer>> rows, ParseContext context,
                     Metadata metadata, String keyPrefix,
                     String exploitFormatLabel) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        ColorAwareConfig cc = context.get(ColorAwareConfig.class);
        if (cc == null || !cc.isEnabled()) {
            return;
        }
        ZXingCPPConfig zcfg = context.get(ZXingCPPConfig.class);
        if (zcfg == null) {
            return;
        }
        int maxCols = 0;
        for (List<Integer> r : rows) {
            if (r.size() > maxCols) {
                maxCols = r.size();
            }
        }
        if (rows.size() < ColorGridQRDecoder.MIN_LINES
                || maxCols < ColorGridQRDecoder.MIN_COLS) {
            return;
        }
        int needed = (int) Math.ceil(maxCols * 0.7);
        int qualifying = 0;
        for (List<Integer> r : rows) {
            if (r.size() >= needed) {
                qualifying++;
            }
        }
        if (qualifying < (int) Math.ceil(rows.size() * 0.7)) {
            return;
        }
        List<List<ColorGridQRDecoder.Cell>> grid = new ArrayList<>();
        for (List<Integer> r : rows) {
            if (r.size() < needed) {
                continue;
            }
            List<ColorGridQRDecoder.Cell> cells = new ArrayList<>(r.size());
            for (Integer luma : r) {
                cells.add(new ColorGridQRDecoder.Cell(
                        luma < ColorGridQRDecoder.DARK_LUMA_THRESHOLD));
            }
            grid.add(cells);
        }
        List<List<List<ColorGridQRDecoder.Cell>>> grids = new ArrayList<>();
        grids.add(grid);
        try {
            ZXingCPPScanner scanner = new ZXingCPPScanner(zcfg);
            List<ZXingCPPScanner.Result> decoded =
                    ColorGridQRDecoder.decode(grids, scanner, zcfg, null);
            ColorGridQRDecoder.emitBarcodes(decoded, metadata);
            metadata.add(keyPrefix + ":rows", String.valueOf(rows.size()));
            metadata.add(keyPrefix + ":maxcols", String.valueOf(maxCols));
            metadata.add(keyPrefix + ":decode_count", String.valueOf(decoded.size()));
            if (!decoded.isEmpty()) {
                metadata.add("ExploitClass",
                        "Decoded " + decoded.size()
                      + " CSS-colored QR code(s) from " + exploitFormatLabel
                      + " — invisible to image-based scanners and to standard "
                      + exploitFormatLabel + " text extraction");
            }
        } catch (RuntimeException ex) {
            metadata.add(keyPrefix + ":error",
                    ex.getClass().getSimpleName() + ":" + ex.getMessage());
            BarcodeMetadataUtil.markAnalysisIncomplete(
                    metadata, exploitFormatLabel + " color-QR analysis", ex);
        }
    }
}
