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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.tika.parser.ParseContext;

/**
 * Recovers QR codes drawn in plaintext using Unicode glyphs and hands the
 * reconstructed bitmap to {@link ZXingCPPScanner} for decoding.
 *
 * <p>Two rendering modes are recognised:</p>
 * <ul>
 *   <li><b>Block elements</b> (U+2580..U+259F + a few extras) — each char
 *       maps to a 2x2 QR-module quadrant. The dominant style in phishing
 *       kits and the one
 *       <a href="https://github.com/wmetcalf/txtqr_one_vision">txtqr_one_vision</a>
 *       was originally built for.</li>
 *   <li><b>Braille patterns</b> (U+2800..U+28FF) — each codepoint encodes
 *       up to 8 dots arranged as 4 rows × 2 cols, giving denser packing than
 *       block elements (about 2.5× the modules per character cell). Seen in
 *       2024-2025 kits that want a tighter on-screen footprint than full
 *       block-element rendering allows.</li>
 * </ul>
 *
 * <p>Per-cluster, the dominant glyph family decides which mode renders the
 * bitmap. Mixing the two within one cluster is not supported.</p>
 */
public final class UnicodeQRExtractor {

    /** Render mode for a cluster. */
    enum Mode {
        /** Block elements / quadrants — 2 module rows × 2 module cols per char. */
        BLOCK_2X2,
        /** Braille patterns — 4 module rows × 2 module cols per char. */
        BRAILLE_4X2,
    }

    /** Module grid per block-element character.
     *  Indices: 0=top-left, 1=top-right, 2=bottom-left, 3=bottom-right. */
    private static final Map<Character, int[]> BLOCK_MAP = buildBlockMap();

    /** A Micro-QR is 11×11 modules — that's ~6 block-rows or ~3 Braille-rows
     *  worth of char lines. We pick a slightly conservative floor to keep
     *  decorative ASCII boxes from tripping the scan. */
    private static final int MIN_CLUSTER_LINES_BLOCK   = 5;
    private static final int MIN_CLUSTER_LINES_BRAILLE = 3;

    /** Per-line glyph density floor. Real QR rows are dense; lines that are
     *  mostly normal text with one stray ■ shouldn't merge into a cluster. */
    private static final int MIN_CLUSTER_LINE_GLYPHS = 6;

    /** Bridge tolerance: allow up to N consecutive low-density lines inside a
     *  cluster before splitting. Real codes sometimes get one blank line
     *  injected by HTML rendering / pre-wrap edge cases. */
    private static final int CLUSTER_GAP_TOLERANCE = 2;

    /** Pixel size of each QR module in the rendered bitmap. */
    private static final int MODULE_PX = 4;

    /** Cap on number of clusters we'll render+decode per call to keep latency
     *  predictable on adversarial input full of decorative ASCII boxes. */
    private static final int MAX_CLUSTERS = 4;

    private UnicodeQRExtractor() { }

    private static Map<Character, int[]> buildBlockMap() {
        Map<Character, int[]> m = new HashMap<>();
        // Full block & equivalents — all 4 quadrants dark
        for (char c : new char[]{'█', '■', '▓', '▇', '▆', '▅', '▃', '▂', '▁',
                '#', '@', '▣', '▩', '◼', '⬛'}) {
            m.put(c, new int[]{1, 1, 1, 1});
        }
        // Space & empty box — all 4 quadrants light
        for (char c : new char[]{' ', '□', '▢', '◻', '⬜', '☐'}) {
            m.put(c, new int[]{0, 0, 0, 0});
        }
        // Half-blocks
        m.put('▀', new int[]{1, 1, 0, 0});  // upper half
        m.put('▄', new int[]{0, 0, 1, 1});  // lower half
        m.put('▌', new int[]{1, 0, 1, 0});  // left half
        m.put('▐', new int[]{0, 1, 0, 1});  // right half
        // Quadrant blocks
        m.put('▖', new int[]{0, 0, 1, 0});  // lower-left
        m.put('▗', new int[]{0, 0, 0, 1});  // lower-right
        m.put('▘', new int[]{1, 0, 0, 0});  // upper-left
        m.put('▝', new int[]{0, 1, 0, 0});  // upper-right
        m.put('▞', new int[]{0, 1, 1, 0});  // upper-right + lower-left
        m.put('▟', new int[]{1, 1, 1, 0});  // upper-left + lower-right complement
        // Mid-density alternates seen in some kits
        m.put('▒', new int[]{1, 1, 1, 1});  // medium shade — treat as dark
        m.put('░', new int[]{0, 0, 0, 0});  // light shade — treat as light
        return m;
    }

    /** True if {@code c} is a block-element QR glyph. */
    public static boolean isBlockQrGlyph(char c) {
        return BLOCK_MAP.containsKey(c);
    }

    /** True if {@code c} is a Braille-pattern QR glyph (U+2800..U+28FF). */
    public static boolean isBrailleQrGlyph(char c) {
        return c >= 0x2800 && c <= 0x28FF;
    }

    /** True if {@code c} is either a block or Braille QR glyph. */
    public static boolean isQrGlyph(char c) {
        return isBlockQrGlyph(c) || isBrailleQrGlyph(c);
    }

    /** Cheap probe — total count of any QR-glyph chars in the text. */
    public static int countQrGlyphs(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isQrGlyph(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    /**
     * Find QR-glyph clusters in {@code text}, render each, decode via ZXing.
     * Bails on the first successful decode — most calls are interested in
     * "is there a code or not", not enumerating every possible cluster.
     */
    public static List<String> extractAndDecode(String text,
                                                ZXingCPPScanner scanner,
                                                ZXingCPPConfig config,
                                                ParseContext context) {
        List<String> decoded = new ArrayList<>();
        if (text == null || text.isEmpty()
                || scanner == null || !scanner.hasZXingCPP(config)) {
            return decoded;
        }
        List<Cluster> clusters = findClusters(text);
        if (clusters.isEmpty()) {
            return decoded;
        }
        int rendered = 0;
        for (Cluster cluster : clusters) {
            if (rendered++ >= MAX_CLUSTERS) {
                break;
            }
            Path tmp = null;
            try {
                BufferedImage img = renderCluster(cluster);
                if (img == null) {
                    continue;
                }
                tmp = Files.createTempFile("txtqr-", ".png");
                ImageIO.write(img, "PNG", tmp.toFile());
                List<ZXingCPPScanner.Result> results = scanner.scan(tmp, config, context);
                boolean hit = false;
                for (ZXingCPPScanner.Result r : results) {
                    String t = r.getText();
                    if (t != null && !t.isEmpty()) {
                        decoded.add(t);
                        hit = true;
                    }
                }
                // Bail-on-first-decode: once we've successfully extracted at
                // least one URL the typical defender's question is answered;
                // remaining clusters are usually noise (decorative boxes in
                // the same body) and just burn ZXing subprocess time.
                if (hit) {
                    break;
                }
            } catch (IOException e) {
                // Best-effort — skip this cluster
            } catch (RuntimeException e) {
                // ZXing scan failures are non-fatal
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

    /** A contiguous run of lines that look like a QR code, plus the dominant
     *  glyph family that determines how it gets rendered. */
    static final class Cluster {
        final String[] lines;
        final Mode mode;
        Cluster(String[] lines, Mode mode) {
            this.lines = lines;
            this.mode = mode;
        }
    }

    /**
     * Split {@code text} into clusters of lines that look like QR rows. The
     * loop tolerates {@link #CLUSTER_GAP_TOLERANCE} low-density "bridge"
     * lines inside a cluster so HTML pre-wrap formatting artifacts don't
     * split a single code into multiple clusters.
     *
     * <p>For each cluster the dominant glyph family (block vs Braille) is
     * recorded. Mixed clusters get the family with the higher glyph count.</p>
     */
    static List<Cluster> findClusters(String text) {
        List<Cluster> clusters = new ArrayList<>();
        String[] lines = text.split("\\r\\n?|\\n");
        List<String> current = new ArrayList<>();
        int blockTotal = 0;
        int brailleTotal = 0;
        int gapStreak = 0;

        for (String line : lines) {
            int blockGlyphs = 0;
            int brailleGlyphs = 0;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (isBlockQrGlyph(c)) {
                    blockGlyphs++;
                } else if (isBrailleQrGlyph(c)) {
                    brailleGlyphs++;
                }
            }
            int total = blockGlyphs + brailleGlyphs;
            if (total >= MIN_CLUSTER_LINE_GLYPHS) {
                current.add(line);
                blockTotal += blockGlyphs;
                brailleTotal += brailleGlyphs;
                gapStreak = 0;
            } else if (!current.isEmpty() && gapStreak < CLUSTER_GAP_TOLERANCE) {
                // Tolerate a short formatting gap inside an otherwise-dense run
                current.add(line);
                gapStreak++;
            } else {
                flushCluster(current, blockTotal, brailleTotal, clusters);
                current.clear();
                blockTotal = 0;
                brailleTotal = 0;
                gapStreak = 0;
            }
        }
        flushCluster(current, blockTotal, brailleTotal, clusters);
        return clusters;
    }

    private static void flushCluster(List<String> lines, int blockTotal,
                                     int brailleTotal, List<Cluster> out) {
        if (lines.isEmpty()) {
            return;
        }
        // Trim trailing bridge lines that have no QR content (cosmetic).
        int end = lines.size();
        while (end > 0) {
            int last = end - 1;
            String line = lines.get(last);
            boolean hasGlyph = false;
            for (int i = 0; i < line.length(); i++) {
                if (isQrGlyph(line.charAt(i))) {
                    hasGlyph = true;
                    break;
                }
            }
            if (hasGlyph) {
                break;
            }
            end--;
        }
        if (end == 0) {
            return;
        }
        Mode mode = brailleTotal > blockTotal ? Mode.BRAILLE_4X2 : Mode.BLOCK_2X2;
        int minLines = (mode == Mode.BRAILLE_4X2)
                ? MIN_CLUSTER_LINES_BRAILLE : MIN_CLUSTER_LINES_BLOCK;
        if (end < minLines) {
            return;
        }
        out.add(new Cluster(lines.subList(0, end).toArray(new String[0]), mode));
    }

    /**
     * Render a single cluster to a 2-colour {@link BufferedImage} suitable
     * for ZXing. Dispatches on cluster mode.
     */
    static BufferedImage renderCluster(Cluster cluster) {
        if (cluster == null || cluster.lines == null || cluster.lines.length == 0) {
            return null;
        }
        switch (cluster.mode) {
            case BRAILLE_4X2:
                return renderBrailleCluster(cluster.lines);
            case BLOCK_2X2:
            default:
                return renderBlockCluster(cluster.lines);
        }
    }

    private static BufferedImage renderBlockCluster(String[] lines) {
        int maxCharWidth = maxGlyphCols(lines, true);
        if (maxCharWidth < 5) {
            return null;
        }
        int widthModules  = maxCharWidth * 2;
        int heightModules = lines.length * 2;
        return paint(lines, widthModules, heightModules, (g, row, col, c, quiet) -> {
            int[] mods = BLOCK_MAP.get(c);
            if (mods == null) {
                return;
            }
            int x = (quiet + col * 2) * MODULE_PX;
            int y = (quiet + row * 2) * MODULE_PX;
            if (mods[0] == 1) {
                g.fillRect(x,            y,            MODULE_PX, MODULE_PX);
            }
            if (mods[1] == 1) {
                g.fillRect(x + MODULE_PX, y,            MODULE_PX, MODULE_PX);
            }
            if (mods[2] == 1) {
                g.fillRect(x,            y + MODULE_PX, MODULE_PX, MODULE_PX);
            }
            if (mods[3] == 1) {
                g.fillRect(x + MODULE_PX, y + MODULE_PX, MODULE_PX, MODULE_PX);
            }
        });
    }

    private static BufferedImage renderBrailleCluster(String[] lines) {
        int maxCharWidth = maxGlyphCols(lines, false);
        if (maxCharWidth < 5) {
            return null;
        }
        // Braille = 4 rows × 2 cols of modules per char.
        int widthModules  = maxCharWidth * 2;
        int heightModules = lines.length * 4;
        return paint(lines, widthModules, heightModules, (g, row, col, c, quiet) -> {
            if (!isBrailleQrGlyph(c)) {
                return;
            }
            int dots = c - 0x2800;
            // ISO Braille dot-numbering -> (row, col) within the 4x2 grid.
            // bit 0 = dot 1 = (0,0)    bit 3 = dot 4 = (0,1)
            // bit 1 = dot 2 = (1,0)    bit 4 = dot 5 = (1,1)
            // bit 2 = dot 3 = (2,0)    bit 5 = dot 6 = (2,1)
            // bit 6 = dot 7 = (3,0)    bit 7 = dot 8 = (3,1)
            int[][] dotPos = {
                {0, 0}, {1, 0}, {2, 0}, {0, 1}, {1, 1}, {2, 1}, {3, 0}, {3, 1}
            };
            for (int b = 0; b < 8; b++) {
                if ((dots & (1 << b)) != 0) {
                    int dr = dotPos[b][0];
                    int dc = dotPos[b][1];
                    int x = (quiet + col * 2 + dc) * MODULE_PX;
                    int y = (quiet + row * 4 + dr) * MODULE_PX;
                    g.fillRect(x, y, MODULE_PX, MODULE_PX);
                }
            }
        });
    }

    private static int maxGlyphCols(String[] lines, boolean block) {
        int maxCharWidth = 0;
        for (String line : lines) {
            int w = 0;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (block ? isBlockQrGlyph(c) : isBrailleQrGlyph(c)) {
                    w++;
                }
            }
            if (w > maxCharWidth) {
                maxCharWidth = w;
            }
        }
        return maxCharWidth;
    }

    @FunctionalInterface
    private interface Painter {
        void paint(Graphics2D g, int row, int col, char c, int quiet);
    }

    private static BufferedImage paint(String[] lines, int widthModules,
                                       int heightModules, Painter painter) {
        int quiet = 4; // 4-module quiet zone is the QR spec minimum.
        int imgW = (widthModules  + 2 * quiet) * MODULE_PX;
        int imgH = (heightModules + 2 * quiet) * MODULE_PX;
        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, imgW, imgH);
            g.setColor(Color.BLACK);
            for (int row = 0; row < lines.length; row++) {
                String line = lines[row];
                int col = 0;
                for (int i = 0; i < line.length(); i++) {
                    char c = line.charAt(i);
                    if (!isQrGlyph(c)) {
                        continue;
                    }
                    painter.paint(g, row, col, c, quiet);
                    col++;
                }
            }
        } finally {
            g.dispose();
        }
        return img;
    }
}
