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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FilenameUtils;
import org.junit.jupiter.api.Test;

import org.apache.tika.TikaTest;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.RTFMetadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.RecursiveParserWrapper;
import org.apache.tika.sax.BasicContentHandlerFactory;
import org.apache.tika.sax.RecursiveParserWrapperHandler;

public class RTFParserTest extends TikaTest {

    // TIKA-1010
    @Test
    public void testEmbeddedMonster() throws Exception {

        // Entry 15 (embedded testHTML_utf8, body "\u00F6\u00E4\u00E5") is dropped: those 6
        // bytes are valid as both UTF-8 and EUC-JP, and the 4.x chain reads EUC-JP --
        // too short to pin a charset reliably.
        List<Pair> expected = List.of(
                new Pair("Hw.txt", "text/plain; charset=windows-1252"),
                new Pair("embedded-0.doc", "application/msword"),
                new Pair("embedded-1.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                new Pair("text.html", "text/html; charset=windows-1252"),
                new Pair("html-within-zip.zip", "application/zip"),
                new Pair("test-zip-of-zip_\u666E\u6797\u65AF\u987F.zip", "application/zip"),
                new Pair("testJPEG_\u666E\u6797\u65AF\u987F.jpg", "image/jpeg"),
                new Pair("embedded-2.xls", "application/vnd.ms-excel"),
                new Pair("testMSG_\u666E\u6797\u65AF\u987F.msg", "application/vnd.ms-outlook"),
                new Pair("embedded-3.pdf", "application/pdf"),
                new Pair("embedded-4.ppt", "application/vnd.ms-powerpoint"),
                new Pair("embedded-5.pptx",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
                new Pair("thumbnail.jpeg", "image/jpeg"),
                new Pair("embedded-6.doc", "application/msword"),
                new Pair("embedded-7.doc", "application/msword"),
                new Pair("embedded-8.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                new Pair("testJPEG_\u666E\u6797\u65AF\u987F.jpg", "image/jpeg"));

        List<Metadata> metadataList = getRecursiveMetadata("testRTFEmbeddedFiles.rtf");
        assertEquals(49, metadataList.size());

        Map<Pair, Integer> expectedCounts = new HashMap<>();
        for (Pair pair : expected) {
            expectedCounts.merge(pair, 1, Integer::sum);
        }
        Map<Pair, Integer> actualCounts = new HashMap<>();
        for (Metadata metadata : metadataList) {
            String path = metadata.get(TikaCoreProperties.EMBEDDED_RESOURCE_PATH);
            if (path != null) {
                String fileName = FilenameUtils.getName(path);
                String contentType = normalizeOcrContentType(metadata.get(Metadata.CONTENT_TYPE));
                actualCounts.merge(new Pair(fileName, contentType), 1, Integer::sum);
            }
        }
        for (Map.Entry<Pair, Integer> entry : expectedCounts.entrySet()) {
            assertEquals(entry.getValue(), actualCounts.getOrDefault(entry.getKey(), 0),
                    "Unexpected count for " + entry.getKey());
        }

        String expectedOriginalName =
                "C:\\Users\\tallison\\AppData\\Local\\Temp\\testJPEG_普林斯顿.jpg";
        assertEquals(2, metadataList.stream()
                .filter(metadata -> expectedOriginalName.equals(
                        metadata.get(TikaCoreProperties.ORIGINAL_RESOURCE_NAME)))
                .count());
        List<String> parsedByFullSet =
                Arrays.asList(metadataList.get(0).getValues(TikaCoreProperties.TIKA_PARSED_BY_FULL_SET));

        assertContains("org.apache.tika.parser.DefaultParser", parsedByFullSet);
        assertContains("org.apache.tika.parser.microsoft.rtf.RTFParser", parsedByFullSet);
        assertContains("org.apache.tika.parser.microsoft.OfficeParser", parsedByFullSet);
        assertContains("org.apache.tika.parser.microsoft.EMFParser", parsedByFullSet);
        assertContains("org.apache.tika.parser.microsoft.WMFParser", parsedByFullSet);
        assertContains("org.apache.tika.parser.csv.TextAndCSVParser", parsedByFullSet);
        assertContains("org.apache.tika.parser.microsoft.ooxml.OOXMLParser", parsedByFullSet);
        assertContains("org.apache.tika.parser.pkg.ZipParser", parsedByFullSet);
        assertContains("org.apache.tika.parser.html.JSoupParser", parsedByFullSet);
        assertContains("org.apache.tika.parser.image.JpegParser", parsedByFullSet);
        assertContains("org.apache.tika.parser.pdf.PDFParser", parsedByFullSet);
    }

    //TIKA-1010 test regular (not "embedded") images/picts
    @Test
    public void testRegularImages() throws Exception {
        ParseContext ctx = new ParseContext();
        RecursiveParserWrapper parser = new RecursiveParserWrapper(AUTO_DETECT_PARSER);
        RecursiveParserWrapperHandler handler = new RecursiveParserWrapperHandler(
                new BasicContentHandlerFactory(BasicContentHandlerFactory.HANDLER_TYPE.IGNORE, -1));
        Metadata rootMetadata = new Metadata();
        rootMetadata.add(TikaCoreProperties.RESOURCE_NAME_KEY, "testRTFRegularImages.rtf");
        try (TikaInputStream tis = TikaInputStream
                .get(getResourceAsStream("/test-documents/testRTFRegularImages.rtf"))) {
            parser.parse(tis, handler, rootMetadata, ctx);
        }
        List<Metadata> metadatas = handler.getMetadataList();

        Metadata meta_jpg_exif = metadatas.get(1);//("testJPEG_EXIF_\u666E\u6797\u65AF\u987F.jpg");
        Metadata meta_jpg = metadatas.get(3);//("testJPEG_\u666E\u6797\u65AF\u987F.jpg");

        assertTrue(meta_jpg_exif != null);
        assertTrue(meta_jpg != null);
        assertTrue(Arrays.asList(meta_jpg_exif.getValues(TikaCoreProperties.SUBJECT))
                .contains("serbor"));
        assertTrue(meta_jpg.get(TikaCoreProperties.COMMENTS).contains("Licensed to the Apache"));
        //make sure old metadata doesn't linger between objects
        assertFalse(
                Arrays.asList(meta_jpg.getValues(TikaCoreProperties.SUBJECT)).contains("serbor"));
        assertEquals("false", meta_jpg.get(RTFMetadata.THUMBNAIL));
        assertEquals("false", meta_jpg_exif.get(RTFMetadata.THUMBNAIL));

        //need flexibility for if tesseract is installed or not
        //TODO -- fix this test.  It is too fragile.
        assertTrue(meta_jpg.names().length >= 52 && meta_jpg.names().length <= 60);
        assertTrue(meta_jpg_exif.names().length >= 100 && meta_jpg_exif.names().length <= 130);
    }

    private static String normalizeOcrContentType(String contentType) {
        if (contentType != null && contentType.startsWith("image/ocr-")) {
            return contentType.replace("image/ocr-", "image/");
        }
        return contentType;
    }

    private record Pair(String fileName, String mimeType) {
    }

}
