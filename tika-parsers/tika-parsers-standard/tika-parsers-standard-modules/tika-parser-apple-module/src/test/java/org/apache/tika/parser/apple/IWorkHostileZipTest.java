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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.ContentHandler;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.iwork.iwana.IWork13PackageParser;

/**
 * Hand-built zips that a marker entry routes to an iWork parser. Every member the central
 * directory lists must reach the extractor (or be warned about), and a member's own failure
 * must still surface.
 */
class IWorkHostileZipTest {

    private static final byte[] PLIST = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<plist version=\"1.0\"><array><string>x</string></array></plist>")
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] PAYLOAD =
            "MZ-pretend-payload-0123456789".getBytes(StandardCharsets.UTF_8);
    private static final String UUID = "C5ED6463-575C-43B9-8FDA-1957B186C422";

    @TempDir
    Path tmp;

    /** Minimal zip writer that can emit what java.util.zip refuses to. */
    private static final class RawZip {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ByteArrayOutputStream cd = new ByteArrayOutputStream();
        private int cdCount;

        private static void le16(ByteArrayOutputStream o, int v) {
            o.write(v & 0xff);
            o.write((v >>> 8) & 0xff);
        }

        private static void le32(ByteArrayOutputStream o, long v) {
            le16(o, (int) (v & 0xffff));
            le16(o, (int) ((v >>> 16) & 0xffff));
        }

        RawZip entry(String name, byte[] data, int flags, boolean dataDescriptor, int method) {
            byte[] n = name.getBytes(StandardCharsets.UTF_8);
            CRC32 c = new CRC32();
            c.update(data);
            long crc = c.getValue();
            long off = out.size();
            int f = flags | (dataDescriptor ? 8 : 0);
            le32(out, 0x04034b50L);
            le16(out, 20);
            le16(out, f);
            le16(out, method);
            le16(out, 0);
            le16(out, 0x21);
            le32(out, dataDescriptor ? 0 : crc);
            le32(out, dataDescriptor ? 0 : data.length);
            le32(out, dataDescriptor ? 0 : data.length);
            le16(out, n.length);
            le16(out, 0);
            out.writeBytes(n);
            out.writeBytes(data);
            if (dataDescriptor) {
                le32(out, 0x08074b50L);
                le32(out, crc);
                le32(out, data.length);
                le32(out, data.length);
            }
            le32(cd, 0x02014b50L);
            le16(cd, 20);
            le16(cd, 20);
            le16(cd, f);
            le16(cd, method);
            le16(cd, 0);
            le16(cd, 0x21);
            le32(cd, crc);
            le32(cd, data.length);
            le32(cd, data.length);
            le16(cd, n.length);
            for (int i = 0; i < 4; i++) {
                le16(cd, 0);
            }
            le32(cd, 0);
            le32(cd, off);
            cd.writeBytes(n);
            cdCount++;
            return this;
        }

        RawZip entry(String name, byte[] data) {
            return entry(name, data, 0, false, 0);
        }

        RawZip raw(byte[] b) {
            out.writeBytes(b);
            return this;
        }

        byte[] finish() {
            long cdOff = out.size();
            byte[] c = cd.toByteArray();
            out.writeBytes(c);
            le32(out, 0x06054b50L);
            le16(out, 0);
            le16(out, 0);
            le16(out, cdCount);
            le16(out, cdCount);
            le32(out, c.length);
            le32(out, cdOff);
            le16(out, 0);
            return out.toByteArray();
        }
    }

    private static final class Result {
        final List<String> names = new ArrayList<>();
        final Metadata metadata = new Metadata();
        Exception thrown;

        String warnings() {
            return Arrays.toString(
                    metadata.getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING));
        }

        @Override
        public String toString() {
            return "names=" + names + " warn=" + warnings() + " thrown=" + thrown;
        }
    }

    private Result run(byte[] zip, boolean direct13) throws Exception {
        Result r = new Result();
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return true;
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext ctx, boolean outputHtml)
                    throws IOException {
                r.names.add(metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY));
                stream.transferTo(OutputStream.nullOutputStream());
            }
        });
        Path p = Files.write(Files.createTempFile(tmp, "hostile", ".zip"), zip);
        try (TikaInputStream s = direct13 ? TikaInputStream.get(zip) : TikaInputStream.get(p)) {
            if (direct13) {
                new IWork13PackageParser().parse(s, new DefaultHandler(), r.metadata, context);
            } else {
                new AutoDetectParser().parse(s, new DefaultHandler(), r.metadata, context);
            }
        } catch (Exception e) {
            r.thrown = e;
        }
        return r;
    }

    private Result run(byte[] zip) throws Exception {
        return run(zip, false);
    }

    @Test
    void strayLeadingSignatureCannotHideIWork09Members() throws Exception {
        // a local-header walk sees a leading EOCD (or a fake central header) as the end
        byte[] eocd = new byte[22];
        eocd[0] = 'P';
        eocd[1] = 'K';
        eocd[2] = 5;
        eocd[3] = 6;
        Result r = run(new RawZip().raw(eocd).entry("buildVersionHistory.plist", PLIST)
                .entry("payload.exe", PAYLOAD).finish());
        assertTrue(r.names.contains("payload.exe"), r.toString());

        byte[] fakeCentralHeader = new byte[46];
        fakeCentralHeader[0] = 'P';
        fakeCentralHeader[1] = 'K';
        fakeCentralHeader[2] = 1;
        fakeCentralHeader[3] = 2;
        r = run(new RawZip().entry("buildVersionHistory.plist", PLIST).raw(fakeCentralHeader)
                .entry("payload.exe", PAYLOAD).finish());
        assertTrue(r.names.contains("payload.exe"), r.toString());
    }

    @Test
    void packageMetadataNamesCannotHidePayloads() throws Exception {
        byte[] docId = (UUID + new String(PAYLOAD, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
        Result r = run(new RawZip().entry("Metadata/BuildVersionHistory.plist", PLIST)
                .entry("Metadata/DocumentIdentifier", docId)
                .entry("Metadata/DocumentIdentifier", PAYLOAD)
                .entry("visible.txt", PAYLOAD).finish());
        assertEquals(2, r.names.stream().filter("Metadata/DocumentIdentifier"::equals).count(),
                r.toString());
        assertEquals(UUID, r.metadata.get(IWork13PackageParser.IWORKS_DOC_ID));
    }

    @Test
    void directoryNamedMemberWithDataIsHandedOn() throws Exception {
        for (String marker : new String[]{"Metadata/BuildVersionHistory.plist",
                "x.pages/Metadata/BuildVersionHistory.plist", "buildVersionHistory.plist"}) {
            byte[] zip = new RawZip().entry(marker, PLIST).entry("payload/", PAYLOAD)
                    .entry("empty/", new byte[0]).finish();
            Result r = run(zip);
            assertTrue(r.names.contains("payload/"), marker + ": " + r);
            assertTrue(!r.names.contains("empty/"), marker + ": " + r);
        }
        Result r = run(new RawZip().entry("Metadata/BuildVersionHistory.plist", PLIST)
                .entry("payload/", PAYLOAD).finish(), true);
        assertTrue(r.names.contains("payload/"), "stream path: " + r);
    }

    @Test
    void storedEntryWithDataDescriptorDoesNotHideLaterIWork09Members() throws Exception {
        Result r = run(new RawZip().entry("buildVersionHistory.plist", PLIST)
                .entry("stub.bin", PAYLOAD, 0, true, 0).entry("payload.exe", PAYLOAD).finish());
        assertTrue(r.names.contains("stub.bin"), r.toString());
        assertTrue(r.names.contains("payload.exe"), r.toString());
    }

    @Test
    void unreadableMembersAreWarnedAboutAndTheRestExtracted() throws Exception {
        Result r = run(new RawZip().entry("Metadata/BuildVersionHistory.plist", PLIST)
                .entry("enc.bin", PAYLOAD, 1, false, 0).entry("payload.exe", PAYLOAD).finish());
        assertTrue(r.names.contains("payload.exe"), r.toString());
        assertTrue(r.warnings().contains("enc.bin"), r.toString());
        assertNotNull(r.metadata.get(TikaCoreProperties.TIKA_META_EXCEPTION_EMBEDDED_STREAM),
                r.toString());

        // an unreadable iWork '09 content entry is warned about too, not silently dropped
        r = run(new RawZip().entry("buildVersionHistory.plist", PLIST)
                .entry("index.xml", PAYLOAD, 0, false, 98).entry("payload.exe", PAYLOAD)
                .finish());
        assertTrue(r.names.contains("payload.exe"), r.toString());
        assertTrue(r.warnings().contains("index.xml"), r.toString());
    }

    @Test
    void aMalformedMembersFailureStillSurfacesAfterTheRestAreExtracted() throws Exception {
        byte[] zip = new RawZip().entry("Metadata/BuildVersionHistory.plist", PLIST)
                .entry("Metadata/Properties.plist",
                        "\u0000not a plist".getBytes(StandardCharsets.UTF_8))
                .entry("payload.exe", PAYLOAD).finish();
        for (boolean direct : new boolean[]{false, true}) {
            Result r = run(zip, direct);
            assertTrue(r.names.contains("payload.exe"), "direct=" + direct + " " + r);
            assertNotNull(r.thrown, "the malformed plist's failure was dropped: direct="
                    + direct + " " + r);
        }
    }
}
