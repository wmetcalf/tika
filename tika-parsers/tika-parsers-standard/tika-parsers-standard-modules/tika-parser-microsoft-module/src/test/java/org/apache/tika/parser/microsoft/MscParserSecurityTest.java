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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.xml.parsers.SAXParser;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Parser;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.XMLFilterImpl;

import org.apache.tika.detect.Detector;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.writefilter.MetadataWriteLimiterFactory;
import org.apache.tika.metadata.writefilter.StandardMetadataLimiterFactory;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

public class MscParserSecurityTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    public void testCanonicalXmlValuesDriveClassification() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <m:MMC_ConsoleFile xmlns:m="urn:mmc">
                  <m:CommandLine>po&#119;ershell.exe -NoProfile</m:CommandLine>
                  <m:String>res://apds.d&#108;l/redirect.html?target=javascript:alert(1)</m:String>
                </m:MMC_ConsoleFile>
                """;

        ParseResult result = parse(xml);
        assertEquals("powershell.exe -NoProfile",
                result.metadata.get("msc:command"));
        assertEquals(
                "res://apds.dll/redirect.html?target=javascript:alert(1)",
                result.metadata.get("msc:string"));
        assertNotNull(result.metadata.get("ExploitClass"));
    }

    @Test
    public void testUrlQueryParametersAreNotTruncatedAtAmpersand() throws Exception {
        ParseResult result = parse("""
                <MMC_ConsoleFile>
                  <String>https://api.example.test/data?key=abc&amp;format=json</String>
                </MMC_ConsoleFile>
                """);

        assertEquals(
                List.of("https://api.example.test/data?key=abc&format=json"),
                List.of(result.metadata.getValues("msc:url")));
    }

    @Test
    public void testExternalXmlEntitiesAreNotResolved() throws Exception {
        String secret = "MSC_XXE_SECRET_SHOULD_NOT_LEAK";
        Path secretFile = temporaryDirectory.resolve("secret.txt");
        Files.writeString(secretFile, secret, StandardCharsets.UTF_8);
        String xml = String.format(Locale.ROOT, """
                <!DOCTYPE MMC_ConsoleFile [
                  <!ENTITY xxe SYSTEM "%s">
                ]>
                <MMC_ConsoleFile><String>&xxe;</String></MMC_ConsoleFile>
                """, secretFile.toUri());

        ParseResult result = parse(xml);
        assertFalse(result.body.contains(secret));
        for (String value : result.metadata.getValues("msc:string")) {
            assertFalse(value.contains(secret));
        }
    }

    @Test
    public void testShellCommandDefinitionKeepsCommandAndParametersTogether() throws Exception {
        String xml = """
                <MMC_ConsoleFile>
                  <ShellCommandDefinition>
                    <Command>cmd.exe</Command>
                    <Params>/c whoami</Params>
                  </ShellCommandDefinition>
                </MMC_ConsoleFile>
                """;

        ParseResult result = parse(xml);
        assertEquals("cmd.exe", result.metadata.get("msc:command"));
        assertEquals("cmd.exe /c whoami",
                result.metadata.get("msc:task_command"));
        assertNotNull(result.metadata.get("ExploitClass"));
    }

    @Test
    public void testShellCommandDefinitionClassifiesArbitraryExecutable() throws Exception {
        ParseResult result = parse("""
                <MMC_ConsoleFile>
                  <ShellCommandDefinition>
                    <Command>C:\\Users\\Public\\stage.exe</Command>
                    <Params>--run</Params>
                  </ShellCommandDefinition>
                </MMC_ConsoleFile>
                """);

        assertEquals("C:\\Users\\Public\\stage.exe --run",
                result.metadata.get("msc:task_command"));
        assertNotNull(result.metadata.get("ExploitClass"));
    }

    @Test
    public void testShellCommandDefinitionClassifiesExtensionlessExecutable() throws Exception {
        ParseResult result = parse("""
                <MMC_ConsoleFile>
                  <ShellCommandDefinition>
                    <Command>dism</Command>
                    <Params>/online /get-features</Params>
                  </ShellCommandDefinition>
                </MMC_ConsoleFile>
                """);

        assertEquals("dism /online /get-features",
                result.metadata.get("msc:task_command"));
        assertNotNull(result.metadata.get("ExploitClass"),
                "every ShellCommandDefinition is an execution boundary");
    }

    @Test
    public void testNestedCommandCapturesAreBounded() throws Exception {
        String xml = "<MMC_ConsoleFile>"
                + "<CommandLine>".repeat(64)
                + "A".repeat(80_000)
                + "</CommandLine>".repeat(64)
                + "</MMC_ConsoleFile>";

        ParseResult result = parse(xml);
        for (String command : result.metadata.getValues("msc:command")) {
            assertFalse(command.length() > 65_536,
                    "a nested capture retained an unbounded command");
        }
    }

    @Test
    public void testGrimResourceAfterCaptureLimitIsStillClassified() throws Exception {
        String xml = "<MMC_ConsoleFile><String>"
                + "A".repeat(65_536)
                + "res://apds.d&#108;l/redirect.html?target=javascript:alert(1)"
                + "</String></MMC_ConsoleFile>";

        ParseResult result = parse(xml);
        assertNotNull(result.metadata.get("ExploitClass"),
                "bounded String metadata must not bound GrimResource detection");
    }

    @Test
    public void testNestedCaptureDepthIsBounded() throws Exception {
        String xml = "<MMC_ConsoleFile>"
                + "<String>".repeat(512)
                + "</String>".repeat(512)
                + "<CommandLine>powershell.exe -NoProfile</CommandLine>"
                + "</MMC_ConsoleFile>";

        ParseResult result = parse(xml);
        assertNotNull(result.metadata.get("msc:warning"),
                "excessive capture nesting must be signaled");
        assertNotNull(result.metadata.get("ExploitClass"),
                "an XML depth abort must fail closed even when its suffix cannot be parsed");
    }

    @Test
    public void testSequentialCaptureCardinalityIsBoundedAndSignaled() throws Exception {
        StringBuilder xml = new StringBuilder("<MMC_ConsoleFile>");
        for (int i = 0; i < 5_000; i++) {
            xml.append("<String>value-").append(i).append("</String>");
        }
        xml.append("</MMC_ConsoleFile>");

        ParseResult result = parse(xml.toString());
        assertTrue(result.metadata.getValues("msc:string").length <= 4_096,
                "shallow sequential captures must not grow retained metadata without bound");
        assertNotNull(result.metadata.get("msc:warning"),
                "dropping excess captures must be signaled");
        assertNotNull(result.metadata.get("ExploitClass"),
                "cardinality truncation can hide later execution indicators");
    }

    @Test
    public void testBinaryBlobCardinalityIsBoundedAndSignaled() throws Exception {
        StringBuilder xml = new StringBuilder("<MMC_ConsoleFile>");
        for (int i = 0; i < 300; i++) {
            xml.append("<Binary>AAAAAAAAAAAAAAAAAAAA</Binary>");
        }
        xml.append("</MMC_ConsoleFile>");

        ParseResult result = parseWithoutEmbedded(xml.toString());
        assertTrue(result.metadata.getValues("msc:binary_sha256").length <= 256,
                "binary blob metadata and embedded dispatch must have a hard count limit");
        assertNotNull(result.metadata.get("msc:warning"),
                "dropping excess binary blobs must be signaled");
        assertNotNull(result.metadata.get("ExploitClass"),
                "skipped binary blobs may hide executable content");
    }

    @Test
    public void testValidShortBinaryBlobsAreHashedAndDispatched() throws Exception {
        List<byte[]> embedded = new ArrayList<>();
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return true;
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext parseContext,
                                      boolean outputHtml) throws IOException {
                embedded.add(stream.readAllBytes());
            }
        });

        ParseResult result = parse("""
                <MMC_ConsoleFile>
                  <Binary>QUJD</Binary>
                  <BinaryData>REVG</BinaryData>
                </MMC_ConsoleFile>
                """, context);

        assertEquals(List.of(
                        "b5d4045c3f466fa91fe2cc6abe79232a1a57cdf104f7a26e716e0a1e2789df78",
                        "967c5a5b7e2fbbe3080a0c5cefea7c279570b16ae8465525538bc3b115267a45"),
                List.of(result.metadata.getValues("msc:binary_sha256")));
        assertEquals(2, result.metadata.getValues("msc:binary_mime").length);
        assertEquals(2, embedded.size());
        assertArrayEquals(new byte[]{'A', 'B', 'C'}, embedded.get(0));
        assertArrayEquals(new byte[]{'D', 'E', 'F'}, embedded.get(1));
    }

    @Test
    public void testEmptyBinaryElementsRemainIgnored() throws Exception {
        ParseResult result = parseWithoutEmbedded("""
                <MMC_ConsoleFile>
                  <Binary></Binary>
                  <BinaryData> \s
                  </BinaryData>
                  <Binary/>
                </MMC_ConsoleFile>
                """);

        assertEquals(0, result.metadata.getValues("msc:binary_sha256").length);
        assertEquals(0, result.metadata.getValues("msc:binary_mime").length);
        assertNull(result.metadata.get("msc:warning"));
        assertNull(result.metadata.get("ExploitClass"));
    }

    @Test
    public void testEmbeddedMetadataUsesContextLimiter() throws Exception {
        ParseContext context = new ParseContext();
        StandardMetadataLimiterFactory factory = new StandardMetadataLimiterFactory();
        factory.setIncludeFields(Set.of("allowed"));
        context.set(MetadataWriteLimiterFactory.class, factory);
        AtomicBoolean limiterApplied = new AtomicBoolean();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                metadata.set("not-allowed", "probe");
                limiterApplied.set(metadata.get("not-allowed") == null);
                return false;
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext parseContext,
                                      boolean outputHtml) {
                throw new AssertionError("embedded parsing should be disabled");
            }
        });

        parse("<MMC_ConsoleFile><Binary>"
                + "QUJDREVGR0hJSktMTU5PUA=="
                + "</Binary></MMC_ConsoleFile>", context);

        assertTrue(limiterApplied.get(),
                "fork-created embedded metadata must inherit the context limiter");
    }

    @Test
    public void testBinaryWriteLimitPropagates() {
        assertThrows(WriteLimitReachedException.class,
                () -> parseWithEmbeddedException(new WriteLimitReachedException(7)));
    }

    @Test
    public void testBinarySecurityExceptionPropagates() {
        assertThrows(SecurityException.class,
                () -> parseWithEmbeddedException(
                        new SecurityException("simulated binary security boundary")));
    }

    @Test
    public void testBinaryDownstreamSaxDenialPropagates() throws Exception {
        String rejectedText = "blocked MSC embedded output";
        SAXException denial =
                new SAXException("simulated MSC output policy denial");
        ParseContext context = embeddedOutputContext(rejectedText);

        SAXException thrown;
        try (TikaInputStream stream = TikaInputStream.get(
                ("<MMC_ConsoleFile><BinaryData>"
                        + "QUJDREVGR0hJSktMTU5PUA=="
                        + "</BinaryData></MMC_ConsoleFile>")
                        .getBytes(StandardCharsets.UTF_8))) {
            thrown = assertThrows(SAXException.class,
                    () -> new MscParser().parse(
                            stream, new TextRejectingHandler(rejectedText, denial),
                            new Metadata(), context));
        }

        assertSame(denial, thrown);
    }

    @Test
    public void testBinaryDirectUncheckedDenialPropagates() throws Exception {
        String rejectedText = "blocked unchecked MSC embedded output";
        IllegalStateException denial =
                new IllegalStateException("simulated unchecked MSC output denial");
        ParseContext context = embeddedOutputContext(rejectedText);

        IllegalStateException thrown;
        try (TikaInputStream stream = TikaInputStream.get(
                ("<MMC_ConsoleFile><BinaryData>"
                        + "QUJDREVGR0hJSktMTU5PUA=="
                        + "</BinaryData></MMC_ConsoleFile>")
                        .getBytes(StandardCharsets.UTF_8))) {
            thrown = assertThrows(IllegalStateException.class,
                    () -> new MscParser().parse(
                            stream,
                            new UncheckedTextRejectingHandler(
                                    rejectedText, denial),
                            new Metadata(), context));
        }

        assertSame(denial, thrown);
    }

    @Test
    public void testBinarySwallowedSaxDenialPropagates() throws Exception {
        String rejectedText = "swallowed MSC embedded output refusal";
        SAXException denial =
                new SAXException("simulated swallowed MSC output denial");
        ParseContext context =
                swallowingEmbeddedOutputContext(rejectedText);

        SAXException thrown;
        try (TikaInputStream stream = TikaInputStream.get(
                ("<MMC_ConsoleFile><BinaryData>"
                        + "QUJDREVGR0hJSktMTU5PUA=="
                        + "</BinaryData></MMC_ConsoleFile>")
                        .getBytes(StandardCharsets.UTF_8))) {
            thrown = assertThrows(SAXException.class,
                    () -> new MscParser().parse(
                            stream, new TextRejectingHandler(rejectedText, denial),
                            new Metadata(), context));
        }

        assertSame(denial, thrown);
    }

    @Test
    public void testBinaryMimeDetectionSecurityExceptionPropagates() {
        SecurityException denial =
                new SecurityException("simulated binary MIME policy denial");
        ParseContext context = new ParseContext();
        context.set(Detector.class, (stream, metadata, parseContext) -> {
            throw denial;
        });
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return false;
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext parseContext,
                                      boolean outputHtml) {
                throw new AssertionError("embedded parsing should be disabled");
            }
        });

        SecurityException thrown = assertThrows(SecurityException.class,
                () -> parse("<MMC_ConsoleFile><BinaryData>"
                        + "QUJDREVGR0hJSktMTU5PUA=="
                        + "</BinaryData></MMC_ConsoleFile>", context));

        assertSame(denial, thrown);
    }

    @Test
    public void testXmlFieldExtractionSecurityExceptionPropagates() {
        SecurityException denial =
                new SecurityException("simulated XML policy denial");
        ParseContext context = new ParseContext();
        context.set(SAXParser.class, new SecurityDenyingSaxParser(denial));

        SecurityException thrown = assertThrows(SecurityException.class,
                () -> parse("<MMC_ConsoleFile/>", context));

        assertEquals(denial, thrown);
    }

    @Test
    public void testOrdinaryBinaryParseFailureRemainsIncompleteWarning() throws Exception {
        ParseResult result = parseWithEmbeddedException(
                new IOException("simulated ordinary binary failure"));

        assertTrue(java.util.Arrays.stream(result.metadata.getValues("msc:warning"))
                .anyMatch(value -> value.contains("simulated ordinary binary failure")));
        assertNotNull(result.metadata.get("ExploitClass"),
                "ordinary embedded parse failures must retain the incomplete marker");
    }

    @Test
    public void testOversizedBinaryBlobIsSkippedAndSignaled() throws Exception {
        String xml = "<MMC_ConsoleFile><Binary>"
                + "A".repeat(8 * 1024 * 1024 + 4)
                + "</Binary></MMC_ConsoleFile>";

        ParseResult result = parseWithoutEmbedded(xml);
        assertEquals(0, result.metadata.getValues("msc:binary_sha256").length,
                "an oversized base64 capture must be rejected before copying or decoding it");
        assertNotNull(result.metadata.get("msc:warning"));
        assertNotNull(result.metadata.get("ExploitClass"),
                "skipped binary content must fail closed for classification");
    }

    @Test
    public void testRepeatedBinaryPrefixesAreScannedInLinearTime() {
        String xml = "<MMC_ConsoleFile>"
                + "<Binary ".repeat(24_000)
                + "</MMC_ConsoleFile>";

        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            ParseResult result = parseWithoutEmbedded(xml);
            assertNotNull(result.metadata.get("msc:warning"));
            assertNotNull(result.metadata.get("ExploitClass"),
                    "incomplete binary parsing must fail closed");
        });
    }

    @Test
    public void testIncompleteXmlFailsClosedForSecurityClassification() throws Exception {
        ParseResult result = parse(
                "<MMC_ConsoleFile><CommandLine>powershell.exe -NoProfile");

        assertNotNull(result.metadata.get("msc:warning"));
        assertNotNull(result.metadata.get("ExploitClass"),
                "incomplete field extraction must remain security-visible");
    }

    @Test
    public void testInputTruncationFailsClosedForSecurityClassification() throws Exception {
        int analysisLimit = 32 * 1024 * 1024;
        byte[] input = new byte[analysisLimit + 128];
        java.util.Arrays.fill(input, (byte) ' ');
        byte[] validPrefix = "<MMC_ConsoleFile/>".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(validPrefix, 0, input, 0, validPrefix.length);
        byte[] hiddenSuffix =
                "<CommandLine>powershell.exe -NoProfile</CommandLine>"
                        .getBytes(StandardCharsets.UTF_8);
        System.arraycopy(hiddenSuffix, 0, input, analysisLimit, hiddenSuffix.length);

        Metadata metadata = new Metadata();
        BodyContentHandler body = new BodyContentHandler(-1);
        try (TikaInputStream stream = TikaInputStream.get(input)) {
            new MscParser().parse(stream, body, metadata, new ParseContext());
        }

        assertNotNull(metadata.get("msc:warning"),
                "bounded input must be reported as incomplete");
        assertNotNull(metadata.get("ExploitClass"),
                "a dangerous suffix beyond the analysis limit must not fail open");
    }

    @Test
    public void testOversizedCommandFailsClosedForSecurityClassification() throws Exception {
        ParseResult result = parse("<MMC_ConsoleFile><CommandLine>"
                + "A".repeat(64 * 1024)
                + "powershell.exe -NoProfile"
                + "</CommandLine></MMC_ConsoleFile>");

        assertNotNull(result.metadata.get("msc:warning"),
                "truncating a security field must be signaled");
        assertNotNull(result.metadata.get("ExploitClass"),
                "a command suffix beyond the capture bound must not fail open");
    }

    @Test
    public void testOversizedCommandAttributeFailsClosed() throws Exception {
        ParseResult result = parse("<MMC_ConsoleFile CommandLine=\""
                + "A".repeat(80_000)
                + "\"/>");

        assertTrue(result.metadata.get("msc:command").length() <= 64 * 1024,
                "command attributes must use the same bound as command elements");
        assertNotNull(result.metadata.get("msc:warning"),
                "truncating a command attribute must be signaled");
        assertNotNull(result.metadata.get("ExploitClass"),
                "a truncated command attribute must fail closed");
    }

    @Test
    public void testPwshCommandIsClassified() throws Exception {
        ParseResult result = parse(
                "<MMC_ConsoleFile CommandLine=\"pwsh.exe -NoProfile -c whoami\"/>");

        assertEquals("pwsh.exe -NoProfile -c whoami",
                result.metadata.get("msc:command"));
        assertNotNull(result.metadata.get("ExploitClass"));
    }

    @Test
    public void testExtensionlessCmdWithLeadingSwitchIsClassified() throws Exception {
        ParseResult result = parse("""
                <MMC_ConsoleFile>
                  <ShellCommandDefinition>
                    <Command>cmd</Command>
                    <Params>/q /c calc.exe</Params>
                  </ShellCommandDefinition>
                </MMC_ConsoleFile>
                """);

        assertEquals("cmd /q /c calc.exe", result.metadata.get("msc:task_command"));
        assertNotNull(result.metadata.get("ExploitClass"));
    }

    private static ParseResult parse(String xml) throws Exception {
        return parse(xml, new ParseContext());
    }

    private static ParseResult parseWithoutEmbedded(String xml) throws Exception {
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return false;
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext parseContext,
                                      boolean outputHtml) throws IOException {
                throw new AssertionError("embedded parsing should be disabled");
            }
        });
        return parse(xml, context);
    }

    private static ParseResult parseWithEmbeddedException(Exception failure) throws Exception {
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return true;
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext parseContext,
                                      boolean outputHtml) throws IOException, SAXException {
                if (failure instanceof IOException ioException) {
                    throw ioException;
                }
                if (failure instanceof SAXException saxException) {
                    throw saxException;
                }
                if (failure instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new AssertionError("unsupported test exception", failure);
            }
        });
        return parse("<MMC_ConsoleFile><BinaryData>"
                + "QUJDREVGR0hJSktMTU5PUA=="
                + "</BinaryData></MMC_ConsoleFile>", context);
    }

    private static ParseResult parse(String xml, ParseContext context) throws Exception {
        Metadata metadata = new Metadata();
        BodyContentHandler body = new BodyContentHandler(-1);
        try (TikaInputStream stream = TikaInputStream.get(
                xml.getBytes(StandardCharsets.UTF_8))) {
            new MscParser().parse(stream, body, metadata, context);
        }
        return new ParseResult(body.toString(), metadata);
    }

    private record ParseResult(String body, Metadata metadata) {
    }

    private static ParseContext embeddedOutputContext(String output) {
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return true;
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext parseContext,
                                      boolean outputHtml) throws SAXException {
                char[] chars = output.toCharArray();
                handler.characters(chars, 0, chars.length);
            }
        });
        return context;
    }

    private static ParseContext swallowingEmbeddedOutputContext(String output) {
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return true;
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext parseContext,
                                      boolean outputHtml) {
                char[] chars = output.toCharArray();
                try {
                    handler.characters(chars, 0, chars.length);
                } catch (SAXException ignored) {
                    // Simulate an embedded parser swallowing downstream refusal.
                }
            }
        });
        return context;
    }

    private static final class TextRejectingHandler extends DefaultHandler {

        private final String rejectedText;
        private final SAXException denial;

        private TextRejectingHandler(String rejectedText, SAXException denial) {
            this.rejectedText = rejectedText;
            this.denial = denial;
        }

        @Override
        public void characters(char[] ch, int start, int length)
                throws SAXException {
            if (new String(ch, start, length).contains(rejectedText)) {
                throw denial;
            }
        }
    }

    private static final class UncheckedTextRejectingHandler extends DefaultHandler {

        private final String rejectedText;
        private final RuntimeException denial;

        private UncheckedTextRejectingHandler(
                String rejectedText, RuntimeException denial) {
            this.rejectedText = rejectedText;
            this.denial = denial;
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (new String(ch, start, length).contains(rejectedText)) {
                throw denial;
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static final class SecurityDenyingSaxParser extends SAXParser {

        private final SecurityException denial;

        private SecurityDenyingSaxParser(SecurityException denial) {
            this.denial = denial;
        }

        @Override
        @SuppressForbidden
        public Parser getParser() {
            throw new UnsupportedOperationException("SAX1 parser not used");
        }

        @Override
        public XMLReader getXMLReader() {
            return new XMLFilterImpl() {
                @Override
                public void parse(InputSource input) {
                    throw denial;
                }

                @Override
                public void parse(String systemId) {
                    throw denial;
                }
            };
        }

        @Override
        public boolean isNamespaceAware() {
            return true;
        }

        @Override
        public boolean isValidating() {
            return false;
        }

        @Override
        public void setProperty(String name, Object value) {
            // no-op
        }

        @Override
        public Object getProperty(String name) {
            return null;
        }
    }
}
