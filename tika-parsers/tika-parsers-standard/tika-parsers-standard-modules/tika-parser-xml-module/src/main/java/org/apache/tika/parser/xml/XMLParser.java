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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.imageio.ImageIO;

import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

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

    // Skip rasterization for SVGs above this size to avoid OOM in Batik
    private static final long SVG_RASTER_MAX_BYTES = 512L * 1024; // 512 KB

    /**
     * Sanitize an SVG for Batik 1.x rasterization:
     * <ul>
     *   <li>Rewrites SVG 2.0 bare {@code href} on {@code <image>} to {@code xlink:href}.</li>
     *   <li>Strips inline data URI image content (replaces with empty string) to prevent
     *       Batik from crashing when decoding large embedded images.</li>
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

        if (fixed.equals(content)) {
            return svgPath;
        }
        Path tmp = Files.createTempFile("tika-svg-norm-", ".svg");
        Files.write(tmp, fixed.getBytes(StandardCharsets.UTF_8));
        return tmp;
    }

    private static void trySvgOcr(Path svgPath, XHTMLContentHandler xhtml,
                                   Metadata metadata, ParseContext context) {
        Path renderPath = svgPath;
        try {
            // Guard against huge SVGs (e.g. embedded base64 images) that OOM the JVM
            if (Files.size(svgPath) > SVG_RASTER_MAX_BYTES) {
                return;
            }

            // Rewrite SVG 2.0 href → xlink:href so Batik does not crash
            renderPath = normalizeSvgHrefs(svgPath);

            org.apache.batik.transcoder.image.PNGTranscoder transcoder =
                new org.apache.batik.transcoder.image.PNGTranscoder();
            transcoder.addTranscodingHint(
                org.apache.batik.transcoder.image.ImageTranscoder.KEY_MAX_WIDTH, 1200f);
            transcoder.addTranscodingHint(
                org.apache.batik.transcoder.image.ImageTranscoder.KEY_MAX_HEIGHT, 1200f);

            ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
            transcoder.transcode(
                new org.apache.batik.transcoder.TranscoderInput(renderPath.toUri().toString()),
                new org.apache.batik.transcoder.TranscoderOutput(pngOut));
            byte[] pngBytes = pngOut.toByteArray();
            if (pngBytes.length == 0) {
                return;
            }

            // Compute perceptual hashes from the rasterized PNG (always, regardless of OCR)
            try {
                BufferedImage raster = ImageIO.read(new ByteArrayInputStream(pngBytes));
                ImageHashUtils.setHashes(raster, metadata);
            } catch (Exception e) {
                // non-fatal
            }

            // OCR dispatch — only if Tesseract (or equivalent) is configured
            org.apache.tika.parser.Parser ocrParser =
                EmbeddedDocumentUtil.getStatelessParser(context);
            if (ocrParser == null) {
                return;
            }
            org.apache.tika.mime.MediaType pngOcrType =
                org.apache.tika.mime.MediaType.image("ocr-png");
            if (!ocrParser.getSupportedTypes(context).contains(pngOcrType)) {
                return;
            }

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
            }
        } catch (Throwable e) {
            // non-fatal: catch Error subclasses (OOM, StackOverflow) too
        } finally {
            if (!renderPath.equals(svgPath)) {
                try { Files.deleteIfExists(renderPath); } catch (IOException ignored) { }
            }
        }
    }

    protected ContentHandler getContentHandler(ContentHandler handler, Metadata metadata,
                                               ParseContext context) {
        return new TextContentHandler(handler, true);
    }
}
