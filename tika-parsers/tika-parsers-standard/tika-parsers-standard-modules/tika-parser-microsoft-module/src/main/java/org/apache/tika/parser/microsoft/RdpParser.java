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
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.config.TikaComponent;
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
 * Parser for Windows Remote Desktop Protocol configuration files (.rdp).
 *
 * <p>RDP files are text-based key-value documents with lines of the form
 * {@code key:type:value}, where type is {@code s} (string), {@code i} (integer),
 * or {@code b} (binary/base64).  Encoding is typically UTF-16LE with BOM, but
 * UTF-8 and UTF-8-without-BOM are also seen from third-party tools.</p>
 *
 * <p>RDP files are a phishing initial-access vector: a crafted attachment
 * silently connects the victim to an attacker-controlled RDP server, redirecting
 * drives, clipboard, WebAuthn credentials, and device peripherals.  The
 * {@code pcb:b:} field ("pre-configured certificate blob") fingerprints the
 * attacker's server and bypasses normal certificate warnings.</p>
 *
 * <p>Extracted metadata fields (all prefixed {@code rdp:}):</p>
 * <ul>
 *   <li>{@code rdp:full_address} — target host:port (primary connection IOC)</li>
 *   <li>{@code rdp:alternate_full_address} — fallback target</li>
 *   <li>{@code rdp:gatewayhostname} — RD Gateway host (lateral movement indicator)</li>
 *   <li>{@code rdp:username} — pre-configured username, if any</li>
 *   <li>{@code rdp:authentication_level} — 0=any cert, 2=valid cert required</li>
 *   <li>{@code rdp:enablecredsspsupport} — CredSSP (NLA) enabled flag</li>
 *   <li>{@code rdp:enablerdsaadauth} — Azure AD auth flag</li>
 *   <li>{@code rdp:redirectwebauthn} — WebAuthn passthrough (credential harvest)</li>
 *   <li>{@code rdp:redirectclipboard} — clipboard redirect flag</li>
 *   <li>{@code rdp:drivestoredirect} — drive redirect scope ('*' = all drives)</li>
 *   <li>{@code rdp:camerastoredirect} — camera redirect scope</li>
 *   <li>{@code rdp:usbdevicestoredirect} — USB redirect scope</li>
 *   <li>{@code rdp:redirectsmartcards} — smart card redirect flag</li>
 *   <li>{@code rdp:remoteapplicationprogram} — RemoteApp name</li>
 *   <li>{@code rdp:remoteapplicationcmdline} — RemoteApp command line</li>
 *   <li>{@code rdp:remoteapplicationname} — RemoteApp display name</li>
 *   <li>{@code rdp:pcb_cert_subject} — X.509 subject DN from pcb: certificate blob</li>
 *   <li>{@code rdp:pcb_cert_issuer} — X.509 issuer DN from pcb: certificate blob</li>
 *   <li>{@code rdp:pcb_cert_san} — Subject Alternative Names from pcb: blob</li>
 *   <li>{@code rdp:pcb_cert_not_before} — certificate validity start (ISO-8601)</li>
 *   <li>{@code rdp:pcb_cert_not_after} — certificate validity end (ISO-8601)</li>
 *   <li>{@code ExploitClass} — set when authentication_level=0 (no cert validation),
 *       pcb: blob present (attacker pre-supplied certificate), or all-drive redirect</li>
 * </ul>
 *
 * <p>The {@code pcb:b:} binary blob (PKCS#7 / DER certificate chain) is
 * base64-decoded, parsed as X.509 via {@code java.security.cert.CertificateFactory},
 * and key fields are surfaced as metadata.  The raw bytes are also submitted to
 * Tika's embedded document pipeline for further extraction.</p>
 *
 * <p>Field names and IOC selection derived from wmetcalf/rdp_holiday (MIT License,
 * https://github.com/wmetcalf/rdp_holiday); no source was copied.</p>
 */
@TikaComponent
public class RdpParser implements Parser {

    private static final long serialVersionUID = 1L;

    private static final MediaType RDP_TYPE = MediaType.application("x-rdp");
    private static final Set<MediaType> SUPPORTED_TYPES = Collections.singleton(RDP_TYPE);

    // Fields we extract to metadata (lowercase key → rdp: metadata name)
    private static final List<String> SURFACE_FIELDS = Arrays.asList(
            "full address",
            "alternate full address",
            "gatewayhostname",
            "username",
            "authentication level",
            "enablecredsspsupport",
            "enablerdsaadauth",
            "redirectwebauthn",
            "redirectclipboard",
            "drivestoredirect",
            "camerastoredirect",
            "usbdevicestoredirect",
            "redirectsmartcards",
            "keyboardhook",
            "audiomode",
            "audiocapturemode",
            "redirectprinters",
            "remoteapplicationmode",
            "remoteapplicationprogram",
            "remoteapplicationcmdline",
            "remoteapplicationname",
            "remoteapplicationfile",
            "bandwidthautodetect",
            "networkautodetect"
    );

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED_TYPES;
    }

    @Override
    public void parse(TikaInputStream stream, ContentHandler handler,
                      Metadata metadata, ParseContext context)
            throws IOException, SAXException, TikaException {

        byte[] raw = stream.readAllBytes();
        String text = decode(raw);

        Map<String, String[]> fields = parseFields(text);

        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata);
        xhtml.startDocument();

        // Surface string and integer fields
        for (String key : SURFACE_FIELDS) {
            String[] kv = fields.get(key);
            if (kv != null && kv[1] != null && !kv[1].isEmpty()) {
                String metaKey = "rdp:" + key.replace(' ', '_');
                metadata.set(metaKey, kv[1]);
                xhtml.element("p", key + ": " + kv[1]);
            }
        }

        // pcb:b: certificate blob
        String[] pcb = fields.get("pcb");
        if (pcb != null && "b".equals(pcb[0]) && pcb[1] != null && !pcb[1].isEmpty()) {
            parseCertBlob(pcb[1], metadata, xhtml, context,
                    EmbeddedDocumentUtil.getEmbeddedDocumentExtractor(context));
        }

        // ExploitClass assessment
        String authLevel = fieldValue(fields, "authentication level");
        String drives    = fieldValue(fields, "drivestoredirect");
        boolean noAuthCheck = "0".equals(authLevel);
        boolean allDrives   = "*".equals(drives);
        boolean hasPcb      = pcb != null;
        boolean hasWebAuthn = "1".equals(fieldValue(fields, "redirectwebauthn"));

        if (noAuthCheck || hasPcb) {
            StringBuilder sb = new StringBuilder();
            sb.append("RDP phishing configuration:");
            if (noAuthCheck) {
                sb.append(" authentication_level=0 (any certificate accepted)");
            }
            if (hasPcb) {
                sb.append(" pcb: attacker pre-supplied certificate blob");
            }
            if (allDrives) {
                sb.append(" drivestoredirect=* (all drives redirected)");
            }
            if (hasWebAuthn) {
                sb.append(" redirectwebauthn=1 (credential harvest via WebAuthn)");
            }
            metadata.set("ExploitClass", sb.toString().trim());
        } else if (allDrives || hasWebAuthn) {
            StringBuilder sb = new StringBuilder();
            sb.append("RDP high-risk redirect configuration:");
            if (allDrives) {
                sb.append(" drivestoredirect=* (all drives redirected)");
            }
            if (hasWebAuthn) {
                sb.append(" redirectwebauthn=1 (credential harvest via WebAuthn)");
            }
            metadata.set("ExploitClass", sb.toString().trim());
        }

        xhtml.endDocument();
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private static String decode(byte[] raw) {
        // UTF-16LE BOM (FF FE)
        if (raw.length >= 2 && (raw[0] & 0xff) == 0xff && (raw[1] & 0xff) == 0xfe) {
            return new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16LE);
        }
        // UTF-16BE BOM (FE FF)
        if (raw.length >= 2 && (raw[0] & 0xff) == 0xfe && (raw[1] & 0xff) == 0xff) {
            return new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16BE);
        }
        // UTF-32LE BOM (FF FE 00 00)
        if (raw.length >= 4
                && (raw[0] & 0xff) == 0xff && (raw[1] & 0xff) == 0xfe
                && raw[2] == 0x00 && raw[3] == 0x00) {
            return new String(raw, 4, raw.length - 4,
                    Charset.forName("UTF-32LE"));
        }
        // UTF-8 BOM (EF BB BF)
        if (raw.length >= 3 && (raw[0] & 0xff) == 0xef
                && (raw[1] & 0xff) == 0xbb && (raw[2] & 0xff) == 0xbf) {
            return new String(raw, 3, raw.length - 3, StandardCharsets.UTF_8);
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static Map<String, String[]> parseFields(String text) {
        Map<String, String[]> map = new LinkedHashMap<>();
        for (String rawLine : text.split("[\r\n]+")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) {
                continue;
            }
            // Format: key:type:value  (type is s/i/b)
            int first = line.indexOf(':');
            if (first < 0) {
                continue;
            }
            String key = line.substring(0, first).trim().toLowerCase(Locale.ROOT);
            String rest = line.substring(first + 1);
            int second = rest.indexOf(':');
            String type;
            String value;
            if (second >= 0) {
                type  = rest.substring(0, second).trim().toLowerCase(Locale.ROOT);
                value = rest.substring(second + 1).trim();
            } else {
                type  = "s";
                value = rest.trim();
            }
            map.put(key, new String[]{type, value});
        }
        return map;
    }

    private static String fieldValue(Map<String, String[]> fields, String key) {
        String[] kv = fields.get(key);
        return (kv != null && kv[1] != null) ? kv[1] : "";
    }

    // ── Certificate blob parsing ──────────────────────────────────────────────

    private void parseCertBlob(String b64, Metadata rootMeta,
                                XHTMLContentHandler xhtml, ParseContext context,
                                EmbeddedDocumentExtractor extractor)
            throws IOException, SAXException, TikaException {
        byte[] der;
        try {
            der = Base64.getDecoder().decode(b64.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            rootMeta.add("rdp:pcb_warning", "base64 decode failed: " + e.getMessage());
            return;
        }

        // Try to parse as X.509 DER certificate
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(
                    new java.io.ByteArrayInputStream(der));
            rootMeta.set("rdp:pcb_cert_subject",
                    cert.getSubjectX500Principal().getName());
            rootMeta.set("rdp:pcb_cert_issuer",
                    cert.getIssuerX500Principal().getName());
            rootMeta.set("rdp:pcb_cert_not_before",
                    cert.getNotBefore().toInstant().toString());
            rootMeta.set("rdp:pcb_cert_not_after",
                    cert.getNotAfter().toInstant().toString());
            // Subject Alternative Names
            try {
                java.util.Collection<java.util.List<?>> sans =
                        cert.getSubjectAlternativeNames();
                if (sans != null) {
                    for (java.util.List<?> san : sans) {
                        if (san.size() >= 2) {
                            rootMeta.add("rdp:pcb_cert_san",
                                    san.get(1).toString());
                        }
                    }
                }
            } catch (Exception ignored) {
                // SAN parsing is best-effort
            }
            xhtml.element("p", "Certificate subject: "
                    + cert.getSubjectX500Principal().getName());
        } catch (CertificateException e) {
            rootMeta.add("rdp:pcb_warning",
                    "X.509 parse failed (may be PKCS#7): " + e.getMessage());
        }

        // Feed raw bytes through embedded pipeline regardless of parse success
        Metadata embMeta = new Metadata();
        embMeta.set(TikaCoreProperties.RESOURCE_NAME_KEY, "rdp-pcb-cert");
        embMeta.set(Metadata.CONTENT_TYPE, "application/pkix-cert");
        try (TikaInputStream tis = TikaInputStream.get(der)) {
            if (extractor.shouldParseEmbedded(embMeta)) {
                extractor.parseEmbedded(tis, xhtml, embMeta, context, true);
            }
        } catch (Exception e) {
            rootMeta.add("rdp:pcb_warning", "embedded parse error: " + e.getMessage());
        }
    }
}
