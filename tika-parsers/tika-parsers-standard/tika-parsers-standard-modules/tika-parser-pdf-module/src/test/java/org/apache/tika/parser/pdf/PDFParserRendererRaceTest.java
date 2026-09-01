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
package org.apache.tika.parser.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import org.apache.tika.renderer.Renderer;

/**
 * A single PDFParser instance is shared across threads -- AutoDetectParser builds one and every
 * concurrent parse goes through it -- and the renderer is resolved DURING a parse. That made
 * initRenderer a check-then-act on shared mutable state: two parses could both find the field
 * unsuitable, both build a renderer, and interleave the assignment with the reads at the call
 * site, so a parse could render with another parse's renderer or observe one mid-publication.
 *
 * <p>Found via OOXMLParserTest.testMultiThreaded, which intermittently lost the embedded
 * /PDF.pdf from testWPSAttachment.ppt -- 20 attachments became 19 with no exception recorded.
 */
public class PDFParserRendererRaceTest {

    /**
     * ROUNDS matters more than threads-per-round. The field goes non-null exactly once per
     * parser, so a single PDFParser offers ONE race window no matter how many iterations run
     * against it -- every later call takes the early-return branch. Measured against the
     * unfixed parser, one window is caught only 63 times in 200 (31.5%), so a handful of
     * iterations inside one round is not a regression test, it is a coin flip. A FRESH parser
     * per round re-arms the window; 40 rounds at that per-round rate miss only ~(0.685^40),
     * about 3 in 100 million.
     */
    private static final int ROUNDS = 40;
    private static final int THREADS = 16;

    @Test
    public void concurrentResolutionYieldsOneRendererPerParser() throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(THREADS);
        try {
            for (int round = 1; round <= ROUNDS; round++) {
                PDFParser parser = new PDFParser();     // re-arm: field starts null again
                Set<Renderer> observed = Collections.newSetFromMap(new ConcurrentHashMap<>());
                CountDownLatch go = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < THREADS; i++) {
                    futures.add(ex.submit(() -> {
                        go.await();
                        Renderer r = parser.resolveRendererForTest();
                        // assertNotNull here, not on the set: the set is backed by a
                        // ConcurrentHashMap, so adding null would throw inside the worker and
                        // surface as an opaque ExecutionException instead of this message.
                        assertNotNull(r, "initRenderer returned null");
                        observed.add(r);
                        return null;
                    }));
                }
                go.countDown();
                for (Future<?> f : futures) {
                    f.get(2, TimeUnit.MINUTES);
                }
                assertEquals(1, observed.size(),
                        "round " + round + " of " + ROUNDS + ": " + THREADS + " threads resolved "
                                + observed.size() + " different Renderer instances through one "
                                + "shared PDFParser. Each surplus instance is a parse that "
                                + "rendered with a renderer another thread was still building.");
            }
        } finally {
            ex.shutdownNow();
            ex.awaitTermination(30, TimeUnit.SECONDS);
        }
    }
}
