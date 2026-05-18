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

    // Hard input ceiling for raw bytes — real ICS files are <1 MB; the cap
    // prevents heap exhaustion (UTF-32 amplification, attendee spam, deep nesting).
    static final int MAX_INPUT_BYTES = 32 * 1024 * 1024;

    // METHOD values that warrant a defender signal when seen on inbound mail.
    // PUBLISH = unsolicited "subscribe to this calendar" (calendar-spam vector),
    // CANCEL  = update-trick where attacker uses a CANCEL to override a prior
    //           benign invite or to slip past content scanners,
    // COUNTER = unusual on first-encounter mail; can be used to flip event
    //           details (location, URL) on a prior accepted invite.
    private static final Set<String> SUSPICIOUS_METHODS = new LinkedHashSet<>(Arrays.asList(
            "PUBLISH", "CANCEL", "COUNTER"
    ));

    // Calendar-bombing threshold: real meetings rarely have >50 attendees.
    private static final int ATTENDEE_BOMB_THRESHOLD = 50;

    // Back-dating threshold: DTSTAMP more than this many days before DTSTART
    // is suspicious (forged "earlier revision" pattern).
    private static final long BACKDATE_DAYS_THRESHOLD = 30L;

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

        // Cap input — real ICS files are <1 MB; 32 MB is a hard ceiling that
        // prevents heap exhaustion from a crafted file (e.g. UTF-32 amplification,
        // millions of nested BEGINs, or attendee-spam floods).
        byte[] raw = stream.readNBytes(MAX_INPUT_BYTES);
        boolean truncated = raw.length == MAX_INPUT_BYTES && stream.read() != -1;
        DecodedIcal decoded = decodeIcal(raw);
        String text = decoded.text;
        metadata.set("ical:source_encoding", decoded.encoding);
        if (decoded.suspicious) {
            metadata.add("ical:warning",
                    "Source encoded as " + decoded.encoding + " — uncommon for ICS; "
                            + "many email gateways scan ASCII/UTF-8 only and can miss this content");
        }
        if (truncated) {
            metadata.add("ical:warning",
                    "Input truncated at " + MAX_INPUT_BYTES + " bytes — "
                            + "any content beyond was not parsed");
        }

        List<String> lines = unfoldLines(text);
        List<Map<String, String>> props = parseProperties(lines);

        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata);
        xhtml.startDocument();

        Set<String> allUrls = new LinkedHashSet<>();
        StringBuilder exploitDesc = new StringBuilder();
        // Track UIDs seen across components in this file. Same UID in multiple
        // VEVENTs is the "in-file update chain" shape (attacker sends one ICS
        // with both an original and an overriding revision).
        Set<String> seenUids = new LinkedHashSet<>();

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
                                context, extractor, allUrls, exploitDesc,
                                seenUids);
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
                        String methodUpper = value.trim().toUpperCase(Locale.ROOT);
                        if (SUSPICIOUS_METHODS.contains(methodUpper)) {
                            exploitDesc.append(
                                    "Calendar METHOD=" + methodUpper
                                  + " — unusual for unsolicited mail "
                                  + "(PUBLISH=subscription spam, CANCEL=update-bypass, "
                                  + "COUNTER=event override); ");
                        }
                    } else if ("version".equals(nameLower)) {
                        metadata.set("ical:version", value);
                    } else if ("x-wr-calname".equals(nameLower)) {
                        metadata.set("ical:calendar_name", value);
                        xhtml.element("p", "Calendar: " + value);
                    } else if ("x-wr-caldesc".equals(nameLower)) {
                        metadata.set("ical:calendar_description", value);
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
                                   StringBuilder exploitDesc,
                                   Set<String> seenUids)
            throws IOException, SAXException, TikaException {
        switch (type) {
            case "VEVENT":
                processVevent(props, metadata, xhtml, allUrls, exploitDesc, seenUids);
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
                                StringBuilder exploitDesc, Set<String> seenUids)
            throws IOException, SAXException, TikaException {
        emitText(metadata, xhtml, props, "summary",     "ical:event_summary",
                "Summary", allUrls);
        emitText(metadata, xhtml, props, "description", "ical:event_description",
                "Description", allUrls);
        emitText(metadata, xhtml, props, "location",    "ical:event_location",
                "Location", allUrls);
        emitText(metadata, xhtml, props, "url",         "ical:event_url",
                "URL", allUrls);
        // X-ALT-DESC carries HTML alternate description — primary phishing vector
        emitText(metadata, xhtml, props, "x-alt-desc", "ical:event_description_html",
                "HTML Description", allUrls);
        emitField(metadata, props, "dtstart",       "ical:event_dtstart");
        emitField(metadata, props, "dtend",         "ical:event_dtend");
        emitField(metadata, props, "dtstamp",       "ical:event_dtstamp");
        emitField(metadata, props, "created",       "ical:event_created");
        emitField(metadata, props, "last-modified", "ical:event_last_modified");
        emitField(metadata, props, "rrule",         "ical:event_rrule");
        emitField(metadata, props, "uid",           "ical:event_uid");
        emitField(metadata, props, "status",        "ical:event_status");
        emitField(metadata, props, "categories",    "ical:event_categories");
        emitField(metadata, props, "class",         "ical:event_class");
        emitField(metadata, props, "sequence",      "ical:event_sequence");
        emitField(metadata, props, "transp",        "ical:event_transp");

        // Organizer — extract CN from param-suffixed key
        String organizer = getFieldWithCN(props, "organizer");
        if (!organizer.isEmpty()) {
            metadata.add("ical:event_organizer", organizer);
        }
        // Attendees — structured extraction of CN, ROLE, PARTSTAT from param keys
        int attendeeCount = processAttendeesStructured(props, metadata);

        // ── Update / revision signals ─────────────────────────────────────────
        // 1. SEQUENCE > 0 means this VEVENT is a revision, not the original
        //    invite. Common attacker shape: send an original benign-looking
        //    invite, then a SEQUENCE=1 revision with phishing content that
        //    auto-applies in clients that honour the original.
        String seqRaw = props.get("sequence");
        if (seqRaw != null && !seqRaw.isEmpty()) {
            try {
                int seq = Integer.parseInt(seqRaw.trim());
                if (seq > 0) {
                    metadata.set("ical:event_revision", "true");
                    exploitDesc.append(
                            "VEVENT SEQUENCE=" + seq
                          + " — this is a revision/update, not the original invite "
                          + "(revisions can silently override prior accepted events); ");
                }
            } catch (NumberFormatException ignored) {
                // malformed sequence — skip
            }
        }

        // 2. Same UID appearing in multiple VEVENTs in one ICS file = in-file
        //    update chain (original + override packaged together).
        String uid = props.get("uid");
        if (uid != null && !uid.isEmpty()) {
            if (!seenUids.add(uid)) {
                exploitDesc.append(
                        "Multiple VEVENTs share UID=" + uid
                      + " — in-file update chain shape "
                      + "(original event packaged with an overriding revision); ");
            }
        }

        // 3. Calendar bombing — implausibly large attendee count for an invite.
        if (attendeeCount > ATTENDEE_BOMB_THRESHOLD) {
            metadata.add("ical:warning",
                    "VEVENT has " + attendeeCount + " ATTENDEEs (>"
                  + ATTENDEE_BOMB_THRESHOLD + " — possible calendar-bombing)");
        }

        // 4. DTSTAMP far before DTSTART = forged "earlier revision" timestamp.
        String dtstamp = props.get("dtstamp");
        String dtstart = props.get("dtstart");
        long backdateDays = backdateGapDays(dtstamp, dtstart);
        if (backdateDays > BACKDATE_DAYS_THRESHOLD) {
            exploitDesc.append(
                    "DTSTAMP precedes DTSTART by " + backdateDays
                  + " days (>" + BACKDATE_DAYS_THRESHOLD
                  + ") — back-dated event, often used to fake legitimate followup; ");
        }
    }

    /**
     * Returns the number of days DTSTAMP precedes DTSTART (positive when stamp
     * is earlier than start), or -1 if either timestamp can't be parsed or
     * the relationship doesn't suggest back-dating.
     */
    private static long backdateGapDays(String dtstamp, String dtstart) {
        if (dtstamp == null || dtstart == null) {
            return -1;
        }
        Long stampEpochSec = parseICalDate(dtstamp);
        Long startEpochSec = parseICalDate(dtstart);
        if (stampEpochSec == null || startEpochSec == null) {
            return -1;
        }
        long deltaSec = startEpochSec - stampEpochSec;
        if (deltaSec <= 0) {
            return -1;
        }
        return deltaSec / 86400L;
    }

    /**
     * Parse an iCalendar DATE-TIME or DATE value into epoch seconds. Accepts
     * the common forms YYYYMMDDTHHMMSSZ, YYYYMMDDTHHMMSS, and YYYYMMDD. Strips
     * any TZID prefix that may have leaked in. Returns null on parse failure.
     */
    private static Long parseICalDate(String raw) {
        String s = raw.trim();
        // Strip any leading TZID=...: parameter that may have stuck to the value
        int colon = s.lastIndexOf(':');
        if (colon >= 0 && colon < s.length() - 1) {
            s = s.substring(colon + 1);
        }
        // Strip Z
        boolean hasZ = s.endsWith("Z");
        if (hasZ) {
            s = s.substring(0, s.length() - 1);
        }
        try {
            int year, month, day, hour = 0, min = 0, sec = 0;
            if (s.length() < 8) {
                return null;
            }
            year  = Integer.parseInt(s.substring(0, 4));
            month = Integer.parseInt(s.substring(4, 6));
            day   = Integer.parseInt(s.substring(6, 8));
            if (s.length() >= 15 && s.charAt(8) == 'T') {
                hour = Integer.parseInt(s.substring(9, 11));
                min  = Integer.parseInt(s.substring(11, 13));
                sec  = Integer.parseInt(s.substring(13, 15));
            }
            // Simple proleptic Gregorian conversion via java.time
            return java.time.OffsetDateTime.of(year, month, day, hour, min, sec, 0,
                    java.time.ZoneOffset.UTC).toEpochSecond();
        } catch (Exception e) {
            return null;
        }
    }

    private void processVtodo(Map<String, String> props, Metadata metadata,
                               XHTMLContentHandler xhtml, Set<String> allUrls)
            throws IOException, SAXException, TikaException {
        emitText(metadata, xhtml, props, "summary",     "ical:todo_summary",
                "TODO Summary", allUrls);
        emitText(metadata, xhtml, props, "description", "ical:todo_description",
                "TODO Description", allUrls);
        emitField(metadata, props, "due",              "ical:todo_due");
        emitField(metadata, props, "dtstart",          "ical:todo_dtstart");
        emitField(metadata, props, "status",           "ical:todo_status");
        emitField(metadata, props, "categories",       "ical:todo_categories");
        emitField(metadata, props, "priority",         "ical:todo_priority");
        emitField(metadata, props, "percent-complete", "ical:todo_percent_complete");
        emitField(metadata, props, "uid",              "ical:todo_uid");
        emitField(metadata, props, "last-modified",    "ical:todo_last_modified");
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

    /**
     * Returns "CN <email>" for a property that may have a CN parameter,
     * falling back to bare email if no CN key found.
     */
    private static String getFieldWithCN(Map<String, String> props, String propName) {
        String val = props.get(propName);
        if (val == null || val.isEmpty()) {
            return "";
        }
        String email = val.replace("mailto:", "").trim();
        String cn = null;
        for (String key : props.keySet()) {
            if (key.startsWith(propName + "|")) {
                String params = key.substring(propName.length() + 1);
                cn = extractParam(params, "cn");
                break;
            }
        }
        if (cn != null && !cn.isEmpty()) {
            return email.isEmpty() ? cn : cn + " <" + email + ">";
        }
        return email;
    }

    /** Returns the number of attendees emitted (used for calendar-bombing detection). */
    private static int processAttendeesStructured(Map<String, String> props,
                                                    Metadata metadata) {
        int count = 0;
        boolean foundParamKey = false;
        for (Map.Entry<String, String> e : props.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith("attendee|")) {
                continue;
            }
            foundParamKey = true;
            String params = key.substring("attendee|".length());
            String email  = e.getValue().replace("mailto:", "").trim();
            String cn       = extractParam(params, "cn");
            String role     = extractParam(params, "role");
            String partstat = extractParam(params, "partstat");
            StringBuilder sb = new StringBuilder();
            if (cn != null && !cn.isEmpty()) {
                sb.append(cn);
            }
            if (!email.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(" <").append(email).append(">");
                } else {
                    sb.append(email);
                }
            }
            if (role != null && !role.isEmpty()) {
                sb.append(" [").append(role).append("]");
            }
            if (partstat != null && !partstat.isEmpty()) {
                sb.append(" (").append(partstat).append(")");
            }
            String out = sb.toString().trim();
            if (!out.isEmpty()) {
                metadata.add("ical:event_attendee", out);
                count++;
            }
            if (partstat != null && !partstat.isEmpty()) {
                metadata.add("ical:event_attendee_partstat",
                        partstat.toUpperCase(Locale.ROOT));
            }
        }
        if (!foundParamKey) {
            // Bare-key fallback: attendees without any parameters
            String allAttendees = props.get("attendee");
            if (allAttendees != null) {
                for (String att : allAttendees.split("\n")) {
                    String formatted = att.replace("mailto:", "").trim();
                    if (!formatted.isEmpty()) {
                        metadata.add("ical:event_attendee", formatted);
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static String extractParam(String params, String name) {
        for (String p : params.split(";")) {
            int eq = p.indexOf('=');
            if (eq > 0 && p.substring(0, eq).trim().equalsIgnoreCase(name)) {
                String v = p.substring(eq + 1).trim();
                return v.replaceAll("^\"|\"$", ""); // strip optional surrounding quotes
            }
        }
        return null;
    }

    // ── RFC 5545 parsing ──────────────────────────────────────────────────────

    /** Result of {@link #decodeIcal(byte[])} — decoded text plus the detected encoding. */
    static final class DecodedIcal {
        final String text;
        final String encoding;
        /** True when the source is encoded as something other than UTF-8/ASCII —
         *  uncommon for legitimate ICS and useful as an evasion signal. */
        final boolean suspicious;

        DecodedIcal(String text, String encoding, boolean suspicious) {
            this.text = text;
            this.encoding = encoding;
            this.suspicious = suspicious;
        }
    }

    static DecodedIcal decodeIcal(byte[] raw) {
        // UTF-8 BOM (EF BB BF)
        if (raw.length >= 3 && (raw[0] & 0xff) == 0xef
                && (raw[1] & 0xff) == 0xbb && (raw[2] & 0xff) == 0xbf) {
            return new DecodedIcal(
                    new String(raw, 3, raw.length - 3, StandardCharsets.UTF_8),
                    "UTF-8 (BOM)", false);
        }
        // UTF-32LE BOM (FF FE 00 00) — must come BEFORE UTF-16LE BOM check
        if (raw.length >= 4 && (raw[0] & 0xff) == 0xff && (raw[1] & 0xff) == 0xfe
                && raw[2] == 0 && raw[3] == 0) {
            return new DecodedIcal(
                    new String(raw, 4, raw.length - 4, Charset.forName("UTF-32LE")),
                    "UTF-32LE (BOM)", true);
        }
        // UTF-32BE BOM (00 00 FE FF)
        if (raw.length >= 4 && raw[0] == 0 && raw[1] == 0
                && (raw[2] & 0xff) == 0xfe && (raw[3] & 0xff) == 0xff) {
            return new DecodedIcal(
                    new String(raw, 4, raw.length - 4, Charset.forName("UTF-32BE")),
                    "UTF-32BE (BOM)", true);
        }
        // UTF-16LE BOM (FF FE)
        if (raw.length >= 2 && (raw[0] & 0xff) == 0xff && (raw[1] & 0xff) == 0xfe) {
            return new DecodedIcal(
                    new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16LE),
                    "UTF-16LE (BOM)", true);
        }
        // UTF-16BE BOM (FE FF)
        if (raw.length >= 2 && (raw[0] & 0xff) == 0xfe && (raw[1] & 0xff) == 0xff) {
            return new DecodedIcal(
                    new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16BE),
                    "UTF-16BE (BOM)", true);
        }
        // BOM-less UTF-16LE / UTF-16BE — sniff for "BEGIN" at a small offset
        // (some files begin with a blank line or whitespace before BEGIN:VCALENDAR).
        if (sniffBomlessUtf16(raw, true)) {
            return new DecodedIcal(
                    new String(raw, 0, raw.length, StandardCharsets.UTF_16LE),
                    "UTF-16LE (no BOM)", true);
        }
        if (sniffBomlessUtf16(raw, false)) {
            return new DecodedIcal(
                    new String(raw, 0, raw.length, StandardCharsets.UTF_16BE),
                    "UTF-16BE (no BOM)", true);
        }
        // Try UTF-8 first
        String utf8 = new String(raw, StandardCharsets.UTF_8);
        if (utf8.contains("BEGIN:VCALENDAR")) {
            return new DecodedIcal(utf8, "UTF-8", false);
        }
        // Fall back to ISO-8859-1
        String latin1 = new String(raw, Charset.forName("ISO-8859-1"));
        boolean suspicious = !latin1.contains("BEGIN:VCALENDAR");
        return new DecodedIcal(latin1,
                suspicious ? "ISO-8859-1 (no VCALENDAR marker — possible exotic encoding)"
                           : "ISO-8859-1",
                suspicious);
    }

    /**
     * Sniff for BOM-less UTF-16-encoded {@code BEGIN} at the start of the buffer.
     * Real ICS files sometimes start with a blank line or whitespace before the
     * first line, so allow a small leading-whitespace window (up to 8 UTF-16 code
     * units of CR/LF/space/tab).
     *
     * @param raw     input bytes
     * @param littleEndian true for UTF-16LE, false for UTF-16BE
     */
    private static boolean sniffBomlessUtf16(byte[] raw, boolean littleEndian) {
        if (raw.length < 14 || (raw.length & 1) != 0) {
            // Even-length only matters for the heuristic; a UTF-16 stream with an
            // odd byte count is malformed but we don't need to reject here.
        }
        final int maxLeadingWs = 8; // up to 8 UTF-16 code units of leading whitespace
        int max = Math.min(raw.length - 14, maxLeadingWs * 2);
        for (int off = 0; off <= max; off += 2) {
            int hi = littleEndian ? raw[off + 1] & 0xff : raw[off]     & 0xff;
            int lo = littleEndian ? raw[off]     & 0xff : raw[off + 1] & 0xff;
            if (off > 0) {
                // Must be a UTF-16 whitespace code unit: NUL hi byte + space/tab/CR/LF lo
                if (hi != 0) {
                    return false;
                }
                if (lo != ' ' && lo != '\t' && lo != '\r' && lo != '\n') {
                    return false;
                }
                continue;
            }
            // At offset `off`, check that bytes form B E G I N (5 UTF-16 code units)
            if (matchesBegin(raw, off, littleEndian)) {
                return true;
            }
        }
        // Also check at each whitespace-skipping offset
        for (int off = 2; off <= max; off += 2) {
            if (matchesBegin(raw, off, littleEndian)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesBegin(byte[] raw, int off, boolean littleEndian) {
        // 5 chars × 2 bytes = 10 bytes for "BEGIN"
        if (off + 10 > raw.length) {
            return false;
        }
        char[] target = {'B', 'E', 'G', 'I', 'N'};
        for (int i = 0; i < target.length; i++) {
            int b0 = raw[off + 2 * i]     & 0xff;
            int b1 = raw[off + 2 * i + 1] & 0xff;
            int hi = littleEndian ? b1 : b0;
            int lo = littleEndian ? b0 : b1;
            if (hi != 0 || lo != target[i]) {
                return false;
            }
        }
        return true;
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
