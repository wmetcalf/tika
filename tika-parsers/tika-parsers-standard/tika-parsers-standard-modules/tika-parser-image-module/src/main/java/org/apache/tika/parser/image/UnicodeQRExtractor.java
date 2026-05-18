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
 * Recovers QR codes drawn in plaintext using Unicode block-element glyphs and
 * hands the reconstructed bitmap to {@link ZXingCPPScanner} for decoding.
 *
 * <p>Phishing kits sometimes embed a scannable QR code directly in a meeting
 * body, an email plaintext part, or an HTML <code>&lt;pre&gt;</code> block by
 * arranging block-element codepoints (U+2580..U+259F) in a 21x21+ grid. A
 * phone camera can scan those off the screen even though no image was
 * attached. Image-based scanners miss these entirely.</p>
 *
 * <p>This class is a Java port of the encoder half of
 * <a href="https://github.com/wmetcalf/txtqr_one_vision">wmetcalf/txtqr_one_vision</a>.
 * It uses the same {@code char_to_modules} table the upstream project
 * validated against in-the-wild phishing samples — each supported character
 * maps to a 2x2 sub-block of QR modules. A cluster of these chars laid out as
 * lines becomes a bitmap.</p>
 */
public final class UnicodeQRExtractor {

    /** Module grid pattern per character. Each char encodes a 2x2 quadrant:
     *  index 0/1 = top-left / top-right, 2/3 = bottom-left / bottom-right.
     *  1 = dark module, 0 = light. */
    private static final Map<Character, int[]> CHAR_TO_MODULES = buildCharMap();

    /** Minimum cluster-line count to attempt decoding — below this almost
     *  certainly isn't a QR code. A Micro-QR is 11x11 modules = ~6 char rows. */
    private static final int MIN_CLUSTER_LINES = 6;

    /** Minimum density of QR-glyph chars per line to count it as part of a
     *  cluster. Real QR rows are dense; lines that are mostly normal text
     *  with one stray ■ shouldn't merge into a cluster. */
    private static final int MIN_CLUSTER_LINE_GLYPHS = 6;

    /** Pixel size of each QR module in the rendered bitmap. ZXing needs at
     *  least a few pixels per module to lock on; 4 is a safe default. */
    private static final int MODULE_PX = 4;

    /** Cap on number of clusters we'll render+decode per call to keep latency
     *  predictable on adversarial input full of decorative ASCII boxes. */
    private static final int MAX_CLUSTERS = 4;

    private UnicodeQRExtractor() { }

    private static Map<Character, int[]> buildCharMap() {
        Map<Character, int[]> m = new HashMap<>();
        // Full block & equivalents — all 4 quadrants dark
        for (char c : new char[]{'█', '■', '▓', '▇', '▆', '▅', '▃', '▂', '▁', '#', '@'}) {
            m.put(c, new int[]{1, 1, 1, 1});
        }
        // Space & empty box — all 4 quadrants light
        m.put(' ', new int[]{0, 0, 0, 0});
        m.put('□', new int[]{0, 0, 0, 0});
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
        return m;
    }

    /** True if {@code c} is one of the validated QR-art glyphs. */
    public static boolean isQrGlyph(char c) {
        return CHAR_TO_MODULES.containsKey(c);
    }

    /** Cheap probe — does the string contain enough QR-glyph chars to make
     *  scanning worthwhile? Returns the count without scanning further. */
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
     * Find dense clusters of QR-glyph characters in {@code text}, render each
     * one to a PNG, hand it to {@link ZXingCPPScanner#scan} and return the
     * decoded text values.
     *
     * <p>If {@code scanner} is null or ZXing isn't available, the method
     * returns an empty list — callers should still consult
     * {@link #countQrGlyphs(String)} for a detection-only signal.</p>
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
        List<String[]> clusters = findClusters(text);
        if (clusters.isEmpty()) {
            return decoded;
        }
        int rendered = 0;
        for (String[] cluster : clusters) {
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
                for (ZXingCPPScanner.Result r : results) {
                    String t = r.getText();
                    if (t != null && !t.isEmpty()) {
                        decoded.add(t);
                    }
                }
            } catch (IOException e) {
                // Best-effort — log and continue
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

    /**
     * Split {@code text} into maximal contiguous run-of-lines where each line
     * contains enough QR-glyph characters to plausibly be part of a code.
     * Each returned array is one cluster's lines, in order.
     */
    static List<String[]> findClusters(String text) {
        List<String[]> clusters = new ArrayList<>();
        String[] lines = text.split("\\r\\n?|\\n");
        List<String> current = new ArrayList<>();
        for (String line : lines) {
            int glyphs = 0;
            for (int i = 0; i < line.length(); i++) {
                if (CHAR_TO_MODULES.containsKey(line.charAt(i))) {
                    glyphs++;
                }
            }
            if (glyphs >= MIN_CLUSTER_LINE_GLYPHS) {
                current.add(line);
            } else {
                if (current.size() >= MIN_CLUSTER_LINES) {
                    clusters.add(current.toArray(new String[0]));
                }
                current.clear();
            }
        }
        if (current.size() >= MIN_CLUSTER_LINES) {
            clusters.add(current.toArray(new String[0]));
        }
        return clusters;
    }

    /**
     * Render a single cluster (already-split lines of QR-glyph chars) to a
     * 2-colour {@link BufferedImage}. Each char produces 2 modules across and
     * 2 modules down; each module is {@link #MODULE_PX} pixels.
     */
    static BufferedImage renderCluster(String[] lines) {
        if (lines == null || lines.length == 0) {
            return null;
        }
        int maxCharWidth = 0;
        for (String line : lines) {
            // Count only QR-glyph chars per line to compute the width — leading
            // / trailing punctuation in the same line shouldn't shift the grid.
            int w = trailingQrCharCount(line);
            if (w > maxCharWidth) {
                maxCharWidth = w;
            }
        }
        if (maxCharWidth < 5) {
            return null;
        }
        int widthModules  = maxCharWidth * 2;
        int heightModules = lines.length * 2;
        // Add a 4-module quiet zone — required by the QR spec for reliable lock.
        int quiet = 4;
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
                    int[] mods = CHAR_TO_MODULES.get(line.charAt(i));
                    if (mods == null) {
                        continue;
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
                    col++;
                }
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    private static int trailingQrCharCount(String line) {
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            if (CHAR_TO_MODULES.containsKey(line.charAt(i))) {
                count++;
            }
        }
        return count;
    }
}
