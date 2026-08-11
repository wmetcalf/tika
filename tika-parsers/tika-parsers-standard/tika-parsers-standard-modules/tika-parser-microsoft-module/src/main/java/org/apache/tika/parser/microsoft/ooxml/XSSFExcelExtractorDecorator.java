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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.apache.poi.hssf.extractor.ExcelExtractor;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagePartName;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.PackageRelationshipCollection;
import org.apache.poi.openxml4j.opc.PackageRelationshipTypes;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.HeaderFooter;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.apache.poi.xssf.usermodel.XSSFRelation;
import org.apache.poi.xssf.usermodel.helpers.HeaderFooterHelper;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.exception.RuntimeSAXException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Office;
import org.apache.tika.metadata.PageAnchoring;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.image.BoundedColorGridCollector;
import org.apache.tika.parser.microsoft.OfficeLinkMetadataUtil;
import org.apache.tika.parser.microsoft.OfficeParserConfig;
import org.apache.tika.parser.microsoft.TikaExcelDataFormatter;
import org.apache.tika.sax.XHTMLContentHandler;
import org.apache.tika.utils.ExceptionUtils;
import org.apache.tika.utils.StringUtils;
import org.apache.tika.utils.XMLReaderUtils;

public class XSSFExcelExtractorDecorator extends AbstractOOXMLExtractor {

    // Relationship types for external data sources
    private static final String EXTERNAL_LINK_RELATION =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/externalLink";
    private static final String CONNECTIONS_RELATION =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/connections";

    // Workbook-wide cell values, keyed "{sheetName}:{rowNum}:{cellRef}" (e.g.
    // "Sheet1:7:A7"). Populated by SheetTextAsHTML during the main sheet walk
    // and consumed by processXlmXmlMacroSheets so the XLM IOC scanner can
    // resolve cross-sheet references like EXEC(Sheet1!A1) — the canonical
    // XLM-dropper obfuscation where URL/payload fragments are stashed in data
    // sheets and concatenated by formulas in the macro sheet.
    //
    // BOUNDED on both axes against attacker-crafted resource-exhaustion: a
    // workbook with 1M cells × 64KB inline strings would otherwise pin 64GB
    // of heap. URL/IP/path fragments long enough to matter as IOCs are well
    // under WORKBOOK_VALUE_MAX_LEN; entries past WORKBOOK_VALUES_MAX_ENTRIES
    // are silently dropped (we still get the first N).
    static final int WORKBOOK_VALUES_MAX_ENTRIES = 200_000;
    // 1 KB is well above any legitimate URL/IP/path fragment we'd surface as
    // an IOC; the prior 4 KB cap × 200k entries = ~825 MB worst case, which is
    // ~20% of a typical worker JVM heap from a single optional capture map.
    // Drop to 1 KB → ~200 MB worst case.
    // Bounds DATA-SHEET CELL VALUES (IOC fragments) only -- the reasoning above holds
    // for those. It must NOT be applied to macrosheet FORMULAS: see XLM_FORMULA_MAX_LEN.
    static final int WORKBOOK_VALUE_MAX_LEN = 1024;
    /**
     * Per-formula character cap for XLM macrosheet formulas.
     *
     * <p>Formulas are not IOC fragments and must not share
     * {@link #WORKBOOK_VALUE_MAX_LEN}'s 1 KB budget. The canonical XLM dropper builds
     * its payload as ONE enormous concatenated FORMULA(a&amp;b&amp;c...) expression --
     * length is the signature, not an anomaly -- so the shared 1 KB cap amputated
     * exactly the documents that matter most, mid-formula, leaving a prefix that still
     * looked like a complete formula.
     *
     * <p>Measured over 250 macro-bearing workbooks from the malicious-document corpus:
     * max-formula-length p50=256, p90=2748, p95=5099, max observed=5099, and 22% of
     * those documents had at least one formula over 1 KB (215 formulas truncated).
     * 16 KB gives roughly 3x headroom over the observed ceiling.
     *
     * <p>Worst-case heap is bounded by {@link #MAX_XLM_FORMULA_TOTAL_CHARS}, NOT by
     * this value times the entry count: 200k entries at 16 KB would be 3.2 GB.
     */
    static final int XLM_FORMULA_MAX_LEN = 16_384;
    /**
     * Aggregate cap on retained macrosheet formula text, in characters.
     *
     * <p>Enforced DOCUMENT-WIDE by the macro-part loop, which carries the running total
     * across parts. That carry is load-bearing: the accumulator itself lives on the
     * per-macrosheet parser, so without it the budget resets on each of up to
     * {@link #MAX_XLM_MACRO_PARTS} parts and the real document-wide ceiling is 128x this.
     *
     * <p>This is a heap bound of last resort, not the primary one. The primary bound is
     * {@link #MAX_XLM_INPUT_BYTES}, which caps how much macrosheet input is read at all --
     * note that raising THAT knob raises the achievable retained total independently of
     * this one. Sized to hold any realistic dropper whole while still refusing an
     * attacker-crafted workbook that tries to pin the heap.
     */
    static final int MAX_XLM_FORMULA_TOTAL_CHARS = 10 * 1024 * 1024;
    /**
     * Aggregate cap on retained cell-VALUE text, in characters, document-wide.
     *
     * <p>The value map previously had only a count cap and a per-entry cap, so its ceiling
     * was their product -- 200,000 x 1,024 == ~200 MiB, an order of magnitude above the
     * formula aggregate, and unbounded in the direction that matters because BOTH factors
     * became operator-settable. Raising the per-value cap to match the formula cap (16,384)
     * for symmetry would have allowed ~3.2 GiB from a few MB of input, since a shared-string
     * reference costs ~32 bytes of input per 1,024 retained chars.
     *
     * <p>Sized as a DoS backstop, not a content limit: far above any legitimate workbook
     * (see the corpus check on this commit) so it cannot amputate real evidence, which is
     * the failure this branch exists to fix.
     */
    static final int MAX_XLM_VALUE_TOTAL_CHARS = 32 * 1024 * 1024;
    static final int MAX_XLM_MACRO_PARTS = 128;
    static final long MAX_XLM_INPUT_BYTES = 32L * 1_024 * 1_024;
    // Aggregate cap on macro text EMITTED as a MACRO entry. Raised from 1 MiB: a triage
    // tool must not silently drop macro payload, and MAX_XLM_INPUT_BYTES already bounds
    // how much can be fed in.
    private static final int MAX_XLM_MACRO_TEXT_CHARS = 10 * 1024 * 1024;
    private final java.util.Map<String, String> workbookCellValues =
            new java.util.HashMap<>();
    // Assigned in the constructor: the effective bound comes from OfficeParserConfig
    // (falling back to MAX_XLM_INPUT_BYTES), which is not available at field-init time.
    protected final XlmInputBudget xlmInputBudget;
    // Effective XLM capture bounds: OfficeParserConfig value when set (> 0), else the
    // built-in default. Resolved once per document in the constructor.
    private final int cfgFormulaMaxLen;
    private final int cfgFormulaTotalMaxChars;
    private final int cfgMacroTextMaxChars;
    private final int cfgValueMaxLen;
    private final int cfgValueTotalMaxChars;
    private final int cfgValuesMaxEntries;
    private static final String QUERY_TABLE_RELATION =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/queryTable";
    private static final String PIVOT_CACHE_DEFINITION_RELATION =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/pivotCacheDefinition";
    // Power Query stores data in customData parts
    private static final String POWER_QUERY_CONTENT_TYPE =
            "application/vnd.ms-excel.customDataProperties+xml";
    private static final String RELATION_DRAWING =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing";
    private static final String RELATION_CHART =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart";
    private static final String RELATION_HYPERLINK =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink";
    private static final String NS_DRAWING_ML =
            "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String NS_RELATIONSHIPS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String RELATION_VML_DRAWING =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/vmlDrawing";
    private static final String RELATION_COMMENTS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments";

    /**
     * Allows access to headers/footers from raw xml strings
     */
    protected static HeaderFooterHelper hfHelper = new HeaderFooterHelper();
    protected final DataFormatter formatter;
    protected final List<PackagePart> sheetParts = new ArrayList<>();
    /**
     * Pre-pass index of embedded-image absolute part name (e.g.
     * {@code /xl/media/image1.png}) → set of 1-based sheet numbers
     * referencing that image.  In XLSX, sheets reference images
     * indirectly via drawing parts (sheet → drawing → image), so the
     * pre-pass walks both hops.  Populated by
     * {@link #getMainDocumentParts()} so that
     * {@link #applyEmbeddedAnchorMetadata} can answer per-target
     * lookups even after {@code AbstractOOXMLExtractor.handleEmbeddedParts}
     * has deduped on second-and-later references.
     */
    private final Map<String, Set<Integer>> picturePages = new HashMap<>();
    protected Metadata metadata;
    protected ParseContext parseContext;

    public XSSFExcelExtractorDecorator(ParseContext context, OPCPackage pkg,
                                       Locale locale) {
        super(context, pkg);

        this.parseContext = context;

        if (locale == null) {
            formatter = new TikaExcelDataFormatter();
        } else {
            formatter = new TikaExcelDataFormatter(locale);
        }
        OfficeParserConfig officeParserConfig = context.get(OfficeParserConfig.class);
        if (officeParserConfig != null) {
            ((TikaExcelDataFormatter) formatter)
                    .setDateFormatOverride(officeParserConfig.getDateFormatOverride());
        }
        // XLM capture bounds are deployment policy, not a compile-time constant: a
        // forensics pipeline wants generous limits (losing macro payload is worse than
        // spending memory) while a general extractor may want tight ones. 0/unset keeps
        // the built-in default, so behaviour is unchanged for callers that set nothing.
        this.cfgFormulaMaxLen = positiveOr(
                officeParserConfig == null ? 0 : officeParserConfig.getXlmFormulaMaxLen(),
                XLM_FORMULA_MAX_LEN);
        this.cfgFormulaTotalMaxChars = positiveOr(
                officeParserConfig == null ? 0 : officeParserConfig.getXlmFormulaTotalMaxChars(),
                MAX_XLM_FORMULA_TOTAL_CHARS);
        this.cfgMacroTextMaxChars = positiveOr(
                officeParserConfig == null ? 0 : officeParserConfig.getXlmMacroTextMaxChars(),
                MAX_XLM_MACRO_TEXT_CHARS);
        this.cfgValueMaxLen = positiveOr(
                officeParserConfig == null ? 0 : officeParserConfig.getXlmWorkbookValueMaxLen(),
                WORKBOOK_VALUE_MAX_LEN);
        this.cfgValuesMaxEntries = positiveOr(
                officeParserConfig == null ? 0 : officeParserConfig.getXlmWorkbookValuesMaxEntries(),
                WORKBOOK_VALUES_MAX_ENTRIES);
        this.cfgValueTotalMaxChars = positiveOr(
                officeParserConfig == null ? 0 : officeParserConfig.getXlmValueTotalMaxChars(),
                MAX_XLM_VALUE_TOTAL_CHARS);
        long inputBytes = officeParserConfig == null ? 0L : officeParserConfig.getXlmMaxInputBytes();
        this.xlmInputBudget = new XlmInputBudget(
                inputBytes > 0L ? inputBytes : MAX_XLM_INPUT_BYTES);
    }

    private static int positiveOr(int configured, int builtInDefault) {
        return configured > 0 ? configured : builtInDefault;
    }

    /** Effective per-formula cap; visible to the macrosheet parser. */
    int getXlmFormulaMaxLen() {
        return cfgFormulaMaxLen;
    }

    @Override
    public MetadataExtractor getMetadataExtractor() {
        return new SAXBasedMetadataExtractor(opcPackage, parseContext);
    }

    @Override
    public void getXHTML(ContentHandler handler, Metadata metadata, ParseContext context)
            throws SAXException, IOException, TikaException {

        this.metadata = metadata;
        this.parseContext = context;
        metadata.set(Office.PROTECTED_WORKSHEET, "false");

        super.getXHTML(handler, metadata, context);
    }

    /**
     * @see org.apache.poi.xssf.extractor.XSSFExcelExtractor#getText()
     */
    @Override
    protected void buildXHTML(XHTMLContentHandler xhtml)
            throws SAXException, IOException {
        OPCPackage container = opcPackage;

        XSSFSharedStringsShim stringsShim = null;
        XSSFReader.SheetIterator iter;
        XSSFReader xssfReader;
        XSSFStylesShim stylesShim = null;
        org.apache.tika.parser.ColorAwareConfig colorAware =
                parseContext.get(org.apache.tika.parser.ColorAwareConfig.class);
        boolean colorAwareOn = colorAware != null && colorAware.isEnabled();
        BoundedColorGridCollector aggregatedColorRows =
                new BoundedColorGridCollector();
        try {
            xssfReader = new XSSFReader(container);
            iter = (XSSFReader.SheetIterator) xssfReader.getSheetsData();
        } catch (OpenXML4JException | RuntimeException e) {
            throw new IOException(e);
        }
        // Styles and shared strings are optional — if either part is missing or
        // unreadable, log to metadata and continue with degraded extraction.
        try {
            stylesShim = new XSSFStylesShim(xssfReader.getStylesData(), parseContext);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                    ExceptionUtils.getStackTrace(e));
        }
        try {
            stringsShim = new XSSFSharedStringsShim(xssfReader.getSharedStringsData(),
                    config.isConcatenatePhoneticRuns(), parseContext);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                    ExceptionUtils.getStackTrace(e));
        }
        boolean captureXlmValues = !container.getPartsByContentType(
                XSSFRelation.MACRO_SHEET_XML.getContentType()).isEmpty()
                || !container.getPartsByContentType(
                        XSSFRelation.INTL_MACRO_SHEET_XML.getContentType()).isEmpty();
        while (true) {
            try {
                if (!iter.hasNext()) {
                    break;
                }
            } catch (RuntimeException e) {
                metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                        ExceptionUtils.getStackTrace(e));
                break;
            }
            SheetTextAsHTML sheetExtractor = new SheetTextAsHTML(config, xhtml);
            sheetExtractor.colorAwareEnabled = colorAwareOn;
            PackagePart sheetPart = null;
            InputStream nextStream;
            try {
                nextStream = iter.next();
            } catch (RuntimeException e) {
                // POI can throw POIXMLException for missing sheet parts (e.g.,
                // truncated workbook references a sheet that isn't in the zip).
                // Break rather than continue — POI's iterator state may not have
                // advanced, which would cause an infinite loop.
                metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                        ExceptionUtils.getStackTrace(e));
                break;
            }
            InputStream captureStream = captureXlmValues
                    ? xlmInputBudget.observe(
                            nextStream,
                            () -> markXlmCaptureLimit(
                                    "XLM input capture limit reached"))
                    : nextStream;
            try (InputStream stream = captureStream) {
                sheetPart = iter.getSheetPart();
                // Wire the workbook-wide cell capture sink now that iter.next()
                // has run — POI's SheetIterator throws if getSheetName() is
                // called before next(), so this MUST come after the try-with
                // takes ownership of nextStream.
                if (captureXlmValues) {
                    sheetExtractor.setCellValueBounds(cfgValuesMaxEntries, cfgValueMaxLen);
                    if (valueCharBudget == null) {
                        valueCharBudget = new ValueCharBudget(cfgValueTotalMaxChars);
                    }
                    sheetExtractor.setValueCharBudget(valueCharBudget);
                    sheetExtractor.setCellValueCapture(
                            workbookCellValues, iter.getSheetName(),
                            () -> markXlmCaptureLimit(
                                    "XLM worksheet-value capture limit reached"),
                            () -> !xlmInputBudget.isLimitReached());
                }

                sheetParts.add(sheetPart);

                XSSFCommentsShim commentsShim = parseSheetComments(sheetPart);
                if (commentsShim != null && commentsShim.getNumberOfComments() > 0) {
                    metadata.set(Office.HAS_COMMENTS, true);
                }

                // Start, and output the sheet name
                xhtml.startElement("div", "class", "sheet");
                xhtml.element("h1", iter.getSheetName());

                // Extract the main sheet contents
                xhtml.startElement("table");
                xhtml.startElement("tbody");

                try {
                    processSheet(sheetExtractor, commentsShim, stylesShim, stringsShim, stream);
                } catch (SAXException e) {
                    // Truncated/malformed sheet XML — keep prior sheets and
                    // record the failure as a warning.
                    WriteLimitReachedException.throwIfWriteLimitReached(e);
                    metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                            ExceptionUtils.getStackTrace(e));
                    // Balance any <tr>/<td> left open by the partial parse so
                    // the </tbody></table></div> emitted below land in the
                    // right place.
                    sheetExtractor.closeAnyPending();
                } catch (IOException e) {
                    // Truncated stream — same risk: partial <tr>/<td> still
                    // open. Close them so the surrounding </tbody></table>
                    // stays balanced, record the failure, and keep going.
                    metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                            ExceptionUtils.getStackTrace(e));
                    sheetExtractor.closeAnyPending();
                }
                try {
                    getThreadedComments(container, sheetPart, xhtml);
                } catch (InvalidFormatException | TikaException | IOException e) {
                    //swallow
                }
                xhtml.endElement("tbody");
                xhtml.endElement("table");
            }

            // Output any headers and footers
            // (Need to process the sheet to get them, so we can't
            //  do the headers before the contents)
            for (String header : sheetExtractor.headers) {
                extractHeaderFooter(header, xhtml);
            }
            for (String footer : sheetExtractor.footers) {
                extractHeaderFooter(footer, xhtml);
            }

            // Do text held in shapes, if required
            if (config.isIncludeShapeBasedContent()) {
                processDrawings(sheetPart, xhtml);
            }

            //for now dump sheet hyperlinks at bottom of page
            //consider a double-pass of the inputstream to reunite hyperlinks with cells/textboxes
            //step 1: extract hyperlink info from bottom of page
            //step 2: process as we do now, but with cached hyperlink relationship info
            extractHyperLinks(sheetPart, xhtml);
            // All done with this sheet
            xhtml.endElement("div");
            if (colorAwareOn) {
                aggregatedColorRows.addCollector(sheetExtractor.colorCollector);
            }
        }
        OOXMLColorQRScanHelper.scan(aggregatedColorRows, parseContext, metadata,
                "xlsx_color_qr", "XLSX");

        //consider adding this back to POI
        try (InputStream wbData = xssfReader.getWorkbookData()) {
            XMLReaderUtils
                    .parseSAX(wbData, new WorkbookMetadataHandler(),
                            parseContext);
        } catch (InvalidFormatException | TikaException e) {
            //swallow
        }
        try {
            getPersons(container, metadata);
        } catch (InvalidFormatException | TikaException | IOException | SAXException e) {
            //swallow
        }

        // Extract external data sources (HIGH security risk - can hide malicious URLs)
        try {
            extractExternalDataSources(container, xhtml);
        } catch (SAXException e) {
            WriteLimitReachedException.throwIfWriteLimitReached(e);
            throw e;
        } catch (InvalidFormatException | TikaException | IOException e) {
            //swallow
        }

        // XLM (Excel 4.0) macro extraction for XML-based OOXML workbooks
        // (.xlsm / .xltm). XSSFBExcelExtractorDecorator handles the binary
        // (XLSB) side via XlmMacroEmulator + Biff12XlmMacrosheetParser; the
        // XML side reuses the same IOC vocabulary via a text-formula scanner.
        // Failures here never fail the whole parse.
        try {
            processXlmXmlMacroSheets(container, xhtml, stringsShim);
        } catch (SecurityException e) {
            throw e;
        } catch (SAXException e) {
            WriteLimitReachedException.throwIfWriteLimitReached(e);
            throw e;
        } catch (Exception e) {
            WriteLimitReachedException.throwIfWriteLimitReached(e);
            //swallow — macro extraction is opportunistic
        }
    }

    /**
     * Walk macro-sheet parts ({@code MACRO_SHEET_XML} / {@code INTL_MACRO_SHEET_XML}),
     * emit each formula to the XHTML stream so it surfaces in extracted text,
     * and run a pattern-based IOC scan against the formula text. Mirrors the
     * binary-side {@code processXlmBinaryMacroSheets} in
     * {@link XSSFBExcelExtractorDecorator}.
     *
     * @param sharedStrings  workbook shared-strings table (may be null) — passed
     *                       to the macrosheet SAX parser so {@code <c t="s">}
     *                       cells resolve to their actual string rather than the
     *                       SST index.
     */
    private void processXlmXmlMacroSheets(OPCPackage container,
                                           XHTMLContentHandler xhtml,
                                           XSSFSharedStringsShim sharedStrings)
            throws SAXException, IOException {
        List<PackagePart> macroParts = new ArrayList<>();
        macroParts.addAll(container.getPartsByContentType(
                XSSFRelation.MACRO_SHEET_XML.getContentType()));
        macroParts.addAll(container.getPartsByContentType(
                XSSFRelation.INTL_MACRO_SHEET_XML.getContentType()));
        if (macroParts.isEmpty()) return;

        metadata.set("msoffice:xlsx:has-xlm-macros", "true");

        // Accumulate formulas + values across all macro sheets so the IOC scanner
        // can resolve cross-sheet references. Sheet name (derived from the part
        // name) is the disambiguator in the per-cell key.
        //
        // Seed with the workbook-wide cell values captured during the standard
        // sheet walk — that's where XLM droppers stash URL/IP/path fragments
        // referenced by macro formulas like =Sheet1!A1. Without this, the
        // scanner only saw the macro-sheets' own cells and missed every
        // split-payload obfuscation.
        Map<String, String> allFormulas = new HashMap<>();
        Map<String, String> allValues = new HashMap<>(workbookCellValues);

        int processedMacroParts = 0;
        // Guards against two macro parts reducing to the same cell-key namespace; see below.
        // TWO sets, because the two collisions mean different things.
        //
        // Macro-part vs macro-part: SpreadsheetML never emits it, so it is an anomaly worth
        // flagging. Macro-part vs WORKSHEET name: entirely NORMAL -- measured on 66 of 1,961 real
        // xlsm documents, where a macro part and a worksheet legitimately share "sheet1". Both
        // still need a distinct key namespace, because worksheet cell values are keyed on the
        // worksheet's declared name and allValues.putAll() would otherwise let the macro part
        // overwrite the worksheet's values (exactly where split-URL fragments live). So rename in
        // both cases, but only FLAG the malformed one -- an anomaly signal that fires on ordinary
        // documents is worse than none, and a first version of this reported all 66 as malformed.
        java.util.Set<String> usedMacroSheetNames = new java.util.HashSet<>();
        java.util.Set<String> worksheetNames = new java.util.HashSet<>();
        for (String worksheetKey : workbookCellValues.keySet()) {
            int firstColon = worksheetKey.indexOf(':');
            if (firstColon > 0) {
                worksheetNames.add(worksheetKey.substring(0, firstColon));
            }
        }
        // Document-wide, NOT per-macrosheet: see the loop below.
        // long: cfgFormulaTotalMaxChars is operator-settable up to Integer.MAX_VALUE, and
        // an int accumulator wraps negative there, defeating the document-wide cap.
        long retainedFormulaCharsDoc = 0;
        for (PackagePart macroPart : macroParts) {
            if (xlmInputBudget.isLimitReached()) {
                markXlmCaptureLimit("XLM input capture limit reached");
                break;
            }
            if (retainedFormulaCharsDoc >= cfgFormulaTotalMaxChars) {
                markXlmCaptureLimit("XLM document-wide formula capture limit reached");
                break;
            }
            if (processedMacroParts >= MAX_XLM_MACRO_PARTS) {
                markXlmCaptureLimit("XML XLM macro-part limit reached");
                break;
            }
            processedMacroParts++;
            // sheetNameFromPart keeps only the BASENAME, and the per-cell key is
            // sheetName:row:col -- so /xl/a/m.xml and /xl/b/m.xml collided. Both parts were
            // charged against the document budget while allFormulas.putAll() kept only the last
            // writer, so a decoy part could delete a payload part's cells from the IOC scan with
            // no flag. Uniquify ONLY on an actual collision, so the ordinary case keeps its
            // human-readable heading and byte-identical keys.
            String sheetName = sheetNameFromPart(macroPart);
            boolean macroCollision = !usedMacroSheetNames.add(sheetName);
            boolean worksheetCollision = worksheetNames.contains(sheetName);
            if (macroCollision || worksheetCollision) {
                String disambiguated = sheetName;
                for (int dup = 2; !usedMacroSheetNames.add(disambiguated)
                        || worksheetNames.contains(disambiguated); dup++) {
                    disambiguated = sheetName + "#" + dup;
                }
                if (macroCollision) {
                    markXlmStructuralAnomaly("Two XLM macro parts share the basename \""
                            + sheetName + "\"; SpreadsheetML does not produce this. Renamed to \""
                            + disambiguated + "\" so neither part's cells are overwritten.");
                }
                sheetName = disambiguated;
            }
            xhtml.startElement("div", "class", "xlm-macrosheet");
            xhtml.element("h1", sheetName);

            // Declared outside the try so a mid-part failure can still harvest the cells
            // that completed before it. See the recovery arm below.
            XlmXmlMacrosheetParser parser = null;
            // A separate BOOLEAN, not just the message: gating the report on
            // `parseError != null` meant an exception whose getMessage() is null (plenty of
            // message-less RuntimeExceptions come out of JAXP/POI stream code) aborted the
            // part's capture with no xlm-parse-error text and NO metadata flag -- a clean-looking
            // parse again. The error indicator must not be a message string.
            boolean parseFailed = false;
            String parseError = null;
            try (InputStream is = xlmInputBudget.limit(
                    macroPart.getInputStream(),
                    () -> markXlmCaptureLimit(
                            "XLM input capture limit reached"))) {
                parser = new XlmXmlMacrosheetParser(
                                is, xhtml, sheetName, sharedStrings,
                                cfgValuesMaxEntries - allFormulas.size(),
                                cfgValuesMaxEntries - allValues.size(),
                                cfgFormulaMaxLen, cfgValueMaxLen,
                                (int) Math.max(0,
                                        cfgFormulaTotalMaxChars - retainedFormulaCharsDoc),
                                valueCharBudget != null ? valueCharBudget
                                        : (valueCharBudget =
                                                new ValueCharBudget(cfgValueTotalMaxChars)));
                parser.parse();
            } catch (SecurityException e) {
                throw e;
            } catch (SAXParseException e) {
                // MALFORMED CONTENT. One unreadable macrosheet must not destroy the WHOLE
                // workbook's results. This used to rethrow, aborting the method and thereby
                // skipping the cross-sheet IOC scan at the bottom, embedded-part extraction and
                // endDocument(). Probe: sheet1 holding =EXEC("powershell -enc EVIL") plus a
                // sheet2 truncated mid-tag reported NO exec IOC and no flag at all -- strictly
                // worse than reporting nothing, because a silent empty result reads as a clean
                // parse. A part truncated mid-tag is trivially attacker-supplied, so this was a
                // one-sheet kill switch on XLM analysis for the entire workbook.
                //
                // MUST stay above the SAXException arm: SAXParseException is a subclass, so the
                // order is what decides recover-vs-rethrow.
                WriteLimitReachedException.throwIfWriteLimitReached(e);
                parseFailed = true;
                parseError = e.getMessage();
            } catch (SAXException e) {
                // OUR OWN ContentHandler aborting -- a write limit, or a consumer refusing
                // content. It must propagate unchanged, and a test pins the exact instance
                // reaching the caller.
                //
                // This arm is narrower than it used to be. Its old justification, "a plain
                // SAXException comes from our own ContentHandler", was FALSE: parse() wrapped
                // ParserConfigurationException in a plain SAXException and setFeature /
                // getXMLReader throw SAXNotRecognized/SAXNotSupportedException straight from
                // JAXP, before any content is read -- so on a JAXP that does not recognise
                // disallow-doctype-decl this arm silently killed the IOC scan for EVERY
                // macro-bearing document in the deployment. parse() now reports setup failure
                // as IOException, which lands in the recovery arm below, so the justification
                // is finally true of what this arm actually catches.
                WriteLimitReachedException.throwIfWriteLimitReached(e);
                throw e;
            } catch (Exception e) {
                // Everything else, including the IOException parse() now uses for factory-setup
                // failure: per-part recovery, same as malformed content.
                WriteLimitReachedException.throwIfWriteLimitReached(e);
                parseFailed = true;
                parseError = e.getMessage();
            }
            if (parser != null) {
                // Harvest unconditionally -- including after a parse error, where the cells
                // completed before the malformation are exactly the evidence we want.
                // Carry the aggregate across parts. The parser's accumulator is per-sheet,
                // so without this the budget reset on each of up to MAX_XLM_MACRO_PARTS
                // parts and the document-wide total was 128x the documented bound.
                retainedFormulaCharsDoc += parser.getRetainedFormulaChars();
                allFormulas.putAll(parser.getFormulas());
                allValues.putAll(parser.getValues());
                if (parser.isTruncated()) {
                    markXlmCaptureLimit(
                            "XLM macrosheet formula or value capture limit reached");
                }
                if (parser.hasStructuralAnomaly()) {
                    // Do NOT assert "Content preserved." -- the anomaly flag is also set on cells
                    // whose formula was then refused by the aggregate cap, so the old text stated
                    // content was preserved on the very documents where the payload was dropped.
                    // isTruncated() is the authority on whether anything was withheld; say which.
                    markXlmStructuralAnomaly("XLM macrosheet " + sheetName + ": nested or "
                            + "duplicated <f>/<v>/<t>/<c> element; SpreadsheetML does not produce "
                            + "this. All occurrences retained"
                            + (parser.isTruncated()
                                    ? ", EXCEPT where a capture limit also fired -- see "
                                            + "msoffice:xlm-capture-limit-reached."
                                    : "."));
                }
                // Parity with VBA: surface this macro sheet as a first-class MACRO
                // entry (embeddedResourceType=MACRO) carrying its formula text.
                // Kept non-fatal, as it was when this lived inside the parse try/catch:
                // failing to surface the MACRO entry must not cost us the IOC scan below.
                try {
                    emitMacroText(sheetName, "text/x-excel-macro",
                            boundedMacroText(parser.getFormulas().values()), xhtml);
                } catch (TikaException | IOException e) {
                    // IOException too: emitMacroText writes to an embedded-document extractor, and
                    // letting that abort the method would cost the cross-sheet IOC scan below --
                    // the same whole-workbook loss this branch removed from the parse path.
                    xhtml.element("p", "xlm-macro-emit-error: " + e.getMessage());
                }
            }
            if (parseFailed) {
                String detail = parseError != null ? parseError : "no message";
                xhtml.element("p", "xlm-parse-error: " + detail);
                // Deliberately does NOT include sheetName or the SAX detail: those vary per part,
                // so with 128 macro parts allowed an attacker could mint 128 DISTINCT warnings and
                // fill the 16-slot cap with decoy parse errors, crowding out the later IOC-scan and
                // IOC-output diagnoses -- reproducing, with 16 cheap tricks instead of one, exactly
                // the "attacker picks which diagnosis is seen" defect the accumulate change fixed.
                // A constant string dedupes to one slot; the per-part detail is already emitted
                // into the extracted text immediately above.
                markXlmCaptureLimit("XLM macrosheet XML parse error: at least one macro part "
                        + "could not be parsed (see xlm-parse-error entries in the text)");
            }

            xhtml.endElement("div");
            if (xlmInputBudget.isLimitReached()) {
                break;
            }
        }

        // Pattern-scan everything once we've seen all sheets. Cross-sheet
        // EXEC(Sheet1!A1) lookups resolve here.
        // NOTE: the IOC output budget, the scan-length coupling and the priority ordering live on
        // branch xlm/ioc-budget-machinery, deliberately NOT here. They address a real theoretical
        // DoS, but no document in the 3,084-document corpus reaches them, and they were the source
        // of most of this work's regressions -- so they get their own review cycle rather than
        // riding along with the evasion fixes, which the corpus does verify.
        List<String> iocs = XlmXmlIocScanner.scan(allFormulas, allValues);
        if (!iocs.isEmpty()) {
            xhtml.startElement("div", "class", "xlm-iocs");
            xhtml.element("h2", "XLM Emulation");
            for (String ioc : iocs) {
                xhtml.element("p", ioc);
            }
            xhtml.endElement("div");
        }
    }

    /**
     * Document-scoped total of macro text emitted across ALL macro parts.
     *
     * <p>{@link #boundedMacroText} is called once per macro part, so measuring
     * {@code cfgMacroTextMaxChars} against a fresh per-call buffer made the real ceiling
     * N_parts x the cap -- up to {@link #MAX_XLM_MACRO_PARTS} times the documented bound.
     * This is the FIFTH per-part/per-sheet scope slip in this family (formula aggregate,
     * value aggregate, image budget, warning gate, and now macro text); the pattern is a
     * budget compared against a buffer whose lifetime is narrower than the budget's scope.
     */
    private long emittedMacroTextCharsDoc;

    private String boundedMacroText(Iterable<String> formulas) {
        StringBuilder text = new StringBuilder();
        for (String formula : formulas) {
            int separator = text.length() == 0 ? 0 : 1;
            // Charge against the DOCUMENT total, not just this part's buffer.
            long remainingLong =
                    cfgMacroTextMaxChars - emittedMacroTextCharsDoc - text.length() - separator;
            int remaining = (int) Math.min(Integer.MAX_VALUE, remainingLong);
            if (remaining <= 0) {
                markXlmCaptureLimit("XLM macro text retention limit reached");
                break;
            }
            if (separator != 0) {
                text.append('\n');
            }
            int retained = Math.min(remaining, formula.length());
            text.append(formula, 0, retained);
            if (retained < formula.length()) {
                markXlmCaptureLimit("XLM macro text retention limit reached");
                break;
            }
        }
        emittedMacroTextCharsDoc += text.length();
        return text.toString();
    }

    /**
     * Document-scoped counter for retained cell-value chars.
     *
     * <p>Deliberately NOT a field on {@link SheetTextAsHTML}: that handler is constructed
     * fresh for every sheet, so a per-instance counter would reset each sheet and the real
     * document-wide ceiling would be N_sheets x the cap. That is precisely the scope bug
     * the formula aggregate shipped with and had to be corrected for.
     */
    static final class ValueCharBudget {
        private final int max;
        private int retained;

        ValueCharBudget(int max) {
            this.max = max > 0 ? max : MAX_XLM_VALUE_TOTAL_CHARS;
        }

        /** @return true if {@code len} chars were charged; false if that would exceed the cap. */
        boolean tryRetain(int len) {
            return tryRetain(len, 0);
        }

        /**
         * Charge the NET change when this value REPLACES one already retained.
         *
         * @param replacing chars currently occupied by the entry being overwritten, credited
         *                  back. Both maps this budget guards are keyed by cell ref and
         *                  {@code put()} REPLACES, so charging gross billed the document budget
         *                  once per repetition while only one copy was kept: ~950 KB of
         *                  duplicate shared-string cells exhausted the 32 MiB document value
         *                  budget, after which every later cell value -- including the split
         *                  URL/IP fragments this map exists to capture -- was refused. Same
         *                  net-vs-gross error the formula path had, on the value path.
         */
        boolean tryRetain(int len, int replacing) {
            long credited = retained - Math.min(retained, Math.max(0, replacing));
            if (credited + (long) len > max) {
                return false;
            }
            retained = (int) Math.min(Integer.MAX_VALUE, credited + len);
            return true;
        }
    }

    /** One per document; see {@link ValueCharBudget} for why this cannot live per-sheet. */
    private ValueCharBudget valueCharBudget;

    /**
     * Structural anomaly in the macrosheet XML: something SpreadsheetML never emits, so the
     * document was hand-built. Deliberately does NOT set TRUNCATED_METADATA or the
     * capture-limit flag -- nothing was withheld, so claiming truncation would inflate the
     * signal that drives re-analysis. This is an evasion tell, not a capture shortfall.
     */
    private void markXlmStructuralAnomaly(String detail) {
        if (metadata == null) {
            return;
        }
        // Flag idempotent, DETAILS accumulate -- the same shape already fixed in
        // markXlmCaptureLimit and XlmMacroEmulator.markLimit, missed here. Returning early once
        // the flag was set meant only the FIRST anomaly's text was ever published, so a
        // basename-collision anomaly in part 1 hid the per-sheet nested-element detail for every
        // later part. Shares reportedXlmWarnings, so the total warning count stays bounded.
        if (!Boolean.parseBoolean(metadata.get("msoffice:xlm-structural-anomaly"))) {
            metadata.set("msoffice:xlm-structural-anomaly", "true");
        }
        if (detail != null && reportedXlmWarnings.size() < MAX_XLM_WARNINGS
                && reportedXlmWarnings.add(detail)) {
            metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING, detail);
        }
    }

    /** Distinct warnings already published, so each diagnosis is reported once and only once. */
    private final java.util.Set<String> reportedXlmWarnings = new java.util.LinkedHashSet<>();
    private static final int MAX_XLM_WARNINGS = 16;

    private void markXlmCaptureLimit(String warning) {
        if (metadata == null) {
            return;
        }
        // The FLAG is idempotent; the WARNING accumulates. This used to return early once the
        // flag was set, so exactly ONE reason was ever recorded per document -- and an attacker
        // picks which one fires first. A cheap padding trick that trips the input budget would
        // hide the far more serious "formula only partially decoded" or "macrosheet parse error"
        // behind it. TIKA_META_EXCEPTION_WARNING is multi-valued and written with add(), so
        // carrying every distinct reason is what the field is for. Bounded, and deduped so a
        // per-cell condition cannot repeat itself into the metadata.
        if (!Boolean.parseBoolean(metadata.get("msoffice:xlm-capture-limit-reached"))) {
            metadata.set("msoffice:xlm-capture-limit-reached", "true");
            metadata.set(TikaCoreProperties.TRUNCATED_METADATA, true);
        }
        if (warning != null && reportedXlmWarnings.size() < MAX_XLM_WARNINGS
                && reportedXlmWarnings.add(warning)) {
            metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING, warning);
        }
        // ExploitClass is a single-valued triage bucket, so first-wins is correct for IT -- but the
        // full reason list above is what carries the detail, which is why the warnings accumulate.
        if (metadata.get("ExploitClass") == null) {
            metadata.set("ExploitClass",
                    "XLM analysis incomplete; macro content may not have been analyzed");
        }
    }

    static final class XlmInputBudget {

        private final long maxInputBytes;
        private long consumedInputBytes;
        private boolean limitReached;

        XlmInputBudget(long maxInputBytes) {
            if (maxInputBytes < 0) {
                throw new IllegalArgumentException(
                        "XLM input limit must be non-negative");
            }
            this.maxInputBytes = maxInputBytes;
        }

        InputStream limit(InputStream stream, Runnable limitHandler) {
            return new BudgetedInputStream(stream, limitHandler, true);
        }

        InputStream observe(InputStream stream, Runnable limitHandler) {
            return new BudgetedInputStream(stream, limitHandler, false);
        }

        boolean isLimitReached() {
            return limitReached;
        }

        private void consume(long bytes, Runnable limitHandler) {
            if (bytes <= 0 || limitReached) {
                return;
            }
            long remaining = maxInputBytes - consumedInputBytes;
            if (bytes <= remaining) {
                consumedInputBytes += bytes;
                return;
            }
            consumedInputBytes = maxInputBytes;
            signalLimit(limitHandler);
        }

        private void signalLimit(Runnable limitHandler) {
            if (limitReached) {
                return;
            }
            limitReached = true;
            if (limitHandler != null) {
                limitHandler.run();
            }
        }

        private final class BudgetedInputStream extends InputStream {

            private final InputStream stream;
            private final Runnable limitHandler;
            private final boolean stopAtLimit;

            private BudgetedInputStream(
                    InputStream stream, Runnable limitHandler,
                    boolean stopAtLimit) {
                this.stream = stream;
                this.limitHandler = limitHandler;
                this.stopAtLimit = stopAtLimit;
            }

            @Override
            public int read() throws IOException {
                if (stopAtLimit && consumedInputBytes >= maxInputBytes) {
                    return probeForOverflow();
                }
                int value = stream.read();
                if (value >= 0) {
                    consume(1, limitHandler);
                }
                return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int length)
                    throws IOException {
                if (length == 0) {
                    return 0;
                }
                long remaining = maxInputBytes - consumedInputBytes;
                if (stopAtLimit && remaining <= 0) {
                    return probeForOverflow();
                }
                int allowed = stopAtLimit
                        ? (int) Math.min(length, remaining) : length;
                int read = stream.read(bytes, offset, allowed);
                if (read > 0) {
                    consume(read, limitHandler);
                }
                return read;
            }

            @Override
            public long skip(long length) throws IOException {
                if (length <= 0) {
                    return 0;
                }
                long remaining = maxInputBytes - consumedInputBytes;
                if (stopAtLimit && remaining <= 0) {
                    probeForOverflow();
                    return 0;
                }
                long allowed = stopAtLimit
                        ? Math.min(length, remaining) : length;
                long skipped = stream.skip(allowed);
                if (skipped > 0) {
                    consume(skipped, limitHandler);
                }
                return skipped;
            }

            @Override
            public void close() throws IOException {
                stream.close();
            }

            private int probeForOverflow() throws IOException {
                int value = stream.read();
                if (value >= 0) {
                    signalLimit(limitHandler);
                    throw new XlmInputLimitReachedException();
                }
                return -1;
            }
        }

        private static final class XlmInputLimitReachedException
                extends IOException {

            private static final long serialVersionUID = 1L;

            private XlmInputLimitReachedException() {
                super("XLM input capture limit reached");
            }
        }
    }

    /** Sheet name derived from a package-part path. Mirrors XSSFBExcelExtractorDecorator. */
    private static String sheetNameFromPart(PackagePart part) {
        String name = part.getPartName().getName();
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name;
    }

    /**
     * Extracts external data sources from the workbook including:
     * - External workbook links
     * - Data connections (database, web queries)
     * - Query tables
     */
    private void extractExternalDataSources(OPCPackage container, XHTMLContentHandler xhtml)
            throws InvalidFormatException, TikaException, IOException, SAXException {

        PackageRelationship coreDocRelationship = container.getRelationshipsByType(
                PackageRelationshipTypes.CORE_DOCUMENT).getRelationship(0);
        if (coreDocRelationship == null) {
            return;
        }
        PackagePart workbookPart = container.getPart(coreDocRelationship);
        if (workbookPart == null) {
            return;
        }

        // Extract external workbook links
        extractExternalLinks(workbookPart, xhtml);

        // Extract connections (database, ODBC, web queries)
        extractConnections(workbookPart, xhtml);

        // Extract query tables from each sheet
        for (PackagePart sheetPart : sheetParts) {
            extractQueryTables(sheetPart, xhtml);
        }

        // Detect pivot cache with external data sources
        extractPivotCacheExternalData(workbookPart, xhtml);

        // Detect Power Query / Data Mashup
        detectPowerQuery(container);
    }

    /**
     * Detects pivot cache definitions with external data sources (OLAP, databases).
     */
    private void extractPivotCacheExternalData(PackagePart workbookPart, XHTMLContentHandler xhtml)
            throws InvalidFormatException {
        PackageRelationshipCollection coll = workbookPart.getRelationshipsByType(PIVOT_CACHE_DEFINITION_RELATION);
        if (coll == null || coll.isEmpty()) {
            return;
        }
        for (PackageRelationship rel : coll) {
            try {
                PackagePart pivotCachePart = workbookPart.getRelatedPart(rel);
                if (pivotCachePart != null) {
                    PivotCacheHandler handler = new PivotCacheHandler(xhtml);
                    try (InputStream is = pivotCachePart.getInputStream()) {
                        XMLReaderUtils.parseSAX(is, handler, parseContext);
                    }
                    if (handler.hasExternalData()) {
                        metadata.set(Office.HAS_EXTERNAL_PIVOT_DATA, true);
                    }
                }
            } catch (IOException | TikaException | SAXException | IllegalArgumentException e) {
                // swallow -- POI throws IllegalArgumentException when a
                // relationship references a part missing from the package
                // (e.g. truncated files)
            }
        }
    }

    /**
     * Detects Power Query / Data Mashup presence.
     */
    private void detectPowerQuery(OPCPackage container) {
        // Power Query data is stored in customData parts with specific content type
        // or in xl/customData/ folder
        try {
            List<PackagePart> customDataParts = container.getPartsByContentType(POWER_QUERY_CONTENT_TYPE);
            if (customDataParts != null && !customDataParts.isEmpty()) {
                metadata.set(Office.HAS_POWER_QUERY, true);
            }
            // Also check for customData folder parts
            for (PackagePart part : container.getParts()) {
                String partName = part.getPartName().getName();
                if (partName.contains("/customData/") || partName.contains("/dataMashup")) {
                    metadata.set(Office.HAS_POWER_QUERY, true);
                    break;
                }
            }
        } catch (InvalidFormatException e) {
            // swallow
        }
    }

    /**
     * Extracts external workbook links from externalLink parts.
     */
    private void extractExternalLinks(PackagePart workbookPart, XHTMLContentHandler xhtml)
            throws InvalidFormatException, SAXException {
        PackageRelationshipCollection coll = workbookPart.getRelationshipsByType(EXTERNAL_LINK_RELATION);
        if (coll == null || coll.isEmpty()) {
            return;
        }
        // If we have any external link relationships, set the metadata flag
        if (coll.size() > 0) {
            metadata.set(Office.HAS_EXTERNAL_LINKS, true);
        }
        for (PackageRelationship rel : coll) {
            if (rel.getTargetMode() == TargetMode.EXTERNAL) {
                // Direct external reference
                emitExternalRef(xhtml, "externalLink", rel.getTargetURI().toString());
            } else {
                // Internal part that contains external reference - parse it
                try {
                    PackagePart externalLinkPart = workbookPart.getRelatedPart(rel);
                    if (externalLinkPart != null) {
                        ExternalLinkHandler handler = new ExternalLinkHandler(xhtml,
                                loadRelationshipTargets(externalLinkPart));
                        try (InputStream is = externalLinkPart.getInputStream()) {
                            XMLReaderUtils.parseSAX(is, handler, parseContext);
                        }
                        if (handler.hasDdeLink()) {
                            metadata.set(Office.HAS_DDE_LINKS, true);
                        }
                    }
                } catch (IOException | TikaException | IllegalArgumentException e) {
                    // swallow -- POI can throw IllegalArgumentException
                    // for malformed relationships
                }
            }
        }
    }

    /**
     * Extracts data connections from connections.xml.
     */
    private void extractConnections(PackagePart workbookPart, XHTMLContentHandler xhtml)
            throws InvalidFormatException, SAXException {
        PackageRelationshipCollection coll = workbookPart.getRelationshipsByType(CONNECTIONS_RELATION);
        if (coll == null || coll.isEmpty()) {
            return;
        }
        for (PackageRelationship rel : coll) {
            try {
                PackagePart connectionsPart = workbookPart.getRelatedPart(rel);
                if (connectionsPart != null) {
                    ConnectionsHandler handler = new ConnectionsHandler(xhtml);
                    try (InputStream is = connectionsPart.getInputStream()) {
                        XMLReaderUtils.parseSAX(is, handler, parseContext);
                    }
                    if (handler.hasConnections()) {
                        metadata.set(Office.HAS_DATA_CONNECTIONS, true);
                    }
                    if (handler.hasWebQueries()) {
                        metadata.set(Office.HAS_WEB_QUERIES, true);
                    }
                }
            } catch (IOException | TikaException | IllegalArgumentException e) {
                // swallow -- POI throws IllegalArgumentException when a
                // relationship references a part missing from the package
                // (e.g. truncated files)
            }
        }
    }

    /**
     * Extracts query table external sources.
     */
    private void extractQueryTables(PackagePart sheetPart, XHTMLContentHandler xhtml)
            throws InvalidFormatException, SAXException {
        PackageRelationshipCollection coll = sheetPart.getRelationshipsByType(QUERY_TABLE_RELATION);
        if (coll == null || coll.isEmpty()) {
            return;
        }
        for (PackageRelationship rel : coll) {
            try {
                PackagePart queryTablePart = sheetPart.getRelatedPart(rel);
                if (queryTablePart != null) {
                    try (InputStream is = queryTablePart.getInputStream()) {
                        XMLReaderUtils.parseSAX(is, new QueryTableHandler(xhtml), parseContext);
                    }
                }
            } catch (IOException | TikaException | IllegalArgumentException e) {
                // swallow -- POI throws IllegalArgumentException when a
                // relationship references a part missing from the package
                // (e.g. truncated files)
            }
        }
    }

    /**
     * Emits an external reference as an anchor element with appropriate class.
     */
    private void emitExternalRef(XHTMLContentHandler xhtml, String refType, String url)
            throws SAXException {
        if (url == null || url.isEmpty()) {
            return;
        }
        OfficeLinkMetadataUtil.addLink(metadata,
                OfficeLinkMetadataUtil.normalizeType(refType), url, null, null,
                "", "relationship", "", "");
        org.xml.sax.helpers.AttributesImpl attrs = new org.xml.sax.helpers.AttributesImpl();
        attrs.addAttribute("", "class", "class", "CDATA", "external-ref-" + refType);
        attrs.addAttribute("", "href", "href", "CDATA", url);
        xhtml.startElement("a", attrs);
        xhtml.endElement("a");
    }

    private Map<String, String> loadRelationshipTargets(PackagePart part) {
        Map<String, String> targets = new HashMap<>();
        try {
            for (PackageRelationship relationship : part.getRelationships()) {
                if (relationship.getId() != null && relationship.getTargetURI() != null) {
                    targets.put(relationship.getId(), relationship.getTargetURI().toString());
                }
            }
        } catch (InvalidFormatException e) {
            // Malformed relationships should not abort extraction.
        }
        return targets;
    }

    /**
     * Handler for parsing externalLink XML to extract external workbook references.
     */
    private class ExternalLinkHandler extends DefaultHandler {
        private final XHTMLContentHandler xhtml;
        private final Map<String, String> linkedRelationships;
        private boolean foundDdeLink = false;

        ExternalLinkHandler(XHTMLContentHandler xhtml, Map<String, String> linkedRelationships) {
            this.xhtml = xhtml;
            this.linkedRelationships = linkedRelationships;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts)
                throws SAXException {
            // Look for externalBook element with r:id attribute
            if ("externalBook".equals(localName)) {
                String rId = atts.getValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
                String url = linkedRelationships.get(rId);
                if (url != null) {
                    emitExternalRef(xhtml, "externalWorkbook", url);
                }
            }
            // Look for file element with href attribute (older format)
            if ("file".equals(localName)) {
                String href = atts.getValue("href");
                if (href != null && !href.isEmpty()) {
                    emitExternalRef(xhtml, "externalWorkbook", href);
                }
            }
            // Look for oleLink with r:id (OLE links to external files)
            if ("oleLink".equals(localName)) {
                String rId = atts.getValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
                    if (rId != null) {
                        String url = linkedRelationships.get(rId);
                        emitExternalRef(xhtml, "oleLink",
                                url == null || url.isEmpty() ? "relationship:" + rId : url);
                    }
            }
            // DDE links - security risk: can execute commands
            if ("ddeLink".equals(localName)) {
                foundDdeLink = true;
                String ddeService = atts.getValue("ddeService");
                String ddeTopic = atts.getValue("ddeTopic");
                if (ddeService != null || ddeTopic != null) {
                    String ddeRef = (ddeService != null ? ddeService : "") + "|" +
                            (ddeTopic != null ? ddeTopic : "");
                    emitExternalRef(xhtml, "ddeLink", ddeRef);
                }
            }
        }

        boolean hasDdeLink() {
            return foundDdeLink;
        }
    }

    /**
     * Handler for parsing connections.xml to extract external data connections.
     */
    private class ConnectionsHandler extends DefaultHandler {
        private final XHTMLContentHandler xhtml;
        private boolean foundConnection = false;
        private boolean foundWebQuery = false;

        ConnectionsHandler(XHTMLContentHandler xhtml) {
            this.xhtml = xhtml;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts)
                throws SAXException {
            if ("connection".equals(localName)) {
                foundConnection = true;
            }
            // Database connection string
            if ("dbPr".equals(localName)) {
                String connection = atts.getValue("connection");
                if (connection != null && !connection.isEmpty()) {
                    emitExternalRef(xhtml, "dbConnection", connection);
                }
            }
            // Web query
            if ("webPr".equals(localName)) {
                foundWebQuery = true;
                String url = atts.getValue("url");
                if (url != null && !url.isEmpty()) {
                    emitExternalRef(xhtml, "webQuery", url);
                }
            }
            // ODBC connection
            if ("olapPr".equals(localName)) {
                String connection = atts.getValue("connection");
                if (connection != null && !connection.isEmpty()) {
                    emitExternalRef(xhtml, "olapConnection", connection);
                }
            }
            // Text file import
            if ("textPr".equals(localName)) {
                String sourceFile = atts.getValue("sourceFile");
                if (sourceFile != null && !sourceFile.isEmpty()) {
                    emitExternalRef(xhtml, "textFileImport", sourceFile);
                }
            }
        }

        boolean hasConnections() {
            return foundConnection;
        }

        boolean hasWebQueries() {
            return foundWebQuery;
        }
    }

    /**
     * Handler for parsing queryTable XML to extract web query sources.
     */
    private class QueryTableHandler extends DefaultHandler {
        private final XHTMLContentHandler xhtml;

        QueryTableHandler(XHTMLContentHandler xhtml) {
            this.xhtml = xhtml;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts)
                throws SAXException {
            if ("queryTable".equals(localName)) {
                String connectionId = atts.getValue("connectionId");
                // Connection details are in connections.xml
            }
            // Web query table refresh
            if ("queryTableRefresh".equals(localName)) {
                // Contains refresh settings
            }
        }
    }

    /**
     * Handler for parsing pivotCacheDefinition XML to detect external data sources.
     */
    private class PivotCacheHandler extends DefaultHandler {
        private final XHTMLContentHandler xhtml;
        private boolean hasExternalData = false;

        PivotCacheHandler(XHTMLContentHandler xhtml) {
            this.xhtml = xhtml;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts)
                throws SAXException {
            // cacheSource with type="external" indicates external data
            if ("cacheSource".equals(localName)) {
                String type = atts.getValue("type");
                if ("external".equals(type) || "consolidation".equals(type)) {
                    hasExternalData = true;
                }
            }
            // worksheetSource can have external references
            if ("worksheetSource".equals(localName)) {
                String ref = atts.getValue("ref");
                String sheet = atts.getValue("sheet");
                String rId = atts.getValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
                // If there's a relationship ID, it likely points to external workbook
                if (rId != null) {
                    hasExternalData = true;
                }
            }
            // consolidation source (multiple ranges, possibly external)
            if ("consolidation".equals(localName) || "rangeSets".equals(localName)) {
                hasExternalData = true;
            }
        }

        boolean hasExternalData() {
            return hasExternalData;
        }
    }

    private void getThreadedComments(OPCPackage container, PackagePart sheetPart, XHTMLContentHandler xhtml) throws TikaException,
            InvalidFormatException, SAXException, IOException {
        //consider caching the person id -> person names in getPersons and injecting that into the xhtml per comment?
        PackageRelationshipCollection coll = sheetPart.getRelationshipsByType(OPCPackageWrapper.THREADED_COMMENT_RELATION);
        if (coll == null || coll.isEmpty()) {
            return;
        }
        for (PackageRelationship rel : coll) {
            PackagePart threadedCommentPart = sheetPart.getRelatedPart(rel);
            if (threadedCommentPart == null) {
                continue;
            }
            try (InputStream is = threadedCommentPart.getInputStream()) {
                XMLReaderUtils.parseSAX(is, new ThreadedCommentHandler(xhtml), parseContext);
            }
        }
    }

    private void getPersons(OPCPackage container, Metadata metadata) throws TikaException, InvalidFormatException,
            IOException, SAXException {
        PackageRelationship coreDocRelationship = container.getRelationshipsByType(
                PackageRelationshipTypes.CORE_DOCUMENT).getRelationship(0);
        if (coreDocRelationship == null) {
            return;
        }
        // Get the part that holds the workbook
        PackagePart workbookPart = container.getPart(coreDocRelationship);
        if (workbookPart == null) {
            return;
        }
        PackageRelationshipCollection coll = workbookPart.getRelationshipsByType(OPCPackageWrapper.PERSON_RELATION);
        if (coll == null) {
            return;
        }
        for (PackageRelationship rel : coll) {
            PackagePart personsPart = workbookPart.getRelatedPart(rel);
            if (personsPart == null) {
                continue;
            }
            try (InputStream is = personsPart.getInputStream()) {
                XMLReaderUtils.parseSAX(is, new CommentPersonHandler(metadata), parseContext);
            }
        }
    }

    protected void extractHyperLinks(PackagePart sheetPart, XHTMLContentHandler xhtml)
            throws SAXException {
        try {
            boolean first = true;
            for (PackageRelationship rel : sheetPart
                    .getRelationshipsByType(RELATION_HYPERLINK)) {
                if (!first) {
                    xhtml.characters(" ");
                }
                first = false;
                xhtml.startElement("a", "href", rel.getTargetURI().toString());
                xhtml.characters(rel.getTargetURI().toString());
                xhtml.endElement("a");
            }
        } catch (InvalidFormatException e) {
            //swallow
        }
    }

    protected void extractHeaderFooter(String hf, XHTMLContentHandler xhtml) throws SAXException {
        String content = ExcelExtractor._extractHeaderFooter(new HeaderFooterFromString(hf));
        if (content.length() > 0) {
            xhtml.element("p", content);
        }
    }

    protected void processDrawings(PackagePart sheetPart, XHTMLContentHandler xhtml)
            throws SAXException {
        try {
            for (PackageRelationship rel : sheetPart
                    .getRelationshipsByType(RELATION_DRAWING)) {
                if (rel.getTargetMode() != TargetMode.INTERNAL) {
                    continue;
                }
                PackagePartName relName =
                        PackagingURIHelper.createPartName(rel.getTargetURI());
                PackagePart drawingPart = rel.getPackage().getPart(relName);
                if (drawingPart == null) {
                    continue;
                }
                Map<String, String> drawingHyperlinks =
                        loadLinkedRelationships(drawingPart, false, metadata);
                // SAX-parse drawing XML for shape text and hyperlinks
                try (InputStream is = drawingPart.getInputStream()) {
                    XMLReaderUtils.parseSAX(is,
                            new DrawingShapeHandler(xhtml, drawingHyperlinks),
                            parseContext);
                } catch (IOException | TikaException e) {
                    //swallow
                }
                // Process diagram and chart data through drawing part relationships
                handleGeneralTextContainingPart(
                        AbstractOOXMLExtractor.RELATION_DIAGRAM_DATA,
                        "diagram-data", drawingPart, metadata,
                        new OOXMLWordAndPowerPointTextHandler(
                                new OOXMLTikaBodyPartHandler(xhtml),
                                new HashMap<>()));
                handleGeneralTextContainingPart(RELATION_CHART, "chart",
                        drawingPart, metadata,
                        new OOXMLWordAndPowerPointTextHandler(
                                new OOXMLTikaBodyPartHandler(xhtml),
                                new HashMap<>()));
            }
        } catch (InvalidFormatException e) {
            //swallow
        }
    }

    /**
     * SAX handler for drawing XML that extracts shape text and hyperlinks
     * without requiring XMLBeans or the POI usermodel (XSSFShape, etc.).
     */
    private static class DrawingShapeHandler extends DefaultHandler {

        private final XHTMLContentHandler xhtml;
        private final Map<String, String> hyperlinks;

        private boolean inTxBody;
        private boolean inT;
        private final StringBuilder textBuffer = new StringBuilder();
        private final StringBuilder shapeText = new StringBuilder();

        DrawingShapeHandler(XHTMLContentHandler xhtml, Map<String, String> hyperlinks) {
            this.xhtml = xhtml;
            this.hyperlinks = hyperlinks;
        }

        @Override
        public void startElement(String uri, String localName, String qName,
                                 Attributes atts) throws SAXException {
            if ("txBody".equals(localName)) {
                inTxBody = true;
                shapeText.setLength(0);
            } else if ("t".equals(localName) && inTxBody) {
                inT = true;
                textBuffer.setLength(0);
            } else if ("hlinkClick".equals(localName) || "hlinkHover".equals(localName)) {
                String rId = atts.getValue(NS_RELATIONSHIPS, "id");
                if (rId == null) {
                    // try non-namespace-aware fallback
                    rId = atts.getValue("r:id");
                }
                if (rId != null) {
                    String url = hyperlinks.get(rId);
                    if (url != null) {
                        xhtml.startElement("a", "href", url);
                        xhtml.characters(url);
                        xhtml.endElement("a");
                    }
                }
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName)
                throws SAXException {
            if ("t".equals(localName) && inT) {
                inT = false;
                shapeText.append(textBuffer);
            } else if ("p".equals(localName) && inTxBody &&
                    shapeText.length() > 0) {
                shapeText.append('\n');
            } else if ("txBody".equals(localName)) {
                inTxBody = false;
                String text = shapeText.toString().trim();
                if (!text.isEmpty()) {
                    xhtml.element("p", text);
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inT) {
                textBuffer.append(ch, start, length);
            }
        }
    }

    public void processSheet(TikaSheetContentsHandler sheetContentsHandler,
                             XSSFCommentsShim commentsShim,
                             XSSFStylesShim stylesShim, XSSFSharedStringsShim stringsShim,
                             InputStream sheetInputStream) throws IOException, SAXException {
        try {
            XSSFSheetInterestingPartsCapturer handler = new XSSFSheetInterestingPartsCapturer(
                    new TikaSheetXMLHandler(stylesShim, commentsShim, stringsShim,
                            sheetContentsHandler, formatter, false));
            XMLReaderUtils.parseSAX(sheetInputStream, handler, parseContext);
            sheetInputStream.close();

            if (handler.hasProtection) {
                metadata.set(Office.PROTECTED_WORKSHEET, true);
            }
            if (handler.hasHiddenColumn) {
                metadata.set(Office.HAS_HIDDEN_COLUMNS, true);
            }
            if (handler.hasHiddenRow) {
                metadata.set(Office.HAS_HIDDEN_ROWS, true);
            }
        } catch (TikaException e) {
            throw new RuntimeException("SAX parser appears to be broken - " + e.getMessage());
        }
    }

    /**
     * Parse the comments XML for a sheet part via SAX, avoiding XMLBeans.
     */
    private XSSFCommentsShim parseSheetComments(PackagePart sheetPart) {
        try {
            PackageRelationshipCollection rels =
                    sheetPart.getRelationshipsByType(RELATION_COMMENTS);
            if (rels.isEmpty()) {
                return null;
            }
            PackageRelationship rel = rels.getRelationship(0);
            PackagePartName partName =
                    PackagingURIHelper.createPartName(rel.getTargetURI());
            PackagePart commentsPart = rel.getPackage().getPart(partName);
            if (commentsPart == null) {
                return null;
            }
            try (InputStream is = commentsPart.getInputStream()) {
                return new XSSFCommentsShim(is, parseContext);
            }
        } catch (InvalidFormatException | IOException | TikaException | SAXException e) {
            //swallow — comments are not critical
            return null;
        }
    }


    /**
     * In Excel files, sheets have things embedded in them,
     * and sheet drawings which have the images
     */
    @Override
    protected List<PackagePart> getMainDocumentParts() throws TikaException {
        List<PackagePart> parts = new ArrayList<>();
        // The sheet order in sheetParts mirrors the workbook's sheet
        // ordering (populated in buildXHTML), so the index here is the
        // 1-based sheet number.
        int sheetNumber = 0;
        for (PackagePart part : sheetParts) {
            sheetNumber++;
            // Add the sheet
            parts.add(part);

            // If it has drawings, return those too
            try {
                for (PackageRelationship rel : part
                        .getRelationshipsByType(RELATION_DRAWING)) {
                    if (rel.getTargetMode() == TargetMode.INTERNAL) {
                        PackagePartName relName =
                                PackagingURIHelper.createPartName(rel.getTargetURI());
                        PackagePart drawingPart = rel.getPackage().getPart(relName);
                        parts.add(drawingPart);
                        recordImagesOnSheet(drawingPart, sheetNumber);
                    }
                }
                for (PackageRelationship rel : part
                        .getRelationshipsByType(RELATION_VML_DRAWING)) {
                    if (rel.getTargetMode() == TargetMode.INTERNAL) {
                        PackagePartName relName =
                                PackagingURIHelper.createPartName(rel.getTargetURI());
                        PackagePart vmlPart = rel.getPackage().getPart(relName);
                        parts.add(vmlPart);
                        recordImagesOnSheet(vmlPart, sheetNumber);
                    }
                }
            } catch (InvalidFormatException e) {
                throw new TikaException("Broken OOXML file", e);
            }
        }

        //add main document so that macros can be extracted
        //by AbstractOOXMLExtractor
        parts.addAll(opcPackage
                .getPartsByRelationshipType(PackageRelationshipTypes.CORE_DOCUMENT));

        return parts;
    }

    /**
     * Scan each sheet XML for {@code <oleObject>} elements to capture {@code progId}
     * and {@code autoLoad} attributes.  {@code autoLoad="1"} means the object
     * executes immediately on open — a primary mechanism for drive-by PE drops.
     * Non-standard ProgIDs (not matching known Office applications) are flagged
     * as suspicious regardless of autoLoad.
     */
    @Override
    protected java.util.Map<String, EmbeddedPartMetadata> getEmbeddedPartMetadataMap() {
        java.util.Map<String, EmbeddedPartMetadata> result = new java.util.HashMap<>();
        for (PackagePart sheetPart : sheetParts) {
            try {
                scanSheetForOleObjects(sheetPart, result);
            } catch (Exception ignore) {
                // non-fatal — metadata extraction failure should not abort parsing
            }
        }
        return result;
    }

    // Known legitimate ProgIDs that should NOT be flagged as suspicious.
    // Anything else (random strings, obfuscated names) is flagged.
    private static final java.util.Set<String> KNOWN_PROGIDS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "Word.Document.12", "Word.Document.8", "Word.DocumentMacroEnabled.12",
                    "Excel.Sheet.12", "Excel.Sheet.8", "Excel.SheetMacroEnabled.12",
                    "Excel.SheetBinaryMacroEnabled.12", "Excel.Addin", "Excel.Addin.12",
                    "PowerPoint.Show.12", "PowerPoint.Show.8",
                    "PowerPoint.ShowMacroEnabled.12",
                    "Package", "Package2",
                    "Word.Picture.8", "MSGraph.Chart.8",
                    "Equation.3", "Equation.2",
                    "AcroExch.Document", "AcroExch.Document.11"
            ));

    private void scanSheetForOleObjects(PackagePart sheetPart,
                                        java.util.Map<String, EmbeddedPartMetadata> result)
            throws Exception {
        org.xml.sax.helpers.DefaultHandler handler = new org.xml.sax.helpers.DefaultHandler() {
            @Override
            public void startElement(String uri, String localName, String qName,
                                     org.xml.sax.Attributes attrs) {
                if (!"oleObject".equals(localName) && !"oleObject".equals(qName)) {
                    return;
                }
                String rId = attrs.getValue("r:id");
                if (rId == null) {
                    rId = attrs.getValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
                }
                if (rId == null) {
                    return;
                }
                String progId = attrs.getValue("progId");
                String autoLoad = attrs.getValue("autoLoad");
                boolean isAutoLoad = "1".equals(autoLoad) || "true".equalsIgnoreCase(autoLoad);
                boolean isSuspiciousProgId = progId != null && !progId.isEmpty()
                        && !KNOWN_PROGIDS.contains(progId);
                if (isAutoLoad || isSuspiciousProgId) {
                    EmbeddedPartMetadata epm = result.computeIfAbsent(rId,
                            k -> new EmbeddedPartMetadata(null));
                    if (progId != null) {
                        epm.setProgId(progId);
                    }
                    epm.setAutoLoad(isAutoLoad);
                    epm.setSuspiciousProgId(isSuspiciousProgId);
                }
            }
        };
        try (java.io.InputStream is = sheetPart.getInputStream()) {
            XMLReaderUtils.parseSAX(is, handler, parseContext);
        }
    }

    /**
     * Turns formatted sheet events into HTML
     */
    protected static class SheetTextAsHTML
            implements TikaSheetContentsHandler, SheetContentsHandler {
        private final boolean includeHeadersFooters;
        private final boolean includeMissingRows;
        protected List<String> headers;
        protected List<String> footers;
        private XHTMLContentHandler xhtml;
        private int lastSeenRow = -1;
        private int lastSeenCol = -1;
        private String pendingFontColor;
        // Per-sheet color rows: each row is a list of luma values, one per
        // non-empty cell. Populated only when color-aware mode is enabled.
        protected final BoundedColorGridCollector colorCollector =
                new BoundedColorGridCollector();
        protected boolean colorAwareEnabled;
        // XLM cross-sheet value capture — see workbookCellValues javadoc on
        // the enclosing class. Both fields are null on workbooks where the
        // outer extractor found no XLM macro-sheet parts to analyze.
        private java.util.Map<String, String> cellValueSink;
        private String captureSheetName;
        private Runnable cellValueCaptureLimitHandler;
        private BooleanSupplier cellValueCaptureAllowed = () -> true;
        private boolean cellValueCaptureLimitSignaled;
        private int currentRowForCapture = -1;
        // Track open <tr>/<td> so the outer catch can emit balanced closes
        // when processSheet throws part-way through a row (e.g., a malformed
        // sheet XML). Without this, the outer code would emit </tbody></table>
        // while <tr> (or <td>) was still on the stack, producing malformed XHTML.
        private boolean rowOpen;
        private boolean cellOpen;

        protected SheetTextAsHTML(OfficeParserConfig config, XHTMLContentHandler xhtml) {
            this.includeHeadersFooters = config.isIncludeHeadersAndFooters();
            this.includeMissingRows = config.isIncludeMissingRows();
            this.xhtml = xhtml;
            headers = new ArrayList<>();
            footers = new ArrayList<>();
        }

        /**
         * Capture per-cell resolved values into a shared workbook map.
         * Used by the XLM IOC scanner to resolve cross-sheet cell references
         * (e.g. {@code EXEC(Sheet1!A1)}) without re-parsing each sheet.
         */
        protected void setCellValueCapture(java.util.Map<String, String> sink, String sheetName,
                                           Runnable limitHandler) {
            setCellValueCapture(sink, sheetName, limitHandler, () -> true);
        }

        protected void setCellValueCapture(java.util.Map<String, String> sink,
                                           String sheetName,
                                           Runnable limitHandler,
                                           BooleanSupplier captureAllowed) {
            this.cellValueSink = sink;
            this.captureSheetName = sheetName;
            this.cellValueCaptureLimitHandler = limitHandler;
            this.cellValueCaptureAllowed = captureAllowed;
        }

        // This is a STATIC nested class, so it cannot read the decorator's per-document
        // effective caps; the enclosing instance pushes them in. Defaults keep existing
        // callers (and tests that construct this directly) behaving as before.
        private ValueCharBudget valueCharBudget;
        private int valuesMaxEntries = WORKBOOK_VALUES_MAX_ENTRIES;
        private int valueMaxLen = WORKBOOK_VALUE_MAX_LEN;

        /** Shared, document-scoped -- see {@link ValueCharBudget}. */
        protected void setValueCharBudget(ValueCharBudget budget) {
            this.valueCharBudget = budget;
        }

        protected void setCellValueBounds(int valuesMaxEntries, int valueMaxLen) {
            if (valuesMaxEntries > 0) {
                this.valuesMaxEntries = valuesMaxEntries;
            }
            if (valueMaxLen > 0) {
                this.valueMaxLen = valueMaxLen;
            }
        }

        public void startRow(int rowNum) {
            try {
                // Missing rows, if desired, with a single empty row
                if (includeMissingRows && rowNum > (lastSeenRow + 1)) {
                    for (int rn = lastSeenRow + 1; rn < rowNum; rn++) {
                        xhtml.startElement("tr");
                        rowOpen = true;
                        xhtml.startElement("td");
                        cellOpen = true;
                        xhtml.endElement("td");
                        cellOpen = false;
                        xhtml.endElement("tr");
                        rowOpen = false;
                    }
                }

                // Track row number for the cell-value capture map (XLM scanner)
                currentRowForCapture = rowNum;

                // Start the new row
                xhtml.startElement("tr");
                rowOpen = true;
                lastSeenCol = -1;
                if (colorAwareEnabled) {
                    colorCollector.startRow();
                }
            } catch (SAXException e) {
                //swallow
                throw new RuntimeSAXException(e);
            }

        }

        public void endRow(int rowNum) {
            try {
                xhtml.endElement("tr");
                rowOpen = false;
            } catch (SAXException e) {
                throw new RuntimeSAXException(e);
            }
            if (colorAwareEnabled) {
                colorCollector.finishRow();
            }
        }

        @Override
        public void cellStyle(String fontColorHex) {
            this.pendingFontColor = fontColorHex;
        }

        /**
         * Closes any pending {@code <tr>} or {@code <td>} that was opened
         * before a {@link SAXException} interrupted sheet processing. Safe to
         * call when nothing is open.
         */
        void closeAnyPending() throws SAXException {
            if (cellOpen) {
                xhtml.endElement("td");
                cellOpen = false;
            }
            if (rowOpen) {
                xhtml.endElement("tr");
                rowOpen = false;
            }
            colorCollector.abandonCurrentRow();
        }

        public void cell(String cellRef, String formattedValue,
                          XSSFCommentsShim.CommentData comment) {
            try {
                // Capture for the XLM cross-sheet scanner before any "missing
                // cell" gap-filling — gap cells have no value to record.
                // Bounded: skip past WORKBOOK_VALUES_MAX_ENTRIES, truncate
                // per-value past WORKBOOK_VALUE_MAX_LEN. See javadoc on
                // workbookCellValues for the threat model.
                if (cellValueSink != null && captureSheetName != null
                        && cellValueCaptureAllowed.getAsBoolean()
                        && cellRef != null && formattedValue != null && !formattedValue.isEmpty()) {
                    String key =
                            captureSheetName + ":" + currentRowForCapture + ":" + cellRef;
                    if (cellValueSink.size() >= valuesMaxEntries
                            && !cellValueSink.containsKey(key)) {
                        signalCellValueCaptureLimit();
                    } else {
                        if (formattedValue.length() > valueMaxLen) {
                            signalCellValueCaptureLimit();
                        }
                        String v = formattedValue.length() > valueMaxLen
                                ? formattedValue.substring(0, valueMaxLen)
                                : formattedValue;
                        // Aggregate guard. Without it the ceiling was entries x per-entry,
                        // both of which are operator-settable, so there was no ceiling.
                        // Credit the entry this replaces -- cellValueSink.put() overwrites on
                        // a repeated cell ref, and gross charging let duplicates drain the
                        // document budget and starve the real IOC fragments.
                        String priorCellValue = cellValueSink.get(key);
                        if (valueCharBudget != null
                                && !valueCharBudget.tryRetain(v.length(),
                                        priorCellValue == null ? 0 : priorCellValue.length())) {
                            signalCellValueCaptureLimit();
                        } else {
                            cellValueSink.put(key, v);
                        }
                    }
                }

                // Handle any missing cells
                int colNum =
                        (cellRef == null) ? lastSeenCol + 1 : (new CellReference(cellRef)).getCol();
                for (int cn = lastSeenCol + 1; cn < colNum; cn++) {
                    xhtml.startElement("td");
                    cellOpen = true;
                    xhtml.endElement("td");
                    cellOpen = false;
                }
                lastSeenCol = colNum;

                // Start this cell
                xhtml.startElement("td");
                cellOpen = true;

                // Main cell contents
                if (formattedValue != null) {
                    xhtml.characters(formattedValue);
                }

                if (colorAwareEnabled && formattedValue != null
                        && !formattedValue.isEmpty()) {
                    int luma = lumaForHex(pendingFontColor);
                    colorCollector.addCell(luma);
                }

                // Comments
                if (comment != null) {
                    xhtml.startElement("br");
                    xhtml.endElement("br");
                    xhtml.characters(comment.getAuthor());
                    xhtml.characters(": ");
                    xhtml.characters(comment.getText());
                }

                xhtml.endElement("td");
                cellOpen = false;
            } catch (SAXException e) {
                throw new RuntimeSAXException(e);
            }
        }

        private void signalCellValueCaptureLimit() {
            if (cellValueCaptureLimitSignaled) {
                return;
            }
            cellValueCaptureLimitSignaled = true;
            if (cellValueCaptureLimitHandler != null) {
                cellValueCaptureLimitHandler.run();
            }
        }

        /**
         * Bridge for POI's {@link SheetContentsHandler} interface, used by the
         * XLSB (binary) path via {@link org.apache.poi.xssf.binary.XSSFBSheetHandler}.
         */
        public void cell(String cellRef, String formattedValue, XSSFComment comment) {
            XSSFCommentsShim.CommentData commentData = null;
            if (comment != null) {
                String text = comment.getString() != null ?
                        comment.getString().getString() : "";
                commentData = new XSSFCommentsShim.CommentData(
                        comment.getAuthor(), text);
            }
            cell(cellRef, formattedValue, commentData);
        }

        public void headerFooter(String text, boolean isHeader, String tagName) {
            if (!includeHeadersFooters) {
                return;
            }
            if (isHeader) {
                headers.add(text);
            } else {
                footers.add(text);
            }
        }

        @Override
        public void endSheet() {
            // no-op — satisfies both TikaSheetContentsHandler and SheetContentsHandler
        }

        /** BT.601 luma from a 6-char RGB hex string. Null/invalid → 0 (dark). */
        private static int lumaForHex(String hex) {
            if (hex == null || hex.length() != 6) {
                return 0;
            }
            try {
                int r = Integer.parseInt(hex.substring(0, 2), 16);
                int g = Integer.parseInt(hex.substring(2, 4), 16);
                int b = Integer.parseInt(hex.substring(4, 6), 16);
                return org.apache.tika.parser.image.ColorGridQRDecoder.luma(r, g, b);
            } catch (NumberFormatException ex) {
                return 0;
            }
        }
    }

    protected static class HeaderFooterFromString implements HeaderFooter {
        private String text;

        protected HeaderFooterFromString(String text) {
            this.text = text;
        }

        public String getCenter() {
            return hfHelper.getCenterSection(text);
        }

        public void setCenter(String paramString) {
        }

        public String getLeft() {
            return hfHelper.getLeftSection(text);
        }

        public void setLeft(String paramString) {
        }

        public String getRight() {
            return hfHelper.getRightSection(text);
        }

        public void setRight(String paramString) {
        }
    }

    /**
     * Captures information on interesting tags, whilst
     * delegating the main work to the formatting handler
     */
    protected static class XSSFSheetInterestingPartsCapturer extends DefaultHandler {
        private ContentHandler delegate;
        private boolean hasProtection = false;
        private boolean hasHiddenRow = false;
        private boolean hasHiddenColumn = false;

        protected XSSFSheetInterestingPartsCapturer(ContentHandler delegate) {
            this.delegate = delegate;
        }

        public void startElement(String uri, String localName, String qName, Attributes atts)
                throws SAXException {
            if ("sheetProtection".equals(qName)) {
                hasProtection = true;
            }
            if (! hasHiddenRow && "row".equals(localName)) {
                String v = atts.getValue("hidden");
                if ("true".equals(v) || "1".equals(v)) {
                    hasHiddenRow = true;
                }
            }
            if (! hasHiddenColumn && "col".equals(localName)) {
                String v = atts.getValue("hidden");
                if ("true".equals(v) || "1".equals(v)) {
                    hasHiddenColumn = true;
                }
            }
            delegate.startElement(uri, localName, qName, atts);
        }

        public void characters(char[] ch, int start, int length) throws SAXException {
            delegate.characters(ch, start, length);
        }

        public void endDocument() throws SAXException {
            delegate.endDocument();
        }

        public void endElement(String uri, String localName, String qName) throws SAXException {
            delegate.endElement(uri, localName, qName);
        }

        public void endPrefixMapping(String prefix) throws SAXException {
            delegate.endPrefixMapping(prefix);
        }

        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
            delegate.ignorableWhitespace(ch, start, length);
        }

        public void processingInstruction(String target, String data) throws SAXException {
            delegate.processingInstruction(target, data);
        }

        public void setDocumentLocator(Locator locator) {
            delegate.setDocumentLocator(locator);
        }

        public void skippedEntity(String name) throws SAXException {
            delegate.skippedEntity(name);
        }

        public void startDocument() throws SAXException {
            delegate.startDocument();
        }

        public void startPrefixMapping(String prefix, String uri) throws SAXException {
            delegate.startPrefixMapping(prefix, uri);
        }
    }

    private class WorkbookMetadataHandler extends DefaultHandler {
        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts)
                throws SAXException {
            //require x15ac //http://schemas.microsoft.com/office/spreadsheetml/2010/11/ac ???
            if ("absPath".equals(localName)) {
                for (int i = 0; i < atts.getLength(); i++) {
                    String n = atts.getLocalName(i);
                    if ("url".equals(n)) {
                        String url = atts.getValue(i);
                        metadata.set(TikaCoreProperties.ORIGINAL_RESOURCE_NAME, url);
                        return;
                    }
                }
            } else if ("sheet".equals(localName)) {
                String n = XMLReaderUtils.getAttrValue("name", atts);
                String state = XMLReaderUtils.getAttrValue("state", atts);
                if ("hidden".equals(state)) {
                    metadata.set(Office.HAS_HIDDEN_SHEETS, true);
                    metadata.add(Office.HIDDEN_SHEET_NAMES, n);
                } else if ("veryHidden".equals(state)) {
                    metadata.set(Office.HAS_VERY_HIDDEN_SHEETS, true);
                    metadata.set(Office.VERY_HIDDEN_SHEET_NAMES, n);
                }
            } else if ("workbookPr".equals(localName)) {
                String codeName = XMLReaderUtils.getAttrValue("codeName", atts);
                if (!StringUtils.isBlank(codeName)) {
                    metadata.set(Office.WORKBOOK_CODENAME, codeName);
                }
            }
            // file version? <fileVersion appName="xl" lastEdited="7" lowestEdited="7" rupBuild="28526"/>
        }
    }

    private static class ThreadedCommentHandler extends DefaultHandler {
        private final XHTMLContentHandler xhtml;
        StringBuilder sb = new StringBuilder();
        boolean inText = false;
        public ThreadedCommentHandler(XHTMLContentHandler xhtml) {
            this.xhtml = xhtml;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
            if ("text".equals(localName)) {
                inText = true;
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            if ("text".equals(localName)) {
                xhtml.startElement("div", "class", "threaded-comment");
                xhtml.startElement("p");
                xhtml.characters(sb.toString());
                xhtml.endElement("p");
                xhtml.endElement("div");
                sb.setLength(0);
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            if (inText) {
                sb.append(ch, start, length);
            }
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
            if (inText) {
                sb.append(ch, start, length);
            }
        }
    }

    /**
     * Records every image relationship of {@code drawingPart} against the
     * given 1-based {@code sheetNumber}.  Called once per drawing during
     * the pre-pass in {@link #getMainDocumentParts()}.  When the same
     * image is referenced from drawings on multiple sheets, all sheet
     * numbers end up in the set so {@link Office#SHEET_NUMBERS} ends up
     * multi-valued.  Keyed by absolute part name so the lookup matches
     * what {@link AbstractOOXMLExtractor#applyEmbeddedAnchorMetadata}
     * sees &mdash; relative target URIs across drawing parts collide
     * and are not stable lookup keys.
     */
    private void recordImagesOnSheet(PackagePart drawingPart, int sheetNumber) {
        if (drawingPart == null) {
            return;
        }
        PackageRelationshipCollection prc;
        try {
            prc = drawingPart.getRelationshipsByType(PackageRelationshipTypes.IMAGE_PART);
        } catch (InvalidFormatException e) {
            metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                    ExceptionUtils.getStackTrace(e));
            return;
        }
        if (prc == null) {
            return;
        }
        for (PackageRelationship rel : prc) {
            if (rel.getTargetMode() != TargetMode.INTERNAL) {
                continue;
            }
            PackagePart imagePart;
            try {
                imagePart = drawingPart.getRelatedPart(rel);
            } catch (InvalidFormatException | IllegalArgumentException e) {
                metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                        ExceptionUtils.getStackTrace(e));
                continue;
            }
            if (imagePart == null) {
                continue;
            }
            picturePages
                    .computeIfAbsent(imagePart.getPartName().getName(),
                            k -> new LinkedHashSet<>())
                    .add(sheetNumber);
        }
    }

    @Override
    protected void applyEmbeddedAnchorMetadata(PackagePart part, Metadata metadata) {
        PageAnchoring.applySheetMetadata(metadata,
                picturePages.get(part.getPartName().getName()));
    }
}
