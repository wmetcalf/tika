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
        assertSubQuadratic("compressed chunks per module", 4096,
                n -> VbaProjectBuilder.ratioBombContainer(n),
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
    private static <T> void assertSubQuadratic(String what, int startN,
                                               java.util.function.IntFunction<T> prepare,
                                               java.util.function.Consumer<T> run) {
        final long minMeasurable = 60_000_000L; // 60 ms of real work
        int baseN = startN;
        long baseCost = 0;
        run.accept(prepare.apply(baseN)); // warm up JIT on the real shape
        int grow = 0;
        for (; grow < 7; grow++) {
            baseCost = bestOfThree(run, prepare.apply(baseN));
            if (baseCost >= minMeasurable) {
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
    private static <T> long bestOfThree(java.util.function.Consumer<T> run, T input) {
        long best = Long.MAX_VALUE;
        for (int rep = 0; rep < 3; rep++) {
            long t0 = System.nanoTime();
            run.accept(input);
            best = Math.min(best, System.nanoTime() - t0);
        }
        return best;
    }
}
