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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Rectangle2D;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Choosing a metafile's raster canvas. The declared frame is document-controlled and unverified;
 * believing it lets a metafile scale itself out of existence and be OCRed as blank, with nothing
 * reporting that it went unexamined.
 */
public class MetafileCanvasTest {

    /** The real sample: declares 1362 x 529110, actually draws inside 7675 x 4604. */
    private static final double EVASION_W = 1362.8;
    private static final double EVASION_H = 529110.8;
    private static final Rectangle2D HONEST_BOUNDS = new Rectangle2D.Double(-3, -3, 7675, 4604);

    @Test
    @DisplayName("the shipping rule would squeeze this metafile to a 3-pixel sliver")
    void testTheDefectItself() {
        // Not an assertion about the fix -- about the arithmetic being defended against, so the
        // fixture cannot quietly stop representing the attack.
        double scale = Math.min(1200.0 / EVASION_W, 1200.0 / EVASION_H);
        int w = Math.max(1, (int) (EVASION_W * scale));
        assertEquals(3, w, "the declared frame yields a 3px-wide canvas: nothing survives OCR");
    }

    @Test
    @DisplayName("a degenerate declared frame falls back to the drawing's own bounds")
    void testDegenerateFrameFallsBackToBounds() {
        MetafileCanvas.Canvas c = MetafileCanvas.choose(EVASION_W, EVASION_H, HONEST_BOUNDS);
        assertNotNull(c);
        assertEquals(MetafileCanvas.Source.BOUNDS_FALLBACK, c.source);
        assertTrue(c.width >= 200 && c.height >= 200,
                "the recovered canvas must be big enough to read: got " + c.width + "x" + c.height);
        assertEquals(1200, Math.max(c.width, c.height), "still bounded by MAX_PX");
        assertTrue(c.declaredAspect > 300, "the rejected ratio is reported, not discarded");
    }

    @Test
    @DisplayName("an ORDINARY metafile is untouched")
    void testOrdinaryMetafileUsesItsDeclaredFrame() {
        // The negative control. Across 233 corpus metafiles p98 of the declared ratio is 2.65, so
        // a rule that fired on ordinary input would silently re-scale nearly every diagram in
        // every document -- far more damage than the evasion it defends against.
        MetafileCanvas.Canvas c = MetafileCanvas.choose(1362.0, 821.3,
                new Rectangle2D.Double(0, 0, 7670, 4608));
        assertEquals(MetafileCanvas.Source.DECLARED, c.source,
                "a 1.7 aspect ratio is ordinary and must use the declared frame");
        assertEquals(1200, c.width);
        assertEquals(723, c.height);
    }

    @Test
    @DisplayName("a genuinely tall-but-plausible banner keeps its declared frame")
    void testPlausibleBannerIsNotRejected() {
        // 15:1 is a real shape -- a wide banner or a tall column chart. The threshold is 20, and
        // the corpus is bimodal (p98=2.65, p99=388), so there is a wide margin either side.
        MetafileCanvas.Canvas c = MetafileCanvas.choose(15000, 1000, null);
        assertEquals(MetafileCanvas.Source.DECLARED, c.source, "15:1 must not be treated as an evasion");
    }

    @Test
    @DisplayName("when the bounds are degenerate too, refuse rather than render a sliver")
    void testBothDegenerateIsRefused() {
        // Rendering a 2px sliver and OCRing it yields confident emptiness. An explicit refusal
        // marks the document under-examined instead, which is the whole point of the change.
        MetafileCanvas.Canvas c = MetafileCanvas.choose(EVASION_W, EVASION_H,
                new Rectangle2D.Double(0, 0, 5, 500000));
        assertEquals(MetafileCanvas.Source.UNUSABLE, c.source);
    }

    @Test
    @DisplayName("missing bounds do not crash the fallback")
    void testNullBoundsWithDegenerateFrame() {
        MetafileCanvas.Canvas c = MetafileCanvas.choose(EVASION_W, EVASION_H, null);
        assertEquals(MetafileCanvas.Source.UNUSABLE, c.source,
                "no bounds to fall back on means the metafile cannot be rendered usefully");
    }

    @Test
    @DisplayName("a non-positive frame is rejected outright")
    void testNonPositiveFrame() {
        assertNull(MetafileCanvas.choose(0, 100, null));
        assertNull(MetafileCanvas.choose(100, -1, null));
    }
}
