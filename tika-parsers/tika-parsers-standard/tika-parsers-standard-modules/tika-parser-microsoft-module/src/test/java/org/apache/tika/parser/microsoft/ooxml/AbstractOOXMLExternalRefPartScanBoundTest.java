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
package org.apache.tika.parser.microsoft.ooxml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagePartName;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.junit.jupiter.api.Test;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToXMLContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;

/**
 * Regression tests for the bound on how many OPC package parts the
 * external-reference machinery will scan.
 *
 * <p>Motivation: {@code AbstractOOXMLExtractor} walks
 * {@code opcPackage.getParts()} in TWO places -- {@code
 * createExternalReferenceBudget()} (a pre-pass that runs before any content is
 * streamed) and {@code surfaceExternalRefsFromAllParts()} (a catch-all after
 * {@code buildXHTML}) -- calling {@code part.getRelationships()}, itself a
 * relationship-XML parse, once per part.
 *
 * <p>The pre-existing {@code MAX_EXTERNAL_REFS_PER_DOC} cap does NOT bound
 * either walk: it limits how many <em>matching</em> high-priority
 * relationships get recorded, so a package padded with parts that carry no
 * matching relationship never trips it while still forcing an O(part-count)
 * scan. The write-limit cannot bound the pre-pass either, since that runs
 * before a single character reaches the handler.
 *
 * <p>A first fix capped only the pre-pass. These tests exist because that left
 * the structurally identical second loop fully unbounded -- so the cap must be
 * a single budget SHARED across both walks, and the tests must be able to fail
 * if only the second loop's check is removed.
 */
public class AbstractOOXMLExternalRefPartScanBoundTest {

    /** Read from production rather than duplicated, so it cannot drift. */
    private static final int MAX_PARTS_SCANNED =
            AbstractOOXMLExtractor.getMaxExternalRefPartsScannedForTesting();

    private static final String MARKER_URL = "http://example.invalid/beyond-the-scan-budget";

    /** Minimal concrete extractor: emits nothing, so only the scans do work. */
    private static class NoBodyExtractor extends AbstractOOXMLExtractor {
        NoBodyExtractor(ParseContext context, OPCPackage pkg) {
            super(context, pkg);
        }

        @Override
        protected void buildXHTML(XHTMLContentHandler xhtml) {
            // deliberately empty -- isolates the part-scan cost
        }

        @Override
        protected List<PackagePart> getMainDocumentParts() {
            return Collections.emptyList();
        }
    }

    /**
     * Builds an in-memory OPC package with {@code partCount} parts that carry
     * no relationships at all. Optionally attaches one "marker" part with a
     * real external relationship at {@code markerIndex}; placing that beyond
     * the budget is what proves a scan actually stopped.
     */
    private static OPCPackage buildPackage(int partCount, int markerIndex) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        OPCPackage pkg = OPCPackage.create(bos);
        for (int i = 0; i < partCount; i++) {
            PackagePartName name = PackagingURIHelper.createPartName(
                    String.format("/customXml/item%06d.xml", i));
            PackagePart part = pkg.createPart(name, "application/xml");
            try (OutputStream os = part.getOutputStream()) {
                os.write("<x/>".getBytes(StandardCharsets.UTF_8));
            }
            if (i == markerIndex) {
                // hyperlink, NOT attachedTemplate. attachedTemplate is high-priority, and
                // tryAcquire() refuses any high-priority rel the pre-pass did not reserve --
                // so with attachedTemplate the marker was unreachable regardless of the
                // scan guard, and the test passed even with the guard deleted (verified:
                // relationship reads went 5,000 -> 10,500 and it still passed).
                part.addExternalRelationship(MARKER_URL,
                        "http://schemas.openxmlformats.org/officeDocument/2006/"
                                + "relationships/hyperlink");
            }
        }
        return pkg;
    }

    private static String runExtractor(OPCPackage pkg, Metadata metadata) throws Exception {
        ToXMLContentHandler handler = new ToXMLContentHandler();
        new NoBodyExtractor(new ParseContext(), pkg)
                .getXHTML(handler, metadata, new ParseContext());
        return handler.toString();
    }

    @Test
    public void testHugePartCountWithNoMatchingRelationshipsIsBounded() throws Exception {
        // 6000 parts, none carrying a matching relationship, so only a
        // part-count-based cap can stop the walks.
        OPCPackage pkg = buildPackage(6000, -1);
        Metadata metadata = new Metadata();

        assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> runExtractor(pkg, metadata),
                "the external-reference part scans must be bounded by part count");

        assertEquals("true", metadata.get(TikaCoreProperties.TRUNCATED_METADATA),
                "exceeding the part-scan budget must surface a truncation signal");
    }

    @Test
    public void testModestPartCountIsNotReportedAsTruncated() throws Exception {
        OPCPackage pkg = buildPackage(25, -1);
        Metadata metadata = new Metadata();

        runExtractor(pkg, metadata);

        assertNull(metadata.get(TikaCoreProperties.TRUNCATED_METADATA),
                "a package well inside the budget must not be reported as truncated");
    }

    /**
     * The test that specifically covers the second loop. The marker part sits
     * beyond the shared budget, so a correctly-bounded scan can never reach
     * it. Deleting only {@code surfaceExternalRefsFromAllParts()}'s
     * {@code tryScanPart()} check -- leaving the pre-pass cap intact -- makes
     * this fail, which is exactly the gap a timing-only test missed.
     */
    @Test
    public void testSecondLoopCannotReachAPartBeyondTheSharedScanBudget() throws Exception {
        OPCPackage pkg = buildPackage(MAX_PARTS_SCANNED + 500, MAX_PARTS_SCANNED + 250);
        Metadata metadata = new Metadata();

        String xhtml = runExtractor(pkg, metadata);

        assertFalse(xhtml.contains(MARKER_URL),
                "a part beyond the shared part-scan budget must never be surfaced in "
                        + "output -- reaching it means a scan loop ran unbounded");
        for (String value : metadata.names()) {
            String[] vals = metadata.getValues(value);
            for (String v : vals) {
                assertFalse(v != null && v.contains(MARKER_URL),
                        "a part beyond the shared part-scan budget leaked into metadata "
                                + "key '" + value + "'");
            }
        }
    }

    /**
     * A package INSIDE the advertised budget must have its external references surfaced.
     *
     * <p>Regression guard for a double-charge: both walks iterate the same
     * {@code getParts()}, and charging per ATTEMPT billed every part twice, halving real
     * coverage to MAX/2. Measured before the fix: a 2,501-part package already lost the
     * hyperlink in its last part, and at 5,000 parts even part 0 became unreachable. The
     * bound must be charged once per DISTINCT part.
     */
    @Test
    public void testPartWellInsideTheBudgetIsStillSurfaced() throws Exception {
        // comfortably inside MAX, but beyond MAX/2 -- the range the double-charge lost
        int partCount = (MAX_PARTS_SCANNED / 2) + 200;
        OPCPackage pkg = buildPackage(partCount, partCount - 1);
        Metadata metadata = new Metadata();

        String xhtml = runExtractor(pkg, metadata);

        assertTrue(xhtml.contains(MARKER_URL) || metadataContains(metadata, MARKER_URL),
                "an external reference in part " + (partCount - 1) + " of a " + partCount
                        + "-part package is INSIDE the advertised " + MAX_PARTS_SCANNED
                        + "-part budget and must be surfaced; losing it means parts are "
                        + "being charged more than once");
        assertNull(metadata.get("msoffice:external-ref-part-scan-limit-reached"),
                "a package inside the budget must not report a part-scan limit");
    }

    private static boolean metadataContains(Metadata metadata, String needle) {
        for (String name : metadata.names()) {
            for (String v : metadata.getValues(name)) {
                if (v != null && v.contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** When the part-scan bound DOES fire, it must say so distinguishably. */
    @Test
    public void testPartScanTruncationIsReportedWithItsOwnSignal() throws Exception {
        OPCPackage pkg = buildPackage(MAX_PARTS_SCANNED + 500, -1);
        Metadata metadata = new Metadata();

        runExtractor(pkg, metadata);

        assertEquals("true",
                metadata.get("msoffice:external-ref-part-scan-limit-reached"),
                "exceeding the part-scan bound must set its OWN flag -- reusing the link "
                        + "limit warning misattributes 'parts never examined' as 'too many "
                        + "links', and fired even with zero links recorded");
    }

    /**
     * Guards against a regression to per-loop budgets. Two independent 5000
     * allowances would still be bounded, but would double the real scan work;
     * the cap is specified as a single shared budget.
     */
    @Test
    public void testTotalPartsScannedAcrossBothLoopsNeverExceedsTheSharedCap() throws Exception {
        OPCPackage pkg = buildPackage(MAX_PARTS_SCANNED + 500, -1);
        Metadata metadata = new Metadata();

        NoBodyExtractor extractor = new NoBodyExtractor(new ParseContext(), pkg);
        extractor.getXHTML(new ToXMLContentHandler(), metadata, new ParseContext());

        int scanned = extractor.getLastExternalRefPartsScannedForTesting();
        assertTrue(scanned <= MAX_PARTS_SCANNED,
                "combined parts scanned across BOTH walks must not exceed the shared cap of "
                        + MAX_PARTS_SCANNED + ", got " + scanned
                        + " (per-loop budgets would double the real scan work)");

        // The assertion that actually catches a deleted guard. partsScanned only
        // advances when tryScanPart() is consulted, so removing a guard leaves it
        // looking healthy; this counts real part.getRelationships() invocations,
        // which is the expensive work the cap exists to bound.
        // Each of the two walks may legitimately touch up to MAX distinct parts, so
        // INVOCATIONS can reach 2xMAX -- but only the FIRST per part parses XML; POI
        // caches the relationship collection, so the second walk's calls are in-memory
        // filters (verified against POI 5.5.1 bytecode). The expensive work is therefore
        // still bounded by MAX. Asserting 2xMAX keeps this mutation-sensitive: deleting
        // the second loop's guard on this 5,500-part package yields 5,000 + 5,500 = 10,500
        // invocations, which trips this bound.
        int reads = extractor.getLastExternalRefPartRelationshipReadsForTesting();
        assertTrue(reads <= 2 * MAX_PARTS_SCANNED,
                "getRelationships() invocations across BOTH walks must not exceed 2x the "
                        + "shared cap of " + MAX_PARTS_SCANNED + " (one cached re-read per "
                        + "charged part is expected; more means a loop ran unbounded), got "
                        + reads);
    }
}
