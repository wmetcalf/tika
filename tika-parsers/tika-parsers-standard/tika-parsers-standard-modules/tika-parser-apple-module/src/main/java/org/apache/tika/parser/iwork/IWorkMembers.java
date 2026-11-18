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

import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.compress.archivers.zip.UnsupportedZipFeatureException;
import org.apache.commons.io.input.CloseShieldInputStream;
import org.xml.sax.SAXException;

import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TemporaryResources;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.XHTMLContentHandler;

/**
 * Hands an iWork package member to the embedded document extractor.
 * <p>
 * A single marker entry is enough to route an arbitrary zip to one of the iWork
 * package parsers, so those parsers must not drop the members they do not
 * understand: every member they do not consume as package metadata goes through
 * here, whatever its name.
 */
public final class IWorkMembers {

    private IWorkMembers() {
    }

    /**
     * @param name         the member's path inside the package
     * @param member       the member's bytes; it is not closed
     * @param resourceType an {@link TikaCoreProperties.EmbeddedResourceType} name, or null
     */
    public static void extract(String name, InputStream member, String resourceType,
                               XHTMLContentHandler xhtml, ParseContext context,
                               EmbeddedDocumentExtractor extractor)
            throws IOException, SAXException, TikaException {
        Metadata embedded = Metadata.newInstance(context);
        embedded.set(TikaCoreProperties.RESOURCE_NAME_KEY, name);
        if (resourceType != null) {
            embedded.set(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE, resourceType);
        }
        if (!extractor.shouldParseEmbedded(embedded)) {
            return;
        }
        try (TemporaryResources tmp = new TemporaryResources()) {
            TikaInputStream tis = TikaInputStream.get(CloseShieldInputStream.wrap(member), tmp,
                    embedded);
            extractor.parseEmbedded(tis, xhtml, embedded, context, true);
        }
    }

    /**
     * Folds one member's failure into the parse: a member that cannot be read at all
     * (an encrypted or unsupported zip entry) is recorded as a warning on the container,
     * anything else is kept as the first failure, rethrown once every other member has
     * been extracted, so one bad member cannot hide the ones after it.
     *
     * @return the failure to rethrow at the end, or null
     */
    public static Exception fold(Exception first, Exception failure, String name,
                                 Metadata container) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof UnsupportedZipFeatureException) {
                container.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                        "iWork member not extracted, unreadable: " + name + ": "
                                + t.getMessage());
                return first;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return first == null ? failure : first;
    }
}
