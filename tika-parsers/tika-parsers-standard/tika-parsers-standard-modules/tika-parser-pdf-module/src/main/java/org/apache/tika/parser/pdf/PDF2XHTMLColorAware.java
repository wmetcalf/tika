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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorN;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.text.TextPosition;
import org.xml.sax.ContentHandler;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.image.BarcodeMetadataUtil;
import org.apache.tika.parser.image.ColorGridQRDecoder;
import org.apache.tika.parser.image.ZXingCPPConfig;
import org.apache.tika.parser.image.ZXingCPPScanner;
import org.apache.tika.renderer.Renderer;

/**
 * Color-aware extension of {@link PDF2XHTML} that tracks per-glyph
 * (position, character, fill color) tuples and, at each page end, attempts
 * to reconstruct a QR code from a grid of dark vs light cells.
 *
 * <p>This catches the evasion variant where a PDF renders each module of a
 * QR code as a colored character — Tika's default text extraction strips
 * color and emits the page as a uniform string of glyphs that no QR
 * scanner can decode.</p>
 *
 * <p>Activation: the {@link PDFParser} instantiates this subclass instead
 * of the base when {@link org.apache.tika.parser.ColorAwareConfig} is
 * present in the {@link ParseContext} with {@code isEnabled() == true}
 * <em>and</em> a {@link ZXingCPPConfig} is also configured.</p>
 */
final class PDF2XHTMLColorAware extends PDF2XHTML {

    /** Tolerance (PDF user-space units) for grouping glyphs into the same
     *  row. Most PDFs render QR cells at fixed font sizes so 1.5pt is a
     *  generous bin without merging adjacent rows. */
    private static final float ROW_BIN_TOLERANCE = 1.5f;

    private final ZXingCPPScanner scanner;
    private final ZXingCPPConfig zxingConfig;
    private final List<Glyph> pageGlyphs = new ArrayList<>();

    PDF2XHTMLColorAware(PDDocument document, ContentHandler handler,
                         ParseContext context, Metadata metadata,
                         PDFParserConfig config, Renderer renderer,
                         ZXingCPPScanner scanner, ZXingCPPConfig zxingConfig)
            throws IOException {
        super(document, handler, context, metadata, config, renderer);
        this.scanner = scanner;
        this.zxingConfig = zxingConfig;
        // PDFTextStripper does not register color operators by default, so
        // getNonStrokingColor() would always return black. Register them
        // explicitly so per-glyph fill color is tracked through the page
        // content stream.
        addOperator(new SetNonStrokingColorSpace(this));
        addOperator(new SetNonStrokingDeviceRGBColor(this));
        addOperator(new SetNonStrokingDeviceGrayColor(this));
        addOperator(new SetNonStrokingDeviceCMYKColor(this));
        addOperator(new SetNonStrokingColor(this));
        addOperator(new SetNonStrokingColorN(this));
    }

    /** One captured text glyph with its position + fill color luminance. */
    private static final class Glyph {
        final float x;
        final float y;
        final int luma;
        Glyph(float x, float y, int luma) {
            this.x = x;
            this.y = y;
            this.luma = luma;
        }
    }

    @Override
    protected void processTextPosition(TextPosition text) {
        try {
            PDColor c = getGraphicsState().getNonStrokingColor();
            float[] rgb = c.getColorSpace().toRGB(c.getComponents());
            int luma = ColorGridQRDecoder.luma(
                    (int) (rgb[0] * 255),
                    (int) (rgb[1] * 255),
                    (int) (rgb[2] * 255));
            pageGlyphs.add(new Glyph(text.getXDirAdj(), text.getYDirAdj(), luma));
        } catch (Exception ignored) {
            // No color available for this glyph — skip (it won't contribute
            // to a QR module either way).
        }
        super.processTextPosition(text);
    }

    @Override
    protected void endPage(PDPage page) throws IOException {
        // Run the color-QR scan before the super call so the metadata it
        // emits lands on the page's XHTML stream before paragraph-end.
        scanPageForColorQR();
        pageGlyphs.clear();
        super.endPage(page);
    }

    private void scanPageForColorQR() {
        metadata.add("pdf_color_qr:glyphs", String.valueOf(pageGlyphs.size()));
        if (pageGlyphs.size() < 64) {
            metadata.add("pdf_color_qr:stage", "skip-below-64");
            return;
        }
        try {
            ClusterResult cr = clusterToGridDiag(pageGlyphs);
            metadata.add("pdf_color_qr:rows", String.valueOf(cr.rowCount));
            metadata.add("pdf_color_qr:maxcols", String.valueOf(cr.maxCols));
            metadata.add("pdf_color_qr:qualifying", String.valueOf(cr.qualifying));
            metadata.add("pdf_color_qr:dark_glyphs", String.valueOf(cr.darkCount));
            if (cr.grid.isEmpty()) {
                metadata.add("pdf_color_qr:stage", "clusterToGrid-empty");
                return;
            }
            List<List<List<ColorGridQRDecoder.Cell>>> grids = new ArrayList<>();
            grids.add(cr.grid);
            List<org.apache.tika.parser.image.ZXingCPPScanner.Result> decoded =
                    ColorGridQRDecoder.decode(grids, scanner, zxingConfig, null);
            ColorGridQRDecoder.emitBarcodes(decoded, metadata);
            metadata.add("pdf_color_qr:decode_count", String.valueOf(decoded.size()));
            if (!decoded.isEmpty()) {
                metadata.add("ExploitClass",
                        "Decoded " + decoded.size()
                      + " CSS-colored QR code(s) from PDF text colors — "
                      + "invisible to image-based scanners and to standard "
                      + "PDF text extraction");
            }
        } catch (RuntimeException ex) {
            metadata.add("pdf_color_qr:stage", "exception:" + ex.getClass().getSimpleName() + ":" + ex.getMessage());
            BarcodeMetadataUtil.markAnalysisIncomplete(
                    metadata, "PDF color-QR analysis", ex);
        }
    }

    private static final class ClusterResult {
        final List<List<ColorGridQRDecoder.Cell>> grid;
        final int rowCount;
        final int maxCols;
        final int qualifying;
        final int darkCount;
        ClusterResult(List<List<ColorGridQRDecoder.Cell>> g, int r, int c, int q, int d) {
            grid = g;
            rowCount = r;
            maxCols = c;
            qualifying = q;
            darkCount = d;
        }
    }

    private static ClusterResult clusterToGridDiag(List<Glyph> glyphs) {
        if (glyphs.isEmpty()) {
            return new ClusterResult(new ArrayList<>(), 0, 0, 0, 0);
        }
        List<Glyph> sorted = new ArrayList<>(glyphs);
        sorted.sort((a, b) -> Float.compare(b.y, a.y));

        List<List<Glyph>> rows = new ArrayList<>();
        List<Glyph> currentRow = new ArrayList<>();
        float currentY = sorted.get(0).y;
        for (Glyph g : sorted) {
            if (Math.abs(g.y - currentY) <= ROW_BIN_TOLERANCE) {
                currentRow.add(g);
            } else {
                rows.add(currentRow);
                currentRow = new ArrayList<>();
                currentRow.add(g);
                currentY = g.y;
            }
        }
        if (!currentRow.isEmpty()) {
            rows.add(currentRow);
        }
        int maxCols = 0;
        for (List<Glyph> r : rows) {
            r.sort((a, b) -> Float.compare(a.x, b.x));
            if (r.size() > maxCols) {
                maxCols = r.size();
            }
        }
        int needed = (int) Math.ceil(maxCols * 0.7);
        int qualifying = 0;
        for (List<Glyph> r : rows) {
            if (r.size() >= needed) {
                qualifying++;
            }
        }
        int darkCount = 0;
        for (Glyph g : glyphs) {
            if (g.luma < ColorGridQRDecoder.DARK_LUMA_THRESHOLD) {
                darkCount++;
            }
        }
        if (rows.size() < ColorGridQRDecoder.MIN_LINES
                || maxCols < ColorGridQRDecoder.MIN_COLS
                || qualifying < (int) Math.ceil(rows.size() * 0.7)) {
            return new ClusterResult(new ArrayList<>(), rows.size(), maxCols, qualifying, darkCount);
        }
        List<List<ColorGridQRDecoder.Cell>> grid = new ArrayList<>();
        for (List<Glyph> r : rows) {
            List<ColorGridQRDecoder.Cell> cells = new ArrayList<>(r.size());
            for (Glyph g : r) {
                cells.add(new ColorGridQRDecoder.Cell(
                        g.luma < ColorGridQRDecoder.DARK_LUMA_THRESHOLD));
            }
            grid.add(cells);
        }
        return new ClusterResult(grid, rows.size(), maxCols, qualifying, darkCount);
    }

    /**
     * Bin glyphs into rows by y-coordinate, then sort each row by x.
     * Produces a Cell grid where each glyph contributes one cell whose
     * dark/light class is set by its captured luma.
     *
     * <p>QR codes rendered via PDF text-show operators are typically a
     * regular grid of fixed-width chars at fixed line spacing, so simple
     * y-binning + x-sort recovers the module layout cleanly.</p>
     */
    private static List<List<ColorGridQRDecoder.Cell>> clusterToGrid(List<Glyph> glyphs) {
        if (glyphs.isEmpty()) {
            return new ArrayList<>();
        }
        // Sort by y descending (PDF y-coordinate origin is bottom-left).
        List<Glyph> sorted = new ArrayList<>(glyphs);
        sorted.sort((a, b) -> Float.compare(b.y, a.y));

        List<List<Glyph>> rows = new ArrayList<>();
        List<Glyph> currentRow = new ArrayList<>();
        float currentY = sorted.get(0).y;
        for (Glyph g : sorted) {
            if (Math.abs(g.y - currentY) <= ROW_BIN_TOLERANCE) {
                currentRow.add(g);
            } else {
                rows.add(currentRow);
                currentRow = new ArrayList<>();
                currentRow.add(g);
                currentY = g.y;
            }
        }
        if (!currentRow.isEmpty()) {
            rows.add(currentRow);
        }

        if (rows.size() < ColorGridQRDecoder.MIN_LINES) {
            return new ArrayList<>();
        }
        // Sort each row by x ascending, then check that the rows are roughly
        // grid-shaped (similar column count). QR layouts are square.
        int maxCols = 0;
        for (List<Glyph> r : rows) {
            r.sort((a, b) -> Float.compare(a.x, b.x));
            if (r.size() > maxCols) {
                maxCols = r.size();
            }
        }
        if (maxCols < ColorGridQRDecoder.MIN_COLS) {
            return new ArrayList<>();
        }
        // Reject obviously-non-grid pages: at least 70% of rows should have
        // 70% of the max column count. Filters out body-text pages.
        int qualifying = 0;
        int needed = (int) Math.ceil(maxCols * 0.7);
        for (List<Glyph> r : rows) {
            if (r.size() >= needed) {
                qualifying++;
            }
        }
        if (qualifying < (int) Math.ceil(rows.size() * 0.7)) {
            return new ArrayList<>();
        }
        // Classify each glyph: dark if its captured luma is below threshold.
        List<List<ColorGridQRDecoder.Cell>> grid = new ArrayList<>();
        for (List<Glyph> r : rows) {
            List<ColorGridQRDecoder.Cell> cells = new ArrayList<>(r.size());
            for (Glyph g : r) {
                cells.add(new ColorGridQRDecoder.Cell(
                        g.luma < ColorGridQRDecoder.DARK_LUMA_THRESHOLD));
            }
            grid.add(cells);
        }
        return grid;
    }

    @SuppressWarnings("unused")  // wired up by PDF2XHTML.process when needed
    static PageDrawer dummyPageDrawer(PageDrawerParameters p) throws IOException {
        return new PageDrawer(p);
    }
}
