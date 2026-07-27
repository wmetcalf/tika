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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.xml.XMLConstants;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.parser.Tag;
import org.jsoup.parser.TagSet;
import org.jsoup.select.NodeFilter;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import org.apache.tika.annotation.TikaComponent;
import org.apache.tika.config.ConfigDeserializer;
import org.apache.tika.config.JsonConfig;
import org.apache.tika.detect.EncodingDetector;
import org.apache.tika.detect.EncodingResult;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AbstractEncodingDetectorParser;
import org.apache.tika.parser.ParseContext;


/**
 * HTML parser. Uses JSoup to turn the input document to HTML SAX events,
 * and post-processes the events to produce XHTML and metadata expected by
 * Tika clients.
 */
@TikaComponent(name = "jsoup-parser")
public class JSoupParser extends AbstractEncodingDetectorParser {

    /**
     * Serial version UID
     */
    private static final long serialVersionUID = 7895315240498733128L;

    public static final Charset DEFAULT_CHARSET = StandardCharsets.US_ASCII;

    /**
     * Configuration class for JSON deserialization.
     */
    public static class Config implements Serializable {
        public boolean extractScripts = false;
    }

    private static final MediaType XHTML = MediaType.application("xhtml+xml");
    private static final MediaType WAP_XHTML = MediaType.application("vnd.wap.xhtml+xml");
    private static final MediaType X_ASP = MediaType.application("x-asp");

    private static final Set<MediaType> SUPPORTED_TYPES = Collections.unmodifiableSet(
            new HashSet<MediaType>(Arrays.asList(MediaType.text("html"), XHTML, WAP_XHTML, X_ASP)));

    private static final TagSet SELF_CLOSEABLE_TAGS = TagSet.Html();
    private static final int MAX_UNICODE_QR_CANDIDATES = 16;
    private static final int MAX_UNICODE_QR_SCANS = 4;
    private static final int MAX_UNICODE_QR_CANDIDATE_CHARS = 128 * 1024;
    private static final int MAX_UNICODE_QR_STYLE_CHARS = 64 * 1024;
    private static final int MAX_UNICODE_QR_STYLE_INSPECTION_CHARS = 2 * 1024 * 1024;
    private static final int MAX_UNICODE_QR_STYLE_RULE_LOOKUPS = 64 * 1024;
    private static final String UNICODE_QR_LIMIT_WARNING =
            "HTML Unicode-QR analysis limit reached; Unicode-QR extraction is incomplete";

    static {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                JSoupParser.class.getResourceAsStream("self-closeable-tags.txt"), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            while (line != null) {
                if (line.startsWith("#") || line.trim().isEmpty()) {
                    line = reader.readLine();
                    continue;
                }
                Tag t = SELF_CLOSEABLE_TAGS.valueOf(line.trim(), Parser.NamespaceHtml);
                t.set(Tag.SelfClose);
                line = reader.readLine();
            }
        } catch (IOException e) {
            throw new RuntimeException("Can't find self-closeable-tags.txt");
        }
    }

    private boolean extractScripts = false;

    public JSoupParser() {
        super();
    }

    public JSoupParser(EncodingDetector encodingDetector) {
        super(encodingDetector);
    }

    /**
     * Constructor with explicit Config object.
     *
     * @param config the configuration
     */
    public JSoupParser(Config config) {
        super();
        this.extractScripts = config.extractScripts;
    }

    /**
     * Constructor for JSON configuration.
     * Requires Jackson on the classpath.
     *
     * @param jsonConfig JSON configuration
     */
    public JSoupParser(JsonConfig jsonConfig) {
        this(ConfigDeserializer.buildConfig(jsonConfig, Config.class));
    }

    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED_TYPES;
    }

    public boolean isExtractScripts() {
        return extractScripts;
    }

    /**
     * Whether or not to extract contents in script entities.
     * Default is <code>false</code>
     *
     * @param extractScripts
     */
    public void setExtractScripts(boolean extractScripts) {
        this.extractScripts = extractScripts;
    }


    public void parse(TikaInputStream tis, ContentHandler handler, Metadata metadata,
                      ParseContext context) throws IOException, SAXException, TikaException {

        EncodingDetector encodingDetector = getEncodingDetector(context);
        List<EncodingResult> encResults = encodingDetector.detect(tis, metadata, context);
        Charset charset = encResults.isEmpty() ? DEFAULT_CHARSET
                : encResults.get(0).getCharset();
        Charset decodeAs = encResults.isEmpty() ? DEFAULT_CHARSET
                : encResults.get(0).getDecodeAs();
        if (!decodeAs.equals(charset)) {
            metadata.set(TikaCoreProperties.DECODED_CHARSET, decodeAs.name());
        }
        String previous = metadata.get(Metadata.CONTENT_TYPE);
        MediaType contentType = null;
        if (previous == null || previous.startsWith("text/html")) {
            contentType = new MediaType(MediaType.TEXT_HTML, charset);
        } else if (previous.startsWith("application/xhtml+xml")) {
            contentType = new MediaType(XHTML, charset);
        } else if (previous.startsWith("application/vnd.wap.xhtml+xml")) {
            contentType = new MediaType(WAP_XHTML, charset);
        } else if (previous.startsWith("application/x-asp")) {
            contentType = new MediaType(X_ASP, charset);
        }
        if (contentType != null) {
            metadata.set(Metadata.CONTENT_TYPE, contentType.toString());
        }
        // deprecated, see TIKA-431
        metadata.set(Metadata.CONTENT_ENCODING, charset.name());

        // Get the HTML mapper from the parse context
        HtmlMapper mapper = context.get(HtmlMapper.class, new DefaultHtmlMapper());

        TagSet tagSet = new TagSet(SELF_CLOSEABLE_TAGS);
        /* TODO -- when we upgrade jsoup to 1.21.1
                .onNewTag(tag -> {
            if (!tag.isKnownTag())
                tag.set(Tag.SelfClose);
        });
        */

        //do better with baseUri?
        tis.setCloseShield();
        Document document;
        try {
            document = Jsoup.parse(tis, decodeAs.name(), "",
                    Parser.htmlParser().tagSet(tagSet));
        } finally {
            tis.removeCloseShield();
        }
        // CSS-colored QR detection: only invoked when the user has set a
        // ZXingCPPConfig (i.e. they care about barcode scanning at all).
        // Failures are non-fatal — the QR scan is best-effort.
        scanForColorQR(document, metadata, context);
        document.quirksMode(Document.QuirksMode.quirks);
        ContentHandler xhtml = new XHTMLDowngradeHandler(
                new HtmlHandler(mapper, handler, metadata, context, extractScripts));
        xhtml.startDocument();
        try {
            NodeTraversor.filter(new TikaNodeFilter(xhtml), document);
        } catch (RuntimeSAXException e) {
            throw e.getWrapped();
        } finally {
            xhtml.endDocument();
        }
    }

    private static void scanForColorQR(Document document, Metadata metadata, ParseContext context) {
        org.apache.tika.parser.image.ZXingCPPConfig zCfg =
                context.get(org.apache.tika.parser.image.ZXingCPPConfig.class);
        if (zCfg == null || !zCfg.isEnabled()) {
            return;
        }
        try {
            org.apache.tika.parser.image.ZXingCPPScanner scanner =
                    new org.apache.tika.parser.image.ZXingCPPScanner(zCfg);
            java.util.List<org.apache.tika.parser.image.ZXingCPPScanner.Result> decoded =
                    HtmlColorQRExtractor.extractAndDecode(
                            document, scanner, zCfg, context, metadata);
            org.apache.tika.parser.image.ColorGridQRDecoder.emitBarcodes(decoded, metadata);
            if (!decoded.isEmpty()) {
                metadata.add("ExploitClass",
                        "Decoded " + decoded.size()
                      + " CSS-colored QR code(s) from HTML — invisible to "
                      + "image-based scanners (color encodes dark/light, not glyph)");
            }
        } catch (SecurityException e) {
            throw e;
        } catch (RuntimeException e) {
            // Ordinary scanner failures remain best-effort.
        }
        scanForUnicodeArtQR(document, metadata, context);
    }

    /** Pull text out of every monospace-preserving HTML element (&lt;pre&gt;,
     *  &lt;code&gt;, anything with {@code white-space: pre*} in inline style)
     *  and feed it to {@link org.apache.tika.parser.image.UnicodeQRExtractor}.
     *  Catches Unicode-art QR payloads embedded in HTML — most notably the
     *  X-ALT-DESC HTML-body variant attackers use when a calendar invite's
     *  plain-text DESCRIPTION renders too poorly to scan but the HTML
     *  alt-description gets the same payload styled in monospace. Works for
     *  any glyph family the {@code UnicodeQRExtractor} supports (block,
     *  Braille, sextant) — the attacker doesn't get to pivot away. */
    private static void scanForUnicodeArtQR(Document document, Metadata metadata,
            ParseContext context) {
        org.apache.tika.parser.image.ZXingCPPConfig zCfg =
                context.get(org.apache.tika.parser.image.ZXingCPPConfig.class);
        if (zCfg == null || !zCfg.isEnabled()) {
            return;
        }
        try {
            HtmlColorQRExtractor.StylesheetParseResult stylesheets =
                    HtmlColorQRExtractor.parseStylesheetsBounded(
                            document, usesXmlSelectorCaseSensitivity(metadata));
            Map<String, String> stylesheetRules = stylesheets.rules;
            boolean incomplete = stylesheets.truncated;
            int inspected = 0;
            CssRuleBudget ruleBudget = new CssRuleBudget();
            org.apache.tika.parser.image.ZXingCPPScanner scanner =
                    new org.apache.tika.parser.image.ZXingCPPScanner(zCfg);
            long aggregateTimeoutMillis =
                    org.apache.tika.config.TimeoutLimits.getProcessTimeoutMillis(
                            context, zCfg.getTimeoutSeconds() * 1000L);
            org.apache.tika.parser.image.ZXingCPPScanner.ScanBudget scanBudget =
                    new org.apache.tika.parser.image.ZXingCPPScanner.ScanBudget(
                            MAX_UNICODE_QR_SCANS, aggregateTimeoutMillis);
            Set<Element> seen =
                    Collections.newSetFromMap(new IdentityHashMap<>());
            // Stream the tree instead of materializing getAllElements(). A
            // hostile document can otherwise allocate an unbounded candidate
            // list before the lookup/candidate budgets below take effect.
            try (Stream<Element> candidateStream = document.nodeStream(Element.class)) {
                Iterator<Element> candidates = candidateStream.iterator();
                while (candidates.hasNext()) {
                    Element element = candidates.next();
                    if (stylesheetRules.isEmpty()
                            && !"pre".equalsIgnoreCase(element.tagName())
                            && !"code".equalsIgnoreCase(element.tagName())
                            && !element.hasAttr("style")) {
                        continue;
                    }
                    boolean preformatted = "pre".equalsIgnoreCase(element.tagName())
                            || "code".equalsIgnoreCase(element.tagName());
                    if (!preformatted
                            && (element.hasAttr("style") || !stylesheetRules.isEmpty())) {
                        String inlineStyle = element.attr("style");
                        if (inlineStyle.length() > MAX_UNICODE_QR_STYLE_CHARS) {
                            incomplete = true;
                            inlineStyle = inlineStyle.substring(
                                    0, MAX_UNICODE_QR_STYLE_CHARS);
                        }
                        WhitespaceInspection inspection = stylesheetPreservesWhitespace(
                                element, stylesheetRules, inlineStyle, ruleBudget);
                        incomplete |= inspection.incomplete;
                        preformatted = inspection.preserves;
                    }
                    if (!preformatted) {
                        if (ruleBudget.exhausted) {
                            incomplete = true;
                            break;
                        }
                        continue;
                    }
                    if (!seen.add(element)) {
                        continue;
                    }
                    if (inspected++ >= MAX_UNICODE_QR_CANDIDATES) {
                        incomplete = true;
                        break;
                    }
                    BoundedMonospaceText candidate = collectMonospaceText(element);
                    incomplete |= candidate.truncated;
                    if (candidate.text.isEmpty()) {
                        continue;
                    }
                    int glyphCount = org.apache.tika.parser.image.UnicodeQRExtractor
                            .countQrGlyphs(candidate.text);
                    if (glyphCount < 50) {
                        continue;
                    }
                    metadata.add("html_unicode_qr:glyph_count", String.valueOf(glyphCount));
                    try {
                        java.util.List<
                                org.apache.tika.parser.image.ZXingCPPScanner.Result> decoded =
                                org.apache.tika.parser.image.UnicodeQRExtractor.extractAndDecode(
                                        candidate.text, scanner, zCfg, context, scanBudget);
                        org.apache.tika.parser.image.ColorGridQRDecoder.emitBarcodes(
                                decoded, metadata);
                        if (!decoded.isEmpty()) {
                            metadata.add("ExploitClass",
                                    "Decoded " + decoded.size()
                                  + " Unicode-art QR code(s) from HTML monospace block (<pre>/"
                                  + "<code>/white-space:pre) — typical X-ALT-DESC carrier");
                        }
                    } catch (SecurityException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        incomplete = true;
                        if (scanBudget.hasRejectedScan()) {
                            break;
                        }
                    }
                }
            }
            if (incomplete) {
                markUnicodeQrIncomplete(metadata);
            }
        } catch (SecurityException e) {
            throw e;
        } catch (RuntimeException e) {
            markUnicodeQrIncomplete(metadata);
        }
    }

    static WhitespaceInspection inspectWhitespace(String style) {
        WhitespaceCascade cascade = whitespaceCascade(style);
        return new WhitespaceInspection(
                cascade.preserves, cascade.incomplete);
    }

    private static WhitespaceInspection stylesheetPreservesWhitespace(
            Element element, Map<String, String> rules,
            String inlineStyle, CssRuleBudget budget) {
        WhitespaceCascade cascade = new WhitespaceCascade(
                false, false, false, false);
        boolean incomplete = false;
        cascade = applyRules(cascade, lookupRules(
                rules, element.tagName().toLowerCase(java.util.Locale.ROOT), budget),
                budget);
        incomplete |= budget.exhausted;

        String classes = element.attr("class");
        int classLimit = Math.min(classes.length(), MAX_UNICODE_QR_STYLE_CHARS);
        if (classLimit < classes.length()) {
            incomplete = true;
        }
        int cursor = 0;
        List<HtmlColorQRExtractor.CssRule> classRules = new ArrayList<>();
        Set<String> seenClasses = new HashSet<>();
        while (cursor < classLimit) {
            while (cursor < classLimit && isHtmlSpace(classes.charAt(cursor))) {
                cursor++;
            }
            int start = cursor;
            while (cursor < classLimit && !isHtmlSpace(classes.charAt(cursor))) {
                cursor++;
            }
            if (start < cursor) {
                String selector = "." + classes.substring(start, cursor);
                if (seenClasses.add(selector)) {
                    classRules.addAll(lookupRules(rules, selector, budget));
                }
            }
        }
        classRules.sort((left, right) ->
                Integer.compare(left.sourceOrder, right.sourceOrder));
        cascade = applyRules(cascade, classRules, budget);
        incomplete |= budget.exhausted;

        String id = element.id();
        if (id.length() > MAX_UNICODE_QR_STYLE_CHARS) {
            return new WhitespaceInspection(false, true);
        }
        if (!id.isEmpty()) {
            cascade = applyRules(cascade,
                    lookupRules(rules, "#" + id, budget), budget);
            incomplete |= budget.exhausted;
        }
        if (!inlineStyle.isEmpty()) {
            cascade = mergeWhitespaceCascade(
                    cascade, budget.inspectInline(inlineStyle));
        }
        return new WhitespaceInspection(
                cascade.preserves, incomplete || cascade.incomplete);
    }

    private static List<HtmlColorQRExtractor.CssRule> lookupRules(
            Map<String, String> rules, String selector, CssRuleBudget budget) {
        if (!budget.consume()) {
            return List.of();
        }
        return HtmlColorQRExtractor.stylesheetRulesFor(rules, selector);
    }

    private static WhitespaceCascade applyRules(
            WhitespaceCascade cascade,
            List<HtmlColorQRExtractor.CssRule> rules,
            CssRuleBudget budget) {
        WhitespaceCascade result = cascade;
        for (HtmlColorQRExtractor.CssRule rule : rules) {
            result = mergeWhitespaceCascade(
                    result, budget.inspect(rule.declaration));
        }
        return result;
    }

    private static WhitespaceCascade mergeWhitespaceCascade(
            WhitespaceCascade current, WhitespaceCascade candidate) {
        boolean incomplete = current.incomplete || candidate.incomplete;
        if (!candidate.declared
                || current.important && !candidate.important) {
            return new WhitespaceCascade(
                    current.preserves, current.important,
                    incomplete, current.declared);
        }
        return new WhitespaceCascade(
                candidate.preserves, candidate.important,
                incomplete, true);
    }

    static List<CssDeclaration> parseCssDeclarations(String style) {
        List<CssDeclaration> declarations = new ArrayList<>();
        int declarationStart = 0;
        int colon = -1;
        int parenthesisDepth = 0;
        int bracketDepth = 0;
        int braceDepth = 0;
        char quote = 0;
        boolean escaped = false;
        boolean incomplete = false;
        int cursor = 0;
        while (cursor < style.length()) {
            char current = style.charAt(cursor);
            if (escaped) {
                escaped = false;
                cursor++;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                cursor++;
                continue;
            }
            if (quote != 0) {
                if (current == quote) {
                    quote = 0;
                }
                cursor++;
                continue;
            }
            if (current == '\'' || current == '"') {
                quote = current;
                cursor++;
                continue;
            }
            if (current == '/' && cursor + 1 < style.length()
                    && style.charAt(cursor + 1) == '*') {
                int close = style.indexOf("*/", cursor + 2);
                if (close < 0) {
                    incomplete = true;
                    break;
                }
                cursor = close + 2;
                continue;
            }
            if (current == '(') {
                parenthesisDepth++;
            } else if (current == ')' && parenthesisDepth > 0) {
                parenthesisDepth--;
            } else if (current == '[') {
                bracketDepth++;
            } else if (current == ']' && bracketDepth > 0) {
                bracketDepth--;
            } else if (current == '{') {
                braceDepth++;
            } else if (current == '}' && braceDepth > 0) {
                braceDepth--;
            } else if (current == ':' && colon < 0
                    && parenthesisDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                colon = cursor;
            } else if (current == ';'
                    && parenthesisDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                addCssDeclaration(
                        declarations, style, declarationStart, colon, cursor, incomplete);
                declarationStart = cursor + 1;
                colon = -1;
                incomplete = false;
            }
            cursor++;
        }
        if (escaped || quote != 0 || parenthesisDepth != 0
                || bracketDepth != 0 || braceDepth != 0) {
            incomplete = true;
        }
        addCssDeclaration(
                declarations, style, declarationStart, colon, style.length(), incomplete);
        return declarations;
    }

    private static void addCssDeclaration(
            List<CssDeclaration> declarations, String style,
            int declarationStart, int colon, int declarationEnd, boolean scanIncomplete) {
        if (colon < declarationStart || colon >= declarationEnd) {
            return;
        }
        CssNormalization name =
                normalizeCss(style.substring(declarationStart, colon));
        CssPriority priority =
                normalizeCssPriority(style.substring(colon + 1, declarationEnd));
        String normalizedName = name.css.trim();
        if (normalizedName.isEmpty()) {
            return;
        }
        declarations.add(new CssDeclaration(
                normalizedName, priority.value, priority.important,
                scanIncomplete || name.incomplete || priority.incomplete));
    }

    private static CssPriority normalizeCssPriority(String rawValue) {
        int importantBang = findImportantBang(rawValue);
        if (importantBang >= 0
                && isImportantIdentifier(rawValue, importantBang + 1)) {
            CssNormalization value =
                    normalizeCss(rawValue.substring(0, importantBang));
            return new CssPriority(value.css.trim(), true, value.incomplete);
        }
        CssNormalization value = normalizeCss(rawValue);
        return new CssPriority(value.css.trim(), false, value.incomplete);
    }

    private static int findImportantBang(String value) {
        int candidate = -1;
        int parenthesisDepth = 0;
        int bracketDepth = 0;
        int braceDepth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (quote != 0) {
                if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"') {
                quote = current;
                continue;
            }
            if (current == '/' && i + 1 < value.length()
                    && value.charAt(i + 1) == '*') {
                int close = value.indexOf("*/", i + 2);
                if (close < 0) {
                    return -1;
                }
                i = close + 1;
                continue;
            }
            if (current == '(') {
                parenthesisDepth++;
            } else if (current == ')' && parenthesisDepth > 0) {
                parenthesisDepth--;
            } else if (current == '[') {
                bracketDepth++;
            } else if (current == ']' && bracketDepth > 0) {
                bracketDepth--;
            } else if (current == '{') {
                braceDepth++;
            } else if (current == '}' && braceDepth > 0) {
                braceDepth--;
            } else if (current == '!' && parenthesisDepth == 0
                    && bracketDepth == 0 && braceDepth == 0) {
                candidate = i;
            }
        }
        return candidate;
    }

    private static boolean isImportantIdentifier(String value, int start) {
        int cursor = skipCssWhitespaceAndComments(value, start);
        int identifierStart = cursor;
        while (cursor < value.length()) {
            char current = value.charAt(cursor);
            if (isCssNameCharacter(current)) {
                cursor++;
                continue;
            }
            if (current == '\\' && cursor + 1 < value.length()
                    && !isCssNewline(value.charAt(cursor + 1))) {
                cursor = cssEscapeEnd(value, cursor + 1);
                continue;
            }
            break;
        }
        if (cursor == identifierStart) {
            return false;
        }
        CssNormalization identifier =
                normalizeCss(value.substring(identifierStart, cursor));
        if (identifier.incomplete
                || !"important".equalsIgnoreCase(identifier.css)) {
            return false;
        }
        return skipCssWhitespaceAndComments(value, cursor) == value.length();
    }

    private static int skipCssWhitespaceAndComments(String value, int start) {
        int cursor = start;
        while (cursor < value.length()) {
            if (isCssWhitespace(value.charAt(cursor))) {
                cursor++;
                continue;
            }
            if (cursor + 1 < value.length()
                    && value.charAt(cursor) == '/' && value.charAt(cursor + 1) == '*') {
                int close = value.indexOf("*/", cursor + 2);
                if (close < 0) {
                    return cursor;
                }
                cursor = close + 2;
                continue;
            }
            break;
        }
        return cursor;
    }

    private static int cssEscapeEnd(String value, int escapedStart) {
        int cursor = escapedStart;
        int digits = 0;
        while (cursor < value.length() && digits < 6
                && Character.digit(value.charAt(cursor), 16) >= 0) {
            cursor++;
            digits++;
        }
        if (digits == 0) {
            return Math.min(value.length(), cursor + 1);
        }
        if (cursor < value.length() && isCssWhitespace(value.charAt(cursor))) {
            if (value.charAt(cursor) == '\r'
                    && cursor + 1 < value.length() && value.charAt(cursor + 1) == '\n') {
                cursor++;
            }
            cursor++;
        }
        return cursor;
    }

    private static boolean isCssNameCharacter(char value) {
        return value >= 0x80 || Character.isLetterOrDigit(value)
                || value == '-' || value == '_';
    }

    private static boolean isCssNewline(char value) {
        return value == '\r' || value == '\n' || value == '\f';
    }

    private static boolean isCssWhitespace(char value) {
        return value == ' ' || value == '\t'
                || value == '\r' || value == '\n' || value == '\f';
    }

    static boolean isHtmlSpace(char value) {
        return value == ' ' || value == '\t'
                || value == '\r' || value == '\n' || value == '\f';
    }

    static boolean usesXmlSelectorCaseSensitivity(Metadata metadata) {
        if (metadata == null) {
            return false;
        }
        String contentType = metadata.get(Metadata.CONTENT_TYPE);
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("application/xhtml+xml")
                || normalized.startsWith("application/vnd.wap.xhtml+xml");
    }

    static CssNormalization normalizeCss(String css) {
        StringBuilder normalized = new StringBuilder(css.length());
        boolean incomplete = false;
        char quote = 0;
        int cursor = 0;
        while (cursor < css.length()) {
            char current = css.charAt(cursor);
            if (quote == 0 && current == '/' && cursor + 1 < css.length()
                    && css.charAt(cursor + 1) == '*') {
                int close = css.indexOf("*/", cursor + 2);
                if (close < 0) {
                    incomplete = true;
                    break;
                }
                normalized.append(' ');
                cursor = close + 2;
                continue;
            }
            if (current != '\\') {
                normalized.append(current);
                if (quote != 0 && current == quote) {
                    quote = 0;
                } else if (quote == 0 && (current == '\'' || current == '"')) {
                    quote = current;
                }
                cursor++;
                continue;
            }
            if (++cursor >= css.length()) {
                incomplete = true;
                break;
            }
            char escaped = css.charAt(cursor);
            if (escaped == '\r' || escaped == '\n' || escaped == '\f') {
                if (escaped == '\r' && cursor + 1 < css.length()
                        && css.charAt(cursor + 1) == '\n') {
                    cursor++;
                }
                cursor++;
                continue;
            }
            int codePoint = 0;
            int digits = 0;
            while (cursor < css.length() && digits < 6) {
                int hex = Character.digit(css.charAt(cursor), 16);
                if (hex < 0) {
                    break;
                }
                codePoint = codePoint * 16 + hex;
                cursor++;
                digits++;
            }
            if (digits == 0) {
                normalized.append(escaped);
                cursor++;
                continue;
            }
            if (cursor < css.length() && isCssWhitespace(css.charAt(cursor))) {
                if (css.charAt(cursor) == '\r' && cursor + 1 < css.length()
                        && css.charAt(cursor + 1) == '\n') {
                    cursor++;
                }
                cursor++;
            }
            if (codePoint <= 0 || !Character.isValidCodePoint(codePoint)) {
                normalized.append('\ufffd');
            } else {
                normalized.appendCodePoint(codePoint);
            }
        }
        return new CssNormalization(normalized.toString(), incomplete);
    }

    private static WhitespaceCascade whitespaceCascade(String style) {
        boolean preserves = false;
        boolean important = false;
        boolean incomplete = false;
        boolean declared = false;
        for (CssDeclaration declaration : parseCssDeclarations(style)) {
            incomplete |= declaration.incomplete;
            if (!"white-space".equalsIgnoreCase(declaration.name)
                    || important && !declaration.important) {
                continue;
            }
            String value = declaration.value.toLowerCase(java.util.Locale.ROOT);
            switch (value) {
                case "normal":
                case "nowrap":
                    preserves = false;
                    important = declaration.important;
                    declared = true;
                    break;
                case "pre":
                case "pre-wrap":
                case "pre-line":
                case "break-spaces":
                    preserves = true;
                    important = declaration.important;
                    declared = true;
                    break;
                default:
                    preserves = false;
                    incomplete = true;
                    important = declaration.important;
                    declared = true;
                    break;
            }
        }
        return new WhitespaceCascade(
                preserves, important, incomplete, declared);
    }

    private static final class CssRuleBudget {
        private int lookups;
        private int inlineChars;
        private boolean exhausted;
        private final Map<String, WhitespaceCascade> inspections = new HashMap<>();

        private boolean consume() {
            if (exhausted || lookups >= MAX_UNICODE_QR_STYLE_RULE_LOOKUPS) {
                exhausted = true;
                return false;
            }
            lookups++;
            return true;
        }

        private WhitespaceCascade inspect(String declaration) {
            WhitespaceCascade cached = inspections.get(declaration);
            if (cached != null) {
                return cached;
            }
            WhitespaceCascade inspection = whitespaceCascade(declaration);
            inspections.put(declaration, inspection);
            return inspection;
        }

        private WhitespaceCascade inspectInline(String declaration) {
            if (declaration.length()
                    > MAX_UNICODE_QR_STYLE_INSPECTION_CHARS - inlineChars) {
                exhausted = true;
                return new WhitespaceCascade(
                        false, false, true, false);
            }
            inlineChars += declaration.length();
            return whitespaceCascade(declaration);
        }
    }

    record CssNormalization(String css, boolean incomplete) {
    }

    record CssDeclaration(String name, String value, boolean important, boolean incomplete) {
    }

    record WhitespaceInspection(boolean preserves, boolean incomplete) {
    }

    private record CssPriority(String value, boolean important, boolean incomplete) {
    }

    private record WhitespaceCascade(
            boolean preserves, boolean important, boolean incomplete, boolean declared) {
    }

    private static BoundedMonospaceText collectMonospaceText(Element element) {
        StringBuilder text = new StringBuilder();
        BoundedTextVisitor visitor = new BoundedTextVisitor(text);
        try {
            element.traverse(visitor);
        } catch (UnicodeQrTextLimitException e) {
            visitor.truncated = true;
        }
        return new BoundedMonospaceText(text.toString(), visitor.truncated);
    }

    private static void markUnicodeQrIncomplete(Metadata metadata) {
        metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                UNICODE_QR_LIMIT_WARNING);
        if (metadata.get("ExploitClass") == null) {
            metadata.set("ExploitClass",
                    "HTML Unicode-QR extraction incomplete; encoded link content may be hidden");
        }
    }

    private static final class BoundedTextVisitor implements NodeVisitor {
        private final StringBuilder text;
        private boolean truncated;

        private BoundedTextVisitor(StringBuilder text) {
            this.text = text;
        }

        @Override
        public void head(Node node, int depth) {
            if (node instanceof Element
                    && "br".equalsIgnoreCase(((Element) node).tagName())) {
                append("\n");
            } else if (node instanceof TextNode) {
                append(((TextNode) node).getWholeText());
            }
        }

        @Override
        public void tail(Node node, int depth) {
            // no-op
        }

        private void append(String value) {
            int remaining = MAX_UNICODE_QR_CANDIDATE_CHARS - text.length();
            if (value.length() > remaining) {
                if (remaining > 0) {
                    text.append(value, 0, remaining);
                }
                throw UnicodeQrTextLimitException.INSTANCE;
            }
            text.append(value);
        }
    }

    private static final class UnicodeQrTextLimitException extends RuntimeException {
        private static final UnicodeQrTextLimitException INSTANCE =
                new UnicodeQrTextLimitException();

        private UnicodeQrTextLimitException() {
            super(null, null, false, false);
        }
    }

    private static final class BoundedMonospaceText {
        private final String text;
        private final boolean truncated;

        private BoundedMonospaceText(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }
    }

    public void parseString(String html, ContentHandler handler, Metadata metadata, ParseContext context) throws SAXException {
        // Get the HTML mapper from the parse context
        HtmlMapper mapper = context.get(HtmlMapper.class, new DefaultHtmlMapper());

        //do better with baseUri?
        Document document = Jsoup.parse(html, Parser.htmlParser().tagSet(SELF_CLOSEABLE_TAGS));
        scanForColorQR(document, metadata, context);
        document.quirksMode(Document.QuirksMode.quirks);
        ContentHandler xhtml = new XHTMLDowngradeHandler(
                new HtmlHandler(mapper, handler, metadata, context, extractScripts));
        xhtml.startDocument();
        try {
            NodeTraversor.filter(new TikaNodeFilter(xhtml), document);
        } catch (RuntimeSAXException e) {
            throw e.getWrapped();
        } finally {
            xhtml.endDocument();
        }
    }

    private class TikaNodeFilter implements NodeFilter {
        ContentHandler handler;

        private TikaNodeFilter(ContentHandler handler) {
            this.handler = handler;
        }

        @Override
        public NodeFilter.FilterResult head(Node node, int i) {

            if (node instanceof TextNode) {
                String txt = ((TextNode) node).getWholeText();
                if (txt != null) {
                    char[] chars = txt.toCharArray();
                    try {
                        if (chars.length > 0) {
                            handler.characters(chars, 0, chars.length);
                        }
                    } catch (SAXException e) {
                        throw new RuntimeSAXException(e);
                    }
                }
                return FilterResult.CONTINUE;
            } else if (node instanceof DataNode) {
                //maybe handle script data directly here instead of
                //passing it through to the HTMLHandler?
                String txt = ((DataNode) node).getWholeData();
                if (txt != null) {
                    char[] chars = txt.toCharArray();
                    try {
                        if (chars.length > 0) {
                            handler.characters(chars, 0, chars.length);
                        }
                    } catch (SAXException e) {
                        throw new RuntimeSAXException(e);
                    }
                }
                return FilterResult.CONTINUE;
            }
            AttributesImpl attributes = new AttributesImpl();
            Iterator<Attribute> jsoupAttrs = node.attributes().iterator();
            while (jsoupAttrs.hasNext()) {
                Attribute jsoupAttr = jsoupAttrs.next();
                attributes.addAttribute("", jsoupAttr.getKey(), jsoupAttr.getKey(), "",
                        jsoupAttr.getValue());
            }
            try {
                handler.startElement(XMLConstants.NULL_NS_URI, node.nodeName(), node.nodeName(),
                        attributes);
            } catch (SAXException e) {
                throw new RuntimeSAXException(e);
            }
            return FilterResult.CONTINUE;
        }

        @Override
        public NodeFilter.FilterResult tail(Node node, int i) {
            if (node instanceof TextNode || node instanceof DataNode) {
                return FilterResult.CONTINUE;
            }
            try {
                handler.endElement(XMLConstants.NULL_NS_URI, node.nodeName(), node.nodeName());
            } catch (SAXException e) {
                throw new RuntimeSAXException(e);
            }
            return FilterResult.CONTINUE;
        }
    }

    private static class RuntimeSAXException extends RuntimeException {
        private SAXException wrapped;

        private RuntimeSAXException(SAXException e) {
            this.wrapped = e;
        }

        SAXException getWrapped() {
            return wrapped;
        }
    }

    /**
     * Look for an EncodingDetetor in the ParseContext.  If it hasn't been
     * passed in, use the original EncodingDetector from initialization.
     *
     * @param parseContext
     * @return
     */
    protected EncodingDetector getEncodingDetector(ParseContext parseContext) {

        EncodingDetector fromParseContext = parseContext.get(EncodingDetector.class);
        if (fromParseContext != null) {
            return fromParseContext;
        }

        return getEncodingDetector();
    }

}
