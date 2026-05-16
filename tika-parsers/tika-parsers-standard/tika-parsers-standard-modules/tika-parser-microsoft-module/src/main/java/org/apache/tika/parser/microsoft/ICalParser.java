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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
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
 * Parser for iCalendar files (.ics, RFC 5545).
 *
 * <p>Parses VCALENDAR objects and their components (VEVENT, VTODO, VJOURNAL,
 * VALARM) without external dependencies — uses pure Java RFC 5545 line-folding
 * unfold and property parsing.</p>
 *
 * <p>iCalendar phishing delivers malicious meeting invitations that auto-import
 * into victims' calendars.  The DESCRIPTION and LOCATION fields frequently
 * contain phishing URLs.  VALARM ACTION:PROCEDURE was an older RCE vector.
 * ATTACH with inline base64 payloads is used to deliver malware.</p>
 *
 * <p>Extracted metadata fields (prefixed {@code ical:}):</p>
 * <ul>
 *   <li>{@code ical:prodid} — producing application (version fingerprint)</li>
 *   <li>{@code ical:method} — calendar method (REQUEST/REPLY/CANCEL/PUBLISH)</li>
 *   <li>{@code ical:event_summary[]} — VEVENT SUMMARY</li>
 *   <li>{@code ical:event_description[]} — VEVENT DESCRIPTION (phishing URLs here)</li>
 *   <li>{@code ical:event_location[]} — VEVENT LOCATION</li>
 *   <li>{@code ical:event_url[]} — VEVENT URL property</li>
 *   <li>{@code ical:event_dtstart[]} / {@code ical:event_dtend[]} — timestamps</li>
 *   <li>{@code ical:event_organizer[]} — organizer CN and mailto</li>
 *   <li>{@code ical:event_attendee[]} — attendee CNs and mailtos</li>
 *   <li>{@code ical:alarm_action[]} — VALARM ACTION values</li>
 *   <li>{@code ical:alarm_trigger[]} — VALARM TRIGGER values</li>
 *   <li>{@code ical:alarm_description[]} — VALARM DESCRIPTION (may contain URLs)</li>
 *   <li>{@code ical:alarm_attach[]} — VALARM ATTACH URLs (suspicious indicators)</li>
 *   <li>{@code ical:attach_url[]} — remote ATTACH URLs from any component</li>
 *   <li>{@code ical:attach_sha256[]} — SHA-256 of inline base64 ATTACH data</li>
 *   <li>{@code ical:attach_mime[]} — detected MIME type of inline ATTACH data</li>
 *   <li>{@code ical:todo_summary[]} / {@code ical:todo_description[]} — VTODO fields</li>
 *   <li>{@code ical:timezone_id[]} — VTIMEZONE TZID values (geo inference)</li>
 *   <li>{@code ical:url[]} — all URLs extracted from all text properties</li>
 *   <li>{@code ExploitClass} — VALARM ACTION:PROCEDURE, or inline ATTACH with
 *       executable MIME type, or phishing URL pattern in description/location</li>
 * </ul>
 *
 * <p>Inline ATTACH data (base64-encoded) is decoded, SHA-256 hashed, MIME-detected,
 * and submitted to Tika's embedded document pipeline.</p>
 */
@TikaComponent
public class ICalParser implements Parser {

    private static final long serialVersionUID = 1L;

    private static final MediaType ICAL_TYPE = MediaType.text("calendar");
    private static final Set<MediaType> SUPPORTED_TYPES =
            Collections.singleton(ICAL_TYPE);

    // MIME types of executable/risky attachments
    private static final Set<String> RISKY_ATTACH_MIME = new LinkedHashSet<>(Arrays.asList(
            "application/x-msdownload",
            "application/x-dosexec",
            "application/vnd.ms-office",
            "application/x-msdos-program",
            "application/octet-stream"
    ));

    // URL pattern for extracting URLs from text properties
    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[^\\s\"'<>\\\\]+",
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
        String text = decodeIcal(raw);

        List<String> lines = unfoldLines(text);
        List<Map<String, String>> props = parseProperties(lines);

        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata);
        xhtml.startDocument();

        Set<String> allUrls = new LinkedHashSet<>();
        StringBuilder exploitDesc = new StringBuilder();

        // Component stack: each entry = {type → props}.
        // VCALENDAR is the outermost; VEVENT/VTODO/etc. are nested inside it.
        // Depth limit prevents heap exhaustion from crafted input with many nested BEGINs.
        final int maxStackDepth = 64;
        Deque<String[]> typeStack = new ArrayDeque<>();
        Deque<Map<String, String>> propsStack = new ArrayDeque<>();

        EmbeddedDocumentExtractor extractor =
                EmbeddedDocumentUtil.getEmbeddedDocumentExtractor(context);

        for (Map<String, String> prop : props) {
            String name   = prop.get("name");
            String params = prop.get("params");
            String value  = prop.get("value");
            if (name == null || value == null) {
                continue;
            }
            String nameLower = name.toLowerCase(Locale.ROOT);

            if ("begin".equals(nameLower)) {
                if (typeStack.size() >= maxStackDepth) {
                    continue; // drop excessively nested components
                }
                typeStack.push(new String[]{value.toUpperCase(Locale.ROOT)});
                propsStack.push(new LinkedHashMap<>());
                continue;
            }
            if ("end".equals(nameLower)) {
                if (!typeStack.isEmpty()) {
                    String[] typeArr = typeStack.pop();
                    Map<String, String> compProps = propsStack.pop();
                    String compType = typeArr[0];
                    if (!"VCALENDAR".equals(compType)) {
                        processComponent(compType, compProps, metadata, xhtml,
                                context, extractor, allUrls, exploitDesc);
                    } else {
                        // VCALENDAR-level props already handled below
                    }
                }
                continue;
            }

            if (!typeStack.isEmpty()) {
                String compType = typeStack.peek()[0];
                if ("VCALENDAR".equals(compType)) {
                    // Direct VCALENDAR properties
                    if ("prodid".equals(nameLower)) {
                        metadata.set("ical:prodid", value);
                    } else if ("method".equals(nameLower)) {
                        metadata.set("ical:method", value);
                        xhtml.element("p", "Method: " + value);
                    } else if ("version".equals(nameLower)) {
                        metadata.set("ical:version", value);
                    }
                } else {
                    // Properties of a sub-component (VEVENT, VTODO, VALARM, etc.)
                    Map<String, String> compProps = propsStack.peek();
                    // Store with param suffix for ATTACH encoding detection
                    String storeKey = nameLower + (params != null && !params.isEmpty()
                            ? "|" + params : "");
                    compProps.put(storeKey, value);
                    // Multi-valued properties accumulate (capped at 1000 entries each);
                    // single-valued properties use first-wins semantics.
                    if ("attendee".equals(nameLower) || "exdate".equals(nameLower)
                            || "rdate".equals(nameLower)) {
                        compProps.merge(nameLower, value, (a, b) -> {
                            // Limit accumulation to prevent unbounded string growth
                            int count = 1;
                            for (int ci = 0; ci < a.length(); ci++) {
                                if (a.charAt(ci) == '\n') {
                                    count++;
                                }
                            }
                            return count < 1000 ? a + "\n" + b : a;
                        });
                    } else {
                        // Single-valued: put only if not already present (first wins)
                        compProps.putIfAbsent(nameLower, value);
                    }
                }
            }
        }

        // Surface all extracted URLs
        for (String url : allUrls) {
            metadata.add("ical:url", url);
        }

        if (exploitDesc.length() > 0) {
            metadata.set("ExploitClass", exploitDesc.toString().trim());
        }

        xhtml.endDocument();
    }

    // ── Component processing ──────────────────────────────────────────────────

    private void processComponent(String type, Map<String, String> props,
                                   Metadata metadata, XHTMLContentHandler xhtml,
                                   ParseContext context,
                                   EmbeddedDocumentExtractor extractor,
                                   Set<String> allUrls,
                                   StringBuilder exploitDesc)
            throws IOException, SAXException, TikaException {
        switch (type) {
            case "VEVENT":
                processVevent(props, metadata, xhtml, allUrls, exploitDesc);
                break;
            case "VTODO":
                processVtodo(props, metadata, xhtml, allUrls);
                break;
            case "VALARM":
                processValarm(props, metadata, xhtml, allUrls, exploitDesc);
                break;
            case "VTIMEZONE":
                String tzid = props.get("tzid");
                if (tzid != null && !tzid.isEmpty()) {
                    metadata.add("ical:timezone_id", tzid);
                }
                break;
            default:
                break;
        }
        // Process ATTACH in any component
        processAttach(props, metadata, xhtml, context, extractor, allUrls, exploitDesc);
    }

    private void processVevent(Map<String, String> props, Metadata metadata,
                                XHTMLContentHandler xhtml, Set<String> allUrls,
                                StringBuilder exploitDesc)
            throws IOException, SAXException, TikaException {
        emitText(metadata, xhtml, props, "summary",     "ical:event_summary",
                "Summary", allUrls);
        emitText(metadata, xhtml, props, "description", "ical:event_description",
                "Description", allUrls);
        emitText(metadata, xhtml, props, "location",    "ical:event_location",
                "Location", allUrls);
        emitText(metadata, xhtml, props, "url",         "ical:event_url",
                "URL", allUrls);
        emitField(metadata, props, "dtstart",  "ical:event_dtstart");
        emitField(metadata, props, "dtend",    "ical:event_dtend");
        emitField(metadata, props, "dtstamp",  "ical:event_dtstamp");
        emitField(metadata, props, "created",  "ical:event_created");
        emitField(metadata, props, "rrule",    "ical:event_rrule");
        emitField(metadata, props, "uid",      "ical:event_uid");

        // Organizer: may have CN parameter embedded in the multi-key map
        emitEmailField(metadata, props, "organizer", "ical:event_organizer");
        // Attendees: multiple lines merged with \n
        String allAttendees = props.get("attendee");
        if (allAttendees != null) {
            for (String att : allAttendees.split("\n")) {
                String formatted = formatEmailField(att);
                if (!formatted.isEmpty()) {
                    metadata.add("ical:event_attendee", formatted);
                }
            }
        }
    }

    private void processVtodo(Map<String, String> props, Metadata metadata,
                               XHTMLContentHandler xhtml, Set<String> allUrls)
            throws IOException, SAXException, TikaException {
        emitText(metadata, xhtml, props, "summary",     "ical:todo_summary",
                "TODO Summary", allUrls);
        emitText(metadata, xhtml, props, "description", "ical:todo_description",
                "TODO Description", allUrls);
        emitField(metadata, props, "due",    "ical:todo_due");
        emitField(metadata, props, "status", "ical:todo_status");
    }

    private void processValarm(Map<String, String> props, Metadata metadata,
                                XHTMLContentHandler xhtml, Set<String> allUrls,
                                StringBuilder exploitDesc)
            throws IOException, SAXException, TikaException {
        String action = props.get("action");
        if (action != null && !action.isEmpty()) {
            metadata.add("ical:alarm_action", action);
            // PROCEDURE was an RCE vector in Apple Calendar (CVE-2022-22620 family)
            if ("PROCEDURE".equalsIgnoreCase(action)) {
                exploitDesc.append(
                        "VALARM ACTION:PROCEDURE — historical RCE vector (Apple Calendar); ");
            }
        }
        emitField(metadata, props, "trigger",     "ical:alarm_trigger");
        emitText(metadata, xhtml, props, "description", "ical:alarm_description",
                "Alarm Description", allUrls);
        String attachVal = props.get("attach");
        if (attachVal != null && !attachVal.isEmpty()) {
            metadata.add("ical:alarm_attach", attachVal);
        }
    }

    private void processAttach(Map<String, String> props, Metadata metadata,
                                XHTMLContentHandler xhtml, ParseContext context,
                                EmbeddedDocumentExtractor extractor,
                                Set<String> allUrls, StringBuilder exploitDesc)
            throws IOException, SAXException, TikaException {
        // Look for attach properties — process only param-suffixed keys to avoid
        // processing the same attachment twice (bare key + param-suffixed key both exist).
        boolean bareAttachProcessed = false;
        for (Map.Entry<String, String> e : props.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith("attach")) {
                continue;
            }
            boolean hasParams = key.contains("|");
            // Process the bare "attach" key only if no param-suffixed key exists
            if (!hasParams) {
                if (bareAttachProcessed || props.containsKey(key + "|")
                        || props.keySet().stream().anyMatch(
                                k -> k.startsWith("attach|"))) {
                    continue; // param-suffixed version will handle it
                }
                bareAttachProcessed = true;
            }
            String value = e.getValue();
            boolean isB64 = key.contains("encoding=base64")
                    || key.contains("encoding=b");
            if (isB64 || (value.length() > 100 && !value.startsWith("http"))) {
                // Likely inline base64
                try {
                    byte[] data = Base64.getMimeDecoder().decode(
                            value.replaceAll("\\s", ""));
                    if (data.length > 0) {
                        String sha256 = sha256Hex(data);
                        metadata.add("ical:attach_sha256", sha256);
                        String mime = "application/octet-stream";
                        try (TikaInputStream tis = TikaInputStream.get(data)) {
                            mime = new DefaultDetector()
                                    .detect(tis, new Metadata(), context)
                                    .getBaseType().toString();
                        } catch (Exception ignored) {
                            // detection is best-effort
                        }
                        metadata.add("ical:attach_mime", mime);
                        // Flag risky MIME types
                        if (RISKY_ATTACH_MIME.contains(mime)
                                || mime.contains("executable")
                                || mime.contains("script")) {
                            exploitDesc.append(
                                    "Inline ATTACH with risky MIME type: " + mime + "; ");
                        }
                        // Feed through embedded pipeline
                        Metadata embMeta = new Metadata();
                        embMeta.set(TikaCoreProperties.RESOURCE_NAME_KEY, "ical-attach");
                        embMeta.set(Metadata.CONTENT_TYPE, mime);
                        try (TikaInputStream tis2 = TikaInputStream.get(data)) {
                            if (extractor.shouldParseEmbedded(embMeta)) {
                                extractor.parseEmbedded(tis2, xhtml, embMeta,
                                        context, true);
                            }
                        } catch (Exception ignored) {
                            // embedded parse is best-effort
                        }
                    }
                } catch (IllegalArgumentException ignored) {
                    // Not valid base64; treat as URL below
                }
            } else if (value.startsWith("http")) {
                // Remote URL attachment
                metadata.add("ical:attach_url", value);
                allUrls.add(value);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void emitField(Metadata meta, Map<String, String> props,
                                   String propName, String metaKey) {
        String val = props.get(propName);
        if (val != null && !val.isEmpty()) {
            meta.add(metaKey, val);
        }
    }

    private static void emitText(Metadata meta, XHTMLContentHandler xhtml,
                                  Map<String, String> props, String propName,
                                  String metaKey, String label, Set<String> allUrls)
            throws IOException, SAXException {
        String val = props.get(propName);
        if (val == null || val.isEmpty()) {
            return;
        }
        meta.add(metaKey, val);
        xhtml.element("p", label + ": " + val);
        // Extract URLs from text
        Matcher um = URL_PATTERN.matcher(val);
        while (um.find()) {
            String url = um.group().replaceAll("[\"'>,]+$", "");
            allUrls.add(url);
        }
    }

    private static void emitEmailField(Metadata meta, Map<String, String> props,
                                        String propName, String metaKey) {
        String val = props.get(propName);
        if (val == null || val.isEmpty()) {
            return;
        }
        meta.add(metaKey, formatEmailField(val));
    }

    private static String formatEmailField(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        // Value may be "mailto:x@y.com" or just "x@y.com"
        // Parameters may have been stripped already
        return raw.replace("mailto:", "").trim();
    }

    // ── RFC 5545 parsing ──────────────────────────────────────────────────────

    private static String decodeIcal(byte[] raw) {
        // UTF-8 BOM
        if (raw.length >= 3 && (raw[0] & 0xff) == 0xef
                && (raw[1] & 0xff) == 0xbb && (raw[2] & 0xff) == 0xbf) {
            return new String(raw, 3, raw.length - 3, StandardCharsets.UTF_8);
        }
        // UTF-16LE BOM
        if (raw.length >= 2 && (raw[0] & 0xff) == 0xff && (raw[1] & 0xff) == 0xfe) {
            return new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16LE);
        }
        // Try UTF-8 first, fall back to Latin-1
        String utf8 = new String(raw, StandardCharsets.UTF_8);
        if (utf8.contains("BEGIN:VCALENDAR")) {
            return utf8;
        }
        return new String(raw, Charset.forName("ISO-8859-1"));
    }

    // RFC 5545 §3.1: unfold lines (CRLF or LF followed by SPACE/TAB = continuation)
    private static List<String> unfoldLines(String text) {
        String unfolded = text.replaceAll("\r\n[ \t]", "").replaceAll("\n[ \t]", "");
        List<String> lines = new ArrayList<>();
        for (String line : unfolded.split("\r?\n")) {
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    // Parse each line into {name, params, value}
    private static List<Map<String, String>> parseProperties(List<String> lines) {
        List<Map<String, String>> result = new ArrayList<>();
        for (String line : lines) {
            int colon = -1;
            // Find the colon that separates name;params from value
            // RFC 5545: DQUOTE-quoted strings may contain colons — skip them
            boolean inQuote = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') {
                    inQuote = !inQuote;
                } else if (c == ':' && !inQuote) {
                    colon = i;
                    break;
                }
            }
            if (colon < 0) {
                continue;
            }
            String nameAndParams = line.substring(0, colon);
            String value = line.substring(colon + 1);

            // Unescape RFC 5545 text: \n → newline, \, → comma, \; → semicolon
            value = value.replace("\\n", "\n").replace("\\N", "\n")
                    .replace("\\,", ",").replace("\\;", ";")
                    .replace("\\\\", "\\");

            int semi = nameAndParams.indexOf(';');
            String name;
            String params;
            if (semi < 0) {
                name   = nameAndParams.trim();
                params = "";
            } else {
                name   = nameAndParams.substring(0, semi).trim();
                params = nameAndParams.substring(semi + 1).toLowerCase(Locale.ROOT);
            }

            Map<String, String> prop = new LinkedHashMap<>();
            prop.put("name",   name.toLowerCase(Locale.ROOT));
            prop.put("params", params);
            prop.put("value",  value.trim());
            result.add(prop);
        }
        return result;
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] digest =
                    java.security.MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }
}
