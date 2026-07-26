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
package org.apache.tika.parser.microsoft.rtf;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.UnsynchronizedByteArrayInputStream;
import org.apache.commons.io.output.UnsynchronizedByteArrayOutputStream;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.Ole10Native;
import org.apache.poi.poifs.filesystem.Ole10NativeException;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.TikaMemoryLimitException;
import org.apache.tika.extractor.EmbeddedDocumentUtil;
import org.apache.tika.io.BoundedInputStream;
import org.apache.tika.io.EndianUtils;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.RTFMetadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.microsoft.OfficeParser.POIFSDocumentType;

/**
 * Many thanks to Simon Mourier for:
 * http://stackoverflow.com/questions/14779647/extract-embedded-image-object-in-rtf
 * and for granting permission to use his code in Tika.
 */
class RTFObjDataParser {

    private final static String WIN_ASCII = "WINDOWS-1252";
    private final int memoryLimitInKb;

    RTFObjDataParser(int memoryLimitInKb) {
        this.memoryLimitInKb = memoryLimitInKb;
    }

    /**
     * Parses the embedded object/pict string
     *
     * @param is actual bytes (already converted from the
     *              hex pair string stored in the embedded object data into actual bytes or read
     *              as raw binary bytes)
     * @return a SimpleRTFEmbObj or null
     * @throws IOException if there are any surprise surprises during parsing
     */

    private static boolean hasPOIFSHeader(InputStream is) throws IOException {
        return FileMagic.valueOf(is) == FileMagic.OLE2;
    }

    /**
     * @param bytes
     * @param metadata             incoming metadata
     * @param unknownFilenameCount
     * @return byte[] for contents of obj data
     * @throws IOException
     */
    protected byte[] parse(byte[] bytes, Metadata metadata, AtomicInteger unknownFilenameCount)
            throws IOException, TikaException {
        UnsynchronizedByteArrayInputStream is = UnsynchronizedByteArrayInputStream.builder().setByteArray(bytes).get();
        long version = readUInt(is);
        metadata.add(RTFMetadata.EMB_APP_VERSION, Long.toString(version));

        long formatId = readUInt(is);
        //2 is an embedded object. 1 is a link.
        if (formatId != 2L) {
            return null;
        }
        // Strip embedded null bytes: weaponized OLE1 class names can contain nulls
        // for AV bypass; Postgres JSONB rejects U+0000 in stored JSON outright.
        String className = readLengthPrefixedAnsiString(is).trim().replace("\u0000", "");
        String topicName = readLengthPrefixedAnsiString(is).trim().replace("\u0000", "");
        String itemName = readLengthPrefixedAnsiString(is).trim().replace("\u0000", "");

        if (className.length() > 0) {
            metadata.add(RTFMetadata.EMB_CLASS, className);
            checkClassNameObfuscation(className, metadata);
        }
        if (topicName.length() > 0) {
            metadata.add(RTFMetadata.EMB_TOPIC, topicName);
        }
        if (itemName.length() > 0) {
            metadata.add(RTFMetadata.EMB_ITEM, itemName);
        }

        long dataSz = readUInt(is);

        //readBytes tests for reading too many bytes
        byte[] embObjBytes = readBytes(is, dataSz);

        String classNorm = normalizeDigitSubs(className).toLowerCase(Locale.ROOT);
        if (classNorm.equals("package") || className.toLowerCase(Locale.ROOT).equals("package")) {
            return handlePackage(embObjBytes, metadata);
        } else if (classNorm.equals("pbrush") || className.toLowerCase(Locale.ROOT).equals("pbrush")) {
            //simple bitmap bytes
            return embObjBytes;
        } else {
            UnsynchronizedByteArrayInputStream embIs = UnsynchronizedByteArrayInputStream.builder().setByteArray(embObjBytes).get();
            boolean hasPoifs;
            try {
                hasPoifs = hasPOIFSHeader(embIs);
            } catch (IOException e) {
                EmbeddedDocumentUtil.recordEmbeddedStreamException(e, metadata);
                return embObjBytes;
            }
            if (hasPoifs) {
                byte[] poifsResult = null;
                try {
                    poifsResult = handleEmbeddedPOIFS(embIs, metadata, unknownFilenameCount);
                } catch (Exception e) {
                    EmbeddedDocumentUtil.recordEmbeddedStreamException(e, metadata);
                }
                // Scan the full compound document bytes for a link URL when the structured
                // parse didn't find one (or found nothing). OLE2Link objects may store the
                // target in \x01OlE or \x03LinkInfo rather than the CONTENTS stream.
                if (metadata.get(RTFMetadata.EMB_OLE2LINK_URL) == null) {
                    String url = extractWideCharUrl(embObjBytes);
                    if (url != null) {
                        metadata.set(RTFMetadata.EMB_OLE2LINK_URL, url);
                    }
                }
                return poifsResult;
            }
        }
        return embObjBytes;
    }

    //will throw IOException if not actually POIFS
    //can return null byte[]
    private byte[] handleEmbeddedPOIFS(InputStream is, Metadata metadata,
                                       AtomicInteger unknownFilenameCount)
            throws TikaException, IOException {

        byte[] ret = null;
        try (POIFSFileSystem fs = new POIFSFileSystem(is)) {

            DirectoryNode root = fs.getRoot();

            if (root == null) {
                return ret;
            }

            // Extract the OLE2 root storage CLSID — identifies the embedding application and,
            // combined with emb_class, can flag known exploit CLSIDs (e.g. StdOleLink, Equation.3).
            org.apache.poi.hpsf.ClassID clsid = root.getStorageClsid();
            if (clsid != null) {
                String clsidStr = clsid.toString();
                if (clsidStr != null && !clsidStr.isEmpty()
                        && !clsidStr.equals("{00000000-0000-0000-0000-000000000000}")) {
                    metadata.set(RTFMetadata.EMB_CLSID, clsidStr);
                    String clsidName = org.apache.tika.parser.microsoft.OleClsidNames.lookup(clsidStr);
                    if (clsidName != null) {
                        metadata.set(RTFMetadata.EMB_CLSID_NAME, clsidName);
                    }
                }
            }

            if (root.hasEntry("Package")) {
                Entry ooxml = root.getEntry("Package");
                UnsynchronizedByteArrayOutputStream out = UnsynchronizedByteArrayOutputStream.builder().get();
                try (BoundedInputStream bis = new BoundedInputStream(memoryLimitInKb * 1024,
                        new DocumentInputStream((DocumentEntry) ooxml))) {
                    IOUtils.copy(bis, out);
                    if (bis.hasHitBound()) {
                        throw new TikaMemoryLimitException((memoryLimitInKb * 1024 + 1),
                                (memoryLimitInKb * 1024));
                    }
                }
                ret = out.toByteArray();
            } else {
                //try poifs
                POIFSDocumentType type = POIFSDocumentType.detectType(root);
                if (type == POIFSDocumentType.OLE10_NATIVE) {
                    try {
                        // Try to un-wrap the OLE10Native record:
                        Ole10Native ole = Ole10Native.createFromEmbeddedOleObject(root);
                        ret = ole.getDataBuffer();
                        // If we got here, POI found the stream with canonical casing.
                        // Surface label and file path from the Ole10Native header.
                        // label = display name shown to the user (e.g. "invoice.scr").
                        // fileName = original source path (attacker's build path).
                        // command = execution command (may differ from fileName in Package embeds).
                        String label = ole.getLabel();
                        if (label != null && !label.isEmpty()) {
                            metadata.set(RTFMetadata.EMB_LABEL, label);
                            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY,
                                    FilenameUtils.getName(label));
                            metadata.set(TikaCoreProperties.ORIGINAL_RESOURCE_NAME, label);
                        }
                        String fileName = ole.getFileName();
                        if (fileName != null && !fileName.isEmpty()) {
                            metadata.set(RTFMetadata.EMB_SOURCE_PATH, fileName);
                            metadata.set(TikaCoreProperties.EMBEDDED_RELATIONSHIP_ID, fileName);
                        }
                        String command = ole.getCommand();
                        if (command != null && !command.isEmpty()
                                && !command.equals(fileName)) {
                            metadata.set(RTFMetadata.EMB_COMMAND, command);
                        }
                    } catch (Ole10NativeException ex) {
                        // POI's lookup is case-sensitive; malware uses mixed-case stream
                        // names like "\x01oLE10nAtiVe" to evade it. Fall back to a
                        // case-insensitive scan and read the raw stream bytes.
                        ret = readOle10NativeCaseInsensitive(root, metadata);
                    }
                } else if (type == POIFSDocumentType.COMP_OBJ) {

                    try {
                        DocumentEntry contentsEntry;
                        try {
                            contentsEntry = (DocumentEntry) root.getEntry("CONTENTS");
                        } catch (FileNotFoundException ioe) {
                            contentsEntry = (DocumentEntry) root.getEntry("Contents");
                        }
                        try (DocumentInputStream inp = new DocumentInputStream(contentsEntry)) {
                            ret = new byte[contentsEntry.getSize()];
                            inp.readFully(ret);
                        }
                        // Scan the CONTENTS stream for an embedded URL moniker (UTF-16LE or ANSI).
                        String url = extractWideCharUrl(ret);
                        if (url != null) {
                            metadata.set(RTFMetadata.EMB_OLE2LINK_URL, url);
                        }
                    } catch (FileNotFoundException ignore) {
                        // No CONTENTS stream — link target may be in \x01OlE or \x03LinkInfo.
                        // The caller (parse()) will scan the full compound document bytes.
                    }
                } else {

                    UnsynchronizedByteArrayOutputStream out = UnsynchronizedByteArrayOutputStream.builder().get();
                    is.reset();
                    BoundedInputStream bis = new BoundedInputStream(memoryLimitInKb * 1024, is);
                    IOUtils.copy(bis, out);  // fix: read from bis, not is, to enforce memory limit
                    if (bis.hasHitBound()) {
                        throw new TikaMemoryLimitException(memoryLimitInKb * 1024 + 1,
                                memoryLimitInKb * 1024);
                    }
                    ret = out.toByteArray();
                    EmbeddedDocumentUtil.setGeneratedResourceName(metadata,
                            EmbeddedDocumentUtil.EmbeddedResourcePrefix.EMBEDDED,
                            unknownFilenameCount.getAndIncrement(),
                            type.getType().toString());
                    metadata.set(Metadata.CONTENT_TYPE, type.getType().toString());
                }
            }
        }
        return ret;
    }

    /**
     * can return null if there is a linked object
     * instead of an embedded file
     */
    private byte[] handlePackage(byte[] pkgBytes, Metadata metadata)
            throws IOException, TikaException {
        //now parse the package header
        UnsynchronizedByteArrayInputStream is = UnsynchronizedByteArrayInputStream.builder().setByteArray(pkgBytes).get();
        readUShort(is);

        String displayName = readAnsiString(is);

        //should we add this to the metadata?
        readAnsiString(is); //iconFilePath
        try {
            //iconIndex
            EndianUtils.readUShortBE(is);
        } catch (EndianUtils.BufferUnderrunException e) {
            throw new IOException(e);
        }
        int type = readUShort(is); //type

        //1 is link, 3 is embedded object
        //this only handles embedded objects
        if (type != 3) {
            return null;
        }
        //should we really be ignoring this filePathLen?
        readUInt(is); //filePathLen

        String ansiFilePath = readAnsiString(is); //filePath
        long bytesLen = readUInt(is);
        // MS Office's OLE Package handler does a lenient read (available bytes wins over
        // declared length) — malware deliberately sets bytesLen > actual stream to trigger
        // strict-parser failures while Word still drops and executes the payload.
        // We emulate Office: read as many bytes as available up to bytesLen.
        byte[] objBytes;
        if (bytesLen > is.available()) {
            int avail = is.available();
            objBytes = new byte[avail];
            IOUtils.readFully(is, objBytes);
        } else {
            objBytes = initByteArray(bytesLen);
            IOUtils.readFully(is, objBytes);
        }
        StringBuilder unicodeFilePath = new StringBuilder();

        try {
            long unicodeLen = readUInt(is);
            if (unicodeLen > memoryLimitInKb * 512L) {
                // attacker-controlled value would cause OOM; skip the unicode path
                unicodeLen = 0;
            }

            for (int i = 0; i < unicodeLen; i++) {
                int lo = is.read();
                int hi = is.read();
                int sum = lo + 256 * hi;
                if (hi == -1 || lo == -1) {
                    //stream ran out; empty SB and stop
                    unicodeFilePath.setLength(0);
                    break;
                }
                unicodeFilePath.append((char) sum);
            }
        } catch (IOException e) {
            //swallow; the unicode file path is optional and might not happen
            unicodeFilePath.setLength(0);
        }
        String fileNameToUse = "";
        String pathToUse = "";
        if (unicodeFilePath.length() > 0) {
            String p = unicodeFilePath.toString();
            fileNameToUse = p;
            pathToUse = p;
        } else {
            fileNameToUse = displayName == null ? "" : displayName;
            pathToUse = ansiFilePath == null ? "" : ansiFilePath;
        }
        metadata.set(TikaCoreProperties.ORIGINAL_RESOURCE_NAME, fileNameToUse);
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, FilenameUtils.getName(fileNameToUse));
        metadata.set(TikaCoreProperties.EMBEDDED_RELATIONSHIP_ID, pathToUse);
        // Surface the source path in the rtf_meta namespace so it passes through extractMetadata()
        // filtering (which drops X-TIKA: keys).  Useful for detecting executables dropped to temp paths.
        if (!pathToUse.isEmpty()) {
            metadata.set(RTFMetadata.EMB_SOURCE_PATH, pathToUse);
        }

        return objBytes;
    }

    private int readUShort(InputStream is) throws IOException {
        try {
            return EndianUtils.readUShortLE(is);
        } catch (EndianUtils.BufferUnderrunException e) {
            throw new IOException(e);
        }
    }

    private long readUInt(InputStream is) throws IOException {
        try {
            return EndianUtils.readUIntLE(is);
        } catch (EndianUtils.BufferUnderrunException e) {
            throw new IOException(e);
        }
    }

    private String readAnsiString(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c = is.read();
        while (c > 0) {
            sb.append((char) c);
            c = is.read();
        }
        if (c == -1) {
            throw new IOException("Hit end of stream before end of AnsiString");
        }
        return sb.toString();
    }

    // never returns null
    private String readLengthPrefixedAnsiString(InputStream is) throws IOException, TikaException {
        long len = readUInt(is);
        byte[] bytes = readBytes(is, len);
        try {
            return new String(bytes, WIN_ASCII);
        } catch (UnsupportedEncodingException e) {
            //shouldn't ever happen
            throw new IOException("Unsupported encoding");
        }
    }

    // never returns null
    private byte[] readBytes(InputStream is, long len) throws IOException, TikaException {
        //initByteArray tests for "reading of too many bytes"
        byte[] bytes = initByteArray(len);
        IOUtils.readFully(is, bytes);
        return bytes;
    }

    // never returns null
    private byte[] initByteArray(long len) throws IOException, TikaException {
        if (len < 0) {
            throw new IOException("Requested length for reading bytes < 0?!: " + len);
        } else if (memoryLimitInKb > -1 && len > memoryLimitInKb * 1024) {
            throw new TikaMemoryLimitException(len, memoryLimitInKb * 1024);
        } else if (len > Integer.MAX_VALUE) {
            throw new TikaMemoryLimitException(len, Integer.MAX_VALUE);
        }

        return new byte[(int) len];

    }

    /**
     * Scan {@code data} for a URL/UNC path stored as either UTF-16LE (wide) or ANSI.
     * Tries wide-char first (URL monikers), then ANSI (file monikers with UNC paths).
     * Returns the first match found, or null.
     *
     * <p>OLE2Link / StdOleLink CONTENTS streams store the link target in a moniker:
     * URL monikers use UTF-16LE; file monikers store ANSI paths (including UNC \\server\share).
     * Both are checked so template-injection attacks via either format are surfaced.
     *
     * <p>Port of the pattern-scan in rt-eff-u-extract's OLE2LinkParser (BSD licence).
     */
    private static String extractWideCharUrl(byte[] data) {
        String wideResult = extractWideCharUrlInternal(data);
        if (wideResult != null) {
            return wideResult;
        }
        return extractAnsiUrl(data);
    }

    /**
     * Scan for ANSI-encoded URLs and UNC paths.
     * File monikers in OLE2Link CONTENTS streams store the target path as a null-terminated
     * ANSI (Windows-1252) string. This covers UNC template injection (\\server\share\file.docx).
     */
    private static String extractAnsiUrl(byte[] data) {
        // Lowercase a copy for case-insensitive matching.
        byte[] lower = data.clone();
        for (int i = 0; i < lower.length; i++) {
            if (lower[i] >= 'A' && lower[i] <= 'Z') {
                lower[i] = (byte) (lower[i] + 32);
            }
        }
        // URL/path patterns (all lowercased for case-insensitive match).
        // Includes Windows env-var paths like %TMP%\file.sct used in OLE drop attacks.
        String[] ansiPatterns = {
            "http://", "https://", "ftp://", "file://", "mhtml:", "\\\\",
            "%tmp%\\", "%temp%\\", "%appdata%\\", "%localappdata%\\",
            "%systemroot%\\", "%windir%\\", "%userprofile%\\",
        };
        int best = -1;
        for (String p : ansiPatterns) {
            byte[] pb = p.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            int idx = indexOf(lower, pb);
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx;
            }
        }
        if (best < 0) {
            return null;
        }
        // Read until null terminator or non-printable byte.
        int end = best;
        while (end < data.length) {
            int b = data[end] & 0xFF;
            if (b == 0 || b < 0x20) {
                break;
            }
            end++;
        }
        if (end <= best) {
            return null;
        }
        String url = new String(data, best, end - best,
                java.nio.charset.StandardCharsets.ISO_8859_1).trim();
        return url.isEmpty() ? null : url;
    }

    private static String extractWideCharUrlInternal(byte[] data) {
        // Build a lowercased copy for case-insensitive matching (HttP://, HTTP:// etc).
        // We lowercase every byte in the array to handle wide-character strings
        // regardless of odd/even byte alignment (e.g. if the string starts at an odd offset).
        byte[] lower = data.clone();
        for (int i = 0; i < lower.length; i++) {
            if (lower[i] >= 'A' && lower[i] <= 'Z') {
                lower[i] = (byte) (lower[i] + 32);
            }
        }
        // Wide-char (UTF-16LE) byte patterns for common URL schemes (all lowercase).
        byte[][] patterns = {
            new byte[]{'h',0,'t',0,'t',0,'p',0,':',0,'/',0,'/',0},      // http://
            new byte[]{'h',0,'t',0,'t',0,'p',0,'s',0,':',0,'/',0,'/',0}, // https://
            new byte[]{'f',0,'t',0,'p',0,':',0,'/',0,'/',0},             // ftp://
            new byte[]{'f',0,'i',0,'l',0,'e',0,':',0,'/',0,'/',0},       // file://
            new byte[]{'m',0,'h',0,'t',0,'m',0,'l',0,':',0},             // mhtml:
            new byte[]{'\\',0,'\\',0},                                    // \\UNC
        };
        int best = -1;
        for (byte[] pattern : patterns) {
            int idx = indexOf(lower, pattern);
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx;
            }
        }
        if (best < 0) {
            return null;
        }
        // Read UTF-16LE chars until double-null terminator or end of data.
        int end = best;
        while (end + 1 < data.length) {
            if (data[end] == 0 && data[end + 1] == 0) {
                break;
            }
            end += 2;
        }
        if (end <= best) {
            return null;
        }
        try {
            String url = new String(data, best, end - best, "UTF-16LE").trim();
            return url.isEmpty() ? null : url;
        } catch (java.io.UnsupportedEncodingException e) {
            return null;
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * Case-insensitive fallback for reading an Ole10Native stream when POI's
     * exact-match lookup fails.  Malware authors obfuscate the stream name
     * (e.g. {@code \x01oLE10nAtiVe}) to evade tools that match exactly.
     *
     * <p>Returns the raw stream bytes (skipping the 4-byte length prefix),
     * or null if no Ole10Native-like stream is found.
     */
    private byte[] readOle10NativeCaseInsensitive(DirectoryNode root, Metadata metadata)
            throws TikaException, IOException {
        for (Entry e : root) {
            if (!(e instanceof DocumentEntry)) {
                continue;
            }
            // Strip any leading system control character (e.g. \x01) before comparing
            // to align with the robust stream-name recovery logic in AbstractOOXMLExtractor.
            String entryName = e.getName();
            String suffix = (entryName.length() > 1 && entryName.charAt(0) < 0x20)
                    ? entryName.substring(1) : entryName;
            if (!suffix.equalsIgnoreCase("Ole10Native")) {
                continue;
            }
            // Non-canonical casing or prefix manipulation — flag obfuscation.
            if (!entryName.equals("\u0001Ole10Native")) {
                metadata.set(RTFMetadata.EMB_CLASS_OBFUSCATED, true);
            }
            DocumentEntry de = (DocumentEntry) e;
            byte[] raw = new byte[de.getSize()];
            try (DocumentInputStream dis = new DocumentInputStream(de)) {
                int read = 0;
                while (read < raw.length) {
                    int n = dis.read(raw, read, raw.length - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
            }
            // Ole10Native stream: 4-byte LE length prefix, then OLE1 Package data.
            if (raw.length > 4) {
                int dataLen = (raw[0] & 0xFF)
                        | ((raw[1] & 0xFF) << 8)
                        | ((raw[2] & 0xFF) << 16)
                        | ((raw[3] & 0xFF) << 24);
                if (dataLen > 0 && dataLen <= raw.length - 4) {
                    byte[] payload = new byte[dataLen];
                    System.arraycopy(raw, 4, payload, 0, dataLen);
                    return payload;
                }
            }
            return raw;
        }
        return null;
    }

    /**
     * Flag when a known OLE class name is present with non-canonical casing or digit substitution.
     * Canonical forms (exactly as the OLE spec / MS Office writes them):
     *   OLE2Link, OLE10Native, Equation.3, Equation.2, Package, pbrush
     * Attackers write e.g. OLE2LInk (mixed case) or 0leNatIve (0 for O) to confuse
     * strict parsers while MS Word still dispatches by CLSID.
     */
    private static void checkClassNameObfuscation(String className, Metadata metadata) {
        // pbrush/PBrush are both used legitimately; exclude to avoid false positives.
        String[] canonical = {"OLE2Link", "OLE10Native", "Equation.3", "Equation.2", "Package",
                              "StdOleLink", "Word.Document.8", "Excel.Sheet.8", "PowerPoint.Show.8"};
        String lower = className.toLowerCase(Locale.ROOT);
        // Normalize common digit-for-letter substitutions (0→o, 1→l, 3→e, 4→a, 5→s, @→a)
        // for a second-pass comparison that catches "0leNatIve", "3quation" etc.
        String norm = normalizeDigitSubs(lower);
        for (String c : canonical) {
            String cLower = c.toLowerCase(Locale.ROOT);
            if (lower.equals(cLower) || norm.equals(cLower)) {
                if (!className.equals(c)) {
                    metadata.set(RTFMetadata.EMB_CLASS_OBFUSCATED, true);
                    return;
                }
            }
        }
    }

    /**
     * Replace common digit-for-letter substitutions used in class name obfuscation.
     * Applied to a lowercased string: 0→o, 1→l, 3→e, 4→a, 5→s, @→a.
     */
    private static String normalizeDigitSubs(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '0') {
                sb.append('o');
            } else if (c == '1') {
                sb.append('l');
            } else if (c == '3') {
                sb.append('e');
            } else if (c == '4') {
                sb.append('a');
            } else if (c == '5') {
                sb.append('s');
            } else if (c == '@') {
                sb.append('a');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
