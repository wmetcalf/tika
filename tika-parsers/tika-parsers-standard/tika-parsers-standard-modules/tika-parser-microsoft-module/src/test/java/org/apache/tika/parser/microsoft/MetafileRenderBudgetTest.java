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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;

/**
 * The per-document cap on how many metafiles may be rasterized and OCRed. Per-metafile cost is
 * bounded by {@link BoundedRenderGraphics2D}; this bounds the COUNT, which the document chooses --
 * the worst corpus document carries 623 metafiles in 1.7 MB, each handed to a Tesseract process
 * with a 120-second timeout of its own.
 */
public class MetafileRenderBudgetTest {

    @Test
    @DisplayName("the budget stops exactly at its limit and counts what it refused")
    void testBudgetStopsAtTheLimit() {
        MetafileRenderBudget budget = new MetafileRenderBudget(3);
        assertTrue(budget.tryConsume());
        assertTrue(budget.tryConsume());
        assertTrue(budget.tryConsume());
        assertFalse(budget.tryConsume(), "the 4th render must be refused at a limit of 3");
        assertFalse(budget.tryConsume());
        assertEquals(3, budget.used());
        assertEquals(2, budget.refused(),
                "refusals are counted, so an under-examined document can say so");
    }

    @Test
    @DisplayName("an ordinary document is never touched by the budget")
    void testOrdinaryDocumentIsUnaffected() {
        // The negative control. Across 453 corpus documents carrying metafiles the median is 1 and
        // p95 is 20, so the default must be nowhere near an ordinary document -- a cap that bites
        // normal input would silently stop OCRing legitimate diagrams.
        MetafileRenderBudget budget = new MetafileRenderBudget();
        for (int i = 0; i < 20; i++) {
            assertTrue(budget.tryConsume(), "p95 of the corpus must fit inside the default budget");
        }
        assertEquals(0, budget.refused(), "nothing refused for a p95 document");
        assertTrue(MetafileRenderBudget.DEFAULT_MAX_RENDERS >= 3 * 20,
                "the default should sit well above p95 (20), not on top of it");
    }

    @Test
    @DisplayName("one budget is shared by every metafile in a document")
    void testBudgetIsSharedThroughTheParseContext() {
        // A per-metafile budget would bound nothing: 623 metafiles would each get a fresh 64.
        // One ParseContext does reach every embedded parse (measured: 233 metafiles, one context).
        ParseContext context = new ParseContext();
        MetafileRenderBudget first = EMFParser.renderBudget(context);
        MetafileRenderBudget second = EMFParser.renderBudget(context);
        assertSame(first, second, "the second metafile must find the FIRST metafile's budget");
        first.tryConsume();
        assertEquals(1, second.used(), "spending through one reference must be visible to the other");
    }

    @Test
    @DisplayName("EMF and WMF draw on the SAME document budget")
    void testEmfAndWmfShareOneBudget() {
        // They are separate parsers over the same container. Two independent budgets would let a
        // document carrying both formats spend twice.
        ParseContext context = new ParseContext();
        MetafileRenderBudget budget = new MetafileRenderBudget(1);
        context.set(MetafileRenderBudget.class, budget);
        assertTrue(EMFParser.renderBudget(context).tryConsume());
        assertFalse(EMFParser.renderBudget(context).tryConsume(),
                "a WMF must not get its own allowance after an EMF spent the budget");
    }

    @Test
    @DisplayName("a document stops OCRing once it has spent its total time budget")
    void testOcrTimeBudgetStops() {
        // The render cap alone does not bound OCR: Tesseract's timeout is PER IMAGE and defaults
        // to 120 s, so 64 renders is 2.1 hours. Bounding the TOTAL makes the worst case
        // independent of that per-image timeout.
        MetafileRenderBudget budget = new MetafileRenderBudget(64, 1000L);
        assertTrue(budget.tryOcr());
        budget.chargeOcr(400);
        assertTrue(budget.tryOcr(), "400ms of a 1000ms budget must not exhaust it");
        budget.chargeOcr(700);
        assertFalse(budget.tryOcr(), "1100ms spent against a 1000ms budget must refuse");
        assertEquals(1100, budget.ocrSpentMillis());
        assertEquals(1, budget.ocrRefused(), "refusals are counted, so the document can say so");
    }

    @Test
    @DisplayName("a TIMED OUT OCR is charged, not forgiven")
    void testTimedOutOcrIsCharged() {
        // The expensive case IS the timeout. Charging only successful calls would let a document
        // spend unbounded time on failures -- the exact shape this budget exists to stop.
        MetafileRenderBudget budget = new MetafileRenderBudget(64, 5000L);
        budget.chargeOcr(120_000);   // one image that ran to Tesseract's default timeout
        assertFalse(budget.tryOcr(), "a single timed-out image must exhaust a 5s budget");
    }

    @Test
    @DisplayName("the worst case is the budget plus ONE in-flight image, not 64 of them")
    void testWorstCaseIsBounded() {
        // 64 renders x 120s per-image timeout = 2.1 hours before this change. The budget is
        // checked BEFORE each call, so the overshoot is at most one image's timeout.
        MetafileRenderBudget budget = new MetafileRenderBudget(64, 120_000L);
        long spent = 0;
        int started = 0;
        while (budget.tryOcr()) {
            budget.chargeOcr(120_000);   // every image hits the per-image timeout
            spent += 120_000;
            started++;
            assertTrue(started <= 2, "at most one image may start beyond an exhausted budget");
        }
        assertTrue(spent <= 240_000,
                "worst case must be the budget plus one in-flight image; got " + spent + " ms");
    }

    @Test
    @DisplayName("an ordinary document never approaches the OCR budget")
    void testOrdinaryDocumentNeverHitsTheOcrBudget() {
        // Negative control. Corpus median is 1 metafile, p95 is 20, and an ordinary metafile OCRs
        // in about a second -- so the default must be a ceiling on pathology, not an allowance
        // that ordinary documents bump into.
        MetafileRenderBudget budget = new MetafileRenderBudget();
        for (int i = 0; i < 20; i++) {
            assertTrue(budget.tryOcr(), "a p95 document must never be denied OCR");
            budget.chargeOcr(1000);
        }
        assertEquals(0, budget.ocrRefused());
        assertTrue(MetafileRenderBudget.DEFAULT_OCR_BUDGET_MILLIS >= 4 * 20 * 1000L,
                "the default should sit well clear of a p95 document's real OCR time");
    }


    @Test
    @DisplayName("tryMetafileOcr actually consults the budget before spending time")
    void testOcrPathConsultsTheBudget() throws Exception {
        // The tests above exercise the budget OBJECT. Without this one, deleting the check from
        // tryMetafileOcr would leave every one of them green while the defect was fully restored.
        ParseContext context = new ParseContext();
        MetafileRenderBudget budget = new MetafileRenderBudget(64, 1000L);
        budget.chargeOcr(120_000);          // one timed-out image has already blown the budget
        context.set(MetafileRenderBudget.class, budget);

        Metadata metadata = new Metadata();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(new BodyContentHandler(-1), metadata);
        EMFParser.tryMetafileOcr(new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB),
                xhtml, metadata, context);

        assertEquals("true", metadata.get("msoffice:metafile-ocr-budget-exhausted"),
                "an exhausted document must report that OCR was skipped, not skip it silently");
        assertEquals(1, budget.ocrRefused());
    }


    @Test
    @DisplayName("the budget does NOT leak across independent documents")
    void testBudgetResetsPerDocument() {
        // TikaCLI builds one ParseContext and passes it to every file on the command line; the GUI
        // keeps one across opens. Measured without a boundary: two independent parses came back
        // used=1 then used=2, so after enough files every later document silently lost
        // rasterization, OCR and hashing while being marked exhausted.
        ParseContext context = new ParseContext();
        MetafileRenderBudget budget = new MetafileRenderBudget(2, 1000L);
        context.set(MetafileRenderBudget.class, budget);

        MetafileRenderBudget.beginDocument(context);       // file 1
        assertTrue(budget.tryConsume());
        assertTrue(budget.tryConsume());
        assertFalse(budget.tryConsume(), "document 1 spends its whole budget");
        budget.chargeOcr(5000);
        assertFalse(budget.tryOcr(), "and its whole OCR allowance");

        MetafileRenderBudget.beginDocument(context);       // file 2, same context

        assertTrue(budget.tryConsume(), "document 2 must get its OWN render budget");
        assertTrue(budget.tryOcr(), "document 2 must get its OWN OCR budget");
        assertEquals(0, budget.ocrSpentMillis(), "spend from document 1 must not follow it");
    }

    @Test
    @DisplayName("a nested parse must NOT hand itself a fresh budget")
    void testNestedParseDoesNotResetTheBudget() {
        // The permissive direction is the dangerous one, and it is invisible from outside until a
        // document has already bypassed the cap: if each embedded metafile counted as a new
        // document it would get a fresh 64 and the cap would bound nothing at all.
        MetafileRenderBudget budget = new MetafileRenderBudget(2, 1000L);
        budget.claim(1);                 // the container owns the budget at depth 1
        budget.tryConsume();
        budget.tryConsume();
        assertFalse(budget.tryConsume(), "precondition: the container has spent its budget");

        budget.claim(2);                 // an embedded metafile, one level deeper
        assertFalse(budget.tryConsume(), "a nested parse must keep spending the owner's budget");
        budget.claim(7);                 // and deeper still
        assertFalse(budget.tryConsume(), "depth alone must not buy a fresh allowance");
    }

    @Test
    @DisplayName("the rule is RELATIVE, so it survives a different parser chain")
    void testRuleDoesNotDependOnAnAbsoluteDepth() {
        // An earlier version asked whether depth <= 2, which held only for the default
        // AutoDetectParser nesting. Constructed as AutoDetectParser(Parser...) the chain is one
        // shorter -- measured: a standalone metafile moves from depth 2 to depth 1 -- so a
        // container runs at 1 and its metafiles at 2. Under the absolute rule every one of those
        // metafiles looked top level and reset the budget, bounding nothing.
        MetafileRenderBudget shortChain = new MetafileRenderBudget(2, 1000L);
        shortChain.claim(1);             // container, short chain
        shortChain.tryConsume();
        shortChain.tryConsume();
        shortChain.claim(2);             // its metafiles -- depth 2, which the old rule allowed
        assertFalse(shortChain.tryConsume(),
                "depth 2 is EMBEDDED here, and must not reset the budget");

        MetafileRenderBudget longChain = new MetafileRenderBudget(2, 1000L);
        longChain.claim(2);              // container, default chain
        longChain.tryConsume();
        longChain.tryConsume();
        longChain.claim(4);              // its metafiles
        assertFalse(longChain.tryConsume(), "same rule, deeper chain, same answer");

        // ...and a sibling document at the owner's own depth still gets its own budget.
        longChain.claim(2);
        assertTrue(longChain.tryConsume(), "a new document at the owner's depth resets");
    }

}
