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
package org.apache.tika.parser.microsoft.ooxml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.xssf.usermodel.XSSFRelation;

/**
 * Reads the XLSB workbook binary to map Area3d/Ref3d Ptg xtiIndex values
 * to actual sheet names.
 *
 * Parses two record types from the workbook stream:
 * - BrtBundleSh (156): sheet names in workbook order
 * - BrtExternSheet (362): xtiIndex → workbook sheet position mapping
 */
final class XlmWorkbookSheetMap {

    private static final int BRT_BUNDLE_SH   = 156;
    private static final int BRT_EXTERN_SHEET = 362;

    /** Maps Area3d xtiIndex → sheet name. */
    final Map<Integer, String> xtiToSheetName;

    private XlmWorkbookSheetMap(Map<Integer, String> xtiToSheetName) {
        this.xtiToSheetName = xtiToSheetName;
    }

    static XlmWorkbookSheetMap empty() {
        return new XlmWorkbookSheetMap(Collections.emptyMap());
    }

    /**
     * Build the xtiIndex → sheet-name map by parsing the workbook binary.
     * Returns {@link #empty()} on any parsing failure.
     */
    static XlmWorkbookSheetMap build(OPCPackage container) {
        try {
            List<PackagePart> wbParts = container.getPartsByContentType(
                    XSSFRelation.XLSB_BINARY_WORKBOOK.getContentType());
            if (wbParts.isEmpty()) {
                return empty();
            }
            try (InputStream is = wbParts.get(0).getInputStream()) {
                return parse(is);
            }
        } catch (Exception e) {
            return empty();
        }
    }

    private static XlmWorkbookSheetMap parse(InputStream is) throws IOException {
        byte[] data = is.readAllBytes();
        List<String> sheetNames = new ArrayList<>();
        Map<Integer, Integer> xtiToItabFirst = new HashMap<>();

        int pos = 0;
        while (pos < data.length) {
            int[] typeResult = readVarint(data, pos);
            int rtype = typeResult[0];
            pos = typeResult[1];
            int[] lenResult = readVarint(data, pos);
            int rlen = lenResult[0];
            pos = lenResult[1];
            if (pos + rlen > data.length) {
                break;
            }
            byte[] payload = new byte[rlen];
            System.arraycopy(data, pos, payload, 0, rlen);
            pos += rlen;

            if (rtype == BRT_BUNDLE_SH) {
                String name = parseBundleSh(payload);
                if (name != null) {
                    sheetNames.add(name);
                }
            } else if (rtype == BRT_EXTERN_SHEET) {
                xtiToItabFirst = parseExternSheet(payload);
            }
        }

        Map<Integer, String> result = new HashMap<>();
        for (Map.Entry<Integer, Integer> entry : xtiToItabFirst.entrySet()) {
            int xtiIndex = entry.getKey();
            int itabFirst = entry.getValue();
            if (itabFirst >= 0 && itabFirst < sheetNames.size()) {
                result.put(xtiIndex, sheetNames.get(itabFirst));
            }
        }
        return new XlmWorkbookSheetMap(result);
    }

    /**
     * BrtBundleSh: hsState(4) + iTABID(4) + strRelId(XLString) + strName(XLString).
     * XLString = cch(uint32) + chars(cch × 2 bytes UTF-16LE).
     */
    private static String parseBundleSh(byte[] payload) {
        if (payload.length < 12) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        b.getInt(); // hsState
        b.getInt(); // iTABID
        // Skip strRelId
        if (b.remaining() < 4) {
            return null;
        }
        int relIdLen = b.getInt();
        if (relIdLen < 0 || relIdLen * 2 > b.remaining()) {
            return null;
        }
        b.position(b.position() + relIdLen * 2);
        // Read strName
        if (b.remaining() < 4) {
            return null;
        }
        int nameLen = b.getInt();
        if (nameLen < 0 || nameLen * 2 > b.remaining()) {
            return null;
        }
        byte[] chars = new byte[nameLen * 2];
        b.get(chars);
        return new String(chars, StandardCharsets.UTF_16LE);
    }

    /**
     * BrtExternSheet: cXti(uint32) + cXti×(iSupBook:uint32, itabFirst:int32, itabLast:int32).
     */
    private static Map<Integer, Integer> parseExternSheet(byte[] payload) {
        Map<Integer, Integer> map = new HashMap<>();
        if (payload.length < 4) {
            return map;
        }
        ByteBuffer b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int count = b.getInt();
        for (int i = 0; i < count && b.remaining() >= 12; i++) {
            b.getInt();              // iSupBook (ignore)
            int itabFirst = b.getInt();
            b.getInt();              // itabLast (ignore)
            map.put(i, itabFirst);
        }
        return map;
    }

    // ── BIFF12 varint decoder ────────────────────────────────────────────────

    private static int[] readVarint(byte[] data, int pos) {
        if (pos >= data.length) {
            return new int[]{-1, pos};
        }
        int b1 = data[pos++] & 0xFF;
        if ((b1 & 0x80) != 0 && pos < data.length) {
            int b2 = data[pos++] & 0xFF;
            return new int[]{(b1 & 0x7F) | ((b2 & 0x7F) << 7), pos};
        }
        return new int[]{b1, pos};
    }
}
