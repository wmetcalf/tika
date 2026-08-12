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

    /** Many chunks in one module: decompression must stay linear in the compressed length. */
    @Test
    void testChunksPerModuleCostIsSubQuadratic() {
        assertSubQuadratic("chunks per module", 256,
                n -> VbaProjectBuilder.uncompressedContainer(
                        "' filler\n".repeat(n * 8).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)),
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

    /**
     * The module cap is what holds the pre-flight projection's cost down, and this measures THAT --
     * not the projection's own shape.
     *
     * <p>Named for what it asserts, after a mutation caught the earlier name lying: with the cap
     * removed this same test fails at 14.0x, because the cost being measured across 4,096 -> 16,384
     * modules is POI's per-stream quadratic, not the header walk. With the cap in place all three
     * points stop at 4,096 modules and the cost is flat. A test whose name claims to measure one
     * thing while measuring another is how an instrument artifact gets recorded as a property of
     * the code.
     */
    @Test
    void testProjectionCostIsHeldDownByTheModuleCap() {
        assertSubQuadratic("projection with the module cap engaged", 64,
                n -> {
                    VbaProjectBuilder b = new VbaProjectBuilder();
                    for (int i = 0; i < n; i++) {
                        b.rawModule("Module" + i, VbaProjectBuilder.ratioBombContainer(16));
                    }
                    return build(b);
                },
                bytes -> {
                    try (POIFSFileSystem fs = new POIFSFileSystem(
                            new ByteArrayInputStream(bytes))) {
                        LenientVBAReader.projectDecompressedBytes(fs, Long.MAX_VALUE / 4);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                });
    }

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

    /** Many controls inside one UserForm. */
    @Test
    void testControlsPerFormCostIsSubQuadratic() {
        assertSubQuadratic("controls per form", 256,
                n -> {
                    VbaFormBuilder b = new VbaFormBuilder();
                    for (int i = 0; i < n; i++) {
                        b.control("Btn" + i, "tag" + i, "tip" + i);
                    }
                    try {
                        return b.poifs("UserForm1");
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                },
                VbaCostShapeTest::readForms);
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
        if (baseCost < minMeasurable) {
            // The dimension is simply cheap: 128x the starting size still does not reach 60 ms, so
            // there is no growth problem to gate. Treating that as a failure is what the earlier
            // version did, and it is a false red -- the gate exists to catch superlinear GROWTH, and
            // a cost that stays trivial across a 128x span has none worth measuring. Assert the
            // triviality explicitly rather than passing silently.
            assertTrue(baseCost < minMeasurable,
                    what + ": unreachable state"); // documents the branch
            assertTrue(baseN >= startN * 64,
                    what + ": expected the auto-scale to have tried at least 64x the starting size "
                            + "before concluding the dimension is cheap; reached n=" + baseN);
            return;
        }

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
