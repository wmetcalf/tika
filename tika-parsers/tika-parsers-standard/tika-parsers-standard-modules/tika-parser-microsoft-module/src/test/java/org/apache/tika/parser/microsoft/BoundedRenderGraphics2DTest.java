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

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The metafile render bound. A dashed stroke costs {@code pathLength / dashPeriod} segments, both
 * document-controlled, and no cheap property of the input predicts it -- byte size, record count
 * and geometry count were each measured against a real sample and each refuted.
 */
public class BoundedRenderGraphics2DTest {

    private static Graphics2D canvas(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        return g;
    }

    /** A long path, the shape that makes a small dash period expensive. */
    private static Path2D longPath(int segments, double span) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(0, 0);
        for (int i = 1; i <= segments; i++) {
            p.lineTo(i % 2 == 0 ? 0 : span, i * (span / segments));
        }
        return p;
    }

    @Test
    @DisplayName("a sub-pixel dash period is rendered solid")
    void testSubPixelDashesAreRenderedSolid() {
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D delegate = canvas(img);
        delegate.scale(0.00037d, 0.00037d);   // the scale measured on the real sample
        BoundedRenderGraphics2D g = new BoundedRenderGraphics2D(delegate);

        // period 7 user units * 0.00037 = 0.0026 DEVICE px -- ~385 dashes inside one output pixel,
        // which cannot be drawn distinctly, so a solid line is the same image and not an
        // approximation of one.
        g.setStroke(new BasicStroke(6350f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f,
                new float[] {3f, 4f}, 0f));

        assertNull(((BasicStroke) delegate.getStroke()).getDashArray(),
                "an unrenderable dash pattern must reach the delegate as a solid stroke");
        assertEquals(1, g.substitutionCount(), "the substitution must be counted, not silent");
    }

    @Test
    @DisplayName("a VISIBLE dash pattern is passed through untouched")
    void testVisibleDashesArePreserved() {
        // The negative control, and the one that matters most: a rule that fires on legitimate
        // input would silently flatten the dashes of every ordinary chart and diagram, which is
        // worse than no rule at all.
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D delegate = canvas(img);
        BoundedRenderGraphics2D g = new BoundedRenderGraphics2D(delegate);

        float[] dash = {6f, 4f};   // 10 device px at scale 1 -- plainly visible
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, dash, 0f));

        float[] got = ((BasicStroke) delegate.getStroke()).getDashArray();
        assertNotNull(got, "a visible dash pattern must survive");
        assertEquals(6f, got[0], 0.001f);
        assertEquals(4f, got[1], 0.001f);
        assertEquals(0, g.substitutionCount(), "nothing was unrenderable, so nothing was substituted");
    }

    @Test
    @DisplayName("a visible dash period over a long path still hits a segment ceiling")
    void testLongPathWithVisibleDashesFallsBackToSolid() {
        // Rule 1 cannot catch this: the period IS renderable. The path is simply long enough that
        // pathLength/period exceeds the ceiling -- a dense spiral, not a sub-pixel pattern.
        BufferedImage img = new BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D delegate = canvas(img);
        BoundedRenderGraphics2D g = new BoundedRenderGraphics2D(delegate);
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                new float[] {1f, 1f}, 0f));
        assertEquals(0, g.substitutionCount(), "precondition: a 2px period is visible, so rule 1 is silent");

        g.draw(longPath(4000, 400_000d));   // far past MAX_DASH_SEGMENTS at a period of 2

        assertTrue(g.substitutionCount() >= 1,
                "a path long enough to blow the segment ceiling must fall back to solid");
        assertNotNull(((BasicStroke) delegate.getStroke()).getDashArray(),
                "the fallback is per-shape: the caller's stroke must be restored afterwards");
    }

    @Test
    @DisplayName("a short path with visible dashes is NOT downgraded")
    void testShortPathKeepsItsDashes() {
        // The negative control for rule 2, so the ceiling cannot quietly become "never dash".
        BufferedImage img = new BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D delegate = canvas(img);
        BoundedRenderGraphics2D g = new BoundedRenderGraphics2D(delegate);
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                new float[] {2f, 2f}, 0f));
        g.draw(longPath(20, 300d));
        assertEquals(0, g.substitutionCount(), "an ordinary dashed outline must render as dashed");
    }

    @Test
    @DisplayName("the unbounded dash cost is actually bounded")
    void testSubPixelDashRenderIsBounded() {
        // The DoS itself. Unbounded, this shape is the 10.5s and 72s the real corpus samples took;
        // the assertion is deliberately loose because what it must catch is minutes, not milliseconds.
        BufferedImage img = new BufferedImage(1200, 720, BufferedImage.TYPE_INT_ARGB);
        Graphics2D delegate = canvas(img);
        delegate.scale(0.00037d, 0.00037d);
        BoundedRenderGraphics2D g = new BoundedRenderGraphics2D(delegate);
        g.setStroke(new BasicStroke(6350f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f,
                new float[] {3f, 4f}, 0f));

        long t0 = System.nanoTime();
        try {
            g.draw(longPath(2000, 3_000_000d));
        } catch (Throwable t) {
            // Unbounded, this fixture drives Marlin's edge array past the maximum size a Java
            // array can have: "ArrayIndexOutOfBoundsException: array exceeds maximum capacity!"
            // from Renderer.addLine. Named explicitly so the gate cannot be satisfied by an
            // incidental error, and so the failure says what it means.
            throw new AssertionError("the render bound must keep Java2D inside its limits, but "
                    + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(ms < 10_000, "bounded render took " + ms + " ms; unbounded this is minutes");
        assertTrue(g.substitutionCount() >= 1, "the bound must have actually fired");
    }

    @Test
    @DisplayName("a child from create() shares the bound and the count")
    void testCreateKeepsTheBound() {
        // POI's metafile replay calls create() for nested state; a child that forgot the bound
        // would leave the whole defect reachable through a path the tests never look at.
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D delegate = canvas(img);
        BoundedRenderGraphics2D g = new BoundedRenderGraphics2D(delegate);
        Graphics2D child = (Graphics2D) g.create();
        child.scale(0.00037d, 0.00037d);
        child.setStroke(new BasicStroke(6350f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f,
                new float[] {3f, 4f}, 0f));
        assertEquals(1, g.substitutionCount(),
                "a substitution made by a child must be visible to the parent's count");
    }
}
