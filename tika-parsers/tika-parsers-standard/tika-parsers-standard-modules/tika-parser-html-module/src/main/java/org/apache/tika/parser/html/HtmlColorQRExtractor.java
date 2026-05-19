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
package org.apache.tika.parser.html;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.jsoup.select.NodeVisitor;

import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.image.ZXingCPPConfig;
import org.apache.tika.parser.image.ZXingCPPScanner;

/**
 * Reconstructs and decodes QR codes that are rendered in HTML using CSS
 * coloring rather than distinct Unicode glyphs. Same evasion vector the
 * Sublime ICS-phishing writeup hints at when describing HTML-attachment
 * phishing kits.
 */
public final class HtmlColorQRExtractor {

    /** Pixel size of each QR module in the rendered bitmap. */
    private static final int MODULE_PX = 4;

    /** Minimum cluster dimensions to attempt decoding. */
    private static final int MIN_CLUSTER_LINES = 6;
    private static final int MIN_CLUSTER_COLS = 6;

    /** Max clusters per HTML document. */
    private static final int MAX_CLUSTERS = 4;

    /** Luminance threshold below which a colour counts as "dark". 0..255. */
    private static final int DARK_LUMA_THRESHOLD = 128;

    private HtmlColorQRExtractor() { }

    /**
     * Scan {@code doc} for CSS-colored QR codes and return the decoded text
     * values. Bails on the first successful decode.
     */
    public static List<String> extractAndDecode(Document doc,
                                                ZXingCPPScanner scanner,
                                                ZXingCPPConfig config,
                                                ParseContext context) {
        List<String> decoded = new ArrayList<>();
        if (doc == null || scanner == null || !scanner.hasZXingCPP()) {
            return decoded;
        }
        Map<String, String> classRules = parseStylesheets(doc);
        List<List<List<Cell>>> clusters = findClusters(doc, classRules);
        if (clusters.isEmpty()) {
            return decoded;
        }
        int rendered = 0;
        for (List<List<Cell>> cluster : clusters) {
            if (rendered++ >= MAX_CLUSTERS) {
                break;
            }
            Path tmp = null;
            try {
                BufferedImage img = renderCluster(cluster);
                if (img == null) {
                    continue;
                }
                tmp = Files.createTempFile("htmlcolorqr-", ".png");
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
                if (hit) {
                    break;
                }
            } catch (IOException e) {
                // best-effort
            } catch (RuntimeException e) {
                // best-effort
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

    /** Per-character classification result. */
    static final class Cell {
        final boolean dark;
        Cell(boolean dark) { this.dark = dark; }
    }

    /**
     * Find candidate clusters: one per {@code <pre>} or {@code <code>} block
     * with sufficient character grid density.
     */
    static List<List<List<Cell>>> findClusters(Document doc,
                                               Map<String, String> classRules) {
        List<List<List<Cell>>> clusters = new ArrayList<>();
        Elements blocks = doc.select("pre, code");
        for (Element block : blocks) {
            List<List<Cell>> grid = buildGrid(block, classRules);
            if (grid.size() < MIN_CLUSTER_LINES) {
                continue;
            }
            int maxCols = 0;
            for (List<Cell> row : grid) {
                if (row.size() > maxCols) {
                    maxCols = row.size();
                }
            }
            if (maxCols < MIN_CLUSTER_COLS) {
                continue;
            }
            clusters.add(grid);
        }
        return clusters;
    }

    /**
     * Walk the block's text nodes; for each non-newline char, classify its
     * visible color and append a Cell. Newlines and {@code <br>} start a
     * new row.
     */
    private static List<List<Cell>> buildGrid(Element block,
                                               Map<String, String> classRules) {
        List<List<Cell>> grid = new ArrayList<>();
        List<Cell> currentRow = new ArrayList<>();
        grid.add(currentRow);

        for (Node child : descendants(block)) {
            if (child instanceof Element) {
                Element el = (Element) child;
                if ("br".equalsIgnoreCase(el.tagName())) {
                    currentRow = new ArrayList<>();
                    grid.add(currentRow);
                }
                continue;
            }
            if (!(child instanceof TextNode)) {
                continue;
            }
            String text = ((TextNode) child).getWholeText();
            Element parent = ((TextNode) child).parent() instanceof Element
                    ? (Element) ((TextNode) child).parent() : null;
            EffectiveStyle eff = resolveEffectiveStyle(parent, classRules);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\n' || c == '\r') {
                    currentRow = new ArrayList<>();
                    grid.add(currentRow);
                    continue;
                }
                currentRow.add(classifyCell(c, eff));
            }
        }
        while (!grid.isEmpty() && grid.get(grid.size() - 1).isEmpty()) {
            grid.remove(grid.size() - 1);
        }
        return grid;
    }

    /**
     * Classify a single rendered char into a dark/light cell.
     * <ul>
     *   <li>Whitespace: cell takes the background color.</li>
     *   <li>Known dark glyph (█ # @ ■ ▓ ▒ ◼ ⬛): cell takes the
     *       foreground (text) color.</li>
     *   <li>Otherwise: default to background (most CSS-QR kits paint
     *       via background-color on a neutral glyph).</li>
     * </ul>
     */
    private static Cell classifyCell(char c, EffectiveStyle eff) {
        boolean isWhitespace = c == ' ' || c == '\t' || c == ' ';
        boolean isDarkGlyph = c == '█' || c == '#' || c == '@' || c == '■'
                || c == '▓' || c == '▒' || c == '◼' || c == '⬛';
        int luma;
        if (isWhitespace) {
            luma = eff.bgLuma;
        } else if (isDarkGlyph) {
            luma = eff.fgLuma;
        } else {
            luma = eff.bgLuma;
        }
        return new Cell(luma < DARK_LUMA_THRESHOLD);
    }

    private static final class EffectiveStyle {
        final int fgLuma;
        final int bgLuma;
        EffectiveStyle(int fgLuma, int bgLuma) {
            this.fgLuma = fgLuma;
            this.bgLuma = bgLuma;
        }
    }

    private static EffectiveStyle resolveEffectiveStyle(
            Element start, Map<String, String> classRules) {
        int fgLuma = 0;
        int bgLuma = 255;
        boolean fgFound = false;
        boolean bgFound = false;
        Element el = start;
        while (el != null) {
            String style = combinedStyle(el, classRules);
            if (!fgFound) {
                Integer fg = readColor(style, "color");
                if (fg != null) {
                    fgLuma = fg;
                    fgFound = true;
                }
            }
            if (!bgFound) {
                Integer bg = readColor(style, "background-color");
                if (bg == null) {
                    bg = readBackgroundShorthand(style);
                }
                if (bg == null) {
                    String bgAttr = el.attr("bgcolor");
                    if (!bgAttr.isEmpty()) {
                        bg = parseColor(bgAttr);
                    }
                }
                if (bg != null) {
                    bgLuma = bg;
                    bgFound = true;
                }
            }
            if (fgFound && bgFound) {
                break;
            }
            el = el.parent() instanceof Element ? (Element) el.parent() : null;
        }
        return new EffectiveStyle(fgLuma, bgLuma);
    }

    private static String combinedStyle(Element el, Map<String, String> rules) {
        StringBuilder sb = new StringBuilder();
        String tagRule = rules.get(el.tagName().toLowerCase(Locale.ROOT));
        if (tagRule != null) {
            sb.append(tagRule).append(';');
        }
        for (String cls : el.classNames()) {
            String r = rules.get("." + cls.toLowerCase(Locale.ROOT));
            if (r != null) {
                sb.append(r).append(';');
            }
        }
        String id = el.id();
        if (!id.isEmpty()) {
            String r = rules.get("#" + id.toLowerCase(Locale.ROOT));
            if (r != null) {
                sb.append(r).append(';');
            }
        }
        String inline = el.attr("style");
        if (!inline.isEmpty()) {
            sb.append(inline);
        }
        return sb.toString();
    }

    /**
     * Parse {@code <style>} blocks, returning a map of selector → declaration
     * string. Only simple tag/class/id selectors are understood.
     */
    static Map<String, String> parseStylesheets(Document doc) {
        Map<String, String> out = new HashMap<>();
        Elements styles = doc.select("style");
        for (Element styleEl : styles) {
            String css = styleEl.data();
            css = css.replaceAll("(?s)/\\*.*?\\*/", "");
            Matcher m = Pattern.compile("([^{}]+)\\{([^{}]*)\\}").matcher(css);
            while (m.find()) {
                String selectors = m.group(1).trim();
                String decl = m.group(2).trim();
                for (String sel : selectors.split(",")) {
                    sel = sel.trim().toLowerCase(Locale.ROOT);
                    if (sel.isEmpty()) {
                        continue;
                    }
                    if (sel.matches("^[a-z][a-z0-9-]*$")
                            || sel.matches("^\\.[a-z0-9_-]+$")
                            || sel.matches("^#[a-z0-9_-]+$")) {
                        out.put(sel, decl);
                    }
                }
            }
        }
        return out;
    }

    static Integer readColor(String style, String property) {
        Matcher m = Pattern.compile(
                "(?i)(?:^|;|\\s)" + Pattern.quote(property) + "\\s*:\\s*([^;]+)"
        ).matcher(style);
        if (!m.find()) {
            return null;
        }
        return parseColor(m.group(1).trim());
    }

    static Integer readBackgroundShorthand(String style) {
        Matcher m = Pattern.compile(
                "(?i)(?:^|;|\\s)background\\s*:\\s*([^;]+)"
        ).matcher(style);
        if (!m.find()) {
            return null;
        }
        String value = m.group(1).trim();
        Matcher hex = Pattern.compile("#[0-9a-fA-F]{3,8}").matcher(value);
        if (hex.find()) {
            return parseColor(hex.group());
        }
        Matcher rgb = Pattern.compile("(?i)rgba?\\([^)]+\\)").matcher(value);
        if (rgb.find()) {
            return parseColor(rgb.group());
        }
        for (String tok : value.split("\\s+")) {
            Integer c = parseColor(tok);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    /** Parse a CSS color value into perceptual luminance (0..255). */
    static Integer parseColor(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.startsWith("#")) {
            v = v.substring(1);
            try {
                int r;
                int g;
                int b;
                if (v.length() == 3) {
                    r = Integer.parseInt(v.substring(0, 1).repeat(2), 16);
                    g = Integer.parseInt(v.substring(1, 2).repeat(2), 16);
                    b = Integer.parseInt(v.substring(2, 3).repeat(2), 16);
                } else if (v.length() == 6 || v.length() == 8) {
                    r = Integer.parseInt(v.substring(0, 2), 16);
                    g = Integer.parseInt(v.substring(2, 4), 16);
                    b = Integer.parseInt(v.substring(4, 6), 16);
                } else {
                    return null;
                }
                return luma(r, g, b);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (v.startsWith("rgb")) {
            Matcher m = Pattern.compile(
                    "rgba?\\(\\s*([0-9]+)\\s*,\\s*([0-9]+)\\s*,\\s*([0-9]+)"
            ).matcher(v);
            if (m.find()) {
                int r = clamp(Integer.parseInt(m.group(1)));
                int g = clamp(Integer.parseInt(m.group(2)));
                int b = clamp(Integer.parseInt(m.group(3)));
                return luma(r, g, b);
            }
            return null;
        }
        switch (v) {
            case "black":     return 0;
            case "white":     return 255;
            case "red":       return luma(0xFF, 0x00, 0x00);
            case "green":     return luma(0x00, 0x80, 0x00);
            case "blue":      return luma(0x00, 0x00, 0xFF);
            case "yellow":    return luma(0xFF, 0xFF, 0x00);
            case "cyan":      return luma(0x00, 0xFF, 0xFF);
            case "magenta":   return luma(0xFF, 0x00, 0xFF);
            case "gray":
            case "grey":      return luma(0x80, 0x80, 0x80);
            case "transparent":
                return null;
            default:
                return null;
        }
    }

    private static int clamp(int x) {
        return Math.max(0, Math.min(255, x));
    }

    private static int luma(int r, int g, int b) {
        return (int) (0.299 * r + 0.587 * g + 0.114 * b);
    }

    /**
     * Render a 2D cell grid to a 2-colour bitmap suitable for ZXing. Rows
     * with different column counts are padded with light cells.
     */
    static BufferedImage renderCluster(List<List<Cell>> grid) {
        if (grid == null || grid.isEmpty()) {
            return null;
        }
        int maxCols = 0;
        for (List<Cell> row : grid) {
            if (row.size() > maxCols) {
                maxCols = row.size();
            }
        }
        if (maxCols < MIN_CLUSTER_COLS) {
            return null;
        }
        int quiet = 4;
        int imgW = (maxCols + 2 * quiet) * MODULE_PX;
        int imgH = (grid.size() + 2 * quiet) * MODULE_PX;
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

    private static List<Node> descendants(Element root) {
        final List<Node> out = new ArrayList<>();
        root.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node != root) {
                    out.add(node);
                }
            }

            @Override
            public void tail(Node node, int depth) {
                // no-op
            }
        });
        return out;
    }
}
