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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes BIFF12 (XLSB) Ptg formula token streams for XLM macro sheets.
 *
 * Ported from pyxlsb2 (MIT licence, github.com/DataBrewery/pyxlsb2) and
 * XLMMacroDeobfuscator (MIT licence, github.com/DissectMalware/XLMMacroDeobfuscator).
 * The Ptg binary format is defined in MS-XLSB §2.5.97.
 *
 * Usage: {@code String formula = Biff12XlmFormulaDecoder.decode(bytes);}
 * Returns null when the bytes cannot be decoded (malformed stream).
 */
final class Biff12XlmFormulaDecoder {

    // ── Ptg type IDs (MS-XLSB §2.5.97) ────────────────────────────────────
    private static final int PTG_EXP          = 0x01;
    private static final int PTG_TABLE        = 0x02;
    private static final int PTG_ADD          = 0x03;
    private static final int PTG_SUB          = 0x04;
    private static final int PTG_MUL          = 0x05;
    private static final int PTG_DIV          = 0x06;
    private static final int PTG_POWER        = 0x07;
    private static final int PTG_CONCAT       = 0x08;
    private static final int PTG_LT           = 0x09;
    private static final int PTG_LE           = 0x0A;
    private static final int PTG_EQ           = 0x0B;
    private static final int PTG_GE           = 0x0C;
    private static final int PTG_GT           = 0x0D;
    private static final int PTG_NE           = 0x0E;
    private static final int PTG_ISECT        = 0x0F;
    private static final int PTG_UNION        = 0x10;
    private static final int PTG_RANGE        = 0x11;
    private static final int PTG_UPLUS        = 0x12;
    private static final int PTG_UMINUS       = 0x13;
    private static final int PTG_PERCENT      = 0x14;
    private static final int PTG_PAREN        = 0x15;
    private static final int PTG_MISS_ARG     = 0x16;
    private static final int PTG_STR          = 0x17;
    private static final int PTG_ATTR         = 0x19;
    private static final int PTG_ERR          = 0x1C;
    private static final int PTG_BOOL         = 0x1D;
    private static final int PTG_INT          = 0x1E;
    private static final int PTG_NUM          = 0x1F;
    private static final int PTG_ARRAY        = 0x20;
    private static final int PTG_FUNC         = 0x21;
    private static final int PTG_FUNC_VAR     = 0x22;
    private static final int PTG_NAME         = 0x23;
    private static final int PTG_REF          = 0x24;
    private static final int PTG_AREA         = 0x25;
    private static final int PTG_MEM_AREA     = 0x26;
    private static final int PTG_MEM_ERR      = 0x27;
    private static final int PTG_MEM_NO_MEM   = 0x28;
    private static final int PTG_MEM_FUNC     = 0x29;
    private static final int PTG_REF_ERR      = 0x2A;
    private static final int PTG_AREA_ERR     = 0x2B;
    private static final int PTG_REF_N        = 0x2C;
    private static final int PTG_AREA_N       = 0x2D;
    private static final int PTG_MEM_AREA_N   = 0x2E;
    private static final int PTG_MEM_NO_MEM_N = 0x2F;
    private static final int PTG_NAME_X       = 0x39;
    private static final int PTG_REF_3D       = 0x3A;
    private static final int PTG_AREA_3D      = 0x3B;
    private static final int PTG_REF_ERR_3D   = 0x3C;
    private static final int PTG_AREA_ERR_3D  = 0x3D;

    // Attr flags (PTG_ATTR has 1-byte flags + 2-byte data)
    private static final int ATTR_SPACE = 0x40;

    // ── XLM / Excel function name table ────────────────────────────────────
    // Source: pyxlsb2/ptgs.py (MIT licence, DataBrewery/pyxlsb2) which derives
    // from MS-XLS §2.5.198.62 and MS-XLSB §2.5.97.88.
    static final Map<Integer, String> FUNC_NAMES;

    static {
        Map<Integer, String> m = new HashMap<>(420);
        m.put(0x0000, "COUNT");
        m.put(0x0001, "IF");
        m.put(0x0002, "ISNA");
        m.put(0x0003, "ISERROR");
        m.put(0x0004, "SUM");
        m.put(0x0005, "AVERAGE");
        m.put(0x0006, "MIN");
        m.put(0x0007, "MAX");
        m.put(0x0008, "ROW");
        m.put(0x0009, "COLUMN");
        m.put(0x000A, "NA");
        m.put(0x000B, "NPV");
        m.put(0x000C, "STDEV");
        m.put(0x000D, "DOLLAR");
        m.put(0x000E, "FIXED");
        m.put(0x000F, "SIN");
        m.put(0x0010, "COS");
        m.put(0x0011, "TAN");
        m.put(0x0012, "ATAN");
        m.put(0x0013, "PI");
        m.put(0x0014, "SQRT");
        m.put(0x0015, "EXP");
        m.put(0x0016, "LN");
        m.put(0x0017, "LOG10");
        m.put(0x0018, "ABS");
        m.put(0x0019, "INT");
        m.put(0x001A, "SIGN");
        m.put(0x001B, "ROUND");
        m.put(0x001C, "LOOKUP");
        m.put(0x001D, "INDEX");
        m.put(0x001E, "REPT");
        m.put(0x001F, "MID");
        m.put(0x0020, "LEN");
        m.put(0x0021, "VALUE");
        m.put(0x0022, "TRUE");
        m.put(0x0023, "FALSE");
        m.put(0x0024, "AND");
        m.put(0x0025, "OR");
        m.put(0x0026, "NOT");
        m.put(0x0027, "MOD");
        m.put(0x0028, "DCOUNT");
        m.put(0x0029, "DSUM");
        m.put(0x002A, "DAVERAGE");
        m.put(0x002B, "DMIN");
        m.put(0x002C, "DMAX");
        m.put(0x002D, "DSTDEV");
        m.put(0x002E, "VAR");
        m.put(0x002F, "DVAR");
        m.put(0x0030, "TEXT");
        m.put(0x0031, "LINEST");
        m.put(0x0032, "TREND");
        m.put(0x0033, "LOGEST");
        m.put(0x0034, "GROWTH");
        m.put(0x0035, "GOTO");
        m.put(0x0036, "HALT");
        m.put(0x0037, "RETURN");
        m.put(0x0038, "PV");
        m.put(0x0039, "FV");
        m.put(0x003A, "NPER");
        m.put(0x003B, "PMT");
        m.put(0x003C, "RATE");
        m.put(0x003D, "MIRR");
        m.put(0x003E, "IRR");
        m.put(0x003F, "RAND");
        m.put(0x0040, "MATCH");
        m.put(0x0041, "DATE");
        m.put(0x0042, "TIME");
        m.put(0x0043, "DAY");
        m.put(0x0044, "MONTH");
        m.put(0x0045, "YEAR");
        m.put(0x0046, "WEEKDAY");
        m.put(0x0047, "HOUR");
        m.put(0x0048, "MINUTE");
        m.put(0x0049, "SECOND");
        m.put(0x004A, "NOW");
        m.put(0x004B, "AREAS");
        m.put(0x004C, "ROWS");
        m.put(0x004D, "COLUMNS");
        m.put(0x004E, "OFFSET");
        m.put(0x004F, "ABSREF");
        m.put(0x0050, "RELREF");
        m.put(0x0051, "ARGUMENT");
        m.put(0x0052, "SEARCH");
        m.put(0x0053, "TRANSPOSE");
        m.put(0x0054, "ERROR");
        m.put(0x0055, "STEP");
        m.put(0x0056, "TYPE");
        m.put(0x0057, "ECHO");
        m.put(0x0058, "SET.NAME");
        m.put(0x0059, "CALLER");
        m.put(0x005A, "DEREF");
        m.put(0x005B, "WINDOWS");
        m.put(0x005C, "SERIES");
        m.put(0x005D, "DOCUMENTS");
        m.put(0x005E, "ACTIVE.CELL");
        m.put(0x005F, "SELECTION");
        m.put(0x0060, "RESULT");
        m.put(0x0061, "ATAN2");
        m.put(0x0062, "ASIN");
        m.put(0x0063, "ACOS");
        m.put(0x0064, "CHOOSE");
        m.put(0x0065, "HLOOKUP");
        m.put(0x0066, "VLOOKUP");
        m.put(0x0067, "LINKS");
        m.put(0x0068, "INPUT");
        m.put(0x0069, "ISREF");
        m.put(0x006A, "GET.FORMULA");
        m.put(0x006B, "GET.NAME");
        m.put(0x006C, "SET.VALUE");
        m.put(0x006D, "LOG");
        m.put(0x006E, "EXEC");
        m.put(0x006F, "CHAR");
        m.put(0x0070, "LOWER");
        m.put(0x0071, "UPPER");
        m.put(0x0072, "PROPER");
        m.put(0x0073, "LEFT");
        m.put(0x0074, "RIGHT");
        m.put(0x0075, "EXACT");
        m.put(0x0076, "TRIM");
        m.put(0x0077, "REPLACE");
        m.put(0x0078, "SUBSTITUTE");
        m.put(0x0079, "CODE");
        m.put(0x007A, "NAMES");
        m.put(0x007B, "DIRECTORY");
        m.put(0x007C, "FIND");
        m.put(0x007D, "CELL");
        m.put(0x007E, "ISERR");
        m.put(0x007F, "ISTEXT");
        m.put(0x0080, "ISNUMBER");
        m.put(0x0081, "ISBLANK");
        m.put(0x0082, "T");
        m.put(0x0083, "N");
        m.put(0x0084, "FOPEN");
        m.put(0x0085, "FCLOSE");
        m.put(0x0086, "FSIZE");
        m.put(0x0087, "FREADLN");
        m.put(0x0088, "FREAD");
        m.put(0x0089, "FWRITELN");
        m.put(0x008A, "FWRITE");
        m.put(0x008B, "FPOS");
        m.put(0x008C, "DATEVALUE");
        m.put(0x008D, "TIMEVALUE");
        m.put(0x008E, "SLN");
        m.put(0x008F, "SYD");
        m.put(0x0090, "DDB");
        m.put(0x0091, "GET.DEF");
        m.put(0x0092, "REFTEXT");
        m.put(0x0093, "TEXTREF");
        m.put(0x0094, "INDIRECT");
        m.put(0x0095, "REGISTER");
        m.put(0x0096, "CALL");
        m.put(0x0097, "ADD.BAR");
        m.put(0x0098, "ADD.MENU");
        m.put(0x0099, "ADD.COMMAND");
        m.put(0x009A, "ENABLE.COMMAND");
        m.put(0x009B, "CHECK.COMMAND");
        m.put(0x009C, "RENAME.COMMAND");
        m.put(0x009D, "SHOW.BAR");
        m.put(0x009E, "DELETE.MENU");
        m.put(0x009F, "DELETE.COMMAND");
        m.put(0x00A0, "GET.CHART.ITEM");
        m.put(0x00A1, "DIALOG.BOX");
        m.put(0x00A2, "CLEAN");
        m.put(0x00A3, "MDETERM");
        m.put(0x00A4, "MINVERSE");
        m.put(0x00A5, "MMULT");
        m.put(0x00A6, "FILES");
        m.put(0x00A7, "IPMT");
        m.put(0x00A8, "PPMT");
        m.put(0x00A9, "COUNTA");
        m.put(0x00AA, "CANCEL.KEY");
        m.put(0x00AB, "FOR");
        m.put(0x00AC, "WHILE");
        m.put(0x00AD, "BREAK");
        m.put(0x00AE, "NEXT");
        m.put(0x00AF, "INITIATE");
        m.put(0x00B0, "REQUEST");
        m.put(0x00B1, "POKE");
        m.put(0x00B2, "EXECUTE");
        m.put(0x00B3, "TERMINATE");
        m.put(0x00B4, "RESTART");
        m.put(0x00B5, "HELP");
        m.put(0x00B6, "GET.BAR");
        m.put(0x00B7, "PRODUCT");
        m.put(0x00B8, "FACT");
        m.put(0x00B9, "GET.CELL");
        m.put(0x00BA, "GET.WORKSPACE");
        m.put(0x00BB, "GET.WINDOW");
        m.put(0x00BC, "GET.DOCUMENT");
        m.put(0x00BD, "DPRODUCT");
        m.put(0x00BE, "ISNONTEXT");
        m.put(0x00BF, "GET.NOTE");
        m.put(0x00C0, "NOTE");
        m.put(0x00C1, "STDEVP");
        m.put(0x00C2, "VARP");
        m.put(0x00C3, "DSTDEVP");
        m.put(0x00C4, "DVARP");
        m.put(0x00C5, "TRUNC");
        m.put(0x00C6, "ISLOGICAL");
        m.put(0x00C7, "DCOUNTA");
        m.put(0x00C8, "DELETE.BAR");
        m.put(0x00C9, "UNREGISTER");
        m.put(0x00CC, "USDOLLAR");
        m.put(0x00CD, "FINDB");
        m.put(0x00CE, "SEARCHB");
        m.put(0x00CF, "REPLACEB");
        m.put(0x00D0, "LEFTB");
        m.put(0x00D1, "RIGHTB");
        m.put(0x00D2, "MIDB");
        m.put(0x00D3, "LENB");
        m.put(0x00D4, "ROUNDUP");
        m.put(0x00D5, "ROUNDDOWN");
        m.put(0x00D6, "ASC");
        m.put(0x00D7, "DBCS");
        m.put(0x00D8, "RANK");
        m.put(0x00DB, "ADDRESS");
        m.put(0x00DC, "DAYS360");
        m.put(0x00DD, "TODAY");
        m.put(0x00DE, "VDB");
        m.put(0x00DF, "ELSE");
        m.put(0x00E0, "ELSE.IF");
        m.put(0x00E1, "END.IF");
        m.put(0x00E2, "FOR.CELL");
        m.put(0x00E3, "MEDIAN");
        m.put(0x00E4, "SUMPRODUCT");
        m.put(0x00E5, "SINH");
        m.put(0x00E6, "COSH");
        m.put(0x00E7, "TANH");
        m.put(0x00E8, "ASINH");
        m.put(0x00E9, "ACOSH");
        m.put(0x00EA, "ATANH");
        m.put(0x00EB, "DGET");
        m.put(0x00EC, "CREATE.OBJECT");
        m.put(0x00ED, "VOLATILE");
        m.put(0x00EE, "LAST.ERROR");
        m.put(0x00EF, "CUSTOM.UNDO");
        m.put(0x00F0, "CUSTOM.REPEAT");
        m.put(0x00F1, "FORMULA.CONVERT");
        m.put(0x00F2, "GET.LINK.INFO");
        m.put(0x00F3, "TEXT.BOX");
        m.put(0x00F4, "INFO");
        m.put(0x00F5, "GROUP");
        m.put(0x00F6, "GET.OBJECT");
        m.put(0x00F7, "DB");
        m.put(0x00F8, "PAUSE");
        m.put(0x00FB, "RESUME");
        m.put(0x00FC, "FREQUENCY");
        m.put(0x00FD, "ADD.TOOLBAR");
        m.put(0x00FE, "DELETE.TOOLBAR");
        m.put(0x00FF, "UserDefinedFunction");
        m.put(0x0100, "RESET.TOOLBAR");
        m.put(0x0101, "EVALUATE");
        m.put(0x0102, "GET.TOOLBAR");
        m.put(0x0103, "GET.TOOL");
        m.put(0x0104, "SPELLING.CHECK");
        m.put(0x0105, "ERROR.TYPE");
        m.put(0x0106, "APP.TITLE");
        m.put(0x0107, "WINDOW.TITLE");
        m.put(0x0108, "SAVE.TOOLBAR");
        m.put(0x0109, "ENABLE.TOOL");
        m.put(0x010A, "PRESS.TOOL");
        m.put(0x010B, "REGISTER.ID");
        m.put(0x010C, "GET.WORKBOOK");
        m.put(0x010D, "AVEDEV");
        m.put(0x010E, "BETADIST");
        m.put(0x010F, "GAMMALN");
        m.put(0x0110, "BETAINV");
        m.put(0x0111, "BINOMDIST");
        m.put(0x0112, "CHIDIST");
        m.put(0x0113, "CHIINV");
        m.put(0x0114, "COMBIN");
        m.put(0x0115, "CONFIDENCE");
        m.put(0x0116, "CRITBINOM");
        m.put(0x0117, "EVEN");
        m.put(0x0118, "EXPONDIST");
        m.put(0x0119, "FDIST");
        m.put(0x011A, "FINV");
        m.put(0x011B, "FISHER");
        m.put(0x011C, "FISHERINV");
        m.put(0x011D, "FLOOR");
        m.put(0x011E, "GAMMADIST");
        m.put(0x011F, "GAMMAINV");
        m.put(0x0120, "CEILING");
        m.put(0x0121, "HYPGEOMDIST");
        m.put(0x0122, "LOGNORMDIST");
        m.put(0x0123, "LOGINV");
        m.put(0x0124, "NEGBINOMDIST");
        m.put(0x0125, "NORMDIST");
        m.put(0x0126, "NORMSDIST");
        m.put(0x0127, "NORMINV");
        m.put(0x0128, "NORMSINV");
        m.put(0x0129, "STANDARDIZE");
        m.put(0x012A, "ODD");
        m.put(0x012B, "PERMUT");
        m.put(0x012C, "POISSON");
        m.put(0x012D, "TDIST");
        m.put(0x012E, "WEIBULL");
        m.put(0x012F, "SUMXMY2");
        m.put(0x0130, "SUMX2MY2");
        m.put(0x0131, "SUMX2PY2");
        m.put(0x0132, "CHITEST");
        m.put(0x0133, "CORREL");
        m.put(0x0134, "COVAR");
        m.put(0x0135, "FORECAST");
        m.put(0x0136, "FTEST");
        m.put(0x0137, "INTERCEPT");
        m.put(0x0138, "PEARSON");
        m.put(0x0139, "RSQ");
        m.put(0x013A, "STEYX");
        m.put(0x013B, "SLOPE");
        m.put(0x013C, "TTEST");
        m.put(0x013D, "PROB");
        m.put(0x013E, "DEVSQ");
        m.put(0x013F, "GEOMEAN");
        m.put(0x0140, "HARMEAN");
        m.put(0x0141, "SUMSQ");
        m.put(0x0142, "KURT");
        m.put(0x0143, "SKEW");
        m.put(0x0144, "ZTEST");
        m.put(0x0145, "LARGE");
        m.put(0x0146, "SMALL");
        m.put(0x0147, "QUARTILE");
        m.put(0x0148, "PERCENTILE");
        m.put(0x0149, "PERCENTRANK");
        m.put(0x014A, "MODE");
        m.put(0x014B, "TRIMMEAN");
        m.put(0x014C, "TINV");
        m.put(0x014E, "MOVIE.COMMAND");
        m.put(0x014F, "GET.MOVIE");
        m.put(0x0150, "CONCATENATE");
        m.put(0x0151, "POWER");
        m.put(0x0152, "PIVOT.ADD.DATA");
        m.put(0x0153, "GET.PIVOT.TABLE");
        m.put(0x0154, "GET.PIVOT.FIELD");
        m.put(0x0155, "GET.PIVOT.ITEM");
        m.put(0x0156, "RADIANS");
        m.put(0x0157, "DEGREES");
        m.put(0x0158, "SUBTOTAL");
        m.put(0x0159, "SUMIF");
        m.put(0x015A, "COUNTIF");
        m.put(0x015B, "COUNTBLANK");
        m.put(0x015C, "SCENARIO.GET");
        m.put(0x015D, "OPTIONS.LISTS.GET");
        m.put(0x015E, "ISPMT");
        m.put(0x015F, "DATEDIF");
        m.put(0x0160, "DATESTRING");
        m.put(0x0161, "NUMBERSTRING");
        m.put(0x0162, "ROMAN");
        m.put(0x0163, "OPEN.DIALOG");
        m.put(0x0164, "SAVE.DIALOG");
        m.put(0x0165, "VIEW.GET");
        m.put(0x0166, "GETPIVOTDATA");
        m.put(0x0167, "HYPERLINK");
        m.put(0x0168, "PHONETIC");
        m.put(0x0169, "AVERAGEA");
        m.put(0x016A, "MAXA");
        m.put(0x016B, "MINA");
        m.put(0x016C, "STDEVPA");
        m.put(0x016D, "VARPA");
        m.put(0x016E, "STDEVA");
        m.put(0x016F, "VARA");
        m.put(0x0170, "BAHTTEXT");
        m.put(0x0171, "THAIDAYOFWEEK");
        m.put(0x0172, "THAIDIGIT");
        m.put(0x0173, "THAIMONTHOFYEAR");
        m.put(0x0174, "THAINUMSOUND");
        m.put(0x0175, "THAINUMSTRING");
        m.put(0x0176, "THAISTRINGLENGTH");
        m.put(0x0177, "ISTHAIDIGIT");
        m.put(0x0178, "ROUNDBAHTDOWN");
        m.put(0x0179, "ROUNDBAHTUP");
        m.put(0x017A, "THAIYEAR");
        m.put(0x017B, "RTD");
        m.put(0x017C, "CUBEVALUE");
        m.put(0x017D, "CUBEMEMBER");
        m.put(0x017E, "CUBEMEMBERPROPERTY");
        m.put(0x017F, "CUBERANKEDMEMBER");
        m.put(0x0180, "HEX2BIN");
        m.put(0x0181, "HEX2DEC");
        m.put(0x0182, "HEX2OCT");
        m.put(0x0183, "DEC2BIN");
        m.put(0x0184, "DEC2HEX");
        m.put(0x0185, "DEC2OCT");
        m.put(0x0186, "OCT2BIN");
        m.put(0x0187, "OCT2HEX");
        m.put(0x0188, "OCT2DEC");
        m.put(0x0189, "BIN2DEC");
        m.put(0x018A, "BIN2OCT");
        m.put(0x018B, "BIN2HEX");
        m.put(0x018C, "IMSUB");
        m.put(0x018D, "IMDIV");
        m.put(0x018E, "IMPOWER");
        m.put(0x018F, "IMABS");
        m.put(0x0190, "IMSQRT");
        m.put(0x0191, "IMLN");
        m.put(0x0192, "IMLOG2");
        m.put(0x0193, "IMLOG10");
        m.put(0x0194, "IMSIN");
        m.put(0x0195, "IMCOS");
        m.put(0x0196, "IMEXP");
        m.put(0x0197, "IMARGUMENT");
        m.put(0x0198, "IMCONJUGATE");
        m.put(0x0199, "IMAGINARY");
        m.put(0x019A, "IMREAL");
        m.put(0x019B, "COMPLEX");
        m.put(0x019C, "IMSUM");
        m.put(0x019D, "IMPRODUCT");
        m.put(0x019E, "SERIESSUM");
        m.put(0x019F, "FACTDOUBLE");
        m.put(0x01A0, "SQRTPI");
        m.put(0x01A1, "QUOTIENT");
        m.put(0x01A2, "DELTA");
        m.put(0x01A3, "GESTEP");
        m.put(0x01A4, "ISEVEN");
        m.put(0x01A5, "ISODD");
        m.put(0x01A6, "MROUND");
        m.put(0x01A7, "ERF");
        m.put(0x01A8, "ERFC");
        m.put(0x01A9, "BESSELJ");
        m.put(0x01AA, "BESSELK");
        m.put(0x01AB, "BESSELY");
        m.put(0x01AC, "BESSELI");
        m.put(0x01AD, "XIRR");
        m.put(0x01AE, "XNPV");
        m.put(0x01AF, "PRICEMAT");
        m.put(0x01B0, "YIELDMAT");
        m.put(0x01B1, "INTRATE");
        m.put(0x01B2, "RECEIVED");
        m.put(0x01B3, "DISC");
        m.put(0x01B4, "PRICEDISC");
        m.put(0x01B5, "YIELDDISC");
        m.put(0x01B6, "TBILLEQ");
        m.put(0x01B7, "TBILLPRICE");
        m.put(0x01B8, "TBILLYIELD");
        m.put(0x01B9, "PRICE");
        m.put(0x01BA, "YIELD");
        m.put(0x01BB, "DOLLARDE");
        m.put(0x01BC, "DOLLARFR");
        m.put(0x01BD, "NOMINAL");
        m.put(0x01BE, "EFFECT");
        m.put(0x01BF, "CUMPRINC");
        m.put(0x01C0, "CUMIPMT");
        m.put(0x01C1, "EDATE");
        m.put(0x01C2, "EOMONTH");
        m.put(0x01C3, "YEARFRAC");
        m.put(0x01C4, "COUPDAYBS");
        m.put(0x01C5, "COUPDAYS");
        m.put(0x01C6, "COUPDAYSNC");
        m.put(0x01C7, "COUPNCD");
        m.put(0x01C8, "COUPNUM");
        m.put(0x01C9, "COUPPCD");
        m.put(0x01CA, "DURATION");
        m.put(0x01CB, "MDURATION");
        m.put(0x01CC, "ODDLPRICE");
        m.put(0x01CD, "ODDLYIELD");
        m.put(0x01CE, "ODDFPRICE");
        m.put(0x01CF, "ODDFYIELD");
        m.put(0x01D0, "RANDBETWEEN");
        m.put(0x01D1, "WEEKNUM");
        m.put(0x01D2, "AMORDEGRC");
        m.put(0x01D3, "AMORLINC");
        m.put(0x01D5, "ACCRINT");
        m.put(0x01D6, "ACCRINTM");
        m.put(0x01D7, "WORKDAY");
        m.put(0x01D8, "NETWORKDAYS");
        m.put(0x01D9, "GCD");
        m.put(0x01DA, "MULTINOMIAL");
        m.put(0x01DB, "LCM");
        m.put(0x01DC, "FVSCHEDULE");
        m.put(0x01DD, "CUBEKPIMEMBER");
        m.put(0x01DE, "CUBESET");
        m.put(0x01DF, "CUBESETCOUNT");
        m.put(0x01E0, "IFERROR");
        m.put(0x01E1, "COUNTIFS");
        m.put(0x01E2, "SUMIFS");
        m.put(0x01E3, "AVERAGEIF");
        // High-range XLM action functions (MS-XLS §2.5.198.62)
        m.put(0x8000, "BEEP");
        m.put(0x8001, "OPEN");
        m.put(0x8002, "OPEN.LINKS");
        m.put(0x8003, "CLOSE.ALL");
        m.put(0x8004, "SAVE");
        m.put(0x8005, "SAVE.AS");
        m.put(0x8006, "FILE.DELETE");
        m.put(0x8007, "PAGE.SETUP");
        m.put(0x8008, "PRINT");
        m.put(0x8009, "PRINTER.SETUP");
        m.put(0x800A, "QUIT");
        m.put(0x800B, "NEW.WINDOW");
        m.put(0x800C, "ARRANGE.ALL");
        m.put(0x800D, "WINDOW.SIZE");
        m.put(0x800E, "WINDOW.MOVE");
        m.put(0x800F, "FULL");
        m.put(0x8010, "CLOSE");
        m.put(0x8011, "RUN");
        FUNC_NAMES = Collections.unmodifiableMap(m);
    }

    private Biff12XlmFormulaDecoder() {
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Decode a BIFF12 Ptg token byte array into a formula string.
     *
     * @return formula string (no leading {@code =}), or {@code null} if decoding fails.
     */
    static String decode(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            List<PtgNode> tokens = parseTokens(data);
            if (tokens.isEmpty()) {
                return null;
            }
            Deque<PtgNode> stack = new ArrayDeque<>(tokens);
            PtgNode top = removeLast(stack);
            if (top == null) {
                return null;
            }
            return top.stringify(stack);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Ptg token parsing ────────────────────────────────────────────────────

    private static List<PtgNode> parseTokens(byte[] data) {
        Buf buf = new Buf(data);
        List<PtgNode> tokens = new ArrayList<>();
        while (buf.hasRemaining()) {
            int raw = buf.readByte();
            if (raw < 0) {
                break;
            }
            // Normalize classification bits: map 0x40-0x7F and 0x60-0x7F to base form
            int base = ((raw & 0x40) != 0) ? ((raw | 0x20) & 0x3F) : (raw & 0x3F);
            PtgNode node = readPtg(base, raw, buf);
            if (node == null) {
                break;
            }
            tokens.add(node);
        }
        return tokens;
    }

    @SuppressWarnings("fallthrough")
    private static PtgNode readPtg(int base, int raw, Buf buf) {
        switch (base) {
            // ── Zero-operand operators (no data) ──────────────────────────
            case PTG_ADD:      return new BinaryOp("+");
            case PTG_SUB:      return new BinaryOp("-");
            case PTG_MUL:      return new BinaryOp("*");
            case PTG_DIV:      return new BinaryOp("/");
            case PTG_POWER:    return new BinaryOp("^");
            case PTG_CONCAT:   return new BinaryOp("&");
            case PTG_LT:       return new BinaryOp("<");
            case PTG_LE:       return new BinaryOp("<=");
            case PTG_EQ:       return new BinaryOp("=");
            case PTG_GE:       return new BinaryOp(">=");
            case PTG_GT:       return new BinaryOp(">");
            case PTG_NE:       return new BinaryOp("<>");
            case PTG_ISECT:    return new BinaryOp(" ");
            case PTG_UNION:    return new BinaryOp(",");
            case PTG_RANGE:    return new BinaryOp(":");
            case PTG_UPLUS:    return new UnaryPrefix("+");
            case PTG_UMINUS:   return new UnaryPrefix("-");
            case PTG_PERCENT:  return new UnarySuffix("%");
            case PTG_PAREN:    return new ParenNode();
            case PTG_MISS_ARG: return new LiteralNode("");

            // ── Literals ───────────────────────────────────────────────────
            case PTG_STR: {
                int count = buf.readU16();
                if (count < 0) {
                    return null;
                }
                String s = buf.readStringN(count);
                if (s == null) {
                    return null;
                }
                return new LiteralNode("\"" + s.replace("\"", "\"\"") + "\"");
            }
            case PTG_BOOL: {
                int v = buf.readByte();
                return new LiteralNode(v != 0 ? "TRUE" : "FALSE");
            }
            case PTG_INT: {
                int v = buf.readU16();
                return new LiteralNode(v < 0 ? "0" : String.valueOf(v));
            }
            case PTG_NUM: {
                double v = buf.readDouble();
                // Format as integer when possible
                if (v == Math.floor(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
                    return new LiteralNode(String.valueOf((long) v));
                }
                return new LiteralNode(String.valueOf(v));
            }
            case PTG_ERR: {
                int code = buf.readByte();
                return new LiteralNode(errString(code));
            }

            // ── Attribute (whitespace / IF optimisation) ──────────────────
            case PTG_ATTR: {
                int flags = buf.readByte();
                int attrData = buf.readU16();
                if (flags < 0 || attrData < 0) {
                    return null;
                }
                // attr_space: prepend spaces before next token
                if ((flags & ATTR_SPACE) != 0) {
                    int spaceType = attrData & 0x00FF;
                    int count = (attrData >> 8) & 0xFF;
                    if (spaceType == 0 || spaceType == 6) {
                        return new SpaceNode(" ".repeat(count));
                    }
                }
                // Other ATTR types are flow-control — transparent to formula text
                return new TransparentNode();
            }

            // ── Cell references ────────────────────────────────────────────
            case PTG_REF:
            case PTG_REF_N: {
                long row = buf.readU32();
                int col = buf.readU16();
                if (col < 0) {
                    return null;
                }
                boolean rowRel = (col & 0x8000) != 0;
                boolean colRel = (col & 0x4000) != 0;
                col = col & 0x3FFF;
                return new LiteralNode(cellAddr(col, (int) row, !colRel, !rowRel));
            }
            case PTG_AREA:
            case PTG_AREA_N: {
                long r1 = buf.readU32();
                long r2 = buf.readU32();
                int c1 = buf.readU16();
                int c2 = buf.readU16();
                if (c1 < 0 || c2 < 0) {
                    return null;
                }
                boolean r1Rel = (c1 & 0x8000) != 0;
                boolean r2Rel = (c2 & 0x8000) != 0;
                boolean c1Rel = (c1 & 0x4000) != 0;
                boolean c2Rel = (c2 & 0x4000) != 0;
                c1 = c1 & 0x3FFF;
                c2 = c2 & 0x3FFF;
                String a = cellAddr(c1, (int) r1, !c1Rel, !r1Rel);
                String b = cellAddr(c2, (int) r2, !c2Rel, !r2Rel);
                return new LiteralNode(a + ":" + b);
            }
            case PTG_REF_3D: {
                int sheetIdx = buf.readU16();
                long row = buf.readU32();
                int col = buf.readU16();
                if (sheetIdx < 0 || col < 0) {
                    return null;
                }
                boolean rowRel = (col & 0x8000) != 0;
                boolean colRel = (col & 0x4000) != 0;
                col = col & 0x3FFF;
                return new LiteralNode("[" + sheetIdx + "]" + cellAddr(col, (int) row, !colRel, !rowRel));
            }
            case PTG_AREA_3D: {
                int sheetIdx = buf.readU16();
                long r1 = buf.readU32();
                long r2 = buf.readU32();
                int c1 = buf.readU16();
                int c2 = buf.readU16();
                if (sheetIdx < 0 || c1 < 0 || c2 < 0) {
                    return null;
                }
                boolean r1Rel = (c1 & 0x8000) != 0;
                boolean r2Rel = (c2 & 0x8000) != 0;
                boolean c1Rel = (c1 & 0x4000) != 0;
                boolean c2Rel = (c2 & 0x4000) != 0;
                c1 = c1 & 0x3FFF;
                c2 = c2 & 0x3FFF;
                String a = cellAddr(c1, (int) r1, !c1Rel, !r1Rel);
                String b = cellAddr(c2, (int) r2, !c2Rel, !r2Rel);
                return new LiteralNode("[" + sheetIdx + "]" + a + ":" + b);
            }
            case PTG_REF_ERR:
                buf.skip(6);
                return new LiteralNode("#REF!");
            case PTG_AREA_ERR:
                buf.skip(12);
                return new LiteralNode("#REF!");
            case PTG_REF_ERR_3D:
                buf.skip(8);
                return new LiteralNode("#REF!");
            case PTG_AREA_ERR_3D:
                buf.skip(14);
                return new LiteralNode("#REF!");

            // ── Names ──────────────────────────────────────────────────────
            case PTG_NAME: {
                int idx = buf.readU16();
                buf.skip(2); // reserved
                return new LiteralNode("NAME[" + idx + "]");
            }
            case PTG_NAME_X: {
                buf.skip(6); // sheet_idx(2) + name_idx(2) + reserved(2)
                return new LiteralNode("#NAMEX!");
            }

            // ── Functions ──────────────────────────────────────────────────
            case PTG_FUNC: {
                int idx = buf.readU16();
                if (idx < 0) {
                    return null;
                }
                return new FuncNode(idx, -1);
            }
            case PTG_FUNC_VAR: {
                int argc = buf.readByte();
                int idx = buf.readU16();
                if (argc < 0 || idx < 0) {
                    return null;
                }
                return new FuncNode(idx & 0x7FFF, argc & 0x7F);
            }

            // ── Array literal ──────────────────────────────────────────────
            case PTG_ARRAY: {
                int cols = buf.readByte();
                if (cols == 0) {
                    cols = 256;
                }
                int rows = buf.readU16();
                if (cols < 0 || rows < 0) {
                    return null;
                }
                StringBuilder sb = new StringBuilder("{");
                for (int r = 0; r < rows; r++) {
                    if (r > 0) {
                        sb.append(";");
                    }
                    for (int c = 0; c < cols; c++) {
                        if (c > 0) {
                            sb.append(",");
                        }
                        int flag = buf.readByte();
                        if (flag == 0x01) {
                            double v = buf.readDouble();
                            sb.append(v);
                        } else if (flag == 0x02) {
                            int len = buf.readU16();
                            String s = len >= 0 ? buf.readStringN(len) : null;
                            sb.append(s != null ? "\"" + s.replace("\"", "\"\"") + "\"" : "\"\"");
                        } else {
                            break;
                        }
                    }
                }
                sb.append("}");
                return new LiteralNode(sb.toString());
            }

            // ── Skip-only tokens ───────────────────────────────────────────
            case PTG_EXP:
            case PTG_TABLE:
                buf.skip(6);
                return new TransparentNode();

            case PTG_MEM_AREA: {
                buf.skip(4); // reserved
                int len = buf.readU16();
                if (len > 0) {
                    buf.skip(2); // rect_count
                    buf.skip(len - 2); // rects
                }
                return new TransparentNode();
            }
            case PTG_MEM_ERR: {
                buf.skip(4); // reserved
                int len = buf.readU16();
                if (len > 0) {
                    buf.skip(len);
                }
                return new TransparentNode();
            }
            case PTG_MEM_NO_MEM: {
                buf.skip(4); // reserved
                int len = buf.readU16();
                if (len > 0) {
                    buf.skip(len);
                }
                return new TransparentNode();
            }
            case PTG_MEM_FUNC:
            case PTG_MEM_AREA_N:
            case PTG_MEM_NO_MEM_N: {
                int len = buf.readU16();
                if (len > 0) {
                    buf.skip(len);
                }
                return new TransparentNode();
            }

            default:
                return new LiteralNode("?PTG" + raw + "?");
        }
    }

    // ── RPN evaluation ────────────────────────────────────────────────────────

    /** Pop from the end of the deque (top of logical stack). */
    private static PtgNode removeLast(Deque<PtgNode> stack) {
        return stack.isEmpty() ? null : stack.removeLast();
    }

    // ── Node types ────────────────────────────────────────────────────────────

    abstract static class PtgNode {
        abstract String stringify(Deque<PtgNode> stack);
    }

    static final class LiteralNode extends PtgNode {
        private final String value;

        LiteralNode(String value) {
            this.value = value;
        }

        @Override
        String stringify(Deque<PtgNode> stack) {
            return value;
        }
    }

    static final class TransparentNode extends PtgNode {
        @Override
        String stringify(Deque<PtgNode> stack) {
            // Pass through — pop the next token and return its string
            PtgNode next = removeLast(stack);
            return next != null ? next.stringify(stack) : "";
        }
    }

    static final class SpaceNode extends PtgNode {
        private final String spaces;

        SpaceNode(String spaces) {
            this.spaces = spaces;
        }

        @Override
        String stringify(Deque<PtgNode> stack) {
            PtgNode next = removeLast(stack);
            return spaces + (next != null ? next.stringify(stack) : "");
        }
    }

    static final class BinaryOp extends PtgNode {
        private final String op;

        BinaryOp(String op) {
            this.op = op;
        }

        @Override
        String stringify(Deque<PtgNode> stack) {
            PtgNode rn = removeLast(stack);
            PtgNode ln = removeLast(stack);
            String r = rn != null ? rn.stringify(stack) : "?";
            String l = ln != null ? ln.stringify(stack) : "?";
            return l + op + r;
        }
    }

    static final class UnaryPrefix extends PtgNode {
        private final String op;

        UnaryPrefix(String op) {
            this.op = op;
        }

        @Override
        String stringify(Deque<PtgNode> stack) {
            PtgNode n = removeLast(stack);
            return op + (n != null ? n.stringify(stack) : "?");
        }
    }

    static final class UnarySuffix extends PtgNode {
        private final String op;

        UnarySuffix(String op) {
            this.op = op;
        }

        @Override
        String stringify(Deque<PtgNode> stack) {
            PtgNode n = removeLast(stack);
            return (n != null ? n.stringify(stack) : "?") + op;
        }
    }

    static final class ParenNode extends PtgNode {
        @Override
        String stringify(Deque<PtgNode> stack) {
            PtgNode n = removeLast(stack);
            return "(" + (n != null ? n.stringify(stack) : "") + ")";
        }
    }

    static final class FuncNode extends PtgNode {
        private final int idx;
        private final int argc; // -1 means look up from table

        FuncNode(int idx, int argc) {
            this.idx = idx;
            this.argc = argc;
        }

        @Override
        String stringify(Deque<PtgNode> stack) {
            String name = FUNC_NAMES.getOrDefault(idx, "FUNC[0x" + Integer.toHexString(idx) + "]");
            int argCount = argc; // variable-arg case
            // For FuncPtg (fixed-arg), argCount = -1 means we can't easily determine
            // the arg count without the full spec table. Use 0 as safe default.
            if (argCount < 0) {
                argCount = 0;
            }

            // Special case: UserDefinedFunction — first arg is the function name
            if (idx == 0x00FF && argCount > 0) {
                // For UDF, the first arg on the stack is the function name token
                // We don't have a clean way to extract just the name here,
                // so pop argc args and format normally
            }

            List<String> args = new ArrayList<>(argCount);
            for (int i = 0; i < argCount; i++) {
                PtgNode arg = removeLast(stack);
                args.add(arg != null ? arg.stringify(stack) : "");
            }
            // Args were popped in reverse order — reverse to get left-to-right
            StringBuilder sb = new StringBuilder(name).append("(");
            for (int i = args.size() - 1; i >= 0; i--) {
                if (i < args.size() - 1) {
                    sb.append(", ");
                }
                sb.append(args.get(i));
            }
            sb.append(")");
            return sb.toString();
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Convert 0-based col/row to A1 notation (with optional $ anchors). */
    static String cellAddr(int col, int row, boolean colAbs, boolean rowAbs) {
        String colStr = colName(col + 1);
        String rowStr = String.valueOf(row + 1);
        return (colAbs ? "$" : "") + colStr + (rowAbs ? "$" : "") + rowStr;
    }

    private static String colName(int n) {
        StringBuilder sb = new StringBuilder();
        while (n > 0) {
            n--;
            sb.insert(0, (char) ('A' + n % 26));
            n /= 26;
        }
        return sb.toString();
    }

    private static String errString(int code) {
        switch (code) {
            case 0x00: return "#NULL!";
            case 0x07: return "#DIV/0!";
            case 0x0F: return "#VALUE!";
            case 0x17: return "#REF!";
            case 0x1D: return "#NAME?";
            case 0x24: return "#NUM!";
            case 0x2A: return "#N/A";
            default:   return "#ERR!";
        }
    }

    // ── Buf: lightweight ByteBuffer wrapper ───────────────────────────────────

    static final class Buf {
        private final ByteBuffer b;

        Buf(byte[] data) {
            b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        }

        boolean hasRemaining() {
            return b.hasRemaining();
        }

        /** Read 1 unsigned byte; returns -1 if buffer exhausted. */
        int readByte() {
            return b.hasRemaining() ? Byte.toUnsignedInt(b.get()) : -1;
        }

        /** Read 2-byte unsigned short (LE); returns -1 if buffer exhausted. */
        int readU16() {
            return b.remaining() >= 2 ? Short.toUnsignedInt(b.getShort()) : -1;
        }

        /** Read 4-byte unsigned int as long (LE); returns -1 if buffer exhausted. */
        long readU32() {
            return b.remaining() >= 4 ? Integer.toUnsignedLong(b.getInt()) : -1L;
        }

        /** Read 8-byte double (LE); returns 0 if buffer exhausted. */
        double readDouble() {
            return b.remaining() >= 8 ? b.getDouble() : 0.0;
        }

        /**
         * Read N UTF-16LE characters (2*N bytes).
         * Returns null if not enough bytes remain.
         */
        String readStringN(int n) {
            int bytes = n * 2;
            if (b.remaining() < bytes) {
                return null;
            }
            byte[] chars = new byte[bytes];
            b.get(chars);
            return new String(chars, StandardCharsets.UTF_16LE);
        }

        void skip(int n) {
            int actual = Math.min(n, b.remaining());
            if (actual > 0) {
                b.position(b.position() + actual);
            }
        }

        /** Read exactly {@code dest.length} bytes; returns number of bytes actually read. */
        int readBytes(byte[] dest) {
            int available = Math.min(dest.length, b.remaining());
            if (available > 0) {
                b.get(dest, 0, available);
            }
            return available;
        }
    }
}
