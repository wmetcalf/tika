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
import java.util.Collections;
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
        } catch (IOException e) {
            try {
                tagged.throwIfCauseOf(e);
            } catch (IOException inputFailure) {
                primaryFailure = inputFailure;
                throw inputFailure;
            }
            SAXException outputFailure =
                    findTaggedOutputFailure(taggedOutput, e);
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
                    findTaggedOutputFailure(taggedOutput, e);
            if (outputFailure != null) {
                shouldCleanupAfterFailure = false;
                primaryFailure = outputFailure;
                throw outputFailure;
            }
            throw e;
        } catch (TikaException e) {
            SAXException outputFailure =
                    findTaggedOutputFailure(taggedOutput, e);
            if (outputFailure != null) {
                shouldCleanupAfterFailure = false;
                primaryFailure = outputFailure;
                throw outputFailure;
            }
            primaryFailure = e;
            throw e;
        } catch (RuntimeException e) {
            primaryFailure = e;
            shouldCleanupAfterFailure = false;
            throw e;
        } catch (Error e) {
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

    private static SAXException findTaggedOutputFailure(
            TaggedContentHandler taggedOutput, Throwable failure) {
        Set<Throwable> seen =
                Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && seen.add(current)) {
            if (current instanceof SAXException saxFailure
                    && taggedOutput.isCauseOf(saxFailure)) {
                try {
                    taggedOutput.throwIfCauseOf(saxFailure);
                } catch (SAXException outputFailure) {
                    return outputFailure;
                }
                return null;
            }
            Throwable cause = current.getCause();
            current = cause != current ? cause : null;
        }
        return null;
    }

    private static void cleanupAfterFailure(
            XHTMLBalancingHandler balancer,
            XHTMLContentHandler xhtml,
            TaggedContentHandler taggedOutput,
            Throwable primaryFailure) throws SAXException {
        try {
            balancer.drainOpenElements();
        } catch (Throwable cleanupFailure) {
            SAXException outputFailure =
                    findTaggedOutputFailure(taggedOutput, cleanupFailure);
            if (outputFailure != null) {
                addSuppressed(outputFailure, primaryFailure);
                throw outputFailure;
            }
            throwIfUncheckedCleanupFailure(
                    cleanupFailure, primaryFailure);
            addSuppressed(primaryFailure, cleanupFailure);
        }
        try {
            xhtml.endDocument();
        } catch (Throwable cleanupFailure) {
            SAXException outputFailure =
                    findTaggedOutputFailure(taggedOutput, cleanupFailure);
            if (outputFailure != null) {
                addSuppressed(outputFailure, primaryFailure);
                throw outputFailure;
            }
            throwIfUncheckedCleanupFailure(
                    cleanupFailure, primaryFailure);
            addSuppressed(primaryFailure, cleanupFailure);
        }
    }

    private static void throwIfUncheckedCleanupFailure(
            Throwable cleanupFailure, Throwable primaryFailure) {
        if (cleanupFailure instanceof RuntimeException runtimeFailure) {
            addSuppressed(runtimeFailure, primaryFailure);
            throw runtimeFailure;
        }
        if (cleanupFailure instanceof Error errorFailure) {
            addSuppressed(errorFailure, primaryFailure);
            throw errorFailure;
        }
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
        } catch (SAXException e) {
            taggedHandler.throwIfCauseOf(e);
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
