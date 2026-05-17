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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.config.TikaComponent;
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
 * Parser for Microsoft Management Console snap-in files (.msc).
 *
 * <p>MSC files are XML documents with root element {@code MMC_ConsoleFile}.
 * They configure MMC snap-ins and are a significant living-off-the-land
 * (LotL) initial-access vector since 2024 ("GrimResource" technique).</p>
 *
 * <p>Extracts:</p>
 * <ul>
 *   <li>{@code msc:snap_in_clsid[]} — snap-in GUIDs with human-readable names
 *       where known (built-in map of common Windows snap-ins)</li>
 *   <li>{@code msc:snap_in_name[]} — human-readable names for known CLSIDs</li>
 *   <li>{@code msc:command[]} — CommandLine and Command attribute values</li>
 *   <li>{@code msc:url[]} — URLs extracted from all text content</li>
 *   <li>{@code msc:string[]} — raw String element content (may contain JS)</li>
 *   <li>{@code msc:binary_sha256[]} — SHA-256 of each base64-decoded Binary blob</li>
 *   <li>{@code msc:binary_mime[]} — detected MIME type of each binary blob</li>
 *   <li>{@code ExploitClass} — set when GrimResource apds.dll redirect is present,
 *       or when commands contain shell execution keywords</li>
 * </ul>
 *
 * <p>GrimResource detection: the {@code res://apds.dll/redirect.html?target=javascript:}
 * pattern in any String element triggers the exploit — MMC executes the JavaScript
 * in the URL via apds.dll's XSL redirect handler, granting access to the MMC COM
 * object model including ActiveX and shell execution.  First documented in-the-wild
 * in June 2024; used by multiple threat actors.</p>
 *
 * <p>Binary blobs embedded via {@code <Binary>} or {@code <BinaryData>} elements
 * are base64-decoded, SHA-256 hashed, MIME-detected, and submitted to Tika's
 * embedded document pipeline.</p>
 *
 * <p>Field-name conventions and extraction logic derived from wmetcalf/msc_dark_all_day
 * (MIT License, https://github.com/wmetcalf/msc_dark_all_day); no source was copied.</p>
 */
@TikaComponent
public class MscParser implements Parser {

    private static final long serialVersionUID = 1L;

    private static final MediaType MSC_TYPE = MediaType.application("x-msc");
    private static final Set<MediaType> SUPPORTED_TYPES = Collections.singleton(MSC_TYPE);

    // Shell execution keywords that indicate a dangerous command
    private static final String[] EXEC_KEYWORDS = {
            "powershell", "cmd.exe", "cmd /c", "wscript", "cscript", "mshta",
            "certutil", "bitsadmin", "regsvr32", "rundll32", "msiexec", "wmic",
            "mshta.exe", "wscript.exe", "cscript.exe", "conhost"
    };

    // Known MMC snap-in CLSIDs → human-readable names.
    // Source: Windows SDK, MSDN, public documentation.
    private static final Map<String, String> CLSID_NAMES;
    static {
        CLSID_NAMES = new HashMap<>();
        // All keys normalized to uppercase for case-insensitive lookup.
        CLSID_NAMES.put("{C96401CC-0E17-11D3-885B-00C04F72C717}", "MMC Console Root");
        CLSID_NAMES.put("{C96401CD-0E17-11D3-885B-00C04F72C717}", "MMC TaskPad");
        CLSID_NAMES.put("{C96401CE-0E17-11D3-885B-00C04F72C717}", "MMC ListView");
        CLSID_NAMES.put("{C96401CF-0E17-11D3-885B-00C04F72C717}", "MMC Folder");
        CLSID_NAMES.put("{C96401D0-0E17-11D3-885B-00C04F72C717}", "MMC Favorites");
        CLSID_NAMES.put("{C96401D1-0E17-11D3-885B-00C04F72C717}", "MMC Online Help");
        CLSID_NAMES.put("{71E5B33E-1064-11D2-808F-0000F875A9CE}", "MMC StringTable");
        CLSID_NAMES.put("{2933BF90-7B36-11D2-B20E-00C04F983E60}", "XML DOM Document");
        CLSID_NAMES.put("{58221C65-EA27-11CF-ADCF-00AA00A80033}", "Certificate Manager");
        CLSID_NAMES.put("{58221C66-EA27-11CF-ADCF-00AA00A80033}", "Certificate Store");
        CLSID_NAMES.put("{8FC0B734-A0E1-11D1-A7D3-0000F87571E3}", "Device Manager");
        CLSID_NAMES.put("{D20EA4E1-3957-11D2-A40B-0C5020524153}", "Scheduled Tasks");
        CLSID_NAMES.put("{D20EA4E1-3957-11D2-A40B-0C5020524152}", "Local Users and Groups");
        CLSID_NAMES.put("{E08EAB38-8E2E-11D1-904B-00C04FB6DDBA}", "Shared Folders");
        CLSID_NAMES.put("{80F0AC5E-7801-11D0-B27D-00C04FD8D5B0}", "ADSI Snap-in");
        CLSID_NAMES.put("{A841B6C7-7577-11D0-BB1F-00A024AB2DBB}", "Group Policy Object");
        CLSID_NAMES.put("{F5DCA93E-F50D-49C9-BA45-E752340E5492}", "Custom TaskPad");
        CLSID_NAMES.put("{B84F8189-0515-487E-9CAB-C9E818A9E6B1}", "Custom Snap-in");
        CLSID_NAMES.put("{8172431C-597B-43F3-9EC8-50FED33B00E5}", "Custom Extension");
    }

    // Pattern to extract attribute values from XML tags
    private static final Pattern ATTR_VAL =
            Pattern.compile("\\b(Command|CommandLine)\\s*=\\s*\"([^\"]+)\"",
                    Pattern.CASE_INSENSITIVE);

    // URL pattern
    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[^\\s\"'<>\\\\]+",
                    Pattern.CASE_INSENSITIVE);

    // CLSID pattern
    private static final Pattern CLSID_PATTERN =
            Pattern.compile("\\{[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}"
                    + "-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\\}");

    // GrimResource: apds.dll redirect to javascript
    private static final Pattern GRIMRESOURCE_PATTERN =
            Pattern.compile("res://apds\\.dll/[^\"'<\\s]+",
                    Pattern.CASE_INSENSITIVE);

    // Base64 blob in Binary/BinaryData elements (min 20 chars to skip tiny entries)
    private static final Pattern BINARY_ELEMENT =
            Pattern.compile("<(?:Binary|BinaryData)(?:\\s[^>]*)?>([A-Za-z0-9+/\\s]{20,}={0,2})"
                    + "</(?:Binary|BinaryData)>",
                    Pattern.CASE_INSENSITIVE);

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED_TYPES;
    }

    @Override
    public void parse(TikaInputStream stream, ContentHandler handler,
                      Metadata metadata, ParseContext context)
            throws IOException, SAXException, TikaException {

        byte[] raw = stream.readAllBytes();
        String xml = new String(raw, detectCharset(raw));

        // Quick sanity check — must look like an MMC console file
        if (!xml.contains("MMC_ConsoleFile")) {
            throw new TikaException("Not an MSC file: MMC_ConsoleFile element not found");
        }

        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata);
        xhtml.startDocument();

        Set<String> clsids    = new LinkedHashSet<>();
        Set<String> commands  = new LinkedHashSet<>();
        Set<String> urls      = new LinkedHashSet<>();
        Set<String> strings   = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        boolean grimResource  = false;

        // Extract CLSIDs
        Matcher cm = CLSID_PATTERN.matcher(xml);
        while (cm.find()) {
            clsids.add(cm.group().toUpperCase(Locale.ROOT));
        }

        // Extract Command / CommandLine attribute values
        Matcher am = ATTR_VAL.matcher(xml);
        while (am.find()) {
            String cmd = am.group(2).trim();
            if (!cmd.isEmpty()) {
                commands.add(cmd);
            }
        }

        // Extract <CommandLine> element content (ConsoleTaskpad tasks)
        for (String cmd : extractTagContent(xml, "CommandLine")) {
            if (!cmd.isEmpty()) {
                commands.add(cmd);
            }
        }

        // Extract String element content (may contain JS, apds redirect, encoded payloads)
        for (String s : extractTagContent(xml, "String")) {
            if (!s.isEmpty()) {
                strings.add(s);
                // Check for GrimResource apds.dll trigger
                if (GRIMRESOURCE_PATTERN.matcher(s).find()) {
                    grimResource = true;
                }
                // URLs within string content
                Matcher um = URL_PATTERN.matcher(s);
                while (um.find()) {
                    urls.add(cleanUrl(um.group()));
                }
            }
        }

        // Extract URLs from full document
        Matcher um = URL_PATTERN.matcher(xml);
        while (um.find()) {
            urls.add(cleanUrl(um.group()));
        }

        // Emit CLSID metadata with name lookup
        for (String clsid : clsids) {
            metadata.add("msc:snap_in_clsid", clsid);
            String name = CLSID_NAMES.get(clsid);
            if (name != null) {
                metadata.add("msc:snap_in_name", name);
            }
        }

        // Emit commands
        for (String cmd : commands) {
            metadata.add("msc:command", cmd);
        }

        // Emit URLs
        for (String url : urls) {
            metadata.add("msc:url", url);
        }

        // Emit string content (truncated to 4KB per entry to avoid bloat)
        for (String s : strings) {
            metadata.add("msc:string",
                    s.length() > 4096 ? s.substring(0, 4096) : s);
        }

        // ExploitClass
        if (grimResource) {
            metadata.set("ExploitClass",
                    "GrimResource: apds.dll XSL redirect to javascript: — "
                    + "MMC COM object model execution bypass (in-the-wild since June 2024)");
        } else {
            // Check commands for shell execution keywords
            for (String cmd : commands) {
                String lower = cmd.toLowerCase(Locale.ROOT);
                for (String kw : EXEC_KEYWORDS) {
                    if (lower.contains(kw)) {
                        metadata.set("ExploitClass",
                                "MSC task command executes shell payload: " + cmd);
                        break;
                    }
                }
            }
        }

        // Write XHTML content body
        xhtml.startElement("div");
        for (String clsid : clsids) {
            String name = CLSID_NAMES.getOrDefault(clsid, "Unknown");
            xhtml.element("p", "SnapIn: " + clsid + " (" + name + ")");
        }
        for (String cmd : commands) {
            xhtml.element("p", "CommandLine: " + cmd);
        }
        for (String url : urls) {
            xhtml.element("p", "URL: " + url);
        }
        if (grimResource) {
            xhtml.element("p", "ExploitClass: GrimResource (apds.dll javascript redirect)");
        }
        xhtml.endElement("div");

        // Process Binary/BinaryData blobs through embedded pipeline
        EmbeddedDocumentExtractor extractor =
                EmbeddedDocumentUtil.getEmbeddedDocumentExtractor(context);
        parseBinaryBlobs(xml, raw, metadata, xhtml, context, extractor, warnings);

        for (String w : warnings) {
            metadata.add("msc:warning", w);
        }

        xhtml.endDocument();
    }

    // ── Binary blob extraction ────────────────────────────────────────────────
    //
    // MSC Binary elements are Windows IMAGELIST streams (HIMAGELIST serialized
    // format, magic "IL" = 0x49 0x4C).  The stream packs all icons side-by-side
    // into a single DIB/BMP that starts at a fixed offset of 28 bytes into the
    // IMAGELIST header.  Tika's DefaultDetector does not know IMAGELIST format and
    // returns application/octet-stream for the full blob, so RedTusk never pHashes
    // or thumbnails it.  We extract the embedded BMP directly and submit that.
    //
    // IMAGELIST header layout (28 bytes):
    //   0x00: magic "IL" (2 bytes)
    //   0x02: version (2 bytes, typically 0x0101)
    //   0x04: flags (2 bytes)
    //   0x06: cGrow (2 bytes)
    //   0x08: cCurr — number of images (2 bytes)
    //   0x0A: cx — image width (2 bytes)
    //   0x0C: cy — image height (2 bytes)
    //   0x0E: clrBk — background colour (4 bytes)
    //   0x12: flags2 (4 bytes)
    //   0x16: overlayImages (4 bytes)
    //   0x1A: (end of fixed header) — BMP/DIB data starts here at offset 28

    private static final int IMAGELIST_HEADER_SIZE = 28;
    // Windows IMAGELIST magic: 'I' 'L' (0x49 0x4C)
    private static final byte IMAGELIST_MAGIC_0 = 0x49;
    private static final byte IMAGELIST_MAGIC_1 = 0x4C;

    private void parseBinaryBlobs(String xml, byte[] rawXml, Metadata rootMeta,
                                  XHTMLContentHandler xhtml, ParseContext context,
                                  EmbeddedDocumentExtractor extractor,
                                  List<String> warnings)
            throws IOException, SAXException, TikaException {
        Matcher bm = BINARY_ELEMENT.matcher(xml);
        int idx = 0;
        while (bm.find()) {
            String b64 = bm.group(1).replaceAll("[\\s]", "");
            byte[] data;
            try {
                data = Base64.getDecoder().decode(b64);
            } catch (IllegalArgumentException e) {
                warnings.add("Binary blob " + idx + " base64 decode failed: " + e.getMessage());
                idx++;
                continue;
            }

            // SHA-256 always computed on the raw blob
            String sha256 = sha256Hex(data);
            rootMeta.add("msc:binary_sha256", sha256);

            // Extract embedded BMP/DIB from Windows IMAGELIST streams so that
            // Tika recognises the content as an image for pHash/thumbnail.
            byte[] imageData = extractImageFromBlob(data);
            boolean isImageList = imageData != data;
            if (isImageList) {
                rootMeta.add("msc:binary_type", "imagelist");
            }

            Metadata embMeta = new Metadata();
            embMeta.set(TikaCoreProperties.RESOURCE_NAME_KEY,
                    "msc-binary-" + idx + (isImageList ? ".bmp" : ""));
            String mime = "application/octet-stream";
            try (TikaInputStream tis = TikaInputStream.get(imageData)) {
                mime = new DefaultDetector()
                        .detect(tis, embMeta, context)
                        .getBaseType().toString();
            } catch (Exception e) {
                warnings.add("MIME detect failed for binary " + idx + ": " + e.getMessage());
            }
            embMeta.set(Metadata.CONTENT_TYPE, mime);
            rootMeta.add("msc:binary_mime", mime);

            try (TikaInputStream tis = TikaInputStream.get(imageData)) {
                if (extractor.shouldParseEmbedded(embMeta)) {
                    extractor.parseEmbedded(tis, xhtml, embMeta, context, true);
                }
            } catch (Exception e) {
                warnings.add("Binary " + idx + " parse error: " + e.getMessage());
            }
            idx++;
        }
    }

    // Returns the BMP payload from a Windows IMAGELIST blob, or the original
    // bytes unchanged if the blob is not in IMAGELIST format.
    //
    // The IMAGELIST stream stores a BITMAPFILEHEADER at offset 28 with bfSize
    // set to 54 (header-only stub) rather than the actual file length.  We fix
    // bfSize in-place so the BMP is recognized as image/bmp by Tika's detector
    // and subsequently pHashed / thumbnailed by the extraction pipeline.
    private static byte[] extractImageFromBlob(byte[] data) {
        if (data.length > IMAGELIST_HEADER_SIZE
                && data[0] == IMAGELIST_MAGIC_0
                && data[1] == IMAGELIST_MAGIC_1) {
            int bmpLen = data.length - IMAGELIST_HEADER_SIZE;
            byte[] bmp = new byte[bmpLen];
            System.arraycopy(data, IMAGELIST_HEADER_SIZE, bmp, 0, bmpLen);
            // Verify BM magic and patch bfSize (bytes 2-5 LE) to actual length
            if (bmpLen >= 6 && (bmp[0] & 0xff) == 0x42 && (bmp[1] & 0xff) == 0x4D) {
                bmp[2] = (byte) (bmpLen & 0xff);
                bmp[3] = (byte) ((bmpLen >> 8) & 0xff);
                bmp[4] = (byte) ((bmpLen >> 16) & 0xff);
                bmp[5] = (byte) ((bmpLen >> 24) & 0xff);
            }
            return bmp;
        }
        return data;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static List<String> extractTagContent(String xml, String tag) {
        List<String> results = new ArrayList<>();
        String open  = "<" + tag + ">";
        String openA = "<" + tag + " ";
        String close = "</" + tag + ">";
        int pos = 0;
        while (pos < xml.length()) {
            int start = xml.indexOf(open, pos);
            int startA = xml.indexOf(openA, pos);
            if (start < 0 && startA < 0) {
                break;
            }
            int contentStart;
            int nextSearch;
            if (start < 0 || (startA >= 0 && startA < start)) {
                // Tag with attributes — find end of opening tag
                int tagEnd = xml.indexOf('>', startA);
                if (tagEnd < 0) {
                    break;
                }
                // Skip self-closing tags (<Tag ... />)
                if (tagEnd > 0 && xml.charAt(tagEnd - 1) == '/') {
                    pos = tagEnd + 1;
                    continue;
                }
                contentStart = tagEnd + 1;
                nextSearch = contentStart;
            } else {
                contentStart = start + open.length();
                nextSearch = contentStart;
            }
            int end = xml.indexOf(close, nextSearch);
            if (end < 0) {
                break;
            }
            String content = xml.substring(contentStart, end).trim();
            if (!content.isEmpty()) {
                results.add(content);
            }
            pos = end + close.length();
        }
        return results;
    }

    private static String cleanUrl(String url) {
        // Strip trailing XML entities like &quot; &amp; etc.
        int i = url.indexOf('&');
        if (i > 0) {
            url = url.substring(0, i);
        }
        // Strip trailing closing tags/quotes
        int j = url.indexOf('"');
        if (j > 0) {
            url = url.substring(0, j);
        }
        return url;
    }

    private static String detectCharset(byte[] raw) {
        // UTF-8 BOM
        if (raw.length >= 3 && (raw[0] & 0xff) == 0xef
                && (raw[1] & 0xff) == 0xbb && (raw[2] & 0xff) == 0xbf) {
            return "UTF-8";
        }
        // UTF-16LE BOM
        if (raw.length >= 2 && (raw[0] & 0xff) == 0xff && (raw[1] & 0xff) == 0xfe) {
            return "UTF-16LE";
        }
        // UTF-16BE BOM
        if (raw.length >= 2 && (raw[0] & 0xff) == 0xfe && (raw[1] & 0xff) == 0xff) {
            return "UTF-16BE";
        }
        // Check XML declaration for encoding hint
        String head = new String(raw, 0, Math.min(200, raw.length), StandardCharsets.US_ASCII);
        int encIdx = head.indexOf("encoding=\"");
        if (encIdx >= 0) {
            int encEnd = head.indexOf('"', encIdx + 10);
            if (encEnd > encIdx + 10) {
                return head.substring(encIdx + 10, encEnd);
            }
        }
        return "UTF-8";
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }
}
