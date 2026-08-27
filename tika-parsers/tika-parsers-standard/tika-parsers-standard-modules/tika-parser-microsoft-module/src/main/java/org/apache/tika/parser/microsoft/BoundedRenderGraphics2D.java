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

import java.awt.BasicStroke;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;

/**
 * A {@link Graphics2D} that neutralises the one metafile drawing operation whose cost is unbounded
 * in attacker-controlled input: a DASHED stroke.
 *
 * <p>Java2D renders a dashed stroke by subdividing the path into {@code pathLength / dashPeriod}
 * segments and emitting caps for each. Both terms are chosen by the document, and neither is
 * visible in any cheap property of the input -- measured on a real sample, a metafile with 3,130
 * records and 3,173 geometry points took 72 seconds, while one with 195,629 records and 195,059
 * points took 585 milliseconds. Byte size, record count and geometry count were each tested as
 * predictors and each refuted, so a bound derived from them would look principled and do nothing.
 *
 * <p>Two rules, both of which leave a legitimate document's rendering untouched:
 *
 * <ol>
 *   <li><b>A sub-pixel dash period renders solid.</b> If the period transforms to less than one
 *       DEVICE pixel it cannot be drawn distinctly at all -- on the sample above it was 0.0026 px,
 *       some 385 dashes inside a single output pixel -- so a solid line is not an approximation of
 *       that, it is the same image. This is what the real documents hit: stripping dashes took one
 *       from 10,525 ms to 498 ms and another from 7,475 ms to 133 ms, with four and seven dashed
 *       strokes respectively accounting for ~95% of the render.</li>
 *   <li><b>A visible dash pattern still gets a segment ceiling.</b> A period of one device pixel
 *       against an arbitrarily long path (a dense spiral) is still unbounded, so before stroking a
 *       dashed shape the flattened device-space path length is measured and the stroke falls back
 *       to solid past {@link #MAX_DASH_SEGMENTS}. Measuring the length is linear in the path and
 *       cheap relative to dashing it.</li>
 * </ol>
 *
 * <p>Substitutions are counted, never silent: the caller reports them so a truncated or simplified
 * render is visible to a consumer rather than passing as a faithful one.
 */
final class BoundedRenderGraphics2D extends Graphics2D {

    /**
     * Below one device pixel a dash pattern cannot be rendered distinctly, so substituting a solid
     * stroke changes no pixel. Deliberately the conservative threshold: it fires ONLY where the
     * dashes are already unrenderable, which is why it needs no configuration knob.
     */
    static final double MIN_DEVICE_DASH_PERIOD = 1.0d;

    /** Ceiling on dash segments for one stroked shape, for periods too large for rule 1. */
    static final int MAX_DASH_SEGMENTS = 20_000;

    /** Flatness for the length estimate; coarse on purpose -- this feeds a threshold, not a render. */
    private static final double LENGTH_FLATNESS = 1.0d;

    private final Graphics2D d;
    private final int[] substitutions;   // shared with every create()d child

    BoundedRenderGraphics2D(Graphics2D delegate) {
        this(delegate, new int[1]);
    }

    private BoundedRenderGraphics2D(Graphics2D delegate, int[] substitutions) {
        this.d = delegate;
        this.substitutions = substitutions;
    }

    /** How many dashed strokes were rendered solid. Zero means the render was faithful. */
    int substitutionCount() {
        return substitutions[0];
    }

    @Override
    public void setStroke(Stroke s) {
        // Deliberately NOT decided here. A metafile may select a dashed pen and then change the
        // world transform WITHOUT re-selecting it, so a decision made at setStroke is made against
        // a transform that no longer applies by the time anything is drawn -- a pattern judged
        // sub-pixel under an early small scale would stay solid after a later transform made its
        // dashes plainly visible, corrupting the raster that OCR and image hashing then read.
        d.setStroke(s);
    }

    /**
     * Both rules, applied per draw against the transform actually in force.
     *
     * <p>The caller's stroke is restored afterwards, so a substitution affects only the shape that
     * needed it rather than everything drawn after it.
     */
    @Override
    public void draw(Shape s) {
        Stroke current = d.getStroke();
        if (s != null && current instanceof BasicStroke
                && ((BasicStroke) current).getDashArray() != null
                && shouldSolidify((BasicStroke) current, s)) {
            substitutions[0]++;
            d.setStroke(solid((BasicStroke) current));
            try {
                d.draw(s);
            } finally {
                d.setStroke(current);
            }
            return;
        }
        d.draw(s);
    }

    private boolean shouldSolidify(BasicStroke bs, Shape s) {
        float[] dash = bs.getDashArray();
        double period = 0;
        for (float f : dash) {
            period += f;
        }
        if (period <= 0) {
            return true;   // a zero period would divide the path infinitely
        }
        if (period * deviceScale() < MIN_DEVICE_DASH_PERIOD) {
            return true;   // rule 1: unrenderable, so solid is the same image
        }
        return dashSegmentsExceedBudget(s, dash, period);
    }

    /**
     * Rule 2: a visible period over a long enough path is still unbounded.
     *
     * <p>Counted in DASH SEGMENTS, not pattern periods. A BasicStroke cycles through every element
     * of the dash array, so one period of a k-element pattern emits k subdivisions -- budgeting by
     * period let an attacker keep the total period comfortably above a device pixel and multiply
     * the real work by the entry count instead. Measured on the previous version: holding the
     * period at 10 px and the path at 10,000 periods (inside the ceiling), raising the array from
     * 2 to 5,000 entries took real segments from 20,000 to 50,000,000 with the bound never firing.
     */
    private boolean dashSegmentsExceedBudget(Shape s, float[] dash, double period) {
        int entries = Math.max(1, dash.length);
        double budgetLength = period * ((double) MAX_DASH_SEGMENTS / entries);
        double length = 0;
        double[] c = new double[6];
        double px = 0, py = 0, sx = 0, sy = 0;
        PathIterator it = s.getPathIterator(null, LENGTH_FLATNESS);
        while (!it.isDone()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO:
                    px = sx = c[0];
                    py = sy = c[1];
                    break;
                case PathIterator.SEG_LINETO:
                    length += Math.hypot(c[0] - px, c[1] - py);
                    px = c[0];
                    py = c[1];
                    break;
                case PathIterator.SEG_CLOSE:
                    length += Math.hypot(sx - px, sy - py);
                    px = sx;
                    py = sy;
                    break;
                default:
                    break;   // getPathIterator(flatness) yields only MOVETO/LINETO/CLOSE
            }
            if (length > budgetLength) {
                return true;   // stop early: the answer cannot change
            }
            it.next();
        }
        return false;
    }

    private static BasicStroke solid(BasicStroke bs) {
        return new BasicStroke(bs.getLineWidth(), bs.getEndCap(), bs.getLineJoin(),
                Math.max(1.0f, bs.getMiterLimit()));
    }

    private double deviceScale() {
        AffineTransform tx = d.getTransform();
        if (tx == null) {
            return 1.0d;
        }
        // The larger axis: a dash is unrenderable only if it is sub-pixel in BOTH directions, so
        // taking the max is the conservative choice -- it substitutes less, not more.
        return Math.max(Math.hypot(tx.getScaleX(), tx.getShearY()),
                Math.hypot(tx.getShearX(), tx.getScaleY()));
    }

    @Override
    public Graphics create() {
        Graphics child = d.create();
        return child instanceof Graphics2D
                ? new BoundedRenderGraphics2D((Graphics2D) child, substitutions)
                : child;
    }

    @Override public void draw3DRect(int a0, int a1, int a2, int a3, boolean a4) { d.draw3DRect(a0, a1, a2, a3, a4); }
    @Override public void fill3DRect(int a0, int a1, int a2, int a3, boolean a4) { d.fill3DRect(a0, a1, a2, a3, a4); }
    @Override public boolean drawImage(java.awt.Image a0, java.awt.geom.AffineTransform a1, java.awt.image.ImageObserver a2) { return d.drawImage(a0, a1, a2); }
    @Override public void drawImage(java.awt.image.BufferedImage a0, java.awt.image.BufferedImageOp a1, int a2, int a3) { d.drawImage(a0, a1, a2, a3); }
    @Override public void drawRenderedImage(java.awt.image.RenderedImage a0, java.awt.geom.AffineTransform a1) { d.drawRenderedImage(a0, a1); }
    @Override public void drawRenderableImage(java.awt.image.renderable.RenderableImage a0, java.awt.geom.AffineTransform a1) { d.drawRenderableImage(a0, a1); }
    @Override public void drawString(java.lang.String a0, int a1, int a2) { d.drawString(a0, a1, a2); }
    @Override public void drawString(java.lang.String a0, float a1, float a2) { d.drawString(a0, a1, a2); }
    @Override public void drawString(java.text.AttributedCharacterIterator a0, int a1, int a2) { d.drawString(a0, a1, a2); }
    @Override public void drawString(java.text.AttributedCharacterIterator a0, float a1, float a2) { d.drawString(a0, a1, a2); }
    @Override public void drawGlyphVector(java.awt.font.GlyphVector a0, float a1, float a2) { d.drawGlyphVector(a0, a1, a2); }
    @Override public void fill(java.awt.Shape a0) { d.fill(a0); }
    @Override public boolean hit(java.awt.Rectangle a0, java.awt.Shape a1, boolean a2) { return d.hit(a0, a1, a2); }
    @Override public java.awt.GraphicsConfiguration getDeviceConfiguration() { return d.getDeviceConfiguration(); }
    @Override public void setComposite(java.awt.Composite a0) { d.setComposite(a0); }
    @Override public void setPaint(java.awt.Paint a0) { d.setPaint(a0); }
    @Override public void setRenderingHint(java.awt.RenderingHints.Key a0, java.lang.Object a1) { d.setRenderingHint(a0, a1); }
    @Override public java.lang.Object getRenderingHint(java.awt.RenderingHints.Key a0) { return d.getRenderingHint(a0); }
    @Override public void setRenderingHints(java.util.Map<?, ?> a0) { d.setRenderingHints(a0); }
    @Override public void addRenderingHints(java.util.Map<?, ?> a0) { d.addRenderingHints(a0); }
    @Override public java.awt.RenderingHints getRenderingHints() { return d.getRenderingHints(); }
    @Override public void translate(int a0, int a1) { d.translate(a0, a1); }
    @Override public void translate(double a0, double a1) { d.translate(a0, a1); }
    @Override public void rotate(double a0) { d.rotate(a0); }
    @Override public void rotate(double a0, double a1, double a2) { d.rotate(a0, a1, a2); }
    @Override public void scale(double a0, double a1) { d.scale(a0, a1); }
    @Override public void shear(double a0, double a1) { d.shear(a0, a1); }
    @Override public void transform(java.awt.geom.AffineTransform a0) { d.transform(a0); }
    @Override public void setTransform(java.awt.geom.AffineTransform a0) { d.setTransform(a0); }
    @Override public java.awt.geom.AffineTransform getTransform() { return d.getTransform(); }
    @Override public java.awt.Paint getPaint() { return d.getPaint(); }
    @Override public java.awt.Composite getComposite() { return d.getComposite(); }
    @Override public void setBackground(java.awt.Color a0) { d.setBackground(a0); }
    @Override public java.awt.Color getBackground() { return d.getBackground(); }
    @Override public java.awt.Stroke getStroke() { return d.getStroke(); }
    @Override public void clip(java.awt.Shape a0) { d.clip(a0); }
    @Override public java.awt.font.FontRenderContext getFontRenderContext() { return d.getFontRenderContext(); }
    @Override public java.awt.Graphics create(int a0, int a1, int a2, int a3) { return d.create(a0, a1, a2, a3); }
    @Override public java.awt.Color getColor() { return d.getColor(); }
    @Override public void setColor(java.awt.Color a0) { d.setColor(a0); }
    @Override public void setPaintMode() { d.setPaintMode(); }
    @Override public void setXORMode(java.awt.Color a0) { d.setXORMode(a0); }
    @Override public java.awt.Font getFont() { return d.getFont(); }
    @Override public void setFont(java.awt.Font a0) { d.setFont(a0); }
    @Override public java.awt.FontMetrics getFontMetrics() { return d.getFontMetrics(); }
    @Override public java.awt.FontMetrics getFontMetrics(java.awt.Font a0) { return d.getFontMetrics(a0); }
    @Override public java.awt.Rectangle getClipBounds() { return d.getClipBounds(); }
    @Override public void clipRect(int a0, int a1, int a2, int a3) { d.clipRect(a0, a1, a2, a3); }
    @Override public void setClip(int a0, int a1, int a2, int a3) { d.setClip(a0, a1, a2, a3); }
    @Override public java.awt.Shape getClip() { return d.getClip(); }
    @Override public void setClip(java.awt.Shape a0) { d.setClip(a0); }
    @Override public void copyArea(int a0, int a1, int a2, int a3, int a4, int a5) { d.copyArea(a0, a1, a2, a3, a4, a5); }
    @Override public void drawLine(int a0, int a1, int a2, int a3) { d.drawLine(a0, a1, a2, a3); }
    @Override public void fillRect(int a0, int a1, int a2, int a3) { d.fillRect(a0, a1, a2, a3); }
    @Override public void drawRect(int a0, int a1, int a2, int a3) { d.drawRect(a0, a1, a2, a3); }
    @Override public void clearRect(int a0, int a1, int a2, int a3) { d.clearRect(a0, a1, a2, a3); }
    @Override public void drawRoundRect(int a0, int a1, int a2, int a3, int a4, int a5) { d.drawRoundRect(a0, a1, a2, a3, a4, a5); }
    @Override public void fillRoundRect(int a0, int a1, int a2, int a3, int a4, int a5) { d.fillRoundRect(a0, a1, a2, a3, a4, a5); }
    @Override public void drawOval(int a0, int a1, int a2, int a3) { d.drawOval(a0, a1, a2, a3); }
    @Override public void fillOval(int a0, int a1, int a2, int a3) { d.fillOval(a0, a1, a2, a3); }
    @Override public void drawArc(int a0, int a1, int a2, int a3, int a4, int a5) { d.drawArc(a0, a1, a2, a3, a4, a5); }
    @Override public void fillArc(int a0, int a1, int a2, int a3, int a4, int a5) { d.fillArc(a0, a1, a2, a3, a4, a5); }
    @Override public void drawPolyline(int[] a0, int[] a1, int a2) { d.drawPolyline(a0, a1, a2); }
    @Override public void drawPolygon(int[] a0, int[] a1, int a2) { d.drawPolygon(a0, a1, a2); }
    @Override public void drawPolygon(java.awt.Polygon a0) { d.drawPolygon(a0); }
    @Override public void fillPolygon(int[] a0, int[] a1, int a2) { d.fillPolygon(a0, a1, a2); }
    @Override public void fillPolygon(java.awt.Polygon a0) { d.fillPolygon(a0); }
    @Override public void drawChars(char[] a0, int a1, int a2, int a3, int a4) { d.drawChars(a0, a1, a2, a3, a4); }
    @Override public void drawBytes(byte[] a0, int a1, int a2, int a3, int a4) { d.drawBytes(a0, a1, a2, a3, a4); }
    @Override public boolean drawImage(java.awt.Image a0, int a1, int a2, java.awt.image.ImageObserver a3) { return d.drawImage(a0, a1, a2, a3); }
    @Override public boolean drawImage(java.awt.Image a0, int a1, int a2, int a3, int a4, java.awt.image.ImageObserver a5) { return d.drawImage(a0, a1, a2, a3, a4, a5); }
    @Override public boolean drawImage(java.awt.Image a0, int a1, int a2, java.awt.Color a3, java.awt.image.ImageObserver a4) { return d.drawImage(a0, a1, a2, a3, a4); }
    @Override
    public boolean drawImage(java.awt.Image a0, int a1, int a2, int a3, int a4, java.awt.Color a5, java.awt.image.ImageObserver a6) {
        return d.drawImage(a0, a1, a2, a3, a4, a5, a6);
    }
    @Override
    public boolean drawImage(java.awt.Image a0, int a1, int a2, int a3, int a4, int a5, int a6, int a7, int a8, java.awt.image.ImageObserver a9) {
        return d.drawImage(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9);
    }
    @Override
    public boolean drawImage(java.awt.Image a0, int a1, int a2, int a3, int a4, int a5, int a6, int a7, int a8, java.awt.Color a9, java.awt.image.ImageObserver a10) {
        return d.drawImage(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10);
    }
    @Override public void dispose() { d.dispose(); }
    @Override public void finalize() { d.finalize(); }
    @Override public java.awt.Rectangle getClipRect() { return d.getClipRect(); }
    @Override public boolean hitClip(int a0, int a1, int a2, int a3) { return d.hitClip(a0, a1, a2, a3); }
    @Override public java.awt.Rectangle getClipBounds(java.awt.Rectangle a0) { return d.getClipBounds(a0); }
}
