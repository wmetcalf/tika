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
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipException;

import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagePartName;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.PackageRelationshipCollection;
import org.apache.poi.openxml4j.opc.PackageRelationshipTypes;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.xslf.usermodel.XSLFRelation;
import org.xml.sax.SAXException;

import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Office;
import org.apache.tika.metadata.PageAnchoring;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.image.BoundedColorGridCollector;
import org.apache.tika.parser.microsoft.ooxml.xslf.XSLFEventBasedPowerPointExtractor;
import org.apache.tika.sax.EmbeddedContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;
import org.apache.tika.utils.ExceptionUtils;
import org.apache.tika.utils.XMLReaderUtils;

/**
 * SAX/Streaming pptx extractior
 */
public class SXSLFPowerPointExtractorDecorator extends AbstractOOXMLExtractor {

    private final static String HANDOUT_MASTER =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/handoutMaster";

    //a pptx file should have one of these "main story" parts
    private final static String[] MAIN_STORY_PART_RELATIONS =
            new String[]{XSLFRelation.MAIN.getContentType(),
                    XSLFRelation.PRESENTATION_MACRO.getContentType(),
                    XSLFRelation.PRESENTATIONML.getContentType(),
                    XSLFRelation.PRESENTATIONML_TEMPLATE.getContentType(),
                    XSLFRelation.MACRO.getContentType(),
                    XSLFRelation.MACRO_TEMPLATE.getContentType(),
                    XSLFRelation.THEME_MANAGER.getContentType()


                    //TODO: what else
            };

    private final ParseContext context;
    private final Metadata metadata;
    private final CommentAuthors commentAuthors = new CommentAuthors();
    private PackagePart mainDocument = null;
    /**
     * Pre-pass index of embedded-image absolute part name → set of
     * 1-based slide numbers referencing that image.  Populated during
     * {@link #getMainDocumentParts()} so that {@link #getPagesForEmbeddedTarget(URI)}
     * can answer per-target lookups even after the deduplication done by
     * {@code AbstractOOXMLExtractor.handleEmbeddedParts} would otherwise hide
     * the second-and-later references.
     */
    private final Map<String, Set<Integer>> picturePages = new HashMap<>();

    public SXSLFPowerPointExtractorDecorator(Metadata metadata, ParseContext context,
                                             XSLFEventBasedPowerPointExtractor extractor) {
        super(context, extractor.getPackage());
        this.metadata = metadata;
        this.context = context;
        for (String contentType : MAIN_STORY_PART_RELATIONS) {
            List<PackagePart> pps = opcPackage.getPartsByContentType(contentType);
            if (pps.size() > 0) {
                mainDocument = pps.get(0);
                break;
            }
        }
    }

    /**
     * @see org.apache.poi.xslf.extractor.XSLFPowerPointExtractor#getText()
     */
    private final BoundedColorGridCollector pptxColorRows =
            new BoundedColorGridCollector();

    protected void buildXHTML(XHTMLContentHandler xhtml) throws SAXException, IOException {

        // .ppam (PowerPoint macro-enabled add-in) is dispatched here by the
        // OOXMLExtractorFactory fallback added for the addin.macroEnabled
        // main+xml content type. Add-ins don't carry a presentation part —
        // pps.get(0) in the constructor leaves mainDocument null — so every
        // mainDocument.getRelationshipsByType() call in the body of buildXHTML
        // (loadCommentAuthors, slidesPRC lookup, handout master, notes, theme
        // parts) would NPE. The VBA macro payload is extracted by the generic
        // AbstractOOXMLExtractor macro path independently of buildXHTML; bail
        // out cleanly here so the overall parse finishes with metadata +
        // macros even when there's no slide tree.
        if (mainDocument == null) {
            return;
        }

        loadCommentAuthors();
        addCommentAuthorMetadata();

        PackageRelationshipCollection slidesPRC = null;
        try {
            slidesPRC = mainDocument.getRelationshipsByType(XSLFRelation.SLIDE.getRelation());
        } catch (InvalidFormatException e) {
            metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                    ExceptionUtils.getStackTrace(e));
        }

        int hiddenSlideCount = 0;
        if (slidesPRC != null && slidesPRC.size() > 0) {
            for (int i = 0; i < slidesPRC.size(); i++) {
                try {
                    PackagePart slidePart =
                            safeGetRelatedPart(mainDocument, slidesPRC.getRelationship(i));
                    if (slidePart == null) {
                        continue;
                    }
                    hiddenSlideCount += handleSlidePart(slidePart, xhtml);
                } catch (InvalidFormatException | ZipException e) {
                    metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                            ExceptionUtils.getStackTrace(e));
                }
            }
        }
        if (hiddenSlideCount > 0) {
            metadata.set(Office.NUM_HIDDEN_SLIDES, hiddenSlideCount);
        }
        if (config.isIncludeSlideMasterContent()) {
            handleColorTextPart(XSLFRelation.SLIDE_MASTER.getRelation(),
                    "slide-master", mainDocument, xhtml, new HashMap<>(), true);
            handleColorTextPart(HANDOUT_MASTER, "slide-handout-master",
                    mainDocument, xhtml, new HashMap<>(), false);
        }
        OOXMLColorQRScanHelper.scan(pptxColorRows, context, metadata,
                "pptx_color_qr", "PPTX");
    }

    private void loadCommentAuthors() {
        PackageRelationshipCollection prc = null;
        try {
            prc = mainDocument.getRelationshipsByType(XSLFRelation.COMMENT_AUTHORS.getRelation());
        } catch (InvalidFormatException e) {
            metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                    ExceptionUtils.getStackTrace(e));
        }
        if (prc == null || prc.size() == 0) {
            return;
        }

        for (int i = 0; i < prc.size(); i++) {
            PackagePart commentAuthorsPart = null;
            try {
                commentAuthorsPart = safeGetRelatedPart(mainDocument, prc.getRelationship(i));
            } catch (InvalidFormatException e) {
                metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                        ExceptionUtils.getStackTrace(e));
            }
            if (commentAuthorsPart == null) {
                continue;
            }
            try (InputStream stream = commentAuthorsPart.getInputStream()) {
                XMLReaderUtils.parseSAX(stream,
                        new XSLFCommentAuthorHandler(commentAuthors), context);

            } catch (TikaException | SAXException | IOException e) {
                metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                        ExceptionUtils.getStackTrace(e));
            }
        }

    }

    private void addCommentAuthorMetadata() {
        for (String name : commentAuthors.nameMap.values()) {
            if (name != null && !name.isBlank()) {
                metadata.add(Office.COMMENT_PERSONS, name);
            }
        }
    }

    /**
     * @return 1 if the slide is hidden, 0 otherwise
     */
    private int handleSlidePart(PackagePart slidePart, XHTMLContentHandler xhtml)
            throws IOException, SAXException {
        Map<String, String> linkedRelationships =
                loadLinkedRelationships(slidePart, false, metadata);

        int hidden = 0;
        xhtml.startElement("div", "class", "slide-content");
        OOXMLTikaBodyPartHandler bodyHandler = new OOXMLTikaBodyPartHandler(xhtml, metadata);
        try (InputStream stream = slidePart.getInputStream()) {
            bodyHandler.setInlineBodyPartMap(null, context);
            OOXMLWordAndPowerPointTextHandler wordAndPPTHandler =
                    new OOXMLWordAndPowerPointTextHandler(bodyHandler, linkedRelationships);
            XMLReaderUtils.parseSAX(stream,
                    new EmbeddedContentHandler(wordAndPPTHandler), context);
            if (wordAndPPTHandler.isHiddenSlide()) {
                metadata.set(Office.HAS_HIDDEN_SLIDES, true);
                hidden = 1;
            }
            if (wordAndPPTHandler.hasAnimations()) {
                metadata.set(Office.HAS_ANIMATIONS, true);
            }
        } catch (SAXException e) {
            // Truncated/malformed slide XML can leave the body handler with
            // unclosed <p>, <td>, etc. on the wire. Close them before the
            // </div> below so subsequent slides -- and the outer </body> --
            // land in a balanced spot.
            WriteLimitReachedException.throwIfWriteLimitReached(e);
            metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                    ExceptionUtils.getStackTrace(e));
            bodyHandler.closeAnyPending();
        } catch (TikaException | IOException e) {
            metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                    ExceptionUtils.getStackTrace(e));
            bodyHandler.closeAnyPending();
        }
        pptxColorRows.addCollector(bodyHandler.getColorCollector());

        xhtml.endElement("div");

        if (config.isIncludeSlideMasterContent()) {
            handleColorTextPart(XSLFRelation.SLIDE_LAYOUT.getRelation(),
                    "slide-master-content", slidePart, xhtml,
                    linkedRelationships, true);
        }
        if (config.isIncludeSlideNotes()) {
            handleColorTextPart(XSLFRelation.NOTES.getRelation(), "slide-notes",
                    slidePart, xhtml, linkedRelationships, false);
            if (config.isIncludeSlideMasterContent()) {
                handleColorTextPart(XSLFRelation.NOTES_MASTER.getRelation(),
                        "slide-notes-master", slidePart, xhtml,
                        linkedRelationships, false);

            }
        }
        handleGeneralTextContainingPart(XSLFRelation.COMMENTS.getRelation(), null, slidePart,
                metadata, new XSLFCommentsHandler(xhtml, commentAuthors));

        handleColorTextPart(AbstractOOXMLExtractor.RELATION_DIAGRAM_DATA,
                "diagram-data", slidePart, xhtml, linkedRelationships, false);
        handleColorTextPart(XSLFRelation.CHART.getRelation(), "chart",
                slidePart, xhtml, linkedRelationships, false);
        return hidden;
    }

    private void handleColorTextPart(
            String relation, String xhtmlClass, PackagePart parent,
            XHTMLContentHandler xhtml, Map<String, String> linkedRelationships,
            boolean skipPlaceholders) throws SAXException {
        OOXMLTikaBodyPartHandler bodyHandler =
                new OOXMLTikaBodyPartHandler(xhtml, metadata);
        bodyHandler.setInlineBodyPartMap(OOXMLInlineBodyPartMap.EMPTY, context);
        OOXMLWordAndPowerPointTextHandler textHandler =
                new OOXMLWordAndPowerPointTextHandler(
                        bodyHandler, linkedRelationships);
        if (skipPlaceholders) {
            handleGeneralTextContainingPart(
                    relation, xhtmlClass, parent, metadata,
                    new PlaceHolderSkipper(textHandler));
        } else {
            handleGeneralTextContainingPart(
                    relation, xhtmlClass, parent, metadata, textHandler);
        }
        pptxColorRows.addCollector(bodyHandler.getColorCollector());
    }

    /**
     * In PowerPoint files, slides have things embedded in them,
     * and slide drawings which have the images
     */
    @Override
    protected List<PackagePart> getMainDocumentParts() {
        List<PackagePart> parts = new ArrayList<>();
        // .ppam (PowerPoint macro-enabled add-in) and other slideless macro containers have no
        // main presentation part (the addin.macroEnabled.main+xml content type isn't in
        // MAIN_STORY_PART_RELATIONS), so mainDocument is null and buildXHTML bails. The VBA
        // project IS still present — referenced via a vbaProject relationship from the addin
        // part — so surface the vba-bearing part(s) here; otherwise the parent's macro walk
        // (handleMacrosEarly / handleEmbeddedParts) has nothing to traverse and the macros are
        // silently dropped (the prior "picked up independently" comment was incorrect).
        if (mainDocument == null) {
            return getPartsWithVbaRelationship();
        }
        //TODO: consider: getPackage().getPartsByName(Pattern.compile("/ppt/embeddings/.*?
        //TODO: consider: getPackage().getPartsByName(Pattern.compile("/ppt/media/.*?
        PackageRelationshipCollection slidePRC = null;
        try {
            slidePRC = mainDocument.getRelationshipsByType(XSLFRelation.SLIDE.getRelation());
        } catch (InvalidFormatException e) {
            metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                    ExceptionUtils.getStackTrace(e));

        }
        if (slidePRC != null) {
            for (int i = 0; i < slidePRC.size(); i++) {
                PackagePart slidePart = null;
                try {
                    slidePart = safeGetRelatedPart(mainDocument, slidePRC.getRelationship(i));
                } catch (InvalidFormatException e) {
                    metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                            ExceptionUtils.getStackTrace(e));
                }
                recordPicturePageRefs(slidePart, i + 1);
                addSlideParts(slidePart, parts);
            }
        }

        parts.add(mainDocument);
        for (String rel : new String[]{XSLFRelation.SLIDE_MASTER.getRelation(), HANDOUT_MASTER}) {

            PackageRelationshipCollection prc = null;
            try {
                prc = mainDocument.getRelationshipsByType(rel);
            } catch (InvalidFormatException e) {
                metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                        ExceptionUtils.getStackTrace(e));
            }
            if (prc != null) {
                for (int i = 0; i < prc.size(); i++) {
                    PackagePart pp = null;
                    try {
                        pp = safeGetRelatedPart(mainDocument, prc.getRelationship(i));
                    } catch (InvalidFormatException e) {
                        metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                                ExceptionUtils.getStackTrace(e));
                    }
                    if (pp != null) {
                        parts.add(pp);
                    }
                }
            }
        }

        return parts;
    }

    /**
     * Records every image relationship of {@code slidePart} against the
     * given 1-based {@code slideNumber}.  Called once per slide during the
     * pre-pass in {@link #getMainDocumentParts()}.  When the same image is
     * referenced from multiple slides, both slide numbers end up in the
     * set so {@link org.apache.tika.metadata.TikaPagedText#PAGE_NUMBERS}
     * ends up multi-valued.  Keyed by absolute part name (e.g.
     * {@code /ppt/media/image1.png}) so the lookup matches what
     * {@link AbstractOOXMLExtractor#applyEmbeddedAnchorMetadata} sees
     * &mdash; relative target URIs from different sources can clash and
     * are not stable lookup keys.
     */
    private void recordPicturePageRefs(PackagePart slidePart, int slideNumber) {
        if (slidePart == null) {
            return;
        }
        PackageRelationshipCollection prc;
        try {
            prc = slidePart.getRelationshipsByType(PackageRelationshipTypes.IMAGE_PART);
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
                imagePart = slidePart.getRelatedPart(rel);
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
                    .add(slideNumber);
        }
    }

    @Override
    protected void applyEmbeddedAnchorMetadata(PackagePart part, Metadata metadata) {
        // Pre-pass keys by absolute part name (canonical zip path).
        PageAnchoring.applyPageMetadata(metadata,
                picturePages.get(part.getPartName().getName()));
    }

    private void addSlideParts(PackagePart slidePart, List<PackagePart> parts) {
        if (slidePart == null) {
            return;
        }
        for (String relation : new String[]{XSLFRelation.VML_DRAWING.getRelation(),
                XSLFRelation.SLIDE_LAYOUT.getRelation(), XSLFRelation.NOTES_MASTER.getRelation(),
                XSLFRelation.NOTES.getRelation(), XSLFRelation.CHART.getRelation(),
                XSLFRelation.DIAGRAM_DRAWING.getRelation()}) {
            PackageRelationshipCollection prc = null;
            try {
                prc = slidePart.getRelationshipsByType(relation);
            } catch (InvalidFormatException e) {
                metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                        ExceptionUtils.getStackTrace(e));
            }
            if (prc != null) {
                for (PackageRelationship packageRelationship : prc) {
                    if (packageRelationship.getTargetMode() == TargetMode.INTERNAL) {
                        PackagePartName relName = null;
                        try {
                            relName = PackagingURIHelper
                                    .createPartName(packageRelationship.getTargetURI());
                        } catch (InvalidFormatException e) {
                            metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                                    ExceptionUtils.getStackTrace(e));
                        }
                        if (relName != null) {
                            parts.add(packageRelationship.getPackage().getPart(relName));
                        }
                    }
                }
            }
        }
        //and slide of course
        parts.add(slidePart);

    }

}
