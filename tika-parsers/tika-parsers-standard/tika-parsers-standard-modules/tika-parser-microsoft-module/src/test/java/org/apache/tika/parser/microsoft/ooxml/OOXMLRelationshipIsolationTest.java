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
package org.apache.tika.parser.microsoft.ooxml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.junit.jupiter.api.Test;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ColorAwareConfig;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.image.BoundedColorGridCollector;
import org.apache.tika.parser.microsoft.ooxml.xslf.XSLFEventBasedPowerPointExtractor;
import org.apache.tika.sax.ToXMLContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;

class OOXMLRelationshipIsolationTest {

    private static final String DRAWING_REL =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing";
    private static final String HYPERLINK_REL =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink";
    private static final String NOTES_REL =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesSlide";
    private static final String DRAWING_XML = """
            <xdr:wsDr
              xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing"
              xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
              xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <xdr:sp><xdr:txBody><a:p><a:r><a:t>shape</a:t></a:r>
                <a:hlinkClick r:id="rId1"/></a:p></xdr:txBody></xdr:sp>
            </xdr:wsDr>
            """;

    @Test
    void testDrawingRelationshipIdsRemainPartLocal() throws Exception {
        ParseContext context = new ParseContext();
        Metadata metadata = new Metadata();
        ToXMLContentHandler output = new ToXMLContentHandler();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(output, metadata, context);
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            PackagePart sheet = opcPackage.createPart(
                    PackagingURIHelper.createPartName("/xl/worksheets/sheet1.xml"),
                    "application/xml");
            PackagePart first = drawing(opcPackage, sheet, 1,
                    "https://first.example/");
            PackagePart second = drawing(opcPackage, sheet, 2,
                    "https://second.example/");
            assertEquals("rId1", first.getRelationshipsByType(HYPERLINK_REL)
                    .getRelationship(0).getId());
            assertEquals("rId1", second.getRelationshipsByType(HYPERLINK_REL)
                    .getRelationship(0).getId());

            xhtml.startDocument();
            new TestExcelExtractor(context, opcPackage)
                    .processDrawingParts(sheet, xhtml, metadata, context);
            xhtml.endDocument();
        }

        String extracted = output.toString();
        assertEquals(2, occurrences(extracted, "https://first.example/"));
        assertEquals(2, occurrences(extracted, "https://second.example/"));
    }

    @Test
    void testPowerPointAuxiliaryRelationshipIdsRemainPartLocal() throws Exception {
        ParseContext context = new ParseContext();
        Metadata metadata = new Metadata();
        ToXMLContentHandler output = new ToXMLContentHandler();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(output, metadata, context);
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            PackagePart parent = opcPackage.createPart(
                    PackagingURIHelper.createPartName("/ppt/slides/slide1.xml"),
                    "application/xml");
            PackagePart notes = opcPackage.createPart(
                    PackagingURIHelper.createPartName("/ppt/notesSlides/notesSlide1.xml"),
                    "application/xml");
            write(notes, """
                    <p:notes xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                      xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                      xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <a:p><a:r><a:t>notes target</a:t></a:r>
                        <a:hlinkClick r:id="rId1"/></a:p>
                    </p:notes>
                    """);
            parent.addExternalRelationship("https://decoy.example/", HYPERLINK_REL,
                    "rId1");
            parent.addRelationship(notes.getPartName(), TargetMode.INTERNAL,
                    NOTES_REL);
            notes.addExternalRelationship("https://attacker.example/", HYPERLINK_REL,
                    "rId1");
            assertEquals(1, parent.getRelationshipsByType(NOTES_REL).size());
            SXSLFPowerPointExtractorDecorator decorator =
                    new SXSLFPowerPointExtractorDecorator(metadata, context,
                            new XSLFEventBasedPowerPointExtractor(opcPackage));

            xhtml.startDocument();
            invokeColorTextPart(decorator, parent, xhtml);
            xhtml.endDocument();
        }

        String extracted = output.toString();
        assertEquals(1, occurrences(extracted, "https://attacker.example/"));
        assertEquals(0, occurrences(extracted, "https://decoy.example/"));
    }

    @Test
    void testMalformedPowerPointAuxiliaryPartAbandonsColorRow() throws Exception {
        ParseContext context = new ParseContext();
        context.set(ColorAwareConfig.class, new ColorAwareConfig().setEnabled(true));
        Metadata metadata = new Metadata();
        ToXMLContentHandler output = new ToXMLContentHandler();
        XHTMLContentHandler xhtml = new XHTMLContentHandler(output, metadata, context);
        BoundedColorGridCollector rows;
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            PackagePart parent = opcPackage.createPart(
                    PackagingURIHelper.createPartName("/ppt/slides/slide1.xml"),
                    "application/xml");
            PackagePart notes = opcPackage.createPart(
                    PackagingURIHelper.createPartName("/ppt/notesSlides/notesSlide1.xml"),
                    "application/xml");
            write(notes, """
                    <p:notes xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                      xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                      <a:p><a:r><a:rPr><a:solidFill><a:srgbClr val="000000"/>
                      </a:solidFill></a:rPr><a:t>X</a:t></a:r>
                    """);
            parent.addRelationship(notes.getPartName(), TargetMode.INTERNAL,
                    NOTES_REL);
            SXSLFPowerPointExtractorDecorator decorator =
                    new SXSLFPowerPointExtractorDecorator(metadata, context,
                            new XSLFEventBasedPowerPointExtractor(opcPackage));

            xhtml.startDocument();
            invokeColorTextPart(decorator, parent, xhtml);
            xhtml.endDocument();
            Field rowsField = SXSLFPowerPointExtractorDecorator.class
                    .getDeclaredField("pptxColorRows");
            rowsField.setAccessible(true);
            rows = (BoundedColorGridCollector) rowsField.get(decorator);
        }

        assertEquals(0, rows.getCellCount());
        assertTrue(rows.isTruncated());
    }

    private static void invokeColorTextPart(
            SXSLFPowerPointExtractorDecorator decorator, PackagePart parent,
            XHTMLContentHandler xhtml) throws Exception {
        Method method = SXSLFPowerPointExtractorDecorator.class.getDeclaredMethod(
                "handleColorTextPart", String.class, String.class,
                PackagePart.class, XHTMLContentHandler.class, boolean.class);
        method.setAccessible(true);
        method.invoke(decorator, NOTES_REL, "slide-notes", parent, xhtml, false);
    }

    private static PackagePart drawing(OPCPackage opcPackage, PackagePart sheet,
            int index, String target) throws Exception {
        PackagePart drawing = opcPackage.createPart(
                PackagingURIHelper.createPartName(
                        "/xl/drawings/drawing" + index + ".xml"),
                "application/xml");
        write(drawing, DRAWING_XML);
        sheet.addRelationship(drawing.getPartName(), TargetMode.INTERNAL,
                DRAWING_REL);
        drawing.addExternalRelationship(target, HYPERLINK_REL, "rId1");
        return drawing;
    }

    private static void write(PackagePart part, String xml) throws Exception {
        try (java.io.OutputStream stream = part.getOutputStream()) {
            stream.write(xml.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        int cursor = 0;
        while ((cursor = haystack.indexOf(needle, cursor)) >= 0) {
            count++;
            cursor += needle.length();
        }
        return count;
    }

    private static final class TestExcelExtractor
            extends XSSFExcelExtractorDecorator {

        private TestExcelExtractor(ParseContext context, OPCPackage opcPackage) {
            super(context, opcPackage, Locale.ROOT);
        }

        private void processDrawingParts(PackagePart sheet,
                XHTMLContentHandler xhtml, Metadata metadata,
                ParseContext context) throws Exception {
            this.metadata = metadata;
            this.parseContext = context;
            processDrawings(sheet, xhtml);
        }
    }
}
