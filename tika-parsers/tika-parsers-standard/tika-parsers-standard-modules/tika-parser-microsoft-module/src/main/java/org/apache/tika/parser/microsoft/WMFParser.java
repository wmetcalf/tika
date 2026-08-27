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
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Set;

import org.apache.poi.hwmf.record.HwmfFont;
import org.apache.poi.hwmf.record.HwmfRecord;
import org.apache.poi.hwmf.record.HwmfRecordType;
import org.apache.poi.hwmf.record.HwmfText;
import org.apache.poi.hwmf.usermodel.HwmfPicture;
import org.apache.poi.util.LocaleUtil;
import org.apache.poi.util.RecordFormatException;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.annotation.TikaComponent;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.XHTMLContentHandler;
import org.apache.tika.utils.ImageHashUtils;

/**
 * This parser offers a very rough capability to extract text if there
 * is text stored in the WMF files.
 */
@TikaComponent
public class WMFParser implements Parser {

    private static final MediaType MEDIA_TYPE = MediaType.image("wmf");

    private static final Set<MediaType> SUPPORTED_TYPES = Collections.singleton(MEDIA_TYPE);

    /** Maximum pixel dimension when rasterizing for OCR. */
    private static final int OCR_RASTER_MAX_PX = 1200;

    private boolean imageHashingEnabled = false;

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED_TYPES;
    }

    @Override
    public void parse(TikaInputStream tis, ContentHandler handler, Metadata metadata,
                      ParseContext context) throws IOException, SAXException, TikaException {
        // A ParseContext is routinely reused across INDEPENDENT documents (TikaCLI passes one to
        // every file on the command line), which would make the budget cumulative and silently
        // blind every later document. No-op when this parse is embedded.
        MetafileRenderBudget.beginDocument(context);
        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata, context);
        xhtml.startDocument();
        tis.setCloseShield();
        try {
            HwmfPicture picture = null;
            try {
                picture = new HwmfPicture(tis);
            } catch (ArrayIndexOutOfBoundsException e) {
                //POI can throw this on corrupt files
                throw new TikaException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            }
            Charset charset = LocaleUtil.CHARSET_1252;
            //TODO: make x/y info public in POI so that we can use it here
            //to determine when to keep two text parts on the same line
            for (HwmfRecord record : picture.getRecords()) {
                //this is pure hackery for specifying the font
                //TODO: do what Graphics does by maintaining the stack, etc.!
                //This fix should be done within POI
                if (record.getWmfRecordType().equals(HwmfRecordType.createFontIndirect)) {
                    HwmfFont font = ((HwmfText.WmfCreateFontIndirect) record).getFont();
                    charset =
                            (font.getCharset() == null || font.getCharset().getCharset() == null) ?
                                    LocaleUtil.CHARSET_1252 : font.getCharset().getCharset();
                }
                if (record.getWmfRecordType().equals(HwmfRecordType.extTextOut)) {
                    HwmfText.WmfExtTextOut textOut = (HwmfText.WmfExtTextOut) record;
                    xhtml.startElement("p");
                    xhtml.characters(textOut.getText(charset));
                    xhtml.endElement("p");
                } else if (record.getWmfRecordType().equals(HwmfRecordType.textOut)) {
                    HwmfText.WmfTextOut textOut = (HwmfText.WmfTextOut) record;
                    xhtml.startElement("p");
                    xhtml.characters(textOut.getText(charset));
                    xhtml.endElement("p");
                }
            }

            boolean hashEnabled = isImageHashingEnabled(context);
            if (hashEnabled || EMFParser.hasMetafileOcr(context)) {
                if (!EMFParser.renderBudget(context).tryConsume()) {
                    metadata.set(EMFParser.RENDER_BUDGET_EXHAUSTED, true);
                    xhtml.endDocument();
                    return;
                }
                int[] simplified = new int[1];
                boolean[] unusable = new boolean[1];
                double[] rejectedAspect = new double[1];
                BufferedImage raster = rasterizeWmf(picture, simplified, unusable, rejectedAspect);
                if (unusable[0]) {
                    metadata.set(EMFParser.RENDER_UNUSABLE, true);
                }
                if (rejectedAspect[0] > 0) {
                    metadata.set(EMFParser.RENDER_ASPECT_RECOVERED, rejectedAspect[0]);
                }
                if (simplified[0] > 0) {
                    metadata.set(EMFParser.RENDER_SIMPLIFIED, simplified[0]);
                }
                EMFParser.tryMetafileOcr(raster, xhtml, metadata, context);
                if (hashEnabled) {
                    ImageHashUtils.setHashes(raster, metadata);
                }
            }

        } catch (RecordFormatException e) { //POI's hwmfparser can \ throw these for "parse
            // exceptions"
            throw new TikaException(e.getMessage(), e);
        } catch (RuntimeException e) { //convert Runtime to RecordFormatExceptions
            throw new TikaException(e.getMessage(), e);
        } catch (AssertionError e) { //POI's hwmfparser can throw these for parse exceptions
            throw new TikaException(e.getMessage(), e);
        } finally {
            tis.removeCloseShield();
        }
        xhtml.endDocument();
    }

    public boolean isImageHashingEnabled() {
        return imageHashingEnabled;
    }

    public void setImageHashingEnabled(boolean imageHashingEnabled) {
        this.imageHashingEnabled = imageHashingEnabled;
    }

    private boolean isImageHashingEnabled(ParseContext context) {
        OfficeParserConfig config = context.get(OfficeParserConfig.class);
        return imageHashingEnabled || (config != null && config.isImageHashingEnabled());
    }

    /**
     * Rasterize a WMF to a BufferedImage at up to OCR_RASTER_MAX_PX on the longest side.
     * Returns null if the size is invalid or rendering fails.
     */
    /** Bounds, or null when POI cannot supply them -- the fallback must not itself throw. */
    private static java.awt.geom.Rectangle2D boundsOrNull(HwmfPicture wmf) {
        try {
            return wmf.getBounds();
        } catch (Exception e) {
            return null;
        }
    }

    private static BufferedImage rasterizeWmf(HwmfPicture wmf, int[] simplified,
                                              boolean[] unusable, double[] rejectedAspect) {
        try {
            Dimension2D size = wmf.getSize();
            // Same rule as the EMF path, in the same helper: a declared aspect ratio is chosen by
            // the document and nothing verifies it.
            MetafileCanvas.Canvas canvas = MetafileCanvas.choose(size.getWidth(), size.getHeight(),
                    boundsOrNull(wmf));
            if (canvas == null) {
                return null;
            }
            if (canvas.source == MetafileCanvas.Source.UNUSABLE) {
                unusable[0] = true;
                return null;
            }
            if (canvas.source == MetafileCanvas.Source.BOUNDS_FALLBACK) {
                rejectedAspect[0] = canvas.declaredAspect;
            }
            int w = canvas.width, h = canvas.height;
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            // Same render bound as the EMF path -- WMF replays through Java2D identically, so it
            // carries the identical unbounded dashed-stroke cost.
            BoundedRenderGraphics2D bounded = new BoundedRenderGraphics2D(g);
            wmf.draw(bounded, new Rectangle2D.Double(0, 0, w, h));
            simplified[0] = bounded.substitutionCount();
            g.dispose();
            return img;
        } catch (Exception e) {
            return null;
        }
    }
}
