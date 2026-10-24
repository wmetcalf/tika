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
package org.apache.tika.parser.pkg;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;

class ArchiveEmbeddedContextTest {
    @Test
    void zipRetainsCallerContext() throws Exception {
        customExtractorReceivesCallerContext("test-documents.zip");
    }

    @Test
    void descriptorZipRetainsCallerContext() throws Exception {
        customExtractorReceivesCallerContext("testZip_with_DataDescriptor.zip");
    }

    @Test
    void sevenZipRetainsCallerContext() throws Exception {
        customExtractorReceivesCallerContext("test-documents.7z");
    }

    @Test
    void tarRetainsCallerContext() throws Exception {
        customExtractorReceivesCallerContext("test-nested-tarball.tar");
    }

    private void customExtractorReceivesCallerContext(String fixture) throws Exception {
        ParseContext context = new ParseContext();
        AtomicInteger captured = new AtomicInteger();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return true;
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext supplied, boolean outputHtml)
                    throws IOException {
                // Stateful parser decorators, configured limits and XML protections
                // must survive the archive -> embedded extractor boundary.
                assertSame(context, supplied);
                stream.transferTo(java.io.OutputStream.nullOutputStream());
                captured.incrementAndGet();
            }
        });
        try (var input = getClass().getResourceAsStream("/test-documents/" + fixture);
                TikaInputStream stream = TikaInputStream.get(input)) {
            new AutoDetectParser().parse(stream, new DefaultHandler(), new Metadata(), context);
        }
        assertTrue(captured.get() > 0);
    }
}
