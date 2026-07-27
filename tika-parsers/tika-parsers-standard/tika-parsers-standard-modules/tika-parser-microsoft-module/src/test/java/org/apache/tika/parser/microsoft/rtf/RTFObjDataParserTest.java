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
package org.apache.tika.parser.microsoft.rtf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.apache.poi.poifs.filesystem.Ole10Native;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;

import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Office;
import org.apache.tika.metadata.RTFMetadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

public class RTFObjDataParserTest {

    private static final byte[] PAYLOAD =
            "RTF_OLE_PAYLOAD".getBytes(StandardCharsets.US_ASCII);
    private static final String SOURCE_PATH = "C:\\source\\payload.bin";
    private static final String DISTINCT_COMMAND = "C:\\temp\\renamed-payload.bin";

    @Test
    public void testDistinctOleCommandDoesNotSuppressPayload() throws Exception {
        List<ExtractedDocument> extracted = parse(buildRtf(DISTINCT_COMMAND));

        assertEquals(1, extracted.size());
        assertArrayEquals(PAYLOAD, extracted.get(0).bytes);
        assertEquals(SOURCE_PATH,
                extracted.get(0).metadata.get(RTFMetadata.EMB_SOURCE_PATH));
        assertEquals(DISTINCT_COMMAND,
                extracted.get(0).metadata.get(RTFMetadata.EMB_COMMAND));
    }

    @Test
    public void testLinkedOle1ObjectIsSurfacedOnParentMetadata() throws Exception {
        Metadata metadata = parseMetadata(buildLinkedRtf(
                "C:\\Users\\Public\\linked-document.doc", "Sheet1!R1C1"));

        assertEquals("C:\\Users\\Public\\linked-document.doc",
                metadata.get(Office.OFFICE_LINK_URL));
        assertEquals("linked_ole_object",
                metadata.get(Office.OFFICE_LINK_TYPE));
        assertEquals("Sheet1!R1C1",
                metadata.get(Office.OFFICE_LINK_TEXT));
    }

    private static List<ExtractedDocument> parse(byte[] rtf) throws Exception {
        List<ExtractedDocument> extracted = new ArrayList<>();
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return true;
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext parseContext,
                                      boolean outputHtml) throws IOException {
                extracted.add(new ExtractedDocument(stream.readAllBytes(), metadata));
            }
        });

        try (TikaInputStream stream = TikaInputStream.get(rtf)) {
            new RTFParser().parse(stream, new BodyContentHandler(-1), new Metadata(), context);
        }
        return extracted;
    }

    private static Metadata parseMetadata(byte[] rtf) throws Exception {
        Metadata metadata = new Metadata();
        try (TikaInputStream stream = TikaInputStream.get(rtf)) {
            new RTFParser().parse(
                    stream, new BodyContentHandler(-1), metadata, new ParseContext());
        }
        return metadata;
    }

    private static byte[] buildRtf(String command) throws IOException {
        byte[] poifs = buildOleCompoundDocument(command);
        ByteArrayOutputStream objData = new ByteArrayOutputStream();
        writeUInt32LE(objData, 0x00000501);
        writeUInt32LE(objData, 2);
        writeLengthPrefixedAnsi(objData, "Package.1");
        writeUInt32LE(objData, 0);
        writeUInt32LE(objData, 0);
        writeUInt32LE(objData, poifs.length);
        objData.write(poifs);

        String rtf = "{\\rtf1\\ansi"
                + "{\\object\\objemb"
                + "{\\*\\objclass Package}"
                + "{\\*\\objdata " + HexFormat.of().formatHex(objData.toByteArray()) + "}"
                + "}"
                + "}";
        return rtf.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] buildLinkedRtf(String topic, String item) throws IOException {
        ByteArrayOutputStream objData = new ByteArrayOutputStream();
        writeUInt32LE(objData, 0x00000501);
        writeUInt32LE(objData, 1);
        writeLengthPrefixedAnsi(objData, "Excel.Sheet.12");
        writeLengthPrefixedAnsi(objData, topic);
        writeLengthPrefixedAnsi(objData, item);

        String rtf = "{\\rtf1\\ansi"
                + "{\\object\\objlink"
                + "{\\*\\objclass Excel.Sheet.12}"
                + "{\\*\\objdata " + HexFormat.of().formatHex(objData.toByteArray()) + "}"
                + "}"
                + "}";
        return rtf.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] buildOleCompoundDocument(String command) throws IOException {
        Ole10Native nativeRecord =
                new Ole10Native("payload.bin", SOURCE_PATH, command, PAYLOAD);
        ByteArrayOutputStream nativeBytes = new ByteArrayOutputStream();
        nativeRecord.writeOut(nativeBytes);

        try (POIFSFileSystem fs = new POIFSFileSystem();
             ByteArrayOutputStream compoundBytes = new ByteArrayOutputStream()) {
            fs.getRoot().createDocument(Ole10Native.OLE10_NATIVE,
                    new ByteArrayInputStream(nativeBytes.toByteArray()));
            Ole10Native.createOleMarkerEntry(fs);
            fs.writeFilesystem(compoundBytes);
            return compoundBytes.toByteArray();
        }
    }

    private static void writeLengthPrefixedAnsi(ByteArrayOutputStream out, String value)
            throws IOException {
        byte[] bytes = (value + "\0").getBytes(StandardCharsets.US_ASCII);
        writeUInt32LE(out, bytes.length);
        out.write(bytes);
    }

    private static void writeUInt32LE(ByteArrayOutputStream out, long value)
            throws IOException {
        out.write(ByteBuffer.allocate(Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt((int) value)
                .array());
    }

    private record ExtractedDocument(byte[] bytes, Metadata metadata) {
    }
}
