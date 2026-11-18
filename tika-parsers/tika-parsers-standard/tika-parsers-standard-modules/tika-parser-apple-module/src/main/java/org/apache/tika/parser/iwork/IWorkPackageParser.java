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
package org.apache.tika.parser.iwork;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javax.xml.namespace.QName;

import org.apache.commons.compress.archivers.zip.UnsupportedZipFeatureException;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.UnsynchronizedByteArrayInputStream;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.annotation.TikaComponent;
import org.apache.tika.detect.XmlRootExtractor;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.EmbeddedDocumentUtil;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.TaggedContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;
import org.apache.tika.utils.XMLReaderUtils;

/**
 * A parser for the IWork container files. This includes *.key, *.pages and *.numbers files.
 * This parser delegates the relevant entries to a {@link ContentHandler} that parsers the content.
 * <p>
 * Currently supported formats:
 * <ol>
 * <li>Keynote format version 2.x. Currently only tested with Keynote version 5.x
 * <li>Pages format version 1.x. Currently only tested with Pages version 4.0.x
 * <li>Numbers format version 1.x. Currently only tested with Numbers version 2.0.x
 * </ol>
 */
@TikaComponent
public class IWorkPackageParser implements Parser {

    /**
     * Which files within an iWork file contain the actual content?
     */
    public final static Set<String> IWORK_CONTENT_ENTRIES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("index.apxl", "index.xml", "presentation.apxl")));
    /**
     * All iWork files contain one of these, so we can detect based on it
     */
    public final static String IWORK_COMMON_ENTRY = "buildVersionHistory.plist";
    /**
     * Serial version UID
     */
    private static final long serialVersionUID = -2160322853809682372L;
    private static final int MARK_LIMIT = 1096;
    /**
     * This parser handles all iWorks formats.
     */
    private final static Set<MediaType> supportedTypes = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(MediaType.application("vnd.apple.iwork"),
                    IWORKDocumentType.KEYNOTE.getType(), IWORKDocumentType.NUMBERS.getType(),
                    IWORKDocumentType.PAGES.getType())));

    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return supportedTypes;
    }

    public void parse(TikaInputStream tis, ContentHandler handler, Metadata metadata,
                      ParseContext context) throws IOException, SAXException, TikaException {
        TaggedContentHandler caller = new TaggedContentHandler(handler);
        try {
            XHTMLContentHandler xhtml = new XHTMLContentHandler(caller, metadata, context);
            xhtml.startDocument();
            EmbeddedDocumentExtractor extractor =
                    EmbeddedDocumentUtil.getEmbeddedDocumentExtractor(context);
            // Pass 1: the document itself, first, so the XHTML <head> carries the metadata the
            // content handlers set (it is written at the first element). Pass 2: every other
            // member, handed on. Both walks see the members in the same order, so the parsed
            // one is identified by name and occurrence.
            java.util.Map<String, Integer> seen1 = new java.util.HashMap<>();
            String[] parsedName = {null};
            int[] parsedOccurrence = {0};
            Exception ex = IWorkMembers.walk(tis,
                    entry -> seen1.merge(entry.getName(), 1, Integer::sum),
                    (entry, data) -> {
                        if (parsedName[0] == null
                                && processMember(entry, data, false, true, xhtml, metadata,
                                        context, extractor)) {
                            parsedName[0] = entry.getName();
                            parsedOccurrence[0] = seen1.get(entry.getName());
                        }
                    }, entry -> {
                        if (IWORK_CONTENT_ENTRIES.contains(entry.getName())) {
                            // an unreadable content entry is how a password-protected iWork
                            // package looks (the stream route's detectType reports ENCRYPTED
                            // the same way)
                            metadata.set(Metadata.CONTENT_TYPE,
                                    IWORKDocumentType.ENCRYPTED.getType().toString());
                        }
                    }, metadata, caller);
            java.util.Map<String, Integer> seen2 = new java.util.HashMap<>();
            Exception ex2 = IWorkMembers.walk(tis,
                    entry -> seen2.merge(entry.getName(), 1, Integer::sum),
                    (entry, data) -> {
                        if (entry.getName().equals(parsedName[0])
                                && seen2.get(entry.getName()) == parsedOccurrence[0]) {
                            return;
                        }
                        processMember(entry, data, true, false, xhtml, metadata, context,
                                extractor);
                    }, null, metadata, caller);
            ex = IWorkMembers.firstOf(ex, ex2);
            xhtml.endDocument();
            if (ex != null) {
                throw new TikaException("problem processing iWork package", ex);
            }
        } catch (SAXException e) {
            // the caller's handler failed: hand back its own exception, not the tagged wrapper
            caller.throwIfCauseOf(e);
            throw e;
        }
        // Don't close the zip InputStream (TIKA-1117).
    }

    /**
     * The package's first iWork content entry is parsed as the document; every other member
     * (a later content entry included: the names repeat in a zip, and only one is the
     * document) is handed to the embedded extractor, so one marker entry routing an arbitrary
     * zip here cannot make its members vanish.
     *
     * @return true if this member was parsed as the document
     */
    private boolean processMember(ZipArchiveEntry entry, InputStream raw, boolean contentParsed,
                                  boolean documentOnly, XHTMLContentHandler xhtml,
                                  Metadata metadata, ParseContext context,
                                  EmbeddedDocumentExtractor extractor)
            throws IOException, SAXException, TikaException {
        InputStream entryStream = new BufferedInputStream(raw);
        IWORKDocumentType type = null;
        if (!contentParsed && !entry.isDirectory()
                && IWORK_CONTENT_ENTRIES.contains(entry.getName())) {
            entryStream.mark(MARK_LIMIT);
            type = detectType(entryStream, MARK_LIMIT);
            entryStream.reset(); // 4096 fails on github
        }
        if (type == IWORKDocumentType.ENCRYPTED) {
            metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                    "iWork content entry not extracted, unreadable: " + entry.getName());
        }
        if (type != null) {
            parseContent(type, entryStream, xhtml, metadata, context);
            return true;
        }
        if (documentOnly) {
            return false;
        }
        IWorkMembers.extractEntry(entry.getName(), entry.isDirectory(), entryStream, null,
                xhtml, context, extractor);
        return false;
    }

    private void parseContent(IWORKDocumentType type, InputStream entryStream,
                              XHTMLContentHandler xhtml, Metadata metadata, ParseContext context)
            throws IOException, SAXException, TikaException {
        ContentHandler contentHandler;
        switch (type) {
            case KEYNOTE:
                contentHandler = new KeynoteContentHandler(xhtml, metadata);
                break;
            case NUMBERS:
                contentHandler = new NumbersContentHandler(xhtml, metadata);
                break;
            case PAGES:
                contentHandler = new PagesContentHandler(xhtml, metadata);
                break;
            case ENCRYPTED:
                // We can't do anything for the file right now
                contentHandler = null;
                break;
            default:
                throw new TikaException("Unhandled iWorks file " + type);
        }

        metadata.set(Metadata.CONTENT_TYPE, type.getType().toString());
        if (contentHandler != null) {
            XMLReaderUtils.parseSAX(entryStream, contentHandler, context);
        }
    }

    private IWORKDocumentType detectType(InputStream entryStream, int markLimit) throws IOException {
        byte[] bytes = new byte[markLimit];
        try {
            int read = IOUtils.read(entryStream, bytes, 0, markLimit);
            try (InputStream bis = UnsynchronizedByteArrayInputStream.builder().setByteArray(bytes)
                    .setOffset(0).setLength(read).get()) {
                return IWORKDocumentType.detectType(bis);
            }
        } catch (UnsupportedZipFeatureException e) {
            // There was a problem with extracting the root type
            // Password Protected iWorks files are funny, but we can usually
            //  spot them because they encrypt part of the zip stream

            // Compression field was likely encrypted
            return IWORKDocumentType.ENCRYPTED;
        }

    }

    public enum IWORKDocumentType {
        KEYNOTE("http://developer.apple.com/namespaces/keynote2", "presentation",
                MediaType.application("vnd.apple.keynote")),
        NUMBERS("http://developer.apple.com/namespaces/ls", "document",
                MediaType.application("vnd.apple.numbers")),
        PAGES("http://developer.apple.com/namespaces/sl", "document",
                MediaType.application("vnd.apple.pages")),
        ENCRYPTED(null, null, MediaType.application("x-tika-iworks-protected"));

        private final String namespace;
        private final String part;
        private final MediaType type;

        IWORKDocumentType(String namespace, String part, MediaType type) {
            this.namespace = namespace;
            this.part = part;
            this.type = type;
        }

        public static IWORKDocumentType detectType(ZipArchiveEntry entry, ZipFile zip) {
            try {
                if (entry == null) {
                    return null;
                }

                try (InputStream stream = zip.getInputStream(entry)) {
                    return detectType(stream);
                }
            } catch (IOException e) {
                return null;
            }
        }

        public static IWORKDocumentType detectType(ZipArchiveEntry entry,
                                                   ZipArchiveInputStream zip) {
            if (entry == null) {
                return null;
            }

            return detectType(zip);
        }

        public static IWORKDocumentType detectType(InputStream tis) {
            QName qname = new XmlRootExtractor().extractRootElement(tis);
            if (qname != null) {
                String uri = qname.getNamespaceURI();
                String local = qname.getLocalPart();

                for (IWORKDocumentType type : values()) {
                    if (ENCRYPTED == type) {
                        //namespace and part are null for ENCRYPTED.
                        continue;
                    }
                    if (type.getNamespace().equals(uri) && type.getPart().equals(local)) {
                        return type;
                    }
                }
            }
            return null;
        }

        public String getNamespace() {
            return namespace;
        }

        public String getPart() {
            return part;
        }

        public MediaType getType() {
            return type;
        }
    }

}
