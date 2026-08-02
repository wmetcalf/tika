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

    /** Must match AbstractOOXMLExtractor.MAX_EXTERNAL_REF_PARTS_SCANNED. */
    private static final int MAX_PARTS_SCANNED = 5000;

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
                part.addExternalRelationship(MARKER_URL,
                        "http://schemas.openxmlformats.org/officeDocument/2006/"
                                + "relationships/attachedTemplate");
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
        int reads = extractor.getLastExternalRefPartRelationshipReadsForTesting();
        assertTrue(reads <= MAX_PARTS_SCANNED,
                "actual part.getRelationships() invocations across BOTH walks must not "
                        + "exceed the shared cap of " + MAX_PARTS_SCANNED + ", got " + reads
                        + " -- a count above the cap means one of the two scan loops ran "
                        + "unbounded (this is what a deleted tryScanPart() guard looks like)");
    }
}
