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
        /** "Symbols for Legacy Computing" sextants — 3 module rows × 2 module
         *  cols per char. U+1FB00..U+1FB3B (60 codepoints) plus the 4 overlap
         *  glyphs (' ', '█', '▌', '▐') that complete the 64-pattern table. */
        SEXTANT_3X2,
    }

    /** Module grid per block-element character.
     *  Indices: 0=top-left, 1=top-right, 2=bottom-left, 3=bottom-right. */
    private static final Map<Character, int[]> BLOCK_MAP = buildBlockMap();

    /** A Micro-QR is 11×11 modules — that's ~6 block-rows or ~3 Braille-rows
     *  worth of char lines. We pick a slightly conservative floor to keep
     *  decorative ASCII boxes from tripping the scan. */
    private static final int MIN_CLUSTER_LINES_BLOCK   = 5;
    private static final int MIN_CLUSTER_LINES_BRAILLE = 3;
    /** Each sextant char carries 3 module rows, so a Micro-QR (~11 modules)
     *  needs at least ⌈11/3⌉ = 4 sextant rows. */
    private static final int MIN_CLUSTER_LINES_SEXTANT = 4;

    /** Per-line glyph density floor. Real QR rows are dense; lines that are
     *  mostly normal text with one stray ■ shouldn't merge into a cluster. */
    private static final int MIN_CLUSTER_LINE_GLYPHS = 6;

    /** Bridge tolerance: allow up to N consecutive low-density lines inside a
     *  cluster before splitting. Real codes sometimes get one blank line
     *  injected by HTML rendering / pre-wrap edge cases. */
    private static final int CLUSTER_GAP_TOLERANCE = 2;

    /** Pixel size of each QR module in the rendered bitmap. */
    private static final int MODULE_PX = 4;
    // Hard cap on rendered QR-art bitmap area (pixels). Guards against a crafted
    // block-glyph text that would otherwise allocate a multi-GB / int-overflowing image.
    private static final long MAX_RENDER_PIXELS = 16L * 1024 * 1024;

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
        // Space & empty box — all 4 quadrants light.
        // U+00A0 NO-BREAK SPACE is included because HTML &nbsp; is the
        // canonical "won't trigger Word HTML font-fallback" trick, and we
        // see it both in defender-corpus fixtures and as a plausible
        // attacker evasion path against U+0020-only detectors.
        for (char c : new char[]{' ', ' ', '□', '▢', '◻', '⬜', '☐'}) {
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

    /** True if {@code c} is either a block or Braille QR glyph.
     *  Sextants live above the BMP and need {@link #isSextantQrCodepoint(int)}. */
    public static boolean isQrGlyph(char c) {
        return isBlockQrGlyph(c) || isBrailleQrGlyph(c);
    }

    /**
     * True if {@code codePoint} contributes at least one dark QR module. Light
     * characters remain valid renderable cells, but must not make ordinary
     * whitespace look like QR signal during the cheap probe or clustering.
     */
    static boolean isQrSignal(int codePoint) {
        if (isSextantQrCodepoint(codePoint)) {
            return true;
        }
        if (codePoint > Character.MAX_VALUE) {
            return false;
        }
        char c = (char) codePoint;
        int[] blockModules = BLOCK_MAP.get(c);
        if (blockModules != null) {
            for (int module : blockModules) {
                if (module != 0) {
                    return true;
                }
            }
            return false;
        }
        return isBrailleQrGlyph(c) && c != 0x2800;
    }

    /** True if {@code cp} is a sextant glyph (U+1FB00..U+1FB3B). The four
     *  overlap glyphs (' ', '█', '▌', '▐') that fill out the 64-pattern
     *  table are NOT counted here — they're already in {@link #BLOCK_MAP}
     *  and get picked up by the block-element scan. The sextant painter
     *  ({@link #sextantBits(int)}) understands them on top. */
    public static boolean isSextantQrCodepoint(int cp) {
        return cp >= 0x1FB00 && cp <= 0x1FB3B;
    }

    /** Returns the 6-bit module pattern for a sextant or overlap glyph, or
     *  {@code -1} if not a sextant-compatible codepoint. Bit positions:
     *  <pre>
     *  bit 0 = cell 1 (top-left)     bit 1 = cell 2 (top-right)
     *  bit 2 = cell 3 (mid-left)     bit 3 = cell 4 (mid-right)
     *  bit 4 = cell 5 (bottom-left)  bit 5 = cell 6 (bottom-right)
     *  </pre>
     *  <p>U+1FB00..U+1FB3B (60 codepoints) cover patterns 1..62 with the
     *  four "already exists" patterns (0, 21=left-half, 42=right-half, 63)
     *  delegated to U+0020, U+258C, U+2590, U+2588 respectively.</p>
     */
    public static int sextantBits(int cp) {
        if (cp == 0x0020) {
            return 0;        // SPACE — empty
        }
        if (cp == 0x2588) {
            return 63;       // FULL BLOCK
        }
        if (cp == 0x258C) {
            return 21;       // LEFT HALF BLOCK (cells 1+3+5)
        }
        if (cp == 0x2590) {
            return 42;       // RIGHT HALF BLOCK (cells 2+4+6)
        }
        if (cp >= 0x1FB00 && cp <= 0x1FB13) {
            return cp - 0x1FB00 + 1;     // patterns 1..20
        }
        if (cp >= 0x1FB14 && cp <= 0x1FB27) {
            return cp - 0x1FB00 + 2;     // patterns 22..41 (skip 21=left-half)
        }
        if (cp >= 0x1FB28 && cp <= 0x1FB3B) {
            return cp - 0x1FB00 + 3;     // patterns 43..62 (skip 21, 42)
        }
        return -1;
    }

    /** Cheap probe — total count of QR glyphs containing a dark module. */
    public static int countQrGlyphs(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isQrSignal(cp)) {
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
    public static List<ZXingCPPScanner.Result> extractAndDecode(String text,
                                                ZXingCPPScanner scanner,
                                                ZXingCPPConfig config,
                                                ParseContext context) {
        return extractAndDecode(text, scanner, config, context, null);
    }

    /**
     * Variant that shares an aggregate subprocess budget across several
     * candidate blocks from the same document.
     */
    public static List<ZXingCPPScanner.Result> extractAndDecode(
            String text, ZXingCPPScanner scanner, ZXingCPPConfig config,
            ParseContext context, ZXingCPPScanner.ScanBudget budget) {
        List<ZXingCPPScanner.Result> decoded = new ArrayList<>();
        if (text == null || text.isEmpty()
                || scanner == null
                || (budget == null && !scanner.hasZXingCPP(config))) {
            return decoded;
        }
        List<Cluster> clusters = findClusters(text);
        if (clusters.isEmpty()) {
            return decoded;
        }
        int rendered = 0;
        for (Cluster cluster : clusters) {
            if (rendered >= MAX_CLUSTERS) {
                if (budget != null) {
                    budget.rejectAdditionalScan(
                            "Unicode QR cluster scan limit exhausted");
                }
                break;
            }
            rendered++;
            Path tmp = null;
            try {
                BufferedImage img = renderCluster(cluster);
                if (img == null) {
                    continue;
                }
                tmp = Files.createTempFile("txtqr-", ".png");
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
                // Bail-on-first-decode: once we've successfully extracted at
                // least one URL the typical defender's question is answered;
                // remaining clusters are usually noise (decorative boxes in
                // the same body) and just burn ZXing subprocess time.
                if (hit) {
                    break;
                }
            } catch (IOException e) {
                throw new IllegalStateException("Unicode QR rendering failed", e);
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
        int sextantTotal = 0;
        int gapStreak = 0;

        for (String line : lines) {
            int blockGlyphs = 0;
            int brailleGlyphs = 0;
            int sextantGlyphs = 0;
            for (int i = 0; i < line.length(); ) {
                int cp = line.codePointAt(i);
                i += Character.charCount(cp);
                if (isSextantQrCodepoint(cp)) {
                    sextantGlyphs++;
                } else if (cp < 0x10000 && isBlockQrGlyph((char) cp)
                        && isQrSignal((char) cp)) {
                    blockGlyphs++;
                } else if (cp < 0x10000 && isBrailleQrGlyph((char) cp)
                        && isQrSignal((char) cp)) {
                    brailleGlyphs++;
                }
            }
            int total = blockGlyphs + brailleGlyphs + sextantGlyphs;
            if (total >= MIN_CLUSTER_LINE_GLYPHS) {
                // Strip a Tika property-label prefix (e.g., "Description: ",
                // "Summary: ") from the start of the line so the first QR
                // glyph lands at col 0. Without this the leading ASCII
                // space in "Description: <QR>" counts as a block/sextant
                // "empty" cell and the row gets shifted right relative to
                // continuation rows, breaking the bitmap. Only triggers
                // when the prefix ends with ":<whitespace>" and is followed
                // by a non-space QR glyph, so QR rows that legitimately
                // start with empty cells aren't touched.
                current.add(stripPropertyLabelPrefix(line));
                blockTotal += blockGlyphs;
                brailleTotal += brailleGlyphs;
                sextantTotal += sextantGlyphs;
                gapStreak = 0;
            } else if (!current.isEmpty() && gapStreak < CLUSTER_GAP_TOLERANCE) {
                // Tolerate a short formatting gap inside an otherwise-dense run
                current.add(line);
                gapStreak++;
            } else {
                flushCluster(current, blockTotal, brailleTotal, sextantTotal,
                        clusters);
                current.clear();
                blockTotal = 0;
                brailleTotal = 0;
                sextantTotal = 0;
                gapStreak = 0;
            }
        }
        flushCluster(current, blockTotal, brailleTotal, sextantTotal, clusters);
        return clusters;
    }

    /** Strip a Tika property-label prefix (e.g., "Description: ") from a
     *  line when the prefix is plausibly such a label — letters, then a
     *  colon, then whitespace, then a non-space QR glyph. QR rows that
     *  legitimately start with empty cells (which Tika emits as a leading
     *  ASCII space) are left untouched. */
    private static String stripPropertyLabelPrefix(String line) {
        int len = line.length();
        if (len < 3) {
            return line;
        }
        int colon = -1;
        for (int i = 0; i < len; i++) {
            char c = line.charAt(i);
            if (c == ':') {
                colon = i;
                break;
            }
            // Property labels are ASCII letters / digits / dashes only.
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_')) {
                return line;
            }
        }
        if (colon <= 0) {
            return line;
        }
        // Skip whitespace after the colon.
        int idx = colon + 1;
        while (idx < len && (line.charAt(idx) == ' ' || line.charAt(idx) == '\t'
                || line.charAt(idx) == ' ')) {
            idx++;
        }
        if (idx >= len) {
            return line;
        }
        // Next codepoint must be a non-space QR glyph to confirm this is a
        // property label sitting in front of QR content.
        int cp = line.codePointAt(idx);
        boolean confirms = (cp < 0x10000 && isQrGlyph((char) cp)
                            && cp != ' ' && cp != 0x00A0)
                || isSextantQrCodepoint(cp);
        if (!confirms) {
            return line;
        }
        return line.substring(idx);
    }

    private static void flushCluster(List<String> lines, int blockTotal,
                                     int brailleTotal, int sextantTotal,
                                     List<Cluster> out) {
        if (lines.isEmpty()) {
            return;
        }
        // Trim trailing bridge lines that have no QR content (cosmetic).
        int end = lines.size();
        while (end > 0) {
            int last = end - 1;
            String line = lines.get(last);
            boolean hasGlyph = false;
            for (int i = 0; i < line.length(); ) {
                int cp = line.codePointAt(i);
                i += Character.charCount(cp);
                if ((cp < 0x10000 && isQrSignal((char) cp))
                        || isSextantQrCodepoint(cp)) {
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
        // Pick the dominant family. When a cluster mixes (e.g., 4 sextant
        // overlap-glyphs like '█' counted as block + many real sextants),
        // sextants need to outweigh blocks to win — otherwise the block
        // renderer (which doesn't know sextants) would corrupt the bitmap.
        Mode mode;
        int minLines;
        if (sextantTotal >= brailleTotal && sextantTotal >= blockTotal
                && sextantTotal > 0) {
            mode = Mode.SEXTANT_3X2;
            minLines = MIN_CLUSTER_LINES_SEXTANT;
        } else if (brailleTotal > blockTotal) {
            mode = Mode.BRAILLE_4X2;
            minLines = MIN_CLUSTER_LINES_BRAILLE;
        } else {
            mode = Mode.BLOCK_2X2;
            minLines = MIN_CLUSTER_LINES_BLOCK;
        }
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
            case SEXTANT_3X2:
                return renderSextantCluster(cluster.lines);
            case BLOCK_2X2:
            default:
                return renderBlockCluster(cluster.lines);
        }
    }

    private static BufferedImage renderBlockCluster(String[] lines) {
        int maxCharWidth = maxGlyphCols(lines, Mode.BLOCK_2X2);
        if (maxCharWidth < 5) {
            return null;
        }
        int widthModules  = maxCharWidth * 2;
        int heightModules = lines.length * 2;
        return paint(lines, Mode.BLOCK_2X2, widthModules, heightModules,
                (g, row, col, cp, quiet) -> {
            if (cp > 0xFFFF) {
                return;
            }
            int[] mods = BLOCK_MAP.get((char) cp);
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
        int maxCharWidth = maxGlyphCols(lines, Mode.BRAILLE_4X2);
        if (maxCharWidth < 5) {
            return null;
        }
        // Braille = 4 rows × 2 cols of modules per char.
        int widthModules  = maxCharWidth * 2;
        int heightModules = lines.length * 4;
        return paint(lines, Mode.BRAILLE_4X2, widthModules, heightModules,
                (g, row, col, cp, quiet) -> {
            if (cp < 0x2800 || cp > 0x28FF) {
                return;
            }
            int dots = cp - 0x2800;
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

    private static BufferedImage renderSextantCluster(String[] lines) {
        int maxCharWidth = maxGlyphCols(lines, Mode.SEXTANT_3X2);
        if (maxCharWidth < 5) {
            return null;
        }
        // Sextant = 3 rows × 2 cols of modules per char.
        int widthModules  = maxCharWidth * 2;
        int heightModules = lines.length * 3;
        return paint(lines, Mode.SEXTANT_3X2, widthModules, heightModules,
                (g, row, col, cp, quiet) -> {
            int bits = sextantBits(cp);
            if (bits < 0) {
                return;
            }
            // Bit positions: 0=TL, 1=TR, 2=ML, 3=MR, 4=BL, 5=BR
            int[][] cellPos = {
                {0, 0}, {0, 1}, {1, 0}, {1, 1}, {2, 0}, {2, 1}
            };
            for (int b = 0; b < 6; b++) {
                if ((bits & (1 << b)) != 0) {
                    int dr = cellPos[b][0];
                    int dc = cellPos[b][1];
                    int x = (quiet + col * 2 + dc) * MODULE_PX;
                    int y = (quiet + row * 3 + dr) * MODULE_PX;
                    g.fillRect(x, y, MODULE_PX, MODULE_PX);
                }
            }
        });
    }

    /** Counts the maximum number of glyphs in any line for the given render
     *  mode. Codepoint-aware so sextants (which are surrogate pairs) count
     *  as 1 glyph, not 2. */
    private static int maxGlyphCols(String[] lines, Mode mode) {
        int maxCharWidth = 0;
        for (String line : lines) {
            int w = 0;
            for (int i = 0; i < line.length(); ) {
                int cp = line.codePointAt(i);
                i += Character.charCount(cp);
                boolean match;
                switch (mode) {
                    case BRAILLE_4X2:
                        match = (cp < 0x10000) && isBrailleQrGlyph((char) cp);
                        break;
                    case SEXTANT_3X2:
                        // Sextant glyph OR overlap glyph (' ', '█', '▌', '▐').
                        match = sextantBits(cp) >= 0;
                        break;
                    case BLOCK_2X2:
                    default:
                        match = (cp < 0x10000) && isBlockQrGlyph((char) cp);
                        break;
                }
                if (match) {
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
        void paint(Graphics2D g, int row, int col, int codepoint, int quiet);
    }

    private static BufferedImage paint(String[] lines, Mode mode,
                                       int widthModules, int heightModules,
                                       Painter painter) {
        int quiet = 4; // 4-module quiet zone is the QR spec minimum.
        // Compute as long: a crafted QR-art block (very wide + tall) drives the render
        // to billions of pixels, overflowing the int raster size and/or allocating
        // gigabytes (memory-exhaustion DoS). Computing imgW/imgH in long first stops a
        // hostile factor from wrapping the int multiply into a small positive that
        // slips past the area check. A real QR is <=177 modules/side.
        long imgWLong = ((long) widthModules  + 2 * quiet) * MODULE_PX;
        long imgHLong = ((long) heightModules + 2 * quiet) * MODULE_PX;
        if (imgWLong <= 0 || imgHLong <= 0 || imgWLong * imgHLong > MAX_RENDER_PIXELS) {
            return null;
        }
        int imgW = (int) imgWLong;
        int imgH = (int) imgHLong;
        // Per-line starting column offset: lets us pad short rows on the
        // LEFT when the first glyph looks like a non-space (suggests a
        // leading-space-strip by some upstream text extractor — e.g. RTF's
        // \par keyword-terminator behavior eating a literal leading space).
        // Rows whose first glyph IS a space stay anchored at col 0 (more
        // consistent with a trailing-space-strip).
        int maxCharWidth = (widthModules + 1) / 2;  // recover from modules
        // maxCharWidth recovery: BLOCK_2X2/BRAILLE_4X2/SEXTANT_3X2 all use
        // 2 module-cols per char, so widthModules / 2 == maxCharWidth.
        if (mode == Mode.BLOCK_2X2 || mode == Mode.BRAILLE_4X2
                || mode == Mode.SEXTANT_3X2) {
            maxCharWidth = widthModules / 2;
        }
        int[] colOffsets = computeColOffsets(lines, mode, maxCharWidth);

        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, imgW, imgH);
            g.setColor(Color.BLACK);
            for (int row = 0; row < lines.length; row++) {
                String line = lines[row];
                int col = colOffsets[row];
                for (int i = 0; i < line.length(); ) {
                    int cp = line.codePointAt(i);
                    i += Character.charCount(cp);
                    boolean isGlyph;
                    switch (mode) {
                        case BRAILLE_4X2:
                            isGlyph = (cp < 0x10000) && isBrailleQrGlyph((char) cp);
                            break;
                        case SEXTANT_3X2:
                            isGlyph = sextantBits(cp) >= 0;
                            break;
                        case BLOCK_2X2:
                        default:
                            isGlyph = (cp < 0x10000) && isBlockQrGlyph((char) cp);
                            break;
                    }
                    if (!isGlyph) {
                        continue;
                    }
                    painter.paint(g, row, col, cp, quiet);
                    col++;
                }
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    /**
     * Per-line column offset for the painter. If a line has fewer glyphs
     * than {@code maxCharWidth}, its offset is set to
     * {@code maxCharWidth - lineGlyphs} when the line's first glyph is a
     * non-space block (heuristic: a leading space was stripped by an
     * upstream text extractor). Lines whose first glyph IS a space, or
     * whose glyph count equals {@code maxCharWidth}, get offset 0.
     */
    private static int[] computeColOffsets(String[] lines, Mode mode, int maxCharWidth) {
        int[] offsets = new int[lines.length];
        for (int r = 0; r < lines.length; r++) {
            int count = 0;
            int firstCp = -1;
            for (int i = 0; i < lines[r].length(); ) {
                int cp = lines[r].codePointAt(i);
                i += Character.charCount(cp);
                boolean isGlyph;
                switch (mode) {
                    case BRAILLE_4X2:
                        isGlyph = (cp < 0x10000) && isBrailleQrGlyph((char) cp);
                        break;
                    case SEXTANT_3X2:
                        isGlyph = sextantBits(cp) >= 0;
                        break;
                    case BLOCK_2X2:
                    default:
                        isGlyph = (cp < 0x10000) && isBlockQrGlyph((char) cp);
                        break;
                }
                if (!isGlyph) {
                    continue;
                }
                if (firstCp < 0) {
                    firstCp = cp;
                }
                count++;
            }
            int gap = maxCharWidth - count;
            if (gap > 0 && firstCp >= 0 && firstCp != ' ' && firstCp != 0x00A0) {
                offsets[r] = gap;
            }
        }
        return offsets;
    }
}
