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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.ContentHandler;

import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
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
    public void testExternalXmlEntitiesAreNotResolved() throws Exception {
        String secret = "MSC_XXE_SECRET_SHOULD_NOT_LEAK";
        Path secretFile = temporaryDirectory.resolve("secret.txt");
        Files.writeString(secretFile, secret, StandardCharsets.UTF_8);
        String xml = """
                <!DOCTYPE MMC_ConsoleFile [
                  <!ENTITY xxe SYSTEM "%s">
                ]>
                <MMC_ConsoleFile><String>&xxe;</String></MMC_ConsoleFile>
                """.formatted(secretFile.toUri());

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
    public void testIncompleteXmlFailsClosedForSecurityClassification() throws Exception {
        ParseResult result = parse(
                "<MMC_ConsoleFile><CommandLine>powershell.exe -NoProfile");

        assertNotNull(result.metadata.get("msc:warning"));
        assertNotNull(result.metadata.get("ExploitClass"),
                "incomplete field extraction must remain security-visible");
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
}
