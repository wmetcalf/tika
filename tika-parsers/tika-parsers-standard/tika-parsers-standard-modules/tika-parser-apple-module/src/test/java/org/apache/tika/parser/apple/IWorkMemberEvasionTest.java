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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.ContentHandler;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.exception.EmbeddedLimitReachedException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.iwork.IWorkPackageParser;
import org.apache.tika.parser.iwork.iwana.IWork13PackageParser;
import org.apache.tika.parser.iwork.iwana.IWork18PackageParser;

/**
 * One marker entry routes an arbitrary zip to an iWork parser. Whatever route a zip takes,
 * none of its members may disappear: each one must reach the embedded extractor.
 */
class IWorkMemberEvasionTest {

    private static final String PLIST = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<plist version=\"1.0\"><array><string>x</string></array></plist>";
    private static final String PAYLOAD = "MZ-pretend-payload";

    @TempDir
    Path tmp;

    @Test
    void iwork18MarkerZipStillExtractsMembers() throws Exception {
        iwork18MarkerZipStillExtractsMembers(true);
        iwork18MarkerZipStillExtractsMembers(false);
    }

    private void iwork18MarkerZipStillExtractsMembers(boolean fromFile) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("evil.pages/Metadata/BuildVersionHistory.plist", PLIST);
        entries.put("payload.exe", PAYLOAD);
        entries.put("evil.pages/Data/hidden.bin", PAYLOAD);
        Result r = parse(zip(entries), fromFile);
        assertEquals("application/vnd.apple.pages.18", r.contentType, "routing precondition");
        assertTrue(r.names.contains("payload.exe"), "payload.exe dropped (fromFile=" + fromFile + "): " + r.names);
        assertTrue(r.names.contains("evil.pages/Data/hidden.bin"),
                "hidden.bin dropped (fromFile=" + fromFile + "): " + r.names);
    }

    @Test
    void iwork13SkippedNamesStillReachExtractor() throws Exception {
        iwork13SkippedNamesStillReachExtractor(true);
        iwork13SkippedNamesStillReachExtractor(false);
    }

    private void iwork13SkippedNamesStillReachExtractor(boolean fromFile) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("Metadata/BuildVersionHistory.plist", PLIST);
        entries.put("Index/evil.iwa", PAYLOAD);
        entries.put("preview-micro.jpg", PAYLOAD);
        entries.put("preview-web.jpg", PAYLOAD);
        Result r = parse(zip(entries), fromFile);
        assertTrue(r.contentType.startsWith("application/vnd.apple."),
                "routing precondition (fromFile=" + fromFile + "): " + r.contentType);
        for (String name : new String[]{"Index/evil.iwa", "preview-micro.jpg", "preview-web.jpg"}) {
            assertTrue(r.names.contains(name), name + " dropped (fromFile=" + fromFile + "): " + r.names);
        }
    }

    @Test
    void iwork13MalformedPlistDoesNotHideLaterMembers() throws Exception {
        iwork13MalformedPlistDoesNotHideLaterMembers(true);
        iwork13MalformedPlistDoesNotHideLaterMembers(false);
    }

    private void iwork13MalformedPlistDoesNotHideLaterMembers(boolean fromFile) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("Metadata/BuildVersionHistory.plist", PLIST);
        entries.put("Metadata/Properties.plist", "\u0000not a plist");
        entries.put("payload.exe", PAYLOAD);
        Result r = parse(zip(entries), fromFile);
        assertTrue(r.names.contains("payload.exe"), "payload.exe dropped (fromFile=" + fromFile + "): " + r.names);
    }

    @Test
    void iwork09MarkerZipStillExtractsMembers() throws Exception {
        iwork09MarkerZipStillExtractsMembers(true);
        iwork09MarkerZipStillExtractsMembers(false);
    }

    private void iwork09MarkerZipStillExtractsMembers(boolean fromFile) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("buildVersionHistory.plist", PLIST);
        entries.put("payload.exe", PAYLOAD);
        Result r = parse(zip(entries), fromFile);
        assertTrue(r.contentType.startsWith("application/vnd.apple."),
                "routing precondition (fromFile=" + fromFile + "): " + r.contentType);
        assertTrue(r.names.contains("payload.exe"), "payload.exe dropped (fromFile=" + fromFile + "): " + r.names);
    }

    @Test
    void streamPathsExtractEveryMemberPastAMalformedOne() throws Exception {
        // no file and no open container: each parser walks the raw zip stream
        Map<String, String> iwork13 = new LinkedHashMap<>();
        iwork13.put("Metadata/BuildVersionHistory.plist", PLIST);
        iwork13.put("Metadata/Properties.plist", "\u0000not a plist");
        iwork13.put("Index/evil.iwa", PAYLOAD);
        iwork13.put("preview-web.jpg", PAYLOAD);
        List<String> names = parseDirect(new IWork13PackageParser(), zip(iwork13));
        assertTrue(names.containsAll(List.of("Index/evil.iwa", "preview-web.jpg")),
                "iWork13 stream path: " + names);

        Map<String, String> iwork18 = new LinkedHashMap<>();
        iwork18.put("evil.key/Metadata/BuildVersionHistory.plist", PLIST);
        iwork18.put("payload.exe", PAYLOAD);
        names = parseDirect(new IWork18PackageParser(), zip(iwork18));
        assertTrue(names.containsAll(List.of("evil.key/Metadata/BuildVersionHistory.plist",
                "payload.exe")), "iWork18 stream path: " + names);

        Map<String, String> iwork09 = new LinkedHashMap<>();
        iwork09.put("buildVersionHistory.plist", PLIST);
        iwork09.put("index.xml", "<not-iwork/>");
        iwork09.put("payload.exe", PAYLOAD);
        names = parseDirect(new IWorkPackageParser(), zip(iwork09));
        assertTrue(names.containsAll(List.of("buildVersionHistory.plist", "index.xml",
                "payload.exe")), "iWork09 stream path: " + names);
    }

    @Test
    void embeddedLimitAndHandlerFailuresStopTheWalkUnwrapped() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("evil.key/Metadata/BuildVersionHistory.plist", PLIST);
        entries.put("one.bin", PAYLOAD);
        entries.put("two.bin", PAYLOAD);
        byte[] bytes = zip(entries);
        for (Parser parser : new Parser[]{new IWork18PackageParser(), new IWork13PackageParser(),
                new IWorkPackageParser()}) {
            List<String> asked = new ArrayList<>();
            ParseContext context = new ParseContext();
            context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
                @Override
                public boolean shouldParseEmbedded(Metadata metadata) {
                    asked.add(metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY));
                    throw new EmbeddedLimitReachedException(
                            EmbeddedLimitReachedException.LimitType.MAX_COUNT, 0);
                }

                @Override
                public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                          Metadata metadata, ParseContext ctx, boolean outputHtml) {
                }
            });
            try (TikaInputStream s = TikaInputStream.get(bytes)) {
                assertThrows(EmbeddedLimitReachedException.class, () -> parser.parse(s,
                        new DefaultHandler(), new Metadata(), context), parser.toString());
            }
            assertEquals(1, asked.size(), parser + " kept walking after the limit: " + asked);
        }
    }

    private static List<String> parseDirect(Parser parser, byte[] bytes) throws Exception {
        List<String> names = new ArrayList<>();
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, recorder(names));
        try (TikaInputStream s = TikaInputStream.get(bytes)) {
            try {
                parser.parse(s, new DefaultHandler(), new Metadata(), context);
            } catch (TikaException e) {
                // the malformed member is reported once every member has been handed on
            }
        }
        return names;
    }

    private static EmbeddedDocumentExtractor recorder(List<String> names) {
        return new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return true;
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext supplied, boolean outputHtml)
                    throws IOException {
                names.add(metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY));
                stream.transferTo(OutputStream.nullOutputStream());
            }
        };
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static final class Result {
        final List<String> names = new ArrayList<>();
        String contentType;
    }

    private Result parse(byte[] bytes, boolean fromFile) throws Exception {
        Result result = new Result();
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, recorder(result.names));
        Metadata metadata = new Metadata();
        TikaInputStream stream;
        if (fromFile) {
            Path p = Files.write(Files.createTempFile(tmp, "sample", ".zip"), bytes);
            stream = TikaInputStream.get(p);
        } else {
            stream = TikaInputStream.get(bytes);
        }
        try (TikaInputStream s = stream) {
            try {
                new AutoDetectParser().parse(s, new DefaultHandler(), metadata, context);
            } catch (TikaException e) {
                // a malformed member may still surface as an exception; members must survive it
            }
        }
        result.contentType = metadata.get(Metadata.CONTENT_TYPE);
        return result;
    }
}
