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
        Metadata embedded = approve(name, resourceType, context, extractor);
        if (embedded != null) {
            extractApproved(embedded, member, xhtml, context, extractor);
        }
    }

    /**
     * Asks the extractor whether it wants this member.
     *
     * @return the member's metadata to hand to {@link #extractApproved}, or null if declined
     */
    public static Metadata approve(String name, String resourceType, ParseContext context,
                                   EmbeddedDocumentExtractor extractor) {
        Metadata embedded = Metadata.newInstance(context);
        embedded.set(TikaCoreProperties.RESOURCE_NAME_KEY, name);
        if (resourceType != null) {
            embedded.set(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE, resourceType);
        }
        return extractor.shouldParseEmbedded(embedded) ? embedded : null;
    }

    /** Hands an approved member (see {@link #approve}) to the extractor; {@code member} is
     * not closed. */
    public static void extractApproved(Metadata embedded, InputStream member,
                                       XHTMLContentHandler xhtml, ParseContext context,
                                       EmbeddedDocumentExtractor extractor)
            throws IOException, SAXException, TikaException {
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
     * Walks every member of the package, whatever the input (spooled to a file if need be,
     * as ZipParser does), by every central-directory reader that accepts it: each member
     * commons-compress's ZipFile lists, then any member java.util.zip's ZipFile lists beyond
     * those (commons-compress stops at the first stray central-directory record, trusts the
     * last EOCD signature, and refuses a whole archive over one inconsistent record). If the
     * JDK refuses the archive, the local headers stand in for it (STORED + data-descriptor
     * entries readable). Members found beyond commons-compress's view, or a package no
     * central-directory reader accepts, are recorded as a warning. The detector's open
     * container is never used: a salvaged one silently drops unreadable and directory-named
     * members.
     * <p>
     * Each member's failure is folded (see {@link #fold}); a member whose decoder is missing
     * from the classpath (a LinkageError, e.g. ZSTD without zstd-jni) is unreadable, not fatal.
     *
     * @return the first member failure to rethrow once the walk is done, or null
     */
    public static Exception walk(TikaInputStream tis, MemberHandler handler, Metadata container,
                                 TaggedContentHandler caller)
            throws IOException, SAXException {
        return walk(tis, null, null, handler, null, null, null, container, caller);
    }

    /**
     * As {@link #walk(TikaInputStream, MemberHandler, Metadata, TaggedContentHandler)};
     * {@code seen}, if given, is told about every member (by name) before its data is opened,
     * so a member whose data cannot be read still counts for, e.g., type detection.
     */
    public static Exception walk(TikaInputStream tis,
                                 java.util.function.Consumer<ZipArchiveEntry> seen,
                                 MemberHandler handler, Metadata container,
                                 TaggedContentHandler caller)
            throws IOException, SAXException {
        return walk(tis, seen, null, handler, null, null, null, container, caller);
    }

    /**
     * As {@link #walk(TikaInputStream, MemberHandler, Metadata, TaggedContentHandler)};
     * {@code unreadable}, if given, is told about each member whose data cannot be opened at
     * all (encrypted or an unsupported method) before that failure is folded.
     */
    public static Exception walk(TikaInputStream tis,
                                 java.util.function.Consumer<ZipArchiveEntry> seen,
                                 MemberHandler handler,
                                 java.util.function.Consumer<ZipArchiveEntry> unreadable,
                                 Metadata container, TaggedContentHandler caller)
            throws IOException, SAXException {
        return walk(tis, seen, null, handler, unreadable, null, null, container, caller);
    }

    /**
     * The full walk. {@code want}, if given, picks the members whose data is opened and
     * handed to {@code handler} (the others are only {@code seen}); {@code failed}, if given,
     * is told about each member whose failure was folded, and whether its data had opened
     * (false: unreadable, a later walk can leave it alone); a member {@code quiet} accepts is
     * walked but its failure is not folded again (an earlier walk already named it).
     */
    public static Exception walk(TikaInputStream tis,
                                 java.util.function.Consumer<ZipArchiveEntry> seen,
                                 java.util.function.Predicate<ZipArchiveEntry> want,
                                 MemberHandler handler,
                                 java.util.function.Consumer<ZipArchiveEntry> unreadable,
                                 java.util.function.BiConsumer<ZipArchiveEntry, Boolean> failed,
                                 java.util.function.Predicate<ZipArchiveEntry> quiet,
                                 Metadata container, TaggedContentHandler caller)
            throws IOException, SAXException {
        Hooks hooks = new Hooks(seen, want, unreadable, failed, quiet);
        Exception[] first = {null};
        java.nio.file.Path file = tis.getPath();
        // Open the channel here so a refused open cannot leak it (commons-compress closes only
        // channels it was handed).
        java.nio.channels.SeekableByteChannel channel = Files.newByteChannel(file);
        ZipFile commons = null;
        try {
            commons = ZipFile.builder().setSeekableByteChannel(channel).get();
        } catch (IOException e) {
            channel.close();
        }
        java.util.zip.ZipFile jdk = null;
        try {
            jdk = new java.util.zip.ZipFile(file.toFile());
        } catch (IOException e) {
            // refused; see below
        }
        // Members each reader listed are walked once: everything commons-compress lists, then
        // whatever the JDK's reader (or, if it refuses, the local headers) has beyond that.
        // commons-compress stops reading the central directory at the first stray record and
        // trusts the last EOCD signature, so its view alone can silently miss members. Members
        // are matched by name AND data identity (CRC, sizes): two central directories may list
        // the same names for different data.
        java.util.Map<String, Integer> walked = new java.util.HashMap<>();
        java.util.Map<String, Integer> walkedNames = new java.util.HashMap<>();
        long[] commonsCount = {0};
        boolean[] extra = {false};
        try {
            if (commons != null) {
                ZipFile zipFile = commons;
                Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
                while (entries.hasMoreElements()) {
                    ZipArchiveEntry entry = entries.nextElement();
                    commonsCount[0]++;
                    walked.merge(identity(entry.getName(), entry.getCrc(),
                            entry.getCompressedSize(), entry.getSize()), 1, Integer::sum);
                    walkedNames.merge(entry.getName(), 1, Integer::sum);
                    member(entry, () -> zipFile.getInputStream(entry), hooks, handler,
                            first, container, caller);
                }
            }
            if (jdk != null) {
                // matched against its own copy of commons-compress's view; members only the JDK
                // lists join that view, so a later local-header pass will not walk them again
                java.util.Map<String, Integer> jdkBudget = new java.util.HashMap<>(walked);
                java.util.zip.ZipFile zipFile = jdk;
                Enumeration<? extends java.util.zip.ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    java.util.zip.ZipEntry jdkEntry = entries.nextElement();
                    String id = identity(jdkEntry.getName(), jdkEntry.getCrc(),
                            jdkEntry.getCompressedSize(), jdkEntry.getSize());
                    if (alreadyWalked(jdkBudget, id)) {
                        continue;
                    }
                    extra[0] = true;
                    walked.merge(id, 1, Integer::sum);
                    walkedNames.merge(jdkEntry.getName(), 1, Integer::sum);
                    member(new ZipArchiveEntry(jdkEntry.getName()),
                            () -> zipFile.getInputStream(jdkEntry), hooks, handler,
                            first, container, caller);
                }
            }
            // The JDK often refuses legitimate packages (bzip2 members, commons-compress zip64
            // output, password-protected iWork '09), and two readers can share a forged view.
            // So the local headers are consulted only on evidence: no central directory either
            // reader accepts, more than one plausible end-of-central-directory record (a shadow
            // directory), or commons-compress reading fewer records than its EOCD declares (it
            // stops at the first stray record). Every such walk is recorded.
            long declared = commons == null ? -1 : declaredEntries(file);
            boolean shortView = commons != null
                    && (declared == -2 || declared >= 0 && declared != commonsCount[0]);
            if (commons == null && jdk == null) {
                warnOnce(container,
                        "iWork package central directory unreadable: members were walked "
                                + "by local header and some may be missing");
            } else if (shortView) {
                warnOnce(container,
                        declared == -2
                                ? "iWork package central directory is inconsistent: "
                                        + "several end-of-central-directory records; "
                                        + "members were sought by local header"
                                : "iWork package central directory is inconsistent: it "
                                        + "declares " + declared + " entries, "
                                        + "commons-compress read " + commonsCount[0]
                                        + "; the rest were sought by local header");
            }
            if (commons == null && jdk == null || shortView) {
                walkLocalHeaders(file, walked, walkedNames, extra, hooks, handler, first,
                        container, caller);
            }
            if (extra[0] && commons != null) {
                warnOnce(container,
                        "iWork package central directory is inconsistent: members missing from "
                                + "commons-compress's view were walked from another reader");
            }
        } finally {
            if (commons != null) {
                commons.close();
            }
            if (jdk != null) {
                jdk.close();
            }
        }
        return first[0];
    }

    private static void walkLocalHeaders(java.nio.file.Path file,
                                         java.util.Map<String, Integer> walked,
                                         java.util.Map<String, Integer> walkedNames,
                                         boolean[] extra, Hooks hooks, MemberHandler handler,
                                         Exception[] first, Metadata container,
                                         TaggedContentHandler caller)
            throws IOException, SAXException {
        try (InputStream raw = Files.newInputStream(file);
                ZipArchiveInputStream zip = new ZipArchiveInputStream(raw, "UTF8", true, true)) {
            while (true) {
                ZipArchiveEntry entry;
                try {
                    entry = zip.getNextEntry();
                } catch (IOException | LinkageError e) {
                    // a damaged tail ("Truncated ZIP file") ends the walk, not the parse
                    warnOnce(container, "iWork package local-header walk ended early: " + e);
                    break;
                }
                if (entry == null) {
                    break;
                }
                boolean known = entry.getCrc() >= 0 && entry.getSize() >= 0
                        && entry.getCompressedSize() >= 0;
                // one listing covers one local entry: spend the name budget either way, so a
                // same-named data-descriptor entry is not covered a second time
                boolean covered = known
                        ? alreadyWalked(walked, identity(entry.getName(), entry.getCrc(),
                                entry.getCompressedSize(), entry.getSize()))
                                && alreadyWalked(walkedNames, entry.getName())
                        : alreadyWalked(walkedNames, entry.getName());
                if (covered) {
                    continue;
                }
                extra[0] = true;
                member(entry, () -> CloseShieldInputStream.wrap(zip), hooks, handler, first,
                        container, caller);
            }
        }
    }

    /**
     * The total entry count the archive's last EOCD record declares (the record
     * commons-compress uses), -1 if there is none or it defers to zip64, or -2 if more than
     * one plausible EOCD record is present (a shadow central directory: readers that choose
     * differently see different members).
     */
    private static long declaredEntries(java.nio.file.Path file) throws IOException {
        try (java.nio.channels.SeekableByteChannel ch = Files.newByteChannel(file)) {
            long size = ch.size();
            int tail = (int) Math.min(size, 22 + 65535);
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(tail);
            ch.position(size - tail);
            while (buf.hasRemaining() && ch.read(buf) >= 0) {
                // fill
            }
            byte[] b = buf.array();
            long base = size - tail;
            long last = -1;
            int plausible = 0;
            for (int i = tail - 22; i >= 0; i--) {
                if (b[i] == 'P' && b[i + 1] == 'K' && b[i + 2] == 5 && b[i + 3] == 6) {
                    long cdSize = le32(b, i + 12);
                    long cdOffset = le32(b, i + 16);
                    if (cdOffset + cdSize <= base + i) {
                        plausible++;
                        if (last < 0 && plausible == 1) {
                            int total = (b[i + 10] & 0xff) | (b[i + 11] & 0xff) << 8;
                            last = total == 0xffff ? -1 : total;
                        }
                    }
                }
            }
            return plausible > 1 ? -2 : last;
        }
    }

    private static long le32(byte[] b, int i) {
        return (b[i] & 0xffL) | (b[i + 1] & 0xffL) << 8 | (b[i + 2] & 0xffL) << 16
                | (b[i + 3] & 0xffL) << 24;
    }

    private static Exception foldUnlessQuiet(Exception first, Throwable failure,
                                             ZipArchiveEntry entry, Hooks hooks,
                                             Metadata container, TaggedContentHandler caller)
            throws SAXException {
        if (hooks.quiet() != null && hooks.quiet().test(entry)) {
            // already named by an earlier walk; the caller's own failures still propagate
            if (failure instanceof Exception) {
                caller.throwIfCauseOf((Exception) failure);
                WriteLimitReachedException.throwIfWriteLimitReached((Exception) failure);
            }
            return first;
        }
        return fold(first, failure, entry.getName(), container, caller);
    }

    /** Combines two walks' failures: the first, with the second suppressed onto it. */
    public static Exception firstOf(Exception first, Exception second) {
        if (first == null) {
            return second;
        }
        if (second != null && second != first) {
            first.addSuppressed(second);
        }
        return first;
    }

    /** A package walked twice reports each package-level problem once. */
    private static boolean warnOnce(Metadata container, String warning) {
        for (String existing : container.getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING)) {
            if (existing.equals(warning)) {
                return false;
            }
        }
        container.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING, warning);
        return true;
    }

    private static String identity(String name, long crc, long compressedSize, long size) {
        return name + '\u0000' + crc + '/' + compressedSize + '/' + size;
    }

    /** Consumes one listing of {@code name} from {@code walked}; false if none was left. */
    private static boolean alreadyWalked(java.util.Map<String, Integer> walked, String name) {
        Integer left = walked.get(name);
        if (left == null || left == 0) {
            return false;
        }
        walked.put(name, left - 1);
        return true;
    }

    @FunctionalInterface
    private interface Opener {
        InputStream open() throws IOException;
    }

    private record Hooks(java.util.function.Consumer<ZipArchiveEntry> seen,
                         java.util.function.Predicate<ZipArchiveEntry> want,
                         java.util.function.Consumer<ZipArchiveEntry> unreadable,
                         java.util.function.BiConsumer<ZipArchiveEntry, Boolean> failed,
                         java.util.function.Predicate<ZipArchiveEntry> quiet) {
    }

    private static void member(ZipArchiveEntry entry, Opener opener, Hooks hooks,
                               MemberHandler handler, Exception[] first, Metadata container,
                               TaggedContentHandler caller) throws SAXException {
        if (hooks.seen() != null) {
            hooks.seen().accept(entry);
        }
        if (hooks.want() != null && !hooks.want().test(entry)) {
            return;
        }
        // Only a member whose data cannot be opened is reported to `failed`: one whose data
        // opened but whose handling failed (a malformed plist, document XML) still has bytes a
        // later walk must hand on.
        final InputStream opened;
        try {
            opened = opener.open();
        } catch (UnsupportedZipFeatureException e) {
            if (hooks.unreadable() != null) {
                hooks.unreadable().accept(entry);
            }
            first[0] = fold(first[0], e, entry.getName(), container, caller);
            if (hooks.failed() != null) {
                hooks.failed().accept(entry, false);
            }
            return;
        } catch (SecurityException e) {
            throw e;
        } catch (Exception | LinkageError e) {
            first[0] = fold(first[0], e, entry.getName(), container, caller);
            if (hooks.failed() != null) {
                hooks.failed().accept(entry, false);
            }
            return;
        }
        try {
            handler.handle(entry, opened);
        } catch (UnsupportedZipFeatureException e) {
            // e.g. the local-header route reports an encrypted entry only when it is read
            if (hooks.unreadable() != null) {
                hooks.unreadable().accept(entry);
            }
            first[0] = foldUnlessQuiet(first[0], e, entry, hooks, container, caller);
            if (hooks.failed() != null) {
                hooks.failed().accept(entry, false);
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception | LinkageError e) {
            first[0] = foldUnlessQuiet(first[0], e, entry, hooks, container, caller);
            if (hooks.failed() != null) {
                hooks.failed().accept(entry, true);
            }
        } finally {
            try {
                opened.close();
            } catch (IOException e) {
                // done with the member either way
            }
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
    public static Exception fold(Exception first, Throwable failure, String name,
                                 Metadata container, TaggedContentHandler caller)
            throws SAXException {
        if (failure instanceof Exception) {
            caller.throwIfCauseOf((Exception) failure);
            WriteLimitReachedException.throwIfWriteLimitReached((Exception) failure);
        }
        // an Error the caller's own handler threw (e.g. a LinkageError) is the caller's too
        Throwable callers = caller.findUncheckedCause(failure);
        if (callers instanceof Error) {
            throw (Error) callers;
        }
        if (callers instanceof RuntimeException) {
            throw (RuntimeException) callers;
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
