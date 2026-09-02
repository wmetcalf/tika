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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.config.EmbeddedLimits;
import org.apache.tika.detect.Detector;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.ParsingEmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.multiple.AbstractMultipleParser;

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

    /**
     * A failed parser selection must not hold the document claim.
     *
     * <p>{@code getParser} runs after {@code beginDocument} claimed the document but before
     * {@code beforeParse} incremented the depth, so if it throws, the {@code afterParse} in the
     * finally -- the only thing that clears the claim on the normal path -- never runs. The claim
     * then outlives the failed parse and the NEXT top-level parse on the same context skips its
     * reset, inheriting the failed selection's warnings straight into its own metadata.
     */
    @Test
    public void aFailedParserSelectionDoesNotLeakIntoTheNextDocument() throws Exception {
        ParseContext shared = new ParseContext();
        CountingParser counting = new CountingParser();
        AtomicInteger selections = new AtomicInteger();
        CompositeParser composite = new CompositeParser(
                org.apache.tika.mime.MediaTypeRegistry.getDefaultRegistry(), counting) {
            @Override
            protected Parser getParser(Metadata metadata, ParseContext context) {
                if (selections.getAndIncrement() == 0) {
                    ParseRecord r = context.get(ParseRecord.class);
                    if (r != null) {
                        r.addWarning("raised by the FAILED selection of document one");
                    }
                    throw new IllegalStateException("parser selection blew up");
                }
                return super.getParser(metadata, context);
            }
        };

        Metadata first = new Metadata();
        first.set(Metadata.CONTENT_TYPE, MediaType.OCTET_STREAM.toString());
        try (TikaInputStream tis = TikaInputStream.get(new byte[] {1, 2, 3})) {
            assertThrows(IllegalStateException.class,
                    () -> composite.parse(tis, new org.xml.sax.helpers.DefaultHandler(), first,
                            shared));
        }

        Metadata second = parseOnce(composite, shared);

        for (String w : second.getValues(
                org.apache.tika.metadata.TikaCoreProperties.EMBEDDED_WARNING)) {
            assertFalse(w.contains("FAILED selection of document one"),
                    "the second document's metadata carries a warning from the FIRST document's "
                            + "failed parser selection; the claim was never released: " + w);
        }
        assertTrue(shared.get(ParseRecord.class).getWarnings().stream()
                        .noneMatch(w -> w.contains("FAILED selection of document one")),
                "the record still holds the failed selection's warning, so beginDocument skipped "
                        + "its reset -- a throwing getParser must release the document claim");
    }

    /**
     * A container parser invoked DIRECTLY must not have its embedded count reset per entry.
     *
     * <p>beginDocument decides "is this a new top-level document?" from the depth, and only
     * CompositeParser maintains that. A container parser called directly -- not through
     * CompositeParser or AutoDetectParser -- never increments it, so every entry it hands to the
     * embedded extractor arrived at depth 0 and looked like a fresh document. Each sibling then
     * reset the record and discarded the embedded count accumulated so far, so a configured
     * maximum embedded count could be bypassed across siblings: the count went back to zero on
     * every entry and the limit was never reached.
     */
    @Test
    public void aDirectContainerParserCannotResetTheEmbeddedCountPerEntry() throws Exception {
        ParseContext context = new ParseContext();
        ParseRecord record = ParseRecord.newInstance(context);
        record.setMaxEmbeddedCount(3);
        context.set(ParseRecord.class, record);

        // The delegate every embedded entry is handed to -- an AutoDetectParser-shaped caller
        // that claims the document, exactly as the real one does.
        AtomicInteger parsedEntries = new AtomicInteger();
        context.set(Parser.class, new AbstractParser() {
            @Override
            public Set<MediaType> getSupportedTypes(ParseContext c) {
                return Collections.singleton(MediaType.OCTET_STREAM);
            }

            @Override
            public void parse(TikaInputStream tis, ContentHandler handler, Metadata metadata,
                              ParseContext c) {
                ParseRecord.beginDocument(c);
                try {
                    parsedEntries.incrementAndGet();
                } finally {
                    ParseRecord.endDocument(c);
                }
            }
        });

        ParsingEmbeddedDocumentExtractor extractor =
                new ParsingEmbeddedDocumentExtractor(context);
        // A direct container parser: it never claims a document of its own, it just hands over
        // ten entries the way MboxParser hands over messages.
        for (int i = 0; i < 10; i++) {
            Metadata entry = new Metadata();
            entry.set(Metadata.CONTENT_TYPE, MediaType.OCTET_STREAM.toString());
            try (TikaInputStream tis = TikaInputStream.get(new byte[] {1, 2, 3})) {
                extractor.parseEmbedded(tis, new org.xml.sax.helpers.DefaultHandler(), entry,
                        context, false);
            }
        }

        assertEquals(3, parsedEntries.get(),
                "the configured maximum embedded count was bypassed: the record was reset on "
                        + "every sibling entry, so the count never reached the limit");
        assertTrue(record.isEmbeddedCountLimitReached(),
                "the embedded count limit should have been recorded as reached");
    }

    /**
     * A detector that runs its own parse on the same context must not end the outer document.
     *
     * <p>Some detectors inspect an auxiliary stream by invoking a parser. That inner parse begins
     * and ends while the OUTER AutoDetect parse is still at depth zero, so releasing the claim
     * whenever any parse returns to depth zero handed the outer parse's claim away. The outer
     * super.parse() then read as a new document and reset everything detection had recorded --
     * including the very diagnostics {@link #diagnosticsRecordedDuringDetectionSurviveTheHandoff}
     * exists to protect. The claim is counted, so the inner parse releases only its own.
     */
    @Test
    public void aDetectorsOwnNestedParseDoesNotEndTheOuterDocument() throws Exception {
        ParseContext shared = new ParseContext();
        CompositeParser auxiliary = new CompositeParser(
                org.apache.tika.mime.MediaTypeRegistry.getDefaultRegistry(), new CountingParser());

        Detector detectorThatParses = (stream, metadata, context) -> {
            ParseRecord r = context.get(ParseRecord.class);
            if (r != null) {
                r.addWarning("recorded during detection");
            }
            // Inspect an auxiliary stream with a full parse on the SAME context. This begins and
            // ends at depth zero, underneath the outer AutoDetect parse.
            Metadata aux = new Metadata();
            aux.set(Metadata.CONTENT_TYPE, MediaType.OCTET_STREAM.toString());
            try (TikaInputStream auxStream = TikaInputStream.get(new byte[] {9, 9, 9})) {
                auxiliary.parse(auxStream, new org.xml.sax.helpers.DefaultHandler(), aux, context);
            } catch (Exception e) {
                throw new IOException(e);
            }
            return MediaType.OCTET_STREAM;
        };

        AutoDetectParser auto = new AutoDetectParser(detectorThatParses, new CountingParser());
        Metadata metadata = new Metadata();
        try (TikaInputStream tis = TikaInputStream.get(new byte[] {1, 2, 3})) {
            auto.parse(tis, new org.xml.sax.helpers.DefaultHandler(), metadata, shared);
        }

        boolean survived = shared.get(ParseRecord.class).getWarnings().stream()
                .anyMatch(w -> w.startsWith("recorded during detection"));
        assertTrue(survived,
                "the detector's warning was wiped: its own nested parse released the OUTER "
                        + "parse's document claim, so the handoff read as a new document");
    }

    /** Drives N entries through the embedded extractor the way a direct container parser does. */
    private static int runDirectContainer(ParseContext context, int entries) throws Exception {
        AtomicInteger parsed = new AtomicInteger();
        context.set(Parser.class, new AbstractParser() {
            @Override
            public Set<MediaType> getSupportedTypes(ParseContext c) {
                return Collections.singleton(MediaType.OCTET_STREAM);
            }

            @Override
            public void parse(TikaInputStream tis, ContentHandler handler, Metadata metadata,
                              ParseContext c) {
                ParseRecord.beginDocument(c);
                try {
                    parsed.incrementAndGet();
                } finally {
                    ParseRecord.endDocument(c);
                }
            }
        });
        ParsingEmbeddedDocumentExtractor extractor =
                new ParsingEmbeddedDocumentExtractor(context);
        for (int i = 0; i < entries; i++) {
            Metadata entry = new Metadata();
            entry.set(Metadata.CONTENT_TYPE, MediaType.OCTET_STREAM.toString());
            try (TikaInputStream tis = TikaInputStream.get(new byte[] {1, 2, 3})) {
                extractor.parseEmbedded(tis, new org.xml.sax.helpers.DefaultHandler(), entry,
                        context, false);
            }
        }
        return parsed.get();
    }

    /**
     * A directly-invoked container parser reusing one context gets isolation by DECLARING it.
     *
     * <p>The boundary cannot be inferred: between two sibling entries and between two containers
     * the record is in the same state (claims 0, nesting 0). Resetting on that state would let a
     * configured maximum embedded count be bypassed between siblings, which is the defect
     * {@link #aDirectContainerParserCannotResetTheEmbeddedCountPerEntry} pins. So the caller
     * declares the boundary, and only the caller can.
     */
    @Test
    public void aDirectContainerCanDeclareItsOwnDocumentBoundary() throws Exception {
        ParseContext context = new ParseContext();
        ParseRecord record = ParseRecord.newInstance(context);
        record.setMaxEmbeddedCount(3);
        context.set(ParseRecord.class, record);

        // Each container declares its own scope. Holding the claim for the span of its parse is
        // what keeps the limit enforceable across its sibling entries; opening the NEXT scope on
        // an idle record is what gives the next container a fresh budget.
        ParseRecord.beginDocument(context);
        try {
            assertEquals(3, runDirectContainer(context, 10),
                    "the limit should hold across sibling entries of ONE container");
        } finally {
            ParseRecord.endDocument(context);
        }
        assertTrue(record.isEmbeddedCountLimitReached(), "limit should be recorded as reached");

        ParseRecord.beginDocument(context);
        try {
            assertEquals(3, runDirectContainer(context, 10),
                    "the second container should get its own budget; without a declared scope it "
                            + "inherits the previous container's exhausted count and extracts "
                            + "nothing at all");
        } finally {
            ParseRecord.endDocument(context);
        }
        assertTrue(record.isEmbeddedCountLimitReached());
    }

    /**
     * A caller that records diagnostics BEFORE the parser runs must claim the document first.
     *
     * <p>This is the tika-pipes {@code ParseHandler} shape: it runs a configured {@code Digester}
     * in preprocessing, which can record a warning, and then sets
     * {@code SkipContainerDocumentDigest} so the container digest is not recomputed. If the
     * parser's own claim is the FIRST one, it sees an unclaimed depth-zero record, reads it as a
     * new document and resets -- discarding the diagnostic, unrecoverably, because the digest
     * does not run again.
     */
    @Test
    public void diagnosticsFromPreprocessingSurviveOnlyIfTheCallerClaimsFirst() {
        // Without a caller claim: the parser's claim resets and the diagnostic is lost.
        ParseContext unclaimed = new ParseContext();
        ParseRecord lost = ParseRecord.newInstance(unclaimed);
        unclaimed.set(ParseRecord.class, lost);
        lost.addWarning("recorded during preprocessing");
        ParseRecord.beginDocument(unclaimed);          // the parser, claiming first
        ParseRecord.endDocument(unclaimed);
        assertTrue(lost.getWarnings().stream()
                        .noneMatch(w -> w.startsWith("recorded during preprocessing")),
                "sanity: with no caller claim the parser's own claim is expected to reset the "
                        + "record -- if this no longer holds, the hazard below is stale");

        // With a caller claim: preprocessing is inside the document, so it survives.
        ParseContext claimed = new ParseContext();
        ParseRecord kept = ParseRecord.newInstance(claimed);
        claimed.set(ParseRecord.class, kept);
        ParseRecord.beginDocument(claimed);            // the caller, before preprocessing
        try {
            kept.addWarning("recorded during preprocessing");
            ParseRecord.beginDocument(claimed);        // the parser, joining
            ParseRecord.endDocument(claimed);
        } finally {
            ParseRecord.endDocument(claimed);
        }
        assertTrue(kept.getWarnings().stream()
                        .anyMatch(w -> w.startsWith("recorded during preprocessing")),
                "a diagnostic recorded before the parser ran was discarded even though the "
                        + "caller claimed the document first");
    }

    /**
     * The FIRST entry of a directly-invoked container must be limit-checked and counted.
     *
     * <p>{@code parseEmbedded} reads the record with {@code context.get(ParseRecord.class)} and
     * no-ops when it is absent. In the direct-container flow it IS absent: the container never
     * claims, no CompositeParser has run, and the record is created by the delegate's own
     * beginDocument -- i.e. DURING entry one. So entry one escaped the count check, the
     * increment, and the embedded bracket, and a configured maximum of N admitted N+1.
     *
     * <p>Deliberately does NOT pre-install the record. The sibling tests here do, via
     * {@code ParseRecord.newInstance(context)}, which is exactly the state the real path does not
     * have -- and that is why they could not see this.
     */
    @Test
    public void theFirstEntryOfADirectContainerIsCountedAgainstTheLimit() throws Exception {
        ParseContext context = new ParseContext();
        EmbeddedLimits limits = new EmbeddedLimits();
        limits.setMaxCount(3);
        context.set(EmbeddedLimits.class, limits);
        // No ParseRecord installed. That is the point.

        assertEquals(3, runDirectContainer(context, 10),
                "a configured maximum embedded count of 3 admitted more than 3: the first entry "
                        + "arrives before any record exists, so it is neither limit-checked nor "
                        + "counted");
    }

    /**
     * A throw between the claim and beforeParse() must not leave the depth counter skewed.
     *
     * <p>Four statements run inside the try before {@code beforeParse()} -- building the tagged
     * handler, resolving the parser class name, recording it on the record and on the metadata --
     * while the finally calls {@code afterParse()} unconditionally. A throw in that window
     * decrements depth with no matching increment. Before this PR that was a transient one-parse
     * glitch; now the per-document reset is gated on {@code depth == 0}, so a single skew LATCHES:
     * the record never resets again, every later document on the context inherits its counts and
     * sticky limit flags, and the depth-gated metadata block stops stamping embedded
     * exceptions and limit hits onto anything, forever.
     */
    @Test
    public void aThrowBeforeBeforeParseDoesNotSkewTheDepthPermanently() throws Exception {
        ParseContext shared = new ParseContext();
        CountingParser counting = new CountingParser();
        AtomicInteger calls = new AtomicInteger();
        CompositeParser composite = new CompositeParser(
                org.apache.tika.mime.MediaTypeRegistry.getDefaultRegistry(), counting) {
            @Override
            protected Parser getParser(Metadata metadata, ParseContext context) {
                if (calls.getAndIncrement() == 0) {
                    ParseRecord r = context.get(ParseRecord.class);
                    if (r != null) {
                        r.addWarning("raised by the document that threw before beforeParse");
                    }
                    // Selection succeeds; the work between the claim and beforeParse() blows up.
                    return null;
                }
                return super.getParser(metadata, context);
            }
        };

        Metadata first = new Metadata();
        first.set(Metadata.CONTENT_TYPE, MediaType.OCTET_STREAM.toString());
        try (TikaInputStream tis = TikaInputStream.get(new byte[] {1, 2, 3})) {
            assertThrows(Throwable.class,
                    () -> composite.parse(tis, new org.xml.sax.helpers.DefaultHandler(), first,
                            shared));
        }

        assertEquals(0, shared.get(ParseRecord.class).getDepth(),
                "a throw between the claim and beforeParse() ran afterParse() without a matching "
                        + "beforeParse(), so depth is skewed; the per-document reset is gated on "
                        + "depth == 0 and can now never fire again on this context");

        // And prove the consequence, not just the counter: the next document still resets.
        // (Assert on a marker from the FAILED parse specifically -- CountingParser's own
        // "document one" warning legitimately belongs to the next document, since the first
        // parse threw before ever reaching it.)
        Metadata second = parseOnce(composite, shared);
        for (String w : second.getValues(
                org.apache.tika.metadata.TikaCoreProperties.EMBEDDED_WARNING)) {
            assertFalse(w.contains("threw before beforeParse"),
                    "the next document inherited the failed document's warning, so the record "
                            + "never reset: " + w);
        }
        assertTrue(shared.get(ParseRecord.class).getWarnings().stream()
                        .noneMatch(w -> w.contains("threw before beforeParse")),
                "the record still carries the failed document's warning");
    }

    /**
     * The boundary invariant: after a parse returns, every counter is back to zero.
     *
     * <p>Untested until now, and unobservable in production -- endDocument and exitEmbedded both
     * clamp at zero, so an orphan or doubled release is silently absorbed. The whole design rests
     * on these returning to zero: a leaked claim latches the record into per-CONTEXT behaviour
     * permanently, and an over-release lets the next nested claim reset mid-document.
     */
    @Test
    public void everyCounterReturnsToZeroAfterAParse() throws Exception {
        ParseContext shared = new ParseContext();
        CompositeParser composite = new CompositeParser(
                org.apache.tika.mime.MediaTypeRegistry.getDefaultRegistry(), new CountingParser());

        parseOnce(composite, shared);
        ParseRecord record = shared.get(ParseRecord.class);
        assertEquals(0, record.getClaims(), "claims did not return to zero after a normal parse");
        assertEquals(0, record.getEmbeddedNesting(), "embeddedNesting did not return to zero");
        assertEquals(0, record.getDepth(), "depth did not return to zero");

        // And after a parse that THREW -- the paths where a release is easiest to miss.
        AtomicInteger calls = new AtomicInteger();
        CompositeParser throwing = new CompositeParser(
                org.apache.tika.mime.MediaTypeRegistry.getDefaultRegistry(), new CountingParser()) {
            @Override
            protected Parser getParser(Metadata metadata, ParseContext context) {
                if (calls.getAndIncrement() == 0) {
                    throw new IllegalStateException("selection blew up");
                }
                return calls.get() == 2 ? null : super.getParser(metadata, context);
            }
        };
        Metadata m = new Metadata();
        m.set(Metadata.CONTENT_TYPE, MediaType.OCTET_STREAM.toString());
        try (TikaInputStream tis = TikaInputStream.get(new byte[] {1, 2, 3})) {
            assertThrows(Throwable.class,
                    () -> throwing.parse(tis, new org.xml.sax.helpers.DefaultHandler(), m, shared));
        }
        assertEquals(0, record.getClaims(), "a throw from parser selection leaked a claim");
        assertEquals(0, record.getDepth(), "a throw from parser selection skewed the depth");

        try (TikaInputStream tis = TikaInputStream.get(new byte[] {1, 2, 3})) {
            assertThrows(Throwable.class,
                    () -> throwing.parse(tis, new org.xml.sax.helpers.DefaultHandler(),
                            new Metadata(), shared));
        }
        assertEquals(0, record.getClaims(),
                "a throw between the claim and beforeParse() leaked a claim");
        assertEquals(0, record.getDepth(),
                "a throw between the claim and beforeParse() skewed the depth");
    }

    /** A child parser that drives {@code n} embedded entries through the extractor. */
    private static Parser embeddingChild(int n, AtomicInteger parsed) {
        return new AbstractParser() {
            @Override
            public Set<MediaType> getSupportedTypes(ParseContext c) {
                return Collections.singleton(MediaType.OCTET_STREAM);
            }

            @Override
            public void parse(TikaInputStream tis, ContentHandler handler, Metadata metadata,
                              ParseContext c) throws IOException, SAXException {
                org.apache.tika.extractor.EmbeddedDocumentExtractor ex =
                        org.apache.tika.extractor.EmbeddedDocumentUtil
                                .getEmbeddedDocumentExtractor(c);
                for (int i = 0; i < n; i++) {
                    Metadata entry = new Metadata();
                    entry.set(Metadata.CONTENT_TYPE, MediaType.OCTET_STREAM.toString());
                    try (TikaInputStream child = TikaInputStream.get(new byte[] {1, 2, 3})) {
                        ex.parseEmbedded(child, new org.xml.sax.helpers.DefaultHandler(), entry, c,
                                false);
                    }
                }
                parsed.incrementAndGet();
            }
        };
    }

    /**
     * Several parser passes over ONE document share one embedded budget.
     *
     * <p>{@link org.apache.tika.parser.multiple.AbstractMultipleParser} runs each of its children
     * over the SAME document, and holds no claim of its own. Each child's own beginDocument
     * therefore arrived idle at depth zero, read as a new document, and RESET the record --
     * handing every pass a fresh embedded budget. With N children a document-wide maximum of M
     * admitted up to N*M.
     */
    @Test
    public void multipleParserPassesShareOneEmbeddedBudget() throws Exception {
        ParseContext context = new ParseContext();
        EmbeddedLimits limits = new EmbeddedLimits();
        limits.setMaxCount(3);
        context.set(EmbeddedLimits.class, limits);
        AtomicInteger embedded = new AtomicInteger();
        context.set(Parser.class, new AbstractParser() {
            @Override
            public Set<MediaType> getSupportedTypes(ParseContext c) {
                return Collections.singleton(MediaType.OCTET_STREAM);
            }

            @Override
            public void parse(TikaInputStream tis, ContentHandler handler, Metadata metadata,
                              ParseContext c) {
                embedded.incrementAndGet();
            }
        });

        AtomicInteger passes = new AtomicInteger();
        // Each child is a CompositeParser, so each takes and releases its OWN document claim --
        // which is what makes the second pass see an idle record and reset it.
        Parser childOne = new CompositeParser(
                org.apache.tika.mime.MediaTypeRegistry.getDefaultRegistry(),
                embeddingChild(5, passes));
        Parser childTwo = new CompositeParser(
                org.apache.tika.mime.MediaTypeRegistry.getDefaultRegistry(),
                embeddingChild(5, passes));
        Parser multiple = new org.apache.tika.parser.multiple.SupplementingParser(
                org.apache.tika.mime.MediaTypeRegistry.getDefaultRegistry(),
                AbstractMultipleParser.MetadataPolicy.FIRST_WINS, childOne, childTwo);

        Metadata metadata = new Metadata();
        metadata.set(Metadata.CONTENT_TYPE, MediaType.OCTET_STREAM.toString());
        try (TikaInputStream tis = TikaInputStream.get(new byte[] {1, 2, 3})) {
            multiple.parse(tis, new org.xml.sax.helpers.DefaultHandler(), metadata, context);
        }

        assertEquals(2, passes.get(), "both children should have run");
        assertEquals(3, embedded.get(),
                "a document-wide maximum embedded count of 3 was applied PER PASS: each child "
                        + "reset the record and got a fresh budget");
    }

    /**
     * FallbackParser's passes are ALTERNATIVES, so they must not share one embedded budget.
     *
     * <p>SupplementingParser's passes all contribute to one output, so sharing a budget is right.
     * FallbackParser is the opposite contract: {@code parserCompleted} returns
     * {@code exception != null}, i.e. the next parser runs ONLY because the previous one FAILED,
     * and the failed pass's output is thrown away. Charging the discarded pass's embedded count
     * against the pass that actually produces the output means the failed primary can exhaust the
     * budget -- and its sticky {@code embeddedCountLimitReached} then hard-stops the fallback
     * before the count is even consulted, so the returned document has ZERO attachments and no
     * limit-reached flag to explain why.
     */
    @Test
    public void fallbackPassesDoNotInheritAFailedPassesExhaustedBudget() throws Exception {
        ParseContext context = new ParseContext();
        EmbeddedLimits limits = new EmbeddedLimits();
        limits.setMaxCount(3);
        context.set(EmbeddedLimits.class, limits);
        AtomicInteger embedded = new AtomicInteger();
        context.set(Parser.class, new AbstractParser() {
            @Override
            public Set<MediaType> getSupportedTypes(ParseContext c) {
                return Collections.singleton(MediaType.OCTET_STREAM);
            }

            @Override
            public void parse(TikaInputStream tis, ContentHandler handler, Metadata metadata,
                              ParseContext c) {
                embedded.incrementAndGet();
            }
        });

        AtomicInteger passes = new AtomicInteger();
        // Primary: burns the whole budget, then fails -- so its output is discarded.
        Parser primary = new CompositeParser(
                org.apache.tika.mime.MediaTypeRegistry.getDefaultRegistry(),
                new AbstractParser() {
                    @Override
                    public Set<MediaType> getSupportedTypes(ParseContext c) {
                        return Collections.singleton(MediaType.OCTET_STREAM);
                    }

                    @Override
                    public void parse(TikaInputStream tis, ContentHandler handler,
                                      Metadata metadata, ParseContext c)
                            throws IOException, SAXException, TikaException {
                        org.apache.tika.extractor.EmbeddedDocumentUtil
                                .getEmbeddedDocumentExtractor(c);
                        embeddingChild(5, passes).parse(tis, handler, metadata, c);
                        throw new TikaException("primary failed after burning the budget");
                    }
                });
        Parser fallback = new CompositeParser(
                org.apache.tika.mime.MediaTypeRegistry.getDefaultRegistry(),
                embeddingChild(5, passes));

        int burnedByPrimary;
        Parser multiple = new org.apache.tika.parser.multiple.FallbackParser(
                org.apache.tika.mime.MediaTypeRegistry.getDefaultRegistry(),
                AbstractMultipleParser.MetadataPolicy.FIRST_WINS, primary, fallback);
        Metadata metadata = new Metadata();
        metadata.set(Metadata.CONTENT_TYPE, MediaType.OCTET_STREAM.toString());
        try (TikaInputStream tis = TikaInputStream.get(new byte[] {1, 2, 3})) {
            multiple.parse(tis, new org.xml.sax.helpers.DefaultHandler(), metadata, context);
        }
        burnedByPrimary = 3;

        assertEquals(burnedByPrimary * 2, embedded.get(),
                "the fallback pass inherited the FAILED primary's exhausted budget and extracted "
                        + "nothing; its passes are alternatives, not contributions, so each gets "
                        + "its own budget");
    }
}
