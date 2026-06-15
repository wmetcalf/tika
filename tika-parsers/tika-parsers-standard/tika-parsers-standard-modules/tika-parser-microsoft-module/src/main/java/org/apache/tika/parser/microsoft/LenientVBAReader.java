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

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.POIFSDocument;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.poifs.property.DocumentProperty;
import org.apache.poi.poifs.property.Property;

/**
 * Lenient VBA source extractor for VBA projects that trip POI's strict reserved-field
 * checks (most commonly Mac-Word–authored .docm files where the dir stream contains
 * record IDs that POI doesn't expect but olevba handles fine).
 *
 * <p>Implements MS-OVBA §2.4.1 decompression and a tolerant §2.3.4.2 dir-stream
 * parser that ignores unknown / unexpected record IDs rather than throwing.
 */
public final class LenientVBAReader {

    // MS-OVBA dir-stream record IDs we care about
    private static final int REC_MODULENAME        = 0x0019;
    private static final int REC_MODULEOFFSET      = 0x0031;
    private static final int REC_MODULETERM        = 0x002B;
    private static final int REC_PROJECTCODEPAGE   = 0x0003;
    private static final int REC_MODULES           = 0x000F; // start of modules section
    private static final int REC_MODULECOUNT       = 0x0029;

    private static final int MAX_STREAM_BYTES = 10 * 1024 * 1024; // 10 MB guard

    private LenientVBAReader() {}

    /**
     * Extract VBA module source code from a vbaProject.bin POIFS.
     *
     * @return map of module-name → source text; empty if nothing could be extracted
     */
    public static Map<String, String> readMacros(POIFSFileSystem fs) throws IOException {
        Map<String, String> viaTree = readMacros(fs.getRoot());
        if (!viaTree.isEmpty()) {
            return viaTree;
        }
        // Tree-walk found nothing. The VBA storage may be ORPHANED — its OLE/CFBF directory
        // entry deliberately unlinked from the directory tree (a malware anti-analysis trick)
        // so POI's tree-walking readers (incl. findVBADir above) can't see it, even though
        // Excel and olevba still run the macro by enumerating ALL raw directory entries.
        // Recover the same way: scan every property, then reuse the dir/module decompression.
        return readMacrosFromOrphans(fs);
    }

    /**
     * Recover VBA source from a project whose directory-tree linkage was corrupted so the
     * {@code VBA} storage + {@code dir}/module streams are orphaned (present in the raw
     * property table but unreachable by tree traversal). Enumerates ALL document properties,
     * locates the orphaned {@code dir} stream, and reuses {@link #parseDir}/{@link #decompress}
     * to pull each module's source. Best-effort: any failure yields an empty map.
     */
    static Map<String, String> readMacrosFromOrphans(POIFSFileSystem fs) {
        Map<String, String> result = new LinkedHashMap<>();
        Map<String, DocumentProperty> streams = collectAllStreamProps(fs);
        DocumentProperty dirProp = streams.get("dir"); // map is lower-cased; "dir" is already lc
        if (dirProp == null) {
            return result;
        }
        try {
            byte[] dirRaw = readPropBytes(fs, dirProp);
            if (dirRaw == null) {
                return result;
            }
            Map<String, Integer> moduleOffsets = parseDir(decompress(dirRaw));
            Charset charset = Charset.forName("windows-1252");
            for (Map.Entry<String, Integer> e : moduleOffsets.entrySet()) {
                // Case-insensitive lookup (see collectAllStreamProps); keep the dir-stream's
                // original-case module name as the result key.
                DocumentProperty modProp = streams.get(e.getKey().toLowerCase(Locale.ROOT));
                if (modProp == null) {
                    continue;
                }
                try {
                    byte[] raw = readPropBytes(fs, modProp);
                    int offset = e.getValue();
                    if (raw == null || offset < 3 || offset >= raw.length) {
                        continue;
                    }
                    String text = new String(decompress(raw, offset), charset);
                    if (!text.isBlank()) {
                        result.put(e.getKey(), text);
                    }
                } catch (Exception ignore) {
                    // skip individual module failures
                }
            }
        } catch (Exception ignore) {
            // dir stream unreadable / not actually a VBA dir
        }
        return result;
    }

    /**
     * Enumerate ALL document (stream) properties from the raw property table — including ones
     * the directory tree doesn't link (orphans). POI exposes only the tree-reachable entries
     * publicly, so reach the full {@code PropertyTable._properties} list via reflection; any
     * POI-internal drift just yields an empty map and disables orphan recovery (no worse than
     * the prior tree-only behaviour). Keyed by stream name; first occurrence wins (VBA stream
     * names — {@code dir} and the module names — are unique within a project).
     */
    private static Map<String, DocumentProperty> collectAllStreamProps(POIFSFileSystem fs) {
        Map<String, DocumentProperty> out = new LinkedHashMap<>();
        try {
            Field ptField = POIFSFileSystem.class.getDeclaredField("_property_table");
            ptField.setAccessible(true);
            Object pt = ptField.get(fs);
            Field propsField = pt.getClass().getDeclaredField("_properties");
            propsField.setAccessible(true);
            Object raw = propsField.get(pt);
            if (!(raw instanceof List<?>)) {
                return out;
            }
            for (Object o : (List<?>) raw) {
                if (!(o instanceof Property)) {
                    continue;
                }
                Property p = (Property) o;
                if (p.isDirectory()) {
                    continue;
                }
                String name = p.getName();
                if (name == null || name.isEmpty() || !(p instanceof DocumentProperty)) {
                    continue;
                }
                // Key case-insensitively: OLE stream names are case-insensitive in MS Office,
                // and malware case-mismatches the dir-stream module name vs the real entry name
                // to evade case-sensitive readers (olevba matches case-insensitively too).
                out.putIfAbsent(name.toLowerCase(Locale.ROOT), (DocumentProperty) p);
            }
        } catch (Throwable t) {
            // POI internals not accessible (version drift / module restriction) — recovery off.
        }
        return out;
    }

    /** Read a stream's bytes directly from its property (start block + size), bypassing the
     *  directory tree, so an orphaned stream is still readable. */
    private static byte[] readPropBytes(POIFSFileSystem fs, DocumentProperty prop) {
        try {
            int size = prop.getSize();
            if (size < 0 || size > MAX_STREAM_BYTES) {
                return null;
            }
            POIFSDocument doc = new POIFSDocument(prop, fs);
            byte[] data = new byte[size];
            try (DocumentInputStream dis = new DocumentInputStream(doc)) {
                int read = 0;
                while (read < data.length) {
                    int n = dis.read(data, read, data.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    public static Map<String, String> readMacros(DirectoryNode root) throws IOException {
        // Locate the VBA storage
        DirectoryNode vbaDir = findVBADir(root);
        if (vbaDir == null) {
            return new LinkedHashMap<>();
        }

        // Decompress the dir stream
        byte[] dirRaw = readStream(vbaDir, "dir");
        if (dirRaw == null) {
            return new LinkedHashMap<>();
        }
        byte[] dirDecompressed = decompress(dirRaw);

        // Parse module names + offsets from dir stream
        Map<String, Integer> moduleOffsets = parseDir(dirDecompressed);

        // Decompress each module stream and return source text
        Map<String, String> result = new LinkedHashMap<>();
        Charset charset = Charset.forName("windows-1252");
        for (Map.Entry<String, Integer> entry : moduleOffsets.entrySet()) {
            String name = entry.getKey();
            int offset = entry.getValue();
            try {
                byte[] raw = readStream(vbaDir, name);
                if (raw == null || offset < 3 || offset >= raw.length) {
                    continue;
                }
                byte[] src = decompress(raw, offset);
                String text = new String(src, charset);
                if (!text.isBlank()) {
                    result.put(name, text);
                }
            } catch (Exception ignore) {
                // skip individual module failures — don't abort the whole extraction
            }
        }
        return result;
    }

    // ── dir stream parser ─────────────────────────────────────────────────────

    private static Map<String, Integer> parseDir(byte[] dir) {
        Map<String, Integer> modules = new LinkedHashMap<>();

        String currentName = null;
        int pos = 0;

        while (pos + 6 <= dir.length) {
            int recId  = ((dir[pos] & 0xFF)) | ((dir[pos + 1] & 0xFF) << 8);
            int recLen = ((dir[pos + 2] & 0xFF)) | ((dir[pos + 3] & 0xFF) << 8)
                       | ((dir[pos + 4] & 0xFF) << 16) | ((dir[pos + 5] & 0xFF) << 24);
            pos += 6;

            if (recLen < 0 || recLen > dir.length - pos) {
                // Oversized / invalid record — Mac-Word writes non-standard reference
                // entries here.  Skip byte-by-byte until we find a plausible record.
                pos -= 4; // back up past the 2-byte record ID, retry from next byte
                continue;
            }

            byte[] recData = new byte[recLen];
            System.arraycopy(dir, pos, recData, 0, recLen);
            pos += recLen;

            try {
                switch (recId) {
                    case REC_MODULENAME:
                        currentName = new String(recData, "windows-1252");
                        break;
                    case REC_MODULEOFFSET:
                        if (currentName != null && recLen == 4) {
                            int offset = (recData[0] & 0xFF) | ((recData[1] & 0xFF) << 8)
                                       | ((recData[2] & 0xFF) << 16) | ((recData[3] & 0xFF) << 24);
                            modules.put(currentName, offset);
                        }
                        break;
                    case REC_MODULETERM:
                        currentName = null;
                        break;
                    default:
                        // skip unknown / reserved records leniently
                        break;
                }
            } catch (Exception ignore) {
                // per-record failure → continue
            }
        }
        return modules;
    }

    // ── MS-OVBA §2.4.1 decompressor ──────────────────────────────────────────

    /** Decompress from the beginning of {@code compressed}. */
    public static byte[] decompress(byte[] compressed) throws IOException {
        return decompress(compressed, 0);
    }

    /**
     * Decompress starting at {@code startOffset} within {@code compressed}.
     * The byte at {@code startOffset} must be {@code 0x01} (signature).
     */
    static byte[] decompress(byte[] compressed, int startOffset) throws IOException {
        if (compressed == null || compressed.length <= startOffset) {
            return new byte[0];
        }
        if ((compressed[startOffset] & 0xFF) != 0x01) {
            // Not compressed — return raw bytes from offset
            byte[] raw = new byte[compressed.length - startOffset];
            System.arraycopy(compressed, startOffset, raw, 0, raw.length);
            return raw;
        }

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int i = startOffset + 1; // skip signature byte

        while (i < compressed.length) {
            // CompressedChunkHeader: 2 bytes LE
            if (i + 2 > compressed.length) {
                break;
            }
            int chunkHeader = ((compressed[i] & 0xFF)) | ((compressed[i + 1] & 0xFF) << 8);
            i += 2;

            boolean isCompressed = (chunkHeader & 0x8000) != 0;
            int signature = (chunkHeader >> 12) & 0x07;
            int chunkSize = (chunkHeader & 0x0FFF) + 3;

            if (signature != 0b011) {
                // Unexpected signature — skip this chunk safely
                i += Math.min(chunkSize, compressed.length - i);
                continue;
            }

            int chunkEnd = Math.min(i + chunkSize, compressed.length);

            if (!isCompressed) {
                // Raw chunk: exactly 4096 bytes
                int rawLen = Math.min(4096, compressed.length - i);
                out.write(compressed, i, rawLen);
                i += rawLen;
            } else {
                // Compressed chunk: process flag groups.
                // Each compressed chunk decompresses to at most 4096 bytes (MS-OVBA §2.4.1.3.2).
                // Use a fixed-size local buffer to avoid O(n²) toByteArray() calls.
                byte[] chunkBuf = new byte[4096];
                int chunkOut = 0;

                while (i < chunkEnd && chunkOut < 4096) {
                    int flagByte = compressed[i++] & 0xFF;
                    for (int bit = 0; bit < 8 && i < chunkEnd && chunkOut < 4096; bit++) {
                        if ((flagByte & (1 << bit)) == 0) {
                            // RawToken
                            chunkBuf[chunkOut++] = compressed[i++];
                        } else {
                            // CopyToken
                            if (i + 2 > chunkEnd) {
                                break;
                            }
                            int copyToken = ((compressed[i] & 0xFF)) |
                                            ((compressed[i + 1] & 0xFF) << 8);
                            i += 2;

                            // Compute copy-token bit count per MS-OVBA §2.4.1.3.19.1:
                            //   bit_count = max(4, ceil(log2(decompressedChunkSize)))
                            // In integer arithmetic: ceil(log2(n)) = 32 - nlz(n-1) for n>=1.
                            int bc = (chunkOut <= 1) ? 4
                                    : Math.max(4,
                                            32 - Integer.numberOfLeadingZeros(chunkOut - 1));
                            int lm = 0xFFFF >> bc;
                            int om = ~lm & 0xFFFF;
                            int length = (copyToken & lm) + 3;
                            int offset = ((copyToken & om) >>> (16 - bc)) + 1;

                            // Copy from already-decompressed chunk buffer (supports overlap).
                            int readStart = chunkOut - offset;
                            if (readStart < 0) {
                                // Back-reference before chunk start — skip token.
                                continue;
                            }
                            for (int j = 0; j < length && chunkOut < 4096; j++) {
                                chunkBuf[chunkOut] = chunkBuf[readStart + j];
                                chunkOut++;
                            }
                        }
                    }
                }
                out.write(chunkBuf, 0, chunkOut);
                i = chunkEnd; // ensure we move past the chunk
            }

            if (out.size() > MAX_STREAM_BYTES) {
                break; // guard against malformed data
            }
        }
        return out.toByteArray();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static DirectoryNode findVBADir(DirectoryNode root) {
        try {
            return (DirectoryNode) root.getEntry("VBA");
        } catch (Exception ignore) {
            // not at root level — fall through to nested search
        }
        // OOXML vbaProject.bin has VBA at root level; OLE2 has it nested
        for (org.apache.poi.poifs.filesystem.Entry e : root) {
            if (e instanceof DirectoryNode) {
                DirectoryNode sub = (DirectoryNode) e;
                try {
                    return (DirectoryNode) sub.getEntry("VBA");
                } catch (Exception ignore) {
                    // not in this subdirectory
                }
            }
        }
        return null;
    }

    private static byte[] readStream(DirectoryNode dir, String name) {
        try {
            DocumentEntry de = (DocumentEntry) dir.getEntry(name);
            if (de.getSize() > MAX_STREAM_BYTES) {
                return null;
            }
            byte[] data = new byte[de.getSize()];
            try (DocumentInputStream dis = new DocumentInputStream(de)) {
                int read = 0;
                while (read < data.length) {
                    int n = dis.read(data, read, data.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }
}
