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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;

import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.config.TikaComponent;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.XHTMLContentHandler;

/**
 * Minimal parser for Windows Shell Link (LNK) files — MS-SHLLINK.
 *
 * Extracts the target LocalPath, Name, RelativePath, WorkingDir, and CommandLine
 * arguments from the StringData section and emits them as plain text.  This
 * surfaces cmd.exe/PowerShell command-line arguments embedded in LNK droppers.
 */
@TikaComponent
public class WinShortcutParser implements Parser {

    private static final long serialVersionUID = 1L;

    private static final MediaType LNK_TYPE =
            MediaType.application("x-ms-shortcut");
    private static final Set<MediaType> SUPPORTED_TYPES =
            Collections.singleton(LNK_TYPE);

    // MS-SHLLINK §2.1 — ShellLinkHeader flag bits
    private static final int HAS_LINK_TARGET_IDLIST  = 0x0001;
    private static final int HAS_LINK_INFO           = 0x0002;
    private static final int HAS_NAME                = 0x0004;
    private static final int HAS_RELATIVE_PATH       = 0x0008;
    private static final int HAS_WORKING_DIR         = 0x0010;
    private static final int HAS_ARGUMENTS           = 0x0020;
    private static final int HAS_ICON_LOCATION       = 0x0040;
    private static final int IS_UNICODE              = 0x0080;

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED_TYPES;
    }

    @Override
    public void parse(TikaInputStream stream, ContentHandler handler, Metadata metadata,
                      ParseContext context) throws IOException, SAXException, TikaException {
        byte[] data = stream.readAllBytes();
        if (data.length < 76) {
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        // Verify magic: 0x4C000000
        if (buf.getInt(0) != 0x0000004C) {
            return;
        }

        int linkFlags = buf.getInt(20);
        boolean unicode = (linkFlags & IS_UNICODE) != 0;

        // Skip past the fixed-size header (76 bytes)
        int pos = 76;

        // Skip IDList if present
        if ((linkFlags & HAS_LINK_TARGET_IDLIST) != 0 && pos + 2 <= data.length) {
            int idListSize = Short.toUnsignedInt(buf.getShort(pos));
            pos += 2 + idListSize;
        }

        // Skip LinkInfo if present
        if ((linkFlags & HAS_LINK_INFO) != 0 && pos + 4 <= data.length) {
            int linkInfoSize = buf.getInt(pos);
            if (linkInfoSize > 0) {
                pos += linkInfoSize;
            }
        }

        // Parse StringData — ordered: Name, RelativePath, WorkingDir, Arguments, IconLocation
        int[] flags = {HAS_NAME, HAS_RELATIVE_PATH, HAS_WORKING_DIR, HAS_ARGUMENTS, HAS_ICON_LOCATION};
        String[] labels = {"Name", "RelativePath", "WorkingDir", "Arguments", "IconLocation"};

        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata, context);
        xhtml.startDocument();

        for (int fi = 0; fi < flags.length; fi++) {
            if ((linkFlags & flags[fi]) == 0) {
                continue;
            }
            if (pos + 2 > data.length) {
                break;
            }
            int countChars = Short.toUnsignedInt(buf.getShort(pos));
            pos += 2;
            String value;
            if (unicode) {
                int byteLen = countChars * 2;
                if (pos + byteLen > data.length) {
                    break;
                }
                value = new String(data, pos, byteLen, StandardCharsets.UTF_16LE);
                pos += byteLen;
            } else {
                if (pos + countChars > data.length) {
                    break;
                }
                value = new String(data, pos, countChars, StandardCharsets.US_ASCII);
                pos += countChars;
            }
            if (!value.isEmpty()) {
                String line = labels[fi] + ": " + value;
                xhtml.element("p", line);
            }
        }

        xhtml.endDocument();
    }
}
