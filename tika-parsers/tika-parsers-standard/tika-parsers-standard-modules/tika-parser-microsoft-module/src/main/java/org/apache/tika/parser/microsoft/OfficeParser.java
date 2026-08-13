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
package org.apache.tika.parser.microsoft;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.poi.hdgf.extractor.VisioTextExtractor;
import org.apache.poi.hpbf.extractor.PublisherTextExtractor;
import org.apache.poi.poifs.crypt.Decryptor;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.poifs.macros.VBAMacroReader;
import org.apache.poi.util.LocaleUtil;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.annotation.TikaComponent;
import org.apache.tika.config.ConfigDeserializer;
import org.apache.tika.config.JsonConfig;
import org.apache.tika.detect.microsoft.POIFSContainerDetector;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.EmbeddedDocumentUtil;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.PasswordProvider;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.EmbeddedContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;
import org.apache.tika.utils.StringUtils;

/**
 * Defines a Microsoft document content extractor.
 */
@TikaComponent
public class OfficeParser extends AbstractOfficeParser {

    /**
     * Serial version UID
     */
    private static final long serialVersionUID = 7393462244028653479L;

    private static final Set<MediaType> SUPPORTED_TYPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(POIFSDocumentType.WORKBOOK.type,
                    POIFSDocumentType.OLE10_NATIVE.type, POIFSDocumentType.WORDDOCUMENT.type,
                    POIFSDocumentType.UNKNOWN.type, POIFSDocumentType.ENCRYPTED.type,
                    POIFSDocumentType.DRMENCRYPTED.type,
                    POIFSDocumentType.POWERPOINT.type, POIFSDocumentType.PUBLISHER.type,
                    POIFSDocumentType.PROJECT.type, POIFSDocumentType.VISIO.type,
                    // Works isn't supported
                    POIFSDocumentType.XLR.type, // but Works 7.0 Spreadsheet is
                    POIFSDocumentType.OUTLOOK.type, POIFSDocumentType.SOLIDWORKS_PART.type,
                    POIFSDocumentType.SOLIDWORKS_ASSEMBLY.type,
                    POIFSDocumentType.SOLIDWORKS_DRAWING.type)));

    public OfficeParser() {
    }

    public OfficeParser(OfficeParserConfig config) {
        setDefaultOfficeParserConfig(config);
    }

    public OfficeParser(JsonConfig jsonConfig) {
        this(ConfigDeserializer.buildConfig(jsonConfig, OfficeParserConfig.class));
    }

    /**
     * Helper to extract macros from an NPOIFS/vbaProject.bin
     * <p>
     * As of POI-3.15-final, there are still some bugs in VBAMacroReader.
     * For now, we are swallowing NPE and other runtime exceptions
     *
     * @param fs                        NPOIFS to extract from
     * @param xhtml                     SAX writer
     * @param embeddedDocumentExtractor extractor for embedded documents
     * @param context                   parse context for creating metadata
     * @throws IOException  on IOException if it occurs during the extraction of the embedded doc
     * @throws SAXException on SAXException for writing to xhtml
     */
    public static void extractMacros(POIFSFileSystem fs, ContentHandler xhtml,
                                     EmbeddedDocumentExtractor embeddedDocumentExtractor,
                                     ParseContext context)
            throws IOException, SAXException, TikaException {
        extractMacros(fs, xhtml, embeddedDocumentExtractor, context, null);
    }

    /**
     * As above, but records on {@code parentMetadata} when a VBA size bound discarded or
     * truncated macro source. Pass the parent metadata wherever it is available: without it
     * the loss is invisible, and a caller cannot distinguish a 40-line macro from a 12 MB
     * module that was dropped whole.
     */
    /**
     * Surface a fired VBA size bound on the parent metadata.
     *
     * <p>MUST be called on EVERY exit from {@link #extractMacros}. The reporting used to sit only at
     * the end, AFTER the early {@code return} taken when the lenient reader recovered nothing -- which
     * is the PRIMARY lenient-reader path (the catch around POI's reader, the whole reason
     * LenientVBAReader exists) and precisely the case a fired bound produces. A document whose VBA
     * stream exceeded the bound was therefore reported as "no recoverable macros" with no truncation
     * flag at all.
     */
    private static void reportVbaBounds(LenientVBAReader.Bounds bounds, Metadata parentMetadata) {
        if (bounds == null || !bounds.isLimitReached() || parentMetadata == null) {
            return;
        }
        // One accumulator is shared across every macro part of a container and reported from each
        // part's finally, so without this the same detail lands on the metadata once per part.
        if (!bounds.claimReport()) {
            return;
        }
        parentMetadata.set("msoffice:vba-capture-limit-reached", "true");
        parentMetadata.set(TikaCoreProperties.TRUNCATED_METADATA, true);
        parentMetadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING, bounds.getLimitDetail());
    }

    public static void extractMacros(POIFSFileSystem fs, ContentHandler xhtml,
                                     EmbeddedDocumentExtractor embeddedDocumentExtractor,
                                     ParseContext context, Metadata parentMetadata)
            throws IOException, SAXException, TikaException {
        extractMacros(fs, xhtml, embeddedDocumentExtractor, context, parentMetadata, null);
    }

    /**
     * As above, but reusing {@code sharedBounds} -- one accumulator for every macro-bearing part of
     * ONE container -- instead of starting a fresh allowance for this call.
     *
     * <p>A container may hold several VBA projects: an OOXML package can declare any number of
     * {@code vbaProject} relationships (walked by {@code AbstractOOXMLExtractor.handleMacrosEarly},
     * deduplicated only by target part name) and a {@code .ppt} may hold several embedded objects
     * under the author-chosen {@code persistId} the VBAInfoAtom names. Every one of those was a
     * separate call and therefore a separate allowance, so what the bound's javadoc, its metadata
     * message and its tests all called a per-DOCUMENT ceiling was really per macro part -- N parts
     * cost N times the ceiling, at a cost to the author of a few kilobytes of zip entry each.
     *
     * <p>Callers with a single macro part should keep passing {@code null}: an unshared accumulator
     * starting at zero is exactly what one part gets either way.
     */
    public static void extractMacros(POIFSFileSystem fs, ContentHandler xhtml,
                                     EmbeddedDocumentExtractor embeddedDocumentExtractor,
                                     ParseContext context, Metadata parentMetadata,
                                     LenientVBAReader.Bounds sharedBounds)
            throws IOException, SAXException, TikaException {

        // Shared across every reader attempt below so a bound that fires in any one of them is
        // reported once on the parent metadata -- from a finally, so no exit path can skip it.
        LenientVBAReader.Bounds vbaBounds = sharedBounds != null ? sharedBounds
                : LenientVBAReader.Bounds.fromConfig(context.get(OfficeParserConfig.class));
        try {
            extractMacrosBounded(fs, xhtml, embeddedDocumentExtractor, context, vbaBounds);
        } finally {
            reportVbaBounds(vbaBounds, parentMetadata);
        }
    }

    private static void extractMacrosBounded(POIFSFileSystem fs, ContentHandler xhtml,
                                             EmbeddedDocumentExtractor embeddedDocumentExtractor,
                                             ParseContext context,
                                             LenientVBAReader.Bounds vbaBounds)
            throws IOException, SAXException, TikaException {

        VBAMacroReader reader = null;
        Map<String, String> macros = null;
        // Whether the macros below came from POI's reader, which charges the budget nothing. The
        // lenient reader charges as it retains, so only this case needs charging here -- and it
        // needs it, or the accumulator reads zero after a full-size part and the next part of the
        // same container gets the whole ceiling again.
        boolean poiRead = false;

        // POI's VBAMacroReader honours NO size bound: it decompresses every module into memory
        // with an unbounded IOUtils.toByteArray. So the VBA bounds, which only the lenient reader
        // below consults, did not constrain the PRIMARY path at all -- a small file whose modules
        // decompress to hundreds of megabytes took the worker's heap with it, and no bound applied
        // after that read could have prevented it. Project the decompressed size from chunk
        // headers FIRST, and when the projection clears the document budget POI cannot exceed it.
        //
        // Against what is LEFT of the document budget, not against the whole of it: this
        // accumulator may already hold an earlier macro part of the same container, and a
        // projection tested against the full ceiling clears once per part however much has been
        // retained. For the single-part case -- effectively every real document -- retained is 0
        // here and this is the same comparison as before.
        long allowance = vbaBounds.remainingTotal();
        long projected = LenientVBAReader.projectDecompressedBytes(fs, allowance);
        boolean overBudget = projected > allowance;
        if (overBudget) {
            // Deliberately NOT marked here: the projection is an upper bound, so a redirect is
            // not by itself evidence that anything was withheld. The bounded reader marks if it
            // actually drops or truncates, and the empty case is marked below.
            try {
                macros = LenientVBAReader.readMacros(fs, vbaBounds);
            } catch (Exception | OutOfMemoryError ignore) {
                macros = null;
            }
            if (macros == null || macros.isEmpty()) {
                vbaBounds.mark("VBA project projected to decompress to more than "
                        + allowance + " bytes (of a " + vbaBounds.totalMax()
                        + "-byte per-document budget); the bounded reader recovered no macros");
            }
        }
        try {
            if (!overBudget) {
                reader = new VBAMacroReader(fs);
                macros = reader.readMacros();
                poiRead = true;
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            // POI's strict reserved-field checks reject some valid VBA projects (notably
            // those authored by Mac Word, which writes non-standard record IDs in the dir
            // stream).  Fall back to our lenient reader before giving up.
            try {
                macros = LenientVBAReader.readMacros(fs, vbaBounds);
            } catch (Exception ignore) {
                macros = null;
            }
            if (macros == null || macros.isEmpty()) {
                Metadata m = Metadata.newInstance(context);
                m.set(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE,
                        TikaCoreProperties.EmbeddedResourceType.MACRO.toString());
                m.set(Metadata.CONTENT_TYPE, "text/x-vbasic");
                EmbeddedDocumentUtil.recordException(e, m);
                if (embeddedDocumentExtractor.shouldParseEmbedded(m)) {
                    embeddedDocumentExtractor.parseEmbedded(
                            //pass in space character so that we don't trigger a zero-byte exception
                            TikaInputStream.get(new byte[]{'\u0020'}), xhtml, m, context, true);
                }
                return; // the caller's finally reports the bound on this path too
            }
        }
        // Charge what POI returned. Its reader consults no bound and charges nothing, so the
        // accumulator sat at zero after a part POI had read in full -- and the form pass below, plus
        // every later macro part of the same container, then got a whole second allowance. Not
        // truncated here: POI ran only because the projection, an upper bound on what it can
        // return, fitted the allowance, so what it did return fits as well. Charged BEFORE the
        // orphan merge, which keeps the deliberately separate accumulator documented below.
        if (poiRead && macros != null) {
            long poiChars = 0;
            for (String v : macros.values()) {
                poiChars += v.length();
            }
            vbaBounds.charge(poiChars);
        }
        // Orphaned VBA storage returns empty WITHOUT throwing (its OLE directory entry was
        // corrupted to hide it from POI's tree-walking reader), so the catch above never fires.
        // Try the lenient reader's olevba-style all-entry / orphan recovery before concluding
        // there are no macros. LenientVBAReader.readMacros(fs) does the tree-walk first, then
        // falls back to scanning every raw directory entry for the orphaned dir + module streams.
        // Skipped when the projection already sent us down the bounded reader: that call WAS the
        // lenient read, and repeating it would re-charge the document budget it already spent.
        // ...and even when POI DID return modules, a project parked in a storage POI does not read
        // (it only reads storages named "VBA") is still invisible to it. Making the orphan scan
        // unconditional inside LenientVBAReader was not enough: on this path that reader is never
        // called, so one tree-visible module -- an empty Sub is enough -- hid everything the property
        // scan would have found. Only pay for the scan when the property table actually holds a dir
        // stream the tree cannot reach.
        if (!overBudget && macros != null && !macros.isEmpty()
                && LenientVBAReader.hasUnreachableDirStream(fs)) {
            try {
                // A SEPARATE accumulator on purpose. Charging this recovery against the shared
                // document budget was measured starving the extraction it is meant to supplement:
                // the orphan scan resolves module names against a FLATTENED property map, so it can
                // attribute unrelated streams as module bodies (a known finding), and those charges
                // then cut the form pass -- one real document went 2,913,040 chars to 2,695,266 and
                // gained a truncation flag with nothing actually withheld. Recovery must not be able
                // to reduce what the primary path already produced.
                //
                // KNOWN, DELIBERATE SCOPE HOLE: modules merged here therefore do not count against
                // vbaMaxTotalBytes. That is smaller than the alternative -- a live total-loss evasion
                // where one decoy module hides an entire project -- and it collapses once the
                // flattened-lookup finding is fixed, at which point this can share vbaBounds again.
                Map<String, String> hidden = LenientVBAReader.readMacrosFromOrphans(fs,
                        new LenientVBAReader.Bounds(0, vbaBounds.totalMax()));
                for (Map.Entry<String, String> e : hidden.entrySet()) {
                    if (!macros.containsValue(e.getValue())) {
                        macros.put(uniqueKey(macros, e.getKey()), e.getValue());
                    }
                }
            } catch (Exception | OutOfMemoryError ignore) {
                // recovery is best-effort
            }
        }
        if (!overBudget && (macros == null || macros.isEmpty())) {
            try {
                Map<String, String> recovered =
                        LenientVBAReader.readMacros(fs, vbaBounds);
                if (recovered != null && !recovered.isEmpty()) {
                    macros = recovered;
                }
            } catch (Exception ignore) {
                // recovery is best-effort
            }
        }
        if (macros == null) {
            return;
        }
        for (Map.Entry<String, String> e : macros.entrySet()) {
            Metadata m = Metadata.newInstance(context);
            m.set(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE,
                    TikaCoreProperties.EmbeddedResourceType.MACRO.toString());
            m.set(Metadata.CONTENT_TYPE, "text/x-vbasic");
            if (!StringUtils.isBlank(e.getKey())) {
                m.set(TikaCoreProperties.RESOURCE_NAME_KEY, e.getKey());
            }
            if (embeddedDocumentExtractor.shouldParseEmbedded(m)) {
                try (TikaInputStream tis = TikaInputStream.get(e.getValue().getBytes(StandardCharsets.UTF_8))) {
                    embeddedDocumentExtractor.parseEmbedded(tis, xhtml, m, context, true);
                }
            }
        }
        // Extract UserForm control properties (ControlTipText, Tag, Caption, Value).
        // These are stored in binary form resources and invisible in VBA source text —
        // a common technique to hide URLs or commands from static analysis.
        //
        // Form text is charged against the SAME document budget as macro source, not given a
        // second allowance: two ceilings that each admit the full amount are not a document
        // ceiling. This surface used to be bounded by nothing at all, which mattered more once
        // OLE2 UserForm discovery started working -- documents that previously yielded no form
        // output at all now yield hundreds of kilobytes.
        //
        // The charging moved INTO extractFormVariables and must not be repeated here. Doing it in
        // this loop meant every form in the document was parsed and its control text built before
        // the first byte was charged, so the ceiling bounded the emission and not the memory. What
        // comes back has already been charged and already fits.
        try {
            for (VbaFormParser.FormModuleResult form
                    : VbaFormParser.extractFormVariables(fs, vbaBounds)) {
                String text = form.toText();
                if (text.isBlank()) continue;
                Metadata m = Metadata.newInstance(context);
                m.set(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE,
                        TikaCoreProperties.EmbeddedResourceType.MACRO.toString());
                m.set(Metadata.CONTENT_TYPE, "text/x-vbasic");
                m.set(TikaCoreProperties.RESOURCE_NAME_KEY, form.moduleName + ".frm");
                if (embeddedDocumentExtractor.shouldParseEmbedded(m)) {
                    try (TikaInputStream tis = TikaInputStream.get(text.getBytes(StandardCharsets.UTF_8))) {
                        embeddedDocumentExtractor.parseEmbedded(tis, xhtml, m, context, true);
                    }
                }
            }
        } catch (Exception ignore) {
            // Non-fatal: form binary parsing errors should never fail the overall extraction
        }
    }

    /** A key not already present in {@code macros}, so a merged module cannot replace one. */
    private static String uniqueKey(Map<String, String> macros, String name) {
        String base = (name == null || name.isEmpty()) ? "Module" : name;
        String key = base;
        int n = 1;
        while (macros.containsKey(key)) {
            key = base + "#" + (++n);
        }
        return key;
    }

    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED_TYPES;
    }

    /**
     * Extracts properties and text from an MS Document input stream
     */
    public void parse(TikaInputStream tis, ContentHandler handler, Metadata metadata,
                      ParseContext context) throws IOException, SAXException, TikaException {

        configure(context);
        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata, context);
        xhtml.startDocument();

        final DirectoryNode root;
        boolean isDirectoryNode = false;
        tis.setCloseShield();
        try {
            final Object container = tis.getOpenContainer();
            if (container instanceof POIFSFileSystem) {
                root = ((POIFSFileSystem) container).getRoot();
            } else if (container instanceof DirectoryNode) {
                root = (DirectoryNode) container;
                isDirectoryNode = true;
            } else {
                POIFSFileSystem fs = null;
                if (tis.hasFile()) {
                    fs = new POIFSFileSystem(tis.getFile(), true);
                } else {
                    fs = new POIFSFileSystem(tis);
                }
                //stream will close the fs, no need to close this below
                tis.setOpenContainer(fs);
                root = fs.getRoot();
            }
            OfficeParserConfig officeParserConfig = context.get(OfficeParserConfig.class);

            // Extract macros BEFORE body content. The recursive write-limit (the worker's
            // BasicContentHandlerFactory char cap) is enforced as a CUMULATIVE total across the
            // whole parse, not per-entry (see RecursiveParserWrapper.SecureHandlerCounter). A
            // document padded with megabytes of junk body text -- a known malspam evasion --
            // therefore exhausts the budget before the macro stream is reached, and the small
            // but forensically-critical VBA source is silently dropped (or the parse aborts
            // pre-macro when throwOnWriteLimitReached). Emitting macros first guarantees they are
            // captured even when the body later busts the limit; the early-stop on huge bodies
            // is preserved (it now just stops AFTER the macros are already out).
            //Note that macros are handled separately for ppt in HSLFExtractor.
            if (officeParserConfig.isExtractMacros() && !isDirectoryNode) {
                // if the "root" is a directory node, we assume that the macros have already
                // been extracted from the parent's fileSystem -- TIKA-4116
                extractMacros(root.getFileSystem(), xhtml,
                        EmbeddedDocumentUtil.getEmbeddedDocumentExtractor(context), context,
                        metadata);
            }

            parse(root, context, metadata, xhtml, tis);
        } finally {
            tis.removeCloseShield();
        }
        xhtml.endDocument();
    }

    protected void parse(DirectoryNode root, ParseContext context, Metadata metadata,
                         XHTMLContentHandler xhtml)
            throws IOException, SAXException, TikaException {
        parse(root, context, metadata, xhtml, null);
    }

    /**
     * Body-extraction dispatch. The optional {@code originalStream} is passed
     * through so handlers that need to invoke external CLIs (e.g. wps2text
     * for legacy MS Works {@code .wps}) can recover the original file path.
     */
    protected void parse(DirectoryNode root, ParseContext context, Metadata metadata,
                         XHTMLContentHandler xhtml, TikaInputStream originalStream)
            throws IOException, SAXException, TikaException {

        // Parse summary entries first, to make metadata available early
        new SummaryExtractor(metadata).parseSummaries(root);

        // Parse remaining document entries
        POIFSDocumentType type = POIFSDocumentType.detectType(root);

        if (type != POIFSDocumentType.UNKNOWN) {
            setType(metadata, type.getType());
        }

        switch (type) {
            case SOLIDWORKS_PART:
            case SOLIDWORKS_ASSEMBLY:
            case SOLIDWORKS_DRAWING:
                break;
            case PUBLISHER:
                PublisherTextExtractor publisherTextExtractor = new PublisherTextExtractor(root);
                xhtml.element("p", publisherTextExtractor.getText());
                break;
            case WORDDOCUMENT:
                new WordExtractor(context, metadata).parse(root, xhtml);
                break;
            case POWERPOINT:
                new HSLFExtractor(context, metadata).parse(root, xhtml);
                break;
            case WORKBOOK:
            case XLR:
                Locale locale = context.get(Locale.class, LocaleUtil.getUserLocale());
                new ExcelExtractor(context, metadata).parse(root, xhtml, locale);
                break;
            case PROJECT:
                // We currently can't do anything beyond the metadata
                break;
            case WORKS:
                // Legacy Microsoft Works .wps — POI ships no extractor. Optionally
                // shell out to libwps's wps2text when WorksConfig is enabled.
                {
                    WorksConfig wpsConfig = context.get(WorksConfig.class);
                    if (wpsConfig != null && wpsConfig.isEnabled()
                            && originalStream != null) {
                        try {
                            WorksTextExtractor extractor = new WorksTextExtractor(wpsConfig);
                            List<String> lines = extractor.extract(originalStream);
                            for (String line : lines) {
                                xhtml.element("p", line);
                            }
                        } catch (IOException e) {
                            // best-effort — metadata still emitted above
                        }
                    }
                }
                break;
            case VISIO:
                VisioTextExtractor visioTextExtractor = new VisioTextExtractor(root);
                for (String text : visioTextExtractor.getAllText()) {
                    xhtml.element("p", text);
                }
                break;
            case OUTLOOK:
                OutlookExtractor extractor = new OutlookExtractor(root, metadata, context);
                extractor.parse(xhtml);
                break;
            case ENCRYPTED:

                try {
                    EncryptionInfo info = new EncryptionInfo(root);
                    Decryptor d = Decryptor.getInstance(info);
                    // By default, use the default Office Password
                    String password = Decryptor.DEFAULT_PASSWORD;

                    // If they supplied a Password Provider, ask that for the password,
                    //  and use the provider given one if available (stick with default if not)
                    PasswordProvider passwordProvider = context.get(PasswordProvider.class);
                    if (passwordProvider != null) {
                        String suppliedPassword = passwordProvider.getPassword(metadata);
                        if (suppliedPassword != null) {
                            password = suppliedPassword;
                        }
                    }

                    // Check if we've the right password or not
                    if (!d.verifyPassword(password)) {
                        throw new EncryptedDocumentException();
                    }

                    // Decrypt the OLE2 tis, and delegate the resulting OOXML
                    //  file to the regular OOXML parser for normal handling
                    OOXMLParser parser = new OOXMLParser();
                    try (TikaInputStream tis = TikaInputStream.get(d.getDataStream(root))) {
                        parser.parse(tis, new EmbeddedContentHandler(new BodyContentHandler(xhtml)),
                                metadata, context);
                    }
                } catch (GeneralSecurityException ex) {
                    throw new EncryptedDocumentException(ex);
                } catch (FileNotFoundException ex) {
                    //this can happen because POI may not support case-insensitive ole2 object
                    //lookups
                    throw new EncryptedDocumentException(ex);
                }
                break;
            case DRMENCRYPTED:
                throw new EncryptedDocumentException("DRM encrypted document is not yet supported" +
                        " by Apache POI");
            default:
                if (root.hasEntry("EncryptedPackage")) {
                    throw new EncryptedDocumentException("OLE2 file with an unrecognized " +
                            "EncryptedPackage entry");
                }
                // For unsupported / unhandled types, just the metadata
                //  is extracted, which happened above
                break;
        }
    }

    private void setType(Metadata metadata, MediaType type) {
        metadata.set(Metadata.CONTENT_TYPE, type.toString());
    }

    public enum POIFSDocumentType {
        WORKBOOK("xls", MediaType.application("vnd.ms-excel")),
        OLE10_NATIVE("ole", POIFSContainerDetector.OLE10_NATIVE),
        COMP_OBJ("ole", POIFSContainerDetector.COMP_OBJ),
        WORDDOCUMENT("doc", MediaType.application("msword")),
        UNKNOWN("unknown", MediaType.application("x-tika-msoffice")),
        DRMENCRYPTED("ole", MediaType.application("x-tika-ole-drm-encrypted")),
        ENCRYPTED("ole", MediaType.application("x-tika-ooxml-protected")),
        POWERPOINT("ppt", MediaType.application("vnd.ms-powerpoint")),
        PUBLISHER("pub", MediaType.application("x-mspublisher")),
        PROJECT("mpp", MediaType.application("vnd.ms-project")),
        VISIO("vsd", MediaType.application("vnd.visio")),
        WORKS("wps", MediaType.application("vnd.ms-works")),
        XLR("xlr", MediaType.application("x-tika-msworks-spreadsheet")),
        OUTLOOK("msg", MediaType.application("vnd.ms-outlook")),
        SOLIDWORKS_PART("sldprt", MediaType.application("sldworks")),
        SOLIDWORKS_ASSEMBLY("sldasm", MediaType.application("sldworks")),
        SOLIDWORKS_DRAWING("slddrw", MediaType.application("sldworks")),
        GRAPH("", MediaType.application("vnd.ms-graph"));

        static Map<MediaType, POIFSDocumentType> TYPE_MAP = new HashMap<>();

        static {
            for (POIFSDocumentType t : values()) {
                TYPE_MAP.put(t.type, t);
            }
        }
        private final String extension;
        private final MediaType type;

        POIFSDocumentType(String extension, MediaType type) {
            this.extension = extension;
            this.type = type;
        }

        public static POIFSDocumentType detectType(POIFSFileSystem fs) {
            return detectType(fs.getRoot());
        }

        public static POIFSDocumentType detectType(DirectoryEntry node) {
            Set<String> names = new HashSet<>();
            for (Entry entry : node) {
                names.add(entry.getName());
            }
            MediaType type = POIFSContainerDetector.detect(names, node);
            if (TYPE_MAP.containsKey(type)) {
                return TYPE_MAP.get(type);
            }
            return UNKNOWN;
        }

        public String getExtension() {
            return extension;
        }

        public MediaType getType() {
            return type;
        }
    }

    /**
     * Looks for entry within root (non-recursive) that has an upper-cased
     * name that equals ucTarget
     * @param root
     * @param ucTarget
     * @return
     */
    public static Entry getUCEntry(DirectoryEntry root, String ucTarget) {
        Iterator<Entry> it = root.getEntries();
        while (it.hasNext()) {
            Entry e = it.next();
            if (e.getName().toUpperCase(Locale.US).equals(ucTarget)) {
                return e;
            }
        }
        return null;
    }

}
