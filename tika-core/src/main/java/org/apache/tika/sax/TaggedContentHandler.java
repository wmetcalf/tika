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
package org.apache.tika.sax;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

/**
 * A content handler decorator that tags potential exceptions so that the
 * handler that caused the exception can easily be identified. This is
 * done by using the {@link TaggedSAXException} class to wrap all thrown
 * {@link SAXException}s. See below for an example of using this class.
 * <pre>
 * TaggedContentHandler handler = new TaggedContentHandler(...);
 * try {
 *     // Processing that may throw an SAXException either from this handler
 *     // or from some other XML parsing activity
 *     processXML(handler);
 * } catch (SAXException e) {
 *     if (handler.isCauseOf(e)) {
 *         // The exception was caused by this handler.
 *         // Use e.getCause() to get the original exception.
 *     } else {
 *         // The exception was caused by something else.
 *     }
 * }
 * </pre>
 * <p>
 * Alternatively, the {@link #throwIfCauseOf(Exception)} method can be
 * used to let higher levels of code handle the exception caused by this
 * stream while other processing errors are being taken care of at this
 * lower level.
 * <pre>
 * TaggedContentHandler handler = new TaggedContentHandler(...);
 * try {
 *     processXML(handler);
 * } catch (SAXException e) {
 *     stream.throwIfCauseOf(e);
 *     // ... or process the exception that was caused by something else
 * }
 * </pre>
 *
 * @see TaggedSAXException
 */
public class TaggedContentHandler extends ContentHandlerDecorator {

    private final Set<Throwable> uncheckedFailures =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private SAXException firstSaxFailure;
    private Throwable firstUncheckedFailure;

    /**
     * Creates a tagging decorator for the given content handler.
     *
     * @param proxy content handler to be decorated
     */
    public TaggedContentHandler(ContentHandler proxy) {
        super(proxy);
    }

    /**
     * Tests if the given exception was caused by this handler.
     *
     * @param exception an exception
     * @return <code>true</code> if the exception was thrown by this handler,
     * <code>false</code> otherwise
     */
    public boolean isCauseOf(SAXException exception) {
        if (exception instanceof TaggedSAXException) {
            TaggedSAXException tagged = (TaggedSAXException) exception;
            return this == tagged.getTag();
        } else {
            return false;
        }
    }

    /**
     * Re-throws the original exception thrown by this handler. This method
     * first checks whether the given exception is a {@link TaggedSAXException}
     * wrapper created by this decorator, and then unwraps and throws the
     * original wrapped exception. Returns normally if the exception was
     * not thrown by this handler.
     *
     * @param exception an exception
     * @throws SAXException original exception, if any, thrown by this handler
     */
    public void throwIfCauseOf(Exception exception) throws SAXException {
        SAXException original = null;
        while (exception instanceof TaggedSAXException tagged
                && this == tagged.getTag()) {
            original = tagged.getCause();
            exception = original;
        }
        if (original != null) {
            throw original;
        }
    }

    /**
     * Returns the first SAX exception thrown by the decorated content handler,
     * preserving its exact identity even if a caller swallowed the tagged
     * wrapper.
     *
     * @return first checked output failure, or {@code null} if none occurred
     */
    public SAXException getSaxFailure() {
        return firstSaxFailure;
    }

    /**
     * Returns the first unchecked exception or error thrown by the decorated
     * content handler, preserving its exact identity.
     *
     * @return first unchecked output failure, or {@code null} if none occurred
     */
    public Throwable getUncheckedFailure() {
        return firstUncheckedFailure;
    }

    /**
     * Searches the cause and suppressed-exception graph for an unchecked
     * failure previously thrown by the decorated content handler.
     *
     * @param failure failure graph to search
     * @return matching unchecked output failure, or {@code null}
     */
    public Throwable findUncheckedCause(Throwable failure) {
        if (failure == null) {
            return null;
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
            if (uncheckedFailures.contains(current)) {
                return current;
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

    @Override
    public void setDocumentLocator(Locator locator) {
        invokeUnchecked(() -> super.setDocumentLocator(locator));
    }

    @Override
    public void startDocument() throws SAXException {
        invoke(this::startDocumentDelegate);
    }

    private void startDocumentDelegate() throws SAXException {
        super.startDocument();
    }

    @Override
    public void endDocument() throws SAXException {
        invoke(this::endDocumentDelegate);
    }

    private void endDocumentDelegate() throws SAXException {
        super.endDocument();
    }

    @Override
    public void startPrefixMapping(String prefix, String uri)
            throws SAXException {
        invoke(() -> super.startPrefixMapping(prefix, uri));
    }

    @Override
    public void endPrefixMapping(String prefix) throws SAXException {
        invoke(() -> super.endPrefixMapping(prefix));
    }

    @Override
    public void startElement(
            String uri, String localName, String qName, Attributes attributes)
            throws SAXException {
        invoke(() -> super.startElement(
                uri, localName, qName, attributes));
    }

    @Override
    public void endElement(
            String uri, String localName, String qName)
            throws SAXException {
        invoke(() -> super.endElement(uri, localName, qName));
    }

    @Override
    public void characters(char[] chars, int start, int length)
            throws SAXException {
        invoke(() -> super.characters(chars, start, length));
    }

    @Override
    public void ignorableWhitespace(
            char[] chars, int start, int length) throws SAXException {
        invoke(() -> super.ignorableWhitespace(chars, start, length));
    }

    @Override
    public void processingInstruction(String target, String data)
            throws SAXException {
        invoke(() -> super.processingInstruction(target, data));
    }

    @Override
    public void skippedEntity(String name) throws SAXException {
        invoke(() -> super.skippedEntity(name));
    }

    private void invoke(SaxCallback callback) throws SAXException {
        try {
            callback.run();
        } catch (RuntimeException | Error failure) {
            recordUncheckedFailure(failure);
            throw failure;
        }
    }

    private void invokeUnchecked(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException | Error failure) {
            recordUncheckedFailure(failure);
            throw failure;
        }
    }

    private void recordUncheckedFailure(Throwable failure) {
        if (firstUncheckedFailure == null) {
            firstUncheckedFailure = failure;
        }
        uncheckedFailures.add(failure);
    }

    @FunctionalInterface
    private interface SaxCallback {
        void run() throws SAXException;
    }

    /**
     * Tags any {@link SAXException}s thrown, wrapping and re-throwing.
     *
     * @param e The SAXException thrown
     * @throws SAXException if an XML error occurs
     */
    @Override
    protected void handleException(SAXException e) throws SAXException {
        if (firstSaxFailure == null) {
            firstSaxFailure = e;
        }
        throw new TaggedSAXException(e, this);
    }

}
