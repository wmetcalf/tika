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

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
import org.apache.tika.sax.XHTMLContentHandler;
import org.apache.tika.utils.ImageHashUtils;


@TikaComponent
public class HeifParser extends AbstractImageParser {

    private static final Set<MediaType> SUPPORTED_TYPES = new HashSet<>(
            Arrays.asList(MediaType.image("heif"), MediaType.image("heif-sequence"),
                    MediaType.image("heic"), MediaType.image("heic-sequence")));

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED_TYPES;
    }

    @Override
    void extractMetadata(InputStream stream, ContentHandler contentHandler, Metadata metadata,
                         ParseContext parseContext)
            throws IOException, SAXException, TikaException {
        new ImageMetadataExtractor(metadata).parseHeif(stream);
    }

    /**
     * Override AbstractImageParser to rasterize via heif-convert and OCR via Tesseract.
     * AbstractImageParser would look for a parser supporting "ocr-image/heic" but
     * TesseractOCRParser only registers ocr-image/png etc., so the standard path
     * silently skips OCR for HEIC/HEIF. We convert to PNG ourselves and reuse the
     * same ocr-image/png dispatch used by EMFParser and SVGParser.
     */
    @Override
    public void parse(TikaInputStream tis, ContentHandler handler, Metadata metadata,
                      ParseContext context) throws IOException, SAXException, TikaException {
        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata, context);
        xhtml.startDocument();

        // Metadata extraction (Exif, IPTC, XMP) — non-fatal
        try (InputStream is = Files.newInputStream(tis.getPath())) {
            extractMetadata(is, new EmbeddedContentHandler(xhtml), metadata, context);
        } catch (Exception e) {
            // metadata failure must not abort OCR
        }

        // Rasterize via heif-convert and run Tesseract OCR
        tryHeifOcr(tis.getPath(), xhtml, metadata, context);

        xhtml.endDocument();
    }

    private static void tryHeifOcr(Path heifPath, XHTMLContentHandler xhtml,
                                    Metadata metadata, ParseContext context) {
        Path tmpPng = null;
        try {
            tmpPng = Files.createTempFile("tika-heif-", ".png");
            Process proc = new ProcessBuilder(
                    "heif-convert", "-q", "90",
                    heifPath.toString(), tmpPng.toString())
                .redirectErrorStream(true)
                .start();
            boolean finished = proc.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return;
            }
            if (proc.exitValue() != 0) {
                return;
            }

            // heif-convert may produce numbered output for multi-frame files
            Path actualPng = tmpPng;
            if (!Files.exists(tmpPng) || Files.size(tmpPng) == 0) {
                String base = tmpPng.toString().replaceFirst("\\.png$", "");
                actualPng = Path.of(base + "-1.png");
            }
            if (!Files.exists(actualPng) || Files.size(actualPng) == 0) {
                return;
            }

            byte[] pngBytes = Files.readAllBytes(actualPng);

            // Compute perceptual hashes from the rasterized PNG (always, regardless of OCR)
            try {
                BufferedImage raster = ImageIO.read(new java.io.ByteArrayInputStream(pngBytes));
                ImageHashUtils.setHashes(raster, metadata);
            } catch (Exception e) {
                // non-fatal
            }

            // OCR dispatch — only if Tesseract (or equivalent) is configured
            Parser ocrParser = EmbeddedDocumentUtil.getStatelessParser(context);
            if (ocrParser == null) {
                return;
            }
            MediaType pngOcrType = MediaType.image("ocr-png");
            if (!ocrParser.getSupportedTypes(context).contains(pngOcrType)) {
                return;
            }

            try (TikaInputStream pngStream = TikaInputStream.get(pngBytes)) {
                Metadata ocrMeta = Metadata.newInstance(context);
                ocrMeta.set(TikaCoreProperties.CONTENT_TYPE_PARSER_OVERRIDE,
                        pngOcrType.toString());
                ocrMeta.set(Metadata.CONTENT_TYPE, "image/png");
                ocrParser.parse(pngStream,
                        new EmbeddedContentHandler(new BodyContentHandler(xhtml)),
                        ocrMeta, context);
            }
        } catch (Exception e) {
            // non-fatal
        } finally {
            if (tmpPng != null) {
                try { Files.deleteIfExists(tmpPng); } catch (IOException ignored) { }
                String base = tmpPng.toString().replaceFirst("\\.png$", "");
                for (int i = 1; i <= 10; i++) {
                    try { Files.deleteIfExists(Path.of(base + "-" + i + ".png")); }
                    catch (IOException ignored) { }
                }
            }
        }
    }
}
