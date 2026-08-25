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
import org.apache.poi.poifs.property.DirectoryProperty;
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

    /**
     * Compiled-region size above which a source-less module is reported as stomped.
     *
     * <p>8 KB, from the corpus distribution rather than from taste: source-less modules with under
     * 4 KB of compiled code are the empty {@code Sheet1}/{@code ThisWorkbook} stubs every workbook
     * carries (101 of 237 refs), 4-8 KB is mostly more of the same (41), and the genuine population
     * sits above it -- 32 refs at 8-32 KB, 19 at 32-256 KB, 4 above 256 KB, the largest a single
     * module with 1,180,428 bytes of compiled code and no source at all.
     */
    private static final int MIN_STOMPED_COMPILED_BYTES = 8192;

    /** Cap on how many missing-module names are reported; the names go on the metadata. */
    private static final int MAX_UNRESOLVED_REPORTED = 32;

    /** Distinct techniques listed in the inventory before the tail is summarised as a count. */
    private static final int MAX_INVENTORY = 512;

    /** Cap on line identities held for inventory de-duplication, to bound memory. */
    private static final int MAX_COUNTED_LINES = 50_000;

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

    /** Cap on distinct TECHNIQUES held per module; when full the least alarming is evicted. */
    private static final int MAX_TECHNIQUES = 256;

    /**
     * Cap on slices held per technique; when full the least alarming is evicted.
     *
     * <p>Must stay ABOVE what the allowance can ever emit, or it bites for no reason. At 16 it sat
     * BELOW that ceiling ({@code INDICATOR_CHARS_PER_MODULE / MIN_STATEMENT_SLICE} is about 42), so
     * sixteen short lines mentioning a technique filled its group and the real payload was refused
     * -- a case the OLD head-first code kept, because seventeen short lines fit in 2,048 characters
     * and it had no per-technique cap at all. A bound that discards evidence the budget could have
     * held is not a bound, it is a bug. This is a memory guard against a crafted body, and it
     * should only ever engage where the emission budget already would.
     */
    private static final int MAX_SLICES_PER_TECHNIQUE = 64;

    /** Cap on slices tracked for de-duplication, purely to bound memory on a crafted body. */
    private static final int MAX_DEDUP_TRACKED = 20_000;

    /** Cap on indicator-bearing statements held per line, so one crafted line stays O(1). */
    private static final int MAX_STATEMENTS_PER_LINE = 64;

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
        private boolean reported;
        /**
         * Modules the dir stream NAMES but the file does not contain, capped.
         *
         * <p>A project declaring five modules while holding four is not the same document as one
         * declaring four, and the difference was invisible: the ref was skipped and nothing said so.
         * Benign causes exist (a stale record left by a deleted module), but so does the adversarial
         * one -- strip the stream and the source cannot be recovered while the project still looks
         * intact. Either way the analyst should be told the extraction is short of what the file
         * itself advertises.
         *
         * <p>Measured on the 6,574-document corpus before this existed: 139 unresolved refs across
         * 48 documents, 0.73%. Rare enough to be worth saying, common enough to be worth capping.
         */
        private final java.util.List<String> unresolved = new java.util.ArrayList<>();
        /**
         * Modules whose COMPILED code is substantial while their SOURCE is absent -- the VBA
         * "stomping" shape. Word executes the compiled code, so the document runs macros while a
         * source-only extractor reports an empty project.
         *
         * <p>Detection only. Decoding P-code would risk emitting fabricated source, which for a
         * triage fork is worse than emitting nothing; naming the module and its compiled size is
         * enough for an analyst to pull the file.
         */
        private final java.util.List<String> stomped = new java.util.ArrayList<>();

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

        /**
         * What is LEFT of the document ceiling.
         *
         * <p>The gate in front of POI's unbounded reader has to compare its projection against
         * this, not against {@link #totalMax()}: one {@code Bounds} is shared across every macro
         * part of a container, and a projection tested against the FULL ceiling clears once per
         * part no matter how much has already been retained. Measured before this existed: five
         * {@code vbaProject} parts of ~150 KB each, every one comfortably under a 200 KB ceiling,
         * yielded 594,321 characters -- POI ran five separate times and its output was charged
         * nowhere at all.
         */
        long remainingTotal() {
            return Math.max(0, maxTotalBytes - retained);
        }

        /**
         * True the FIRST time only, so surfacing a fired bound on the parent metadata is
         * idempotent. One shared accumulator is reported from every macro part's {@code finally}
         * and {@link #mark} is first-wins, so without this the same detail is added once per part.
         */
        /**
         * Every indicator TECHNIQUE seen in this document, with how often it occurred.
         *
         * <p>Separate from the reserve's text excerpt on purpose, and this is the whole point of
         * the shape. The excerpt is a fixed 2,048 characters per module, so something always has to
         * be dropped and the document's author influences what -- seven rounds of review found
         * seven different ways to make the payload be the thing dropped. An inventory entry costs
         * about twenty bytes and answers the question triage actually asks first ("does this module
         * run powershell?") without ever choosing between techniques. The excerpt stays, as
         * EXAMPLES, and is allowed to be incomplete.
         *
         * <p>Recorded at SCAN time, before any budget, cap or eviction decision. Bounded by
         * MAX_INVENTORY distinct techniques; beyond that the count of omitted ones is reported
         * rather than the omission being silent.
         */
        private final Map<String, Integer> techniques = new LinkedHashMap<>();
        private final Set<Long> countedLines = new java.util.HashSet<>();
        private int techniquesOmitted;

        /**
         * @param identity the LINE this technique was seen on, so the same line counted twice does
         *                 not inflate the tally. readMacros deliberately runs BOTH the tree walk
         *                 and the orphan scan -- a decoy module must not be able to suppress orphan
         *                 recovery -- so both routes reach the same module. retain() collapses the
         *                 duplicate TEXT; without this the inventory reported everything twice
         *                 (400 decoy lines came back as shell=800).
         */
        void noteTechnique(String technique, String identity) {
            if (technique == null || technique.isEmpty()) {
                return;
            }
            if (identity != null && countedLines.size() < MAX_COUNTED_LINES
                    && !countedLines.add(technique.hashCode() * 31L + identity.hashCode())) {
                return;   // this exact line, under this technique, is already counted
            }
            if (techniques.containsKey(technique)) {
                techniques.merge(technique, 1, Integer::sum);
            } else if (techniques.size() < MAX_INVENTORY) {
                techniques.put(technique, 1);
            } else {
                techniquesOmitted++;
            }
        }

        /** Techniques and counts, most frequent first, plus any omitted tail. */
        public String indicatorInventory() {
            if (techniques.isEmpty()) {
                return null;
            }
            java.util.List<Map.Entry<String, Integer>> out =
                    new java.util.ArrayList<>(techniques.entrySet());
            out.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : out) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            if (techniquesOmitted > 0) {
                sb.append(", (+").append(techniquesOmitted).append(" more techniques not listed)");
            }
            return sb.toString();
        }

        /** Record a module named by the dir stream that the file does not contain. */
        void noteUnresolved(String name) {
            if (name == null || name.isEmpty() || unresolved.size() >= MAX_UNRESOLVED_REPORTED) {
                return;
            }
            if (!unresolved.contains(name)) {
                unresolved.add(name);
            }
        }

        /**
         * Record a module that carries compiled code but no source.
         *
         * <p>The threshold matters more than the rule. Every Excel workbook carries empty
         * {@code Sheet1}/{@code ThisWorkbook}/{@code Chart1} modules whose compiled stub is a couple
         * of kilobytes, so a naive "compiled bytes and no source" test reports ordinary documents:
         * measured over 6,574 documents, a 2 KB threshold flagged 237 refs of which 142 were those
         * stubs. The size distribution separates them -- below 4 KB is stub noise, and the genuine
         * population starts around 8 KB and runs to 1.18 MB.
         */
        void noteStomped(String name, long compiledBytes) {
            if (name == null || name.isEmpty() || compiledBytes < MIN_STOMPED_COMPILED_BYTES
                    || stomped.size() >= MAX_UNRESOLVED_REPORTED) {
                return;
            }
            String entry = name + " (" + compiledBytes + " bytes compiled, no source)";
            if (!stomped.contains(entry)) {
                stomped.add(entry);
            }
        }

        /** Modules carrying compiled code but no source; empty for an ordinary document. */
        public java.util.List<String> stompedModules() {
            return java.util.Collections.unmodifiableList(stomped);
        }

        /** Modules named but absent; empty when the project is intact. */
        public java.util.List<String> unresolvedModules() {
            return java.util.Collections.unmodifiableList(unresolved);
        }

        boolean claimReport() {
            if (reported) {
                return false;
            }
            reported = true;
            return true;
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
    /**
     * Admit a candidate into the bounded group map, evicting by SEVERITY when full.
     *
     * <p>The caps here exist to keep a crafted body O(1) in memory. Enforcing them by refusing
     * whatever arrives after the cap is what made them exploitable: the author writes the order, so
     * "the first 512" is "whatever the author put first". When a bound must bite, it drops the
     * least alarming thing held, not the most recent thing seen.
     */
    private static void admitCandidate(Map<String, java.util.List<String>> byToken,
                                       Map<String, java.util.List<Integer>> groupSeverities,
                                       Map<String, Integer> promoted,
                                       Map<String, Integer> tokenSeverity, String token,
                                       String slice, int severity) {
        java.util.List<String> group = byToken.get(token);
        if (group == null) {
            if (byToken.size() >= MAX_TECHNIQUES) {
                String weakest = null;
                int weakestSeverity = Integer.MAX_VALUE;
                for (Map.Entry<String, Integer> e : tokenSeverity.entrySet()) {
                    if (e.getValue() < weakestSeverity) {
                        weakestSeverity = e.getValue();
                        weakest = e.getKey();
                    }
                }
                if (weakest == null || weakestSeverity > severity) {
                    return;   // everything held is MORE alarming than this; keep what we have
                }
                // A TIE displaces. Refusing ties sealed the map permanently: severityOf ceilings
                // at 4, so an author who fills all 256 slots at tier 4 -- 256 invented hostnames
                // with the word "shellexecute" on the line -- locked out every later technique,
                // however alarming, because nothing can outrank the ceiling. Displacing on a tie
                // keeps the map open; the cost is churn among equals, which is the documented
                // equal-severity floor rather than a lockout.
                byToken.remove(weakest);
                groupSeverities.remove(weakest);
                promoted.remove(weakest);
                tokenSeverity.remove(weakest);
            }
            group = new java.util.ArrayList<>();
            byToken.put(token, group);
        }
        // Same fix as the per-line bucket: the severity of a retained slice is remembered, never
        // recomputed. Slices here run to INDICATOR_CHARS_PER_MODULE, so rescanning the group for
        // every candidate had the same quadratic-in-bytes shape.
        java.util.List<Integer> severities = groupSeverities.computeIfAbsent(
                token, k -> new java.util.ArrayList<>());
        if (group.size() < MAX_SLICES_PER_TECHNIQUE) {
            group.add(slice);
            severities.add(severity);
        } else {
            int weakest = 0;
            for (int i = 1; i < severities.size(); i++) {
                if (severities.get(i) < severities.get(weakest)) {
                    weakest = i;
                }
            }
            if (severity > severities.get(weakest)) {
                // Drop the weakest and put the newcomer FIRST, not in the vacated slot.
                //
                // Writing it into the evicted index handed it that slot's position, so a bucket
                // whose only weak entry sat late gave the payload a late index and the allowance
                // expired before its round -- admitted, then never emitted. Severity ordering does
                // not rescue it either, because after the swap every entry ties at the same tier.
                // A slice that DISPLACED another has demonstrated it outranks something present,
                // and that is the one ordering fact here the author does not control.
                group.remove(weakest);
                severities.remove(weakest);
                // Insert AFTER the promotions already made, not at index 0.
                //
                // add(0, ...) made each new promotion shove the previous ones back, so an author
                // could bury an already-promoted payload by submitting more promotions behind it:
                // fill a bucket with 64 weak slices, promote the payload, then promote 63 decoys
                // and the payload drifts from index 0 to index 63. Reported by codex on PR #20,
                // answering a question I had asked it to attack. Promotions now keep the order in
                // which they earned their place, which is the property "displaced something" was
                // supposed to buy.
                int promotedHere = promoted.merge(token, 1, Integer::sum) - 1;
                int at = Math.min(promotedHere, group.size());
                group.add(at, slice);
                severities.add(at, severity);
            }
        }
        tokenSeverity.merge(token, severity, Math::max);
    }

    /**
     * How alarming a TECHNIQUE is in itself, independent of the line that carried it.
     *
     * <p>Used only to break ties between groups whose best lines score the same. A url key carries
     * no scheme text, so it is scored as the scheme it came from rather than falling through to
     * the floor -- which is the bug that made urls rank lowest in an earlier revision.
     */
    private static int techniqueSeverity(String token) {
        if (token.startsWith("url:")) {
            return severityOf("http://");
        }
        if (token.startsWith("unc:")) {
            return severityOf("\\\\host\\share");
        }
        return severityOf(token);
    }

    /** The url starting at {@code at}, up to the first character that cannot be part of one. */
    private static String urlSpanAt(String slice, int at) {
        int end = slice.length();
        for (int i = at; i < slice.length(); i++) {
            char c = slice.charAt(i);
            if (c == '"' || c == '\'' || c == ' ' || c == '\t' || c == '<' || c == '>'
                    || c == ')' || c == ']' || c == ',' || c == ';') {
                end = i;
                break;
            }
        }
        return slice.substring(at, end);
    }

    /**
     * The host a url-ish token points at, as a grouping key -- NOT validation, and never a lookup.
     *
     * <p>Keying every url as one technique killed the flood case but cost the ordinary one: a
     * module citing twenty genuinely different hosts got a single slot for all of them and the
     * corpus lost thirteen complete, well-formed urls. Keying by HOST keeps both properties --
     * four hundred {@code decoy0001..0400.example} paths on one host still collapse to one group,
     * while twenty distinct hosts are twenty facts and are retained as such. An author who answers
     * with four hundred DISTINCT hosts has published four hundred real indicators.
     *
     * <p>{@link java.net.URI} rather than {@code java.net.URL} on purpose: URL's equals/hashCode
     * resolve DNS, and a malware parser must never touch the network. URI is strict and malware
     * urls frequently are not legal URIs (a corpus example carries a literal {@code |}), so a
     * failed parse falls back to the raw authority span rather than discarding the grouping.
     */
    static String hostOf(String token) {
        try {
            String host = new java.net.URI(token).getHost();
            if (host != null && !host.isEmpty()) {
                return host.toLowerCase(Locale.ROOT);
            }
        } catch (java.net.URISyntaxException | IllegalArgumentException ignore) {
            // malformed by RFC, which is common in real samples: fall through to the span scan
        }
        // UNC first: \\server\share. URI throws on the backslashes, and the generic fallback below
        // used to hit the leading separator at index 0 and yield an empty host -- so EVERY UNC path
        // in existence keyed as the literal "url" and a hundred distinct servers shared one group.
        // That is the inversion of what host keying is for, on the indicator class where the host
        // is the whole fact.
        if (token.startsWith("\\\\")) {
            int from = 2;
            int to = token.length();
            for (int i = from; i < token.length(); i++) {
                char c = token.charAt(i);
                if (c == '\\' || c == '/' || c == '"' || c == '\'') {
                    to = i;
                    break;
                }
            }
            String unc = token.substring(from, to).toLowerCase(Locale.ROOT);
            return unc.isEmpty() ? "url" : unc;
        }
        int start = token.indexOf("://");
        start = start < 0 ? 0 : start + 3;
        int end = token.length();
        for (int i = start; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '/' || c == '\\' || c == '?' || c == '#' || c == '"' || c == '\'') {
                end = i;
                break;
            }
        }
        String host = token.substring(start, end).toLowerCase(Locale.ROOT);
        return host.isEmpty() ? "url" : host;
    }

    /**
     * The most alarming indicator token a slice carries, normalised into a technique key.
     *
     * <p>Every url collapses to one key on purpose: a module listing four hundred distinct
     * addresses is making ONE claim about itself, and letting each address hold its own slot is
     * exactly how a flood of them crowds out a lone {@code powershell}.
     */
    private static String primaryIndicatorToken(String slice, java.util.regex.Matcher m) {
        // CORRECTION, because the note that stood here was wrong and was load-bearing. It claimed
        // VBA_INDICATOR's alternation matches `shell` INSIDE "powershell", so a powershell payload
        // would key as "shell". Two reviewers independently disproved it: Java alternation is
        // leftmost-first PER START POSITION, and at the 'p' the `powershell` branch matches. On
        // `Shell "powershell -enc X"` the matches are `Shell` and `powershell`, and this method
        // returns "powershell". A dedicated key was therefore never needed -- but the reason
        // recorded for not adding one was false, and the next person would have reasoned from it.
        //
        // What IS still open: within ONE technique at EQUAL severity, the first
        // MAX_SLICES_PER_TECHNIQUE win, so a module with more than sixteen distinct powershell
        // invocations reports sixteen of them and the rest are chosen by position. That is a much
        // smaller exposure -- the module is unambiguously flagged and the analyst has sixteen
        // examples -- but it is not closed, and it is the one place a positional rule survives.
        String best = "";
        int bestSeverity = -1;
        m.reset(slice);
        while (m.find()) {
            String token = m.group().toLowerCase(Locale.ROOT);
            int severity = severityOf(token);
            if (token.contains("://") || token.startsWith("\\\\")) {
                // VBA_INDICATOR matches only the SCHEME ("http://"), so the match itself carries no
                // host. Widen to the end of the url before keying, or every url keys identically
                // and host grouping is a silent no-op -- which is exactly what it was until this
                // line existed, with the tests passing throughout because behaviour never changed.
                // Label UNC distinctly from http. Both key by host, but they are different facts
                // to an analyst reading the inventory: one is a fetch, the other is lateral
                // movement or staging on the network.
                boolean unc = token.startsWith("\\\\");
                token = (unc ? "unc:" : "url:") + hostOf(urlSpanAt(slice, m.start()));
                severity = 3;
            }
            if (severity > bestSeverity) {
                bestSeverity = severity;
                best = token;
            }
        }
        return best;
    }

    /**
     * How much an indicator-bearing slice is worth when the reserve cannot hold them all.
     *
     * <p>Tiers, highest first: outbound-execution and download primitives; process launch and
     * late binding; everything else the indicator pattern matches (Environ, Chr, a bare UNC).
     * The point is not precision -- it is that an author who wants to crowd out a payload must
     * now flood with indicators as alarming as the payload.
     */
    private static int severityOf(String slice) {
        String s = slice.toLowerCase(Locale.ROOT);
        // EXECUTION and download primitives first. A lone powershell must outrank any number of
        // bare urls: a url is data, and a module can cite four hundred of them, but the primitive
        // that runs something is the rarer and more decisive fact for triage. Ranking urls level
        // with execution let a flood of hosts bury the one call that matters.
        //
        // Tested on the SLICE rather than the matched token, so a line is scored by everything it
        // contains. (An earlier comment here claimed powershell "never matches as a token" because
        // the alternation finds `shell` inside it -- that is false; see primaryIndicatorToken.)
        if (s.contains("powershell") || s.contains("urldownloadtofile") || s.contains("cmd.exe")
                || s.contains("rundll32") || s.contains("shellexecute")) {
            return 4;
        }
        if (s.contains("shell") || s.contains("wscript") || s.contains("winhttp")
                || s.contains("xmlhttp") || s.contains("msxml2") || s.contains("callbyname")
                || s.contains("declare")) {
            return 3;
        }
        if (s.contains("\\\\") && s.indexOf("\\\\") + 2 < s.length()) {
            // A UNC path is a staging, drop or exfil target -- a movement primitive, and one of
            // the highest-value facts a macro can carry. It used to fall through to the floor,
            // BELOW an ordinary http link, so it was the first thing evicted and the last thing
            // emitted. Ranked with the other capability indicators.
            return 3;
        }
        if (s.contains("://") || s.contains("createobject") || s.contains("getobject")) {
            return 2;
        }
        return 1;
    }

    /** Marks a gap where {@link #keepIndicatorStatements} dropped part of an over-long line. */
    private static final String STATEMENT_CUT_MARKER = " [TIKA-VBA-STMT-CUT] ";

    /** Narrowest statement slice worth emitting. */
    private static final int MIN_STATEMENT_SLICE = 48;

    /**
     * The parts of an over-long line worth keeping: the STATEMENTS that carry an indicator.
     *
     * <p>VBA separates statements on one physical line with {@code :}, so
     * {@code CreateObject("DECOY") : <6 KB of padding> : Shell "..."} is three statements, not one
     * line. Selecting by structure rather than by position is what makes this
     * order-independent: every indicator-bearing statement is a candidate no matter where the
     * author put it, and the budget is shared evenly between them instead of being spent
     * left-to-right. A character window anchored on match positions was tried on the sibling path
     * and defeated three times running -- first match, then last match, then evenly-spaced
     * anchors, each beaten by an author who moved the payload somewhere the rule did not look.
     *
     * <p>Colons inside string literals do not separate statements, so {@code Shell "cmd: /c"} stays
     * one statement; VBA escapes a quote by doubling it, which the scan honours.
     */
    private static String keepIndicatorStatements(String line, int budget,
                                                  java.util.regex.Matcher m) {
        if (line.length() <= budget) {
            return line;
        }
        java.util.List<String> hits = new java.util.ArrayList<>();
        java.util.List<Integer> hitSeverities = new java.util.ArrayList<>();
        boolean inQuote = false;
        int start = 0;
        for (int i = 0; i <= line.length(); i++) {
            boolean atEnd = i == line.length();
            char c = atEnd ? ':' : line.charAt(i);
            if (!atEnd && c == '"') {
                // A doubled quote is an escaped quote INSIDE the literal, not a close-then-open.
                if (inQuote && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    i++;
                    continue;
                }
                inQuote = !inQuote;
            } else if ((c == ':' || c == '&') && !inQuote) {
                // '&' as well as ':'. VBA chains statements with ':' but CONCATENATES with '&',
                // and concatenation is the more common way a long line is actually built --
                // `u = "<3 KB of padding>" & "powershell -enc ..."`. Splitting only on ':' meant
                // such a line had exactly one "statement" (the whole line) and fell through to the
                // prefix cut this method exists to replace: measured, the ':'-chained twin kept its
                // payload and the '&'-joined one did not. Splitting on '&' costs only that hex
                // literals (&H1234) and type suffixes produce a few extra short fragments, which
                // the indicator filter then discards.
                String stmt = line.substring(start, atEnd ? line.length() : i).trim();
                if (!stmt.isEmpty() && m.reset(stmt).find()) {
                    // Bounded by EVICTION, not by stopping. Breaking after the first n statements
                    // let an author put n decoy statements ahead of the payload on one line and
                    // have it never collected at all. Reported by codex on PR #20.
                    // Severity is computed ONCE per statement and carried alongside it. Rescanning
                    // the retained bucket for every later candidate re-lowercased and re-scanned
                    // every retained string: with 64 retained ~16 KB statements and 20,000 later
                    // candidates that is tens of gigabytes of scanning -- ~35 s measured -- inside
                    // the very code meant to bound hostile input. Reported by codex on PR #20.
                    int stmtSeverity = severityOf(stmt);
                    if (hits.size() < MAX_STATEMENTS_PER_LINE) {
                        hits.add(stmt);
                        hitSeverities.add(stmtSeverity);
                    } else {
                        int weakest = 0;
                        for (int k = 1; k < hitSeverities.size(); k++) {
                            if (hitSeverities.get(k) < hitSeverities.get(weakest)) {
                                weakest = k;
                            }
                        }
                        if (stmtSeverity > hitSeverities.get(weakest)) {
                            hits.set(weakest, stmt);
                            hitSeverities.set(weakest, stmtSeverity);
                        }
                    }
                }
                start = i + 1;
            }
        }
        if (hits.isEmpty()) {
            // Indicator matched the line but no single statement carries it -- it straddles a
            // separator, or the line has none. Keep a prefix rather than nothing.
            return line.substring(0, budget) + STATEMENT_CUT_MARKER;
        }
        // Emit statements WHOLE, most alarming first, until the budget runs out -- do not divide
        // the budget evenly and truncate them all.
        //
        // Even division guaranteed mangled output on exactly the lines this is for. A
        // url-dense concatenated line splits into many statements, so `budget / hits` collapses to
        // the MIN_STATEMENT_SLICE floor and every url is cut mid-path. Measured on a 40-url line:
        // 14 urls present, ZERO of them complete. That is the corrupt-IOC failure this parser was
        // criticised for producing elsewhere -- an analyst pivoting on a half address chases a
        // host that does not exist -- and it is worse than emitting fewer whole ones. It also cost
        // real recall: corpus distinct-url gains fell from 97 to 82 when '&' splitting started
        // fragmenting these lines.
        //
        // Severity order, not source order: the author writes the source order, so emitting whole
        // statements front-to-back would just hand the budget to whatever decoys were written
        // first.
        Integer[] byRank = new Integer[hits.size()];
        for (int i = 0; i < byRank.length; i++) {
            byRank[i] = i;
        }
        java.util.Arrays.sort(byRank, (x, y) ->
                Integer.compare(hitSeverities.get(y), hitSeverities.get(x)));
        StringBuilder sb = new StringBuilder(budget + 64);
        for (int rank : byRank) {
            String h = hits.get(rank);
            int cost = h.length() + 3;
            if (sb.length() + cost <= budget) {
                sb.append(h).append(" : ");
            } else if (sb.length() == 0) {
                // Nothing fits whole: keep a prefix of the most alarming one rather than nothing.
                sb.append(h, 0, Math.min(h.length(), Math.max(1, budget - 1)))
                        .append(STATEMENT_CUT_MARKER);
                break;
            }
        }
        if (sb.length() == 0) {
            sb.append(line, 0, Math.min(line.length(), budget)).append(STATEMENT_CUT_MARKER);
        }
        return sb.toString();
    }

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
        // Scan the WHOLE body and keep indicator lines from BOTH ENDS, then keep the indicator
        // STATEMENTS within each line rather than its first characters.
        //
        // Both halves of this were author-controlled. The loop stopped as soon as the 2,048-char
        // quota filled while scanning from offset 0, so a few hundred harmless CreateObject lines
        // at the TOP of a module consumed the reserve and the payload at the bottom was never
        // examined. And an over-long line was kept as `line[0..room]`, a pure prefix cut, so a
        // payload written after a decoy on the same physical line was discarded even when the line
        // was selected. Measured by testReservePayloadSurvivesEveryArrangement: of six placements
        // of one payload, FOUR lost it, and the two that survived were the two where the author
        // had written it first.
        //
        // Both buffers are bounded, so this is one pass in O(INDICATOR_CHARS_PER_MODULE) memory
        // however many indicator lines the body holds.
        // Select by WHAT the line contains, never by WHERE it sits.
        //
        // A head-plus-tail ring was tried here first and is what the sibling path still uses. It
        // fails the same way: with decoys before AND after the payload, the head fills with the
        // early ones, the ring keeps the late ones, and the middle is squeezed out -- the exact
        // defect codex found in windowLine, rebuilt one level up. Even spacing across the
        // candidates does not fix it either; the author simply places the payload off-anchor.
        //
        // ANY positional rule loses, because the author writes the positions. So: de-duplicate
        // (repetition is the cheapest flood, and 240 identical CreateObject lines are ONE fact),
        // then fill the budget by indicator SEVERITY. To crowd out a powershell/download payload
        // an author must now spend high-severity decoys -- and a module full of those is itself
        // worth reporting, which is the property that makes this stable rather than another round
        // of the same game.
        // Group candidates by the INDICATOR THEY CARRY, then take one from each group in turn.
        //
        // Severity ranking alone was still positional one level down: within a tier the sort is
        // stable, stable means insertion order, and insertion order is document order, which the
        // author writes. Measured -- 400 DISTINCT tier-3 decoy urls placed before the payload
        // pushed it out, and on the corpus this is what dropped two complete, well-formed urls (a
        // github.io docs link and a youtube playlist) while keeping 400 near-identical decoys.
        //
        // Round-robin over distinct indicator tokens fixes the incentive: the reserve now buys
        // COVERAGE of the techniques present, not volume of whichever one appears first. Four
        // hundred urls occupy the url slot; a lone powershell keeps its own. To hide a payload an
        // author must now avoid using any indicator the rest of the module does not already use --
        // which is a real constraint on the payload, not on us.
        java.util.Map<String, java.util.List<String>> byToken = new LinkedHashMap<>();
        java.util.Map<String, java.util.List<Integer>> groupSeverities = new java.util.HashMap<>();
        java.util.Map<String, Integer> promoted = new java.util.HashMap<>();
        java.util.Map<String, Integer> tokenKind = new java.util.HashMap<>();
        java.util.Map<String, Integer> tokenSeverity = new java.util.HashMap<>();
        java.util.regex.Matcher m = VBA_INDICATOR.matcher("");
        java.util.Set<String> seen = new java.util.HashSet<>();
        int from = 0;
        // Scan to scanLimit ALWAYS. Stopping at a candidate cap admitted the first n things met
        // and refused everything after, which restores head-first starvation above the cap -- the
        // very evasion the selection below removes. The bound now lives in ADMISSION (evict the
        // lowest-severity group when full), not in how far we are willing to look.
        while (from < scanLimit) {
            int nl = text.indexOf('\n', from);
            int end = nl < 0 || nl > scanLimit ? scanLimit : nl;
            if (end > from) {
                String line = text.substring(from, end);
                if (m.reset(line).find()) {
                    String slice = keepIndicatorStatements(line, INDICATOR_CHARS_PER_MODULE, m);
                    // First sighting wins; a repeat adds no information and must not add cost.
                    // Past the tracking cap we stop REMEMBERING slices, but we keep admitting
                    // them. The short-circuit here made the size check gate admission itself, so
                    // beyond 20,000 distinct slices nothing was ever offered to admitCandidate
                    // again -- the first-n-wins cap this design claims to have removed, rebuilt one
                    // line lower and directly beneath the comment saying it was gone. Dedup
                    // degrades past the cap (a repeat may be admitted twice); admission does not.
                    boolean fresh = seen.size() >= MAX_DEDUP_TRACKED || seen.add(slice);
                    if (fresh) {
                        String token = primaryIndicatorToken(slice, m);
                        // BEFORE any cap, eviction or budget decision. Whatever the excerpt ends
                        // up showing, the inventory records that this technique was present.
                        bounds.noteTechnique(token, slice);
                        tokenKind.putIfAbsent(token, techniqueSeverity(token));
                        int sliceSeverity = severityOf(slice);
                        admitCandidate(byToken, groupSeverities, promoted, tokenSeverity, token,
                                slice, sliceSeverity);
                    }
                }
            }
            from = end + 1;
        }
        java.util.List<String> tokens = new java.util.ArrayList<>(byToken.keySet());
        // Highest-severity technique first, so if the allowance runs out mid-round it runs out on
        // the least alarming thing present.
        // Primary key: the most alarming LINE the technique carries. Tie-break: how alarming the
        // TECHNIQUE itself is.
        //
        // Without the tie-break, 300 invented hostnames each carrying the word "shellexecute" all
        // score tier 4 on the slice, tie with a powershell payload, and ties keep document order --
        // so the author writes 300 fake techniques first and the real one is emitted last, after
        // the allowance is gone. The slices are genuinely indistinguishable there; the TECHNIQUES
        // are not. `powershell` is an execution primitive, `url:host` is data.
        //
        // Only a tie-break, deliberately: making technique severity the primary key would demote
        // every url group below every Shell group and cost distinct urls, which an earlier revision
        // measured at thirteen complete addresses lost on the corpus.
        tokens.sort((x, y) -> {
            int bySlice = Integer.compare(tokenSeverity.getOrDefault(y, 0),
                    tokenSeverity.getOrDefault(x, 0));
            return bySlice != 0 ? bySlice
                    : Integer.compare(tokenKind.getOrDefault(y, 0), tokenKind.getOrDefault(x, 0));
        });
        // Emit each group in SEVERITY order, not insertion order.
        //
        // Admission and emission are different things and this code conflated them. Eviction writes
        // an admitted slice into the evicted slot's INDEX, so a bucket whose only weak slot sits
        // late hands the payload a late index -- and the allowance expires long before that round.
        // The payload was correctly admitted and then never emitted, which costs the analyst
        // exactly as much as refusing it. Admission already knows which slices matter; emission
        // must not throw that away. Reported by codex on PR #20.
        Map<String, int[]> emitOrder = new java.util.HashMap<>();
        for (Map.Entry<String, java.util.List<String>> e : byToken.entrySet()) {
            java.util.List<Integer> sev = groupSeverities.get(e.getKey());
            Integer[] idx = new Integer[e.getValue().size()];
            for (int i = 0; i < idx.length; i++) {
                idx[i] = i;
            }
            // Stable, so equal severities keep insertion order and the result stays deterministic.
            java.util.Arrays.sort(idx, (x, y) -> Integer.compare(
                    sev == null || y >= sev.size() ? 0 : sev.get(y),
                    sev == null || x >= sev.size() ? 0 : sev.get(x)));
            int[] order = new int[idx.length];
            for (int i = 0; i < idx.length; i++) {
                order[i] = idx[i];
            }
            emitOrder.put(e.getKey(), order);
        }
        StringBuilder kept = new StringBuilder();
        for (int round = 0; ; round++) {
            boolean progressed = false;
            for (String token : tokens) {
                java.util.List<String> g = byToken.get(token);
                if (round >= g.size()) {
                    continue;
                }
                progressed = true;
                int remaining = INDICATOR_CHARS_PER_MODULE - kept.length();
                if (remaining <= 1) {
                    break;
                }
                int[] order = emitOrder.get(token);
                String slice = g.get(order == null || round >= order.length ? round : order[round]);
                if (slice.length() + 1 > remaining) {
                    // FILL the remaining allowance rather than skipping to something smaller.
                    slice = keepIndicatorStatements(slice, remaining - 1, m);
                    if (slice.length() + 1 > remaining) {
                        slice = slice.substring(0, remaining - 1);
                    }
                }
                kept.append(slice).append('\n');
            }
            if (!progressed || kept.length() >= INDICATOR_CHARS_PER_MODULE) {
                break;
            }
        }
        boolean prefixOnly = kept.length() == 0;
        if (prefixOnly) {
            // No indicator line at all, but this module does NOT fit, so we ARE withholding it:
            // keep a bounded PREFIX rather than nothing. Rejecting an over-budget body whole is the
            // same defect the XLM path had ("the point of raising the cap was more payload reaching
            // the analyst, not none") -- measured as a 4-module bomb carrier yielding 21 characters
            // in total, i.e. the budget acting as a total-loss switch.
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

        /**
         * The name the body's {@code Attribute VB_Name} should carry: MODULENAME, which is what the
         * VBA compiler writes into the source. Used to verify a relocated container really belongs
         * to THIS module rather than to another one that happens to live in the same stream.
         */
        String expectedBodyName() {
            return displayName != null ? displayName : streamName;
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
        // Built ONCE for the whole file and reused for every dir stream below. siblingStreamsOf
        // rebuilt allProps() and re-walked every directory's children for EACH dir stream, and
        // dirPropsOf caps nothing, so a crafted CFBF carrying many entries named `dir` turned
        // orphan discovery into O(dirs x properties) before any per-project cap could help --
        // a denial-of-service surface on the recall fix itself. Reported by codex on PR #19.
        Map<Property, Map<String, DocumentProperty>> siblings = siblingIndex(fs);
        // EVERY dir stream, not just the first. Keying by name kept only one, so a document with a
        // tree-visible VBA storage next to an orphaned one had the orphan's dir shadowed -- and
        // the orphan is where the payload is put, that being the whole point of orphaning it.
        for (DocumentProperty dirProp : dirPropsOf(fs, streams)) {
            if (!collectFromOneOrphanDir(fs, dirProp, streams, siblings, bounds, result)) {
                return; // document budget exhausted
            }
        }
    }

    /** @return false when the document budget is exhausted and the caller must stop. */
    private static boolean collectFromOneOrphanDir(POIFSFileSystem fs, DocumentProperty dirProp,
                                                   Map<String, DocumentProperty> streams,
                                                   Map<Property, Map<String, DocumentProperty>>
                                                           siblings,
                                                   Bounds bounds, ModuleSink result) {
        try {
            byte[] dirRaw = readPropBytes(fs, dirProp, bounds);
            if (dirRaw == null) {
                return true;
            }
            DirInfo dir = parseDir(decompress(dirRaw, 0, bounds), bounds);
            Charset charset = dir.charset;
            // Resolve within THIS dir stream's own storage. Falling back to the flat map only when
            // the storage cannot be determined keeps recall on files whose property table we cannot
            // walk, without letting a neighbouring project's stream answer for this one.
            Map<String, DocumentProperty> scoped =
                    siblings.getOrDefault(dirProp, java.util.Collections.emptyMap());
            Map<String, DocumentProperty> lookup = scoped.isEmpty() ? streams : scoped;
            for (ModuleRef ref : dir.modules) {
                // Case-insensitive lookup (see collectAllStreamProps); keep the dir-stream's
                // original-case module name as the result key.
                DocumentProperty modProp = null;
                for (String candidate : ref.candidates()) {
                    if (candidate == null) {
                        continue;
                    }
                    modProp = lookup.get(candidate.toLowerCase(Locale.ROOT));
                    if (modProp != null) {
                        break;
                    }
                }
                if (modProp == null) {
                    // Named by the dir stream, absent from the file: say so rather than skipping.
                    bounds.noteUnresolved(ref.expectedBodyName());
                    continue;
                }
                try {
                    byte[] raw = readPropBytes(fs, modProp, bounds);
                    int offset = ref.offset;
                    if (raw == null || offset < 3 || offset >= raw.length) {
                        continue;
                    }
                    noteIfStomped(bounds, ref.expectedBodyName(), offset, raw, charset);
                    String text = new String(
                            decompressModuleBody(raw, offset, bounds, ref.expectedBodyName()),
                            charset);
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
    /**
     * Streams in the SAME STORAGE as {@code dirProp}, keyed case-insensitively.
     *
     * <p>Module resolution used a single FLATTENED map of every stream in the file, built with
     * {@code putIfAbsent}, so in a document holding several VBA projects -- each with its own
     * {@code ThisDocument} -- only the first survived and every dir stream resolved
     * {@code ThisDocument} to that one stream while applying its OWN MODULEOFFSET. Four reads out
     * of five then landed mid-container and produced garbled fragments, stored under {@code #2},
     * {@code #3} family names.
     *
     * <p>The signature is 3x the modules and 1/3 the characters (one document 69 modules /
     * 416,844 chars against olevba's 26 / 1,147,642).
     *
     * <p>PREVALENCE, corrected. An earlier version of this comment said "~5% of macro-bearing
     * documents", taken from a 300-document stratified sample against olevba. The FULL corpus of
     * 6,574 says 5 documents -- 0.08%, a 60x overstatement -- and those five are the only ones
     * whose printable ratio moved at all (every one of them to exactly 1.000). The fix is still
     * worth having, because fabricated source is worse for triage than absent source and one of
     * the five was emitting a CORRUPTED URL an analyst could have pivoted on. But it is not the
     * largest recall gap on this branch, and a stratified sample is not a prevalence measurement.
     * Reported by codex on PR #19, against a commit that corrected this number everywhere EXCEPT
     * the comment a maintainer actually reads.
     *
     * <p>Returns empty when the containing storage cannot be determined, which the caller treats as
     * "fall back to the flat map" rather than "extract nothing".
     */
    /**
     * Property -> its storage's streams, built ONCE for the whole file.
     *
     * <p>One pass over the property table answers every dir stream's lookup, instead of
     * {@link #siblingStreamsOf} re-walking the table per dir. See the note at the call site.
     */
    private static Map<Property, Map<String, DocumentProperty>> siblingIndex(POIFSFileSystem fs) {
        Map<Property, Map<String, DocumentProperty>> index = new java.util.HashMap<>();
        for (Property p : allProps(fs)) {
            if (!(p instanceof DirectoryProperty)) {
                continue;
            }
            Map<String, DocumentProperty> kids = new LinkedHashMap<>();
            for (java.util.Iterator<Property> it = ((DirectoryProperty) p).getChildren();
                    it.hasNext(); ) {
                Property child = it.next();
                if (child instanceof DocumentProperty && child.getName() != null) {
                    kids.putIfAbsent(child.getName().toLowerCase(Locale.ROOT),
                            (DocumentProperty) child);
                }
            }
            for (java.util.Iterator<Property> it = ((DirectoryProperty) p).getChildren();
                    it.hasNext(); ) {
                index.put(it.next(), kids);
            }
        }
        return index;
    }

    private static Map<String, DocumentProperty> siblingStreamsOf(POIFSFileSystem fs,
                                                                  DocumentProperty dirProp) {
        Map<String, DocumentProperty> out = new LinkedHashMap<>();
        for (Property p : allProps(fs)) {
            if (!(p instanceof DirectoryProperty)) {
                continue;
            }
            Map<String, DocumentProperty> kids = new LinkedHashMap<>();
            boolean holdsThisDir = false;
            for (java.util.Iterator<Property> it = ((DirectoryProperty) p).getChildren();
                    it.hasNext(); ) {
                Property child = it.next();
                if (child == dirProp) {
                    holdsThisDir = true;
                }
                if (child instanceof DocumentProperty && child.getName() != null) {
                    kids.putIfAbsent(child.getName().toLowerCase(Locale.ROOT),
                            (DocumentProperty) child);
                }
            }
            if (holdsThisDir) {
                return kids;
            }
        }
        return out;
    }

    /** Every property in the raw table, directories included. */
    private static List<Property> allProps(POIFSFileSystem fs) {
        List<Property> out = new java.util.ArrayList<>();
        try {
            Field ptField = POIFSFileSystem.class.getDeclaredField("_property_table");
            ptField.setAccessible(true);
            Object pt = ptField.get(fs);
            Field propsField = pt.getClass().getDeclaredField("_properties");
            propsField.setAccessible(true);
            Object raw = propsField.get(pt);
            if (raw instanceof List<?>) {
                for (Object o : (List<?>) raw) {
                    if (o instanceof Property) {
                        out.add((Property) o);
                    }
                }
            }
        } catch (Throwable t) {
            // POI internals not reachable; caller falls back to the flat map.
        }
        return out;
    }

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
     * @return the projected byte count; a value greater than {@code ceiling} as soon as the walk
     *         passes it (the exact total above the ceiling is not computed); or
     *         {@link #PROJECTION_CANNOT_VOUCH} when the walk cannot bound the project at all
     */
    /**
     * Returned by {@link #projectDecompressedBytes} when it cannot vouch for the project at all --
     * its own caps truncated the walk, or the walk threw. Distinct from "projected above the
     * ceiling" because it must be honoured however large the caller's ceiling is.
     *
     * <p>The fail-closed returns used to be {@code ceiling + 1}, which is not fail-closed for a
     * large configured ceiling: at {@code Long.MAX_VALUE} it overflows to {@code Long.MIN_VALUE},
     * and the caller's {@code projected > ceiling} test then reads FALSE -- clearing POI's unbounded
     * reader to run on precisely the document the projection just said it could not bound. Even
     * without overflow, no value can exceed a ceiling of {@code Long.MAX_VALUE}, so the arithmetic
     * could not express "refuse" at all. Callers must test this sentinel explicitly.
     */
    public static final long PROJECTION_CANNOT_VOUCH = Long.MAX_VALUE;

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
                    return PROJECTION_CANNOT_VOUCH;
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
            return PROJECTION_CANNOT_VOUCH;
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
        // Decompress each (stream, offset) at most ONCE. A dir stream may name the same stream any
        // number of times -- up to MAX_MODULE_REFS -- and each ref used to re-read and re-decompress
        // it in full, so 4,096 refs to one 10 MB stream cost 4,096 decompressions of it. The result
        // is identical for identical inputs, so this is pure memoisation: same output, bounded work.
        Map<String, String> decoded = new java.util.HashMap<>();
        for (ModuleRef ref : dir.modules) {
            try {
                byte[] raw = null;
                String resolvedName = null;
                for (String candidate : ref.candidates()) {
                    raw = readStream(vbaDir, candidate, bounds);
                    if (raw != null) {
                        resolvedName = candidate;
                        break;
                    }
                }
                if (raw == null) {
                    // The dir stream names this module; the storage does not hold it.
                    bounds.noteUnresolved(ref.expectedBodyName());
                    continue;
                }
                if (ref.offset < 3 || ref.offset >= raw.length) {
                    continue;
                }
                noteIfStomped(bounds, ref.expectedBodyName(), ref.offset, raw, charset);
                String cacheKey = resolvedName + "\u0000" + ref.offset;
                String text = decoded.get(cacheKey);
                if (text == null) {
                    text = new String(
                            decompressModuleBody(raw, ref.offset, bounds, ref.expectedBodyName()),
                            charset);
                    decoded.put(cacheKey, text);
                }
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

    /**
     * How far either side of a declared MODULEOFFSET to look for the container it should have
     * pointed at.
     *
     * <p>DELIBERATELY TINY, and the size is the whole safety argument. The measured distances at
     * which a container exists at all are 8, -54, -302, -2028, 1805, 3313 and 6595, so a wider search
     * finds more -- and the corpus showed what it finds: a stream can hold ANOTHER container for the
     * same module, a stale or partial copy, and swapping it in cost three documents an exec indicator
     * (3 to 2, 3 to 2, 9 to 8), gave four documents a truncation flag with nothing withheld, and took
     * one from 16,115 characters to 3,483.
     *
     * <p>Requiring the relocated body to declare the module's own name does NOT rescue the wider
     * search: those copies declare the same name, being copies of the same module, and the arm with
     * name matching reproduced the wide arm's damage byte for byte. Two guards that fail together are
     * one guard. So the bound is the distance: at 8 bytes the only thing reachable is the container
     * the offset was meant to point INTO -- signature plus a 2-byte header, three bytes of skew in
     * the observed shape -- and a stale copy elsewhere in the stream is out of reach by construction.
     * That recovers 3 of the 42 affected refs rather than 10, and the 3 are verified correct.
     */
    private static final int MODULE_OFFSET_SEARCH_WINDOW = 8;

    /**
     * Decompress a MODULE BODY, relocating the container start when the declared MODULEOFFSET does
     * not point at one.
     *
     * <p>{@link #decompress} treats "no 0x01 at the offset" as "this stream is stored uncompressed"
     * and returns the bytes verbatim. When the offset instead points a few bytes INTO a compressed
     * container, that fallback hands back COMPRESSED bytes as macro source -- and the result is not
     * obviously broken, which is why it survived review: MS-OVBA writes a FlagByte before every 8
     * tokens, so a mostly-literal chunk reads as the real text with one junk byte every 8
     * characters. Readable enough to pass for a macro, broken enough that every consumer matching a
     * pattern fails: a URL, a Shell line or a base64 blob each acquire a stray byte partway through.
     *
     * <p>Measured on the 6,574-document corpus before this existed: 102 documents (1.6%) returned
     * module bodies declaring no {@code Attribute VB_Name} at all, which a genuine module body
     * always carries. Found by probing for bodies whose content disagrees with their declared name,
     * not by the review panel.
     *
     * <p>Relocation must PROVE itself, because a search for 0x01 followed by a plausible header
     * finds "containers" in arbitrary binary -- decompressing those yields megabytes of plausible
     * nothing, and an earlier byte-by-byte resync experiment on this branch lost 631 of 659
     * procedures on one document exactly that way.
     *
     * <p>"Declares SOME module name" is not a strong enough gate, and the corpus proved it. A stream
     * can hold ANOTHER module's container, so a search over 64 candidates found clean source
     * belonging to a different module and swapped a complete body for it. Measured cost of the weaker
     * gate: three documents lost an exec indicator (3 to 2, 3 to 2, 9 to 8), four gained a truncation
     * flag with nothing actually withheld, and one fell from 16,115 characters to 3,483 -- the same
     * document and the same magnitude as an earlier reverted experiment on this branch, which is
     * itself a clue about that unexplained result. A relocated body must therefore declare the name
     * of the module being RESOLVED; anything weaker leaves the original fallback untouched.
     */
    private static byte[] decompressModuleBody(byte[] raw, int offset, Bounds bounds,
                                               String expectedName) throws IOException {
        byte[] direct = decompress(raw, offset, bounds);
        if (offset >= 0 && offset < raw.length && (raw[offset] & 0xFF) == 0x01) {
            return direct; // the offset really is on a container; nothing to relocate
        }
        if (declaresModuleName(direct)) {
            return direct; // genuinely uncompressed source: leave it exactly as it was
        }
        // Nearest-first, both directions, within a window small enough that only the container the
        // offset points INTO is reachable. The window also bounds the cost: at most 16 candidate
        // positions, so no per-candidate decompression storm is possible.
        for (int d = 1; d <= MODULE_OFFSET_SEARCH_WINDOW; d++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                byte[] out = containerAt(raw, offset + sign * d, bounds, expectedName);
                if (out != null) {
                    return out;
                }
            }
        }
        return direct;
    }

    /**
     * Flag a module whose compiled region is substantial and whose source is empty.
     *
     * <p>The compiled region is everything before MODULEOFFSET: the performance cache Word executes.
     * Stripping the {@code Attribute VB_*} preamble is what separates "no source" from "source that
     * is only the compiler's own header", which is what a stomped module leaves behind.
     */
    private static void noteIfStomped(Bounds bounds, String name, int offset, byte[] raw,
                                      Charset charset) {
        if (name == null || offset < MIN_STOMPED_COMPILED_BYTES || raw == null) {
            return;
        }
        try {
            String src = new String(decompress(raw, offset, bounds), charset);
            String body = src.replaceAll("(?im)^\\s*attribute\\s+vb_[^\\n]*\\n?", "").trim();
            if (body.length() < 64) {
                bounds.noteStomped(name, offset);
            }
        } catch (Exception | OutOfMemoryError ignore) {
            // A body we cannot decode is not evidence of stomping.
        }
    }

    /** Cheap O(1) test: could a container plausibly start here? Keeps the scan off the decompressor. */
    private static boolean isCandidateStart(byte[] raw, int start) {
        if (start < 0 || start + 3 > raw.length || (raw[start] & 0xFF) != 0x01) {
            return false;
        }
        int header = (raw[start + 1] & 0xFF) | ((raw[start + 2] & 0xFF) << 8);
        return ((header >> 12) & 0x07) == 0b011;
    }

    /**
     * Decompress at {@code start}, returning the result only if it proves itself to be the body of
     * the module named {@code expectedName} -- not merely the body of some module.
     */
    private static byte[] containerAt(byte[] raw, int start, Bounds bounds, String expectedName) {
        if (!isCandidateStart(raw, start)) {
            return null;
        }
        try {
            byte[] out = decompress(raw, start, bounds);
            return declaresName(out, expectedName) ? out : null;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** Whether the body's {@code Attribute VB_Name} names exactly {@code expected}. */
    private static boolean declaresName(byte[] body, String expected) {
        if (body == null || expected == null || expected.isEmpty()) {
            return false;
        }
        String declared = declaredName(body);
        return declared != null && declared.equalsIgnoreCase(expected);
    }

    /**
     * The name a body declares for itself, or null. Parsed from the first
     * {@code Attribute VB_Name = "..."} in the first 8 KB, ASCII-matched so it does not depend on
     * PROJECTCODEPAGE having been resolved first.
     */
    private static String declaredName(byte[] body) {
        final byte[] needle = "attribute vb_name".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int limit = Math.min(body.length, 8192);
        outer:
        for (int i = 0; i + needle.length <= limit; i++) {
            for (int j = 0; j < needle.length; j++) {
                byte c = body[i + j];
                if (c >= 'A' && c <= 'Z') {
                    c += 32;
                }
                if (c != needle[j]) {
                    continue outer;
                }
            }
            // Skip to the opening quote, then read to the closing one.
            int p = i + needle.length;
            while (p < body.length && body[p] != '"') {
                p++;
            }
            if (p >= body.length) {
                return null;
            }
            int from = ++p;
            while (p < body.length && body[p] != '"') {
                p++;
            }
            return p <= body.length
                    ? new String(body, from, p - from, java.nio.charset.StandardCharsets.ISO_8859_1)
                    : null;
        }
        return null;
    }

    /**
     * Whether these bytes declare a module name, the one structural marker every real module body
     * carries. ASCII-matched deliberately: the keyword is ASCII in every MBCS codepage a VBA project
     * can declare, so this does not depend on having resolved PROJECTCODEPAGE first.
     */
    private static boolean declaresModuleName(byte[] body) {
        if (body == null) {
            return false;
        }
        final byte[] needle = "attribute vb_name".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int limit = Math.min(body.length, 8192); // the declaration is the first line of the body
        outer:
        for (int i = 0; i + needle.length <= limit; i++) {
            for (int j = 0; j < needle.length; j++) {
                byte c = body[i + j];
                if (c >= 'A' && c <= 'Z') {
                    c += 32;
                }
                if (c != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
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
