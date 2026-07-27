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
package org.apache.tika.parser.pdf;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent;
import org.apache.pdfbox.text.PDFMarkedContentExtractor;
import org.apache.pdfbox.text.TextPosition;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.renderer.Renderer;

/**
 * <p>This was added in Tika 1.24 as an alpha version of a text extractor
 * that builds the text from the marked text tree and includes/normalizes
 * some of the structural tags.
 * </p>
 *
 * @since 1.24
 */

public class PDFMarkedContent2XHTML extends PDF2XHTML {

    private static final int MAX_PAGE_TREE_DEPTH = 128;
    private static final int MAX_PAGE_TREE_OBJECTS = 100_000;
    private static final int MAX_RECURSION_DEPTH = 1000;
    private static final int MAX_STRUCTURE_TREE_NODES = 100_000;
    private static final String DIV = "div";
    private static final Map<String, HtmlTag> COMMON_TAG_MAP = new HashMap<>();

    static {
        //code requires these to be all lower case
        COMMON_TAG_MAP.put("document", new HtmlTag("body"));
        COMMON_TAG_MAP.put("div", new HtmlTag("div"));
        COMMON_TAG_MAP.put("p", new HtmlTag("p"));
        COMMON_TAG_MAP.put("span", new HtmlTag("span"));
        COMMON_TAG_MAP.put("table", new HtmlTag("table"));
        COMMON_TAG_MAP.put("thead", new HtmlTag("thead"));
        COMMON_TAG_MAP.put("tbody", new HtmlTag("tbody"));
        COMMON_TAG_MAP.put("tr", new HtmlTag("tr"));
        COMMON_TAG_MAP.put("th", new HtmlTag("th"));
        COMMON_TAG_MAP.put("td", new HtmlTag("td"));//TODO -- convert to th if in thead?
        COMMON_TAG_MAP.put("l", new HtmlTag("ul"));
        COMMON_TAG_MAP.put("li", new HtmlTag("li"));
        COMMON_TAG_MAP.put("h1", new HtmlTag("h1"));
        COMMON_TAG_MAP.put("h2", new HtmlTag("h2"));
        COMMON_TAG_MAP.put("h3", new HtmlTag("h3"));
        COMMON_TAG_MAP.put("h4", new HtmlTag("h4"));
        COMMON_TAG_MAP.put("h5", new HtmlTag("h5"));
        COMMON_TAG_MAP.put("h6", new HtmlTag("h6"));
    }

    //this stores state as we recurse through the structure tag tree
    private State state = new State();
    private final int outputPageLimit;
    private Set<ObjectRef> outputPageRefs = Collections.emptySet();

    private PDFMarkedContent2XHTML(PDDocument document, ContentHandler handler,
                                   ParseContext context, Metadata metadata, PDFParserConfig config,
                                   Renderer renderer)
            throws IOException {
        this(document, handler, context, metadata, config, renderer, -1);
    }

    private PDFMarkedContent2XHTML(PDDocument document, ContentHandler handler,
                                   ParseContext context, Metadata metadata, PDFParserConfig config,
                                   Renderer renderer, int outputPageLimit)
            throws IOException {
        super(document, handler, context, metadata, config, renderer);
        this.outputPageLimit = outputPageLimit;
    }

    /**
     * Converts the given PDF document (and related metadata) to a stream
     * of XHTML SAX events sent to the given content handler.
     *
     * @param pdDocument PDF document
     * @param handler    SAX content handler
     * @param context    parse context
     * @param metadata   PDF metadata
     * @param config     PDF parser config
     * @param renderer   the renderer to use for rendering pages
     * @throws SAXException  if the content handler fails to process SAX events
     * @throws TikaException if there was an exception outside of per page processing
     */
    public static void process(PDDocument pdDocument, ContentHandler handler,
                               ParseContext context,
                               Metadata metadata, PDFParserConfig config, Renderer renderer)
            throws SAXException, TikaException {
        process(pdDocument, handler, context, metadata, config, renderer, -1);
    }

    static void process(PDDocument pdDocument, ContentHandler handler,
                        ParseContext context, Metadata metadata, PDFParserConfig config,
                        Renderer renderer, int outputPageLimit)
            throws SAXException, TikaException {
        PDFMarkedContent2XHTML pdfMarkedContent2XHTML = null;
        try {
            pdfMarkedContent2XHTML =
                    new PDFMarkedContent2XHTML(pdDocument, handler, context, metadata, config,
                            renderer, outputPageLimit);
        } catch (IOException e) {
            throw new TikaException("couldn't initialize PDFMarkedContent2XHTML", e);
        }
        try {
            pdfMarkedContent2XHTML.writeText(pdDocument, new Writer() {
                @Override
                public void write(char[] cbuf, int off, int len) {
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
        } catch (IOException e) {
            if (e.getCause() instanceof SAXException) {
                throw (SAXException) e.getCause();
            } else {
                throw new TikaException("Unable to extract PDF content", e);
            }
        }
        if (!pdfMarkedContent2XHTML.exceptions.isEmpty()) {
            //throw the first
            throw new TikaException("Unable to extract PDF content",
                    pdfMarkedContent2XHTML.exceptions.get(0));
        }
    }

    private static Map<String, HtmlTag> loadRoleMap(Map<String, Object> roleMap) {
        if (roleMap == null) {
            return Collections.EMPTY_MAP;
        }
        Map<String, HtmlTag> tags = new HashMap<>();
        for (Map.Entry<String, Object> e : roleMap.entrySet()) {
            String k = e.getKey();
            Object obj = e.getValue();
            if (obj instanceof String) {
                String v = (String) obj;
                String lc = v.toLowerCase(Locale.US);
                if (COMMON_TAG_MAP.containsValue(new HtmlTag(lc))) {
                    tags.put(k, new HtmlTag(lc));
                } else {
                    tags.put(k, new HtmlTag(DIV, lc));
                }
            }
        }
        return tags;
    }

    private static void findPages(COSBase kidsObj, List<ObjectRef> pageRefs)
            throws IOException {
        findPages(kidsObj, pageRefs, new HashSet<>(), 0);
    }

    private static void findPages(COSBase kidsObj, List<ObjectRef> pageRefs,
                                  Set<ObjectRef> visitedObjects, int depth)
            throws IOException {
        if (kidsObj == null) {
            return;
        }
        if (depth > MAX_PAGE_TREE_DEPTH) {
            throw new IOException(
                    "PDF page tree exceeded depth limit " + MAX_PAGE_TREE_DEPTH);
        }
        if (kidsObj instanceof COSArray) {
            for (COSBase kid : ((COSArray) kidsObj)) {
                if (kid instanceof COSObject) {
                    ObjectRef kidRef = toObjectRef(kid);
                    if (!visitedObjects.add(kidRef)) {
                        throw new IOException(
                                "PDF page tree contains a repeated or cyclic object "
                                        + kidRef);
                    }
                    if (visitedObjects.size() > MAX_PAGE_TREE_OBJECTS) {
                        throw new IOException(
                                "PDF page tree exceeded object limit "
                                        + MAX_PAGE_TREE_OBJECTS);
                    }
                    COSBase kidbase = ((COSObject) kid).getObject();
                    if (kidbase instanceof COSDictionary) {
                        COSDictionary dict = (COSDictionary) kidbase;
                        if (COSName.PAGE.equals(dict.getCOSName(COSName.TYPE))) {
                            pageRefs.add(kidRef);
                            continue;
                        }
                        if (dict.containsKey(COSName.KIDS)) {
                            findPages(dict.getDictionaryObject(COSName.KIDS), pageRefs,
                                    visitedObjects, depth + 1);
                        }
                    }
                }
            }
        }
    }

    @Override
    protected void processPages(PDPageTree pageTree) throws IOException {

        //this is a 0-indexed list of object refs for each page
        //we need this to map the mcids later...
        //TODO: is there a better way of getting these/doing the mapping?

        List<ObjectRef> pageRefs = new ArrayList<>();
        //STEP 1: get the page refs
        findPages(pageTree.getCOSObject().getDictionaryObject(COSName.KIDS), pageRefs);
        //confirm the right number of pages was found
        if (pageRefs.size() != pdDocument.getNumberOfPages()) {
            // PDF carries a marked-content structure tree but findPages
            // can't recurse the page tree into a flat ref list (common when
            // /Pages /Kids contains indirect refs to intermediate nodes the
            // recursion doesn't follow). Without aligned pageRefs we can't
            // map MCIDs back to pages, so the marked-content extractor
            // can't function — but the document still has text, so falling
            // back to the parent PDF2XHTML's regular page-by-page extraction
            // is strictly better than throwing IOException and emitting
            // zero output. Record the gap so consumers know marked-content
            // detail wasn't surfaced.
            metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                    "PDF marked content extraction unavailable: pageRefs (" +
                            pageRefs.size() + ") did not match document pages (" +
                            pdDocument.getNumberOfPages() + "); fell back to " +
                            "plain PDF2XHTML extraction.");
            super.processPages(pageTree);
            return;
        }
        if (outputPageLimit >= 0) {
            outputPageRefs = new HashSet<>(
                    pageRefs.subList(0, Math.min(outputPageLimit, pageRefs.size())));
        }

        PDStructureTreeRoot structureTreeRoot =
                pdDocument.getDocumentCatalog().getStructureTreeRoot();

        //STEP 2: load the roleMap
        Map<String, HtmlTag> roleMap = loadRoleMap(structureTreeRoot.getRoleMap());

        //STEP 3: load all of the text, mapped to MCIDs
        Map<MCID, String> paragraphs = loadTextByMCID(pageTree, pageRefs);

        //STEP 4: now recurse the the structure tree root and output the structure
        //and the text bits from paragraphs

        try {
            recurse(structureTreeRoot.getK(), null, 0, false, paragraphs, roleMap);
        } catch (SAXException e) {
            throw new IOException(e);
        }

        //STEP 5: handle all the potentially unprocessed bits
        try {
            StringBuilder unwrittenLinkText = new StringBuilder();
            state.linkStates.descendingIterator().forEachRemaining(
                    linkState -> linkState.appendPlainText(unwrittenLinkText));
            if (unwrittenLinkText.length() > 0) {
                xhtml.startElement("p");
                writeString(unwrittenLinkText.toString());
                xhtml.endElement("p");
            }
            for (MCID mcid : paragraphs.keySet()) {
                if (!state.processedMCIDs.contains(mcid) && isOutputAllowed(mcid)) {
                    if (mcid.mcid > -1) {
                        //TODO: LOG! piece of text that wasn't referenced  in the marked content
                        // tree
                        // but should have been.  If mcid == -1, this was a known item not part of
                        // content tree.
                    }

                    xhtml.startElement("p");
                    writeString(paragraphs.get(mcid));
                    xhtml.endElement("p");
                }
            }
        } catch (SAXException e) {
            throw new IOException(e);
        }
        //Step 6: for now, iterate through the pages again and do all the other handling
        //TODO: figure out when we're crossing page boundaries during the recursion
        // step above and do the page by page processing then...rather than dumping this
        // all here.
        for (PDPage page : pageTree) {
            startPage(page);
            endPage(page);
        }

    }

    private void recurse(COSBase kids, ObjectRef currentPageRef, int depth,
                         boolean ownsActiveLinkAction,
                         Map<MCID, String> paragraphs, Map<String, HtmlTag> roleMap)
            throws IOException, SAXException {

        if (depth > MAX_RECURSION_DEPTH) {
            throw new IOException(
                    new TikaException("Exceeded max recursion depth " + MAX_RECURSION_DEPTH));
        }
        if (++state.structureNodeVisits > MAX_STRUCTURE_TREE_NODES) {
            throw new IOException(new TikaException(
                    "Exceeded max structure tree nodes " + MAX_STRUCTURE_TREE_NODES));
        }
        if (kids instanceof COSObject
                && !state.visitedStructureObjects.add((COSObject) kids)) {
            throw new IOException(new TikaException(
                    "PDF structure tree contains a repeated or cyclic object"));
        }

        if (kids instanceof COSArray) {
            for (COSBase k : ((COSArray) kids)) {
                recurse(k, currentPageRef, depth + 1, ownsActiveLinkAction,
                        paragraphs, roleMap);
            }
        } else if (kids instanceof COSObject && 
                ((COSObject) kids).getObject() instanceof COSDictionary) {
            //TODO should be merged with COSDictionary segment below?
            // and maybe dereference COSObject first, i.e. before the first "if"?
            // No, because we're using the object key for a map
            // However, we could replace ObjectRef with COSBase for currentPageRef. 
            // This way we could also get rid of findPages because that logic is in the
            // iterator of PageTree which we get by calling PDDocument.getPages()
            COSDictionary dict = (COSDictionary) ((COSObject) kids).getObject();
            COSName type = dict.getCOSName(COSName.TYPE);
            if (COSName.OBJR.equals(type)) {
                COSBase referencedObject = dict.getDictionaryObject(COSName.OBJ);
                ObjectRef objectPageRef =
                        resolveObjectReferencePage(dict, referencedObject, currentPageRef);
                if (isKnownOutputAllowed(objectPageRef)) {
                    recurse(referencedObject, objectPageRef, depth + 1, false,
                            paragraphs, roleMap);
                }
                return;
            }

            COSName n = dict.getCOSName(COSName.S);
            String name = "";
            if (n != null) {
                name = ((COSName) n).getName();
            }
            COSBase grandkids = dict.getItem(COSName.K);
            if (grandkids == null) {
                return;
            }
            COSBase pageBase = dict.getItem(COSName.PG);

            ObjectRef explicitPageRef = toObjectRef(pageBase);
            if (explicitPageRef != null) {
                currentPageRef = explicitPageRef;
            }
            boolean outputAllowed = isOutputAllowed(currentPageRef);

            HtmlTag tag = getTag(name, roleMap);
            boolean startedLink = false;
            boolean ignoreTag = false;
            if ("link".equals(tag.clazz)) {
                LinkState linkState = new LinkState(currentPageRef);
                linkState.hasAllowedContent = outputPageLimit < 0
                        || (currentPageRef != null && outputAllowed);
                state.linkStates.push(linkState);
                startedLink = true;
            }
            if (outputAllowed && state.linkStates.isEmpty()) {
                //TODO: currently suppressing span and lbody...
                // is this what we want to do?  What else should we suppress?
                if ("span".equals(tag.tag)) {
                    ignoreTag = true;
                } else if ("lbody".equals(tag.clazz)) {
                    ignoreTag = true;
                }
                if (!ignoreTag) {
                    if (tag.clazz != null && !tag.clazz.isBlank()) {
                        xhtml.startElement(tag.tag, "class", tag.clazz);
                    } else {
                        xhtml.startElement(tag.tag);
                    }
                }
            }

            // Only an action directly within this Link's children belongs to
            // the new LinkState. Descending through another structure element
            // must not let that element's action overwrite its ancestor link.
            recurse(grandkids, currentPageRef, depth + 1, startedLink,
                    paragraphs, roleMap);
            if (startedLink) {
                writeLink();
            }
            if (outputAllowed && state.linkStates.isEmpty() && !startedLink && !ignoreTag) {
                xhtml.endElement(tag.tag);
            }
        } else if (kids instanceof COSInteger) {
            int mcidInt = ((COSInteger) kids).intValue();
            MCID mcid = new MCID(currentPageRef, mcidInt);
            if (paragraphs.containsKey(mcid)) {
                if (isOutputAllowed(mcid)) {
                    if (!state.linkStates.isEmpty()) {
                        LinkState linkState = state.linkStates.peek();
                        linkState.hasAllowedContent = true;
                        linkState.anchorBuilder.append(paragraphs.get(mcid));
                    } else {
                        try {
                            //if it isn't a uri, output this anyhow
                            writeString(paragraphs.get(mcid));
                        } catch (IOException e) {
                            handleCatchableIOE(e);
                        }
                    }
                }
                state.processedMCIDs.add(mcid);
            } else {
                //TODO: log can't find mcid
            }
        } else if (kids instanceof COSDictionary) {
            //TODO: check for other types of dictionary?
            COSDictionary dict = (COSDictionary) kids;
            if (COSName.OBJR.equals(dict.getCOSName(COSName.TYPE))) {
                COSBase referencedObject = dict.getDictionaryObject(COSName.OBJ);
                ObjectRef objectPageRef =
                        resolveObjectReferencePage(dict, referencedObject, currentPageRef);
                if (isKnownOutputAllowed(objectPageRef)) {
                    recurse(referencedObject, objectPageRef, depth + 1, false,
                            paragraphs, roleMap);
                }
                return;
            }
            COSDictionary anchor = dict.getCOSDictionary(COSName.A);
            //check for subtype /Link ?
            //COSName subtype = obj.getCOSName(COSName.SUBTYPE);
            if (anchor != null && !state.linkStates.isEmpty()) {
                boolean anchorOutputAllowed = outputPageLimit < 0
                        || (currentPageRef != null && isOutputAllowed(currentPageRef));
                LinkState linkState = state.linkStates.peek();
                if (anchorOutputAllowed || ownsActiveLinkAction) {
                    linkState.uri = anchor.getString(COSName.URI);
                }
                if (anchorOutputAllowed) {
                    linkState.hasAllowedContent = true;
                }
            } else {
                if (dict.containsKey(COSName.K)) {
                    recurse(dict.getDictionaryObject(COSName.K), currentPageRef, depth + 1,
                            false, paragraphs, roleMap);
                } else if (dict.containsKey(COSName.OBJ)) {
                    recurse(dict.getDictionaryObject(COSName.OBJ), currentPageRef, depth + 1,
                            false, paragraphs, roleMap);
                }
            }
        } else {
            //TODO: handle a different object?
        }
    }

    private static ObjectRef resolveObjectReferencePage(
            COSDictionary objectReference, COSBase referencedObject,
            ObjectRef inheritedPageRef) {
        ObjectRef pageRef = toObjectRef(objectReference.getItem(COSName.PG));
        if (pageRef == null && referencedObject instanceof COSDictionary) {
            pageRef = toObjectRef(
                    ((COSDictionary) referencedObject).getItem(COSName.P));
        }
        return pageRef == null ? inheritedPageRef : pageRef;
    }

    private static ObjectRef toObjectRef(COSBase pageBase) {
        if (pageBase instanceof COSObject) {
            COSObject pageObject = (COSObject) pageBase;
            return new ObjectRef(
                    pageObject.getKey().getNumber(),
                    pageObject.getKey().getGeneration());
        }
        return null;
    }

    private boolean isOutputAllowed(MCID mcid) {
        return isOutputAllowed(mcid.objectRef);
    }

    private boolean isOutputAllowed(ObjectRef pageRef) {
        return outputPageLimit < 0 || pageRef == null || outputPageRefs.contains(pageRef);
    }

    private boolean isKnownOutputAllowed(ObjectRef pageRef) {
        return outputPageLimit < 0
                || (pageRef != null && outputPageRefs.contains(pageRef));
    }

    private void writeLink() throws SAXException, IOException {
        //This is only for uris, obv.
        //If we want to catch within doc references (GOTO, we need to cache those in state.
        //See testPDF_childAttachments.pdf for examples
        LinkState linkState = state.linkStates.pop();
        if (outputPageLimit >= 0 && !linkState.hasAllowedContent) {
            return;
        }

        if (!state.linkStates.isEmpty()) {
            LinkState parent = state.linkStates.peek();
            parent.addNestedLink(linkState);
            return;
        }

        writeLinkState(linkState);
    }

    private void writeLinkState(LinkState linkState) throws SAXException, IOException {
        if (linkState.parts.isEmpty()) {
            writeLinkText(linkState.uri, linkState.anchorBuilder.toString(), true);
            return;
        }

        boolean wroteOwnText = false;
        for (LinkPart part : linkState.parts) {
            if (part.nestedLink != null) {
                writeLinkState(part.nestedLink);
            } else {
                writeLinkText(linkState.uri, part.text, false);
                wroteOwnText |= !part.text.isEmpty();
            }
        }
        String remainingText = linkState.anchorBuilder.toString();
        writeLinkText(linkState.uri, remainingText, false);
        wroteOwnText |= !remainingText.isEmpty();
        if (!wroteOwnText && linkState.uri != null && !linkState.uri.isBlank()) {
            writeLinkText(linkState.uri, "", true);
        }
    }

    private void writeLinkText(String uri, String text, boolean emitEmpty)
            throws SAXException, IOException {
        if (text.isEmpty() && !emitEmpty) {
            return;
        }
        if (uri != null && !uri.isBlank()) {
            xhtml.startElement("a", "href", uri);
            xhtml.characters(text);
            xhtml.endElement("a");
            return;
        }
        try {
            //if it isn't a uri, output this anyhow
            writeString(text);
        } catch (IOException e) {
            handleCatchableIOE(e);
        }
    }

    private HtmlTag getTag(String name, Map<String, HtmlTag> roleMap) {
        if (roleMap.containsKey(name)) {
            return roleMap.get(name);
        }
        String lc = name.toLowerCase(Locale.US);
        if (COMMON_TAG_MAP.containsKey(lc)) {
            return COMMON_TAG_MAP.get(lc);
        }
        roleMap.put(name, new HtmlTag(DIV, name.toLowerCase(Locale.US)));
        return roleMap.get(name);
    }

    private Map<MCID, String> loadTextByMCID(PDPageTree pageTree, List<ObjectRef> pageRefs) throws IOException {
        int pageCount = 1;
        Map<MCID, String> paragraphs = new HashMap<>();
        for (PDPage page : pageTree) {
            if (config.getMaxPages() > 0 && pageCount > config.getMaxPages()) {
                break;
            }
            ObjectRef pageRef = pageRefs.get(pageCount - 1);
            PDFMarkedContentExtractor ex = new PDFMarkedContentExtractor();
            try {
                ex.processPage(page);
            } catch (IOException e) {
                handleCatchableIOE(e);
                continue;
            }
            for (PDMarkedContent c : ex.getMarkedContents()) {
                //TODO: at some point also handle
                // 1. c.getActualText()
                // 2. c.getExpandedForm()
                // 3. c.getAlternateDescription()
                // 4. c.getLanguage()

                List<Object> objects = c.getContents();
                StringBuilder sb = new StringBuilder();
                //TODO: sort text positions? Figure out when to add/remove a newline and/or space?
                for (Object o : objects) {
                    if (o instanceof TextPosition) {
                        String unicode = ((TextPosition) o).getUnicode();
                        if (unicode != null) {
                            sb.append(unicode);
                        }
                    }
                    /*
                    TODO: do we want to do anything with these?
                    TODO: Are there other types of objects we need to handle here?
                    else if (o instanceof PDImageXObject) {

                    } else if (o instanceof PDTransparencyGroup) {

                    } else if (o instanceof PDMarkedContent) {

                    } else if (o instanceof PDFormXObject) {

                    } else {
                        throw new RuntimeException("can't handle "+o.getClass());
                    }*/
                }

                int mcidInt = c.getMCID();
                MCID mcid = new MCID(pageRef, mcidInt);
                String p = sb.toString();
                if (c.getTag().equals("P")) {
                    p = p.trim();
                }

                if (mcidInt < 0) {
                    //mcidInt == -1 for text bits that do not have an actual
                    //mcid -- concatenate these bits
                    if (paragraphs.containsKey(mcid)) {
                        p = paragraphs.get(mcid) + "\n" + p;
                    }
                }

                paragraphs.put(mcid, p);

            }
            pageCount++;
        }
        return paragraphs;
    }

    private static class State {
        Set<MCID> processedMCIDs = new HashSet<>();
        int tableDepth = 0;
        private final Deque<LinkState> linkStates = new ArrayDeque<>();
        private final Set<COSObject> visitedStructureObjects =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private int structureNodeVisits = 0;
        private int tdDepth = 0;
    }

    private static class LinkState {
        private final StringBuilder anchorBuilder = new StringBuilder();
        private final List<LinkPart> parts = new ArrayList<>();
        private final ObjectRef pageRef;
        private boolean hasAllowedContent = false;
        private String uri = null;

        private LinkState(ObjectRef pageRef) {
            this.pageRef = pageRef;
        }

        private void addNestedLink(LinkState nestedLink) {
            if (anchorBuilder.length() > 0) {
                parts.add(LinkPart.text(anchorBuilder.toString()));
                anchorBuilder.setLength(0);
            }
            parts.add(LinkPart.link(nestedLink));
            hasAllowedContent |= nestedLink.hasAllowedContent;
        }

        private void appendPlainText(StringBuilder target) {
            for (LinkPart part : parts) {
                if (part.nestedLink != null) {
                    part.nestedLink.appendPlainText(target);
                } else {
                    target.append(part.text);
                }
            }
            target.append(anchorBuilder);
        }
    }

    private static class LinkPart {
        private final String text;
        private final LinkState nestedLink;

        private LinkPart(String text, LinkState nestedLink) {
            this.text = text;
            this.nestedLink = nestedLink;
        }

        private static LinkPart text(String text) {
            return new LinkPart(text, null);
        }

        private static LinkPart link(LinkState link) {
            return new LinkPart(null, link);
        }
    }

    private static class HtmlTag {
        private final String tag;
        private final String clazz;

        HtmlTag() {
            this("");
        }

        HtmlTag(String tag) {
            this(tag, "");
        }

        HtmlTag(String tag, String clazz) {
            this.tag = tag;
            this.clazz = clazz;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            HtmlTag htmlTag = (HtmlTag) o;

            if (!Objects.equals(tag, htmlTag.tag)) {
                return false;
            }
            return Objects.equals(clazz, htmlTag.clazz);
        }

        @Override
        public int hashCode() {
            int result = tag != null ? tag.hashCode() : 0;
            result = 31 * result + (clazz != null ? clazz.hashCode() : 0);
            return result;
        }
    }

    private static class ObjectRef {
        private final long objId;
        private final int version;

        public ObjectRef(long objId, int version) {
            this.objId = objId;
            this.version = version;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ObjectRef objectRef = (ObjectRef) o;
            return objId == objectRef.objId && version == objectRef.version;
        }

        @Override
        public int hashCode() {
            return Objects.hash(objId, version);
        }

        @Override
        public String toString() {
            return "ObjectRef{" + "objId=" + objId + ", version=" + version + '}';
        }
    }

    /**
     * In PDF land, MCID are integers that should be unique _per page_.
     * This class includes the object ref to the page and the mcid
     * so that this should be a cross-document unique key to
     * given content.
     * <p>
     * If the mcid integer == -1, that means that there is text on the page
     * not assigned to any marked content.
     */
    private static class MCID {
        //this is the object ref to the particular page
        private final ObjectRef objectRef;
        private final int mcid;

        public MCID(ObjectRef objectRef, int mcid) {
            this.objectRef = objectRef;
            this.mcid = mcid;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            MCID mcid1 = (MCID) o;
            return mcid == mcid1.mcid && Objects.equals(objectRef, mcid1.objectRef);
        }

        @Override
        public int hashCode() {
            return Objects.hash(objectRef, mcid);
        }

        @Override
        public String toString() {
            return "MCID{" + "objectRef=" + objectRef + ", mcid=" + mcid + '}';
        }
    }
}
