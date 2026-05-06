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
package org.apache.tika.sax;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.config.TikaComponent;
import org.apache.tika.digest.DigestDef;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.HexCoDec;
import org.apache.tika.parser.ParseContext;

/**
 * Calculates message digests over the extracted SAX text stream and writes them to metadata
 * under {@code X-TIKA:digest:text:*}. The text is hashed as UTF-8 after parser extraction.
 */
@TikaComponent
public class TextDigestingContentHandlerDecoratorFactory
        implements ContentHandlerDecoratorFactory {

    private static final long serialVersionUID = -2127501369171786324L;

    private static final char[] BASE32_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private volatile List<DigestDef> digests = Arrays.asList(
            new DigestDef(DigestDef.Algorithm.MD5),
            new DigestDef(DigestDef.Algorithm.SHA1),
            new DigestDef(DigestDef.Algorithm.SHA256));

    public List<DigestDef> getDigests() {
        return digests;
    }

    public void setDigests(List<DigestDef> digests) {
        this.digests = digests == null ? Collections.emptyList() : digests;
    }

    @Override
    public ContentHandler decorate(ContentHandler contentHandler, Metadata metadata,
                                   ParseContext parseContext) {
        return new TextDigestingContentHandler(contentHandler, metadata, digests);
    }

    private static class TextDigestingContentHandler extends ContentHandlerDecorator {

        private final Metadata metadata;
        private final List<DigestDef> digestDefs;
        private final List<MessageDigest> messageDigests;
        private Character pendingHighSurrogate = null;
        private final byte[] encodeBuffer = new byte[4096];
        private int encodeBufferPos = 0;

        private TextDigestingContentHandler(ContentHandler handler, Metadata metadata,
                                            List<DigestDef> digestDefs) {
            super(handler);
            this.metadata = metadata;
            this.digestDefs = new ArrayList<>(digestDefs);
            this.messageDigests = new ArrayList<>(digestDefs.size());
            for (DigestDef digestDef : digestDefs) {
                this.messageDigests.add(newMessageDigest(digestDef));
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            updateText(ch, start, length);
            super.characters(ch, start, length);
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
            updateText(ch, start, length);
            super.ignorableWhitespace(ch, start, length);
        }

        @Override
        public void endDocument() throws SAXException {
            if (pendingHighSurrogate != null) {
                writeCodePoint(pendingHighSurrogate);
                pendingHighSurrogate = null;
            }
            flushBuffer();
            for (int i = 0; i < digestDefs.size(); i++) {
                DigestDef digestDef = digestDefs.get(i);
                metadata.set(metadataKey(digestDef),
                        encode(messageDigests.get(i).digest(), digestDef.getEncoding()));
            }
            super.endDocument();
        }

        private void updateText(char[] ch, int start, int length) {
            int offset = start;
            int end = start + length;
            if (pendingHighSurrogate != null) {
                if (offset < end && Character.isLowSurrogate(ch[offset])) {
                    writeCodePoint(Character.toCodePoint(pendingHighSurrogate, ch[offset]));
                    offset++;
                } else {
                    writeCodePoint(pendingHighSurrogate);
                }
                pendingHighSurrogate = null;
            }
            if (offset < end && Character.isHighSurrogate(ch[end - 1])) {
                pendingHighSurrogate = ch[end - 1];
                end--;
            }
            for (int i = offset; i < end; i++) {
                char c = ch[i];
                if (Character.isHighSurrogate(c)) {
                    if (i + 1 < end && Character.isLowSurrogate(ch[i + 1])) {
                        writeCodePoint(Character.toCodePoint(c, ch[i + 1]));
                        i++;
                    } else {
                        writeCodePoint(c);
                    }
                } else {
                    writeCodePoint(c);
                }
            }
        }

        private void writeCodePoint(int cp) {
            if (cp <= 0x7F) {
                writeByte((byte) cp);
            } else if (cp <= 0x7FF) {
                writeByte((byte) (0xC0 | (cp >> 6)));
                writeByte((byte) (0x80 | (cp & 0x3F)));
            } else if (cp <= 0xFFFF) {
                writeByte((byte) (0xE0 | (cp >> 12)));
                writeByte((byte) (0x80 | ((cp >> 6) & 0x3F)));
                writeByte((byte) (0x80 | (cp & 0x3F)));
            } else {
                writeByte((byte) (0xF0 | (cp >> 18)));
                writeByte((byte) (0x80 | ((cp >> 12) & 0x3F)));
                writeByte((byte) (0x80 | ((cp >> 6) & 0x3F)));
                writeByte((byte) (0x80 | (cp & 0x3F)));
            }
        }

        private void writeByte(byte b) {
            encodeBuffer[encodeBufferPos++] = b;
            if (encodeBufferPos == encodeBuffer.length) {
                flushBuffer();
            }
        }

        private void flushBuffer() {
            if (encodeBufferPos > 0) {
                for (MessageDigest messageDigest : messageDigests) {
                    messageDigest.update(encodeBuffer, 0, encodeBufferPos);
                }
                encodeBufferPos = 0;
            }
        }

        private static MessageDigest newMessageDigest(DigestDef digestDef) {
            try {
                return MessageDigest.getInstance(digestDef.getAlgorithm().getJavaName());
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalArgumentException(e);
            }
        }

        private static String metadataKey(DigestDef digestDef) {
            StringBuilder sb = new StringBuilder();
            sb.append(TikaCoreProperties.TIKA_META_PREFIX);
            sb.append("digest");
            sb.append(TikaCoreProperties.NAMESPACE_PREFIX_DELIMITER);
            sb.append("text");
            sb.append(TikaCoreProperties.NAMESPACE_PREFIX_DELIMITER);
            sb.append(digestDef.getAlgorithm().name());
            if (digestDef.getEncoding() != DigestDef.Encoding.HEX) {
                sb.append(TikaCoreProperties.NAMESPACE_PREFIX_DELIMITER);
                sb.append(digestDef.getEncoding().name());
            }
            return sb.toString();
        }

        private static String encode(byte[] bytes, DigestDef.Encoding encoding) {
            switch (encoding) {
                case BASE32:
                    return encodeBase32(bytes);
                case BASE64:
                    return Base64.getEncoder().encodeToString(bytes);
                case HEX:
                default:
                    return new String(HexCoDec.encode(bytes));
            }
        }

        private static String encodeBase32(byte[] bytes) {
            StringBuilder sb = new StringBuilder(((bytes.length + 4) / 5) * 8);
            int buffer = 0;
            int bitsLeft = 0;
            for (byte b : bytes) {
                buffer = (buffer << 8) | (b & 0xff);
                bitsLeft += 8;
                while (bitsLeft >= 5) {
                    sb.append(BASE32_ALPHABET[(buffer >> (bitsLeft - 5)) & 0x1f]);
                    bitsLeft -= 5;
                }
            }
            if (bitsLeft > 0) {
                sb.append(BASE32_ALPHABET[(buffer << (5 - bitsLeft)) & 0x1f]);
            }
            while (sb.length() % 8 != 0) {
                sb.append('=');
            }
            return sb.toString();
        }
    }
}
