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
    static final int WORKBOOK_VALUE_MAX_LEN = 1024;
    private final java.util.Map<String, String> workbookCellValues =
            new java.util.HashMap<>();
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
        } catch (Exception e) {
            metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                    ExceptionUtils.getStackTrace(e));
        }
        try {
            stringsShim = new XSSFSharedStringsShim(xssfReader.getSharedStringsData(),
                    config.isConcatenatePhoneticRuns(), parseContext);
        } catch (Exception e) {
            metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                    ExceptionUtils.getStackTrace(e));
        }
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
            try (InputStream stream = nextStream) {
                sheetPart = iter.getSheetPart();
                // Wire the workbook-wide cell capture sink now that iter.next()
                // has run — POI's SheetIterator throws if getSheetName() is
                // called before next(), so this MUST come after the try-with
                // takes ownership of nextStream.
                sheetExtractor.setCellValueCapture(workbookCellValues, iter.getSheetName());

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
        } catch (InvalidFormatException | TikaException | IOException | SAXException e) {
            //swallow
        }

        // XLM (Excel 4.0) macro extraction for XML-based OOXML workbooks
        // (.xlsm / .xltm). XSSFBExcelExtractorDecorator handles the binary
        // (XLSB) side via XlmMacroEmulator + Biff12XlmMacrosheetParser; the
        // XML side reuses the same IOC vocabulary via a text-formula scanner.
        // Failures here never fail the whole parse.
        try {
            processXlmXmlMacroSheets(container, xhtml, stringsShim);
        } catch (Exception e) {
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

        for (PackagePart macroPart : macroParts) {
            String sheetName = sheetNameFromPart(macroPart);
            xhtml.startElement("div", "class", "xlm-macrosheet");
            xhtml.element("h1", sheetName);

            try (InputStream is = macroPart.getInputStream()) {
                XlmXmlMacrosheetParser parser =
                        new XlmXmlMacrosheetParser(is, xhtml, sheetName, sharedStrings);
                parser.parse();
                allFormulas.putAll(parser.getFormulas());
                allValues.putAll(parser.getValues());
                // Parity with VBA: surface this macro sheet as a first-class MACRO
                // entry (embeddedResourceType=MACRO) carrying its formula text.
                emitMacroText(sheetName, "text/x-excel-macro",
                        String.join("\n", parser.getFormulas().values()), xhtml);
            } catch (Exception e) {
                xhtml.element("p", "xlm-parse-error: " + e.getMessage());
            }

            xhtml.endElement("div");
        }

        // Pattern-scan everything once we've seen all sheets. Cross-sheet
        // EXEC(Sheet1!A1) lookups resolve here.
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
        // outer extractor decided not to capture (currently always non-null
        // because capture is unconditional).
        private java.util.Map<String, String> cellValueSink;
        private String captureSheetName;
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
        protected void setCellValueCapture(java.util.Map<String, String> sink, String sheetName) {
            this.cellValueSink = sink;
            this.captureSheetName = sheetName;
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
                        && cellRef != null && formattedValue != null && !formattedValue.isEmpty()
                        && cellValueSink.size() < WORKBOOK_VALUES_MAX_ENTRIES) {
                    String v = formattedValue.length() > WORKBOOK_VALUE_MAX_LEN
                            ? formattedValue.substring(0, WORKBOOK_VALUE_MAX_LEN)
                            : formattedValue;
                    cellValueSink.put(
                            captureSheetName + ":" + currentRowForCapture + ":" + cellRef, v);
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
