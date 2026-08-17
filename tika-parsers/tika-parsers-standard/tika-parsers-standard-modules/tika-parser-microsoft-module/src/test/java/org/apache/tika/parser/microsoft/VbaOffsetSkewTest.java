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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.Map;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;

/**
 * A MODULEOFFSET that lands INSIDE the compressed container must not be read as source.
 *
 * <p>{@code decompress} requires 0x01 at the declared offset and otherwise returns the bytes from
 * that offset verbatim, on the theory that the stream is stored uncompressed. When the offset points
 * a few bytes INTO a compressed container -- past the signature and the 2-byte chunk header -- that
 * fallback hands back COMPRESSED bytes as macro source. The result is not obviously broken, which is
 * why it survived: MS-OVBA emits a FlagByte before every 8 tokens, and a mostly-literal chunk
 * therefore reads as the real source text with one junk byte inserted every 8 characters.
 *
 * <p>Readable enough to pass review, broken enough to defeat every consumer that matches a pattern:
 * a URL, a Shell line, a base64 blob all acquire a stray byte partway through, so the IOC scan finds
 * nothing. Measured on the 6,574-document corpus: 102 documents (1.6%) whose recovered module bodies
 * declare no {@code Attribute VB_Name} at all, which for a genuine module body is impossible.
 *
 * <p>Discovered by probing the corpus for bodies whose declared name disagrees with their content,
 * not by the review panel.
 */
public class VbaOffsetSkewTest {

    private static final String SRC = "Attribute VB_Name = \"Module1\"\n"
            + "Option Explicit\n"
            + "Sub AutoOpen()\n"
            + "  Shell \"powershell -w hidden -enc SQBFAFgA\"\n"
            + "  Dim u As String\n"
            + "  u = \"http://evil.example/payload.exe\"\n"
            + "End Sub\n";

    /** The skew a real container has: 0x01 + a 2-byte chunk header sit before the data. */
    private static final int HEADER_SKEW = 3;

    @Test
    void testOffsetInsideTheContainerStillYieldsCleanSource() throws Exception {
        byte[] project = new VbaProjectBuilder()
                .offsetSkew(HEADER_SKEW)
                .module("Module1", SRC)
                .build();

        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
            Map<String, String> macros =
                    LenientVBAReader.readMacros(fs, new LenientVBAReader.Bounds());
            assertEquals(1, macros.size(), "the module must be found; got " + macros.keySet());
            String body = macros.values().iterator().next();

            // The structural test, and the one a genuine module body always passes.
            assertTrue(body.contains("Attribute VB_Name"),
                    "a module body must declare its own name; a body that does not is compressed "
                            + "bytes being read as text. Got: " + preview(body));
            // The consumer-visible half: an IOC must survive INTACT, not with a FlagByte in it.
            assertTrue(body.contains("http://evil.example/payload.exe"),
                    "the URL must survive unbroken -- one stray byte inside it is the difference "
                            + "between an indicator found and an indicator missed. Got: "
                            + preview(body));
            assertTrue(body.contains("powershell -w hidden -enc SQBFAFgA"),
                    "and so must the Shell line. Got: " + preview(body));
            // No FlagByte contamination: real source of this shape is fully printable.
            assertTrue(printableRatio(body) > 0.99,
                    "clean source is printable; got ratio " + printableRatio(body) + " for "
                            + preview(body));
        }
    }

    /**
     * NEGATIVE CONTROL: a stream that really is stored uncompressed must still be returned verbatim.
     * The fix must not turn the raw fallback into a search that mangles genuinely-plain streams.
     */
    @Test
    void testGenuinelyUncompressedStreamIsStillReturnedVerbatim() throws Exception {
        byte[] plain = ("Attribute VB_Name = \"Plain\"\nSub P()\n  MsgBox 1\nEnd Sub\n")
                .repeat(20).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] project = new VbaProjectBuilder().rawModule("Module1", plain).build();
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
            Map<String, String> macros =
                    LenientVBAReader.readMacros(fs, new LenientVBAReader.Bounds());
            assertEquals(1, macros.size());
            String body = macros.values().iterator().next();
            assertTrue(body.startsWith("Attribute VB_Name = \"Plain\""),
                    "an uncompressed stream must come back verbatim; got " + preview(body));
            assertEquals(20, count(body, "End Sub"),
                    "every repetition must survive: " + preview(body));
        }
    }

    /**
     * NEGATIVE CONTROL: an offset pointing at genuine junk with no container anywhere must not be
     * "recovered" into something invented. Relocation is only allowed to accept output that proves
     * itself, so a stream with no container must behave exactly as before.
     */
    @Test
    void testOffsetOnJunkWithNoContainerIsUnchanged() throws Exception {
        byte[] junk = new byte[4096];
        for (int i = 0; i < junk.length; i++) {
            junk[i] = (byte) (0xAA ^ (i * 7));
        }
        byte[] project = new VbaProjectBuilder().rawModule("Module1", junk).build();
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
            Map<String, String> macros =
                    LenientVBAReader.readMacros(fs, new LenientVBAReader.Bounds());
            for (String body : macros.values()) {
                assertFalse(body.contains("Attribute VB_Name"),
                        "nothing may be conjured from junk; got " + preview(body));
            }
        }
    }

    /**
     * A container belonging to a DIFFERENT module must never be substituted.
     *
     * <p>This is the failure the corpus found when relocation accepted any body declaring some
     * module name. A module stream can hold another module's container -- so the search found clean,
     * genuine-looking source and swapped it in for the body being resolved. It reads perfectly and
     * it is the wrong module: three corpus documents lost an exec indicator (3 to 2, 3 to 2, 9 to 8),
     * four gained a truncation flag with nothing withheld, and one fell from 16,115 characters to
     * 3,483. Substituting a clean wrong answer for a garbled right one is a worse outcome than the
     * defect being fixed, so the gate requires the body to declare the name of the module being
     * resolved.
     */
    @Test
    void testAnotherModulesContainerIsNotSubstituted() throws Exception {
        // Module1's stream: the declared offset lands on filler, and further along sits a perfectly
        // valid container for a DIFFERENT module. Relocation must refuse it.
        byte[] intruder = VbaProjectBuilder.compressedContainer(
                ("Attribute VB_Name = \"SomeoneElse\"\nSub Other()\n  MsgBox \"not mine\"\nEnd Sub\n")
                        .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < 40; i++) {
            body.write(0xAA); // the declared offset lands here
        }
        body.write(intruder, 0, intruder.length);
        byte[] project = new VbaProjectBuilder().rawModule("Module1", body.toByteArray()).build();

        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
            Map<String, String> macros =
                    LenientVBAReader.readMacros(fs, new LenientVBAReader.Bounds());
            for (Map.Entry<String, String> e : macros.entrySet()) {
                // Assert on the UNBROKEN declaration, not on the words. The raw fallback returns the
                // intruder container's own compressed bytes, so fragments of its text ("not mine",
                // "Som.eoneElse") DO appear there with FlagBytes interleaved -- an assertion on the
                // words fails whether relocation refused or accepted, which is a test that cannot
                // tell the two apart. Only a wrongly-ACCEPTED relocation yields the clean form.
                assertFalse(e.getValue().contains("Attribute VB_Name = \"SomeoneElse\""),
                        "module " + e.getKey() + " was given another module's source, cleanly "
                                + "decompressed: " + preview(e.getValue()));
            }
        }
    }

    /**
     * The search WINDOW is the safety bound, so pin it: a container further away than the window
     * must not be used even when it declares the right name.
     *
     * <p>This replaces a timing gate. That gate bounded a whole-stream search by a candidate count,
     * and once the search itself was narrowed to 8 bytes the timing could not vary with the number
     * of fake container starts -- at most 16 positions are ever examined. A timing assertion whose
     * dimension is capped by construction is green for a reason other than the property it names,
     * which is the defect class this file has already been cleaned of twice.
     *
     * <p>What replaced it asserts the bound directly, and in the direction that matters: the wide
     * search is what cost three documents an exec indicator, so "far away is refused" IS the fix.
     */
    @Test
    void testContainerBeyondTheWindowIsNotUsed() throws Exception {
        // A valid container for THIS module, correctly named, but 200 bytes past the declared
        // offset -- far outside the window. It must be refused: at that distance it is
        // indistinguishable from the stale duplicate copies that cost real indicators on the corpus.
        byte[] far = VbaProjectBuilder.compressedContainer(
                ("Attribute VB_Name = \"Module1\"\nSub Far()\n  MsgBox \"too far\"\nEnd Sub\n")
                        .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < 200; i++) {
            body.write(0xAA); // the declared offset lands here; nothing within 8 bytes
        }
        body.write(far, 0, far.length);
        byte[] project = new VbaProjectBuilder().rawModule("Module1", body.toByteArray()).build();

        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(project))) {
            Map<String, String> macros =
                    LenientVBAReader.readMacros(fs, new LenientVBAReader.Bounds());
            for (String v : macros.values()) {
                assertFalse(v.contains("Attribute VB_Name = \"Module1\"\nSub Far()"),
                        "a container beyond the window must not be adopted: " + preview(v));
            }
        }
    }

    private static double printableRatio(String s) {
        int printable = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || (c >= 0x20 && c < 0x7F)) {
                printable++;
            }
        }
        return s.isEmpty() ? 0 : (double) printable / s.length();
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }

    private static String preview(String s) {
        String head = s.length() > 120 ? s.substring(0, 120) : s;
        return head.replaceAll("[^\\p{Print}]", ".");
    }
}
