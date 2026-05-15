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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.config.TikaComponent;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.XHTMLContentHandler;

/**
 * Parser for Windows Shell Link (LNK) files — MS-SHLLINK specification.
 *
 * Extracts forensically significant fields: target paths, command-line arguments,
 * volume/machine identifiers from LinkInfo and ExtraData (TrackerDataBlock,
 * EnvironmentVariableDataBlock, DarwinDataBlock, ShimDataBlock).  Closely
 * follows the field layout documented in EricZimmerman/Lnk and Matmaus/LnkParse3.
 *
 * All string fields are emitted without length caps — truncation hides IOCs.
 */
@TikaComponent
public class WinShortcutParser implements Parser {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(WinShortcutParser.class);

    private static final MediaType LNK_TYPE = MediaType.application("x-ms-shortcut");
    private static final Set<MediaType> SUPPORTED_TYPES = Collections.singleton(LNK_TYPE);

    // ── MS-SHLLINK §2.1 ShellLinkHeader ──────────────────────────────────────

    private static final int LNK_MAGIC   = 0x0000004C;
    private static final int HEADER_SIZE = 76;

    // LinkFlags
    private static final int FLAG_HAS_TARGET_IDLIST  = 0x00000001;
    private static final int FLAG_HAS_LINK_INFO      = 0x00000002;
    private static final int FLAG_HAS_NAME           = 0x00000004;
    private static final int FLAG_HAS_RELATIVE_PATH  = 0x00000008;
    private static final int FLAG_HAS_WORKING_DIR    = 0x00000010;
    private static final int FLAG_HAS_ARGUMENTS      = 0x00000020;
    private static final int FLAG_HAS_ICON_LOCATION  = 0x00000040;
    private static final int FLAG_IS_UNICODE         = 0x00000080;

    // ExtraData block signatures
    private static final int SIG_ENVIRONMENT_VAR  = 0xA0000001;
    private static final int SIG_CONSOLE          = 0xA0000002;
    private static final int SIG_TRACKER          = 0xA0000003;
    private static final int SIG_CONSOLE_FE       = 0xA0000004;
    private static final int SIG_SPECIAL_FOLDER   = 0xA0000005;
    private static final int SIG_DARWIN           = 0xA0000006;
    private static final int SIG_ICON_ENVIRONMENT = 0xA0000007;
    private static final int SIG_SHIM             = 0xA0000008;
    private static final int SIG_PROPERTY_STORE   = 0xA0000009;
    private static final int SIG_KNOWN_FOLDER     = 0xA000000B;

    // Windows FILETIME epoch offset (100-ns ticks from 1601-01-01 to 1970-01-01)
    private static final long FILETIME_EPOCH_DIFF_NS100 = 116444736000000000L;

    // DriveType labels (§2.3.1)
    private static final String[] DRIVE_TYPES = {
        "Unknown", "NoRootDir", "Removable", "Fixed", "Remote", "CDROM", "RAMDisk"
    };

    // ShowCommand labels
    private static final Map<Integer, String> SHOW_COMMANDS = new LinkedHashMap<>();
    static {
        SHOW_COMMANDS.put(1, "Normal");
        SHOW_COMMANDS.put(3, "Maximized");
        SHOW_COMMANDS.put(7, "MinNoActive");
    }

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED_TYPES;
    }

    @Override
    public void parse(TikaInputStream stream, ContentHandler handler, Metadata metadata,
                      ParseContext context) throws IOException, SAXException, TikaException {
        byte[] raw = stream.readAllBytes();
        if (raw.length < HEADER_SIZE) {
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);

        if (buf.getInt(0) != LNK_MAGIC) {
            return;
        }

        metadata.set(Metadata.CONTENT_TYPE, LNK_TYPE.toString());

        Map<String, String> fields = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        try {
            int pos = parseHeader(buf, raw.length, fields, warnings);
            int linkFlags = buf.getInt(20);
            boolean unicode = (linkFlags & FLAG_IS_UNICODE) != 0;

            pos = skipIdList(buf, pos, linkFlags, raw.length);
            pos = parseLinkInfo(buf, pos, linkFlags, raw.length, fields, warnings);
            pos = parseStringData(buf, pos, linkFlags, unicode, raw.length, fields);
            parseExtraData(buf, pos, raw.length, fields, warnings);
        } catch (Exception e) {
            LOG.warn("Error parsing LNK file: {}", e.getMessage());
            warnings.add("parse-error: " + e.getMessage());
        }

        // Propagate target path to metadata
        String targetPath = fields.get("LocalBasePath");
        if (targetPath == null) {
            targetPath = fields.get("EnvironmentVariableTarget");
        }
        if (targetPath != null) {
            metadata.set(TikaCoreProperties.ORIGINAL_RESOURCE_NAME, targetPath);
        }

        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata, context);
        xhtml.startDocument();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            xhtml.element("p", e.getKey() + ": " + e.getValue());
        }
        for (String w : warnings) {
            xhtml.element("p", "Warning: " + w);
        }
        xhtml.endDocument();
    }

    // ── ShellLinkHeader §2.1 ─────────────────────────────────────────────────

    private int parseHeader(ByteBuffer buf, int fileLen,
                            Map<String, String> fields, List<String> warnings) {
        int linkFlags = buf.getInt(20);
        int fileAttributes = buf.getInt(24);
        long creationTime = buf.getLong(28);
        long accessTime   = buf.getLong(36);
        long writeTime    = buf.getLong(44);
        long fileSize     = Integer.toUnsignedLong(buf.getInt(52));
        int  showCommand  = buf.getInt(60);
        short hotKey      = buf.getShort(64);

        if (creationTime != 0) {
            fields.put("CreationTime", filetimeToIso(creationTime));
        }
        if (accessTime != 0) {
            fields.put("AccessTime", filetimeToIso(accessTime));
        }
        if (writeTime != 0) {
            fields.put("WriteTime", filetimeToIso(writeTime));
        }
        if (fileSize > 0) {
            fields.put("FileSize", Long.toString(fileSize));
        }
        String showCmd = SHOW_COMMANDS.getOrDefault(showCommand, "Normal");
        fields.put("ShowCommand", showCmd);
        if (hotKey != 0) {
            fields.put("HotKey", decodeHotKey(hotKey));
        }
        if (fileAttributes != 0) {
            fields.put("FileAttributes", decodeFileAttributes(fileAttributes));
        }

        return HEADER_SIZE;
    }

    // ── IDList §2.2 ──────────────────────────────────────────────────────────

    private int skipIdList(ByteBuffer buf, int pos, int linkFlags, int fileLen) {
        if ((linkFlags & FLAG_HAS_TARGET_IDLIST) == 0) {
            return pos;
        }
        if (pos + 2 > fileLen) {
            return pos;
        }
        int idListSize = Short.toUnsignedInt(buf.getShort(pos));
        return pos + 2 + idListSize;
    }

    // ── LinkInfo §2.3 ────────────────────────────────────────────────────────

    private int parseLinkInfo(ByteBuffer buf, int pos, int linkFlags, int fileLen,
                              Map<String, String> fields, List<String> warnings) {
        if ((linkFlags & FLAG_HAS_LINK_INFO) == 0) {
            return pos;
        }
        if (pos + 4 > fileLen) {
            return pos;
        }
        int linkInfoSize = buf.getInt(pos);
        if (linkInfoSize < 28 || pos + linkInfoSize > fileLen) {
            return pos + Math.max(0, linkInfoSize);
        }
        try {
            int headerSize    = buf.getInt(pos + 4);
            int infoFlags     = buf.getInt(pos + 8);
            int volIdOff      = buf.getInt(pos + 12);
            int localBaseOff  = buf.getInt(pos + 16);
            int netLinkOff    = buf.getInt(pos + 20);
            int pathSuffixOff = buf.getInt(pos + 24);

            int localBaseOffU  = (headerSize >= 0x24 && pos + 32 <= fileLen) ? buf.getInt(pos + 28) : 0;
            int pathSuffixOffU = (headerSize >= 0x28 && pos + 36 <= fileLen) ? buf.getInt(pos + 32) : 0;

            boolean hasLocal   = (infoFlags & 0x01) != 0;
            boolean hasNetwork = (infoFlags & 0x02) != 0;

            if (hasLocal) {
                // Prefer Unicode path when header provides Unicode offset
                String localPath = null;
                if (localBaseOffU > 0) {
                    localPath = readNullTermW(buf, pos + localBaseOffU, fileLen);
                }
                if (localPath == null || localPath.isEmpty()) {
                    localPath = readNullTermA(buf, pos + localBaseOff, fileLen);
                }
                if (localPath != null && !localPath.isEmpty()) {
                    fields.put("LocalBasePath", localPath);
                }

                String suffix = null;
                if (pathSuffixOffU > 0) {
                    suffix = readNullTermW(buf, pos + pathSuffixOffU, fileLen);
                }
                if (suffix == null || suffix.isEmpty()) {
                    suffix = readNullTermA(buf, pos + pathSuffixOff, fileLen);
                }
                if (suffix != null && !suffix.isEmpty()) {
                    fields.put("CommonPathSuffix", suffix);
                }

                // VolumeID sub-block
                if (volIdOff > 0 && pos + volIdOff + 16 <= fileLen) {
                    parseVolumeId(buf, pos + volIdOff, fileLen, fields);
                }
            }

            if (hasNetwork && netLinkOff > 0 && pos + netLinkOff + 8 <= fileLen) {
                parseNetworkLink(buf, pos + netLinkOff, fileLen, fields);
            }
        } catch (Exception e) {
            warnings.add("LinkInfo parse error: " + e.getMessage());
        }

        return pos + linkInfoSize;
    }

    private void parseVolumeId(ByteBuffer buf, int base, int fileLen,
                               Map<String, String> fields) {
        int volSize = buf.getInt(base);
        if (volSize < 16 || base + volSize > fileLen) {
            return;
        }
        int driveType   = buf.getInt(base + 4);
        int driveSerial = buf.getInt(base + 8);
        int labelOff    = buf.getInt(base + 12);

        if (driveType >= 0 && driveType < DRIVE_TYPES.length) {
            fields.put("DriveType", DRIVE_TYPES[driveType]);
        }
        fields.put("DriveSerialNumber", String.format(Locale.ROOT,"%08X", driveSerial));

        // Unicode label offset present when labelOff == 0x14
        String label = null;
        if (labelOff == 0x14 && volSize >= 20) {
            int labelOffU = buf.getInt(base + 16);
            if (labelOffU > 0) {
                label = readNullTermW(buf, base + labelOffU, fileLen);
            }
        }
        if (label == null || label.isEmpty()) {
            label = readNullTermA(buf, base + labelOff, fileLen);
        }
        if (label != null && !label.isEmpty()) {
            fields.put("VolumeLabel", label);
        }
    }

    private void parseNetworkLink(ByteBuffer buf, int base, int fileLen,
                                  Map<String, String> fields) {
        int size       = buf.getInt(base);
        if (size < 20 || base + size > fileLen) {
            return;
        }
        int netFlags    = buf.getInt(base + 4);
        int netNameOff  = buf.getInt(base + 8);
        int devNameOff  = buf.getInt(base + 12);

        // Unicode offsets present when NetNameOffset > 20
        int netNameOffU = (netNameOff > 20 && base + 24 <= fileLen) ? buf.getInt(base + 20) : 0;
        int devNameOffU = (netNameOff > 20 && base + 28 <= fileLen) ? buf.getInt(base + 24) : 0;

        String netName = null;
        if (netNameOffU > 0) {
            netName = readNullTermW(buf, base + netNameOffU, fileLen);
        }
        if (netName == null || netName.isEmpty()) {
            netName = readNullTermA(buf, base + netNameOff, fileLen);
        }
        if (netName != null && !netName.isEmpty()) {
            fields.put("NetworkShareName", netName);
        }

        String devName = null;
        if (devNameOffU > 0) {
            devName = readNullTermW(buf, base + devNameOffU, fileLen);
        }
        if (devName == null || devName.isEmpty()) {
            devName = readNullTermA(buf, base + devNameOff, fileLen);
        }
        if (devName != null && !devName.isEmpty()) {
            fields.put("NetworkDeviceName", devName);
        }
    }

    // ── StringData §2.4 ──────────────────────────────────────────────────────

    private int parseStringData(ByteBuffer buf, int pos, int linkFlags, boolean unicode,
                                int fileLen, Map<String, String> fields) {
        int[] flagsOrder   = {FLAG_HAS_NAME, FLAG_HAS_RELATIVE_PATH,
                               FLAG_HAS_WORKING_DIR, FLAG_HAS_ARGUMENTS, FLAG_HAS_ICON_LOCATION};
        String[] keyNames  = {"Name", "RelativePath", "WorkingDir", "Arguments", "IconLocation"};

        for (int fi = 0; fi < flagsOrder.length; fi++) {
            if ((linkFlags & flagsOrder[fi]) == 0) {
                continue;
            }
            if (pos + 2 > fileLen) {
                break;
            }
            int charCount = Short.toUnsignedInt(buf.getShort(pos));
            pos += 2;
            String value;
            if (unicode) {
                int byteLen = charCount * 2;
                if (pos + byteLen > fileLen) {
                    break;
                }
                value = new String(buf.array(), pos, byteLen, StandardCharsets.UTF_16LE);
                pos += byteLen;
            } else {
                if (pos + charCount > fileLen) {
                    break;
                }
                // Use cp1252 (Windows default ANSI) per both reference implementations
                Charset cp1252 = Charset.forName("windows-1252");
                value = new String(buf.array(), pos, charCount, cp1252);
                pos += charCount;
            }
            if (!value.isEmpty()) {
                fields.put(keyNames[fi], value);
            }
        }
        return pos;
    }

    // ── ExtraData §2.5 ───────────────────────────────────────────────────────

    private void parseExtraData(ByteBuffer buf, int pos, int fileLen,
                                Map<String, String> fields, List<String> warnings) {
        while (pos + 8 <= fileLen) {
            int blockSize = buf.getInt(pos);
            if (blockSize < 4 || pos + blockSize > fileLen) {
                break;
            }
            int sig = buf.getInt(pos + 4);
            try {
                switch (sig) {
                    case SIG_ENVIRONMENT_VAR:
                        parseEnvironmentVarBlock(buf, pos, blockSize, fileLen, fields, "EnvironmentVariableTarget");
                        break;
                    case SIG_ICON_ENVIRONMENT:
                        parseEnvironmentVarBlock(buf, pos, blockSize, fileLen, fields, "IconEnvironmentTarget");
                        break;
                    case SIG_TRACKER:
                        parseTrackerBlock(buf, pos, blockSize, fileLen, fields, warnings);
                        break;
                    case SIG_DARWIN:
                        parseDarwinBlock(buf, pos, blockSize, fileLen, fields);
                        break;
                    case SIG_SHIM:
                        parseShimBlock(buf, pos, blockSize, fileLen, fields);
                        break;
                    case SIG_CONSOLE_FE:
                        if (blockSize >= 12) {
                            fields.put("ConsoleCodePage", Integer.toString(buf.getInt(pos + 8)));
                        }
                        break;
                    case SIG_KNOWN_FOLDER:
                        parseKnownFolderBlock(buf, pos, blockSize, fileLen, fields);
                        break;
                    default:
                        break;
                }
            } catch (Exception e) {
                warnings.add("ExtraData block " + String.format(Locale.ROOT,"0x%08X", sig) + " error: " + e.getMessage());
            }
            pos += blockSize;
        }
    }

    // §2.5.1 / §2.5.7 — EnvironmentVariableDataBlock / IconEnvironmentDataBlock
    // Structure: [8..267] 260-byte ANSI target, [268..787] 520-byte UTF-16LE target
    private void parseEnvironmentVarBlock(ByteBuffer buf, int pos, int blockSize,
                                          int fileLen, Map<String, String> fields, String key) {
        if (blockSize < 0x314 || pos + 0x314 > fileLen) {
            return;
        }
        // Prefer Unicode (offset 268, 260 UTF-16LE chars = 520 bytes)
        String unicode = readFixedW(buf, pos + 268, 260, fileLen);
        if (unicode != null && !unicode.isEmpty()) {
            fields.put(key, unicode);
            return;
        }
        String ansi = readFixedA(buf, pos + 8, 260, fileLen);
        if (ansi != null && !ansi.isEmpty()) {
            fields.put(key, ansi);
        }
    }

    // §2.5.6 — DarwinDataBlock (application identifier)
    // Same 788-byte layout as EnvironmentVariableDataBlock
    private void parseDarwinBlock(ByteBuffer buf, int pos, int blockSize,
                                  int fileLen, Map<String, String> fields) {
        if (blockSize < 0x314 || pos + 0x314 > fileLen) {
            return;
        }
        String unicode = readFixedW(buf, pos + 268, 260, fileLen);
        if (unicode != null && !unicode.isEmpty()) {
            fields.put("DarwinID", unicode);
            return;
        }
        String ansi = readFixedA(buf, pos + 8, 260, fileLen);
        if (ansi != null && !ansi.isEmpty()) {
            fields.put("DarwinID", ansi);
        }
    }

    // §2.5.8 — ShimDataBlock (shim layer name)
    private void parseShimBlock(ByteBuffer buf, int pos, int blockSize,
                                int fileLen, Map<String, String> fields) {
        if (blockSize < 8 || pos + 8 > fileLen) {
            return;
        }
        int dataLen = blockSize - 8;
        if (pos + 8 + dataLen > fileLen || dataLen < 2) {
            return;
        }
        String shim = new String(buf.array(), pos + 8, dataLen, StandardCharsets.UTF_16LE);
        // Strip null terminator
        int nullIdx = shim.indexOf('\0');
        if (nullIdx >= 0) {
            shim = shim.substring(0, nullIdx);
        }
        if (!shim.isEmpty()) {
            fields.put("ShimLayer", shim);
        }
    }

    // §2.5.3 — TrackerDataBlock (machine ID, volume GUID, file GUID with MAC address)
    private void parseTrackerBlock(ByteBuffer buf, int pos, int blockSize,
                                   int fileLen, Map<String, String> fields, List<String> warnings) {
        // Must be exactly 0x60 bytes; payload starts at offset 8
        if (blockSize != 0x60 || pos + 0x60 > fileLen) {
            return;
        }
        // MachineID: 16-byte null-terminated ASCII hostname at [+16]
        String machineId = readNullTermA(buf, pos + 16, Math.min(pos + 32, fileLen));
        if (machineId != null && !machineId.isEmpty()) {
            fields.put("MachineID", machineId);
        }

        // DroidVolumeID: 16-byte GUID at [+32]
        if (pos + 48 <= fileLen) {
            fields.put("DroidVolumeID", formatGuid(buf, pos + 32));
        }

        // DroidFileID: 16-byte UUID v1 at [+48] — contains MAC address and creation timestamp
        if (pos + 64 <= fileLen) {
            String droidFile = formatGuid(buf, pos + 48);
            fields.put("DroidFileID", droidFile);
            // Extract embedded MAC address from last 6 bytes of UUID node field
            String mac = extractMac(buf, pos + 48);
            if (mac != null) {
                fields.put("MACAddress", mac);
            }
            // Extract UUID v1 timestamp (Zimmerman technique)
            String uuidTs = extractUuidV1Timestamp(buf, pos + 48);
            if (uuidTs != null) {
                fields.put("DroidFileCreationTime", uuidTs);
            }
        }

        // BirthDroidVolumeID at [+64], BirthDroidFileID at [+80]
        if (pos + 80 <= fileLen) {
            fields.put("BirthDroidVolumeID", formatGuid(buf, pos + 64));
        }
        if (pos + 96 <= fileLen) {
            fields.put("BirthDroidFileID", formatGuid(buf, pos + 80));
        }
    }

    // §2.5.11 — KnownFolderDataBlock
    private void parseKnownFolderBlock(ByteBuffer buf, int pos, int blockSize,
                                       int fileLen, Map<String, String> fields) {
        if (blockSize < 0x1C || pos + 0x1C > fileLen) {
            return;
        }
        // 16-byte GUID at [+8]
        fields.put("KnownFolderID", formatGuid(buf, pos + 8));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Read null-terminated ANSI (cp1252) string starting at {@code pos}. */
    private String readNullTermA(ByteBuffer buf, int pos, int limit) {
        if (pos < 0 || pos >= limit) {
            return null;
        }
        byte[] data = buf.array();
        int end = pos;
        while (end < limit && data[end] != 0) {
            end++;
        }
        if (end == pos) {
            return null;
        }
        Charset cp1252;
        try {
            cp1252 = Charset.forName("windows-1252");
        } catch (Exception e) {
            cp1252 = StandardCharsets.ISO_8859_1;
        }
        return new String(data, pos, end - pos, cp1252);
    }

    /** Read null-terminated UTF-16LE string starting at {@code pos}. */
    private String readNullTermW(ByteBuffer buf, int pos, int limit) {
        if (pos < 0 || pos + 2 > limit) {
            return null;
        }
        byte[] data = buf.array();
        int end = pos;
        while (end + 1 < limit && (data[end] != 0 || data[end + 1] != 0)) {
            end += 2;
        }
        if (end == pos) {
            return null;
        }
        return new String(data, pos, end - pos, StandardCharsets.UTF_16LE);
    }

    /** Read fixed-length ANSI string (null-padded), stripping null terminator. */
    private String readFixedA(ByteBuffer buf, int pos, int maxChars, int fileLen) {
        if (pos < 0 || pos + maxChars > fileLen) {
            return null;
        }
        byte[] data = buf.array();
        int len = 0;
        while (len < maxChars && data[pos + len] != 0) {
            len++;
        }
        if (len == 0) {
            return null;
        }
        Charset cp1252;
        try {
            cp1252 = Charset.forName("windows-1252");
        } catch (Exception e) {
            cp1252 = StandardCharsets.ISO_8859_1;
        }
        return new String(data, pos, len, cp1252);
    }

    /** Read fixed-length UTF-16LE string (null-padded), stripping null terminator. */
    private String readFixedW(ByteBuffer buf, int pos, int maxChars, int fileLen) {
        if (pos < 0 || pos + maxChars * 2 > fileLen) {
            return null;
        }
        byte[] data = buf.array();
        int len = 0;
        while (len < maxChars && (data[pos + len * 2] != 0 || data[pos + len * 2 + 1] != 0)) {
            len++;
        }
        if (len == 0) {
            return null;
        }
        return new String(data, pos, len * 2, StandardCharsets.UTF_16LE);
    }

    /** Format a 16-byte GUID as {xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}. */
    private String formatGuid(ByteBuffer buf, int pos) {
        // GUID on disk: first 3 fields are little-endian, last 2 are big-endian bytes
        int   data1 = buf.getInt(pos);
        short data2 = buf.getShort(pos + 4);
        short data3 = buf.getShort(pos + 6);
        byte[] b = buf.array();
        return String.format(Locale.ROOT,"{%08X-%04X-%04X-%02X%02X-%02X%02X%02X%02X%02X%02X}",
                data1, Short.toUnsignedInt(data2), Short.toUnsignedInt(data3),
                b[pos + 8] & 0xFF, b[pos + 9] & 0xFF,
                b[pos + 10] & 0xFF, b[pos + 11] & 0xFF, b[pos + 12] & 0xFF,
                b[pos + 13] & 0xFF, b[pos + 14] & 0xFF, b[pos + 15] & 0xFF);
    }

    /**
     * Extract MAC address from UUID v1 node field.
     * In a GUID on disk the node (last 6 bytes) is stored big-endian (bytes [10..15]).
     */
    private String extractMac(ByteBuffer buf, int pos) {
        byte[] b = buf.array();
        // Check multicast bit — if set the MAC is synthetic (not real hardware)
        if ((b[pos + 10] & 0x01) != 0) {
            return null;
        }
        return String.format(Locale.ROOT,"%02X:%02X:%02X:%02X:%02X:%02X",
                b[pos + 10] & 0xFF, b[pos + 11] & 0xFF, b[pos + 12] & 0xFF,
                b[pos + 13] & 0xFF, b[pos + 14] & 0xFF, b[pos + 15] & 0xFF);
    }

    /**
     * Extract the creation timestamp from a UUID v1 node.
     * UUID v1 timestamp = 100-ns ticks since 1582-10-15; stored split across
     * time-low (bytes 0-3 LE), time-mid (bytes 4-5 LE), time-hi-and-version (bytes 6-7 LE).
     * Strip the 4-bit version nibble from time-hi before reassembling.
     */
    private String extractUuidV1Timestamp(ByteBuffer buf, int pos) {
        int  timeLow  = buf.getInt(pos);
        int  timeMid  = Short.toUnsignedInt(buf.getShort(pos + 4));
        int  timeHiV  = Short.toUnsignedInt(buf.getShort(pos + 6));
        int  version  = (timeHiV >> 12) & 0xF;
        if (version != 1) {
            return null;
        }
        int  timeHi   = timeHiV & 0x0FFF;
        long ticks100ns = ((long) timeHi << 48) | ((long) timeMid << 32)
                | Integer.toUnsignedLong(timeLow);
        // UUID epoch is 1582-10-15; convert to Unix epoch
        long unixTicks100ns = ticks100ns - 122192928000000000L;
        long unixNanos = unixTicks100ns * 100L;
        long epochSecs  = unixNanos / 1_000_000_000L;
        int  nanoAdjust = (int) (unixNanos % 1_000_000_000L);
        try {
            return Instant.ofEpochSecond(epochSecs, nanoAdjust).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Convert Windows FILETIME (100-ns intervals since 1601-01-01) to ISO-8601. */
    private String filetimeToIso(long filetime) {
        // Convert to 100-ns ticks since Unix epoch
        long ticks = filetime - FILETIME_EPOCH_DIFF_NS100;
        long epochSecs   = ticks / 10_000_000L;
        int  nanosAdjust = (int) ((ticks % 10_000_000L) * 100L);
        try {
            return Instant.ofEpochSecond(epochSecs, nanosAdjust).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String decodeHotKey(short hotKey) {
        int low  = hotKey & 0xFF;
        int high = (hotKey >> 8) & 0xFF;
        if (low == 0) {
            return "None";
        }
        StringBuilder sb = new StringBuilder();
        if ((high & 0x04) != 0) {
            sb.append("ALT+");
        }
        if ((high & 0x02) != 0) {
            sb.append("CTRL+");
        }
        if ((high & 0x01) != 0) {
            sb.append("SHIFT+");
        }
        if (low >= 0x30 && low <= 0x39) {
            sb.append((char) low);
        } else if (low >= 0x41 && low <= 0x5A) {
            sb.append((char) low);
        } else if (low >= 0x70 && low <= 0x87) {
            sb.append('F').append(low - 0x6F);
        } else {
            sb.append(String.format(Locale.ROOT,"0x%02X", low));
        }
        return sb.toString();
    }

    private String decodeFileAttributes(int attrs) {
        List<String> flags = new ArrayList<>();
        if ((attrs & 0x01) != 0) {
            flags.add("ReadOnly");
        }
        if ((attrs & 0x02) != 0) {
            flags.add("Hidden");
        }
        if ((attrs & 0x04) != 0) {
            flags.add("System");
        }
        if ((attrs & 0x10) != 0) {
            flags.add("Directory");
        }
        if ((attrs & 0x20) != 0) {
            flags.add("Archive");
        }
        if ((attrs & 0x80) != 0) {
            flags.add("Normal");
        }
        if ((attrs & 0x100) != 0) {
            flags.add("Temporary");
        }
        if ((attrs & 0x400) != 0) {
            flags.add("ReparsePoint");
        }
        if ((attrs & 0x800) != 0) {
            flags.add("Compressed");
        }
        if ((attrs & 0x4000) != 0) {
            flags.add("Encrypted");
        }
        return flags.isEmpty() ? String.format(Locale.ROOT,"0x%08X", attrs) : String.join("|", flags);
    }
}
