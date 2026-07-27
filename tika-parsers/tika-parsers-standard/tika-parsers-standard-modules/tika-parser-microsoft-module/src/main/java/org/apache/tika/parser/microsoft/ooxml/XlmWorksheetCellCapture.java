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

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

import org.apache.poi.xssf.binary.XSSFBParseException;
import org.apache.poi.xssf.binary.XSSFBParser;

/**
 * Lightweight BIFF12 worksheet scanner that captures numeric cell values into
 * a shared map for use by the {@link XlmMacroEmulator}.
 *
 * Only numeric cell types are read (BrtCellRk, BrtCellReal, BrtCellFmlaNum).
 * String, boolean and error cells are ignored — the emulator only needs numbers
 * to reconstruct CHAR()-encoded payloads.
 *
 * Keys in the output map: {@code "{sheetName}:{row}:{col}"} → double value,
 * where sheetName is the Excel sheet name (e.g. "Sheet1").
 */
class XlmWorksheetCellCapture extends XSSFBParser {

    // BIFF12 record type IDs (MS-XLSB §2.4)
    private static final int BRT_ROW_HDR   = 0x0000;
    private static final int BRT_CELL_RK   = 0x0002;
    private static final int BRT_CELL_REAL = 0x0005;
    private static final int BRT_FMLA_NUM  = 0x0009;

    private final String sheetName;
    private final Map<String, Double> cellValues;
    private int currentRow;
    private boolean limitReached;

    XlmWorksheetCellCapture(InputStream stream, String sheetName, Map<String, Double> cellValues) {
        super(stream);
        this.sheetName = sheetName;
        this.cellValues = cellValues;
    }

    @Override
    public void handleRecord(int type, byte[] data) throws XSSFBParseException {
        switch (type) {
            case BRT_ROW_HDR:
                if (data.length >= 4) {
                    currentRow = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt();
                }
                break;
            case BRT_CELL_REAL:
                captureReal(data);
                break;
            case BRT_CELL_RK:
                captureRk(data);
                break;
            case BRT_FMLA_NUM:
                captureFmlaNum(data);
                break;
            default:
                break;
        }
    }

    // ── Record decoders ─────────────────────────────────────────────────────

    /** BrtCellReal: col(4) + style(4) + double(8) */
    private void captureReal(byte[] data) {
        if (data.length < 16) {
            return;
        }
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int col = b.getInt();
        b.getInt(); // style
        double value = b.getDouble();
        store(col, value);
    }

    /** BrtCellRk: col(4) + style(4) + rk(4) */
    private void captureRk(byte[] data) {
        if (data.length < 12) {
            return;
        }
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int col = b.getInt();
        b.getInt(); // style
        int rk = b.getInt();
        store(col, decodeRk(rk));
    }

    /** BrtCellFmlaNum: col(4) + style(4) + double(8) + flags(2) + sz(4) + formula */
    private void captureFmlaNum(byte[] data) {
        if (data.length < 16) {
            return;
        }
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int col = b.getInt();
        b.getInt(); // style
        double value = b.getDouble();
        store(col, value);
    }

    private void store(int col, double value) {
        String key = sheetName + ":" + currentRow + ":" + col;
        if (cellValues.size()
                >= XSSFExcelExtractorDecorator.WORKBOOK_VALUES_MAX_ENTRIES
                && !cellValues.containsKey(key)) {
            limitReached = true;
            return;
        }
        cellValues.put(key, value);
    }

    boolean isLimitReached() {
        return limitReached;
    }

    // ── RK number decoding (MS-XLSB §2.5.122) ───────────────────────────────

    private static double decodeRk(int rk) {
        double value;
        if ((rk & 0x02) == 0x02) {
            value = rk >> 2;
        } else {
            long bits = ((long) (rk & 0xFFFFFFFC)) << 32;
            value = Double.longBitsToDouble(bits);
        }
        if ((rk & 0x01) == 0x01) {
            value /= 100.0;
        }
        return value;
    }
}
