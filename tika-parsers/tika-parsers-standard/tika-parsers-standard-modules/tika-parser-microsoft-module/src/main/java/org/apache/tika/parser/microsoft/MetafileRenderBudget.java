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

    private final int maxRenders;
    private int used;
    private int refused;

    public MetafileRenderBudget() {
        this(DEFAULT_MAX_RENDERS);
    }

    public MetafileRenderBudget(int maxRenders) {
        this.maxRenders = maxRenders;
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
