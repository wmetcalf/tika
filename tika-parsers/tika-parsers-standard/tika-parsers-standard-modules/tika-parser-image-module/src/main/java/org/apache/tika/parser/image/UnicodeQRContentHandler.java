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

import java.util.List;

import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ContentHandlerDecorator;

/**
 * Content-handler decorator that accumulates all {@code characters()} SAX
 * events and, on {@link #endDocument()}, scans the accumulated buffer for
 * Unicode block-element QR codes embedded in plaintext. Decoded URLs/text
 * are added to the wrapped {@link Metadata} as multi-valued
 * {@code unicode_qr:decoded}, with the cluster size as
 * {@code unicode_qr:glyph_count}.
 *
 * <p>Plug into any parser that emits its body through XHTMLContentHandler by
 * wrapping the user-provided handler at the start of {@code parse()}:</p>
 *
 * <pre>{@code
 * ContentHandler wrapped = new UnicodeQRContentHandler(
 *         handler, metadata, scanner, config, context);
 * XHTMLContentHandler xhtml = new XHTMLContentHandler(wrapped, metadata);
 * }</pre>
 *
 * <p>Memory is capped so adversarial inputs (e.g. a 100 MB extracted text
 * stream with no QR content) don't consume unbounded heap.</p>
 */
public class UnicodeQRContentHandler extends ContentHandlerDecorator {

    /** Hard cap on buffered text. A QR code in a meeting body is at most a
     *  few KB; a phishing kit emitting tens of MB of decorative ASCII can be
     *  truncated without losing the signal. */
    private static final int MAX_BUFFER_CHARS = 2 * 1024 * 1024;

    /** Minimum total QR-glyph count required before we even attempt the
     *  cluster-find + render pass. Cheap pre-filter to avoid invoking the
     *  expensive bitmap render on bodies that obviously contain no code. */
    private static final int PROBE_MIN_GLYPHS = 100;

    private final Metadata metadata;
    private final ZXingCPPScanner scanner;
    private final ZXingCPPConfig config;
    private final ParseContext context;
    private final StringBuilder buffer = new StringBuilder();
    private boolean truncated;
    private int qrGlyphCount;

    /** Wrap an existing handler; the QR scan runs on endDocument. */
    public UnicodeQRContentHandler(ContentHandler delegate, Metadata metadata,
                                   ZXingCPPScanner scanner, ZXingCPPConfig config,
                                   ParseContext context) {
        super(delegate == null ? new DefaultHandler() : delegate);
        this.metadata = metadata;
        this.scanner = scanner;
        this.config = config;
        this.context = context;
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        super.characters(ch, start, length);
        if (length > 0 && buffer.length() >= MAX_BUFFER_CHARS) {
            truncated = true;
            return;
        }
        if (buffer.length() < MAX_BUFFER_CHARS) {
            int room = MAX_BUFFER_CHARS - buffer.length();
            int take = Math.min(length, room);
            buffer.append(ch, start, take);
            for (int i = 0; i < take; i++) {
                if (UnicodeQRExtractor.isQrGlyph(ch[start + i])) {
                    qrGlyphCount++;
                }
            }
            if (take < length) {
                truncated = true;
            }
        }
    }

    @Override
    public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
        super.ignorableWhitespace(ch, start, length);
        // Don't accumulate whitespace into buffer — keeps memory bounded.
    }

    @Override
    public void endDocument() throws SAXException {
        try {
            if (truncated) {
                metadata.add("unicode_qr:warning",
                        "Text exceeded " + MAX_BUFFER_CHARS
                                + " chars — QR scan ran on the prefix only");
                BarcodeMetadataUtil.markAnalysisIncomplete(
                        metadata, "Unicode QR analysis limit", null);
            }
            if (qrGlyphCount >= PROBE_MIN_GLYPHS) {
                metadata.set("unicode_qr:glyph_count", Integer.toString(qrGlyphCount));
                if (scanner != null && scanner.hasZXingCPP(config)) {
                    try {
                        List<ZXingCPPScanner.Result> decoded =
                                UnicodeQRExtractor.extractAndDecode(
                                        buffer.toString(), scanner, config, context);
                        ColorGridQRDecoder.emitBarcodes(decoded, metadata);
                        if (!decoded.isEmpty()) {
                            metadata.set("ExploitClass",
                                    "Decoded " + decoded.size()
                                  + " Unicode-block-art QR code(s) from extracted text "
                                  + "— invisible-to-image-scanner phishing payload");
                        }
                    } catch (RuntimeException e) {
                        BarcodeMetadataUtil.markAnalysisIncomplete(
                                metadata, "Unicode QR analysis", e);
                    }
                }
            }
        } finally {
            super.endDocument();
        }
    }
}
