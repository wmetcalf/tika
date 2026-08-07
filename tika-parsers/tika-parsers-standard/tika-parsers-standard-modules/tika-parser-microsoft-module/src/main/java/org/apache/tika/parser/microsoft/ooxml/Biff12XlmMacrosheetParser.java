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
import java.nio.ByteOrder;

import org.apache.poi.xssf.binary.XSSFBParseException;
import org.apache.poi.xssf.binary.XSSFBParser;
import org.xml.sax.SAXException;

import org.apache.tika.exception.RuntimeSAXException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.sax.XHTMLContentHandler;

/**
 * Low-level BIFF12 parser for XLM macro sheets in XLSB files.
 *
 * Reads formula-cell records (BrtCellFmlaString/Num/Bool/Error, types 8-11)
 * from the macrosheet binary stream, decodes the Ptg formula bytes using
 * {@link Biff12XlmFormulaDecoder}, and emits each formula as a line of text
 * directly into the supplied {@link XHTMLContentHandler}.
 *
 * Writing directly to the XHTML handler avoids {@code SheetTextAsHTML}'s
 * column-filling logic, which would emit hundreds of empty {@code <td>}
 * elements for every formula when all cells are in a far-right column.
 */
class Biff12XlmMacrosheetParser extends XSSFBParser {

    // BIFF12 record type IDs (MS-XLSB §2.4 / §2.5.97)
    private static final int BRT_ROW_HDR     = 0x0000;
    private static final int BRT_FMLA_STRING = 0x0008;
    private static final int BRT_FMLA_NUM    = 0x0009;
    private static final int BRT_FMLA_BOOL   = 0x000A;
    private static final int BRT_FMLA_ERROR  = 0x000B;

    /**
     * Default bound on a single BIFF12 formula record. Generous for real XLM (Excel's own
     * formula limit is far below this); it exists to bound the attacker-controlled
     * {@code new byte[sz]} allocation below.
     */
    static final int DEFAULT_MAX_FORMULA_RECORD_BYTES = 65536;

    private final XHTMLContentHandler xhtml;
    private final XlmMacroEmulator emulator;
    private final int maxFormulaRecordBytes;
    /** Invoked when a formula record is dropped for exceeding the bound. */
    private final Runnable onRecordDropped;
    private int currentRow = -1;

    Biff12XlmMacrosheetParser(InputStream stream, XHTMLContentHandler xhtml) {
        this(stream, xhtml, null);
    }

    Biff12XlmMacrosheetParser(InputStream stream, XHTMLContentHandler xhtml,
                               XlmMacroEmulator emulator) {
        this(stream, xhtml, emulator, 0, null);
    }

    Biff12XlmMacrosheetParser(InputStream stream, XHTMLContentHandler xhtml,
                               XlmMacroEmulator emulator, int maxFormulaRecordBytes,
                               Runnable onRecordDropped) {
        super(stream);
        this.xhtml = xhtml;
        this.emulator = emulator;
        this.maxFormulaRecordBytes = maxFormulaRecordBytes > 0
                ? maxFormulaRecordBytes : DEFAULT_MAX_FORMULA_RECORD_BYTES;
        this.onRecordDropped = onRecordDropped;
    }

    @Override
    public void handleRecord(int type, byte[] data) throws XSSFBParseException {
        switch (type) {
            case BRT_ROW_HDR:
                if (data.length >= 4) {
                    currentRow = (int) java.nio.ByteBuffer.wrap(data)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .getInt();
                }
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

    // ── Record handlers ─────────────────────────────────────────────────────

    private void handleFormulaCell(int type, byte[] data) {
        Biff12XlmFormulaDecoder.Buf buf = new Biff12XlmFormulaDecoder.Buf(data);

        // col (4 bytes, 0-based)
        long colLong = buf.readU32();
        if (colLong < 0) {
            return;
        }

        // style (4 bytes, skip)
        buf.skip(4);

        // value (type-dependent, skip)
        skipCellValue(type, buf);

        // flags (2 bytes, skip)
        buf.skip(2);

        // formula size (4 bytes) + formula bytes
        long sz = buf.readU32();
        if (sz <= 0 || sz > maxFormulaRecordBytes) {
            // Was a silent  against a hardcoded 65536. Dropping an entire macro
            // formula without a trace is the same evidence-loss failure as truncating one:
            // the analyst sees a short macro and no indication anything was withheld.
            if (sz > maxFormulaRecordBytes && onRecordDropped != null) {
                onRecordDropped.run();
            }
            return;
        }
        byte[] formulaBytes = new byte[(int) sz];
        if (buf.readBytes(formulaBytes) < (int) sz) {
            return;
        }

        // Feed raw bytes to emulator before decoding (emulator needs bytes, not text)
        int row = currentRow < 0 ? 0 : currentRow;
        if (emulator != null && !emulator.addMacroCell(row, formulaBytes)) {
            return;
        }

        String formula = Biff12XlmFormulaDecoder.decode(formulaBytes);
        if (formula == null || formula.isEmpty()) {
            return;
        }

        String cellRef = Biff12XlmFormulaDecoder.cellAddr((int) colLong, row, false, false);
        try {
            xhtml.element("p", cellRef + ": =" + formula);
        } catch (SAXException e) {
            if (WriteLimitReachedException.isWriteLimitReached(e)) {
                throw new RuntimeSAXException(e);
            }
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
}
