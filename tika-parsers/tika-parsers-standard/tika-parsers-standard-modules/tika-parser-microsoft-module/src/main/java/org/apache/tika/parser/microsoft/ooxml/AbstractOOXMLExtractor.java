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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.microsoft.OfficeLinkMetadataUtil;
import org.apache.tika.parser.microsoft.OfficeParser;
import org.apache.tika.parser.microsoft.OfficeParser.POIFSDocumentType;
import org.apache.tika.parser.microsoft.OfficeParserConfig;
import org.apache.tika.parser.microsoft.SummaryExtractor;
import org.apache.tika.sax.EmbeddedContentHandler;
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


    private final EmbeddedDocumentExtractor embeddedExtractor;
    private final ParseContext context;
    protected OfficeParserConfig config;
    protected OPCPackage opcPackage;

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
        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata, context);
        xhtml.startDocument();

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
        handleEmbeddedParts(xhtml, metadata, getEmbeddedPartMetadataMap());

        // Catch-all: walk EVERY part's relationships for external Targets, not
        // just main-document parts. Settings/footnotes/comments/header/footer/
        // webSettings/sheet/slide .rels can all carry attachedTemplate /
        // oleObject / frame / subDocument / hyperlink relations with
        // TargetMode=External, and handleEmbeddedParts() above only walks
        // getMainDocumentParts(). Frame URLs in webSettings.xml.rels were the
        // historical example — generalize to anything analogous.
        surfaceExternalRefsFromAllParts(xhtml, metadata);

        // thumbnail
        handleThumbnail(xhtml, metadata);

        xhtml.endDocument();
    }

    protected Map<String, EmbeddedPartMetadata> getEmbeddedPartMetadataMap() {
        return Collections.emptyMap();
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

    // VBA macro targets already emitted by handleMacrosEarly(), so the normal
    // embedded-part walk in handleEmbeddedPart() does not emit them a second time.
    private final Set<String> macroTargetsHandledEarly = new HashSet<>();

    /**
     * Emit VBA macros ahead of the body content so they are not starved by the cumulative
     * write-limit when the document is padded with junk body text. Walks the same parts as
     * {@link #handleEmbeddedParts} but only for the {@code VBA_MACROS} relationship, and
     * records the handled targets in {@link #macroTargetsHandledEarly}. Non-fatal: a broken
     * macro part is recorded on the metadata and never fails the overall parse (mirroring
     * the swallow in handleEmbeddedParts). A SAXException (e.g. a write-limit signal) is
     * allowed to propagate, exactly as the body path would.
     */
    private void handleMacrosEarly(XHTMLContentHandler xhtml, Metadata metadata)
            throws TikaException, IOException, SAXException {
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
                        handleMacros(target, xhtml);
                        URI targetURI = rel.getTargetURI();
                        if (targetURI != null) {
                            macroTargetsHandledEarly.add(targetURI.toString());
                        }
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

    private void handleEmbeddedParts(XHTMLContentHandler xhtml, Metadata metadata,
                                     Map<String, EmbeddedPartMetadata> embeddedPartMetadataMap)
            throws TikaException, IOException, SAXException {
        //keep track of media items that have been handled
        //there can be multiple relationships pointing to the
        //same underlying media item.  We only want to process
        //the underlying media item once.
        Set<String> handledTarget = new HashSet<>();
        try {
            for (PackagePart source : getMainDocumentParts()) {
                if (source == null) {
                    //parts can go missing; silently ignore --  TIKA-2134
                    continue;
                }
                for (PackageRelationship rel : source.getRelationships()) {
                    try {
                        handleEmbeddedPart(source, rel, xhtml, metadata,
                                embeddedPartMetadataMap, handledTarget);
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

    private void handleEmbeddedPart(PackagePart source, PackageRelationship rel,
                                    XHTMLContentHandler xhtml, Metadata parentMetadata,
                                    Map<String, EmbeddedPartMetadata> embeddedPartMetadataMap,
                                    Set<String> handledTarget)
            throws IOException, SAXException, TikaException, InvalidFormatException {
        URI targetURI = rel.getTargetURI();
        if (targetURI != null) {
            if (handledTarget.contains(targetURI.toString())) {
                return;
            }
        }

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
            // External target - emit as external reference for security analysis
            String type = rel.getRelationshipType();
            String sourcePath = sourceURI != null ? sourceURI.getPath() : "";
            if (OLE_OBJECT_REL_TYPE.equals(type)) {
                emitExternalRef(xhtml, parentMetadata, "externalOleObject", targetURI.toString(),
                        sourcePath, type, rel.getId());
                parentMetadata.set(Office.HAS_EXTERNAL_OLE_OBJECTS, true);
            } else if (PackageRelationshipTypes.IMAGE_PART.equals(type)) {
                emitExternalRef(xhtml, parentMetadata, "externalImage", targetURI.toString(),
                        sourcePath, type, rel.getId());
            } else {
                emitExternalRef(xhtml, parentMetadata, "externalResource", targetURI.toString(),
                        sourcePath, type, rel.getId());
            }
            return;
        }
        PackagePart target;

        try {
            target = source.getRelatedPart(rel);
        } catch (IllegalArgumentException ex) {
            return;
        }
        EmbeddedPartMetadata embeddedPartMetadata = embeddedPartMetadataMap.get(rel.getId());
        String type = rel.getRelationshipType();
        updateParentMetadata(parentMetadata, embeddedPartMetadata);
        if (OLE_OBJECT_REL_TYPE.equals(type) &&
                TYPE_OLE_OBJECT.equals(target.getContentType())) {
            handleEmbeddedOLE(target, xhtml, sourceDesc + rel.getId(), parentMetadata,
                    embeddedPartMetadata);
            if (targetURI != null) {
                handledTarget.add(targetURI.toString());
            }
        } else if (PackageRelationshipTypes.IMAGE_PART.equals(type)) {
            handleEmbeddedFile(target, xhtml, sourceDesc + rel.getId(),
                    embeddedPartMetadata, TikaCoreProperties.EmbeddedResourceType.INLINE);
            if (targetURI != null) {
                handledTarget.add(targetURI.toString());
            }
        } else if (RELATION_MEDIA.equals(type) || RELATION_VIDEO.equals(type) ||
                RELATION_AUDIO.equals(type) ||
                PACK_OBJECT_REL_TYPE.equals(type) ||
                OLE_OBJECT_REL_TYPE.equals(type)) {
            handleEmbeddedFile(target, xhtml, sourceDesc + rel.getId(),
                    embeddedPartMetadata,
                    TikaCoreProperties.EmbeddedResourceType.ATTACHMENT);
            if (targetURI != null) {
                handledTarget.add(targetURI.toString());
            }
        } else if (XSSFRelation.VBA_MACROS.getRelation().equals(type)) {
            // Skip if handleMacrosEarly() already emitted this macro part (it runs before
            // buildXHTML so the VBA source survives the cumulative write-limit).
            if (targetURI == null || !macroTargetsHandledEarly.contains(targetURI.toString())) {
                handleMacros(target, xhtml);
            }
            if (targetURI != null) {
                handledTarget.add(targetURI.toString());
            }
        } else if (RELATION_ALTERNATE_FORMAT_CHUNK.equals(type)) {
            //TODO check for targetMode=INTERNAL?
            handleEmbeddedFile(target, xhtml, sourceDesc + rel.getId(),
                    embeddedPartMetadata,
                    TikaCoreProperties.EmbeddedResourceType.ALTERNATE_FORMAT_CHUNK);
            if (targetURI != null) {
                handledTarget.add(targetURI.toString());
            }
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
            } else if (POIFSDocumentType.OLE10_NATIVE == type) {
                // TIKA-704: OLE 1.0 embedded document
                // Note: Ole10Native.createFromEmbeddedOleObject is case-sensitive.
                // Malware uses obfuscated stream names (e.g. \x01oLE10nAtiVe) to evade it;
                // the Ole10NativeException catch below handles that case.
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
            } else if (hasOle10NativeLike(root)) {
                // detectType() missed the Ole10Native stream because the stream name uses
                // non-canonical casing (e.g. \x01oLE10nAtiVe). Surface an Ole10NativeException
                // so the case-insensitive fallback catch below extracts the payload.
                throw new Ole10NativeException("non-canonical Ole10Native stream name");
            } else {
                handleEmbeddedFile(part, xhtml, rel, embeddedPartMetadata,
                        TikaCoreProperties.EmbeddedResourceType.ATTACHMENT);
            }
        } catch (FileNotFoundException e) {
            // There was no CONTENTS entry, so skip this part
        } catch (Ole10NativeException e) {
            // POI's Ole10Native lookup is case-sensitive.  Fall back to a case-insensitive
            // stream scan — malware uses "\x01oLE10nAtiVe" to evade exact-match detection.
            try {
                byte[] data = readOle10NativeCaseInsensitive(fs.getRoot(), parentMetadata);
                if (data != null && data.length > 0) {
                    tis = TikaInputStream.get(data);
                    Metadata m2 = Metadata.newInstance(context);
                    m2.set(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE,
                            TikaCoreProperties.EmbeddedResourceType.ATTACHMENT.name());
                    m2.set(TikaCoreProperties.EMBEDDED_RELATIONSHIP_ID, rel);
                    m2.set(org.apache.tika.metadata.HttpHeaders.CONTENT_LENGTH,
                            Integer.toString(data.length));
                    // Synthetic name so the worker can save the file for hashing.
                    m2.set(TikaCoreProperties.RESOURCE_NAME_KEY, rel + ".bin");
                    updateParentMetadata(parentMetadata, embeddedPartMetadata);
                    if (embeddedExtractor.shouldParseEmbedded(m2)) {
                        embeddedExtractor.parseEmbedded(tis, xhtml, m2, context, true);
                    }
                }
            } catch (Exception ignore) {
                // non-fatal
            }
        } catch (IOException e) {
            EmbeddedDocumentUtil.recordEmbeddedStreamException(e, parentMetadata);
        } finally {
            fs.close();
            if (tis != null) {
                tis.close();
            }
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
     * Case-insensitive fallback for Ole10Native stream lookup.
     * Returns the OLE1 payload bytes (after the 4-byte length prefix) or null.
     */
    private static byte[] readOle10NativeCaseInsensitive(
            org.apache.poi.poifs.filesystem.DirectoryNode root, Metadata parentMetadata)
            throws java.io.IOException {
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
            byte[] raw = new byte[de.getSize()];
            try (org.apache.poi.poifs.filesystem.DocumentInputStream dis =
                         new org.apache.poi.poifs.filesystem.DocumentInputStream(de)) {
                int read = 0;
                while (read < raw.length) {
                    int n = dis.read(raw, read, raw.length - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
            }
            if (raw.length > 4) {
                int len = (raw[0] & 0xFF) | ((raw[1] & 0xFF) << 8)
                        | ((raw[2] & 0xFF) << 16) | ((raw[3] & 0xFF) << 24);
                if (len > 0 && len <= raw.length - 4) {
                    byte[] payload = new byte[len];
                    System.arraycopy(raw, 4, payload, 0, len);
                    return payload;
                }
            }
            return raw;
        }
        return null;
    }

    private static boolean hasOle10NativeLike(org.apache.poi.poifs.filesystem.DirectoryNode root) {
        for (org.apache.poi.poifs.filesystem.Entry e : root) {
            if (!(e instanceof org.apache.poi.poifs.filesystem.DocumentEntry)) {
                continue;
            }
            String name = e.getName();
            String suffix = (name.length() > 1 && name.charAt(0) < 0x20) ? name.substring(1) : name;
            if (suffix.equalsIgnoreCase("Ole10Native")) {
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
     * Handles an embedded file in the document
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
                                 String url, String source, String relationshipType, String id)
            throws SAXException {
        if (url == null || url.isEmpty()) {
            return;
        }
        OfficeLinkMetadataUtil.addLink(metadata,
                OfficeLinkMetadataUtil.normalizeType(refType), url, null, null,
                source, "relationship", relationshipType, id);
        AttributesImpl attrs = new AttributesImpl();
        attrs.addAttribute("", "class", "class", "CDATA", "external-ref-" + refType);
        attrs.addAttribute("", "href", "href", "CDATA", url);
        xhtml.startElement("a", attrs);
        xhtml.endElement("a");
    }

    /**
     * Walk every PackagePart in the package and surface external-mode
     * relationships that the main-doc walk would otherwise miss. Each
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

    private void surfaceExternalRefsFromAllParts(XHTMLContentHandler xhtml, Metadata metadata) {
        if (opcPackage == null) return;
        // Dedup ON URL ALONE so an attacker can't bloat by mentioning the same
        // URL under N fabricated relationship types. Forensically this means we
        // keep the FIRST relType we see for any given URL — typically the most
        // specific one (frame/attachedTemplate beats hyperlink because the
        // type-specific walks elsewhere often run first via the per-part
        // iteration order POI returns).
        java.util.Set<String> seen = new java.util.HashSet<>();
        // Hard cap to bound total emissions. Counted across the package root
        // and every part. Once exceeded, downstream emissions are silently
        // skipped — the link index still has the first MAX_EXTERNAL_REFS_PER_DOC
        // links, which is more than any legitimate doc would carry.
        int[] emitted = new int[]{0};
        // Package-level relationships (/_rels/.rels at the OPC root). These
        // sit ABOVE any part and could in principle carry an external Target
        // (e.g. a malformed/handcrafted package). opcPackage.getParts() does
        // not include the root, so check it separately.
        try {
            PackageRelationshipCollection rootRels = opcPackage.getRelationships();
            if (rootRels != null) {
                surfaceExternalRels(xhtml, metadata, rootRels, "_rels/.rels", seen, emitted);
            }
        } catch (Exception ignored) {
            // best-effort
        }
        try {
            for (PackagePart part : opcPackage.getParts()) {
                if (emitted[0] >= MAX_EXTERNAL_REFS_PER_DOC) break;
                if (part == null || part.getPartName() == null) continue;
                String partName = part.getPartName().getName();
                PackageRelationshipCollection rels;
                try {
                    rels = part.getRelationships();
                } catch (Exception e) {
                    continue;
                }
                if (rels == null) continue;
                surfaceExternalRels(xhtml, metadata, rels, partName, seen, emitted);
            }
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
                                     java.util.Set<String> seen, int[] emitted) {
        for (PackageRelationship rel : rels) {
            if (emitted[0] >= MAX_EXTERNAL_REFS_PER_DOC) return;
            if (rel.getTargetMode() != TargetMode.EXTERNAL) continue;
            if (rel.getTargetURI() == null) continue;
            String url = rel.getTargetURI().toString();
            if (url.isEmpty()) continue;
            // Dedup on URL alone — see class-level comment in
            // surfaceExternalRefsFromAllParts on attacker URL-bloat.
            if (!seen.add(url)) continue;

            String refType = shortRelType(rel.getRelationshipType());
            try {
                emitExternalRef(xhtml, metadata, refType, url,
                        partName.startsWith("/") ? partName.substring(1) : partName,
                        rel.getRelationshipType(), rel.getId());
                emitted[0]++;
            } catch (SAXException e) {
                // best-effort — never fail the parse over a link surface
            }
            // Light HAS_* flag heuristics so downstream filters know which
            // categories appeared. Types without a HAS_* constant still get
            // an addLink entry via emitExternalRef.
            setHasFlagFor(rel.getRelationshipType(), metadata);
        }
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


    void handleMacros(PackagePart macroPart, ContentHandler handler)
            throws TikaException, SAXException {
        OfficeParserConfig officeParserConfig = context.get(OfficeParserConfig.class);

        if (officeParserConfig.isExtractMacros()) {
            try (InputStream is = macroPart.getInputStream()) {
                try (POIFSFileSystem poifs = new POIFSFileSystem(is)) {
                    //Macro reading exceptions are already swallowed here
                    OfficeParser.extractMacros(poifs, handler, embeddedExtractor, context);
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
                        linkedRelationships.put(id, fileName);
                    }
                }
            }

        } catch (InvalidFormatException e) {
            EmbeddedDocumentUtil.recordEmbeddedStreamException(e, metadata);
        }
        return linkedRelationships;
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
                    try (InputStream stream = relatedPartPart.getInputStream()) {
                        XMLReaderUtils.parseSAX(stream,
                                new EmbeddedContentHandler(contentHandler), context);

                    } catch (IOException | TikaException e) {
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

}
