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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.iwork.IWorkPackageParser;
import org.apache.tika.parser.iwork.iwana.IWork13PackageParser;
import org.apache.tika.parser.iwork.iwana.IWork18PackageParser;

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

        /** An entry whose stored bytes are given as-is (e.g. a cut deflate stream). */
        RawZip entryRaw(String name, byte[] stored, int size, int method) {
            byte[] n = name.getBytes(StandardCharsets.UTF_8);
            long off = out.size();
            le32(out, 0x04034b50L);
            le16(out, 20);
            le16(out, 0);
            le16(out, method);
            le16(out, 0);
            le16(out, 0x21);
            le32(out, 0x12345678L);
            le32(out, stored.length);
            le32(out, size);
            le16(out, n.length);
            le16(out, 0);
            out.writeBytes(n);
            out.writeBytes(stored);
            le32(cd, 0x02014b50L);
            le16(cd, 20);
            le16(cd, 20);
            le16(cd, 0);
            le16(cd, method);
            le16(cd, 0);
            le16(cd, 0x21);
            le32(cd, 0x12345678L);
            le32(cd, stored.length);
            le32(cd, size);
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

        /** Junk bytes inside the central directory (counted in its size). */
        RawZip centralJunk(byte[] junk) {
            cd.writeBytes(junk);
            return this;
        }

        /** A central-directory record with no local entry behind it. */
        RawZip centralOnly(String name, long offset) {
            byte[] n = name.getBytes(StandardCharsets.UTF_8);
            le32(cd, 0x02014b50L);
            le16(cd, 20);
            le16(cd, 20);
            le16(cd, 0);
            le16(cd, 0);
            le16(cd, 0);
            le16(cd, 0x21);
            le32(cd, 0);
            le32(cd, 4);
            le32(cd, 4);
            le16(cd, n.length);
            for (int i = 0; i < 4; i++) {
                le16(cd, 0);
            }
            le32(cd, 0);
            le32(cd, offset);
            cd.writeBytes(n);
            cdCount++;
            return this;
        }

        RawZip raw(byte[] b) {
            out.writeBytes(b);
            return this;
        }

        /** Local headers only: no central directory, no EOCD. */
        byte[] finishWithoutCentralDirectory() {
            return out.toByteArray();
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

    private static final String[] MARKERS = {"buildVersionHistory.plist",
        "Metadata/BuildVersionHistory.plist", "x.pages/Metadata/BuildVersionHistory.plist"};

    private static Parser parserFor(String marker) {
        if (marker.startsWith("x.pages")) {
            return new IWork18PackageParser();
        }
        return marker.startsWith("Metadata") ? new IWork13PackageParser()
                : new IWorkPackageParser();
    }

    /** Calls the routed parser directly: on raw bytes (no file, no container) or a file. */
    private Result direct(String marker, byte[] zip, boolean fromFile) throws Exception {
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
        TikaInputStream s = fromFile
                ? TikaInputStream.get(Files.write(Files.createTempFile(tmp, "d", ".zip"), zip))
                : TikaInputStream.get(zip);
        try (TikaInputStream t = s) {
            parserFor(marker).parse(t, new DefaultHandler(), r.metadata, context);
        } catch (Exception e) {
            r.thrown = e;
        }
        return r;
    }

    private static byte[] eocd() {
        byte[] b = new byte[22];
        b[0] = 'P';
        b[1] = 'K';
        b[2] = 5;
        b[3] = 6;
        return b;
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

    @Test
    void everyRouteWalksTheCentralDirectoryWhateverTheInput() throws Exception {
        // a raw (non-file) stream too: a local-header walk would stop at the stray EOCD
        for (String marker : MARKERS) {
            byte[] zip = new RawZip().raw(eocd()).entry(marker, PLIST)
                    .entry("payload.exe", PAYLOAD).finish();
            Result r = direct(marker, zip, false);
            assertTrue(r.names.contains("payload.exe"), marker + ": " + r);
        }
    }

    @Test
    void unreadableMemberOnARawStreamIsWarnedAboutNotFatal() throws Exception {
        for (String marker : MARKERS) {
            byte[] zip = new RawZip().entry(marker, PLIST).entry("enc.bin", PAYLOAD, 1, false, 0)
                    .entry("payload.exe", PAYLOAD).finish();
            Result r = direct(marker, zip, false);
            assertTrue(r.names.contains("payload.exe"), marker + ": " + r);
            assertTrue(r.warnings().contains("enc.bin"), marker + ": " + r);
        }
    }

    @Test
    void memberWhoseDecoderIsMissingDoesNotHideTheRest() throws Exception {
        // method 93 = ZSTD; without zstd-jni on the classpath its stream cannot even open
        for (String marker : MARKERS) {
            byte[] zip = new RawZip().entry(marker, PLIST).entry("decoy.zst", PAYLOAD, 0, false, 93)
                    .entry("payload.exe", PAYLOAD).finish();
            Result r = run(zip);
            assertTrue(r.names.contains("payload.exe"), marker + ": " + r);
            assertTrue(r.names.contains("decoy.zst") || r.warnings().contains("decoy.zst"),
                    marker + ": " + r);
        }
    }

    @Test
    void unreadableCentralDirectoryFallsBackToTheLocalHeaders() throws Exception {
        for (String marker : MARKERS) {
            byte[] zip = new RawZip().entry(marker, PLIST).entry("stub.bin", PAYLOAD, 0, true, 0)
                    .entry("payload.exe", PAYLOAD).finishWithoutCentralDirectory();
            Result r = direct(marker, zip, true);
            assertTrue(r.names.contains("stub.bin"), marker + ": " + r);
            assertTrue(r.names.contains("payload.exe"), marker + ": " + r);
        }
    }

    @Test
    void repeatedIWork09ContentEntriesAreHandedOn() throws Exception {
        byte[] pages = ("<sl:document xmlns:sl=\"http://developer.apple.com/namespaces/sl\">"
                + "<sl:stash>x</sl:stash></sl:document>").getBytes(StandardCharsets.UTF_8);
        Result r = run(new RawZip().entry("buildVersionHistory.plist", PLIST)
                .entry("index.xml", pages).entry("index.xml", pages).entry("index.apxl", pages)
                .finish());
        assertEquals(2, r.names.stream().filter(n -> n.startsWith("index.")).count(),
                r.toString());
    }

    @Test
    void everyFailedMemberIsNamed() throws Exception {
        // method 12 = bzip2, data that is not bzip2: each member fails on its own
        Result r = run(new RawZip().entry("Metadata/BuildVersionHistory.plist", PLIST)
                .entry("decoy.bin", PAYLOAD, 0, false, 12).entry("real.bin", PAYLOAD, 0, false, 12)
                .entry("visible.txt", PAYLOAD).finish());
        assertNotNull(r.thrown, r.toString());
        assertTrue(r.warnings().contains("decoy.bin") && r.warnings().contains("real.bin"),
                r.toString());
        assertTrue(r.names.contains("visible.txt"), r.toString());
    }

    @Test
    void protectedIWork09PackageKeepsItsTypeOnTheFileRoute() throws Exception {
        byte[] bytes;
        try (var in = getClass().getResourceAsStream("/test-documents/testPagesPwdProtected.pages")) {
            bytes = in.readAllBytes();
        }
        Result r = run(bytes);
        assertEquals("application/x-tika-iworks-protected", r.metadata.get(Metadata.CONTENT_TYPE),
                r.toString());
    }

    @Test
    void declinedPackageMetadataIsStillReadAsMetadata() throws Exception {
        byte[] zip = new RawZip().entry("Metadata/BuildVersionHistory.plist", PLIST)
                .entry("Metadata/DocumentIdentifier", UUID.getBytes(StandardCharsets.UTF_8))
                .finish();
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata m) {
                return false;
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata m, ParseContext ctx, boolean outputHtml) {
                throw new AssertionError("declined member parsed");
            }
        });
        try (TikaInputStream s = TikaInputStream.get(zip)) {
            new IWork13PackageParser().parse(s, new DefaultHandler(), metadata, context);
        }
        assertEquals(UUID, metadata.get(IWork13PackageParser.IWORKS_DOC_ID));
        assertEquals("x", metadata.get(IWork13PackageParser.IWORKS_BUILD_VERSION_HISTORY));
    }

    @Test
    void oneBadCentralDirectoryRecordCannotHideTheOthers() throws Exception {
        // commons-compress refuses the whole archive over one inconsistent record; the
        // members the central directory lists must still be walked by it, not by local header
        byte[] fakeCentralHeader = new byte[46];
        fakeCentralHeader[0] = 'P';
        fakeCentralHeader[1] = 'K';
        fakeCentralHeader[2] = 1;
        fakeCentralHeader[3] = 2;
        for (String marker : MARKERS) {
            byte[] zip = new RawZip().entry(marker, PLIST).entry("visible.txt", PAYLOAD)
                    .raw(fakeCentralHeader).entry("payload.exe", PAYLOAD)
                    .centralOnly("bogus.bin", 0xFFFFFF00L).finish();
            for (boolean fromFile : new boolean[]{false, true}) {
                Result r = direct(marker, zip, fromFile);
                assertTrue(r.names.contains("payload.exe"), marker + " file=" + fromFile + " " + r);
                assertTrue(r.warnings().contains("bogus.bin"), marker + " file=" + fromFile + " " + r);
            }
            Result r = run(zip);
            assertTrue(r.names.contains("payload.exe"), marker + " autodetect " + r);
        }
    }

    @Test
    void anErrorFromTheCallersHandlerIsTheCallers() throws Exception {
        for (String fixture : new String[]{"testKeynote.key", "testKeynote2013.key"}) {
            byte[] bytes;
            try (var in = getClass().getResourceAsStream("/test-documents/" + fixture)) {
                bytes = in.readAllBytes();
            }
            DefaultHandler failing = new DefaultHandler() {
                @Override
                public void characters(char[] ch, int start, int length) {
                    throw new ExceptionInInitializerError("caller's own failure");
                }
            };
            try (TikaInputStream s = TikaInputStream.get(bytes)) {
                assertThrows(ExceptionInInitializerError.class, () -> new AutoDetectParser()
                        .parse(s, failing, new Metadata(), new ParseContext()), fixture);
            }
        }
    }

    @Test
    void aDamagedTailEndsTheWalkNotTheParse() throws Exception {
        byte[] bytes;
        try (var in = getClass().getResourceAsStream("/test-documents/testKeynote2013.key")) {
            bytes = in.readAllBytes();
        }
        byte[] noEocd = java.util.Arrays.copyOf(bytes, bytes.length - 22);
        Result r = direct("Metadata/BuildVersionHistory.plist", noEocd, false);
        assertEquals(null, r.thrown, r.toString());
        assertEquals("application/vnd.apple.keynote.13", r.metadata.get(Metadata.CONTENT_TYPE),
                r.toString());
        assertTrue(r.names.size() > 10, r.toString());
    }

    @Test
    void anUnreadableMemberStillIdentifiesThePackage() throws Exception {
        byte[] zip = new RawZip().entry("Metadata/BuildVersionHistory.plist", PLIST)
                .entry("Index/Slide.iwa", PAYLOAD, 1, false, 0).finish();
        Result r = new Result();
        r.metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, "sample.pages");
        try (TikaInputStream s = TikaInputStream.get(zip)) {
            new IWork13PackageParser().parse(s, new DefaultHandler(), r.metadata,
                    new ParseContext());
        }
        assertEquals(IWork13PackageParser.IWork13DocumentType.KEYNOTE13.getType().toString(),
                r.metadata.get(Metadata.CONTENT_TYPE));
    }

    @Test
    void aStrayRecordInsideTheCentralDirectoryCannotHideLaterMembers() throws Exception {
        // commons-compress stops reading the central directory at the junk; the members after
        // it must still be walked (from another reader) and the inconsistency recorded
        for (String marker : MARKERS) {
            byte[] zip = new RawZip().entry(marker, PLIST).entry("visible.txt", PAYLOAD)
                    .centralJunk(new byte[]{'J', 'U', 'N', 'K'}).entry("payload.exe", PAYLOAD)
                    .finish();
            for (boolean fromFile : new boolean[]{false, true}) {
                Result r = direct(marker, zip, fromFile);
                assertTrue(r.names.contains("payload.exe"), marker + " file=" + fromFile + " " + r);
                assertTrue(r.warnings().contains("inconsistent"), marker + " " + r);
            }
            assertTrue(run(zip).names.contains("payload.exe"), marker);
        }
    }

    @Test
    void autoDetectDoesNotTrustTheDetectorsSalvagedCopy() throws Exception {
        // no central directory: the detector salvages a copy that silently drops unreadable
        // and directory-named members; the parser walks the local headers itself
        for (String marker : MARKERS) {
            byte[] zip = new RawZip().entry(marker, PLIST).entry("enc.bin", PAYLOAD, 1, false, 0)
                    .entry("payload/", PAYLOAD).entry("visible.txt", PAYLOAD)
                    .finishWithoutCentralDirectory();
            Result r = run(zip);
            assertTrue(r.names.contains("payload/"), marker + " " + r);
            assertTrue(r.warnings().contains("enc.bin"), marker + " " + r);
        }
    }

    @Test
    void theDocumentsHeadKeepsItsMetadata() throws Exception {
        for (String fixture : new String[]{"testPages.pages", "testPagesComments.pages"}) {
            byte[] bytes;
            try (var in = getClass().getResourceAsStream("/test-documents/" + fixture)) {
                bytes = in.readAllBytes();
            }
            Metadata metadata = new Metadata();
            org.apache.tika.sax.ToXMLContentHandler xml = new org.apache.tika.sax.ToXMLContentHandler();
            try (TikaInputStream s = TikaInputStream.get(bytes)) {
                new AutoDetectParser().parse(s, xml, metadata, new ParseContext());
            }
            String head = xml.toString().substring(0, xml.toString().indexOf("</head>"));
            String title = metadata.get(TikaCoreProperties.TITLE);
            assertNotNull(title, fixture);
            assertTrue(head.contains("<title>" + title + "</title>"), fixture + ": " + head);
        }
    }

    @Test
    void anUnreadableLaterContentEntryDoesNotRelabelTheDocument() throws Exception {
        byte[] pages = ("<sl:document xmlns:sl=\"http://developer.apple.com/namespaces/sl\">"
                + "<sl:p>x</sl:p></sl:document>").getBytes(StandardCharsets.UTF_8);
        Result r = run(new RawZip().entry("buildVersionHistory.plist", PLIST)
                .entry("index.xml", pages).entry("index.apxl", PAYLOAD, 1, false, 0).finish());
        assertEquals("application/vnd.apple.pages", r.metadata.get(Metadata.CONTENT_TYPE),
                r.toString());
    }

    /**
     * Hand-built zips (2026-11-18 review of this change): alias-* list the same names in two
     * central directories (a shadow one, behind a skewed EOCD in the comment, which
     * commons-compress takes) with different data; lfh* truncate commons-compress's view with a
     * junk record while an entry the JDK refuses stops a local-header cross-check early.
     * crcalias forges the real CRC and sizes in the shadow directory's records;
     * dd* hide a same-named data-descriptor entry behind a decoy; cfh* put a stray central
     * header in the local headers. None may hide payload.exe ("MZ-HIDDEN-PAYLOAD") without a
     * trace.
     */
    @Test
    void readersThatDisagreeAreNeverSilent() throws Exception {
        for (String kind : new String[]{"alias", "crcalias", "lfhbz2", "lfhenc", "ddalias",
                "ddaliasdefl", "ddaliasrev", "ddaliasrev2defl", "cfhstop", "cfhshadow"}) {
            for (String d : new String[]{"09", "13", "18"}) {
                String name = "iwork-hostile/" + kind + "-d" + d + ".zip";
                byte[] bytes;
                try (var in = getClass().getResourceAsStream("/test-documents/" + name)) {
                    bytes = in.readAllBytes();
                }
                List<String> contents = new ArrayList<>();
                Metadata metadata = new Metadata();
                ParseContext context = new ParseContext();
                context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
                    @Override
                    public boolean shouldParseEmbedded(Metadata m) {
                        return true;
                    }

                    @Override
                    public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                              Metadata m, ParseContext ctx, boolean outputHtml)
                            throws IOException {
                        contents.add(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                    }
                });
                try (TikaInputStream s = TikaInputStream.get(bytes)) {
                    new AutoDetectParser().parse(s, new DefaultHandler(), metadata, context);
                } catch (Exception e) {
                    // a surfaced failure is fine; silence is not
                }
                String warnings = String.join("|",
                        metadata.getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING));
                boolean handedOn = contents.stream().anyMatch(c -> c.contains("MZ-HIDDEN-PAYLOAD"));
                assertTrue(handedOn || warnings.contains("inconsistent")
                        || warnings.contains("ended early"), name + ": " + warnings);
                if (kind.equals("alias")) {
                    assertTrue(handedOn, name + " (the JDK reads it, so it must be handed on)");
                }
            }
        }
    }

    @Test
    void aMalformedPackageMemberIsStillHandedOn() throws Exception {
        // a member whose bytes are readable but whose parse failed must still reach the
        // extractor: a malformed plist or document is a fine place to hide a payload
        byte[] notAPlist = ("MZ" + new String(PAYLOAD, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
        byte[] badXml = "<sl:document xmlns:sl=\"http://developer.apple.com/namespaces/sl\"><"
                .getBytes(StandardCharsets.UTF_8);
        Result r13 = run(new RawZip().entry("Metadata/BuildVersionHistory.plist", PLIST)
                .entry("Metadata/Properties.plist", notAPlist).finish());
        assertTrue(r13.names.contains("Metadata/Properties.plist"), r13.toString());
        Result r09 = run(new RawZip().entry("buildVersionHistory.plist", PLIST)
                .entry("index.xml", badXml).finish());
        assertTrue(r09.names.contains("index.xml"), r09.toString());
    }

    @Test
    void legitimatePackagesTheJdkRefusesCarryNoEvasionWarning() throws Exception {
        // password-protected iWork '09 uses compression methods the JDK refuses; a bzip2 member
        // does the same. Neither is evidence of a hidden member.
        byte[] bytes;
        try (var in = getClass().getResourceAsStream("/test-documents/testPagesPwdProtected.pages")) {
            bytes = in.readAllBytes();
        }
        Result r = run(bytes);
        assertTrue(!r.warnings().contains("inconsistent") && !r.warnings().contains("cross-check")
                && !r.warnings().contains("local header"), r.toString());
        r = run(new RawZip().entry("Metadata/BuildVersionHistory.plist", PLIST)
                .entry("b.bin", PAYLOAD, 0, false, 12).entry("visible.txt", PAYLOAD).finish());
        assertTrue(!r.warnings().contains("inconsistent") && !r.warnings().contains("local header"),
                r.toString());
    }

    @Test
    void aMemberThatFailsMidReadIsReportedOnce() throws Exception {
        // a deflate stream that ends early fails in pass 1 and again when pass 2 hands it on
        java.io.ByteArrayOutputStream deflated = new java.io.ByteArrayOutputStream();
        try (java.util.zip.DeflaterOutputStream d = new java.util.zip.DeflaterOutputStream(deflated,
                new java.util.zip.Deflater(9, true))) {
            d.write(PLIST);
        }
        byte[] truncated = java.util.Arrays.copyOf(deflated.toByteArray(), deflated.size() / 2);
        Result r = run(new RawZip().entry("Metadata/BuildVersionHistory.plist", PLIST)
                .entryRaw("Metadata/Properties.plist", truncated, PLIST.length, 8).finish());
        long reports = java.util.Arrays.stream(
                r.metadata.getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING))
                .filter(w -> w.contains("Metadata/Properties.plist")).count();
        assertEquals(1, reports, r.toString());
    }

    private static List<String> resources(String dir) throws Exception {
        java.net.URL url = IWorkHostileZipTest.class.getResource("/test-documents/" + dir);
        try (var files = Files.list(Path.of(url.toURI()))) {
            return files.map(p -> dir + "/" + p.getFileName()).sorted()
                    .collect(java.util.stream.Collectors.toList());
        }
    }

    private static String[] parseAuto(String name, List<String> contents) throws Exception {
        byte[] bytes;
        try (var in = IWorkHostileZipTest.class.getResourceAsStream("/test-documents/" + name)) {
            bytes = in.readAllBytes();
        }
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata m) {
                return true;
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata m, ParseContext ctx, boolean outputHtml)
                    throws IOException {
                contents.add(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        });
        try (TikaInputStream s = TikaInputStream.get(bytes)) {
            new AutoDetectParser().parse(s, new DefaultHandler(), metadata, context);
        } catch (Exception e) {
            // a surfaced failure is fine for these checks
        }
        return metadata.getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING);
    }

    /**
     * The hostile fixtures again, with the EOCD's entry counts forged (to what commons-compress
     * sees, or 0xFFFF) or the real directory deferring to zip64: no count, CRC or offset any
     * directory claims may silence the check. Every local header outside commons-compress's
     * members is read at its own offset.
     */
    @Test
    void forgedDirectoryFieldsCannotSilenceTheCheck() throws Exception {
        for (String name : resources("iwork-hostile/forged")) {
            List<String> contents = new ArrayList<>();
            String warnings = String.join("|", parseAuto(name, contents));
            boolean handedOn = contents.stream().anyMatch(c -> c.contains("MZ-HIDDEN-PAYLOAD")
                    || c.contains("MZ-pretend-payload"));
            assertTrue(handedOn || warnings.contains("inconsistent"), name + ": " + warnings);
            if (name.contains("dupname-") || name.contains("z64skip-") || name.contains("/p1")
                    || name.contains("/p2") || name.contains("/s1-")) {
                // readers other than commons-compress see this payload: it must be handed on
                assertTrue(handedOn, name + " (payload not handed on): " + warnings);
            }
        }
    }

    /** Legitimate packages (zip64, data descriptors, bzip2 members, stored inner zips such as a
     * re-zipped Keynote bundle's Index.zip) carry no evasion warning. */
    @Test
    void legitimatePackagesCarryNoEvasionWarning() throws Exception {
        for (String name : resources("iwork-legit")) {
            String warnings = String.join("|", parseAuto(name, new ArrayList<>()));
            assertTrue(!warnings.contains("inconsistent") && !warnings.contains("local header"),
                    name + ": " + warnings);
        }
    }

    @Test
    void anEmbeddedLimitOnAnAlreadyNamedMemberStillStopsTheWalk() throws Exception {
        // pass 1 names the malformed plist; pass 2 hands it on quietly, but a limit that lands on
        // it is not about the member and must still propagate
        byte[] zip = new RawZip().entry("Metadata/BuildVersionHistory.plist", PLIST)
                .entry("Metadata/Properties.plist", "NOT-A-PLIST".getBytes(StandardCharsets.UTF_8))
                .entry("payload.exe", PAYLOAD).finish();
        List<String> asked = new ArrayList<>();
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata m) {
                return true;
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata m, ParseContext ctx, boolean outputHtml) {
                String name = m.get(TikaCoreProperties.RESOURCE_NAME_KEY);
                asked.add(name);
                if (name.equals("Metadata/Properties.plist")) {
                    throw new org.apache.tika.exception.EmbeddedLimitReachedException(
                            org.apache.tika.exception.EmbeddedLimitReachedException.LimitType.MAX_COUNT, 1);
                }
            }
        });
        try (TikaInputStream s = TikaInputStream.get(zip)) {
            assertThrows(org.apache.tika.exception.EmbeddedLimitReachedException.class,
                    () -> new IWork13PackageParser().parse(s, new DefaultHandler(), new Metadata(),
                            context));
        }
        assertTrue(!asked.contains("payload.exe"), asked.toString());
    }

    @Test
    void aMemberSeenByTwoRoutesIsHandedOnOnce() throws Exception {
        // shadow directory: only the JDK lists payload.exe; the scan also finds its local header
        // (with a data descriptor, its identity is known only after its data is read)
        for (String name : resources("iwork-hostile/dup")) {
            List<String> contents = new ArrayList<>();
            parseAuto(name, contents);
            assertEquals(1, contents.stream().filter(c -> c.contains("MZ-HIDDEN-PAYLOAD")).count(),
                    name);
        }
    }

    @Test
    void strayLocalHeaderSignaturesAreBounded() throws Exception {
        // 64k back-to-back "PK\3\4" before the central directory of a valid package
        byte[] spam = new byte[256 * 1024];
        for (int i = 0; i < spam.length; i += 4) {
            spam[i] = 'P';
            spam[i + 1] = 'K';
            spam[i + 2] = 3;
            spam[i + 3] = 4;
        }
        byte[] zip = new RawZip().entry("Metadata/BuildVersionHistory.plist", PLIST)
                .entry("visible.txt", PAYLOAD).raw(spam).finish();
        long start = System.nanoTime();
        Result r = run(zip);
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(millis < 15_000, millis + " ms");
        assertTrue(r.names.size() < 300, r.names.size() + " members");
        assertTrue(r.metadata.getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length < 300,
                r.warnings().length() + " chars of warnings");
        assertTrue(r.warnings().contains("do not parse") || r.warnings().contains("inconsistent"),
                r.warnings());
    }

    @Test
    void aMemberEmbeddedInTheCentralDirectoryIsNotHidden() throws Exception {
        // a complete local member placed after the last central record, inside the directory's
        // byte range: no directory lists it, and the scan must not skip the region blindly
        RawZip hidden = new RawZip().entry("payload.exe",
                "MZ-HIDDEN-PAYLOAD".getBytes(StandardCharsets.UTF_8));
        for (String marker : MARKERS) {
            byte[] zip = new RawZip().entry(marker, PLIST).entry("visible.txt", PAYLOAD)
                    .centralJunk(hidden.out.toByteArray()).finish();
            Result r = run(zip);
            assertTrue(r.names.contains("payload.exe") || r.warnings().contains("inconsistent"),
                    marker + ": " + r);
        }
    }
}
