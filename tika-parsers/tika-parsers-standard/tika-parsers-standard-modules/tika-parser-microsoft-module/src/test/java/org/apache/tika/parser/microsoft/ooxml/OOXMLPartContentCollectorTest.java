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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.xwpf.usermodel.XWPFRelation;
import org.junit.jupiter.api.Test;
import org.xml.sax.helpers.AttributesImpl;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.microsoft.ooxml.xwpf.XWPFEventBasedWordExtractor;
import org.apache.tika.utils.XMLReaderUtils;

class OOXMLPartContentCollectorTest {

    private static final String W_NS =
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    @Test
    void testDefaultCollectorBoundsRetainedPartCount() throws Exception {
        OOXMLPartContentCollector.CollectionBudget budget =
                OOXMLPartContentCollector.newDefaultCollectionBudget();
        OOXMLPartContentCollector collector =
                new OOXMLPartContentCollector(
                        Set.of("footnote"), Set.of("0", "-1"), budget);
        parse(partXml("footnotes", "footnote", 1_025), collector);

        assertEquals(1_024, collector.getContentMap().size());
        assertTrue(budget.isLimitReached());
    }

    @Test
    void testExactDefaultPartCountIsComplete() throws Exception {
        OOXMLPartContentCollector.CollectionBudget budget =
                OOXMLPartContentCollector.newDefaultCollectionBudget();
        OOXMLPartContentCollector collector =
                new OOXMLPartContentCollector(
                        Set.of("footnote"), Set.of("0", "-1"), budget);
        parse(partXml("footnotes", "footnote", 1_024), collector);

        assertEquals(1_024, collector.getContentMap().size());
        assertFalse(budget.isLimitReached());
    }

    @Test
    void testDefaultCollectorDropsFragmentOverSerializedByteBudget()
            throws Exception {
        String xml = "<w:footnotes xmlns:w=\"" + W_NS + "\">"
                + "<w:footnote w:id=\"1\"><w:p><w:r><w:t>"
                + "x".repeat(8 * 1_024 * 1_024)
                + "</w:t></w:r></w:p></w:footnote></w:footnotes>";

        OOXMLPartContentCollector collector =
                new OOXMLPartContentCollector(Set.of("footnote"));
        parse(xml, collector);

        assertEquals(0, collector.getContentMap().size());
    }

    @Test
    void testEscapingPreservesSurrogatePairAtChunkBoundary() throws Exception {
        OOXMLPartContentCollector collector =
                new OOXMLPartContentCollector(Set.of("footnote"));
        AttributesImpl attributes = new AttributesImpl();
        attributes.addAttribute(W_NS, "id", "w:id", "CDATA", "1");
        collector.startPrefixMapping("w", W_NS);
        collector.startElement(W_NS, "footnote", "w:footnote", attributes);
        char[] text = ("a".repeat(4_095) + "\uD83D\uDE00").toCharArray();
        collector.characters(text, 0, text.length);
        collector.endElement(W_NS, "footnote", "w:footnote");

        String serialized = new String(
                collector.getContentMap().get("1"), StandardCharsets.UTF_8);
        assertTrue(serialized.contains("\uD83D\uDE00"));
    }

    @Test
    void testEscapingPreservesSurrogatePairAcrossCharacterCallbacks()
            throws Exception {
        OOXMLPartContentCollector collector =
                new OOXMLPartContentCollector(Set.of("footnote"));
        AttributesImpl attributes = new AttributesImpl();
        attributes.addAttribute(W_NS, "id", "w:id", "CDATA", "1");
        collector.startPrefixMapping("w", W_NS);
        collector.startElement(W_NS, "footnote", "w:footnote", attributes);
        char[] firstCallback = ("a".repeat(4_095) + "\uD83D").toCharArray();
        collector.characters(firstCallback, 0, firstCallback.length);
        collector.characters(new char[]{'\uDE00'}, 0, 1);
        collector.endElement(W_NS, "footnote", "w:footnote");

        String serialized = new String(
                collector.getContentMap().get("1"), StandardCharsets.UTF_8);
        assertTrue(serialized.contains("\uD83D\uDE00"));
    }

    @Test
    void testDocumentWideLimitAcrossNoteTypesIsSignaledOnce() throws Exception {
        ParseContext context = new ParseContext();
        Metadata metadata = new Metadata();
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            PackagePart document = opcPackage.createPart(
                    PackagingURIHelper.createPartName("/word/document.xml"),
                    XWPFRelation.DOCUMENT.getContentType());
            PackagePart footnotes = createPart(opcPackage, "/word/footnotes.xml",
                    partXml("footnotes", "footnote", 513));
            PackagePart comments = createPart(opcPackage, "/word/comments.xml",
                    partXml("comments", "comment", 512));
            document.addRelationship(footnotes.getPartName(), TargetMode.INTERNAL,
                    XWPFRelation.FOOTNOTE.getRelation());
            document.addRelationship(comments.getPartName(), TargetMode.INTERNAL,
                    "http://schemas.openxmlformats.org/officeDocument/2006/"
                            + "relationships/comments");

            SXWPFWordExtractorDecorator decorator =
                    new SXWPFWordExtractorDecorator(metadata, context,
                            new XWPFEventBasedWordExtractor(opcPackage));
            Method collectInlineParts = SXWPFWordExtractorDecorator.class
                    .getDeclaredMethod("collectInlineParts", PackagePart.class);
            collectInlineParts.setAccessible(true);
            collectInlineParts.invoke(decorator, document);
            collectInlineParts.invoke(decorator, document);
        }

        assertEquals("true", metadata.get(TikaCoreProperties.TRUNCATED_METADATA));
        assertEquals(1, metadata.getValues(
                TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length);
        assertNotNull(metadata.get("ExploitClass"));
    }

    @Test
    void testDocumentWideLimitAcrossMainStoryPartsIsSignaled()
            throws Exception {
        ParseContext context = new ParseContext();
        Metadata metadata = new Metadata();
        try (ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
             OPCPackage opcPackage = OPCPackage.create(packageBytes)) {
            PackagePart firstDocument = opcPackage.createPart(
                    PackagingURIHelper.createPartName("/word/document.xml"),
                    XWPFRelation.DOCUMENT.getContentType());
            PackagePart secondDocument = opcPackage.createPart(
                    PackagingURIHelper.createPartName("/word/document2.xml"),
                    XWPFRelation.DOCUMENT.getContentType());
            PackagePart footnotes = createPart(opcPackage, "/word/footnotes.xml",
                    partXml("footnotes", "footnote", 600));
            PackagePart comments = createPart(opcPackage, "/word/comments.xml",
                    partXml("comments", "comment", 425));
            firstDocument.addRelationship(footnotes.getPartName(), TargetMode.INTERNAL,
                    XWPFRelation.FOOTNOTE.getRelation());
            secondDocument.addRelationship(comments.getPartName(), TargetMode.INTERNAL,
                    "http://schemas.openxmlformats.org/officeDocument/2006/"
                            + "relationships/comments");

            SXWPFWordExtractorDecorator decorator =
                    new SXWPFWordExtractorDecorator(metadata, context,
                            new XWPFEventBasedWordExtractor(opcPackage));
            Method collectInlineParts = SXWPFWordExtractorDecorator.class
                    .getDeclaredMethod("collectInlineParts", PackagePart.class);
            collectInlineParts.setAccessible(true);
            collectInlineParts.invoke(decorator, firstDocument);
            collectInlineParts.invoke(decorator, secondDocument);
        }

        assertEquals("true", metadata.get(TikaCoreProperties.TRUNCATED_METADATA));
        assertEquals(1, metadata.getValues(
                TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length);
        assertNotNull(metadata.get("ExploitClass"));
    }

    private static PackagePart createPart(
            OPCPackage opcPackage, String name, String xml) throws Exception {
        PackagePart part = opcPackage.createPart(
                PackagingURIHelper.createPartName(name), "application/xml");
        try (java.io.OutputStream stream = part.getOutputStream()) {
            stream.write(xml.getBytes(StandardCharsets.UTF_8));
        }
        return part;
    }

    private static String partXml(String root, String wrapper, int count) {
        StringBuilder xml = new StringBuilder("<w:").append(root)
                .append(" xmlns:w=\"").append(W_NS).append("\">");
        for (int i = 1; i <= count; i++) {
            xml.append("<w:").append(wrapper).append(" w:id=\"")
                    .append(i).append("\"/>");
        }
        return xml.append("</w:").append(root).append(">").toString();
    }

    private static void parse(String xml, OOXMLPartContentCollector collector)
            throws Exception {
        try (InputStream stream = new ByteArrayInputStream(
                xml.getBytes(StandardCharsets.UTF_8))) {
            XMLReaderUtils.parseSAX(stream, collector, new ParseContext());
        }
    }
}
