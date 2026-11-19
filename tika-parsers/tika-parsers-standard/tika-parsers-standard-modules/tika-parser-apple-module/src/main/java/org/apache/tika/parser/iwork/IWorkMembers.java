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
        boolean[] extra = {false};
        // where commons-compress's members live: their local headers and their data
        java.util.Set<Long> knownHeaders = new java.util.HashSet<>();
        java.util.List<Extent> extents = new java.util.ArrayList<>();
        // members only the JDK lists, by name: a stray with the same name and the same bytes is
        // the same member (digests are computed only then, lazily)
        java.util.Map<String, java.util.List<java.util.zip.ZipEntry>> jdkExtras =
                new java.util.HashMap<>();
        java.util.Map<java.util.zip.ZipEntry, String> jdkDigests = new java.util.IdentityHashMap<>();
        boolean[] sizeMismatch = {false};
        try {
            if (commons != null) {
                ZipFile zipFile = commons;
                Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
                while (entries.hasMoreElements()) {
                    ZipArchiveEntry entry = entries.nextElement();
                    knownHeaders.add(entry.getLocalHeaderOffset());
                    Extent extent = Extent.of(file, zipFile, entry);
                    if (extent != null) {
                        sizeMismatch[0] |= extent.mismatch;
                        extents.add(extent);
                    }
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
                // java.util.zip resolves getInputStream(entry) by name once the enumeration has
                // moved on, so a name the JDK lists more than once is digested right here, while
                // the entry is the current one; unique names are digested lazily, by name
                java.util.Map<String, Integer> jdkNames = new java.util.HashMap<>();
                for (Enumeration<? extends java.util.zip.ZipEntry> e = zipFile.entries();
                        e.hasMoreElements(); ) {
                    jdkNames.merge(e.nextElement().getName(), 1, Integer::sum);
                }
                Enumeration<? extends java.util.zip.ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    java.util.zip.ZipEntry jdkEntry = entries.nextElement();
                    String id = identity(jdkEntry.getName(), jdkEntry.getCrc(),
                            jdkEntry.getCompressedSize(), jdkEntry.getSize());
                    boolean ambiguousName = jdkNames.get(jdkEntry.getName()) > 1;
                    if (alreadyWalked(jdkBudget, id)) {
                        continue;
                    }
                    extra[0] = true;
                    walked.merge(id, 1, Integer::sum);
                    walkedNames.merge(jdkEntry.getName(), 1, Integer::sum);
                    jdkExtras.computeIfAbsent(jdkEntry.getName(), k -> new java.util.ArrayList<>())
                            .add(jdkEntry);
                    if (ambiguousName) {
                        jdkDigests.put(jdkEntry, digestOf(zipFile, jdkEntry));
                    }
                    member(new ZipArchiveEntry(jdkEntry.getName()),
                            () -> zipFile.getInputStream(jdkEntry), hooks, handler,
                            first, container, caller);
                }
            }
            // Every local header in the file must belong to a member commons-compress listed,
            // or lie inside one's data (a stored inner zip). One that does not is a member some
            // other reader can see and commons-compress cannot (a shadow or truncated central
            // directory, whatever its EOCD claims): it is read at its own offset and handed on,
            // and recorded. This needs no trust in any directory's counts, CRCs or offsets.
            if (commons == null && jdk == null) {
                warnOnce(container,
                        "iWork package central directory unreadable: members were walked "
                                + "by local header and some may be missing");
                walkLocalHeaders(file, walked, walkedNames, hooks, handler, first, container,
                        caller);
            } else if (commons == null) {
                // commons-compress refused what the JDK accepts (e.g. one record's data running
                // into the directory): account for the local headers against the JDK's view
                if (walkLocalHeaders(file, walked, walkedNames, hooks, handler, first, container,
                        caller) > 0) {
                    warnOnce(container,
                            "iWork package central directory is inconsistent: commons-compress "
                                    + "refused it and local headers outside the JDK's view "
                                    + "were walked");
                }
            } else {
                if (sizeMismatch[0]) {
                    warnOnce(container,
                            "iWork package central directory is inconsistent: a member's "
                                    + "compressed size differs from its local header's");
                }
                java.util.List<Long> central = new java.util.ArrayList<>();
                long[] overflow = {0};
                java.util.List<Long> stray = unaccountedLocalHeaders(central, overflow, file, knownHeaders, extents,
                        commons);
                if (overflow[0] > 0) {
                    warnOnce(container, "iWork package: " + overflow[0] + " more local header "
                            + "signature(s) outside the central directory were not examined");
                }
                if (!stray.isEmpty() || !central.isEmpty()) {
                    walkStrays(central, file, stray, jdk, jdkExtras, jdkDigests, hooks, handler, first,
                            container, caller);
                }
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

    /** @return how many local entries were not covered by {@code walked} and were handed on */
    private static int walkLocalHeaders(java.nio.file.Path file,
                                        java.util.Map<String, Integer> walked,
                                        java.util.Map<String, Integer> walkedNames,
                                        Hooks hooks, MemberHandler handler,
                                        Exception[] first, Metadata container,
                                        TaggedContentHandler caller)
            throws IOException, SAXException {
        int extras = 0;
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
                extras++;
                member(entry, () -> CloseShieldInputStream.wrap(zip), hooks, handler, first,
                        container, caller);
            }
        }
        return extras;
    }

    /**
     * Where a commons-compress member lives: its local header (name, extra field) and its data.
     * The data's end is the central directory's claim, which an attacker can stretch over
     * another member's local header, so it is {@code verified} only when the local header's
     * own size (32-bit or zip64 extra) agrees; otherwise (a data descriptor) it is measured
     * lazily, and only if a stray signature actually falls inside it.
     */
    private static final class Extent {
        final ZipArchiveEntry entry;
        final long start;
        long end;
        boolean verified;
        boolean mismatch;
        int localMethod;
        int localFlags;

        Extent(ZipArchiveEntry entry, long start, long end) {
            this.entry = entry;
            this.start = start;
            this.end = end;
        }

        static Extent of(java.nio.file.Path file, ZipFile zipFile, ZipArchiveEntry entry)
                throws IOException {
            long data = entry.getDataOffset();
            long claimed = entry.getCompressedSize();
            if (data < 0 || claimed < 0) {
                return null;
            }
            Extent x = new Extent(entry, entry.getLocalHeaderOffset(), data + claimed);
            byte[] h = new byte[30];
            try (java.nio.channels.SeekableByteChannel ch = Files.newByteChannel(file)) {
                ch.position(entry.getLocalHeaderOffset());
                readFully(ch, h);
                // streaming readers follow the LOCAL header's method and flags
                x.localFlags = u16(h, 6);
                x.localMethod = u16(h, 8);
                if ((x.localFlags & 8) != 0) {
                    return x;                       // data descriptor: measured lazily
                }
                long local = le32(h, 18);
                if (local == 0xFFFFFFFFL || le32(h, 22) == 0xFFFFFFFFL) {
                    long z = zip64CompressedSize(ch, h);
                    if (local == 0xFFFFFFFFL) {
                        if (z < 0) {
                            return x;
                        }
                        local = z;
                    }
                }
                x.verified = true;
                if (local != claimed) {
                    x.mismatch = true;
                    x.end = data + Math.min(local, claimed);
                }
                return x;
            }
        }

        /** True if {@code offset} lies inside this member; measures an unverified extent (once
         * per local header, shared by every record that points at it). */
        boolean covers(long offset, java.nio.file.Path file, ZipFile zipFile,
                       java.util.Map<Long, Long> measuredByHeader) {
            if (offset < start || offset >= end) {
                return false;
            }
            if (verified || offset < entry.getDataOffset()) {
                return true;
            }
            Long used = measuredByHeader.get(start);
            if (used == null) {
                used = measure(file, zipFile, this);
                measuredByHeader.put(start, used);
            }
            // an extent nobody can measure (encrypted, another method, no valid descriptor)
            // does not account for a signature inside it: the signature is read as a stray
            return used >= 0 && offset < entry.getDataOffset() + used;
        }
    }

    private static void readFully(java.nio.channels.SeekableByteChannel ch, byte[] b)
            throws IOException {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(b);
        while (buf.hasRemaining() && ch.read(buf) >= 0) {
            // fill
        }
    }

    /** The compressed size in a local header's zip64 extra field (channel positioned after
     * the fixed header), or -1. A local header's zip64 extra always holds both sizes,
     * original first (APPNOTE 4.5.3), as both streaming readers read it. */
    private static long zip64CompressedSize(java.nio.channels.SeekableByteChannel ch, byte[] h)
            throws IOException {
        int nameLen = u16(h, 26);
        int extraLen = u16(h, 28);
        byte[] extra = new byte[extraLen];
        ch.position(ch.position() + nameLen);
        readFully(ch, extra);
        for (int i = 0; i + 4 <= extraLen; ) {
            int id = u16(extra, i);
            int len = u16(extra, i + 2);
            if (id == 0x0001) {
                int p;
                if (len >= 16) {
                    p = i + 4 + 8;                  // both sizes, original first
                } else if (len >= 8 && le32(h, 22) != 0xFFFFFFFFL) {
                    p = i + 4;                      // only the compressed size is deferred
                } else {
                    return -1;
                }
                if (p + 8 > extraLen) {
                    return -1;
                }
                return le32(extra, p) | le32(extra, p + 4) << 32;
            }
            i += 4 + len;
        }
        return -1;
    }

    /** Compressed bytes a data-descriptor member really uses, by its LOCAL method, or -1 when
     * that cannot be told (encrypted, another method, no valid descriptor, or a deflate stream
     * whose output exceeds deflate's maximum ratio). */
    private static long measure(java.nio.file.Path file, ZipFile zipFile, Extent x) {
        if ((x.localFlags & 1) != 0) {
            return -1;
        }
        if (x.localMethod == ZipArchiveEntry.STORED) {
            return measureStored(file, x.entry);
        }
        if (x.localMethod != ZipArchiveEntry.DEFLATED) {
            return -1;
        }
        long claimed = x.entry.getCompressedSize();
        long maxOut = claimed * 1032L + (1L << 20);
        java.util.zip.Inflater inflater = new java.util.zip.Inflater(true);
        try (InputStream raw = zipFile.getRawInputStream(x.entry)) {
            if (raw == null) {
                return -1;
            }
            byte[] in = new byte[64 * 1024];
            byte[] out = new byte[64 * 1024];
            long fed = 0;
            long produced = 0;
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    int n = raw.read(in);
                    if (n < 0) {
                        return -1;
                    }
                    fed += n;
                    inflater.setInput(in, 0, n);
                }
                int got = inflater.inflate(out);
                produced += got;
                if (produced > maxOut || got == 0 && inflater.needsDictionary()) {
                    return -1;
                }
            }
            return fed - inflater.getRemaining();
        } catch (IOException | java.util.zip.DataFormatException e) {
            return -1;
        } finally {
            inflater.end();
        }
    }

    /**
     * Where a STORED data-descriptor member ends for a streaming reader (ZipArchiveInputStream
     * with stored data descriptors allowed): at the first offset d holding either a signed
     * descriptor ("PK\\7\\8") whose compressed size is d, or a local or central header
     * signature preceded by an unsigned 12- or 20-byte descriptor whose compressed size is the
     * bytes before it. The bytes up to the claimed size plus one descriptor are scanned;
     * -1 if no valid end is found.
     */
    private static long measureStored(java.nio.file.Path file, ZipArchiveEntry entry) {
        long data = entry.getDataOffset();
        long claimed = entry.getCompressedSize();
        long limit = claimed + 24 + 4;
        try (java.nio.channels.SeekableByteChannel ch = Files.newByteChannel(file)) {
            ch.position(data);
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(1 << 20);
            byte[] b = buf.array();
            long base = 0;                      // file offset (relative to data) of b[0]
            int keep = 24;                      // bytes carried over for look-behind / ahead
            while (base < limit) {
                int n = ch.read(buf);
                int len = buf.position();
                boolean last = n < 0;
                int upto = last ? len : len - keep;
                for (int i = 0; i < upto && base + i <= claimed; i++) {
                    long d = base + i;
                    if (i + 4 > len) {
                        break;
                    }
                    if (b[i] != 'P' || b[i + 1] != 'K') {
                        continue;
                    }
                    int k = b[i + 2];
                    int m = b[i + 3];
                    if (k == 7 && m == 8 && i + 16 <= len) {
                        if (le32(b, i + 8) == d || i + 24 <= len
                                && (le32(b, i + 8) | le32(b, i + 12) << 32) == d) {
                            return d;
                        }
                    } else if ((k == 3 && m == 4 || k == 1 && m == 2) && i >= 12) {
                        if (le32(b, i - 8) == d - 12) {
                            return d - 12;
                        }
                        if (i >= 20 && (le32(b, i - 16) | le32(b, i - 12) << 32) == d - 20) {
                            return d - 20;
                        }
                    }
                }
                if (last) {
                    break;
                }
                int carry = Math.min(keep, len);
                base += len - carry;
                System.arraycopy(b, len - carry, b, 0, carry);
                buf.position(carry);
                buf.limit(buf.capacity());
            }
            return -1;
        } catch (IOException e) {
            return -1;
        }
    }

    /** Offsets of "PK\\3\\4" signatures that are neither a known member's local header nor
     * inside a known member's (verified or measured) extent. */
    private static java.util.List<Long> unaccountedLocalHeaders(java.util.List<Long> central,
                                                                long[] overflow,
                                                                java.nio.file.Path file,
                                                                java.util.Set<Long> headers,
                                                                java.util.List<Extent> extents,
                                                                ZipFile zipFile)
            throws IOException {
        // verified extents merged into disjoint ranges: one binary search per signature
        java.util.List<long[]> sorted = new java.util.ArrayList<>();
        java.util.List<Extent> unverified = new java.util.ArrayList<>();
        for (Extent x : extents) {
            if (x.verified) {
                sorted.add(new long[]{x.start, x.end});
            } else {
                sorted.add(new long[]{x.start, x.entry.getDataOffset()});   // its header
                unverified.add(x);
            }
        }
        sorted.sort((x, y) -> Long.compare(x[0], y[0]));
        java.util.List<long[]> merged = new java.util.ArrayList<>();
        for (long[] r : sorted) {
            long[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && r[0] <= last[1]) {
                last[1] = Math.max(last[1], r[1]);
            } else {
                merged.add(new long[]{r[0], r[1]});
            }
        }
        long[][] ranges = merged.toArray(new long[0][]);
        // unverified extents by start, with a running maximum end: a lookup walks back only
        // over extents that can still reach the offset (none or one for non-overlapping ones)
        unverified.sort((x, y) -> Long.compare(x.start, y.start));
        long[] maxEnd = new long[unverified.size()];
        for (int i = 0; i < maxEnd.length; i++) {
            maxEnd[i] = Math.max(i == 0 ? 0 : maxEnd[i - 1], unverified.get(i).end);
        }
        // inside the central directory (from commons-compress's EOCD on) records hold arbitrary
        // bytes (dates, CRCs, offsets) that can spell "PK\\3\\4": such signatures are kept
        // apart and only count if a whole member really parses there
        long cdStart = centralDirectoryStart(file);
        java.util.List<Long> stray = new java.util.ArrayList<>();
        java.util.Map<Long, Long> measuredByHeader = new java.util.HashMap<>();
        try (java.nio.channels.SeekableByteChannel ch = Files.newByteChannel(file)) {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(1 << 20);
            long base = 0;
            while (true) {
                int n = ch.read(buf);
                if (n < 0) {
                    break;
                }
                byte[] b = buf.array();
                int len = buf.position();
                for (int i = 0; i + 3 < len; i++) {
                    if (b[i] == 'P' && b[i + 1] == 'K' && b[i + 2] == 3 && b[i + 3] == 4) {
                        long off = base + i;
                        if (headers.contains(off) || inside(ranges, off)) {
                            continue;
                        }
                        if (cdStart >= 0 && off >= cdStart) {
                            if (central.size() < MAX_RETAINED) {
                                central.add(off);
                            } else {
                                overflow[0]++;
                            }
                            continue;
                        }
                        boolean covered = false;
                        int lo = 0;
                        int hi = unverified.size() - 1;
                        int at = -1;
                        while (lo <= hi) {
                            int mid = (lo + hi) >>> 1;
                            if (unverified.get(mid).start <= off) {
                                at = mid;
                                lo = mid + 1;
                            } else {
                                hi = mid - 1;
                            }
                        }
                        for (int k = at; k >= 0 && maxEnd[k] > off; k--) {
                            if (unverified.get(k).covers(off, file, zipFile, measuredByHeader)) {
                                covered = true;
                                break;
                            }
                        }
                        if (!covered) {
                            if (stray.size() < MAX_RETAINED) {
                                stray.add(off);
                            } else {
                                overflow[0]++;
                            }
                        }
                    }
                }
                // keep the last 3 bytes: a signature may straddle the buffer boundary
                int carry = Math.min(3, len);
                base += len - carry;
                System.arraycopy(b, len - carry, b, 0, carry);
                buf.position(carry);
                buf.limit(buf.capacity());
            }
        }
        return stray;
    }

    /** Where commons-compress's central directory starts (the last EOCD's offset, if a
     * central header is there), or -1. */
    private static long centralDirectoryStart(java.nio.file.Path file) throws IOException {
        try (java.nio.channels.SeekableByteChannel ch = Files.newByteChannel(file)) {
            long size = ch.size();
            int tail = (int) Math.min(size, 22 + 65535);
            byte[] b = new byte[tail];
            ch.position(size - tail);
            readFully(ch, b);
            for (int i = tail - 22; i >= 0; i--) {
                if (b[i] == 'P' && b[i + 1] == 'K' && b[i + 2] == 5 && b[i + 3] == 6) {
                    long cd = le32(b, i + 16);
                    if (cd == 0xFFFFFFFFL || cd + 4 > size) {
                        return -1;
                    }
                    byte[] sig = new byte[4];
                    ch.position(cd);
                    readFully(ch, sig);
                    return sig[0] == 'P' && sig[1] == 'K' && sig[2] == 1 && sig[3] == 2 ? cd : -1;
                }
            }
            return -1;
        }
    }

    private static boolean inside(long[][] ranges, long offset) {
        int lo = 0;
        int hi = ranges.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (ranges[mid][1] <= offset) {
                lo = mid + 1;
            } else if (ranges[mid][0] > offset) {
                hi = mid - 1;
            } else {
                return true;
            }
        }
        return false;
    }

    /** A member found inside the central directory is read in full only up to this size. */
    private static final long CENTRAL_MEMBER_CAP = 64L << 20;

    /** At most this many signature offsets are retained per scan; the rest are only counted. */
    private static final int MAX_RETAINED = 4096;

    /** At most this many stray local headers are read; the rest are only counted. */
    private static final int MAX_STRAYS = 256;

    /**
     * Reads each stray local header at its own offset and hands its member on, skipping
     * signatures inside a stray already read (its header, name, extra field and data), and
     * a member the JDK's reader already handed on with the same name and data. A data-descriptor
     * member's identity is only known once its data is read, so it is spooled first.
     */
    private static void walkStrays(java.util.List<Long> central, java.nio.file.Path file,
                                   java.util.List<Long> stray,
                                   java.util.zip.ZipFile jdk,
                                   java.util.Map<String, java.util.List<java.util.zip.ZipEntry>>
                                           jdkExtras,
                                   java.util.Map<java.util.zip.ZipEntry, String> digests,
                                   Hooks hooks,
                                   MemberHandler handler, Exception[] first, Metadata container,
                                   TaggedContentHandler caller)
            throws IOException, SAXException {
        long skipUntil = -1;
        int read = 0;
        int members = 0;
        int unreadable = 0;
        int skipped = 0;
        for (long offset : stray) {
            if (offset < skipUntil) {
                continue;
            }
            if (read >= MAX_STRAYS) {
                skipped++;
                continue;
            }
            read++;
            try (java.nio.channels.SeekableByteChannel ch = Files.newByteChannel(file);
                    TemporaryResources tmp = new TemporaryResources()) {
                byte[] header = new byte[30];
                ch.position(offset);
                java.nio.ByteBuffer hb = java.nio.ByteBuffer.wrap(header);
                while (hb.hasRemaining() && ch.read(hb) >= 0) {
                    // fill
                }
                long extent = 30L + u16(header, 26) + u16(header, 28);
                ch.position(offset);
                ZipArchiveInputStream zip = new ZipArchiveInputStream(
                        java.nio.channels.Channels.newInputStream(ch), "UTF8", true, true);
                try (zip) {
                    ZipArchiveEntry entry;
                    try {
                        entry = zip.getNextEntry();
                    } catch (IOException | LinkageError e) {
                        unreadable++;
                        skipUntil = Math.max(skipUntil, offset + extent);
                        continue;
                    }
                    if (entry == null) {
                        unreadable++;
                        continue;
                    }
                    java.util.List<java.util.zip.ZipEntry> sameName =
                            jdkExtras.getOrDefault(entry.getName(), java.util.List.of());
                    if (sameName.isEmpty()) {
                        // nothing to dedupe against: hand it on as it is read, unspooled
                        long size = (u16(header, 6) & 8) != 0 ? 0 : le32(header, 18);
                        if (size == 0xFFFFFFFFL) {
                            // the real size is in the zip64 extra, as the reader uses it
                            ch.position(offset + 30);
                            size = Math.max(0, zip64CompressedSize(ch, header));
                        }
                        skipUntil = Math.max(skipUntil, offset + extent + size);
                        members++;
                        member(entry, () -> CloseShieldInputStream.wrap(zip), hooks, handler,
                                first, container, caller);
                        continue;
                    }
                    java.nio.file.Path spool = tmp.createTempFile();
                    java.security.MessageDigest sha;
                    try {
                        // spooled and hashed first: a data-descriptor member's CRC and sizes,
                        // and whether the JDK's reader already handed on these very bytes, are
                        // known only once its data is read
                        sha = java.security.MessageDigest.getInstance("SHA-256");
                        long copied;
                        try (InputStream in = new java.security.DigestInputStream(
                                org.apache.commons.io.input.BoundedInputStream.builder()
                                        .setInputStream(CloseShieldInputStream.wrap(zip))
                                        .setMaxCount(CENTRAL_MEMBER_CAP + 1).get(), sha)) {
                            copied = Files.copy(in, spool,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                        if (copied > CENTRAL_MEMBER_CAP) {
                            // too large to hold for deduplication: hand it on as it is read
                            // (at worst a duplicate of the JDK's copy), never inflate it to disk
                            skipUntil = Math.max(skipUntil, offset + extent);
                            members++;
                            handOnAt(file, offset, hooks, handler, first, container, caller);
                            continue;
                        }
                    } catch (IOException | LinkageError | java.security.NoSuchAlgorithmException e) {
                        unreadable++;
                        skipUntil = Math.max(skipUntil, offset + extent);
                        continue;
                    }
                    skipUntil = Math.max(skipUntil, offset + extent
                            + Math.max(0, entry.getCompressedSize()));
                    String strayDigest = java.util.HexFormat.of().formatHex(sha.digest());
                    boolean same = false;
                    for (java.util.zip.ZipEntry candidate : sameName) {
                        String d = digests.computeIfAbsent(candidate, c -> digestOf(jdk, c));
                        if (strayDigest.equals(d)) {
                            same = true;
                            break;
                        }
                    }
                    if (same) {
                        continue;
                    }
                    members++;
                    member(entry, () -> Files.newInputStream(spool), hooks, handler, first,
                            container, caller);
                }
            }
        }
        // Signatures inside the central directory count only if a whole member parses and reads
        // there (an embedded member no directory lists); anything else is just directory bytes.
        int centralRead = 0;
        int centralSkipped = 0;
        int centralUnreadable = 0;
        for (long offset : central) {
            if (centralRead >= MAX_STRAYS) {
                centralSkipped++;
                continue;
            }
            centralRead++;
            ZipArchiveEntry parsed = null;
            try (java.nio.channels.SeekableByteChannel ch = Files.newByteChannel(file);
                    TemporaryResources tmp = new TemporaryResources()) {
                ch.position(offset);
                ZipArchiveInputStream zip = new ZipArchiveInputStream(
                        java.nio.channels.Channels.newInputStream(ch), "UTF8", true, true);
                try (zip) {
                    ZipArchiveEntry entry = zip.getNextEntry();
                    if (entry == null) {
                        continue;
                    }
                    parsed = entry;
                    java.nio.file.Path spool = tmp.createTempFile();
                    java.security.MessageDigest sha =
                            java.security.MessageDigest.getInstance("SHA-256");
                    // read whole (it counts only if it does), but never more than the cap: a
                    // larger one is recorded as unreadable rather than inflated without limit
                    try (InputStream in = new java.security.DigestInputStream(
                            org.apache.commons.io.input.BoundedInputStream.builder()
                                    .setInputStream(CloseShieldInputStream.wrap(zip))
                                    .setMaxCount(CENTRAL_MEMBER_CAP + 1).get(), sha)) {
                        if (Files.copy(in, spool,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                                > CENTRAL_MEMBER_CAP) {
                            throw new IOException("member in the central directory over "
                                    + CENTRAL_MEMBER_CAP + " bytes");
                        }
                    }
                    String strayDigest = java.util.HexFormat.of().formatHex(sha.digest());
                    boolean same = false;
                    for (java.util.zip.ZipEntry candidate
                            : jdkExtras.getOrDefault(entry.getName(), java.util.List.of())) {
                        if (strayDigest.equals(digests.computeIfAbsent(candidate,
                                c -> digestOf(jdk, c)))) {
                            same = true;
                            break;
                        }
                    }
                    if (same) {
                        continue;
                    }
                    members++;
                    member(entry, () -> Files.newInputStream(spool), hooks, handler, first,
                            container, caller);
                }
            } catch (IOException | LinkageError | java.security.NoSuchAlgorithmException e) {
                // directory bytes that happen to spell a signature, unless they parsed as a
                // plausible member header (a short printable name, stored or deflated) whose
                // data then failed: that is a member someone placed there
                if (parsed != null && plausibleHeader(parsed)) {
                    centralUnreadable++;
                }
            }
        }
        if (centralUnreadable > 0 || centralSkipped > 0) {
            warnOnce(container, "iWork package: " + centralUnreadable + " member(s) embedded in "
                    + "the central directory could not be read"
                    + (centralSkipped > 0 ? ", " + centralSkipped + " more signature(s) not read"
                            : ""));
        }
        if (members > 0) {
            warnOnce(container, "iWork package central directory is inconsistent: " + members
                    + " member(s) outside it were read at their own local header");
        }
        if (unreadable > 0 || skipped > 0) {
            warnOnce(container, "iWork package: " + unreadable + " local header signature(s) "
                    + "outside the central directory do not parse"
                    + (skipped > 0 ? ", " + skipped + " more not read" : ""));
        }
    }

    /** Reads the member at {@code offset} afresh and hands it on as it is read. */
    private static void handOnAt(java.nio.file.Path file, long offset, Hooks hooks,
                                 MemberHandler handler, Exception[] first, Metadata container,
                                 TaggedContentHandler caller) throws IOException, SAXException {
        try (java.nio.channels.SeekableByteChannel ch = Files.newByteChannel(file)) {
            ch.position(offset);
            ZipArchiveInputStream zip = new ZipArchiveInputStream(
                    java.nio.channels.Channels.newInputStream(ch), "UTF8", true, true);
            try (zip) {
                ZipArchiveEntry entry = zip.getNextEntry();
                if (entry != null) {
                    member(entry, () -> CloseShieldInputStream.wrap(zip), hooks, handler, first,
                            container, caller);
                }
            }
        }
    }

    private static boolean plausibleHeader(ZipArchiveEntry entry) {
        String name = entry.getName();
        if (name == null || name.isEmpty() || name.length() > 1024) {
            return false;
        }
        int method = entry.getMethod();
        if (method != ZipArchiveEntry.STORED && method != ZipArchiveEntry.DEFLATED) {
            return false;
        }
        return name.chars().allMatch(c -> c >= 0x20 && c != 0x7f && c != 0xfffd);
    }

    /** SHA-256 of a JDK-listed member's bytes, or "" if they cannot be read. */
    private static String digestOf(java.util.zip.ZipFile jdk, java.util.zip.ZipEntry entry) {
        try (InputStream in = jdk.getInputStream(entry)) {
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                sha.update(buf, 0, n);
            }
            return java.util.HexFormat.of().formatHex(sha.digest());
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static int u16(byte[] b, int i) {
        return (b[i] & 0xff) | (b[i + 1] & 0xff) << 8;
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
            // already named by an earlier walk; failures that are not about the member (the
            // caller's own, an embedded-document limit) still propagate
            rethrowIfNotAboutTheMember(failure, caller);
            return first;
        }
        return fold(first, failure, entry.getName(), container, caller);
    }

    /** The caller's own failures (exceptions or Errors its handler threw, a write limit) and
     * an embedded-document limit stop the walk at once, unwrapped. */
    private static void rethrowIfNotAboutTheMember(Throwable failure, TaggedContentHandler caller)
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
        rethrowIfNotAboutTheMember(failure, caller);
        int depth = 0;
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
