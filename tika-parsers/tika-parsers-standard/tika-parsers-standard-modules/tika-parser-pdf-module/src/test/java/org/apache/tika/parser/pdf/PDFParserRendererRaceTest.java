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
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    public void concurrentParsesResolveOneStableRenderer() throws Exception {
        PDFParser parser = new PDFParser();
        int threads = 16;
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        Set<Renderer> observed = Collections.newSetFromMap(new ConcurrentHashMap<>());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(ex.submit(() -> {
                    go.await();
                    for (int n = 0; n < 200; n++) {
                        // the same resolution the parse path performs, hammered concurrently
                        observed.add(parser.resolveRendererForTest());
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : futures) {
                f.get(2, TimeUnit.MINUTES);
            }
        } finally {
            // Await termination as well as requesting shutdown: without it a mid-run failure
            // leaks non-daemon pool threads into the rest of the suite.
            ex.shutdownNow();
            ex.awaitTermination(30, TimeUnit.SECONDS);
        }
        assertEquals(1, observed.size(),
                "concurrent parses resolved " + observed.size() + " different Renderer instances "
                        + "through one shared PDFParser; each surplus instance is a parse that "
                        + "rendered with a renderer another thread was still constructing");
        assertTrue(observed.iterator().next() != null, "resolved renderer must not be null");
    }
}
