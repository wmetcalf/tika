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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static final String XMLNS_NS = "http://www.w3.org/2000/xmlns/";
    private static final int DEFAULT_MAX_PARTS = 1_024;
    private static final long DEFAULT_MAX_SERIALIZED_BYTES = 8L * 1_024 * 1_024;
    private static final long DEFAULT_MAX_INPUT_BYTES = 32L * 1_024 * 1_024;
    private static final int DEFAULT_MAX_AUXILIARY_ENTRIES = 1_024;
    private static final int RETAINED_ENTRY_OVERHEAD_BYTES = 64;
    private static final int ESCAPE_CHUNK_CHARS = 4_096;

    private final Set<String> wrapperElementNames;
    private final Set<String> skipIds;
    private final CollectionBudget collectionBudget;
    private final Map<String, byte[]> contentMap = new HashMap<>();
    private final Map<String, ArrayDeque<String>> activeNamespaceMappings =
            new LinkedHashMap<>();
    private final List<NamespaceMapping> pendingNamespaceMappings =
            new ArrayList<>();

    private String currentId = null;
    private ByteArrayOutputStream buffer = null;
    private Map<String, String> currentPartRootNamespaceMappings = Map.of();
    private boolean currentPartDropped = false;
    private boolean namespaceCollectionStopped = false;
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
        return new CollectionBudget(DEFAULT_MAX_PARTS,
                DEFAULT_MAX_SERIALIZED_BYTES, DEFAULT_MAX_AUXILIARY_ENTRIES,
                DEFAULT_MAX_INPUT_BYTES);
    }

    @Override
    public void startPrefixMapping(String prefix, String uri)
            throws SAXException {
        throwIfLimitReached();
        if (namespaceCollectionStopped) {
            return;
        }
        String normalizedPrefix = prefix == null ? "" : prefix;
        String normalizedUri = uri == null ? "" : uri;
        if (!collectionBudget.tryRetainAuxiliaryEntry(
                normalizedPrefix, normalizedUri)) {
            namespaceCollectionStopped = true;
            activeNamespaceMappings.clear();
            pendingNamespaceMappings.clear();
            if (currentId != null) {
                dropCurrentPart();
            }
            throwIfLimitReached();
        }
        activeNamespaceMappings.computeIfAbsent(
                normalizedPrefix, ignored -> new ArrayDeque<>()).addLast(normalizedUri);
        pendingNamespaceMappings.add(
                new NamespaceMapping(normalizedPrefix, normalizedUri));
    }

    @Override
    public void endPrefixMapping(String prefix) {
        if (namespaceCollectionStopped) {
            return;
        }
        String normalizedPrefix = prefix == null ? "" : prefix;
        ArrayDeque<String> mappings =
                activeNamespaceMappings.get(normalizedPrefix);
        if (mappings == null || mappings.isEmpty()) {
            return;
        }
        mappings.removeLast();
        if (mappings.isEmpty()) {
            activeNamespaceMappings.remove(normalizedPrefix);
        }
    }

    Map<String, byte[]> getContentMap() {
        return contentMap;
    }

    @Override
    public void startElement(String uri, String localName, String qName,
            Attributes atts) throws SAXException {
        throwIfLimitReached();
        if (currentId != null) {
            flushPendingHighSurrogate();
            depth++;
            appendStartTag(localName, qName, atts, pendingNamespaceMappings);
            pendingNamespaceMappings.clear();
            throwIfLimitReached();
            return;
        }

        if (wrapperElementNames.contains(localName)) {
            String id = atts.getValue(W_NS, "id");
            if (id != null && !skipIds.contains(id)) {
                currentPartDropped = !collectionBudget.tryStartPart(id);
                currentId = currentPartDropped ? "" : id;
                buffer = currentPartDropped ? null : new ByteArrayOutputStream();
                currentPartRootNamespaceMappings =
                        snapshotActiveNamespaceMappings();
                depth = 0;
            }
        }
        pendingNamespaceMappings.clear();
        throwIfLimitReached();
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
            throwIfLimitReached();
            return;
        }

        depth--;
        if (qName != null && !qName.isEmpty()) {
            writeString("</" + qName + ">");
        } else {
            writeString("</" + localName + ">");
        }
        throwIfLimitReached();
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
        throwIfLimitReached();
    }

    private byte[] finishCurrentPart() {
        long maxBytes = collectionBudget.getRemainingBytes();
        int initialCapacity = (int) Math.min(Integer.MAX_VALUE,
                Math.min(maxBytes, (long) buffer.size() + 256));
        ByteArrayOutputStream combined = new ByteArrayOutputStream(initialCapacity);
        if (!writeString(combined, "<w:body", maxBytes)) {
            return dropFinishedPart();
        }
        for (Map.Entry<String, String> entry :
                currentPartRootNamespaceMappings.entrySet()) {
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
        if (!currentPartRootNamespaceMappings.containsKey("w")) {
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

    private Map<String, String> snapshotActiveNamespaceMappings() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, ArrayDeque<String>> entry :
                activeNamespaceMappings.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                snapshot.put(entry.getKey(), entry.getValue().peekLast());
            }
        }
        return snapshot;
    }

    private void appendStartTag(String localName, String qName, Attributes atts,
            List<NamespaceMapping> namespaceMappings) {
        String tagName = (qName != null && !qName.isEmpty()) ? qName : localName;
        writeString("<");
        writeString(tagName);
        for (NamespaceMapping mapping : namespaceMappings) {
            if (mapping.prefix().isEmpty()) {
                writeString(" xmlns=\"");
            } else {
                writeString(" xmlns:");
                writeString(mapping.prefix());
                writeString("=\"");
            }
            writeEscaped(mapping.uri());
            writeString("\"");
        }
        for (int i = 0; i < atts.getLength(); i++) {
            String attributeUri = atts.getURI(i);
            String attName = atts.getQName(i);
            if (attName == null || attName.isEmpty()) {
                attName = atts.getLocalName(i);
            }
            if (XMLNS_NS.equals(attributeUri)
                    || "xmlns".equals(attName)
                    || attName.startsWith("xmlns:")) {
                continue;
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
        currentPartRootNamespaceMappings = Map.of();
        currentPartDropped = false;
        pendingHighSurrogate = 0;
    }

    private void flushPendingHighSurrogate() {
        if (pendingHighSurrogate != 0) {
            writeEscaped(Character.toString(pendingHighSurrogate));
            pendingHighSurrogate = 0;
        }
    }

    private void throwIfLimitReached()
            throws CollectionLimitReachedException {
        if (collectionBudget.isLimitReached()) {
            throw new CollectionLimitReachedException();
        }
    }

    static final class CollectionLimitReachedException extends SAXException {

        private static final long serialVersionUID = 1L;

        private CollectionLimitReachedException() {
            super("OOXML inline-part collection limit reached");
        }
    }

    static final class CollectionBudget {

        private final int maxParts;
        private final long maxSerializedBytes;
        private final int maxAuxiliaryEntries;
        private final long maxInputBytes;
        private int retainedParts;
        private long retainedBytes;
        private int retainedAuxiliaryEntries;
        private long consumedInputBytes;
        private boolean limitReached;
        private boolean inputLimitReached;

        CollectionBudget(int maxParts, long maxSerializedBytes) {
            this(maxParts, maxSerializedBytes, DEFAULT_MAX_AUXILIARY_ENTRIES,
                    DEFAULT_MAX_INPUT_BYTES);
        }

        CollectionBudget(int maxParts, long maxSerializedBytes,
                int maxAuxiliaryEntries) {
            this(maxParts, maxSerializedBytes, maxAuxiliaryEntries,
                    DEFAULT_MAX_INPUT_BYTES);
        }

        CollectionBudget(int maxParts, long maxSerializedBytes,
                int maxAuxiliaryEntries, long maxInputBytes) {
            if (maxParts < 0 || maxSerializedBytes < 0 ||
                    maxAuxiliaryEntries < 0 || maxInputBytes < 0) {
                throw new IllegalArgumentException("Collection limits must be non-negative");
            }
            this.maxParts = maxParts;
            this.maxSerializedBytes = maxSerializedBytes;
            this.maxAuxiliaryEntries = maxAuxiliaryEntries;
            this.maxInputBytes = maxInputBytes;
        }

        private boolean tryStartPart(String id) {
            if (limitReached) {
                return false;
            }
            long remaining = maxSerializedBytes - retainedBytes;
            long idCharge = retainedEntryCharge(id, "", remaining);
            if (retainedParts >= maxParts ||
                    idCharge > remaining) {
                limitReached = true;
                return false;
            }
            retainedBytes += idCharge;
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

        boolean tryRetainAuxiliaryEntry(String key, String value) {
            long remaining = maxSerializedBytes - retainedBytes;
            long charge = retainedEntryCharge(key, value, remaining);
            if (limitReached ||
                    retainedAuxiliaryEntries >= maxAuxiliaryEntries ||
                    charge > remaining) {
                limitReached = true;
                return false;
            }
            retainedAuxiliaryEntries++;
            retainedBytes += charge;
            return true;
        }

        private static long retainedEntryCharge(
                String key, String value, long remaining) {
            long chars = (long) key.length() + value.length();
            if (remaining < RETAINED_ENTRY_OVERHEAD_BYTES ||
                    chars > (remaining - RETAINED_ENTRY_OVERHEAD_BYTES) / 2) {
                return remaining + 1;
            }
            return RETAINED_ENTRY_OVERHEAD_BYTES + chars * 2;
        }

        private long getRemainingBytes() {
            return maxSerializedBytes - retainedBytes;
        }

        private void markLimitReached() {
            limitReached = true;
        }

        InputStream limitInput(InputStream stream) {
            return new BudgetedInputStream(stream);
        }

        boolean isInputLimitReached() {
            return inputLimitReached;
        }

        boolean isLimitReached() {
            return limitReached;
        }

        private void consumeInput(long bytes) {
            consumedInputBytes += bytes;
        }

        private void markInputLimitReached() {
            inputLimitReached = true;
            limitReached = true;
        }

        private final class BudgetedInputStream extends InputStream {

            private final InputStream stream;

            private BudgetedInputStream(InputStream stream) {
                this.stream = stream;
            }

            @Override
            public int read() throws IOException {
                if (consumedInputBytes >= maxInputBytes) {
                    return probeForOverflow();
                }
                int value = stream.read();
                if (value >= 0) {
                    consumeInput(1);
                }
                return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int length)
                    throws IOException {
                if (length == 0) {
                    return 0;
                }
                long remaining = maxInputBytes - consumedInputBytes;
                if (remaining <= 0) {
                    return probeForOverflow();
                }
                int read = stream.read(bytes, offset,
                        (int) Math.min(length, remaining));
                if (read > 0) {
                    consumeInput(read);
                }
                return read;
            }

            @Override
            public long skip(long length) throws IOException {
                if (length <= 0) {
                    return 0;
                }
                long remaining = maxInputBytes - consumedInputBytes;
                if (remaining <= 0) {
                    probeForOverflow();
                    return 0;
                }
                long skipped = stream.skip(Math.min(length, remaining));
                if (skipped > 0) {
                    consumeInput(skipped);
                }
                return skipped;
            }

            @Override
            public void close() throws IOException {
                stream.close();
            }

            private int probeForOverflow() throws IOException {
                int value = stream.read();
                if (value >= 0) {
                    markInputLimitReached();
                }
                return -1;
            }
        }
    }

    private record NamespaceMapping(String prefix, String uri) {
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
