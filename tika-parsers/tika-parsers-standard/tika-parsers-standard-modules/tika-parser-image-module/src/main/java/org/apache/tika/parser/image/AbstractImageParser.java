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
import java.awt.image.DataBuffer;
import java.awt.image.SampleModel;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.ImageInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

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
import org.apache.tika.utils.ImageHashUtils;

public abstract class AbstractImageParser implements Parser {

    public static String OCR_MEDIATYPE_PREFIX = "ocr-";
    private static final Logger LOG = LoggerFactory.getLogger(AbstractImageParser.class);
    private static final long MAX_IMAGE_HASH_PIXELS = 16L * 1024 * 1024;
    private static final long MAX_IMAGE_HASH_RASTER_BYTES = 16L * 1024 * 1024;
    private static final String IMAGE_HASH_DIMENSION_WARNING =
            "Image hashing skipped because decoded dimensions exceed the "
                    + MAX_IMAGE_HASH_PIXELS + " pixel limit";
    private static final String IMAGE_HASH_RASTER_WARNING =
            "Image hashing skipped because the decoded raster exceeds the "
                    + MAX_IMAGE_HASH_RASTER_BYTES + " byte limit";
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
                BarcodeMetadataUtil.addResult(metadata, result, "");
            }
        } catch (ZXingCPPScanner.ScanException e) {
            LOG.warn("Unable to scan barcodes from image {}", imagePath, e);
        }
    }

    private void addImageHashMetadata(Path imagePath, Metadata metadata) throws IOException {
        if (!imageHashingEnabled || imagePath == null) {
            return;
        }
        if (!dimensionsSafeForHashing(imagePath, metadata)) {
            return;
        }
        BufferedImage image = ImageIO.read(imagePath.toFile());
        if (image == null) {
            return;
        }
        // Delegates to tika-core's ImageHashUtils, which uses the rosetta-squint
        // hash library — byte-exact-compatible with Python imagehash 4.3.2. Sets
        // phash, dhash, ahash, and colorhash in one shot. Mirrors XMLParser.
        ImageHashUtils.setHashes(image, metadata);
    }

    private static boolean dimensionsSafeForHashing(Path imagePath, Metadata metadata)
            throws IOException {
        try (ImageInputStream imageInput =
                     ImageIO.createImageInputStream(imagePath.toFile())) {
            if (imageInput == null) {
                return true;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                return true;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0
                        || (long) width * height > MAX_IMAGE_HASH_PIXELS) {
                    metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                            IMAGE_HASH_DIMENSION_WARNING + ": " + width + "x" + height);
                    return false;
                }
                ImageTypeSpecifier imageType = reader.getRawImageType(0);
                if (imageType == null) {
                    Iterator<ImageTypeSpecifier> imageTypes = reader.getImageTypes(0);
                    imageType = imageTypes.hasNext() ? imageTypes.next() : null;
                }
                if (imageType == null) {
                    metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                            IMAGE_HASH_RASTER_WARNING + ": unknown sample model");
                    return false;
                }
                SampleModel sampleModel = imageType.getSampleModel();
                long bitsPerPixel = 0;
                for (int sampleBits : sampleModel.getSampleSize()) {
                    bitsPerPixel += sampleBits;
                }
                long storageBitsPerPixel =
                        (long) DataBuffer.getDataTypeSize(sampleModel.getDataType())
                                * sampleModel.getNumDataElements();
                bitsPerPixel = Math.max(bitsPerPixel, storageBitsPerPixel);
                long decodedBytes =
                        (((long) width * height * bitsPerPixel) + 7) / 8;
                if (bitsPerPixel <= 0 || decodedBytes > MAX_IMAGE_HASH_RASTER_BYTES) {
                    metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                            IMAGE_HASH_RASTER_WARNING + ": " + decodedBytes + " bytes");
                    return false;
                }
                return true;
            } finally {
                reader.dispose();
            }
        }
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
            // Mirror the OCR branch below: metadata extraction / image hashing
            // (ImageIO.read) / barcode scanning can throw unchecked exceptions on a
            // malformed/adversarial image. Wrap them so a raw RuntimeException never
            // escapes parse() — the Tika parser contract requires TikaException.
            try {
                extractMetadata(tis, handler, metadata, context);
                Path barcodePath = getBarcodePath(tis, context);
                addImageHashMetadata(barcodePath, metadata);
                addBarcodeMetadata(barcodePath, metadata, context);
            } catch (IOException | SAXException | TikaException e) {
                throw e;
            } catch (SecurityException e) {
                // Mirror the OCR branch: let a SecurityException propagate unwrapped
                // rather than masking it as a generic TikaException.
                throw e;
            } catch (RuntimeException e) {
                throw new TikaException("problem extracting image metadata", e);
            }
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
