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
package org.apache.tika.parser.strings;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.HashSet;
import java.util.Set;

import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.annotation.TikaComponent;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.XHTMLContentHandler;

/**
 * Parser to extract printable Latin1 strings from arbitrary files with pure java
 * without running any external process. Useful for binary or unknown files, for
 * files without a specific parser and for corrupted ones causing a TikaException
 * as a fallback parser. To enable the parsing of unknown or files without a
 * specific parser with AutoDetectParser:
 * <p>
 * AutoDetectParser parser = new AutoDetectParser();
 * parser.setFallback(new Latin1StringsParser());
 * </p>
 * Currently the parser does a best effort to extract Latin1 strings, used by
 * Western European languages, encoded with ISO-8859-1, UTF-8 or UTF-16 charsets
 * mixed within the same file.
 * <p>
 * The implementation is optimized for fast parsing with only one pass.
 */
@TikaComponent(spi = false)
public class Latin1StringsParser implements Parser {

    private static final long serialVersionUID = 1L;

    /**
     * The set of supported types
     */
    private static final Set<MediaType> SUPPORTED_TYPES = getTypes();

    /**
     * The valid ISO-8859-1 character map.
     */
    private static final boolean[] isChar = getCharMap();

    /**
     * The size of the internal buffers.
     */
    private static int BUF_SIZE = 64 * 1024;

    /**
     * Largest accepted {@code minSize}. The decode buffers are sized at twice the floor, so this
     * is the point past which a configured value stops being a decoding preference and becomes an
     * allocation request. 16 MB is orders of magnitude above any meaningful minimum string length
     * and keeps 2 * minSize clear of integer overflow.
     */
    private static final int MAX_MIN_SIZE = 16 * 1024 * 1024;

    /**
     * The minimum size of a character sequence to be extracted.
     */
    private int minSize = 4;

    /**
     * The output buffer.
     */
    /**
     * Size of {@link #input} and {@link #output}, at least {@link #BUF_SIZE}.
     *
     * <p>Grown by {@link #setMinSize} so that the buffer can always hold the retained floor plus
     * as much again. {@code flushBuffer()} emits {@code bufSize - minSize} bytes and retains
     * {@code minSize}, so keeping {@code bufSize >= 2 * minSize} keeps the copied-to-emitted
     * ratio at or below 1 -- linear in the input -- for ANY configured floor.
     */
    private transient int bufSize;

    private transient byte[] output;

    /**
     * The input buffer.
     */
    private transient byte[] input;

    /**
     * The temporary position into the output buffer.
     */
    private int tmpPos = 0;

    /**
     * The current position into the output buffer.
     */
    private int outPos = 0;

    /**
     * The number of bytes into the input buffer.
     */
    private int inSize = 0;

    /**
     * The position into the input buffer.
     */
    private int inPos = 0;

    /**
     * The output content handler.
     */
    private XHTMLContentHandler xhtml;

    /**
     * Populates the valid ISO-8859-1 character map.
     *
     * @return the valid ISO-8859-1 character map.
     */
    private static boolean[] getCharMap() {

        boolean[] isChar = new boolean[256];
        for (int c = Byte.MIN_VALUE; c <= Byte.MAX_VALUE; c++)
            if ((c >= 0x20 && c <= 0x7E) || (c >= (byte) 0xC0 && c <= (byte) 0xFE) || c == 0x0A ||
                    c == 0x0D || c == 0x09) {
                isChar[c & 0xFF] = true;
            }
        return isChar;

    }

    /**
     * Returns the set of supported types.
     *
     * @return the set of supported types
     */
    private static Set<MediaType> getTypes() {
        HashSet<MediaType> supportedTypes = new HashSet<>();
        supportedTypes.add(MediaType.OCTET_STREAM);
        return supportedTypes;
    }

    /**
     * Tests if the byte is a ISO-8859-1 char.
     *
     * @param c the byte to test.
     * @return if the byte is a char.
     */
    private static final boolean isChar(byte c) {
        return isChar[c & 0xFF];
    }

    /**
     * Returns the minimum size of a character sequence to be extracted.
     *
     * @return the minimum size of a character sequence
     */
    public int getMinSize() {
        return minSize;
    }

    /**
     * Sets the minimum size of a character sequence to be extracted.
     *
     * @param minSize the minimum size of a character sequence
     */
    public void setMinSize(int minSize) {
        // Validate, because this value is now actually USED. It previously reached nothing:
        // parse() built its delegate with a bare constructor and the default of 4, so every value
        // -- in range or not -- was silently discarded.
        //
        // flushBuffer() runs when tmpPos hits bufSize. It emits (bufSize - minSize) bytes and
        // retains minSize as the possible prefix of a longer run, so each flush consumes
        // (bufSize - minSize) new bytes at a cost of minSize copied. Work per byte consumed is
        // minSize / (bufSize - minSize), which at a FIXED 64 KB buffer degrades without bound as
        // the floor approaches it: 1 at 32 KB, ~65535 at 65535 (quadratic on
        // document-controlled input), and at 65536 the flush frees nothing at all -- outPos stays
        // 0, tmpPos stays at the buffer length, and the next printable byte runs
        // output[tmpPos++] off the end.
        //
        // Rejecting large floors would be the wrong fix twice over: 40000 decodes perfectly well
        // (it still emits 25536 bytes per flush), and a config that a deployment previously
        // started with -- ignored, but started -- would begin throwing at construction. So size
        // the buffer to the floor instead. bufSize >= 2 * minSize holds the ratio at or below 1
        // for every accepted value, which is the property that actually matters.
        //
        // The buffers are allocated in doParse(), NOT here. This setter runs on the long-lived
        // CONFIGURED parser -- DefaultParser holds one for the life of the JVM -- which never
        // decodes anything: parse() hands the work to a fresh delegate that allocates its own.
        // Allocating here would retain 2 * minSize twice over on an instance that never reads a
        // byte of it, on top of the same allocation in every concurrent parse, and the arrays
        // would ride along in the serialized form of a configured Parser.
        if (minSize < 1) {
            throw new IllegalArgumentException("minSize must be at least 1, got " + minSize);
        }
        if (minSize > MAX_MIN_SIZE) {
            throw new IllegalArgumentException(
                    "minSize must be at most " + MAX_MIN_SIZE + ", got " + minSize
                            + ". The decode buffers are sized at twice the floor, so a larger "
                            + "value asks for an unbounded allocation per parse.");
        }
        this.minSize = minSize;
    }

    /**
     * Flushes the internal output buffer to the content handler.
     *
     * @throws UnsupportedEncodingException
     * @throws SAXException
     */
    private void flushBuffer() throws UnsupportedEncodingException, SAXException {
        if (tmpPos - outPos >= minSize) {
            outPos = tmpPos - minSize;
        }

        xhtml.characters(new String(output, 0, outPos, "windows-1252"));

        if (tmpPos - outPos >= 0) {
            System.arraycopy(output, outPos, output, 0, tmpPos - outPos);
        }
        tmpPos = tmpPos - outPos;
        outPos = 0;
    }

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext arg0) {
        return SUPPORTED_TYPES;
    }

    /**
     * @see org.apache.tika.parser.Parser#parse(TikaInputStream,
     * org.xml.sax.ContentHandler, org.apache.tika.metadata.Metadata,
     * org.apache.tika.parser.ParseContext)
     */
    @Override
    public void parse(TikaInputStream tis, ContentHandler handler, Metadata metadata,
                      ParseContext context) throws IOException, SAXException {
        /*
         * Creates a new instance because the object is not immutable: doParse writes its decode
         * buffers, positions and content handler into instance fields, and a parser instance is
         * shared -- DefaultParser holds one and every thread parses through it. Cloning per parse
         * is what keeps that safe, so keep doing it.
         *
         * Carry the configuration across, though. Without this line the fresh instance always ran
         * with the DEFAULT minSize of 4, so setMinSize() was silently discarded: callers got
         * every 4-character run back however high they had set the floor, and nothing failed to
         * tell them.
         */
        Latin1StringsParser perDocument = new Latin1StringsParser();
        perDocument.setMinSize(getMinSize());
        perDocument.doParse(tis, handler, metadata, context);
    }

    /**
     * Does a best effort to extract Latin1 strings encoded with ISO-8859-1,
     * UTF-8 or UTF-16. Valid chars are saved into the output buffer and the
     * temporary buffer position is incremented. When an invalid char is read,
     * the difference of the temporary and current buffer position is checked.
     * If it is greater than the minimum string size, the current buffer
     * position is updated to the temp position. If it is not, the temp position
     * is reseted to the current position.
     *
     * @param stream   the input tis.
     * @param handler  the output content handler
     * @param metadata the metadata of the file
     * @param context  the parsing context
     * @throws IOException  if an io error occurs
     * @throws SAXException if a sax error occurs
     */
    private void doParse(InputStream tis, ContentHandler handler, Metadata metadata,
                         ParseContext context) throws IOException, SAXException {

        // Per-document buffers, sized from the floor so that flushBuffer() always emits at
        // least as much as it retains. Allocated here rather than in setMinSize because THIS is
        // the instance that decodes: parse() delegates to a fresh one per document.
        bufSize = Math.max(BUF_SIZE, 2 * minSize);
        input = new byte[bufSize];
        output = new byte[bufSize];
        tmpPos = 0;
        outPos = 0;

        xhtml = new XHTMLContentHandler(handler, metadata, context);
        xhtml.startDocument();

        int i = 0;
        do {
            inSize = 0;
            while ((i = tis.read(input, inSize, bufSize - inSize)) > 0) {
                inSize += i;
            }
            inPos = 0;
            while (inPos < inSize) {
                byte c = input[inPos++];
                boolean utf8 = false;
                /*
                 * Test for a possible UTF8 encoded char
                 */
                if (c == (byte) 0xC3) {
                    byte c_ = inPos < inSize ? input[inPos++] : (byte) tis.read();
                    /*
                     * Test if the next byte is in the valid UTF8 range
                     */
                    if (c_ >= (byte) 0x80 && c_ <= (byte) 0xBF) {
                        utf8 = true;
                        output[tmpPos++] = (byte) (c_ + 0x40);
                    } else {
                        output[tmpPos++] = c;
                        c = c_;
                    }
                    if (tmpPos == bufSize) {
                        flushBuffer();
                    }

                    /*
                     * Test for a possible UTF8 encoded char
                     */
                } else if (c == (byte) 0xC2) {
                    byte c_ = inPos < inSize ? input[inPos++] : (byte) tis.read();
                    /*
                     * Test if the next byte is in the valid UTF8 range
                     */
                    if (c_ >= (byte) 0xA0 && c_ <= (byte) 0xBF) {
                        utf8 = true;
                        output[tmpPos++] = c_;
                    } else {
                        output[tmpPos++] = c;
                        c = c_;
                    }
                    if (tmpPos == bufSize) {
                        flushBuffer();
                    }
                }
                if (!utf8)
                    /*
                     * Test if the byte is a valid char.
                     */ {
                    if (isChar(c)) {
                        output[tmpPos++] = c;
                        if (tmpPos == bufSize) {
                            flushBuffer();
                        }
                    } else {
                        /*
                         * Test if the byte is an invalid char, marking a string
                         * end. If it is a zero, test 2 positions before or
                         * ahead for a valid char, meaning it marks the
                         * transition between ISO-8859-1 and UTF16 sequences.
                         */
                        if (c != 0 || (inPos >= 3 && isChar(input[inPos - 3])) ||
                                (inPos + 1 < inSize && isChar(input[inPos + 1]))) {

                            if (tmpPos - outPos >= minSize) {
                                output[tmpPos++] = 0x0A;
                                outPos = tmpPos;

                                if (tmpPos == bufSize) {
                                    flushBuffer();
                                }
                            } else {
                                tmpPos = outPos;
                            }

                        }
                    }
                }
            }
        } while (i != -1 && !Thread.currentThread().isInterrupted());

        if (tmpPos - outPos >= minSize) {
            output[tmpPos++] = 0x0A;
            outPos = tmpPos;
        }
        xhtml.characters(new String(output, 0, outPos, "windows-1252"));

        xhtml.endDocument();

    }

}
