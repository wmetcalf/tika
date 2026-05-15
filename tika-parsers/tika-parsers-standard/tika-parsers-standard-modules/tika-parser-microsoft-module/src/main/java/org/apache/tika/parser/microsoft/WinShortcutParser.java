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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * Covers ShellLinkHeader, IDList (with 0xBEEF0004 extension block for creation/access
 * timestamps and Unicode long filenames per wmetcalf/LnkParse3 fork), LinkInfo,
 * StringData, ExtraData (TrackerDataBlock, EnvironmentVariableDataBlock, DarwinDataBlock,
 * ShimDataBlock, KnownFolderDataBlock), and terminal-block appended data (SHA-256).
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

    // ExtraData block signatures (§2.5)
    private static final int SIG_ENVIRONMENT_VAR    = 0xA0000001;
    private static final int SIG_CONSOLE            = 0xA0000002;
    private static final int SIG_TRACKER            = 0xA0000003;
    private static final int SIG_CONSOLE_FE         = 0xA0000004;
    private static final int SIG_SPECIAL_FOLDER     = 0xA0000005;
    private static final int SIG_DARWIN             = 0xA0000006;
    private static final int SIG_ICON_ENVIRONMENT   = 0xA0000007;
    private static final int SIG_SHIM               = 0xA0000008;
    private static final int SIG_PROPERTY_STORE     = 0xA0000009;
    private static final int SIG_KNOWN_FOLDER       = 0xA000000B;
    private static final int SIG_VISTA_IDLIST       = 0xA000000C;

    // PropertyStoreDataBlock: GUID that uses string-named properties (D5CDD505...)
    private static final String NAMED_PROP_GUID = "{D5CDD505-2E9C-101B-9397-08002B2CF9AE}";

    // CSIDL → human-readable name for SpecialFolderDataBlock
    private static final Map<Integer, String> CSIDL_NAMES = new LinkedHashMap<>();
    static {
        CSIDL_NAMES.put(0x00, "Desktop");
        CSIDL_NAMES.put(0x01, "Internet");
        CSIDL_NAMES.put(0x02, "Programs");
        CSIDL_NAMES.put(0x03, "Controls");
        CSIDL_NAMES.put(0x05, "Personal/MyDocuments");
        CSIDL_NAMES.put(0x06, "Favorites");
        CSIDL_NAMES.put(0x07, "Startup");
        CSIDL_NAMES.put(0x08, "Recent");
        CSIDL_NAMES.put(0x09, "SendTo");
        CSIDL_NAMES.put(0x0A, "RecycleBin");
        CSIDL_NAMES.put(0x0B, "StartMenu");
        CSIDL_NAMES.put(0x0D, "MyDocuments");
        CSIDL_NAMES.put(0x0E, "MyMusic");
        CSIDL_NAMES.put(0x0F, "MyVideo");
        CSIDL_NAMES.put(0x10, "DesktopDirectory");
        CSIDL_NAMES.put(0x11, "MyComputer");
        CSIDL_NAMES.put(0x12, "NetworkNeighborhood");
        CSIDL_NAMES.put(0x13, "NetHood");
        CSIDL_NAMES.put(0x14, "Fonts");
        CSIDL_NAMES.put(0x15, "Templates");
        CSIDL_NAMES.put(0x1A, "AppData");
        CSIDL_NAMES.put(0x1C, "LocalAppData");
        CSIDL_NAMES.put(0x20, "InternetCache");
        CSIDL_NAMES.put(0x21, "Cookies");
        CSIDL_NAMES.put(0x22, "History");
        CSIDL_NAMES.put(0x23, "CommonAppData");
        CSIDL_NAMES.put(0x24, "Windows");
        CSIDL_NAMES.put(0x25, "System");
        CSIDL_NAMES.put(0x26, "ProgramFiles");
        CSIDL_NAMES.put(0x27, "MyPictures");
        CSIDL_NAMES.put(0x28, "UserProfile");
        CSIDL_NAMES.put(0x29, "SystemX86");
        CSIDL_NAMES.put(0x2A, "ProgramFilesX86");
        CSIDL_NAMES.put(0x2B, "ProgramFilesCommon");
        CSIDL_NAMES.put(0x30, "AdminTools");
    }

    // IDList extension block signature (0xBEEF0004 — File Entry)
    private static final int SIG_BEEF0004 = 0xBEEF0004;

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

        int pos = HEADER_SIZE;
        try {
            parseHeader(buf, fields);
            int linkFlags = buf.getInt(20);
            boolean unicode = (linkFlags & FLAG_IS_UNICODE) != 0;

            pos = parseIdList(buf, pos, linkFlags, raw.length, fields, warnings);
            pos = parseLinkInfo(buf, pos, linkFlags, raw.length, fields, warnings);
            pos = parseStringData(buf, pos, linkFlags, unicode, raw.length, fields);
            pos = parseExtraData(buf, pos, raw.length, fields, warnings);
            parseAppendedData(buf, pos, raw.length, fields, warnings);
        } catch (Exception e) {
            LOG.warn("Error parsing LNK file: {}", e.getMessage());
            warnings.add("parse-error: " + e.getMessage());
        }

        // Propagate target path to metadata
        String targetPath = fields.get("IDListPath");
        if (targetPath == null) {
            targetPath = fields.get("LocalBasePath");
        }
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

    private void parseHeader(ByteBuffer buf, Map<String, String> fields) {
        int linkFlags      = buf.getInt(20);
        int fileAttributes = buf.getInt(24);
        long creationTime  = buf.getLong(28);
        long accessTime    = buf.getLong(36);
        long writeTime     = buf.getLong(44);
        long fileSize      = Integer.toUnsignedLong(buf.getInt(52));
        int  showCommand   = buf.getInt(60);
        short hotKey       = buf.getShort(64);

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
        fields.put("ShowCommand", SHOW_COMMANDS.getOrDefault(showCommand, "Normal"));
        if (hotKey != 0) {
            fields.put("HotKey", decodeHotKey(hotKey));
        }
        if (fileAttributes != 0) {
            fields.put("FileAttributes", decodeFileAttributes(fileAttributes));
        }

        // Decode flag names that are useful for analysts
        List<String> flagNames = decodeLinkFlags(linkFlags);
        if (!flagNames.isEmpty()) {
            fields.put("LinkFlags", String.join("|", flagNames));
        }
    }

    // ── IDList §2.2 with 0xBEEF0004 extension blocks ─────────────────────────
    //
    // wmetcalf/LnkParse3 fork: parses Shell Item extension blocks to extract
    // creation_time, last_access_time, and Unicode secondary_name (LFN) from
    // each FileSystem (0x30-0x3F type) shell item in the chain.

    private int parseIdList(ByteBuffer buf, int pos, int linkFlags, int fileLen,
                            Map<String, String> fields, List<String> warnings) {
        if ((linkFlags & FLAG_HAS_TARGET_IDLIST) == 0) {
            return pos;
        }
        if (pos + 2 > fileLen) {
            return pos;
        }
        int idListSize = Short.toUnsignedInt(buf.getShort(pos));
        int idListEnd  = pos + 2 + idListSize;
        pos += 2;

        List<String> pathComponents = new ArrayList<>();
        int itemStart = pos;

        while (itemStart + 2 < idListEnd && itemStart + 2 <= fileLen) {
            int itemSize = Short.toUnsignedInt(buf.getShort(itemStart));
            if (itemSize == 0) {
                break; // terminator
            }
            int itemEnd = itemStart + itemSize;
            if (itemEnd > idListEnd || itemEnd > fileLen) {
                break;
            }

            try {
                String component = parseShellItem(buf, itemStart, itemSize, itemEnd, fields);
                if (component != null && !component.isEmpty()) {
                    pathComponents.add(component);
                }
            } catch (Exception e) {
                warnings.add("IDList item parse error: " + e.getMessage());
            }

            itemStart = itemEnd;
        }

        if (!pathComponents.isEmpty()) {
            fields.put("IDListPath", String.join("\\", pathComponents));
        }

        return idListEnd;
    }

    /**
     * Parse one Shell Item from the IDList.
     *
     * @param base    start offset of the item (includes the 2-byte size field)
     * @param size    total item size including the size field
     * @param end     exclusive end offset of the item
     */
    private String parseShellItem(ByteBuffer buf, int base, int size, int end,
                                  Map<String, String> fields) {
        if (size < 3) {
            return null;
        }
        int typeIndicator = buf.get(base + 2) & 0xFF;

        // Root folder (GUID item, type 0x1F) — skip
        if (typeIndicator == 0x1F) {
            return null;
        }

        // Drive root (0x23, 0x2E, 0x2F, 0x29) — volume letter at offset base+3
        if (typeIndicator == 0x2F || typeIndicator == 0x2E
                || typeIndicator == 0x23 || typeIndicator == 0x29) {
            if (base + 4 <= end) {
                char ch = (char) (buf.get(base + 3) & 0xFF);
                if (ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z') {
                    return ch + ":";
                }
            }
            return null;
        }

        // File system folder/file (0x30-0x3F)
        if ((typeIndicator & 0xF0) == 0x30) {
            return parseShellFsFolder(buf, base, size, end, typeIndicator, fields);
        }

        // Network location (0x40-0x47) — location string at offset ~14
        if ((typeIndicator & 0xF0) == 0x40) {
            if (base + 15 <= end) {
                String loc = readNullTermA(buf, base + 14, end);
                return loc;
            }
        }

        return null;
    }

    /**
     * Parse a FileSystem shell item (type 0x30-0x3F, ShellFSFolder in LnkParse3).
     *
     * Layout (MS-SHLLINK / explorer shell item documentation):
     *   base+0   uint16  ItemIDSize
     *   base+2   uint8   TypeIndicator
     *   base+3   uint8   FileAttributes (FAT-era flags)
     *   base+4   uint32  FileSize (0 for directories)
     *   base+8   uint32  ModificationTime (DOS date/time)
     *   base+12  uint16  FileAttributeFlags
     *   base+14  char[]  PrimaryName (8.3, null-terminated ANSI or Unicode)
     *
     * Extension blocks live between extOffset (last 2 bytes of item) and item end - 2.
     * 0xBEEF0004 (FileEntry extension) provides: creation_time, last_access_time,
     * and Unicode long filename (secondary_name / LFN).  Ported from wmetcalf/LnkParse3.
     */
    private String parseShellFsFolder(ByteBuffer buf, int base, int size, int end,
                                      int typeIndicator, Map<String, String> fields) {
        if (size < 16) {
            return null;
        }

        // Whether primary name is Unicode (bit 2 of type indicator)
        boolean itemUnicode = (typeIndicator & 0x04) != 0;

        // Primary name (8.3 short name)
        String primaryName = null;
        if (itemUnicode) {
            primaryName = readNullTermW(buf, base + 14, end);
        } else {
            primaryName = readNullTermA(buf, base + 14, end);
        }

        // Modification time for this path component
        int modTimeDos = buf.getInt(base + 8);
        if (modTimeDos != 0) {
            String modStr = dosTimeToIso(modTimeDos);
            if (modStr != null) {
                fields.put("IDListModTime[" + safeLabel(primaryName) + "]", modStr);
            }
        }

        // Extension blocks — offset stored in last 2 bytes of item (relative to item start)
        if (size >= 4) {
            int extOffset = Short.toUnsignedInt(buf.getShort(end - 2));
            if (extOffset > 0 && base + extOffset < end - 2) {
                String longName = parseBeef0004Extensions(buf, base + extOffset, end - 2,
                        primaryName, fields);
                if (longName != null && !longName.isEmpty()) {
                    return longName;
                }
            }
        }

        return primaryName;
    }

    /**
     * Walk extension blocks looking for 0xBEEF0004 (FileEntry).
     * Ported from wmetcalf/LnkParse3 fork: extension/__init__.py + extension/file_entry.py
     *
     * Layout of the extension block area (iterated from extBase to extEnd):
     *   +0  uint16  ExtensionBlockSize
     *   +2  uint16  ExtensionBlockVersion
     *   +4  uint32  ExtensionBlockSignature (0xBEEF0004 for FileEntry)
     *   +8  uint32  CreationTime  (DOS date/time, 4 bytes)
     *  +12  uint16  LastAccessDate  }  DOS date (2) and time (2) split
     *  +14  uint16  LastAccessTime  }
     *  +16  uint16  unknown
     *  +18+  LongName (version-gated, UTF-16LE)
     *
     * LongName offset from block start:
     *   Base offset = 18
     *   version >= 3: +2 bytes unknown
     *   version >= 7: +18 bytes (file ref 8B + 2 timestamps 8B each = actually 8+4+4 or varies)
     *   version >= 8: +4 bytes unknown
     *   version >= 9: +4 bytes unknown
     */
    private String parseBeef0004Extensions(ByteBuffer buf, int extBase, int extEnd,
                                           String primaryName, Map<String, String> fields) {
        int pos = extBase;
        while (pos + 8 <= extEnd) {
            int blockSize = Short.toUnsignedInt(buf.getShort(pos));
            if (blockSize < 8 || pos + blockSize > extEnd) {
                break;
            }
            int version   = Short.toUnsignedInt(buf.getShort(pos + 2));
            int sig       = buf.getInt(pos + 4);

            if (sig == SIG_BEEF0004) {
                // Creation time: DOS format at bytes 8-11 (date+time, each 2 bytes)
                if (pos + 12 <= extEnd) {
                    int dosDate = Short.toUnsignedInt(buf.getShort(pos + 10));
                    int dosTime = Short.toUnsignedInt(buf.getShort(pos + 8));
                    String creation = dosDateTimeToIso(dosDate, dosTime);
                    if (creation != null) {
                        fields.put("IDListCreationTime[" + safeLabel(primaryName) + "]", creation);
                    }
                }
                // Last access time: bytes 12-15
                if (pos + 16 <= extEnd) {
                    int dosDate = Short.toUnsignedInt(buf.getShort(pos + 14));
                    int dosTime = Short.toUnsignedInt(buf.getShort(pos + 12));
                    String access = dosDateTimeToIso(dosDate, dosTime);
                    if (access != null) {
                        fields.put("IDListAccessTime[" + safeLabel(primaryName) + "]", access);
                    }
                }
                // Long name (Unicode LFN) — version-gated per wmetcalf fork
                String longName = null;
                if (version >= 3) {
                    int nameStart = pos + 18;
                    if (version >= 3) {
                        nameStart += 2;  // 2 bytes unknown
                    }
                    if (version >= 7) {
                        nameStart += 18; // MFT reference + extra timestamps
                    }
                    if (version >= 8) {
                        nameStart += 4;
                    }
                    if (version >= 9) {
                        nameStart += 4;
                    }
                    if (nameStart + 2 <= extEnd) {
                        longName = readNullTermW(buf, nameStart, extEnd);
                    }
                }
                return longName;
            }

            pos += blockSize;
        }
        return null;
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
        fields.put("DriveSerialNumber", String.format(Locale.ROOT, "%08X", driveSerial));

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
        int size = buf.getInt(base);
        if (size < 20 || base + size > fileLen) {
            return;
        }
        int netNameOff  = buf.getInt(base + 8);
        int devNameOff  = buf.getInt(base + 12);

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
        int[] flagsOrder  = {FLAG_HAS_NAME, FLAG_HAS_RELATIVE_PATH,
                              FLAG_HAS_WORKING_DIR, FLAG_HAS_ARGUMENTS, FLAG_HAS_ICON_LOCATION};
        String[] keyNames = {"Name", "RelativePath", "WorkingDir", "Arguments", "IconLocation"};

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
                Charset cp1252 = cp1252();
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

    private int parseExtraData(ByteBuffer buf, int pos, int fileLen,
                               Map<String, String> fields, List<String> warnings) {
        while (pos + 8 <= fileLen) {
            int blockSize = buf.getInt(pos);
            // Terminal block: size < 4 signals end of ExtraData
            if (blockSize < 4) {
                break;
            }
            if (pos + blockSize > fileLen) {
                break;
            }
            int sig = buf.getInt(pos + 4);
            try {
                switch (sig) {
                    case SIG_ENVIRONMENT_VAR:
                        parseEnvironmentVarBlock(buf, pos, blockSize, fileLen, fields,
                                "EnvironmentVariableTarget");
                        break;
                    case SIG_CONSOLE:
                        parseConsoleBlock(buf, pos, blockSize, fileLen, fields);
                        break;
                    case SIG_TRACKER:
                        parseTrackerBlock(buf, pos, blockSize, fileLen, fields);
                        break;
                    case SIG_CONSOLE_FE:
                        if (blockSize >= 12) {
                            fields.put("ConsoleCodePage",
                                    Integer.toString(buf.getInt(pos + 8)));
                        }
                        break;
                    case SIG_SPECIAL_FOLDER:
                        parseSpecialFolderBlock(buf, pos, blockSize, fileLen, fields);
                        break;
                    case SIG_DARWIN:
                        parseDarwinBlock(buf, pos, blockSize, fileLen, fields);
                        break;
                    case SIG_ICON_ENVIRONMENT:
                        parseEnvironmentVarBlock(buf, pos, blockSize, fileLen, fields,
                                "IconEnvironmentTarget");
                        break;
                    case SIG_SHIM:
                        parseShimBlock(buf, pos, blockSize, fileLen, fields);
                        break;
                    case SIG_PROPERTY_STORE:
                        parsePropertyStoreBlock(buf, pos, blockSize, fileLen, fields,
                                warnings);
                        break;
                    case SIG_KNOWN_FOLDER:
                        if (blockSize >= 0x1C && pos + 0x1C <= fileLen) {
                            fields.put("KnownFolderID", formatGuid(buf, pos + 8));
                        }
                        break;
                    case SIG_VISTA_IDLIST:
                        parseVistaIdListBlock(buf, pos, blockSize, fileLen, fields,
                                warnings);
                        break;
                    default:
                        break;
                }
            } catch (Exception e) {
                warnings.add("ExtraData sig " + String.format(Locale.ROOT, "0x%08X", sig)
                        + " error: " + e.getMessage());
            }
            pos += blockSize;
        }
        return pos;
    }

    // §2.5.1 / §2.5.7 — EnvironmentVariableDataBlock / IconEnvironmentDataBlock
    private void parseEnvironmentVarBlock(ByteBuffer buf, int pos, int blockSize,
                                          int fileLen, Map<String, String> fields, String key) {
        if (blockSize < 0x314 || pos + 0x314 > fileLen) {
            return;
        }
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

    // §2.5.6 — DarwinDataBlock
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

    // §2.5.8 — ShimDataBlock
    private void parseShimBlock(ByteBuffer buf, int pos, int blockSize,
                                int fileLen, Map<String, String> fields) {
        int dataLen = blockSize - 8;
        if (dataLen < 2 || pos + 8 + dataLen > fileLen) {
            return;
        }
        String shim = new String(buf.array(), pos + 8, dataLen, StandardCharsets.UTF_16LE);
        int nullIdx = shim.indexOf('\0');
        if (nullIdx >= 0) {
            shim = shim.substring(0, nullIdx);
        }
        if (!shim.isEmpty()) {
            fields.put("ShimLayer", shim);
        }
    }

    // §2.5.3 — TrackerDataBlock
    private void parseTrackerBlock(ByteBuffer buf, int pos, int blockSize,
                                   int fileLen, Map<String, String> fields) {
        if (blockSize != 0x60 || pos + 0x60 > fileLen) {
            return;
        }
        String machineId = readNullTermA(buf, pos + 16, Math.min(pos + 32, fileLen));
        if (machineId != null && !machineId.isEmpty()) {
            fields.put("MachineID", machineId);
        }
        if (pos + 48 <= fileLen) {
            fields.put("DroidVolumeID", formatGuid(buf, pos + 32));
        }
        if (pos + 64 <= fileLen) {
            fields.put("DroidFileID", formatGuid(buf, pos + 48));
            String mac = extractMac(buf, pos + 48);
            if (mac != null) {
                fields.put("MACAddress", mac);
            }
            String uuidTs = extractUuidV1Timestamp(buf, pos + 48);
            if (uuidTs != null) {
                fields.put("DroidFileCreationTime", uuidTs);
            }
        }
        if (pos + 80 <= fileLen) {
            fields.put("BirthDroidVolumeID", formatGuid(buf, pos + 64));
        }
        if (pos + 96 <= fileLen) {
            fields.put("BirthDroidFileID", formatGuid(buf, pos + 80));
        }
    }

    // ── New ExtraData block parsers ──────────────────────────────────────────

    // §2.5.2 — ConsoleDataBlock (0xA0000002, size 0xCC)
    // Forensically useful: FaceName reveals attacker's terminal font; codepage
    // indicates locale; FullScreen/QuickEdit indicate the shell configuration.
    private void parseConsoleBlock(ByteBuffer buf, int pos, int blockSize,
                                   int fileLen, Map<String, String> fields) {
        if (blockSize < 0xCC || pos + 0xCC > fileLen) {
            return;
        }
        // FaceName: UTF-16LE, 32 chars, at offset +44
        String faceName = readFixedW(buf, pos + 44, 32, fileLen);
        if (faceName != null && !faceName.isEmpty()) {
            fields.put("ConsoleFaceName", faceName);
        }
        int fontSize = buf.getInt(pos + 32);
        if (fontSize > 0) {
            fields.put("ConsoleFontSize", Integer.toString(fontSize));
        }
        int fontWeight = buf.getInt(pos + 40);
        fields.put("ConsoleFontWeight", fontWeight >= 700 ? "Bold" : "Normal");
        int fullScreen = buf.getInt(pos + 112);
        if (fullScreen != 0) {
            fields.put("ConsoleFullScreen", "true");
        }
    }

    // §2.5.5 — SpecialFolderDataBlock (0xA0000005, size 0x10)
    // Contains CSIDL constant identifying the Windows special folder target.
    private void parseSpecialFolderBlock(ByteBuffer buf, int pos, int blockSize,
                                         int fileLen, Map<String, String> fields) {
        if (blockSize < 0x10 || pos + 0x10 > fileLen) {
            return;
        }
        int csidl  = buf.getInt(pos + 8);
        String name = CSIDL_NAMES.getOrDefault(csidl,
                String.format(Locale.ROOT, "0x%02X", csidl));
        fields.put("SpecialFolderID", name + " (" + csidl + ")");
    }

    // §2.5.11 — VistaAndAboveIDListDataBlock (0xA000000C)
    // Contains an IDList identical to the main IDList — walk it with the same
    // logic to produce an alternate target path on Vista+ (Matmaus upstream).
    private void parseVistaIdListBlock(ByteBuffer buf, int pos, int blockSize,
                                       int fileLen, Map<String, String> fields,
                                       List<String> warnings) {
        if (blockSize < 10 || pos + 8 > fileLen) {
            return;
        }
        // IDList starts at offset +8; size = blockSize - 8
        int idListStart = pos + 8;
        int idListEnd   = pos + blockSize;
        List<String> path = new ArrayList<>();
        int itemStart = idListStart;
        while (itemStart + 2 < idListEnd && itemStart + 2 <= fileLen) {
            int itemSize = Short.toUnsignedInt(buf.getShort(itemStart));
            if (itemSize == 0) {
                break;
            }
            int itemEnd = itemStart + itemSize;
            if (itemEnd > idListEnd || itemEnd > fileLen) {
                break;
            }
            if (itemSize >= 3) {
                try {
                    String comp = parseShellItem(buf, itemStart, itemSize, itemEnd, fields);
                    if (comp != null && !comp.isEmpty()) {
                        path.add(comp);
                    }
                } catch (Exception e) {
                    warnings.add("VistaIDList item error: " + e.getMessage());
                }
            }
            itemStart = itemEnd;
        }
        if (!path.isEmpty()) {
            fields.put("VistaIDListPath", String.join("\\", path));
        }
    }

    // §2.5.9 — PropertyStoreDataBlock (0xA0000009)
    // Full MS-OLEPS parsing: SerializedPropertyStorage chain with typed values.
    // Implements the same two naming modes as Matmaus/LnkParse3 upstream:
    //   - GUID D5CDD505-... → string-named properties
    //   - All other GUIDs   → integer-named properties
    private void parsePropertyStoreBlock(ByteBuffer buf, int pos, int blockSize,
                                         int fileLen, Map<String, String> fields,
                                         List<String> warnings) {
        if (blockSize < 12 || pos + blockSize > fileLen) {
            return;
        }
        // Skip block header (size=4, sig=4) → first SerializedPropertyStorage at +8
        int storeEnd = pos + blockSize;
        int spos = pos + 8;
        int storageIdx = 0;

        while (spos + 12 <= storeEnd) {
            int storageSize = buf.getInt(spos);
            if (storageSize < 4) {
                break; // terminal
            }
            if (spos + storageSize > storeEnd) {
                break;
            }
            // Version: should be 0x53505331 ("SPS1")
            int version = buf.getInt(spos + 4);
            if (version != 0x53505331) {
                spos += storageSize;
                storageIdx++;
                continue;
            }
            // FormatID GUID at spos+8 (16 bytes)
            String formatGuid = formatGuid(buf, spos + 8);
            boolean namedProps = NAMED_PROP_GUID.equals(formatGuid);
            String prefix = "PropertyStore[" + formatGuid + "]";

            // Properties start at spos+24
            int vpos = spos + 24;
            int storageEndAbs = spos + storageSize;

            while (vpos + 8 <= storageEndAbs) {
                int valueSize = buf.getInt(vpos);
                if (valueSize < 4) {
                    break; // terminal
                }
                if (vpos + valueSize > storageEndAbs) {
                    break;
                }
                try {
                    if (namedProps) {
                        parseStringNamedProperty(buf, vpos, valueSize, storageEndAbs,
                                prefix, fields);
                    } else {
                        parseIntNamedProperty(buf, vpos, valueSize, storageEndAbs,
                                prefix, fields);
                    }
                } catch (Exception e) {
                    warnings.add("PropertyStore parse error: " + e.getMessage());
                }
                vpos += valueSize;
            }

            spos += storageSize;
            storageIdx++;
        }
    }

    /**
     * SerializedPropertyValueStringName — for GUID D5CDD505-...
     * Layout: valueSize(4) + nameSize(4) + reserved(1) + name(nameSize bytes UTF-16LE)
     *         + TypedPropertyValue
     */
    private void parseStringNamedProperty(ByteBuffer buf, int pos, int valueSize,
                                          int limit, String prefix,
                                          Map<String, String> fields) {
        if (pos + 9 > limit) {
            return;
        }
        int nameSize = buf.getInt(pos + 4);
        // reserved byte at pos+8 must be 0
        if (buf.get(pos + 8) != 0 || nameSize < 0 || pos + 9 + nameSize > limit) {
            return;
        }
        String name = "";
        if (nameSize > 0) {
            name = new String(buf.array(), pos + 9, nameSize, StandardCharsets.UTF_16LE);
            int nullIdx = name.indexOf('\0');
            if (nullIdx >= 0) {
                name = name.substring(0, nullIdx);
            }
        }
        int typedValueStart = pos + 9 + nameSize;
        String value = readTypedPropertyValue(buf, typedValueStart, limit);
        if (value != null && !name.isEmpty()) {
            fields.put(prefix + "[" + name + "]", value);
        }
    }

    /**
     * SerializedPropertyValueIntegerName — for all other GUIDs.
     * Layout: valueSize(4) + id(4) + reserved(1) + TypedPropertyValue
     */
    private void parseIntNamedProperty(ByteBuffer buf, int pos, int valueSize,
                                       int limit, String prefix,
                                       Map<String, String> fields) {
        if (pos + 9 > limit) {
            return;
        }
        int id = buf.getInt(pos + 4);
        if (buf.get(pos + 8) != 0) {
            return;
        }
        int typedValueStart = pos + 9;
        String value = readTypedPropertyValue(buf, typedValueStart, limit);
        if (value != null) {
            fields.put(prefix + "[" + id + "]", value);
        }
    }

    /**
     * TypedPropertyValue (MS-OLEPS §2.15):
     * type(uint16) + padding(uint16, must be 0) + value-bytes
     *
     * Decodes the most common VT_* types. Returns null for unknown/empty.
     */
    private String readTypedPropertyValue(ByteBuffer buf, int pos, int limit) {
        if (pos + 4 > limit) {
            return null;
        }
        int vtype = Short.toUnsignedInt(buf.getShort(pos));
        // padding at pos+2 should be 0
        if (buf.getShort(pos + 2) != 0) {
            return null;
        }
        int dataPos = pos + 4;
        switch (vtype) {
            case 0x0000: // VT_EMPTY
            case 0x0001: // VT_NULL
                return null;
            case 0x0002: // VT_I2 (int16)
                if (dataPos + 2 > limit) {
                    return null;
                }
                return Integer.toString(buf.getShort(dataPos));
            case 0x0003: // VT_I4
            case 0x0016: // VT_INT
                if (dataPos + 4 > limit) {
                    return null;
                }
                return Integer.toString(buf.getInt(dataPos));
            case 0x0013: // VT_UI4
            case 0x0017: // VT_UINT
                if (dataPos + 4 > limit) {
                    return null;
                }
                return Long.toString(Integer.toUnsignedLong(buf.getInt(dataPos)));
            case 0x0014: // VT_I8
                if (dataPos + 8 > limit) {
                    return null;
                }
                return Long.toString(buf.getLong(dataPos));
            case 0x0015: // VT_UI8
                if (dataPos + 8 > limit) {
                    return null;
                }
                return Long.toUnsignedString(buf.getLong(dataPos));
            case 0x000B: // VT_BOOL (VARIANT_BOOL: 0xFFFF=TRUE, 0x0000=FALSE)
                if (dataPos + 2 > limit) {
                    return null;
                }
                return buf.getShort(dataPos) != 0 ? "true" : "false";
            case 0x001E: // VT_LPSTR (null-terminated ANSI)
                return readNullTermA(buf, dataPos, limit);
            case 0x001F: // VT_LPWSTR (null-terminated UTF-16LE)
                return readNullTermW(buf, dataPos, limit);
            case 0x0040: // VT_FILETIME (8 bytes)
                if (dataPos + 8 > limit) {
                    return null;
                }
                long ft = buf.getLong(dataPos);
                return ft == 0 ? null : filetimeToIso(ft);
            case 0x0048: // VT_CLSID (16 bytes GUID)
                if (dataPos + 16 > limit) {
                    return null;
                }
                return formatGuid(buf, dataPos);
            default:
                return null;
        }
    }

    // ── Appended data (terminal block) ───────────────────────────────────────
    //
    // wmetcalf/LnkParse3 fork: surfaces raw bytes appended after the ExtraData
    // terminal block. Upstream (Matmaus) computes a SHA-256 hash instead.
    // We store the SHA-256 (forensic IOC) and note the byte count.

    private void parseAppendedData(ByteBuffer buf, int pos, int fileLen,
                                   Map<String, String> fields, List<String> warnings) {
        if (pos >= fileLen) {
            return;
        }
        // Skip terminal block size field (4 bytes of 0x00000000 or < 4)
        if (pos + 4 <= fileLen) {
            int terminal = buf.getInt(pos);
            if (terminal < 4) {
                pos += 4;
            }
        }
        int remaining = fileLen - pos;
        if (remaining <= 0) {
            return;
        }
        fields.put("AppendedDataSize", Integer.toString(remaining));
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(buf.array(), pos, remaining);
            byte[] digest = sha256.digest();
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            }
            fields.put("AppendedDataSHA256", hex.toString());
        } catch (NoSuchAlgorithmException e) {
            warnings.add("SHA-256 unavailable: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
        return new String(data, pos, end - pos, cp1252());
    }

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

    private String readFixedA(ByteBuffer buf, int pos, int maxChars, int fileLen) {
        if (pos < 0 || pos + maxChars > fileLen) {
            return null;
        }
        byte[] data = buf.array();
        int len = 0;
        while (len < maxChars && data[pos + len] != 0) {
            len++;
        }
        return len == 0 ? null : new String(data, pos, len, cp1252());
    }

    private String readFixedW(ByteBuffer buf, int pos, int maxChars, int fileLen) {
        if (pos < 0 || pos + maxChars * 2 > fileLen) {
            return null;
        }
        byte[] data = buf.array();
        int len = 0;
        while (len < maxChars && (data[pos + len * 2] != 0 || data[pos + len * 2 + 1] != 0)) {
            len++;
        }
        return len == 0 ? null : new String(data, pos, len * 2, StandardCharsets.UTF_16LE);
    }

    private String formatGuid(ByteBuffer buf, int pos) {
        int   data1 = buf.getInt(pos);
        short data2 = buf.getShort(pos + 4);
        short data3 = buf.getShort(pos + 6);
        byte[] b = buf.array();
        return String.format(Locale.ROOT, "{%08X-%04X-%04X-%02X%02X-%02X%02X%02X%02X%02X%02X}",
                data1, Short.toUnsignedInt(data2), Short.toUnsignedInt(data3),
                b[pos + 8] & 0xFF, b[pos + 9] & 0xFF,
                b[pos + 10] & 0xFF, b[pos + 11] & 0xFF, b[pos + 12] & 0xFF,
                b[pos + 13] & 0xFF, b[pos + 14] & 0xFF, b[pos + 15] & 0xFF);
    }

    private String extractMac(ByteBuffer buf, int pos) {
        byte[] b = buf.array();
        if ((b[pos + 10] & 0x01) != 0) {
            return null; // multicast/synthetic MAC
        }
        return String.format(Locale.ROOT, "%02X:%02X:%02X:%02X:%02X:%02X",
                b[pos + 10] & 0xFF, b[pos + 11] & 0xFF, b[pos + 12] & 0xFF,
                b[pos + 13] & 0xFF, b[pos + 14] & 0xFF, b[pos + 15] & 0xFF);
    }

    private String extractUuidV1Timestamp(ByteBuffer buf, int pos) {
        int  timeLow = buf.getInt(pos);
        int  timeMid = Short.toUnsignedInt(buf.getShort(pos + 4));
        int  timeHiV = Short.toUnsignedInt(buf.getShort(pos + 6));
        if (((timeHiV >> 12) & 0xF) != 1) {
            return null; // not UUID v1
        }
        int  timeHi  = timeHiV & 0x0FFF;
        long ticks   = ((long) timeHi << 48) | ((long) timeMid << 32)
                | Integer.toUnsignedLong(timeLow);
        long unixTicks = ticks - 122192928000000000L;
        long epochSecs = unixTicks / 10_000_000L;
        int  nanoAdj   = (int) ((unixTicks % 10_000_000L) * 100L);
        try {
            return Instant.ofEpochSecond(epochSecs, nanoAdj).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Convert Windows FILETIME to ISO-8601. */
    private String filetimeToIso(long filetime) {
        long ticks    = filetime - FILETIME_EPOCH_DIFF_NS100;
        long secs     = ticks / 10_000_000L;
        int  nanoAdj  = (int) ((ticks % 10_000_000L) * 100L);
        try {
            return Instant.ofEpochSecond(secs, nanoAdj).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Convert a 4-byte DOS date+time word (combined) to ISO-8601.
     * DOS time: bits 31-16 = date (year-1980 in 15-9, month 8-5, day 4-0)
     *           bits 15-0  = time (hours 15-11, minutes 10-5, seconds/2 4-0)
     */
    private String dosTimeToIso(int dosDateTime) {
        int dosTime = dosDateTime & 0xFFFF;
        int dosDate = (dosDateTime >> 16) & 0xFFFF;
        return dosDateTimeToIso(dosDate, dosTime);
    }

    private String dosDateTimeToIso(int dosDate, int dosTime) {
        if (dosDate == 0) {
            return null;
        }
        int year    = ((dosDate >> 9) & 0x7F) + 1980;
        int month   = (dosDate >> 5) & 0x0F;
        int day     = dosDate & 0x1F;
        int hours   = (dosTime >> 11) & 0x1F;
        int minutes = (dosTime >> 5) & 0x3F;
        int seconds = (dosTime & 0x1F) * 2;
        if (month < 1 || month > 12 || day < 1 || day > 31) {
            return null;
        }
        try {
            return LocalDateTime.of(year, month, day, hours, minutes, seconds)
                    .toInstant(ZoneOffset.UTC).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Sanitize a name for use as a field-name suffix (strip or replace special chars). */
    private String safeLabel(String name) {
        if (name == null || name.isEmpty()) {
            return "?";
        }
        return name.replaceAll("[^A-Za-z0-9._\\-]", "_");
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
        if (low >= 0x30 && low <= 0x39 || low >= 0x41 && low <= 0x5A) {
            sb.append((char) low);
        } else if (low >= 0x70 && low <= 0x87) {
            sb.append('F').append(low - 0x6F);
        } else {
            sb.append(String.format(Locale.ROOT, "0x%02X", low));
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
        return flags.isEmpty() ? String.format(Locale.ROOT, "0x%08X", attrs)
                : String.join("|", flags);
    }

    private List<String> decodeLinkFlags(int flags) {
        List<String> names = new ArrayList<>();
        if ((flags & FLAG_HAS_TARGET_IDLIST) != 0) {
            names.add("HasTargetIDList");
        }
        if ((flags & FLAG_HAS_LINK_INFO) != 0) {
            names.add("HasLinkInfo");
        }
        if ((flags & 0x00000200) != 0) {
            names.add("HasExpString");
        }
        if ((flags & 0x00002000) != 0) {
            names.add("RunAsUser");
        }
        if ((flags & 0x00040000) != 0) {
            names.add("ForceNoLinkTrack");
        }
        if ((flags & FLAG_IS_UNICODE) != 0) {
            names.add("IsUnicode");
        }
        return names;
    }

    private Charset cp1252() {
        try {
            return Charset.forName("windows-1252");
        } catch (Exception e) {
            return StandardCharsets.ISO_8859_1;
        }
    }
}
