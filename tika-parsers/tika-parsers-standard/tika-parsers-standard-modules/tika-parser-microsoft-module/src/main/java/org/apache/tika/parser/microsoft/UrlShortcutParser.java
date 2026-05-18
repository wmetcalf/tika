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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * Parser for Windows Internet Shortcut (.url) files.
 *
 * <p>.url files are tiny INI-format text files driven by the
 * {@code IExplore.InternetShortcut} COM handler. They are commonly used in
 * phishing because they sail past gateways that only inspect macros/HTML and
 * fire when double-clicked.</p>
 *
 * <p>Format (RFC-ish):</p>
 * <pre>
 *   [InternetShortcut]
 *   URL=https://example.com/
 *   URLW=68007400740070003A...     ← hex-encoded UTF-16LE alternate URL
 *   IconFile=C:\\path\\to\\icon.ico
 *   IconIndex=0
 *   WorkingDirectory=C:\\Users\\target\\Downloads
 *   IDList=...                     ← optional binary IDList (hex)
 *   HotKey=0
 *   ShowCommand=1
 * </pre>
 *
 * <p>Known weaponizations surfaced as {@code ExploitClass}:</p>
 * <ul>
 *   <li>CVE-2023-29324 — {@code URLW} pointing at a UNC path bypasses
 *       Mark-of-the-Web by triggering WebDAV authentication.</li>
 *   <li>{@code URL=file://} or {@code URL=\\server\share} — pulls a payload
 *       over SMB/WebDAV without the user ever seeing the path.</li>
 *   <li>{@code URL=search-ms:} or {@code URL=ms-officecmd:} or
 *       {@code URL=ms-msdt:} — protocol-handler abuse (Follina, etc.).</li>
 *   <li>{@code URL} and {@code URLW} disagree — classic parser-confusion;
 *       gateway sees one URL, the shell handler follows the other.</li>
 * </ul>
 */
@TikaComponent
public class UrlShortcutParser implements Parser {

    private static final long serialVersionUID = 1L;

    private static final MediaType URL_TYPE =
            MediaType.application("x-mswinurl");
    private static final Set<MediaType> SUPPORTED_TYPES =
            Collections.singleton(URL_TYPE);

    private static final int MAX_INPUT_BYTES = 256 * 1024;

    // Suspicious URL schemes / patterns that warrant an ExploitClass record.
    private static final Pattern SUSPICIOUS_URL = Pattern.compile(
            "^(file:|smb:|search-ms:|ms-msdt:|ms-officecmd:|ms-appinstaller:"
                    + "|ms-publisher:|javascript:|vbscript:|data:|res://)"
                    + "|^\\\\\\\\[^\\\\]",   // UNC path \\server\...
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SECTION_HEADER = Pattern.compile("^\\[([^\\]]+)\\]\\s*$");
    private static final Pattern KEY_VALUE = Pattern.compile("^([^=;#]+)=(.*)$");

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED_TYPES;
    }

    @Override
    public void parse(TikaInputStream stream, ContentHandler handler,
                      Metadata metadata, ParseContext context)
            throws IOException, SAXException, TikaException {

        byte[] raw = stream.readNBytes(MAX_INPUT_BYTES);
        Decoded decoded = decode(raw);
        metadata.set("url:source_encoding", decoded.encoding);

        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata);
        xhtml.startDocument();

        // INI parse — section-scoped, last-wins per key
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        String current = "InternetShortcut";  // default if no header
        sections.put(current, new LinkedHashMap<>());
        for (String rawLine : decoded.text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) {
                continue;
            }
            Matcher sh = SECTION_HEADER.matcher(line);
            if (sh.matches()) {
                current = sh.group(1).trim();
                sections.computeIfAbsent(current, k -> new LinkedHashMap<>());
                continue;
            }
            Matcher kv = KEY_VALUE.matcher(line);
            if (kv.matches()) {
                String key = kv.group(1).trim();
                String val = kv.group(2).trim();
                // Strip surrounding quotes — some generators write URL="...".
                if (val.length() >= 2
                        && ((val.charAt(0) == '"' && val.charAt(val.length() - 1) == '"')
                         || (val.charAt(0) == '\'' && val.charAt(val.length() - 1) == '\''))) {
                    val = val.substring(1, val.length() - 1);
                }
                sections.get(current).put(key.toLowerCase(Locale.ROOT), val);
            }
        }

        // Hoist the [InternetShortcut] section (or [DEFAULT] for some variants)
        Map<String, String> shortcut = sections.getOrDefault("InternetShortcut",
                sections.getOrDefault("DEFAULT", Collections.emptyMap()));

        String url = shortcut.get("url");
        String urlw = shortcut.get("urlw");
        String iconFile = shortcut.get("iconfile");
        String iconIndex = shortcut.get("iconindex");
        String workingDir = shortcut.get("workingdirectory");
        String hotKey = shortcut.get("hotkey");
        String showCmd = shortcut.get("showcommand");
        String modified = shortcut.get("modified");
        String idList = shortcut.get("idlist");

        List<String> warnings = new ArrayList<>();

        if (url != null && !url.isEmpty()) {
            metadata.set("url:url", url);
            xhtml.element("p", "URL: " + url);
        }
        // URLW is hex-encoded UTF-16LE — decode it.
        if (urlw != null && !urlw.isEmpty()) {
            String urlwDecoded = decodeUrlW(urlw);
            metadata.set("url:url_wide_raw", urlw);
            if (urlwDecoded != null) {
                metadata.set("url:url_wide", urlwDecoded);
                xhtml.element("p", "URLW: " + urlwDecoded);
            } else {
                warnings.add("URLW field present but not decodable as hex/UTF-16LE");
            }
        }
        if (iconFile != null && !iconFile.isEmpty()) {
            metadata.set("url:icon_file", iconFile);
        }
        if (iconIndex != null && !iconIndex.isEmpty()) {
            metadata.set("url:icon_index", iconIndex);
        }
        if (workingDir != null && !workingDir.isEmpty()) {
            metadata.set("url:working_directory", workingDir);
        }
        if (hotKey != null && !hotKey.isEmpty() && !"0".equals(hotKey)) {
            metadata.set("url:hot_key", hotKey);
        }
        if (showCmd != null && !showCmd.isEmpty()) {
            metadata.set("url:show_command", showCmd);
        }
        if (modified != null && !modified.isEmpty()) {
            metadata.set("url:modified", modified);
        }
        if (idList != null && !idList.isEmpty()) {
            // IDList is binary hex — surface its length only (full binary would
            // bloat metadata).  WinShortcutParser already handles IDList in LNK
            // context; .url IDList is rarer and structurally identical.
            metadata.set("url:idlist_hex_length", Integer.toString(idList.length()));
        }

        // Threat signals.
        if (url != null && SUSPICIOUS_URL.matcher(url).find()) {
            metadata.set("ExploitClass",
                    ".url shortcut points at non-HTTP URL: " + url);
        }
        if (urlw != null && url != null) {
            String urlwDecoded = decodeUrlW(urlw);
            if (urlwDecoded != null && !urlwDecoded.equalsIgnoreCase(url)) {
                metadata.set("ExploitClass",
                        ".url URL/URLW disagree (parser-confusion / MotW-bypass shape): "
                                + "URL=" + url + " URLW=" + urlwDecoded);
            }
            if (urlwDecoded != null && SUSPICIOUS_URL.matcher(urlwDecoded).find()) {
                metadata.set("ExploitClass",
                        ".url URLW points at non-HTTP URL: " + urlwDecoded);
            }
        }
        if (iconFile != null
                && (iconFile.startsWith("\\\\") || iconFile.startsWith("//"))) {
            // UNC IconFile causes Windows to authenticate to the share to fetch
            // the icon — Net-NTLMv2 hash leak vector (CVE-2024-21412 family).
            metadata.set("ExploitClass",
                    ".url IconFile is a UNC path (NTLM hash leak vector): " + iconFile);
        }

        // Body: raw INI for analyst inspection
        xhtml.startElement("pre");
        xhtml.characters(decoded.text);
        xhtml.endElement("pre");

        for (String w : warnings) {
            metadata.add("url:warning", w);
        }

        xhtml.endDocument();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static final class Decoded {
        final String text;
        final String encoding;

        Decoded(String text, String encoding) {
            this.text = text;
            this.encoding = encoding;
        }
    }

    /** Decode the .url byte stream, honouring BOMs. .url files are usually
     *  UTF-8 or Windows-1252; we tolerate UTF-16 too because some phishing
     *  kits generate them via .NET's default encoding. */
    static Decoded decode(byte[] raw) {
        if (raw.length >= 3 && (raw[0] & 0xff) == 0xef
                && (raw[1] & 0xff) == 0xbb && (raw[2] & 0xff) == 0xbf) {
            return new Decoded(new String(raw, 3, raw.length - 3, StandardCharsets.UTF_8),
                    "UTF-8 (BOM)");
        }
        if (raw.length >= 2 && (raw[0] & 0xff) == 0xff && (raw[1] & 0xff) == 0xfe) {
            return new Decoded(new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16LE),
                    "UTF-16LE (BOM)");
        }
        if (raw.length >= 2 && (raw[0] & 0xff) == 0xfe && (raw[1] & 0xff) == 0xff) {
            return new Decoded(new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16BE),
                    "UTF-16BE (BOM)");
        }
        // Try UTF-8 — if the byte stream isn't valid UTF-8 the JVM substitutes
        // U+FFFD; pick cp1252 in that case.
        String utf8 = new String(raw, StandardCharsets.UTF_8);
        if (utf8.indexOf(0xFFFD) < 0 && utf8.contains("[InternetShortcut]")) {
            return new Decoded(utf8, "UTF-8");
        }
        return new Decoded(new String(raw, Charset.forName("windows-1252")), "windows-1252");
    }

    /** Decode {@code URLW}: hex string of UTF-16LE code units. */
    static String decodeUrlW(String hex) {
        String h = hex.trim();
        if (h.isEmpty() || (h.length() & 1) != 0) {
            return null;
        }
        byte[] bytes = new byte[h.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int hi = Character.digit(h.charAt(2 * i), 16);
            int lo = Character.digit(h.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            bytes[i] = (byte) ((hi << 4) | lo);
        }
        String s = new String(bytes, StandardCharsets.UTF_16LE);
        // Trim trailing NULs (some encoders pad)
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == 0) {
            end--;
        }
        return end == 0 ? null : s.substring(0, end);
    }

    // Kept for documentation; not currently used.
    @SuppressWarnings("unused")
    private static final List<String> SUSPICIOUS_KEYS = Arrays.asList(
            "url", "urlw", "iconfile", "modified");
}
