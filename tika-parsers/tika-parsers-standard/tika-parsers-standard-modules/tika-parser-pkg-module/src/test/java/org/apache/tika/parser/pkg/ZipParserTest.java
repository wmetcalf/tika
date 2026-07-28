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
package org.apache.tika.parser.pkg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.compress.archivers.zip.UnsupportedZipFeatureException;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.metadata.Zip;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.XHTMLContentHandler;

/**
 * Test case for parsing zip files.
 */
public class ZipParserTest extends AbstractPkgTest {

    private static final String DENIED_OUTPUT = "denied output";

    /**
     * Tests that the ParseContext parser is correctly
     * fired for all the embedded entries.
     */
    @Test
    public void testEmbedded() throws Exception {
        List<Metadata> metadataList = getRecursiveMetadata("test-documents.zip");

        // First metadata is the container, rest are embedded documents
        // With recursive parsing, we get more than 10 entries due to nested documents
        // (e.g., ODT, PPT, DOC contain embedded resources)
        assertTrue(metadataList.size() >= 10, "Expected at least 10 metadata entries");

        // Collect all resource names for verification
        List<String> resourceNames = new java.util.ArrayList<>();
        for (Metadata m : metadataList) {
            String name = m.get(TikaCoreProperties.RESOURCE_NAME_KEY);
            if (name != null) {
                resourceNames.add(name);
            }
        }

        // Should contain all 9 direct embedded files from the ZIP
        assertContains("testEXCEL.xls", resourceNames);
        assertContains("testHTML.html", resourceNames);
        assertContains("testOpenOffice2.odt", resourceNames);
        assertContains("testPDF.pdf", resourceNames);
        assertContains("testPPT.ppt", resourceNames);
        assertContains("testRTF.rtf", resourceNames);
        assertContains("testTXT.txt", resourceNames);
        assertContains("testWORD.doc", resourceNames);
        assertContains("testXML.xml", resourceNames);
    }

    /**
     * Test case for the ability of the ZIP parser to extract the name of
     * a ZIP entry even if the content of the entry is unreadable due to an
     * unsupported compression method.
     *
     * @see <a href="https://issues.apache.org/jira/browse/TIKA-346">TIKA-346</a>
     */
    @Test
    public void testUnsupportedZipCompressionMethod() throws Exception {
        String content = new Tika().parseToString(getResourceAsStream("/test-documents/moby.zip"));
        assertContains("README", content);
    }


    @Test // TIKA-936
    public void testCustomEncoding() throws Exception {
        ZipParserConfig config = new ZipParserConfig();
        config.setEntryEncoding(Charset.forName("SJIS"));
        ParseContext context = new ParseContext();
        context.set(ZipParserConfig.class, config);

        List<Metadata> metadataList;
        try (TikaInputStream tis = TikaInputStream.get(Base64.decodeBase64(
                "UEsDBBQAAAAIAI+CvUCDo3+zIgAAACgAAAAOAAAAk/qWe4zqg4GDgi50" +
                        "eHRr2tj0qulsc2pzRHN609Gm7Y1OvFxNYLHJv6ZV97yCiQEAUEsBAh" +
                        "QLFAAAAAgAj4K9QIOjf7MiAAAAKAAAAA4AAAAAAAAAAAAgAAAAAAAA" +
                        "AJP6lnuM6oOBg4IudHh0UEsFBgAAAAABAAEAPAAAAE4AAAAAAA=="))) {
            metadataList = getRecursiveMetadata(tis, new Metadata(), context, false);
        }

        // Container + 1 embedded document
        assertEquals(2, metadataList.size());
        assertEquals("\u65E5\u672C\u8A9E\u30E1\u30E2.txt",
                metadataList.get(1).get(TikaCoreProperties.RESOURCE_NAME_KEY));
    }

    @Test
    public void testCharsetAutoDetectionDisabled() throws Exception {
        // Test that disabling charset detection leaves non-UTF8 names as-is (garbled)
        ZipParserConfig config = new ZipParserConfig();
        config.setDetectCharsetsInEntryNames(false);
        ParseContext context = new ParseContext();
        context.set(ZipParserConfig.class, config);

        List<Metadata> metadataList;
        try (TikaInputStream tis = TikaInputStream.get(Base64.decodeBase64(
                "UEsDBBQAAAAIAI+CvUCDo3+zIgAAACgAAAAOAAAAk/qWe4zqg4GDgi50" +
                        "eHRr2tj0qulsc2pzRHN609Gm7Y1OvFxNYLHJv6ZV97yCiQEAUEsBAh" +
                        "QLFAAAAAgAj4K9QIOjf7MiAAAAKAAAAA4AAAAAAAAAAAAgAAAAAAAA" +
                        "AJP6lnuM6oOBg4IudHh0UEsFBgAAAAABAAEAPAAAAE4AAAAAAA=="))) {
            metadataList = getRecursiveMetadata(tis, new Metadata(), context, false);
        }

        // Container + 1 embedded document
        assertEquals(2, metadataList.size());
        String name = metadataList.get(1).get(TikaCoreProperties.RESOURCE_NAME_KEY);
        // With detection disabled, the SJIS bytes are interpreted as default charset (garbled)
        // The correct Japanese name is 日本語メモ.txt - verify we DON'T get that
        assertTrue(!"\u65E5\u672C\u8A9E\u30E1\u30E2.txt".equals(name),
                "With detection disabled, SJIS name should NOT be correctly decoded");
    }

    @Test
    public void testQuineRecursiveParserWrapper() throws Exception {
        //Anti-virus can surreptitiously remove this file
        assumeTrue(
                ZipParserTest.class.getResourceAsStream("/test-documents/droste.zip") != null);
        //received permission from author via dm
        //2019-07-25 to include
        //http://alf.nu/s/droste.zip in unit tests
        //Out of respect to the author, please maintain
        //the original file name
        getRecursiveMetadata("droste.zip");
    }

    @Test
    public void testQuine() {
        //Anti-virus can surreptitiously remove this file
        assumeTrue(
                ZipParserTest.class.getResourceAsStream("/test-documents/droste.zip") != null);
        assertThrows(TikaException.class, () -> {
            getXML("droste.zip");
        });
    }

    @Test
    public void testZipFilePreservesOutputDenialWithoutCleanupCallbacks(@TempDir Path tempDir)
            throws Exception {
        Path zipPath = tempDir.resolve("output-denial.zip");
        Files.write(zipPath, createSingleEntryZip(true));

        try (TikaInputStream tis = TikaInputStream.get(zipPath)) {
            tis.setOpenContainer(ZipFile.builder().setFile(zipPath.toFile()).get());
            assertOutputDenialStopsCallbacks(tis);
        }
    }

    @Test
    public void testStreamingPreservesOutputDenialWithoutCleanupCallbacks() throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(createSingleEntryZip(false))) {
            assertOutputDenialStopsCallbacks(tis);
            assertNull(tis.getOpenContainer(), "fixture must use the streaming ZIP path");
        }
    }

    @Test
    public void testZipFileSwallowedSaxDenialFailsStopByIdentity(@TempDir Path tempDir)
            throws Exception {
        Path zipPath = tempDir.resolve("swallowed-sax-denial.zip");
        Files.write(zipPath, createSingleEntryZip(true));

        try (TikaInputStream tis = TikaInputStream.get(zipPath)) {
            tis.setOpenContainer(ZipFile.builder().setFile(zipPath.toFile()).get());
            assertWrappedOutputDenialFailsStop(
                    tis, new SAXException("swallowed ZIP SAX denial"),
                    OutputFailureMode.SWALLOW);
        }
    }

    @Test
    public void testStreamingSwallowedSaxDenialFailsStopByIdentity() throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(createSingleEntryZip(false))) {
            assertWrappedOutputDenialFailsStop(
                    tis, new SAXException("swallowed ZIP SAX denial"),
                    OutputFailureMode.SWALLOW);
            assertNull(tis.getOpenContainer(), "fixture must use the streaming ZIP path");
        }
    }

    @Test
    public void testZipFileErrorCauseWrappedSaxDenialFailsStopByIdentity(
            @TempDir Path tempDir) throws Exception {
        Path zipPath = tempDir.resolve("error-cause-sax-denial.zip");
        Files.write(zipPath, createSingleEntryZip(true));

        try (TikaInputStream tis = TikaInputStream.get(zipPath)) {
            tis.setOpenContainer(ZipFile.builder().setFile(zipPath.toFile()).get());
            assertWrappedOutputDenialFailsStop(
                    tis, new SAXException("Error cause-wrapped ZIP SAX denial"),
                    OutputFailureMode.ERROR_CAUSE);
        }
    }

    @Test
    public void testStreamingErrorSuppressedSaxDenialFailsStopByIdentity() throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(createSingleEntryZip(false))) {
            assertWrappedOutputDenialFailsStop(
                    tis, new SAXException("Error suppressed ZIP SAX denial"),
                    OutputFailureMode.ERROR_SUPPRESSED);
            assertNull(tis.getOpenContainer(), "fixture must use the streaming ZIP path");
        }
    }

    @Test
    public void testZipFileErrorCauseUnwrappedSaxDenialFailsStopByIdentity(
            @TempDir Path tempDir) throws Exception {
        Path zipPath = tempDir.resolve("error-cause-unwrapped-sax-denial.zip");
        Files.write(zipPath, createSingleEntryZip(true));

        try (TikaInputStream tis = TikaInputStream.get(zipPath)) {
            tis.setOpenContainer(ZipFile.builder().setFile(zipPath.toFile()).get());
            assertWrappedOutputDenialFailsStop(
                    tis, new SAXException("Error cause-unwrapped ZIP SAX denial"),
                    OutputFailureMode.ERROR_UNWRAPPED_SAX_CAUSE);
        }
    }

    @Test
    public void testZipFileErrorSuppressedUnwrappedSaxDenialFailsStopByIdentity(
            @TempDir Path tempDir) throws Exception {
        Path zipPath = tempDir.resolve("error-suppressed-unwrapped-sax-denial.zip");
        Files.write(zipPath, createSingleEntryZip(true));

        try (TikaInputStream tis = TikaInputStream.get(zipPath)) {
            tis.setOpenContainer(ZipFile.builder().setFile(zipPath.toFile()).get());
            assertWrappedOutputDenialFailsStop(
                    tis, new SAXException("Error suppressed-unwrapped ZIP SAX denial"),
                    OutputFailureMode.ERROR_UNWRAPPED_SAX_SUPPRESSED);
        }
    }

    @Test
    public void testStreamingErrorCauseUnwrappedSaxDenialFailsStopByIdentity()
            throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(createSingleEntryZip(false))) {
            assertWrappedOutputDenialFailsStop(
                    tis, new SAXException("Error cause-unwrapped ZIP SAX denial"),
                    OutputFailureMode.ERROR_UNWRAPPED_SAX_CAUSE);
            assertNull(tis.getOpenContainer(), "fixture must use the streaming ZIP path");
        }
    }

    @Test
    public void testStreamingErrorSuppressedUnwrappedSaxDenialFailsStopByIdentity()
            throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(createSingleEntryZip(false))) {
            assertWrappedOutputDenialFailsStop(
                    tis, new SAXException("Error suppressed-unwrapped ZIP SAX denial"),
                    OutputFailureMode.ERROR_UNWRAPPED_SAX_SUPPRESSED);
            assertNull(tis.getOpenContainer(), "fixture must use the streaming ZIP path");
        }
    }

    @Test
    public void testZipFileExactRecordedSaxWinsOverEarlierTaggedBranchInCyclicErrorGraph(
            @TempDir Path tempDir) throws Exception {
        Path zipPath = tempDir.resolve("competing-tagged-sax-branches.zip");
        Files.write(zipPath, createSingleEntryZip(true));

        try (TikaInputStream tis = TikaInputStream.get(zipPath)) {
            tis.setOpenContainer(ZipFile.builder().setFile(zipPath.toFile()).get());
            assertExactRecordedSaxWinsOverEarlierTaggedBranchInCyclicErrorGraph(
                    tis);
        }
    }

    @Test
    public void testStreamingExactRecordedSaxWinsOverEarlierTaggedBranchInCyclicErrorGraph()
            throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(createSingleEntryZip(false))) {
            assertExactRecordedSaxWinsOverEarlierTaggedBranchInCyclicErrorGraph(
                    tis);
            assertNull(tis.getOpenContainer(), "fixture must use the streaming ZIP path");
        }
    }

    @Test
    public void testZipFileUnrelatedErrorRemainsAuthoritativeAfterSwallowedSaxDenial(
            @TempDir Path tempDir) throws Exception {
        Path zipPath = tempDir.resolve("unrelated-error-after-swallowed-sax-denial.zip");
        Files.write(zipPath, createSingleEntryZip(true));

        try (TikaInputStream tis = TikaInputStream.get(zipPath)) {
            tis.setOpenContainer(ZipFile.builder().setFile(zipPath.toFile()).get());
            assertUnrelatedErrorRemainsAuthoritativeAfterSwallowedSaxDenial(tis);
        }
    }

    @Test
    public void testStreamingUnrelatedErrorRemainsAuthoritativeAfterSwallowedSaxDenial()
            throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(createSingleEntryZip(false))) {
            assertUnrelatedErrorRemainsAuthoritativeAfterSwallowedSaxDenial(tis);
            assertNull(tis.getOpenContainer(), "fixture must use the streaming ZIP path");
        }
    }

    @Test
    public void testZipFileErrorCauseWrappedRuntimeDenialFailsStopByIdentity(
            @TempDir Path tempDir) throws Exception {
        Path zipPath = tempDir.resolve("error-cause-runtime-denial.zip");
        Files.write(zipPath, createSingleEntryZip(true));

        try (TikaInputStream tis = TikaInputStream.get(zipPath)) {
            tis.setOpenContainer(ZipFile.builder().setFile(zipPath.toFile()).get());
            assertWrappedOutputDenialFailsStop(
                    tis, new IllegalStateException(
                            "Error cause-wrapped ZIP Runtime denial"),
                    OutputFailureMode.ERROR_CAUSE);
        }
    }

    @Test
    public void testStreamingErrorSuppressedErrorDenialFailsStopByIdentity() throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(createSingleEntryZip(false))) {
            assertWrappedOutputDenialFailsStop(
                    tis, new AssertionError(
                            "Error suppressed ZIP Error denial"),
                    OutputFailureMode.ERROR_SUPPRESSED);
            assertNull(tis.getOpenContainer(), "fixture must use the streaming ZIP path");
        }
    }

    @Test
    public void testZipFileParserFailureStillFinalizesDocument(@TempDir Path tempDir)
            throws Exception {
        Path zipPath = tempDir.resolve("parser-failure.zip");
        Files.write(zipPath, createSingleEntryZip(true));

        try (TikaInputStream tis = TikaInputStream.get(zipPath)) {
            tis.setOpenContainer(ZipFile.builder().setFile(zipPath.toFile()).get());
            assertParserFailureStillFinalizesDocument(
                    tis, new IOException("simulated embedded parser failure"));
        }
    }

    @Test
    public void testStreamingParserFailureStillFinalizesDocument() throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(createSingleEntryZip(false))) {
            assertParserFailureStillFinalizesDocument(
                    tis, new IllegalStateException(
                            "simulated embedded parser runtime failure"));
            assertNull(tis.getOpenContainer(), "fixture must use the streaming ZIP path");
        }
    }

    @Test
    public void testZipFileRecoverableUnsupportedFeatureBalancesEmbeddedOutput(
            @TempDir Path tempDir) throws Exception {
        Path zipPath = tempDir.resolve("recoverable-unsupported-feature.zip");
        Files.write(zipPath, createSingleEntryZip(true));

        try (TikaInputStream tis = TikaInputStream.get(zipPath)) {
            tis.setOpenContainer(ZipFile.builder().setFile(zipPath.toFile()).get());
            assertRecoverableUnsupportedFeatureBalancesEmbeddedOutput(tis);
        }
    }

    @Test
    public void testStreamingRecoverableUnsupportedFeatureBalancesEmbeddedOutput()
            throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(createSingleEntryZip(false))) {
            assertRecoverableUnsupportedFeatureBalancesEmbeddedOutput(tis);
            assertNull(tis.getOpenContainer(), "fixture must use the streaming ZIP path");
        }
    }

    @Test
    public void testZipFileRecoverableUnsupportedFeatureDrainErrorFailsStop(
            @TempDir Path tempDir) throws Exception {
        Path zipPath = tempDir.resolve("recoverable-drain-denial.zip");
        Files.write(zipPath, createSingleEntryZip(true));

        try (TikaInputStream tis = TikaInputStream.get(zipPath)) {
            tis.setOpenContainer(ZipFile.builder().setFile(zipPath.toFile()).get());
            assertRecoverableUnsupportedFeatureDrainErrorFailsStop(tis);
        }
    }

    @Test
    public void testStreamingRecoverableUnsupportedFeatureDrainErrorFailsStop()
            throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(createSingleEntryZip(false))) {
            assertRecoverableUnsupportedFeatureDrainErrorFailsStop(tis);
            assertNull(tis.getOpenContainer(), "fixture must use the streaming ZIP path");
        }
    }

    @Test
    public void testZipFileFatalCleanupErrorSupersedesParserFailure(@TempDir Path tempDir)
            throws Exception {
        Path zipPath = tempDir.resolve("fatal-cleanup.zip");
        Files.write(zipPath, createSingleEntryZip(true));

        try (TikaInputStream tis = TikaInputStream.get(zipPath)) {
            tis.setOpenContainer(ZipFile.builder().setFile(zipPath.toFile()).get());
            assertFatalCleanupErrorSupersedesParserFailure(tis);
        }
    }

    @Test
    public void testStreamingFatalCleanupErrorSupersedesParserFailure() throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(createSingleEntryZip(false))) {
            assertFatalCleanupErrorSupersedesParserFailure(tis);
            assertNull(tis.getOpenContainer(), "fixture must use the streaming ZIP path");
        }
    }

    @Test
    public void testZipFileNoPrimaryTemporaryCleanupPropagatesExactIOException(
            @TempDir Path tempDir)
            throws Exception {
        Path zipPath = tempDir.resolve("cleanup-compatibility.zip");
        Files.write(zipPath, createSingleEntryZip(true));

        try (TikaInputStream tis = TikaInputStream.get(zipPath)) {
            tis.setOpenContainer(ZipFile.builder().setFile(zipPath.toFile()).get());
            assertNoPrimaryTemporaryCleanupPropagatesExactIOException(tis);
        }
    }

    @Test
    public void testStreamingNoPrimaryTemporaryCleanupPropagatesExactIOException()
            throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(createSingleEntryZip(false))) {
            assertNoPrimaryTemporaryCleanupPropagatesExactIOException(tis);
            assertNull(tis.getOpenContainer(), "fixture must use the streaming ZIP path");
        }
    }

    @Test
    public void testZipFileDirectSecurityExceptionFailsStop(@TempDir Path tempDir)
            throws Exception {
        Path zipPath = tempDir.resolve("direct-security-exception.zip");
        Files.write(zipPath, createSingleEntryZip(true));

        try (TikaInputStream tis = TikaInputStream.get(zipPath)) {
            tis.setOpenContainer(ZipFile.builder().setFile(zipPath.toFile()).get());
            assertDirectSecurityExceptionFailsStop(tis);
        }
    }

    @Test
    public void testStreamingDirectSecurityExceptionFailsStop() throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(createSingleEntryZip(false))) {
            assertDirectSecurityExceptionFailsStop(tis);
            assertNull(tis.getOpenContainer(), "fixture must use the streaming ZIP path");
        }
    }

    @Test
    public void testZipFileCleanupSecurityExceptionSupersedesRecoverableUnsupportedFeature(
            @TempDir Path tempDir) throws Exception {
        Path zipPath = tempDir.resolve("cleanup-security-unsupported-feature.zip");
        Files.write(zipPath, createSingleEntryZip(true));

        try (TikaInputStream tis = TikaInputStream.get(zipPath)) {
            tis.setOpenContainer(ZipFile.builder().setFile(zipPath.toFile()).get());
            assertCleanupSecurityExceptionSupersedesRecoverableUnsupportedFeature(tis);
        }
    }

    @Test
    public void testStreamingCleanupSecurityExceptionSupersedesRecoverableUnsupportedFeature()
            throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(createSingleEntryZip(false))) {
            assertCleanupSecurityExceptionSupersedesRecoverableUnsupportedFeature(tis);
            assertNull(tis.getOpenContainer(), "fixture must use the streaming ZIP path");
        }
    }

    @Test
    public void testZipFileFatalCleanupErrorSupersedesOutputDenial(@TempDir Path tempDir)
            throws Exception {
        Path zipPath = tempDir.resolve("output-denial-fatal-cleanup-error.zip");
        Files.write(zipPath, createSingleEntryZip(true));

        try (TikaInputStream tis = TikaInputStream.get(zipPath)) {
            tis.setOpenContainer(ZipFile.builder().setFile(zipPath.toFile()).get());
            assertFatalCleanupSupersedesOutputDenial(
                    tis, new AssertionError("simulated fatal cleanup error"));
        }
    }

    @Test
    public void testStreamingFatalCleanupErrorSupersedesOutputDenial() throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(createSingleEntryZip(false))) {
            assertFatalCleanupSupersedesOutputDenial(
                    tis, new AssertionError("simulated fatal cleanup error"));
            assertNull(tis.getOpenContainer(), "fixture must use the streaming ZIP path");
        }
    }

    @Test
    public void testZipFileFatalCleanupSecurityExceptionSupersedesOutputDenial(
            @TempDir Path tempDir) throws Exception {
        Path zipPath = tempDir.resolve("output-denial-fatal-cleanup-security.zip");
        Files.write(zipPath, createSingleEntryZip(true));

        try (TikaInputStream tis = TikaInputStream.get(zipPath)) {
            tis.setOpenContainer(ZipFile.builder().setFile(zipPath.toFile()).get());
            assertFatalCleanupSupersedesOutputDenial(
                    tis, new SecurityException("simulated fatal cleanup security denial"));
        }
    }

    @Test
    public void testStreamingFatalCleanupSecurityExceptionSupersedesOutputDenial()
            throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(createSingleEntryZip(false))) {
            assertFatalCleanupSupersedesOutputDenial(
                    tis, new SecurityException("simulated fatal cleanup security denial"));
            assertNull(tis.getOpenContainer(), "fixture must use the streaming ZIP path");
        }
    }

    @Test
    public void testZipFileFatalCleanupErrorSupersedesErrorCauseUnwrappedSaxDenial(
            @TempDir Path tempDir) throws Exception {
        Path zipPath = tempDir.resolve(
                "error-cause-unwrapped-sax-fatal-cleanup-error.zip");
        Files.write(zipPath, createSingleEntryZip(true));

        try (TikaInputStream tis = TikaInputStream.get(zipPath)) {
            tis.setOpenContainer(ZipFile.builder().setFile(zipPath.toFile()).get());
            assertWrappedOutputFatalCleanupPrecedence(
                    tis, OutputFailureMode.ERROR_UNWRAPPED_SAX_CAUSE,
                    new AssertionError("simulated fatal cleanup error"));
        }
    }

    @Test
    public void testStreamingFatalCleanupErrorSupersedesErrorSuppressedUnwrappedSaxDenial()
            throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(createSingleEntryZip(false))) {
            assertWrappedOutputFatalCleanupPrecedence(
                    tis, OutputFailureMode.ERROR_UNWRAPPED_SAX_SUPPRESSED,
                    new AssertionError("simulated fatal cleanup error"));
            assertNull(tis.getOpenContainer(), "fixture must use the streaming ZIP path");
        }
    }

    @Test
    public void testZipFileFatalCleanupSecuritySupersedesErrorSuppressedUnwrappedSaxDenial(
            @TempDir Path tempDir) throws Exception {
        Path zipPath = tempDir.resolve(
                "error-suppressed-unwrapped-sax-fatal-cleanup-security.zip");
        Files.write(zipPath, createSingleEntryZip(true));

        try (TikaInputStream tis = TikaInputStream.get(zipPath)) {
            tis.setOpenContainer(ZipFile.builder().setFile(zipPath.toFile()).get());
            assertWrappedOutputFatalCleanupPrecedence(
                    tis, OutputFailureMode.ERROR_UNWRAPPED_SAX_SUPPRESSED,
                    new SecurityException(
                            "simulated fatal cleanup security denial"));
        }
    }

    @Test
    public void testStreamingFatalCleanupSecuritySupersedesErrorCauseUnwrappedSaxDenial()
            throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(createSingleEntryZip(false))) {
            assertWrappedOutputFatalCleanupPrecedence(
                    tis, OutputFailureMode.ERROR_UNWRAPPED_SAX_CAUSE,
                    new SecurityException(
                            "simulated fatal cleanup security denial"));
            assertNull(tis.getOpenContainer(), "fixture must use the streaming ZIP path");
        }
    }

    @Test
    public void testZipFileCleanupErrorSupersedesPrimarySecurity(
            @TempDir Path tempDir) throws Exception {
        Path zipPath = tempDir.resolve(
                "primary-security-fatal-cleanup-error.zip");
        Files.write(zipPath, createSingleEntryZip(true));

        try (TikaInputStream tis = TikaInputStream.get(zipPath)) {
            tis.setOpenContainer(ZipFile.builder().setFile(zipPath.toFile()).get());
            assertCleanupErrorSupersedesPrimarySecurity(tis);
        }
    }

    @Test
    public void testStreamingCleanupErrorSupersedesPrimarySecurity()
            throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(createSingleEntryZip(false))) {
            assertCleanupErrorSupersedesPrimarySecurity(tis);
            assertNull(tis.getOpenContainer(), "fixture must use the streaming ZIP path");
        }
    }

    @Test
    public void testZipFileSwallowedSaxDenialRetainsUnrelatedUnsupportedFeature(
            @TempDir Path tempDir) throws Exception {
        Path zipPath = tempDir.resolve("swallowed-sax-unsupported-feature.zip");
        Files.write(zipPath, createSingleEntryZip(true));

        try (TikaInputStream tis = TikaInputStream.get(zipPath)) {
            tis.setOpenContainer(ZipFile.builder().setFile(zipPath.toFile()).get());
            assertSwallowedOutputDenialRetainsUnrelatedFailure(
                    tis, new SAXException("swallowed ZIP SAX denial"),
                    new UnsupportedZipFeatureException(
                            UnsupportedZipFeatureException.Feature.METHOD));
        }
    }

    @Test
    public void testStreamingSwallowedSaxDenialRetainsUnrelatedUnsupportedFeature()
            throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(createSingleEntryZip(false))) {
            assertSwallowedOutputDenialRetainsUnrelatedFailure(
                    tis, new SAXException("swallowed ZIP SAX denial"),
                    new UnsupportedZipFeatureException(
                            UnsupportedZipFeatureException.Feature.METHOD));
            assertNull(tis.getOpenContainer(), "fixture must use the streaming ZIP path");
        }
    }

    @Test
    public void testZipFileSwallowedUncheckedDenialRetainsUnrelatedParserFailure(
            @TempDir Path tempDir) throws Exception {
        Path zipPath = tempDir.resolve("swallowed-unchecked-parser-failure.zip");
        Files.write(zipPath, createSingleEntryZip(true));

        try (TikaInputStream tis = TikaInputStream.get(zipPath)) {
            tis.setOpenContainer(ZipFile.builder().setFile(zipPath.toFile()).get());
            assertSwallowedOutputDenialRetainsUnrelatedFailure(
                    tis, new IllegalStateException("swallowed ZIP unchecked denial"),
                    new IOException("unrelated embedded parser failure"));
        }
    }

    @Test
    public void testStreamingSwallowedUncheckedDenialRetainsUnrelatedParserFailure()
            throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(createSingleEntryZip(false))) {
            assertSwallowedOutputDenialRetainsUnrelatedFailure(
                    tis, new IllegalStateException("swallowed ZIP unchecked denial"),
                    new IOException("unrelated embedded parser failure"));
            assertNull(tis.getOpenContainer(), "fixture must use the streaming ZIP path");
        }
    }

    @Test
    public void testZipFileFatalCleanupErrorRetainsSwallowedSaxAndUnsupportedFeature(
            @TempDir Path tempDir) throws Exception {
        Path zipPath = tempDir.resolve(
                "fatal-cleanup-error-swallowed-sax-unsupported-feature.zip");
        Files.write(zipPath, createSingleEntryZip(true));

        try (TikaInputStream tis = TikaInputStream.get(zipPath)) {
            tis.setOpenContainer(ZipFile.builder().setFile(zipPath.toFile()).get());
            assertFatalCleanupRetainsSwallowedOutputAndParserFailure(
                    tis, new SAXException("swallowed ZIP SAX denial"),
                    new UnsupportedZipFeatureException(
                            UnsupportedZipFeatureException.Feature.METHOD),
                    new AssertionError("simulated fatal cleanup error"));
        }
    }

    @Test
    public void testStreamingFatalCleanupErrorRetainsSwallowedSaxAndUnsupportedFeature()
            throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(createSingleEntryZip(false))) {
            assertFatalCleanupRetainsSwallowedOutputAndParserFailure(
                    tis, new SAXException("swallowed ZIP SAX denial"),
                    new UnsupportedZipFeatureException(
                            UnsupportedZipFeatureException.Feature.METHOD),
                    new AssertionError("simulated fatal cleanup error"));
            assertNull(tis.getOpenContainer(), "fixture must use the streaming ZIP path");
        }
    }

    @Test
    public void testZipFileFatalCleanupSecurityRetainsSwallowedUncheckedAndParserFailure(
            @TempDir Path tempDir) throws Exception {
        Path zipPath = tempDir.resolve(
                "fatal-cleanup-security-swallowed-unchecked-parser-failure.zip");
        Files.write(zipPath, createSingleEntryZip(true));

        try (TikaInputStream tis = TikaInputStream.get(zipPath)) {
            tis.setOpenContainer(ZipFile.builder().setFile(zipPath.toFile()).get());
            assertFatalCleanupRetainsSwallowedOutputAndParserFailure(
                    tis, new IllegalStateException("swallowed ZIP unchecked denial"),
                    new IOException("unrelated embedded parser failure"),
                    new SecurityException("simulated fatal cleanup security denial"));
        }
    }

    @Test
    public void testStreamingFatalCleanupSecurityRetainsSwallowedUncheckedAndParserFailure()
            throws Exception {
        try (TikaInputStream tis = TikaInputStream.get(createSingleEntryZip(false))) {
            assertFatalCleanupRetainsSwallowedOutputAndParserFailure(
                    tis, new IllegalStateException("swallowed ZIP unchecked denial"),
                    new IOException("unrelated embedded parser failure"),
                    new SecurityException("simulated fatal cleanup security denial"));
            assertNull(tis.getOpenContainer(), "fixture must use the streaming ZIP path");
        }
    }

    private void assertOutputDenialStopsCallbacks(TikaInputStream tis) {
        SAXException denial = new SAXException("simulated ZIP output denial");
        IOException cleanupFailure =
                new IOException("simulated temporary resource cleanup failure");
        FailingCloseable cleanupResource = new FailingCloseable(cleanupFailure);
        FailStopHandler handler = new FailStopHandler(denial);
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class,
                new DenyingEmbeddedDocumentExtractor(cleanupResource));

        Exception thrown = assertThrows(Exception.class,
                () -> new ZipParser().parse(tis, handler, new Metadata(), context));

        assertTrue(cleanupResource.closed);
        assertSame(denial, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(cleanupFailure, thrown.getSuppressed()[0]);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    private void assertWrappedOutputDenialFailsStop(
            TikaInputStream tis, Throwable denial, OutputFailureMode mode) {
        FailStopThrowableHandler handler =
                new FailStopThrowableHandler(denial);
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class,
                new OutputFailureExtractor(mode));

        Throwable thrown = assertThrows(Throwable.class,
                () -> new ZipParser().parse(tis, handler, new Metadata(), context));

        assertSame(denial, thrown);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    private void assertExactRecordedSaxWinsOverEarlierTaggedBranchInCyclicErrorGraph(
            TikaInputStream tis) {
        SAXException firstDenial =
                new SAXException("simulated first ZIP output denial");
        FailStopHandler handler = new FailStopHandler(firstDenial);
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class,
                new CompetingTaggedSaxBranchesExtractor());

        SAXException thrown = assertThrows(SAXException.class,
                () -> new ZipParser().parse(
                        tis, handler, new Metadata(), context));

        assertSame(firstDenial, thrown);
        assertEquals(1, handler.callbacksAfterDenial);
    }

    private void assertUnrelatedErrorRemainsAuthoritativeAfterSwallowedSaxDenial(
            TikaInputStream tis) {
        SAXException outputDenial =
                new SAXException("swallowed unrelated ZIP SAX denial");
        AssertionError parserFailure =
                new AssertionError("unrelated ZIP parser Error");
        FailStopThrowableHandler handler =
                new FailStopThrowableHandler(outputDenial);
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class,
                new SwallowedOutputThenFailureExtractor(parserFailure));

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> new ZipParser().parse(
                        tis, handler, new Metadata(), context));

        assertSame(parserFailure, thrown);
        assertFalse(containsThrowableByIdentity(thrown, outputDenial));
        assertFalse(containsThrowableByIdentity(outputDenial, thrown));
        assertEquals(0, handler.callbacksAfterDenial);
    }

    private void assertParserFailureStillFinalizesDocument(
            TikaInputStream tis, Throwable parserFailure) {
        DocumentLifecycleHandler handler = new DocumentLifecycleHandler();
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class,
                new ControlledFailureExtractor(parserFailure, null, true));

        Throwable thrown = assertThrows(Throwable.class,
                () -> new ZipParser().parse(tis, handler, new Metadata(), context));

        assertSame(parserFailure, thrown);
        assertEquals(1, handler.endDocumentCalls);
        assertTrue(handler.openElements.isEmpty(),
                "recoverable parser failure left XHTML open: "
                        + handler.openElements);
    }

    private void assertRecoverableUnsupportedFeatureBalancesEmbeddedOutput(
            TikaInputStream tis) throws Exception {
        DocumentLifecycleHandler handler = new DocumentLifecycleHandler();
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class,
                new PartialOutputUnsupportedFeatureExtractor());

        new ZipParser().parse(tis, handler, metadata, context);

        assertEquals(1, handler.endDocumentCalls);
        assertTrue(handler.openElements.isEmpty(),
                "recoverable unsupported feature left XHTML open: "
                        + handler.openElements);
        String[] embeddedStreamFailures =
                metadata.getValues(
                        TikaCoreProperties.TIKA_META_EXCEPTION_EMBEDDED_STREAM);
        assertEquals(1, embeddedStreamFailures.length);
        assertTrue(embeddedStreamFailures[0].contains(
                UnsupportedZipFeatureException.class.getName()));
    }

    private void assertRecoverableUnsupportedFeatureDrainErrorFailsStop(
            TikaInputStream tis) {
        AssertionError denial =
                new AssertionError("simulated ZIP recovery drain denial");
        DrainFailStopHandler handler =
                new DrainFailStopHandler(denial);
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class,
                new PartialOutputUnsupportedFeatureExtractor());

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> new ZipParser().parse(
                        tis, handler, new Metadata(), context));

        assertSame(denial, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertTrue(thrown.getSuppressed()[0]
                instanceof UnsupportedZipFeatureException);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    private void assertFatalCleanupErrorSupersedesParserFailure(TikaInputStream tis) {
        IOException parserFailure = new IOException("simulated embedded parser failure");
        AssertionError cleanupFailure = new AssertionError("simulated fatal cleanup failure");
        DocumentLifecycleHandler handler = new DocumentLifecycleHandler();
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class,
                new ControlledFailureExtractor(parserFailure,
                        () -> {
                            throw cleanupFailure;
                        }));

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> new ZipParser().parse(tis, handler, new Metadata(), context));

        assertSame(cleanupFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(parserFailure, thrown.getSuppressed()[0]);
        assertEquals(0, handler.endDocumentCalls);
    }

    private void assertNoPrimaryTemporaryCleanupPropagatesExactIOException(
            TikaInputStream tis) {
        IOException cleanupFailure = new IOException("simulated temporary cleanup failure");
        DocumentLifecycleHandler handler = new DocumentLifecycleHandler();
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class,
                new ControlledFailureExtractor(null, new FailingCloseable(cleanupFailure)));

        IOException thrown = assertThrows(IOException.class,
                () -> new ZipParser().parse(tis, handler, new Metadata(), context));

        assertSame(cleanupFailure, thrown);
        assertEquals(1, handler.endDocumentCalls);
    }

    private void assertDirectSecurityExceptionFailsStop(TikaInputStream tis) {
        SecurityException failure =
                new SecurityException("simulated direct ZIP security failure");
        FailStopThrowableHandler handler =
                new FailStopThrowableHandler(failure);
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class,
                new DirectSecurityFailureExtractor(failure, handler));

        SecurityException thrown = assertThrows(SecurityException.class,
                () -> new ZipParser().parse(
                        tis, handler, new Metadata(), context));

        assertSame(failure, thrown);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    private void assertCleanupSecurityExceptionSupersedesRecoverableUnsupportedFeature(
            TikaInputStream tis) {
        UnsupportedZipFeatureException parserFailure =
                new UnsupportedZipFeatureException(
                        UnsupportedZipFeatureException.Feature.METHOD);
        SecurityException cleanupFailure =
                new SecurityException("simulated temporary cleanup security denial");
        ThrowableCloseable cleanupResource =
                new ThrowableCloseable(cleanupFailure);
        DocumentLifecycleHandler handler = new DocumentLifecycleHandler();
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class,
                new ControlledFailureExtractor(
                        parserFailure, cleanupResource, true));

        SecurityException thrown = assertThrows(SecurityException.class,
                () -> new ZipParser().parse(
                        tis, handler, new Metadata(), context));

        assertTrue(cleanupResource.closed);
        assertSame(cleanupFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(parserFailure, thrown.getSuppressed()[0]);
        assertEquals(0, handler.endDocumentCalls);
    }

    private void assertFatalCleanupSupersedesOutputDenial(
            TikaInputStream tis, Throwable cleanupFailure) {
        SAXException outputDenial =
                new SAXException("simulated ZIP output denial before fatal cleanup");
        ThrowableCloseable cleanupResource =
                new ThrowableCloseable(cleanupFailure);
        FailStopHandler handler = new FailStopHandler(outputDenial);
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class,
                new DenyingEmbeddedDocumentExtractor(cleanupResource));

        Throwable thrown = assertThrows(Throwable.class,
                () -> new ZipParser().parse(
                        tis, handler, new Metadata(), context));

        assertTrue(cleanupResource.closed);
        assertSame(cleanupFailure, thrown);
        assertTrue(containsThrowableByIdentity(thrown, outputDenial));
        assertEquals(0, handler.callbacksAfterDenial);
    }

    private void assertWrappedOutputFatalCleanupPrecedence(
            TikaInputStream tis, OutputFailureMode mode,
            Throwable cleanupFailure) {
        SAXException outputDenial =
                new SAXException(
                        "simulated raw unwrapped ZIP output denial before fatal cleanup");
        ThrowableCloseable cleanupResource =
                new ThrowableCloseable(cleanupFailure);
        FailStopHandler handler = new FailStopHandler(outputDenial);
        OutputFailureExtractor extractor =
                new OutputFailureExtractor(mode, cleanupResource);
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, extractor);

        Throwable thrown = assertThrows(Throwable.class,
                () -> new ZipParser().parse(
                        tis, handler, new Metadata(), context));

        assertTrue(cleanupResource.closed);
        assertSame(cleanupFailure, thrown);
        assertTrue(containsThrowableByIdentity(thrown, outputDenial));
        assertTrue(containsThrowableByIdentity(
                thrown, extractor.wrapperFailure));
        assertTrue(containsThrowableByIdentity(
                extractor.wrapperFailure, outputDenial));
        assertFalse(containsThrowableByIdentity(outputDenial, thrown));
        assertFalse(containsThrowableByIdentity(
                extractor.wrapperFailure, thrown));
        assertEquals(0, handler.callbacksAfterDenial);
    }

    private void assertCleanupErrorSupersedesPrimarySecurity(
            TikaInputStream tis) {
        SecurityException parserFailure =
                new SecurityException(
                        "simulated primary ZIP security denial");
        AssertionError cleanupFailure =
                new AssertionError(
                        "simulated fatal ZIP cleanup error");
        ThrowableCloseable cleanupResource =
                new ThrowableCloseable(cleanupFailure);
        DocumentLifecycleHandler handler =
                new DocumentLifecycleHandler();
        ParseContext context = new ParseContext();
        context.set(
                EmbeddedDocumentExtractor.class,
                new ControlledFailureExtractor(
                        parserFailure, cleanupResource));

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> new ZipParser().parse(
                        tis, handler, new Metadata(), context));

        assertTrue(cleanupResource.closed);
        assertSame(cleanupFailure, thrown);
        assertTrue(containsThrowableByIdentity(
                thrown, parserFailure));
        assertFalse(containsThrowableByIdentity(
                parserFailure, thrown));
        assertEquals(0, handler.endDocumentCalls);
    }

    private void assertSwallowedOutputDenialRetainsUnrelatedFailure(
            TikaInputStream tis, Throwable outputDenial,
            Throwable parserFailure) {
        FailStopThrowableHandler handler =
                new FailStopThrowableHandler(outputDenial);
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class,
                new SwallowedOutputThenFailureExtractor(parserFailure));

        Throwable thrown = assertThrows(Throwable.class,
                () -> new ZipParser().parse(
                        tis, handler, new Metadata(), context));

        assertSame(outputDenial, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(parserFailure, thrown.getSuppressed()[0]);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    private void assertFatalCleanupRetainsSwallowedOutputAndParserFailure(
            TikaInputStream tis, Throwable outputDenial,
            Throwable parserFailure, Throwable cleanupFailure) {
        ThrowableCloseable cleanupResource =
                new ThrowableCloseable(cleanupFailure);
        FailStopThrowableHandler handler =
                new FailStopThrowableHandler(outputDenial);
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class,
                new SwallowedOutputThenFailureExtractor(
                        parserFailure, cleanupResource));

        Throwable thrown = assertThrows(Throwable.class,
                () -> new ZipParser().parse(
                        tis, handler, new Metadata(), context));

        assertTrue(cleanupResource.closed);
        assertSame(cleanupFailure, thrown);
        assertTrue(containsThrowableByIdentity(thrown, outputDenial));
        assertTrue(containsThrowableByIdentity(thrown, parserFailure));
        assertFalse(containsThrowableByIdentity(outputDenial, thrown));
        assertFalse(containsThrowableByIdentity(parserFailure, thrown));
        assertEquals(0, handler.callbacksAfterDenial);
    }

    private byte[] createSingleEntryZip(boolean includeCentralDirectory) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] content = "entry content".getBytes(StandardCharsets.UTF_8);
        int localHeaderOffset = baos.size();
        writeLocalFileHeader(baos, "entry.txt", content);
        if (includeCentralDirectory) {
            int centralDirectoryOffset = baos.size();
            writeCentralDirectoryEntry(baos, "entry.txt", content, localHeaderOffset);
            int centralDirectorySize = baos.size() - centralDirectoryOffset;
            writeEndOfCentralDirectory(
                    baos, 1, centralDirectorySize, centralDirectoryOffset);
        }
        return baos.toByteArray();
    }

    private static final class DenyingEmbeddedDocumentExtractor
            implements EmbeddedDocumentExtractor {

        private final Closeable cleanupResource;

        private DenyingEmbeddedDocumentExtractor(Closeable cleanupResource) {
            this.cleanupResource = cleanupResource;
        }

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return true;
        }

        @Override
        public void parseEmbedded(
                TikaInputStream stream, ContentHandler handler, Metadata metadata,
                ParseContext parseContext, boolean outputHtml) throws SAXException {
            stream.addCloseableResource(cleanupResource);
            char[] deniedOutput = DENIED_OUTPUT.toCharArray();
            handler.characters(deniedOutput, 0, deniedOutput.length);
        }
    }

    private static final class OutputFailureExtractor
            implements EmbeddedDocumentExtractor {

        private final OutputFailureMode mode;
        private final Closeable cleanupResource;
        private Throwable wrapperFailure;

        private OutputFailureExtractor(OutputFailureMode mode) {
            this(mode, null);
        }

        private OutputFailureExtractor(
                OutputFailureMode mode, Closeable cleanupResource) {
            this.mode = mode;
            this.cleanupResource = cleanupResource;
        }

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return true;
        }

        @Override
        public void parseEmbedded(
                TikaInputStream stream, ContentHandler handler, Metadata metadata,
                ParseContext parseContext, boolean outputHtml)
                throws SAXException {
            if (cleanupResource != null) {
                stream.addCloseableResource(cleanupResource);
            }
            char[] deniedOutput = DENIED_OUTPUT.toCharArray();
            try {
                handler.characters(deniedOutput, 0, deniedOutput.length);
            } catch (SAXException | RuntimeException | Error outputFailure) {
                if (mode == OutputFailureMode.SWALLOW) {
                    return;
                }
                Throwable wrappedFailure =
                        mode == OutputFailureMode.ERROR_UNWRAPPED_SAX_CAUSE
                                || mode == OutputFailureMode.ERROR_UNWRAPPED_SAX_SUPPRESSED
                                ? unwrapFailure(outputFailure) : outputFailure;
                OutputWrapperError wrapper =
                        new OutputWrapperError("wrapped ZIP output denial",
                                mode == OutputFailureMode.ERROR_CAUSE
                                        || mode == OutputFailureMode.ERROR_UNWRAPPED_SAX_CAUSE
                                        ? wrappedFailure : null);
                if (mode == OutputFailureMode.ERROR_SUPPRESSED
                        || mode == OutputFailureMode.ERROR_UNWRAPPED_SAX_SUPPRESSED) {
                    wrapper.addSuppressed(wrappedFailure);
                }
                wrapperFailure = wrapper;
                throw wrapper;
            }
        }
    }

    private static final class CompetingTaggedSaxBranchesExtractor
            implements EmbeddedDocumentExtractor {

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return true;
        }

        @Override
        public void parseEmbedded(
                TikaInputStream stream, ContentHandler handler,
                Metadata metadata, ParseContext parseContext,
                boolean outputHtml) throws SAXException {
            char[] deniedOutput = DENIED_OUTPUT.toCharArray();

            SAXException firstTaggedFailure;
            try {
                handler.characters(
                        deniedOutput, 0, deniedOutput.length);
                throw new AssertionError("expected first ZIP output denial");
            } catch (SAXException e) {
                firstTaggedFailure = e;
            }

            SAXException laterTaggedFailure;
            try {
                handler.characters(
                        deniedOutput, 0, deniedOutput.length);
                throw new AssertionError("expected later ZIP output denial");
            } catch (SAXException e) {
                laterTaggedFailure = e;
            }

            SAXException firstRawFailure =
                    (SAXException) unwrapFailure(firstTaggedFailure);
            RuntimeException cycleFirst =
                    new RuntimeException("simulated ZIP cyclic branch");
            RuntimeException cycleSecond =
                    new RuntimeException(
                            "simulated ZIP cyclic branch peer", cycleFirst);
            cycleFirst.initCause(cycleSecond);

            AssertionError parserFailure =
                    new AssertionError(
                            "fatal ZIP parser failure with competing SAX branches");
            parserFailure.addSuppressed(firstRawFailure);
            parserFailure.addSuppressed(cycleFirst);
            parserFailure.addSuppressed(laterTaggedFailure);
            throw parserFailure;
        }
    }

    private enum OutputFailureMode {
        SWALLOW,
        ERROR_CAUSE,
        ERROR_SUPPRESSED,
        ERROR_UNWRAPPED_SAX_CAUSE,
        ERROR_UNWRAPPED_SAX_SUPPRESSED
    }

    private static final class OutputWrapperError extends Error {

        private OutputWrapperError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class DirectSecurityFailureExtractor
            implements EmbeddedDocumentExtractor {

        private final SecurityException failure;
        private final FailStopThrowableHandler handler;

        private DirectSecurityFailureExtractor(
                SecurityException failure,
                FailStopThrowableHandler handler) {
            this.failure = failure;
            this.handler = handler;
        }

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return true;
        }

        @Override
        public void parseEmbedded(
                TikaInputStream stream, ContentHandler output,
                Metadata metadata, ParseContext parseContext,
                boolean outputHtml) throws SAXException {
            output.startElement(
                    XHTMLContentHandler.XHTML, "p", "p",
                    new AttributesImpl());
            handler.markDenied();
            throw failure;
        }
    }

    private static final class SwallowedOutputThenFailureExtractor
            implements EmbeddedDocumentExtractor {

        private final Throwable parserFailure;
        private final Closeable cleanupResource;

        private SwallowedOutputThenFailureExtractor(
                Throwable parserFailure) {
            this(parserFailure, null);
        }

        private SwallowedOutputThenFailureExtractor(
                Throwable parserFailure, Closeable cleanupResource) {
            this.parserFailure = parserFailure;
            this.cleanupResource = cleanupResource;
        }

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return true;
        }

        @Override
        public void parseEmbedded(
                TikaInputStream stream, ContentHandler handler,
                Metadata metadata, ParseContext parseContext,
                boolean outputHtml) throws IOException, SAXException {
            if (cleanupResource != null) {
                stream.addCloseableResource(cleanupResource);
            }
            char[] deniedOutput = DENIED_OUTPUT.toCharArray();
            try {
                handler.characters(
                        deniedOutput, 0, deniedOutput.length);
            } catch (SAXException | RuntimeException | Error expected) {
                throwTestFailure(parserFailure);
                return;
            }
            throw new AssertionError(
                    "expected the output handler to reject content");
        }
    }

    private static final class FailingCloseable implements Closeable {

        private final IOException cleanupFailure;
        private boolean closed;

        private FailingCloseable(IOException cleanupFailure) {
            this.cleanupFailure = cleanupFailure;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            throw cleanupFailure;
        }
    }

    private static final class ThrowableCloseable implements Closeable {

        private final Throwable cleanupFailure;
        private boolean closed;

        private ThrowableCloseable(Throwable cleanupFailure) {
            this.cleanupFailure = cleanupFailure;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            if (cleanupFailure instanceof IOException ioException) {
                throw ioException;
            }
            if (cleanupFailure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cleanupFailure instanceof Error error) {
                throw error;
            }
            throw new IOException(
                    "unsupported cleanup failure type", cleanupFailure);
        }
    }

    private static final class ControlledFailureExtractor implements EmbeddedDocumentExtractor {

        private final Throwable parserFailure;
        private final Closeable cleanupResource;
        private final boolean openElementBeforeFailure;

        private ControlledFailureExtractor(
                Throwable parserFailure, Closeable cleanupResource) {
            this(parserFailure, cleanupResource, false);
        }

        private ControlledFailureExtractor(
                Throwable parserFailure, Closeable cleanupResource,
                boolean openElementBeforeFailure) {
            this.parserFailure = parserFailure;
            this.cleanupResource = cleanupResource;
            this.openElementBeforeFailure = openElementBeforeFailure;
        }

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return true;
        }

        @Override
        public void parseEmbedded(
                TikaInputStream stream, ContentHandler handler, Metadata metadata,
                ParseContext parseContext, boolean outputHtml)
                throws IOException, SAXException {
            if (cleanupResource != null) {
                stream.addCloseableResource(cleanupResource);
            }
            if (openElementBeforeFailure) {
                handler.startElement(
                        XHTMLContentHandler.XHTML, "p", "p",
                        new AttributesImpl());
            }
            if (parserFailure != null) {
                throwTestFailure(parserFailure);
            }
        }
    }

    private static final class PartialOutputUnsupportedFeatureExtractor
            implements EmbeddedDocumentExtractor {

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return true;
        }

        @Override
        public void parseEmbedded(
                TikaInputStream stream, ContentHandler handler, Metadata metadata,
                ParseContext parseContext, boolean outputHtml)
                throws IOException, SAXException {
            handler.startElement(
                    XHTMLContentHandler.XHTML, "p", "p",
                    new AttributesImpl());
            throw new UnsupportedZipFeatureException(
                    UnsupportedZipFeatureException.Feature.METHOD);
        }
    }

    private static final class DocumentLifecycleHandler extends DefaultHandler {

        private final Deque<String> openElements = new ArrayDeque<>();
        private int endDocumentCalls;

        @Override
        public void startElement(
                String uri, String localName, String qName,
                Attributes attributes) {
            openElements.push(elementName(localName, qName));
        }

        @Override
        public void endElement(
                String uri, String localName, String qName)
                throws SAXException {
            String closing = elementName(localName, qName);
            if (openElements.isEmpty()
                    || !closing.equals(openElements.peek())) {
                throw new SAXException(
                        "mismatched ZIP recovery close: "
                                + closing + " while open=" + openElements);
            }
            openElements.pop();
        }

        @Override
        public void endDocument() throws SAXException {
            if (!openElements.isEmpty()) {
                throw new SAXException(
                        "ZIP recovery ended with open XHTML: "
                                + openElements);
            }
            endDocumentCalls++;
        }

        private static String elementName(
                String localName, String qName) {
            return localName == null || localName.isEmpty()
                    ? qName : localName;
        }
    }

    private static final class DrainFailStopHandler
            extends DefaultHandler {

        private final AssertionError denial;
        private boolean denied;
        private int callbacksAfterDenial;

        private DrainFailStopHandler(AssertionError denial) {
            this.denial = denial;
        }

        @Override
        public void startDocument() {
            noteCallback();
        }

        @Override
        public void endDocument() {
            noteCallback();
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) {
            noteCallback();
        }

        @Override
        public void endPrefixMapping(String prefix) {
            noteCallback();
        }

        @Override
        public void startElement(
                String uri, String localName, String qName,
                Attributes attributes) {
            noteCallback();
        }

        @Override
        public void endElement(
                String uri, String localName, String qName) {
            noteCallback();
            if (!denied && "p".equals(localName)) {
                denied = true;
                throw denial;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            noteCallback();
        }

        @Override
        public void ignorableWhitespace(
                char[] ch, int start, int length) {
            noteCallback();
        }

        @Override
        public void processingInstruction(String target, String data) {
            noteCallback();
        }

        @Override
        public void skippedEntity(String name) {
            noteCallback();
        }

        private void noteCallback() {
            if (denied) {
                callbacksAfterDenial++;
            }
        }
    }

    private static final class FailStopHandler extends DefaultHandler {

        private final SAXException denial;
        private final SAXException cleanupFailure =
                new SAXException("SAX callback delivered after ZIP output denial");
        private boolean denied;
        private int callbacksAfterDenial;

        private FailStopHandler(SAXException denial) {
            this.denial = denial;
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            if (denied) {
                rejectCallback();
            }
            if (!DENIED_OUTPUT.equals(new String(ch, start, length))) {
                return;
            }
            denied = true;
            throw denial;
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            if (denied) {
                rejectCallback();
            }
        }

        @Override
        public void endDocument() throws SAXException {
            if (denied) {
                rejectCallback();
            }
        }

        private void rejectCallback() throws SAXException {
            callbacksAfterDenial++;
            throw cleanupFailure;
        }
    }

    private static final class FailStopThrowableHandler
            extends DefaultHandler {

        private final Throwable denial;
        private boolean denied;
        private int callbacksAfterDenial;

        private FailStopThrowableHandler(Throwable denial) {
            this.denial = denial;
        }

        @Override
        public void startDocument() {
            noteCallback();
        }

        @Override
        public void endDocument() {
            noteCallback();
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) {
            noteCallback();
        }

        @Override
        public void endPrefixMapping(String prefix) {
            noteCallback();
        }

        @Override
        public void startElement(
                String uri, String localName, String qName,
                Attributes attributes) {
            noteCallback();
        }

        @Override
        public void endElement(
                String uri, String localName, String qName) {
            noteCallback();
        }

        @Override
        public void characters(
                char[] ch, int start, int length) throws SAXException {
            noteCallback();
            if (!denied
                    && DENIED_OUTPUT.equals(new String(ch, start, length))) {
                denied = true;
                throwOutputFailure(denial);
            }
        }

        @Override
        public void ignorableWhitespace(
                char[] ch, int start, int length) {
            noteCallback();
        }

        @Override
        public void processingInstruction(String target, String data) {
            noteCallback();
        }

        @Override
        public void skippedEntity(String name) {
            noteCallback();
        }

        private void noteCallback() {
            if (denied) {
                callbacksAfterDenial++;
            }
        }

        private void markDenied() {
            denied = true;
        }
    }

    private static boolean containsThrowableByIdentity(
            Throwable root, Throwable sought) {
        java.util.Set<Throwable> seen =
                java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Throwable current = pending.pop();
            if (current == sought) {
                return true;
            }
            if (!seen.add(current)) {
                continue;
            }
            Throwable cause = current.getCause();
            if (cause != null && cause != current) {
                pending.push(cause);
            }
            for (Throwable suppressed : current.getSuppressed()) {
                if (suppressed != null && suppressed != current) {
                    pending.push(suppressed);
                }
            }
        }
        return false;
    }

    private static Throwable unwrapFailure(Throwable failure) {
        java.util.Set<Throwable> seen =
                java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && seen.add(current)) {
            Throwable cause = current.getCause();
            if (cause == null || cause == current) {
                return current;
            }
            current = cause;
        }
        return current;
    }

    private static void throwOutputFailure(Throwable failure)
            throws SAXException {
        if (failure instanceof SAXException saxException) {
            throw saxException;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError(
                "unsupported output failure type", failure);
    }

    private static void throwTestFailure(Throwable failure)
            throws IOException, SAXException {
        if (failure instanceof IOException ioException) {
            throw ioException;
        }
        if (failure instanceof SAXException saxException) {
            throw saxException;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError(
                "unsupported test failure type", failure);
    }

    @Test
    public void testZipUsingStoredWithDataDescriptor() throws Exception {
        List<Metadata> metadataList = getRecursiveMetadata("testZip_with_DataDescriptor.zip");

        // Container + 5 embedded documents
        assertEquals(6, metadataList.size());
        assertEquals("en0", metadataList.get(1).get(TikaCoreProperties.RESOURCE_NAME_KEY));
        assertEquals("en1", metadataList.get(2).get(TikaCoreProperties.RESOURCE_NAME_KEY));
        assertEquals("en2", metadataList.get(3).get(TikaCoreProperties.RESOURCE_NAME_KEY));
        assertEquals("en3", metadataList.get(4).get(TikaCoreProperties.RESOURCE_NAME_KEY));
        assertEquals("en4", metadataList.get(5).get(TikaCoreProperties.RESOURCE_NAME_KEY));

        // This ZIP with DATA_DESCRIPTOR is salvaged and parsed with file-based access
        // Integrity check can compare central directory vs local headers
        Metadata containerMetadata = metadataList.get(0);
        assertEquals("PASS", containerMetadata.get(Zip.INTEGRITY_CHECK_RESULT));
    }

    @Test
    public void testIntegrityCheckPass() throws Exception {
        List<Metadata> metadataList = getRecursiveMetadata("test-documents.zip");

        // Normal ZIP with file-based access should pass integrity check
        Metadata containerMetadata = metadataList.get(0);
        assertEquals("PASS", containerMetadata.get(Zip.INTEGRITY_CHECK_RESULT));
        assertNull(containerMetadata.get(Zip.DUPLICATE_ENTRY_NAMES));
        assertNull(containerMetadata.get(Zip.CENTRAL_DIRECTORY_ONLY_ENTRIES));
        assertNull(containerMetadata.get(Zip.LOCAL_HEADER_ONLY_ENTRIES));
    }

    @Test
    public void testIntegrityCheckDisabled() throws Exception {
        ZipParserConfig config = new ZipParserConfig();
        config.setIntegrityCheck(false);
        ParseContext context = new ParseContext();
        context.set(ZipParserConfig.class, config);

        List<Metadata> metadataList = getRecursiveMetadata("test-documents.zip", context);

        // Integrity check disabled - no result should be set
        Metadata containerMetadata = metadataList.get(0);
        assertNull(containerMetadata.get(Zip.INTEGRITY_CHECK_RESULT));
    }

    @Test
    public void testIntegrityCheckHiddenEntry(@TempDir Path tempDir) throws Exception {
        // Create a ZIP with a hidden entry (in local headers but not central directory)
        Path zipPath = tempDir.resolve("hidden-entry.zip");
        byte[] zipBytes = createZipWithHiddenEntry();
        Files.write(zipPath, zipBytes);

        List<Metadata> metadataList = getRecursiveMetadata(zipPath, false);

        Metadata containerMetadata = metadataList.get(0);
        assertEquals("FAIL", containerMetadata.get(Zip.INTEGRITY_CHECK_RESULT));
        String[] localOnly = containerMetadata.getValues(Zip.LOCAL_HEADER_ONLY_ENTRIES);
        assertEquals(1, localOnly.length);
        assertEquals("hidden.txt", localOnly[0]);
    }

    /**
     * Creates a ZIP file with an entry that exists in local headers but not in the
     * central directory. This simulates a hidden/smuggled entry attack.
     */
    private byte[] createZipWithHiddenEntry() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // Entry 1: visible.txt (will be in both local header and central directory)
        byte[] visible = "visible content".getBytes(StandardCharsets.UTF_8);
        // Entry 2: hidden.txt (will be in local header ONLY - not in central directory)
        byte[] hidden = "hidden content".getBytes(StandardCharsets.UTF_8);

        // Local file header for visible.txt
        int visibleLocalOffset = baos.size();
        writeLocalFileHeader(baos, "visible.txt", visible);

        // Local file header for hidden.txt (this won't have a central directory entry)
        writeLocalFileHeader(baos, "hidden.txt", hidden);

        // Central directory - only includes visible.txt
        int centralDirOffset = baos.size();
        writeCentralDirectoryEntry(baos, "visible.txt", visible, visibleLocalOffset);

        // End of central directory
        int centralDirSize = baos.size() - centralDirOffset;
        writeEndOfCentralDirectory(baos, 1, centralDirSize, centralDirOffset);

        return baos.toByteArray();
    }

    private void writeLocalFileHeader(ByteArrayOutputStream baos, String name, byte[] content)
            throws Exception {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);

        // Local file header signature
        writeInt(baos, 0x04034b50);
        // Version needed
        writeShort(baos, 10);
        // General purpose bit flag
        writeShort(baos, 0);
        // Compression method (0 = stored)
        writeShort(baos, 0);
        // Last mod time/date
        writeShort(baos, 0);
        writeShort(baos, 0);
        // CRC-32
        writeInt(baos, (int) computeCrc32(content));
        // Compressed size
        writeInt(baos, content.length);
        // Uncompressed size
        writeInt(baos, content.length);
        // File name length
        writeShort(baos, nameBytes.length);
        // Extra field length
        writeShort(baos, 0);
        // File name
        baos.write(nameBytes);
        // File data
        baos.write(content);
    }

    private void writeCentralDirectoryEntry(ByteArrayOutputStream baos, String name,
                                             byte[] content, int localHeaderOffset) throws Exception {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);

        // Central directory file header signature
        writeInt(baos, 0x02014b50);
        // Version made by
        writeShort(baos, 20);
        // Version needed
        writeShort(baos, 10);
        // General purpose bit flag
        writeShort(baos, 0);
        // Compression method
        writeShort(baos, 0);
        // Last mod time/date
        writeShort(baos, 0);
        writeShort(baos, 0);
        // CRC-32
        writeInt(baos, (int) computeCrc32(content));
        // Compressed size
        writeInt(baos, content.length);
        // Uncompressed size
        writeInt(baos, content.length);
        // File name length
        writeShort(baos, nameBytes.length);
        // Extra field length
        writeShort(baos, 0);
        // File comment length
        writeShort(baos, 0);
        // Disk number start
        writeShort(baos, 0);
        // Internal file attributes
        writeShort(baos, 0);
        // External file attributes
        writeInt(baos, 0);
        // Relative offset of local header
        writeInt(baos, localHeaderOffset);
        // File name
        baos.write(nameBytes);
    }

    private void writeEndOfCentralDirectory(ByteArrayOutputStream baos, int numEntries,
                                             int centralDirSize, int centralDirOffset) {
        // End of central directory signature
        writeInt(baos, 0x06054b50);
        // Disk number
        writeShort(baos, 0);
        // Disk number with central directory
        writeShort(baos, 0);
        // Number of entries on this disk
        writeShort(baos, numEntries);
        // Total number of entries
        writeShort(baos, numEntries);
        // Size of central directory
        writeInt(baos, centralDirSize);
        // Offset of central directory
        writeInt(baos, centralDirOffset);
        // Comment length
        writeShort(baos, 0);
    }

    private void writeInt(ByteArrayOutputStream baos, int value) {
        baos.write(value & 0xff);
        baos.write((value >> 8) & 0xff);
        baos.write((value >> 16) & 0xff);
        baos.write((value >> 24) & 0xff);
    }

    private void writeShort(ByteArrayOutputStream baos, int value) {
        baos.write(value & 0xff);
        baos.write((value >> 8) & 0xff);
    }

    private long computeCrc32(byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        return crc.getValue();
    }

    /**
     * Microbenchmark to measure the performance impact of integrity checking.
     * This test is disabled by default - remove the assumeTrue to run it.
     *
     * WARNING: The large ZIP test creates a multi-GB file and takes significant time.
     */
    @Test
    public void benchmarkIntegrityCheck(@TempDir Path tempDir) throws Exception {
        // Skip by default - set this to true to run the benchmark
        assumeTrue(false, "Benchmark disabled by default - set to true to run");

        int iterations = 20;
        int warmupIterations = 3;

        // Create small ZIP (10 entries, ~1KB each) - ~10KB total
        Path smallZip = tempDir.resolve("small.zip");
        System.out.println("Creating small ZIP (10 entries, ~10KB)...");
        createBenchmarkZip(smallZip, 10, 1024);
        System.out.println("  Created: " + Files.size(smallZip) / 1024 + " KB");

        // Create medium ZIP (1000 entries, ~100KB each) - ~100MB total
        Path mediumZip = tempDir.resolve("medium.zip");
        System.out.println("Creating medium ZIP (1000 entries, ~100MB)...");
        createBenchmarkZip(mediumZip, 1000, 100 * 1024);
        System.out.println("  Created: " + Files.size(mediumZip) / (1024 * 1024) + " MB");

        // Create large ZIP (5000 entries, ~500KB each) - ~2.5GB total
        Path largeZip = tempDir.resolve("large.zip");
        System.out.println("Creating large ZIP (5000 entries, ~2.5GB)...");
        createBenchmarkZip(largeZip, 5000, 500 * 1024);
        System.out.println("  Created: " + Files.size(largeZip) / (1024 * 1024) + " MB");

        System.out.println();
        System.out.println("=== Integrity Check Benchmark ===");
        System.out.println("Iterations: " + iterations + " (warmup: " + warmupIterations + ")");
        System.out.println();

        // Benchmark small ZIP
        System.out.println("Small ZIP (10 entries, ~10KB):");
        runBenchmark(smallZip, iterations, warmupIterations);

        System.out.println();

        // Benchmark medium ZIP
        System.out.println("Medium ZIP (1000 entries, ~100MB):");
        runBenchmark(mediumZip, 10, 2);

        System.out.println();

        // Benchmark large ZIP
        System.out.println("Large ZIP (5000 entries, ~2.5GB):");
        runBenchmark(largeZip, 5, 1);
    }

    private void createBenchmarkZip(Path zipPath, int numEntries, int entrySize) throws Exception {
        try (java.util.zip.ZipOutputStream zos =
                     new java.util.zip.ZipOutputStream(Files.newOutputStream(zipPath))) {
            // Use STORED to avoid compression - we want actual file size
            zos.setMethod(java.util.zip.ZipOutputStream.STORED);

            // Use random data to prevent any accidental compression
            java.util.Random random = new java.util.Random(42);
            byte[] content = new byte[entrySize];
            random.nextBytes(content);

            for (int i = 0; i < numEntries; i++) {
                java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry("entry" + i + ".txt");
                entry.setMethod(java.util.zip.ZipEntry.STORED);
                entry.setSize(content.length);
                entry.setCompressedSize(content.length);
                entry.setCrc(computeCrc32(content));
                zos.putNextEntry(entry);
                zos.write(content);
                zos.closeEntry();
            }
        }
    }

    private void runBenchmark(Path zipPath, int iterations, int warmupIterations) throws Exception {
        ZipParser parser = new ZipParser();

        // Config with integrity check enabled
        ZipParserConfig configWithCheck = new ZipParserConfig();
        configWithCheck.setIntegrityCheck(true);

        // Config with integrity check disabled
        ZipParserConfig configWithoutCheck = new ZipParserConfig();
        configWithoutCheck.setIntegrityCheck(false);

        // Warmup - with integrity check
        for (int i = 0; i < warmupIterations; i++) {
            parseZip(parser, zipPath, configWithCheck);
        }

        // Warmup - without integrity check
        for (int i = 0; i < warmupIterations; i++) {
            parseZip(parser, zipPath, configWithoutCheck);
        }

        // Benchmark with integrity check
        long startWithCheck = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            parseZip(parser, zipPath, configWithCheck);
        }
        long durationWithCheck = System.nanoTime() - startWithCheck;

        // Benchmark without integrity check
        long startWithoutCheck = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            parseZip(parser, zipPath, configWithoutCheck);
        }
        long durationWithoutCheck = System.nanoTime() - startWithoutCheck;

        double avgWithCheck = durationWithCheck / (double) iterations / 1_000_000.0;
        double avgWithoutCheck = durationWithoutCheck / (double) iterations / 1_000_000.0;
        double overhead = avgWithCheck - avgWithoutCheck;
        double overheadPercent = (overhead / avgWithoutCheck) * 100;

        System.out.printf(Locale.ROOT, "  Without integrity check: %.3f ms/parse%n", avgWithoutCheck);
        System.out.printf(Locale.ROOT, "  With integrity check:    %.3f ms/parse%n", avgWithCheck);
        System.out.printf(Locale.ROOT, "  Overhead:                %.3f ms (%.1f%%)%n", overhead, overheadPercent);
    }

    private void parseZip(ZipParser parser, Path zipPath, ZipParserConfig config) throws Exception {
        ParseContext context = new ParseContext();
        context.set(ZipParserConfig.class, config);

        try (TikaInputStream tis = TikaInputStream.get(zipPath)) {
            Metadata metadata = new Metadata();
            parser.parse(tis, new org.xml.sax.helpers.DefaultHandler(), metadata, context);
        }
    }
}
