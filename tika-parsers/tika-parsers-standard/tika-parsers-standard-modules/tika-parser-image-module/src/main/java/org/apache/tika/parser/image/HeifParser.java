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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;

import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.annotation.TikaComponent;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentUtil;
import org.apache.tika.io.TemporaryResources;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.EmbeddedContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;
import org.apache.tika.utils.FileProcessResult;
import org.apache.tika.utils.ImageHashUtils;
import org.apache.tika.utils.ProcessUtils;


@TikaComponent
public class HeifParser extends AbstractImageParser {

    private static final long HEIF_CONVERT_TIMEOUT_MILLIS = 30_000L;
    private static final long MAX_RENDERED_PNG_BYTES = 50L * 1024L * 1024L;
    private static final int MAX_PROCESS_OUTPUT = 8192;

    private static final Set<MediaType> SUPPORTED_TYPES = new HashSet<>(
            Arrays.asList(MediaType.image("heif"), MediaType.image("heif-sequence"),
                    MediaType.image("heic"), MediaType.image("heic-sequence")));

    private String heifConvertPath = "heif-convert";

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED_TYPES;
    }

    @Override
    void extractMetadata(InputStream stream, ContentHandler contentHandler, Metadata metadata,
                         ParseContext parseContext)
            throws IOException, SAXException, TikaException {
        TemporaryResources tmp = new TemporaryResources();
        try {
            TikaInputStream tis = TikaInputStream.get(stream, tmp, metadata);
            File file = tis.getFile();   // spool so the file can be re-read below
            // XMP first so it is canonical; metadata-extractor (EXIF/GPS) fills gaps.
            // Locate the XMP item precisely via meta/iinf/iloc first, so an embedded resource's XMP
            // (e.g. a motion-photo video in mdat) can't be attributed to the image; fall back to the
            // byte scanner for packets not reachable through the boxes.
            if (!HeifXmp.extract(file, metadata, parseContext)) {
                ImageXmp.scanAndExtract(tis, metadata, parseContext);
            }
            try (InputStream heif = Files.newInputStream(file.toPath())) {
                new ImageMetadataExtractor(metadata).parseHeif(heif);
            }
        } finally {
            tmp.dispose();
        }
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

        // Rasterize via heif-convert only when OCR or image hashing needs a PNG.
        tryHeifOcr(tis.getPath(), xhtml, metadata, context);

        xhtml.endDocument();
    }

    public String getHeifConvertPath() {
        return heifConvertPath;
    }

    public void setHeifConvertPath(String heifConvertPath) {
        if (heifConvertPath != null && heifConvertPath.indexOf('\0') > -1) {
            throw new IllegalArgumentException("heif-convert path must not contain null bytes");
        }
        this.heifConvertPath = heifConvertPath == null || heifConvertPath.isEmpty() ?
                "heif-convert" : heifConvertPath;
    }

    private void tryHeifOcr(Path heifPath, XHTMLContentHandler xhtml,
                            Metadata metadata, ParseContext context) {
        Parser ocrParser = EmbeddedDocumentUtil.getStatelessParser(context);
        MediaType pngOcrType = MediaType.image("ocr-png");
        boolean ocrEnabled = ocrParser != null &&
                ocrParser.getSupportedTypes(context).contains(pngOcrType);
        if (!isImageHashingEnabled() && !ocrEnabled) {
            return;
        }

        Path tmpPng = null;
        try {
            tmpPng = Files.createTempFile("tika-heif-", ".png");
            ProcessBuilder pb = new ProcessBuilder(
                    heifConvertPath, "-q", "90", heifPath.toString(), tmpPng.toString());
            FileProcessResult result = ProcessUtils.execute(pb, HEIF_CONVERT_TIMEOUT_MILLIS,
                    MAX_PROCESS_OUTPUT, MAX_PROCESS_OUTPUT);
            if (result.isTimeout() || result.getExitValue() != 0) {
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
            if (Files.size(actualPng) > MAX_RENDERED_PNG_BYTES) {
                return;
            }

            byte[] pngBytes = Files.readAllBytes(actualPng);

            if (isImageHashingEnabled()) {
                try {
                    BufferedImage raster = ImageIO.read(new java.io.ByteArrayInputStream(pngBytes));
                    ImageHashUtils.setHashes(raster, metadata);
                } catch (Exception | OutOfMemoryError e) {
                    // non-fatal (Error too: a small PNG within MAX_RENDERED_PNG_BYTES can still
                    // decode to a huge in-memory raster and OOM the ImageIO decode — contain it)
                }
            }

            if (!ocrEnabled) {
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
                Path parent = tmpPng.getParent();
                String prefix = new File(base).getName() + "-";
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent,
                        prefix + "*.png")) {
                    for (Path path : stream) {
                        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                    }
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            }
        }
    }
}
