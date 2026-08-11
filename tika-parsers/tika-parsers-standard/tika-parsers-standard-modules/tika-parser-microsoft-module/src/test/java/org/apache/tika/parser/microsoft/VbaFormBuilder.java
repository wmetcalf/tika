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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/**
 * Builds MS-OFORMS UserForm {@code f} streams that {@link VbaFormParser} actually parses.
 *
 * <p>Without this, every form test could only assert "nothing was found", which cannot
 * distinguish a traversal that never arrived from a form that held nothing -- and the form path had
 * no test at all. {@link VbaFormBuilderTest} is the positive control: it asserts the parser reads
 * back the exact control properties written here.
 *
 * <p>Only the minimum of [MS-OFORMS] 2.2.10 needed for one site per form is emitted: a
 * FormControl header with an all-zero FormPropMask, an empty class table, a SiteDepthsAndTypes
 * entry, and one OleSiteConcreteControl carrying Name, Tag and ControlTipText. clsidCacheIndex is
 * left 0 so the parser never consults the {@code o} stream.
 */
final class VbaFormBuilder {

    /** SitePropMask bits: fName, fTag, fClsidCacheIndex, fControlTipText. */
    private static final int SITE_MASK = (1 << 0) | (1 << 1) | (1 << 7) | (1 << 11);

    private final List<String[]> controls = new ArrayList<>();

    /** Add one control carrying the three payload-bearing string properties. */
    VbaFormBuilder control(String name, String tag, String controlTipText) {
        controls.add(new String[] {name, tag, controlTipText});
        return this;
    }

    /** The {@code f} (FormControl) stream bytes. */
    byte[] fStream() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x00);                       // FormControl version minor
        out.write(0x04);                       // FormControl version major
        le16(out, 4);                          // cbForm: just the FormPropMask below
        le32(out, 0);                          // FormPropMask: nothing set
        le16(out, 0);                          // class table count (DONTSAVECLASSTABLE clear)
        le32(out, controls.size());            // countOfSites
        le32(out, 0);                          // countOfBytes (unused by the parser)

        // SiteDepthsAndTypes: one (depth, type) pair per site, then padded to 4 bytes.
        ByteArrayOutputStream depths = new ByteArrayOutputStream();
        for (int i = 0; i < controls.size(); i++) {
            depths.write(0x00);                // depth
            depths.write(0x00);                // type, high bit clear => this pair counts 1 site
        }
        byte[] d = depths.toByteArray();
        out.write(d, 0, d.length);
        for (int pad = (4 - (d.length % 4)) % 4; pad > 0; pad--) {
            out.write(0x00);
        }

        for (String[] c : controls) {
            site(out, c[0], c[1], c[2]);
        }
        return out.toByteArray();
    }

    /** One OleSiteConcreteControl ([MS-OFORMS] 2.2.10.12.1). */
    private static void site(ByteArrayOutputStream out, String name, String tag, String tip) {
        byte[] n = bytes(name);
        byte[] t = bytes(tag);
        byte[] p = bytes(tip);
        le16(out, 0);                          // OleSiteConcreteControl version
        // cbSite counts from just after this field: propmask(4) + the four SiteDataBlock fields
        // (4+4+2+4 = 14) + 2 bytes of padding to the next 4-byte boundary + the string bytes.
        le16(out, 4 + 14 + 2 + n.length + t.length + p.length);
        le32(out, SITE_MASK);
        le32(out, n.length);                   // SizeOfName  (flag bit is masked off by the parser)
        le32(out, t.length);                   // SizeOfTag
        le16(out, 0);                          // ClsidCacheIndex 0 => no o-stream lookup
        le32(out, p.length);                   // SizeOfControlTipText
        out.write(0x00);                       // padding to the 4-byte boundary (14 % 4 == 2)
        out.write(0x00);
        out.write(n, 0, n.length);
        out.write(t, 0, t.length);
        out.write(p, 0, p.length);
    }

    /** A POIFS holding this form under {@code storagePath} (e.g. {@code "Macros/UserForm1"}). */
    byte[] poifs(String storagePath) throws Exception {
        return poifs(new String[] {storagePath}, 1);
    }

    /** A POIFS holding {@code copies} identical forms under each of {@code storagePaths}. */
    byte[] poifs(String[] storagePaths, int copies) throws Exception {
        byte[] f = fStream();
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            for (String storagePath : storagePaths) {
                for (int c = 0; c < copies; c++) {
                    String[] parts = (copies == 1 ? storagePath : storagePath + c).split("/");
                    DirectoryEntry dir = fs.getRoot();
                    for (String part : parts) {
                        dir = dir.hasEntry(part)
                                ? (DirectoryEntry) dir.getEntry(part)
                                : dir.createDirectory(part);
                    }
                    dir.createDocument("f", new ByteArrayInputStream(f));
                }
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            fs.writeFilesystem(bos);
            return bos.toByteArray();
        }
    }

    private static byte[] bytes(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void le16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private static void le32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }
}
