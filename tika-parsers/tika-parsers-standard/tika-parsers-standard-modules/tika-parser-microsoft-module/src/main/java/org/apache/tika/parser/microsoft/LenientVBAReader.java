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

        /** Whether {@code len} more retained bytes still fit in the document budget. */
        boolean hasRoomFor(long len) {
            return retained + len <= maxTotalBytes;
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
            List<ModuleRef> refs = parseDir(decompress(dirRaw, 0, bounds), bounds);
            Charset charset = Charset.forName("windows-1252");
            for (ModuleRef ref : refs) {
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
                    if (!text.isBlank() && !retain(result, ref.key(), text, bounds)) {
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
                for (ModuleRef ref : parseDir(decompress(dirRaw, 0, probe), probe)) {
                    DocumentEntry de = null;
                    for (String candidate : ref.candidates()) {
                        de = findEntry(vbaDir, candidate);
                        if (de != null) {
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
    private static final int MAX_BRUTE_FORCE_CANDIDATES = 256;

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
            return projectContainer(raw, offset, remainingCeiling);
        }
        // decompress() returns every byte from `offset` onward when there is no container there,
        // and POI additionally brute-forces. Charge both.
        long total = raw.length - offset;
        int candidates = 0;
        for (int i = 0; i + 2 < raw.length; i++) {
            if ((raw[i] & 0xFF) != 0x01) {
                continue;
            }
            int w = (raw[i + 1] & 0xFF) | ((raw[i + 2] & 0xFF) << 8);
            if (w <= 0 || (w & 0x7000) != 0x3000) {
                continue; // same candidate test POI applies
            }
            if (++candidates > MAX_BRUTE_FORCE_CANDIDATES) {
                return remainingCeiling + 1;
            }
            total += projectContainer(raw, i, remainingCeiling - total);
            if (total > remainingCeiling) {
                return total;
            }
        }
        return total;
    }

    /** Chunk-header walk over {@code raw} from {@code start}; stops once past the ceiling. */
    private static long projectContainer(byte[] raw, int start, long remainingCeiling) {
        long projected = 0;
        int i = start + 1; // skip the container signature byte
        while (i + 2 <= raw.length) {
            int chunkHeader = (raw[i] & 0xFF) | ((raw[i + 1] & 0xFF) << 8);
            i += 2;
            projected += 4096; // MS-OVBA: one chunk decompresses to at most 4096 bytes
            if (projected > remainingCeiling) {
                return projected;
            }
            i += (chunkHeader & 0x0FFF) + 1;
        }
        return projected;
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
        List<ModuleRef> refs = parseDir(decompress(dirRaw, 0, bounds), bounds);
        Charset charset = Charset.forName("windows-1252");
        for (ModuleRef ref : refs) {
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
                if (!text.isBlank() && !retain(result, ref.key(), text, bounds)) {
                    return false;
                }
            } catch (Exception ignore) {
                // skip individual module failures — don't abort the whole extraction
            }
        }
        return true;
    }

    // ── dir stream parser ─────────────────────────────────────────────────────

    private static List<ModuleRef> parseDir(byte[] dir, Bounds bounds) {
        List<ModuleRef> modules = new java.util.ArrayList<>();

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
                // entries here.  Skip byte-by-byte until we find a plausible record.
                pos -= 4; // back up past the 2-byte record ID, retry from next byte
                continue;
            }

            byte[] recData = new byte[recLen];
            System.arraycopy(dir, pos, recData, 0, recLen);
            pos += recLen;

            try {
                switch (recId) {
                    case REC_MODULENAME:
                        currentName = new String(recData, "windows-1252");
                        break;
                    case REC_MODULESTREAMNAME:
                        // MODULENAME is a LABEL; MODULESTREAMNAME (which used to be ignored
                        // entirely) is what says which OLE stream holds the source. When an
                        // author makes them differ, resolving by MODULENAME finds no stream and
                        // the module's whole body is lost with no signal -- and POI, which reads
                        // this record, finds the macro that we then report as absent.
                        currentStreamName = new String(recData, "windows-1252");
                        break;
                    case REC_MODULEOFFSET:
                        if (modules.size() >= MAX_MODULE_REFS) {
                            bounds.mark("the dir stream describes more than " + MAX_MODULE_REFS
                                    + " modules; later ones were not read");
                            return modules;
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
        return modules;
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
