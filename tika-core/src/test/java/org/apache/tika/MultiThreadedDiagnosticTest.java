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
package org.apache.tika;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;

/**
 * The failure message is the whole point of this instrumentation -- it is how a race that only
 * reproduces on CI gets diagnosed. Verify it actually says what it claims, rather than only that
 * it compiles: shipping a diagnostic that never fires is the same failure as a test that passes
 * for the wrong reason.
 */
public class MultiThreadedDiagnosticTest {

    private static String describe(List<Metadata> truth, List<Metadata> observed)
            throws Exception {
        Class<?> extract = Class.forName("org.apache.tika.MultiThreadedTikaTest$Extract");
        java.lang.reflect.Constructor<?> ctor = extract.getDeclaredConstructor(List.class);
        ctor.setAccessible(true);
        Method m = MultiThreadedTikaTest.class
                .getDeclaredMethod("describeMissing", extract, extract);
        m.setAccessible(true);
        return (String) m.invoke(null, ctor.newInstance(truth), ctor.newInstance(observed));
    }

    private static Metadata named(String path) {
        Metadata m = new Metadata();
        m.set(TikaCoreProperties.EMBEDDED_RESOURCE_PATH, path);
        return m;
    }

    @Test
    public void aSilentLossSaysNoLimitWasTripped() throws Exception {
        List<Metadata> truth = new ArrayList<>();
        truth.add(named("/PDF.pdf"));
        truth.add(named("/TXT.txt"));
        List<Metadata> observed = new ArrayList<>();
        observed.add(named("/TXT.txt"));

        String msg = describe(truth, observed);
        assertTrue(msg.contains("/PDF.pdf"), "should name the lost attachment: " + msg);
        assertTrue(msg.contains("without any reason being reported"),
                "a silent loss must say so explicitly, or the next investigator cannot tell it "
                        + "from a limit refusal: " + msg);
    }

    @Test
    public void aLimitRefusalIsReportedAsSuch() throws Exception {
        List<Metadata> truth = new ArrayList<>();
        truth.add(named("/PDF.pdf"));
        truth.add(named("/TXT.txt"));
        Metadata refused = named("/TXT.txt");
        refused.set(TikaCoreProperties.EMBEDDED_RESOURCE_LIMIT_REACHED, true);
        List<Metadata> observed = new ArrayList<>();
        observed.add(refused);

        String msg = describe(truth, observed);
        assertTrue(msg.contains("embedded limits tripped"),
                "a limit refusal must be reported as a refusal, not as a silent loss: " + msg);
        assertTrue(!msg.contains("no embedded limit was tripped"),
                "must not also claim nothing was tripped: " + msg);
    }

    /**
     * A loss WITH a recorded exception must not also be called unexplained.
     *
     * <p>The two clauses are assembled independently, so a missing attachment whose parse threw
     * would print the exception and then contradict it -- pointing the investigation at a race
     * when the cause was right there.
     */
    @Test
    public void aLossWithARecordedExceptionIsNotCalledSilent() throws Exception {
        List<Metadata> truth = new ArrayList<>();
        truth.add(named("/PDF.pdf"));
        truth.add(named("/TXT.txt"));
        Metadata threw = named("/TXT.txt");
        threw.add(TikaCoreProperties.EMBEDDED_EXCEPTION,
                "java.io.IOException: boom\n\tat org.example.Parser.parse(Parser.java:1)");
        List<Metadata> observed = new ArrayList<>();
        observed.add(threw);

        String msg = describe(truth, observed);
        assertTrue(msg.contains("embedded exceptions recorded"),
                "the recorded exception should still be surfaced: " + msg);
        assertTrue(!msg.contains("without any reason being reported"),
                "a loss with a recorded exception must not ALSO be reported as unexplained -- "
                        + "that sends the investigation at a race instead of the exception: "
                        + msg);
    }
}
