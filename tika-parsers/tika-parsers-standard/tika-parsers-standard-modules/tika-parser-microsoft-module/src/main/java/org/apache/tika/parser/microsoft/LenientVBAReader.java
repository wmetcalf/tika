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
package org.apache.tika.parser.microsoft;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.POIFSDocument;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.poifs.property.DocumentProperty;
import org.apache.poi.poifs.property.Property;

/**
 * Lenient VBA source extractor for VBA projects that trip POI's strict reserved-field
 * checks (most commonly Mac-Word–authored .docm files where the dir stream contains
 * record IDs that POI doesn't expect but olevba handles fine).
 *
 * <p>Implements MS-OVBA §2.4.1 decompression and a tolerant §2.3.4.2 dir-stream
 * parser that ignores unknown / unexpected record IDs rather than throwing.
 */
public final class LenientVBAReader {

    // MS-OVBA dir-stream record IDs we care about
    private static final int REC_MODULENAME        = 0x0019;
    private static final int REC_MODULESTREAMNAME  = 0x001A;
    private static final int REC_MODULEOFFSET      = 0x0031;
    private static final int REC_MODULETERM        = 0x002B;
    private static final int REC_PROJECTCODEPAGE   = 0x0003;
    private static final int REC_MODULES           = 0x000F; // start of modules section
    private static final int REC_MODULECOUNT       = 0x0029;

    static final int MAX_STREAM_BYTES = 10 * 1024 * 1024; // 10 MB default guard

    /** Default MBCS charset when a project declares no usable PROJECTCODEPAGE. */
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    /**
     * Default ceiling on the TOTAL macro source one document may yield. Chosen well above any
     * real VBA project (the largest in a 52,920-document macro corpus is orders of magnitude
     * below it) so it bounds bombs without touching real extraction.
     */
    static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;

    /** Depth cap on the search for VBA storages; a crafted CFBF child tree can be cyclic. */
    private static final int MAX_STORAGE_DEPTH = 32;
    /** Cap on how many VBA storages one document may contribute. */
    private static final int MAX_VBA_STORAGES = 64;

    /**
     * Cap on how many modules one dir stream may describe.
     *
     * <p>Reading a document's module streams is QUADRATIC in the number of streams -- and that is
     * POI's cost, not ours: reading N small streams straight through POI's own API, with no Tika
     * code involved at all, measures 6 ms at N=1,024 and 742 ms at N=16,384. Each of the three
     * passes over a project (the projection, the tree walk, the orphan scan) pays it, so an
     * attacker-chosen module count is a CPU amplifier even though every stream stays tiny and the
     * byte budget is never approached.
     *
     * <p>4,096 is ~9.5x the largest module count in a 6,574-document macro corpus (429; mean 9.2,
     * nothing above 512), so it cannot touch real documents, while holding the quadratic to about
     * 80 ms per pass. Firing it drops modules, so it is reported.
     */
    private static final int MAX_MODULE_REFS = 4096;

    /**
     * Slice of the document budget held back for the indicator-bearing lines of modules that no
     * longer fit whole. CARVED OUT of the budget, never added to it, so the documented ceiling
     * still holds.
     *
     * <p>Exists because a budget has to choose what to drop, and without this the choice was made
     * by the dir stream's ORDER -- which the document's author picks. Measured: twenty filler
     * modules and one payload module, identical bytes and identical budget, recovered the payload
     * when it was listed first and lost it entirely when listed last.
     */
    private static final int INDICATOR_RESERVE_BYTES = 64 * 1024;
    /** Per-module ceiling on retained indicator lines, so one module cannot take the reserve. */
    private static final int INDICATOR_CHARS_PER_MODULE = 2048;

    /**
     * Total characters the indicator scan may READ across a document, as opposed to retain.
     *
     * <p>The reserve phase keeps going past a module that does not fit, where the old code stopped,
     * so it introduced a cost that did not exist before: scanning. Measured at ~180 ms per megabyte
     * on the worst case for a line-oriented pattern (one enormous line with no newline and no
     * match) -- 40 modules of 1 MB each took 7.2 seconds. Unbounded, at the module cap that is
     * hours. This holds the added cost near a third of a second, and it is only ever paid by a
     * document that already exhausted a 32 MB macro budget.
     */
    private static final int INDICATOR_SCAN_BUDGET = 2 * 1024 * 1024;

    /** Marks a module kept only as its indicator lines, so no consumer reads it as complete. */
    public static final String INDICATORS_ONLY_MARKER = "[TIKA-VBA-INDICATORS-ONLY]";

    /**
     * Lines worth keeping when a module cannot be kept whole: the calls that make a macro do
     * something observable, plus URLs and UNC paths.
     *
     * <p>Deliberately anchored and possessive-free but alternation-only over literals -- no nested
     * quantifiers, so it cannot backtrack pathologically. A scanner regex that goes quadratic is
     * its own denial of service; that lesson cost 7 minutes of CPU on one document earlier in this
     * work.
     */
    private static final java.util.regex.Pattern VBA_INDICATOR = java.util.regex.Pattern.compile(
            "(?i)(?:shell|createobject|getobject|wscript|powershell|cmd\\.exe|rundll32"
                    + "|urldownloadtofile|xmlhttp|winhttp|msxml2|environ|declare\\s+(?:ptr)?function"
                    + "|callbyname|https?://|ftp://|\\\\\\\\[a-z0-9._-])");

    /**
     * Per-stream AND per-document VBA size bounds, plus the signal that one fired.
     *
     * <p>Exists because every bound in this reader used to fail SILENTLY: an over-cap module
     * stream returned {@code null} and its entire source vanished, and the decompressor
     * {@code break}s mid-stream leaving a partial macro body. Either way the caller received
     * a short-but-plausible macro with no indication that evidence had been withheld -- the
     * same defect class as the XLM capture caps, on the VBA path.
     *
     * <p>The stream bound alone bounds nothing at document scope: a project may hold any number
     * of modules, so N modules each just under the cap cost N times the cap. That is the same
     * per-part-vs-per-document scope slip the XLM caps had. {@link #hasRoomFor}/{@link #charge}
     * add the document-wide ceiling; charge EXACTLY what is retained, never what was read.
     */
    public static final class Bounds {
        private final int maxStreamBytes;
        private final long maxTotalBytes;
        private long retained;
        private long indicatorScanned;
        private boolean limitReached;
        private String limitDetail;

        public Bounds() {
            this(0);
        }

        public Bounds(int maxStreamBytes) {
            this(maxStreamBytes, 0);
        }

        public Bounds(int maxStreamBytes, long maxTotalBytes) {
            this.maxStreamBytes = maxStreamBytes > 0 ? maxStreamBytes : MAX_STREAM_BYTES;
            // The two knobs are independent and the TIGHTER one wins, as bounds should: a caller
            // that asks for a 200 KB document total means it even though one stream may be 10 MB.
            // (Clamping the total up to the stream cap instead silently ignored every requested
            // total below 10 MB -- which made the document bound untestable and unusable.)
            this.maxTotalBytes = maxTotalBytes > 0 ? maxTotalBytes : MAX_TOTAL_BYTES;
        }

        /** 0 / null config means the built-in default. */
        public static Bounds fromConfig(OfficeParserConfig config) {
            return new Bounds(config == null ? 0 : config.getVbaMaxStreamBytes(),
                    config == null ? 0 : config.getVbaMaxTotalBytes());
        }

        int max() {
            return maxStreamBytes;
        }

        long totalMax() {
            return maxTotalBytes;
        }

        /** Characters of indicator scanning still allowed for this document. */
        int remainingScan() {
            return (int) Math.max(0, INDICATOR_SCAN_BUDGET - indicatorScanned);
        }

        void chargeScan(long chars) {
            indicatorScanned += chars;
        }

        /** Bytes held back from the main phase for indicator lines; see the constant. */
        private long reserve() {
            return Math.min(INDICATOR_RESERVE_BYTES, maxTotalBytes / 8);
        }

        /** Whether {@code len} more retained bytes still fit in the document budget. */
        boolean hasRoomFor(long len) {
            return retained + len <= maxTotalBytes - reserve();
        }

        /** As {@link #hasRoomFor}, but may dip into the indicator reserve. */
        boolean hasReserveFor(long len) {
            return retained + len <= maxTotalBytes;
        }

        /**
         * As {@link #hasReserveFor}, but for a bare PREFIX of a module that carried no indicator
         * line -- allowed only half the reserve.
         *
         * <p>Two properties have to hold at once and they pull against each other: a module that
         * does not fit must still yield something (else the budget is a total-loss switch), and
         * indicator-bearing modules must not be starved by ones with nothing in them (else the
         * dir stream's ORDER decides what the analyst sees, which is the evasion the reserve exists
         * to stop). Measured both ways: with prefixes unrestricted, twenty filler modules consumed
         * the reserve and the payload listed last was lost again. Prefixes therefore get half.
         */
        boolean hasPrefixReserveFor(long len) {
            return retained + len <= maxTotalBytes - reserve() / 2;
        }

        /** Account for bytes actually KEPT. Must mirror {@link #hasRoomFor} exactly. */
        void charge(long len) {
            retained += len;
        }

        long retainedBytes() {
            return retained;
        }

        void mark(String detail) {
            if (!limitReached) {
                limitReached = true;
                limitDetail = detail;
            }
        }

        public boolean isLimitReached() {
            return limitReached;
        }

        public String getLimitDetail() {
            return limitDetail;
        }
    }

    private LenientVBAReader() {}

    /**
     * Extract VBA module source code from a vbaProject.bin POIFS.
     *
     * @return map of module-name → source text; empty if nothing could be extracted
     */
    public static Map<String, String> readMacros(POIFSFileSystem fs) throws IOException {
        return readMacros(fs, new Bounds());
    }

    /** As {@link #readMacros(POIFSFileSystem)}, but reports whether a bound fired. */
    public static Map<String, String> readMacros(POIFSFileSystem fs, Bounds bounds)
            throws IOException {
        // Both routes fill ONE map, so there is exactly one place a module is retained and
        // charged against the document budget. Collecting into two maps and merging would
        // charge twice for every module both routes reach.
        ModuleSink result = new ModuleSink();
        collectFromTree(fs.getRoot(), bounds, result);
        // The VBA storage may be ORPHANED — its OLE/CFBF directory entry deliberately unlinked
        // from the directory tree (a malware anti-analysis trick) so POI's tree-walking readers
        // can't see it, even though Excel and olevba still run the macro by enumerating ALL raw
        // directory entries. Recover the same way: scan every property, then reuse the
        // dir/module decompression.
        //
        // This used to run ONLY when the tree walk came back empty, which made it defeatable by
        // a decoy: one tree-visible module -- an empty Sub is enough -- and the orphaned payload
        // was never looked for. Always run it; retain() collapses what both routes reach.
        collectFromOrphans(fs, bounds, result);
        return result.out;
    }

    /**
     * Record a module without letting a repeated name discard one. Keying results by module name
     * alone made a second module of the same name silently replace the first -- and the one that
     * loses is the one written second, which is where a payload goes. Identical bodies ARE
     * collapsed: the tree walk and the orphan scan legitimately reach the same module twice.
     */
    private static boolean putUnique(ModuleSink sink, String name, String text) {
        String key = (name == null || name.isEmpty()) ? "Module" : name;
        // Two things have to be true at once: a repeated name must NOT discard a module (the second
        // one is where a payload goes), and the same module reached by both the tree walk and the
        // orphan scan must NOT appear twice. So bodies are indexed per name in a hash set, and the
        // suffix counter is remembered rather than rediscovered.
        //
        // The obvious implementations are both wrong. Map.containsValue scans every value in the
        // document, O(modules x length) in String comparisons. Walking just this name's family --
        // key, key#2, key#3 ... -- comparing bodies as it goes is quadratic in the number of
        // SAME-NAMED modules: measured 14.8x the cost of the same 2,048 modules with distinct names
        // (1084 ms vs 73 ms) on same-length bodies differing only at the end, which is the shape
        // that denies String.equals its early exit. See VbaCostShapeTest.
        Set<String> bodies = sink.bodiesByKey.computeIfAbsent(key, k -> new java.util.HashSet<>());
        if (!bodies.add(text)) {
            return false; // this exact body is already stored under this name
        }
        int suffix = sink.nextSuffix.merge(key, 1, Integer::sum);
        sink.out.put(suffix == 1 ? key : key + "#" + suffix, text);
        return true;
    }

    /**
     * The result map plus the bookkeeping that keeps {@link #putUnique} linear. One sink per
     * document, shared by the tree walk and the orphan scan so a module both reach is retained --
     * and charged -- exactly once.
     */
    private static final class ModuleSink {
        final Map<String, String> out = new LinkedHashMap<>();
        final Map<String, Set<String>> bodiesByKey = new java.util.HashMap<>();
        final Map<String, Integer> nextSuffix = new java.util.HashMap<>();

        /** Whether this exact body is already stored under this name -- a NON-mutating check. */
        boolean alreadyStored(String name, String text) {
            Set<String> bodies =
                    bodiesByKey.get((name == null || name.isEmpty()) ? "Module" : name);
            return bodies != null && bodies.contains(text);
        }
    }

    /**
     * Retain one module against the document budget. Returns false when the budget is exhausted,
     * meaning the caller must stop -- and it is marked, because stopping here drops whole modules.
     *
     * <p>Charges only what is actually STORED: a duplicate that {@link #putUnique} collapses costs
     * nothing, so the accumulator always equals the bytes a consumer can see. Charging what was
     * READ instead would let a repeated module exhaust the budget for the modules after it.
     */
    private static boolean retain(ModuleSink sink, String name, String text,
                                  Bounds bounds) {
        // Duplicate check BEFORE the budget check. The tree walk and the orphan scan both reach
        // most modules, and putUnique collapses the second sighting -- so it costs nothing and
        // withholds nothing. Testing it against the ceiling first raised the truncation flag on a
        // document that lost NOTHING (measured: one 10,000-char module, 20,000-byte budget, flag
        // set) and could spend the indicator reserve on a body already stored in full.
        if (sink.alreadyStored(name, text)) {
            return true;
        }
        if (!bounds.hasRoomFor(text.length())) {
            bounds.mark("VBA macro source reached the " + bounds.totalMax()
                    + "-byte per-document bound; later modules were dropped");
            return false;
        }
        if (putUnique(sink, name, text)) {
            bounds.charge(text.length());
        }
        return true;
    }

    /**
     * Last resort for a module that no longer fits: keep only its indicator-bearing lines, from the
     * reserve. Returns false once even the reserve is gone, which is the point at which the caller
     * genuinely must stop.
     *
     * <p>A module with nothing indicator-like in it costs nothing and is skipped, so the reserve is
     * spent on the modules an analyst would actually want.
     */
    private static boolean retainIndicators(ModuleSink sink, String name, String text,
                                            Bounds bounds) {
        if (sink.alreadyStored(name, text)) {
            return true; // already stored in full; the reserve must not pay for a duplicate
        }
        int scanLimit = Math.min(text.length(), bounds.remainingScan());
        if (scanLimit <= 0) {
            // The document has had all the scanning it is going to get.
            return false;
        }
        bounds.chargeScan(scanLimit);
        StringBuilder kept = new StringBuilder();
        java.util.regex.Matcher m = VBA_INDICATOR.matcher("");
        int from = 0;
        while (from < scanLimit && kept.length() < INDICATOR_CHARS_PER_MODULE) {
            int nl = text.indexOf('\n', from);
            int end = nl < 0 || nl > scanLimit ? scanLimit : nl;
            String line = text.substring(from, end);
            if (m.reset(line).find()) {
                int room = INDICATOR_CHARS_PER_MODULE - kept.length();
                kept.append(line, 0, Math.min(line.length(), room)).append('\n');
            }
            from = end + 1;
        }
        boolean prefixOnly = kept.length() == 0;
        if (prefixOnly) {
            // No indicator line, but this module does NOT fit, so we ARE withholding it: keep a
            // bounded PREFIX rather than nothing. Rejecting an over-budget body whole is the same
            // defect the XLM path had ("the point of raising the cap was more payload reaching the
            // analyst, not none") -- measured here as a 4-module bomb carrier yielding 21 characters
            // in total, i.e. the budget acting as a total-loss switch. A prefix carries the module
            // header and its first statements, which is what triage reads first.
            kept.append(text, 0, Math.min(text.length(), INDICATOR_CHARS_PER_MODULE));
        }
        String value = INDICATORS_ONLY_MARKER + "\n" + kept;
        boolean room = prefixOnly
                ? bounds.hasPrefixReserveFor(value.length())
                : bounds.hasReserveFor(value.length());
        if (!room) {
            // A prefix that cannot fit its half of the reserve is skipped, NOT a stop: stopping here
            // would abandon the modules after it, and one of them may carry indicators that do fit.
            return prefixOnly;
        }
        if (putUnique(sink, name, value)) {
            bounds.charge(value.length());
        }
        return true;
    }

    /** One module as the dir stream describes it. */
    private static final class ModuleRef {
        /** MODULENAME (0x0019) — the label a human sees; used as the result key. */
        final String displayName;
        /** MODULESTREAMNAME (0x001A) — which OLE stream actually holds the source. */
        final String streamName;
        final int offset;

        ModuleRef(String displayName, String streamName, int offset) {
            this.displayName = displayName;
            this.streamName = streamName;
            this.offset = offset;
        }

        /** Names to try, in order, when locating this module's stream. */
        String[] candidates() {
            if (streamName == null) {
                return new String[] {displayName};
            }
            if (displayName == null || displayName.equals(streamName)) {
                return new String[] {streamName};
            }
            return new String[] {streamName, displayName};
        }

        String key() {
            return displayName != null ? displayName : streamName;
        }
    }

    /**
     * Recover VBA source from a project whose directory-tree linkage was corrupted so the
     * {@code VBA} storage + {@code dir}/module streams are orphaned (present in the raw
     * property table but unreachable by tree traversal). Enumerates ALL document properties,
     * locates the orphaned {@code dir} stream, and reuses {@link #parseDir}/{@link #decompress}
     * to pull each module's source. Best-effort: any failure yields an empty map.
     */
    static Map<String, String> readMacrosFromOrphans(POIFSFileSystem fs) {
        return readMacrosFromOrphans(fs, new Bounds());
    }

    static Map<String, String> readMacrosFromOrphans(POIFSFileSystem fs, Bounds bounds) {
        ModuleSink result = new ModuleSink();
        collectFromOrphans(fs, bounds, result);
        return result.out;
    }

    private static void collectFromOrphans(POIFSFileSystem fs, Bounds bounds,
                                           ModuleSink result) {
        Map<String, DocumentProperty> streams = collectAllStreamProps(fs);
        // EVERY dir stream, not just the first. Keying by name kept only one, so a document with a
        // tree-visible VBA storage next to an orphaned one had the orphan's dir shadowed -- and
        // the orphan is where the payload is put, that being the whole point of orphaning it.
        for (DocumentProperty dirProp : dirPropsOf(fs, streams)) {
            if (!collectFromOneOrphanDir(fs, dirProp, streams, bounds, result)) {
                return; // document budget exhausted
            }
        }
    }

    /** @return false when the document budget is exhausted and the caller must stop. */
    private static boolean collectFromOneOrphanDir(POIFSFileSystem fs, DocumentProperty dirProp,
                                                   Map<String, DocumentProperty> streams,
                                                   Bounds bounds, ModuleSink result) {
        try {
            byte[] dirRaw = readPropBytes(fs, dirProp, bounds);
            if (dirRaw == null) {
                return true;
            }
            DirInfo dir = parseDir(decompress(dirRaw, 0, bounds), bounds);
            Charset charset = dir.charset;
            for (ModuleRef ref : dir.modules) {
                // Case-insensitive lookup (see collectAllStreamProps); keep the dir-stream's
                // original-case module name as the result key.
                DocumentProperty modProp = null;
                for (String candidate : ref.candidates()) {
                    if (candidate == null) {
                        continue;
                    }
                    modProp = streams.get(candidate.toLowerCase(Locale.ROOT));
                    if (modProp != null) {
                        break;
                    }
                }
                if (modProp == null) {
                    continue;
                }
                try {
                    byte[] raw = readPropBytes(fs, modProp, bounds);
                    int offset = ref.offset;
                    if (raw == null || offset < 3 || offset >= raw.length) {
                        continue;
                    }
                    String text = new String(decompress(raw, offset, bounds), charset);
                    if (!text.isBlank() && !retain(result, ref.key(), text, bounds)
                            && !retainIndicators(result, ref.key(), text, bounds)) {
                        return false;
                    }
                } catch (Exception ignore) {
                    // skip individual module failures
                }
            }
        } catch (Exception ignore) {
            // dir stream unreadable / not actually a VBA dir
        }
        return true;
    }

    /**
     * Every raw property named {@code dir}, in property-table order. {@link #collectAllStreamProps}
     * keeps one entry per name, which is right for module lookup but wrong for the dir stream: a
     * document may carry several VBA projects, and the interesting one is the shadowed one.
     */
    private static List<DocumentProperty> dirPropsOf(POIFSFileSystem fs,
                                                     Map<String, DocumentProperty> streams) {
        List<DocumentProperty> out = new java.util.ArrayList<>();
        for (DocumentProperty p : allStreamProps(fs)) {
            if ("dir".equalsIgnoreCase(p.getName())) {
                out.add(p);
            }
        }
        if (out.isEmpty()) {
            DocumentProperty single = streams.get("dir");
            if (single != null) {
                out.add(single);
            }
        }
        return out;
    }

    /**
     * Whether the raw property table holds a {@code dir} stream that the DIRECTORY TREE cannot
     * reach -- i.e. whether an orphan scan could find anything a tree-walking reader cannot.
     *
     * <p>Exists so a caller can decide whether to look without paying for the scan on every
     * document. POI only reads storages named {@code VBA}, so a project parked anywhere else is
     * invisible to it while still being reachable here.
     */
    public static boolean hasUnreachableDirStream(POIFSFileSystem fs) {
        try {
            int viaTree = 0;
            for (DirectoryNode vbaDir : findVBADirs(fs.getRoot(), new Bounds())) {
                if (findEntry(vbaDir, "dir") != null) {
                    viaTree++;
                }
            }
            int total = 0;
            for (DocumentProperty prop : allStreamProps(fs)) {
                if ("dir".equalsIgnoreCase(prop.getName())) {
                    total++;
                }
            }
            return total > viaTree;
        } catch (Exception | OutOfMemoryError e) {
            return false; // cannot tell: do not impose the extra scan
        }
    }

    /**
     * Enumerate ALL document (stream) properties from the raw property table — including ones
     * the directory tree doesn't link (orphans). POI exposes only the tree-reachable entries
     * publicly, so reach the full {@code PropertyTable._properties} list via reflection; any
     * POI-internal drift just yields an empty map and disables orphan recovery (no worse than
     * the prior tree-only behaviour). Keyed by stream name; first occurrence wins (VBA stream
     * names — {@code dir} and the module names — are unique within a project).
     */
    private static Map<String, DocumentProperty> collectAllStreamProps(POIFSFileSystem fs) {
        Map<String, DocumentProperty> out = new LinkedHashMap<>();
        for (DocumentProperty p : allStreamProps(fs)) {
            // Key case-insensitively: OLE stream names are case-insensitive in MS Office,
            // and malware case-mismatches the dir-stream module name vs the real entry name
            // to evade case-sensitive readers (olevba matches case-insensitively too).
            out.putIfAbsent(p.getName().toLowerCase(Locale.ROOT), p);
        }
        return out;
    }

    /** The raw property-table stream entries, in table order, duplicates included. */
    private static List<DocumentProperty> allStreamProps(POIFSFileSystem fs) {
        List<DocumentProperty> out = new java.util.ArrayList<>();
        try {
            Field ptField = POIFSFileSystem.class.getDeclaredField("_property_table");
            ptField.setAccessible(true);
            Object pt = ptField.get(fs);
            Field propsField = pt.getClass().getDeclaredField("_properties");
            propsField.setAccessible(true);
            Object raw = propsField.get(pt);
            if (!(raw instanceof List<?>)) {
                return out;
            }
            for (Object o : (List<?>) raw) {
                if (!(o instanceof Property)) {
                    continue;
                }
                Property p = (Property) o;
                if (p.isDirectory()) {
                    continue;
                }
                String name = p.getName();
                if (name == null || name.isEmpty() || !(p instanceof DocumentProperty)) {
                    continue;
                }
                out.add((DocumentProperty) p);
            }
        } catch (Throwable t) {
            // POI internals not accessible (version drift / module restriction) — recovery off.
        }
        return out;
    }

    /** Read a stream's bytes directly from its property (start block + size), bypassing the
     *  directory tree, so an orphaned stream is still readable. */
    private static byte[] readPropBytes(POIFSFileSystem fs, DocumentProperty prop,
                                       Bounds bounds) {
        try {
            int size = prop.getSize();
            if (size < 0 || size > bounds.max()) {
                // Dropping the stream discards the WHOLE module source. Say so.
                if (size > bounds.max()) {
                    bounds.mark("VBA stream exceeded the size bound and was dropped");
                }
                return null;
            }
            POIFSDocument doc = new POIFSDocument(prop, fs);
            byte[] data = new byte[size];
            try (DocumentInputStream dis = new DocumentInputStream(doc)) {
                int read = 0;
                while (read < data.length) {
                    int n = dis.read(data, read, data.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    // ── pre-flight projection ─────────────────────────────────────────────────

    /**
     * Upper bound on the bytes this project's module streams can decompress to, derived from
     * MS-OVBA chunk headers alone -- no decompression, no allocation proportional to the output.
     * Each chunk yields at most 4096 bytes (§2.4.1.1.3), so counting chunk headers bounds the
     * total. Returns as soon as {@code ceiling} is passed, so a bomb costs a partial walk.
     *
     * <p>This exists so a caller can decide whether it is safe to run a reader that has NO size
     * bound. POI's {@link org.apache.poi.poifs.macros.VBAMacroReader} decompresses every module
     * into memory with an unbounded {@code IOUtils.toByteArray}; a small file whose modules
     * decompress to hundreds of megabytes takes the worker's heap with it, and a bound applied
     * after that read cannot prevent it. The projection has to happen first.
     *
     * <p>KNOWN RESIDUAL, stated rather than papered over: a module whose MODULEOFFSET is out of
     * range is skipped here, because there is no container start to walk from. POI reacts to the
     * same input by brute-force searching the stream for a decompressible offset, and that search
     * is not covered by this projection. The exposure is therefore documents with a malformed
     * MODULEOFFSET whose stream nonetheless decompresses hugely from some other offset. Failing
     * closed on those would cost real recall (the bounded reader skips such modules entirely, so
     * we would lose what POI recovers), so the trade is deliberate. See {@code VbaBudgetTest}.
     *
     * @return the projected byte count, or a value greater than {@code ceiling} as soon as the
     *         walk passes it (the exact total above the ceiling is not computed)
     */
    public static long projectDecompressedBytes(POIFSFileSystem fs, long ceiling) {
        long total = 0;
        // The probe must be at least as permissive as the ceiling it is testing: with the default
        // 10 MB per-stream cap it would refuse to read a larger dir stream, project 0, and clear
        // POI to run unbounded on exactly the document the operator raised the cap for. Its marks
        // are discarded -- a projection must never set a flag.
        int probeStream = (int) Math.min(Integer.MAX_VALUE, Math.max(MAX_STREAM_BYTES, ceiling));
        Bounds probe = new Bounds(probeStream, Long.MAX_VALUE / 4);
        try {
            for (DirectoryNode vbaDir : findVBADirs(fs.getRoot(), probe)) {
                byte[] dirRaw = readStream(vbaDir, "dir", probe);
                if (dirRaw == null) {
                    continue;
                }
                Set<String> described = new java.util.HashSet<>();
                List<ModuleRef> refs = parseDir(decompress(dirRaw, 0, probe), probe).modules;
                if (refs.size() >= MAX_MODULE_REFS) {
                    // Our OWN cap truncated the walk, and POI has no counterpart to it: its
                    // processDirStream registers every MODULEOFFSET with no count limit. A partial
                    // sum is therefore not an upper bound on what POI will decompress -- 4,096
                    // trivial refs followed by a bomb projected ~18 MB against a 32 MB ceiling and
                    // POI expanded ~819 MB. Capping what we READ is a deliberate loss with a mark;
                    // capping what we PROJECT has to fail closed instead.
                    return ceiling + 1;
                }
                for (ModuleRef ref : refs) {
                    DocumentEntry de = null;
                    for (String candidate : ref.candidates()) {
                        de = findEntry(vbaDir, candidate);
                        if (de != null) {
                            described.add(de.getName().toLowerCase(Locale.ROOT));
                            break;
                        }
                    }
                    if (de == null || ref.offset < 3 || ref.offset >= de.getSize()) {
                        continue;
                    }
                    total += projectStream(de, ref.offset, ceiling - total, probe);
                    if (total > ceiling) {
                        return total;
                    }
                }
                // Streams the dir stream never DESCRIBES are still read by POI: finding no module
                // entry for one, readModuleFromDocumentStream stores its RAW bytes and getContent()
                // returns them as macro source. Projecting only the described refs counted those as
                // zero, so the gate's guarantee was false for them -- measured, a 41 MB document
                // projected at 4,096 bytes and POI returned 41,943,056 chars. Charge each one what
                // POI actually retains, its raw size, using POI's own skip list so the performance
                // caches every real project carries are not charged.
                total += projectUndescribedStreams(vbaDir, described);
                if (total > ceiling) {
                    return total;
                }
            }
        } catch (Exception | OutOfMemoryError e) {
            // A project we cannot walk is one we cannot vouch for. Report it as over the
            // ceiling rather than silently clearing an unbounded reader to run on it.
            return ceiling + 1;
        }
        return total;
    }

    /**
     * Cap on how many brute-force container starts one stream may contribute to the projection.
     * Beyond it the projection fails closed rather than spending unbounded time enumerating.
     */
    static final int MAX_BRUTE_FORCE_CANDIDATES = 256;

    /**
     * Projected upper bound for one module stream, charging 4096 bytes per MS-OVBA chunk.
     *
     * <p>When the declared offset points at a compressed container this is a walk of that
     * container's chunk headers. When it does NOT -- an in-range offset landing on a byte that is
     * not 0x01 -- POI does something else entirely, and the projection has to model THAT or it is
     * not an upper bound on what POI will do: {@code findCompressedStreamWBruteForce} scans every
     * byte position for 0x01 followed by a valid chunk header and decompresses at each one with an
     * unbounded {@code IOUtils.toByteArray}, keeping a single result but allocating all of them.
     *
     * <p>That gap was a measured BYPASS of the whole gate, not a theoretical one: a 399 KB document
     * whose module offset lands on filler and whose stream carries 16 bomb containers projected at
     * 393 KB -- comfortably inside the 32 MB budget -- and then produced 16,769,024 chars from POI.
     * Summing the candidates closes it.
     */
    private static long projectStream(DocumentEntry de, int offset, long remainingCeiling,
                                      Bounds probe) {
        byte[] raw = readEntryBytes(de, probe);
        if (raw == null) {
            // Unreadable at the probe's own bound: we cannot vouch for it.
            return remainingCeiling + 1;
        }
        if (offset < 0 || offset >= raw.length) {
            return 0;
        }
        if ((raw[offset] & 0xFF) == 0x01) {
            ContainerWalk walk = projectContainer(raw, offset, remainingCeiling);
            if (walk.clean) {
                return walk.bytes; // POI decompresses this and never brute-forces
            }
            // A container DOES start at the declared offset, but POI throws part-way through it and
            // falls back to the brute-force search all the same: readModuleFromDocumentStream wraps
            // the whole from-offset read in catch(IllegalArgumentException | IllegalStateException).
            // Branching only on "no 0x01 at the offset" modelled a narrower condition than POI's and
            // left the gate open -- measured, a 120 KB stream projected 4,096 bytes and POI returned
            // 81,880,002 chars. Charge both what the partial walk yields and what the search can
            // find, since POI transiently allocates both.
            return walk.bytes + projectBruteForceCandidates(raw, remainingCeiling - walk.bytes);
        }
        // No container at the offset: decompress() returns every byte from it onward, and POI
        // brute-forces the whole stream.
        long tail = raw.length - offset;
        return tail + projectBruteForceCandidates(raw, remainingCeiling - tail);
    }

    /**
     * Raw sizes of the streams POI would read but the dir stream never described.
     *
     * <p>Mirrors POI's own filter in {@code readMacros(DirectoryNode, ModuleMap)}: it skips
     * {@code dir}, anything starting {@code __SRP}, and anything starting {@code _VBA_PROJECT}, and
     * reads every other document in the storage. Those three are the caches a real project always
     * carries, so charging them would redirect ordinary documents.
     */
    private static long projectUndescribedStreams(DirectoryNode vbaDir, Set<String> described) {
        long total = 0;
        for (org.apache.poi.poifs.filesystem.Entry e : vbaDir) {
            if (!(e instanceof DocumentEntry)) {
                continue;
            }
            String name = e.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            if (described.contains(lower)
                    || "dir".equalsIgnoreCase(name)
                    || lower.startsWith("__srp")
                    || lower.startsWith("_vba_project")) {
                continue;
            }
            total += Math.max(0, ((DocumentEntry) e).getSize());
        }
        return total;
    }

    /** Outcome of a chunk-header walk: how much it yields, and whether POI would accept it. */
    private static final class ContainerWalk {
        long bytes;
        /** False when POI's RLEDecompressingInputStream would THROW, sending it to brute force. */
        boolean clean = true;
    }

    /**
     * Chunk-header walk over {@code raw} from {@code start}, mirroring POI's
     * {@code RLEDecompressingInputStream.readChunk} decision by decision, because any divergence
     * between this model and POI's behaviour is a hole in the gate.
     *
     * <p>Three things it must get right, each of which was wrong when it charged a flat 4096 per
     * header and ran to the end of the buffer: a {@code 0x0000} header is END OF STREAM for POI
     * (readChunk returns -1), not a chunk; an UNCOMPRESSED chunk yields its DECLARED length, not
     * 4096; and a bad signature makes POI THROW rather than skip, which is what sends it to the
     * brute-force search. Charging 4096 per 3 bytes of trailing slack over-projected by up to
     * 1365x, which redirects benign documents away from POI -- a recall loss, the mirror image of
     * the bypass.
     */
    private static ContainerWalk projectContainer(byte[] raw, int start, long remainingCeiling) {
        ContainerWalk walk = new ContainerWalk();
        int i = start + 1; // skip the container signature byte
        while (true) {
            if (i + 2 > raw.length) {
                return walk; // POI's readShort returns -1 here: clean end of stream
            }
            int w = (raw[i] & 0xFF) | ((raw[i + 1] & 0xFF) << 8);
            i += 2;
            if (w == 0) {
                return walk; // POI treats a zero header as end of stream
            }
            if ((w & 0x7000) != 0x3000) {
                walk.clean = false; // POI throws IllegalArgumentException -> brute force
                return walk;
            }
            int chunkDataLen = (w & 0x0FFF) + 1;
            boolean compressed = (w & 0x8000) != 0;
            if (chunkDataLen > raw.length - i) {
                // POI's readFully comes up short: IllegalStateException for a raw chunk, a -1
                // return for a compressed one. Treat as not-clean, which is the safe direction.
                walk.clean = false;
                walk.bytes += compressed ? 4096 : Math.max(0, raw.length - i);
                return walk;
            }
            walk.bytes += compressed ? 4096 : chunkDataLen;
            if (walk.bytes > remainingCeiling) {
                return walk;
            }
            i += chunkDataLen;
        }
    }

    /**
     * Sum of what POI's brute-force search could decompress from {@code raw}: it scans EVERY byte
     * position for 0x01 followed by a valid chunk header and decompresses at each one.
     *
     * @return a value greater than {@code remainingCeiling} when the candidate count is itself
     *         beyond what we are willing to enumerate
     */
    private static long projectBruteForceCandidates(byte[] raw, long remainingCeiling) {
        long total = 0;
        int candidates = 0;
        for (int i = 0; i + 2 < raw.length; i++) {
            if ((raw[i] & 0xFF) != 0x01) {
                continue;
            }
            int w = (raw[i + 1] & 0xFF) | ((raw[i + 2] & 0xFF) << 8);
            if (w <= 0 || (w & 0x7000) != 0x3000) {
                continue; // the same candidate test POI applies
            }
            if (++candidates > MAX_BRUTE_FORCE_CANDIDATES) {
                return remainingCeiling + 1;
            }
            total += projectContainer(raw, i, remainingCeiling - total).bytes;
            if (total > remainingCeiling) {
                return total;
            }
        }
        return total;
    }

    /** Read a document entry whole, refusing anything over the probe's per-stream bound. */
    private static byte[] readEntryBytes(DocumentEntry de, Bounds probe) {
        try {
            int size = de.getSize();
            if (size < 0 || size > probe.max()) {
                return null;
            }
            byte[] data = new byte[size];
            try (DocumentInputStream dis = new DocumentInputStream(de)) {
                int read = 0;
                while (read < data.length) {
                    int n = dis.read(data, read, data.length - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    public static Map<String, String> readMacros(DirectoryNode root) throws IOException {
        return readMacros(root, new Bounds());
    }

    public static Map<String, String> readMacros(DirectoryNode root, Bounds bounds)
            throws IOException {
        ModuleSink result = new ModuleSink();
        collectFromTree(root, bounds, result);
        return result.out;
    }

    private static void collectFromTree(DirectoryNode root, Bounds bounds,
                                        ModuleSink result) throws IOException {
        // EVERY VBA storage, not just the first. A project may legally hold more than one, and
        // stopping at the first let a decoy storage placed where the reader looks first hide the
        // real one -- POI reads them all, so stopping early lost macros POI would have found.
        for (DirectoryNode vbaDir : findVBADirs(root, bounds)) {
            if (!readOneStorage(vbaDir, bounds, result)) {
                return; // document budget exhausted
            }
        }
    }

    /** @return false when the document budget is exhausted and the caller must stop. */
    private static boolean readOneStorage(DirectoryNode vbaDir, Bounds bounds,
                                          ModuleSink result) throws IOException {
        byte[] dirRaw = readStream(vbaDir, "dir", bounds);
        if (dirRaw == null) {
            return true;
        }
        DirInfo dir = parseDir(decompress(dirRaw, 0, bounds), bounds);
        Charset charset = dir.charset;
        for (ModuleRef ref : dir.modules) {
            try {
                byte[] raw = null;
                for (String candidate : ref.candidates()) {
                    raw = readStream(vbaDir, candidate, bounds);
                    if (raw != null) {
                        break;
                    }
                }
                if (raw == null || ref.offset < 3 || ref.offset >= raw.length) {
                    continue;
                }
                byte[] src = decompress(raw, ref.offset, bounds);
                String text = new String(src, charset);
                if (!text.isBlank() && !retain(result, ref.key(), text, bounds)
                        && !retainIndicators(result, ref.key(), text, bounds)) {
                    return false;
                }
            } catch (Exception ignore) {
                // skip individual module failures — don't abort the whole extraction
            }
        }
        return true;
    }

    // ── dir stream parser ─────────────────────────────────────────────────────

    /** What a dir stream tells us: which modules exist, and which codepage names/source use. */
    private static final class DirInfo {
        final List<ModuleRef> modules = new java.util.ArrayList<>();
        /**
         * PROJECTCODEPAGE (0x0003). The record was parsed and then IGNORED: every name and every
         * module body was decoded windows-1252 regardless. POI honours it
         * ({@code modules.charset = CodePageUtil.codepageToEncoding(codepage)}), so a project
         * declaring e.g. cp1251 had its Cyrillic module names decoded to mojibake here, matched no
         * OLE entry, and the module was dropped with NO mark -- a silent total loss on the two
         * paths that exist for malware, for documents POI reads fine.
         */
        Charset charset = WINDOWS_1252;
    }

    private static DirInfo parseDir(byte[] dir, Bounds bounds) {
        DirInfo info = new DirInfo();
        List<ModuleRef> modules = info.modules;

        String currentName = null;
        String currentStreamName = null;
        int pos = 0;

        while (pos + 6 <= dir.length) {
            int recId  = ((dir[pos] & 0xFF)) | ((dir[pos + 1] & 0xFF) << 8);
            int recLen = ((dir[pos + 2] & 0xFF)) | ((dir[pos + 3] & 0xFF) << 8)
                       | ((dir[pos + 4] & 0xFF) << 16) | ((dir[pos + 5] & 0xFF) << 24);
            pos += 6;

            if (recLen < 0 || recLen > dir.length - pos) {
                // Oversized / invalid record — Mac-Word writes non-standard reference
                // entries here. Resync by advancing TWO bytes (pos has already moved +6, so -4 is a
                // net +2), i.e. on the 2-byte record-ID grid rather than byte by byte.
                //
                // A reviewer correctly observed that this contradicts the older comment's claim of a
                // byte-by-byte scan, and that half of all offsets are therefore unreachable. Changing
                // it to a true byte-by-byte scan (pos -= 5) was MEASURED AGAINST THE CORPUS AND
                // REVERTED: on one real document it cut extraction from 1,431,021 chars to 116,894 --
                // 631 of 659 procedures and 402 of 427 module headers lost, with the surviving text
                // LESS printable (0.909 -> 0.708). A one-byte stride finds spurious "plausible"
                // records in junk, locks onto a wrong alignment and abandons the real records after
                // it; the two-byte stride stays on the grid real record IDs actually sit on.
                //
                // So the stride is deliberate and the OLD COMMENT was the defect. Records beginning
                // at an odd offset relative to a failure are genuinely unreachable here, which is a
                // known limit of this resync, not an oversight.
                pos -= 4;
                continue;
            }

            byte[] recData = new byte[recLen];
            System.arraycopy(dir, pos, recData, 0, recLen);
            pos += recLen;

            try {
                switch (recId) {
                    case REC_PROJECTCODEPAGE:
                        info.charset = codepageCharset(recData, info.charset);
                        break;
                    case REC_MODULENAME:
                        currentName = new String(recData, info.charset);
                        break;
                    case REC_MODULESTREAMNAME:
                        // MODULENAME is a LABEL; MODULESTREAMNAME (which used to be ignored
                        // entirely) is what says which OLE stream holds the source. When an
                        // author makes them differ, resolving by MODULENAME finds no stream and
                        // the module's whole body is lost with no signal -- and POI, which reads
                        // this record, finds the macro that we then report as absent.
                        currentStreamName = new String(recData, info.charset);
                        break;
                    case REC_MODULEOFFSET:
                        if (modules.size() >= MAX_MODULE_REFS) {
                            bounds.mark("the dir stream describes more than " + MAX_MODULE_REFS
                                    + " modules; later ones were not read");
                            return info;
                        }
                        if ((currentName != null || currentStreamName != null) && recLen == 4) {
                            int offset = (recData[0] & 0xFF) | ((recData[1] & 0xFF) << 8)
                                       | ((recData[2] & 0xFF) << 16) | ((recData[3] & 0xFF) << 24);
                            modules.add(new ModuleRef(currentName, currentStreamName, offset));
                        }
                        break;
                    case REC_MODULETERM:
                        currentName = null;
                        currentStreamName = null;
                        break;
                    default:
                        // skip unknown / reserved records leniently
                        break;
                }
            } catch (Exception ignore) {
                // per-record failure → continue
            }
        }
        return info;
    }

    /** Resolve a PROJECTCODEPAGE record to a charset, keeping the default if it is unusable. */
    private static Charset codepageCharset(byte[] recData, Charset fallback) {
        if (recData == null || recData.length < 2) {
            return fallback;
        }
        int codepage = (recData[0] & 0xFF) | ((recData[1] & 0xFF) << 8);
        try {
            return Charset.forName(
                    org.apache.poi.util.CodePageUtil.codepageToEncoding(codepage, true));
        } catch (Exception e) {
            return fallback; // unknown/unsupported codepage: decode as before
        }
    }

    // ── MS-OVBA §2.4.1 decompressor ──────────────────────────────────────────

    /** Decompress from the beginning of {@code compressed}. */
    public static byte[] decompress(byte[] compressed) throws IOException {
        return decompress(compressed, 0, new Bounds());
    }

    /**
     * Decompress starting at {@code startOffset} within {@code compressed}.
     * The byte at {@code startOffset} must be {@code 0x01} (signature).
     */
    static byte[] decompress(byte[] compressed, int startOffset) throws IOException {
        return decompress(compressed, startOffset, new Bounds());
    }

    static byte[] decompress(byte[] compressed, int startOffset, Bounds bounds)
            throws IOException {
        if (compressed == null || compressed.length <= startOffset) {
            return new byte[0];
        }
        if ((compressed[startOffset] & 0xFF) != 0x01) {
            // Not compressed — return raw bytes from offset
            byte[] raw = new byte[compressed.length - startOffset];
            System.arraycopy(compressed, startOffset, raw, 0, raw.length);
            return raw;
        }

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int i = startOffset + 1; // skip signature byte
        // A skipped chunk is only evidence loss if real data followed it; see the signature check.
        boolean sawBadSignature = false;

        while (i < compressed.length) {
            // CompressedChunkHeader: 2 bytes LE
            if (i + 2 > compressed.length) {
                break;
            }
            int chunkHeader = ((compressed[i] & 0xFF)) | ((compressed[i + 1] & 0xFF) << 8);
            i += 2;

            boolean isCompressed = (chunkHeader & 0x8000) != 0;
            int signature = (chunkHeader >> 12) & 0x07;
            // MS-OVBA §2.4.1.1.5: the stored CompressedChunkSize is the chunk's TOTAL length
            // INCLUDING its own 2-byte header, minus 3. The data following the header is
            // therefore field + 1 bytes -- which is what POI's RLEDecompressingInputStream
            // reads. This used to compute field + 3, overshooting every chunk by 2 bytes; the
            // next read then started 2 bytes past the following chunk header, failed the
            // signature check below, and abandoned the remainder of the stream. Any VBA module
            // needing more than one chunk (more than ~4 KB of source) was silently truncated
            // to its first chunk, on the two paths that exist precisely FOR malware:
            // POI-rejecting projects and orphaned VBA storages.
            int chunkDataLen = (chunkHeader & 0x0FFF) + 1;

            if (signature != 0b011) {
                // A chunk we cannot interpret. POI throws here; being lenient and skipping it is
                // the right call for triage, but the skipped bytes may have held macro source, and
                // dropping that silently is the same evidence loss as truncating.
                //
                // Do NOT report it yet, though: a module stream commonly carries slack after its
                // last chunk, and reading that slack as a chunk header fails this check with
                // nothing withheld. Measured on a 6,574-document macro corpus, marking here
                // unconditionally flagged ~1.3% of real documents. Remember it instead and report
                // only if a LATER chunk decodes -- which proves we were inside real data rather
                // than past the end of it.
                sawBadSignature = true;
                i += Math.min(chunkDataLen, compressed.length - i);
                continue;
            }
            if (sawBadSignature) {
                bounds.mark("VBA chunk header had an invalid signature mid-stream; up to 4096 "
                        + "bytes of macro source were skipped");
                sawBadSignature = false;
            }

            int chunkEnd = Math.min(i + chunkDataLen, compressed.length);

            if (!isCompressed) {
                // Raw chunk: the header declares its length (4096 for a well-formed chunk).
                // Honour the declared length rather than assuming 4096, so a short final
                // chunk stays aligned with the next header instead of over-reading into it.
                int rawLen = Math.min(chunkDataLen, compressed.length - i);
                out.write(compressed, i, rawLen);
                i += rawLen;
            } else {
                // Compressed chunk: process flag groups.
                // Each compressed chunk decompresses to at most 4096 bytes (MS-OVBA §2.4.1.3.2).
                // Use a fixed-size local buffer to avoid O(n²) toByteArray() calls.
                byte[] chunkBuf = new byte[4096];
                int chunkOut = 0;

                while (i < chunkEnd && chunkOut < 4096) {
                    int flagByte = compressed[i++] & 0xFF;
                    for (int bit = 0; bit < 8 && i < chunkEnd && chunkOut < 4096; bit++) {
                        if ((flagByte & (1 << bit)) == 0) {
                            // RawToken
                            chunkBuf[chunkOut++] = compressed[i++];
                        } else {
                            // CopyToken
                            if (i + 2 > chunkEnd) {
                                break;
                            }
                            int copyToken = ((compressed[i] & 0xFF)) |
                                            ((compressed[i + 1] & 0xFF) << 8);
                            i += 2;

                            // Compute copy-token bit count per MS-OVBA §2.4.1.3.19.1:
                            //   bit_count = max(4, ceil(log2(decompressedChunkSize)))
                            // In integer arithmetic: ceil(log2(n)) = 32 - nlz(n-1) for n>=1.
                            int bc = (chunkOut <= 1) ? 4
                                    : Math.max(4,
                                            32 - Integer.numberOfLeadingZeros(chunkOut - 1));
                            int lm = 0xFFFF >> bc;
                            int om = ~lm & 0xFFFF;
                            int length = (copyToken & lm) + 3;
                            int offset = ((copyToken & om) >>> (16 - bc)) + 1;

                            // Copy from already-decompressed chunk buffer (supports overlap).
                            int readStart = chunkOut - offset;
                            if (readStart < 0) {
                                // Back-reference before chunk start — skip token.
                                continue;
                            }
                            for (int j = 0; j < length && chunkOut < 4096; j++) {
                                chunkBuf[chunkOut] = chunkBuf[readStart + j];
                                chunkOut++;
                            }
                        }
                    }
                }
                out.write(chunkBuf, 0, chunkOut);
                i = chunkEnd; // ensure we move past the chunk
            }

            if (out.size() > bounds.max()) {
                // Cutting here leaves a PARTIAL macro body that still reads as complete -- but
                // only if there was in fact more to read. When the last chunk is what pushed the
                // total past the bound, nothing is being withheld and the flag would be a false
                // positive, which is exactly as harmful as a missing one: a truncation flag on
                // clean documents is indistinguishable from noise, and an analyst learns to
                // ignore it. Check for remaining input before claiming loss.
                if (i < compressed.length) {
                    bounds.mark("VBA decompressed stream exceeded the size bound "
                            + "and was truncated");
                }
                break;
            }
        }
        return out.toByteArray();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Every VBA storage in the tree, at any depth. OOXML {@code vbaProject.bin} has one at root
     * level; OLE2 nests it under {@code Macros} / {@code _VBA_PROJECT_CUR}; a document may hold
     * several. Mirrors POI's {@code findMacros} traversal (which does not recurse INTO a VBA
     * storage), with a depth cap because a crafted CFBF child tree can be cyclic and a count cap
     * so one document cannot enumerate unboundedly. Either cap firing is reported.
     */
    private static List<DirectoryNode> findVBADirs(DirectoryNode root, Bounds bounds) {
        List<DirectoryNode> out = new java.util.ArrayList<>();
        collectVBADirs(root, out, 0, bounds);
        return out;
    }

    private static void collectVBADirs(DirectoryNode node, List<DirectoryNode> out, int depth,
                                       Bounds bounds) {
        if (out.size() >= MAX_VBA_STORAGES) {
            bounds.mark("VBA storage count exceeded " + MAX_VBA_STORAGES
                    + "; later storages were not read");
            return;
        }
        if ("VBA".equalsIgnoreCase(node.getName())) {
            out.add(node);
            return;
        }
        if (depth >= MAX_STORAGE_DEPTH) {
            bounds.mark("VBA storage search stopped at depth " + MAX_STORAGE_DEPTH);
            return;
        }
        for (org.apache.poi.poifs.filesystem.Entry e : node) {
            if (e instanceof DirectoryNode) {
                collectVBADirs((DirectoryNode) e, out, depth + 1, bounds);
            }
        }
    }

    /**
     * Resolve a stream by name, case-INSENSITIVELY. OLE storage names are case-insensitive in
     * Office and olevba matches them that way, so a one-character case difference between the
     * dir stream's module name and the real entry name must not hide a module.
     */
    private static DocumentEntry findEntry(DirectoryNode dir, String name) {
        try {
            return (DocumentEntry) dir.getEntry(name);
        } catch (Exception ignore) {
            // fall through to a case-insensitive scan
        }
        for (org.apache.poi.poifs.filesystem.Entry e : dir) {
            if (e instanceof DocumentEntry && e.getName().equalsIgnoreCase(name)) {
                return (DocumentEntry) e;
            }
        }
        return null;
    }

    private static byte[] readStream(DirectoryNode dir, String name, Bounds bounds) {
        try {
            DocumentEntry de = findEntry(dir, name);
            if (de == null) {
                return null;
            }
            if (de.getSize() > bounds.max()) {
                bounds.mark("VBA stream '" + name
                        + "' exceeded the size bound and was dropped");
                return null;
            }
            byte[] data = new byte[de.getSize()];
            try (DocumentInputStream dis = new DocumentInputStream(de)) {
                int read = 0;
                while (read < data.length) {
                    int n = dis.read(data, read, data.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }
}
