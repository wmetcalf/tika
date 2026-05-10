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
package org.apache.tika.parser.xml;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import org.apache.tika.config.TikaComponent;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentUtil;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.EmbeddedContentHandler;
import org.apache.tika.sax.TaggedContentHandler;
import org.apache.tika.sax.TextContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;
import org.apache.tika.metadata.ImageHash;
import org.apache.tika.utils.ImageHashUtils;
import org.apache.tika.utils.XMLReaderUtils;

/**
 * XML parser.
 */
@TikaComponent(spi = false)
public class XMLParser implements Parser {

    /**
     * Serial version UID
     */
    private static final long serialVersionUID = -6028836725280212837L;

    private static final Set<MediaType> SUPPORTED_TYPES = Collections.unmodifiableSet(
            new HashSet<>(
                    Arrays.asList(MediaType.application("xml"), MediaType.image("svg+xml"))));

    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED_TYPES;
    }

    public void parse(TikaInputStream tis, ContentHandler handler, Metadata metadata,
                      ParseContext context) throws IOException, SAXException, TikaException {
        if (metadata.get(Metadata.CONTENT_TYPE) == null) {
            metadata.set(Metadata.CONTENT_TYPE, "application/xml");
        }

        boolean isSvg = "image/svg+xml".equals(metadata.get(Metadata.CONTENT_TYPE));
        Path svgPath = isSvg ? tis.getPath() : null;

        final XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata, context);
        xhtml.startDocument();
        xhtml.startElement("p");

        TaggedContentHandler tagged = new TaggedContentHandler(handler);
        tis.setCloseShield();
        try {
            XMLReaderUtils.parseSAX(tis,
                            new EmbeddedContentHandler(
                                    getContentHandler(tagged, metadata, context)),
                    context);
        } catch (SAXException e) {
            tagged.throwIfCauseOf(e);
            throw new TikaException("XML parse error", e);
        } finally {
            tis.removeCloseShield();
            if (svgPath != null) {
                trySvgOcr(svgPath, xhtml, metadata, context);
            }
            xhtml.endElement("p");
            xhtml.endDocument();
        }
    }

    // Skip rasterization for SVGs above this size to avoid OOM in Batik.
    // Data URI images are stripped before rendering so the main crash vector is handled;
    // 10 MB covers legitimate AI-generated art, data visualisations, and complex diagrams.
    private static final long SVG_RASTER_MAX_BYTES = 10L * 1024 * 1024; // 10 MB

    /**
     * Sanitize an SVG for Batik 1.x rasterization:
     * <ul>
     *   <li>Rewrites SVG 2.0 bare {@code href} on {@code <image>} to {@code xlink:href}.</li>
     *   <li>Strips inline data URI image content (replaces with empty string) to prevent
     *       Batik from crashing when decoding large embedded images.</li>
     *   <li>Strips external xlink:href values from {@code <use>} elements (SSRF protection).</li>
     * </ul>
     * Returns the original path unchanged if no edits were needed.
     */
    private static Path normalizeSvgHrefs(Path svgPath) throws IOException {
        byte[] raw = Files.readAllBytes(svgPath);
        String content = new String(raw, StandardCharsets.UTF_8);

        String fixed = content;

        // 1. Ensure xlink namespace is declared (Batik 1.x requires it)
        if (!fixed.contains("xmlns:xlink")) {
            fixed = fixed.replaceFirst(
                "(<svg\\b)([^>]*)(>)",
                "$1$2 xmlns:xlink=\"http://www.w3.org/1999/xlink\"$3");
        }

        // 2. Convert bare href= → xlink:href= (SVG 2.0 → SVG 1.1)
        fixed = fixed.replace(" href=", " xlink:href=");

        // 3. Strip data URI content from image hrefs — Batik crashes while decoding
        //    large or malformed inline images embedded as base64 data URIs.
        //    Keep the <image> element so layout is preserved; just remove the data.
        fixed = fixed.replaceAll(
            "(xlink:href|href)=\"data:[^\"]*\"",
            "$1=\"\"");

        // 4. Strip external xlink:href values from <use> elements (SSRF protection for Batik).
        //    Local fragment refs (#id) are preserved; only external URLs are cleared.
        fixed = fixed.replaceAll(
            "(<use\\b[^>]*?)xlink:href=\"(?!#)[^\"]+\"",
            "$1xlink:href=\"\"");

        if (fixed.equals(content)) {
            return svgPath;
        }
        Path tmp = Files.createTempFile("tika-svg-norm-", ".svg");
        Files.write(tmp, fixed.getBytes(StandardCharsets.UTF_8));
        return tmp;
    }

    /**
     * Rasterize {@code svgPath} to PNG bytes at the given max dimension.
     * Returns null if Batik fails for any reason (including OOM).
     */
    private static byte[] rasterizeSvg(Path svgPath, float maxDim) {
        try {
            org.apache.batik.transcoder.image.PNGTranscoder t =
                new org.apache.batik.transcoder.image.PNGTranscoder();
            t.addTranscodingHint(
                org.apache.batik.transcoder.image.ImageTranscoder.KEY_MAX_WIDTH, maxDim);
            t.addTranscodingHint(
                org.apache.batik.transcoder.image.ImageTranscoder.KEY_MAX_HEIGHT, maxDim);

            // Harden against XXE: use a pre-configured SAX reader with external entities
            // disabled. Passing a raw file URI lets Batik's own parser resolve DOCTYPE
            // entities; the XMLReader path blocks that without needing a custom UserAgent.
            SAXParserFactory spf = SAXParserFactory.newInstance();
            spf.setNamespaceAware(true);
            spf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            spf.setFeature(
                "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            spf.setXIncludeAware(false);
            XMLReader xmlReader = spf.newSAXParser().getXMLReader();
            // Belt-and-suspenders: null entity resolver returns empty for any surviving ref
            xmlReader.setEntityResolver((publicId, systemId) ->
                new InputSource(new StringReader("")));

            org.apache.batik.transcoder.TranscoderInput input =
                new org.apache.batik.transcoder.TranscoderInput(xmlReader);
            input.setURI(svgPath.toUri().toString());

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            t.transcode(input, new org.apache.batik.transcoder.TranscoderOutput(out));
            byte[] bytes = out.toByteArray();
            return bytes.length > 0 ? bytes : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static void trySvgOcr(Path svgPath, XHTMLContentHandler xhtml,
                                   Metadata metadata, ParseContext context) {
        Path renderPath = svgPath;
        try {
            // Guard against huge SVGs (e.g. embedded base64 images) that OOM the JVM
            if (Files.size(svgPath) > SVG_RASTER_MAX_BYTES) {
                return;
            }

            // Only rasterize in the primary parse pass (where EmbeddedLimits is set by
            // ParserRunner). EmbeddedFileExtractor's second pass does not set EmbeddedLimits,
            // so this guard prevents rasterizing the same SVG a second time unnecessarily.
            if (context.get(org.apache.tika.config.EmbeddedLimits.class) == null) {
                return;
            }

            // Rewrite SVG 2.0 href → xlink:href so Batik does not crash
            renderPath = normalizeSvgHrefs(svgPath);

            // Full-resolution raster for OCR and phash (1200px max).
            // If Batik fails here (OOM on complex SVGs), fall through to the
            // lower-res phash fallback below.
            byte[] pngBytes = rasterizeSvg(renderPath, 1200f);
            if (pngBytes != null) {
                // Phash/colorhash from the full-res render
                try {
                    BufferedImage raster = ImageIO.read(new ByteArrayInputStream(pngBytes));
                    ImageHashUtils.setHashes(raster, metadata);
                } catch (Exception ignored) { }

                // OCR dispatch — only if Tesseract (or equivalent) is configured
                org.apache.tika.parser.Parser ocrParser =
                    EmbeddedDocumentUtil.getStatelessParser(context);
                org.apache.tika.mime.MediaType pngOcrType =
                    org.apache.tika.mime.MediaType.image("ocr-png");
                if (ocrParser != null
                        && ocrParser.getSupportedTypes(context).contains(pngOcrType)) {
                    try (org.apache.tika.io.TikaInputStream pngStream =
                            org.apache.tika.io.TikaInputStream.get(pngBytes)) {
                        Metadata ocrMeta = Metadata.newInstance(context);
                        ocrMeta.set(TikaCoreProperties.CONTENT_TYPE_PARSER_OVERRIDE,
                            pngOcrType.toString());
                        ocrMeta.set(Metadata.CONTENT_TYPE, "image/png");
                        ocrParser.parse(pngStream,
                            new org.apache.tika.sax.EmbeddedContentHandler(
                                new BodyContentHandler(xhtml)),
                            ocrMeta, context);
                    } catch (Exception ignored) { }
                }
            }

            // Fallback: if phash still unset (1200px rasterization failed), try 512px.
            // This keeps phash in the Tika fork for complex/large SVGs where high-res
            // Batik rendering fails, rather than delegating to the application layer.
            if (metadata.get(ImageHash.PHASH) == null) {
                byte[] fallback = rasterizeSvg(renderPath, 512f);
                if (fallback != null) {
                    try {
                        BufferedImage img = ImageIO.read(new ByteArrayInputStream(fallback));
                        ImageHashUtils.setHashes(img, metadata);
                    } catch (Exception ignored) { }
                }
            }

        } catch (Throwable e) {
            // non-fatal
        } finally {
            if (!renderPath.equals(svgPath)) {
                try { Files.deleteIfExists(renderPath); } catch (IOException ignored) { }
            }
        }
    }

    protected ContentHandler getContentHandler(ContentHandler handler, Metadata metadata,
                                               ParseContext context) {
        if ("image/svg+xml".equals(metadata.get(Metadata.CONTENT_TYPE))) {
            return new SvgEnrichingHandler(new TextContentHandler(handler, true), metadata);
        }
        return new TextContentHandler(handler, true);
    }

    /**
     * SAX ContentHandler that wraps a TextContentHandler for SVG content and extracts
     * security-relevant features into metadata: foreignObject presence, event handler
     * attributes, external href/src references, and zero-width characters.
     */
    private static final class SvgEnrichingHandler implements ContentHandler {

        private static final String XLINK_NS = "http://www.w3.org/1999/xlink";

        private static final Set<String> EVENT_ATTRS;
        static {
            EVENT_ATTRS = new HashSet<>(Arrays.asList(
                "onclick", "onload", "onerror", "onmouseover", "onmouseenter",
                "onmouseleave", "onmouseout", "onmousedown", "onmouseup", "ondblclick",
                "onfocus", "onblur", "onsubmit", "oninput", "onkeydown", "onkeyup",
                "onkeypress", "onbegin", "onend", "onunload", "onabort"
            ));
        }

        // Zero-width / invisible characters to detect
        private static final String ZERO_WIDTH_CHARS = "​‌‍⁠﻿­";

        private final ContentHandler delegate;
        private final Metadata metadata;

        SvgEnrichingHandler(ContentHandler delegate, Metadata metadata) {
            this.delegate = delegate;
            this.metadata = metadata;
        }

        private static String getHref(Attributes attrs) {
            String v = attrs.getValue("href");
            if (v != null) {
                return v;
            }
            v = attrs.getValue(XLINK_NS, "href");
            if (v != null) {
                return v;
            }
            return attrs.getValue("xlink:href");
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts)
                throws SAXException {
            String local = localName != null && !localName.isEmpty() ? localName : qName;

            // Feature 1: foreignObject detection
            if ("foreignObject".equals(local)) {
                metadata.set("svg:hasForeignObject", "true");
            }

            // Feature 2: event handler attribute extraction
            int n = atts.getLength();
            for (int i = 0; i < n; i++) {
                String attrName = atts.getLocalName(i);
                if (attrName == null || attrName.isEmpty()) {
                    attrName = atts.getQName(i);
                }
                if (EVENT_ATTRS.contains(attrName)) {
                    metadata.set("svg:hasEventHandlers", "true");
                    String val = atts.getValue(i);
                    if (val != null && !val.isEmpty()) {
                        char[] chars = val.toCharArray();
                        delegate.characters(chars, 0, chars.length);
                    }
                }
            }

            // Feature 3: external href/src extraction
            if ("use".equals(local)) {
                String href = getHref(atts);
                if (href != null && !href.isEmpty() && !href.startsWith("#")) {
                    metadata.add("svg:externalUseRef", href);
                }
            } else if ("a".equals(local)) {
                String href = getHref(atts);
                if (href != null && !href.isEmpty() && !href.startsWith("#")) {
                    metadata.add("svg:link", href);
                }
            } else if ("script".equals(local)) {
                String src = atts.getValue("src");
                if (src != null && !src.isEmpty()) {
                    metadata.add("svg:externalScript", src);
                }
            }

            delegate.startElement(uri, localName, qName, atts);
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            // Feature 4: zero-width character detection
            for (int i = start; i < start + length; i++) {
                if (ZERO_WIDTH_CHARS.indexOf(ch[i]) >= 0) {
                    metadata.set("svg:hasZeroWidthChars", "true");
                    break;
                }
            }
            delegate.characters(ch, start, length);
        }

        @Override
        public void setDocumentLocator(Locator locator) {
            delegate.setDocumentLocator(locator);
        }

        @Override
        public void startDocument() throws SAXException {
            delegate.startDocument();
        }

        @Override
        public void endDocument() throws SAXException {
            delegate.endDocument();
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) throws SAXException {
            delegate.startPrefixMapping(prefix, uri);
        }

        @Override
        public void endPrefixMapping(String prefix) throws SAXException {
            delegate.endPrefixMapping(prefix);
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            delegate.endElement(uri, localName, qName);
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
            delegate.ignorableWhitespace(ch, start, length);
        }

        @Override
        public void processingInstruction(String target, String data) throws SAXException {
            delegate.processingInstruction(target, data);
        }

        @Override
        public void skippedEntity(String name) throws SAXException {
            delegate.skippedEntity(name);
        }
    }
}
