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

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

import com.pff.PSTAttachment;
import com.pff.PSTException;
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
        //TIKA-4806: CREATED/MODIFIED reflect the client submit time, not the storage-level
        //PidTagLastModificationTime (2014-02-26T12:37:43Z), which is ~5 hours later here.
        assertEquals("2014-02-26T07:50:04Z", m1.get(TikaCoreProperties.CREATED));
        assertEquals("2014-02-26T07:50:04Z", m1.get(TikaCoreProperties.MODIFIED));
        assertEquals("2014-02-26T07:50:04Z", m1.get(MAPI.CLIENT_SUBMIT_TIME));
        assertEquals("2014-02-26T07:51:02Z", m1.get(MAPI.CREATION_TIME));
        assertEquals("2014-02-26T12:37:43Z", m1.get(MAPI.LAST_MODIFICATION_TIME));
        assertEquals("2014-02-26T07:51:02Z", m1.get(MAPI.MESSAGE_DELIVERY_TIME));
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
        assertContains("2014-02-26", m1.get(MAPI.CLIENT_SUBMIT_TIME));

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

    @Test
    public void testAttachmentWrappedAndSwallowedSaxDenialsPropagate()
            throws Exception {
        SAXException causeDenial =
                new SAXException(
                        "simulated cause-wrapped PST attachment output denial");
        TextRejectingHandler causeHandler =
                new TextRejectingHandler(
                        AttachmentRejectingExtractor.BLOCKED_OUTPUT,
                        causeDenial);

        SAXException suppressedDenial =
                new SAXException(
                        "simulated suppressed PST attachment output denial");
        TextRejectingHandler suppressedHandler =
                new TextRejectingHandler(
                        AttachmentRejectingExtractor.BLOCKED_OUTPUT,
                        suppressedDenial);

        SAXException swallowedDenial =
                new SAXException(
                        "simulated swallowed PST attachment output denial");
        TextRejectingHandler swallowedHandler =
                new TextRejectingHandler(
                        AttachmentRejectingExtractor.BLOCKED_OUTPUT,
                        swallowedDenial);

        assertAll(
                () -> {
                    assertAttachmentDenialPropagates(
                            causeDenial, causeHandler,
                            FailureMode.WRAP_CHECKED_SAX_CAUSE);
                    assertEquals(0, causeHandler.callbacksAfterDenial);
                },
                () -> {
                    assertAttachmentDenialPropagates(
                            suppressedDenial, suppressedHandler,
                            FailureMode.WRAP_CHECKED_SAX_SUPPRESSED);
                    assertEquals(0, suppressedHandler.callbacksAfterDenial);
                },
                () -> {
                    assertAttachmentDenialPropagates(
                            swallowedDenial, swallowedHandler,
                            FailureMode.SWALLOW_CHECKED_SAX);
                    assertEquals(0, swallowedHandler.callbacksAfterDenial);
                });
    }

    @Test
    public void testAttachmentUncheckedOutputDenialsPropagate()
            throws Exception {
        RuntimeException directDenial =
                new RuntimeException(
                        "simulated direct PST attachment output denial");
        FailStopUncheckedTextRejectingHandler directHandler =
                new FailStopUncheckedTextRejectingHandler(
                        AttachmentRejectingExtractor.BLOCKED_OUTPUT,
                        directDenial);

        RuntimeException swallowedDenial =
                new RuntimeException(
                        "simulated swallowed PST attachment output denial");
        FailStopUncheckedTextRejectingHandler swallowedHandler =
                new FailStopUncheckedTextRejectingHandler(
                        AttachmentRejectingExtractor.BLOCKED_OUTPUT,
                        swallowedDenial);

        RuntimeException wrappedDenial =
                new RuntimeException(
                        "simulated wrapped PST attachment output denial");
        FailStopUncheckedTextRejectingHandler wrappedHandler =
                new FailStopUncheckedTextRejectingHandler(
                        AttachmentRejectingExtractor.BLOCKED_OUTPUT,
                        wrappedDenial);

        assertAll(
                () -> {
                    assertAttachmentDenialPropagates(
                            directDenial, directHandler, FailureMode.DIRECT);
                    assertEquals(0, directHandler.callbacksAfterDenial);
                },
                () -> {
                    assertAttachmentDenialPropagates(
                            swallowedDenial, swallowedHandler, FailureMode.SWALLOW);
                    assertEquals(0, swallowedHandler.callbacksAfterDenial);
                },
                () -> {
                    assertAttachmentDenialPropagates(
                            wrappedDenial, wrappedHandler,
                            FailureMode.WRAP_IO_CAUSE);
                    assertEquals(0, wrappedHandler.callbacksAfterDenial);
                });
    }

    @Test
    public void testAttachmentSaxWrappedUncheckedOutputDenialsPropagate()
            throws Exception {
        RuntimeException runtimeDenial =
                new IllegalStateException(
                        "simulated SAX-cause-wrapped PST output denial");
        FailStopUncheckedTextRejectingHandler runtimeHandler =
                new FailStopUncheckedTextRejectingHandler(
                        AttachmentRejectingExtractor.BLOCKED_OUTPUT,
                        runtimeDenial);

        AssertionError errorDenial =
                new AssertionError(
                        "simulated SAX-suppressed PST output denial");
        FailStopUncheckedTextRejectingHandler errorHandler =
                new FailStopUncheckedTextRejectingHandler(
                        AttachmentRejectingExtractor.BLOCKED_OUTPUT,
                        errorDenial);

        assertAll(
                () -> {
                    assertAttachmentDenialPropagates(
                            runtimeDenial, runtimeHandler,
                            FailureMode.WRAP_SAX_CAUSE);
                    assertEquals(0, runtimeHandler.callbacksAfterDenial);
                },
                () -> {
                    assertAttachmentDenialPropagates(
                            errorDenial, errorHandler,
                            FailureMode.WRAP_SAX_SUPPRESSED);
                    assertEquals(0, errorHandler.callbacksAfterDenial);
                });
    }

    @Test
    public void testAttachmentErrorWrappedUncheckedOutputDenialsPropagate()
            throws Exception {
        RuntimeException causeDenial =
                new IllegalStateException(
                        "simulated error-cause-wrapped PST output denial");
        FailStopUncheckedTextRejectingHandler causeHandler =
                new FailStopUncheckedTextRejectingHandler(
                        AttachmentRejectingExtractor.BLOCKED_OUTPUT,
                        causeDenial);

        AssertionError suppressedDenial =
                new AssertionError(
                        "simulated error-suppressed PST output denial");
        FailStopUncheckedTextRejectingHandler suppressedHandler =
                new FailStopUncheckedTextRejectingHandler(
                        AttachmentRejectingExtractor.BLOCKED_OUTPUT,
                        suppressedDenial);

        assertAll(
                () -> {
                    assertAttachmentDenialPropagates(
                            causeDenial, causeHandler,
                            FailureMode.WRAP_ERROR_CAUSE);
                    assertEquals(0, causeHandler.callbacksAfterDenial);
                },
                () -> {
                    assertAttachmentDenialPropagates(
                            suppressedDenial, suppressedHandler,
                            FailureMode.WRAP_ERROR_SUPPRESSED);
                    assertEquals(
                            0, suppressedHandler.callbacksAfterDenial);
                });
    }

    @Test
    public void testBinaryAttachmentSwallowedUncheckedDenialsStopCallbacks()
            throws Exception {
        RuntimeException runtimeDenial =
                new IllegalStateException(
                        "simulated swallowed binary attachment runtime denial");
        FailStopUncheckedTextRejectingHandler runtimeHandler =
                new FailStopUncheckedTextRejectingHandler(
                        AttachmentRejectingExtractor.BLOCKED_OUTPUT,
                        runtimeDenial);

        AssertionError errorDenial =
                new AssertionError(
                        "simulated swallowed binary attachment error denial");
        FailStopUncheckedTextRejectingHandler errorHandler =
                new FailStopUncheckedTextRejectingHandler(
                        AttachmentRejectingExtractor.BLOCKED_OUTPUT,
                        errorDenial);

        assertAll(
                () -> {
                    assertBinaryAttachmentDenialPropagates(
                            runtimeDenial, runtimeHandler,
                            FailureMode.SWALLOW);
                    assertEquals(0, runtimeHandler.callbacksAfterDenial);
                },
                () -> {
                    assertBinaryAttachmentDenialPropagates(
                            errorDenial, errorHandler,
                            FailureMode.SWALLOW);
                    assertEquals(0, errorHandler.callbacksAfterDenial);
                });
    }

    @Test
    public void testAttachmentWrappedSecurityDenialsPropagate()
            throws Exception {
        SecurityException parserSecurityDenial =
                new SecurityException(
                        "simulated wrapped parser-origin PST security denial");

        RuntimeException outputDenial =
                new IllegalStateException(
                        "simulated security-wrapped PST output denial");
        FailStopUncheckedTextRejectingHandler outputHandler =
                new FailStopUncheckedTextRejectingHandler(
                        AttachmentRejectingExtractor.BLOCKED_OUTPUT,
                        outputDenial);

        assertAll(
                () -> assertAttachmentDenialPropagates(
                        parserSecurityDenial, new DefaultHandler(),
                        FailureMode.WRAP_PARSER_SECURITY_IO),
                () -> {
                    assertAttachmentDenialPropagates(
                            outputDenial, outputHandler,
                            FailureMode.WRAP_OUTPUT_SECURITY);
                    assertEquals(0, outputHandler.callbacksAfterDenial);
                });
    }

    @Test
    public void testAttachmentOutputSaxSurvivesCleanupFailure()
            throws Exception {
        SAXException outputDenial =
                new SAXException(
                        "simulated PST attachment output SAX denial");
        IOException cleanupFailure =
                new IOException(
                        "simulated PST attachment cleanup failure");
        FailingCloseable cleanupResource =
                new FailingCloseable(cleanupFailure);
        TextRejectingHandler handler =
                new TextRejectingHandler(
                        AttachmentRejectingExtractor.BLOCKED_OUTPUT,
                        outputDenial);

        SAXException thrown = assertThrows(
                SAXException.class,
                () -> parseMessageWithBinaryAttachment(
                        handler,
                        new AttachmentRejectingExtractor(
                                outputDenial, FailureMode.DIRECT,
                                cleanupResource)));

        assertSame(outputDenial, thrown);
        assertTrue(cleanupResource.closed);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(cleanupFailure, thrown.getSuppressed()[0]);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    public void testSwallowedUncheckedOutputRetainsCleanupFailure()
            throws Exception {
        RuntimeException outputDenial =
                new RuntimeException(
                        "simulated swallowed PST attachment output denial");
        IOException cleanupFailure =
                new IOException(
                        "simulated PST attachment cleanup failure");
        FailingCloseable cleanupResource =
                new FailingCloseable(cleanupFailure);
        FailStopUncheckedTextRejectingHandler handler =
                new FailStopUncheckedTextRejectingHandler(
                        AttachmentRejectingExtractor.BLOCKED_OUTPUT,
                        outputDenial);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> parseMessageWithBinaryAttachment(
                        handler,
                        new AttachmentRejectingExtractor(
                                outputDenial, FailureMode.SWALLOW,
                                cleanupResource)));

        assertSame(outputDenial, thrown);
        assertTrue(cleanupResource.closed);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(cleanupFailure, thrown.getSuppressed()[0]);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    public void testAttachmentParserSecuritySurvivesCleanupFailure()
            throws Exception {
        SecurityException parserDenial =
                new SecurityException(
                        "simulated PST attachment parser security denial");
        IOException cleanupFailure =
                new IOException(
                        "simulated PST attachment cleanup failure");
        FailingCloseable cleanupResource =
                new FailingCloseable(cleanupFailure);

        SecurityException thrown = assertThrows(
                SecurityException.class,
                () -> parseMessageWithBinaryAttachment(
                        new DefaultHandler(),
                        new AttachmentRejectingExtractor(
                                parserDenial, FailureMode.DIRECT,
                                cleanupResource)));

        assertSame(parserDenial, thrown);
        assertTrue(cleanupResource.closed);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(cleanupFailure, thrown.getSuppressed()[0]);
    }

    @Test
    public void testAttachmentFatalCleanupErrorSupersedesParserFailure()
            throws Exception {
        SecurityException parserDenial =
                new SecurityException(
                        "simulated PST attachment parser security denial");
        AssertionError cleanupFailure =
                new AssertionError(
                        "simulated fatal PST attachment cleanup failure");
        FailingCloseable cleanupResource =
                new FailingCloseable(cleanupFailure);

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> parseMessageWithBinaryAttachment(
                        new DefaultHandler(),
                        new AttachmentRejectingExtractor(
                                parserDenial, FailureMode.DIRECT,
                                cleanupResource)));

        assertSame(cleanupFailure, thrown);
        assertTrue(cleanupResource.closed);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(parserDenial, thrown.getSuppressed()[0]);
    }

    @Test
    public void testEmbeddedAttachmentFatalCleanupErrorSupersedesSaxOutputDenial()
            throws Exception {
        SAXException outputDenial =
                new SAXException(
                        "simulated embedded PST attachment SAX denial");
        AssertionError cleanupFailure =
                new AssertionError(
                        "simulated embedded PST fatal cleanup error");
        TextRejectingHandler handler =
                new TextRejectingHandler(
                        AttachmentRejectingExtractor.BLOCKED_OUTPUT,
                        outputDenial);
        AttachmentRejectingExtractor extractor =
                new AttachmentRejectingExtractor(
                        outputDenial, FailureMode.DIRECT,
                        new FailingCloseable(cleanupFailure));

        assertFatalCleanupSupersedesOutputDenial(
                AttachmentFixture.EMBEDDED, handler, extractor,
                outputDenial, cleanupFailure);

        assertTrue(extractor.outputHtml);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    public void testBinaryAttachmentFatalCleanupErrorSupersedesUncheckedOutputDenial()
            throws Exception {
        RuntimeException outputDenial =
                new IllegalStateException(
                        "simulated binary PST attachment unchecked denial");
        AssertionError cleanupFailure =
                new AssertionError(
                        "simulated binary PST fatal cleanup error");
        FailStopUncheckedTextRejectingHandler handler =
                new FailStopUncheckedTextRejectingHandler(
                        AttachmentRejectingExtractor.BLOCKED_OUTPUT,
                        outputDenial);
        AttachmentRejectingExtractor extractor =
                new AttachmentRejectingExtractor(
                        outputDenial, FailureMode.DIRECT,
                        new FailingCloseable(cleanupFailure));

        assertFatalCleanupSupersedesOutputDenial(
                AttachmentFixture.BINARY, handler, extractor,
                outputDenial, cleanupFailure);

        assertTrue(!extractor.outputHtml);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    public void testEmbeddedAttachmentFatalCleanupSecuritySupersedesUncheckedOutputDenial()
            throws Exception {
        RuntimeException outputDenial =
                new IllegalStateException(
                        "simulated embedded PST attachment unchecked denial");
        SecurityException cleanupFailure =
                new SecurityException(
                        "simulated embedded PST fatal cleanup security denial");
        FailStopUncheckedTextRejectingHandler handler =
                new FailStopUncheckedTextRejectingHandler(
                        AttachmentRejectingExtractor.BLOCKED_OUTPUT,
                        outputDenial);
        AttachmentRejectingExtractor extractor =
                new AttachmentRejectingExtractor(
                        outputDenial, FailureMode.DIRECT,
                        new FailingCloseable(cleanupFailure));

        assertFatalCleanupSupersedesOutputDenial(
                AttachmentFixture.EMBEDDED, handler, extractor,
                outputDenial, cleanupFailure);

        assertTrue(extractor.outputHtml);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    public void testBinaryAttachmentFatalCleanupSecuritySupersedesSaxOutputDenial()
            throws Exception {
        SAXException outputDenial =
                new SAXException(
                        "simulated binary PST attachment SAX denial");
        SecurityException cleanupFailure =
                new SecurityException(
                        "simulated binary PST fatal cleanup security denial");
        TextRejectingHandler handler =
                new TextRejectingHandler(
                        AttachmentRejectingExtractor.BLOCKED_OUTPUT,
                        outputDenial);
        AttachmentRejectingExtractor extractor =
                new AttachmentRejectingExtractor(
                        outputDenial, FailureMode.DIRECT,
                        new FailingCloseable(cleanupFailure));

        assertFatalCleanupSupersedesOutputDenial(
                AttachmentFixture.BINARY, handler, extractor,
                outputDenial, cleanupFailure);

        assertTrue(!extractor.outputHtml);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    public void testEmbeddedAttachmentFatalCleanupErrorSupersedesErrorCauseWrappedSaxDenial()
            throws Exception {
        SAXException outputDenial =
                new SAXException(
                        "simulated embedded PST Error-cause-wrapped SAX denial");
        AssertionError cleanupFailure =
                new AssertionError(
                        "simulated embedded PST fatal cleanup error");
        TextRejectingHandler handler =
                new TextRejectingHandler(
                        AttachmentRejectingExtractor.BLOCKED_OUTPUT,
                        outputDenial);
        AttachmentRejectingExtractor extractor =
                new AttachmentRejectingExtractor(
                        outputDenial,
                        FailureMode.WRAP_ERROR_UNWRAPPED_SAX_CAUSE,
                        new FailingCloseable(cleanupFailure));

        assertFatalCleanupSupersedesOutputDenial(
                AttachmentFixture.EMBEDDED, handler, extractor,
                outputDenial, cleanupFailure);

        assertTrue(extractor.outputHtml);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    public void testBinaryAttachmentFatalCleanupSecuritySupersedesErrorSuppressedSaxDenial()
            throws Exception {
        SAXException outputDenial =
                new SAXException(
                        "simulated binary PST Error-suppressed SAX denial");
        SecurityException cleanupFailure =
                new SecurityException(
                        "simulated binary PST fatal cleanup security denial");
        TextRejectingHandler handler =
                new TextRejectingHandler(
                        AttachmentRejectingExtractor.BLOCKED_OUTPUT,
                        outputDenial);
        AttachmentRejectingExtractor extractor =
                new AttachmentRejectingExtractor(
                        outputDenial,
                        FailureMode.WRAP_ERROR_UNWRAPPED_SAX_SUPPRESSED,
                        new FailingCloseable(cleanupFailure));

        assertFatalCleanupSupersedesOutputDenial(
                AttachmentFixture.BINARY, handler, extractor,
                outputDenial, cleanupFailure);

        assertTrue(!extractor.outputHtml);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    public void testEmbeddedAttachmentFatalCleanupSecuritySupersedesSecurityWrappedOutputDenial()
            throws Exception {
        RuntimeException outputDenial =
                new IllegalStateException(
                        "simulated embedded PST Security-wrapped output denial");
        SecurityException cleanupFailure =
                new SecurityException(
                        "simulated embedded PST fatal cleanup security denial");
        FailStopUncheckedTextRejectingHandler handler =
                new FailStopUncheckedTextRejectingHandler(
                        AttachmentRejectingExtractor.BLOCKED_OUTPUT,
                        outputDenial);
        AttachmentRejectingExtractor extractor =
                new AttachmentRejectingExtractor(
                        outputDenial, FailureMode.WRAP_OUTPUT_SECURITY,
                        new FailingCloseable(cleanupFailure));

        assertFatalCleanupSupersedesOutputDenial(
                AttachmentFixture.EMBEDDED, handler, extractor,
                outputDenial, cleanupFailure);

        assertTrue(extractor.outputHtml);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    public void testAttachmentErrorWrappedUnwrappedSaxDenialsPropagate()
            throws Exception {
        SAXException causeDenial =
                new SAXException(
                        "simulated unwrapped SAX denial in Error cause");
        TextRejectingHandler causeHandler =
                new TextRejectingHandler(
                        AttachmentRejectingExtractor.BLOCKED_OUTPUT,
                        causeDenial);
        SAXException suppressedDenial =
                new SAXException(
                        "simulated unwrapped SAX denial suppressed on Error");
        TextRejectingHandler suppressedHandler =
                new TextRejectingHandler(
                        AttachmentRejectingExtractor.BLOCKED_OUTPUT,
                        suppressedDenial);

        assertAll(
                () -> {
                    assertAttachmentDenialPropagates(
                            causeDenial, causeHandler,
                            FailureMode.WRAP_ERROR_UNWRAPPED_SAX_CAUSE);
                    assertEquals(0, causeHandler.callbacksAfterDenial);
                },
                () -> {
                    assertAttachmentDenialPropagates(
                            suppressedDenial, suppressedHandler,
                            FailureMode.WRAP_ERROR_UNWRAPPED_SAX_SUPPRESSED);
                    assertEquals(
                            0, suppressedHandler.callbacksAfterDenial);
                });
    }

    @Test
    public void testExactRecordedSaxOutranksCompetingTaggedBranch()
            throws Exception {
        SAXException firstDenial =
                new SAXException("first recorded PST SAX output denial");
        SAXException laterDenial =
                new SAXException("later competing PST SAX output denial");
        ContentHandler rejectingHandler = new DefaultHandler() {
            @Override
            public void characters(char[] ch, int start, int length)
                    throws SAXException {
                throw firstDenial;
            }
        };

        org.apache.tika.sax.TaggedContentHandler taggedOutput =
                new org.apache.tika.sax.TaggedContentHandler(
                        rejectingHandler);

        SAXException firstTagged = assertThrows(
                SAXException.class,
                () -> taggedOutput.characters(new char[]{'x'}, 0, 1));
        Object outputTag =
                ((org.apache.tika.sax.TaggedSAXException) firstTagged).getTag();
        SAXException competingTagged =
                new org.apache.tika.sax.TaggedSAXException(
                        laterDenial, outputTag);
        Error parserFailure =
                new Error("competing PST SAX failure branches");
        RuntimeException cycle =
                new RuntimeException("competing PST failure graph cycle");
        parserFailure.addSuppressed(competingTagged);
        parserFailure.addSuppressed(firstDenial);
        parserFailure.addSuppressed(cycle);
        cycle.addSuppressed(parserFailure);

        SAXException found =
                PSTMailItemParser.findOutputSaxFailure(
                        taggedOutput, parserFailure);

        assertSame(firstDenial, found);
    }

    @Test
    public void testUnrelatedFatalErrorRemainsAuthoritativeAfterSwallowedSaxDenial()
            throws Exception {
        SAXException outputDenial =
                new SAXException(
                        "simulated swallowed PST attachment SAX denial");
        AssertionError parserFailure =
                new AssertionError(
                        "simulated unrelated fatal PST parser error");
        TextRejectingHandler handler =
                new TextRejectingHandler(
                        SwallowedSaxThenErrorExtractor.BLOCKED_OUTPUT,
                        outputDenial);

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> parseMessageWithBinaryAttachment(
                        handler,
                        new SwallowedSaxThenErrorExtractor(
                                parserFailure)));

        assertSame(parserFailure, thrown);
        assertEquals(0, handler.callbacksAfterDenial);
    }

    @Test
    public void testAttachmentParserSaxRemainsRecoverable()
            throws Exception {
        SAXException parserFailure =
                new SAXException(
                        "simulated recoverable PST attachment parser failure");

        Metadata metadata = parseMessage(
                new DefaultHandler(),
                new AttachmentRejectingExtractor(
                        parserFailure, FailureMode.DIRECT_PARSER_SAX));

        assertNotNull(
                metadata.get(
                        TikaCoreProperties.TIKA_META_EXCEPTION_EMBEDDED_STREAM));
    }

    private void assertFatalCleanupSupersedesOutputDenial(
            AttachmentFixture fixture, ContentHandler handler,
            AttachmentRejectingExtractor extractor,
            Throwable outputDenial, Throwable cleanupFailure) {
        Throwable thrown = assertThrows(
                Throwable.class,
                () -> parseMessage(
                        handler, extractor, fixture));

        assertSame(cleanupFailure, thrown);
        assertTrue(containsThrowableByIdentity(
                cleanupFailure, outputDenial));
        assertTrue(!containsThrowableByIdentity(
                outputDenial, cleanupFailure));
        if (extractor.wrapperFailure != null) {
            assertTrue(containsThrowableByIdentity(
                    cleanupFailure, extractor.wrapperFailure));
            assertTrue(containsThrowableByIdentity(
                    extractor.wrapperFailure, outputDenial));
            assertTrue(!containsThrowableByIdentity(
                    extractor.wrapperFailure, cleanupFailure));
        }
    }

    private void assertAttachmentDenialPropagates(
            Throwable denial, ContentHandler handler) throws Exception {
        assertAttachmentDenialPropagates(
                denial, handler, FailureMode.DIRECT);
    }

    private void assertAttachmentDenialPropagates(
            Throwable denial, ContentHandler handler,
            FailureMode failureMode) throws Exception {
        Throwable thrown = assertThrows(
                denial.getClass(),
                () -> parseMessage(
                        handler,
                        new AttachmentRejectingExtractor(
                                denial, failureMode)));
        assertSame(denial, thrown);
    }

    private void assertBinaryAttachmentDenialPropagates(
            Throwable denial, ContentHandler handler,
            FailureMode failureMode) throws Exception {
        Throwable thrown = assertThrows(
                denial.getClass(),
                () -> parseMessageWithBinaryAttachment(
                        handler,
                        new AttachmentRejectingExtractor(
                                denial, failureMode)));
        assertSame(denial, thrown);
    }

    private Metadata parseMessage(
            ContentHandler handler,
            EmbeddedDocumentExtractor embeddedExtractor) throws Exception {
        return parseMessage(
                handler, embeddedExtractor, AttachmentFixture.ANY);
    }

    private Metadata parseMessageWithBinaryAttachment(
            ContentHandler handler,
            EmbeddedDocumentExtractor embeddedExtractor) throws Exception {
        return parseMessage(
                handler, embeddedExtractor, AttachmentFixture.BINARY);
    }

    private Metadata parseMessage(
            ContentHandler handler,
            EmbeddedDocumentExtractor embeddedExtractor,
            AttachmentFixture fixture) throws Exception {
        ParseContext context = new ParseContext();
        context.set(
                EmbeddedDocumentExtractor.class, embeddedExtractor);
        try (TikaInputStream pstStream =
                getResourceAsStream("/test-documents/testPST.pst")) {
            PSTFile pstFile = new PSTFile(pstStream.getFile());
            try {
                PSTMessage message =
                        switch (fixture) {
                            case ANY -> findMessageWithAttachment(
                                    pstFile.getRootFolder());
                            case BINARY -> findMessageWithBinaryAttachment(
                                    pstFile.getRootFolder());
                            case EMBEDDED -> findMessageWithEmbeddedAttachment(
                                    pstFile.getRootFolder());
                        };
                assertNotNull(message, "fixture must contain an attachment");
                Metadata metadata = new Metadata();
                long size = OutlookPSTParser.estimateSize(message);
                try (TikaInputStream messageStream =
                        TikaInputStream.getFromContainer(
                                message, size, metadata)) {
                    new PSTMailItemParser().parse(
                            messageStream, handler, metadata, context);
                }
                return metadata;
            } finally {
                pstFile.close();
            }
        }
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

    private static PSTMessage findMessageWithEmbeddedAttachment(
            PSTFolder folder) throws Exception {
        PSTMessage message = (PSTMessage) folder.getNextChild();
        while (message != null) {
            for (int i = 0; i < message.getNumberOfAttachments(); i++) {
                if (message
                                .getAttachment(i)
                                .getEmbeddedPSTMessage()
                        != null) {
                    return message;
                }
            }
            message = (PSTMessage) folder.getNextChild();
        }
        try {
            for (PSTFolder child : folder.getSubFolders()) {
                message = findMessageWithEmbeddedAttachment(child);
                if (message != null) {
                    return message;
                }
            }
        } catch (PSTException e) {
            // Some synthetic search folders in this fixture have dangling
            // descriptors. They do not contain an embedded attachment.
        }
        return null;
    }

    private static PSTMessage findMessageWithBinaryAttachment(
            PSTFolder folder) throws Exception {
        PSTMessage message = (PSTMessage) folder.getNextChild();
        while (message != null) {
            PSTMessage match =
                    findNestedMessageWithBinaryAttachment(message);
            if (match != null) {
                return match;
            }
            message = (PSTMessage) folder.getNextChild();
        }
        try {
            for (PSTFolder child : folder.getSubFolders()) {
                message = findMessageWithBinaryAttachment(child);
                if (message != null) {
                    return message;
                }
            }
        } catch (PSTException e) {
            // Some synthetic search folders in this fixture have dangling
            // descriptors. They do not contain the binary attachment.
        }
        return null;
    }

    private static PSTMessage findNestedMessageWithBinaryAttachment(
            PSTMessage message) throws Exception {
        for (int i = 0; i < message.getNumberOfAttachments(); i++) {
            PSTAttachment attachment = message.getAttachment(i);
            PSTMessage attachedMessage = attachment.getEmbeddedPSTMessage();
            if (attachedMessage == null) {
                return message;
            }
            PSTMessage match =
                    findNestedMessageWithBinaryAttachment(attachedMessage);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private static final class AttachmentRejectingExtractor
            implements EmbeddedDocumentExtractor {

        static final String BLOCKED_OUTPUT =
                "blocked PST attachment output";
        private final Throwable denial;
        private final FailureMode failureMode;
        private final Closeable cleanupResource;
        private boolean outputHtml;
        private Throwable wrapperFailure;

        private AttachmentRejectingExtractor(Throwable denial) {
            this(denial, FailureMode.DIRECT);
        }

        private AttachmentRejectingExtractor(
                Throwable denial, FailureMode failureMode) {
            this(denial, failureMode, null);
        }

        private AttachmentRejectingExtractor(
                Throwable denial, FailureMode failureMode,
                Closeable cleanupResource) {
            this.denial = denial;
            this.failureMode = failureMode;
            this.cleanupResource = cleanupResource;
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
            this.outputHtml = outputHtml;
            if (cleanupResource != null) {
                stream.addCloseableResource(cleanupResource);
            }
            if (denial instanceof SecurityException securityException) {
                if (failureMode == FailureMode.WRAP_PARSER_SECURITY_IO) {
                    throw new IOException(
                            "wrapped parser-origin PST security denial",
                            securityException);
                }
                throw securityException;
            }
            if (denial instanceof SAXException saxException
                    && failureMode == FailureMode.DIRECT_PARSER_SAX) {
                throw saxException;
            }
            char[] output = BLOCKED_OUTPUT.toCharArray();
            try {
                handler.characters(output, 0, output.length);
            } catch (SAXException outputFailure) {
                switch (failureMode) {
                    case SWALLOW_CHECKED_SAX:
                        return;
                    case WRAP_CHECKED_SAX_CAUSE:
                        throw new IOException(
                                "cause-wrapped PST attachment SAX denial",
                                outputFailure);
                    case WRAP_CHECKED_SAX_SUPPRESSED:
                        SAXException wrapper =
                                new SAXException(
                                        "suppressed PST attachment SAX denial");
                        wrapper.addSuppressed(outputFailure);
                        throw wrapper;
                    case WRAP_ERROR_UNWRAPPED_SAX_CAUSE:
                        wrapperFailure = new AssertionError(
                                "Error cause-wrapped unwrapped PST SAX denial",
                                unwrapFailure(outputFailure));
                        throw (AssertionError) wrapperFailure;
                    case WRAP_ERROR_UNWRAPPED_SAX_SUPPRESSED:
                        AssertionError errorWrapper =
                                new AssertionError(
                                        "Error-suppressed unwrapped PST SAX denial");
                        errorWrapper.addSuppressed(
                                unwrapFailure(outputFailure));
                        wrapperFailure = errorWrapper;
                        throw errorWrapper;
                    default:
                        throw outputFailure;
                }
            } catch (RuntimeException | Error outputFailure) {
                switch (failureMode) {
                    case SWALLOW:
                        return;
                    case WRAP_IO_CAUSE:
                        throw new IOException(
                                "wrapped PST attachment output denial",
                                outputFailure);
                    case WRAP_SAX_CAUSE:
                        throw new SAXException(
                                "SAX-wrapped PST attachment output denial",
                                (Exception) outputFailure);
                    case WRAP_SAX_SUPPRESSED:
                        SAXException saxWrapper =
                                new SAXException(
                                        "SAX-suppressed PST attachment output denial");
                        saxWrapper.addSuppressed(outputFailure);
                        throw saxWrapper;
                    case WRAP_ERROR_CAUSE:
                        throw new AssertionError(
                                "error-cause-wrapped PST attachment output denial",
                                outputFailure);
                    case WRAP_ERROR_SUPPRESSED:
                        AssertionError errorWrapper =
                                new AssertionError(
                                        "error-suppressed PST attachment output denial");
                        errorWrapper.addSuppressed(outputFailure);
                        throw errorWrapper;
                    case WRAP_OUTPUT_SECURITY:
                        wrapperFailure = new SecurityException(
                                "security-wrapped PST attachment output denial",
                                outputFailure);
                        throw (SecurityException) wrapperFailure;
                    default:
                        throwUnchecked(outputFailure);
                }
            }
        }
    }

    private enum FailureMode {
        DIRECT,
        SWALLOW,
        WRAP_IO_CAUSE,
        WRAP_SAX_CAUSE,
        WRAP_SAX_SUPPRESSED,
        WRAP_ERROR_CAUSE,
        WRAP_ERROR_SUPPRESSED,
        WRAP_OUTPUT_SECURITY,
        WRAP_PARSER_SECURITY_IO,
        DIRECT_PARSER_SAX,
        WRAP_CHECKED_SAX_CAUSE,
        WRAP_CHECKED_SAX_SUPPRESSED,
        SWALLOW_CHECKED_SAX,
        WRAP_ERROR_UNWRAPPED_SAX_CAUSE,
        WRAP_ERROR_UNWRAPPED_SAX_SUPPRESSED
    }

    private enum AttachmentFixture {
        ANY,
        BINARY,
        EMBEDDED
    }

    private static final class SwallowedSaxThenErrorExtractor
            implements EmbeddedDocumentExtractor {

        private static final String BLOCKED_OUTPUT =
                "blocked swallowed-SAX PST output";
        private final AssertionError parserFailure;

        private SwallowedSaxThenErrorExtractor(
                AssertionError parserFailure) {
            this.parserFailure = parserFailure;
        }

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return true;
        }

        @Override
        public void parseEmbedded(
                TikaInputStream stream, ContentHandler handler,
                Metadata metadata, ParseContext context,
                boolean outputHtml) throws SAXException {
            char[] output = BLOCKED_OUTPUT.toCharArray();
            try {
                handler.characters(output, 0, output.length);
            } catch (SAXException expected) {
                throw parserFailure;
            }
            throw new AssertionError(
                    "expected PST output handler to deny content");
        }
    }

    private static final class TextRejectingHandler
            extends DefaultHandler {

        private final String rejectedText;
        private final SAXException denial;
        private boolean denied;
        private int callbacksAfterDenial;

        private TextRejectingHandler(
                String rejectedText, SAXException denial) {
            this.rejectedText = rejectedText;
            this.denial = denial;
        }

        @Override
        public void characters(char[] ch, int start, int length)
                throws SAXException {
            if (denied) {
                callbacksAfterDenial++;
                return;
            }
            if (new String(ch, start, length).contains(rejectedText)) {
                denied = true;
                throw denial;
            }
        }

        @Override
        public void endElement(
                String uri, String localName, String qName) {
            recordCallback();
        }

        @Override
        public void endDocument() {
            recordCallback();
        }

        private void recordCallback() {
            if (denied) {
                callbacksAfterDenial++;
            }
        }
    }

    private static final class FailingCloseable implements Closeable {

        private final Throwable cleanupFailure;
        private boolean closed;

        private FailingCloseable(Throwable cleanupFailure) {
            this.cleanupFailure = cleanupFailure;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            if (cleanupFailure instanceof IOException ioException) {
                throw ioException;
            }
            throwUnchecked(cleanupFailure);
        }
    }

    private static final class FailStopUncheckedTextRejectingHandler
            extends DefaultHandler {

        private final String rejectedText;
        private final Throwable denial;
        private boolean denied;
        private int callbacksAfterDenial;

        private FailStopUncheckedTextRejectingHandler(
                String rejectedText, Throwable denial) {
            this.rejectedText = rejectedText;
            this.denial = denial;
        }

        @Override
        public void startDocument() {
            recordCallback();
        }

        @Override
        public void endDocument() {
            recordCallback();
        }

        @Override
        public void startElement(
                String uri, String localName, String qName,
                org.xml.sax.Attributes attributes) {
            recordCallback();
        }

        @Override
        public void endElement(
                String uri, String localName, String qName) {
            recordCallback();
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (denied) {
                callbacksAfterDenial++;
                return;
            }
            if (new String(ch, start, length).contains(rejectedText)) {
                denied = true;
                throwUnchecked(denial);
            }
        }

        private void recordCallback() {
            if (denied) {
                callbacksAfterDenial++;
            }
        }
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        throw (Error) failure;
    }

    private static Throwable unwrapFailure(Throwable failure) {
        Throwable current = failure;
        java.util.Set<Throwable> seen =
                java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<>());
        while (current.getCause() != null
                && current.getCause() != current
                && seen.add(current)) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean containsThrowableByIdentity(
            Throwable root, Throwable sought) {
        java.util.Set<Throwable> seen =
                java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<>());
        java.util.Deque<Throwable> pending =
                new java.util.ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Throwable current = pending.pop();
            if (current == sought) {
                return true;
            }
            if (!seen.add(current)) {
                continue;
            }
            Throwable cause = current.getCause();
            if (cause != null && cause != current) {
                pending.push(cause);
            }
            for (Throwable suppressed : current.getSuppressed()) {
                if (suppressed != null && suppressed != current) {
                    pending.push(suppressed);
                }
            }
        }
        return false;
    }
}
