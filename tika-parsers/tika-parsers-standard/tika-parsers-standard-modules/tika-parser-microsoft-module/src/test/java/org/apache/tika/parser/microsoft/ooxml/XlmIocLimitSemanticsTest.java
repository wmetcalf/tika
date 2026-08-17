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
package org.apache.tika.parser.microsoft.ooxml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * What {@code msoffice:xlm-capture-limit-reached} MEANS: something was REFUSED.
 *
 * <p>The flag drives {@code TRUNCATED_METADATA}, so an analyst reads it as "this extraction is
 * incomplete". Firing it when nothing was dropped is not a harmless extra warning -- on a corpus of
 * thousands of clean documents it is indistinguishable from noise, and the one document where
 * something really was withheld stops standing out.
 *
 * <p>Reported by an external reviewer on PR #18 and confirmed here. The scanner already carried a
 * NOTE recording this exact defect as measured and fixed -- "do NOT set `limited` here ... setting
 * it from a SATURATION test instead made onIocLimit fire on documents where nothing was dropped" --
 * directly below a line doing precisely that. The line had come back.
 */
public class XlmIocLimitSemanticsTest {

    /**
     * A workbook whose high-value indicators fit EXACTLY must not report a shortfall.
     *
     * <p>The saturation test sits at the top of every cell iteration, so once the sink is exactly
     * full the next pass reaches it and marks the scan limited without ever asking whether another
     * indicator exists. Two EXEC calls against a two-entry budget: both are emitted, nothing is
     * refused, and the flag must stay clear.
     */
    @Test
    void testExactlyFullIsNotReportedAsTruncated() {
        Map<String, String> formulas = new LinkedHashMap<>();
        formulas.put("Macro1:1:A1", "=EXEC(\"cmd /c calc\")");
        formulas.put("Macro1:2:A2", "=EXEC(\"powershell -enc AAA\")");

        AtomicInteger limitFired = new AtomicInteger();
        List<String> iocs = XlmXmlIocScanner.scan(formulas, new LinkedHashMap<>(),
                0, null, 2, 0, limitFired::incrementAndGet);

        assertEquals(2, iocs.size(),
                "both EXEC indicators fit the two-entry budget; got " + iocs);
        assertEquals(0, limitFired.get(),
                "nothing was refused, so the capture-limit flag must NOT fire -- it drives "
                        + "TRUNCATED_METADATA, and a flag on a complete extraction is noise. "
                        + "Emitted: " + iocs);
    }

    /**
     * NEGATIVE CONTROL: when an indicator really IS refused, the flag must still fire. Without this
     * the assertion above passes against a build that never reports anything at all -- which is the
     * opposite defect and the more dangerous one.
     */
    @Test
    void testAGenuineRefusalStillReportsTheLimit() {
        Map<String, String> formulas = new LinkedHashMap<>();
        formulas.put("Macro1:1:A1", "=EXEC(\"cmd /c calc\")");
        formulas.put("Macro1:2:A2", "=EXEC(\"powershell -enc AAA\")");
        formulas.put("Macro1:3:A3", "=EXEC(\"wscript evil.js\")");

        AtomicInteger limitFired = new AtomicInteger();
        List<String> iocs = XlmXmlIocScanner.scan(formulas, new LinkedHashMap<>(),
                0, null, 2, 0, limitFired::incrementAndGet);

        assertEquals(2, iocs.size(), "the budget bounds the output; got " + iocs);
        assertTrue(limitFired.get() > 0,
                "a third indicator did not fit, so the loss MUST be reported; emitted " + iocs);
    }

    /** And the ordinary case: room to spare, nothing reported. */
    @Test
    void testRoomToSpareIsNotFlagged() {
        Map<String, String> formulas = new LinkedHashMap<>();
        formulas.put("Macro1:1:A1", "=EXEC(\"cmd /c calc\")");

        AtomicInteger limitFired = new AtomicInteger();
        List<String> iocs = XlmXmlIocScanner.scan(formulas, new LinkedHashMap<>(),
                0, null, 64, 0, limitFired::incrementAndGet);

        assertFalse(iocs.isEmpty(), "the EXEC must be extracted");
        assertEquals(0, limitFired.get(), "nothing was withheld; emitted " + iocs);
    }

    /**
     * A cell holding more high-value indicators than its fair share must not lose them SILENTLY
     * while the document budget sits mostly unspent.
     *
     * <p>The fair pass gives each cell {@code max(1, maxEntries / cells)} slots so one bulk emitter
     * cannot starve the rest. But nothing ever reclaimed the shares the other cells did not use, and
     * a quota refusal deliberately does not set the limit flag -- it is a deferral, not a loss. With
     * high-value indicators clustered in one cell, the deferral was permanent: pass 0 emits
     * high-value only and then moves on, pass 1 emits bulk only, so the remainder was never
     * revisited. Reported by an external reviewer on PR #18.
     *
     * <p>Three cells and a 9-entry budget give a quota of 3. All six EXEC calls live in one cell, so
     * three are deferred; the other two cells contribute nothing, leaving six slots idle. All six
     * must come back, and nothing may be flagged, because nothing was lost.
     */
    @Test
    void testQuotaDeferredHighValueIndicatorsAreRecovered() {
        Map<String, String> formulas = new LinkedHashMap<>();
        StringBuilder crowded = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            crowded.append("=EXEC(\"cmd /c payload").append(i).append("\")");
        }
        formulas.put("Macro1:1:A1", crowded.toString());
        formulas.put("Macro1:2:A2", "=ALERT(\"nothing high value here\")");
        formulas.put("Macro1:3:A3", "=ALERT(\"nor here\")");

        AtomicInteger limitFired = new AtomicInteger();
        List<String> iocs = XlmXmlIocScanner.scan(formulas, new LinkedHashMap<>(),
                0, null, 9, 0, limitFired::incrementAndGet);

        for (int i = 1; i <= 6; i++) {
            final String want = "payload" + i;
            assertTrue(iocs.stream().anyMatch(x -> x.contains(want)),
                    "EXEC " + want + " was deferred by the per-cell quota and never recovered, "
                            + "while the budget had room to spare. Emitted: " + iocs);
        }
        assertEquals(0, limitFired.get(),
                "everything fit in the end, so nothing may be reported; emitted " + iocs);
    }

    /**
     * Duplicates are collapsed, and collapsing them is not a loss.
     *
     * <p>This is what makes the recovery pass above safe: it necessarily re-walks formulas the fair
     * pass already emitted from. An earlier attempt at such a pass without deduplication doubled the
     * output -- measured on the corpus as EXEC 239 to 478, exactly 2x -- and was reverted. Two cells
     * holding the same EXEC are also simply one piece of evidence.
     */
    @Test
    void testIdenticalIndicatorsCollapseAndAreNotReportedAsLoss() {
        Map<String, String> formulas = new LinkedHashMap<>();
        for (int i = 1; i <= 20; i++) {
            formulas.put("Macro1:" + i + ":A" + i, "=EXEC(\"cmd /c calc\")");
        }

        AtomicInteger limitFired = new AtomicInteger();
        List<String> iocs = XlmXmlIocScanner.scan(formulas, new LinkedHashMap<>(),
                0, null, 64, 0, limitFired::incrementAndGet);

        assertEquals(1, iocs.size(),
                "twenty copies of one EXEC are one indicator; got " + iocs);
        assertEquals(0, limitFired.get(),
                "collapsing duplicates withholds nothing, so no flag; emitted " + iocs);
    }
}
