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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.Map;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.poifs.macros.VBAMacroReader;
import org.junit.jupiter.api.Test;

/**
 * POSITIVE CONTROLS for {@link VbaProjectBuilder}. Nothing else in the VBA tests means
 * anything unless these pass: they establish that a project built here is one POI's own
 * {@link VBAMacroReader} accepts and reads macros from. Without this, a "no macros found"
 * result anywhere else is indistinguishable from a bad fixture -- which is exactly how an
 * earlier VBA audit produced 0 modules for every case and concluded nothing.
 */
public class VbaProjectBuilderTest {

    private static final String SRC = "Sub AutoOpen()\n"
            + "  Shell \"powershell -enc SQBFAFgA\"\n"
            + "End Sub\n";

    @Test
    void testPoiReadsABuiltProject() throws Exception {
        byte[] poifs = new VbaProjectBuilder().module("Module1", SRC).build();
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(poifs));
             VBAMacroReader reader = new VBAMacroReader(fs)) {
            Map<String, String> macros = reader.readMacros();
            assertEquals(1, macros.size(),
                    "POI must find exactly the one module; got keys " + macros.keySet());
            assertEquals(SRC, macros.get("Module1"),
                    "POI must recover the source verbatim");
        }
    }

    @Test
    void testLenientReaderReadsABuiltProject() throws Exception {
        byte[] poifs = new VbaProjectBuilder().module("Module1", SRC).build();
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(poifs))) {
            Map<String, String> macros = LenientVBAReader.readMacros(fs,
                    new LenientVBAReader.Bounds());
            assertEquals(1, macros.size(),
                    "the lenient reader must find the module too; got " + macros.keySet());
            assertEquals(SRC, macros.get("Module1"));
        }
    }

    @Test
    void testMultiModuleAndMultiChunkSourceSurvivesBothReaders() throws Exception {
        // >3600 bytes forces more than one compressed chunk -- the shape that used to be
        // truncated to its first chunk by the lenient reader.
        String big = "' filler line to push this module past one chunk\n".repeat(200);
        byte[] poifs = new VbaProjectBuilder()
                .module("Module1", SRC)
                .module("Module2", big)
                .build();
        assertTrue(big.length() > 4096, "fixture must span multiple chunks; got " + big.length());

        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(poifs));
             VBAMacroReader reader = new VBAMacroReader(fs)) {
            Map<String, String> poi = reader.readMacros();
            assertEquals(big, poi.get("Module2"), "POI oracle: whole module");
        }
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(poifs))) {
            Map<String, String> ours = LenientVBAReader.readMacros(fs,
                    new LenientVBAReader.Bounds());
            assertEquals(2, ours.size(), "both modules; got " + ours.keySet());
            assertEquals(big, ours.get("Module2"),
                    "a module spanning several chunks must survive whole");
        }
    }

    /** OLE2 documents nest the VBA storage under Macros/_VBA_PROJECT_CUR; both readers walk it. */
    @Test
    void testNestedStorageIsFoundByBothReaders() throws Exception {
        byte[] poifs = new VbaProjectBuilder()
                .nestedUnder("Macros")
                .module("Module1", SRC)
                .build();
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(poifs));
             VBAMacroReader reader = new VBAMacroReader(fs)) {
            assertEquals(SRC, reader.readMacros().get("Module1"), "POI oracle, nested");
        }
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(poifs))) {
            assertEquals(SRC,
                    LenientVBAReader.readMacros(fs, new LenientVBAReader.Bounds()).get("Module1"),
                    "the lenient reader must walk into a nested VBA storage too");
        }
    }

    /** The ratio-bomb builder must actually amplify, else the bomb tests are vacuous. */
    @Test
    void testRatioBombContainerActuallyAmplifies() throws Exception {
        byte[] bomb = VbaProjectBuilder.ratioBombContainer(64);
        byte[] out = LenientVBAReader.decompress(bomb, 0,
                new LenientVBAReader.Bounds(64 * 1024 * 1024));
        assertTrue(out.length > 200_000,
                "64 chunks should decompress to ~262 KB; got " + out.length);
        assertTrue(out.length / (double) bomb.length > 300,
                "ratio must be extreme to be a bomb; got "
                        + (out.length / (double) bomb.length));
    }
}
