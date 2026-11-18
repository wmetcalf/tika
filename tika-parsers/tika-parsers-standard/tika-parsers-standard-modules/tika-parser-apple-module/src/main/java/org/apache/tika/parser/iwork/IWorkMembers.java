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
import java.nio.file.Files;
import java.util.Enumeration;

import org.apache.commons.compress.archivers.zip.UnsupportedZipFeatureException;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
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

    /** Handles one zip member; the stream is the member's data and may be closed. */
    @FunctionalInterface
    public interface MemberHandler {
        void handle(ZipArchiveEntry entry, InputStream data) throws Exception;
    }

    /**
     * Walks every member of the package, whatever the input: the central directory of the
     * detector's open ZipFile, else of a ZipFile on the input's file (spooled if need be, as
     * ZipParser does); only when no central directory can be read, the local headers (with
     * STORED + data-descriptor entries readable). A local-header walk ends quietly at a stray
     * EOCD or central-header signature, so it is never the first choice.
     * <p>
     * Each member's failure is folded (see {@link #fold}); a member whose decoder is missing
     * from the classpath (a LinkageError, e.g. ZSTD without zstd-jni) is unreadable, not fatal.
     *
     * @return the first member failure to rethrow once the walk is done, or null
     */
    public static Exception walk(TikaInputStream tis, MemberHandler handler, Metadata container,
                                 TaggedContentHandler caller)
            throws IOException, SAXException {
        return walk(tis, handler, null, container, caller);
    }

    /**
     * As {@link #walk(TikaInputStream, MemberHandler, Metadata, TaggedContentHandler)};
     * {@code unreadable}, if given, is told about each member whose data cannot be opened at
     * all (encrypted or an unsupported method) before that failure is folded.
     */
    public static Exception walk(TikaInputStream tis, MemberHandler handler,
                                 java.util.function.Consumer<ZipArchiveEntry> unreadable,
                                 Metadata container, TaggedContentHandler caller)
            throws IOException, SAXException {
        ZipFile zipFile = null;
        ZipFile owned = null;
        Object open = tis.getOpenContainer();
        if (open instanceof ZipFile) {
            zipFile = (ZipFile) open;
        } else {
            try {
                owned = ZipFile.builder().setFile(tis.getFile()).get();
                zipFile = owned;
            } catch (IOException e) {
                // no readable central directory: fall back to the local headers below
            }
        }
        Exception first = null;
        try {
            if (zipFile != null) {
                Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
                while (entries.hasMoreElements()) {
                    ZipArchiveEntry entry = entries.nextElement();
                    try (InputStream data = zipFile.getInputStream(entry)) {
                        handler.handle(entry, data);
                    } catch (UnsupportedZipFeatureException e) {
                        if (unreadable != null) {
                            unreadable.accept(entry);
                        }
                        first = fold(first, e, entry.getName(), container, caller);
                    } catch (SecurityException e) {
                        throw e;
                    } catch (Exception | LinkageError e) {
                        first = fold(first, e, entry.getName(), container, caller);
                    }
                }
            } else {
                try (InputStream raw = Files.newInputStream(tis.getPath());
                        ZipArchiveInputStream zip =
                                new ZipArchiveInputStream(raw, "UTF8", true, true)) {
                    ZipArchiveEntry entry = zip.getNextEntry();
                    while (entry != null) {
                        try {
                            handler.handle(entry, CloseShieldInputStream.wrap(zip));
                        } catch (SecurityException e) {
                            throw e;
                        } catch (Exception | LinkageError e) {
                            first = fold(first, e, entry.getName(), container, caller);
                        }
                        entry = zip.getNextEntry();
                    }
                }
            }
        } finally {
            if (owned != null) {
                owned.close();
            }
        }
        return first;
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
    public static Exception fold(Exception first, Throwable failure, String name,
                                 Metadata container, TaggedContentHandler caller)
            throws SAXException {
        if (failure instanceof Exception) {
            caller.throwIfCauseOf((Exception) failure);
            WriteLimitReachedException.throwIfWriteLimitReached((Exception) failure);
        }
        int depth = 0;
        for (Throwable t = failure; t != null && depth < 64; t = t.getCause(), depth++) {
            if (t instanceof EmbeddedLimitReachedException) {
                throw (EmbeddedLimitReachedException) t;
            }
        }
        depth = 0;
        for (Throwable t = failure; t != null && depth < 64; t = t.getCause(), depth++) {
            if (t instanceof UnsupportedZipFeatureException || t instanceof LinkageError) {
                container.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                        "iWork member not extracted, unreadable: " + name + ": " + t);
                // the same signal ZipParser gives an unreadable (e.g. encrypted) member
                EmbeddedDocumentUtil.recordEmbeddedStreamException(t, container);
                return first;
            }
        }
        // every failed member is named, not only the first (which is rethrown at the end)
        container.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                "iWork member failed: " + name + ": " + failure);
        Exception asException = failure instanceof Exception ? (Exception) failure
                : new TikaException("iWork member failed: " + name, failure);
        if (first == null) {
            return asException;
        }
        first.addSuppressed(asException);
        return first;
    }
}
