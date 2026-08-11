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

import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/**
 * Builds SPEC-FORMED MS-OVBA VBA projects for tests: a {@code VBA} storage holding a
 * {@code dir} stream plus one module stream per module.
 *
 * <p>Every test in this package that needs "a document with macros" used to either need a real
 * binary fixture or settle for a POIFS that no real reader accepts -- which is how the VBA path
 * ended up with no test able to distinguish "extracted nothing because there was nothing" from
 * "extracted nothing because the parser lost it". Projects built here are validated by POI's own
 * {@link org.apache.poi.poifs.macros.VBAMacroReader} in
 * {@link VbaProjectBuilderTest}, so a test that finds no macros here is reporting a real defect.
 *
 * <p>Record layouts follow MS-OVBA §2.3.4.2. The dir stream is emitted as an uncompressed
 * MS-OVBA container (§2.4.1.1.4), which both POI and {@link LenientVBAReader} accept.
 */
final class VbaProjectBuilder {

    /** MS-OVBA dir-stream record ids. */
    private static final int REC_PROJECT_CODEPAGE  = 0x0003;
    private static final int REC_PROJECT_MODULES   = 0x000F;
    private static final int REC_MODULE_COUNT      = 0x0029;
    private static final int REC_MODULE_NAME       = 0x0019;
    private static final int REC_MODULE_STREAMNAME = 0x001A;
    private static final int REC_STREAMNAME_UNICODE = 0x0032; // Reserved id after the MBCS name
    private static final int REC_MODULE_OFFSET     = 0x0031;
    private static final int REC_MODULE_TERMINATOR = 0x002B;
    private static final int REC_DIR_TERMINATOR    = 0x0010;

    /** Bytes of performance-cache junk placed before each module's compressed container. */
    static final int MODULE_PREFIX_BYTES = 16;

    private final List<Module> modules = new ArrayList<>();
    private String storageName = "VBA";
    private String nestUnder;
    private boolean poiHostile;

    /**
     * Prepend a dir-stream record that POI's parser REJECTS and a lenient parser skips: a
     * REFERENCE_NAME (0x0016) whose trailing reserved short is neither 0x003E nor the
     * REFERENCE_REGISTERED id, which is the shape POI throws on.
     *
     * <p>This is the population {@link LenientVBAReader} exists for -- Mac-Word-authored projects
     * write records POI does not expect -- and without it an end-to-end test on a well-formed
     * project silently measures POI rather than the lenient reader. Mutating the lenient reader
     * left such a test green, which is how the gap was found.
     */
    VbaProjectBuilder poiHostile() {
        this.poiHostile = true;
        return this;
    }

    private static final class Module {
        String moduleName;      // MODULENAME  (0x0019) -- the name a human sees
        String streamName;      // MODULESTREAMNAME (0x001A) -- the OLE stream to read
        String entryName;       // the OLE entry actually created (may differ in case)
        byte[] container;       // compressed container for the source
    }

    /** Add a module whose stream name, dir-stream name and entry name all agree. */
    VbaProjectBuilder module(String name, String source) {
        return module(name, name, name, source);
    }

    /**
     * Add a module, controlling each name independently.
     *
     * @param moduleName what MODULENAME says (the label)
     * @param streamName what MODULESTREAMNAME says (where to look)
     * @param entryName  the OLE stream actually created (case may differ from streamName)
     */
    VbaProjectBuilder module(String moduleName, String streamName, String entryName,
                             String source) {
        Module m = new Module();
        m.moduleName = moduleName;
        m.streamName = streamName;
        m.entryName = entryName;
        m.container = compressedContainer(source.getBytes(StandardCharsets.ISO_8859_1));
        modules.add(m);
        return this;
    }

    /** Add a module whose stream holds a pre-built container (for bombs / malformed input). */
    VbaProjectBuilder rawModule(String name, byte[] container) {
        Module m = new Module();
        m.moduleName = name;
        m.streamName = name;
        m.entryName = name;
        m.container = container;
        modules.add(m);
        return this;
    }

    /**
     * {@code count} modules that all share ONE MODULENAME but live in distinct streams, with
     * bodies of IDENTICAL length that differ only in their last byte.
     *
     * <p>This is the adversarial shape for duplicate-name handling. Same-length bodies sharing a
     * long prefix are the worst case for any comparison-based duplicate check: differing lengths or
     * an early-differing byte let {@link String#equals} short-circuit, so a cost-shape test built
     * from those would pass against a quadratic implementation.
     */
    VbaProjectBuilder sameNameDistinctStreams(String moduleName, int count, int bodyLen) {
        for (int i = 0; i < count; i++) {
            StringBuilder body = new StringBuilder(bodyLen);
            for (int c = 0; c < bodyLen - 6; c++) {
                body.append('A');
            }
            // Differ only at the very end, and keep every body the same length.
            body.append(String.format("%06d", i));
            module(moduleName, "s" + i, "s" + i, body.toString());
        }
        return this;
    }

    /** The control arm for {@link #sameNameDistinctStreams}: same shape, distinct names. */
    VbaProjectBuilder distinctNamesDistinctStreams(int count, int bodyLen) {
        for (int i = 0; i < count; i++) {
            StringBuilder body = new StringBuilder(bodyLen);
            for (int c = 0; c < bodyLen - 6; c++) {
                body.append('A');
            }
            body.append(String.format("%06d", i));
            module("Module" + i, "s" + i, "s" + i, body.toString());
        }
        return this;
    }

    /**
     * {@code count} dir-stream module records that all point at ONE small stream.
     *
     * <p>The cheap shape of a module-count attack: the dir stream is a few tens of kilobytes of
     * records and the document holds a single tiny module stream, yet a reader that trusts the
     * record count does that many stream reads.
     */
    VbaProjectBuilder refsToOneStream(String moduleName, int count, String source) {
        for (int i = 0; i < count; i++) {
            Module m = new Module();
            m.moduleName = moduleName + i;
            m.streamName = "shared";
            m.entryName = null;            // only the first ref creates the stream
            m.container = compressedContainer(source.getBytes(StandardCharsets.ISO_8859_1));
            modules.add(m);
        }
        modules.get(0).entryName = "shared";
        return this;
    }

    /** Nest the VBA storage one level down, as OLE2 (.doc/.xls) documents do. */
    VbaProjectBuilder nestedUnder(String parentStorage) {
        this.nestUnder = parentStorage;
        return this;
    }

    byte[] build() throws Exception {
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            DirectoryNode parent = fs.getRoot();
            if (nestUnder != null) {
                parent = (DirectoryNode) parent.createDirectory(nestUnder);
            }
            DirectoryNode vba = (DirectoryNode) parent.createDirectory(storageName);
            vba.createDocument("dir", new ByteArrayInputStream(dirStream()));
            for (Module m : modules) {
                if (m.entryName == null) {
                    continue; // a ref that deliberately shares another module's stream
                }
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                for (int i = 0; i < MODULE_PREFIX_BYTES; i++) {
                    stream.write(0xAA); // performance cache; readers skip to MODULEOFFSET
                }
                stream.write(m.container, 0, m.container.length);
                vba.createDocument(m.entryName, new ByteArrayInputStream(stream.toByteArray()));
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            fs.writeFilesystem(bos);
            return bos.toByteArray();
        }
    }

    // ── dir stream ──────────────────────────────────────────────────────────────

    private byte[] dirStream() {
        ByteArrayOutputStream d = new ByteArrayOutputStream();
        // PROJECTCODEPAGE: size 2, codepage 1252
        record(d, REC_PROJECT_CODEPAGE, le16(1252));
        if (poiHostile) {
            // REFERENCE_NAME, then a reserved short POI does not accept. POI throws here; a lenient
            // parser reads both as unknown records and skips them.
            record(d, 0x0016, "junk".getBytes(StandardCharsets.ISO_8859_1));
            record(d, 0x0099, new byte[0]);
        }
        // PROJECTMODULES: size 2, count
        record(d, REC_PROJECT_MODULES, le16(modules.size()));
        record(d, REC_MODULE_COUNT, le16(modules.size()));
        for (Module m : modules) {
            record(d, REC_MODULE_NAME, m.moduleName.getBytes(StandardCharsets.ISO_8859_1));
            record(d, REC_MODULE_STREAMNAME, m.streamName.getBytes(StandardCharsets.ISO_8859_1));
            // Reserved 0x0032 carries the UTF-16LE stream name; both readers frame it as a record.
            record(d, REC_STREAMNAME_UNICODE, m.streamName.getBytes(StandardCharsets.UTF_16LE));
            record(d, REC_MODULE_OFFSET, le32(MODULE_PREFIX_BYTES));
            // MODULETERMINATOR (§2.3.4.2.3.2.10) and the dir Terminator (§2.3.4.2.4) carry a
            // 4-byte Reserved field where other records carry their size, so they are written
            // as id + 4 zero bytes -- not as id + size + payload. Writing them the general way
            // desynchronises POI, which reads those 4 bytes as the Reserved value.
            idWithReserved(d, REC_MODULE_TERMINATOR);
        }
        idWithReserved(d, REC_DIR_TERMINATOR);
        return uncompressedContainer(d.toByteArray());
    }

    private static void record(ByteArrayOutputStream out, int id, byte[] data) {
        out.write(id & 0xFF);
        out.write((id >> 8) & 0xFF);
        out.write(data.length & 0xFF);
        out.write((data.length >> 8) & 0xFF);
        out.write((data.length >> 16) & 0xFF);
        out.write((data.length >> 24) & 0xFF);
        out.write(data, 0, data.length);
    }

    /** A record whose 4 bytes after the id are a Reserved field, not a size. */
    private static void idWithReserved(ByteArrayOutputStream out, int id) {
        out.write(id & 0xFF);
        out.write((id >> 8) & 0xFF);
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(0);
    }

    private static byte[] le16(int v) {
        return new byte[] {(byte) (v & 0xFF), (byte) ((v >> 8) & 0xFF)};
    }

    private static byte[] le32(int v) {
        return new byte[] {(byte) (v & 0xFF), (byte) ((v >> 8) & 0xFF),
                (byte) ((v >> 16) & 0xFF), (byte) ((v >> 24) & 0xFF)};
    }

    // ── MS-OVBA §2.4.1 containers ───────────────────────────────────────────────

    /**
     * Wrap {@code payload} in uncompressed chunks. Per MS-OVBA §2.4.1.1.5 the stored size
     * field is the chunk's total length INCLUDING its 2-byte header, minus 3.
     */
    static byte[] uncompressedContainer(byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x01);
        int pos = 0;
        while (pos < payload.length) {
            int len = Math.min(4096, payload.length - pos);
            header(out, len - 1, false);
            out.write(payload, pos, len);
            pos += len;
        }
        return out.toByteArray();
    }

    /** Wrap {@code payload} in COMPRESSED chunks holding only literal tokens. */
    static byte[] compressedContainer(byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x01);
        int pos = 0;
        while (pos < payload.length) {
            // 3600 literals encode to 3600 + 450 flag bytes = 4050, under the 4095 max
            int len = Math.min(3600, payload.length - pos);
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            int written = 0;
            while (written < len) {
                int group = Math.min(8, len - written);
                data.write(0x00); // flag byte: all eight tokens are literals
                data.write(payload, pos + written, group);
                written += group;
            }
            byte[] d = data.toByteArray();
            header(out, d.length - 1, true);
            out.write(d, 0, d.length);
            pos += len;
        }
        return out.toByteArray();
    }

    /**
     * A container that decompresses to {@code chunks * ~4094} bytes from ~7 bytes per chunk --
     * MS-OVBA copy tokens back-referencing a single literal. The format's maximum ratio, and
     * the shape a decompression bomb takes.
     */
    static byte[] ratioBombContainer(int chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x01);
        for (int c = 0; c < chunks; c++) {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            data.write(0x02);                 // token 0 = literal, token 1 = copy token
            data.write('X');
            // At one byte of output the copy token is 4 offset bits + 12 length bits.
            int length = 4093;
            int token = ((1 - 1) << 12) | ((length - 3) & 0x0FFF);
            data.write(token & 0xFF);
            data.write((token >> 8) & 0xFF);
            byte[] d = data.toByteArray();
            header(out, d.length - 1, true);
            out.write(d, 0, d.length);
        }
        return out.toByteArray();
    }

    private static void header(ByteArrayOutputStream out, int sizeField, boolean compressed) {
        int h = 0x3000 | (sizeField & 0x0FFF) | (compressed ? 0x8000 : 0);
        out.write(h & 0xFF);
        out.write((h >> 8) & 0xFF);
    }
}
