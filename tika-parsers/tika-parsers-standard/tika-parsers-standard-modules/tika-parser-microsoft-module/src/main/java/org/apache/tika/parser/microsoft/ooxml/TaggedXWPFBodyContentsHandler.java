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

import java.util.Date;

import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.TaggedSAXException;
import org.apache.tika.sax.XHTMLContentHandler;

/**
 * Tags SAX failures from an OOXML body-output handler so callers can
 * distinguish downstream output denials from malformed source XML.
 */
final class TaggedXWPFBodyContentsHandler implements XWPFBodyContentsHandler {

    private final XWPFBodyContentsHandler delegate;
    private final Object outputTag;

    TaggedXWPFBodyContentsHandler(XWPFBodyContentsHandler delegate) {
        this(delegate, null);
    }

    TaggedXWPFBodyContentsHandler(
            XWPFBodyContentsHandler delegate, Object outputTag) {
        this.delegate = delegate;
        this.outputTag = outputTag;
    }

    static XHTMLContentHandler tagOutput(
            XHTMLContentHandler delegate, Metadata metadata,
            ParseContext context, Object outputTag) {
        return new FailStopTaggedXHTMLContentHandler(
                delegate, metadata, context, outputTag);
    }

    void throwIfCauseOf(Exception exception) throws SAXException {
        SAXException original = null;
        while (exception instanceof TaggedSAXException tagged
                && (tagged.getTag() == this
                || tagged.getTag() == outputTag)) {
            original = tagged.getCause();
            exception = original;
        }
        if (original != null) {
            throw original;
        }
    }

    @Override
    public void run(RunProperties properties, String contents)
            throws SAXException {
        call(() -> delegate.run(properties, contents));
    }

    @Override
    public void hyperlinkStart(String link) throws SAXException {
        call(() -> delegate.hyperlinkStart(link));
    }

    @Override
    public void fieldCodeHyperlinkStart(String link) throws SAXException {
        call(() -> delegate.fieldCodeHyperlinkStart(link));
    }

    @Override
    public void hyperlinkEnd() throws SAXException {
        call(delegate::hyperlinkEnd);
    }

    @Override
    public void startParagraph(ParagraphProperties properties)
            throws SAXException {
        call(() -> delegate.startParagraph(properties));
    }

    @Override
    public void endParagraph() throws SAXException {
        call(delegate::endParagraph);
    }

    @Override
    public void startTable() throws SAXException {
        call(delegate::startTable);
    }

    @Override
    public void endTable() throws SAXException {
        call(delegate::endTable);
    }

    @Override
    public void startTableRow() throws SAXException {
        call(delegate::startTableRow);
    }

    @Override
    public void endTableRow() throws SAXException {
        call(delegate::endTableRow);
    }

    @Override
    public void startTableCell() throws SAXException {
        call(delegate::startTableCell);
    }

    @Override
    public void endTableCell() throws SAXException {
        call(delegate::endTableCell);
    }

    @Override
    public void startSDT() throws SAXException {
        call(delegate::startSDT);
    }

    @Override
    public void endSDT() throws SAXException {
        call(delegate::endSDT);
    }

    @Override
    public void startEditedSection(String editor, Date date, EditType editType)
            throws SAXException {
        call(() -> delegate.startEditedSection(editor, date, editType));
    }

    @Override
    public void endEditedSection() throws SAXException {
        call(delegate::endEditedSection);
    }

    @Override
    public boolean isIncludeDeletedText() throws SAXException {
        return query(delegate::isIncludeDeletedText);
    }

    @Override
    public void footnoteReference(String id) throws SAXException {
        call(() -> delegate.footnoteReference(id));
    }

    @Override
    public void endnoteReference(String id) throws SAXException {
        call(() -> delegate.endnoteReference(id));
    }

    @Override
    public void commentReference(String id) throws SAXException {
        call(() -> delegate.commentReference(id));
    }

    @Override
    public boolean isIncludeMoveFromText() throws SAXException {
        return query(delegate::isIncludeMoveFromText);
    }

    @Override
    public void embeddedOLERef(
            String refId, String progId, String emfImageRId)
            throws SAXException {
        call(() -> delegate.embeddedOLERef(refId, progId, emfImageRId));
    }

    @Override
    public void linkedOLERef(String refId, String url) throws SAXException {
        call(() -> delegate.linkedOLERef(refId, url));
    }

    @Override
    public void embeddedPicRef(String fileName, String description)
            throws SAXException {
        call(() -> delegate.embeddedPicRef(fileName, description));
    }

    @Override
    public void startBookmark(String id, String name) throws SAXException {
        call(() -> delegate.startBookmark(id, name));
    }

    @Override
    public void endBookmark(String id) throws SAXException {
        call(() -> delegate.endBookmark(id));
    }

    @Override
    public void externalRef(String fieldType, String url)
            throws SAXException {
        call(() -> delegate.externalRef(fieldType, url));
    }

    private void call(SaxAction action) throws SAXException {
        try {
            action.run();
        } catch (SAXException e) {
            throw new TaggedSAXException(e, this);
        }
    }

    private boolean query(SaxQuery query) throws SAXException {
        try {
            return query.get();
        } catch (SAXException e) {
            throw new TaggedSAXException(e, this);
        }
    }

    @FunctionalInterface
    private interface SaxAction {
        void run() throws SAXException;
    }

    @FunctionalInterface
    private interface SaxQuery {
        boolean get() throws SAXException;
    }

    private static final class FailStopTaggedXHTMLContentHandler
            extends XHTMLContentHandler {

        private final XHTMLContentHandler delegate;
        private final Object outputTag;
        private SAXException saxDenial;
        private SecurityException securityDenial;

        private FailStopTaggedXHTMLContentHandler(
                XHTMLContentHandler delegate, Metadata metadata,
                ParseContext context, Object outputTag) {
            super(new DefaultHandler(), metadata, context);
            this.delegate = delegate;
            this.outputTag = outputTag;
        }

        @Override
        public void setDocumentLocator(Locator locator) {
            delegate.setDocumentLocator(locator);
        }

        @Override
        public void startDocument() throws SAXException {
            call(delegate::startDocument);
        }

        @Override
        public void endDocument() throws SAXException {
            call(delegate::endDocument);
        }

        @Override
        public void startPrefixMapping(String prefix, String uri)
                throws SAXException {
            call(() -> delegate.startPrefixMapping(prefix, uri));
        }

        @Override
        public void endPrefixMapping(String prefix) throws SAXException {
            call(() -> delegate.endPrefixMapping(prefix));
        }

        @Override
        public void startElement(
                String uri, String localName, String qName,
                Attributes attributes) throws SAXException {
            call(() -> delegate.startElement(
                    uri, localName, qName, attributes));
        }

        @Override
        public void endElement(
                String uri, String localName, String qName)
                throws SAXException {
            call(() -> delegate.endElement(uri, localName, qName));
        }

        @Override
        public void characters(char[] ch, int start, int length)
                throws SAXException {
            call(() -> delegate.characters(ch, start, length));
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length)
                throws SAXException {
            call(() -> delegate.ignorableWhitespace(ch, start, length));
        }

        @Override
        public void processingInstruction(String target, String data)
                throws SAXException {
            call(() -> delegate.processingInstruction(target, data));
        }

        @Override
        public void skippedEntity(String name) throws SAXException {
            call(() -> delegate.skippedEntity(name));
        }

        private void call(SaxAction action) throws SAXException {
            if (securityDenial != null) {
                throw securityDenial;
            }
            if (saxDenial != null) {
                throw new TaggedSAXException(saxDenial, outputTag);
            }
            try {
                action.run();
            } catch (SecurityException e) {
                securityDenial = e;
                throw e;
            } catch (SAXException e) {
                saxDenial = e;
                throw new TaggedSAXException(e, outputTag);
            }
        }
    }
}
