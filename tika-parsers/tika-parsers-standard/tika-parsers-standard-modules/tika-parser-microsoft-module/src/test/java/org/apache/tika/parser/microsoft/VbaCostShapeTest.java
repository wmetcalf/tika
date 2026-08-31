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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.Locale;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * COST SHAPE on the VBA path: does the work grow linearly with the input, or does some dimension
 * go quadratic on a document an attacker controls?
 *
 * <p>The XLM path has this gate and it earned its keep -- it found a quadratic value join that
 * turned 3.1 MB of input into 6.4 GB of text. The VBA path had no equivalent, so nothing measured
 * what happens when a document repeats one dimension many times. A size bound does not help here:
 * the input stays small and the CPU is what burns, and a worker stuck for minutes on one document is
 * as unavailable as one that crashed.
 *
 * <p>Every dimension below is attacker-chosen: the dir stream says how many modules there are and
 * what they are called, and the form storages say how many forms and controls there are.
 *
 * <p>MEASURED FIRST, then gated. Reading a document's streams is QUADRATIC in the NUMBER of streams,
 * and that cost is POI's, not ours -- reading N small streams straight through POI's own API with no
 * Tika code involved measures 6 ms at N=1,024 and 742 ms at N=16,384. That cannot be fixed here, so
 * stream count is bounded by a cap instead (see {@code testModuleRefCapEngagesAndIsReported}), and
 * the sub-quadratic gates below cover the dimensions we do own: work per module, work inside one
 * stream, and the duplicate-name bookkeeping. Gating stream count as if it were ours would have
 * produced a permanently red test that says nothing about our code.
 */

// Run in its OWN JVM: see the surefire cost-shape execution in this module's pom. These gates
// measure how cost GROWS, which a fork warmed by a thousand other tests silently distorts.
@Tag("cost-shape")
public class VbaCostShapeTest {

    // ── the dimensions ──────────────────────────────────────────────────────

    /**
     * Duplicate module names must be kept under distinct keys (collapsing by name loses the payload
     * module), and the check for "is this body already stored under this name" is what can go
     * quadratic: comparing each new body against every one already stored is O(modules^2 x body
     * length).
     *
     * <p>Compared against the SAME number of streams with DISTINCT names, so POI's per-stream
     * quadratic is paid identically by both arms and cancels out. Bodies are the same length and
     * differ only in their last bytes, which is the worst case -- differing lengths or an
     * early-differing byte let String.equals short-circuit, and a test built from those would pass
     * against a quadratic implementation.
     */
    @Test
    void testDuplicateNameBookkeepingCostsNoMoreThanDistinctNames() {
        final int n = 2048;
        byte[] distinct = build(new VbaProjectBuilder().distinctNamesDistinctStreams(n, 4096));
        byte[] shared = build(new VbaProjectBuilder().sameNameDistinctStreams("Module1", n, 4096));

        readMacros(distinct); // warm up JIT on the real shape
        readMacros(shared);
        long distinctCost = bestOfThree(VbaCostShapeTest::readMacros, distinct);
        long sharedCost = bestOfThree(VbaCostShapeTest::readMacros, shared);

        assertTrue(distinctCost > 20_000_000L,
                "the control arm must be expensive enough to compare against; got "
                        + distinctCost / 1_000_000 + " ms");
        double ratio = (double) sharedCost / distinctCost;
        assertTrue(ratio < 4.0,
                "handling " + n + " modules that share one name costs "
                        + String.format(Locale.ROOT, "%.1fx", ratio)
                        + " what the same number of distinctly-named modules costs ("
                        + sharedCost / 1_000_000 + " vs " + distinctCost / 1_000_000
                        + " ms). The duplicate-name bookkeeping is quadratic in the number of "
                        + "same-named modules.");
    }

    /**
     * The indicator scan that runs once the byte budget is exhausted must not grow with module
     * SIZE. It keeps going past a module too big to fit -- where the old code stopped -- so it
     * introduced a scanning cost that did not exist before, and unbounded that cost is a denial of
     * service in its own right: measured at ~180 ms per megabyte on the worst case for a
     * line-oriented pattern, 40 modules of 1 MB each took 7.2 SECONDS before the scan budget was
     * added.
     *
     * <p>Fixture is that worst case deliberately: one enormous line, no newline to break the scan
     * up, and nothing in it that matches, so every alternative is tried at every position.
     */
    @Test
    void testIndicatorScanCostDoesNotGrowWithModuleSize() {
        long small = bestOfThree(VbaCostShapeTest::readMacrosTightBudget, oneLineModules(64));
        long large = bestOfThree(VbaCostShapeTest::readMacrosTightBudget, oneLineModules(1024));
        double ratio = (double) large / small;
        assertTrue(ratio < 3.0,
                "16x the module bytes cost " + String.format(Locale.ROOT, "%.1fx", ratio)
                        + " the time (" + small / 1_000_000 + " -> " + large / 1_000_000
                        + " ms); the indicator scan budget is not bounding the scan");
    }

    /** 40 modules, each one unmatched line of {@code kb} kilobytes. */
    private static byte[] oneLineModules(int kb) {
        StringBuilder line = new StringBuilder(kb * 1024 + 16);
        while (line.length() < kb * 1024) {
            line.append("xyzqwv0123456789");
        }
        VbaProjectBuilder b = new VbaProjectBuilder();
        for (int i = 0; i < 40; i++) {
            b.module("Mod" + i, line.toString());
        }
        return build(b);
    }

    /** Read with a budget small enough that the indicator reserve phase runs. */
    private static void readMacrosTightBudget(byte[] project) {
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
            LenientVBAReader.readMacros(fs, new LenientVBAReader.Bounds(64 * 1024 * 1024, 20_000));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Many chunks in one module. Feeds a COMPRESSED container on purpose: the quadratic risk the
     * production code guards against ("use a fixed-size local buffer to avoid O(n^2) toByteArray()
     * calls") is in the compressed branch, and an earlier version of this gate fed an UNCOMPRESSED
     * container, so it measured a System.arraycopy and left the real branch unmeasured -- making the
     * compressed accumulation quadratic (17 ms to 1000 ms on 4 MB) kept every gate green.
     */
    @Test
    void testChunksPerModuleCostIsSubQuadratic() {
        // Copy-token chunks: ~7 input bytes yield ~4094 output bytes each, so the work per unit of
        // n is real decompression (the copy loop plus the chunkBuf -> out handoff where the
        // quadratic would live) rather than the size of the input buffer.
        // 509 bytes per chunk, not 4,093: the axis under test is the NUMBER of chunk handoffs, and
        // at the larger payload the top point was dominated by ~0.5 GB of ByteArrayOutputStream
        // growth rather than by decompression, which read as ~10x growth on linear code. Verified
        // still to catch the real defect at this size: the historical per-chunk toByteArray() is
        // detected at 21.4x.
        assertSubQuadratic("compressed chunks per module", 4096, 16_384,
                n -> VbaProjectBuilder.ratioBombContainer(n, 509),
                container -> {
                    try {
                        LenientVBAReader.decompress(container, 0,
                                new LenientVBAReader.Bounds(256 * 1024 * 1024,
                                        Long.MAX_VALUE / 4));
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                });
    }

    // REMOVED: a "projection cost is held down by the module cap" gate. Once the projection was
    // made to FAIL CLOSED at MAX_MODULE_REFS it returns immediately at the cap, so the gate timed an
    // early return (4 ms at every size) and could no longer distinguish anything. The cost it used to
    // measure is POI's per-stream quadratic, which is not ours to flatten; what matters is that the
    // cap engages and is reported, and that is pinned functionally by
    // testModuleRefCapEngagesAndIsReported plus VbaBudgetTest's fail-closed cases. A timing gate that
    // measures an early return is worse than no gate: it reads as coverage.

    /**
     * The module-count cap is the ONLY defence against POI's per-stream quadratic, so it has to
     * actually engage -- and say so. A cap that silently truncates reads as "this document has 4,096
     * modules", which is a different and false statement from "it has more and we stopped".
     *
     * <p>The fixture is the cheap shape of the attack: a dir stream carrying many thousands of
     * module records that all point at ONE tiny stream, so the file stays small while the read count
     * does not.
     */
    @Test
    void testModuleRefCapEngagesAndIsReported() {
        byte[] project = build(new VbaProjectBuilder()
                .refsToOneStream("M", 12_000, "Sub S()\n  Shell \"calc\"\nEnd Sub\n"));
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
            LenientVBAReader.Bounds bounds =
                    new LenientVBAReader.Bounds(64 * 1024 * 1024, Long.MAX_VALUE / 4);
            java.util.Map<String, String> macros = LenientVBAReader.readMacros(fs, bounds);
            assertTrue(macros.size() <= 4096,
                    "the cap must bound how many modules are read; got " + macros.size());
            assertTrue(bounds.isLimitReached(),
                    "dropping modules at the cap must be reported, never silent");
            assertTrue(bounds.getLimitDetail() != null
                            && bounds.getLimitDetail().contains("more than"),
                    "the report should say the count was exceeded; got "
                            + bounds.getLimitDetail());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Below the cap, nothing is dropped and nothing is claimed to be. */
    @Test
    void testModuleRefCapDoesNotFireOnAnOrdinaryProject() {
        byte[] project = build(new VbaProjectBuilder()
                .refsToOneStream("M", 500, "Sub S()\nEnd Sub\n"));
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
            LenientVBAReader.Bounds bounds =
                    new LenientVBAReader.Bounds(64 * 1024 * 1024, Long.MAX_VALUE / 4);
            LenientVBAReader.readMacros(fs, bounds);
            assertTrue(!bounds.isLimitReached(),
                    "500 modules is far under the cap and 100x a real document; the flag must "
                            + "stay clear, got " + bounds.getLimitDetail());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // REMOVED: a "controls per form" timing gate. The dimension is capped by MAX_SITES = 65,536, and
    // measurement showed the cost FALLING above it (41 ms at 32,768 controls, 25 ms at 131,072)
    // because the parser refuses the oversized site count outright -- so the fixture can never reach
    // a measurable floor and a growth ratio is the wrong instrument for a dimension a cap already
    // bounds. What matters is that the cap engages and is reported, which is asserted below rather
    // than inferred from a clock.

    /**
     * The per-form site cap must refuse an oversized {@code countOfSites} AND report it, because
     * that cap is the only thing bounding how much work one form can demand.
     */
    @Test
    void testSiteCountCapEngagesAndIsReported() throws Exception {
        // Declare far more sites than the cap allows, with no site data behind the count.
        java.io.ByteArrayOutputStream f = new java.io.ByteArrayOutputStream();
        f.write(0x00);
        f.write(0x04);
        f.write(4);
        f.write(0);                       // cbForm = 4 (the FormPropMask below)
        for (int i = 0; i < 4; i++) {
            f.write(0);                   // FormPropMask: nothing set
        }
        f.write(0);
        f.write(0);                       // class table count = 0
        int sites = 70_000;               // > MAX_SITES
        f.write(sites & 0xFF);
        f.write((sites >> 8) & 0xFF);
        f.write((sites >> 16) & 0xFF);
        f.write((sites >> 24) & 0xFF);
        for (int i = 0; i < 4; i++) {
            f.write(0);                   // countOfBytes
        }

        byte[] poifs;
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            fs.getRoot().createDirectory("UserForm1")
                    .createDocument("f", new ByteArrayInputStream(f.toByteArray()));
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            fs.writeFilesystem(bos);
            poifs = bos.toByteArray();
        }
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(poifs))) {
            LenientVBAReader.Bounds bounds = new LenientVBAReader.Bounds();
            java.util.List<VbaFormParser.FormModuleResult> forms =
                    VbaFormParser.extractFormVariables(fs, bounds);
            long controls = forms.stream().mapToLong(r -> r.controls.size()).sum();
            assertTrue(controls == 0,
                    "a form declaring more sites than the cap allows must yield no controls; got "
                            + controls);
            assertTrue(bounds.isLimitReached(),
                    "refusing a form for an oversized site count withholds whatever it held, so it "
                            + "must be reported; detail was: " + bounds.getLimitDetail());
        }
    }

    // ── the timed operations ────────────────────────────────────────────────

    private static void readMacros(byte[] project) {
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
            LenientVBAReader.readMacros(fs,
                    new LenientVBAReader.Bounds(64 * 1024 * 1024, Long.MAX_VALUE / 4));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void readForms(byte[] project) {
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
            for (VbaFormParser.FormModuleResult f : VbaFormParser.extractFormVariables(fs)) {
                f.toText();
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] build(VbaProjectBuilder b) {
        try {
            return b.build();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ── the harness (same contract as the XLM one) ──────────────────────────

    /**
     * PREPARE is untimed, RUN is timed: building the input is inherently linear and folding it into
     * the measurement dilutes the signal enough for a quadratic operation to hide behind it.
     *
     * <p>The base auto-scales until it is large enough to mean something -- a hand-tuned size is
     * machine-dependent. Growth is judged over the whole 4x span rather than per doubling, because
     * a single noisy point makes per-doubling ratios contradict each other; over 4x input a linear
     * cost is ~4x and a quadratic one ~16x, so 8x is a midpoint neither reaches by accident. A flaky
     * gate is worse than none: it teaches people to ignore red.
     */
    /**
     * @param maxBaseN ceiling on how far the scale loop may grow the base size.
     *
     * <p>The ceiling exists because an UNBOUNDED scale loop silently picks the fixture size, and
     * therefore the cost REGIME, from whatever the machine and the JIT happen to be doing. Measured:
     * the same code took the loop to n=16384 standalone and to n=65536 inside a warm full-suite JVM,
     * where the top point produced a quarter-gigabyte of output. Past roughly 100 MB the timing is
     * dominated by ByteArrayOutputStream growth and the final toByteArray copy rather than by the
     * work under test, and a perfectly linear implementation reads as ~10x growth. Three separate
     * "fixes" -- more samples, a GC hint, then thread CPU time -- each failed because each treated
     * the INSTRUMENT as the problem when the fixture had drifted into a regime where a different
     * cost dominated.
     */
    private static <T> void assertSubQuadratic(String what, int startN, int maxBaseN,
                                               java.util.function.IntFunction<T> prepare,
                                               java.util.function.Consumer<T> run) {
        // 15 ms, not 60: this reads thread CPU time, which has nanosecond resolution and none of
        // wall clock's scheduling noise, so a smaller floor is still a real measurement -- and a
        // smaller floor is what keeps the fixture out of the allocation-dominated regime.
        final long minMeasurable = 15_000_000L;
        int baseN = startN;
        long baseCost = 0;
        run.accept(prepare.apply(baseN)); // warm up JIT on the real shape
        int grow = 0;
        for (; grow < 7 && baseN <= maxBaseN; grow++) {
            baseCost = bestOfThree(run, prepare.apply(baseN));
            if (baseCost >= minMeasurable) {
                break;
            }
            if (baseN * 2 > maxBaseN) {
                break;
            }
            baseN *= 2;
        }
        // HARD FAIL, matching the XLM harness this was copied from. The earlier version turned an
        // unreachable floor into a PASS built from two tautologies -- assertTrue(baseCost <
        // minMeasurable) inside if (baseCost < minMeasurable), plus a baseN bound the scale loop
        // always satisfies -- and 2 of the 3 gates took that branch, so they never evaluated a
        // growth ratio at all while reading as green. A gate that cannot reach a measurable cost is
        // a gate that needs a bigger fixture, not a silent skip.
        assertTrue(baseCost >= minMeasurable,
                what + ": could not reach a measurable base cost even at n=" + baseN + " ("
                        + baseCost / 1_000_000 + " ms). Raise startN or make each unit of n cost "
                        + "more, rather than trusting a ratio built on noise.");

        long c2 = bestOfThree(run, prepare.apply(baseN * 2));
        long c4 = bestOfThree(run, prepare.apply(baseN * 4));

        double total = (double) c4 / baseCost;
        assertTrue(total < 8.0,
                what + ": cost grows ~quadratically. n=" + baseN + "," + (baseN * 2) + ","
                        + (baseN * 4) + " -> " + baseCost / 1_000_000 + "," + c2 / 1_000_000 + ","
                        + c4 / 1_000_000 + " ms; total growth over 4x input "
                        + String.format(Locale.ROOT, "%.1fx", total)
                        + " (linear ~4x, quadratic ~16x)");
    }

    /** Fastest of three runs: the minimum is the least noise-contaminated estimate. */
    /**
     * Minimum of {@value #SAMPLES} timed runs -- "how fast can this go", which is the estimator a
     * cost-SHAPE gate wants: a genuinely super-linear path is slow in every sample, while a single
     * full GC landing inside one sample is worth the entire growth budget on its own.
     *
     * <p>Measured as this THREAD's CPU time, not wall clock. The largest fixture allocates ~268 MB
     * per sample against a 256 MB cap, so a collection lands inside a timed region routinely; wall
     * clock bills that pause to the measurement even though the collector runs on its own threads,
     * and the gate was flipping to 9-11x purely on GC scheduling. Minimum-of-five with a collection
     * hint was tried first and was NOT enough on its own -- it cut the rate and left the gate still
     * failing, which is the whole reason this reads CPU time instead.
     *
     * <p>Deliberately NOT a looser threshold -- 8.0 stays, because raising it is how a real
     * quadratic hid here before. Reducing measurement noise makes the gate sharper; raising the
     * bar makes it blinder.
     */
    /**
     * Whether thread CPU time can actually resolve the durations this harness measures.
     *
     * <p>SUPPORTED and ENABLED are not enough. On Windows {@code getCurrentThreadCpuTime()} is
     * both supported and enabled, and still advances in ~15.6 ms ticks, so work below one tick
     * measures as exactly 0 no matter how many times it is repeated. The nanosecond RETURN UNIT
     * says nothing about the underlying granularity. Observed on a windows-latest runner: the
     * compressed-chunks fixture reached the 15 ms floor on Linux but reported "0 ms" at n=16384
     * on Windows, failing the gate for a reason that had nothing to do with the code under test.
     *
     * <p>So probe the granularity instead of trusting the flags: busy-loop until the clock moves,
     * and require the observed step to be small relative to {@code minMeasurable}. If it is not,
     * fall back to wall clock, which is high resolution on every platform this runs on.
     */
    private static boolean probeCpuTimeResolution() {
        java.lang.management.ThreadMXBean threads =
                java.lang.management.ManagementFactory.getThreadMXBean();
        if (!threads.isCurrentThreadCpuTimeSupported() || !threads.isThreadCpuTimeEnabled()) {
            return false;
        }
        long start = threads.getCurrentThreadCpuTime();
        if (start < 0) {
            return false;
        }
        long step = 0;
        long deadline = System.nanoTime() + 200_000_000L;   // bounded: 200 ms of probing at most
        long spin = 0;
        while (System.nanoTime() < deadline) {
            spin++;
            long now = threads.getCurrentThreadCpuTime();
            if (now > start) {
                step = now - start;
                break;
            }
        }
        if (spin == 0 || step <= 0) {
            return false;
        }
        // One tick must be a small fraction of the floor, or a measurement at the floor is mostly
        // quantisation error. 15 ms floor against a 15.6 ms tick is unusable; a ~1 ms tick is fine.
        return step * 4 <= 15_000_000L;
    }

    private static final boolean CPU_TIME_RESOLUTION_IS_USABLE = probeCpuTimeResolution();

    private static <T> long bestOfThree(java.util.function.Consumer<T> run, T input) {
        java.lang.management.ThreadMXBean threads =
                java.lang.management.ManagementFactory.getThreadMXBean();
        // SUPPORTED and ENABLED are separate states. On a JVM where thread CPU timing is supported
        // but switched off, getCurrentThreadCpuTime() returns -1: both timestamps become -1, every
        // measured duration becomes zero, and the "could not reach a measurable base cost" gate
        // then fails every test using this helper. Fall back to wall clock unless it is actually on.
        boolean cpuTime = threads.isCurrentThreadCpuTimeSupported()
                && threads.isThreadCpuTimeEnabled()
                && CPU_TIME_RESOLUTION_IS_USABLE;
        long best = Long.MAX_VALUE;
        for (int rep = 0; rep < SAMPLES; rep++) {
            System.gc();
            long t0 = cpuTime ? threads.getCurrentThreadCpuTime() : System.nanoTime();
            run.accept(input);
            long dt = (cpuTime ? threads.getCurrentThreadCpuTime() : System.nanoTime()) - t0;
            best = Math.min(best, dt);
        }
        return best;
    }

    private static final int SAMPLES = 5;
    /**
     * The reserve's eviction must not rescan what it already knows.
     *
     * <p>Both eviction paths used to call {@code severityOf} on every RETAINED entry for every new
     * candidate, and severityOf lowercases and scans the whole string. Cost was therefore
     * {@code candidates x retained bytes}, both of which the author controls: 64 retained ~16 KB
     * statements and 20,000 candidates measured ~35 s inside the code whose job is to bound
     * hostile input. Reported by codex on PR #20.
     *
     * <p>VARY BOTH AXES. The first version of this test doubled only the candidate count and
     * PASSED under the defect -- each candidate does a fixed-size rescan, so that axis alone looks
     * linear (2x work, ratio 2). The product only shows up when both grow: doubling each should
     * cost ~2x if severity is remembered and ~4x if it is recomputed.
     */
    @Test
    void testReserveEvictionDoesNotRescanRetainedStatements() throws Exception {
        // Vary ONLY the retained statement SIZE, holding the candidate count fixed. That is the
        // axis the defect actually scales on -- cost was (candidates x retained bytes) because
        // every candidate rescanned every retained string -- so a 160x size increase shows up
        // directly, while machine load cancels between the two runs in the same JVM.
        //
        // Two earlier instruments failed here and both are worth remembering. A ratio over the
        // CANDIDATE count passed under the defect (each candidate does a fixed-size rescan, so
        // that axis alone looks linear). A ratio over BOTH axes also passed (the inflation divides
        // out). An absolute 10 s bound caught it but was FLAKY: green alone at 6.9 s, red inside
        // the full suite where tests compete for CPU.
        // The control run must do enough work to dominate scheduler noise. At 100 bytes it took
        // ~600 ms, and under full-suite CPU contention that varied enough to push the ratio past a
        // tight threshold -- green alone, red in the suite. 1,000 bytes keeps the size gap at 16x
        // while making both runs substantial.
        long tiny = timeCraftedLine(1_000, 10_000);
        long huge = timeCraftedLine(16_000, 10_000);
        double ratio = (double) huge / Math.max(tiny, 1L);
        // 16x the retained bytes: ~1x if severity is remembered, ~16x if it is recomputed. A
        // threshold of 6 sits well clear of both, rather than hugging the healthy value.
        assertTrue(ratio < 6.0,
                "16x the retained statement bytes cost "
                        + String.format(Locale.ROOT, "%.1fx", ratio)
                        + " at the same candidate count -- eviction is rescanning retained "
                        + "statements instead of remembering their severity, which is quadratic "
                        + "in (retained bytes x candidates), both attacker-controlled. tiny="
                        + tiny / 1_000_000 + "ms huge=" + huge / 1_000_000 + "ms");
    }

    /** One physical line: 64 Shell statements of {@code stmtBytes}, then {@code candidates} short ones. */
    private static long timeCraftedLine(int stmtBytes, int candidates) throws Exception {
        StringBuilder line = new StringBuilder("  ");
        for (int i = 0; i < 64; i++) {
            line.append("Shell \"").append("A".repeat(stmtBytes)).append(i).append("\" : ");
        }
        for (int i = 0; i < candidates; i++) {
            line.append("Set c").append(i).append(" = CreateObject(\"D\") : ");
        }
        byte[] project = new VbaProjectBuilder()
                .module("P", "' filler\n".repeat(12000) + line + "\n").build();
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
            long t0 = System.nanoTime();
            LenientVBAReader.readMacros(fs, new LenientVBAReader.Bounds(64 * 1024 * 1024, 60_000));
            return System.nanoTime() - t0;
        }
    }

    /**
     * The inventory walk must stay linear in the module body.
     *
     * <p>This exact shape -- a rescan inside a per-match loop -- has now appeared THREE times in
     * this file: severityOf over retained slices, urlSpanAt widening with no newline terminator,
     * and a line counter restarting from offset zero. Each was quadratic, each measured 4x per
     * doubling, and the second reached 13.4 s on a 256 KB body. This gate exists so the fourth is
     * caught by CI rather than by a reviewer.
     *
     * <p>The fixture deliberately contains NO span terminators (no quotes, no spaces) so a url
     * match has nothing to stop at -- that is what made the widening version blow up, and a
     * fixture with quotes in it misses the defect entirely, as one of mine did.
     */
    @Test
    void testInventoryWalkIsLinearInBodySize() throws Exception {
        long small = timeInventory(64);
        long large = timeInventory(256);
        double ratio = (double) large / Math.max(small, 1L);
        assertTrue(ratio < 8.0,
                "4x the body cost " + String.format(Locale.ROOT, "%.1fx", ratio)
                        + " -- the inventory walk is rescanning per match instead of advancing "
                        + "(small=" + small / 1_000_000 + "ms large=" + large / 1_000_000 + "ms)");
    }

    private static long timeInventory(int kb) throws Exception {
        StringBuilder b = new StringBuilder();
        while (b.length() < kb * 1024) {
            b.append("http://a\nq=1\n");
        }
        byte[] project = new VbaProjectBuilder().module("M", b.toString()).build();
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
            long t0 = System.nanoTime();
            LenientVBAReader.readMacros(fs, new LenientVBAReader.Bounds());
            return System.nanoTime() - t0;
        }
    }

}
