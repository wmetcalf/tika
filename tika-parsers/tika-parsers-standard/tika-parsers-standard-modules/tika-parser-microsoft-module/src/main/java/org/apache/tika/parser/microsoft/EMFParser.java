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
package org.apache.tika.parser.microsoft;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Dimension2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

import org.apache.poi.hemf.record.emf.HemfComment;
import org.apache.poi.hemf.record.emf.HemfRecord;
import org.apache.poi.hemf.record.emf.HemfRecordType;
import org.apache.poi.hemf.record.emf.HemfText;
import org.apache.poi.hemf.usermodel.HemfPicture;
import org.apache.poi.util.RecordFormatException;
import org.apache.poi.util.StringUtil;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.config.TikaComponent;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.EmbeddedDocumentUtil;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Property;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.EmbeddedContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;
import org.apache.tika.utils.ImageHashUtils;

/**
 * Extracts files embedded in EMF and offers a
 * very rough capability to extract text if there
 * is text stored in the EMF.
 * <p/>
 * To improve text extraction, we'd have to implement
 * quite a bit more at the POI level. We'd want to track changes
 * in font and use that information for identifying character sets,
 * inserting spaces and new lines.
 * <p/>
 * We're also relying on storage order for text order, which isn't great.
 * We'd have to do something like what PDFBox or XPS do to sort the
 * runs and then put the cow back together from the hamburger...lol...
 */
@TikaComponent
public class EMFParser implements Parser {

    public static Property EMF_ICON_ONLY = Property.internalBoolean("emf:iconOnly");
    public static Property EMF_ICON_STRING = Property.internalText("emf:iconString");

    private static String ICON_ONLY = "IconOnly";

    private static final MediaType MEDIA_TYPE = MediaType.image("emf");
    private static final MediaType WMF_MEDIA_TYPE = MediaType.image("wmf");

    private static final Set<MediaType> SUPPORTED_TYPES = Collections.singleton(MEDIA_TYPE);

    /** Maximum pixel dimension when rasterizing for OCR. */
    private static final int OCR_RASTER_MAX_PX = 1200;

    private static void handleEmbedded(byte[] data,
                                       EmbeddedDocumentExtractor embeddedDocumentExtractor,
                                       ContentHandler handler, ParseContext context) throws TikaException, SAXException {
        try (TikaInputStream tis = TikaInputStream.get(data)) {
            Metadata embeddedMetadata = Metadata.newInstance(context);
            if (embeddedDocumentExtractor.shouldParseEmbedded(embeddedMetadata)) {
                embeddedDocumentExtractor
                        .parseEmbedded(tis, new EmbeddedContentHandler(handler), embeddedMetadata, context, true);
            }
        } catch (IOException e) {
            //swallow
        }
    }

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED_TYPES;
    }

    @Override
    public void parse(TikaInputStream tis, ContentHandler handler, Metadata metadata,
                      ParseContext context) throws IOException, SAXException, TikaException {

        EmbeddedDocumentExtractor embeddedDocumentExtractor = null;
        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata, context);
        xhtml.startDocument();
        try {
            HemfPicture ex = new HemfPicture(tis);
            ParseState parseState = new ParseState();
            long fudgeFactorX = 10;//derive this from the font or frame/bounds information
            StringBuilder buffer = new StringBuilder();
            //iterate through the records.  if you hit IconOnly in a comment
            //and it is the first IconOnly, grab the string in the next comment record
            //and that'll be the full name of the file.

            //NOTE that we're just scraping the text out in storage order. The proper way to do this
            //is to sort the text records by x,y like we do for PDFs and xps
            for (HemfRecord record : ex) {
                parseState.isIconOnly = false;
                if (record.getEmfRecordType() == HemfRecordType.comment) {
                    handleCommentData(
                            ((HemfComment.EmfComment) record).getCommentData(), parseState, xhtml, context);
                } else if (record.getEmfRecordType().equals(HemfRecordType.extTextOutW)) {
                    handleExtTextOut((HemfText.EmfExtTextOutW) record, parseState, buffer, xhtml, fudgeFactorX, StandardCharsets.UTF_16LE);
                } else if (record.getEmfRecordType().equals(HemfRecordType.extTextOutA)) {
                    //do something better than assigning utf8.
                    handleExtTextOut((HemfText.EmfExtTextOutA) record, parseState, buffer, xhtml, fudgeFactorX, StandardCharsets.UTF_8);
                }

                if (parseState.isIconOnly) {
                    parseState.lastWasIconOnly = true;
                } else {
                    parseState.lastWasIconOnly = false;
                }
            }
            if (parseState.iconOnlyString != null) {
                metadata.set(EMF_ICON_ONLY, true);
                metadata.set(EMF_ICON_STRING, parseState.iconOnlyString);
            }
            if (! buffer.isEmpty()) {
                xhtml.startElement("p");
                xhtml.characters(buffer.toString());
                xhtml.endElement("p");
            }

            // Rasterize for OCR and perceptual hashing
            BufferedImage raster = rasterizeEmf(ex);
            tryMetafileOcr(raster, xhtml, metadata, context);
            ImageHashUtils.setHashes(raster, metadata);

        } catch (RecordFormatException e) { //POI's hemfparser can throw these for "parse
            // exceptions"
            throw new TikaException(e.getMessage(), e);
        } catch (RuntimeException e) { //convert Runtime to RecordFormatExceptions
            throw new TikaException(e.getMessage(), e);
        }
        xhtml.endDocument();
    }

    /**
     * Rasterize an EMF to a BufferedImage at up to OCR_RASTER_MAX_PX on the longest side.
     * Returns null if the size is invalid or rendering fails.
     */
    private static BufferedImage rasterizeEmf(HemfPicture emf) {
        try {
            Dimension2D size = emf.getSize();
            double pw = size.getWidth(), ph = size.getHeight();
            if (pw <= 0 || ph <= 0) return null;
            double scale = Math.min((double) OCR_RASTER_MAX_PX / pw, (double) OCR_RASTER_MAX_PX / ph);
            int w = Math.max(1, (int) (pw * scale)), h = Math.max(1, (int) (ph * scale));
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            emf.draw(g, new Rectangle2D.Double(0, 0, w, h));
            g.dispose();
            return img;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Encode {@code raster} as PNG and run it through Tesseract (if configured).
     * Uses the same ocr-image/png dispatch that AbstractImageParser uses for JPEG/PNG.
     * No-op if the OCR parser is absent or not configured.
     */
    static void tryMetafileOcr(BufferedImage raster, XHTMLContentHandler xhtml,
                                Metadata metadata, ParseContext context) {
        if (raster == null) return;
        try {
            Parser ocrParser = EmbeddedDocumentUtil.getStatelessParser(context);
            if (ocrParser == null) return;
            MediaType pngOcrType = MediaType.image("ocr-png");
            if (!ocrParser.getSupportedTypes(context).contains(pngOcrType)) return;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (!ImageIO.write(raster, "png", baos)) return;

            try (TikaInputStream pngStream = TikaInputStream.get(baos.toByteArray())) {
                Metadata ocrMeta = Metadata.newInstance(context);
                ocrMeta.set(TikaCoreProperties.CONTENT_TYPE_PARSER_OVERRIDE, pngOcrType.toString());
                ocrMeta.set(Metadata.CONTENT_TYPE, "image/png");
                ocrParser.parse(pngStream,
                        new EmbeddedContentHandler(new BodyContentHandler(xhtml)),
                        ocrMeta, context);
            }
        } catch (Exception e) {
            // non-fatal: OCR is best-effort
        }
    }

    private void handleExtTextOut(HemfText.EmfExtTextOutA record, ParseState parseState,
                                  StringBuilder buffer, XHTMLContentHandler xhtml, double fudgeFactorX,
                                  Charset charset) throws IOException, SAXException {
        Rectangle2D currRectangle = getCurrentRectangle(record);
        if (parseState.lastRectangle.getY() > -1 &&
                deltaGreaterThan(parseState.lastRectangle.getMinY(), currRectangle.getMinY(), 0.0001)) {
            xhtml.startElement("p");
            xhtml.characters(buffer.toString());
            xhtml.endElement("p");
            buffer.setLength(0);
        } else if (parseState.lastRectangle.getX() > -1 &&
                deltaGreaterThan(currRectangle.getMinX(),
                        parseState.lastRectangle.getMaxX(), fudgeFactorX)) {
            buffer.append(" ");
        }
        //do something better than this
        String txt = record.getText(charset);
        buffer.append(txt);
        parseState.lastRectangle = currRectangle;

    }

    private boolean deltaGreaterThan(double a, double b, double delta) {
        return (Math.abs(a - b) > delta);
    }

    private Rectangle2D getCurrentRectangle(HemfText.EmfExtTextOutA extTextOutA) {
        //This gets the current rectangle out of the emfextTextOutA record.
        //via TIKA-4432, if the rectangle is 0,0,0,0 then back-off to the bounds ignored, if those exist

        //TODO: maybe use modifyWorldTransform and calculate font width etc...
        Rectangle2D bounds = extTextOutA.getBounds();
        double smidge = 0.000000001;
        if (deltaGreaterThan(bounds.getX(), 0.0d, smidge) ||
                deltaGreaterThan(bounds.getY(), 0.0d, smidge) ||
                deltaGreaterThan(bounds.getWidth(), 0.0d, smidge) ||
                deltaGreaterThan(bounds.getHeight(), 0.0d, smidge)) {
            return bounds;
        }
        Supplier<?> boundsIgnored = extTextOutA.getGenericProperties().get("boundsIgnored");
        if (boundsIgnored == null) {
            return bounds;
        }
        Object maybeBounds = boundsIgnored.get();
        if (maybeBounds == null) {
            return bounds;
        }
        if (! (maybeBounds instanceof Rectangle2D)) {
            return bounds;
        }
        return (Rectangle2D) maybeBounds;
    }

    private void handleCommentData(
            HemfComment.EmfCommentData commentData, ParseState parseState,
            XHTMLContentHandler xhtml, ParseContext context)
            throws IOException, TikaException, SAXException {

        if (commentData instanceof HemfComment.EmfCommentDataMultiformats) {
            if (parseState.extractor == null) {
                parseState.extractor =
                        EmbeddedDocumentUtil.getEmbeddedDocumentExtractor(context);
            }
            handleMultiFormats((HemfComment.EmfCommentDataMultiformats) commentData,
                    xhtml, parseState.extractor, context);
        } else if (commentData instanceof HemfComment.EmfCommentDataWMF) {
            if (parseState.extractor == null) {
                parseState.extractor =
                        EmbeddedDocumentUtil.getEmbeddedDocumentExtractor(context);
            }
            handleWMF(((HemfComment.EmfCommentDataWMF) commentData).getWMFData(), xhtml,
                    parseState.extractor, context);
        } else if (commentData instanceof HemfComment.EmfCommentDataGeneric) {
            String val =
                    tryToReadAsString((((HemfComment.EmfCommentDataGeneric) commentData).getPrivateData()));
            if (ICON_ONLY.equals(val) && parseState.hitIconOnly == false) {
                parseState.hitIconOnly = true;
                parseState.isIconOnly = true;
            } else if (parseState.lastWasIconOnly && parseState.iconOnlyString == null) {
                parseState.iconOnlyString = val;
            }
        }
    }

    private String tryToReadAsString(byte[] bytes) {
        if (bytes.length < 2) {
            return null;
        }
        //act like this is a null terminated unicode le
        int stringLen = (bytes.length - 2) / 2;
        try {
            return StringUtil.getFromUnicodeLE0Terminated(bytes, 0, stringLen);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            //didn't work out...oh, well
        }
        return null;
    }

    private void handleWMF(byte[] bytes, ContentHandler contentHandler,
                           EmbeddedDocumentExtractor embeddedDocumentExtractor,
                           ParseContext context)
            throws IOException, SAXException, TikaException {
        Metadata embeddedMetadata = Metadata.newInstance(context);
        embeddedMetadata.set(Metadata.CONTENT_TYPE, WMF_MEDIA_TYPE.toString());
        if (embeddedDocumentExtractor.shouldParseEmbedded(embeddedMetadata)) {
            try (TikaInputStream tis = TikaInputStream.get(bytes)) {
                embeddedDocumentExtractor
                        .parseEmbedded(tis, new EmbeddedContentHandler(contentHandler),
                                embeddedMetadata, context, true);

            }

        }

    }

    private void handleMultiFormats(HemfComment.EmfCommentDataMultiformats commentData,
                                    ContentHandler handler,
                                    EmbeddedDocumentExtractor embeddedDocumentExtractor,
                                    ParseContext context)
            throws IOException, TikaException, SAXException {

        for (HemfComment.EmfCommentDataFormat dataFormat : commentData.getFormats()) {
            //is this right?!
            handleEmbedded(dataFormat.getRawData(), embeddedDocumentExtractor, handler, context);
        }
    }

    private static class ParseState {
        Rectangle2D lastRectangle = new Rectangle2D.Double(-1.0, -1.0, 0.0, 0.0);
        boolean hitIconOnly = false;
        boolean lastWasIconOnly = false;
        boolean isIconOnly = false;
        String iconOnlyString = null;

        EmbeddedDocumentExtractor extractor;
    }
}
