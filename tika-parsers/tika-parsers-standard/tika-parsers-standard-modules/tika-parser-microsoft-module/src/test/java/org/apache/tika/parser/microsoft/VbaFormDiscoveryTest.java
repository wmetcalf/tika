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
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;

/**
 * Which storages the UserForm scan actually VISITS.
 *
 * <p>{@link VbaFormParser} exists to read control properties (ControlTipText, Tag, Caption, Value)
 * that never appear in VBA source -- a standard place to hide a URL or a command. It skipped the
 * storages named {@code Macros} and {@code _VBA_PROJECT_CUR} WITHOUT recursing into them, and in an
 * OLE2 {@code .doc}/{@code .xls} the UserForm storages live exactly there
 * ({@code Macros/UserForm1/{f,o}}). So for the entire OLE2 half of the format family the scan
 * visited nothing and every hidden control property was lost.
 *
 * <p>These tests assert on DISCOVERY rather than on extracted control values on purpose: crafting
 * a valid MS-OFORMS {@code f} stream is a project of its own, and a test that asserts "no controls
 * found" cannot tell a traversal that never arrived from a form that had nothing in it.
 */
public class VbaFormDiscoveryTest {

    /** Build a POIFS from {@code paths} like {@code "Macros/UserForm1/f"}. */
    private static byte[] poifsWith(String... paths) throws Exception {
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            for (String path : paths) {
                String[] parts = path.split("/");
                DirectoryEntry dir = fs.getRoot();
                for (int i = 0; i < parts.length - 1; i++) {
                    dir = dir.hasEntry(parts[i])
                            ? (DirectoryEntry) dir.getEntry(parts[i])
                            : dir.createDirectory(parts[i]);
                }
                dir.createDocument(parts[parts.length - 1],
                        new ByteArrayInputStream(new byte[] {0, 4, 4, 0, 0, 0, 0, 0}));
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            fs.writeFilesystem(bos);
            return bos.toByteArray();
        }
    }

    private static List<String> discover(byte[] poifs) throws Exception {
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(poifs))) {
            List<String> names = new ArrayList<>();
            for (DirectoryEntry d : VbaFormParser.findFormDirs(fs)) {
                names.add(d.getName());
            }
            return names;
        }
    }

    /** The OOXML shape, which already worked: forms at the top level of vbaProject.bin. */
    @Test
    void testTopLevelFormIsFound() throws Exception {
        assertEquals(List.of("UserForm1"), discover(poifsWith("UserForm1/f", "UserForm1/o")));
    }

    /** The OLE2 shape, which found nothing at all. */
    @Test
    void testFormNestedUnderMacrosIsFound() throws Exception {
        List<String> found = discover(poifsWith("Macros/UserForm1/f", "Macros/UserForm1/o",
                "Macros/VBA/dir", "Macros/VBA/Module1"));
        assertTrue(found.contains("UserForm1"),
                "a UserForm under Macros/ must be discovered -- that is where every OLE2 "
                        + "document puts it; found " + found);
        assertFalse(found.contains("Macros"),
                "Macros is a container, not a form storage; it must not be parsed as one");
        assertFalse(found.contains("VBA"),
                "the VBA storage holds module source, not forms");
    }

    /** _VBA_PROJECT_CUR nests one level deeper in some documents; it is also a container. */
    @Test
    void testFormNestedUnderVbaProjectCurIsFound() throws Exception {
        List<String> found = discover(poifsWith(
                "Macros/_VBA_PROJECT_CUR/UserForm2/f",
                "Macros/_VBA_PROJECT_CUR/VBA/dir"));
        assertTrue(found.contains("UserForm2"),
                "a UserForm under _VBA_PROJECT_CUR must be discovered; found " + found);
        assertFalse(found.contains("_VBA_PROJECT_CUR"),
                "_VBA_PROJECT_CUR is a container, not a form storage");
    }

    /** Several forms in one document must all be found, not just the first. */
    @Test
    void testEveryFormIsFound() throws Exception {
        List<String> found = discover(poifsWith("Macros/UserForm1/f", "Macros/UserForm2/f",
                "UserForm3/f"));
        assertTrue(found.containsAll(List.of("UserForm1", "UserForm2", "UserForm3")),
                "all three forms must be discovered; found " + found);
    }

    /** A 0x01-prefixed storage is a compiled artefact, never a UserForm. */
    @Test
    void testCompiledStoragesAreSkipped() throws Exception {
        List<String> found = discover(poifsWith("\u0001CompObj/f", "Macros/UserForm1/f"));
        assertEquals(List.of("UserForm1"), found,
                "only the real form may be reported; found " + found);
    }
}
