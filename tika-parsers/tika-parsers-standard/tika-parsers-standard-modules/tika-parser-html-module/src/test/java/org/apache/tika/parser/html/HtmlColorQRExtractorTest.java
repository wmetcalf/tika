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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.AbstractList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
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
    public void colorQrCandidateTraversalDoesNotMaterializeSelectorResults() {
        Document document = new GetAllElementsTrapDocument();
        document.appendElement("pre")
                .attr("style", "color:black;background-color:white")
                .text("######\n######\n######\n######\n######\n######");

        assertEquals(1, HtmlColorQRExtractor.findClusters(document, Map.of()).size());
    }

    @Test
    public void fullBlockForegroundOverridesCellBackground() {
        Document document = Jsoup.parse("<html><body>" + VALID_GRID + "</body></html>");

        List<List<List<HtmlColorQRExtractor.Cell>>> clusters =
                HtmlColorQRExtractor.findClusters(document, Map.of());

        assertTrue(clusters.get(0).get(0).get(0).dark,
                "a full-block glyph is visibly painted with its foreground color");
    }

    @Test
    public void finalCssDeclarationWins() {
        assertEquals(0, HtmlColorQRExtractor.readColor(
                "color:white;color:black", "color"));
    }

    @Test
    public void importantSuffixDoesNotHideCssColor() {
        assertEquals(0, HtmlColorQRExtractor.readColor(
                "background-color:black!important", "background-color"));
    }

    @Test
    public void importantColorIsNotOverriddenByLaterNormalDeclaration() {
        assertEquals(0, HtmlColorQRExtractor.readColor(
                "color:black!important;color:white", "color"));
        assertEquals(0, HtmlColorQRExtractor.readColor(
                "color:white!important;color:black!important", "color"));
        assertEquals(0, HtmlColorQRExtractor.readBackgroundShorthand(
                "background:black!important;background:white"));
    }

    @Test
    public void importantBackgroundShorthandOutranksNormalLonghand() {
        Document document = Jsoup.parse("""
                <pre style="background:black!important;background-color:white">xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx</pre>
                """);

        List<List<List<HtmlColorQRExtractor.Cell>>> clusters =
                HtmlColorQRExtractor.findClusters(document, Map.of());

        assertTrue(clusters.get(0).get(0).get(0).dark,
                "important shorthand must win over a normal background-color");
    }

    @Test
    public void stylesheetSourceOrderOutranksHtmlClassTokenOrder() {
        Document document = Jsoup.parse("""
                <style>
                  .light { color: white; }
                  .dark { color: black; }
                </style>
                <pre class="dark light">######
                ######
                ######
                ######
                ######
                ######</pre>
                """);

        List<List<List<HtmlColorQRExtractor.Cell>>> clusters =
                HtmlColorQRExtractor.findClusters(
                        document, HtmlColorQRExtractor.parseStylesheets(document));

        assertTrue(clusters.get(0).get(0).get(0).dark,
                "equal-specificity rules follow stylesheet order, not class token order");
    }

    @Test
    public void commentsCannotHideInlineImportantPriority() {
        Document document = Jsoup.parse("""
                <pre style="color:black!/**/important;color:white">######
                ######
                ######
                ######
                ######
                ######</pre>
                """);

        List<List<List<HtmlColorQRExtractor.Cell>>> clusters =
                HtmlColorQRExtractor.findClusters(document, Map.of());

        assertTrue(clusters.get(0).get(0).get(0).dark,
                "CSS comments are removed before !important priority is resolved");
    }

    @Test
    public void escapedImportantPriorityMatchesBrowserCascade() {
        assertEquals(0, HtmlColorQRExtractor.readColor(
                "color:black!\\69mportant;color:white", "color"));
    }

    @Test
    public void commentsPreserveCssTokenBoundaries() {
        Document splitProperty = Jsoup.parse("""
                <pre style="background-co/**/lor:black">xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx</pre>
                """);
        Document splitImportant = Jsoup.parse("""
                <pre style="color:black!im/**/portant;color:white">######
                ######
                ######
                ######
                ######
                ######</pre>
                """);

        assertFalse(HtmlColorQRExtractor.findClusters(
                splitProperty, Map.of()).get(0).get(0).get(0).dark,
                "a comment cannot join two identifier tokens into a valid property");
        assertFalse(HtmlColorQRExtractor.findClusters(
                splitImportant, Map.of()).get(0).get(0).get(0).dark,
                "a comment cannot join two identifier tokens into !important");
    }

    @Test
    public void backgroundShorthandWithoutColorResetsEarlierLonghand() {
        Document document = Jsoup.parse("""
                <pre style="background-color:black;background:url(x)">xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx</pre>
                """);

        assertFalse(HtmlColorQRExtractor.findClusters(
                document, Map.of()).get(0).get(0).get(0).dark,
                "a valid background shorthand resets background-color to transparent");
    }

    @Test
    public void customPropertyContentsAreNotColorDeclarations() {
        Document document = Jsoup.parse("""
                <pre style="background:black;--decoy: background:white">xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx</pre>
                """);

        assertTrue(HtmlColorQRExtractor.findClusters(
                document, Map.of()).get(0).get(0).get(0).dark,
                "declaration-like text inside a custom property is not applied CSS");
    }

    @Test
    public void escapedBangDoesNotCreateImportantPriority() {
        assertEquals(0, HtmlColorQRExtractor.readBackgroundShorthand(
                "background:white \\21 important;background:black"),
                "an escaped bang is part of a CSS value token, not the !important delimiter");
    }

    @Test
    public void positionOnlyBackgroundShorthandResetsEarlierColor() {
        Document document = Jsoup.parse("""
                <pre style="background-color:black;background:0 0">xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx</pre>
                """);

        assertFalse(HtmlColorQRExtractor.findClusters(
                document, Map.of()).get(0).get(0).get(0).dark,
                "a valid shorthand with only a position resets background-color");
    }

    @Test
    public void invalidThreeValueNumericBackgroundDoesNotResetEarlierColor() {
        Document document = Jsoup.parse("""
                <pre style="background-color:black;background:0 0 0">xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx</pre>
                """);

        assertTrue(HtmlColorQRExtractor.findClusters(
                document, Map.of()).get(0).get(0).get(0).dark,
                "three numeric components are not a valid background-position shorthand");
    }

    @Test
    public void standardsModeClassAndIdSelectorsRemainCaseSensitive() {
        Document classDocument = Jsoup.parse("""
                <!doctype html>
                <style>
                  .Dark { background: black; }
                  .dark { background: white; }
                </style>
                <pre class="Dark">xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx</pre>
                """);
        Document idDocument = Jsoup.parse("""
                <!doctype html>
                <style>
                  #Grid { background: black; }
                  #grid { background: white; }
                </style>
                <pre id="Grid">xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx</pre>
                """);

        assertTrue(HtmlColorQRExtractor.findClusters(
                classDocument, HtmlColorQRExtractor.parseStylesheets(classDocument))
                .get(0).get(0).get(0).dark);
        assertTrue(HtmlColorQRExtractor.findClusters(
                idDocument, HtmlColorQRExtractor.parseStylesheets(idDocument))
                .get(0).get(0).get(0).dark);
    }

    @Test
    public void quirksModeClassAndIdSelectorsRemainCaseInsensitive() {
        Document classDocument = Jsoup.parse("""
                <style>.Dark { background: black; }</style>
                <pre class="dark">xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx</pre>
                """);
        Document idDocument = Jsoup.parse("""
                <style>#Grid { background: black; }</style>
                <pre id="grid">xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx
                xxxxxx</pre>
                """);

        assertTrue(HtmlColorQRExtractor.findClusters(
                classDocument, HtmlColorQRExtractor.parseStylesheets(classDocument))
                .get(0).get(0).get(0).dark);
        assertTrue(HtmlColorQRExtractor.findClusters(
                idDocument, HtmlColorQRExtractor.parseStylesheets(idDocument))
                .get(0).get(0).get(0).dark);
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
    public void percentEncodedDataStylesheetIsInspected() throws Exception {
        Path fakeScanner = createFakeScanner();
        Metadata metadata = new Metadata();
        String unicodeGrid = String.join("\n",
                "████████", "████████", "████████", "████████",
                "████████", "████████", "████████", "████████");

        parse("<html><head><link rel=\"stylesheet\" "
                        + "href=\"data:text/css,.qr%7Bwhite-space%3Apre%7D\"></head>"
                        + "<body><div class=\"qr\">" + unicodeGrid + "</div></body></html>",
                new BodyContentHandler(-1), metadata, contextFor(fakeScanner));

        assertEquals("64", metadata.get("html_unicode_qr:glyph_count"),
                "percent-encoded in-document CSS must participate in QR analysis");
    }

    @Test
    public void base64DataStylesheetIsInspected() throws Exception {
        Path fakeScanner = createFakeScanner();
        Metadata metadata = new Metadata();
        String unicodeGrid = String.join("\n",
                "████████", "████████", "████████", "████████",
                "████████", "████████", "████████", "████████");

        parse("<html><head><link rel=\"stylesheet\" "
                        + "href=\"data:text/css;base64,LnFye3doaXRlLXNwYWNlOnByZX0=\"></head>"
                        + "<body><div class=\"qr\">" + unicodeGrid + "</div></body></html>",
                new BodyContentHandler(-1), metadata, contextFor(fakeScanner));

        assertEquals("64", metadata.get("html_unicode_qr:glyph_count"),
                "base64 in-document CSS must participate in QR analysis");
    }

    @Test
    public void oversizedDataStylesheetsAreSecurityVisible() throws Exception {
        Path fakeScanner = createFakeScanner();
        ParseContext context = contextFor(fakeScanner);
        for (String href : List.of(
                "data:text/css," + "a".repeat(300_000),
                "data:text/css;base64," + "YWFh".repeat(100_000))) {
            Metadata metadata = new Metadata();

            parse("<html><head><link rel=\"stylesheet\" href=\"" + href
                            + "\"></head><body>visible</body></html>",
                    new BodyContentHandler(-1), metadata, context);

            assertTrue(metadata
                    .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0,
                    "bounded data CSS decoding must report incomplete analysis");
            assertNotNull(metadata.get("ExploitClass"),
                    "truncated data CSS must not look like a clean negative");
        }
    }

    @Test
    public void oversizedDataStylesheetHeaderIsSecurityVisible() throws Exception {
        Path fakeScanner = createFakeScanner();
        Metadata metadata = new Metadata();

        parse("<html><head><link rel=\"stylesheet\" href=\"data:text/css"
                        + " ".repeat(300_000)
                        + ",.qr%7Bwhite-space%3Apre%7D\"></head><body>visible</body></html>",
                new BodyContentHandler(-1), metadata, contextFor(fakeScanner));

        assertTrue(metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0,
                "data URI metadata scanning must also have a hard bound");
        assertNotNull(metadata.get("ExploitClass"),
                "an oversized data URI header must not look like a clean negative");
    }

    @Test
    public void unsupportedLinkedStylesheetIsSecurityVisible() throws Exception {
        Path fakeScanner = createFakeScanner();
        Metadata metadata = new Metadata();

        parse("<html><head><link rel=\"stylesheet\" "
                        + "href=\"https://attacker.example/hidden.css\"></head>"
                        + "<body><div class=\"qr\">████████</div></body></html>",
                new BodyContentHandler(-1), metadata, contextFor(fakeScanner));

        assertTrue(metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0,
                "an unavailable linked stylesheet makes color analysis incomplete");
        assertNotNull(metadata.get("ExploitClass"),
                "unsupported linked CSS must not look like a clean negative");
    }

    @Test
    public void importedStylesheetIsSecurityVisible() throws Exception {
        Path fakeScanner = createFakeScanner();
        Metadata metadata = new Metadata();

        parse("<html><head><style>"
                        + "@import url('data:text/css,.qr%7Bcolor%3Ablack%7D');"
                        + "</style></head><body><div class=\"qr\">████████</div></body></html>",
                new BodyContentHandler(-1), metadata, contextFor(fakeScanner));

        assertTrue(metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0,
                "unprocessed CSS imports make color analysis incomplete");
        assertNotNull(metadata.get("ExploitClass"),
                "imported CSS must not look like a clean negative");
        assertEquals(
                "HTML color-QR stylesheet analysis is incomplete; "
                        + "unsupported or bounded CSS was omitted",
                metadata.getValues(
                        TikaCoreProperties.TIKA_META_EXCEPTION_WARNING)[0],
                "unsupported CSS must not be mislabeled as a resource-limit failure");
    }

    @Test
    public void leadingCharsetDirectiveIsHarmlessButCannotHideImports() {
        Document harmless = Jsoup.parse("""
                <style>
                  @charset "UTF-8";
                  .dark { color: black; }
                </style>
                """);
        Document followedByImport = Jsoup.parse("""
                <style>
                  @charset "UTF-8";
                  @import url("hidden.css");
                  .dark { color: black; }
                </style>
                """);

        HtmlColorQRExtractor.StylesheetParseResult harmlessResult =
                HtmlColorQRExtractor.parseStylesheetsBounded(harmless);
        HtmlColorQRExtractor.StylesheetParseResult importResult =
                HtmlColorQRExtractor.parseStylesheetsBounded(followedByImport);

        assertFalse(harmlessResult.truncated);
        assertTrue(harmlessResult.rules.containsKey(".dark"));
        assertTrue(importResult.truncated,
                "a safe charset prefix must not blind later security-relevant at-rules");
    }

    @Test
    public void legacyCdoWrappedImportIsSecurityVisible() {
        Document document = Jsoup.parse("""
                <style>
                  <!-- @import url("hidden.css"); -->
                  .qr { color: black; }
                </style>
                """);

        HtmlColorQRExtractor.StylesheetParseResult result =
                HtmlColorQRExtractor.parseStylesheetsBounded(document);

        assertTrue(result.truncated,
                "CSS CDO and CDC wrappers must not hide an at-rule");
    }

    @Test
    public void quotedCommentMarkerCannotHideLaterAtRule() {
        Document document = Jsoup.parse("""
                <style>
                  .label { content: "/*"; }
                  @media screen { .qr { color: black; } }
                </style>
                """);

        HtmlColorQRExtractor.StylesheetParseResult result =
                HtmlColorQRExtractor.parseStylesheetsBounded(document);

        assertTrue(result.truncated,
                "comment markers inside strings must not blind at-rule detection");
    }

    @Test
    public void atCharactersInsideCssValuesAreNotAtRules() {
        Document document = Jsoup.parse("""
                <style>
                  .contact {
                    content: "user@example.com";
                    background-image: url(https://user@example.invalid/pixel.png);
                    color: black;
                  }
                </style>
                """);

        HtmlColorQRExtractor.StylesheetParseResult result =
                HtmlColorQRExtractor.parseStylesheetsBounded(document);

        assertTrue(result.rules.containsKey(".contact"));
        assertTrue(!result.truncated,
                "ordinary at characters in strings and URLs are not CSS at-rules");
    }

    @Test
    public void atCharacterAfterSemicolonInsideUrlIsNotAnAtRule() {
        Document document = Jsoup.parse("""
                <style>
                  .contact {
                    background-image: url(https://example.invalid/a;@b.png);
                    color: black;
                  }
                </style>
                """);

        HtmlColorQRExtractor.StylesheetParseResult result =
                HtmlColorQRExtractor.parseStylesheetsBounded(document);

        assertTrue(result.rules.containsKey(".contact"));
        assertTrue(!result.truncated,
                "a semicolon inside a URL must not create an at-rule boundary");
    }

    @Test
    public void atCharacterInsideCustomPropertyBlockIsNotAnAtRule() {
        Document document = Jsoup.parse("""
                <style>
                  .contact {
                    --token: {@benign};
                    color: black;
                  }
                </style>
                """);

        HtmlColorQRExtractor.StylesheetParseResult result =
                HtmlColorQRExtractor.parseStylesheetsBounded(document);

        assertTrue(result.rules.containsKey(".contact"));
        assertTrue(result.rules.get(".contact").contains("color: black"),
                "a nested custom-property value must not truncate later declarations");
        assertTrue(!result.truncated,
                "custom-property block values may contain ordinary at characters");
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
    public void oversizedInlineCommentIsBoundedAndSecurityVisible() throws Exception {
        Path fakeScanner = createFakeScanner();
        Metadata metadata = new Metadata();
        String html = "<html><body><pre style=\"/*"
                + "x".repeat(100_000)
                + "*/color:black\">######\n######\n######\n######\n######\n######"
                + "</pre></body></html>";

        parse(html, new BodyContentHandler(-1), metadata, contextFor(fakeScanner));

        assertTrue(metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0,
                "comment stripping must not bypass the inline-style scan budget");
        assertNotNull(metadata.get("ExploitClass"),
                "bounded inline comments can hide later declarations and must fail closed");
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
    public void importantWhitespaceRuleSurvivesLaterNormalRule() throws Exception {
        Path fakeScanner = createFakeScanner();
        Metadata metadata = new Metadata();
        String unicodeGrid = String.join("\n",
                "████████", "████████", "████████", "████████",
                "████████", "████████", "████████", "████████");

        parse("<html><style>"
                        + ".qr{white-space:pre!important}"
                        + ".qr{white-space:normal}"
                        + "</style><div class=\"qr\">" + unicodeGrid + "</div></html>",
                new BodyContentHandler(-1), metadata, contextFor(fakeScanner));

        assertEquals("64", metadata.get("html_unicode_qr:glyph_count"),
                "a later normal declaration cannot override an earlier important one");
    }

    @Test
    public void quirksModeClassAndIdWhitespaceSelectorsRemainCaseInsensitive()
            throws Exception {
        Path fakeScanner = createFakeScanner();
        String unicodeGrid = String.join("\n",
                "████████", "████████", "████████", "████████",
                "████████", "████████", "████████", "████████");
        Metadata classMetadata = new Metadata();
        Metadata idMetadata = new Metadata();

        parse("<html><style>.Qr{white-space:pre}</style>"
                        + "<div class=\"qr\">" + unicodeGrid + "</div></html>",
                new BodyContentHandler(-1), classMetadata, contextFor(fakeScanner));
        parse("<html><style>#Grid{white-space:pre}</style>"
                        + "<div id=\"grid\">" + unicodeGrid + "</div></html>",
                new BodyContentHandler(-1), idMetadata, contextFor(fakeScanner));

        assertEquals("64", classMetadata.get("html_unicode_qr:glyph_count"));
        assertEquals("64", idMetadata.get("html_unicode_qr:glyph_count"));
    }

    @Test
    public void parserPreservesStandardsModeForWhitespaceSelectorMatching()
            throws Exception {
        Path fakeScanner = createFakeScanner();
        Metadata metadata = new Metadata();
        String unicodeGrid = String.join("\n",
                "████████", "████████", "████████", "████████",
                "████████", "████████", "████████", "████████");

        parse("<!doctype html><html><style>"
                        + ".Dark{white-space:pre!important}"
                        + ".dark{white-space:normal!important}"
                        + "</style><div class=\"Dark\">" + unicodeGrid + "</div></html>",
                new BodyContentHandler(-1), metadata, contextFor(fakeScanner));

        assertEquals("64", metadata.get("html_unicode_qr:glyph_count"),
                "the parser must scan using the doctype-derived standards mode");
    }

    @Test
    public void parserPreservesXhtmlSelectorCaseWithoutDoctype() throws Exception {
        Path fakeScanner = createFakeScanner();
        Metadata metadata = new Metadata();
        String unicodeGrid = String.join("\n",
                "████████", "████████", "████████", "████████",
                "████████", "████████", "████████", "████████");

        parse("<html><style>"
                        + ".Dark{white-space:pre!important}"
                        + ".dark{white-space:normal!important}"
                        + "</style><div class=\"Dark\">" + unicodeGrid + "</div></html>",
                "application/xhtml+xml; charset=UTF-8",
                new BodyContentHandler(-1), metadata, contextFor(fakeScanner));

        assertEquals("64", metadata.get("html_unicode_qr:glyph_count"),
                "XHTML class and ID values remain case-sensitive without a doctype");
    }

    @Test
    public void xhtmlTypeSelectorCaseAmbiguityFailsClosed() throws Exception {
        Path fakeScanner = createFakeScanner();
        Metadata metadata = new Metadata();
        String unicodeGrid = String.join("\n",
                "████████", "████████", "████████", "████████",
                "████████", "████████", "████████", "████████");

        parse("<html><style>"
                        + "DIV{white-space:pre!important}"
                        + "div{white-space:normal!important}"
                        + "</style><DIV>" + unicodeGrid + "</DIV></html>",
                "application/xhtml+xml; charset=UTF-8",
                new BodyContentHandler(-1), metadata, contextFor(fakeScanner));

        assertTrue(metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0,
                "HTML-mode parsing loses XHTML element-name case and must fail closed");
        assertNotNull(metadata.get("ExploitClass"));
    }

    @Test
    public void xhtmlAnalysisFailsClosedWhenMarkupCaseIsLost()
            throws Exception {
        Path fakeScanner = createFakeScanner();
        String unicodeGrid = String.join("\n",
                "████████", "████████", "████████", "████████",
                "████████", "████████", "████████", "████████");
        Metadata styleElementMetadata = new Metadata();
        Metadata styleAttributeMetadata = new Metadata();

        parse("<html><STYLE>.qr{white-space:normal!important}</STYLE>"
                        + "<div class=\"qr\" style=\"white-space:pre\">"
                        + unicodeGrid + "</div></html>",
                "application/xhtml+xml; charset=UTF-8",
                new BodyContentHandler(-1), styleElementMetadata,
                contextFor(fakeScanner));
        parse("<html><div STYLE=\"white-space:normal!important\" "
                        + "style=\"white-space:pre\">"
                        + unicodeGrid + "</div></html>",
                "application/xhtml+xml; charset=UTF-8",
                new BodyContentHandler(-1), styleAttributeMetadata,
                contextFor(fakeScanner));

        assertTrue(styleElementMetadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0);
        assertTrue(styleAttributeMetadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0);
    }

    @Test
    public void nonAsciiWhitespaceDoesNotSplitHtmlClassTokens() throws Exception {
        Path fakeScanner = createFakeScanner();
        Metadata metadata = new Metadata();
        String unicodeGrid = String.join("\n",
                "████████", "████████", "████████", "████████",
                "████████", "████████", "████████", "████████");

        parse("<!doctype html><html><style>"
                        + ".qr{white-space:normal!important}"
                        + "</style><div class=\"x\u2003qr\" style=\"white-space:pre\">"
                        + unicodeGrid + "</div></html>",
                new BodyContentHandler(-1), metadata, contextFor(fakeScanner));

        assertEquals("64", metadata.get("html_unicode_qr:glyph_count"),
                "HTML class tokens are separated only by ASCII whitespace");
    }

    @Test
    public void importantNormalWhitespaceOverridesLaterPre() {
        JSoupParser.WhitespaceInspection inspection = JSoupParser.inspectWhitespace(
                "white-space:normal!important;white-space:pre");

        assertFalse(inspection.preserves());
        assertFalse(inspection.incomplete());
    }

    @Test
    public void escapedStylesheetSelectorDoesNotHideUnicodeQr() throws Exception {
        Path fakeScanner = createFakeScanner();
        Metadata metadata = new Metadata();
        String unicodeGrid = String.join("\n",
                "████████", "████████", "████████", "████████",
                "████████", "████████", "████████", "████████");

        parse("<html><style>.q\\72 {white-space:pre}</style>"
                        + "<div class=\"qr\">" + unicodeGrid + "</div></html>",
                new BodyContentHandler(-1), metadata, contextFor(fakeScanner));

        assertEquals("64", metadata.get("html_unicode_qr:glyph_count"));
    }

    @Test
    public void unsupportedRelevantSelectorIsSecurityVisible() throws Exception {
        Path fakeScanner = createFakeScanner();
        Metadata metadata = new Metadata();
        String unicodeGrid = String.join("\n",
                "████████", "████████", "████████", "████████",
                "████████", "████████", "████████", "████████");

        parse("<html><style>.mono.qr{white-space:pre}</style>"
                        + "<div class=\"mono qr\">" + unicodeGrid + "</div></html>",
                new BodyContentHandler(-1), metadata, contextFor(fakeScanner));

        assertTrue(metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0);
        assertNotNull(metadata.get("ExploitClass"),
                "an unsupported rendered selector must not look like a clean negative");
    }

    @Test
    public void unresolvedWhitespaceValueIsSecurityVisible() throws Exception {
        Path fakeScanner = createFakeScanner();
        Metadata metadata = new Metadata();
        String unicodeGrid = String.join("\n",
                "████████", "████████", "████████", "████████",
                "████████", "████████", "████████", "████████");

        parse("<html><style>:root{--ws:pre}.qr{white-space:var(--ws)}</style>"
                        + "<div class=\"qr\">" + unicodeGrid + "</div></html>",
                new BodyContentHandler(-1), metadata, contextFor(fakeScanner));

        assertTrue(metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0);
        assertNotNull(metadata.get("ExploitClass"),
                "unresolved browser whitespace semantics must not look clean");
    }

    @Test
    public void unsupportedBrowserColorValueIsSecurityVisible() throws Exception {
        Path fakeScanner = createFakeScanner();
        Metadata metadata = new Metadata();

        parse("<html><body><pre style=\"color:hsl(0 0% 0%)\">"
                        + "######\n######\n######\n######\n######\n######"
                        + "</pre></body></html>",
                new BodyContentHandler(-1), metadata, contextFor(fakeScanner));

        assertTrue(metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0);
        assertNotNull(metadata.get("ExploitClass"),
                "unsupported browser-valid colors must not look like a clean negative");
    }

    @Test
    public void transparentRgbaColorIsSecurityVisible() throws Exception {
        Path fakeScanner = createFakeScanner();
        Metadata metadata = new Metadata();

        parse("<html><body><pre style=\"color:rgba(255,255,255,0);background:black\">"
                        + "######\n######\n######\n######\n######\n######"
                        + "</pre></body></html>",
                new BodyContentHandler(-1), metadata, contextFor(fakeScanner));

        assertTrue(metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0);
        assertNotNull(metadata.get("ExploitClass"),
                "discarded alpha must not turn transparent text into a clean opaque grid");
    }

    @Test
    public void standardNamedColorOutsideShortcutTableIsSecurityVisible() throws Exception {
        Path fakeScanner = createFakeScanner();
        Metadata metadata = new Metadata();

        parse("<html><body><pre style=\"color:navy;background:white\">"
                        + "######\n######\n######\n######\n######\n######"
                        + "</pre></body></html>",
                new BodyContentHandler(-1), metadata, contextFor(fakeScanner));

        assertTrue(metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0);
        assertNotNull(metadata.get("ExploitClass"),
                "unresolved browser-valid named colors must not look like a clean negative");
    }

    @Test
    public void transparentBackgroundShorthandIsSecurityVisible() throws Exception {
        Path fakeScanner = createFakeScanner();
        Metadata metadata = new Metadata();

        parse("<html><body><pre style=\"background:transparent\">"
                        + "######\n######\n######\n######\n######\n######"
                        + "</pre></body></html>",
                new BodyContentHandler(-1), metadata, contextFor(fakeScanner));

        assertTrue(metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0,
                "transparent shorthand depends on the painted background and "
                        + "must not look like a fully classified grid");
    }

    @Test
    public void stylesheetTraversalDoesNotMaterializeAllElements() throws Exception {
        Document document = new GetAllElementsTrapDocument();
        document.appendElement("style")
                .appendChild(new org.jsoup.nodes.DataNode(".x{white-space:normal}"));
        document.appendElement("div").addClass("x").text("ordinary text");
        Metadata metadata = new Metadata();

        Method scan = JSoupParser.class.getDeclaredMethod(
                "scanForUnicodeArtQR", Document.class, Metadata.class, ParseContext.class);
        scan.setAccessible(true);
        scan.invoke(null, document, metadata, contextForUncheckedScanner());

        assertEquals(0, metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length,
                "bounded traversal must not call Document.getAllElements()");
    }

    @Test
    public void matchingLargeStylesheetDeclarationIsInspectedOnce() {
        String declaration = "a".repeat(64 * 1024);
        String elements = "<div>x</div>".repeat(20_000);
        Document document = Jsoup.parse(
                "<html><style>div{" + declaration + "}</style><body>"
                        + elements + "</body></html>");

        assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> new JSoupParser().parseString(
                        document.outerHtml(), new BodyContentHandler(-1),
                        new Metadata(), contextForUncheckedScanner()));
    }

    @Test
    public void uniqueInlineStylesStopAtCumulativeInspectionBudget() throws Exception {
        Document document = Jsoup.parse("<html><body></body></html>");
        for (int i = 0; i < 40; i++) {
            String prefix = "white-space:normal;--unique:" + i + ";";
            document.body().appendElement("div")
                    .attr("style", prefix + "x".repeat(64 * 1024 - prefix.length()))
                    .text("ordinary text");
        }
        Metadata metadata = new Metadata();
        Method scan = JSoupParser.class.getDeclaredMethod(
                "scanForUnicodeArtQR", Document.class, Metadata.class, ParseContext.class);
        scan.setAccessible(true);

        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                scan.invoke(null, document, metadata, contextForUncheckedScanner()));
        assertTrue(metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0,
                "distinct inline styles must stop at the cumulative character budget");
    }

    @Test
    public void atRuleInspectionIsLinearAcrossColonDenseDeclaration() {
        Document document = Jsoup.parse("<style>.x{a"
                + ":".repeat(200_000) + "x}</style>");

        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                assertFalse(HtmlColorQRExtractor
                        .parseStylesheetsBounded(document).truncated));
    }

    @Test
    public void inheritedColorResolutionIsLinearAcrossDeepCandidates() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            StringBuilder html = new StringBuilder("<pre>");
            for (int i = 0; i < 100_000; i++) {
                html.append("<div>x");
            }
            html.append("</pre>");
            HtmlColorQRExtractor.findClusters(Jsoup.parse(html.toString()), Map.of());
        });
    }

    @Test
    public void unicodeQrScannerFailureIsSecurityVisible() throws Exception {
        Path fakeScanner = createFailingScanner();
        Metadata metadata = new Metadata();
        String unicodeGrid = String.join("\n",
                "████████", "████████", "████████", "████████",
                "████████", "████████", "████████", "████████");

        parse("<html><div style=\"white-space:pre\">" + unicodeGrid + "</div></html>",
                new BodyContentHandler(-1), metadata, contextFor(fakeScanner));

        assertEquals("64", metadata.get("html_unicode_qr:glyph_count"));
        assertTrue(metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0);
        assertNotNull(metadata.get("ExploitClass"),
                "a configured Unicode-QR scanner failure must fail closed");
    }

    @Test
    public void colorAndUnicodeQrCandidatesShareOneSubprocessBudget() throws Exception {
        Path invocationLog = temporaryDirectory.resolve("zxing-invocations.log");
        Path fakeScanner = createCountingEmptyScanner(invocationLog);
        String unicodeGrid = String.join("\n",
                "████████", "████████", "████████", "████████",
                "████████", "████████", "████████", "████████");
        StringBuilder html = new StringBuilder("<html><body>")
                .append(VALID_GRID);
        for (int i = 0; i < 6; i++) {
            html.append("<pre>").append(unicodeGrid).append("</pre>");
        }
        html.append("</body></html>");
        Metadata metadata = new Metadata();

        parse(html.toString(), new BodyContentHandler(-1), metadata,
                contextFor(fakeScanner));

        List<String> invocations = Files.readAllLines(invocationLog);
        assertEquals(4, invocations.stream()
                .filter("scan"::equals).count());
        assertEquals(0, invocations.stream()
                .filter("probe"::equals).count());
        assertTrue(metadata
                .getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0);
        assertNotNull(metadata.get("ExploitClass"),
                "skipped HTML QR candidates must not look like a clean negative");
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

    @Test
    public void colorQrSecurityExceptionPropagates() {
        Document document = Jsoup.parse("<html><body>" + VALID_GRID + "</body></html>");
        ZXingCPPConfig config = new ZXingCPPConfig();
        config.setEnabled(true);

        assertThrows(SecurityException.class,
                () -> HtmlColorQRExtractor.extractAndDecode(
                        document, new SecurityExceptionScanner(), config,
                        new ParseContext(), new Metadata()));
    }

    @Test
    public void htmlScannerSecurityExceptionPropagatesWhenCandidateIsScanned() {
        Metadata metadata = new Metadata();

        assertThrows(SecurityException.class, () ->
                parse("<html><body>" + VALID_GRID + "</body></html>",
                        new BodyContentHandler(-1), metadata,
                        contextForSecurityException()));
    }

    @Test
    public void unicodeQrScannerSecurityExceptionPropagates() throws Exception {
        String unicodeGrid = String.join("\n",
                "████████", "████████", "████████", "████████",
                "████████", "████████", "████████", "████████");
        Document document = Jsoup.parse(
                "<html><body><pre>" + unicodeGrid + "</pre></body></html>");
        Method scan = JSoupParser.class.getDeclaredMethod(
                "scanForUnicodeArtQR", Document.class, Metadata.class, ParseContext.class);
        scan.setAccessible(true);

        InvocationTargetException thrown = assertThrows(
                InvocationTargetException.class,
                () -> scan.invoke(null, document, new Metadata(),
                        contextForSecurityException()));

        assertTrue(thrown.getCause() instanceof SecurityException);
    }

    private static void parse(String html, BodyContentHandler handler, Metadata metadata,
                              ParseContext context) throws Exception {
        parse(html, "text/html; charset=UTF-8", handler, metadata, context);
    }

    private static void parse(String html, String contentType,
                              BodyContentHandler handler, Metadata metadata,
                              ParseContext context) throws Exception {
        metadata.set(Metadata.CONTENT_TYPE, contentType);
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

    private static ParseContext contextForUncheckedScanner() {
        ZXingCPPConfig config = new ZXingCPPConfig();
        config.setEnabled(true);
        ParseContext context = new ParseContext();
        context.set(ZXingCPPConfig.class, config);
        return context;
    }

    private static ParseContext contextForSecurityException() {
        ZXingCPPConfig config = new ZXingCPPConfig() {
            @Override
            public String getZxingPath() {
                throw new SecurityException("simulated scanner policy rejection");
            }
        };
        config.setEnabled(true);
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

    private Path createFailingScanner() throws IOException {
        Path script = temporaryDirectory.resolve("failing-zxing-reader");
        Files.writeString(script, """
                #!/bin/sh
                if [ "$1" = "-version" ]; then
                    exit 0
                fi
                exit 2
                """, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script,
                PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    private Path createCountingEmptyScanner(Path invocationLog) throws IOException {
        Path script = temporaryDirectory.resolve("counting-zxing-reader");
        Files.writeString(script, String.format(Locale.ROOT, """
                #!/bin/sh
                if [ "$1" = "-version" ]; then
                    printf '%%s\\n' probe >> '%s'
                    exit 0
                fi
                if [ "$1" = "-json" ]; then
                    printf '%%s\\n' scan >> '%s'
                    exit 0
                fi
                exit 2
                """, invocationLog, invocationLog), StandardCharsets.UTF_8);
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

    private static final class GetAllElementsTrapDocument extends Document {
        private GetAllElementsTrapDocument() {
            super("");
        }

        @Override
        public Elements getAllElements() {
            throw new AssertionError("getAllElements materializes the attacker DOM");
        }

        @Override
        public Elements select(String cssQuery) {
            throw new AssertionError("select materializes attacker-wide candidates");
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

    private static final class SecurityExceptionScanner extends ZXingCPPScanner {
        @Override
        public boolean hasZXingCPP() {
            return true;
        }

        @Override
        public List<Result> scan(
                Path imagePath, ZXingCPPConfig config, ParseContext context) {
            throw new SecurityException("simulated scanner policy rejection");
        }
    }
}
