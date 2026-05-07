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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentUtil;
import org.apache.tika.io.TemporaryResources;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Barcode;
import org.apache.tika.metadata.ImageHash;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.EmbeddedContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;

public abstract class AbstractImageParser implements Parser {

    public static String OCR_MEDIATYPE_PREFIX = "ocr-";
    private static final Logger LOG = LoggerFactory.getLogger(AbstractImageParser.class);
    private boolean imageHashingEnabled = false;

    /**
     *
     * @param mediaType
     * @return ocr media type if mediatype is not null; returns null if mediatype is null
     */
    static MediaType convertToOCRMediaType(MediaType mediaType) {
        if (mediaType == null) {
            return null;
        }
        return new MediaType(mediaType.getType(), OCR_MEDIATYPE_PREFIX + mediaType.getSubtype());
    }

    abstract void extractMetadata(InputStream is, ContentHandler contentHandler, Metadata metadata,
                                  ParseContext parseContext)
            throws IOException, SAXException, TikaException;

    //if the parser needs to normalize the mediaType, override this.
    //this is a no-op, returning the mediaType that is sent in
    MediaType normalizeMediaType(MediaType mediaType) {
        return mediaType;
    }

    List<ZXingCPPScanner.Result> scanBarcodes(Path imagePath, ParseContext context) {
        ZXingCPPConfig config = context.get(ZXingCPPConfig.class);
        if (config == null || !config.isEnabled()) {
            return Collections.emptyList();
        }
        ZXingCPPScanner scanner = getBarcodeScanner();
        if (!scanner.hasZXingCPP(config)) {
            return Collections.emptyList();
        }
        return scanner.scan(imagePath, config, context);
    }

    ZXingCPPScanner getBarcodeScanner() {
        return new ZXingCPPScanner();
    }

    private void addBarcodeMetadata(Path imagePath, Metadata metadata, ParseContext context) {
        if (imagePath == null) {
            return;
        }
        try {
            for (ZXingCPPScanner.Result result : scanBarcodes(imagePath, context)) {
                metadata.add(Barcode.BARCODE_VALUE, safe(result.getText()));
                metadata.add(Barcode.BARCODE_FORMAT, safe(normalizeBarcodeFormat(result.getFormat())));
                metadata.add(Barcode.BARCODE_RAW_BYTES, safe(result.getRawBytes()));
                metadata.add(Barcode.BARCODE_POSITION, safe(result.getPosition()));
                metadata.add(Barcode.BARCODE_ERROR_CORRECTION_LEVEL,
                        safe(result.getErrorCorrectionLevel()));
                metadata.add(Barcode.BARCODE_IS_MIRRORED,
                        Boolean.toString(result.isMirrored()));
            }
        } catch (ZXingCPPScanner.ScanException e) {
            LOG.warn("Unable to scan barcodes from image {}", imagePath, e);
        }
    }

    private void addImageHashMetadata(Path imagePath, Metadata metadata) throws IOException {
        if (!imageHashingEnabled || imagePath == null) {
            return;
        }
        BufferedImage image = ImageIO.read(imagePath.toFile());
        if (image == null) {
            return;
        }
        metadata.set(ImageHash.PHASH, ImageHashExtractor.computePhash(image));
        metadata.set(ImageHash.COLORHASH, ImageHashExtractor.computeColorHash(image));
    }

    private String normalizeBarcodeFormat(String format) {
        if (format == null) {
            return null;
        }
        String normalized = format.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.length() == 0 ? null : normalized;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    void prepareBarcodePathLookup(TikaInputStream tis, ParseContext context) {
        ZXingCPPConfig config = context.get(ZXingCPPConfig.class);
        if ((config == null || !config.isEnabled()) && !imageHashingEnabled) {
            return;
        }
        tis.enableRewind();
    }

    Path getBarcodePath(TikaInputStream tis, ParseContext context) throws IOException {
        ZXingCPPConfig config = context.get(ZXingCPPConfig.class);
        if ((config == null || !config.isEnabled()) && !imageHashingEnabled) {
            return null;
        }
        return tis.getPath();
    }

    public boolean isImageHashingEnabled() {
        return imageHashingEnabled;
    }

    public void setImageHashingEnabled(boolean imageHashingEnabled) {
        this.imageHashingEnabled = imageHashingEnabled;
    }

    @Override
    public void parse(TikaInputStream tis, ContentHandler handler, Metadata metadata,
                      ParseContext context) throws IOException, SAXException, TikaException {

        String mediaTypeString = metadata.get(Metadata.CONTENT_TYPE);
        //note: mediaType can be null if mediaTypeString is null or
        //not parseable.
        MediaType mediaType = normalizeMediaType(MediaType.parse(mediaTypeString));
        MediaType ocrMediaType = convertToOCRMediaType(mediaType);
        Parser ocrParser = EmbeddedDocumentUtil.getStatelessParser(context);
        if (ocrMediaType == null ||
                ocrParser == null || !ocrParser.getSupportedTypes(context).contains(ocrMediaType)) {
            prepareBarcodePathLookup(tis, context);
            extractMetadata(tis, handler, metadata, context);
            Path barcodePath = getBarcodePath(tis, context);
            addImageHashMetadata(barcodePath, metadata);
            addBarcodeMetadata(barcodePath, metadata, context);
            XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata, context);
            xhtml.startDocument();
            xhtml.endDocument();
            return;
        }

        TemporaryResources tmpResources = new TemporaryResources();
        Exception metadataException = null;
        try {
            XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata, context);
            xhtml.startDocument();
            prepareBarcodePathLookup(tis, context);
            Path path = tis.getPath();
            try (InputStream pathStream = Files.newInputStream(path)) {
                extractMetadata(pathStream, new EmbeddedContentHandler(xhtml), metadata, context);
                Path barcodePath = getBarcodePath(tis, context);
                addImageHashMetadata(barcodePath, metadata);
                addBarcodeMetadata(barcodePath, metadata, context);
            } catch (SecurityException e) {
                throw e;
            } catch (Exception e) {
                metadataException = e;
            }

            try (TikaInputStream pathStream = TikaInputStream.get(path)) {
                //specify ocr content type
                String originalParserOverride =
                        metadata.get(TikaCoreProperties.CONTENT_TYPE_PARSER_OVERRIDE);
                String originalContentType = metadata.get(Metadata.CONTENT_TYPE);
                metadata.set(TikaCoreProperties.CONTENT_TYPE_PARSER_OVERRIDE,
                        ocrMediaType.toString());
                //need to use bodycontenthandler to filter out re-dumping of metadata
                //in xhtmlhandler
                try {
                    ocrParser.parse(pathStream,
                            new EmbeddedContentHandler(new BodyContentHandler(xhtml)), metadata,
                            context);
                } finally {
                    if (originalParserOverride == null) {
                        metadata.remove(TikaCoreProperties.CONTENT_TYPE_PARSER_OVERRIDE.getName());
                    } else {
                        metadata.set(TikaCoreProperties.CONTENT_TYPE_PARSER_OVERRIDE,
                                originalParserOverride);
                    }
                    if (originalContentType == null) {
                        metadata.remove(Metadata.CONTENT_TYPE);
                    } else {
                        metadata.set(Metadata.CONTENT_TYPE, originalContentType);
                    }
                }
            }
            xhtml.endDocument();
        } finally {
            tmpResources.close();
        }
        if (metadataException != null) {
            throw new TikaException("problem extracting metadata", metadataException);
        }
    }
}
