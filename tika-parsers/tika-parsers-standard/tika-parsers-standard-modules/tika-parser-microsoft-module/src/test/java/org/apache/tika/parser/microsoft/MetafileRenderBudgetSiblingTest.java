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

import org.junit.jupiter.api.Test;

import org.apache.tika.parser.ParseContext;

/**
 * The render and OCR caps must bound a document's SIBLING metafiles, not just its nested ones.
 *
 * <p>Every metafile parse calls {@link MetafileRenderBudget#beginDocument}, and siblings inside
 * one container all run at the same {@code ParseRecord} depth. {@code claim} keeps the owner's
 * budget only when the depth is strictly GREATER, so equal-depth siblings reset it -- unless the
 * owner is the CONTAINER, one level shallower. It was not: nothing installed a budget up front,
 * so the container's own beginDocument returned early with nothing to claim, and the first
 * metafile became the owner at its own depth. Every sibling then reset the counters before
 * spending, and the 64-render and 120s OCR caps bounded nothing across a document.
 */
public class MetafileRenderBudgetSiblingTest {

    /** The shape that matters: one container, many sibling metafiles, no nesting. */
    @Test
    public void siblingMetafilesShareTheDocumentsRenderBudget() {
        ParseContext context = new ParseContext();
        // The container claims first, exactly as OfficeParser/OOXMLParser do.
        MetafileRenderBudget.beginDocument(context);
        MetafileRenderBudget budget = context.get(MetafileRenderBudget.class);
        assertNotNull(budget,
                "the container's beginDocument installed no budget, so the first metafile becomes "
                        + "the owner at its own depth and every sibling resets it");

        int rendered = 0;
        for (int sibling = 0; sibling < 10; sibling++) {
            // Each sibling metafile parse claims at ITS depth -- one deeper than the container.
            budget.claim(1);
            if (budget.tryConsume()) {
                rendered++;
            }
        }
        assertEquals(MetafileRenderBudget.DEFAULT_MAX_RENDERS < 10
                        ? MetafileRenderBudget.DEFAULT_MAX_RENDERS : 10, rendered,
                "sibling metafiles did not share one document budget");
    }

    /** With an explicit small cap, the arithmetic is unambiguous. */
    @Test
    public void anExplicitCapBoundsSiblingsToo() {
        ParseContext context = new ParseContext();
        context.set(MetafileRenderBudget.class, new MetafileRenderBudget(2));
        MetafileRenderBudget.beginDocument(context);
        MetafileRenderBudget budget = context.get(MetafileRenderBudget.class);

        int rendered = 0;
        for (int sibling = 0; sibling < 10; sibling++) {
            budget.claim(1);
            if (budget.tryConsume()) {
                rendered++;
            }
        }
        assertEquals(2, rendered,
                "maxRenders=2 but " + rendered + " sibling metafiles rendered: each sibling "
                        + "claims at the same depth as the owner and resets the counters, so the "
                        + "cap bounds nothing across a document");
        assertEquals(8, budget.refused(), "the refusals should have been recorded");
    }

    /** Nested metafiles must still keep spending the owner's budget, not reset it. */
    @Test
    public void nestedMetafilesStillDoNotResetTheBudget() {
        ParseContext context = new ParseContext();
        context.set(MetafileRenderBudget.class, new MetafileRenderBudget(2));
        MetafileRenderBudget.beginDocument(context);
        MetafileRenderBudget budget = context.get(MetafileRenderBudget.class);

        int rendered = 0;
        for (int depth = 1; depth <= 10; depth++) {
            budget.claim(depth);
            if (budget.tryConsume()) {
                rendered++;
            }
        }
        assertEquals(2, rendered, "a deeper metafile reset the owning document's budget");
    }
}
