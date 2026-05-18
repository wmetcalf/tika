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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.config.TikaComponent;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.EmbeddedDocumentUtil;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.XHTMLContentHandler;

/**
 * Parser for Windows Provisioning Package (.ppkg) files.
 *
 * <p>PPKG files are WIM (Windows Imaging Format) containers using XPRESS Huffman
 * compression (MS-XCA §2.3). This parser implements a minimal pure-Java WIM reader
 * sufficient to extract PackageConfig metadata and ProvisioningCommands from the
 * XML/PROVXML files inside, then feeds all embedded files into Tika's embedded
 * document pipeline.</p>
 *
 * <p>The WIM container format is documented in the Microsoft Open Specifications
 * (MS-WIM). The XPRESS Huffman algorithm is specified in MS-XCA §2.3 and is
 * implemented here from scratch using only the open specification — no LGPL code
 * was ported or copied. The wmetcalf/ppkg_happiness tool (MIT) was used as a
 * field-name and extraction-logic reference.</p>
 *
 * <p>Only XPRESS compression is implemented (type 0x00020000 in WIM header flags),
 * which covers all real-world PPKG files. LZX and LZMS are not supported.</p>
 */
@TikaComponent
public class PpkgParser implements Parser {

    private static final long serialVersionUID = 1L;

    private static final MediaType PPKG_TYPE =
            MediaType.application("vnd.ms-windows.provisioningpackage");
    private static final Set<MediaType> SUPPORTED_TYPES =
            Collections.singleton(PPKG_TYPE);

    // WIM magic: "MSWIM\0\0\0"
    static final byte[] WIM_MAGIC =
            new byte[]{0x4d, 0x53, 0x57, 0x49, 0x4d, 0x00, 0x00, 0x00};

    // WIM header flag: XPRESS compression
    private static final int WIM_HDR_FLAG_COMPRESS_XPRESS = 0x00020000;
    // WIM resource flag: compressed
    private static final int RESHDR_FLAG_COMPRESSED = 0x02;

    // Extensions considered high-risk when referenced by provisioning commands
    private static final Set<String> DANGEROUS_EXTENSIONS = new HashSet<>(Arrays.asList(
            ".exe", ".dll", ".ocx", ".cpl", ".scr", ".msi", ".msp", ".bat", ".cmd",
            ".ps1", ".vbs", ".js", ".hta", ".wsf", ".py", ".jar", ".cab", ".iso",
            ".vhd", ".vhdx", ".lnk", ".msc", ".hta", ".pif", ".reg"
    ));

    // Shell invocation prefixes that indicate code execution
    private static final String[] EXEC_KEYWORDS = {
            "powershell", "cmd.exe", "cmd /c", "wscript", "cscript", "mshta",
            "certutil", "bitsadmin", "regsvr32", "rundll32", "msiexec", "wmic"
    };

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED_TYPES;
    }

    @Override
    public void parse(TikaInputStream stream, ContentHandler handler,
                      Metadata metadata, ParseContext context)
            throws IOException, SAXException, TikaException {

        // Cap input — PPKG/WIM files are usually <50 MB; 256 MB is a hard ceiling
        // that prevents heap exhaustion from a crafted oversize package.
        byte[] raw = stream.readNBytes(256 * 1024 * 1024);
        if (raw.length < 208) {
            throw new TikaException("File too small to be a WIM/PPKG");
        }
        if (raw.length == 256 * 1024 * 1024 && stream.read() != -1) {
            metadata.add("ppkg:warning",
                    "Input truncated at 256 MB — any content beyond was not parsed");
        }
        for (int i = 0; i < WIM_MAGIC.length; i++) {
            if (raw[i] != WIM_MAGIC[i]) {
                throw new TikaException("Not a WIM file (bad magic)");
            }
        }

        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int wimFlags = buf.getInt(16);
        boolean xpress = (wimFlags & WIM_HDR_FLAG_COMPRESS_XPRESS) != 0;
        if (!xpress) {
            throw new TikaException("WIM uses unsupported compression (not XPRESS)");
        }
        int chunkSize = buf.getInt(20);
        if (chunkSize <= 0) {
            chunkSize = 32768;
        }
        int imageCount = buf.getInt(44);

        // Resource header descriptors are at fixed offsets in the WIM header
        ResHdr lookupHdr = readResHdr(buf, 48);
        ResHdr xmlHdr    = readResHdr(buf, 72);

        // WIM XML descriptor (uncompressed UTF-16LE) — package-level metadata
        String wimXml = "";
        if (xmlHdr.size > 0 && xmlHdr.size < 4 * 1024 * 1024) {
            byte[] xmlBytes = Arrays.copyOfRange(raw, (int) xmlHdr.offset,
                    (int) (xmlHdr.offset + xmlHdr.size));
            // UTF-16LE with optional BOM
            int bom = (xmlBytes.length >= 2
                    && (xmlBytes[0] & 0xff) == 0xff
                    && (xmlBytes[1] & 0xff) == 0xfe) ? 2 : 0;
            wimXml = new String(xmlBytes, bom, xmlBytes.length - bom,
                    StandardCharsets.UTF_16LE);
            metadata.set("wim:descriptor_xml", wimXml);
        }
        metadata.set("wim:image_count", Integer.toString(imageCount));

        // Parse lookup table (always uncompressed)
        Map<String, ResHdr> lookupTable = parseLookupTable(raw, lookupHdr);

        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata);
        xhtml.startDocument();

        List<String> commands   = new ArrayList<>();
        List<String> warnings   = new ArrayList<>();
        List<String> allDataRefs = new ArrayList<>();
        Map<String, String> pkgMeta = new LinkedHashMap<>();

        // Identify metadata resources: the METADATA flag (0x04) marks directory trees.
        // Either compressed (0x06) or uncompressed (0x04) metadata resources are valid.
        for (ResHdr rh : lookupTable.values()) {
            if ((rh.flags & 0x04) == 0) {
                continue;
            }
            byte[] metaRes = decompressResource(raw, rh, chunkSize);
            if (metaRes == null || metaRes.length < 8) {
                continue;
            }
            // Security descriptor set precedes the root dentry.
            // u32 total_length at offset 0 gives its byte length; round up to 8.
            long sdLen = ((ByteBuffer.wrap(metaRes).order(ByteOrder.LITTLE_ENDIAN)
                    .getInt(0)) & 0xFFFFFFFFL);
            long rootOff = (sdLen + 7L) & ~7L;
            // Root dentry has subdir_offset pointing at its children
            ByteBuffer mbuf = ByteBuffer.wrap(metaRes).order(ByteOrder.LITTLE_ENDIAN);
            if (rootOff + 24 > metaRes.length) {
                continue;
            }
            long childOff = mbuf.getLong((int) rootOff + 16);
            if (childOff > 0 && childOff < metaRes.length) {
                walkDirectory(metaRes, (int) childOff, raw, lookupTable, chunkSize,
                        commands, warnings, allDataRefs, pkgMeta, xhtml, context,
                        metadata);
            }
        }

        // Surface structured metadata
        for (Map.Entry<String, String> e : pkgMeta.entrySet()) {
            xhtml.element("p", e.getKey() + ": " + e.getValue());
            metadata.add("ppkg:" + e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }

        // Deduplicate and emit commands
        Set<String> seen = new LinkedHashSet<>();
        for (String cmd : commands) {
            seen.add(cmd);
        }
        for (String cmd : seen) {
            xhtml.element("p", "CommandLine: " + cmd);
            metadata.add("ppkg:command", cmd);
        }

        // ExploitClass for dangerous command patterns
        for (String cmd : seen) {
            String lower = cmd.toLowerCase(Locale.ROOT);
            for (String kw : EXEC_KEYWORDS) {
                if (lower.contains(kw)) {
                    metadata.set("ExploitClass",
                            "PPKG provisioning command executes shell payload: " + cmd);
                    break;
                }
            }
        }

        for (String ref : allDataRefs) {
            metadata.add("ppkg:data_asset_ref", ref);
        }
        for (String w : warnings) {
            metadata.add("ppkg:warning", w);
        }

        xhtml.endDocument();
    }

    // ── WIM container parsing ─────────────────────────────────────────────────

    private static ResHdr readResHdr(ByteBuffer buf, int off) {
        ResHdr h = new ResHdr();
        // 7-byte LE size
        h.size = 0;
        for (int i = 0; i < 7; i++) {
            h.size |= ((long) (buf.get(off + i) & 0xff)) << (8 * i);
        }
        h.flags = buf.get(off + 7) & 0xff;
        h.offset = buf.getLong(off + 8);
        h.uncompressed = buf.getLong(off + 16);
        return h;
    }

    private static Map<String, ResHdr> parseLookupTable(byte[] raw, ResHdr hdr) {
        Map<String, ResHdr> map = new HashMap<>();
        if (hdr.size <= 0 || hdr.offset < 0 || hdr.offset + hdr.size > raw.length) {
            return map;
        }
        // Lookup table is always stored uncompressed (size == uncompressed)
        int n = (int) (hdr.size / 50);
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            int base = (int) hdr.offset + i * 50;
            ResHdr rh = new ResHdr();
            rh.size = 0;
            for (int b = 0; b < 7; b++) {
                rh.size |= ((long) (raw[base + b] & 0xff)) << (8 * b);
            }
            rh.flags = raw[base + 7] & 0xff;
            rh.offset = buf.getLong(base + 8);
            rh.uncompressed = buf.getLong(base + 16);
            // part (2) + refcnt (4) = 6 bytes, then SHA-1 (20)
            byte[] sha1 = Arrays.copyOfRange(raw, base + 30, base + 50);
            map.put(bytesToHex(sha1), rh);
        }
        return map;
    }

    private static byte[] decompressResource(byte[] raw, ResHdr hdr, int chunkSize) {
        if (hdr.size <= 0 || hdr.offset < 0 || hdr.offset + hdr.size > raw.length) {
            return null;
        }
        boolean compressed = (hdr.flags & RESHDR_FLAG_COMPRESSED) != 0
                || hdr.size != hdr.uncompressed;
        if (!compressed) {
            return Arrays.copyOfRange(raw, (int) hdr.offset,
                    (int) (hdr.offset + hdr.size));
        }
        // Guard: reject unreasonably large or negative uncompressed sizes.
        if (hdr.uncompressed <= 0 || hdr.uncompressed > 256 * 1024 * 1024L) {
            return null;
        }
        // XPRESS chunk decompress
        int uncompLen = (int) hdr.uncompressed;
        byte[] out = new byte[uncompLen];
        int outPos = 0;
        int nChunks = (uncompLen + chunkSize - 1) / chunkSize;
        int dataOff = (int) hdr.offset;

        if (nChunks == 1) {
            // Single chunk — no chunk offset table, compressed data starts immediately
            byte[] chunk = Arrays.copyOfRange(raw, dataOff, (int) (hdr.offset + hdr.size));
            byte[] dec = xpressDecompress(chunk, uncompLen);
            if (dec == null) {
                return null;
            }
            System.arraycopy(dec, 0, out, 0, Math.min(dec.length, uncompLen));
            return out;
        }

        // Multi-chunk: chunk offset table precedes the data
        // Table has (nChunks - 1) 4-byte LE entries giving end-offset of each chunk
        // (last chunk end is implicit from total compressed size)
        int tableBytes = (nChunks - 1) * 4;
        ByteBuffer tbuf = ByteBuffer.wrap(raw, dataOff, tableBytes).order(ByteOrder.LITTLE_ENDIAN);
        int[] chunkEnds = new int[nChunks];
        for (int i = 0; i < nChunks - 1; i++) {
            chunkEnds[i] = tbuf.getInt();
        }
        chunkEnds[nChunks - 1] = (int) hdr.size - tableBytes;

        int chunkDataStart = dataOff + tableBytes;
        int prevEnd = 0;
        for (int ci = 0; ci < nChunks; ci++) {
            int chunkCompEnd = chunkEnds[ci];
            int chunkCompLen = chunkCompEnd - prevEnd;
            int chunkUncomp = Math.min(chunkSize, uncompLen - outPos);
            byte[] cdata = Arrays.copyOfRange(raw,
                    chunkDataStart + prevEnd, chunkDataStart + chunkCompEnd);
            byte[] dec;
            if (chunkCompLen >= chunkUncomp) {
                // Stored uncompressed
                dec = cdata;
            } else {
                dec = xpressDecompress(cdata, chunkUncomp);
                if (dec == null) {
                    return null;
                }
            }
            int copy = Math.min(dec.length, chunkUncomp);
            System.arraycopy(dec, 0, out, outPos, copy);
            outPos += copy;
            prevEnd = chunkCompEnd;
        }
        return out;
    }

    // ── XPRESS Huffman decompressor (MS-XCA §2.3 "LZ77+Huffman") ─────────────
    //
    // Implemented from the Microsoft Open Specification MS-XCA (Open Specifications
    // Promise — royalty-free for any implementation).  Cross-validated against
    // wimlib (Eric Biggers, LGPL-2.1+) for bit-ordering and length-extension
    // semantics; no wimlib source was copied.
    //
    // Bit ordering: LE 16-bit words, bits consumed high-to-low (MSB first within
    // each 16-bit word). The 32-bit accumulator is LEFT-JUSTIFIED: next bit is
    // always bit 31.  This matches wimlib's input_bitstream / bitstream_ensure_bits.
    //
    // Code-length table: first 256 bytes of compressed data; 2 lengths per byte,
    // low nibble = even symbol, high nibble = odd symbol.
    // Canonical code assignment: shorter lengths → lower codes, within a length
    // symbols assigned in ascending symbol-number order.
    // Decode table: direct 15-bit lookup; code occupies TOP (code_length) bits of
    // the 15-bit index.

    private static final int XPRESS_NUM_SYMBOLS = 512;
    private static final int XPRESS_MAX_CODE_LEN = 15;
    private static final int DECODE_TABLE_SIZE = 1 << XPRESS_MAX_CODE_LEN;

    private static byte[] xpressDecompress(byte[] in, int outLen) {
        if (in.length < 256) {
            return null;
        }

        // Read 512 code lengths (4-bit nibbles, low nibble first, 256 bytes)
        int[] codeLens = new int[XPRESS_NUM_SYMBOLS];
        for (int i = 0; i < 256; i++) {
            codeLens[i * 2]     = in[i] & 0x0f;
            codeLens[i * 2 + 1] = (in[i] >> 4) & 0x0f;
        }

        // Build canonical Huffman decode table (direct-lookup, 15-bit index)
        int[] decTable = buildDecodeTable(codeLens);
        if (decTable == null) {
            return null;
        }

        byte[] out = new byte[outLen];
        int outPos = 0;

        // MSB-first left-justified 32-bit accumulator (bit 31 = next bit).
        // Filled from LE 16-bit words: bitbuf |= le16(bytes) << (16 - bitsleft).
        // To peek N bits: bitbuf >>> (32 - N).
        // To consume N bits: bitbuf <<= N; bitsleft -= N.
        // Raw (non-bit) bytes for length extensions are read directly from inPos
        // without going through bitbuf — they share the same byte stream.
        int inPos = 256;
        int bitbuf = 0;
        int bitsleft = 0;

        while (outPos < outLen) {
            // Ensure at least XPRESS_MAX_CODE_LEN bits in the accumulator
            if (bitsleft < XPRESS_MAX_CODE_LEN && inPos + 1 < in.length) {
                int w = (in[inPos] & 0xff) | ((in[inPos + 1] & 0xff) << 8);
                bitbuf |= (w << (16 - bitsleft));
                inPos += 2;
                bitsleft += 16;
            }

            // MSB-first decode: table indexed by top 15 bits
            int idx = bitbuf >>> (32 - XPRESS_MAX_CODE_LEN);
            int entry = decTable[idx];
            if (entry == 0) {
                break;
            }
            int sym     = entry & 0x1ff;
            int codeLen = (entry >> 9) & 0x0f;
            bitbuf <<= codeLen;
            bitsleft -= codeLen;

            if (sym < 256) {
                out[outPos++] = (byte) sym;
            } else {
                // LZ77 match: sym = 256 + (log2_offset << 4) + length_slot
                int matchSym   = sym - 256;
                int log2Offset = (matchSym >> 4) & 0x0f;
                int lenSlot    = matchSym & 0x0f;

                // Ensure enough bits for the offset low bits
                if (bitsleft < 16 && inPos + 1 < in.length) {
                    int w = (in[inPos] & 0xff) | ((in[inPos + 1] & 0xff) << 8);
                    bitbuf |= (w << (16 - bitsleft));
                    inPos += 2;
                    bitsleft += 16;
                }
                // Guard: Java's x >>> 32 = x (not 0) due to shift masking.
                // log2Offset=0 means offset=1 (RLE of preceding byte); offsetLow=0.
                int offsetLow = (log2Offset > 0) ? (bitbuf >>> (32 - log2Offset)) : 0;
                bitbuf <<= log2Offset;
                bitsleft -= log2Offset;
                int matchOffset = (1 << log2Offset) | offsetLow;

                // Extra length bytes are read as raw bytes (shared byte stream)
                int matchLen;
                if (lenSlot < 15) {
                    matchLen = lenSlot + 3;
                } else {
                    if (inPos >= in.length) {
                        break;
                    }
                    int extra = in[inPos++] & 0xff;
                    if (extra == 255) {
                        if (inPos + 1 >= in.length) {
                            break;
                        }
                        matchLen = (in[inPos] & 0xff) | ((in[inPos + 1] & 0xff) << 8);
                        inPos += 2;
                        if (matchLen == 0) {
                            break;
                        }
                    } else {
                        matchLen = extra + 15 + 3;
                    }
                }

                int matchSrc = outPos - matchOffset;
                if (matchSrc < 0 || matchSrc >= outLen) {
                    break;
                }
                for (int k = 0; k < matchLen && outPos < outLen; k++) {
                    // matchSrc + k must stay within the output buffer
                    int srcIdx = matchSrc + k;
                    if (srcIdx >= outLen) {
                        break;
                    }
                    out[outPos] = out[srcIdx];
                    outPos++;
                }
            }
        }
        return out;
    }

    private static int[] buildDecodeTable(int[] codeLens) {
        int[] lenCounts = new int[XPRESS_MAX_CODE_LEN + 1];
        for (int len : codeLens) {
            if (len > 0 && len <= XPRESS_MAX_CODE_LEN) {
                lenCounts[len]++;
            }
        }
        int[] nextCode = new int[XPRESS_MAX_CODE_LEN + 1];
        int code = 0;
        for (int len = 1; len <= XPRESS_MAX_CODE_LEN; len++) {
            nextCode[len] = code;
            code += lenCounts[len];
            code <<= 1;
        }
        // MSB-first: canonical code occupies the TOP (codeLen) bits of 15-bit index.
        // start = code << (15 - codeLen); fill start..(start + 2^(15-codeLen) - 1).
        int[] table = new int[DECODE_TABLE_SIZE];
        for (int sym = 0; sym < XPRESS_NUM_SYMBOLS; sym++) {
            int len = codeLens[sym];
            if (len == 0 || len > XPRESS_MAX_CODE_LEN) {
                continue;
            }
            int c = nextCode[len];
            nextCode[len]++;
            int start = c << (XPRESS_MAX_CODE_LEN - len);
            int count = 1 << (XPRESS_MAX_CODE_LEN - len);
            int entryVal = sym | (len << 9);
            for (int j = start; j < start + count; j++) {
                table[j] = entryVal;
            }
        }
        return table;
    }

    // ── WIM directory tree walk ───────────────────────────────────────────────
    //
    // WIM dentry on-disk layout (empirically verified against EncryptHub.ppkg):
    //   offset  0: length (u64) — total dentry size; round up to 8 for next entry
    //   offset  8: attributes (u32)
    //   offset 12: reparse_tag (u32)
    //   offset 16: subdir_offset (u64) — children list offset from metadata start
    //   offset 24: unused_1 (u64)
    //   offset 32: unused_2 (u64)
    //   offset 40: creation_time (u64)
    //   offset 48: last_access_time (u64)
    //   offset 56: last_write_time (u64)
    //   offset 64: unknown_0x48 field (u64) — vague purpose
    //   offset 64: sha1[20] — file data hash; all-zero for directories
    //   offset 84: (reserved/padding, 14 bytes including hard_link_group and rp fields)
    //   offset 98: short_filename_nbytes (u16)
    //   offset 100: filename_nbytes (u16)
    //   offset 102: filename (UTF-16LE, filename_nbytes bytes, no null in count)
    // Zero-length entry (u64 = 0 at offset 0) terminates a directory listing.

    private static final int WALK_MAX_DEPTH = 64;

    private void walkDirectory(byte[] meta, int dirOff, byte[] raw,
                               Map<String, ResHdr> lut, int chunkSize,
                               List<String> commands, List<String> warnings,
                               List<String> dataRefs, Map<String, String> pkgMeta,
                               XHTMLContentHandler xhtml, ParseContext context,
                               Metadata rootMeta)
            throws IOException, SAXException, TikaException {
        walkDirectory(meta, dirOff, raw, lut, chunkSize, commands, warnings,
                dataRefs, pkgMeta, xhtml, context, rootMeta, 0);
    }

    private void walkDirectory(byte[] meta, int dirOff, byte[] raw,
                               Map<String, ResHdr> lut, int chunkSize,
                               List<String> commands, List<String> warnings,
                               List<String> dataRefs, Map<String, String> pkgMeta,
                               XHTMLContentHandler xhtml, ParseContext context,
                               Metadata rootMeta, int depth)
            throws IOException, SAXException, TikaException {
        if (dirOff < 0 || dirOff >= meta.length || depth > WALK_MAX_DEPTH) {
            return;
        }
        ByteBuffer mbuf = ByteBuffer.wrap(meta).order(ByteOrder.LITTLE_ENDIAN);
        int pos = dirOff;
        while (pos + 8 <= meta.length) {
            long entryLen = mbuf.getLong(pos);
            if (entryLen == 0) {
                break;
            }
            if (entryLen < 104 || pos + entryLen > meta.length) {
                break;
            }
            int attrs    = mbuf.getInt(pos + 8);
            boolean isDir = (attrs & 0x10) != 0;
            long subdirOff = mbuf.getLong(pos + 16);
            byte[] sha1 = Arrays.copyOfRange(meta, pos + 64, pos + 84);
            int nameNb = mbuf.getShort(pos + 100) & 0xffff;
            String name = "";
            if (nameNb > 0 && pos + 102 + nameNb <= meta.length) {
                name = new String(meta, pos + 102, nameNb, StandardCharsets.UTF_16LE);
            }

            String sha1Hex = bytesToHex(sha1);
            String nameLower = name.toLowerCase(Locale.ROOT);

            if (isDir && subdirOff > 0 && subdirOff < meta.length) {
                walkDirectory(meta, (int) subdirOff, raw, lut, chunkSize,
                        commands, warnings, dataRefs, pkgMeta, xhtml, context,
                        rootMeta, depth + 1);
            } else if (!isDir) {
                ResHdr rh = lut.get(sha1Hex);
                if (rh != null && (nameLower.endsWith(".xml")
                        || nameLower.endsWith(".provxml"))) {
                    byte[] xmlBytes = decompressResource(raw, rh, chunkSize);
                    if (xmlBytes != null) {
                        parseXmlContent(xmlBytes, name, commands, warnings,
                                dataRefs, pkgMeta, sha1Hex, xhtml, context, rootMeta);
                    }
                } else if (rh != null) {
                    emitEmbedded(raw, rh, chunkSize, name, sha1Hex,
                            xhtml, context, rootMeta, dataRefs, warnings);
                }
            }

            pos += (int) ((entryLen + 7L) & ~7L);
        }
    }

    // ── XML content parsing ───────────────────────────────────────────────────

    private void parseXmlContent(byte[] xmlBytes, String name,
                                 List<String> commands, List<String> warnings,
                                 List<String> dataRefs, Map<String, String> pkgMeta,
                                 String sha1, XHTMLContentHandler xhtml,
                                 ParseContext context, Metadata rootMeta)
            throws IOException, SAXException, TikaException {
        String xml = decodeXml(xmlBytes);
        if (xml == null) {
            return;
        }

        // Extract CommandLine values — primary forensic field
        extractTagValues(xml, "CommandLine", commands);

        // WAP-provisioningdoc parm elements: value= attribute on <parm ... CommandLine>
        extractParmValues(xml, "CommandLine", commands);

        // PackageConfig metadata
        extractTagValues(xml, "ID", pkgMeta, "id");
        extractTagValues(xml, "Name", pkgMeta, "name");
        extractTagValues(xml, "Version", pkgMeta, "version");
        extractTagValues(xml, "OwnerType", pkgMeta, "owner_type");
        extractTagValues(xml, "Rank", pkgMeta, "rank");

        // Data asset references (dangerous file extensions)
        extractDataRefs(xml, dataRefs);

        // Surface XML text in Tika content stream
        xhtml.startElement("div");
        xhtml.element("p", "Source: " + name + " (sha256: " + sha1 + ")");
        xhtml.element("pre", xml);
        xhtml.endElement("div");

        // Also feed through embedded pipeline for recursive parsing
        EmbeddedDocumentExtractor extractor =
                EmbeddedDocumentUtil.getEmbeddedDocumentExtractor(context);
        Metadata embMeta = new Metadata();
        embMeta.set(TikaCoreProperties.RESOURCE_NAME_KEY, name);
        embMeta.set(Metadata.CONTENT_TYPE, "text/xml");
        try (TikaInputStream tis = TikaInputStream.get(xmlBytes)) {
            if (extractor.shouldParseEmbedded(embMeta)) {
                extractor.parseEmbedded(tis, xhtml, embMeta, context, true);
            }
        } catch (Exception e) {
            warnings.add("XML parse error in " + name + ": " + e.getMessage());
        }
    }

    private void emitEmbedded(byte[] raw, ResHdr rh, int chunkSize,
                              String name, String sha1Hex,
                              XHTMLContentHandler xhtml, ParseContext context,
                              Metadata rootMeta, List<String> dataRefs,
                              List<String> warnings)
            throws IOException, SAXException, TikaException {
        // Check extension before decompressing
        String nameLower = name.toLowerCase(Locale.ROOT);
        int dot = nameLower.lastIndexOf('.');
        if (dot >= 0) {
            String ext = nameLower.substring(dot);
            if (DANGEROUS_EXTENSIONS.contains(ext)) {
                dataRefs.add(name);
            }
        }
        byte[] data = decompressResource(raw, rh, chunkSize);
        if (data == null) {
            return;
        }
        String sha256 = sha256Hex(data);
        String md5 = md5Hex(data);

        // MIME detection FIRST so all parallel arrays add exactly once each
        // (sha256[N], md5[N], sha1[N], name[N], size[N], mime[N] stay aligned).
        EmbeddedDocumentExtractor extractor =
                EmbeddedDocumentUtil.getEmbeddedDocumentExtractor(context);
        Metadata embMeta = new Metadata();
        embMeta.set(TikaCoreProperties.RESOURCE_NAME_KEY, name);
        String mime = "application/octet-stream";
        try (TikaInputStream tis = TikaInputStream.get(data)) {
            mime = new DefaultDetector()
                    .detect(tis, embMeta, context).getBaseType().toString();
        } catch (Exception e) {
            warnings.add("MIME detect error " + name + ": " + e.getMessage());
        }
        embMeta.set(Metadata.CONTENT_TYPE, mime);

        rootMeta.add("ppkg:embedded_file_sha256", sha256);
        rootMeta.add("ppkg:embedded_file_md5", md5);
        rootMeta.add("ppkg:embedded_file_sha1", sha1Hex);
        rootMeta.add("ppkg:embedded_file_name", name);
        rootMeta.add("ppkg:embedded_file_size", Long.toString(data.length));
        rootMeta.add("ppkg:embedded_file_mime", mime);

        try (TikaInputStream tis = TikaInputStream.get(data)) {
            if (extractor.shouldParseEmbedded(embMeta)) {
                extractor.parseEmbedded(tis, xhtml, embMeta, context, true);
            }
        } catch (Exception e) {
            warnings.add("Embedded parse error " + name + ": " + e.getMessage());
        }

        // Structured per-asset record (matches ppkg_happiness copiedDataAssets shape).
        // Reference is percent-encoded to prevent ';' / '=' in attacker-controlled
        // WIM paths from spoofing record fields downstream.
        String asset = "reference=" + sanitizeAssetField(name)
                + ";mime_type=" + sanitizeAssetField(mime)
                + ";size=" + data.length
                + ";sha256=" + sha256
                + ";sha1=" + sha1Hex
                + ";md5=" + md5;
        rootMeta.add("ppkg:data_asset", asset);
        xhtml.element("p", "DataAsset: " + asset);
    }

    /**
     * Percent-encode characters that would break the {@code k=v;k=v;...} record
     * format used by {@code ppkg:data_asset}: '%', ';', '=', and any control
     * byte. Attacker-controlled WIM paths are passed through this before being
     * concatenated into the record so a name like {@code evil.exe;sha256=AAAA}
     * cannot spoof the trailing hash field.
     */
    private static String sanitizeAssetField(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' || c == ';' || c == '=' || c < 0x20 || c == 0x7f) {
                out.append('%')
                   .append(String.format(Locale.ROOT, "%02X", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ── XML field extraction (regex-free, simple tag scan) ────────────────────

    private static String decodeXml(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xfe) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        }
        if (b.length >= 3 && (b[0] & 0xff) == 0xef
                && (b[1] & 0xff) == 0xbb && (b[2] & 0xff) == 0xbf) {
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        }
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void extractTagValues(String xml, String tag, List<String> out) {
        String open  = "<" + tag + ">";
        String close = "</" + tag + ">";
        int pos = 0;
        while (true) {
            int start = xml.indexOf(open, pos);
            if (start < 0) {
                break;
            }
            start += open.length();
            int end = xml.indexOf(close, start);
            if (end < 0) {
                break;
            }
            String val = xml.substring(start, end).trim();
            if (!val.isEmpty()) {
                out.add(val);
            }
            pos = end + close.length();
        }
    }

    private static void extractTagValues(String xml, String tag,
                                         Map<String, String> out, String key) {
        List<String> vals = new ArrayList<>();
        extractTagValues(xml, tag, vals);
        if (!vals.isEmpty() && !out.containsKey(key)) {
            out.put(key, vals.get(0));
        }
    }

    private static void extractParmValues(String xml, String fieldName, List<String> out) {
        // <parm ... name="CommandLine" ... value="..." />
        String search = "name=\"" + fieldName + "\"";
        int pos = 0;
        while (true) {
            int idx = xml.indexOf(search, pos);
            if (idx < 0) {
                break;
            }
            // Find enclosing <parm ...> tag start
            int tagStart = xml.lastIndexOf('<', idx);
            if (tagStart < 0) {
                pos = idx + 1;
                continue;
            }
            int tagEnd = xml.indexOf('>', idx);
            if (tagEnd < 0) {
                break;
            }
            String tag = xml.substring(tagStart, tagEnd + 1);
            String val = attrValue(tag, "value");
            if (val != null && !val.isEmpty()) {
                // Unescape XML entities
                val = val.replace("&quot;", "\"").replace("&amp;", "&")
                        .replace("&lt;", "<").replace("&gt;", ">");
                out.add(val);
            }
            pos = tagEnd + 1;
        }
    }

    private static String attrValue(String tag, String attr) {
        String search = attr + "=\"";
        int idx = tag.indexOf(search);
        if (idx < 0) {
            return null;
        }
        int start = idx + search.length();
        int end = tag.indexOf('"', start);
        if (end < 0) {
            return null;
        }
        return tag.substring(start, end);
    }

    private static void extractDataRefs(String xml, List<String> out) {
        // Find any token in the XML that ends with a dangerous extension
        // Simple whitespace/quote tokenizer
        for (String token : xml.split("[\\s\"'<>]+")) {
            if (token.length() < 4) {
                continue;
            }
            int dot = token.lastIndexOf('.');
            if (dot < 0) {
                continue;
            }
            String ext = token.substring(dot).toLowerCase(Locale.ROOT);
            if (DANGEROUS_EXTENSIONS.contains(ext)) {
                out.add(token);
            }
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        }
        return sb.toString();
    }

    private static String sha256Hex(byte[] data) {
        return digestHex("SHA-256", data);
    }

    private static String md5Hex(byte[] data) {
        return digestHex("MD5", data);
    }

    private static String digestHex(String algorithm, byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            return bytesToHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    private static final class ResHdr {
        long size;
        int  flags;
        long offset;
        long uncompressed;
    }
}
