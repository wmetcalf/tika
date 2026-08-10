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

import static org.apache.tika.sax.XHTMLContentHandler.XHTML;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.input.BoundedInputStream;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.PackageRelationshipCollection;
import org.apache.poi.openxml4j.opc.PackageRelationshipTypes;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.openxml4j.opc.internal.FileHelper;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.Ole10Native;
import org.apache.poi.poifs.filesystem.Ole10NativeException;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xssf.usermodel.XSSFRelation;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.EmbeddedDocumentUtil;
import org.apache.tika.io.FilenameUtils;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Office;
import org.apache.tika.metadata.PageAnchoring;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.microsoft.LenientVBAReader;
import org.apache.tika.parser.microsoft.OfficeLinkMetadataUtil;
import org.apache.tika.parser.microsoft.OfficeParser;
import org.apache.tika.parser.microsoft.OfficeParser.POIFSDocumentType;
import org.apache.tika.parser.microsoft.OfficeParserConfig;
import org.apache.tika.parser.microsoft.SummaryExtractor;
import org.apache.tika.sax.EmbeddedContentHandler;
import org.apache.tika.sax.TaggedContentHandler;
import org.apache.tika.sax.XHTMLBalancingHandler;
import org.apache.tika.sax.XHTMLContentHandler;
import org.apache.tika.utils.ExceptionUtils;
import org.apache.tika.utils.StringUtils;
import org.apache.tika.utils.XMLReaderUtils;

/**
 * Base class for all Tika OOXML extractors.
 * <p>
 * Tika extractors decorate POI extractors so that the parsed content of
 * documents is returned as a sequence of XHTML SAX events. Subclasses must
 * implement the buildXHTML method {@link #buildXHTML(XHTMLContentHandler)} that
 * populates the {@link XHTMLContentHandler} object received as parameter.
 */
public abstract class AbstractOOXMLExtractor implements OOXMLExtractor {


    static final String RELATION_AUDIO =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/audio";
    static final String RELATION_MEDIA =
            "http://schemas.microsoft.com/office/2007/relationships/media";
    static final String RELATION_VIDEO =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/video";
    static final String RELATION_DIAGRAM_DATA =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/diagramData";

    static final String RELATION_ALTERNATE_FORMAT_CHUNK =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/aFChunk";

    private static final String PACK_OBJECT_REL_TYPE =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/package";
    private static final String OLE_OBJECT_REL_TYPE =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/oleObject";

    protected static final String[] EMBEDDED_RELATIONSHIPS =
            new String[]{RELATION_AUDIO, PackageRelationshipTypes.IMAGE_PART,
                    PACK_OBJECT_REL_TYPE, PackageRelationshipTypes.CORE_DOCUMENT,
                    RELATION_DIAGRAM_DATA};
    private static final String TYPE_OLE_OBJECT =
            "application/vnd.openxmlformats-officedocument.oleObject";
    private static final String LINKED_RELATIONSHIP_COLLECTION_WARNING =
            "OOXML linked-relationship collection limit reached";


    private final EmbeddedDocumentExtractor embeddedExtractor;
    private final ParseContext context;
    private final OOXMLPartContentCollector.CollectionBudget
            linkedRelationshipCollectionBudget =
            OOXMLPartContentCollector.newDefaultCollectionBudget();
    protected OfficeParserConfig config;
    protected OPCPackage opcPackage;
    private Exception vbaRelationshipDiscoveryFailure;
    /**
     * ONE VBA size accumulator for the whole package, created on first use.
     *
     * <p>A package may declare any number of {@code vbaProject} relationships and
     * {@link #handleMacrosEarly} walks every one of them, deduplicated only by target part name. The
     * accumulator used to be built inside {@code OfficeParser.extractMacros}, so each part started a
     * fresh allowance and N parts cost N times the ceiling that calls itself per-document -- for the
     * price of a few kilobytes of zip entry per part. Instance-scoped, like
     * {@link #linkedRelationshipCollectionBudget}: one extractor is one package.
     */
    private LenientVBAReader.Bounds vbaBounds;

    public AbstractOOXMLExtractor(ParseContext context, OPCPackage opcPackage) {
        this.context = context;
        this.opcPackage = opcPackage;
        embeddedExtractor = EmbeddedDocumentUtil.getEmbeddedDocumentExtractor(context);

        // This has already been set by OOXMLParser's call to configure()
        // We can rely on this being non-null.
        this.config = context.get(OfficeParserConfig.class);
    }

    /**
     * @see org.apache.tika.parser.microsoft.ooxml.OOXMLExtractor#getMetadataExtractor()
     */
    public MetadataExtractor getMetadataExtractor() {
        return new SAXBasedMetadataExtractor(opcPackage, context);
    }

    ParseContext getParseContext() {
        return context;
    }
    /**
     * @see
     * org.apache.tika.parser.microsoft.ooxml.OOXMLExtractor#getXHTML(ContentHandler, Metadata,
     * ParseContext)
     */
    public void getXHTML(ContentHandler handler, Metadata metadata, ParseContext context)
            throws SAXException, IOException, TikaException {
        vbaRelationshipDiscoveryFailure = null;
        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata, context);
        xhtml.startDocument();

        // Reserve structured link metadata for executable relationships before
        // body hyperlinks can consume OfficeLinkMetadataUtil's character budget.
        // XHTML anchors are still emitted by the normal relationship walks.
        ExternalReferenceBudget externalReferenceBudget =
                createExternalReferenceBudget(metadata);

        // Extract VBA macros BEFORE the main body. The recursive write-limit is a CUMULATIVE
        // total across the whole parse (RecursiveParserWrapper.SecureHandlerCounter), so a
        // workbook/doc padded with megabytes of junk body text -- a known malspam evasion --
        // exhausts the budget inside buildXHTML() before handleEmbeddedParts() reaches the
        // macro stream, silently dropping the small-but-critical VBA source. Doing it first
        // guarantees capture; targets handled here are recorded so the normal embedded-part
        // walk below skips them (no double emission).
        handleMacrosEarly(xhtml, metadata);

        buildXHTML(xhtml);

        // Now do any embedded parts
        Set<String> handledRelationshipParts =
                handleEmbeddedParts(xhtml, metadata, getEmbeddedPartMetadataMap(),
                        externalReferenceBudget);
        reportIncompleteVbaRelationshipDiscovery(metadata);

        // Catch-all: walk package-root and every remaining part's relationships
        // for external Targets. Settings/footnotes/comments/header/footer/
        // webSettings/sheet/slide .rels can all carry attachedTemplate /
        // oleObject / frame / subDocument / hyperlink relations with
        // TargetMode=External, and handleEmbeddedParts() above only walks
        // getMainDocumentParts(). Frame URLs in webSettings.xml.rels were the
        // historical example — generalize to anything analogous.
        surfaceExternalRefsFromAllParts(xhtml, metadata, handledRelationshipParts,
                externalReferenceBudget);
        externalReferenceBudget.markTruncation(metadata);
        lastExternalRefPartsScannedForTesting = externalReferenceBudget.getPartsScannedForTesting();
        lastExternalRefPartRelationshipReadsForTesting =
                externalReferenceBudget.getPartRelationshipReadsForTesting();

        // thumbnail
        handleThumbnail(xhtml, metadata);

        xhtml.endDocument();
    }

    // Visible for testing only: the total number of package parts scanned by the
    // combined createExternalReferenceBudget()/surfaceExternalRefsFromAllParts()
    // walks during the most recent getXHTML() call. Exposed as a plain int (rather
    // than the private ExternalReferenceBudget type) so tests can assert the shared
    // part-scan cap actually bounds BOTH loops combined, without relying on
    // wall-clock timing (which can't distinguish "second loop capped" from
    // "second loop unbounded" when per-part relationship parsing is cheap).
    private int lastExternalRefPartsScannedForTesting = -1;

    // Visible for testing only: how many times part.getRelationships() was
    // actually invoked across BOTH external-reference walks during the most
    // recent getXHTML(). This is the real scan cost, and unlike the
    // tryScanPart()-driven counter it stays accurate if a guard is removed.
    private int lastExternalRefPartRelationshipReadsForTesting = -1;

    int getLastExternalRefPartRelationshipReadsForTesting() {
        return lastExternalRefPartRelationshipReadsForTesting;
    }

    int getLastExternalRefPartsScannedForTesting() {
        return lastExternalRefPartsScannedForTesting;
    }

    protected Map<String, EmbeddedPartMetadata> getEmbeddedPartMetadataMap() {
        return Collections.emptyMap();
    }

    /**
     * Hook for subclasses to apply anchor metadata (page or sheet numbers)
     * to an embedded part's metadata, for paginated/sheeted containers.
     * Called from {@link #handleEmbeddedFile} after the basic metadata
     * (relationship id, content type, etc.) has been written, before the
     * embedded part is handed off to the recursing parser.
     *
     * <p>Default is no-op &mdash; non-paginated containers (Word, Visio,
     * ...) leave embedded resources without anchor metadata.  Subclasses
     * for paginated containers should override and invoke either
     * {@link PageAnchoring#applyPageMetadata} (presentations) or
     * {@link PageAnchoring#applySheetMetadata} (spreadsheets) with the
     * indices for {@code part}.  Letting each subclass own the write
     * keeps the abstract class format-agnostic &mdash; pages vs. sheets
     * is a per-format decision.
     *
     * <p>The {@link PackagePart} (not just its target URI) is supplied
     * because relative target URIs differ depending on the relationship
     * source (e.g. {@code ../media/image1.png} from a slide, or the same
     * relative URI from an Excel drawing) and would not be stable
     * lookup keys across sources.  Subclasses can use
     * {@code part.getPartName().getName()} for a canonical absolute path.
     *
     * @param part      the embedded part being emitted
     * @param metadata  metadata to enrich
     */
    protected void applyEmbeddedAnchorMetadata(PackagePart part, Metadata metadata) {
        // default: no-op
    }

    protected String getJustFileName(String desc) {
        int idx = desc.lastIndexOf('/');
        if (idx != -1) {
            desc = desc.substring(idx + 1);
        }
        idx = desc.lastIndexOf('.');
        if (idx != -1) {
            desc = desc.substring(0, idx);
        }

        return desc;
    }

    private void handleThumbnail(ContentHandler handler, Metadata metadata) throws SAXException {
        try {
            for (PackageRelationship rel : opcPackage
                    .getRelationshipsByType(PackageRelationshipTypes.THUMBNAIL)) {
                PackagePart tPart = opcPackage.getPart(rel);
                if (tPart == null) {
                    continue;
                }
                try (InputStream tStream = tPart.getInputStream()) {
                    Metadata thumbnailMetadata = Metadata.newInstance(context);
                    String thumbName = tPart.getPartName().getName();
                    thumbnailMetadata.set(TikaCoreProperties.INTERNAL_PATH, thumbName);
                    thumbnailMetadata.set(TikaCoreProperties.RESOURCE_NAME_KEY,
                            FilenameUtils.getName(thumbName));

                    AttributesImpl attributes = new AttributesImpl();
                    attributes.addAttribute(XHTML, "class", "class", "CDATA", "embedded");
                    attributes.addAttribute(XHTML, "id", "id", "CDATA", thumbName);
                    handler.startElement(XHTML, "div", "div", attributes);
                    handler.endElement(XHTML, "div", "div");

                    thumbnailMetadata.set(TikaCoreProperties.EMBEDDED_RELATIONSHIP_ID, thumbName);
                    thumbnailMetadata.set(Metadata.CONTENT_TYPE, tPart.getContentType());
                    thumbnailMetadata.set(TikaCoreProperties.TITLE, tPart.getPartName().getName());
                    thumbnailMetadata.set(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE,
                            TikaCoreProperties.EmbeddedResourceType.THUMBNAIL.name());

                    if (embeddedExtractor.shouldParseEmbedded(thumbnailMetadata)) {
                        try (TikaInputStream tis = TikaInputStream.get(tStream)) {
                            embeddedExtractor.parseEmbedded(tis,
                                    new EmbeddedContentHandler(handler), thumbnailMetadata, context, false);
                        }
                    }
                }
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception ex) {
            WriteLimitReachedException.throwIfWriteLimitReached(ex);
            //swallow otherwise
            metadata.add(TikaCoreProperties.EMBEDDED_EXCEPTION,
                    ExceptionUtils.getStackTrace(ex));
        }
    }

    /**
     * Find every package part that carries a VBA-macros (vbaProject) relationship. An extractor
     * whose {@link #getMainDocumentParts()} would otherwise be empty for a macro-only container
     * with no body part — notably a PowerPoint {@code .ppam} add-in: no presentation/slide tree,
     * but the VBA project is still referenced from the addin main part — can return these so the
     * macro walk has something to traverse. Without it the VBA is silently dropped.
     */
    protected List<PackagePart> getPartsWithVbaRelationship() {
        List<PackagePart> out = new ArrayList<>();
        if (opcPackage == null) {
            return out;
        }
        try {
            for (PackagePart pp : opcPackage.getParts()) {
                if (pp == null) {
                    continue;
                }
                try {
                    if (pp.getRelationshipsByType(XSSFRelation.VBA_MACROS.getRelation()).size() > 0) {
                        out.add(pp);
                    }
                } catch (SecurityException e) {
                    throw e;
                } catch (Exception e) {
                    recordVbaRelationshipDiscoveryFailure(e);
                }
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            recordVbaRelationshipDiscoveryFailure(e);
        }
        return out;
    }

    private void recordVbaRelationshipDiscoveryFailure(Exception failure) {
        if (vbaRelationshipDiscoveryFailure == null) {
            vbaRelationshipDiscoveryFailure = failure;
        }
    }

    private void reportIncompleteVbaRelationshipDiscovery(Metadata metadata) {
        if (vbaRelationshipDiscoveryFailure == null) {
            return;
        }
        metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                "VBA relationship discovery was incomplete; macro content may be hidden:\n"
                        + ExceptionUtils.getStackTrace(vbaRelationshipDiscoveryFailure));
        if (metadata.get("ExploitClass") == null) {
            metadata.set("ExploitClass",
                    "OOXML VBA relationship discovery incomplete; macros may be hidden");
        }
    }

    // Canonical names of VBA macro parts attempted by handleMacrosEarly(), so the
    // normal embedded-part walk in handleEmbeddedPart() does not parse them again.
    private final Set<String> macroTargetsHandledEarly = new HashSet<>();

    /**
     * Emit VBA macros ahead of the body content so they are not starved by the cumulative
     * write-limit when the document is padded with junk body text. Walks the same parts as
     * {@link #handleEmbeddedParts} but only for the {@code VBA_MACROS} relationship, and
     * records each actual target before parsing it in {@link #macroTargetsHandledEarly}.
     * Non-fatal: a broken
     * macro part is recorded on the metadata and never fails the overall parse (mirroring
     * the swallow in handleEmbeddedParts). A SAXException (e.g. a write-limit signal) is
     * allowed to propagate, exactly as the body path would.
     */
    private void handleMacrosEarly(XHTMLContentHandler xhtml, Metadata metadata)
            throws TikaException, IOException, SAXException {
        // Macros disabled -> skip the part walk entirely. handleMacros() itself no-ops when
        // extraction is off, but the walk (getRelationships / getRelatedPart) is pure overhead
        // and can throw on malformed files for a feature the caller didn't request.
        OfficeParserConfig officeParserConfig = context.get(OfficeParserConfig.class);
        if (officeParserConfig == null || !officeParserConfig.isExtractMacros()) {
            return;
        }
        try {
            for (PackagePart source : getMainDocumentParts()) {
                if (source == null) {
                    continue;
                }
                for (PackageRelationship rel : source.getRelationships()) {
                    if (!XSSFRelation.VBA_MACROS.getRelation().equals(rel.getRelationshipType())) {
                        continue;
                    }
                    if (rel.getTargetMode() != TargetMode.INTERNAL) {
                        continue;
                    }
                    try {
                        PackagePart target = source.getRelatedPart(rel);
                        if (target == null) {
                            continue;
                        }
                        String targetPartName =
                                target.getPartName().getName();
                        if (!macroTargetsHandledEarly.add(targetPartName)) {
                            continue;
                        }
                        handleMacros(target, xhtml, metadata);
                    } catch (SAXException | SecurityException e) {
                        throw e;
                    } catch (Exception e) {
                        EmbeddedDocumentUtil.recordEmbeddedStreamException(e, metadata);
                    }
                }
            }
        } catch (InvalidFormatException e) {
            throw new TikaException("Broken OOXML file", e);
        }
    }

    private Set<String> handleEmbeddedParts(
            XHTMLContentHandler xhtml, Metadata metadata,
            Map<String, EmbeddedPartMetadata> embeddedPartMetadataMap,
            ExternalReferenceBudget externalReferenceBudget)
            throws TikaException, IOException, SAXException {
        //keep track of media items that have been handled
        //there can be multiple relationships pointing to the
        //same underlying media item.  We only want to process
        //the underlying media item once.
        Set<String> handledInternalPartNames = new HashSet<>();
        Set<String> handledRelationshipParts = new HashSet<>();
        try {
            for (PackagePart source : getMainDocumentParts()) {
                if (source == null) {
                    //parts can go missing; silently ignore --  TIKA-2134
                    continue;
                }
                String sourcePartName = source.getPartName() == null
                        ? null : source.getPartName().getName();
                if (sourcePartName != null
                        && !handledRelationshipParts.add(sourcePartName)) {
                    continue;
                }
                for (PackageRelationship rel : source.getRelationships()) {
                    try {
                        handleEmbeddedPart(source, rel, xhtml, metadata,
                                embeddedPartMetadataMap, handledInternalPartNames,
                                externalReferenceBudget);
                    } catch (SAXException | SecurityException e) {
                        throw e;
                    } catch (Exception e) {
                        EmbeddedDocumentUtil.recordEmbeddedStreamException(e, metadata);
                    }
                }
            }
        } catch (InvalidFormatException e) {
            throw new TikaException("Broken OOXML file", e);
        }
        return handledRelationshipParts;
    }

    private void handleEmbeddedPart(PackagePart source, PackageRelationship rel,
                                    XHTMLContentHandler xhtml, Metadata parentMetadata,
                                    Map<String, EmbeddedPartMetadata> embeddedPartMetadataMap,
                                    Set<String> handledInternalPartNames,
                                    ExternalReferenceBudget externalReferenceBudget)
            throws IOException, SAXException, TikaException, InvalidFormatException {
        URI targetURI = rel.getTargetURI();
        URI sourceURI = rel.getSourceURI();
        String sourceDesc;
        if (sourceURI != null) {
            sourceDesc = getJustFileName(sourceURI.getPath());
            if (sourceDesc.startsWith("slide")) {
                sourceDesc += "_";
            } else {
                sourceDesc = "";
            }
        } else {
            sourceDesc = "";
        }
        if (rel.getTargetMode() != TargetMode.INTERNAL) {
            if (targetURI == null || targetURI.toString().isEmpty()) {
                return;
            }
            String sourcePath = normalizePartName(
                    sourceURI != null ? sourceURI.getPath() : "");
            if (!externalReferenceBudget.tryAcquire(sourcePath, rel)) {
                return;
            }
            // External target - emit as external reference for security analysis
            String type = rel.getRelationshipType();
            String refType = externalRefType(type);
            emitExternalRef(xhtml, parentMetadata, refType, targetURI.toString(),
                    sourcePath, type, rel.getId(),
                    !externalReferenceBudget.wasMetadataPreRecorded(
                            sourcePath, rel));
            setHasFlagFor(type, parentMetadata);
            return;
        }
        PackagePart target;

        try {
            target = source.getRelatedPart(rel);
        } catch (IllegalArgumentException ex) {
            return;
        }
        String targetPartName = target.getPartName().getName();
        if (handledInternalPartNames.contains(targetPartName)) {
            return;
        }
        EmbeddedPartMetadata embeddedPartMetadata = embeddedPartMetadataMap.get(rel.getId());
        String type = rel.getRelationshipType();
        updateParentMetadata(parentMetadata, embeddedPartMetadata);
        if (OLE_OBJECT_REL_TYPE.equals(type) &&
                TYPE_OLE_OBJECT.equals(target.getContentType())) {
            handleEmbeddedOLE(target, xhtml, sourceDesc + rel.getId(), parentMetadata,
                    embeddedPartMetadata);
            handledInternalPartNames.add(targetPartName);
        } else if (PackageRelationshipTypes.IMAGE_PART.equals(type)) {
            handleEmbeddedFile(target, xhtml, sourceDesc + rel.getId(),
                    embeddedPartMetadata, TikaCoreProperties.EmbeddedResourceType.INLINE);
            handledInternalPartNames.add(targetPartName);
        } else if (RELATION_MEDIA.equals(type) || RELATION_VIDEO.equals(type) ||
                RELATION_AUDIO.equals(type) ||
                PACK_OBJECT_REL_TYPE.equals(type) ||
                OLE_OBJECT_REL_TYPE.equals(type)) {
            handleEmbeddedFile(target, xhtml, sourceDesc + rel.getId(),
                    embeddedPartMetadata,
                    TikaCoreProperties.EmbeddedResourceType.ATTACHMENT);
            handledInternalPartNames.add(targetPartName);
        } else if (XSSFRelation.VBA_MACROS.getRelation().equals(type)) {
            // Skip if handleMacrosEarly() already emitted this macro part (it runs before
            // buildXHTML so the VBA source survives the cumulative write-limit).
            if (!macroTargetsHandledEarly.contains(targetPartName)) {
                handleMacros(target, xhtml, parentMetadata);
            }
            handledInternalPartNames.add(targetPartName);
        } else if (RELATION_ALTERNATE_FORMAT_CHUNK.equals(type)) {
            //TODO check for targetMode=INTERNAL?
            handleEmbeddedFile(target, xhtml, sourceDesc + rel.getId(),
                    embeddedPartMetadata,
                    TikaCoreProperties.EmbeddedResourceType.ALTERNATE_FORMAT_CHUNK);
            handledInternalPartNames.add(targetPartName);
        }
    }


    /**
     * Handles an embedded OLE object in the document
     */
    private void handleEmbeddedOLE(PackagePart part, XHTMLContentHandler xhtml, String rel,
                                   Metadata parentMetadata,
                                   EmbeddedPartMetadata embeddedPartMetadata) throws IOException,
            SAXException, TikaException {
        // A POIFSFileSystem needs to be at least 3 blocks big to be valid
        if (part.getSize() >= 0 && part.getSize() < 512 * 3) {
            // Too small, skip
            return;
        }

        // Open the POIFS (OLE2) structure and process
        POIFSFileSystem fs;
        try {
            fs = new POIFSFileSystem(part.getInputStream());
        } catch (Exception e) {
            EmbeddedDocumentUtil.recordEmbeddedStreamException(e, parentMetadata);
            return;
        }
        TikaInputStream tis = null;
        try {
            Metadata metadata = Metadata.newInstance(context);
            metadata.set(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE,
                    TikaCoreProperties.EmbeddedResourceType.ATTACHMENT.name());
            metadata.set(TikaCoreProperties.EMBEDDED_RELATIONSHIP_ID, rel);
            metadata.set(TikaCoreProperties.INTERNAL_PATH, part.getPartName().getName());

            DirectoryNode root = fs.getRoot();
            POIFSDocumentType type = POIFSDocumentType.detectType(root);

            String packageEntryName = getPackageEntryName(root);
            try {
                SummaryExtractor summaryExtractor = new SummaryExtractor(metadata);
                summaryExtractor.parseSummaries(root);
            } catch (TikaException e) {
                //swallow -- things happened
            }
            if (packageEntryName != null) {
                //OLE 2.0
                updateMetadata(metadata, embeddedPartMetadata);
                updateParentMetadata(parentMetadata, embeddedPartMetadata);

                tis = TikaInputStream.get(fs.createDocumentInputStream(packageEntryName));
                if (embeddedExtractor.shouldParseEmbedded(metadata)) {
                    embeddedExtractor
                            .parseEmbedded(tis, xhtml, metadata, context, true);
                }
            } else if (hasNonCanonicalOle10Native(root)) {
                // POI 5.5+ resolves Ole10Native names case-insensitively. Handle
                // non-canonical names here first so the obfuscation remains
                // visible and the payload is never materialized as one large
                // attacker-sized byte array.
                handleOle10NativeFallback(fs, xhtml, rel, embeddedPartMetadata,
                        parentMetadata);
            } else if (POIFSDocumentType.OLE10_NATIVE == type) {
                // TIKA-704: OLE 1.0 embedded document
                Ole10Native ole = Ole10Native.createFromEmbeddedOleObject(fs);
                if (ole.getLabel() != null) {
                    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, ole.getLabel());
                    // Resolve CLSID name for the label if available
                    String progIdFromLabel = org.apache.tika.parser.microsoft.OleClsidNames
                            .lookup(metadata.get(org.apache.tika.metadata.RTFMetadata.EMB_CLSID));
                    if (progIdFromLabel != null) {
                        metadata.set(org.apache.tika.metadata.RTFMetadata.EMB_CLSID_NAME,
                                progIdFromLabel);
                    }
                }
                if (ole.getCommand() != null) {
                    metadata.add(TikaCoreProperties.ORIGINAL_RESOURCE_NAME, ole.getCommand());
                }
                if (ole.getFileName() != null) {
                    metadata.add(TikaCoreProperties.ORIGINAL_RESOURCE_NAME, ole.getFileName());
                }
                // Propagate autoLoad flag — this OLE object executes on open.
                updateParentMetadata(parentMetadata, embeddedPartMetadata);
                byte[] data = ole.getDataBuffer();
                if (data != null) {
                    tis = TikaInputStream.get(data);
                }

                if (tis != null && embeddedExtractor.shouldParseEmbedded(metadata)) {
                    embeddedExtractor
                            .parseEmbedded(tis, xhtml, metadata, context, true);
                }
            } else {
                handleEmbeddedFile(part, xhtml, rel, embeddedPartMetadata,
                        TikaCoreProperties.EmbeddedResourceType.ATTACHMENT);
            }
        } catch (FileNotFoundException e) {
            // There was no CONTENTS entry, so skip this part
        } catch (Ole10NativeException e) {
            handleOle10NativeFallback(fs, xhtml, rel, embeddedPartMetadata,
                    parentMetadata);
        } catch (IOException e) {
            EmbeddedDocumentUtil.recordEmbeddedStreamException(e, parentMetadata);
        } finally {
            try {
                if (tis != null) {
                    tis.close();
                }
            } finally {
                fs.close();
            }
        }
    }

    private void handleOle10NativeFallback(POIFSFileSystem fs,
                                           XHTMLContentHandler xhtml,
                                           String rel,
                                           EmbeddedPartMetadata embeddedPartMetadata,
                                           Metadata parentMetadata)
            throws SAXException {
        try {
            Ole10NativePayload payload =
                    openOle10NativeCaseInsensitive(fs.getRoot(), parentMetadata);
            if (payload == null) {
                return;
            }
            try (TikaInputStream stream = TikaInputStream.get(payload.stream())) {
                if (payload.length() <= 0) {
                    return;
                }
                Metadata metadata = Metadata.newInstance(context);
                metadata.set(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE,
                        TikaCoreProperties.EmbeddedResourceType.ATTACHMENT.name());
                metadata.set(TikaCoreProperties.EMBEDDED_RELATIONSHIP_ID, rel);
                metadata.set(org.apache.tika.metadata.HttpHeaders.CONTENT_LENGTH,
                        Long.toString(payload.length()));
                // Synthetic name so the worker can save the file for hashing.
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, rel + ".bin");
                updateParentMetadata(parentMetadata, embeddedPartMetadata);
                if (embeddedExtractor.shouldParseEmbedded(metadata)) {
                    embeddedExtractor.parseEmbedded(
                            stream, xhtml, metadata, context, true);
                }
            }
        } catch (SAXException exception) {
            throw exception;
        } catch (IOException exception) {
            EmbeddedDocumentUtil.recordEmbeddedStreamException(
                    exception, parentMetadata);
        }
    }

    private void updateMetadata(Metadata metadata, EmbeddedPartMetadata embeddedPartMetadata) {
        if (embeddedPartMetadata == null) {
            return;
        }
        if (! StringUtils.isBlank(embeddedPartMetadata.getProgId())) {
            metadata.set(Office.PROG_ID, embeddedPartMetadata.getProgId());
        }
        if (embeddedPartMetadata.isAutoLoad()) {
            // propagate to parent document (passed as parentMetadata by caller)
            metadata.set(Office.OOXML_OLE_AUTO_EXEC, true);
        }
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, embeddedPartMetadata.getFullName());
    }

    private void updateParentMetadata(Metadata parentMetadata,
                                      EmbeddedPartMetadata embeddedPartMetadata) {
        if (embeddedPartMetadata == null) {
            return;
        }
        if (embeddedPartMetadata.isAutoLoad()) {
            parentMetadata.set(Office.OOXML_OLE_AUTO_EXEC, true);
        }
        if (embeddedPartMetadata.isSuspiciousProgId()
                && !StringUtils.isBlank(embeddedPartMetadata.getProgId())) {
            parentMetadata.add(Office.OOXML_OLE_SUSPICIOUS_PROG_IDS,
                    embeddedPartMetadata.getProgId());
        }
    }

    /**
     * Case-insensitive fallback for Ole10Native stream lookup. Returns a
     * bounded stream over the actual embedded payload without allocating an
     * array sized by an attacker-controlled directory entry. Malformed
     * records retain the previous best-effort raw extraction behavior.
     */
    private static Ole10NativePayload openOle10NativeCaseInsensitive(
            org.apache.poi.poifs.filesystem.DirectoryNode root,
            Metadata parentMetadata) throws IOException {
        for (org.apache.poi.poifs.filesystem.Entry e : root) {
            if (!(e instanceof org.apache.poi.poifs.filesystem.DocumentEntry)) {
                continue;
            }
            // POI entry names include the \x01 control-char prefix for OLE system streams.
            // Strip a leading control character before case-insensitive comparison.
            String entryName = e.getName();
            String nameSuffix = (entryName.length() > 1 && entryName.charAt(0) < 0x20)
                    ? entryName.substring(1) : entryName;
            if (!nameSuffix.equalsIgnoreCase("Ole10Native")) {
                continue;
            }
            // Non-canonical casing → flag obfuscation. Canonical is "\x01Ole10Native".
            if (!entryName.equals("\u0001Ole10Native")) {
                parentMetadata.set(org.apache.tika.metadata.RTFMetadata.EMB_CLASS_OBFUSCATED, true);
            }
            org.apache.poi.poifs.filesystem.DocumentEntry de =
                    (org.apache.poi.poifs.filesystem.DocumentEntry) e;
            int maxRecordLength = Ole10Native.getMaxRecordLength();
            if (de.getSize() > maxRecordLength) {
                throw new IOException(
                        "Ole10Native record length " + de.getSize()
                                + " exceeds configured maximum "
                                + maxRecordLength);
            }
            try {
                return openValidOle10NativePayload(de, maxRecordLength);
            } catch (Ole10NativeException malformed) {
                return openMalformedOle10NativePayload(de);
            }
        }
        return null;
    }

    private static Ole10NativePayload openValidOle10NativePayload(
            org.apache.poi.poifs.filesystem.DocumentEntry entry,
            int maxRecordLength)
            throws IOException, Ole10NativeException {
        org.apache.poi.poifs.filesystem.DocumentInputStream stream =
                new org.apache.poi.poifs.filesystem.DocumentInputStream(entry);
        try {
            int totalSize = readLittleEndianInt(stream);
            if (totalSize < 2
                    || totalSize > maxRecordLength
                    || totalSize > entry.getSize() - Integer.BYTES) {
                throw new Ole10NativeException("invalid Ole10Native total size");
            }

            byte[] flags = readBytes(stream, Short.BYTES);
            int flags1 = (flags[0] & 0xFF) | ((flags[1] & 0xFF) << Byte.SIZE);
            if (flags1 != 2) {
                InputStream payload = new SequenceInputStream(
                        new ByteArrayInputStream(flags), stream);
                return new Ole10NativePayload(
                        bounded(payload, totalSize), totalSize);
            }

            requireRemaining(totalSize - Short.BYTES, 1);
            int firstLabelByte = readByte(stream);
            if (Character.isISOControl((byte) firstLabelByte)) {
                long payloadLength = totalSize - Short.BYTES;
                InputStream payload = new SequenceInputStream(
                        new ByteArrayInputStream(new byte[]{(byte) firstLabelByte}),
                        stream);
                return new Ole10NativePayload(
                        bounded(payload, payloadLength), payloadLength);
            }

            int remaining = totalSize - Short.BYTES;
            int labelLength = skipAsciiZ(stream, firstLabelByte, remaining);
            remaining -= labelLength;
            requireRemaining(remaining, 1);
            int fileNameLength = skipAsciiZ(stream, readByte(stream), remaining);
            remaining -= fileNameLength;
            skip(stream, 2 * Short.BYTES, remaining);
            remaining -= 2 * Short.BYTES;

            requireRemaining(remaining, Integer.BYTES);
            int commandLength = readLittleEndianInt(stream);
            remaining -= Integer.BYTES;
            if (commandLength < 0
                    || commandLength > Ole10Native.getMaxStringLength()) {
                throw new Ole10NativeException("invalid Ole10Native command length");
            }
            skip(stream, commandLength, remaining);
            remaining -= commandLength;

            requireRemaining(remaining, Integer.BYTES);
            int payloadLength = readLittleEndianInt(stream);
            remaining -= Integer.BYTES;
            if (payloadLength < 0 || payloadLength > remaining) {
                throw new Ole10NativeException("invalid Ole10Native payload length");
            }
            return new Ole10NativePayload(
                    bounded(stream, payloadLength), payloadLength);
        } catch (IOException | Ole10NativeException | RuntimeException exception) {
            stream.close();
            throw exception;
        }
    }

    private static Ole10NativePayload openMalformedOle10NativePayload(
            org.apache.poi.poifs.filesystem.DocumentEntry entry)
            throws IOException {
        org.apache.poi.poifs.filesystem.DocumentInputStream stream =
                new org.apache.poi.poifs.filesystem.DocumentInputStream(entry);
        try {
            byte[] prefix = new byte[Integer.BYTES];
            int read = readUpTo(stream, prefix);
            if (read == prefix.length) {
                int length = littleEndianInt(prefix);
                if (length > 0 && length <= entry.getSize() - prefix.length) {
                    return new Ole10NativePayload(
                            bounded(stream, length), length);
                }
            }
            InputStream fullEntry = new SequenceInputStream(
                    new ByteArrayInputStream(prefix, 0, read), stream);
            return new Ole10NativePayload(fullEntry, entry.getSize());
        } catch (IOException | RuntimeException exception) {
            stream.close();
            throw exception;
        }
    }

    private static int skipAsciiZ(InputStream stream, int firstByte,
                                  int remaining)
            throws IOException, Ole10NativeException {
        int length = 1;
        if (firstByte == 0) {
            return length;
        }
        int maximum = Math.min(remaining, Ole10Native.getMaxStringLength());
        while (length < maximum) {
            length++;
            if (readByte(stream) == 0) {
                return length;
            }
        }
        throw new Ole10NativeException(
                "Ole10Native string is not null terminated");
    }

    private static void skip(InputStream stream, int length, int remaining)
            throws IOException, Ole10NativeException {
        requireRemaining(remaining, length);
        byte[] buffer = new byte[Math.min(length, 1024)];
        int skipped = 0;
        while (skipped < length) {
            int read = stream.read(buffer, 0,
                    Math.min(buffer.length, length - skipped));
            if (read < 0) {
                throw new Ole10NativeException(
                        "unexpected end of Ole10Native record");
            }
            skipped += read;
        }
    }

    private static void requireRemaining(int remaining, int required)
            throws Ole10NativeException {
        if (required < 0 || required > remaining) {
            throw new Ole10NativeException("invalid Ole10Native field length");
        }
    }

    private static byte[] readBytes(InputStream stream, int length)
            throws IOException, Ole10NativeException {
        byte[] bytes = new byte[length];
        if (readUpTo(stream, bytes) != length) {
            throw new Ole10NativeException(
                    "unexpected end of Ole10Native record");
        }
        return bytes;
    }

    private static int readUpTo(InputStream stream, byte[] bytes)
            throws IOException {
        int read = 0;
        while (read < bytes.length) {
            int count = stream.read(bytes, read, bytes.length - read);
            if (count < 0) {
                break;
            }
            read += count;
        }
        return read;
    }

    private static int readByte(InputStream stream)
            throws IOException, Ole10NativeException {
        int value = stream.read();
        if (value < 0) {
            throw new Ole10NativeException(
                    "unexpected end of Ole10Native record");
        }
        return value;
    }

    private static int readLittleEndianInt(InputStream stream)
            throws IOException, Ole10NativeException {
        return littleEndianInt(readBytes(stream, Integer.BYTES));
    }

    private static int littleEndianInt(byte[] bytes) {
        return (bytes[0] & 0xFF) | ((bytes[1] & 0xFF) << Byte.SIZE)
                | ((bytes[2] & 0xFF) << 2 * Byte.SIZE)
                | ((bytes[3] & 0xFF) << 3 * Byte.SIZE);
    }

    private static InputStream bounded(InputStream stream, long length)
            throws IOException {
        return BoundedInputStream.builder()
                .setInputStream(stream)
                .setMaxCount(length)
                .setPropagateClose(true)
                .get();
    }

    private record Ole10NativePayload(InputStream stream, long length) {
    }

    private static boolean hasNonCanonicalOle10Native(
            org.apache.poi.poifs.filesystem.DirectoryNode root) {
        for (org.apache.poi.poifs.filesystem.Entry e : root) {
            if (!(e instanceof org.apache.poi.poifs.filesystem.DocumentEntry)) {
                continue;
            }
            String name = e.getName();
            String suffix = (name.length() > 1 && name.charAt(0) < 0x20) ? name.substring(1) : name;
            if (suffix.equalsIgnoreCase("Ole10Native")
                    && !name.equals("\u0001Ole10Native")) {
                return true;
            }
        }
        return false;
    }

    private String getPackageEntryName(DirectoryNode root) {
        if (root.hasEntry("\u0001Ole")) {
            //we used to require this too: root.hasEntry("\u0001CompObj") before TIKA-3526
            if (root.hasEntry("Package")) {
                return "Package";
            } else if (root.hasEntry("CONTENTS")) {
                return "CONTENTS";
            } else if (root.hasEntry("package")) {
                return "package";
            }
        }
        if (root.hasEntry("package")) {
            return "package";
        }
        /*
            raw CorelDraw stream may be in an ole bundle
            but there can be other resources for the image
            in other streams under root...think about this...
            see: AZG2X4VXB3KIEDT3OVZC4R645KU5VSOF
        if (root.hasEntry("CorelDRAW")) {
            return "CorelDRAW";
        }*/
        return null;
    }

    /**
     * Emit a macro as a first-class MACRO embedded entry — the parity counterpart
     * to how VBA modules surface (an entry with {@code embeddedResourceType=MACRO}).
     * The macro source/formula text becomes the entry body. Used for both VBA and
     * XLM (Excel 4.0) macro sheets so a downstream consumer can find every macro by
     * scanning for MACRO entries rather than special-casing XLM metadata flags.
     *
     * @param name        resource name (module or macro-sheet name)
     * @param contentType media type, e.g. {@code text/x-vbasic} or {@code text/x-excel-macro}
     * @param text        the macro text (entry body); blank text is skipped
     */
    protected void emitMacroText(String name, String contentType, String text,
                                 XHTMLContentHandler xhtml)
            throws IOException, SAXException, TikaException {
        // Honor the macro-extraction toggle (parity with the VBA path), so MACRO entries are
        // suppressed when the caller didn't request macros.
        if (config == null || !config.isExtractMacros()) {
            return;
        }
        if (text == null || text.isBlank()) {
            return;
        }
        Metadata m = Metadata.newInstance(context);
        m.set(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE,
                TikaCoreProperties.EmbeddedResourceType.MACRO.toString());
        if (contentType != null) {
            m.set(Metadata.CONTENT_TYPE, contentType);
        }
        if (name != null && !name.isBlank()) {
            m.set(TikaCoreProperties.RESOURCE_NAME_KEY, name);
        }
        if (embeddedExtractor.shouldParseEmbedded(m)) {
            try (TikaInputStream tis =
                         TikaInputStream.get(text.getBytes(StandardCharsets.UTF_8))) {
                embeddedExtractor.parseEmbedded(tis, xhtml, m, context, true);
            }
        }
    }

    /**
     * Handles an embedded file in the document.  Invokes
     * {@link #applyEmbeddedAnchorMetadata} so paginated/sheeted
     * subclasses can tag the embedded resource's metadata with the
     * pages or sheets it is anchored to.
     */
    protected void handleEmbeddedFile(PackagePart part, XHTMLContentHandler xhtml,
                                      String rel,
                                      EmbeddedPartMetadata embeddedPartMetadata,
                                      TikaCoreProperties.EmbeddedResourceType embeddedResourceType)
            throws SAXException, IOException, TikaException {
        Metadata metadata = Metadata.newInstance(context);
        metadata.set(TikaCoreProperties.EMBEDDED_RELATIONSHIP_ID, rel);
        metadata.set(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE,
                embeddedResourceType.name());
        metadata.set(TikaCoreProperties.INTERNAL_PATH, part.getPartName().getName());

        // Get the name
        updateResourceName(part, embeddedPartMetadata, metadata);

        // Get the content type
        metadata.set(Metadata.CONTENT_TYPE, part.getContentType());

        applyEmbeddedAnchorMetadata(part, metadata);

        // Call the recursing handler
        if (embeddedExtractor.shouldParseEmbedded(metadata)) {
            try (TikaInputStream tis = TikaInputStream.get(part.getInputStream())) {
                embeddedExtractor
                        .parseEmbedded(tis, xhtml, metadata, context, true);
            }
        }
    }

    private void updateResourceName(PackagePart part, EmbeddedPartMetadata embeddedPartMetadata,
                                    Metadata metadata) {

        if (embeddedPartMetadata != null) {
            if (! StringUtils.isBlank(embeddedPartMetadata.getProgId())) {
                metadata.set(Office.PROG_ID, embeddedPartMetadata.getProgId());
            }
            String fullName = embeddedPartMetadata.getFullName();
            if (!StringUtils.isBlank(fullName)) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fullName);
                return;
            }
        }
        //TODO -- should we record the literal name of the embedded file?
        String name = part.getPartName().getName();
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash > -1) {
            name = name.substring(lastSlash + 1);
        }
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, name);
    }

    /**
     * Emits an external reference as an anchor element with appropriate class.
     * Used for detecting external resources that could be security risks.
     */
    private void emitExternalRef(XHTMLContentHandler xhtml, Metadata metadata, String refType,
                                 String url, String source, String relationshipType, String id,
                                 boolean addMetadata)
            throws SAXException {
        if (url == null || url.isEmpty()) {
            return;
        }
        if (addMetadata) {
            OfficeLinkMetadataUtil.addLink(metadata,
                    OfficeLinkMetadataUtil.normalizeType(refType), url, null, null,
                    source, "relationship", relationshipType, id);
        }
        AttributesImpl attrs = new AttributesImpl();
        attrs.addAttribute("", "class", "class", "CDATA", "external-ref-" + refType);
        attrs.addAttribute("", "href", "href", "CDATA", url);
        xhtml.startElement("a", attrs);
        xhtml.endElement("a");
    }

    /**
     * Walk every PackagePart not already processed by the main-document pass
     * and surface external-mode relationships that the pass would otherwise miss. Each
     * relationship type maps to a short refType string used by the
     * {@link OfficeLinkMetadataUtil} link index and the body {@code <a>} ref.
     *
     * <p>Targets like {@code attachedTemplate}, {@code frame}, {@code oleObject},
     * {@code subDocument}, {@code externalLink}, {@code hyperlink} can appear
     * in any {@code .rels} file in an OOXML package — webSettings.xml.rels,
     * footnotes.xml.rels, comments.xml.rels, slideN.xml.rels, sheetN.xml.rels,
     * etc. The existing {@link #handleEmbeddedParts} loop walks only the
     * "main" parts, so anything attached via a non-main rels file was silently
     * dropped before this method ran.</p>
     */
    /** Hard cap on external-ref emissions per document. A crafted package can
     *  list the same URL under many fabricated relationship types — without
     *  this cap a single doc could produce unbounded link-metadata entries. */
    private static final int MAX_EXTERNAL_REFS_PER_DOC = 1024;

    /**
     * Hard cap on the number of package parts scanned -- SHARED, cumulative
     * across BOTH {@link #createExternalReferenceBudget} (the pre-pass, which
     * runs before {@link #handleMacrosEarly} / {@link #buildXHTML} emit any
     * content, so the cumulative write-limit / WriteLimitReachedException
     * cannot bound it) and {@link #surfaceExternalRefsFromAllParts} (the
     * catch-all walk that runs AFTER buildXHTML). MAX_EXTERNAL_REFS_PER_DOC
     * only bounds how many *matching* high-priority/external relationships
     * are recorded or emitted -- not how many parts are examined to find
     * them. A package padded with a huge number of otherwise-empty parts
     * (e.g. thousands of trivial customXml/theme/chart parts, none of which
     * carry a matching relationship) would force either loop to call
     * part.getRelationships() -- itself a relationship-XML parse -- once per
     * part, unbounded by part count. That is a real gap in
     * surfaceExternalRefsFromAllParts() too: once ExternalReferenceBudget
     * #tryAcquire() stops being able to throw (MAX_EXTERNAL_REFS_PER_DOC
     * already reserved, or -- the exact padding shape here -- a part simply
     * has zero relationships), nothing in that loop's body can throw a
     * write-limit SAXException, so the cumulative write-limit interlock the
     * loop otherwise relies on to terminate early never fires, and the loop
     * runs to O(part count) completion.
     *
     * <p>The cap is a SINGLE budget shared across both loops, charged ONCE PER DISTINCT
     * PART ({@link ExternalReferenceBudget#tryScanPart(PackagePart)}). An earlier revision
     * charged per ATTEMPT and justified it by claiming per-loop budgets would force "~2x
     * expensive part scans". That justification was wrong: POI caches the parsed
     * relationship collection on the PackagePart, so only the FIRST getRelationships() for
     * a given part parses XML and the second pass is an in-memory filter. Charging twice
     * bought nothing and halved real coverage to 2,500 parts -- a 2,501-part package lost
     * the external hyperlink in its last part. Per-distinct-part keeps the true worst case
     * at one XML parse per part while actually delivering the advertised coverage.</p>
     */
    /**
     * Backstop on parts examined for external references. Deliberately set ABOVE POI's hard
     * ceiling of 10,000 zip entries per package ({@code ZipSecureFile.setMaxFileCount(10000)}
     * in {@link OOXMLParser}), so it CANNOT fire for any zip-backed package POI will open.
     *
     * <p>It was 5,000, and that was actively harmful. The pre-pass does not merely optimise:
     * it RESERVES high-priority relationship keys, and {@code tryAcquire} refuses any
     * high-priority reference the pre-pass never reached. The main-document walk is not
     * part-capped, so with a 5,000 cap it still FOUND an external attachedTemplate past the
     * cap and then discarded it -- silently destroying remote-template / external-oleObject
     * detection. Measured on a real 5,100-junk-part package: the reference surfaced before
     * the cap existed and vanished after. It was attacker-controlled, because
     * {@code getParts()} is ordered by part name, so junk named {@code /customXml/...} sorts
     * ahead of {@code /word/document.xml} and starves it; and it was SELECTIVE for exactly
     * the dangerous relationship types (attachedTemplate/oleObject/frame suppressed while
     * plain hyperlinks survived).
     *
     * <p>The cap also bought far less than its original comment claimed: with POI's 10,000
     * entry ceiling the pre-existing worst case was ~10k cached relationship reads, so a
     * 5,000 cap saved 2x -- not protection from an "unbounded" part count. Trading 2x for
     * the loss of executable-reference detection is the wrong trade for this fork.
     *
     * <p>Kept as a true backstop rather than deleted: it still bounds a hypothetical
     * non-zip-backed OPCPackage (a directory- or stream-backed package, or a future build
     * that raises the zip ceiling), and it reports itself when it fires.
     */
    private static final int MAX_EXTERNAL_REF_PARTS_SCANNED = 20_000;

    private void surfaceExternalRefsFromAllParts(
            XHTMLContentHandler xhtml, Metadata metadata,
            Set<String> handledRelationshipParts,
            ExternalReferenceBudget externalReferenceBudget)
            throws SAXException {
        if (opcPackage == null) return;
        // Preserve relationship semantics while suppressing only exact duplicate
        // records. A benign root hyperlink must not hide a later executable
        // relationship that happens to target the same URL.
        java.util.Set<String> seen = new java.util.HashSet<>();
        // Package-level relationships (/_rels/.rels at the OPC root). These
        // sit ABOVE any part and could in principle carry an external Target
        // (e.g. a malformed/handcrafted package). opcPackage.getParts() does
        // not include the root, so check it separately.
        try {
            PackageRelationshipCollection rootRels = opcPackage.getRelationships();
            if (rootRels != null) {
                surfaceExternalRels(
                        xhtml, metadata, rootRels, "_rels/.rels", seen,
                        externalReferenceBudget);
            }
        } catch (SAXException e) {
            WriteLimitReachedException.throwIfWriteLimitReached(e);
            throw e;
        } catch (SecurityException e) {
            throw e;
        } catch (Exception ignored) {
            // best-effort
        }
        try {
            for (PackagePart part : opcPackage.getParts()) {
                if (part == null || part.getPartName() == null) continue;
                String partName = part.getPartName().getName();
                if (handledRelationshipParts.contains(partName)) continue;
                // Shared cap with createExternalReferenceBudget()'s pre-pass loop: once
                // the combined total of parts scanned by BOTH loops hits
                // MAX_EXTERNAL_REF_PARTS_SCANNED, stop -- this loop is not bounded by
                // the write-limit once tryAcquire() stops being able to throw (cap
                // already reserved, or a part simply has zero relationships), so it
                // needs its own explicit part-count bound.
                if (!externalReferenceBudget.tryScanPart(part)) {
                    break;
                }
                PackageRelationshipCollection rels;
                try {
                    externalReferenceBudget.countPartRelationshipRead();
                    rels = part.getRelationships();
                } catch (SecurityException e) {
                    throw e;
                } catch (Exception e) {
                    continue;
                }
                if (rels == null) continue;
                surfaceExternalRels(
                        xhtml, metadata, rels, partName, seen,
                        externalReferenceBudget);
            }
        } catch (SAXException e) {
            WriteLimitReachedException.throwIfWriteLimitReached(e);
            throw e;
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            // never fail the parse over a relationship walk
        }
    }

    /**
     * Emit any external-mode relationships from {@code rels} that haven't
     * already been recorded in {@code seen}. Shared between the per-part walk
     * and the package-level root rels walk. Honors the per-doc emission cap.
     */
    private void surfaceExternalRels(XHTMLContentHandler xhtml, Metadata metadata,
                                     PackageRelationshipCollection rels, String partName,
                                     java.util.Set<String> seen,
                                     ExternalReferenceBudget externalReferenceBudget)
            throws SAXException {
        for (PackageRelationship rel : rels) {
            if (rel.getTargetMode() != TargetMode.EXTERNAL) continue;
            if (rel.getTargetURI() == null) continue;
            String url = rel.getTargetURI().toString();
            if (url.isEmpty()) continue;
            String sourceName = normalizePartName(partName);
            String relationshipKey = sourceName + "\u0000" + rel.getId()
                    + "\u0000" + rel.getRelationshipType() + "\u0000" + url;
            if (seen.contains(relationshipKey)) continue;
            if (!externalReferenceBudget.tryAcquire(sourceName, rel)) continue;
            seen.add(relationshipKey);

            String refType = externalRefType(rel.getRelationshipType());
            try {
                emitExternalRef(xhtml, metadata, refType, url,
                        sourceName, rel.getRelationshipType(), rel.getId(),
                        !externalReferenceBudget.wasMetadataPreRecorded(
                                sourceName, rel));
            } catch (SAXException e) {
                WriteLimitReachedException.throwIfWriteLimitReached(e);
                throw e;
            }
            // Light HAS_* flag heuristics so downstream filters know which
            // categories appeared. Types without a HAS_* constant still get
            // an addLink entry via emitExternalRef.
            setHasFlagFor(rel.getRelationshipType(), metadata);
        }
    }

    private ExternalReferenceBudget createExternalReferenceBudget(
            Metadata metadata) {
        ExternalReferenceBudget budget = new ExternalReferenceBudget();
        if (opcPackage == null) {
            return budget;
        }
        try {
            budget.preRecordHighPriority(
                    opcPackage.getRelationships(), "_rels/.rels", metadata);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception ignored) {
            // best-effort
        }
        try {
            for (PackagePart part : opcPackage.getParts()) {
                if (part == null || part.getPartName() == null) continue;
                // Shared cap with surfaceExternalRefsFromAllParts()'s catch-all loop --
                // see MAX_EXTERNAL_REF_PARTS_SCANNED's javadoc. tryScanPart() itself
                // marks the budget truncated once the shared cap is hit.
                if (!budget.tryScanPart(part)) {
                    break;
                }
                try {
                    budget.countPartRelationshipRead();
                    budget.preRecordHighPriority(
                            part.getRelationships(),
                            normalizePartName(part.getPartName().getName()),
                            metadata);
                } catch (SecurityException e) {
                    throw e;
                } catch (Exception ignored) {
                    continue;
                }
                if (budget.hasReservedMaximum()) {
                    break;
                }
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception ignored) {
            // best-effort
        }
        return budget;
    }

    private static boolean isHighPriorityExternalRelationship(
            String relationshipType) {
        switch (shortRelType(relationshipType)) {
            case "attachedTemplate":
            case "ddeLink":
            case "externalLink":
            case "frame":
            case "oleObject":
            case "subDocument":
                return true;
            default:
                return false;
        }
    }

    private static final class ExternalReferenceBudget {
        private final Set<String> admittedRelationshipKeys =
                new HashSet<>();
        private final Set<String> reservedHighPriorityKeys =
                new HashSet<>();
        private final Set<String> preRecordedMetadataKeys =
                new HashSet<>();
        private int remainingHighPriority;
        private int emitted;
        private boolean truncated;
        // Cumulative parts scanned across BOTH createExternalReferenceBudget()'s
        // pre-pass and surfaceExternalRefsFromAllParts()'s catch-all walk -- see
        // MAX_EXTERNAL_REF_PARTS_SCANNED's javadoc for why this must be one shared
        // budget rather than a separate allowance per loop.
        private int partsScanned;
        // Counts ACTUAL part.getRelationships() invocations across both walks --
        // i.e. the real, expensive scan work, independent of whether a guard was
        // consulted first. partsScanned only advances when tryScanPart() is
        // called, so a regression that DELETES a tryScanPart() guard would leave
        // partsScanned looking healthy while the relationship parsing ran
        // unbounded. This counter is what makes that regression observable.
        private int partRelationshipReads;

        private void preRecordHighPriority(
                PackageRelationshipCollection relationships,
                String source, Metadata metadata) {
            if (relationships == null) {
                return;
            }
            for (PackageRelationship relationship : relationships) {
                if (reservedHighPriorityKeys.size()
                        >= MAX_EXTERNAL_REFS_PER_DOC) {
                    return;
                }
                if (relationship.getTargetMode() != TargetMode.EXTERNAL
                        || relationship.getTargetURI() == null
                        || relationship.getTargetURI().toString().isEmpty()
                        || !isHighPriorityExternalRelationship(
                        relationship.getRelationshipType())) {
                    continue;
                }
                String key = relationshipKey(source, relationship);
                if (!reservedHighPriorityKeys.add(key)) {
                    continue;
                }
                remainingHighPriority++;
                long before = getStoredOfficeLinkTargetCount(metadata);
                String relationshipType =
                        relationship.getRelationshipType();
                OfficeLinkMetadataUtil.addLink(
                        metadata,
                        OfficeLinkMetadataUtil.normalizeType(
                                externalRefType(relationshipType)),
                        relationship.getTargetURI().toString(),
                        null, null, source, "relationship",
                        relationshipType, relationship.getId());
                if (getStoredOfficeLinkTargetCount(metadata) > before) {
                    preRecordedMetadataKeys.add(key);
                }
                setHasFlagFor(relationshipType, metadata);
            }
        }

        private static long getStoredOfficeLinkTargetCount(
                Metadata metadata) {
            // Auxiliary aligned fields can fit after the URL becomes an empty
            // placeholder; only these fields prove that the target survived.
            return countNonEmptyValues(
                    metadata.getValues(Office.OFFICE_LINK_URL))
                    + countNonEmptyValues(
                    metadata.getValues(Office.OFFICE_LINK_RECORD));
        }

        private static long countNonEmptyValues(String[] values) {
            long count = 0;
            for (String value : values) {
                if (value != null && !value.isEmpty()) {
                    count++;
                }
            }
            return count;
        }

        private boolean hasReservedMaximum() {
            return reservedHighPriorityKeys.size()
                    >= MAX_EXTERNAL_REFS_PER_DOC;
        }

        private boolean tryAcquire(
                String source, PackageRelationship relationship) {
            String key = relationshipKey(source, relationship);
            if (admittedRelationshipKeys.contains(key)) {
                return false;
            }
            boolean highPriority = isHighPriorityExternalRelationship(
                    relationship.getRelationshipType());
            if (highPriority
                    && !reservedHighPriorityKeys.contains(key)) {
                truncated = true;
                return false;
            }
            int remaining = MAX_EXTERNAL_REFS_PER_DOC - emitted;
            if (remaining <= 0
                    || !highPriority && remaining <= remainingHighPriority) {
                truncated = true;
                return false;
            }
            admittedRelationshipKeys.add(key);
            if (highPriority) {
                remainingHighPriority--;
            }
            emitted++;
            return true;
        }

        private boolean wasMetadataPreRecorded(
                String source, PackageRelationship relationship) {
            return preRecordedMetadataKeys.contains(
                    relationshipKey(source, relationship));
        }

        private void markTruncation(Metadata metadata) {
            if (truncated) {
                OfficeLinkMetadataUtil.markLinkLimitReached(metadata);
            }
            if (partScanTruncated) {
                // A DISTINCT signal. Reusing the link-limit warning misattributed the
                // cause: it says "too many links to record" when the truth is "part of the
                // package was never examined", and it fired even on documents with zero
                // links recorded. Those two have very different follow-ups, and a consumer
                // that treats link-volume warnings as benign noise would discard the only
                // indication that parts went unscanned.
                metadata.set("msoffice:external-ref-part-scan-limit-reached", "true");
                metadata.set(TikaCoreProperties.TRUNCATED_METADATA, true);
                metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                        "External-reference part scan stopped after "
                                + MAX_EXTERNAL_REF_PARTS_SCANNED
                                + " parts; later parts were not examined for external "
                                + "references");
            }
        }

        /** Set only by the part-scan bound, so its signal stays distinguishable. */
        private boolean partScanTruncated;

        private void markPartScanTruncation() {
            partScanTruncated = true;
        }

        /** Test-only instrumentation: counts actual getRelationships() calls. */
        private void countPartRelationshipRead() {
            partRelationshipReads++;
        }

        int getPartRelationshipReadsForTesting() {
            return partRelationshipReads;
        }

        /** Part names already charged, so the two passes cannot double-bill one part. */
        private final Set<String> scannedPartNames = new HashSet<>();

        /**
         * Charge a part against the shared scan budget, ONCE PER DISTINCT PART.
         *
         * <p>Both {@link #createExternalReferenceBudget} (pre-pass) and
         * {@link #surfaceExternalRefsFromAllParts} (catch-all) iterate the SAME
         * {@code opcPackage.getParts()}. Charging per ATTEMPT therefore billed every part
         * twice and halved the real coverage to MAX_EXTERNAL_REF_PARTS_SCANNED/2: measured,
         * a 2,501-part package already lost the external hyperlink in its last part, and at
         * 5,000 parts even part 0 became unreachable. Keying on the part name makes the
         * second pass free for parts the pre-pass already paid for, which is also what the
         * cost model actually is -- see the note on getRelationships() caching below.
         *
         * <p>Callers must still call this BEFORE {@code part.getRelationships()} and break
         * as soon as it returns false.
         */
        private boolean tryScanPart(PackagePart part) {
            String key = part == null || part.getPartName() == null
                    ? null : part.getPartName().getName();
            if (key != null && scannedPartNames.contains(key)) {
                // Already paid for by the other pass; POI caches the parsed relationship
                // collection per part, so re-reading it costs no XML parse.
                return true;
            }
            if (partsScanned >= MAX_EXTERNAL_REF_PARTS_SCANNED) {
                // Deliberately does NOT set : that keys the link-limit warning
                // ("additional links were skipped") plus an ExploitClass stamp, which is
                // the misattribution this signal exists to replace. Setting both meant a
                // 5,001-part package with ZERO links still reported a link-volume
                // truncation. Part-scan truncation reports only its own flag.
                markPartScanTruncation();
                return false;
            }
            if (key != null) {
                scannedPartNames.add(key);
            }
            partsScanned++;
            return true;
        }

        /** Visible for testing: total parts scanned across both loops so far. */
        int getPartsScannedForTesting() {
            return partsScanned;
        }
    }

    /** Visible for testing: package-private so tests can assert on the shared
     *  part-scan cap without exposing it as part of the public API. */
    static int getMaxExternalRefPartsScannedForTesting() {
        return MAX_EXTERNAL_REF_PARTS_SCANNED;
    }

    private static String relationshipKey(
            String source, PackageRelationship relationship) {
        return normalizePartName(source) + "\u0000"
                + relationship.getId() + "\u0000"
                + relationship.getRelationshipType() + "\u0000"
                + relationship.getTargetURI();
    }

    private static String normalizePartName(String partName) {
        if (partName == null) {
            return "";
        }
        return partName.startsWith("/")
                ? partName.substring(1) : partName;
    }

    private static String externalRefType(String relationshipType) {
        if (OLE_OBJECT_REL_TYPE.equals(relationshipType)) {
            return "externalOleObject";
        }
        if (PackageRelationshipTypes.IMAGE_PART.equals(
                relationshipType)) {
            return "externalImage";
        }
        return shortRelType(relationshipType);
    }

    /** Trim a fully-qualified relationship type URI down to its last path component. */
    private static String shortRelType(String relType) {
        if (relType == null || relType.isEmpty()) return "externalRef";
        int slash = relType.lastIndexOf('/');
        return slash >= 0 && slash < relType.length() - 1
                ? relType.substring(slash + 1) : relType;
    }

    /** Best-effort HAS_* flag setter for known external relationship types. */
    private static void setHasFlagFor(String relType, Metadata metadata) {
        if (relType == null) return;
        switch (shortRelType(relType)) {
            case "oleObject":
                metadata.set(org.apache.tika.metadata.Office.HAS_EXTERNAL_OLE_OBJECTS, true);
                break;
            case "attachedTemplate":
                metadata.set(org.apache.tika.metadata.Office.HAS_ATTACHED_TEMPLATE, true);
                break;
            case "ddeLink":
                metadata.set(org.apache.tika.metadata.Office.HAS_DDE_LINKS, true);
                break;
            case "externalLink":
                metadata.set(org.apache.tika.metadata.Office.HAS_EXTERNAL_LINKS, true);
                break;
            case "subDocument":
                metadata.set(org.apache.tika.metadata.Office.HAS_SUBDOCUMENTS, true);
                break;
            case "frame":
                metadata.set(org.apache.tika.metadata.Office.HAS_FRAMESETS, true);
                break;
            default:
                // no HAS_* mapping — the addLink metadata is enough
        }
    }

    /**
     * Populates the {@link XHTMLContentHandler} object received as parameter.
     */
    protected abstract void buildXHTML(XHTMLContentHandler xhtml)
            throws SAXException, IOException;

    /**
     * Return a list of the main parts of the document, used
     * when searching for embedded resources.
     * This should be all the parts of the document that end
     * up with things embedded into them.
     */
    protected abstract List<PackagePart> getMainDocumentParts() throws TikaException;


    void handleMacros(PackagePart macroPart, ContentHandler handler, Metadata metadata)
            throws TikaException, SAXException {
        OfficeParserConfig officeParserConfig = context.get(OfficeParserConfig.class);

        if (officeParserConfig.isExtractMacros()) {
            if (vbaBounds == null) {
                vbaBounds = LenientVBAReader.Bounds.fromConfig(officeParserConfig);
            }
            try (InputStream is = macroPart.getInputStream()) {
                try (POIFSFileSystem poifs = new POIFSFileSystem(is)) {
                    //Macro reading exceptions are already swallowed here
                    OfficeParser.extractMacros(poifs, handler, embeddedExtractor, context,
                            metadata, vbaBounds);
                }
            } catch (IOException e) {
                throw new TikaException("Broken OOXML file", e);
            }
        }
    }

    /**
     * This is used by the SAX docx and pptx decorators to load hyperlinks and
     * other linked objects
     *
     * @param bodyPart
     * @return
     */
    protected Map<String, String> loadLinkedRelationships(PackagePart bodyPart,
                                                          boolean includeInternal,
                                                          Metadata metadata) {
        Map<String, String> relationships =
                loadLinkedRelationships(bodyPart, includeInternal, metadata,
                        linkedRelationshipCollectionBudget);
        if (linkedRelationshipCollectionBudget.isLimitReached()) {
            signalLinkedRelationshipCollectionLimit(metadata);
        }
        return relationships;
    }

    protected Map<String, String> loadLinkedRelationships(
            PackagePart bodyPart, boolean includeInternal, Metadata metadata,
            OOXMLPartContentCollector.CollectionBudget collectionBudget) {
        Map<String, String> linkedRelationships = new HashMap<>();
        try {
            PackageRelationshipCollection prc =
                    bodyPart.getRelationshipsByType(PackageRelationshipTypes.HYPERLINK_PART);
            for (int i = 0; i < prc.size(); i++) {
                PackageRelationship pr = prc.getRelationship(i);
                if (pr == null) {
                    continue;
                }
                if (!includeInternal && TargetMode.INTERNAL.equals(pr.getTargetMode())) {
                    continue;
                }
                String id = pr.getId();
                String url = (pr.getTargetURI() == null) ? null : pr.getTargetURI().toString();
                if (id != null && url != null) {
                    if (collectionBudget != null &&
                            !collectionBudget.tryRetainAuxiliaryEntry(id, url)) {
                        return linkedRelationships;
                    }
                    linkedRelationships.put(id, url);
                }
            }

            for (String rel : EMBEDDED_RELATIONSHIPS) {

                prc = bodyPart.getRelationshipsByType(rel);
                for (int i = 0; i < prc.size(); i++) {
                    PackageRelationship pr = prc.getRelationship(i);
                    if (pr == null) {
                        continue;
                    }
                    String id = pr.getId();
                    String uriString =
                            (pr.getTargetURI() == null) ? null : pr.getTargetURI().toString();
                    String fileName = uriString;
                    if (pr.getTargetURI() != null) {
                        try {
                            fileName = FileHelper.getFilename(new File(fileName));
                        } catch (Exception e) {
                            fileName = uriString;
                        }
                    }
                    if (id != null) {
                        fileName = (fileName == null) ? "" : fileName;
                        if (collectionBudget != null &&
                                !collectionBudget.tryRetainAuxiliaryEntry(
                                        id, fileName)) {
                            return linkedRelationships;
                        }
                        linkedRelationships.put(id, fileName);
                    }
                }
            }

        } catch (InvalidFormatException e) {
            EmbeddedDocumentUtil.recordEmbeddedStreamException(e, metadata);
        }
        return linkedRelationships;
    }

    private static void signalLinkedRelationshipCollectionLimit(
            Metadata metadata) {
        metadata.set(TikaCoreProperties.TRUNCATED_METADATA, true);
        boolean warningAlreadyPresent = false;
        for (String warning : metadata.getValues(
                TikaCoreProperties.TIKA_META_EXCEPTION_WARNING)) {
            if (LINKED_RELATIONSHIP_COLLECTION_WARNING.equals(warning)) {
                warningAlreadyPresent = true;
                break;
            }
        }
        if (!warningAlreadyPresent) {
            metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                    LINKED_RELATIONSHIP_COLLECTION_WARNING);
        }
        if (metadata.get("ExploitClass") == null) {
            metadata.set("ExploitClass",
                    "OOXML linked-relationship collection incomplete");
        }
    }

    /**
     * This should handle the comments, master, notes, with the streaming "general docx/pptx
     * handler"
     *
     * @param contentType
     * @param xhtmlClassLabel
     * @param parentPart
     * @param contentHandler
     */
    /**
     * Safely resolves a related part, returning null if the part cannot be found
     * instead of throwing {@link IllegalArgumentException}.
     */
    public static PackagePart safeGetRelatedPart(PackagePart source,
                                           PackageRelationship relationship)
            throws InvalidFormatException {
        if (source == null || relationship == null) {
            return null;
        }
        if (!source.isRelationshipExists(relationship)) {
            return null;
        }
        try {
            return source.getRelatedPart(relationship);
        } catch (IllegalArgumentException e) {
            // Relationship exists but target part is missing from the package
            return null;
        }
    }

    void handleGeneralTextContainingPart(String contentType, String xhtmlClassLabel,
                                         PackagePart parentPart, Metadata parentMetadata,
                                         ContentHandler contentHandler) throws SAXException {

        PackageRelationshipCollection relatedPartPRC = null;

        try {
            relatedPartPRC = parentPart.getRelationshipsByType(contentType);
        } catch (InvalidFormatException e) {
            parentMetadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                    ExceptionUtils.getStackTrace(e));
        }
        if (relatedPartPRC != null && relatedPartPRC.size() > 0) {
            AttributesImpl attributes = new AttributesImpl();

            attributes.addAttribute("", "class", "class",
                    "CDATA", xhtmlClassLabel);
            contentHandler.startElement("", "div", "div", attributes);
            for (int i = 0; i < relatedPartPRC.size(); i++) {
                PackageRelationship relatedPartPackageRelationship =
                        relatedPartPRC.getRelationship(i);
                try {
                    PackagePart relatedPartPart =
                            safeGetRelatedPart(parentPart, relatedPartPackageRelationship);
                    if (relatedPartPart == null) {
                        continue;
                    }
                    // Wrap the contentHandler so we can close anything the
                    // inner parser left open if it throws mid-element. Without
                    // this, the </div> emitted after the loop would land on
                    // top of an open <p>/<td>/etc. from the failed sub-parse.
                    TaggedContentHandler taggedHandler =
                            new TaggedContentHandler(contentHandler);
                    XHTMLBalancingHandler balancer =
                            new XHTMLBalancingHandler(taggedHandler);
                    try (InputStream stream = relatedPartPart.getInputStream()) {
                        XMLReaderUtils.parseSAX(stream,
                                new EmbeddedContentHandler(balancer), context);

                    } catch (IOException | TikaException e) {
                        drainOpenElements(balancer, taggedHandler);
                        parentMetadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                                ExceptionUtils.getStackTrace(e));
                    } catch (SAXException e) {
                        taggedHandler.throwIfCauseOf(e);
                        drainOpenElements(balancer, taggedHandler);
                        WriteLimitReachedException.throwIfWriteLimitReached(e);
                        parentMetadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                                ExceptionUtils.getStackTrace(e));
                    }
                } catch (InvalidFormatException e) {
                    parentMetadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                            ExceptionUtils.getStackTrace(e));
                }
            }
            contentHandler.endElement("", "div", "div");
        }

    }

    private static void drainOpenElements(
            XHTMLBalancingHandler balancer,
            TaggedContentHandler taggedHandler) throws SAXException {
        try {
            balancer.drainOpenElements();
        } catch (SAXException cleanupFailure) {
            taggedHandler.throwIfCauseOf(cleanupFailure);
            throw cleanupFailure;
        }
    }

}
