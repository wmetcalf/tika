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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    // Fields we extract to metadata (lowercase key → rdp: metadata name).
    // Full set matching wmetcalf/rdp_holiday property_patterns.
    private static final List<String> SURFACE_FIELDS = Arrays.asList(
            "full address",
            "alternate full address",
            "alternate shell",
            "gatewayhostname",
            "gatewaycredentialssource",
            "gatewayprofileusagemethod",
            "gatewayusagemethod",
            "username",
            "authentication level",
            "autoreconnection enabled",
            "enablecredsspsupport",
            "enablerdsaadauth",
            "redirectwebauthn",
            "redirectclipboard",
            "redirectcomports",
            "redirectlocation",
            "redirectprinters",
            "redirectsmartcards",
            "drivestoredirect",
            "devicestoredirect",
            "camerastoredirect",
            "usbdevicestoredirect",
            "disableconnectionsharing",
            "promptcredentialonce",
            "keyboardhook",
            "audiomode",
            "audiocapturemode",
            "videoplaybackmode",
            "compression",
            "screen mode id",
            "bandwidthautodetect",
            "networkautodetect",
            "remoteapplicationmode",
            "remoteapplicationprogram",
            "remoteapplicationcmdline",
            "remoteapplicationexpandcmdline",
            "remoteapplicationexpandworkingdir",
            "remoteapplicationname",
            "remoteapplicationfile",
            "remoteapplicationicon",
            "signscope"
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

        // Surface string and integer fields as metadata only — the prior
        // duplicate "<p>key: value</p>" dump polluted full-text search with
        // field-name boilerplate. Downstream tools query rdp:* keys directly.
        for (String key : SURFACE_FIELDS) {
            String[] kv = fields.get(key);
            if (kv != null && kv[1] != null && !kv[1].isEmpty()) {
                String metaKey = "rdp:" + key.replace(' ', '_');
                metadata.set(metaKey, kv[1]);
            }
        }

        // pcb:b: certificate blob
        String[] pcb = fields.get("pcb");
        if (pcb != null && "b".equals(pcb[0]) && pcb[1] != null && !pcb[1].isEmpty()) {
            parseCertBlob(pcb[1], metadata, xhtml, context,
                    EmbeddedDocumentUtil.getEmbeddedDocumentExtractor(context));
        }

        // signature:s: — base64-encoded signed scope (RDMS/broker signature).
        // The raw value and hashes are surfaced for TI correlation.
        String[] sig = fields.get("signature");
        if (sig != null && "s".equals(sig[0]) && sig[1] != null && !sig[1].isEmpty()) {
            metadata.set("rdp:signature_raw", sig[1]);
            try {
                byte[] sigBytes = Base64.getDecoder().decode(
                        sig[1].replaceAll("\\s", ""));
                metadata.set("rdp:signature_sha256", sha256Hex(sigBytes));
                metadata.set("rdp:signature_sha1",   sha1Hex(sigBytes));
            } catch (IllegalArgumentException ignored) {
                metadata.set("rdp:signature_warning", "base64 decode failed");
            }
        }
        // signscope_but_missing_sig: flag if signscope present without signature
        String[] ss = fields.get("signscope");
        if (ss != null && ss[1] != null && !ss[1].isEmpty() && sig == null) {
            metadata.set("rdp:signscope_but_missing_sig", "true");
        }

        // ExploitClass assessment
        String authLevel = fieldValue(fields, "authentication level");
        String drives    = fieldValue(fields, "drivestoredirect");
        boolean noAuthCheck = "0".equals(authLevel);
        boolean allDrives   = "*".equals(drives);
        boolean hasPcb      = pcb != null;
        boolean hasWebAuthn = "1".equals(fieldValue(fields, "redirectwebauthn"));
        boolean selfSigned  = "true".equals(metadata.get("rdp:pcb_cert_self_signed"));

        if (noAuthCheck || hasPcb) {
            StringBuilder sb = new StringBuilder();
            sb.append("RDP phishing configuration:");
            if (noAuthCheck) {
                sb.append(" authentication_level=0 (any certificate accepted)");
            }
            if (hasPcb) {
                sb.append(" pcb: attacker pre-supplied certificate blob");
                if (selfSigned) {
                    sb.append(" (self-signed — attacker-controlled server)");
                }
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
        // UTF-32LE BOM (FF FE 00 00) — must be checked BEFORE UTF-16LE (shares FF FE prefix)
        if (raw.length >= 4
                && (raw[0] & 0xff) == 0xff && (raw[1] & 0xff) == 0xfe
                && raw[2] == 0x00 && raw[3] == 0x00) {
            return new String(raw, 4, raw.length - 4,
                    Charset.forName("UTF-32LE"));
        }
        // UTF-16LE BOM (FF FE)
        if (raw.length >= 2 && (raw[0] & 0xff) == 0xff && (raw[1] & 0xff) == 0xfe) {
            return new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16LE);
        }
        // UTF-16BE BOM (FE FF)
        if (raw.length >= 2 && (raw[0] & 0xff) == 0xfe && (raw[1] & 0xff) == 0xff) {
            return new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16BE);
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

    // ── Crypto helpers ────────────────────────────────────────────────────────

    private static String sha256Hex(byte[] data) {
        return digestHex("SHA-256", data);
    }

    private static String sha1Hex(byte[] data) {
        return digestHex("SHA-1", data);
    }

    private static String digestHex(String algorithm, byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
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

        // Parse the full certificate chain without validation.
        // generateCertificates() reads all certs from PKCS#7/DER — chain[0] is
        // the leaf (server cert), subsequent entries are intermediates/roots.
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            java.util.Collection<? extends java.security.cert.Certificate> chain =
                    cf.generateCertificates(new java.io.ByteArrayInputStream(der));
            int idx = 0;
            for (java.security.cert.Certificate c : chain) {
                if (!(c instanceof X509Certificate)) {
                    idx++;
                    continue;
                }
                X509Certificate x509 = (X509Certificate) c;
                String subject = x509.getSubjectX500Principal().getName();
                String issuer  = x509.getIssuerX500Principal().getName();
                String prefix  = "rdp:pcb_chain_" + idx + "_";
                rootMeta.add(prefix + "subject",     subject);
                rootMeta.add(prefix + "issuer",      issuer);
                rootMeta.add(prefix + "serial",      x509.getSerialNumber().toString());
                rootMeta.add(prefix + "not_before",
                        x509.getNotBefore().toInstant().toString());
                rootMeta.add(prefix + "not_after",
                        x509.getNotAfter().toInstant().toString());
                // Cert validity at parse time
                boolean nowValid = !java.time.Instant.now()
                        .isBefore(x509.getNotBefore().toInstant())
                        && !java.time.Instant.now()
                        .isAfter(x509.getNotAfter().toInstant());
                rootMeta.add(prefix + "valid_now", Boolean.toString(nowValid));
                // Fingerprints for TI lookups
                try {
                    byte[] encoded = x509.getEncoded();
                    rootMeta.add(prefix + "fingerprint_sha256", sha256Hex(encoded));
                    rootMeta.add(prefix + "fingerprint_sha1",   sha1Hex(encoded));
                } catch (Exception ignored) {
                    // encoding is best-effort
                }
                // Leaf cert (idx=0): self-signed check and SAN extraction
                if (idx == 0) {
                    if (subject.equals(issuer)) {
                        rootMeta.set("rdp:pcb_cert_self_signed", "true");
                    }
                    try {
                        java.util.Collection<java.util.List<?>> sans =
                                x509.getSubjectAlternativeNames();
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
                    xhtml.element("p", "Certificate subject: " + subject
                            + (subject.equals(issuer) ? " [SELF-SIGNED]" : "")
                            + " issuer: " + issuer);
                }
                idx++;
            }
            rootMeta.set("rdp:pcb_chain_depth", Integer.toString(idx));
            // Convenience aliases for the leaf cert (backward compat)
            String leafSubject = rootMeta.get("rdp:pcb_chain_0_subject");
            if (leafSubject != null) {
                rootMeta.set("rdp:pcb_cert_subject", leafSubject);
                rootMeta.set("rdp:pcb_cert_issuer",
                        rootMeta.get("rdp:pcb_chain_0_issuer"));
            }
        } catch (CertificateException e) {
            rootMeta.add("rdp:pcb_warning",
                    "X.509 chain parse failed: " + e.getMessage());
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
