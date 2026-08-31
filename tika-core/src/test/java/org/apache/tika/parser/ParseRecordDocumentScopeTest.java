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
package org.apache.tika.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.detect.Detector;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;

/**
 * A ParseRecord is per-DOCUMENT, not per-ParseContext. CompositeParser installs one only when
 * absent and never removes it, and contexts are routinely reused across independent documents
 * (TikaCLI passes one to every file on the command line), so without a reset the record from the
 * first document silently governs every later one.
 */
public class ParseRecordDocumentScopeTest {

    @Test
    public void countAndStickyFlagsDoNotLeakIntoTheNextDocument() {
        ParseRecord record = new ParseRecord();

        // document 1: some embedded documents, and it trips the count limit
        record.beforeParse();
        record.incrementEmbeddedCount();
        record.incrementEmbeddedCount();
        record.setEmbeddedCountLimitReached(true);
        record.setEmbeddedDepthLimitReached(true);
        record.setWriteLimitReached(true);
        record.afterParse();

        assertEquals(0, record.getDepth(), "depth should unwind to 0 after a top-level parse");

        // document 2 begins: the reset CompositeParser performs at depth 0
        record.resetForNewDocument();

        assertEquals(0, record.getEmbeddedCount(),
                "embedded count carried over from the previous document");
        assertFalse(record.isEmbeddedCountLimitReached(),
                "sticky embedded-count limit carried over: every later document would silently "
                        + "yield no embedded documents at all");
        assertFalse(record.isWriteLimitReached(),
                "sticky write limit carried over from the previous document");
        assertFalse(record.isEmbeddedDepthLimitReached(),
                "sticky embedded-DEPTH limit carried over: CompositeParser would stamp "
                        + "EMBEDDED_DEPTH_LIMIT_REACHED on every later document");
    }

    /**
     * The unit test above would still pass if CompositeParser never called the reset, so assert
     * the WIRING as well: two top-level parses through one reused context must not accumulate.
     */
    @Test
    public void compositeParserResetsTheRecordBetweenDocuments() throws Exception {
        CountingParser counting = new CountingParser();
        CompositeParser composite = new CompositeParser(
                org.apache.tika.mime.MediaTypeRegistry.getDefaultRegistry(), counting);
        ParseContext shared = new ParseContext();

        parseOnce(composite, shared);
        int afterFirst = shared.get(ParseRecord.class).getEmbeddedCount();

        parseOnce(composite, shared);
        int afterSecond = shared.get(ParseRecord.class).getEmbeddedCount();

        assertEquals(afterFirst, afterSecond,
                "embedded count accumulated across two independent documents parsed through one "
                        + "reused ParseContext; the record is per-context instead of per-document");
        // Checked on ENTRY to the second parse, not after it: CountingParser sets the flag again
        // every time, so its state afterwards says nothing about whether it leaked.
        assertFalse(counting.limitReachedOnEntry,
                "the sticky embedded-count limit was still set when the SECOND document began; "
                        + "ParsingEmbeddedDocumentExtractor and CompositeParser both early-return "
                        + "on it, so that document would silently yield no embedded documents");
    }

    /**
     * The default config needs no maxCount for this to bite. CompositeParser copies the record's
     * exceptions, warnings and limit flags into the CURRENT document's metadata when a top-level
     * parse ends, so a record that survives the document attributes one file's problems to the
     * next one.
     */
    @Test
    public void warningsDoNotLeakIntoTheNextDocumentsMetadata() throws Exception {
        CountingParser counting = new CountingParser();
        CompositeParser composite = new CompositeParser(
                org.apache.tika.mime.MediaTypeRegistry.getDefaultRegistry(), counting);
        ParseContext shared = new ParseContext();

        parseOnce(composite, shared);
        Metadata second = parseOnce(composite, shared);

        for (String w : second.getValues(
                org.apache.tika.metadata.TikaCoreProperties.EMBEDDED_WARNING)) {
            assertFalse(w.contains("document one"),
                    "the second document's metadata carries a warning raised while parsing the "
                            + "FIRST document: " + w);
        }
    }

    private static Metadata parseOnce(CompositeParser composite, ParseContext context)
            throws Exception {
        Metadata metadata = new Metadata();
        metadata.set(Metadata.CONTENT_TYPE, MediaType.OCTET_STREAM.toString());
        try (TikaInputStream tis = TikaInputStream.get(new byte[] {1, 2, 3})) {
            composite.parse(tis, new org.xml.sax.helpers.DefaultHandler(), metadata, context);
        }
        return metadata;
    }

    /** Bumps the embedded count and trips the sticky flag, the way a real container parse would. */
    private static class CountingParser extends AbstractParser {

        private int parses;
        private boolean limitReachedOnEntry;

        @Override
        public java.util.Set<MediaType> getSupportedTypes(ParseContext context) {
            return MediaType.set(MediaType.OCTET_STREAM);
        }

        @Override
        public void parse(TikaInputStream stream, ContentHandler handler, Metadata metadata,
                          ParseContext context) throws IOException, SAXException, TikaException {
            ParseRecord record = context.get(ParseRecord.class);
            parses++;
            if (parses == 2) {
                limitReachedOnEntry = record.isEmbeddedCountLimitReached();
            }
            record.incrementEmbeddedCount();
            record.incrementEmbeddedCount();
            record.setEmbeddedCountLimitReached(true);
            if (parses == 1) {
                record.addWarning("warning from document one");
            }
        }
    }

    /**
     * The early-exit path. AutoDetectParser returns for MetadataOnlyParse BEFORE delegating to
     * CompositeParser, so a reset placed only in CompositeParser never runs and the previous
     * document's record stays visible to anyone inspecting it afterwards.
     */
    @Test
    public void metadataOnlySecondDocumentStillGetsAFreshRecord() throws Exception {
        AutoDetectParser auto = new AutoDetectParser();
        ParseContext shared = new ParseContext();

        // document 1: a normal parse that leaves state behind
        Metadata m1 = new Metadata();
        try (TikaInputStream tis = TikaInputStream.get(new byte[] {1, 2, 3})) {
            auto.parse(tis, new org.xml.sax.helpers.DefaultHandler(), m1, shared);
        }
        ParseRecord afterFirst = shared.get(ParseRecord.class);
        afterFirst.addWarning("warning from document one");
        afterFirst.setEmbeddedCountLimitReached(true);

        // document 2: metadata-only, which returns before CompositeParser is ever reached
        shared.set(MetadataOnlyParse.class, MetadataOnlyParse.INSTANCE);
        Metadata m2 = new Metadata();
        try (TikaInputStream tis = TikaInputStream.get(new byte[] {1, 2, 3})) {
            auto.parse(tis, new org.xml.sax.helpers.DefaultHandler(), m2, shared);
        }

        ParseRecord afterSecond = shared.get(ParseRecord.class);
        assertEquals(0, afterSecond.getWarnings().size(),
                "a metadata-only second document still exposes the FIRST document's warnings; "
                        + "AutoDetectParser returned before CompositeParser could reset");
        assertFalse(afterSecond.isEmbeddedCountLimitReached(),
                "sticky limit survived into a metadata-only document");
    }

    /**
     * The AutoDetect -> Composite handoff is ONE document. A detector (or an overridden
     * getParser) that records a diagnostic runs between the two beginDocument calls, and the
     * second call must not read as a new document and wipe it.
     */
    @Test
    public void diagnosticsRecordedDuringDetectionSurviveTheHandoff() throws Exception {
        ParseContext shared = new ParseContext();
        Detector recordingDetector = (stream, metadata, context) -> {
            ParseRecord r = context.get(ParseRecord.class);
            if (r != null) {
                r.addWarning("recorded during detection");
            }
            return MediaType.OCTET_STREAM;
        };
        AutoDetectParser auto = new AutoDetectParser(recordingDetector, new CountingParser());

        Metadata metadata = new Metadata();
        try (TikaInputStream tis = TikaInputStream.get(new byte[] {1, 2, 3})) {
            auto.parse(tis, new org.xml.sax.helpers.DefaultHandler(), metadata, shared);
        }

        // Assert the DETECTOR's warning specifically, not a total: CountingParser adds its own
        // on its first parse, so a count assertion measures the fixture rather than the property.
        boolean survived = shared.get(ParseRecord.class).getWarnings().stream()
                .anyMatch(w -> w.startsWith("recorded during detection"));
        assertTrue(survived,
                "a warning recorded by the detector was wiped by CompositeParser's reset; the "
                        + "AutoDetect-to-Composite handoff is being treated as two documents");
    }

    /**
     * Direct CompositeParser use has no earlier claim from AutoDetectParser, so the reset must
     * happen before the overridable getParser hook rather than after it.
     */
    @Test
    public void diagnosticsFromParserSelectionSurvive() throws Exception {
        ParseContext shared = new ParseContext();
        CountingParser counting = new CountingParser();
        CompositeParser composite = new CompositeParser(
                org.apache.tika.mime.MediaTypeRegistry.getDefaultRegistry(), counting) {
            @Override
            protected Parser getParser(Metadata metadata, ParseContext context) {
                ParseRecord r = context.get(ParseRecord.class);
                if (r != null) {
                    r.addWarning("recorded during parser selection");
                }
                return super.getParser(metadata, context);
            }
        };

        Metadata metadata = new Metadata();
        metadata.set(Metadata.CONTENT_TYPE, MediaType.OCTET_STREAM.toString());
        try (TikaInputStream tis = TikaInputStream.get(new byte[] {1, 2, 3})) {
            composite.parse(tis, new org.xml.sax.helpers.DefaultHandler(), metadata, shared);
        }

        boolean survived = shared.get(ParseRecord.class).getWarnings().stream()
                .anyMatch(w -> w.startsWith("recorded during parser selection"));
        assertTrue(survived,
                "a warning recorded by an overridden getParser was discarded by beginDocument; "
                        + "the document must be claimed before parser selection");
    }
}
