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
package org.apache.tika.parser.indesign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import org.apache.tika.TikaTest;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Office;
import org.apache.tika.parser.Parser;

/**
 * {@link IDMLParser} accumulated its spread counts in instance fields. A parser instance is
 * shared -- DefaultParser holds one and every thread parses through it -- and JUnit's default
 * per-method lifecycle hides this from {@link IDMLParserTest}, which gets a fresh parser for
 * each test method.
 */
public class IDMLParserSharedStateTest extends TikaTest {

    private static final String DOC = "testIndesign.idml";

    private Metadata parse(Parser parser) throws Exception {
        Metadata metadata = new Metadata();
        getXML(DOC, parser, metadata);
        return metadata;
    }

    private static void assertOwnCounts(Metadata m, String which) {
        assertEquals("1", m.get("SpreadPageCount"), which + " SpreadPageCount");
        assertEquals("2", m.get("MasterSpreadPageCount"), which + " MasterSpreadPageCount");
        assertEquals("3", m.get(Office.PAGE_COUNT), which + " PAGE_COUNT");
    }

    /** The counters were {@code +=}'d and never reset, so document two reported one plus two. */
    @Test
    public void countsDoNotAccumulateAcrossDocuments() throws Exception {
        Parser parser = new IDMLParser();

        assertOwnCounts(parse(parser), "first document:");
        Metadata second = parse(parser);
        assertEquals("3", second.get(Office.PAGE_COUNT),
                "the second document reported its own pages PLUS the first document's; the "
                        + "spread counters are living on the shared parser instance");
        assertOwnCounts(second, "second document:");
    }

    /** {@code +=} on a shared int is a non-atomic read-modify-write, so counts were also lost. */
    @Test
    public void concurrentParsesEachReportTheirOwnCounts() throws Exception {
        final int rounds = 10;
        final int threads = 8;
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 1; round <= rounds; round++) {
                Parser shared = new IDMLParser();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Metadata>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    futures.add(ex.submit(() -> {
                        start.await();
                        return parse(shared);
                    }));
                }
                start.countDown();
                for (int t = 0; t < threads; t++) {
                    assertOwnCounts(futures.get(t).get(120, TimeUnit.SECONDS),
                            "round " + round + " thread " + t + ":");
                }
            }
        } finally {
            ex.shutdownNow();
            assertTrue(ex.awaitTermination(30, TimeUnit.SECONDS), "executor did not terminate");
        }
    }
}
