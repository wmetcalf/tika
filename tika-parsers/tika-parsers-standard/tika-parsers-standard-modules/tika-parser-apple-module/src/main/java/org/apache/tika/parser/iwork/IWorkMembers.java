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
package org.apache.tika.parser.iwork;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;

import org.apache.commons.compress.archivers.zip.UnsupportedZipFeatureException;
import org.apache.commons.io.input.CloseShieldInputStream;
import org.xml.sax.SAXException;

import org.apache.tika.exception.EmbeddedLimitReachedException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.EmbeddedDocumentUtil;
import org.apache.tika.io.TemporaryResources;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.TaggedContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;

/**
 * Hands an iWork package member to the embedded document extractor.
 * <p>
 * A single marker entry is enough to route an arbitrary zip to one of the iWork
 * package parsers, so those parsers must not drop the members they do not
 * understand: every member they do not consume as package metadata goes through
 * here, whatever its name.
 */
public final class IWorkMembers {

    private IWorkMembers() {
    }

    /**
     * @param name         the member's path inside the package
     * @param member       the member's bytes; it is not closed
     * @param resourceType an {@link TikaCoreProperties.EmbeddedResourceType} name, or null
     */
    public static void extract(String name, InputStream member, String resourceType,
                               XHTMLContentHandler xhtml, ParseContext context,
                               EmbeddedDocumentExtractor extractor)
            throws IOException, SAXException, TikaException {
        Metadata embedded = Metadata.newInstance(context);
        embedded.set(TikaCoreProperties.RESOURCE_NAME_KEY, name);
        if (resourceType != null) {
            embedded.set(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE, resourceType);
        }
        if (!extractor.shouldParseEmbedded(embedded)) {
            return;
        }
        try (TemporaryResources tmp = new TemporaryResources();
                TikaInputStream tis = TikaInputStream.get(CloseShieldInputStream.wrap(member),
                        tmp, embedded)) {
            extractor.parseEmbedded(tis, xhtml, embedded, context, true);
        }
    }

    /**
     * As {@link #extract}, for a zip entry. A directory entry is skipped only when it really
     * is empty: its name alone (a trailing '/') does not stop data it carries from being
     * handed on.
     */
    public static void extractEntry(String name, boolean directory, InputStream member,
                                    String resourceType, XHTMLContentHandler xhtml,
                                    ParseContext context, EmbeddedDocumentExtractor extractor)
            throws IOException, SAXException, TikaException {
        if (directory) {
            PushbackInputStream peek = new PushbackInputStream(member, 1);
            int first = peek.read();
            if (first < 0) {
                return;
            }
            peek.unread(first);
            member = peek;
        }
        extract(name, member, resourceType, xhtml, context, extractor);
    }

    /**
     * Folds one member's failure into the parse so one bad member cannot hide the ones after
     * it: a member that cannot be read at all (an encrypted or unsupported zip entry) is
     * recorded as a warning on the container; any other member failure is kept (the first
     * one) and rethrown once every other member has been extracted.
     * <p>
     * Failures that are not about the member stop the walk at once, unwrapped: anything the
     * caller's own content handler threw (a write limit, an output error), and an embedded
     * document limit.
     *
     * @param caller the caller's handler, as wrapped by the parser's XHTML output
     * @return the failure to rethrow at the end, or null
     */
    public static Exception fold(Exception first, Exception failure, String name,
                                 Metadata container, TaggedContentHandler caller)
            throws SAXException {
        caller.throwIfCauseOf(failure);
        WriteLimitReachedException.throwIfWriteLimitReached(failure);
        int depth = 0;
        for (Throwable t = failure; t != null && depth < 64; t = t.getCause(), depth++) {
            if (t instanceof EmbeddedLimitReachedException) {
                throw (EmbeddedLimitReachedException) t;
            }
        }
        depth = 0;
        for (Throwable t = failure; t != null && depth < 64; t = t.getCause(), depth++) {
            if (t instanceof UnsupportedZipFeatureException) {
                container.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                        "iWork member not extracted, unreadable: " + name + ": "
                                + t.getMessage());
                // the same signal ZipParser gives an unreadable (e.g. encrypted) member
                EmbeddedDocumentUtil.recordEmbeddedStreamException(t, container);
                return first;
            }
        }
        return first == null ? failure : first;
    }
}
