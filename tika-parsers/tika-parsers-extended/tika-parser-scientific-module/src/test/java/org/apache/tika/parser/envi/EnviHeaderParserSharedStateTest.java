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
package org.apache.tika.parser.envi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToXMLContentHandler;

/**
 * {@link EnviHeaderParser} held per-document state in instance fields. A parser instance is
 * shared -- DefaultParser holds one and every thread parses through it -- so that state was
 * neither per-document nor per-thread.
 */
public class EnviHeaderParserSharedStateTest {

    private static byte[] header(String marker) {
        return ("ENVI\n"
                + "description = {\n"
                + " FIRST-" + marker + "}\n"
                + "band names = {\n"
                + " SECOND-" + marker + "}\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String parse(EnviHeaderParser parser, String marker) throws Exception {
        ToXMLContentHandler handler = new ToXMLContentHandler();
        try (TikaInputStream tis = TikaInputStream.get(header(marker))) {
            parser.parse(tis, handler, new Metadata(), new ParseContext());
        }
        return handler.toString();
    }

    /**
     * The multi-line accumulator was never cleared, so the second multi-line field in a header
     * was emitted with the first field's lines prepended.
     */
    @Test
    public void aMultiLineFieldDoesNotCarryThePreviousFieldsLines() throws Exception {
        String xml = parse(new EnviHeaderParser(), "A");

        assertTrue(xml.contains("FIRST-A"), "the first multi-line field is missing: " + xml);
        assertTrue(xml.contains("SECOND-A"), "the second multi-line field is missing: " + xml);
        assertFalse(xml.contains("FIRST-A}band names"),
                "the second multi-line field was emitted with the FIRST field's lines "
                        + "prepended; the accumulator is not cleared when a field completes: "
                        + xml);
    }

    /** The same accumulator also grew across documents, for the life of the JVM. */
    @Test
    public void oneParserInstanceDoesNotCarryFieldsIntoTheNextDocument() throws Exception {
        EnviHeaderParser parser = new EnviHeaderParser();
        parse(parser, "DOC1");
        String second = parse(parser, "DOC2");

        assertFalse(second.contains("DOC1"),
                "the second document's output carries the FIRST document's fields; per-document "
                        + "state is living on the shared parser instance: " + second);
    }

    /**
     * The {@code XHTMLContentHandler} was an instance field assigned in parse(), so two
     * concurrent parses through one shared instance wrote their paragraphs into each other's
     * handler. Run many rounds: a race that reproduces a third of the time still ships.
     */
    @Test
    public void concurrentParsesDoNotWriteIntoEachOthersHandler() throws Exception {
        final int rounds = 25;
        final int threads = 8;
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 1; round <= rounds; round++) {
                EnviHeaderParser shared = new EnviHeaderParser();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<String>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    final String marker = "R" + round + "T" + t;
                    futures.add(ex.submit(() -> {
                        start.await();
                        return parse(shared, marker);
                    }));
                }
                start.countDown();
                for (int t = 0; t < threads; t++) {
                    String xml = futures.get(t).get(60, TimeUnit.SECONDS);
                    String mine = "R" + round + "T" + t;
                    assertTrue(xml.contains(mine),
                            "round " + round + " thread " + t + " lost its own content: " + xml);
                    for (int other = 0; other < threads; other++) {
                        if (other == t) {
                            continue;
                        }
                        String theirs = "R" + round + "T" + other;
                        assertFalse(xml.contains(theirs),
                                "round " + round + ": thread " + t + "'s document contains thread "
                                        + other + "'s content -- the parser's XHTML handler is a "
                                        + "shared instance field: " + xml);
                    }
                }
            }
        } finally {
            ex.shutdownNow();
            assertTrue(ex.awaitTermination(30, TimeUnit.SECONDS),
                    "executor did not terminate");
        }
    }
}
