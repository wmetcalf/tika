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

import java.awt.geom.Rectangle2D;

/**
 * Chooses the pixel canvas for rasterizing a metafile, and refuses to produce a useless one.
 *
 * <p>Scaling by {@code min(MAX/width, MAX/height)} preserves the aspect ratio the metafile
 * DECLARES -- which the metafile chooses, and which nothing verifies. A document declaring a frame
 * of 1362 x 529110 gets {@code min(0.88, 0.0023)}, a canvas <b>3 pixels wide</b>, and whatever it
 * draws is squeezed out of existence. Nothing failed, nothing was slow, and OCR dutifully read a
 * blank sliver: for a triage tool that is the worst failure mode of the three, because the content
 * is not examined and nothing says so.
 *
 * <p>Not hypothetical. In one corpus document four metafiles declare ratios of 224, 388, 447 and
 * 448, producing 2x1200, 3x1200 and 5x1200 canvases carrying 0.13%-0.20% ink.
 *
 * <p>The declared frame is the lie; the drawing is ordinary. All four report BOUNDS of roughly
 * 7675 x 4604 or 12732 x 7635 -- an aspect ratio of 1.7. So the recovery is to believe the extent
 * of what is actually drawn rather than the frame that was declared.
 *
 * <p>{@link #MAX_ASPECT} is 20 because the corpus is sharply bimodal: across 233 metafiles p98 is
 * 2.65 and p99 is 388, with nothing in between. Every threshold from 4 to 64 selects exactly the
 * same four metafiles, so the choice is insensitive; 20 sits far above any legitimate banner or
 * tall chart and far below anything observed.
 */
final class MetafileCanvas {

    /** Longest side of the rasterized image. */
    static final int MAX_PX = 1200;

    /** Declared width:height beyond which the frame is not believed. */
    static final double MAX_ASPECT = 20.0d;

    /** How the canvas was chosen -- reported, so a degraded render is never silent. */
    enum Source {
        /** The declared frame was plausible and was used. */
        DECLARED,
        /** The declared frame was degenerate; the drawing's own bounds were used instead. */
        BOUNDS_FALLBACK,
        /** Both were degenerate: no canvas can represent this, so nothing is rendered. */
        UNUSABLE
    }

    static final class Canvas {
        final int width;
        final int height;
        final Source source;
        /** The declared ratio, kept for reporting even when it was rejected. */
        final double declaredAspect;

        Canvas(int width, int height, Source source, double declaredAspect) {
            this.width = width;
            this.height = height;
            this.source = source;
            this.declaredAspect = declaredAspect;
        }
    }

    private MetafileCanvas() {
    }

    static double aspect(double w, double h) {
        if (w <= 0 || h <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.max(w / h, h / w);
    }

    /**
     * @param declaredWidth  frame width the metafile declares
     * @param declaredHeight frame height the metafile declares
     * @param bounds         extent of what the metafile actually draws, or null if unavailable
     * @return the canvas to rasterize into, or null if the metafile cannot be usefully rendered
     */
    static Canvas choose(double declaredWidth, double declaredHeight, Rectangle2D bounds) {
        if (declaredWidth <= 0 || declaredHeight <= 0) {
            return null;
        }
        double declaredAspect = aspect(declaredWidth, declaredHeight);
        if (declaredAspect <= MAX_ASPECT) {
            return scale(declaredWidth, declaredHeight, Source.DECLARED, declaredAspect);
        }
        if (bounds != null && aspect(bounds.getWidth(), bounds.getHeight()) <= MAX_ASPECT) {
            return scale(bounds.getWidth(), bounds.getHeight(), Source.BOUNDS_FALLBACK,
                    declaredAspect);
        }
        // Deliberately NOT a squeezed or cropped canvas. Rendering a 2-pixel-wide sliver and
        // OCRing it produces confident emptiness, which is worse than an explicit refusal: the
        // caller reports UNUSABLE so the document is marked under-examined rather than clean.
        return new Canvas(0, 0, Source.UNUSABLE, declaredAspect);
    }

    private static Canvas scale(double w, double h, Source source, double declaredAspect) {
        double scale = Math.min((double) MAX_PX / w, (double) MAX_PX / h);
        return new Canvas(Math.max(1, (int) (w * scale)), Math.max(1, (int) (h * scale)),
                source, declaredAspect);
    }
}
