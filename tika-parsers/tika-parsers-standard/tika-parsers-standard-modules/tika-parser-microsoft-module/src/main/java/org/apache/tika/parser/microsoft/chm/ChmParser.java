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
package org.apache.tika.parser.microsoft.chm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.config.TikaComponent;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.EmbeddedDocumentUtil;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.html.JSoupParser;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.EmbeddedContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;

@TikaComponent
public class ChmParser implements Parser {

    private static final Logger LOG = LoggerFactory.getLogger(ChmParser.class);

    /**
     * Serial version UID
     */
    private static final long serialVersionUID = 5938777307516469802L;

    // Matches <PARAM name="..." value="..."> in any case/attribute order.
    // Group 1 = name attribute value, Group 2 = value attribute value.
    private static final Pattern PARAM_PATTERN = Pattern.compile(
            "<[Pp][Aa][Rr][Aa][Mm][^>]*\\bname\\s*=\\s*[\"']([^\"']+)[\"'][^>]*\\bvalue\\s*=\\s*[\"']([^\"']*)[\"'][^>]*/?>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    // Also handle reversed attribute order (value before name)
    private static final Pattern PARAM_PATTERN_REV = Pattern.compile(
            "<[Pp][Aa][Rr][Aa][Mm][^>]*\\bvalue\\s*=\\s*[\"']([^\"']*)[\"'][^>]*\\bname\\s*=\\s*[\"']([^\"']+)[\"'][^>]*/?>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Set<MediaType> SUPPORTED_TYPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(MediaType.application("vnd.ms-htmlhelp"),
                    MediaType.application("chm"), MediaType.application("x-chm"))));

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED_TYPES;
    }

    @Override
    public void parse(TikaInputStream tis, ContentHandler handler, Metadata metadata,
                      ParseContext context) throws IOException, SAXException, TikaException {
        ChmExtractor chmExtractor = new ChmExtractor(tis);

        // metadata
        metadata.set(Metadata.CONTENT_TYPE, "application/vnd.ms-htmlhelp");

        // content
        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata, context);
        xhtml.startDocument();

        Parser htmlParser =
                EmbeddedDocumentUtil.tryToFindExistingLeafParser(JSoupParser.class, context);
        if (htmlParser == null) {
            htmlParser = new JSoupParser();
        }

        EmbeddedDocumentExtractor embeddedExtractor =
                EmbeddedDocumentUtil.getEmbeddedDocumentExtractor(context);

        for (DirectoryListingEntry entry : chmExtractor.getChmDirList()
                .getDirectoryListingEntryList()) {
            final String entryName = entry.getName();
            if (entry.getLength() <= 0 || ChmCommons.hasSkip(entry)) {
                continue;
            }
            // Strip leading slash for display/metadata
            String displayName = entryName.startsWith("/") ? entryName.substring(1) : entryName;
            if (displayName.isEmpty() || displayName.endsWith("/")) {
                continue;
            }

            byte[] data;
            try {
                data = chmExtractor.extractChmEntry(entry);
            } catch (TikaException e) {
                LOG.warn("Failed to extract CHM entry '{}': {}", entryName, e.getMessage());
                continue;
            }
            if (data.length == 0) {
                continue;
            }

            if (entryName.endsWith(".html") || entryName.endsWith(".htm")) {
                parsePage(data, htmlParser, xhtml, context);
            } else {
                // Non-HTML embedded file (e.g. PDF, LNK, ZIP dropped inside CHM)
                Metadata embeddedMeta = new Metadata();
                embeddedMeta.set(TikaCoreProperties.RESOURCE_NAME_KEY, displayName);
                try (TikaInputStream embeddedTis = TikaInputStream.get(data)) {
                    if (embeddedExtractor.shouldParseEmbedded(embeddedMeta)) {
                        embeddedExtractor.parseEmbedded(embeddedTis, xhtml, embeddedMeta,
                                context, true);
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to parse embedded CHM entry '{}': {}", displayName,
                            e.getMessage());
                }
            }
        }

        xhtml.endDocument();
    }


    private void parsePage(byte[] byteObject, Parser htmlParser, ContentHandler xhtml,
                           ParseContext context) throws TikaException, IOException, SAXException {
        Metadata metadata = Metadata.newInstance(context);
        ContentHandler handler = new EmbeddedContentHandler(new BodyContentHandler(xhtml));
        try (TikaInputStream tis = TikaInputStream.get(byteObject)) {
            htmlParser.parse(tis, handler, metadata, context);
        }
        emitParamValues(byteObject, xhtml);
    }

    /**
     * JSoupParser's BodyContentHandler only emits visible text — OBJECT/PARAM attribute
     * values are stripped.  This method scans the raw HTML bytes for &lt;PARAM&gt; elements
     * and emits their value attribute as plain text so that ActiveX shortcut commands
     * (e.g. PowerShell stagers in CHM droppers) are surfaced in the extraction output.
     */
    private void emitParamValues(byte[] html, ContentHandler xhtml) throws SAXException {
        if (html == null || html.length == 0) {
            return;
        }
        String text = new String(html, StandardCharsets.UTF_8);
        emitParamMatches(PARAM_PATTERN, text, 1, 2, xhtml);
        emitParamMatches(PARAM_PATTERN_REV, text, 2, 1, xhtml);
    }

    private void emitParamMatches(Pattern pattern, String text,
                                  int nameGroup, int valueGroup,
                                  ContentHandler xhtml) throws SAXException {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String name = m.group(nameGroup).trim();
            String value = m.group(valueGroup).trim();
            if (!value.isEmpty()) {
                String line = name + ": " + value;
                xhtml.characters(line.toCharArray(), 0, line.length());
                xhtml.characters(new char[]{'\n'}, 0, 1);
            }
        }
    }

}
