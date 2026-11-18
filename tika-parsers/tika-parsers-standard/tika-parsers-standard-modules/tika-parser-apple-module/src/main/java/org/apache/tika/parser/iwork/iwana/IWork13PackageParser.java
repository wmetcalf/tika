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
package org.apache.tika.parser.iwork.iwana;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.PropertyListParser;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.io.IOUtils;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.annotation.TikaComponent;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.EmbeddedDocumentUtil;
import org.apache.tika.io.TemporaryResources;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.PassthroughPrefix;
import org.apache.tika.metadata.Property;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.iwork.IWorkMembers;
import org.apache.tika.sax.TaggedContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;

@TikaComponent
public class IWork13PackageParser implements Parser {

    /**
     * All iWork 13 files contain this, so we can detect based on it
     */
    public final static String IWORK13_COMMON_ENTRY = "Metadata/BuildVersionHistory.plist";
    public final static String IWORK13_MAIN_ENTRY = "Index/Document.iwa";

    public static final String IWORKS_PREFIX = "iworks:";
    public static final Property IWORKS_DOC_ID =
            Property.externalText(IWORKS_PREFIX + "document-id");
    public static final Property IWORKS_BUILD_VERSION_HISTORY =
            Property.externalTextBag(IWORKS_PREFIX + "build-version-history");
    public static final PassthroughPrefix IWORKS_PROPERTIES =
            PassthroughPrefix.file(IWORKS_PREFIX, "iWork '13 plist document properties");


    private final static Set<MediaType> supportedTypes = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(IWork13DocumentType.KEYNOTE13.getType(),
                    IWork13DocumentType.NUMBERS13.getType(),
                    IWork13DocumentType.PAGES13.getType(),
                    IWork13DocumentType.UNKNOWN13.getType())));

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return supportedTypes;
    }

    @Override
    public void parse(TikaInputStream tis, ContentHandler handler, Metadata metadata,
                      ParseContext context) throws IOException, SAXException, TikaException {
        TaggedContentHandler caller = new TaggedContentHandler(handler);
        try {
            XHTMLContentHandler xhtml = new XHTMLContentHandler(caller, metadata, context);
            xhtml.startDocument();
            EmbeddedDocumentExtractor extractor =
                    EmbeddedDocumentUtil.getEmbeddedDocumentExtractor(context);
            MediaType[] detected = {null};
            Exception ex = IWorkMembers.walk(tis, entry -> {
                // by name, before the data is opened: an unreadable member still identifies
                if (detected[0] == null) {
                    detected[0] = IWork13DocumentType.detectIfPossible(entry);
                }
            }, (entry, data) -> {
                try (TemporaryResources tmp = new TemporaryResources();
                        TikaInputStream member = TikaInputStream.get(data, tmp, new Metadata())) {
                    processZipEntry(entry, member, metadata, xhtml, context, extractor);
                }
            }, metadata, caller);
            MediaType type = detected[0] == null ? IWork13DocumentType.UNKNOWN13.getType()
                    : detected[0];
            if (type == IWork13DocumentType.UNKNOWN13.getType()) {
                type = guessTypeByExtension(metadata);
            }
            metadata.set(Metadata.CONTENT_TYPE, type.toString());
            xhtml.endDocument();
            if (ex != null) {
                throw new TikaException("problem processing iWork '13 package", ex);
            }
        } catch (SAXException e) {
            // the caller's handler failed: hand back its own exception, not the tagged wrapper
            caller.throwIfCauseOf(e);
            throw e;
        }
    }

    private void processZipEntry(ZipEntry entry,
                                 TikaInputStream tis,
                                 Metadata metadata, XHTMLContentHandler xhtml,
                                 ParseContext parseContext,
                                 EmbeddedDocumentExtractor embeddedDocumentExtractor)
            throws TikaException, IOException, SAXException {
        String streamName = entry.getName();
        if (streamName == null) {
            return;
        }
        boolean packageMetadata = "Metadata/Properties.plist".equals(streamName)
                || "Metadata/BuildVersionHistory.plist".equals(streamName)
                || "Metadata/DocumentIdentifier".equals(streamName);
        if (packageMetadata) {
            // Read as package metadata AND handed on: these fixed names (repeatable in a zip)
            // must not become a place to hide bytes the metadata readers ignore. Only a member
            // the extractor wants is spooled so it can be read twice.
            // (these exact names have no trailing '/', so none is a directory entry)
            Metadata embedded = IWorkMembers.approve(streamName, null, parseContext,
                    embeddedDocumentExtractor);
            if (embedded == null) {
                readPackageMetadata(streamName, tis, metadata);
                return;
            }
            Path member = tis.getPath();
            try (InputStream copy = Files.newInputStream(member)) {
                IWorkMembers.extractApproved(embedded, copy, xhtml, parseContext,
                        embeddedDocumentExtractor);
            }
            try (InputStream copy = Files.newInputStream(member)) {
                readPackageMetadata(streamName, copy, metadata);
            }
        } else if ("preview.jpg".equals(streamName) || "preview-micro.jpg".equals(streamName)
                || "preview-web.jpg".equals(streamName)) {
            IWorkMembers.extractEntry(streamName, entry.isDirectory(), tis,
                    TikaCoreProperties.EmbeddedResourceType.THUMBNAIL.toString(), xhtml,
                    parseContext, embeddedDocumentExtractor);
        } else {
            // Everything else, *.iwa included, is handed on. Tika cannot decode IWA, but
            // skipping members by name would let a zip routed here by one marker entry
            // hide whatever it likes behind that name.
            IWorkMembers.extractEntry(streamName, entry.isDirectory(), tis, null, xhtml,
                    parseContext, embeddedDocumentExtractor);
        }
    }

    private void readPackageMetadata(String name, InputStream in, Metadata metadata)
            throws IOException, TikaException {
        if ("Metadata/Properties.plist".equals(name)) {
            extractProperties(in, metadata);
        } else if ("Metadata/BuildVersionHistory.plist".equals(name)) {
            extractVersionHistory(in, metadata);
        } else {
            extractDocumentIdentifier(in, metadata);
        }
    }

    private void extractVersionHistory(InputStream inputStream, Metadata metadata) throws TikaException {
        try {
            NSObject rootObj = PropertyListParser.parse(inputStream);
            if (rootObj instanceof NSArray) {
                for (NSObject obj : ((NSArray)rootObj).getArray()) {
                    metadata.add(IWORKS_BUILD_VERSION_HISTORY, obj.toString());
                }
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new TikaException("problem with plist", e);
        }

    }

    private void extractProperties(InputStream inputStream, Metadata metadata) throws TikaException {
        try {
            NSObject rootObj = PropertyListParser.parse(inputStream);
            if (rootObj instanceof NSDictionary) {
                NSDictionary dict = (NSDictionary)rootObj;
                for (String k : dict.keySet()) {
                    String v = dict.get(k).toString();
                    metadata.set(IWORKS_PROPERTIES.key(k), v);
                }
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new TikaException("problem with plist", e);
        }
    }

    private void extractDocumentIdentifier(InputStream inputStream, Metadata metadata)
            throws IOException {
        byte[] bytes = new byte[36];
        int read = IOUtils.read(inputStream, bytes);
        if (read == 36) {
            metadata.set(IWORKS_DOC_ID, new String(bytes, StandardCharsets.ISO_8859_1));
        }
    }

    private MediaType guessTypeByExtension(Metadata metadata) {

        String fName = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
        if (fName == null) {
            return IWork13DocumentType.UNKNOWN13.getType();
        } else if (fName.toLowerCase(Locale.US).endsWith(".numbers")) {
            return IWork13DocumentType.NUMBERS13.getType();
        } else if (fName.toLowerCase(Locale.US).endsWith(".pages")) {
            return IWork13DocumentType.PAGES13.getType();
        } else if (fName.toLowerCase(Locale.US).endsWith(".key")) {
            return IWork13DocumentType.KEYNOTE13.getType();
        }
        return IWork13DocumentType.UNKNOWN13.getType();
    }

    public enum IWork13DocumentType {
        KEYNOTE13(MediaType.application("vnd.apple.keynote.13")),
        NUMBERS13(MediaType.application("vnd.apple.numbers.13")),
        PAGES13(MediaType.application("vnd.apple.pages.13")),
        UNKNOWN13(MediaType.application("vnd.apple.unknown.13"));

        private final MediaType mediaType;

        IWork13DocumentType(MediaType mediaType) {
            this.mediaType = mediaType;
        }

        public static MediaType detect(ZipFile zipFile) {
            MediaType type = null;
            Enumeration<? extends ZipEntry> entries = zipFile.getEntries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                type = IWork13DocumentType.detectIfPossible(entry);
                if (type != null) {
                    return type;
                }
            }

            // If we get here, we don't know what it is
            return UNKNOWN13.getType();
        }

        /**
         * @return Specific type if this identifies one, otherwise null
         */
        public static MediaType detectIfPossible(ZipEntry entry) {
            String name = entry.getName();
            if (!name.endsWith(".iwa")) {
                return null;
            }

            // Is it a uniquely identifying filename?
            if (name.equals("Index/MasterSlide.iwa") || name.startsWith("Index/MasterSlide-")) {
                return KEYNOTE13.getType();
            }
            if (name.equals("Index/Slide.iwa") || name.startsWith("Index/Slide-")) {
                return KEYNOTE13.getType();
            }

            // Is it the main document?
            if (name.equals(IWORK13_MAIN_ENTRY)) {
                // TODO Decode the snappy tis, and check for the Message Type
                // =     2 (TN::SheetArchive), it is a numbers file;
                // = 10000 (TP::DocumentArchive), that's a pages file
                return null;
            }

            // Unknown
            return null;
        }

        public MediaType getType() {
            return mediaType;
        }
    }
}
