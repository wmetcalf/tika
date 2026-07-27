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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.annotation.TikaComponent;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.EmbeddedDocumentUtil;
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
 * <p>Covers ShellLinkHeader (all fields), IDList traversal with per-item
 * 0xBEEF0004 FileEntry extension blocks (creation/access timestamps, Unicode LFN),
 * root-GUID decoding (My Computer, Control Panel, etc.), LinkInfo (local and
 * network paths, volume identifiers), StringData (all five fields including
 * Arguments padding/obfuscation detection), and the full ExtraData set:
 * TrackerDataBlock (MachineID, MAC address, UUID-v1 timestamp), PropertyStore
 * (MS-OLEPS with typed-value decoder and human-readable property names),
 * EnvironmentVariable/Icon, ConsoleDataBlock (full field set), SpecialFolder,
 * Darwin/AppID, Shim, KnownFolder, VistaIDList.  Terminal-block appended data is
 * SHA-256 hashed and submitted to Tika's embedded-document pipeline for MIME
 * detection and recursive extraction.</p>
 *
 * <p>All string fields are emitted without length caps — truncation hides IOCs.</p>
 *
 * <p>The MS-SHLLINK field layout, IDList shell-item dispatch table, 0xBEEF0004
 * extension block structure (including version-gated LFN offsets), ExtraData block
 * formats, and PropertyStore typed-value decoder were derived from and validated
 * against the following open-source reference implementations, each available
 * under the MIT License:</p>
 * <ul>
 *   <li>EricZimmerman/Lnk — C# implementation
 *       (https://github.com/EricZimmerman/Lnk)</li>
 *   <li>Matmaus/LnkParse3 — Python implementation
 *       (https://github.com/Matmaus/LnkParse3)</li>
 *   <li>wmetcalf/LnkParse3 — forensic fork adding 0xBEEF0004 extension-block
 *       parsing and terminal-block appended-data extraction
 *       (https://github.com/wmetcalf/LnkParse3)</li>
 * </ul>
 * <p>This parser is original Java code written for Apache Tika; no source from
 * those projects was copied.  The implementations above were used as field-layout
 * and offset references against the Microsoft Open Specification MS-SHLLINK.</p>
 */
@TikaComponent
public class WinShortcutParser implements Parser {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(WinShortcutParser.class);

    private static final MediaType LNK_TYPE = MediaType.application("x-ms-shortcut");
    private static final Set<MediaType> SUPPORTED_TYPES = Collections.singleton(LNK_TYPE);

    // ── ShellLinkHeader constants ──────────────────────────────────────────────
    private static final int LNK_MAGIC   = 0x0000004C;
    private static final int HEADER_SIZE = 76;
    private static final int MAX_INPUT_BYTES = 16 * 1024 * 1024;
    private static final int MAX_LINK_INFO_STRING_CHARS = 32 * 1024;
    private static final int MAX_LINK_INFO_TOTAL_STRING_CHARS = 128 * 1024;

    private static final int FLAG_HAS_TARGET_IDLIST  = 0x00000001;
    private static final int FLAG_HAS_LINK_INFO      = 0x00000002;
    private static final int FLAG_HAS_NAME           = 0x00000004;
    private static final int FLAG_HAS_RELATIVE_PATH  = 0x00000008;
    private static final int FLAG_HAS_WORKING_DIR    = 0x00000010;
    private static final int FLAG_HAS_ARGUMENTS      = 0x00000020;
    private static final int FLAG_HAS_ICON_LOCATION  = 0x00000040;
    private static final int FLAG_IS_UNICODE         = 0x00000080;

    // ── ExtraData block signatures (§2.5) ─────────────────────────────────────
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

    // PropertyStore named-property GUID (D5CDD505-...)
    private static final String NAMED_PROP_GUID = "{D5CDD505-2E9C-101B-9397-08002B2CF9AE}";

    // IDList extension block signature
    private static final int SIG_BEEF0004 = 0xBEEF0004;

    // FILETIME epoch offset (100-ns ticks 1601-01-01 → 1970-01-01)
    private static final long FILETIME_EPOCH_DIFF_NS100 = 116444736000000000L;

    // ── Static lookup tables ───────────────────────────────────────────────────

    private static final String[] DRIVE_TYPES = {
        "Unknown", "NoRootDir", "Removable", "Fixed", "Remote", "CDROM", "RAMDisk"
    };

    private static final String[] SHOW_CMD = {"Normal","","","Maximized","","","","MinNoActive"};

    // Well-known root folder GUIDs for 0x1F shell items
    private static final Map<String, String> KNOWN_ROOT_GUIDS = new LinkedHashMap<>();
    static {
        KNOWN_ROOT_GUIDS.put("{20D04FE0-3AEA-1069-A2D8-08002B30309D}", "MyComputer");
        KNOWN_ROOT_GUIDS.put("{26EE0668-A00A-44D7-9371-BEB064C98683}", "ControlPanel");
        KNOWN_ROOT_GUIDS.put("{21EC2020-3AEA-1069-A2DD-08002B30309D}", "ControlPanel");
        KNOWN_ROOT_GUIDS.put("{F02C1A0D-BE21-4350-88B0-7367FC96EF3C}", "Network");
        KNOWN_ROOT_GUIDS.put("{645FF040-5081-101B-9F08-00AA002F954E}", "RecycleBin");
        KNOWN_ROOT_GUIDS.put("{031E4825-7B94-4DC3-B131-E946B44C8DD5}", "Libraries");
        KNOWN_ROOT_GUIDS.put("{B4BFCC3A-DB2C-424C-B029-7FE99A87C641}", "Desktop");
        KNOWN_ROOT_GUIDS.put("{4BD8D571-6D19-48D3-BE97-422220080E43}", "Music");
        KNOWN_ROOT_GUIDS.put("{33E28130-4E1E-4676-835A-98395C3BC3BB}", "Pictures");
        KNOWN_ROOT_GUIDS.put("{18989B1D-99B5-455B-841C-AB7C74E4DDFC}", "Videos");
        KNOWN_ROOT_GUIDS.put("{FDD39AD0-238F-46AF-ADB4-6C85480369C7}", "Documents");
        KNOWN_ROOT_GUIDS.put("{374DE290-123F-4565-9164-39C4925E467B}", "Downloads");
        KNOWN_ROOT_GUIDS.put("{59031A47-3F72-44A7-89C5-5595FE6B30EE}", "UserProfile");
        KNOWN_ROOT_GUIDS.put("{1F3427C8-5C10-4210-AA03-2EE45287D668}", "UserPinned");
        KNOWN_ROOT_GUIDS.put("{76FC4E2D-D6AD-4519-A663-37BD56068185}", "Printers");
        KNOWN_ROOT_GUIDS.put("{724EF170-A42D-4FEF-9F26-B60E846FBA4F}", "Administrative");
        KNOWN_ROOT_GUIDS.put("{9B74B6A3-0DFD-4F11-9E78-5F7800F2E772}", "SyncCenter");
    }

    // ControlPanelCategory labels (type 0x01)
    private static final Map<Integer, String> CP_CATEGORIES = new LinkedHashMap<>();
    static {
        CP_CATEGORIES.put(0, "All Control Panel Items");
        CP_CATEGORIES.put(1, "Appearance and Personalization");
        CP_CATEGORIES.put(2, "Hardware and Sound");
        CP_CATEGORIES.put(3, "Network and Internet");
        CP_CATEGORIES.put(4, "Sound, Speech, and Audio");
        CP_CATEGORIES.put(5, "System and Security");
        CP_CATEGORIES.put(6, "Clock, Language, and Region");
        CP_CATEGORIES.put(7, "Ease of Access");
        CP_CATEGORIES.put(8, "Programs");
        CP_CATEGORIES.put(9, "User Accounts");
        CP_CATEGORIES.put(10, "Mobile PC");
        CP_CATEGORIES.put(11, "Additional Options");
    }

    // CSIDL → human-readable name for SpecialFolderDataBlock
    private static final Map<Integer, String> CSIDL_NAMES = new LinkedHashMap<>();
    static {
        CSIDL_NAMES.put(0x00, "Desktop");
        CSIDL_NAMES.put(0x01, "Internet");
        CSIDL_NAMES.put(0x02, "Programs");
        CSIDL_NAMES.put(0x03, "Controls");
        CSIDL_NAMES.put(0x05, "Personal");
        CSIDL_NAMES.put(0x06, "Favorites");
        CSIDL_NAMES.put(0x07, "Startup");
        CSIDL_NAMES.put(0x08, "Recent");
        CSIDL_NAMES.put(0x09, "SendTo");
        CSIDL_NAMES.put(0x0A, "RecycleBin");
        CSIDL_NAMES.put(0x0B, "StartMenu");
        CSIDL_NAMES.put(0x0D, "MyDocuments");
        CSIDL_NAMES.put(0x0E, "MyMusic");
        CSIDL_NAMES.put(0x0F, "MyVideo");
        CSIDL_NAMES.put(0x10, "DesktopDir");
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

    // Property name dictionary: GUID → (pid → System.Xxx name)
    // Sources: Windows SDK propkey.h, MSDN Shell Properties, libfwsi forensic docs
    private static final Map<String, Map<Integer, String>> PROPERTY_NAMES =
            new LinkedHashMap<>();
    static {
        // {B725F130-47EF-101A-A5F1-02608C9EEBAC} — Shell File System Item properties
        Map<Integer, String> shell = new LinkedHashMap<>();
        shell.put(4,  "System.FileAttributes");
        shell.put(10, "System.ItemType");
        shell.put(11, "System.SFGAOFlags");
        shell.put(12, "System.Size");
        shell.put(14, "System.DateModified");
        shell.put(15, "System.DateCreated");
        shell.put(16, "System.DateAccessed");
        PROPERTY_NAMES.put("{B725F130-47EF-101A-A5F1-02608C9EEBAC}", shell);

        // {F29F85E0-4FF9-1068-AB91-08002B27B3D9} — Summary Information (Office docs)
        Map<Integer, String> summary = new LinkedHashMap<>();
        summary.put(2,  "System.Title");
        summary.put(3,  "System.Subject");
        summary.put(4,  "System.Author");
        summary.put(5,  "System.Keywords");
        summary.put(6,  "System.Comment");
        summary.put(8,  "System.LastSavedBy");
        summary.put(12, "System.ContentStatus");
        summary.put(14, "System.ContentCreated");
        summary.put(15, "System.DateSaved");
        PROPERTY_NAMES.put("{F29F85E0-4FF9-1068-AB91-08002B27B3D9}", summary);

        // {D5CDD505-2E9C-101B-9397-08002B2CF9AE} — custom/named; names come from the
        // string-named property parsing path, not this integer table.

        // {28636AA6-953D-11D2-B5D6-00C04FD918D0} — Query/Search properties
        Map<Integer, String> query = new LinkedHashMap<>();
        query.put(2,  "System.Search.Contents");
        query.put(5,  "System.Search.LastIndexedTotalTime");
        query.put(30, "System.Search.QueryString");
        PROPERTY_NAMES.put("{28636AA6-953D-11D2-B5D6-00C04FD918D0}", query);

        // {446D16B1-8DAD-4870-A748-402EA43D788C} — AppUserModel
        Map<Integer, String> aum = new LinkedHashMap<>();
        aum.put(104, "System.AppUserModel.ID");
        aum.put(5,   "System.AppUserModel.IsDestListSeparator");
        PROPERTY_NAMES.put("{446D16B1-8DAD-4870-A748-402EA43D788C}", aum);

        // {DABD30ED-0043-4789-A7F8-D013A4736622} — Shell link transfer data
        Map<Integer, String> link = new LinkedHashMap<>();
        link.put(100, "System.Link.TargetUrl");
        link.put(101, "System.Link.TargetSFGAOFlags");
        PROPERTY_NAMES.put("{DABD30ED-0043-4789-A7F8-D013A4736622}", link);

        // {46588AE2-4CBC-4338-BBFC-139326986DCE} — GPS location
        Map<Integer, String> gps = new LinkedHashMap<>();
        gps.put(4, "System.GPS.Latitude");
        gps.put(5, "System.GPS.Longitude");
        gps.put(6, "System.GPS.Altitude");
        PROPERTY_NAMES.put("{46588AE2-4CBC-4338-BBFC-139326986DCE}", gps);
    }

    // ── VT_* type constants ────────────────────────────────────────────────────
    private static final int VT_EMPTY    = 0x0000;
    private static final int VT_NULL     = 0x0001;
    private static final int VT_I2       = 0x0002;
    private static final int VT_I4       = 0x0003;
    private static final int VT_R4       = 0x0004;
    private static final int VT_R8       = 0x0005;
    private static final int VT_BOOL     = 0x000B;
    private static final int VT_UI1      = 0x0011;
    private static final int VT_UI2      = 0x0012;
    private static final int VT_UI4      = 0x0013;
    private static final int VT_I8       = 0x0014;
    private static final int VT_UI8      = 0x0015;
    private static final int VT_INT      = 0x0016;
    private static final int VT_UINT     = 0x0017;
    private static final int VT_DECIMAL  = 0x000E;
    private static final int VT_LPSTR    = 0x001E;
    private static final int VT_LPWSTR   = 0x001F;
    private static final int VT_FILETIME = 0x0040;
    private static final int VT_BLOB     = 0x0041;
    private static final int VT_CLSID    = 0x0048;
    private static final int VT_VECTOR   = 0x1000; // OR'd with base type
    private static final int MAX_PROPERTY_VECTOR_ELEMENTS = 4_096;
    private static final int MAX_PROPERTY_VALUE_CHARS = 64 * 1024;
    private static final int MAX_STRUCTURED_FIELDS = 4_096;
    private static final int MAX_IDLIST_COMPONENTS = 4_096;
    private static final int MAX_IDLIST_PATH_CHARS = 64 * 1024;
    private static final int MAX_WARNINGS = 256;

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED_TYPES;
    }

    @Override
    public void parse(TikaInputStream stream, ContentHandler handler, Metadata metadata,
                      ParseContext context) throws IOException, SAXException, TikaException {
        Map<String, String> fields = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        byte[] raw = stream.readNBytes(MAX_INPUT_BYTES);
        boolean inputTruncated = raw.length == MAX_INPUT_BYTES && stream.read() != -1;
        if (raw.length < HEADER_SIZE) {
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (buf.getInt(0) != LNK_MAGIC) {
            return;
        }
        metadata.set(Metadata.CONTENT_TYPE, LNK_TYPE.toString());

        if (inputTruncated) {
            addWarning(warnings,
                    "LNK input exceeded the 16 MiB analysis limit; extraction is incomplete");
            fields.put("ExploitClass",
                    "LNK input extraction incomplete; exploit indicators may be hidden");
        }

        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata, context);
        xhtml.startDocument();

        int pos = HEADER_SIZE;
        try {
            parseHeader(buf, fields);
            int linkFlags = buf.getInt(20);
            boolean unicode = (linkFlags & FLAG_IS_UNICODE) != 0;

            pos = parseIdList(buf, pos, linkFlags, raw.length, fields, warnings);
            pos = parseLinkInfo(buf, pos, linkFlags, raw.length, fields, warnings);
            pos = parseStringData(buf, pos, linkFlags, unicode, raw.length, fields, warnings);
            pos = parseExtraData(buf, pos, raw.length, fields, warnings, xhtml, context);
            parseAppendedData(buf, pos, raw.length, fields, warnings, xhtml, context);
        } catch (Exception e) {
            LOG.warn("Error parsing LNK file: {}", e.getMessage());
            markAnalysisIncomplete(fields, warnings,
                    "parse-error: " + e.getMessage());
        }

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

        // Synthesize resolved command strings — mirrors wmetcalf/LnkParse3 lnk_command
        // and lnk_command_alt (MIT).  Two resolution paths per the fork:
        //
        // ResolvedCommand (lnk_command): LinkInfo-first resolution.
        //   Priority: LocalBasePath > EnvironmentVariableTarget > RelativePath,
        //   preceded by WorkingDir when present (sets execution context).
        //   Reflects the path Windows actually resolves via LinkInfo + volume tracking.
        //
        // AltCommand (lnk_command_alt): IDList-first resolution.
        //   Uses IDListPath (our shell-namespace traversal) or VistaIDListPath.
        //   This is what Windows uses when LinkInfo is absent or stale — the IDList
        //   is the canonical target in modern Windows shortcut resolution.
        synthesizeCommands(fields);

        // Surface every parsed structured field as a typed metadata key so
        // downstream tooling (search, similarity, UI metadata panels) can
        // query by name. Dumping them as plain <p>key: value</p> body text
        // — the prior behaviour — polluted full-text search with field
        // names and made structured access impossible.
        for (Map.Entry<String, String> e : fields.entrySet()) {
            metadata.add("lnk:" + e.getKey(), e.getValue());
            if ("ExploitClass".equals(e.getKey())) {
                metadata.add("ExploitClass", e.getValue());
            }
        }
        for (String w : warnings) {
            metadata.add("lnk:warning", w);
        }
        // Emit a minimal analyst-readable body containing just the resolved
        // command strings. These are the closest thing the LNK has to
        // "content" — the actual command Windows would launch — and useful
        // for OCR-equivalent text scans and similarity-by-body. Everything
        // else is metadata.
        String resolved = fields.get("ResolvedCommand");
        if (resolved != null && !resolved.isEmpty()) {
            xhtml.element("p", resolved);
        }
        String alt = fields.get("AltCommand");
        if (alt != null && !alt.isEmpty() && !alt.equals(resolved)) {
            xhtml.element("p", alt);
        }
        xhtml.endDocument();
    }

    // ── ShellLinkHeader §2.1 ──────────────────────────────────────────────────

    private void parseHeader(ByteBuffer buf, Map<String, String> fields) {
        int linkFlags      = buf.getInt(20);
        int fileAttributes = buf.getInt(24);
        long creationTime  = buf.getLong(28);
        long accessTime    = buf.getLong(36);
        long writeTime     = buf.getLong(44);
        long fileSize      = Integer.toUnsignedLong(buf.getInt(52));
        int  iconIndex     = buf.getInt(56);
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
        if (iconIndex != 0) {
            fields.put("IconIndex", Integer.toString(iconIndex));
        }
        int showIdx = showCommand >= 0 && showCommand < SHOW_CMD.length ? showCommand : 0;
        fields.put("ShowCommand",
                showIdx < SHOW_CMD.length && !SHOW_CMD[showIdx].isEmpty()
                ? SHOW_CMD[showIdx] : "Normal");
        if (hotKey != 0) {
            fields.put("HotKey", decodeHotKey(hotKey));
        }
        if (fileAttributes != 0) {
            fields.put("FileAttributes", decodeFileAttributes(fileAttributes));
        }
        List<String> flagNames = decodeLinkFlags(linkFlags);
        if (!flagNames.isEmpty()) {
            fields.put("LinkFlags", String.join("|", flagNames));
        }
    }

    // ── IDList §2.2 ───────────────────────────────────────────────────────────

    private int parseIdList(ByteBuffer buf, int pos, int linkFlags, int fileLen,
                            Map<String, String> fields, List<String> warnings) {
        if ((linkFlags & FLAG_HAS_TARGET_IDLIST) == 0) {
            return pos;
        }
        if (pos + 2 > fileLen) {
            markAnalysisIncomplete(fields, warnings,
                    "IDList size field extends beyond the available input");
            return fileLen;
        }
        int idListSize = Short.toUnsignedInt(buf.getShort(pos));
        long idListEndLong = (long) pos + 2L + idListSize;
        if (idListEndLong > fileLen) {
            markAnalysisIncomplete(fields, warnings,
                    "IDList extends beyond the available input");
            return fileLen;
        }
        int idListEnd = (int) idListEndLong;
        pos += 2;

        List<String> path = walkIdList(buf, pos, idListEnd, fileLen, fields, warnings);
        if (!path.isEmpty()) {
            fields.put("IDListPath", String.join("\\", path));
        }
        return idListEnd;
    }

    private List<String> walkIdList(ByteBuffer buf, int start, int end, int fileLen,
                                    Map<String, String> fields, List<String> warnings) {
        List<String> path = new ArrayList<>();
        int retainedChars = 0;
        int itemStart = start;
        while (itemStart + 2 < end && itemStart + 2 <= fileLen) {
            if (path.size() >= MAX_IDLIST_COMPONENTS) {
                markAnalysisIncomplete(fields, warnings,
                        "IDList component count exceeded the analysis limit");
                break;
            }
            int itemSize = Short.toUnsignedInt(buf.getShort(itemStart));
            if (itemSize == 0) {
                break;
            }
            int itemEnd = itemStart + itemSize;
            if (itemEnd > end || itemEnd > fileLen) {
                break;
            }
            if (itemSize >= 3) {
                try {
                    String comp = parseShellItem(buf, itemStart, itemSize, itemEnd, fields);
                    if (comp != null && !comp.isEmpty()) {
                        long joinedChars = (long) retainedChars + comp.length()
                                + (path.isEmpty() ? 0 : 1);
                        if (joinedChars > MAX_IDLIST_PATH_CHARS) {
                            markAnalysisIncomplete(fields, warnings,
                                    "IDList path exceeded the output limit");
                            break;
                        }
                        path.add(comp);
                        retainedChars = (int) joinedChars;
                    }
                } catch (Exception e) {
                    addWarning(warnings, "IDList item error: " + e.getMessage());
                }
            }
            itemStart = itemEnd;
        }
        return path;
    }

    private String parseShellItem(ByteBuffer buf, int base, int size, int end,
                                  Map<String, String> fields) {
        if (size < 3) {
            return null;
        }
        int typeIndicator = buf.get(base + 2) & 0xFF;

        // 0x1F — Root GUID (My Computer, Control Panel, etc.)
        // Layout: size(2) + type(1) + sortIndex(1) + CLSID(16)
        if (typeIndicator == 0x1F) {
            if (base + 20 <= end) {
                String guid = formatGuid(buf, base + 4);
                String name = KNOWN_ROOT_GUIDS.get(guid);
                return name != null ? name : "RootFolder:" + guid;
            }
            return null;
        }

        // 0x00 — ControlPanelCPL item
        if (typeIndicator == 0x00 && size >= 10) {
            return parseControlPanelCpl(buf, base, end);
        }

        // 0x01 — ControlPanelCategory item
        if (typeIndicator == 0x01 && size >= 10) {
            if (base + 10 <= end) {
                int categoryId = buf.getInt(base + 6);
                return "ControlPanel:" + CP_CATEGORIES.getOrDefault(
                        categoryId, "Category" + categoryId);
            }
            return "ControlPanel";
        }

        // Drive root (0x23, 0x2E, 0x2F, 0x29)
        if (typeIndicator == 0x2F || typeIndicator == 0x2E
                || typeIndicator == 0x23 || typeIndicator == 0x29) {
            if (base + 4 <= end) {
                char ch = (char) (buf.get(base + 3) & 0xFF);
                if (ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z') {
                    // For 0x23 (volume without letter), try to get volume GUID
                    if (typeIndicator == 0x23 && base + 20 <= end) {
                        String volGuid = formatGuid(buf, base + 4);
                        return "Volume:" + volGuid;
                    }
                    return ch + ":";
                }
            }
            return null;
        }

        // File system folder/file (0x30-0x3F)
        if ((typeIndicator & 0xF0) == 0x30) {
            return parseShellFsFolder(buf, base, size, end, typeIndicator, fields);
        }

        // Network location (0x40-0x47)
        if ((typeIndicator & 0xF0) == 0x40) {
            return parseNetworkShellItem(buf, base, size, end);
        }

        return null;
    }

    private String parseControlPanelCpl(ByteBuffer buf, int base, int end) {
        // Both Unicode (hint at offset 10 == 0x00) and ASCII variants
        // Unicode: sig(4) at +2, NameOffset(2)+CommentsOffset(2) at +18, strings at +22
        // ASCII:   sig(4) at +2, NameOffset(2)+CommentsOffset(2) at +6, strings at +10
        if (base + 22 > end) {
            return "ControlPanelCPL";
        }
        boolean isUnicode = (buf.get(base + 10) & 0xFF) == 0x00;
        String name;
        if (isUnicode && base + 22 <= end) {
            int nameOff = Short.toUnsignedInt(buf.getShort(base + 18));
            int nameAbs = base + nameOff;
            name = nameAbs < end ? readNullTermW(buf, nameAbs, end) : null;
        } else {
            int nameOff = Short.toUnsignedInt(buf.getShort(base + 6));
            int nameAbs = base + nameOff;
            name = nameAbs < end ? readNullTermA(buf, nameAbs, end) : null;
        }
        return name != null && !name.isEmpty() ? "ControlPanel:" + name : "ControlPanelCPL";
    }

    private String parseShellFsFolder(ByteBuffer buf, int base, int size, int end,
                                      int typeIndicator, Map<String, String> fields) {
        if (size < 16) {
            return null;
        }
        boolean itemUnicode = (typeIndicator & 0x04) != 0;
        boolean isFile = (typeIndicator & 0x02) != 0;   // bit 1: file vs folder

        String primaryName = null;
        if (itemUnicode) {
            primaryName = readNullTermW(buf, base + 14, end);
        } else {
            primaryName = readNullTermA(buf, base + 14, end);
        }

        // Modification time
        int modTimeDos = buf.getInt(base + 8);
        if (modTimeDos != 0) {
            String modStr = dosTimeToIso(modTimeDos);
            if (modStr != null) {
                fields.put("IDListModTime[" + safeLabel(primaryName) + "]", modStr);
            }
        }

        // Extension blocks at extOffset (last 2 bytes of item)
        boolean hasBeef0004 = false;
        String longName = null;
        if (size >= 4) {
            int extOffset = Short.toUnsignedInt(buf.getShort(end - 2));
            if (extOffset > 0 && base + extOffset < end - 2) {
                longName = parseBeef0004Extensions(buf, base + extOffset, end - 2,
                        primaryName, fields);
                // Walk the extension chain — a benign block (e.g. 0xBEEF0014) may
                // precede BEEF0004 within the same item.
                hasBeef0004 = chainHasBeef0004(buf, base + extOffset, end - 2);
            }
        }

        // Defensive signal: ANSI-typed FileSystem item targeting a LoLBin executable
        // with NO BEEF0004 Unicode LFN extension. Modern Windows always writes the
        // BEEF0004 block — absence on an executable target indicates a hand-crafted
        // LNK (common in the 2023-2025 LoLBin-via-LNK phishing campaigns).
        if (!itemUnicode && isFile && !hasBeef0004
                && primaryName != null && !primaryName.isEmpty()) {
            String pLower = primaryName.toLowerCase(Locale.ROOT);
            if (LOLBIN_NAMES.contains(pLower) || isExecutableExtension(pLower)) {
                fields.put("idlist:target_ansi_only", "true");
                fields.put("idlist:target_ansi_only_name", primaryName);
                String reason = LOLBIN_NAMES.contains(pLower)
                        ? "hand-crafted LNK targets LoLBin " + primaryName
                          + " via ANSI-only IDList (no BEEF0004 LFN)"
                        : "hand-crafted LNK targets executable " + primaryName
                          + " via ANSI-only IDList (no BEEF0004 LFN)";
                fields.put("ExploitClass", reason);
            }
        }

        if (longName != null && !longName.isEmpty()) {
            return longName;
        }
        return primaryName;
    }

    private static final Set<String> LOLBIN_NAMES = new HashSet<>(Arrays.asList(
            "cmd.exe", "powershell.exe", "powershell_ise.exe", "pwsh.exe",
            "wscript.exe", "cscript.exe", "mshta.exe", "rundll32.exe",
            "regsvr32.exe", "conhost.exe", "hh.exe", "forfiles.exe",
            "certutil.exe", "bitsadmin.exe", "wmic.exe", "msbuild.exe",
            "installutil.exe", "regasm.exe", "regsvcs.exe", "msiexec.exe",
            "syncappvpublishingserver.vbs", "msxsl.exe", "ie4uinit.exe",
            "control.exe", "ftp.exe", "diskshadow.exe",
            "csc.exe", "regedit.exe", "schtasks.exe", "werfault.exe",
            "runonce.exe", "runscripthelper.exe", "pcalua.exe", "cdb.exe",
            "dnscmd.exe", "atbroker.exe", "msdt.exe", "wsl.exe", "bash.exe",
            "extexport.exe", "extrac32.exe", "explorer.exe", "esentutl.exe",
            "expand.exe", "finger.exe", "fltmc.exe", "fsutil.exe",
            "gpscript.exe", "ie4uinit.exe", "infdefaultinstall.exe",
            "jsc.exe", "ldifde.exe", "makecab.exe", "manage-bde.wsf",
            "mavinject.exe", "microsoft.workflow.compiler.exe", "mmc.exe",
            "msdt.exe", "mshta.exe", "msiexec.exe", "msxsl.exe",
            "odbcconf.exe", "pcalua.exe", "pcwrun.exe", "pktmon.exe",
            "presentationhost.exe", "print.exe", "psr.exe", "rasautou.exe",
            "reg.exe", "register-cimprovider.exe", "regini.exe",
            "regsvcs.exe", "replace.exe", "runscripthelper.exe", "scriptrunner.exe",
            "setres.exe", "sftp.exe", "ssh.exe",
            "ttdinject.exe", "tttracer.exe", "vbc.exe", "verclsid.exe",
            "wab.exe", "wlrmdr.exe", "wmiprvse.exe", "wsreset.exe",
            "xwizard.exe", "te.exe", "msconfig.exe", "explorer.exe",
            "appvlp.exe", "bginfo.exe", "cdb.exe", "csi.exe", "devtoolslauncher.exe",
            "dotnet.exe", "dnx.exe", "dxcap.exe", "msxsl.exe", "ntdsutil.exe",
            "powerpnt.exe", "rcsi.exe", "sqldumper.exe", "sqltoolsps.exe",
            "squirrel.exe", "tracker.exe", "update.exe", "vsjitdebugger.exe",
            "wfc.exe", "windbg.exe", "wsl.exe"
    ));

    /** Walk the extension-block chain to determine whether a BEEF0004 entry exists. */
    private static boolean chainHasBeef0004(ByteBuffer buf, int extBase, int extEnd) {
        int pos = extBase;
        while (pos + 8 <= extEnd) {
            int blockSize = Short.toUnsignedInt(buf.getShort(pos));
            if (blockSize < 8 || pos + blockSize > extEnd) {
                return false;
            }
            int sig = buf.getInt(pos + 4);
            if (sig == SIG_BEEF0004) {
                return true;
            }
            pos += blockSize;
        }
        return false;
    }

    private static boolean isExecutableExtension(String pathLower) {
        int dot = pathLower.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        String ext = pathLower.substring(dot);
        return ext.equals(".exe") || ext.equals(".dll") || ext.equals(".scr")
                || ext.equals(".com") || ext.equals(".bat") || ext.equals(".cmd")
                || ext.equals(".ps1") || ext.equals(".vbs") || ext.equals(".js")
                || ext.equals(".jse") || ext.equals(".hta") || ext.equals(".wsf")
                || ext.equals(".msi") || ext.equals(".msp");
    }

    private String parseNetworkShellItem(ByteBuffer buf, int base, int size, int end) {
        // Network location: location string at offset 14 (after header)
        // description at next null-terminated string, comments after that
        String location = null;
        if (base + 15 <= end) {
            location = readNullTermA(buf, base + 14, end);
        }
        if (location != null && !location.isEmpty()) {
            // Try to get description and comments (appear after location string)
            int afterLoc = base + 14 + location.length() + 1;
            if (afterLoc < end) {
                String desc = readNullTermA(buf, afterLoc, end);
                if (desc != null && !desc.isEmpty() && !desc.equals(location)) {
                    return location + " (" + desc + ")";
                }
            }
            return location;
        }
        return null;
    }

    // 0xBEEF0004 FileEntry extension block layout and version-gated LFN offset
    // computation ported from wmetcalf/LnkParse3 (MIT):
    // extension/file_entry.py — long_name() version guard logic.
    private String parseBeef0004Extensions(ByteBuffer buf, int extBase, int extEnd,
                                           String primaryName, Map<String, String> fields) {
        int pos = extBase;
        while (pos + 8 <= extEnd) {
            int blockSize = Short.toUnsignedInt(buf.getShort(pos));
            if (blockSize < 8 || pos + blockSize > extEnd) {
                break;
            }
            int version = Short.toUnsignedInt(buf.getShort(pos + 2));
            int sig     = buf.getInt(pos + 4);

            if (sig == SIG_BEEF0004) {
                if (pos + 12 <= extEnd) {
                    int dosDate = Short.toUnsignedInt(buf.getShort(pos + 10));
                    int dosTime = Short.toUnsignedInt(buf.getShort(pos + 8));
                    String creation = dosDateTimeToIso(dosDate, dosTime);
                    if (creation != null) {
                        fields.put("IDListCreationTime[" + safeLabel(primaryName) + "]",
                                creation);
                    }
                }
                if (pos + 16 <= extEnd) {
                    int dosDate = Short.toUnsignedInt(buf.getShort(pos + 14));
                    int dosTime = Short.toUnsignedInt(buf.getShort(pos + 12));
                    String access = dosDateTimeToIso(dosDate, dosTime);
                    if (access != null) {
                        fields.put("IDListAccessTime[" + safeLabel(primaryName) + "]", access);
                    }
                }
                String longName = null;
                if (version >= 3) {
                    int nameStart = pos + 18 + 2; // +2 unknown
                    if (version >= 7) {
                        nameStart += 18;
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

    // ── LinkInfo §2.3 ─────────────────────────────────────────────────────────

    private int parseLinkInfo(ByteBuffer buf, int pos, int linkFlags, int fileLen,
                              Map<String, String> fields, List<String> warnings) {
        if ((linkFlags & FLAG_HAS_LINK_INFO) == 0) {
            return pos;
        }
        if (pos + 4 > fileLen) {
            markAnalysisIncomplete(fields, warnings,
                    "LinkInfo size field extends beyond the available input");
            return fileLen;
        }
        int linkInfoSize = buf.getInt(pos);
        if (linkInfoSize < 28
                || (long) pos + linkInfoSize > fileLen) {
            markAnalysisIncomplete(fields, warnings,
                    "LinkInfo has an invalid or out-of-bounds size");
            return fileLen;
        }
        try {
            int linkInfoEnd = pos + linkInfoSize;
            int headerSize    = buf.getInt(pos + 4);
            int infoFlags     = buf.getInt(pos + 8);
            int volIdOff      = buf.getInt(pos + 12);
            int localBaseOff  = buf.getInt(pos + 16);
            int netLinkOff    = buf.getInt(pos + 20);
            int pathSuffixOff = buf.getInt(pos + 24);

            int localBaseOffU  = (headerSize >= 0x24 && pos + 32 <= fileLen)
                    ? buf.getInt(pos + 28) : 0;
            int pathSuffixOffU = (headerSize >= 0x28 && pos + 36 <= fileLen)
                    ? buf.getInt(pos + 32) : 0;

            boolean hasLocal   = (infoFlags & 0x01) != 0;
            boolean hasNetwork = (infoFlags & 0x02) != 0;
            LinkInfoStringBudget stringBudget = new LinkInfoStringBudget();

            if (hasLocal) {
                String localPath = (localBaseOffU > 0)
                        ? readLinkInfoStringW(
                                buf, pos + localBaseOffU, linkInfoEnd,
                                stringBudget, fields, warnings) : null;
                if (localPath == null || localPath.isEmpty()) {
                    localPath = readLinkInfoStringA(
                            buf, pos + localBaseOff, linkInfoEnd,
                            stringBudget, fields, warnings);
                }
                if (localPath != null && !localPath.isEmpty()) {
                    fields.put("LocalBasePath", localPath);
                }

                String suffix = (pathSuffixOffU > 0)
                        ? readLinkInfoStringW(
                                buf, pos + pathSuffixOffU, linkInfoEnd,
                                stringBudget, fields, warnings) : null;
                if (suffix == null || suffix.isEmpty()) {
                    suffix = readLinkInfoStringA(
                            buf, pos + pathSuffixOff, linkInfoEnd,
                            stringBudget, fields, warnings);
                }
                if (suffix != null && !suffix.isEmpty()) {
                    fields.put("CommonPathSuffix", suffix);
                }

                if (volIdOff > 0 && pos + volIdOff + 16 <= linkInfoEnd) {
                    parseVolumeId(
                            buf, pos + volIdOff, linkInfoEnd, fields,
                            warnings, stringBudget);
                }
            }

            if (hasNetwork && netLinkOff > 0
                    && pos + netLinkOff + 8 <= linkInfoEnd) {
                parseNetworkLink(
                        buf, pos + netLinkOff, linkInfoEnd, fields,
                        warnings, stringBudget);
            }
        } catch (Exception e) {
            markAnalysisIncomplete(fields, warnings,
                    "LinkInfo error: " + e.getMessage());
        }
        return pos + linkInfoSize;
    }

    private void parseVolumeId(ByteBuffer buf, int base, int fileLen,
                               Map<String, String> fields, List<String> warnings,
                               LinkInfoStringBudget stringBudget) {
        int volSize = buf.getInt(base);
        if (volSize < 16 || (long) base + volSize > fileLen) {
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
                label = readLinkInfoStringW(
                        buf, base + labelOffU, fileLen,
                        stringBudget, fields, warnings);
            }
        }
        if (label == null || label.isEmpty()) {
            label = readLinkInfoStringA(
                    buf, base + labelOff, fileLen,
                    stringBudget, fields, warnings);
        }
        if (label != null && !label.isEmpty()) {
            fields.put("VolumeLabel", label);
        }
    }

    private void parseNetworkLink(ByteBuffer buf, int base, int fileLen,
                                  Map<String, String> fields, List<String> warnings,
                                  LinkInfoStringBudget stringBudget) {
        int size = buf.getInt(base);
        if (size < 20 || (long) base + size > fileLen) {
            return;
        }
        int linkFlags   = buf.getInt(base + 4);
        int netNameOff  = buf.getInt(base + 8);
        int devNameOff  = buf.getInt(base + 12);
        int netProvType = buf.getInt(base + 16);

        int netNameOffU = (netNameOff > 20 && base + 24 <= fileLen) ? buf.getInt(base + 20) : 0;
        int devNameOffU = (netNameOff > 20 && base + 28 <= fileLen) ? buf.getInt(base + 24) : 0;

        String netName = (netNameOffU > 0)
                ? readLinkInfoStringW(
                        buf, base + netNameOffU, fileLen,
                        stringBudget, fields, warnings) : null;
        if (netName == null || netName.isEmpty()) {
            netName = readLinkInfoStringA(
                    buf, base + netNameOff, fileLen,
                    stringBudget, fields, warnings);
        }
        if (netName != null && !netName.isEmpty()) {
            fields.put("NetworkShareName", netName);
        }

        String devName = (devNameOffU > 0)
                ? readLinkInfoStringW(
                        buf, base + devNameOffU, fileLen,
                        stringBudget, fields, warnings) : null;
        if (devName == null || devName.isEmpty()) {
            devName = readLinkInfoStringA(
                    buf, base + devNameOff, fileLen,
                    stringBudget, fields, warnings);
        }
        if (devName != null && !devName.isEmpty()) {
            fields.put("NetworkDeviceName", devName);
        }

        // NetworkProviderType: valid only when Flags bit 1 set
        if ((linkFlags & 0x02) != 0 && netProvType != 0) {
            fields.put("NetworkProviderType",
                    String.format(Locale.ROOT, "0x%08X", netProvType));
        }
    }

    // ── StringData §2.4 ───────────────────────────────────────────────────────

    private int parseStringData(ByteBuffer buf, int pos, int linkFlags, boolean unicode,
                                int fileLen, Map<String, String> fields, List<String> warnings) {
        int[] flagsOrder  = {FLAG_HAS_NAME, FLAG_HAS_RELATIVE_PATH,
                              FLAG_HAS_WORKING_DIR, FLAG_HAS_ARGUMENTS, FLAG_HAS_ICON_LOCATION};
        String[] keyNames = {"Name", "RelativePath", "WorkingDir", "Arguments", "IconLocation"};

        for (int fi = 0; fi < flagsOrder.length; fi++) {
            if ((linkFlags & flagsOrder[fi]) == 0) {
                continue;
            }
            if (pos + 2 > fileLen) {
                markAnalysisIncomplete(fields, warnings,
                        keyNames[fi] + " size field extends beyond the available input");
                return fileLen;
            }
            int charCount = Short.toUnsignedInt(buf.getShort(pos));
            pos += 2;
            // MS-SHLLINK §2.4 makes IsUnicode authoritative for field ownership:
            // COUNT_CHARACTERS occupies charCount*2 bytes for Unicode and charCount
            // bytes for ANSI. Alternate decoding may improve analyst-facing text,
            // but it must never move the structural cursor into following records.
            byte[] data = buf.array();
            int structuralBytes = unicode ? charCount * 2 : charCount;
            if (pos + structuralBytes > fileLen) {
                markAnalysisIncomplete(fields, warnings,
                        keyNames[fi] + " extends beyond the available input");
                return fileLen;
            }
            int unicodeBytes = structuralBytes - (structuralBytes & 1);
            String unicodeStr = unicodeBytes == 0 ? ""
                    : new String(data, pos, unicodeBytes, StandardCharsets.UTF_16LE);
            String ansiStr = structuralBytes == 0 ? ""
                    : new String(data, pos, structuralBytes, cp1252());
            int unicodeScore = unicodeBytes == 0 ? -1 : textScore(unicodeStr);
            int ansiScore = structuralBytes == 0 ? -1 : textScore(ansiStr);
            boolean actualUnicode;
            if (unicodeScore > ansiScore) {
                actualUnicode = true;
            } else if (ansiScore > unicodeScore) {
                actualUnicode = false;
            } else {
                actualUnicode = unicode;  // tie — trust the flag
            }
            // If the flag-claimed encoding is also valid and the loser only beats
            // it by a small margin, prefer the flag (avoid CJK Unicode → ANSI
            // misclassification when both look "fine").
            int margin = Math.abs(unicodeScore - ansiScore);
            int reqMargin = Math.max(2, Math.min(unicodeStr.length(), ansiStr.length()) / 8);
            if (margin < reqMargin) {
                actualUnicode = unicode;
            }
            String value;
            if (actualUnicode) {
                value = unicodeStr;
            } else {
                value = ansiStr;
            }
            pos += structuralBytes;
            if (actualUnicode != unicode && !value.isEmpty()) {
                addWarning(warnings, keyNames[fi] + ": IsUnicode flag=" + unicode
                        + " but content decodes cleanly as "
                        + (actualUnicode ? "UTF-16LE" : "ANSI/cp1252")
                        + " (flag/content mismatch — possible parser-confusion attempt)");
                fields.put(keyNames[fi] + ".Encoding",
                        actualUnicode ? "UTF-16LE" : "ANSI/cp1252");
                fields.put(keyNames[fi] + ".FlagMismatch", "true");
            }
            if (!value.isEmpty()) {
                // Trim trailing NULs (NUL-padded shorter strings)
                int trimEnd = value.length();
                while (trimEnd > 0 && value.charAt(trimEnd - 1) == 0) {
                    trimEnd--;
                }
                if (trimEnd != value.length()) {
                    value = value.substring(0, trimEnd);
                }
                fields.put(keyNames[fi], value);
                // Padding obfuscation: Windows UI truncates Arguments at 260 chars
                if (fi == 3 && value.length() > 260) {
                    String hidden  = value.substring(260);
                    if (!hidden.trim().isEmpty()) {
                        fields.put("Arguments.Hidden", hidden.trim());
                        addWarning(warnings,
                                "Arguments field padded past 260 chars — hidden content detected");
                    }
                }
            }
        }
        return pos;
    }

    private static void markAnalysisIncomplete(Map<String, String> fields,
                                               List<String> warnings,
                                               String warning) {
        addWarning(warnings, warning);
        fields.putIfAbsent("ExploitClass",
                "LNK parsing incomplete; exploit indicators may be hidden");
    }

    private static void addWarning(List<String> warnings, String warning) {
        if (warnings.size() < MAX_WARNINGS) {
            warnings.add(warning);
        }
    }

    /**
     * Score how "text-like" a decoded string is, biased heavily toward ASCII.
     * <p>LNK StringData is virtually always paths, commands, or arguments —
     * Latin text. We weight ASCII printable bytes (+2) far above generic
     * "defined Unicode" codepoints (0) so that an ANSI string misread as
     * UTF-16LE (where every cp1252 pair becomes a CJK-looking codepoint that
     * <em>technically</em> has {@link Character#isLetterOrDigit} = true) does
     * not falsely tie with the correct ASCII decode. Embedded NULs (which
     * appear in every other slot when ANSI bytes are decoded as UTF-16LE) are
     * a strong negative signal.</p>
     * <p>For legitimately non-Latin Unicode content (CJK paths, Cyrillic), the
     * UTF-16LE decode will still beat the cp1252 alternative because cp1252 of
     * the same bytes hits Latin-1 control range with high frequency.</p>
     */
    private static int textScore(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int score = 0;
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == '\t' || c == '\r' || c == '\n') {
                score += 1;
            } else if (c < 0x20) {
                score -= 3;  // NUL contamination from wrong-decode
            } else if (c == 0xFFFD) {
                score -= 3;
            } else if (c < 0x7f) {
                score += 2;  // ASCII printable — strong positive
            } else if (c < 0xA0) {
                score -= 1;  // C1 control range — unlikely in real text
            } else if (c < 0x100) {
                score += 1;  // Extended Latin (cp1252 high range) — plausible
            } else if (Character.isLetterOrDigit(c)) {
                score += 1;  // non-Latin letter/digit (CJK, Cyrillic, etc.)
            } else if (Character.isDefined(c)) {
                score += 0;  // other defined codepoint — neutral
            } else {
                score -= 1;
            }
        }
        return score;
    }

    // ── ExtraData §2.5 ────────────────────────────────────────────────────────
    // Returns the cursor at the terminal block or the first invalid block.

    private int parseExtraData(ByteBuffer buf, int pos, int fileLen,
                               Map<String, String> fields, List<String> warnings,
                               XHTMLContentHandler xhtml, ParseContext context) {
        int consoleFePage = -1;
        while (pos >= 0 && pos <= fileLen - 8) {
            int blockSize = buf.getInt(pos);
            if (blockSize < 4) {
                break;
            }
            if (blockSize < 8) {
                markAnalysisIncomplete(fields, warnings,
                        "ExtraData block has an invalid size " + blockSize);
                break;
            }
            if (blockSize > fileLen - pos) {
                markAnalysisIncomplete(fields, warnings,
                        "ExtraData block extends beyond the available input");
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
                        parseConsoleBlock(buf, pos, blockSize, fileLen, fields, consoleFePage);
                        break;
                    case SIG_TRACKER:
                        parseTrackerBlock(buf, pos, blockSize, fileLen, fields);
                        break;
                    case SIG_CONSOLE_FE:
                        if (blockSize >= 12) {
                            consoleFePage = buf.getInt(pos + 8);
                            fields.put("ConsoleCodePage", Integer.toString(consoleFePage));
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
                        parsePropertyStoreBlock(buf, pos, blockSize, fileLen, fields, warnings);
                        break;
                    case SIG_KNOWN_FOLDER:
                        if (blockSize >= 0x1C && pos + 0x1C <= fileLen) {
                            fields.put("KnownFolderID", formatGuid(buf, pos + 8));
                        }
                        break;
                    case SIG_VISTA_IDLIST:
                        parseVistaIdListBlock(buf, pos, blockSize, fileLen, fields, warnings);
                        break;
                    default:
                        break;
                }
            } catch (Exception e) {
                markAnalysisIncomplete(fields, warnings,
                        "ExtraData sig " + String.format(Locale.ROOT, "0x%08X", sig)
                                + " error: " + e.getMessage());
            }
            pos += blockSize;
        }
        return pos;
    }

    // §2.5.1 / §2.5.7
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

    // §2.5.2 — ConsoleDataBlock (size 0xCC)
    private void parseConsoleBlock(ByteBuffer buf, int pos, int blockSize,
                                   int fileLen, Map<String, String> fields, int codePage) {
        if (blockSize < 0xCC || pos + 0xCC > fileLen) {
            return;
        }
        // FillAttributes: foreground/background colors at +8 (uint16)
        int fillAttr      = Short.toUnsignedInt(buf.getShort(pos + 8));
        int popupAttr     = Short.toUnsignedInt(buf.getShort(pos + 10));
        int screenBufX    = buf.getShort(pos + 12);
        int screenBufY    = buf.getShort(pos + 14);
        int winSizeX      = buf.getShort(pos + 16);
        int winSizeY      = buf.getShort(pos + 18);
        int fontSize       = buf.getInt(pos + 32);
        int fontWeight    = buf.getInt(pos + 40);
        // FaceName: UTF-16LE, 32 chars at +44
        String faceName   = readFixedW(buf, pos + 44, 32, fileLen);
        int cursorSize    = buf.getInt(pos + 108);
        int fullScreen    = buf.getInt(pos + 112);
        int quickEdit     = buf.getInt(pos + 116);
        int insertMode    = buf.getInt(pos + 120);
        int histBufSize   = buf.getInt(pos + 128);

        if (faceName != null && !faceName.isEmpty()) {
            fields.put("ConsoleFaceName", faceName);
        }
        if (fontSize > 0) {
            fields.put("ConsoleFontSize", Integer.toString(fontSize));
        }
        fields.put("ConsoleFontWeight", fontWeight >= 700 ? "Bold" : "Normal");
        fields.put("ConsoleScreenBuffer",
                screenBufX + "x" + screenBufY);
        fields.put("ConsoleWindowSize", winSizeX + "x" + winSizeY);
        if (fullScreen != 0) {
            fields.put("ConsoleFullScreen", "true");
        }
        if (quickEdit != 0) {
            fields.put("ConsoleQuickEdit", "true");
        }
        if (insertMode != 0) {
            fields.put("ConsoleInsertMode", "true");
        }
        fields.put("ConsoleHistoryBufferSize", Integer.toString(histBufSize));
        // FillAttributes decoded: low nibble = foreground, high nibble = background
        fields.put("ConsoleFillAttributes",
                String.format(Locale.ROOT, "0x%04X", fillAttr));
        if (cursorSize > 0 && cursorSize <= 100) {
            fields.put("ConsoleCursorSize",
                    cursorSize <= 25 ? "Small" : cursorSize <= 50 ? "Normal" : "Large");
        }
    }

    // §2.5.5 — SpecialFolderDataBlock
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

    // §2.5.11 — VistaAndAboveIDListDataBlock
    private void parseVistaIdListBlock(ByteBuffer buf, int pos, int blockSize,
                                       int fileLen, Map<String, String> fields,
                                       List<String> warnings) {
        if (blockSize < 10 || pos + 8 > fileLen) {
            return;
        }
        int idListStart = pos + 8;
        int idListEnd   = pos + blockSize;
        List<String> path = walkIdList(buf, idListStart, idListEnd, fileLen,
                fields, warnings);
        if (!path.isEmpty()) {
            fields.put("VistaIDListPath", String.join("\\", path));
        }
    }

    // §2.5.9 — PropertyStoreDataBlock (full MS-OLEPS).
    // SerializedPropertyStorage chain structure, string-named vs integer-named dispatch,
    // and TypedPropertyValue decoding cross-validated against Matmaus/LnkParse3 (MIT)
    // extra/metadata.py and EricZimmerman/Lnk (MIT) ExtensionBlocks/PropertyStore.cs.
    private void parsePropertyStoreBlock(ByteBuffer buf, int pos, int blockSize,
                                         int fileLen, Map<String, String> fields,
                                         List<String> warnings) {
        if (blockSize < 12 || pos + blockSize > fileLen) {
            return;
        }
        int storeEnd = pos + blockSize;
        int spos = pos + 8;
        while (spos + 12 <= storeEnd) {
            int storageSize = buf.getInt(spos);
            if (storageSize < 4) {
                break;
            }
            if (spos + storageSize > storeEnd) {
                break;
            }
            // Version must be 0x53505331 ("SPS1")
            if (buf.getInt(spos + 4) != 0x53505331) {
                spos += storageSize;
                continue;
            }
            String fmtGuid = formatGuid(buf, spos + 8);
            boolean named  = NAMED_PROP_GUID.equals(fmtGuid);
            Map<Integer, String> propNames = PROPERTY_NAMES.getOrDefault(
                    fmtGuid, Collections.emptyMap());
            String prefix = "PropertyStore[" + fmtGuid + "]";

            int vpos = spos + 24;
            int sEnd = spos + storageSize;
            while (vpos + 8 <= sEnd) {
                if (fields.size() >= MAX_STRUCTURED_FIELDS) {
                    markAnalysisIncomplete(fields, warnings,
                            "PropertyStore field count exceeded the analysis limit");
                    return;
                }
                int valueSize = buf.getInt(vpos);
                if (valueSize < 4) {
                    break;
                }
                if (vpos + valueSize > sEnd) {
                    break;
                }
                try {
                    if (named) {
                        parseStringNamedProp(
                                buf, vpos, valueSize, sEnd, prefix, fields, warnings);
                    } else {
                        parseIntNamedProp(buf, vpos, valueSize, sEnd, prefix,
                                propNames, fields, warnings);
                    }
                } catch (Exception e) {
                    addWarning(warnings,
                            "PropertyStore parse error: " + e.getMessage());
                }
                vpos += valueSize;
            }
            spos += storageSize;
        }
    }

    private void parseStringNamedProp(ByteBuffer buf, int pos, int valueSize,
                                      int limit, String prefix,
                                      Map<String, String> fields,
                                      List<String> warnings) {
        int valueLimit = (int) Math.min((long) limit, (long) pos + valueSize);
        if (pos + 9 > valueLimit) {
            return;
        }
        int nameSize = buf.getInt(pos + 4);
        if (buf.get(pos + 8) != 0 || nameSize < 0
                || (long) pos + 9 + nameSize > valueLimit) {
            return;
        }
        if (nameSize > (MAX_PROPERTY_VALUE_CHARS + 1) * 2) {
            markAnalysisIncomplete(fields, warnings,
                    "PropertyStore property name exceeded the output limit");
            return;
        }
        String name = nameSize > 0
                ? new String(buf.array(), pos + 9, nameSize, StandardCharsets.UTF_16LE) : "";
        int nullIdx = name.indexOf('\0');
        if (nullIdx >= 0) {
            name = name.substring(0, nullIdx);
        }
        int typedStart = pos + 9 + nameSize;
        String value =
                readTypedPropertyValue(buf, typedStart, valueLimit, fields, warnings);
        if (value != null && !name.isEmpty()) {
            fields.put(prefix + "[" + name + "]", value);
        }
    }

    private void parseIntNamedProp(ByteBuffer buf, int pos, int valueSize,
                                   int limit, String prefix,
                                   Map<Integer, String> propNames,
                                   Map<String, String> fields,
                                   List<String> warnings) {
        int valueLimit = (int) Math.min((long) limit, (long) pos + valueSize);
        if (pos + 9 > valueLimit) {
            return;
        }
        int id = buf.getInt(pos + 4);
        if (buf.get(pos + 8) != 0) {
            return;
        }
        String value =
                readTypedPropertyValue(buf, pos + 9, valueLimit, fields, warnings);
        if (value != null) {
            String keyName = propNames.getOrDefault(id, String.valueOf(id));
            fields.put(prefix + "[" + keyName + "]", value);
        }
    }

    private String readTypedPropertyValue(ByteBuffer buf, int pos, int limit,
                                          Map<String, String> fields,
                                          List<String> warnings) {
        if (pos + 4 > limit) {
            return null;
        }
        int vtype = Short.toUnsignedInt(buf.getShort(pos));
        if (buf.getShort(pos + 2) != 0) {
            return null;
        }
        int dataPos = pos + 4;

        // Handle VT_VECTOR (0x1000 | base type) — iterate and join
        if ((vtype & VT_VECTOR) != 0) {
            int baseType = vtype & ~VT_VECTOR;
            if (dataPos + 4 > limit) {
                return null;
            }
            int count = buf.getInt(dataPos);
            dataPos += 4;
            if (count < 0) {
                markAnalysisIncomplete(
                        fields, warnings, "PropertyStore vector has a negative element count");
                return null;
            }
            int acceptedCount = Math.min(count, MAX_PROPERTY_VECTOR_ELEMENTS);
            if (count > acceptedCount) {
                markAnalysisIncomplete(fields, warnings,
                        "PropertyStore vector exceeded the element limit; extraction is incomplete");
            }
            StringBuilder items = new StringBuilder();
            for (int i = 0; i < acceptedCount; i++) {
                String element;
                // For VT_LPWSTR vector each element is a counted string (uint32 + UTF-16LE)
                if (baseType == VT_LPWSTR) {
                    if (dataPos + 4 > limit) {
                        markAnalysisIncomplete(fields, warnings,
                                "PropertyStore string vector ended before its declared count");
                        break;
                    }
                    int len = buf.getInt(dataPos);
                    dataPos += 4;
                    long stringBytes = (long) len * 2;
                    if (len < 0 || stringBytes > limit - dataPos) {
                        markAnalysisIncomplete(fields, warnings,
                                "PropertyStore string vector has an invalid element length");
                        break;
                    }
                    if (len > MAX_PROPERTY_VALUE_CHARS + 1) {
                        markAnalysisIncomplete(fields, warnings,
                                "PropertyStore string vector exceeded the output limit; "
                                        + "extraction is incomplete");
                        break;
                    }
                    String value = new String(buf.array(), dataPos, (int) stringBytes,
                            StandardCharsets.UTF_16LE);
                    int nullIndex = value.indexOf('\0');
                    element = nullIndex >= 0 ? value.substring(0, nullIndex) : value;
                    dataPos += (int) stringBytes;
                } else {
                    // For fixed-size scalars, decode each element
                    int elementBytes = vtElementSize(baseType);
                    if (elementBytes <= 0 || dataPos + elementBytes > limit) {
                        markAnalysisIncomplete(fields, warnings,
                                "PropertyStore vector ended before its declared count");
                        break;
                    }
                    element = readTypedPropertyValueAt(
                            buf, dataPos, baseType, limit, fields, warnings);
                    dataPos += elementBytes;
                }
                if (element != null
                        && !appendPropertyVectorItem(items, element, fields, warnings)) {
                    break;
                }
            }
            return items.length() == 0 ? null : items.toString();
        }

        return readTypedPropertyValueAt(buf, dataPos, vtype, limit, fields, warnings);
    }

    private boolean appendPropertyVectorItem(StringBuilder items, String value,
                                             Map<String, String> fields,
                                             List<String> warnings) {
        int separatorChars = items.length() == 0 ? 0 : 2;
        if (value.length() > MAX_PROPERTY_VALUE_CHARS - items.length() - separatorChars) {
            markAnalysisIncomplete(fields, warnings,
                    "PropertyStore vector exceeded the output limit; extraction is incomplete");
            return false;
        }
        if (separatorChars != 0) {
            items.append(", ");
        }
        items.append(value);
        return true;
    }

    private String readTypedPropertyValueAt(ByteBuffer buf, int pos, int vtype, int limit,
                                            Map<String, String> fields,
                                            List<String> warnings) {
        switch (vtype) {
            case VT_EMPTY:
            case VT_NULL:
                return null;
            case VT_I2:
                return pos + 2 > limit ? null : Integer.toString(buf.getShort(pos));
            case VT_I4:
            case VT_INT:
                return pos + 4 > limit ? null : Integer.toString(buf.getInt(pos));
            case VT_UI1:
                return pos + 1 > limit ? null
                        : Integer.toString(Byte.toUnsignedInt(buf.get(pos)));
            case VT_UI2:
                return pos + 2 > limit ? null
                        : Integer.toString(Short.toUnsignedInt(buf.getShort(pos)));
            case VT_UI4:
            case VT_UINT:
                return pos + 4 > limit ? null
                        : Long.toString(Integer.toUnsignedLong(buf.getInt(pos)));
            case VT_I8:
                return pos + 8 > limit ? null : Long.toString(buf.getLong(pos));
            case VT_UI8:
                return pos + 8 > limit ? null : Long.toUnsignedString(buf.getLong(pos));
            case VT_R4:
                return pos + 4 > limit ? null
                        : String.format(Locale.ROOT, "%g", buf.getFloat(pos));
            case VT_R8:
                return pos + 8 > limit ? null
                        : String.format(Locale.ROOT, "%g", buf.getDouble(pos));
            case VT_DECIMAL:
                // 16-byte DECIMAL: scale(1) + sign(1) + hi32(4) + lo64(8)
                if (pos + 14 > limit) {
                    return null;
                }
                long hi32 = Integer.toUnsignedLong(buf.getInt(pos + 2));
                long lo64 = buf.getLong(pos + 6);
                return "Decimal(" + hi32 + "," + lo64 + ")";
            case VT_BOOL:
                return pos + 2 > limit ? null : (buf.getShort(pos) != 0 ? "true" : "false");
            case VT_LPSTR:
                return readBoundedPropertyStringA(buf, pos, limit, fields, warnings);
            case VT_LPWSTR:
                return readBoundedPropertyStringW(buf, pos, limit, fields, warnings);
            case VT_FILETIME:
                if (pos + 8 > limit) {
                    return null;
                }
                long ft = buf.getLong(pos);
                return ft == 0 ? null : filetimeToIso(ft);
            case VT_BLOB:
                if (pos + 4 > limit) {
                    return null;
                }
                int blobSize = buf.getInt(pos);
                return "Blob(" + blobSize + " bytes)";
            case VT_CLSID:
                return pos + 16 > limit ? null : formatGuid(buf, pos);
            default:
                return null;
        }
    }

    private String readBoundedPropertyStringA(ByteBuffer buf, int pos, int limit,
                                              Map<String, String> fields,
                                              List<String> warnings) {
        int boundedLimit = (int) Math.min((long) limit,
                (long) pos + MAX_PROPERTY_VALUE_CHARS + 1);
        String value = readNullTermA(buf, pos, boundedLimit);
        if (boundedLimit < limit && boundedLimit > pos
                && buf.array()[boundedLimit - 1] != 0) {
            markAnalysisIncomplete(fields, warnings,
                    "PropertyStore string exceeded the output limit");
        }
        return value;
    }

    private String readBoundedPropertyStringW(ByteBuffer buf, int pos, int limit,
                                              Map<String, String> fields,
                                              List<String> warnings) {
        int boundedLimit = (int) Math.min((long) limit,
                (long) pos + (MAX_PROPERTY_VALUE_CHARS + 1L) * 2);
        String value = readNullTermW(buf, pos, boundedLimit);
        if (boundedLimit < limit && boundedLimit - pos >= 2
                && (buf.array()[boundedLimit - 2] != 0
                || buf.array()[boundedLimit - 1] != 0)) {
            markAnalysisIncomplete(fields, warnings,
                    "PropertyStore wide string exceeded the output limit");
        }
        return value;
    }

    /** Returns the fixed byte size of a scalar VT_* type, or -1 if variable/unknown. */
    private int vtElementSize(int vtype) {
        switch (vtype) {
            case VT_I2:  case VT_UI2:  case VT_BOOL: return 2;
            case VT_I4:  case VT_UI4:  case VT_INT:
            case VT_UINT: case VT_R4:               return 4;
            case VT_I8:  case VT_UI8: case VT_R8:
            case VT_FILETIME:                        return 8;
            case VT_DECIMAL: case VT_CLSID:         return 16;
            case VT_UI1:                             return 1;
            default:                                 return -1;
        }
    }

    // ── Command synthesis (lnk_command / lnk_command_alt) ────────────────────
    // Mirrors wmetcalf/LnkParse3 (MIT) lnk_command and lnk_command_alt properties.

    private void synthesizeCommands(Map<String, String> fields) {
        String args     = fields.get("Arguments");
        String workDir  = fields.get("WorkingDir");
        String relPath  = fields.get("RelativePath");

        // lnk_command — LinkInfo-first: LocalBasePath > EnvVar > IDListPath > RelativePath
        // IDListPath is preferred over RelativePath since it gives an absolute path.
        String linkInfoTarget = fields.get("LocalBasePath");
        if (linkInfoTarget == null) {
            linkInfoTarget = fields.get("EnvironmentVariableTarget");
        }
        if (linkInfoTarget == null) {
            // Fall back to IDList-derived path only if it resolves to a real filesystem
            // path (contains a drive letter). Virtual paths (ControlPanel\..., etc.) are
            // not meaningful as a command and are skipped.
            String idp = fields.get("IDListPath");
            if (idp != null) {
                int bs = idp.indexOf('\\');
                String candidate = (bs > 0 && !idp.substring(0, bs).contains(":"))
                        ? idp.substring(bs + 1) : idp;
                if (candidate.length() >= 3 && candidate.charAt(1) == ':') {
                    linkInfoTarget = candidate;
                }
            }
        }
        if (linkInfoTarget == null) {
            linkInfoTarget = relPath;
        }
        if (linkInfoTarget != null) {
            StringBuilder cmd = new StringBuilder();
            if (workDir != null) {
                cmd.append(workDir).append(" > ");
            }
            cmd.append(linkInfoTarget);
            if (args != null && !args.isEmpty()) {
                cmd.append(' ').append(args);
            }
            fields.put("ResolvedCommand", cmd.toString());
        }

        // lnk_command_alt — IDList-first: IDListPath or VistaIDListPath
        String idListTarget = fields.get("VistaIDListPath");
        if (idListTarget == null) {
            idListTarget = fields.get("IDListPath");
        }
        if (idListTarget != null) {
            // Strip virtual root prefix (MyComputer\, ControlPanel\, etc.) so the
            // result is a plain filesystem path matching the Windows resolver output.
            int backslash = idListTarget.indexOf('\\');
            if (backslash > 0 && !idListTarget.substring(0, backslash).contains(":")) {
                idListTarget = idListTarget.substring(backslash + 1);
            }
            // Only emit if this is a real filesystem path (drive letter present)
            if (idListTarget.length() < 3 || idListTarget.charAt(1) != ':') {
                return;
            }
            StringBuilder alt = new StringBuilder(idListTarget);
            if (args != null && !args.isEmpty()) {
                alt.append(' ').append(args);
            }
            String resolved = fields.get("ResolvedCommand");
            // Only emit AltCommand when it differs from ResolvedCommand
            if (resolved == null || !alt.toString().equals(resolved)) {
                fields.put("AltCommand", alt.toString());
            }
        }
    }

    // ── Exploit indicator detection ───────────────────────────────────────────
    //
    // Two layers:
    //
    // 1. Structural (detectStructuralExploits): checks LNK file-structure properties
    //    rather than payload content.  CVE-2026-21513 is detected here because the
    //    vulnerable Windows code path is triggered by the LNK structure alone — a
    //    virtual-folder IDList decoy combined with appended HTML causes Explorer to
    //    render the HTML via MSHTML without applying MotW or IE ESC restrictions,
    //    regardless of what the HTML contains.
    //
    // 2. Content-based (detectExploitIndicators): raw-byte scan for patterns that
    //    are meaningful independent of LNK structure (e.g., SmartScreen-bypass path
    //    patterns, generic MSHTML execution primitives).
    //
    // Structural detection runs last and overwrites content-based results when both
    // match, because structural is always more specific.

    private static final List<ExploitSignature> EXPLOIT_SIGNATURES;
    private static final Map<String, Integer> ASCII_HTML_ENTITIES = Map.of(
            "amp;", (int) '&',
            "apos;", (int) '\'',
            "bsol;", (int) '\\',
            "colon;", (int) ':',
            "gt;", (int) '>',
            "lpar;", (int) '(',
            "lt;", (int) '<',
            "NewLine;", (int) '\n',
            "period;", (int) '.',
            "quot;", (int) '"');
    private static final int SKIP_ASCII = -2;
    private static final int RESET_ASCII = -3;
    private static final long INCOMPLETE_JSCRIPT_JOIN = -2L;
    private static final int MAX_JSCRIPT_JOIN_GROUPING = 8;
    private static final String INCOMPLETE_JSCRIPT_JOIN_WARNING =
            "JScript constant-join analysis could not complete within parser limits";
    static {
        EXPLOIT_SIGNATURES = new ArrayList<>();

        // CVE-2024-21412: Windows SmartScreen / Internet Shortcut bypass.
        // LNK with UNC-path arguments containing a .url reference bypasses MotW.
        // Signature: search-ms: protocol or .url extension in appended data.
        EXPLOIT_SIGNATURES.add(new ExploitSignature(
                "CVE-2024-21412",
                "Windows SmartScreen bypass via crafted Internet Shortcut file",
                new String[]{"search-ms:", ".url"}
        ));

        // Generic: ActiveX in LNK appended HTML (not specific CVE but high-risk pattern)
        EXPLOIT_SIGNATURES.add(new ExploitSignature(
                null,
                "ActiveXObject in LNK appended HTML payload — MSHTML/IE execution technique",
                new String[]{"activexobject"}
        ));

        // Generic: execScript (IE-only — strong signal of MSHTML-targeted payload)
        EXPLOIT_SIGNATURES.add(new ExploitSignature(
                null,
                "execScript() in LNK appended payload — IE-only function, MSHTML exploit indicator",
                new String[]{"execscript("}
        ));
    }

    private static final class ExploitSignature {
        final String cve;
        final String description;
        final String[] allOf;

        ExploitSignature(String cve, String description, String[] allOf) {
            this.cve = cve;
            this.description = description;
            this.allOf = allOf;
        }
    }

    private static final class ExploitScanState {
        private final int[][] incompleteOffsets = {
                {Integer.MAX_VALUE, Integer.MAX_VALUE},
                {Integer.MAX_VALUE, Integer.MAX_VALUE},
                {Integer.MAX_VALUE, Integer.MAX_VALUE}
        };
        private boolean incompleteJScriptJoin;

        private void markIncompleteJScriptJoin(
                int encoding, int alignment, int offset) {
            incompleteJScriptJoin = true;
            incompleteOffsets[encoding][alignment] =
                    Math.min(incompleteOffsets[encoding][alignment], offset);
        }

        private int incompleteOffset(int encoding, int alignment) {
            return incompleteOffsets[encoding][alignment];
        }
    }

    private static String sniffMimeType(byte[] bytes) {
        try (TikaInputStream tis = TikaInputStream.get(bytes)) {
            return new DefaultDetector()
                    .detect(tis, new Metadata(), new ParseContext())
                    .getBaseType().toString();
        } catch (Exception e) {
            return "application/octet-stream";
        }
    }

    // CVE-2026-21513: MSHTML Framework Security Feature Bypass (CVSS 8.8).
    // The vulnerability lives in the LNK structure, not the script payload:
    // Explorer renders appended HTML via MSHTML when the IDList resolves only
    // to a virtual shell-namespace folder (no real filesystem target), bypassing
    // MotW and IE ESC entirely.  Detecting content keywords would miss variants
    // and misattribute the root cause — the trigger is structural.
    //
    // Structural signature:
    //   • IDList present, resolves to virtual folder (no drive-letter path)
    //   • No real target from LinkInfo (LocalBasePath + NetworkShareName both absent)
    //   • Appended data is text/html
    //
    // Ref: MS-SHLLINK §2.1 HasTargetIDList, §2.3 IDList, §2.4 LinkInfo
    //      Patch: Feb 2026 Patch Tuesday; exploited in the wild by APT28 as zero-day
    private static void detectStructuralExploits(boolean htmlPayload,
                                                  Map<String, String> fields,
                                                  List<String> warnings) {
        if (!htmlPayload) {
            return;
        }
        String idListPath = fields.get("IDListPath");
        boolean virtualFolderDecoy =
                idListPath != null
                && !idListPath.matches(".*[A-Za-z]:\\\\.*")
                && fields.get("LocalBasePath") == null
                && fields.get("NetworkShareName") == null;
        if (virtualFolderDecoy) {
            fields.put("ExploitCVE", "CVE-2026-21513");
            fields.put("ExploitClass",
                    "LNK virtual-folder decoy + appended HTML rendered by MSHTML "
                    + "without MotW restrictions — MSHTML Framework Security Feature "
                    + "Bypass (APT28 zero-day, Feb 2026 Patch Tuesday, CVSS 8.8)");
        }
    }

    private static boolean isStructurallyRenderableHtml(
            String appendedMime, byte[] payload) {
        if ("text/html".equals(appendedMime)) {
            return true;
        }
        for (int encoding = 0; encoding < 3; encoding++) {
            if (containsEncodedHtmlElement(payload, encoding)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsEncodedHtmlElement(byte[] payload, int encoding) {
        int stride = encoding == 0 ? 1 : 2;
        for (int alignment = 0; alignment < stride; alignment++) {
            for (int offset = alignment; offset + stride <= payload.length;
                    offset += stride) {
                if (readEncodedAscii(payload, offset, encoding) != '<') {
                    continue;
                }
                int nameOffset = offset + stride;
                if (readEncodedAscii(payload, nameOffset, encoding) == '/') {
                    nameOffset += stride;
                }
                if (matchesEncodedHtmlTagName(
                        payload, nameOffset, encoding)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesEncodedHtmlTagName(
            byte[] payload, int offset, int encoding) {
        int first = lowerAscii(readEncodedAscii(payload, offset, encoding));
        if (first == '!') {
            return matchesEncodedHtmlTagName(
                    payload, offset, encoding, "!doctype");
        }
        if (first < 'a' || first > 'z') {
            return false;
        }
        int stride = encoding == 0 ? 1 : 2;
        for (int i = 1; i <= 64; i++) {
            int value = lowerAscii(readEncodedAscii(
                    payload, offset + i * stride, encoding));
            if (value < 0 || value == '>' || value == '/'
                    || Character.isWhitespace((char) value)) {
                return true;
            }
            if (!((value >= 'a' && value <= 'z')
                    || (value >= '0' && value <= '9')
                    || value == '-' || value == '_' || value == ':')) {
                return false;
            }
        }
        return false;
    }

    private static boolean matchesEncodedHtmlTagName(
            byte[] payload, int offset, int encoding, String tagName) {
        int stride = encoding == 0 ? 1 : 2;
        for (int i = 0; i < tagName.length(); i++) {
            int value = readEncodedAscii(
                    payload, offset + i * stride, encoding);
            if (lowerAscii(value) != tagName.charAt(i)) {
                return false;
            }
        }
        int boundary = readEncodedAscii(
                payload, offset + tagName.length() * stride, encoding);
        return boundary < 0 || boundary == '>' || boundary == '/'
                || Character.isWhitespace((char) boundary);
    }

    private void detectExploitIndicators(byte[] payload, Map<String, String> fields,
                                         List<String> warnings) {
        if (payload == null || payload.length == 0) {
            return;
        }
        // The parser already bounds the complete input, so scan every retained
        // appended byte rather than letting padding or a misleading encoding
        // prefix hide late indicators. Match the ASCII signatures directly in
        // each supported encoding to avoid allocating multiple full-size strings.

        List<String> matchedCves = new ArrayList<>();
        List<String> matchedDescs = new ArrayList<>();
        ExploitScanState scanState = new ExploitScanState();

        for (ExploitSignature sig : EXPLOIT_SIGNATURES) {
            boolean allMatch = false;
            for (int encoding = 0; encoding < 3; encoding++) {
                allMatch = true;
                for (String term : sig.allOf) {
                    if (!containsEncodedAsciiTerm(
                            payload, term, encoding, scanState)) {
                        allMatch = false;
                        break;
                    }
                }
                if (allMatch) {
                    break;
                }
            }
            if (allMatch) {
                if (sig.cve != null && !matchedCves.contains(sig.cve)) {
                    matchedCves.add(sig.cve);
                }
                matchedDescs.add(sig.description);
            }
        }

        if (!matchedCves.isEmpty()) {
            fields.put("ExploitCVE", String.join(", ", matchedCves));
        }
        if (!matchedDescs.isEmpty()) {
            // Use the most specific description (first match is most specific)
            fields.put("ExploitClass", matchedDescs.get(0));
            if (matchedDescs.size() > 1) {
                // Surface additional indicators as warnings
                for (String desc : matchedDescs.subList(1, matchedDescs.size())) {
                    addWarning(warnings, "ExploitIndicator: " + desc);
                }
            }
        }
        if (scanState.incompleteJScriptJoin) {
            if (!warnings.contains(INCOMPLETE_JSCRIPT_JOIN_WARNING)) {
                addWarning(warnings, INCOMPLETE_JSCRIPT_JOIN_WARNING);
            }
            fields.putIfAbsent("ExploitClass",
                    "LNK script-indicator analysis incomplete; executable "
                            + "content may be hidden");
        }
    }

    private static boolean containsEncodedAsciiTerm(byte[] payload, String term,
                                                    int encoding) {
        return containsEncodedAsciiTerm(
                payload, term, encoding, new ExploitScanState());
    }

    private static boolean containsEncodedAsciiTerm(
            byte[] payload, String term, int encoding, ExploitScanState scanState) {
        int stride = encoding == 0 ? 1 : 2;
        int alignments = stride;
        int[] failure = buildFailureTable(term);
        for (int alignment = 0; alignment < alignments; alignment++) {
            int matched = 0;
            for (int offset = alignment; offset + stride <= payload.length;
                    offset += stride) {
                if (offset >= scanState.incompleteOffset(encoding, alignment)) {
                    break;
                }
                long normalized = readHtmlNormalizedAscii(payload, offset, encoding);
                int value = (int) normalized;
                int consumedCodeUnits = (int) (normalized >>> 32);
                if (value == '\\') {
                    long escaped = decodeAsciiEscape(payload, offset, encoding);
                    if (escaped >= 0) {
                        value = (int) escaped;
                        consumedCodeUnits = (int) (escaped >>> 32);
                    }
                }
                if (matched > 0 && (value == '\'' || value == '"')) {
                    long stringJoin = decodeJScriptStringJoin(payload, offset, encoding);
                    if (stringJoin == INCOMPLETE_JSCRIPT_JOIN) {
                        scanState.markIncompleteJScriptJoin(
                                encoding, alignment, offset);
                        break;
                    }
                    if (stringJoin >= 0) {
                        value = SKIP_ASCII;
                        consumedCodeUnits = (int) (stringJoin >>> 32);
                        if ((int) stringJoin == RESET_ASCII) {
                            value = RESET_ASCII;
                        }
                    }
                }
                offset += (consumedCodeUnits - 1) * stride;
                if (value == SKIP_ASCII) {
                    continue;
                }
                if (value == RESET_ASCII) {
                    matched = 0;
                    continue;
                }
                value = lowerAscii(value);
                while (matched > 0 && value != term.charAt(matched)) {
                    matched = failure[matched - 1];
                }
                if (value == term.charAt(matched)) {
                    matched++;
                    if (matched == term.length()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static long readHtmlNormalizedAscii(byte[] payload, int offset,
                                                int encoding) {
        int value = readEncodedAscii(payload, offset, encoding);
        if (value != '&') {
            return packAscii(value, 1);
        }

        int stride = encoding == 0 ? 1 : 2;
        int cursor = offset + stride;
        if (readEncodedAscii(payload, cursor, encoding) != '#') {
            for (Map.Entry<String, Integer> entity : ASCII_HTML_ENTITIES.entrySet()) {
                if (matchesEncodedAscii(payload, cursor, encoding, entity.getKey())) {
                    return packAscii(entity.getValue(), entity.getKey().length() + 1);
                }
            }
            return packAscii(value, 1);
        }
        cursor += stride;

        int radix = 10;
        int radixMarker = readEncodedAscii(payload, cursor, encoding);
        if (radixMarker == 'x' || radixMarker == 'X') {
            radix = 16;
            cursor += stride;
        }
        int digitCount = 0;
        int decoded = 0;
        while (cursor + stride <= payload.length) {
            int encoded = readEncodedAscii(payload, cursor, encoding);
            int digit = radix == 16 ? hexValue(encoded) : decimalValue(encoded);
            if (digit < 0) {
                break;
            }
            digitCount++;
            if (decoded <= 0x7f) {
                decoded = decoded > (0x7f - digit) / radix
                        ? 0x80 : decoded * radix + digit;
            }
            cursor += stride;
        }
        if (digitCount == 0) {
            return packAscii(value, 1);
        }
        if (readEncodedAscii(payload, cursor, encoding) == ';') {
            cursor += stride;
        }
        int consumedCodeUnits = (cursor - offset) / stride;
        return packAscii(decoded <= 0x7f ? decoded : -1,
                consumedCodeUnits);
    }

    private static boolean matchesEncodedAscii(byte[] payload, int offset,
                                               int encoding, String expected) {
        int stride = encoding == 0 ? 1 : 2;
        for (int i = 0; i < expected.length(); i++) {
            if (readEncodedAscii(payload, offset + i * stride, encoding)
                    != expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int readEncodedAscii(byte[] payload, int offset, int encoding) {
        int stride = encoding == 0 ? 1 : 2;
        if (offset < 0 || offset + stride > payload.length) {
            return -1;
        }
        if (encoding == 0) {
            int value = payload[offset] & 0xff;
            return value <= 0x7f ? value : -1;
        }
        int characterOffset = encoding == 1 ? offset : offset + 1;
        int zeroOffset = encoding == 1 ? offset + 1 : offset;
        int value = payload[characterOffset] & 0xff;
        return payload[zeroOffset] == 0 && value <= 0x7f ? value : -1;
    }

    private static long decodeAsciiEscape(byte[] payload, int offset, int encoding) {
        int stride = encoding == 0 ? 1 : 2;
        long slash = readHtmlNormalizedAscii(payload, offset, encoding);
        int consumedCodeUnits = (int) (slash >>> 32);
        int cursor = offset + consumedCodeUnits * stride;
        long markerValue = readHtmlNormalizedAscii(payload, cursor, encoding);
        int marker = (int) markerValue;
        int markerUnits = (int) (markerValue >>> 32);
        cursor += markerUnits * stride;
        consumedCodeUnits += markerUnits;

        int digits;
        int radix;
        if (marker == 'u') {
            digits = 4;
            radix = 16;
        } else if (marker == 'x') {
            digits = 2;
            radix = 16;
        } else if (marker >= '0' && marker <= '7') {
            digits = 3;
            radix = 8;
        } else if (marker == '\r' || marker == '\n') {
            if (marker == '\r') {
                long nextValue = readHtmlNormalizedAscii(payload, cursor, encoding);
                if ((int) nextValue == '\n') {
                    int nextUnits = (int) (nextValue >>> 32);
                    consumedCodeUnits += nextUnits;
                }
            }
            return packAscii(SKIP_ASCII, consumedCodeUnits);
        } else if (marker == '8' || marker == '9'
                || isJScriptNonEscapeCharacter(marker)) {
            return packAscii(lowerAscii(marker), consumedCodeUnits);
        } else {
            return -1L;
        }

        int decoded = 0;
        for (int i = 0; i < digits; i++) {
            long digitValue;
            if (radix == 8 && i == 0) {
                digitValue = markerValue;
            } else {
                digitValue = readHtmlNormalizedAscii(payload, cursor, encoding);
            }
            int digit = radix == 16
                    ? hexValue((int) digitValue) : octalValue((int) digitValue);
            if (digit < 0) {
                if (radix == 8 && i > 0) {
                    break;
                }
                return -1L;
            }
            decoded = decoded * radix + digit;
            if (!(radix == 8 && i == 0)) {
                int digitUnits = (int) (digitValue >>> 32);
                cursor += digitUnits * stride;
                consumedCodeUnits += digitUnits;
            }
        }
        if (decoded > 0x7f) {
            return -1L;
        }
        return packAscii(lowerAscii(decoded), consumedCodeUnits);
    }

    private static long decodeJScriptStringJoin(
            byte[] payload, int offset, int encoding) {
        int stride = encoding == 0 ? 1 : 2;
        long closingQuote = readHtmlNormalizedAscii(payload, offset, encoding);
        int quote = (int) closingQuote;
        if (quote != '\'' && quote != '"') {
            return -1L;
        }
        int cursor = offset + (int) (closingQuote >>> 32) * stride;
        cursor = skipJScriptTrivia(payload, cursor, encoding);
        if (cursor < 0) {
            return INCOMPLETE_JSCRIPT_JOIN;
        }
        int grouping = 0;
        long token = readHtmlNormalizedAscii(payload, cursor, encoding);
        while ((int) token == ')') {
            if (++grouping > MAX_JSCRIPT_JOIN_GROUPING) {
                return INCOMPLETE_JSCRIPT_JOIN;
            }
            cursor += (int) (token >>> 32) * stride;
            cursor = skipJScriptTrivia(payload, cursor, encoding);
            if (cursor < 0) {
                return INCOMPLETE_JSCRIPT_JOIN;
            }
            token = readHtmlNormalizedAscii(payload, cursor, encoding);
        }
        if ((int) token != '+') {
            return packAscii(
                    RESET_ASCII, Math.max(1, (cursor - offset) / stride));
        }
        cursor += (int) (token >>> 32) * stride;
        cursor = skipJScriptTrivia(payload, cursor, encoding);
        if (cursor < 0) {
            return INCOMPLETE_JSCRIPT_JOIN;
        }
        grouping = 0;
        token = readHtmlNormalizedAscii(payload, cursor, encoding);
        while ((int) token == '(') {
            if (++grouping > MAX_JSCRIPT_JOIN_GROUPING) {
                return INCOMPLETE_JSCRIPT_JOIN;
            }
            cursor += (int) (token >>> 32) * stride;
            cursor = skipJScriptTrivia(payload, cursor, encoding);
            if (cursor < 0) {
                return INCOMPLETE_JSCRIPT_JOIN;
            }
            token = readHtmlNormalizedAscii(payload, cursor, encoding);
        }
        int opening = (int) token;
        if (opening != '\'' && opening != '"') {
            return packAscii(
                    RESET_ASCII, Math.max(1, (cursor - offset) / stride));
        }
        cursor += (int) (token >>> 32) * stride;
        return packAscii(SKIP_ASCII, (cursor - offset) / stride);
    }

    private static int skipJScriptTrivia(byte[] payload, int offset, int encoding) {
        int stride = encoding == 0 ? 1 : 2;
        int cursor = offset;
        while (cursor + stride <= payload.length) {
            long current = readHtmlNormalizedAscii(payload, cursor, encoding);
            int value = (int) current;
            if (isJScriptWhitespace(value)) {
                cursor += (int) (current >>> 32) * stride;
                continue;
            }
            if (value != '/') {
                return cursor;
            }
            int afterSlash = cursor + (int) (current >>> 32) * stride;
            long next = readHtmlNormalizedAscii(payload, afterSlash, encoding);
            int marker = (int) next;
            if (marker == '/') {
                cursor = afterSlash + (int) (next >>> 32) * stride;
                while (cursor + stride <= payload.length) {
                    long commentChar =
                            readHtmlNormalizedAscii(payload, cursor, encoding);
                    int commentValue = (int) commentChar;
                    cursor += (int) (commentChar >>> 32) * stride;
                    if (commentValue == '\r' || commentValue == '\n') {
                        break;
                    }
                }
                continue;
            }
            if (marker != '*') {
                return cursor;
            }
            cursor = afterSlash + (int) (next >>> 32) * stride;
            boolean closed = false;
            while (cursor + stride <= payload.length) {
                long commentChar =
                        readHtmlNormalizedAscii(payload, cursor, encoding);
                int commentValue = (int) commentChar;
                int commentUnits = (int) (commentChar >>> 32);
                if (commentValue == '*') {
                    int afterStar = cursor + commentUnits * stride;
                    long end = readHtmlNormalizedAscii(payload, afterStar, encoding);
                    if ((int) end == '/') {
                        cursor = afterStar + (int) (end >>> 32) * stride;
                        closed = true;
                        break;
                    }
                }
                cursor += commentUnits * stride;
            }
            if (!closed) {
                return -1;
            }
        }
        return cursor;
    }

    private static boolean isJScriptWhitespace(int value) {
        return value == ' ' || value == '\t' || value == '\r'
                || value == '\n' || value == '\f' || value == 0x0b;
    }

    private static long packAscii(int value, int consumedCodeUnits) {
        return ((long) consumedCodeUnits << 32) | (value & 0xffffffffL);
    }

    private static int hexValue(int value) {
        if (value >= '0' && value <= '9') {
            return value - '0';
        }
        if (value >= 'a' && value <= 'f') {
            return value - 'a' + 10;
        }
        if (value >= 'A' && value <= 'F') {
            return value - 'A' + 10;
        }
        return -1;
    }

    private static int decimalValue(int value) {
        return value >= '0' && value <= '9' ? value - '0' : -1;
    }

    private static int octalValue(int value) {
        return value >= '0' && value <= '7' ? value - '0' : -1;
    }

    private static boolean isJScriptNonEscapeCharacter(int value) {
        if (value < 0 || value > 0x7f || value == '\r' || value == '\n'
                || (value >= '0' && value <= '9')) {
            return false;
        }
        return value != '\'' && value != '"' && value != '\\'
                && value != 'b' && value != 'f' && value != 'n'
                && value != 'r' && value != 't' && value != 'x'
                && value != 'u';
    }

    private static int[] buildFailureTable(String term) {
        int[] failure = new int[term.length()];
        int matched = 0;
        for (int i = 1; i < term.length(); i++) {
            while (matched > 0 && term.charAt(i) != term.charAt(matched)) {
                matched = failure[matched - 1];
            }
            if (term.charAt(i) == term.charAt(matched)) {
                matched++;
            }
            failure[i] = matched;
        }
        return failure;
    }

    private static int lowerAscii(int value) {
        if (value >= 'A' && value <= 'Z') {
            return value + ('a' - 'A');
        }
        return value;
    }

    // ── Appended data ─────────────────────────────────────────────────────────
    // SHA-256 hashing approach follows Matmaus/LnkParse3 upstream (MIT) extra/terminal.py.
    // Raw-byte dump / extra_garbage approach from wmetcalf/LnkParse3 (MIT) —
    // we use the hash only and submit bytes to Tika's embedded pipeline instead.

    private void parseAppendedData(ByteBuffer buf, int pos, int fileLen,
                                   Map<String, String> fields, List<String> warnings,
                                   XHTMLContentHandler xhtml, ParseContext context) {
        if (pos >= fileLen) {
            return;
        }
        if (pos + 4 <= fileLen && buf.getInt(pos) < 4) {
            pos += 4;
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
            addWarning(warnings, "SHA-256 unavailable: " + e.getMessage());
        }

        byte[] appendedBytes = new byte[remaining];
        System.arraycopy(buf.array(), pos, appendedBytes, 0, remaining);

        String appendedMime = sniffMimeType(appendedBytes);
        fields.put("AppendedDataMimeType", appendedMime);

        // Content-based scan: generic patterns and CVE-2024-21412.
        detectExploitIndicators(appendedBytes, fields, warnings);

        // Structural check runs last so it overwrites content-based results when
        // it matches — structural detection is always more specific.
        detectStructuralExploits(
                isStructurallyRenderableHtml(appendedMime, appendedBytes),
                fields, warnings);

        EmbeddedDocumentExtractor extractor =
                EmbeddedDocumentUtil.getEmbeddedDocumentExtractor(context);
        Metadata embeddedMeta = Metadata.newInstance(context);
        embeddedMeta.set(TikaCoreProperties.RESOURCE_NAME_KEY, "lnk-appended-data");
        try (TikaInputStream embeddedTis = TikaInputStream.get(appendedBytes)) {
            if (extractor.shouldParseEmbedded(embeddedMeta)) {
                extractor.parseEmbedded(embeddedTis, xhtml, embeddedMeta, context, true);
            }
        } catch (Exception e) {
            addWarning(warnings, "Appended data parse error: " + e.getMessage());
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
        return end == pos ? null : new String(data, pos, end - pos, cp1252());
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
        return end == pos ? null
                : new String(data, pos, end - pos, StandardCharsets.UTF_16LE);
    }

    private String readLinkInfoStringA(
            ByteBuffer buf, int pos, int limit, LinkInfoStringBudget budget,
            Map<String, String> fields, List<String> warnings) {
        if (pos < 0 || pos >= limit || budget.remainingChars == 0) {
            if (budget.remainingChars == 0) {
                budget.markExceeded(fields, warnings);
            }
            return null;
        }
        byte[] data = buf.array();
        int allowed = Math.min(
                MAX_LINK_INFO_STRING_CHARS, budget.remainingChars);
        int end = pos;
        while (end < limit && data[end] != 0 && end - pos < allowed) {
            end++;
        }
        int retained = end - pos;
        budget.remainingChars -= retained;
        if (end >= limit || (retained == allowed && data[end] != 0)) {
            budget.markExceeded(fields, warnings);
        }
        return retained == 0 ? null
                : new String(data, pos, retained, cp1252());
    }

    private String readLinkInfoStringW(
            ByteBuffer buf, int pos, int limit, LinkInfoStringBudget budget,
            Map<String, String> fields, List<String> warnings) {
        if (pos < 0 || pos + 2 > limit || budget.remainingChars == 0) {
            if (budget.remainingChars == 0) {
                budget.markExceeded(fields, warnings);
            }
            return null;
        }
        byte[] data = buf.array();
        int allowed = Math.min(
                MAX_LINK_INFO_STRING_CHARS, budget.remainingChars);
        int end = pos;
        int retained = 0;
        while (end + 1 < limit
                && (data[end] != 0 || data[end + 1] != 0)
                && retained < allowed) {
            end += 2;
            retained++;
        }
        budget.remainingChars -= retained;
        if (end + 1 >= limit
                || (retained == allowed
                && (data[end] != 0 || data[end + 1] != 0))) {
            budget.markExceeded(fields, warnings);
        }
        return retained == 0 ? null
                : new String(
                        data, pos, retained * 2, StandardCharsets.UTF_16LE);
    }

    private static final class LinkInfoStringBudget {
        private int remainingChars = MAX_LINK_INFO_TOTAL_STRING_CHARS;
        private boolean exceeded;

        private void markExceeded(
                Map<String, String> fields, List<String> warnings) {
            if (!exceeded) {
                exceeded = true;
                markAnalysisIncomplete(fields, warnings,
                        "LinkInfo strings exceed safe retention limits");
            }
        }
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
        return len == 0 ? null
                : new String(data, pos, len * 2, StandardCharsets.UTF_16LE);
    }

    private String formatGuid(ByteBuffer buf, int pos) {
        int   d1 = buf.getInt(pos);
        short d2 = buf.getShort(pos + 4);
        short d3 = buf.getShort(pos + 6);
        byte[] b = buf.array();
        return String.format(Locale.ROOT,
                "{%08X-%04X-%04X-%02X%02X-%02X%02X%02X%02X%02X%02X}",
                d1, Short.toUnsignedInt(d2), Short.toUnsignedInt(d3),
                b[pos + 8] & 0xFF, b[pos + 9] & 0xFF,
                b[pos + 10] & 0xFF, b[pos + 11] & 0xFF, b[pos + 12] & 0xFF,
                b[pos + 13] & 0xFF, b[pos + 14] & 0xFF, b[pos + 15] & 0xFF);
    }

    private String extractMac(ByteBuffer buf, int pos) {
        byte[] b = buf.array();
        if ((b[pos + 10] & 0x01) != 0) {
            return null;
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
            return null;
        }
        long ticks = ((long)(timeHiV & 0x0FFF) << 48) | ((long) timeMid << 32)
                | Integer.toUnsignedLong(timeLow);
        long unix  = ticks - 122192928000000000L;
        try {
            return Instant.ofEpochSecond(unix / 10_000_000L,
                    (int)((unix % 10_000_000L) * 100L)).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String filetimeToIso(long ft) {
        long ticks = ft - FILETIME_EPOCH_DIFF_NS100;
        try {
            return Instant.ofEpochSecond(ticks / 10_000_000L,
                    (int)((ticks % 10_000_000L) * 100L)).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String dosTimeToIso(int dosDateTime) {
        return dosDateTimeToIso((dosDateTime >> 16) & 0xFFFF, dosDateTime & 0xFFFF);
    }

    private String dosDateTimeToIso(int dosDate, int dosTime) {
        if (dosDate == 0) {
            return null;
        }
        int year = ((dosDate >> 9) & 0x7F) + 1980;
        int mon  = (dosDate >> 5) & 0x0F;
        int day  = dosDate & 0x1F;
        int hrs  = (dosTime >> 11) & 0x1F;
        int min  = (dosTime >> 5) & 0x3F;
        int sec  = (dosTime & 0x1F) * 2;
        if (mon < 1 || mon > 12 || day < 1 || day > 31) {
            return null;
        }
        try {
            return LocalDateTime.of(year, mon, day, hrs, min, sec)
                    .toInstant(ZoneOffset.UTC).toString();
        } catch (Exception e) {
            return null;
        }
    }

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
        if ((attrs & 0x01) != 0) { flags.add("ReadOnly"); }
        if ((attrs & 0x02) != 0) { flags.add("Hidden"); }
        if ((attrs & 0x04) != 0) { flags.add("System"); }
        if ((attrs & 0x10) != 0) { flags.add("Directory"); }
        if ((attrs & 0x20) != 0) { flags.add("Archive"); }
        if ((attrs & 0x80) != 0) { flags.add("Normal"); }
        if ((attrs & 0x100) != 0) { flags.add("Temporary"); }
        if ((attrs & 0x400) != 0) { flags.add("ReparsePoint"); }
        if ((attrs & 0x800) != 0) { flags.add("Compressed"); }
        if ((attrs & 0x4000) != 0) { flags.add("Encrypted"); }
        return flags.isEmpty() ? String.format(Locale.ROOT, "0x%08X", attrs)
                : String.join("|", flags);
    }

    private List<String> decodeLinkFlags(int flags) {
        List<String> names = new ArrayList<>();
        if ((flags & FLAG_HAS_TARGET_IDLIST) != 0) { names.add("HasTargetIDList"); }
        if ((flags & FLAG_HAS_LINK_INFO) != 0) { names.add("HasLinkInfo"); }
        if ((flags & 0x00000200) != 0) { names.add("HasExpString"); }
        if ((flags & 0x00002000) != 0) { names.add("RunAsUser"); }
        if ((flags & 0x00040000) != 0) { names.add("ForceNoLinkTrack"); }
        if ((flags & FLAG_IS_UNICODE) != 0) { names.add("IsUnicode"); }
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
