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


import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Office;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.image.BoundedColorGridCollector;
import org.apache.tika.parser.microsoft.OfficeLinkMetadataUtil;
import org.apache.tika.parser.microsoft.OfficeParserConfig;
import org.apache.tika.parser.microsoft.WordExtractor;
import org.apache.tika.parser.microsoft.ooxml.xwpf.XWPFStylesShim;
import org.apache.tika.sax.EmbeddedContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;
import org.apache.tika.utils.XMLReaderUtils;

public class OOXMLTikaBodyPartHandler
        implements XWPFBodyContentsHandler {

    private static final String P = "p";

    private static final char[] NEWLINE = new char[]{'\n'};

    private final XHTMLContentHandler xhtml;
    private final XWPFListManager listManager;
    private final boolean includeDeletedText;
    private final boolean includeMoveFromText;
    private final XWPFStylesShim styles;
    private final Metadata metadata;

    private int pDepth = 0; //paragraph depth
    private int tableDepth = 0;//table depth
    private int sdtDepth = 0;//
    private FormattingTagManager formattingTags;

    //TODO: fix this
    //pWithinCell should be an array/stack of given cell depths
    //so that when you get to the end of an embedded table, e.g.,
    //you know what your paragraph count was in the parent cell.
    //<tc><p/><p/><table><tr><tc></p></p></tc></tr></table>...
    private int tableCellDepth = 0;
    private int pWithinCell = 0;
    // Stack of structural elements (paragraphs, tables, rows, cells) this
    // handler has emitted to the xhtml stream and not yet closed. Used by
    // closeAnyPending() to drain the stack in reverse order so the captured
    // XHTML stays balanced when a caller's parseSAX call throws part-way.
    // Tags emitted by FormattingTagManager (<b>/<i>/<u>/<s>/<a>) are not
    // tracked here -- closeAnyPending closes them via formattingTags.closeAll()
    // before draining this stack.
    private final java.util.Deque<String> openStructuralTags = new java.util.ArrayDeque<>();

    //will need to replace this with a stack
    //if we're marking more that the first level <p/> element
    private String paragraphTag = null;

    private OOXMLInlineBodyPartMap inlinePartMap = OOXMLInlineBodyPartMap.EMPTY;
    private ParseContext parseContext = null;
    private final java.util.List<String> pendingCommentIds = new java.util.ArrayList<>();
    private final java.util.Set<String> emittedCommentIds = new java.util.HashSet<>();
    private final Map<String, EmbeddedPartMetadata> embeddedPartMetadataMap = new HashMap<>();
    private String activeHyperlinkUrl = null;
    private String activeHyperlinkType = null;
    private String activeRunHyperlinkUrl = null;
    private static final int MAX_LINK_TEXT_CHARS = 64 * 1_024;
    private static final int MAX_INLINE_NOTE_EXPANSIONS = 1_024;
    private static final long MAX_INLINE_NOTE_XML_BYTES = 8L * 1_024 * 1_024;
    private final StringBuilder activeHyperlinkText = new StringBuilder();
    private boolean activeHyperlinkTextTruncated;
    private int inlineNoteExpansions;
    private long inlineNoteXmlBytes;
    private boolean inlineNoteLimitReached;
    private boolean inlineNoteLimitSignaled;

    // Color-aware QR collector: optionally records (per-glyph color) for
    // every character emitted in run(), binned by paragraph row. Populated
    // only when ColorAwareConfig is enabled in the ParseContext. Each row
    // is a list of luma integers; the final grid is fed to ColorGridQRDecoder.
    private final BoundedColorGridCollector colorCollector =
            new BoundedColorGridCollector();
    private boolean colorAwareEnabled = false;

    public OOXMLTikaBodyPartHandler(XHTMLContentHandler xhtml) {
        this(xhtml, null);
    }

    public OOXMLTikaBodyPartHandler(XHTMLContentHandler xhtml, Metadata metadata) {
        this.xhtml = xhtml;
        this.metadata = metadata;
        this.formattingTags = new FormattingTagManager(xhtml);
        this.styles = XWPFStylesShim.EMPTY_STYLES;
        this.listManager = XWPFListManager.EMPTY_LIST;
        this.includeDeletedText = false;
        this.includeMoveFromText = false;
    }

    public OOXMLTikaBodyPartHandler(XHTMLContentHandler xhtml, XWPFStylesShim styles,
                                    XWPFListManager listManager,
                                    OfficeParserConfig parserConfig) {
        this(xhtml, styles, listManager, parserConfig, null);
    }

    public OOXMLTikaBodyPartHandler(XHTMLContentHandler xhtml, XWPFStylesShim styles,
                                    XWPFListManager listManager,
                                    OfficeParserConfig parserConfig, Metadata metadata) {
        this.xhtml = xhtml;
        this.metadata = metadata;
        this.formattingTags = new FormattingTagManager(xhtml);
        this.styles = styles;
        this.listManager = listManager;
        this.includeDeletedText = parserConfig.isIncludeDeletedContent();
        this.includeMoveFromText = parserConfig.isIncludeMoveFromContent();
    }

    /**
     * Sets pre-parsed inline body part content (footnotes, endnotes, comments)
     * so that references encountered during main document parsing can be
     * resolved inline.
     */
    public void setInlineBodyPartMap(OOXMLInlineBodyPartMap inlinePartMap,
            ParseContext parseContext) {
        this.inlinePartMap = inlinePartMap != null ? inlinePartMap : OOXMLInlineBodyPartMap.EMPTY;
        this.parseContext = parseContext;
        if (parseContext != null) {
            org.apache.tika.parser.ColorAwareConfig cc =
                    parseContext.get(org.apache.tika.parser.ColorAwareConfig.class);
            this.colorAwareEnabled = cc != null && cc.isEnabled();
        }
    }

    /**
     * Returns the collected color grid (paragraph row × per-character luma)
     * for color-aware QR scanning. Returns an empty list when color-aware
     * mode is disabled or no per-run colors were seen.
     */
    public java.util.List<java.util.List<Integer>> getColorRows() {
        return colorCollector.getRows();
    }

    public BoundedColorGridCollector getColorCollector() {
        return colorCollector;
    }

    @Override
    public void run(RunProperties runProperties, String contents) throws SAXException {
        updateRunHyperlinkState(runProperties.getHlinkClickUrl());
        formattingTags.applyFormatting(runProperties);
        xhtml.characters(contents);
        if (isCollectingLinkMetadata() && contents != null) {
            appendHyperlinkText(contents);
        }
        if (colorAwareEnabled && contents != null) {
            int luma = lumaForHex(runProperties.getColor());
            for (int i = 0; i < contents.length(); i++) {
                char c = contents.charAt(i);
                // Skip pure whitespace — adds no QR-grid signal and would
                // typically be inter-module spacing. NBSP (U+00A0) is a
                // common Word-HTML evasion alternative to U+0020.
                if (c == ' ' || c == '\u00a0'
                        || c == '\t' || c == '\n' || c == '\r') {
                    continue;
                }
                colorCollector.addCell(luma);
            }
        }
    }

    /** BT.601 luma from a 6-char RGB hex string. Null/invalid → 0 (treated dark). */
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

    @Override
    public void hyperlinkStart(String link) throws SAXException {
        startCollectingHyperlink(link, "hyperlink");
        formattingTags.openHyperlink(link);
    }

    @Override
    public void hyperlinkEnd() throws SAXException {
        flushActiveHyperlink();
        formattingTags.closeHyperlink();
    }

    @Override
    public void startParagraph(ParagraphProperties paragraphProperties) throws SAXException {

        //if you're in a table cell and your after the first paragraph
        //make sure to prepend a \n
        if (tableCellDepth > 0 && pWithinCell > 0) {
            xhtml.characters(NEWLINE, 0, 1);
        }

        if (pDepth == 0 && tableDepth == 0 && sdtDepth == 0) {
            paragraphTag = P;
            String styleClass = null;
            //TIKA-2144 check that styles is not null
            if (paragraphProperties.getStyleID() != null && styles != null) {
                String styleName = styles.getStyleName(paragraphProperties.getStyleID());
                if (styleName != null) {
                    WordExtractor.TagAndStyle tas =
                            WordExtractor.buildParagraphTagAndStyle(styleName, false);
                    paragraphTag = tas.getTag();
                    styleClass = tas.getStyleClass();
                }
            }


            if (styleClass == null) {
                xhtml.startElement(paragraphTag);
            } else {
                xhtml.startElement(paragraphTag, "class", styleClass);
            }
            openStructuralTags.push(paragraphTag);
        }

        writeParagraphNumber(paragraphProperties.getNumId(), paragraphProperties.getIlvl(),
                listManager, xhtml);
        pDepth++;
        if (colorAwareEnabled) {
            colorCollector.startRow();
        }
    }


    @Override
    public void endParagraph() throws SAXException {
        flushRunHyperlink();
        formattingTags.closeAll();
        if (pDepth == 1 && tableDepth == 0) {
            xhtml.endElement(paragraphTag);
            popExpected(paragraphTag);
        } else if (tableCellDepth > 0 && pWithinCell > 0) {
            xhtml.characters(NEWLINE, 0, 1);
        } else if (tableCellDepth == 0) {
            xhtml.characters(NEWLINE, 0, 1);
        }

        // Emit any pending comment content after the paragraph closes
        // (matching the DOM parser's behavior of appending comments after paragraphs)
        emitPendingComments();

        if (tableCellDepth > 0) {
            pWithinCell++;
        }
        pDepth--;
        if (colorAwareEnabled) {
            colorCollector.finishRow();
        }
    }

    private void emitPendingComments() throws SAXException {
        if (pendingCommentIds.isEmpty()) {
            return;
        }
        for (String id : pendingCommentIds) {
            OOXMLInlineBodyPartMap.InlineBodyPart part = inlinePartMap.getComment(id);
            if (part != null) {
                inlineNoteContent(part, "comment");
                emittedCommentIds.add(id);
            }
        }
        pendingCommentIds.clear();
    }

    /**
     * Returns the set of comment IDs that were inlined during parsing.
     * Used by the decorator to skip these when dumping remaining comments.
     */
    public java.util.Set<String> getEmittedCommentIds() {
        return emittedCommentIds;
    }

    /**
     * Closes any XHTML elements this handler opened but didn't get a chance to
     * close, in the proper nesting order. Intended ONLY for the catch arm of a
     * caller that swallowed a {@link SAXException} from the inner SAX parser;
     * the normal happy-path flow keeps the trackers in sync via endParagraph
     * / endTableCell / endTableRow / endTable / FormattingTagManager.closeAll.
     * Without this, swallowed exceptions leave dangling {@code <p>}, {@code <td>},
     * {@code <tr>}, {@code <table>}, or formatting tags on the wire that
     * collide with the outer {@code </body></html>}.
     */
    public void closeAnyPending() throws SAXException {
        flushActiveHyperlink();
        flushRunHyperlink();
        colorCollector.abandonCurrentRow();
        formattingTags.closeAll();
        // Drain the structural-element stack in reverse open order. This
        // handles nested tables correctly (multiple cells/rows/tables
        // interleaved), unlike per-element counters which lose nesting info.
        while (!openStructuralTags.isEmpty()) {
            String tag = openStructuralTags.pop();
            xhtml.endElement(tag);
        }
        // Reset internal depth/state so subsequent emits start clean.
        tableDepth = 0;
        tableCellDepth = 0;
        pDepth = 0;
        pWithinCell = 0;
    }

    /**
     * Pops {@code openStructuralTags} expecting the given tag on top.
     * If the stack is empty or the top differs, this is a no-op rather than a
     * throw -- the stack is best-effort tracking for closeAnyPending(), and
     * the existing happy-path tests (which don't trigger closeAnyPending) must
     * not be perturbed by stack tracking bugs.
     */
    private void popExpected(String tag) {
        if (!openStructuralTags.isEmpty() && tag.equals(openStructuralTags.peek())) {
            openStructuralTags.pop();
        }
    }

    @Override
    public void startTable() throws SAXException {
        // A <w:tbl> can appear nested inside an outer <w:p> -- corrupt-ish but
        // present in the corpus (e.g., <w:p><w:r>...<wps:txbx><w:txbxContent>
        // <w:tbl>...). At that point a run-level <b>/<i>/<u>/<s>/<a> may be on
        // the SAX stack just above where the <table> is about to land. When a
        // later paragraph inside a cell ends, formattingTags.closeAll() tries
        // to emit </b> for the outer-paragraph state, but <td> is topmost --
        // strict validator rejects the mismatch. Close pending formatting now
        // so the table opens at a clean layer and the outer style is forgotten.
        // Mirrors startSDT()'s same-shape guard.
        formattingTags.closeAll();
        xhtml.startElement("table");
        openStructuralTags.push("table");
        tableDepth++;

    }

    @Override
    public void endTable() throws SAXException {

        xhtml.endElement("table");
        popExpected("table");
        tableDepth--;

    }

    @Override
    public void startTableRow() throws SAXException {
        xhtml.startElement("tr");
        openStructuralTags.push("tr");
    }

    @Override
    public void endTableRow() throws SAXException {
        xhtml.endElement("tr");
        popExpected("tr");
    }

    @Override
    public void startTableCell() throws SAXException {
        xhtml.startElement("td");
        openStructuralTags.push("td");
        tableCellDepth++;
    }

    @Override
    public void endTableCell() throws SAXException {
        xhtml.endElement("td");
        popExpected("td");
        pWithinCell = 0;
        tableCellDepth--;
    }

    @Override
    public void startSDT() throws SAXException {
        formattingTags.closeAll();
        sdtDepth++;
    }

    @Override
    public void endSDT() {
        sdtDepth--;
    }

    @Override
    public void startEditedSection(String editor, Date date,
                                   EditType editType) {
        //no-op
    }

    @Override
    public void endEditedSection() {
        //no-op
    }

    @Override
    public boolean isIncludeDeletedText() {
        return includeDeletedText;
    }

    @Override
    public void footnoteReference(String id) throws SAXException {
        if (id == null) {
            return;
        }
        OOXMLInlineBodyPartMap.InlineBodyPart part = inlinePartMap.getFootnote(id);
        if (part != null) {
            inlineNoteContent(part, "footnote");
        } else {
            xhtml.characters("[");
            xhtml.characters(id);
            xhtml.characters("]");
        }
    }

    @Override
    public void endnoteReference(String id) throws SAXException {
        if (id == null) {
            return;
        }
        OOXMLInlineBodyPartMap.InlineBodyPart part = inlinePartMap.getEndnote(id);
        if (part != null) {
            inlineNoteContent(part, "endnote");
        } else {
            xhtml.characters("[");
            xhtml.characters(id);
            xhtml.characters("]");
        }
    }

    @Override
    public void commentReference(String id) throws SAXException {
        if (id != null) {
            if (pendingCommentIds.size() >= MAX_INLINE_NOTE_EXPANSIONS) {
                signalInlineNoteLimitReached();
                return;
            }
            pendingCommentIds.add(id);
        }
    }

    private void inlineNoteContent(OOXMLInlineBodyPartMap.InlineBodyPart part,
            String cssClass) throws SAXException {
        if (inlineNoteLimitReached) {
            return;
        }
        int xmlBytes = part.xml().length;
        if (inlineNoteExpansions >= MAX_INLINE_NOTE_EXPANSIONS
                || xmlBytes > MAX_INLINE_NOTE_XML_BYTES - inlineNoteXmlBytes) {
            inlineNoteLimitReached = true;
            signalInlineNoteLimitReached();
            xhtml.characters("[additional inline notes omitted]");
            return;
        }
        inlineNoteExpansions++;
        inlineNoteXmlBytes += xmlBytes;
        Map<String, String> noteRelationships = part.linkedRelationships();
        xhtml.startElement("div", "class", cssClass);
        // Track the inner handler so we can call its closeAnyPending() if
        // the inline-note parseSAX aborts mid-element. Without the drain
        // the surrounding </div> mismatches whatever the inner handler
        // left on the SAX stack (<p>/<td>/etc.) and StrictXHTMLValidator
        // propagates a misleading error.
        OOXMLTikaBodyPartHandler innerHandler = new OOXMLTikaBodyPartHandler(xhtml, metadata);
        innerHandler.setInlineBodyPartMap(OOXMLInlineBodyPartMap.EMPTY, parseContext);
        try {
            XMLReaderUtils.parseSAX(new ByteArrayInputStream(part.xml()),
                    new EmbeddedContentHandler(
                            new OOXMLWordAndPowerPointTextHandler(
                                    innerHandler,
                                    noteRelationships)),
                    parseContext);
        } catch (SAXException e) {
            innerHandler.closeAnyPending();
            WriteLimitReachedException.throwIfWriteLimitReached(e);
            xhtml.characters("[" + cssClass + " parse error]");
        } catch (TikaException | IOException e) {
            innerHandler.closeAnyPending();
            xhtml.characters("[" + cssClass + " parse error]");
        } finally {
            colorCollector.addCollector(innerHandler.getColorCollector());
        }
        xhtml.endElement("div");
    }

    private void signalInlineNoteLimitReached() {
        if (inlineNoteLimitSignaled || metadata == null) {
            return;
        }
        inlineNoteLimitSignaled = true;
        metadata.set(TikaCoreProperties.TRUNCATED_METADATA, true);
        metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                "OOXML inline note expansion limit reached; additional "
                        + "footnotes, endnotes, or comments were skipped");
        if (metadata.get("ExploitClass") == null) {
            metadata.set("ExploitClass",
                    "OOXML inline-note analysis incomplete; referenced content "
                            + "may not have been analyzed");
        }
    }

    @Override
    public boolean isIncludeMoveFromText() {
        return includeMoveFromText;
    }

    @Override
    public void embeddedOLERef(String relId, String progId, String emfImageRId)
            throws SAXException {
        if (relId == null) {
            return;
        }
        if ((progId != null && !progId.isEmpty()) ||
                (emfImageRId != null && !emfImageRId.isEmpty())) {
            EmbeddedPartMetadata epm = new EmbeddedPartMetadata(emfImageRId);
            if (progId != null && !progId.isEmpty()) {
                epm.setProgId(progId);
            }
            embeddedPartMetadataMap.put(relId, epm);
        }
        AttributesImpl attributes = new AttributesImpl();
        attributes.addAttribute("", "class", "class", "CDATA", "embedded");
        attributes.addAttribute("", "id", "id", "CDATA", relId);
        xhtml.startElement("div", attributes);
        xhtml.endElement("div");
    }

    public Map<String, EmbeddedPartMetadata> getEmbeddedPartMetadataMap() {
        return embeddedPartMetadataMap;
    }

    @Override
    public void linkedOLERef(String relId, String url) throws SAXException {
        if (relId == null) {
            return;
        }
        if (metadata != null) {
            metadata.set(Office.HAS_LINKED_OLE_OBJECTS, true);
            if (url != null && !url.isEmpty()) {
                OfficeLinkMetadataUtil.addLink(metadata, "linked_ole", url, null, null,
                        "", "relationship", "", relId);
            }
        }
        // Emit as an external reference anchor - linked OLE objects reference external files
        AttributesImpl attributes = new AttributesImpl();
        attributes.addAttribute("", "class", "class", "CDATA", "external-ref-linkedOle");
        attributes.addAttribute("", "id", "id", "CDATA", relId);
        if (url != null && !url.isEmpty()) {
            attributes.addAttribute("", "href", "href", "CDATA", url);
        }
        xhtml.startElement("a", attributes);
        xhtml.endElement("a");
    }

    @Override
    public void embeddedPicRef(String picFileName, String picDescription) throws SAXException {

        AttributesImpl attr = new AttributesImpl();
        if (picFileName != null) {
            attr.addAttribute("", "src", "src", "CDATA", "embedded:" + picFileName);
        }
        if (picDescription != null) {
            attr.addAttribute("", "alt", "alt", "CDATA", picDescription);
        }

        xhtml.startElement("img", attr);
        xhtml.endElement("img");


    }

    @Override
    public void fieldCodeHyperlinkStart(String link) throws SAXException {
        if (metadata != null) {
            metadata.set(Office.HAS_FIELD_HYPERLINKS, true);
        }
        startCollectingHyperlink(link, "field_hyperlink");
        formattingTags.openHyperlink(link);
    }

    @Override
    public void externalRef(String fieldType, String url) throws SAXException {
        if (url == null || url.isEmpty()) {
            return;
        }
        if (metadata != null) {
            if ("hlinkHover".equals(fieldType)) {
                metadata.set(Office.HAS_HOVER_HYPERLINKS, true);
            } else if ("vml-shape-href".equals(fieldType)) {
                metadata.set(Office.HAS_VML_HYPERLINKS, true);
            } else {
                metadata.set(Office.HAS_FIELD_HYPERLINKS, true);
            }
            OfficeLinkMetadataUtil.addLink(metadata,
                    OfficeLinkMetadataUtil.normalizeType(fieldType), url, null, null,
                    "", "external-ref", "", "",
                    OfficeLinkMetadataUtil.normalizeTrigger(fieldType),
                    OfficeLinkMetadataUtil.normalizeActionType(fieldType, url));
        }
        AttributesImpl attr = new AttributesImpl();
        attr.addAttribute("", "class", "class", "CDATA", "external-ref-" + fieldType);
        attr.addAttribute("", "href", "href", "CDATA", url);
        xhtml.startElement("a", attr);
        xhtml.endElement("a");
    }

    private void startCollectingHyperlink(String link, String type) {
        if (link == null || link.startsWith("#")) {
            return;
        }
        activeHyperlinkUrl = link;
        activeHyperlinkType = type;
        activeHyperlinkText.setLength(0);
        activeHyperlinkTextTruncated = false;
    }

    private void flushActiveHyperlink() {
        if (activeHyperlinkUrl == null) {
            return;
        }
        boolean textTruncated = activeHyperlinkTextTruncated;
        OfficeLinkMetadataUtil.addLink(metadata,
                OfficeLinkMetadataUtil.normalizeType(activeHyperlinkType), activeHyperlinkUrl,
                activeHyperlinkText.toString().trim(), null, "", "text", "", "",
                OfficeLinkMetadataUtil.normalizeTrigger(activeHyperlinkType),
                OfficeLinkMetadataUtil.normalizeActionType(activeHyperlinkType, activeHyperlinkUrl));
        activeHyperlinkUrl = null;
        activeHyperlinkType = null;
        activeHyperlinkText.setLength(0);
        activeHyperlinkTextTruncated = false;
        if (textTruncated) {
            OfficeLinkMetadataUtil.markLinkLimitReached(metadata);
        }
    }

    private void updateRunHyperlinkState(String hyperlinkUrl) {
        if (hyperlinkUrl != null && hyperlinkUrl.startsWith("#")) {
            hyperlinkUrl = null;
        }
        if (!Objects.equals(hyperlinkUrl, activeRunHyperlinkUrl)) {
            flushRunHyperlink();
            activeRunHyperlinkUrl = hyperlinkUrl;
        }
    }

    private void flushRunHyperlink() {
        if (activeRunHyperlinkUrl == null) {
            activeHyperlinkText.setLength(0);
            activeHyperlinkTextTruncated = false;
            return;
        }
        boolean textTruncated = activeHyperlinkTextTruncated;
        OfficeLinkMetadataUtil.addLink(metadata, "hyperlink", activeRunHyperlinkUrl,
                activeHyperlinkText.toString().trim(), null, "", "text", "", "",
                "click", "external_url");
        activeRunHyperlinkUrl = null;
        activeHyperlinkText.setLength(0);
        activeHyperlinkTextTruncated = false;
        if (textTruncated) {
            OfficeLinkMetadataUtil.markLinkLimitReached(metadata);
        }
    }

    private boolean isCollectingLinkMetadata() {
        return activeHyperlinkUrl != null || activeRunHyperlinkUrl != null;
    }

    private void appendHyperlinkText(String contents) {
        int remaining = MAX_LINK_TEXT_CHARS - activeHyperlinkText.length();
        if (remaining > 0) {
            activeHyperlinkText.append(contents, 0, Math.min(contents.length(), remaining));
        }
        if (contents.length() > remaining) {
            activeHyperlinkTextTruncated = true;
        }
    }

    @Override
    public void startBookmark(String id, String name) throws SAXException {
        //skip bookmarks within hyperlinks
        if (name != null && !formattingTags.isHyperlinkActive()) {
            xhtml.startElement("a", "name", name);
            xhtml.endElement("a");
        }
    }

    @Override
    public void endBookmark(String id) {
        //no-op
    }

    private void writeParagraphNumber(int numId, int ilvl, XWPFListManager listManager,
                                      XHTMLContentHandler xhtml) throws SAXException {

        if (ilvl < 0 || numId < 0 || listManager == null) {
            return;
        }
        String number = listManager.getFormattedNumber(BigInteger.valueOf(numId), ilvl);
        if (number != null) {
            xhtml.characters(number);
        }

    }
}
