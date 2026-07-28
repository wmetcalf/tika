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

import static java.lang.String.valueOf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

import com.pff.PSTAttachment;
import com.pff.PSTException;
import com.pff.PSTMessage;
import com.pff.PSTRecipient;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import org.apache.tika.annotation.TikaComponent;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.EmbeddedDocumentUtil;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.MAPI;
import org.apache.tika.metadata.Message;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.PST;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.html.JSoupParser;
import org.apache.tika.parser.microsoft.OutlookExtractor;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.EmbeddedContentHandler;
import org.apache.tika.sax.TaggedContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;
import org.apache.tika.utils.StringUtils;

@TikaComponent
public class PSTMailItemParser implements Parser {

    //this is a synthetic file type to represent a notional "pst item"
    public static final MediaType PST_MAIL_ITEM = MediaType.application("x-tika-pst-mail-item");
    public static final String PST_MAIL_ITEM_STRING = PST_MAIL_ITEM.toString();
    public static final Set<MediaType> SUPPORTED_ITEMS = Set.of(PST_MAIL_ITEM);

    private static final class AttachmentTaggedContentHandler
            extends TaggedContentHandler {

        private final Set<Throwable> fatalCleanupFailures =
                Collections.newSetFromMap(new IdentityHashMap<>());

        private AttachmentTaggedContentHandler(ContentHandler proxy) {
            super(proxy);
        }

        private void recordFatalCleanupFailure(Throwable failure) {
            fatalCleanupFailures.add(failure);
        }

        private boolean isFatalCleanupFailure(Throwable failure) {
            return fatalCleanupFailures.contains(failure);
        }
    }

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED_ITEMS;
    }

    @Override
    public void parse(TikaInputStream tis, ContentHandler handler, Metadata metadata, ParseContext context) throws IOException, SAXException, TikaException {
        if (tis == null) {
            throw new TikaException("Stream must be a TikaInputStream");
        }
        Object openContainerObj = tis.getOpenContainer();
        if (openContainerObj == null) {
            throw new TikaException("Open container must not be null.");
        }
        if (! (openContainerObj instanceof PSTMessage)) {
            throw new TikaException("Open container must be a PSTMessage");
        }
        PSTMessage pstMsg = (PSTMessage) openContainerObj;
        EmbeddedDocumentExtractor ex = EmbeddedDocumentUtil.getEmbeddedDocumentExtractor(context);
        AttachmentTaggedContentHandler taggedOutput =
                new AttachmentTaggedContentHandler(handler);
        XHTMLContentHandler xhtml =
                new XHTMLContentHandler(taggedOutput, metadata, context);
        try {
            xhtml.startDocument();
            parseMailAndAttachments(
                    pstMsg, xhtml, metadata, context, ex, taggedOutput);
            throwIfOutputFailure(taggedOutput, null);
            xhtml.endDocument();
        } catch (SAXException e) {
            throwIfOutputFailure(taggedOutput, e);
            throwIfSecurityException(e);
            throw e;
        } catch (IOException | TikaException e) {
            throwIfOutputFailure(taggedOutput, e);
            throwIfSecurityException(e);
            throw e;
        } catch (RuntimeException e) {
            throwIfOutputFailure(taggedOutput, e);
            throwIfSecurityException(e);
            throw e;
        } catch (Error e) {
            throwIfOutputFailure(taggedOutput, e);
            throw e;
        }
    }

    private void parseMailAndAttachments(PSTMessage pstMsg, XHTMLContentHandler handler, Metadata metadata, ParseContext context,
                                         EmbeddedDocumentExtractor embeddedExtractor,
                                         AttachmentTaggedContentHandler taggedOutput)
            throws SAXException, IOException, TikaException {
        extractMetadata(pstMsg, metadata);
        AttributesImpl attributes = new AttributesImpl();
        attributes.addAttribute("", "class", "class", "CDATA", "embedded");
        attributes.addAttribute("", "id", "id", "CDATA", pstMsg.getInternetMessageId());
        handler.startElement("div", attributes);

        parseMailItem(pstMsg, handler, metadata, context);
        throwIfOutputFailure(taggedOutput, null);
        parseMailAttachments(
                pstMsg, handler, metadata, context, embeddedExtractor,
                taggedOutput);
        handler.endElement("div");
    }

    private void parseMailItem(PSTMessage pstMail, XHTMLContentHandler xhtml,
                                Metadata metadata, ParseContext context) throws SAXException, IOException, TikaException {

        //try the html first. It preserves logical paragraph markers
        String htmlChunk = pstMail.getBodyHTML();
        if (! StringUtils.isBlank(htmlChunk)) {
            Parser htmlParser = EmbeddedDocumentUtil
                    .tryToFindExistingLeafParser(JSoupParser.class, context);
            if (htmlParser == null) {
                htmlParser = new JSoupParser();
            }
            if (htmlParser instanceof JSoupParser) {
                ((JSoupParser)htmlParser).parseString(htmlChunk,
                        new EmbeddedContentHandler(new BodyContentHandler(xhtml)),
                        metadata, context);
            } else {
                byte[] data = htmlChunk.getBytes(StandardCharsets.UTF_8);
                try (TikaInputStream tis = TikaInputStream.get(data)) {
                    htmlParser.parse(tis, new EmbeddedContentHandler(new BodyContentHandler(xhtml)), Metadata.newInstance(context), context);
                }
            }
            return;
        }
        //if there's no html, back off to straight text -- TODO maybe add RTF parsing?
        //splitting on "\r\n|\n" doesn't work because the new lines in the
        //body are not logical new lines...they are presentation new lines.
        String mailContent = pstMail.getBody();
        xhtml.startElement("p");
        xhtml.characters(mailContent);
        xhtml.endElement("p");
    }

    private void extractMetadata(PSTMessage pstMail, Metadata metadata) {
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, pstMail.getSubject() + ".msg");
        metadata.set(TikaCoreProperties.EMBEDDED_RELATIONSHIP_ID, pstMail.getInternetMessageId());
        metadata.set(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE, TikaCoreProperties.EmbeddedResourceType.ATTACHMENT.name());
        metadata.set(TikaCoreProperties.IDENTIFIER, pstMail.getInternetMessageId());
        metadata.set(TikaCoreProperties.TITLE, pstMail.getSubject());
        metadata.set(TikaCoreProperties.SUBJECT, pstMail.getSubject());
        metadata.set(Metadata.MESSAGE_FROM, pstMail.getSenderName());
        metadata.set(TikaCoreProperties.CREATOR, pstMail.getSenderName());
        metadata.set(TikaCoreProperties.CREATED, pstMail.getCreationTime());
        metadata.set(MAPI.SUBMISSION_ACCEPTED_AT_TIME, pstMail.getClientSubmitTime());
        metadata.set(TikaCoreProperties.MODIFIED, pstMail.getLastModificationTime());
        metadata.set(TikaCoreProperties.COMMENTS, pstMail.getComment());
        metadata.set(PST.DESCRIPTOR_NODE_ID, valueOf(pstMail.getDescriptorNodeId()));
        metadata.set(Message.MESSAGE_FROM_EMAIL, pstMail.getSenderEmailAddress());
        if (! StringUtils.isBlank(pstMail.getRecipientsString()) &&
                ! pstMail.getRecipientsString().equals("No recipients table!")) {
            metadata.set(MAPI.RECIPIENTS_STRING, pstMail.getRecipientsString());
        }
        metadata.set(Message.MESSAGE_TO_DISPLAY_NAME, pstMail.getDisplayTo());
        metadata.set(Message.MESSAGE_CC_DISPLAY_NAME, pstMail.getDisplayCC());
        metadata.set(Message.MESSAGE_BCC_DISPLAY_NAME, pstMail.getDisplayBCC());
        metadata.set(MAPI.IMPORTANCE, pstMail.getImportance());
        metadata.set(MAPI.PRIORTY, pstMail.getPriority());
        metadata.set(MAPI.IS_FLAGGED, pstMail.isFlagged());
        metadata.set(MAPI.MESSAGE_CLASS,
                OutlookExtractor.getNormalizedMessageClass(pstMail.getMessageClass()));
        metadata.set(MAPI.MESSAGE_CLASS_RAW, pstMail.getMessageClass());


        metadata.set(Message.MESSAGE_FROM_EMAIL, pstMail.getSenderEmailAddress());

        metadata.set(MAPI.FROM_REPRESENTING_EMAIL,
                pstMail.getSentRepresentingEmailAddress());

        metadata.set(Message.MESSAGE_FROM_NAME, pstMail.getSenderName());
        metadata.set(MAPI.FROM_REPRESENTING_NAME, pstMail.getSentRepresentingName());

        //add recipient details
        try {
            for (int i = 0; i < pstMail.getNumberOfRecipients(); i++) {
                PSTRecipient recipient = pstMail.getRecipient(i);
                switch (OutlookExtractor.RECIPIENT_TYPE
                        .getTypeFromVal(recipient.getRecipientType())) {
                    case TO:
                        OutlookExtractor.addEvenIfNull(Message.MESSAGE_TO_DISPLAY_NAME,
                                recipient.getDisplayName(), metadata);
                        OutlookExtractor.addEvenIfNull(Message.MESSAGE_TO_EMAIL,
                                recipient.getEmailAddress(), metadata);
                        break;
                    case CC:
                        OutlookExtractor.addEvenIfNull(Message.MESSAGE_CC_DISPLAY_NAME,
                                recipient.getDisplayName(), metadata);
                        OutlookExtractor.addEvenIfNull(Message.MESSAGE_CC_EMAIL,
                                recipient.getEmailAddress(), metadata);
                        break;
                    case BCC:
                        OutlookExtractor.addEvenIfNull(Message.MESSAGE_BCC_DISPLAY_NAME,
                                recipient.getDisplayName(), metadata);
                        OutlookExtractor.addEvenIfNull(Message.MESSAGE_BCC_EMAIL,
                                recipient.getEmailAddress(), metadata);
                        break;
                    default:
                        //do we want to handle unspecified or unknown?
                        break;
                }
            }
        } catch (IOException | PSTException e) {
            //swallow
        }

    }

    private void parseMailAttachments(PSTMessage email, XHTMLContentHandler xhtml,
                                      Metadata metadata, ParseContext context,
                                      EmbeddedDocumentExtractor embeddedExtractor,
                                      AttachmentTaggedContentHandler taggedOutput)
            throws TikaException, SAXException {
        int numberOfAttachments = email.getNumberOfAttachments();
        for (int i = 0; i < numberOfAttachments; i++) {
            try {
                PSTAttachment attachment = email.getAttachment(i);
                parseMailAttachment(
                        xhtml, attachment, metadata, embeddedExtractor,
                        context, taggedOutput);
                throwIfOutputFailure(taggedOutput, null);
            } catch (SecurityException e) {
                throwIfOutputFailure(taggedOutput, e);
                throw e;
            } catch (SAXException e) {
                throwIfOutputFailure(taggedOutput, e);
                throwIfSecurityException(e);
                WriteLimitReachedException.throwIfWriteLimitReached(e);
                EmbeddedDocumentUtil.recordEmbeddedStreamException(e, metadata);
            } catch (Exception e) {
                throwIfOutputFailure(taggedOutput, e);
                throwIfSecurityException(e);
                EmbeddedDocumentUtil.recordEmbeddedStreamException(e, metadata);
            } catch (Error e) {
                throwIfOutputFailure(taggedOutput, e);
                throw e;
            }
        }
    }

    private static void throwIfOutputFailure(
            AttachmentTaggedContentHandler taggedOutput, Throwable failure)
            throws SAXException {
        if (taggedOutput.isFatalCleanupFailure(failure)) {
            return;
        }
        Throwable outputFailure =
                findOutputSaxFailure(taggedOutput, failure);
        if (outputFailure == null) {
            outputFailure = taggedOutput.findUncheckedCause(failure);
        }
        if (!(failure instanceof Error)) {
            if (outputFailure == null) {
                outputFailure = taggedOutput.getSaxFailure();
            }
            if (outputFailure == null) {
                outputFailure = taggedOutput.getUncheckedFailure();
            }
        }
        if (outputFailure == null) {
            return;
        }
        if (failure != null) {
            if (containsThrowable(failure, outputFailure)) {
                copySuppressed(outputFailure, failure);
            } else {
                addSuppressed(outputFailure, failure);
            }
        }
        if (outputFailure instanceof SAXException saxFailure) {
            throw saxFailure;
        }
        if (outputFailure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (outputFailure instanceof Error errorFailure) {
            throw errorFailure;
        }
    }

    private static SAXException findOutputSaxFailure(
            AttachmentTaggedContentHandler taggedOutput, Throwable failure) {
        if (failure == null) {
            return null;
        }
        SAXException recordedFailure = taggedOutput.getSaxFailure();
        Set<Throwable> seen =
                Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        pending.push(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.pop();
            if (!seen.add(current)) {
                continue;
            }
            if (current == recordedFailure) {
                return recordedFailure;
            }
            if (current instanceof SAXException saxFailure
                    && taggedOutput.isCauseOf(saxFailure)) {
                try {
                    taggedOutput.throwIfCauseOf(saxFailure);
                } catch (SAXException outputFailure) {
                    return outputFailure;
                }
            }
            Throwable cause = current.getCause();
            if (cause != null && cause != current) {
                pending.push(cause);
            }
            Throwable[] suppressed = current.getSuppressed();
            for (int i = suppressed.length - 1; i >= 0; i--) {
                Throwable candidate = suppressed[i];
                if (candidate != null && candidate != current) {
                    pending.push(candidate);
                }
            }
        }
        return null;
    }

    private static void throwIfSecurityException(Throwable failure) {
        if (failure == null) {
            return;
        }
        Set<Throwable> seen =
                Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        pending.push(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.pop();
            if (!seen.add(current)) {
                continue;
            }
            if (current instanceof SecurityException securityFailure) {
                copySuppressed(securityFailure, failure);
                throw securityFailure;
            }
            Throwable cause = current.getCause();
            if (cause != null && cause != current) {
                pending.push(cause);
            }
            Throwable[] suppressed = current.getSuppressed();
            for (int i = suppressed.length - 1; i >= 0; i--) {
                Throwable candidate = suppressed[i];
                if (candidate != null && candidate != current) {
                    pending.push(candidate);
                }
            }
        }
    }

    private void parseMailAttachment(XHTMLContentHandler xhtml, PSTAttachment attachment, Metadata metadata,
                                     EmbeddedDocumentExtractor embeddedExtractor, ParseContext context,
                                     AttachmentTaggedContentHandler taggedOutput)
            throws PSTException, IOException, TikaException, SAXException {

        PSTMessage attachedEmail = attachment.getEmbeddedPSTMessage();
        //check for whether this is a binary attachment or an embedded pst msg
        if (attachedEmail != null) {
            long sz = OutlookPSTParser.estimateSize(attachedEmail);
            TikaInputStream tis =
                    TikaInputStream.getFromContainer(
                            attachedEmail, sz, metadata);
            Metadata attachMetadata = Metadata.newInstance(context);
            attachMetadata.set(Metadata.CONTENT_TYPE, PSTMailItemParser.PST_MAIL_ITEM_STRING);
            attachMetadata.set(TikaCoreProperties.CONTENT_TYPE_PARSER_OVERRIDE, PSTMailItemParser.PST_MAIL_ITEM_STRING);
            attachMetadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, attachedEmail.getSubject() + ".msg");
            attachMetadata.set(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE, TikaCoreProperties.EmbeddedResourceType.ATTACHMENT.name());
            parseEmbeddedAndClose(
                    tis, embeddedExtractor, xhtml, attachMetadata,
                    context, true, taggedOutput);
            return;
        }

        // Get the filename; both long and short filenames can be used for attachments
        String filename = attachment.getLongFilename();
        if (filename.isEmpty()) {
            filename = attachment.getFilename();
        }

        xhtml.element("p", filename);

        Metadata attachMeta = Metadata.newInstance(context);
        attachMeta.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        attachMeta.set(TikaCoreProperties.EMBEDDED_RELATIONSHIP_ID, filename);
        attachMeta.set(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE, TikaCoreProperties.EmbeddedResourceType.ATTACHMENT.toString());
        attachMeta.set(Metadata.CONTENT_LENGTH, Integer.toString(attachment.getSize()));
        AttributesImpl attributes = new AttributesImpl();
        attributes.addAttribute("", "class", "class", "CDATA", "embedded");
        attributes.addAttribute("", "id", "id", "CDATA", filename);
        xhtml.startElement("div", attributes);
        if (embeddedExtractor.shouldParseEmbedded(attachMeta)) {
            TikaInputStream tis = null;
            try {
                tis = TikaInputStream.get(attachment.getFileInputStream());
            } catch (NullPointerException e) { //TIKA-2488
                EmbeddedDocumentUtil.recordEmbeddedStreamException(e, metadata);
                return;
            }

            parseEmbeddedAndClose(
                    tis, embeddedExtractor, xhtml, attachMeta,
                    context, false, taggedOutput);
        }
        throwIfOutputFailure(taggedOutput, null);
        xhtml.endElement("div");
    }

    private static void parseEmbeddedAndClose(
            TikaInputStream stream,
            EmbeddedDocumentExtractor embeddedExtractor,
            ContentHandler handler, Metadata metadata,
            ParseContext context, boolean outputHtml,
            AttachmentTaggedContentHandler taggedOutput)
            throws IOException, SAXException {
        Throwable primaryFailure = null;
        Throwable parserFailure = null;
        try {
            embeddedExtractor.parseEmbedded(
                    stream, handler, metadata, context, outputHtml);
            throwIfOutputFailure(taggedOutput, null);
        } catch (IOException | SAXException | RuntimeException | Error e) {
            parserFailure = e;
            primaryFailure =
                    normalizeParserFailure(taggedOutput, parserFailure);
            if (primaryFailure instanceof IOException ioFailure) {
                throw ioFailure;
            }
            if (primaryFailure instanceof SAXException saxFailure) {
                throw saxFailure;
            }
            if (primaryFailure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw (Error) primaryFailure;
        } finally {
            closeAttachmentStream(
                    stream, primaryFailure, parserFailure, taggedOutput);
        }
    }

    private static Throwable normalizeParserFailure(
            AttachmentTaggedContentHandler taggedOutput,
            Throwable parserFailure) {
        Throwable outputFailure =
                findOutputSaxFailure(taggedOutput, parserFailure);
        if (outputFailure == null) {
            outputFailure =
                    taggedOutput.findUncheckedCause(parserFailure);
        }
        if (outputFailure == null) {
            return parserFailure;
        }
        copySuppressed(outputFailure, parserFailure);
        return outputFailure;
    }

    private static void closeAttachmentStream(
            TikaInputStream stream, Throwable primaryFailure,
            Throwable parserFailure,
            AttachmentTaggedContentHandler taggedOutput)
            throws IOException {
        try {
            stream.close();
        } catch (Throwable cleanupFailure) {
            if (primaryFailure == null) {
                if (isFatalFailure(cleanupFailure)) {
                    preserveRecordedOutputEvidence(
                            taggedOutput, cleanupFailure);
                    taggedOutput.recordFatalCleanupFailure(cleanupFailure);
                }
                throwCleanupFailure(cleanupFailure);
            }
            if (fatalCleanupSupersedes(
                    cleanupFailure, primaryFailure)) {
                addSuppressed(cleanupFailure, primaryFailure);
                addSuppressed(cleanupFailure, parserFailure);
                preserveRecordedOutputEvidence(
                        taggedOutput, cleanupFailure);
                taggedOutput.recordFatalCleanupFailure(cleanupFailure);
                throwCleanupFailure(cleanupFailure);
            }
            addSuppressed(primaryFailure, cleanupFailure);
        }
    }

    private static boolean fatalCleanupSupersedes(
            Throwable cleanupFailure, Throwable primaryFailure) {
        if (cleanupFailure instanceof Error) {
            return !(primaryFailure instanceof Error);
        }
        return cleanupFailure instanceof SecurityException
                && !(primaryFailure instanceof Error)
                && !(primaryFailure instanceof SecurityException);
    }

    private static boolean isFatalFailure(Throwable failure) {
        return failure instanceof SecurityException
                || failure instanceof Error;
    }

    private static void preserveRecordedOutputEvidence(
            AttachmentTaggedContentHandler taggedOutput,
            Throwable primaryFailure) {
        preserveRecordedOutputFailure(
                primaryFailure, taggedOutput.getSaxFailure());
        preserveRecordedOutputFailure(
                primaryFailure, taggedOutput.getUncheckedFailure());
    }

    private static void preserveRecordedOutputFailure(
            Throwable primaryFailure, Throwable outputFailure) {
        if (outputFailure != null
                && !containsThrowable(primaryFailure, outputFailure)) {
            addSuppressed(primaryFailure, outputFailure);
        }
    }

    private static void throwCleanupFailure(Throwable failure)
            throws IOException {
        if (failure instanceof IOException ioException) {
            throw ioException;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IOException(
                "Unexpected PST attachment cleanup failure", failure);
    }

    private static void copySuppressed(
            Throwable target, Throwable failure) {
        if (target == null || failure == null || target == failure) {
            return;
        }
        Set<Throwable> seen =
                Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        pending.push(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.pop();
            if (current == null || !seen.add(current)) {
                continue;
            }
            for (Throwable suppressed : current.getSuppressed()) {
                addSuppressed(target, suppressed);
                if (suppressed != null && suppressed != current) {
                    pending.push(suppressed);
                }
            }
            Throwable cause = current.getCause();
            if (cause != null && cause != current) {
                pending.push(cause);
            }
        }
    }

    private static void addSuppressed(
            Throwable primaryFailure, Throwable suppressedFailure) {
        if (primaryFailure == null || suppressedFailure == null
                || primaryFailure == suppressedFailure
                || containsThrowable(suppressedFailure, primaryFailure)) {
            return;
        }
        for (Throwable existing : primaryFailure.getSuppressed()) {
            if (existing == suppressedFailure) {
                return;
            }
        }
        primaryFailure.addSuppressed(suppressedFailure);
    }

    private static boolean containsThrowable(
            Throwable root, Throwable sought) {
        Set<Throwable> seen =
                Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Throwable current = pending.pop();
            if (current == sought) {
                return true;
            }
            if (current == null || !seen.add(current)) {
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
