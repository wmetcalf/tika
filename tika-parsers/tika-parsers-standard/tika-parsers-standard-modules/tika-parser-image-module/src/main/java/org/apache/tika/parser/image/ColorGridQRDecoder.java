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

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

import org.apache.tika.parser.ParseContext;

/**
 * Shared QR-from-grid decoder. Inputs are already-classified 2D grids of
 * dark/light cells; the decoder renders each grid to a 2-colour bitmap and
 * hands it to {@link ZXingCPPScanner}.
 *
 * <p>Format-specific scanners ({@code HtmlColorQRExtractor} for HTML,
 * {@code PDFColorQRTracker} for PDF, OOXML run-color analyzers for DOCX/
 * XLSX/PPTX) build a grid by walking their native document model and
 * resolving per-cell color information, then delegate the bitmap render +
 * ZXing fork to this class. Keeps the decode policy (module pixel size,
 * quiet zone, min cluster dimensions, bail-on-first) in one place.</p>
 */
public final class ColorGridQRDecoder {

    /** Pixel size of each QR module in the rendered bitmap. */
    public static final int MODULE_PX = 4;

    /** Minimum cluster dimensions to attempt decoding. Below this we don't
     *  even bother — small grids waste a ZXing subprocess fork. */
    public static final int MIN_LINES = 6;
    public static final int MIN_COLS = 6;

    /** Hard cap on number of clusters to decode per call. */
    public static final int MAX_CLUSTERS = 4;

    private ColorGridQRDecoder() { }

    /** Light = false, dark = true. */
    public static final class Cell {
        public final boolean dark;
        public Cell(boolean dark) { this.dark = dark; }
    }

    /**
     * Render and decode the given grids. Bails on the first successful
     * decode (the typical defender question is "is there a code", not
     * "enumerate every cluster").
     *
     * @param grids   one Cell-grid per candidate cluster; each grid's outer
     *                list is rows, inner list is columns within a row
     * @param scanner ZXing-CPP subprocess wrapper
     * @return decoded text values
     */
    public static List<ZXingCPPScanner.Result> decode(List<List<List<Cell>>> grids,
                                      ZXingCPPScanner scanner,
                                      ZXingCPPConfig config,
                                      ParseContext context) {
        return decode(grids, scanner, config, context, null);
    }

    /**
     * Variant that shares an aggregate subprocess budget across several
     * candidate grids or document pages.
     */
    public static List<ZXingCPPScanner.Result> decode(List<List<List<Cell>>> grids,
                                      ZXingCPPScanner scanner,
                                      ZXingCPPConfig config,
                                      ParseContext context,
                                      ZXingCPPScanner.ScanBudget budget) {
        List<ZXingCPPScanner.Result> decoded = new ArrayList<>();
        if (grids == null || grids.isEmpty()
                || scanner == null
                || (budget == null && !scanner.hasZXingCPP())) {
            return decoded;
        }
        int rendered = 0;
        for (List<List<Cell>> grid : grids) {
            if (rendered++ >= MAX_CLUSTERS) {
                break;
            }
            if (grid.size() < MIN_LINES || maxCols(grid) < MIN_COLS) {
                continue;
            }
            Path tmp = null;
            try {
                BufferedImage img = render(grid);
                if (img == null) {
                    continue;
                }
                tmp = Files.createTempFile("colorqr-", ".png");
                ImageIO.write(img, "PNG", tmp.toFile());
                List<ZXingCPPScanner.Result> results =
                        budget == null
                                ? scanner.scan(tmp, config, context)
                                : scanner.scan(tmp, config, context, budget);
                boolean hit = false;
                for (ZXingCPPScanner.Result r : results) {
                    String t = r.getText();
                    if (t != null && !t.isEmpty()) {
                        decoded.add(r);
                        hit = true;
                    }
                }
                if (hit) {
                    break;
                }
            } catch (IOException e) {
                throw new IllegalStateException("Color-grid QR rendering failed", e);
            } finally {
                if (tmp != null) {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (IOException ignored) {
                        // ignore
                    }
                }
            }
        }
        return decoded;
    }

    /**
     * Write each decoded result to the supplied metadata as a canonical barcode
     * record and compatibility fields.
     */
    public static void emitBarcodes(List<ZXingCPPScanner.Result> results,
                                    org.apache.tika.metadata.Metadata metadata) {
        if (results == null || metadata == null) {
            return;
        }
        for (ZXingCPPScanner.Result r : results) {
            String text = r.getText();
            if (text == null || text.isEmpty()) {
                continue;
            }
            BarcodeMetadataUtil.addResult(metadata, r, "qrcode");
        }
    }

    /** Render a 2D Cell grid to a 2-colour bitmap. */
    public static BufferedImage render(List<List<Cell>> grid) {
        if (grid == null || grid.isEmpty()) {
            return null;
        }
        int maxCols = maxCols(grid);
        if (maxCols < MIN_COLS) {
            return null;
        }
        int quiet = 4;
        // Compute as long so a hostile maxCols/grid.size() can't overflow the int
        // multiply into a small positive that slips past the area check (memory-
        // exhaustion DoS). A real QR is <=177 modules per side.
        long imgWLong = ((long) maxCols     + 2 * quiet) * MODULE_PX;
        long imgHLong = ((long) grid.size() + 2 * quiet) * MODULE_PX;
        if (imgWLong <= 0 || imgHLong <= 0 || imgWLong * imgHLong > 16L * 1024 * 1024) {
            return null;
        }
        int imgW = (int) imgWLong;
        int imgH = (int) imgHLong;
        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, imgW, imgH);
            g.setColor(Color.BLACK);
            for (int row = 0; row < grid.size(); row++) {
                List<Cell> r = grid.get(row);
                for (int col = 0; col < r.size(); col++) {
                    if (r.get(col).dark) {
                        int x = (quiet + col) * MODULE_PX;
                        int y = (quiet + row) * MODULE_PX;
                        g.fillRect(x, y, MODULE_PX, MODULE_PX);
                    }
                }
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    /** ITU-R BT.601 luminance from 8-bit RGB (0..255). */
    public static int luma(int r, int g, int b) {
        return (int) (0.299 * r + 0.587 * g + 0.114 * b);
    }

    /** Threshold below which a colour counts as a "dark" QR module. */
    public static final int DARK_LUMA_THRESHOLD = 128;

    /** Convenience: is the given RGB triple a dark module? */
    public static boolean isDark(int r, int g, int b) {
        return luma(r, g, b) < DARK_LUMA_THRESHOLD;
    }

    private static int maxCols(List<List<Cell>> grid) {
        int max = 0;
        for (List<Cell> row : grid) {
            if (row.size() > max) {
                max = row.size();
            }
        }
        return max;
    }
}
