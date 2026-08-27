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

import java.io.Serializable;

/**
 * How many embedded metafiles ONE document may rasterize and OCR.
 *
 * <p>Rendering a metafile is bounded per-metafile by {@link BoundedRenderGraphics2D}, but the
 * NUMBER of them is chosen by the document and was not bounded at all. In the triage corpus the
 * worst document carries <b>623 metafiles in 1.7 MB</b>, and every one of them is rasterized and
 * then handed to a separate Tesseract process whose own timeout is 120 seconds EACH -- so a small
 * file still buys hours. That is the cheaper attack of the two: it needs no crafted geometry, only
 * a lot of ordinary pictures.
 *
 * <p>Deliberately NOT Tika's {@code EmbeddedLimits.maxCount}, which is unlimited by default and,
 * when it does fire, stops embedded processing entirely including siblings. The two costs here are
 * not alike: pulling the vector TEXT out of a metafile is cheap and is exactly what triage wants,
 * while rasterizing and OCRing it is the expensive part. This budget caps only the expensive part,
 * so a document past the limit still yields every metafile's text.
 *
 * <p>Default of {@value #DEFAULT_MAX_RENDERS}, from the corpus distribution rather than taste:
 * across 453 documents carrying metafiles the median is 1, p90 is 13 and p95 is 20, so 64 sits
 * more than three times above p95 and takes effect on 9 documents in 6,574 (0.14%). Those nine are
 * the anomalous ones, and hitting the limit is reported rather than silent.
 */
public class MetafileRenderBudget implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Renders allowed per document before the budget refuses. */
    public static final int DEFAULT_MAX_RENDERS = 64;

    /**
     * Total wall-clock milliseconds one document may spend OCRing its metafiles.
     *
     * <p>The render cap alone does not bound OCR, because Tesseract's timeout is <b>per image</b>
     * and defaults to 120 seconds: 64 renders x 120 s is 2.1 hours for a single document, which is
     * not a usable answer for batch triage no matter how the count is capped. Bounding the TOTAL
     * makes the worst case independent of that per-image timeout -- the document spends at most
     * this budget, plus however long the one in-flight image takes to hit its own timeout.
     *
     * <p>Two minutes is far beyond any legitimate document: the corpus median is one metafile, p95
     * is twenty, and an ordinary metafile OCRs in about a second. It is a ceiling on pathological
     * input, not a working allowance.
     */
    public static final long DEFAULT_OCR_BUDGET_MILLIS = 120_000L;

    private final int maxRenders;
    private final long ocrBudgetMillis;
    private int used;
    private int refused;
    private long ocrSpentMillis;
    private int ocrRefused;

    public MetafileRenderBudget() {
        this(DEFAULT_MAX_RENDERS, DEFAULT_OCR_BUDGET_MILLIS);
    }

    public MetafileRenderBudget(int maxRenders) {
        this(maxRenders, DEFAULT_OCR_BUDGET_MILLIS);
    }

    public MetafileRenderBudget(int maxRenders, long ocrBudgetMillis) {
        this.maxRenders = maxRenders;
        this.ocrBudgetMillis = ocrBudgetMillis;
    }

    /**
     * Whether this document may still spend time on OCR. Checked BEFORE handing an image to
     * Tesseract, so an exhausted document stops paying rather than starting one more 120-second
     * wait it has no budget for.
     */
    public boolean tryOcr() {
        if (ocrSpentMillis >= ocrBudgetMillis) {
            ocrRefused++;
            return false;
        }
        return true;
    }

    /** Charge the time an OCR attempt actually took, whether it succeeded, failed or timed out. */
    public void chargeOcr(long millis) {
        if (millis > 0) {
            ocrSpentMillis += millis;
        }
    }

    /** Milliseconds of OCR this document has spent. */
    public long ocrSpentMillis() {
        return ocrSpentMillis;
    }

    /** How many metafiles were denied OCR. Non-zero means the document is under-examined. */
    public int ocrRefused() {
        return ocrRefused;
    }

    public long ocrBudgetMillis() {
        return ocrBudgetMillis;
    }

    /**
     * Claim one render. Returns false once the document has spent its budget, in which case the
     * caller must skip rasterization and OCR -- but NOT the vector text extraction that already
     * happened, which is the cheap half this budget deliberately leaves alone.
     */
    public boolean tryConsume() {
        if (used >= maxRenders) {
            refused++;
            return false;
        }
        used++;
        return true;
    }

    /** How many metafiles were refused a render. Non-zero means the document is under-examined. */
    public int refused() {
        return refused;
    }

    public int used() {
        return used;
    }

    public int maxRenders() {
        return maxRenders;
    }
}
