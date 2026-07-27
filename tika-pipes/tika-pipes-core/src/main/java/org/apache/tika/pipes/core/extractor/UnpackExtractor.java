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
package org.apache.tika.pipes.core.extractor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.extractor.DefaultEmbeddedStreamTranslator;
import org.apache.tika.extractor.EmbeddedStreamTranslator;
import org.apache.tika.extractor.ParsingEmbeddedDocumentExtractor;
import org.apache.tika.extractor.UnpackHandler;
import org.apache.tika.extractor.UnpackSelector;
import org.apache.tika.io.BoundedInputStream;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ParseRecord;

/**
 * Embedded document extractor that parses and unpacks embedded documents,
 * extracting both text/metadata and raw bytes.
 *
 * @since Apache Tika 3.0.0
 */
public class UnpackExtractor extends ParsingEmbeddedDocumentExtractor {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ParsingEmbeddedDocumentExtractor.class);

    private final EmbeddedStreamTranslator embeddedStreamTranslator = new DefaultEmbeddedStreamTranslator();
    private long bytesExtracted = 0;
    private final long maxEmbeddedBytesForExtraction;

    public UnpackExtractor(ParseContext context) {
        super(context);
        // Get maxUnpackBytes from UnpackConfig, defaulting to 10GB if not configured
        // or using Long.MAX_VALUE if set to -1 (unlimited)
        UnpackConfig unpackConfig = context.get(UnpackConfig.class);
        if (unpackConfig != null) {
            long configuredMax = unpackConfig.getMaxUnpackBytes();
            // -1 means no limit (use Long.MAX_VALUE)
            this.maxEmbeddedBytesForExtraction = configuredMax >= 0 ? configuredMax : Long.MAX_VALUE;
        } else {
            this.maxEmbeddedBytesForExtraction = UnpackConfig.DEFAULT_MAX_UNPACK_BYTES;
        }
    }


    @Override
    public void parseEmbedded(
            TikaInputStream tis, ContentHandler handler, Metadata metadata, ParseContext parseContext, boolean outputHtml)
            throws SAXException, IOException {
        // Check and enforce embedded limits even if caller didn't call shouldParseEmbedded()
        // This guarantees limits are enforced for all callers
        ParseRecord parseRecord = context.get(ParseRecord.class);
        if (parseRecord != null && !checkEmbeddedLimits(parseRecord)) {
            return;
        }

        UnpackHandler bytesHandler = context.get(UnpackHandler.class);
        if (bytesHandler != null) {
            parseWithBytes(
                    tis, handler, metadata, parseContext, outputHtml);
        } else {
            super.parseEmbedded(
                    tis, handler, metadata, parseContext, outputHtml);
        }
    }

    private void parseWithBytes(
            TikaInputStream tis, ContentHandler handler, Metadata metadata,
            ParseContext parseContext, boolean outputHtml)
            throws IOException, SAXException {

        //trigger spool to disk
        Path rawBytes = tis.getPath();

        //There may be a "translated" path for OLE2 etc
        Path translated = null;
        try {
            //translate the stream or not
            if (embeddedStreamTranslator.shouldTranslate(tis, metadata)) {
                translated = Files.createTempFile("tika-tmp-", ".bin");
                try (OutputStream os = Files.newOutputStream(translated)) {
                    embeddedStreamTranslator.translate(tis, metadata, os);
                }
            }
            super.parseEmbedded(
                    tis, handler, metadata, parseContext, outputHtml);
        } finally {
            try {
                if (translated != null) {
                    storeEmbeddedBytes(translated, metadata);
                } else {
                    storeEmbeddedBytes(rawBytes, metadata);
                }
            } finally {
                if (translated != null) {
                    Files.delete(translated);
                }
            }
        }
    }

    private void storeEmbeddedBytes(Path p, Metadata metadata) {
        if (p == null) {
            return;
        }

        // Get UnpackSelector from ParseContext - if configured, use it to filter
        // If no selector configured, accept all embedded documents
        UnpackSelector selector = context.get(UnpackSelector.class);
        if (selector != null && !selector.select(metadata)) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("skipping embedded bytes {} <-> {}",
                        metadata.get(Metadata.CONTENT_TYPE),
                        metadata.get(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE));
            }
            return;
        }
        UnpackHandler unpackHandler =
                context.get(UnpackHandler.class);
        int id = metadata.getInt(TikaCoreProperties.EMBEDDED_ID);
        try (InputStream is = Files.newInputStream(p)) {
            if (bytesExtracted >= maxEmbeddedBytesForExtraction) {
                throw new IOException("Bytes extracted (" + bytesExtracted +
                        ") >= max allowed (" + maxEmbeddedBytesForExtraction + ")");
            }
            long maxToRead = maxEmbeddedBytesForExtraction - bytesExtracted;

            try (BoundedInputStream boundedIs = new BoundedInputStream(maxToRead, is)) {
                unpackHandler.add(id, metadata, boundedIs);
                bytesExtracted += boundedIs.getPos();
                if (boundedIs.hasHitBound()) {
                    throw new IOException("Bytes extracted (" + bytesExtracted +
                            ") >= max allowed (" + maxEmbeddedBytesForExtraction + "). Truncated " +
                            "bytes");
                }
            }
        } catch (IOException e) {
            LOGGER.warn("problem writing out embedded bytes", e);
        }
    }
}
