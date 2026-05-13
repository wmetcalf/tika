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
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

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
        return readMacros(fs.getRoot());
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
                if (raw == null || offset >= raw.length) {
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
                pos -= 5; // back up, advance by one byte and retry
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
                int decompressedStart = out.size();
                while (i < chunkEnd && (out.size() - decompressedStart) < 4096) {
                    int flagByte = compressed[i++] & 0xFF;
                    for (int bit = 0; bit < 8 && i < chunkEnd
                            && (out.size() - decompressedStart) < 4096; bit++) {
                        if ((flagByte & (1 << bit)) == 0) {
                            // RawToken
                            out.write(compressed[i++] & 0xFF);
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
                            // In integer arithmetic: ceil(log2(n)) = 32 - nlz(n-1) for n≥1.
                            int decompressedLen = out.size() - decompressedStart;
                            int bc = (decompressedLen <= 1) ? 4
                                    : Math.max(4,
                                            32 - Integer.numberOfLeadingZeros(decompressedLen - 1));
                            int lm = 0xFFFF >> bc;
                            int om = ~lm & 0xFFFF;
                            int length = (copyToken & lm) + 3;
                            int offset = ((copyToken & om) >>> (16 - bc)) + 1;

                            // Copy from already-decompressed output (supports overlap).
                            // readPos is relative to the full output buffer start.
                            int readStart = out.size() - offset;
                            if (readStart < 0) {
                                // Back-reference points before the buffer — skip token.
                                continue;
                            }
                            for (int j = 0; j < length
                                    && (out.size() - decompressedStart) < 4096; j++) {
                                // Re-read each time so overlapping copies work correctly.
                                byte[] current = out.toByteArray();
                                int readPos = current.length - offset;
                                if (readPos >= 0) {
                                    out.write(current[readPos] & 0xFF);
                                }
                            }
                        }
                    }
                }
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
