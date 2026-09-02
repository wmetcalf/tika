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
package org.apache.tika.extractor;

import static org.apache.tika.sax.XHTMLContentHandler.XHTML;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import org.apache.tika.config.EmbeddedLimits;
import org.apache.tika.exception.CorruptedFileException;
import org.apache.tika.exception.EmbeddedLimitReachedException;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.DelegatingParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ParseRecord;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.EmbeddedContentHandler;
import org.apache.tika.sax.SAXOutputConfig;
import org.apache.tika.sax.TaggedContentHandler;
import org.apache.tika.sax.XHTMLBalancingHandler;

/**
 * Helper class for parsers of package archives or other compound document
 * formats that support embedded or attached component documents.
 *
 * @since Apache Tika 0.8
 */
public class ParsingEmbeddedDocumentExtractor implements EmbeddedDocumentExtractor {

    private static final File ABSTRACT_PATH = new File("");

    private static final Parser DELEGATING_PARSER = new DelegatingParser();

    protected final ParseContext context;

    public ParsingEmbeddedDocumentExtractor(ParseContext context) {
        this.context = context;
    }

    /**
     * The record for this parse, created if no one has installed one yet.
     *
     * <p>Never returns null, and that is the point. Both limit gates used to read the record with
     * a plain {@code context.get} and no-op when it was absent -- but in the flow this class is
     * most exposed on, a CONTAINER parser invoked directly, it IS absent: the container never
     * claims a document, no CompositeParser has run, and the record is created by the delegate's
     * own beginDocument, i.e. DURING the first entry. So the first entry was neither
     * limit-checked nor counted, and a configured maximum embedded count of N admitted N+1 --
     * an off-by-one relaxation of a DoS bound, in a hardening fork.
     *
     * <p>{@code newInstance} reads the configured {@link EmbeddedLimits} out of the context, so
     * creating the record here applies the caller's limits from entry one rather than from
     * entry two.
     */
    private ParseRecord parseRecord() {
        ParseRecord parseRecord = context.get(ParseRecord.class);
        if (parseRecord == null) {
            parseRecord = ParseRecord.newInstance(context);
            context.set(ParseRecord.class, parseRecord);
        }
        return parseRecord;
    }

    @Override
    public boolean shouldParseEmbedded(Metadata metadata) {
        // Check ParseRecord for depth/count limits first
        ParseRecord parseRecord = parseRecord();
        if (!checkEmbeddedLimits(parseRecord)) {
            return false;
        }

        // Then check DocumentSelector for content-based filtering
        DocumentSelector selector = context.get(DocumentSelector.class);
        if (selector != null) {
            return selector.select(metadata);
        }

        // Then check FilenameFilter
        FilenameFilter filter = context.get(FilenameFilter.class);
        if (filter != null) {
            String name = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
            if (name != null) {
                return filter.accept(ABSTRACT_PATH, name);
            }
        }

        return true;
    }

    /**
     * Checks embedded document limits from ParseRecord.
     * <p>
     * If throwOnMaxDepth or throwOnMaxCount is configured and the respective limit is hit,
     * an EmbeddedLimitReachedException is thrown. Otherwise, returns false and sets the
     * appropriate limit flag on the ParseRecord.
     * <p>
     * Note: The count limit is a hard stop (once hit, no more embedded docs are parsed).
     * The depth limit only affects documents at that depth - sibling documents at
     * shallower depths will still be parsed.
     * <p>
     * Subclasses that override parseEmbedded() should call this method to enforce limits.
     *
     * @param parseRecord the parse record to check
     * @return true if the embedded document should be parsed, false if limits are exceeded
     * @throws EmbeddedLimitReachedException if a limit is exceeded and throwing is configured
     */
    protected boolean checkEmbeddedLimits(ParseRecord parseRecord) {
        // Count limit is a hard stop - once we've hit max, no more embedded parsing
        if (parseRecord.isEmbeddedCountLimitReached()) {
            return false;
        }
        int maxCount = parseRecord.getMaxEmbeddedCount();
        if (maxCount >= 0 && parseRecord.getEmbeddedCount() >= maxCount) {
            parseRecord.setEmbeddedCountLimitReached(true);
            if (parseRecord.isThrowOnMaxCount()) {
                throw new EmbeddedLimitReachedException(
                        EmbeddedLimitReachedException.LimitType.MAX_COUNT, maxCount);
            }
            return false;
        }

        // Depth limit only applies to current depth - siblings at shallower levels
        // can still be parsed. The flag is set for reporting purposes.
        // depth is 1-indexed (main doc is depth 1), so embedded depth limit of N
        // means we allow parsing up to depth N+1
        int maxDepth = parseRecord.getMaxEmbeddedDepth();
        if (maxDepth >= 0 && parseRecord.getDepth() > maxDepth + 1) {
            parseRecord.setEmbeddedDepthLimitReached(true);
            if (parseRecord.isThrowOnMaxDepth()) {
                throw new EmbeddedLimitReachedException(
                        EmbeddedLimitReachedException.LimitType.MAX_DEPTH, maxDepth);
            }
            return false;
        }
        return true;
    }

    @Override
    public void parseEmbedded(
            TikaInputStream tis, ContentHandler handler, Metadata metadata, ParseContext parseContext, boolean outputHtml)
            throws SAXException, IOException {
        // Check and enforce embedded limits even if caller didn't call shouldParseEmbedded()
        // This guarantees limits are enforced for all callers
        ParseRecord parseRecord = parseRecord();
        if (!checkEmbeddedLimits(parseRecord)) {
            return;
        }

        // Increment embedded count for tracking
        parseRecord.incrementEmbeddedCount();

        TaggedContentHandler taggedOutput =
                new TaggedContentHandler(handler);

        // Wrap the delegate's handler so we can close anything it left open if
        // it throws mid-element. Without this, the </div> emitted in finally
        // could land on top of an open <p>/<table>/etc. from the failed
        // sub-parse and produce malformed XHTML.
        XHTMLBalancingHandler balancer =
                outputHtml ? new XHTMLBalancingHandler(taggedOutput) : null;
        ContentHandler delegateHandler = outputHtml ? balancer : taggedOutput;

        // Tell the record we are INSIDE an embedded parse. beginDocument decides "is this a new
        // top-level document?" from the depth, which only CompositeParser maintains -- so a
        // container parser invoked directly, handing entries to an AutoDetectParser in the
        // context, delivered every entry at depth 0. Each sibling then reset the record and
        // discarded the embedded count accumulated so far, letting a configured maximum embedded
        // count be bypassed across siblings. This path exists only to parse an embedded document,
        // so the claim is unambiguous here, and it deliberately does not touch depth -- that is
        // what the embedded-DEPTH limit is measured against.
        parseRecord.enterEmbedded();

        // Use the delegate parser to parse this entry
        boolean parsedCleanly = false;
        Throwable primaryFailure = null;
        boolean downstreamOutputFailure = false;
        boolean packageEntryStarted = false;
        try {
            tis.setCloseShield();

            if (outputHtml) {
                AttributesImpl attributes = new AttributesImpl();
                attributes.addAttribute("", "class", "class", "CDATA", "package-entry");
                taggedOutput.startElement(XHTML, "div", "div", attributes);
                packageEntryStarted = true;
            }

            String name = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
            if (isWriteFileNameToContent() && name != null && name.length() > 0 && outputHtml) {
                taggedOutput.startElement(XHTML, "h1", "h1", new AttributesImpl());
                char[] chars = name.toCharArray();
                taggedOutput.characters(chars, 0, chars.length);
                taggedOutput.endElement(XHTML, "h1", "h1");
            }

            DELEGATING_PARSER.parse(tis,
                    new EmbeddedContentHandler(new BodyContentHandler(delegateHandler)),
                    metadata, context);
            Throwable swallowedOutputFailure =
                    taggedOutput.getSaxFailure();
            if (swallowedOutputFailure == null) {
                swallowedOutputFailure = taggedOutput.getUncheckedFailure();
            }
            if (swallowedOutputFailure != null) {
                primaryFailure = swallowedOutputFailure;
                downstreamOutputFailure = true;
                throwOutputFailure(swallowedOutputFailure);
            }
            parsedCleanly = true;
        } catch (EncryptedDocumentException ede) {
            Throwable outputFailure =
                    findOutputFailure(taggedOutput, ede);
            if (outputFailure != null) {
                primaryFailure = outputFailure;
                downstreamOutputFailure = true;
                throwOutputFailure(outputFailure);
            }
            primaryFailure = ede;
            recordException(ede, context);
        } catch (CorruptedFileException e) {
            Throwable outputFailure =
                    findOutputFailure(taggedOutput, e);
            if (outputFailure != null) {
                primaryFailure = outputFailure;
                downstreamOutputFailure = true;
                throwOutputFailure(outputFailure);
            }
            //necessary to stop the parse to avoid infinite loops
            //on corrupt sqlite3 files
            IOException ioFailure = new IOException(e);
            primaryFailure = ioFailure;
            throw ioFailure;
        } catch (TikaException e) {
            Throwable outputFailure =
                    findOutputFailure(taggedOutput, e);
            if (outputFailure != null) {
                primaryFailure = outputFailure;
                downstreamOutputFailure = true;
                throwOutputFailure(outputFailure);
            }
            primaryFailure = e;
            recordException(e, context);
        } catch (SAXException e) {
            Throwable outputFailure =
                    findOutputFailure(taggedOutput, e);
            if (outputFailure != null) {
                primaryFailure = outputFailure;
                downstreamOutputFailure = true;
                throwOutputFailure(outputFailure);
            }
            primaryFailure = e;
            throw e;
        } catch (IOException e) {
            Throwable outputFailure =
                    findOutputFailure(taggedOutput, e);
            if (outputFailure != null) {
                primaryFailure = outputFailure;
                downstreamOutputFailure = true;
                throwOutputFailure(outputFailure);
            }
            primaryFailure = e;
            throw e;
        } catch (SecurityException e) {
            Throwable outputFailure =
                    findOutputFailure(taggedOutput, e);
            if (outputFailure != null) {
                primaryFailure = outputFailure;
                downstreamOutputFailure = true;
                throwOutputFailure(outputFailure);
            }
            primaryFailure = e;
            downstreamOutputFailure = true;
            throw e;
        } catch (RuntimeException e) {
            Throwable outputFailure =
                    findOutputFailure(taggedOutput, e);
            if (outputFailure != null) {
                primaryFailure = outputFailure;
                downstreamOutputFailure = true;
                throwOutputFailure(outputFailure);
            }
            primaryFailure = e;
            throw e;
        } catch (Error e) {
            Throwable outputFailure =
                    findOutputFailure(taggedOutput, e);
            if (outputFailure != null) {
                primaryFailure = outputFailure;
                downstreamOutputFailure = true;
                throwOutputFailure(outputFailure);
            }
            primaryFailure = e;
            downstreamOutputFailure = true;
            throw e;
        } finally {
            // Release the embedded bracket first: everything below is output cleanup, and a
            // throw from it must not leave the record permanently looking like it is inside an
            // embedded parse -- that would suppress the reset for every later document on this
            // context, which is the bug this whole change exists to fix, inverted.
            parseRecord.exitEmbedded();
            tis.removeCloseShield();
            if (outputHtml && packageEntryStarted && !downstreamOutputFailure) {
                // Only an aborted parse can leave elements open; on a clean parse
                // the balancer stack is empty. Draining only on abort keeps the
                // package-entry div well-formed when the inner parse throws, while
                // letting StrictXHTMLValidator still catch genuine imbalances on the
                // happy path (TIKA-4728).
                if (!parsedCleanly) {
                    try {
                        balancer.drainOpenElements();
                    } catch (Throwable cleanupFailure) {
                        handleCleanupFailure(
                                taggedOutput, primaryFailure, cleanupFailure);
                    }
                }
                try {
                    taggedOutput.endElement(XHTML, "div", "div");
                } catch (Throwable cleanupFailure) {
                    handleCleanupFailure(
                            taggedOutput, primaryFailure, cleanupFailure);
                }
            }
        }
    }

    private static Throwable findOutputFailure(
            TaggedContentHandler taggedOutput, Throwable failure) {
        SAXException saxOutputFailure =
                findTaggedOutputFailure(taggedOutput, failure);
        if (saxOutputFailure != null) {
            return saxOutputFailure;
        }
        Throwable uncheckedOutputFailure =
                taggedOutput.findUncheckedCause(failure);
        if (uncheckedOutputFailure != null) {
            return uncheckedOutputFailure;
        }
        if (failure instanceof Error) {
            return null;
        }
        SAXException swallowedSaxFailure = taggedOutput.getSaxFailure();
        if (swallowedSaxFailure != null) {
            return swallowedSaxFailure;
        }
        return taggedOutput.getUncheckedFailure();
    }

    private static SAXException findTaggedOutputFailure(
            TaggedContentHandler taggedOutput, Throwable failure) {
        if (taggedOutput == null || failure == null) {
            return null;
        }
        Set<Throwable> seen =
                Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        SAXException recordedOutputFailure = taggedOutput.getSaxFailure();
        SAXException taggedOutputCandidate = null;
        pending.push(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.pop();
            if (!seen.add(current)) {
                continue;
            }
            if (current == recordedOutputFailure) {
                return recordedOutputFailure;
            }
            if (current instanceof SAXException saxFailure
                    && taggedOutput.isCauseOf(saxFailure)) {
                try {
                    taggedOutput.throwIfCauseOf(saxFailure);
                } catch (SAXException outputFailure) {
                    if (taggedOutputCandidate == null) {
                        taggedOutputCandidate = outputFailure;
                    }
                }
            }
            Throwable cause = current.getCause();
            if (cause != null && cause != current) {
                pending.push(cause);
            }
            Throwable[] suppressed = current.getSuppressed();
            for (int i = suppressed.length - 1; i >= 0; i--) {
                Throwable candidate = suppressed[i];
                if (candidate != null && candidate != current) {
                    pending.push(candidate);
                }
            }
        }
        return taggedOutputCandidate;
    }

    private static void handleCleanupFailure(
            TaggedContentHandler taggedOutput, Throwable primaryFailure,
            Throwable cleanupFailure) throws SAXException {
        Throwable outputFailure =
                findOutputFailure(taggedOutput, cleanupFailure);
        if (outputFailure != null) {
            addSuppressed(outputFailure, primaryFailure);
            throwOutputFailure(outputFailure);
        }
        if (cleanupFailure instanceof Error) {
            addSuppressed(cleanupFailure, primaryFailure);
            throwOutputFailure(cleanupFailure);
        }
        if (primaryFailure == null) {
            throwOutputFailure(cleanupFailure);
        }
        addSuppressed(primaryFailure, cleanupFailure);
    }

    private static void throwOutputFailure(Throwable failure)
            throws SAXException {
        if (failure instanceof SAXException saxFailure) {
            throw saxFailure;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error errorFailure) {
            throw errorFailure;
        }
        throw new IllegalArgumentException(
                "Unexpected output failure", failure);
    }

    private static void addSuppressed(
            Throwable primaryFailure, Throwable suppressedFailure) {
        if (suppressedFailure != null
                && primaryFailure != suppressedFailure) {
            primaryFailure.addSuppressed(suppressedFailure);
        }
    }

    protected void recordException(Exception e, ParseContext context) {
        ParseRecord record = context.get(ParseRecord.class);
        if (record == null) {
            return;
        }
        record.addException(e);
    }

    public Parser getDelegatingParser() {
        return DELEGATING_PARSER;
    }

    /**
     * Returns whether to write file names to content based on {@link SAXOutputConfig}
     * in the ParseContext. Defaults to {@code true} if no config is present.
     *
     * @return true if file names should be written to content
     */
    public boolean isWriteFileNameToContent() {
        SAXOutputConfig config = context.get(SAXOutputConfig.class);
        return config == null || config.isWriteFileNameToContent();
    }
}
