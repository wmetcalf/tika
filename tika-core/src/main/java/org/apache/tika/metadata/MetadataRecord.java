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
package org.apache.tika.metadata;

import java.util.Map;
import java.util.Set;

/**
 * Encodes related metadata fields as one JSON object so their association survives
 * write-time filtering.
 */
public final class MetadataRecord {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final Map<String, Set<String>> COMPOSITE_FIELD_MEMBERS = Map.of(
            Office.OFFICE_LINK_RECORD.getName(), Set.of(
                    Office.OFFICE_LINK_URL.getName(),
                    Office.OFFICE_LINK_TYPE.getName(),
                    Office.OFFICE_LINK_TEXT.getName(),
                    Office.OFFICE_LINK_OCR_TEXT.getName(),
                    Office.OFFICE_LINK_SOURCE.getName(),
                    Office.OFFICE_LINK_CONTEXT.getName(),
                    Office.OFFICE_LINK_RELATIONSHIP_TYPE.getName(),
                    Office.OFFICE_LINK_ID.getName(),
                    Office.OFFICE_LINK_TRIGGER.getName(),
                    Office.OFFICE_LINK_ACTION_TYPE.getName()),
            Barcode.BARCODE_RECORD.getName(), Set.of(
                    Barcode.BARCODE_VALUE.getName(),
                    Barcode.BARCODE_FORMAT.getName(),
                    Barcode.BARCODE_RAW_BYTES.getName(),
                    Barcode.BARCODE_POSITION.getName(),
                    Barcode.BARCODE_ERROR_CORRECTION_LEVEL.getName(),
                    Barcode.BARCODE_IS_MIRRORED.getName()));

    private MetadataRecord() {
    }

    /**
     * Returns whether a composite record contains any of the supplied metadata fields.
     * Filtering code uses this to prevent excluded legacy fields from surviving inside
     * their canonical record representation.
     *
     * @param recordField composite record property name
     * @param fields candidate member property names
     * @return {@code true} when the record contains at least one candidate field
     */
    public static boolean containsAnyField(String recordField, Set<String> fields) {
        Set<String> members = COMPOSITE_FIELD_MEMBERS.get(recordField);
        return members != null && fields != null
                && members.stream().anyMatch(fields::contains);
    }

    /**
     * Encodes alternating field names and values as a compact JSON object.
     * Null values are represented as empty strings so every record has a stable schema.
     *
     * @param fields alternating field names and values
     * @return a compact JSON object
     */
    public static String encode(String... fields) {
        if (fields == null || fields.length == 0 || fields.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "fields must contain one or more name/value pairs");
        }
        StringBuilder json = new StringBuilder();
        json.append('{');
        for (int i = 0; i < fields.length; i += 2) {
            if (fields[i] == null) {
                throw new IllegalArgumentException("field names must not be null");
            }
            if (i > 0) {
                json.append(',');
            }
            appendQuoted(json, fields[i]);
            json.append(':');
            appendQuoted(json, fields[i + 1] == null ? "" : fields[i + 1]);
        }
        return json.append('}').toString();
    }

    private static void appendQuoted(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    json.append("\\\"");
                    break;
                case '\\':
                    json.append("\\\\");
                    break;
                case '\b':
                    json.append("\\b");
                    break;
                case '\f':
                    json.append("\\f");
                    break;
                case '\n':
                    json.append("\\n");
                    break;
                case '\r':
                    json.append("\\r");
                    break;
                case '\t':
                    json.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        json.append("\\u")
                                .append(HEX[(c >>> 12) & 0x0f])
                                .append(HEX[(c >>> 8) & 0x0f])
                                .append(HEX[(c >>> 4) & 0x0f])
                                .append(HEX[c & 0x0f]);
                    } else {
                        json.append(c);
                    }
                    break;
            }
        }
        json.append('"');
    }
}
