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
package org.apache.tika.parser.apple;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;

/**
 * The apple container parsers must hand their members the caller's ParseContext, as the
 * archive parsers do (see ArchiveEmbeddedContextTest in the pkg module).
 */
class AppleContainerEmbeddedContextTest {

    @Test
    void iwork13PackageMembersGetCallerContext() throws Exception {
        assertMembersGetCallerContext(fixture("testNumbers2013.numbers"));
    }

    @Test
    void plistDataBlobsGetCallerContext() throws Exception {
        assertMembersGetCallerContext(fixture("testBPList.bplist"));
    }

    @Test
    void zipRoutedToIWork13ByMarkerEntryGetsCallerContext() throws Exception {
        // One marker entry is enough for IWorkDetector to route an arbitrary zip to
        // IWork13PackageParser, so that parser must not drop the caller's context either.
        ByteArrayOutputStream outer = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(outer)) {
            zos.putNextEntry(new ZipEntry("Metadata/BuildVersionHistory.plist"));
            zos.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\">"
                    + "<array><string>x</string></array></plist>").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("payload.txt"));
            zos.write("payload".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        assertMembersGetCallerContext(outer.toByteArray());
    }

    private byte[] fixture(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/test-documents/" + name)) {
            return input.readAllBytes();
        }
    }

    private void assertMembersGetCallerContext(byte[] bytes) throws Exception {
        ParseContext context = new ParseContext();
        AtomicInteger same = new AtomicInteger();
        AtomicInteger fresh = new AtomicInteger();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return true;
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext supplied, boolean outputHtml)
                    throws IOException {
                (supplied == context ? same : fresh).incrementAndGet();
                stream.transferTo(OutputStream.nullOutputStream());
            }
        });
        try (TikaInputStream stream = TikaInputStream.get(bytes)) {
            new AutoDetectParser().parse(stream, new DefaultHandler(), new Metadata(), context);
        }
        assertTrue(same.get() + fresh.get() > 0, "no member reached the extractor");
        assertEquals(0, fresh.get(), "members were handed a fresh ParseContext");
    }
}
