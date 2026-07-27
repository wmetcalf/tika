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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Generic SAX handler that collects raw XML content by ID from OOXML part files.
 * Works with any part that contains wrapper elements with {@code w:id} attributes
 * containing body content (paragraphs, tables, formatting, etc.).
 * <p>
 * Used for:
 * <ul>
 *   <li>footnotes.xml — wrapper element "footnote"</li>
 *   <li>endnotes.xml — wrapper element "endnote"</li>
 *   <li>comments.xml — wrapper element "comment"</li>
 * </ul>
 * <p>
 * IDs "0" and "-1" are skipped (these are separator/continuation elements in
 * footnotes/endnotes).
 */
class OOXMLPartContentCollector extends DefaultHandler {

    private static final String W_NS =
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final int DEFAULT_MAX_PARTS = 1_024;
    private static final long DEFAULT_MAX_SERIALIZED_BYTES = 8L * 1_024 * 1_024;
    private static final int ESCAPE_CHUNK_CHARS = 4_096;

    private final Set<String> wrapperElementNames;
    private final Set<String> skipIds;
    private final CollectionBudget collectionBudget;
    private final Map<String, byte[]> contentMap = new HashMap<>();
    private final Map<String, String> namespaceMappings = new HashMap<>();

    private String currentId = null;
    private ByteArrayOutputStream buffer = null;
    private boolean currentPartDropped = false;
    private char pendingHighSurrogate = 0;
    private int depth = 0;

    /**
     * @param wrapperElementNames local names of wrapper elements to collect
     *                            (e.g., "footnote", "endnote", "comment")
     */
    OOXMLPartContentCollector(Set<String> wrapperElementNames) {
        this(wrapperElementNames, Set.of("0", "-1"), newDefaultCollectionBudget());
    }

    /**
     * @param wrapperElementNames local names of wrapper elements to collect
     * @param skipIds             IDs to skip (e.g., "0", "-1" for footnote
     *                            separator/continuation elements)
     */
    OOXMLPartContentCollector(Set<String> wrapperElementNames, Set<String> skipIds) {
        this(wrapperElementNames, skipIds, newDefaultCollectionBudget());
    }

    OOXMLPartContentCollector(Set<String> wrapperElementNames, Set<String> skipIds,
            CollectionBudget collectionBudget) {
        this.wrapperElementNames = wrapperElementNames;
        this.skipIds = skipIds;
        this.collectionBudget = collectionBudget;
    }

    static CollectionBudget newDefaultCollectionBudget() {
        return new CollectionBudget(DEFAULT_MAX_PARTS, DEFAULT_MAX_SERIALIZED_BYTES);
    }

    @Override
    public void startPrefixMapping(String prefix, String uri) {
        namespaceMappings.put(prefix, uri);
    }

    Map<String, byte[]> getContentMap() {
        return contentMap;
    }

    @Override
    public void startElement(String uri, String localName, String qName,
            Attributes atts) throws SAXException {
        if (currentId != null) {
            flushPendingHighSurrogate();
            depth++;
            appendStartTag(localName, qName, atts);
            return;
        }

        if (wrapperElementNames.contains(localName)) {
            String id = atts.getValue(W_NS, "id");
            if (id != null && !skipIds.contains(id)) {
                currentId = id;
                currentPartDropped = !collectionBudget.tryStartPart();
                buffer = currentPartDropped ? null : new ByteArrayOutputStream();
                // Don't write wrapper open tag yet — inline xmlns declarations
                // (e.g., xmlns:a on nested elements) haven't been captured via
                // startPrefixMapping. Defer to endElement when all are known.
                depth = 0;
            }
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName)
            throws SAXException {
        if (currentId == null) {
            return;
        }
        flushPendingHighSurrogate();

        if (depth == 0) {
            if (!currentPartDropped) {
                byte[] serialized = finishCurrentPart();
                if (serialized != null &&
                        collectionBudget.tryRetain(serialized.length)) {
                    contentMap.put(currentId, serialized);
                }
            }
            resetCurrentPart();
            return;
        }

        depth--;
        if (qName != null && !qName.isEmpty()) {
            writeString("</" + qName + ">");
        } else {
            writeString("</" + localName + ">");
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        if (currentId == null || length == 0) {
            return;
        }
        int end = start + length;
        if (pendingHighSurrogate != 0) {
            if (Character.isLowSurrogate(ch[start])) {
                writeEscaped(new String(
                        new char[]{pendingHighSurrogate, ch[start]}));
                start++;
            } else {
                writeEscaped(Character.toString(pendingHighSurrogate));
            }
            pendingHighSurrogate = 0;
        }
        if (start < end && Character.isHighSurrogate(ch[end - 1])) {
            pendingHighSurrogate = ch[end - 1];
            end--;
        }
        if (start < end) {
            writeEscaped(new String(ch, start, end - start));
        }
    }

    private byte[] finishCurrentPart() {
        long maxBytes = collectionBudget.getRemainingBytes();
        int initialCapacity = (int) Math.min(Integer.MAX_VALUE,
                Math.min(maxBytes, (long) buffer.size() + 256));
        ByteArrayOutputStream combined = new ByteArrayOutputStream(initialCapacity);
        if (!writeString(combined, "<w:body", maxBytes)) {
            return dropFinishedPart();
        }
        // include all namespace declarations from the source document
        for (Map.Entry<String, String> entry : namespaceMappings.entrySet()) {
            String prefix = entry.getKey();
            String nsUri = entry.getValue();
            if (prefix == null || prefix.isEmpty()) {
                if (!writeString(combined, " xmlns=\"", maxBytes) ||
                        !writeEscaped(combined, nsUri, maxBytes) ||
                        !writeString(combined, "\"", maxBytes)) {
                    return dropFinishedPart();
                }
            } else {
                if (!writeString(combined, " xmlns:", maxBytes) ||
                        !writeString(combined, prefix, maxBytes) ||
                        !writeString(combined, "=\"", maxBytes) ||
                        !writeEscaped(combined, nsUri, maxBytes) ||
                        !writeString(combined, "\"", maxBytes)) {
                    return dropFinishedPart();
                }
            }
        }
        // ensure w namespace is present
        if (!namespaceMappings.containsKey("w")) {
            if (!writeString(combined, " xmlns:w=\"", maxBytes) ||
                    !writeString(combined, W_NS, maxBytes) ||
                    !writeString(combined, "\"", maxBytes)) {
                return dropFinishedPart();
            }
        }
        if (!writeString(combined, ">", maxBytes) ||
                buffer.size() > maxBytes - combined.size()) {
            return dropFinishedPart();
        }
        try {
            buffer.writeTo(combined);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unexpected in-memory write failure", e);
        }
        if (!writeString(combined, "</w:body>", maxBytes)) {
            return dropFinishedPart();
        }
        return combined.toByteArray();
    }

    private byte[] dropFinishedPart() {
        collectionBudget.markLimitReached();
        return null;
    }

    private void appendStartTag(String localName, String qName, Attributes atts) {
        String tagName = (qName != null && !qName.isEmpty()) ? qName : localName;
        writeString("<");
        writeString(tagName);
        for (int i = 0; i < atts.getLength(); i++) {
            String attName = atts.getQName(i);
            if (attName == null || attName.isEmpty()) {
                attName = atts.getLocalName(i);
            }
            writeString(" ");
            writeString(attName);
            writeString("=\"");
            writeEscaped(atts.getValue(i));
            writeString("\"");
        }
        writeString(">");
    }

    private void writeString(String s) {
        if (currentPartDropped) {
            return;
        }
        if (!writeString(buffer, s, collectionBudget.getRemainingBytes())) {
            dropCurrentPart();
        }
    }

    private void writeEscaped(String s) {
        if (currentPartDropped) {
            return;
        }
        if (!writeEscaped(buffer, s, collectionBudget.getRemainingBytes())) {
            dropCurrentPart();
        }
    }

    private static boolean writeString(
            ByteArrayOutputStream target, String s, long maxBytes) {
        long remaining = maxBytes - target.size();
        if (s.length() > remaining) {
            return false;
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > remaining) {
            return false;
        }
        target.write(bytes, 0, bytes.length);
        return true;
    }

    private static boolean writeEscaped(
            ByteArrayOutputStream target, String s, long maxBytes) {
        if (s == null) {
            return true;
        }
        StringBuilder chunk = new StringBuilder(
                Math.min(ESCAPE_CHUNK_CHARS, s.length()));
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() &&
                    Character.isLowSurrogate(s.charAt(i + 1))) {
                if (chunk.length() >= ESCAPE_CHUNK_CHARS - 1 &&
                        !writeString(target, chunk.toString(), maxBytes)) {
                    return false;
                }
                if (chunk.length() >= ESCAPE_CHUNK_CHARS - 1) {
                    chunk.setLength(0);
                }
                chunk.append(c).append(s.charAt(++i));
            } else {
                switch (c) {
                    case '&':
                        chunk.append("&amp;");
                        break;
                    case '<':
                        chunk.append("&lt;");
                        break;
                    case '>':
                        chunk.append("&gt;");
                        break;
                    case '"':
                        chunk.append("&quot;");
                        break;
                    default:
                        chunk.append(c);
                        break;
                }
            }
            if (chunk.length() >= ESCAPE_CHUNK_CHARS) {
                if (!writeString(target, chunk.toString(), maxBytes)) {
                    return false;
                }
                chunk.setLength(0);
            }
        }
        return chunk.length() == 0 ||
                writeString(target, chunk.toString(), maxBytes);
    }

    private void dropCurrentPart() {
        currentPartDropped = true;
        buffer = null;
        collectionBudget.markLimitReached();
    }

    private void resetCurrentPart() {
        currentId = null;
        buffer = null;
        currentPartDropped = false;
        pendingHighSurrogate = 0;
    }

    private void flushPendingHighSurrogate() {
        if (pendingHighSurrogate != 0) {
            writeEscaped(Character.toString(pendingHighSurrogate));
            pendingHighSurrogate = 0;
        }
    }

    static final class CollectionBudget {

        private final int maxParts;
        private final long maxSerializedBytes;
        private int retainedParts;
        private long retainedBytes;
        private boolean limitReached;

        CollectionBudget(int maxParts, long maxSerializedBytes) {
            if (maxParts < 0 || maxSerializedBytes < 0) {
                throw new IllegalArgumentException("Collection limits must be non-negative");
            }
            this.maxParts = maxParts;
            this.maxSerializedBytes = maxSerializedBytes;
        }

        private boolean tryStartPart() {
            if (limitReached) {
                return false;
            }
            if (retainedParts >= maxParts ||
                    retainedBytes >= maxSerializedBytes) {
                limitReached = true;
                return false;
            }
            return true;
        }

        private boolean tryRetain(long serializedBytes) {
            if (limitReached || retainedParts >= maxParts ||
                    serializedBytes > maxSerializedBytes - retainedBytes) {
                limitReached = true;
                return false;
            }
            retainedParts++;
            retainedBytes += serializedBytes;
            return true;
        }

        private long getRemainingBytes() {
            return maxSerializedBytes - retainedBytes;
        }

        private void markLimitReached() {
            limitReached = true;
        }

        boolean isLimitReached() {
            return limitReached;
        }
    }

    static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String replacement = null;
            switch (c) {
                case '&':
                    replacement = "&amp;";
                    break;
                case '<':
                    replacement = "&lt;";
                    break;
                case '>':
                    replacement = "&gt;";
                    break;
                case '"':
                    replacement = "&quot;";
                    break;
                default:
                    if (sb != null) {
                        sb.append(c);
                    }
                    continue;
            }
            if (sb == null) {
                sb = new StringBuilder(s.length() + 16);
                sb.append(s, 0, i);
            }
            sb.append(replacement);
        }
        return sb != null ? sb.toString() : s;
    }
}
