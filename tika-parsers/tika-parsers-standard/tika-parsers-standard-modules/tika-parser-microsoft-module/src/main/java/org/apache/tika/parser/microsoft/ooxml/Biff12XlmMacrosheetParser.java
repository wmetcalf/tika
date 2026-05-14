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
import java.nio.ByteOrder;

import org.apache.poi.xssf.binary.XSSFBParseException;
import org.apache.poi.xssf.binary.XSSFBParser;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;

/**
 * Low-level BIFF12 parser for XLM macro sheets in XLSB files.
 *
 * Replaces the generic {@link org.apache.poi.xssf.binary.XSSFBSheetHandler} for macrosheet
 * processing so that formula bytes can be decoded by {@link Biff12XlmFormulaDecoder}
 * instead of POI's standard formula engine (which does not know XLM function names).
 *
 * For each formula cell record the decoded formula is emitted as the cell value through
 * the supplied {@link XSSFSheetXMLHandler.SheetContentsHandler}.
 */
class Biff12XlmMacrosheetParser extends XSSFBParser {

    // BIFF12 record type IDs (MS-XLSB §2.4)
    private static final int BRT_ROW_HDR     = 0x0000;
    private static final int BRT_FMLA_STRING = 0x0008;
    private static final int BRT_FMLA_NUM    = 0x0009;
    private static final int BRT_FMLA_BOOL   = 0x000A;
    private static final int BRT_FMLA_ERROR  = 0x000B;

    private final XSSFSheetXMLHandler.SheetContentsHandler handler;

    private int currentRow = -1;
    private boolean rowOpen = false;

    Biff12XlmMacrosheetParser(InputStream stream,
                               XSSFSheetXMLHandler.SheetContentsHandler handler) {
        super(stream);
        this.handler = handler;
    }

    @Override
    protected void handleRecord(int type, byte[] data) throws XSSFBParseException {
        switch (type) {
            case BRT_ROW_HDR:
                handleRowHdr(data);
                break;
            case BRT_FMLA_STRING:
            case BRT_FMLA_NUM:
            case BRT_FMLA_BOOL:
            case BRT_FMLA_ERROR:
                handleFormulaCell(type, data);
                break;
            default:
                break;
        }
    }

    @Override
    public void parse() throws XSSFBParseException, IOException {
        try {
            super.parse();
        } finally {
            endOpenRow();
        }
    }

    // ── Record handlers ─────────────────────────────────────────────────────

    private void handleRowHdr(byte[] data) {
        endOpenRow();
        if (data.length >= 4) {
            currentRow = (int) java.nio.ByteBuffer.wrap(data)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .getInt();
        }
    }

    private void handleFormulaCell(int type, byte[] data) {
        Biff12XlmFormulaDecoder.Buf buf =
                new Biff12XlmFormulaDecoder.Buf(data);

        // col (4 bytes, 0-based)
        long colLong = buf.readU32();
        if (colLong < 0) {
            return;
        }
        int col = (int) colLong;

        // style (4 bytes, skip)
        buf.skip(4);

        // value bytes (depends on record type)
        skipCellValue(type, buf);

        // flags (2 bytes, skip)
        buf.skip(2);

        // formula size (4 bytes) + formula bytes
        long sz = buf.readU32();
        if (sz <= 0 || sz > 65536) {
            return;
        }
        byte[] formulaBytes = new byte[(int) sz];
        if (buf.readBytes(formulaBytes) < (int) sz) {
            return;
        }

        String formula = Biff12XlmFormulaDecoder.decode(formulaBytes);
        if (formula == null || formula.isEmpty()) {
            return;
        }

        ensureRowOpen();

        String cellRef = Biff12XlmFormulaDecoder.cellAddr(col, currentRow, false, false);
        try {
            handler.cell(cellRef, "=" + formula, null);
        } catch (Exception e) {
            // Non-fatal — continue with remaining cells
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static void skipCellValue(int type, Biff12XlmFormulaDecoder.Buf buf) {
        switch (type) {
            case BRT_FMLA_NUM:
                buf.skip(8);
                break;
            case BRT_FMLA_BOOL:
            case BRT_FMLA_ERROR:
                buf.skip(1);
                break;
            case BRT_FMLA_STRING:
                // 4-byte char count + count * 2 bytes UTF-16LE
                long count = buf.readU32();
                if (count > 0 && count <= 32767) {
                    buf.skip((int) count * 2);
                }
                break;
            default:
                break;
        }
    }

    private void ensureRowOpen() {
        if (!rowOpen) {
            try {
                handler.startRow(currentRow < 0 ? 0 : currentRow);
            } catch (Exception e) {
                // ignore
            }
            rowOpen = true;
        }
    }

    private void endOpenRow() {
        if (rowOpen) {
            try {
                handler.endRow(currentRow < 0 ? 0 : currentRow);
            } catch (Exception e) {
                // ignore
            }
            rowOpen = false;
        }
    }
}
