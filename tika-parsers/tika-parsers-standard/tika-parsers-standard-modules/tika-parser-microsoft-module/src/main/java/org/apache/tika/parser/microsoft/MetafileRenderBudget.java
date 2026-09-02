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

import org.apache.tika.parser.ParseContext;

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

    /** Nesting depth of the parse that currently owns this budget; -1 until one claims it. */
    /**
     * How many document scopes are currently open on this budget.
     *
     * <p>Replaces an owner-DEPTH comparison. Depth was the wrong oracle, for the same reason it
     * was the wrong oracle in {@code ParseRecord}: it only distinguishes a container from its
     * metafiles when something actually increments it. It does not on the SXWPF path --
     * {@code SXWPFWordExtractorDecorator.resolveEmfNames} calls {@code EMFParser.parse} directly,
     * not through CompositeParser -- so every EMF ran at the owner's own depth, failed the
     * "strictly deeper" test, and reset the counters for each sibling. Counting instead: the
     * outermost scope resets, inner ones just spend, and no one has to guess from a number that
     * something else is responsible for maintaining.
     */
    private int scopes = 0;

    /**
     * Begin a document, resetting the budget when this parse is a new one rather than something
     * nested inside the parse that already owns it.
     *
     * <p>Necessary because a {@link ParseContext} is routinely reused across INDEPENDENT documents
     * -- TikaCLI builds one and passes it to every file on the command line, and the GUI keeps one
     * across successive opens. Measured without this: two independent parses through one context
     * came back used=1 then used=2, so after enough files every later document silently lost
     * rasterization, OCR and image hashing while being marked exhausted. A limit that leaks across
     * documents is worse than no limit, because it turns a bound into a progressive blinding.
     *
     * <p>The rule is RELATIVE, not an absolute depth. An earlier version asked whether the depth
     * was at most 2, which held only for the default {@code AutoDetectParser} nesting: constructed
     * as {@code AutoDetectParser(Parser...)} the chain is one shorter, a container runs at depth 1
     * and its embedded metafiles at depth 2 -- so every metafile would have looked top-level and
     * been handed a fresh budget, and the render and OCR caps would have bounded nothing at all.
     * Comparing against the OWNER's depth needs no such assumption: whatever the chain, an
     * embedded parse is strictly deeper than the parse that contains it.
     */
    public static void beginDocument(ParseContext context) {
        MetafileRenderBudget budget = context.get(MetafileRenderBudget.class);
        if (budget == null) {
            // INSTALL it, do not return. Returning here is what broke the cap: the container
            // (OfficeParser, OOXMLParser) calls this first, found nothing to claim, and left
            // ownership unset -- so the FIRST metafile created the budget and claimed it at its
            // OWN depth. Every sibling metafile then claimed at that same depth, and claim()
            // keeps the owner's budget only when the depth is strictly GREATER, so each sibling
            // reset used/refused/ocrSpentMillis/ocrRefused before spending. The 64-render and
            // 120s OCR caps therefore bounded nothing across a document's siblings -- only
            // across its nested metafiles, which is not the shape these limits exist for
            // (measured: 623 sibling metafiles in 1.7 MB).
            //
            // Installing here makes the CONTAINER the owner, one level shallower than its
            // metafiles, which is exactly the relationship claim() was written for.
            budget = new MetafileRenderBudget();
            context.set(MetafileRenderBudget.class, budget);
        }
        budget.claim();
    }

    /**
     * Close the scope opened by {@link #beginDocument}. Must run in a finally, one for one.
     */
    public static void endDocument(ParseContext context) {
        MetafileRenderBudget budget = context.get(MetafileRenderBudget.class);
        if (budget != null) {
            budget.release();
        }
    }

    /**
     * Open a document scope. The OUTERMOST scope resets; inner ones just spend its budget.
     *
     * <p>Package-private rather than private so the rule can be tested without standing up a
     * whole parser chain -- the failure that matters is the permissive one, and it is invisible
     * from the outside until a document has already bypassed the cap.
     */
    void claim() {
        if (scopes == 0) {
            reset();
        }
        scopes++;
    }

    /** Open scopes. Package-private so the pairing invariant is assertable at all. */
    int scopes() {
        return scopes;
    }

    /** Close a document scope opened by {@link #claim()}. Must run in a finally. */
    void release() {
        if (scopes > 0) {
            scopes--;
        }
    }

    /** Clears everything this document spent, keeping the configured limits. */
    private void reset() {
        used = 0;
        refused = 0;
        ocrSpentMillis = 0;
        ocrRefused = 0;
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
