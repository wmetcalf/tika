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
package org.apache.tika.parser.microsoft.rtf;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

import org.apache.commons.io.input.TaggedInputStream;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.annotation.TikaComponent;
import org.apache.tika.config.ConfigDeserializer;
import org.apache.tika.config.JsonConfig;
import org.apache.tika.exception.TikaConfigException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.TaggedContentHandler;
import org.apache.tika.sax.XHTMLBalancingHandler;
import org.apache.tika.sax.XHTMLContentHandler;

/**
 * RTF parser
 */
@TikaComponent
public class RTFParser implements Parser {

    /**
     * Serial version UID
     */
    private static final long serialVersionUID = -4165069489372320313L;

    private static final Set<MediaType> SUPPORTED_TYPES =
            Collections.singleton(MediaType.application("rtf"));
    /**
     * maximum number of bytes per embedded object/pict (default: 20MB)
     */
    private static int EMB_OBJ_MAX_BYTES = 20 * 1024 * 1024; //20MB
    //get rid of this once we get rid of the other static maxbytes...
    private static volatile boolean USE_STATIC = false;

    /**
     * Configuration class for JSON deserialization.
     */
    public static class Config {
        public int memoryLimitInKb = EMB_OBJ_MAX_BYTES / 1024;
        public boolean ignoreListMarkup = false;
    }

    private int memoryLimitInKb = EMB_OBJ_MAX_BYTES / 1024;
    private boolean ignoreListMarkup = false;

    public RTFParser() {
    }

    public RTFParser(Config config) {
        this.memoryLimitInKb = config.memoryLimitInKb;
        this.ignoreListMarkup = config.ignoreListMarkup;
    }

    public RTFParser(JsonConfig jsonConfig) throws TikaConfigException {
        this(ConfigDeserializer.buildConfig(jsonConfig, Config.class));
    }

    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED_TYPES;
    }

    public void parse(TikaInputStream tis, ContentHandler handler, Metadata metadata,
                      ParseContext context) throws IOException, SAXException, TikaException {
        metadata.set(Metadata.CONTENT_TYPE, "application/rtf");
        TaggedInputStream tagged = new TaggedInputStream(tis);
        TaggedContentHandler taggedOutput = new TaggedContentHandler(handler);
        XHTMLContentHandler xhtml =
                new XHTMLContentHandler(taggedOutput, metadata, context);
        // Wrap xhtml in a balancing handler so the finally's endDocument
        // doesn't fire on an unbalanced stack if the RTF state machine
        // emits inconsistent SAX events (e.g., </b> while <p> is topmost
        // due to state-vs-stack drift on certain corpus files). Without
        // this, StrictXHTMLValidator's </body> vs <p>/<b> at endDocument
        // masks the real well-formedness error from inside extract().
        XHTMLBalancingHandler balancer = new XHTMLBalancingHandler(xhtml);
        Throwable primaryFailure = null;
        boolean shouldCleanupAfterFailure = true;
        try {
            xhtml.startDocument();
            parseInline(tis, balancer, metadata, context);
            SAXException swallowedSaxFailure =
                    taggedOutput.getSaxFailure();
            if (swallowedSaxFailure != null) {
                shouldCleanupAfterFailure = false;
                primaryFailure = swallowedSaxFailure;
                throw swallowedSaxFailure;
            }
            Throwable swallowedOutputFailure =
                    taggedOutput.getUncheckedFailure();
            if (swallowedOutputFailure != null) {
                shouldCleanupAfterFailure = false;
                primaryFailure = swallowedOutputFailure;
                throwUnchecked(swallowedOutputFailure);
            }
        } catch (IOException e) {
            Throwable uncheckedOutputFailure =
                    findUncheckedOutputFailure(taggedOutput, e);
            if (uncheckedOutputFailure != null) {
                shouldCleanupAfterFailure = false;
                primaryFailure = uncheckedOutputFailure;
                throwUnchecked(uncheckedOutputFailure);
            }
            try {
                tagged.throwIfCauseOf(e);
            } catch (IOException inputFailure) {
                primaryFailure = inputFailure;
                throw inputFailure;
            }
            SAXException outputFailure =
                    findRecoverableTaggedOutputFailure(
                            taggedOutput, e);
            if (outputFailure != null) {
                shouldCleanupAfterFailure = false;
                primaryFailure = outputFailure;
                throw outputFailure;
            }
            TikaException parseFailure =
                    new TikaException("Error parsing an RTF document", e);
            primaryFailure = parseFailure;
            throw parseFailure;
        } catch (SAXException e) {
            primaryFailure = e;
            SAXException outputFailure =
                    findRecoverableTaggedOutputFailure(
                            taggedOutput, e);
            if (outputFailure != null) {
                shouldCleanupAfterFailure = false;
                primaryFailure = outputFailure;
                throw outputFailure;
            }
            Throwable uncheckedOutputFailure =
                    findUncheckedOutputFailure(taggedOutput, e);
            if (uncheckedOutputFailure != null) {
                shouldCleanupAfterFailure = false;
                primaryFailure = uncheckedOutputFailure;
                throwUnchecked(uncheckedOutputFailure);
            }
            throw e;
        } catch (TikaException e) {
            SAXException outputFailure =
                    findRecoverableTaggedOutputFailure(
                            taggedOutput, e);
            if (outputFailure != null) {
                shouldCleanupAfterFailure = false;
                primaryFailure = outputFailure;
                throw outputFailure;
            }
            Throwable uncheckedOutputFailure =
                    findUncheckedOutputFailure(taggedOutput, e);
            if (uncheckedOutputFailure != null) {
                shouldCleanupAfterFailure = false;
                primaryFailure = uncheckedOutputFailure;
                throwUnchecked(uncheckedOutputFailure);
            }
            primaryFailure = e;
            throw e;
        } catch (RuntimeException e) {
            SAXException taggedOutputFailure =
                    findRecoverableTaggedOutputFailure(
                            taggedOutput, e);
            if (taggedOutputFailure != null) {
                shouldCleanupAfterFailure = false;
                primaryFailure = taggedOutputFailure;
                throw taggedOutputFailure;
            }
            Throwable uncheckedOutputFailure =
                    findUncheckedOutputFailure(taggedOutput, e);
            if (uncheckedOutputFailure != null) {
                shouldCleanupAfterFailure = false;
                primaryFailure = uncheckedOutputFailure;
                throwUnchecked(uncheckedOutputFailure);
            }
            primaryFailure = e;
            throw e;
        } catch (Error e) {
            SAXException taggedOutputFailure =
                    findTaggedOutputFailure(taggedOutput, e);
            if (taggedOutputFailure != null) {
                shouldCleanupAfterFailure = false;
                primaryFailure = taggedOutputFailure;
                throw taggedOutputFailure;
            }
            Throwable uncheckedOutputFailure =
                    taggedOutput.findUncheckedCause(e);
            if (uncheckedOutputFailure != null) {
                shouldCleanupAfterFailure = false;
                primaryFailure = uncheckedOutputFailure;
                throwUnchecked(uncheckedOutputFailure);
            }
            primaryFailure = e;
            shouldCleanupAfterFailure = false;
            throw e;
        } finally {
            if (primaryFailure == null) {
                try {
                    xhtml.endDocument();
                } catch (SAXException endDocumentFailure) {
                    SAXException outputFailure =
                            findTaggedOutputFailure(
                                    taggedOutput, endDocumentFailure);
                    if (outputFailure != null) {
                        throw outputFailure;
                    }
                    throw endDocumentFailure;
                }
            } else if (shouldCleanupAfterFailure) {
                cleanupAfterFailure(
                        balancer, xhtml, taggedOutput, primaryFailure);
            }
        }
    }

    static SAXException findTaggedOutputFailure(
            TaggedContentHandler taggedOutput, Throwable failure) {
        SAXException recordedFailure = taggedOutput.getSaxFailure();
        Set<Throwable> seen =
                Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        if (failure != null) {
            pending.push(failure);
        }
        SAXException taggedCandidate = null;
        while (!pending.isEmpty()) {
            Throwable current = pending.pop();
            if (!seen.add(current)) {
                continue;
            }
            if (current == recordedFailure) {
                return recordedFailure;
            }
            if (taggedCandidate == null
                    && current instanceof SAXException saxFailure
                    && taggedOutput.isCauseOf(saxFailure)) {
                try {
                    taggedOutput.throwIfCauseOf(saxFailure);
                } catch (SAXException outputFailure) {
                    taggedCandidate = outputFailure;
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
        return taggedCandidate;
    }

    private static SAXException findRecoverableTaggedOutputFailure(
            TaggedContentHandler taggedOutput, Throwable failure) {
        SAXException outputFailure =
                findTaggedOutputFailure(taggedOutput, failure);
        return outputFailure != null
                ? outputFailure : taggedOutput.getSaxFailure();
    }

    private static Throwable findUncheckedOutputFailure(
            TaggedContentHandler taggedOutput, Throwable failure) {
        Throwable outputFailure =
                taggedOutput.findUncheckedCause(failure);
        return outputFailure != null
                ? outputFailure : taggedOutput.getUncheckedFailure();
    }

    private static void cleanupAfterFailure(
            XHTMLBalancingHandler balancer,
            XHTMLContentHandler xhtml,
            TaggedContentHandler taggedOutput,
            Throwable primaryFailure) throws SAXException {
        try {
            balancer.drainOpenElements();
        } catch (Throwable cleanupFailure) {
            handleCleanupFailure(
                    taggedOutput, primaryFailure, cleanupFailure);
        }
        try {
            xhtml.endDocument();
        } catch (Throwable cleanupFailure) {
            handleCleanupFailure(
                    taggedOutput, primaryFailure, cleanupFailure);
        }
    }

    static void handleCleanupFailure(
            TaggedContentHandler taggedOutput, Throwable primaryFailure,
            Throwable cleanupFailure) throws SAXException {
        SAXException outputFailure =
                findTaggedOutputFailure(taggedOutput, cleanupFailure);
        if (outputFailure != null) {
            addSuppressed(outputFailure, primaryFailure);
            throw outputFailure;
        }
        Throwable uncheckedOutputFailure =
                cleanupFailure instanceof Error
                        ? taggedOutput.findUncheckedCause(cleanupFailure)
                        : findUncheckedOutputFailure(
                                taggedOutput, cleanupFailure);
        if (uncheckedOutputFailure != null) {
            addSuppressed(uncheckedOutputFailure, primaryFailure);
            throwUnchecked(uncheckedOutputFailure);
        }
        if (cleanupFailure instanceof Error fatalCleanupFailure) {
            addSuppressed(fatalCleanupFailure, primaryFailure);
            throw fatalCleanupFailure;
        }
        addSuppressed(primaryFailure, cleanupFailure);
    }

    static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error errorFailure) {
            throw errorFailure;
        }
        throw new IllegalArgumentException(
                "Expected an unchecked output failure", failure);
    }

    private static void addSuppressed(
            Throwable primaryFailure, Throwable cleanupFailure) {
        if (cleanupFailure != primaryFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
        }
    }

    /**
     * This bypasses wrapping the handler for inline parsing (in at least the OutlookExtractor).
     *
     * @param is
     * @param handler
     * @param metadata
     * @param context
     * @throws TikaException
     * @throws IOException
     * @throws SAXException
     */
    public void parseInline(InputStream is, ContentHandler handler, Metadata metadata, ParseContext context)
            throws TikaException, IOException, SAXException {
        TaggedContentHandler taggedHandler = new TaggedContentHandler(handler);
        RTFEmbObjHandler embObjHandler =
                new RTFEmbObjHandler(taggedHandler, metadata, context, getMemoryLimitInKb());
        final TextExtractor ert =
                new TextExtractor(taggedHandler, metadata, embObjHandler, context);
        ert.setIgnoreListMarkup(ignoreListMarkup);
        try {
            ert.extract(is);
            SAXException saxOutputFailure =
                    taggedHandler.getSaxFailure();
            if (saxOutputFailure != null) {
                throw saxOutputFailure;
            }
            Throwable uncheckedOutputFailure =
                    taggedHandler.getUncheckedFailure();
            if (uncheckedOutputFailure != null) {
                throwUnchecked(uncheckedOutputFailure);
            }
        } catch (SAXException e) {
            SAXException outputFailure =
                    findTaggedOutputFailure(taggedHandler, e);
            if (outputFailure != null) {
                throw outputFailure;
            }
            Throwable uncheckedOutputFailure =
                    findUncheckedOutputFailure(taggedHandler, e);
            if (uncheckedOutputFailure != null) {
                throwUnchecked(uncheckedOutputFailure);
            }
            throw e;
        }
    }

    private int getMemoryLimitInKb() {
        //there's a race condition here, but it shouldn't matter.
        if (USE_STATIC) {
            if (EMB_OBJ_MAX_BYTES < 0) {
                return EMB_OBJ_MAX_BYTES;
            }
            return EMB_OBJ_MAX_BYTES / 1024;
        }
        return memoryLimitInKb;
    }
}
