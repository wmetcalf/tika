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
package org.apache.tika.parser.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.AbstractList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Barcode;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.image.ZXingCPPConfig;
import org.apache.tika.parser.image.ZXingCPPScanner;
import org.apache.tika.sax.BodyContentHandler;

public class HtmlColorQRExtractorTest {

    private static final String VALID_GRID = """
            <pre style="color:black;background-color:white">######
            ######
            ######
            ######
            ######
            ######</pre>
            """;

    @TempDir
    Path temporaryDirectory;

    @Test
    public void oversizedRgbDoesNotDisableEarlierColorQr() throws Exception {
        Path fakeScanner = createFakeScanner();
        ParseContext context = contextFor(fakeScanner);

        Metadata metadata = new Metadata();
        BodyContentHandler body = new BodyContentHandler(-1);
        String html = "<html><body>" + VALID_GRID
                + "<code style=\"color:rgb(2147483648,0,0)\">poison</code>"
                + "</body></html>";
        parse(html, body, metadata, context);

        assertTrue(body.toString().contains("poison"),
                "ordinary HTML extraction must still complete");
        assertEquals("decoded-control", metadata.get(Barcode.BARCODE_VALUE),
                "one malformed candidate must not suppress an earlier valid color QR");
        assertEquals(0, metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length);
    }

    @Test
    public void clusterDiscoveryStopsAtItsAcceptedCandidateLimit() {
        Document document = Jsoup.parse(
                "<html><body>" + VALID_GRID.repeat(20) + "</body></html>");

        assertEquals(4, HtmlColorQRExtractor.findClusters(document, Map.of()).size());
    }

    @Test
    public void oversizedCandidateGridIsRejectedBeforeMaterialization() {
        String row = "#".repeat(50_000);
        Document document = Jsoup.parse("<html><body><pre>"
                + String.join("\n", row, row, row, row, row, row)
                + "</pre></body></html>");

        assertTrue(HtmlColorQRExtractor.findClusters(document, Map.of()).isEmpty());
    }

    @Test
    public void renderedDimensionsAreCheckedWithoutIntegerOverflow() {
        List<HtmlColorQRExtractor.Cell> hugeRow = new AbstractList<>() {
            @Override
            public HtmlColorQRExtractor.Cell get(int index) {
                return new HtmlColorQRExtractor.Cell(false);
            }

            @Override
            public int size() {
                return Integer.MAX_VALUE;
            }
        };

        assertNull(HtmlColorQRExtractor.renderCluster(
                List.of(hugeRow, hugeRow, hugeRow, hugeRow, hugeRow, hugeRow)));
    }

    @Test
    public void stylesheetRuleRetentionIsBounded() {
        StringBuilder css = new StringBuilder();
        for (int i = 0; i < 5_000; i++) {
            css.append(".rule-").append(i).append("{color:black}");
        }
        Document document = Jsoup.parse("<html><head><style>"
                + css + "</style></head><body/></html>");

        assertTrue(HtmlColorQRExtractor.parseStylesheets(document).size() <= 4_096,
                "stylesheet preprocessing must have a hard retained-rule limit");
    }

    @Test
    public void stylesheetTruncationIsSecurityVisible() throws Exception {
        Path fakeScanner = createFakeScanner();
        Metadata metadata = new Metadata();
        BodyContentHandler body = new BodyContentHandler(-1);
        String html = "<html><head><style>"
                + "x".repeat(1024 * 1024 + 1)
                + "</style></head><body>visible</body></html>";

        parse(html, body, metadata, contextFor(fakeScanner));

        assertTrue(metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0);
        assertNotNull(metadata.get("ExploitClass"),
                "skipped color-QR style analysis must not look complete");
    }

    @Test
    public void oversizedInlineStyleIsBoundedAndSecurityVisible() throws Exception {
        Path fakeScanner = createFakeScanner();
        Metadata metadata = new Metadata();
        BodyContentHandler body = new BodyContentHandler(-1);
        String html = "<html><body><pre style=\"background:"
                + "notacolor ".repeat(16_000)
                + "\">######\n######\n######\n######\n######\n######</pre></body></html>";

        parse(html, body, metadata, contextFor(fakeScanner));

        assertTrue(body.toString().contains("######"));
        assertTrue(metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0,
                "truncated inline-style analysis must be reported");
        assertNotNull(metadata.get("ExploitClass"),
                "an oversized inline style can hide a later color-QR declaration");
    }

    @Test
    public void effectiveStyleIsResolvedOncePerElement() {
        String row = "x<!---->".repeat(500);
        Document document = Jsoup.parse("<html><body><pre class=\"a b\">"
                + String.join("\n", row, row, row, row, row, row)
                + "</pre></body></html>");
        CountingRules rules = new CountingRules();
        rules.put(".a", "color:black");
        rules.put(".b", "background-color:white");

        HtmlColorQRExtractor.findClusters(document, rules);

        assertTrue(rules.lookups < 100,
                "sibling text nodes must share one effective-style computation");
    }

    @Test
    public void earlyLargeMonospaceBlockCannotHideLaterUnicodeQr() throws Exception {
        Path fakeScanner = createFakeScanner();
        Metadata metadata = new Metadata();
        BodyContentHandler body = new BodyContentHandler(-1);
        String unicodeGrid = String.join("\n",
                "████████", "████████", "████████", "████████",
                "████████", "████████", "████████", "████████");
        String html = "<html><body><pre>"
                + "A".repeat(2 * 1024 * 1024)
                + "</pre><pre>" + unicodeGrid + "</pre></body></html>";

        parse(html, body, metadata, contextFor(fakeScanner));

        assertEquals("64", metadata.get("html_unicode_qr:glyph_count"),
                "each bounded monospace candidate must be inspected independently");
        assertTrue(metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0,
                "truncated Unicode-QR analysis must be reported");
        assertNotNull(metadata.get("ExploitClass"),
                "truncated Unicode-QR analysis must fail closed");
    }

    @Test
    public void cssWhitespaceAroundColonDoesNotHideUnicodeQr() throws Exception {
        Path fakeScanner = createFakeScanner();
        Metadata metadata = new Metadata();
        BodyContentHandler body = new BodyContentHandler(-1);
        String unicodeGrid = String.join("\n",
                "████████", "████████", "████████", "████████",
                "████████", "████████", "████████", "████████");
        String html = "<html><body><div style=\"white-space : pre\">"
                + unicodeGrid + "</div></body></html>";

        parse(html, body, metadata, contextFor(fakeScanner));

        assertEquals("64", metadata.get("html_unicode_qr:glyph_count"),
                "valid CSS whitespace must not remove a monospace QR carrier");
        assertEquals("decoded-control", metadata.get(Barcode.BARCODE_VALUE));
    }

    @Test
    public void equivalentCssWhitespaceRulesDoNotHideUnicodeQr() throws Exception {
        Path fakeScanner = createFakeScanner();
        ParseContext context = contextFor(fakeScanner);
        String unicodeGrid = String.join("\n",
                "████████", "████████", "████████", "████████",
                "████████", "████████", "████████", "████████");
        List<String> carriers = List.of(
                "<div style=\"white-space/**/ : /**/ pre\">"
                        + unicodeGrid + "</div>",
                "<div style=\"white-space:pre/**/\">"
                        + unicodeGrid + "</div>",
                "<div style=\"white\\2d space:pre\">"
                        + unicodeGrid + "</div>",
                "<style>.mono{white-space:pre}</style><div class=\"mono\">"
                        + unicodeGrid + "</div>",
                "<div style=\"white-space:break-spaces\">"
                        + unicodeGrid + "</div>");

        for (String carrier : carriers) {
            Metadata metadata = new Metadata();
            parse("<html><body>" + carrier + "</body></html>",
                    new BodyContentHandler(-1), metadata, context);

            assertEquals("64", metadata.get("html_unicode_qr:glyph_count"),
                    "browser-equivalent whitespace-preserving CSS must be inspected: "
                            + carrier);
        }
    }

    @Test
    public void colorQrScannerFailureIsSecurityVisible() {
        Document document = Jsoup.parse("<html><body>" + VALID_GRID + "</body></html>");
        Metadata metadata = new Metadata();
        ZXingCPPConfig config = new ZXingCPPConfig();
        config.setEnabled(true);
        ThrowingScanner scanner = new ThrowingScanner();

        List<ZXingCPPScanner.Result> decoded = HtmlColorQRExtractor.extractAndDecode(
                document, scanner, config, new ParseContext(), metadata);

        assertTrue(scanner.invoked);
        assertEquals(Collections.emptyList(), decoded);
        assertTrue(metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0);
        assertNotNull(metadata.get("ExploitClass"),
                "a configured scanner failure must not look like a clean negative");
    }

    private static void parse(String html, BodyContentHandler handler, Metadata metadata,
                              ParseContext context) throws Exception {
        metadata.set(Metadata.CONTENT_TYPE, "text/html; charset=UTF-8");
        try (TikaInputStream stream = TikaInputStream.get(
                html.getBytes(StandardCharsets.UTF_8))) {
            new JSoupParser().parse(stream, handler, metadata, context);
        }
    }

    private static ParseContext contextFor(Path fakeScanner) {
        ZXingCPPConfig config = new ZXingCPPConfig();
        config.setEnabled(true);
        config.setZxingPath(fakeScanner.toString());
        ParseContext context = new ParseContext();
        context.set(ZXingCPPConfig.class, config);
        return context;
    }

    private Path createFakeScanner() throws IOException {
        Path script = temporaryDirectory.resolve("fake-zxing-reader");
        Files.writeString(script, """
                #!/bin/sh
                if [ "$1" = "-version" ]; then
                    printf '%s\\n' 'ZXingReader test double 1.0'
                    exit 0
                fi
                if [ "$1" = "-json" ]; then
                    printf '%s\\n' '{"FilePath":"grid.png","Text":"decoded-control","Format":"QRCode","IsMirrored":false}'
                    exit 0
                fi
                exit 2
                """, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script,
                PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    private static final class CountingRules extends HashMap<String, String> {
        private int lookups;

        @Override
        public String get(Object key) {
            lookups++;
            return super.get(key);
        }
    }

    private static final class ThrowingScanner extends ZXingCPPScanner {
        private boolean invoked;

        @Override
        public boolean hasZXingCPP() {
            return true;
        }

        @Override
        public List<Result> scan(
                Path imagePath, ZXingCPPConfig config, ParseContext context) {
            invoked = true;
            throw new RuntimeException("simulated scanner failure");
        }
    }
}
