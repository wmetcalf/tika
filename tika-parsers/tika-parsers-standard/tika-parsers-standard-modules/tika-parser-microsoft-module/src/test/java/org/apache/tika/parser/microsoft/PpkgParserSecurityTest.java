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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.writefilter.StandardMetadataLimiterFactory;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

/**
 * Adversarial-input regression tests for {@link PpkgParser} (fork-authored WIM/PPKG
 * parser). Each test feeds a crafted malformed WIM and asserts the Tika parser
 * contract: on hostile input the parser must either extract what it can or throw a
 * {@link TikaException} — it must NEVER escape a raw unchecked {@link RuntimeException}
 * to the caller, and must NEVER hang. Pre-fix these reproduce real defects
 * (uncaught ArrayIndexOutOfBoundsException / NegativeArraySizeException, and an
 * exponential directory-walk hang).
 */
public class PpkgParserSecurityTest {

    private static final String COMMAND = "powershell.exe -NoProfile";

    @TempDir
    Path temporaryDirectory;

    private static void put7(byte[] b, int off, long v) {
        for (int i = 0; i < 7; i++) {
            b[off + i] = (byte) ((v >>> (8 * i)) & 0xff);
        }
    }

    private static void parse(byte[] wim) throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(wim)) {
            new PpkgParser().parse(tis, new BodyContentHandler(-1),
                    new Metadata(), new ParseContext());
        }
    }

    private static Metadata parseMetadata(byte[] wim) throws Exception {
        return parseResult(wim).metadata;
    }

    private static ParseResult parseResult(byte[] wim) throws Exception {
        Metadata metadata = new Metadata();
        BodyContentHandler body = new BodyContentHandler(-1);
        try (TikaInputStream tis = TikaInputStream.get(wim)) {
            new PpkgParser().parse(tis, body, metadata, new ParseContext());
        }
        return new ParseResult(body.toString(), metadata);
    }

    /** Run parse(); pass if it completes or throws TikaException; fail on a raw RuntimeException. */
    private static void assertContractHeld(byte[] wim, String label) {
        try {
            parse(wim);
        } catch (TikaException e) {
            // acceptable: graceful failure
        } catch (RuntimeException e) {
            fail(label + ": parser leaked a raw " + e.getClass().getName()
                    + " (Tika contract requires TikaException): " + e);
        } catch (Exception e) {
            // IOException/SAXException are declared/acceptable
        }
    }

    private static byte[] header(int size, int chunkSize) {
        byte[] b = new byte[Math.max(size, 208)];
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        // WIM magic "MSWIM\0\0\0"
        byte[] magic = {0x4d, 0x53, 0x57, 0x49, 0x4d, 0x00, 0x00, 0x00};
        System.arraycopy(magic, 0, b, 0, magic.length);
        buf.putInt(16, 0x00020000);   // XPRESS flag
        buf.putInt(20, chunkSize);
        return b;
    }

    // ── Finding: line 148 — xmlHdr.offset never bounds-checked ────────────────
    @Test
    public void xmlDescriptorOffsetOutOfRange() {
        byte[] b = header(208, 0);
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        put7(b, 72, 100);              // xmlHdr.size = 100 (passes 0<size<4MB)
        buf.putLong(80, 100_000L);     // xmlHdr.offset = 100000, far past the 208-byte file
        assertContractHeld(b, "xml-descriptor-offset");
    }

    // ── Finding: line 298 — XPRESS nChunks integer overflow ───────────────────
    @Test
    public void chunkCountIntegerOverflow() {
        // chunkSize = Integer.MAX_VALUE, resource uncompressed = 2 ->
        // nChunks = (2 + MAX - 1)/MAX overflows to a negative value.
        int fileLen = 300;
        byte[] b = header(fileLen, Integer.MAX_VALUE);
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        // lookupHdr @48: size=50 (1 entry), offset=208, uncompressed=50
        put7(b, 48, 50);
        buf.putLong(48 + 8, 208);
        buf.putLong(48 + 16, 50);
        // lookup entry @208: size=10 (compressed on disk), flags=0x06 (METADATA|COMPRESSED),
        // offset=258, uncompressed=2
        put7(b, 208, 10);
        b[208 + 7] = 0x06;
        buf.putLong(208 + 8, 258);
        buf.putLong(208 + 16, 2);
        assertContractHeld(b, "nchunks-overflow");
    }

    @Test
    public void lookupTableRangeAdditionCannotOverflow() {
        byte[] b = header(208, 32768);
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        put7(b, 48, 0x00ff_ffff_ffff_ffffL);
        buf.putLong(56, Long.MAX_VALUE);
        buf.putLong(64, 50);

        assertContractHeld(b, "lookup-range-overflow");
    }

    @Test
    public void decompressedResourceLimitRejectsHeapSizedDeclarations() {
        assertFalse(PpkgParser.isDecompressedResourceSizeAllowed(256 * 1024 * 1024L),
                "a tiny WIM resource must not be allowed to allocate a 256 MiB output");
        assertFalse(PpkgParser.isDecompressedResourceSizeAllowed(64 * 1024 * 1024L),
                "one accepted resource must leave room for the container and parser state");
        assertTrue(PpkgParser.isDecompressedResourceSizeAllowed(8 * 1024 * 1024L));
    }

    @Test
    public void oversizedCompressedMetadataResourceFailsClosed() throws Exception {
        Metadata metadata = parseMetadata(
                buildOversizedMetadataResource(10, 0x06, 64L * 1024 * 1024 + 1));

        assertNotNull(metadata.get("ppkg:warning"),
                "skipping oversized compressed metadata must be signaled");
        assertNotNull(metadata.get("ExploitClass"),
                "skipping command-bearing metadata must fail closed");
    }

    @Test
    public void oversizedUncompressedMetadataResourceFailsClosed() throws Exception {
        long oversized = 64L * 1024 * 1024 + 1;
        Metadata metadata = parseMetadata(
                buildOversizedMetadataResource(oversized, 0x04, oversized));

        assertNotNull(metadata.get("ppkg:warning"),
                "the resource cap must apply before the uncompressed copy path");
        assertNotNull(metadata.get("ExploitClass"),
                "skipping command-bearing metadata must fail closed");
    }

    @Test
    public void malformedCompressedMetadataResourceFailsClosed() throws Exception {
        Metadata metadata = parseMetadata(
                buildOversizedMetadataResource(10, 0x06, 100));

        assertNotNull(metadata.get("ppkg:warning"),
                "structurally invalid compressed metadata must be signaled");
        assertNotNull(metadata.get("ExploitClass"),
                "skipping structurally invalid command-bearing metadata must fail closed");
    }

    @Test
    public void incompleteXpressOutputFailsClosed() throws Exception {
        Metadata metadata = parseMetadata(buildIncompleteXpressMetadataResource());

        assertNotNull(metadata.get("ppkg:warning"),
                "premature XPRESS termination must be reported");
        assertNotNull(metadata.get("ExploitClass"),
                "an incomplete metadata decode can hide provisioning commands");
    }

    @Test
    public void oversizedLookupTableFailsClosed() throws Exception {
        byte[] wim = header(300, 32768);
        ByteBuffer buffer = ByteBuffer.wrap(wim).order(ByteOrder.LITTLE_ENDIAN);
        long lookupSize = 100_001L * 50L;
        putResourceHeader(wim, buffer, 48, lookupSize, 0, 208, lookupSize);

        Metadata metadata = parseMetadata(wim);
        assertNotNull(metadata.get("ppkg:warning"),
                "lookup-table object amplification must be bounded and signaled");
        assertNotNull(metadata.get("ExploitClass"),
                "an incomplete lookup table can hide command-bearing resources");
    }

    // ── Finding: line 585 — walkDirectory exponential fan-out (no visited-set) ─
    @Test
    public void selfReferentialDirectoryWalkTerminates() {
        int metaOff = 258;
        int metaLen = 256;
        int fileLen = metaOff + metaLen;
        byte[] b = header(fileLen, 0);
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        // lookupHdr @48: one uncompressed METADATA entry
        put7(b, 48, 50);
        buf.putLong(48 + 8, 208);
        buf.putLong(48 + 16, 50);
        // lookup entry @208: size==uncompressed (uncompressed metadata), flags=0x04
        put7(b, 208, metaLen);
        b[208 + 7] = 0x04;
        buf.putLong(208 + 8, metaOff);
        buf.putLong(208 + 16, metaLen);
        // metadata resource @258: sdLen=8 -> rootOff=8; root dentry childOff @ rootOff+16 = 32
        int m = metaOff;
        buf.putInt(m + 0, 8);          // sdLen
        buf.putLong(m + 8 + 16, 32);   // root dentry (@rootOff=8) subdir_offset -> listing @32
        // listing @32: two directory dentries both pointing subdir_offset back to 32
        int d1 = m + 32;
        buf.putLong(d1, 104);          // entryLen
        buf.putInt(d1 + 8, 0x10);      // attrs = directory
        buf.putLong(d1 + 16, 32);      // subdir_offset -> back to this listing
        int d2 = m + 136;
        buf.putLong(d2, 104);
        buf.putInt(d2 + 8, 0x10);
        buf.putLong(d2 + 16, 32);
        // terminator @240: entryLen 0 (already zero-filled)
        assertTimeoutPreemptively(Duration.ofSeconds(15),
                () -> assertContractHeld(b, "self-referential-walk"),
                "PpkgParser.walkDirectory did not terminate on a self-referential "
                        + "directory listing (exponential fan-out DoS)");
    }

    @Test
    public void directoryEntryLengthOverflowTerminates() {
        byte[] wim = buildWim(
                "<provisioning><CommandLine>benign</CommandLine></provisioning>");
        ByteBuffer.wrap(wim).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(308 + 32, Long.MAX_VALUE);

        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> parse(wim),
                "an overflowing directory-entry length must not prevent cursor progress");
    }

    @Test
    public void aliasedResourceIsParsedOnce() throws Exception {
        ParseResult result = parseResult(buildAliasedWim(32,
                "<provisioning><CommandLine>" + COMMAND
                        + "</CommandLine></provisioning>"));

        assertEquals(1, result.metadata.getValues("ppkg:command").length);
        assertEquals(1, countOccurrences(result.body, "Source: "),
                "content-addressed WIM aliases must not repeatedly expand one resource");
    }

    @Test
    public void sameContentWithDifferentSemanticExtensionsIsProcessedSeparately()
            throws Exception {
        ParseResult result = parseResult(buildAliasedWim(
                new String[]{"benign.dat", "stage.ps1"},
                "Write-Output 'same bytes'".getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, result.metadata.getValues("ppkg:embedded_file_name").length);
        assertTrue(Arrays.asList(result.metadata.getValues("ppkg:embedded_file_name"))
                .contains("benign.dat"));
        assertTrue(Arrays.asList(result.metadata.getValues("ppkg:embedded_file_name"))
                .contains("stage.ps1"));
    }

    @Test
    public void sameContentWithDifferentXmlExtensionsPreservesBothSources()
            throws Exception {
        ParseResult result = parseResult(buildAliasedWim(
                new String[]{"policy.xml", "commands.provxml"},
                ("<provisioning><CommandLine>" + COMMAND
                        + "</CommandLine></provisioning>")
                        .getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, result.metadata.getValues("ppkg:command").length);
        assertEquals(2, countOccurrences(result.body, "Source: "));
        assertTrue(result.body.contains("Source: policy.xml"));
        assertTrue(result.body.contains("Source: commands.provxml"));
    }

    @Test
    public void embeddedAssetCompatibilityArraysStayAlignedUnderLowTotalBudget()
            throws Exception {
        assertEmbeddedAssetCompatibilityArraysStayAligned(158);
        assertEmbeddedAssetCompatibilityArraysStayAligned(626);
    }

    private static void assertEmbeddedAssetCompatibilityArraysStayAligned(int totalBytes)
            throws Exception {
        StandardMetadataLimiterFactory factory = new StandardMetadataLimiterFactory();
        factory.setMaxKeySize(100);
        factory.setMaxFieldSize(10_000);
        factory.setMaxTotalBytes(totalBytes);
        factory.setMaxValuesPerField(10);
        Metadata metadata = new Metadata(factory.newInstance());

        emitDataAssetMetadataForTest(metadata, 'a', "first.exe");
        emitDataAssetMetadataForTest(metadata, 'b', "second.ps1");

        String[] fields = {
                "ppkg:embedded_file_sha256",
                "ppkg:embedded_file_md5",
                "ppkg:embedded_file_sha1",
                "ppkg:embedded_file_name",
                "ppkg:embedded_file_size",
                "ppkg:embedded_file_mime"
        };
        int expected = metadata.getValues(fields[0]).length;
        for (String field : fields) {
            assertEquals(expected, metadata.getValues(field).length,
                    "low total budgets must not split PPKG compatibility records at "
                            + field + " for total bytes " + totalBytes);
        }
    }

    @Test
    public void singleQuotedCommandAttributesAreCanonical() throws Exception {
        String xml = """
                <wap-provisioningdoc xmlns:p="urn:test">
                  <p:characteristic>
                    <p:parm name='CommandLine' value='powershell.exe -NoProfile'/>
                  </p:characteristic>
                </wap-provisioningdoc>
                """;

        Metadata metadata = parseMetadata(buildWim(xml));
        assertEquals(COMMAND, metadata.get("ppkg:command"));
        assertEquals(1, metadata.getValues("ppkg:data_asset_ref").length);
        assertEquals("powershell.exe", metadata.get("ppkg:data_asset_ref"));
        assertNotNull(metadata.get("ExploitClass"));
    }

    @Test
    public void dangerousReferencesInOrdinaryElementTextArePreserved() throws Exception {
        String xml = """
                <wap-provisioningdoc>
                  <CustomData>\\\\server\\share\\stage.ps1</CustomData>
                </wap-provisioningdoc>
                """;

        Metadata metadata = parseMetadata(buildWim(xml));
        assertEquals(1, metadata.getValues("ppkg:data_asset_ref").length);
        assertEquals("\\\\server\\share\\stage.ps1",
                metadata.get("ppkg:data_asset_ref"));
    }

    @Test
    public void dangerousReferencesWithEncodedAndPathSuffixesArePreserved() throws Exception {
        String xml = """
                <wap-provisioningdoc>
                  <CustomData>https://example.invalid/payload.exe?download=1
                    https://example.invalid/stage.ps1#run
                    (https://example.invalid/setup.msi)
                    https://example.invalid/payload%2Eexe?download=1
                    https://example.invalid/payload.%65xe?download=1
                    https://example.invalid/stage.p%731#run
                    https://example.invalid/payload.exe;v=1
                    https://example.invalid/payload.exe%3Bv=1
                    C:\\temp\\payload.exe:stream</CustomData>
                </wap-provisioningdoc>
                """;

        Metadata metadata = parseMetadata(buildWim(xml));
        assertEquals(9, metadata.getValues("ppkg:data_asset_ref").length);
        assertEquals("https://example.invalid/payload.exe?download=1",
                metadata.getValues("ppkg:data_asset_ref")[0]);
        assertEquals("https://example.invalid/stage.ps1#run",
                metadata.getValues("ppkg:data_asset_ref")[1]);
        assertEquals("(https://example.invalid/setup.msi)",
                metadata.getValues("ppkg:data_asset_ref")[2]);
        assertEquals("https://example.invalid/payload%2Eexe?download=1",
                metadata.getValues("ppkg:data_asset_ref")[3]);
        assertEquals("https://example.invalid/payload.%65xe?download=1",
                metadata.getValues("ppkg:data_asset_ref")[4]);
        assertEquals("https://example.invalid/stage.p%731#run",
                metadata.getValues("ppkg:data_asset_ref")[5]);
        assertEquals("https://example.invalid/payload.exe;v=1",
                metadata.getValues("ppkg:data_asset_ref")[6]);
        assertEquals("https://example.invalid/payload.exe%3Bv=1",
                metadata.getValues("ppkg:data_asset_ref")[7]);
        assertEquals("C:\\temp\\payload.exe:stream",
                metadata.getValues("ppkg:data_asset_ref")[8]);
    }

    @Test
    public void executableLookingUrlHostIsNotADataReference() throws Exception {
        Metadata metadata = parseMetadata(buildWim("""
                <wap-provisioningdoc>
                  <CustomData>https://host.exe:443/index.html</CustomData>
                </wap-provisioningdoc>
                """));

        assertEquals(0, metadata.getValues("ppkg:data_asset_ref").length);
    }

    @Test
    public void dataReferenceCardinalityIsBoundedAndSignaled() throws Exception {
        StringBuilder xml = new StringBuilder("<wap-provisioningdoc><CustomData>");
        for (int i = 0; i < 5_000; i++) {
            xml.append("payload-").append(i).append(".exe ");
        }
        xml.append("</CustomData></wap-provisioningdoc>");

        Metadata metadata = parseMetadata(buildWim(xml.toString()));
        assertTrue(metadata.getValues("ppkg:data_asset_ref").length <= 4_096,
                "hostile XML must not retain an unbounded number of data references");
        assertNotNull(metadata.get("ppkg:warning"),
                "dropping excess data references must be signaled");
        assertNotNull(metadata.get("ExploitClass"),
                "cardinality truncation can hide later execution indicators");
    }

    @Test
    public void dangerousSuffixAfterStreamingTokenLimitIsPreserved() throws Exception {
        String xml = "<wap-provisioningdoc><CustomData>\\\\server\\share\\"
                + "A".repeat(8_192)
                + "\\stage.ps1</CustomData></wap-provisioningdoc>";

        Metadata metadata = parseMetadata(buildWim(xml));
        assertEquals(1, metadata.getValues("ppkg:data_asset_ref").length);
        assertTrue(metadata.get("ppkg:data_asset_ref").endsWith("\\stage.ps1"));
        assertNotNull(metadata.get("ppkg:warning"),
                "oversized security-relevant tokens must be signaled");
    }

    @Test
    public void nestedCaptureDepthIsBounded() throws Exception {
        String xml = "<wap-provisioningdoc>"
                + "<CommandLine>".repeat(512)
                + "</CommandLine>".repeat(512)
                + "<CustomData>\\\\server\\share\\stage.ps1</CustomData>"
                + "<CommandLine>powershell.exe -NoProfile</CommandLine>"
                + "</wap-provisioningdoc>";

        Metadata metadata = parseMetadata(buildWim(xml));
        assertNotNull(metadata.get("ppkg:warning"),
                "excessive capture nesting must be signaled");
        assertNotNull(metadata.get("ExploitClass"),
                "an XML depth abort must fail closed even when its suffix cannot be parsed");
    }

    @Test
    public void incompleteXmlFailsClosedForSecurityClassification() throws Exception {
        Metadata metadata = parseMetadata(buildWim(
                "<wap-provisioningdoc><CommandLine>powershell.exe -NoProfile"));

        assertNotNull(metadata.get("ppkg:warning"));
        assertNotNull(metadata.get("ExploitClass"),
                "incomplete field extraction must remain security-visible");
    }

    @Test
    public void oversizedCommandFailsClosedForSecurityClassification() throws Exception {
        Metadata metadata = parseMetadata(buildWim(
                "<wap-provisioningdoc><CommandLine>"
                        + "A".repeat(64 * 1024)
                        + "powershell.exe -NoProfile"
                        + "</CommandLine></wap-provisioningdoc>"));

        assertNotNull(metadata.get("ppkg:warning"),
                "truncating a security field must be signaled");
        assertNotNull(metadata.get("ExploitClass"),
                "a command suffix beyond the capture bound must not fail open");
    }

    @Test
    public void oversizedCommandAttributeFailsClosed() throws Exception {
        Metadata metadata = parseMetadata(buildWim(
                "<wap-provisioningdoc><parm name=\"CommandLine\" value=\""
                        + "A".repeat(80_000)
                        + "\"/></wap-provisioningdoc>"));

        assertTrue(metadata.get("ppkg:command").length() <= 64 * 1024,
                "command attributes must use the same bound as command elements");
        assertNotNull(metadata.get("ppkg:warning"),
                "truncating a command attribute must be signaled");
        assertNotNull(metadata.get("ExploitClass"),
                "a truncated command attribute must fail closed");
    }

    @Test
    public void pwshCommandIsClassified() throws Exception {
        Metadata metadata = parseMetadata(buildWim("""
                <wap-provisioningdoc>
                  <parm name="CommandLine" value="pwsh.exe -NoProfile -c whoami"/>
                </wap-provisioningdoc>
                """));

        assertEquals("pwsh.exe -NoProfile -c whoami",
                metadata.get("ppkg:command"));
        assertNotNull(metadata.get("ExploitClass"));
    }

    @Test
    public void extensionlessCmdWithLeadingSwitchIsClassified() throws Exception {
        Metadata metadata = parseMetadata(buildWim("""
                <wap-provisioningdoc>
                  <parm name="CommandLine" value="cmd /q /c calc.exe"/>
                </wap-provisioningdoc>
                """));

        assertEquals("cmd /q /c calc.exe", metadata.get("ppkg:command"));
        assertNotNull(metadata.get("ExploitClass"));
    }

    @Test
    public void commandLineClassifiesArbitraryExecutable() throws Exception {
        Metadata metadata = parseMetadata(buildWim("""
                <wap-provisioningdoc>
                  <parm name="CommandLine"
                        value="C:\\Users\\Public\\stage.exe --run"/>
                </wap-provisioningdoc>
                """));

        assertEquals("C:\\Users\\Public\\stage.exe --run",
                metadata.get("ppkg:command"));
        assertNotNull(metadata.get("ExploitClass"));
    }

    @Test
    public void commandLineClassifiesExtensionlessExecutable() throws Exception {
        Metadata metadata = parseMetadata(buildWim("""
                <wap-provisioningdoc>
                  <parm name="CommandLine" value="dism /online /get-features"/>
                </wap-provisioningdoc>
                """));

        assertEquals("dism /online /get-features",
                metadata.get("ppkg:command"));
        assertNotNull(metadata.get("ExploitClass"),
                "every provisioning CommandLine is an execution boundary");
    }

    @Test
    public void nestedCommandCapturesAreBounded() throws Exception {
        String xml = "<wap-provisioningdoc>"
                + "<CommandLine>".repeat(64)
                + "A".repeat(80_000)
                + "</CommandLine>".repeat(64)
                + "</wap-provisioningdoc>";

        Metadata metadata = parseMetadata(buildWim(xml));
        for (String command : metadata.getValues("ppkg:command")) {
            assertFalse(command.length() > 65_536,
                    "a nested capture retained an unbounded command");
        }
    }

    @Test
    public void externalXmlEntitiesAreNotResolved() throws Exception {
        String secret = "PPKG_XXE_SECRET_SHOULD_NOT_LEAK";
        Path secretFile = temporaryDirectory.resolve("secret.txt");
        Files.writeString(secretFile, secret, StandardCharsets.UTF_8);
        String xml = String.format(Locale.ROOT, """
                <!DOCTYPE provisioning [
                  <!ENTITY xxe SYSTEM "%s">
                ]>
                <provisioning><CommandLine>&xxe;</CommandLine></provisioning>
                """, secretFile.toUri());

        ParseResult result = parseResult(buildWim(xml));
        assertFalse(result.body.contains(secret));
        assertEquals(0, result.metadata.getValues("ppkg:command").length);
    }

    @Test
    public void xmlEmbeddedWriteLimitPropagates() {
        assertThrows(WriteLimitReachedException.class,
                () -> parseWithEmbeddedException(buildWim(
                        "<wap-provisioningdoc/>"), new WriteLimitReachedException(7)));
    }

    @Test
    public void xmlEmbeddedSecurityExceptionPropagates() {
        assertThrows(SecurityException.class,
                () -> parseWithEmbeddedException(buildWim(
                        "<wap-provisioningdoc/>"),
                        new SecurityException("simulated XML security boundary")));
    }

    @Test
    public void dataAssetWriteLimitPropagates() {
        assertThrows(WriteLimitReachedException.class,
                () -> parseWithEmbeddedException(
                        buildWimResource("payload.bin",
                                "ordinary embedded data".getBytes(StandardCharsets.UTF_8)),
                        new WriteLimitReachedException(7)));
    }

    @Test
    public void dataAssetSecurityExceptionPropagates() {
        assertThrows(SecurityException.class,
                () -> parseWithEmbeddedException(
                        buildWimResource("payload.bin",
                                "ordinary embedded data".getBytes(StandardCharsets.UTF_8)),
                        new SecurityException("simulated data security boundary")));
    }

    @Test
    public void ordinaryXmlEmbeddedFailureRemainsWarning() throws Exception {
        Metadata metadata = parseWithEmbeddedException(
                buildWim("<wap-provisioningdoc/>"),
                new IOException("simulated ordinary XML failure"));

        assertTrue(Arrays.stream(metadata.getValues("ppkg:warning"))
                .anyMatch(value -> value.contains("simulated ordinary XML failure")));
    }

    @Test
    public void ordinaryDataAssetFailureRemainsWarning() throws Exception {
        Metadata metadata = parseWithEmbeddedException(
                buildWimResource("payload.bin",
                        "ordinary embedded data".getBytes(StandardCharsets.UTF_8)),
                new IOException("simulated ordinary data failure"));

        assertTrue(Arrays.stream(metadata.getValues("ppkg:warning"))
                .anyMatch(value -> value.contains("simulated ordinary data failure")));
    }

    private static Metadata parseWithEmbeddedException(byte[] wim, Exception failure)
            throws Exception {
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

        Metadata metadata = new Metadata();
        try (TikaInputStream stream = TikaInputStream.get(wim)) {
            new PpkgParser().parse(
                    stream, new BodyContentHandler(-1), metadata, context);
        }
        return metadata;
    }

    private static byte[] buildWim(String xml) {
        return buildWimResource(
                "payload.provxml", xml.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] buildWimResource(String name, byte[] resourceBytes) {
        int lookupOffset = 208;
        int lookupLength = 100;
        int metadataOffset = lookupOffset + lookupLength;
        int metadataLength = 256;
        int resourceOffset = metadataOffset + metadataLength;
        byte[] wim = new byte[resourceOffset + resourceBytes.length];
        ByteBuffer buffer = ByteBuffer.wrap(wim).order(ByteOrder.LITTLE_ENDIAN);

        System.arraycopy(PpkgParser.WIM_MAGIC, 0, wim, 0, PpkgParser.WIM_MAGIC.length);
        buffer.putInt(16, 0x00020000);
        buffer.putInt(20, 32768);
        buffer.putInt(44, 1);
        putResourceHeader(wim, buffer, 48, lookupLength, 0,
                lookupOffset, lookupLength);

        byte[] metadataHash = repeated((byte) 0x11);
        byte[] resourceHash = repeated((byte) 0x42);
        putLookupEntry(wim, buffer, lookupOffset, metadataLength, 0x04,
                metadataOffset, metadataLength, metadataHash);
        putLookupEntry(wim, buffer, lookupOffset + 50, resourceBytes.length, 0,
                resourceOffset, resourceBytes.length, resourceHash);

        buffer.putInt(metadataOffset, 8);
        buffer.putLong(metadataOffset + 8 + 16, 32);

        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_16LE);
        int dentry = metadataOffset + 32;
        buffer.putLong(dentry, 102L + nameBytes.length);
        System.arraycopy(resourceHash, 0, wim, dentry + 64, resourceHash.length);
        buffer.putShort(dentry + 100, (short) nameBytes.length);
        System.arraycopy(nameBytes, 0, wim, dentry + 102, nameBytes.length);
        System.arraycopy(resourceBytes, 0, wim, resourceOffset, resourceBytes.length);
        return wim;
    }

    private static byte[] buildOversizedMetadataResource(long size, int flags,
                                                         long uncompressed) {
        byte[] wim = header(300, 32768);
        ByteBuffer buffer = ByteBuffer.wrap(wim).order(ByteOrder.LITTLE_ENDIAN);
        putResourceHeader(wim, buffer, 48, 50, 0, 208, 50);
        putLookupEntry(wim, buffer, 208, size, flags, 258, uncompressed,
                repeated((byte) 0x55));
        return wim;
    }

    private static byte[] buildIncompleteXpressMetadataResource() {
        int metadataOffset = 258;
        int compressedSize = 256;
        byte[] wim = header(metadataOffset + compressedSize, 32768);
        ByteBuffer buffer = ByteBuffer.wrap(wim).order(ByteOrder.LITTLE_ENDIAN);
        putResourceHeader(wim, buffer, 48, 50, 0, 208, 50);
        putLookupEntry(wim, buffer, 208, compressedSize, 0x06,
                metadataOffset, 512, repeated((byte) 0x55));
        return wim;
    }

    private static byte[] buildAliasedWim(int entries, String xml) {
        String[] names = new String[entries];
        for (int i = 0; i < entries; i++) {
            names[i] = String.format(Locale.ROOT, "p%05d.provxml", i);
        }
        return buildAliasedWim(names, xml.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] buildAliasedWim(String[] names, byte[] resourceBytes) {
        int lookupOffset = 208;
        int lookupLength = 100;
        int metadataOffset = lookupOffset + lookupLength;
        int dentryBytes = 0;
        for (String name : names) {
            int entryLength = 102 + name.getBytes(StandardCharsets.UTF_16LE).length;
            dentryBytes += (entryLength + 7) & ~7;
        }
        int metadataLength = 32 + dentryBytes + 8;
        int resourceOffset = metadataOffset + metadataLength;
        byte[] wim = header(resourceOffset + resourceBytes.length, 32768);
        ByteBuffer buffer = ByteBuffer.wrap(wim).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(44, 1);
        putResourceHeader(wim, buffer, 48, lookupLength, 0,
                lookupOffset, lookupLength);

        byte[] metadataHash = repeated((byte) 0x11);
        byte[] resourceHash = repeated((byte) 0x42);
        putLookupEntry(wim, buffer, lookupOffset, metadataLength, 0x04,
                metadataOffset, metadataLength, metadataHash);
        putLookupEntry(wim, buffer, lookupOffset + 50, resourceBytes.length, 0,
                resourceOffset, resourceBytes.length, resourceHash);

        buffer.putInt(metadataOffset, 8);
        buffer.putLong(metadataOffset + 24, 32);
        int dentry = metadataOffset + 32;
        for (int i = 0; i < names.length; i++) {
            byte[] name = names[i].getBytes(StandardCharsets.UTF_16LE);
            buffer.putLong(dentry, 102L + name.length);
            System.arraycopy(resourceHash, 0, wim, dentry + 64, resourceHash.length);
            buffer.putShort(dentry + 100, (short) name.length);
            System.arraycopy(name, 0, wim, dentry + 102, name.length);
            dentry += (102 + name.length + 7) & ~7;
        }
        System.arraycopy(resourceBytes, 0, wim, resourceOffset, resourceBytes.length);
        return wim;
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static void emitDataAssetMetadataForTest(
            Metadata metadata, char fill, String name) throws Exception {
        Method method = PpkgParser.class.getDeclaredMethod(
                "emitDataAssetMetadata",
                Metadata.class, String.class, String.class, long.class,
                String.class, String.class, String.class);
        method.setAccessible(true);
        method.invoke(null, metadata, name, "application/octet-stream", 1234L,
                String.valueOf(fill).repeat(64),
                String.valueOf(fill).repeat(40),
                String.valueOf(fill).repeat(32));
    }

    private static void putResourceHeader(byte[] bytes, ByteBuffer buffer, int offset,
                                          long size, int flags, long dataOffset,
                                          long uncompressed) {
        put7(bytes, offset, size);
        bytes[offset + 7] = (byte) flags;
        buffer.putLong(offset + 8, dataOffset);
        buffer.putLong(offset + 16, uncompressed);
    }

    private static void putLookupEntry(byte[] bytes, ByteBuffer buffer, int offset,
                                       long size, int flags, long dataOffset,
                                       long uncompressed, byte[] sha1) {
        putResourceHeader(bytes, buffer, offset, size, flags, dataOffset, uncompressed);
        System.arraycopy(sha1, 0, bytes, offset + 30, sha1.length);
    }

    private static byte[] repeated(byte value) {
        byte[] bytes = new byte[20];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private record ParseResult(String body, Metadata metadata) {
    }
}
