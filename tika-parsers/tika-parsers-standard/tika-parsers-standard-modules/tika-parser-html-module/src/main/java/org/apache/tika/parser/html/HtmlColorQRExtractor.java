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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
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

    /** A real QR code is at most 177x177 modules; leave room for noisy grids. */
    private static final int MAX_GRID_UNITS = 256 * 1024;
    private static final int MAX_DOCUMENT_GRID_UNITS = MAX_GRID_UNITS * MAX_CLUSTERS;
    private static final long MAX_RENDERED_PIXELS = 16L * 1024 * 1024;
    private static final int MAX_STYLESHEET_CHARS = 256 * 1024;
    private static final int MAX_STYLESHEET_SELECTORS = 4_096;
    private static final int MAX_CELL_TEXT_SCAN_CHARS = 4_096;
    private static final int MAX_COMBINED_STYLE_CHARS = 64 * 1024;
    private static final int MAX_STYLE_RESOLUTION_CHARS = 2 * 1024 * 1024;
    private static final int MAX_STYLE_RULE_LOOKUPS = 64 * 1024;
    private static final int MAX_DATA_URI_HEADER_CHARS = 1_024;
    private static final String STYLESHEET_LIMIT_WARNING =
            "HTML color-QR stylesheet limit reached; color-QR extraction is incomplete";
    private static final String COLOR_QR_LIMIT_WARNING =
            "HTML color-QR analysis limit reached; color-QR extraction is incomplete";
    private static final Pattern SIMPLE_CSS_SELECTOR = Pattern.compile(
            "^(?:[a-z][a-z0-9-]*|\\.[a-z0-9_-]+|#[a-z0-9_-]+)$");
    private static final Pattern COLOR_DECLARATION = Pattern.compile(
            "(?i)(?:^|;|\\s)color\\s*:\\s*([^;]+)");
    private static final Pattern BACKGROUND_COLOR_DECLARATION = Pattern.compile(
            "(?i)(?:^|;|\\s)background-color\\s*:\\s*([^;]+)");
    private static final Pattern BACKGROUND_DECLARATION = Pattern.compile(
            "(?i)(?:^|;|\\s)background\\s*:\\s*([^;]+)");
    private static final Pattern BACKGROUND_HEX_COLOR = Pattern.compile(
            "#[0-9a-fA-F]{3,8}");
    private static final Pattern BACKGROUND_RGB_COLOR = Pattern.compile(
            "(?i)rgba?\\([^)]+\\)");
    private static final Pattern TRANSPARENT_TOKEN = Pattern.compile(
            "(?i)(?:^|[\\s/,])transparent(?:$|[\\s/,])");
    private static final Pattern RGB_COMMA_COLOR = Pattern.compile(
            "rgb\\(\\s*([0-9]+)\\s*,\\s*([0-9]+)\\s*,\\s*([0-9]+)\\s*\\)");
    private static final Pattern RGBA_COMMA_COLOR = Pattern.compile(
            "rgba\\(\\s*([0-9]+)\\s*,\\s*([0-9]+)\\s*,\\s*([0-9]+)"
                    + "\\s*,\\s*([0-9.]+%?)\\s*\\)");
    private static final Pattern MODERN_RGB_COLOR = Pattern.compile(
            ".*rgba?\\([^,)]*\\s+[^,)]*\\).*");
    private static final Pattern ALPHA_HEX_COLOR = Pattern.compile(
            "#(?:[0-9a-f]{4}|[0-9a-f]{8})");
    private static final Pattern CSS_IDENTIFIER_COLOR = Pattern.compile(
            "[a-z][a-z0-9-]*");

    /** Luminance threshold below which a colour counts as "dark". 0..255. */
    private static final int DARK_LUMA_THRESHOLD = 128;

    private HtmlColorQRExtractor() { }

    /**
     * Scan {@code doc} for CSS-colored QR codes and return the decoded text
     * values. Bails on the first successful decode.
     */
    public static List<ZXingCPPScanner.Result> extractAndDecode(Document doc,
                                                ZXingCPPScanner scanner,
                                                ZXingCPPConfig config,
                                                ParseContext context) {
        return extractAndDecode(doc, scanner, config, context, null);
    }

    public static List<ZXingCPPScanner.Result> extractAndDecode(Document doc,
                                                ZXingCPPScanner scanner,
                                                ZXingCPPConfig config,
                                                ParseContext context,
                                                Metadata metadata) {
        List<ZXingCPPScanner.Result> decoded = new ArrayList<>();
        if (doc == null || scanner == null || !scanner.hasZXingCPP()) {
            return decoded;
        }
        StylesheetParseResult stylesheetResult = parseStylesheetsBounded(doc);
        if (stylesheetResult.truncated && metadata != null) {
            metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                    STYLESHEET_LIMIT_WARNING);
            if (metadata.get("ExploitClass") == null) {
                metadata.set("ExploitClass",
                        "HTML color-QR extraction incomplete; encoded link content may be hidden");
            }
        }
        ClusterSearchResult clusterResult =
                findClustersBounded(doc, stylesheetResult.rules);
        if (clusterResult.incomplete && metadata != null) {
            markAnalysisIncomplete(metadata, COLOR_QR_LIMIT_WARNING);
        }
        List<List<List<Cell>>> clusters = clusterResult.clusters;
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
                for (ZXingCPPScanner.Result r : results) {
                    String t = r.getText();
                    if (t != null && !t.isEmpty()) {
                        decoded.add(r);
                    }
                }
            } catch (IOException | RuntimeException e) {
                if (metadata != null) {
                    markAnalysisIncomplete(metadata,
                            "HTML color-QR scanner failed: "
                                    + e.getClass().getSimpleName());
                }
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
     * Find candidate clusters from two HTML rendering modes:
     * <ul>
     *   <li>{@code <pre>} / {@code <code>} blocks — each char is one cell,
     *       its color drives dark/light classification.</li>
     *   <li>{@code <table>} grids — each {@code <td>} (or {@code <th>}) is
     *       one cell, taking its color from {@code bgcolor=""} or style.
     *       Common in HTML phishing-kit QR rendering because Outlook /
     *       Gmail rendering preserves table cell backgrounds better than
     *       inline-span backgrounds.</li>
     * </ul>
     */
    static List<List<List<Cell>>> findClusters(Document doc,
                                               Map<String, String> classRules) {
        return findClustersBounded(doc, classRules).clusters;
    }

    private static ClusterSearchResult findClustersBounded(
            Document doc, Map<String, String> classRules) {
        List<List<List<Cell>>> clusters = new ArrayList<>();
        GridBudget budget = new GridBudget();
        StyleResolutionContext styles =
                new StyleResolutionContext(classRules, new StyleBudget());
        // Mode 1: <pre>/<code>
        try (Stream<Element> elements = doc.nodeStream(Element.class)) {
            Iterator<Element> iterator = elements.iterator();
            while (iterator.hasNext()) {
                Element block = iterator.next();
                if (!"pre".equalsIgnoreCase(block.tagName())
                        && !"code".equalsIgnoreCase(block.tagName())) {
                    continue;
                }
                if (clusters.size() >= MAX_CLUSTERS || budget.exhausted()) {
                    budget.markIncomplete();
                    break;
                }
                List<List<Cell>> grid = buildGrid(block, styles, budget);
                if (grid != null && grid.size() >= MIN_CLUSTER_LINES
                        && maxCols(grid) >= MIN_CLUSTER_COLS) {
                    clusters.add(grid);
                }
            }
        }
        // Mode 2: <table> grids — one row per <tr>, one cell per <td>/<th>.
        try (Stream<Element> elements = doc.nodeStream(Element.class)) {
            Iterator<Element> iterator = elements.iterator();
            while (iterator.hasNext()) {
                Element table = iterator.next();
                if (!"table".equalsIgnoreCase(table.tagName())) {
                    continue;
                }
                if (clusters.size() >= MAX_CLUSTERS || budget.exhausted()) {
                    budget.markIncomplete();
                    break;
                }
                List<List<Cell>> grid = buildTableGrid(table, styles, budget);
                if (grid != null && grid.size() >= MIN_CLUSTER_LINES
                        && maxCols(grid) >= MIN_CLUSTER_COLS) {
                    clusters.add(grid);
                }
            }
        }
        return new ClusterSearchResult(
                clusters, budget.incomplete || styles.budget.incomplete);
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

    /**
     * Build a cell grid from a {@code <table>}: each {@code <tr>} is a row,
     * each {@code <td>}/{@code <th>} is a cell whose color is resolved from
     * the cell's own bgcolor/style or the inherited row/table.
     */
    private static List<List<Cell>> buildTableGrid(Element table,
                                                   StyleResolutionContext styles,
                                                   GridBudget budget) {
        List<List<Cell>> grid = new ArrayList<>();
        int[] candidateUnits = new int[1];
        for (Node childNode : table.childNodes()) {
            if (!(childNode instanceof Element)) {
                continue;
            }
            Element child = (Element) childNode;
            if ("tr".equalsIgnoreCase(child.tagName())) {
                if (!appendTableRow(child, grid, styles, budget, candidateUnits)) {
                    return null;
                }
                continue;
            }
            if (!"thead".equalsIgnoreCase(child.tagName())
                    && !"tbody".equalsIgnoreCase(child.tagName())
                    && !"tfoot".equalsIgnoreCase(child.tagName())) {
                continue;
            }
            for (Node sectionChildNode : child.childNodes()) {
                if (!(sectionChildNode instanceof Element)) {
                    continue;
                }
                Element sectionChild = (Element) sectionChildNode;
                if ("tr".equalsIgnoreCase(sectionChild.tagName())) {
                    if (!appendTableRow(
                            sectionChild, grid, styles, budget, candidateUnits)) {
                        return null;
                    }
                }
            }
        }
        return grid;
    }

    private static boolean appendTableRow(
            Element tr, List<List<Cell>> grid, StyleResolutionContext styles,
            GridBudget budget, int[] candidateUnits) {
        if (!budget.consume() || ++candidateUnits[0] > MAX_GRID_UNITS) {
            budget.markIncomplete();
            return false;
        }
        List<Cell> row = new ArrayList<>();
        for (Node cellNode : tr.childNodes()) {
            if (!(cellNode instanceof Element)) {
                continue;
            }
            Element td = (Element) cellNode;
            if (!"td".equalsIgnoreCase(td.tagName())
                    && !"th".equalsIgnoreCase(td.tagName())) {
                continue;
            }
            if (!budget.consume() || ++candidateUnits[0] > MAX_GRID_UNITS) {
                budget.markIncomplete();
                return false;
            }
            EffectiveStyle eff = styles.resolve(td);
            char ref = firstRepresentativeCharacter(td);
            row.add(classifyCell(ref, eff));
        }
        grid.add(row);
        return true;
    }

    private static char firstRepresentativeCharacter(Element cell) {
        CellTextProbe probe = new CellTextProbe();
        try {
            cell.traverse(probe);
        } catch (CellTextProbeComplete e) {
            // The probe stops as soon as it finds a visible character or
            // reaches its bounded scan budget.
        }
        return probe.character;
    }

    private static final class CellTextProbe implements NodeVisitor {
        private int inspected;
        private char character = ' ';

        @Override
        public void head(Node node, int depth) {
            if (!(node instanceof TextNode)) {
                return;
            }
            String text = ((TextNode) node).getWholeText();
            for (int i = 0; i < text.length(); i++) {
                if (++inspected > MAX_CELL_TEXT_SCAN_CHARS) {
                    throw CellTextProbeComplete.INSTANCE;
                }
                if (!Character.isWhitespace(text.charAt(i))) {
                    character = text.charAt(i);
                    throw CellTextProbeComplete.INSTANCE;
                }
            }
        }

        @Override
        public void tail(Node node, int depth) {
            // no-op
        }
    }

    private static final class CellTextProbeComplete extends RuntimeException {
        private static final CellTextProbeComplete INSTANCE =
                new CellTextProbeComplete();

        private CellTextProbeComplete() {
            super(null, null, false, false);
        }
    }

    /**
     * Walk the block's text nodes; for each non-newline char, classify its
     * visible color and append a Cell. Newlines and {@code <br>} start a
     * new row.
     */
    private static List<List<Cell>> buildGrid(Element block,
                                               StyleResolutionContext styles,
                                               GridBudget budget) {
        GridAccumulator accumulator =
                new GridAccumulator(styles, budget);
        try {
            block.traverse(accumulator);
        } catch (GridLimitException e) {
            budget.markIncomplete();
            return null;
        }
        List<List<Cell>> grid = accumulator.grid;
        while (!grid.isEmpty() && grid.get(grid.size() - 1).isEmpty()) {
            grid.remove(grid.size() - 1);
        }
        return grid;
    }

    private static final class GridAccumulator implements NodeVisitor {
        private final StyleResolutionContext styles;
        private final GridBudget budget;
        private final List<List<Cell>> grid = new ArrayList<>();
        private List<Cell> currentRow = new ArrayList<>();
        private int candidateUnits;

        private GridAccumulator(StyleResolutionContext styles, GridBudget budget) {
            this.styles = styles;
            this.budget = budget;
            grid.add(currentRow);
        }

        @Override
        public void head(Node node, int depth) {
            if (depth == 0) {
                return;
            }
            consumeUnit();
            if (node instanceof Element) {
                Element element = (Element) node;
                if ("br".equalsIgnoreCase(element.tagName())) {
                    startRow();
                }
                return;
            }
            if (!(node instanceof TextNode)) {
                return;
            }
            TextNode textNode = (TextNode) node;
            Element parent = textNode.parent() instanceof Element
                    ? (Element) textNode.parent() : null;
            EffectiveStyle effectiveStyle = styles.resolve(parent);
            String text = textNode.getWholeText();
            for (int i = 0; i < text.length(); i++) {
                consumeUnit();
                char character = text.charAt(i);
                if (character == '\n' || character == '\r') {
                    startRow();
                } else {
                    currentRow.add(classifyCell(character, effectiveStyle));
                }
            }
        }

        @Override
        public void tail(Node node, int depth) {
            // no-op
        }

        private void startRow() {
            currentRow = new ArrayList<>();
            grid.add(currentRow);
        }

        private void consumeUnit() {
            if (!budget.consume() || ++candidateUnits > MAX_GRID_UNITS) {
                throw GridLimitException.INSTANCE;
            }
        }
    }

    private static final class GridBudget {
        private int consumed;
        private boolean incomplete;

        private boolean consume() {
            if (consumed >= MAX_DOCUMENT_GRID_UNITS) {
                return false;
            }
            consumed++;
            return true;
        }

        private boolean exhausted() {
            return consumed >= MAX_DOCUMENT_GRID_UNITS;
        }

        private void markIncomplete() {
            incomplete = true;
        }
    }

    private static final class GridLimitException extends RuntimeException {
        private static final GridLimitException INSTANCE = new GridLimitException();

        private GridLimitException() {
            super(null, null, false, false);
        }
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
        boolean isDarkGlyph = c == '█' || c == '#' || c == '@' || c == '■'
                || c == '▓' || c == '▒' || c == '◼' || c == '⬛';
        int luma;
        if (isDarkGlyph && eff.fgFound) {
            luma = eff.fgLuma;
        } else if (eff.bgFound) {
            luma = eff.bgLuma;
        } else if (eff.fgFound) {
            luma = eff.fgLuma;
        } else if (isDarkGlyph) {
            luma = 0;
        } else {
            luma = 255;
        }
        return new Cell(luma < DARK_LUMA_THRESHOLD);
    }

    private static final class EffectiveStyle {
        final int fgLuma;
        final int bgLuma;
        final boolean fgFound;
        final boolean bgFound;
        EffectiveStyle(int fgLuma, int bgLuma, boolean fgFound, boolean bgFound) {
            this.fgLuma = fgLuma;
            this.bgLuma = bgLuma;
            this.fgFound = fgFound;
            this.bgFound = bgFound;
        }
    }

    private static final class StyleResolutionContext {
        private final Map<String, String> rules;
        private final StyleBudget budget;
        private final Map<Element, String> combinedStyles = new IdentityHashMap<>();
        private final Map<Element, EffectiveStyle> effectiveStyles = new IdentityHashMap<>();
        private final Map<Element, ForegroundStyle> foregroundStyles =
                new IdentityHashMap<>();

        private StyleResolutionContext(Map<String, String> rules, StyleBudget budget) {
            this.rules = rules;
            this.budget = budget;
        }

        private EffectiveStyle resolve(Element start) {
            if (start == null) {
                return new EffectiveStyle(0, 255, false, false);
            }
            EffectiveStyle cached = effectiveStyles.get(start);
            if (cached != null) {
                return cached;
            }
            int bgLuma = 255;
            boolean bgFound = false;

            String startStyle = combinedStyle(start);
            ColorReadResult bgResult =
                    readColorResult(startStyle, "background-color");
            if (!bgResult.declared) {
                bgResult = readBackgroundShorthandResult(startStyle);
            }
            if (bgResult.unresolved) {
                budget.incomplete = true;
            }
            Integer bg = bgResult.luma;
            if (!bgResult.declared) {
                String bgAttr = start.attr("bgcolor");
                if (!bgAttr.isEmpty()) {
                    bg = parseColor(bgAttr);
                    if (bg == null && isUnsupportedBrowserColor(bgAttr)) {
                        budget.incomplete = true;
                    }
                }
            }
            if (bg != null) {
                bgLuma = bg;
                bgFound = true;
            }

            ForegroundStyle foreground = resolveForeground(start);
            EffectiveStyle result =
                    new EffectiveStyle(
                            foreground.luma, bgLuma, foreground.found, bgFound);
            effectiveStyles.put(start, result);
            return result;
        }

        private ForegroundStyle resolveForeground(Element start) {
            List<Element> uncached = new ArrayList<>();
            Element element = start;
            ForegroundStyle result = null;
            while (element != null) {
                ForegroundStyle cached = foregroundStyles.get(element);
                if (cached != null) {
                    result = cached;
                    break;
                }
                uncached.add(element);
                String style = combinedStyle(element);
                ColorReadResult foreground = style.isEmpty()
                        ? ColorReadResult.NONE
                        : readColorResult(style, "color");
                if (foreground.unresolved) {
                    budget.incomplete = true;
                    result = ForegroundStyle.NONE;
                    break;
                }
                if (foreground.luma != null) {
                    result = new ForegroundStyle(foreground.luma, true);
                    break;
                }
                if (foreground.declared) {
                    result = ForegroundStyle.NONE;
                    break;
                }
                element = element.parent() instanceof Element
                        ? (Element) element.parent() : null;
            }
            if (result == null) {
                result = ForegroundStyle.NONE;
            }
            for (Element unresolved : uncached) {
                foregroundStyles.put(unresolved, result);
            }
            return result;
        }

        private String combinedStyle(Element element) {
            String cached = combinedStyles.get(element);
            if (cached != null) {
                return cached;
            }
            StringBuilder style = new StringBuilder();
            appendRule(style, element.tagName().toLowerCase(Locale.ROOT));

            String classes = element.attr("class");
            int classLimit = Math.min(classes.length(), MAX_COMBINED_STYLE_CHARS);
            if (classLimit < classes.length()) {
                budget.incomplete = true;
            }
            int cursor = 0;
            while (cursor < classLimit) {
                while (cursor < classLimit && Character.isWhitespace(classes.charAt(cursor))) {
                    cursor++;
                }
                int start = cursor;
                while (cursor < classLimit && !Character.isWhitespace(classes.charAt(cursor))) {
                    cursor++;
                }
                if (start < cursor) {
                    appendRule(style, "."
                            + classes.substring(start, cursor).toLowerCase(Locale.ROOT));
                }
            }

            String id = element.id();
            if (!id.isEmpty()) {
                if (id.length() <= MAX_COMBINED_STYLE_CHARS) {
                    appendRule(style, "#" + id.toLowerCase(Locale.ROOT));
                } else {
                    budget.incomplete = true;
                }
            }
            appendDeclaration(style, element.attr("style"));
            String result = style.toString();
            combinedStyles.put(element, result);
            return result;
        }

        private void appendRule(StringBuilder style, String selector) {
            if (!budget.consumeLookup()) {
                return;
            }
            appendDeclaration(style, rules.get(selector));
        }

        private void appendDeclaration(StringBuilder style, String declaration) {
            if (declaration == null || declaration.isEmpty()) {
                return;
            }
            int localRemaining = MAX_COMBINED_STYLE_CHARS - style.length();
            int retained = budget.retainableChars(declaration.length(), localRemaining);
            if (retained > 0) {
                style.append(declaration, 0, retained);
            }
            if (retained < declaration.length()) {
                budget.incomplete = true;
                return;
            }
            if (style.length() < MAX_COMBINED_STYLE_CHARS && budget.retainableChars(1, 1) == 1) {
                style.append(';');
            }
        }
    }

    private static final class StyleBudget {
        private int chars;
        private int lookups;
        private boolean incomplete;

        private int retainableChars(int requested, int localRemaining) {
            int remaining = Math.min(
                    Math.max(0, MAX_STYLE_RESOLUTION_CHARS - chars),
                    Math.max(0, localRemaining));
            int retained = Math.min(requested, remaining);
            chars += retained;
            if (retained < requested) {
                incomplete = true;
            }
            return retained;
        }

        private boolean consumeLookup() {
            if (lookups >= MAX_STYLE_RULE_LOOKUPS) {
                incomplete = true;
                return false;
            }
            lookups++;
            return true;
        }
    }

    private static final class ClusterSearchResult {
        private final List<List<List<Cell>>> clusters;
        private final boolean incomplete;

        private ClusterSearchResult(List<List<List<Cell>>> clusters, boolean incomplete) {
            this.clusters = clusters;
            this.incomplete = incomplete;
        }
    }

    /**
     * Parse {@code <style>} blocks, returning a map of selector → declaration
     * string. Only simple tag/class/id selectors are understood.
     */
    static Map<String, String> parseStylesheets(Document doc) {
        return parseStylesheetsBounded(doc).rules;
    }

    static StylesheetParseResult parseStylesheetsBounded(Document doc) {
        StylesheetAccumulator accumulator = new StylesheetAccumulator();
        try {
            doc.traverse(accumulator);
        } catch (StylesheetLimitException e) {
            accumulator.truncated = true;
        }
        return new StylesheetParseResult(accumulator.rules, accumulator.truncated);
    }

    private static final class StylesheetAccumulator implements NodeVisitor {
        private final Map<String, String> rules = new HashMap<>();
        private int consumedChars;
        private int selectors;
        private boolean truncated;

        @Override
        public void head(Node node, int depth) {
            if (!(node instanceof Element)) {
                return;
            }
            Element element = (Element) node;
            if ("style".equalsIgnoreCase(element.tagName())) {
                consumeStyleElement(element);
            } else if (isStylesheetLink(element)) {
                consumeDataStylesheet(element.attr("href"));
            }
        }

        private void consumeStyleElement(Element style) {
            StringBuilder boundedCss = new StringBuilder();
            for (Node child : style.childNodes()) {
                if (!(child instanceof DataNode)) {
                    continue;
                }
                String data = ((DataNode) child).getWholeData();
                int remaining = MAX_STYLESHEET_CHARS - consumedChars;
                if (remaining <= 0) {
                    throw StylesheetLimitException.INSTANCE;
                }
                int retained = Math.min(remaining, data.length());
                boundedCss.append(data, 0, retained);
                consumedChars += retained;
                if (retained < data.length()) {
                    truncated = true;
                    break;
                }
            }
            parseBoundedCss(boundedCss);
        }

        private void consumeDataStylesheet(String href) {
            int remaining = MAX_STYLESHEET_CHARS - consumedChars;
            if (remaining <= 0) {
                throw StylesheetLimitException.INSTANCE;
            }
            DataStylesheet dataStylesheet = decodeDataStylesheet(href, remaining);
            if (dataStylesheet == null) {
                truncated = true;
                throw StylesheetLimitException.INSTANCE;
            }
            if (dataStylesheet.incomplete) {
                truncated = true;
            }
            consumedChars += dataStylesheet.css.length();
            parseBoundedCss(dataStylesheet.css);
        }

        private void parseBoundedCss(CharSequence css) {
            parseCssRules(stripCssComments(css), this);
            if (truncated) {
                throw StylesheetLimitException.INSTANCE;
            }
        }

        @Override
        public void tail(Node node, int depth) {
            // no-op
        }
    }

    private static boolean isStylesheetLink(Element element) {
        if (!"link".equalsIgnoreCase(element.tagName())) {
            return false;
        }
        for (String relation : element.attr("rel").split("\\s+")) {
            if ("stylesheet".equalsIgnoreCase(relation)) {
                return true;
            }
        }
        return false;
    }

    private static DataStylesheet decodeDataStylesheet(String href, int maxDecodedChars) {
        if (href == null || !href.regionMatches(true, 0, "data:", 0, 5)) {
            return null;
        }
        int headerLimit = Math.min(href.length(), 5 + MAX_DATA_URI_HEADER_CHARS + 1);
        int comma = -1;
        for (int i = 5; i < headerLimit; i++) {
            if (href.charAt(i) == ',') {
                comma = i;
                break;
            }
        }
        if (comma < 0) {
            return new DataStylesheet("", true);
        }
        String[] headerParts = href.substring(5, comma).split(";", -1);
        if (headerParts.length == 0
                || !"text/css".equalsIgnoreCase(headerParts[0].trim())) {
            return null;
        }
        boolean base64 = false;
        boolean incomplete = false;
        for (int i = 1; i < headerParts.length; i++) {
            String parameter = headerParts[i].trim();
            if ("base64".equalsIgnoreCase(parameter)) {
                base64 = true;
            } else if (!parameter.equalsIgnoreCase("charset=utf-8")
                    && !parameter.equalsIgnoreCase("charset=us-ascii")) {
                incomplete = true;
            }
        }
        DataStylesheet decoded = base64
                ? decodeBase64Css(href, comma + 1, maxDecodedChars)
                : decodePercentEncodedCss(href, comma + 1, maxDecodedChars);
        return new DataStylesheet(decoded.css, incomplete || decoded.incomplete);
    }

    private static DataStylesheet decodeBase64Css(
            String href, int payloadStart, int maxDecodedChars) {
        int maxEncodedChars = ((maxDecodedChars + 2) / 3) * 4;
        int available = href.length() - payloadStart;
        int retained = Math.min(available, maxEncodedChars);
        boolean incomplete = retained < available;
        if (incomplete) {
            retained -= retained % 4;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(
                    href.substring(payloadStart, payloadStart + retained));
            String css = decodeUtf8(bytes);
            if (css.length() > maxDecodedChars) {
                css = css.substring(0, maxDecodedChars);
                incomplete = true;
            }
            return new DataStylesheet(css, incomplete);
        } catch (IllegalArgumentException | CharacterCodingException e) {
            return new DataStylesheet("", true);
        }
    }

    private static DataStylesheet decodePercentEncodedCss(
            String href, int payloadStart, int maxDecodedChars) {
        byte[] bytes = new byte[maxDecodedChars];
        int byteCount = 0;
        int cursor = payloadStart;
        boolean incomplete = false;
        while (cursor < href.length() && byteCount < bytes.length) {
            char value = href.charAt(cursor);
            if (value == '%') {
                if (cursor + 2 >= href.length()) {
                    incomplete = true;
                    break;
                }
                int high = Character.digit(href.charAt(cursor + 1), 16);
                int low = Character.digit(href.charAt(cursor + 2), 16);
                if (high < 0 || low < 0) {
                    incomplete = true;
                    break;
                }
                bytes[byteCount++] = (byte) ((high << 4) | low);
                cursor += 3;
            } else if (value <= 0x7f) {
                bytes[byteCount++] = (byte) value;
                cursor++;
            } else {
                incomplete = true;
                break;
            }
        }
        if (cursor < href.length()) {
            incomplete = true;
        }
        try {
            return new DataStylesheet(
                    decodeUtf8(java.util.Arrays.copyOf(bytes, byteCount)), incomplete);
        } catch (CharacterCodingException e) {
            return new DataStylesheet("", true);
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static final class DataStylesheet {
        private final String css;
        private final boolean incomplete;

        private DataStylesheet(String css, boolean incomplete) {
            this.css = css;
            this.incomplete = incomplete;
        }
    }

    private static void parseCssRules(CharSequence css,
                                      StylesheetAccumulator accumulator) {
        int cursor = 0;
        while (cursor < css.length()) {
            int open = indexOf(css, '{', cursor);
            if (open < 0) {
                return;
            }
            int close = indexOf(css, '}', open + 1);
            if (close < 0) {
                return;
            }
            String declaration = css.subSequence(open + 1, close).toString().trim();
            int selectorStart = cursor;
            for (int i = cursor; i <= open; i++) {
                if (i != open && css.charAt(i) != ',') {
                    continue;
                }
                if (++accumulator.selectors > MAX_STYLESHEET_SELECTORS) {
                    throw StylesheetLimitException.INSTANCE;
                }
                String rawSelector = css.subSequence(selectorStart, i)
                        .toString().trim().toLowerCase(Locale.ROOT);
                JSoupParser.CssNormalization normalization =
                        JSoupParser.normalizeCss(rawSelector);
                String selector = normalization.css().trim();
                if (normalization.incomplete()) {
                    accumulator.truncated = true;
                }
                if (!selector.isEmpty()
                        && SIMPLE_CSS_SELECTOR.matcher(selector).matches()) {
                    accumulator.rules.put(selector, declaration);
                } else if (!selector.isEmpty()
                        && isSecurityRelevantDeclaration(declaration)) {
                    accumulator.truncated = true;
                }
                selectorStart = i + 1;
            }
            cursor = close + 1;
        }
    }

    private static boolean isSecurityRelevantDeclaration(String declaration) {
        JSoupParser.CssNormalization normalization =
                JSoupParser.normalizeCss(declaration);
        String css = normalization.css();
        if (JSoupParser.inspectWhitespace(css).preserves()) {
            return true;
        }
        String lower = css.toLowerCase(Locale.ROOT);
        return lower.contains("color") || lower.contains("background")
                || lower.contains("white-space");
    }

    private static StringBuilder stripCssComments(CharSequence css) {
        StringBuilder stripped = new StringBuilder(css.length());
        for (int i = 0; i < css.length();) {
            if (i + 1 < css.length()
                    && css.charAt(i) == '/' && css.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < css.length()
                        && !(css.charAt(i) == '*' && css.charAt(i + 1) == '/')) {
                    i++;
                }
                if (i + 1 >= css.length()) {
                    break;
                }
                i += 2;
                continue;
            }
            stripped.append(css.charAt(i++));
        }
        return stripped;
    }

    private static int indexOf(CharSequence value, char wanted, int start) {
        for (int i = start; i < value.length(); i++) {
            if (value.charAt(i) == wanted) {
                return i;
            }
        }
        return -1;
    }

    static final class StylesheetParseResult {
        final Map<String, String> rules;
        final boolean truncated;

        private StylesheetParseResult(Map<String, String> rules, boolean truncated) {
            this.rules = rules;
            this.truncated = truncated;
        }
    }

    private static final class StylesheetLimitException extends RuntimeException {
        private static final StylesheetLimitException INSTANCE =
                new StylesheetLimitException();

        private StylesheetLimitException() {
            super(null, null, false, false);
        }
    }

    static Integer readColor(String style, String property) {
        return readColorResult(style, property).luma;
    }

    private static ColorReadResult readColorResult(String style, String property) {
        if (style == null || style.isEmpty()) {
            return ColorReadResult.NONE;
        }
        Matcher m = ("background-color".equalsIgnoreCase(property)
                ? BACKGROUND_COLOR_DECLARATION : COLOR_DECLARATION).matcher(style);
        Integer luma = null;
        boolean declared = false;
        boolean unresolved = false;
        while (m.find()) {
            String value = m.group(1).trim();
            Integer parsed = parseColor(value);
            if (parsed != null) {
                luma = parsed;
                declared = true;
                unresolved = false;
            } else if ("transparent".equalsIgnoreCase(value)) {
                luma = null;
                declared = true;
                unresolved = true;
            } else if (isUnsupportedBrowserColor(value)) {
                luma = null;
                declared = true;
                unresolved = true;
            }
        }
        return new ColorReadResult(luma, declared, unresolved);
    }

    static Integer readBackgroundShorthand(String style) {
        return readBackgroundShorthandResult(style).luma;
    }

    private static ColorReadResult readBackgroundShorthandResult(String style) {
        if (style == null || style.isEmpty()) {
            return ColorReadResult.NONE;
        }
        Matcher m = BACKGROUND_DECLARATION.matcher(style);
        Integer luma = null;
        boolean declared = false;
        boolean unresolved = false;
        while (m.find()) {
            String value = m.group(1).trim();
            Integer parsed = parseBackgroundColor(value);
            if (parsed != null) {
                luma = parsed;
                declared = true;
                unresolved = false;
            } else if (containsTransparentToken(value)) {
                luma = null;
                declared = true;
                unresolved = true;
            } else if (isUnsupportedBrowserColor(value)) {
                luma = null;
                declared = true;
                unresolved = true;
            }
        }
        return new ColorReadResult(luma, declared, unresolved);
    }

    private static Integer parseBackgroundColor(String value) {
        Matcher hex = BACKGROUND_HEX_COLOR.matcher(value);
        if (hex.find()) {
            return parseColor(hex.group());
        }
        Matcher rgb = BACKGROUND_RGB_COLOR.matcher(value);
        if (rgb.find()) {
            return parseColor(rgb.group());
        }
        int cursor = 0;
        while (cursor < value.length()) {
            while (cursor < value.length()
                    && Character.isWhitespace(value.charAt(cursor))) {
                cursor++;
            }
            int start = cursor;
            while (cursor < value.length()
                    && !Character.isWhitespace(value.charAt(cursor))) {
                cursor++;
            }
            if (start == cursor) {
                continue;
            }
            Integer c = parseColor(value.substring(start, cursor));
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    private static boolean containsTransparentToken(String value) {
        return TRANSPARENT_TOKEN.matcher(value).find();
    }

    private static boolean isUnsupportedBrowserColor(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("var(")
                || normalized.contains("hsl(")
                || normalized.contains("hwb(")
                || normalized.contains("lab(")
                || normalized.contains("lch(")
                || normalized.contains("oklab(")
                || normalized.contains("oklch(")
                || normalized.contains("color(")
                || normalized.contains("rgba(")
                || MODERN_RGB_COLOR.matcher(normalized).matches()
                || ALPHA_HEX_COLOR.matcher(normalized).matches()
                || CSS_IDENTIFIER_COLOR.matcher(normalized).matches();
    }

    private static final class ColorReadResult {
        private static final ColorReadResult NONE =
                new ColorReadResult(null, false, false);

        private final Integer luma;
        private final boolean declared;
        private final boolean unresolved;

        private ColorReadResult(Integer luma, boolean declared, boolean unresolved) {
            this.luma = luma;
            this.declared = declared;
            this.unresolved = unresolved;
        }
    }

    private static final class ForegroundStyle {
        private static final ForegroundStyle NONE =
                new ForegroundStyle(0, false);

        private final int luma;
        private final boolean found;

        private ForegroundStyle(int luma, boolean found) {
            this.luma = luma;
            this.found = found;
        }
    }

    private static void markAnalysisIncomplete(Metadata metadata, String warning) {
        metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING, warning);
        if (metadata.get("ExploitClass") == null) {
            metadata.set("ExploitClass",
                    "HTML color-QR extraction incomplete; encoded link content may be hidden");
        }
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
                } else if (v.length() == 4) {
                    if (v.charAt(3) != 'f') {
                        return null;
                    }
                    r = Integer.parseInt(v.substring(0, 1).repeat(2), 16);
                    g = Integer.parseInt(v.substring(1, 2).repeat(2), 16);
                    b = Integer.parseInt(v.substring(2, 3).repeat(2), 16);
                } else if (v.length() == 6) {
                    r = Integer.parseInt(v.substring(0, 2), 16);
                    g = Integer.parseInt(v.substring(2, 4), 16);
                    b = Integer.parseInt(v.substring(4, 6), 16);
                } else if (v.length() == 8) {
                    if (!"ff".equals(v.substring(6, 8))) {
                        return null;
                    }
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
        if (v.startsWith("rgba")) {
            Matcher m = RGBA_COMMA_COLOR.matcher(v);
            if (m.matches() && isOpaqueAlpha(m.group(4))) {
                try {
                    int r = clamp(Integer.parseInt(m.group(1)));
                    int g = clamp(Integer.parseInt(m.group(2)));
                    int b = clamp(Integer.parseInt(m.group(3)));
                    return luma(r, g, b);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }
        if (v.startsWith("rgb")) {
            Matcher m = RGB_COMMA_COLOR.matcher(v);
            if (m.matches()) {
                try {
                    int r = clamp(Integer.parseInt(m.group(1)));
                    int g = clamp(Integer.parseInt(m.group(2)));
                    int b = clamp(Integer.parseInt(m.group(3)));
                    return luma(r, g, b);
                } catch (NumberFormatException e) {
                    return null;
                }
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

    private static boolean isOpaqueAlpha(String alpha) {
        try {
            if (alpha.endsWith("%")) {
                return Double.parseDouble(
                        alpha.substring(0, alpha.length() - 1)) == 100.0d;
            }
            return Double.parseDouble(alpha) == 1.0d;
        } catch (NumberFormatException e) {
            return false;
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
        long imgWLong = ((long) maxCols + 2 * quiet) * MODULE_PX;
        long imgHLong = ((long) grid.size() + 2 * quiet) * MODULE_PX;
        if (imgWLong <= 0 || imgHLong <= 0
                || imgWLong > MAX_RENDERED_PIXELS / imgHLong) {
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

}
