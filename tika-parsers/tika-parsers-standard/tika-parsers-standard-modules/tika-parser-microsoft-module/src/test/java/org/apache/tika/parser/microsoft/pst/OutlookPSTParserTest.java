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
package org.apache.tika.parser.microsoft.pst;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.util.List;

import com.pff.PSTFile;
import com.pff.PSTFolder;
import com.pff.PSTMessage;
import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.TikaTest;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.MAPI;
import org.apache.tika.metadata.Message;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;

public class OutlookPSTParserTest extends TikaTest {

    private Parser parser = new OutlookPSTParser();

    @Test
    public void testAccept() throws Exception {
        assertTrue((parser.getSupportedTypes(null)
                .contains(MediaType.application("vnd.ms-outlook-pst"))));
    }

    @Test
    public void testLegacyXML() throws Exception {
        String output = getXML("testPST.pst").xml;
        assertTrue(output.contains("<meta name=\"Content-Length\" content=\"2302976\""));
        assertTrue(output.contains("<meta name=\"Content-Type\" content=\"application/vnd.ms-outlook-pst\""));

        assertTrue(output.contains("<body><div class=\"email-folder\"><h1>"));
        assertTrue(output.contains("<div class=\"embedded\" id=\"&lt;530D9CAC.5080901@gmail.com&gt;\">"));
        assertTrue(output.contains(
                "<div class=\"embedded\" id=\"&lt;1393363252.28814.YahooMailNeo@web140906.mail" + ".bf1.yahoo.com&gt;\">"));
        assertTrue(output.contains("Gary Murphy commented on TIKA-1250:"));

        assertTrue(output.contains("<div class=\"email-folder\"><h1>Racine (pour la recherche)</h1>"));

        assertTrue(output.contains("This is a docx attachment."));
    }

    @Test
    public void testExtendedMetadata() throws Exception {
        List<Metadata> metadataList = getRecursiveMetadata("testPST.pst");
        assertEquals(10, metadataList.size());

        Metadata m1 = metadataList.get(1);
        assertEquals("application/x-tika-pst-mail-item", m1.get(TikaCoreProperties.CONTENT_TYPE_PARSER_OVERRIDE));
        assertEquals("application/vnd.ms-outlook", m1.get(TikaCoreProperties.CONTENT_TYPE_MAGIC_DETECTED));
        assertEquals("application/x-tika-pst-mail-item", m1.get(Metadata.CONTENT_TYPE));
        assertEquals("Jörn Kottmann", m1.get(Message.MESSAGE_FROM_NAME));
        assertEquals("Jörn Kottmann", m1.get(TikaCoreProperties.CREATOR));
        assertEquals("Re: Feature Generators", m1.get(TikaCoreProperties.TITLE));
        assertEquals("users@opennlp.apache.org", m1.get(Message.MESSAGE_TO_DISPLAY_NAME));
        assertEquals("", m1.get(Message.MESSAGE_CC_DISPLAY_NAME));
        assertEquals("", m1.get(Message.MESSAGE_BCC_DISPLAY_NAME));
        assertEquals("kottmann@gmail.com", m1.get(Message.MESSAGE_FROM_EMAIL));
        assertEquals("Jörn Kottmann", m1.get(MAPI.FROM_REPRESENTING_NAME));
        assertEquals("kottmann@gmail.com", m1.get(MAPI.FROM_REPRESENTING_EMAIL));
        assertEquals("NOTE", m1.get(MAPI.MESSAGE_CLASS));
        assertEquals("/Début du fichier de données Outlook/Re: Feature Generators.msg",
                m1.get(TikaCoreProperties.INTERNAL_PATH));
        //test that subject is making it into the xhtml
        assertContains("<meta name=\"dc:subject\" content=\"Re: Feature Generators\"", m1.get(TikaCoreProperties.TIKA_CONTENT));

        Metadata m6 = metadataList.get(6);
        assertEquals("Couchbase", m6.get(Message.MESSAGE_FROM_NAME));
        assertEquals("couchbase@couchbase.com", m6.get(Message.MESSAGE_FROM_EMAIL));
        assertEquals("Couchbase", m6.get(MAPI.FROM_REPRESENTING_NAME));
        assertEquals("couchbase@couchbase.com", m6.get(MAPI.FROM_REPRESENTING_EMAIL));
        assertEquals("NOTE", m1.get(MAPI.MESSAGE_CLASS));
        assertNull(m1.get(MAPI.RECIPIENTS_STRING));
        assertContains("2014-02-26", m1.get(MAPI.SUBMISSION_ACCEPTED_AT_TIME));

        //test full EX email
        assertEquals(
                "/o=ExchangeLabs/ou=Exchange Administrative Group (FYDIBOHF23SPDLT)" +
                        "/cn=Recipients/cn=polyspot1.onmicrosoft.com-50609-Hong-Thai.Ng",
                m6.get(Message.MESSAGE_TO_EMAIL));
        assertEquals("Hong-Thai Nguyen", m6.get(Message.MESSAGE_TO_DISPLAY_NAME));

        assertEquals("Couchbase", m6.get(Message.MESSAGE_FROM_NAME));
        assertEquals("couchbase@couchbase.com", m6.get(Message.MESSAGE_FROM_EMAIL));

        Metadata m7 = metadataList.get(7);
        assertEquals("/ First email.msg/First email.msg/attachment.docx",
                m7.get(TikaCoreProperties.EMBEDDED_RESOURCE_PATH));
        assertEquals("/7/8/9", m7.get(TikaCoreProperties.EMBEDDED_ID_PATH));
    }

    @Test
    public void testOverrideDetector() throws Exception {
        List<Metadata> metadataList = getRecursiveMetadata("testPST_variousBodyTypes.pst");
        assertEquals(5,
                metadataList.size());//before the fix that prevents the RFC parser, this was 6
        for (Metadata metadata : metadataList) {
            for (String v : metadata.getValues(TikaCoreProperties.TIKA_PARSED_BY)) {
                if (v.contains("RFC822Parser")) {
                    fail("RFCParser should never be called");
                }
            }
        }
        //TODO: figure out why the bold markup isn't coming through if we do extract then parse
        // the bodyhtml
    }

    @Test
    public void testAttachmentSecurityAndSaxDenialsPropagate()
            throws Exception {
        SecurityException securityDenial =
                new SecurityException(
                        "simulated PST attachment security denial");
        String blockedOutput = "blocked PST attachment output";
        SAXException saxDenial =
                new SAXException("simulated PST attachment output denial");
        assertAll(
                () -> assertAttachmentDenialPropagates(
                        securityDenial, new DefaultHandler()),
                () -> assertAttachmentDenialPropagates(
                        saxDenial,
                        new TextRejectingHandler(
                                blockedOutput, saxDenial)));
    }

    private void assertAttachmentDenialPropagates(
            Throwable denial, ContentHandler handler) throws Exception {
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class,
                new AttachmentRejectingExtractor(denial));

        Throwable thrown;
        try (TikaInputStream pstStream =
                getResourceAsStream("/test-documents/testPST.pst")) {
            PSTFile pstFile = new PSTFile(pstStream.getFile());
            try {
                PSTMessage message =
                        findMessageWithAttachment(pstFile.getRootFolder());
                assertNotNull(message, "fixture must contain an attachment");
                Metadata metadata = new Metadata();
                long size = OutlookPSTParser.estimateSize(message);
                try (TikaInputStream messageStream =
                        TikaInputStream.getFromContainer(
                                message, size, metadata)) {
                    thrown = assertThrows(denial.getClass(),
                            () -> new PSTMailItemParser().parse(
                                    messageStream, handler, metadata,
                                    context));
                }
            } finally {
                pstFile.close();
            }
        }

        assertSame(denial, thrown);
    }

    private static PSTMessage findMessageWithAttachment(PSTFolder folder)
            throws Exception {
        PSTMessage message = (PSTMessage) folder.getNextChild();
        while (message != null) {
            if (message.getNumberOfAttachments() > 0) {
                return message;
            }
            message = (PSTMessage) folder.getNextChild();
        }
        for (PSTFolder child : folder.getSubFolders()) {
            message = findMessageWithAttachment(child);
            if (message != null) {
                return message;
            }
        }
        return null;
    }

    private static final class AttachmentRejectingExtractor
            implements EmbeddedDocumentExtractor {

        private static final String BLOCKED_OUTPUT =
                "blocked PST attachment output";
        private final Throwable denial;

        private AttachmentRejectingExtractor(Throwable denial) {
            this.denial = denial;
        }

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return true;
        }

        @Override
        public void parseEmbedded(
                TikaInputStream stream, ContentHandler handler,
                Metadata metadata, ParseContext context,
                boolean outputHtml) throws IOException, SAXException {
            if (denial instanceof SecurityException securityException) {
                throw securityException;
            }
            char[] output = BLOCKED_OUTPUT.toCharArray();
            handler.characters(output, 0, output.length);
        }
    }

    private static final class TextRejectingHandler
            extends DefaultHandler {

        private final String rejectedText;
        private final SAXException denial;

        private TextRejectingHandler(
                String rejectedText, SAXException denial) {
            this.rejectedText = rejectedText;
            this.denial = denial;
        }

        @Override
        public void characters(char[] ch, int start, int length)
                throws SAXException {
            if (new String(ch, start, length).contains(rejectedText)) {
                throw denial;
            }
        }
    }
}
