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
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;

import org.apache.commons.compress.archivers.zip.ZipFile;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.annotation.TikaComponent;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.EmbeddedDocumentUtil;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.iwork.IWorkMembers;
import org.apache.tika.sax.TaggedContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;

/**
 * For now, this parser isn't even registered.  It contains
 * code that will detect the newer 2018 .keynote, .numbers, .pages files.
 */
@TikaComponent
public class IWork18PackageParser implements Parser {

    private final static Set<MediaType> supportedTypes = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(IWork18DocumentType.KEYNOTE18.getType(),
                    IWork18DocumentType.NUMBERS18.getType(),
                    IWork18DocumentType.PAGES18.getType())));

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
            // Tika cannot decode the iWork '18 content yet, so detect the type and hand every
            // member on. One marker entry routes an arbitrary zip here, so "only detect" would
            // let such a zip hide all of its members.
            MediaType[] type = {null};
            Exception ex = IWorkMembers.walk(tis, entry -> {
                // by name, before the data is opened: an unreadable member still identifies
                if (type[0] == null) {
                    type[0] = IWork18DocumentType.detectIfPossible(entry);
                }
            }, (entry, data) -> {
                IWorkMembers.extractEntry(entry.getName(), entry.isDirectory(), data, null, xhtml,
                        context, extractor);
            }, metadata, caller);
            if (type[0] != null) {
                metadata.set(Metadata.CONTENT_TYPE, type[0].toString());
            }
            xhtml.endDocument();
            if (ex != null) {
                throw new TikaException("problem processing iWork '18 package", ex);
            }
        } catch (SAXException e) {
            // the caller's handler failed: hand back its own exception, not the tagged wrapper
            caller.throwIfCauseOf(e);
            throw e;
        }
    }

    public enum IWork18DocumentType {
        KEYNOTE18(MediaType.application("vnd.apple.keynote.18")),
        NUMBERS18(MediaType.application("vnd.apple.numbers.18")),
        PAGES18(MediaType.application("vnd.apple.pages.18"));

        private final MediaType mediaType;

        IWork18DocumentType(MediaType mediaType) {
            this.mediaType = mediaType;
        }

        /**
         * @param zipFile
         * @return mime if detected or null
         */
        public static MediaType detect(ZipFile zipFile) {
            MediaType type = null;
            Enumeration<? extends ZipEntry> entries = zipFile.getEntries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                type = IWork18DocumentType.detectIfPossible(entry);
                if (type != null) {
                    return type;
                }
            }

            // If we get here, we don't know what it is
            return null;
        }

        /**
         * @return Specific type if this identifies one, otherwise null
         */
        public static MediaType detectIfPossible(ZipEntry entry) {
            String name = entry.getName();
            if (name.endsWith(".numbers/Metadata/BuildVersionHistory.plist")) {
                return IWork18DocumentType.NUMBERS18.getType();
            } else if (name.endsWith(".pages/Metadata/BuildVersionHistory.plist")) {
                return IWork18DocumentType.PAGES18.getType();
            } else if (name.endsWith(".key/Metadata/BuildVersionHistory.plist")) {
                return IWork18DocumentType.KEYNOTE18.getType();
            }
            // Unknown
            return null;
        }

        public MediaType getType() {
            return mediaType;
        }
    }
}
