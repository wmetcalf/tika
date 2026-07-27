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
package org.apache.tika.parser.microsoft;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.MetadataRecord;
import org.apache.tika.metadata.Office;
import org.apache.tika.metadata.TikaCoreProperties;

/**
 * Writes aligned multi-value Office link metadata records.
 */
public final class OfficeLinkMetadataUtil {

    private static final int MAX_LINKS = 4_096;
    private static final int MAX_LINK_TYPE_CHARS = 1_024;
    private static final int MAX_LINK_RECORD_INPUT_CHARS = 64 * 1_024;
    private static final int MAX_TOTAL_LINK_RECORD_CHARS = 1_024 * 1_024;
    private static final String LINK_LIMIT_WARNING =
            "Office link metadata limit reached; additional links were skipped";
    private static final String LINK_VALUE_TRUNCATED_WARNING =
            "Office link metadata value truncated; retained link was bounded";

    private OfficeLinkMetadataUtil() {
    }

    public static void addLink(Metadata metadata, String type, String url, String text,
                               String ocrText, String source, String context,
                               String relationshipType, String id) {
        addLink(metadata, type, url, text, ocrText, source, context, relationshipType, id, "",
                "");
    }

    public static void addLink(Metadata metadata, String type, String url, String text,
                               String ocrText, String source, String context,
                               String relationshipType, String id, String trigger,
                               String actionType) {
        if (metadata == null || isBlank(type) || isBlank(url)) {
            return;
        }
        long retainedRecordChars = getLinkRecordChars(metadata);
        if (getLinkCount(metadata) >= MAX_LINKS
                || retainedRecordChars >= MAX_TOTAL_LINK_RECORD_CHARS) {
            markLinkLimitReached(metadata);
            return;
        }

        LinkValueBudget budget = new LinkValueBudget();
        String boundedType = budget.take(type, MAX_LINK_TYPE_CHARS);
        String boundedUrl = budget.take(url);
        String boundedText = budget.take(text);
        String boundedOcrText = budget.take(ocrText);
        String boundedSource = budget.take(source);
        String boundedContext = budget.take(context);
        String boundedRelationshipType = budget.take(relationshipType);
        String boundedId = budget.take(id);
        String boundedTrigger = budget.take(trigger);
        String boundedActionType = budget.take(actionType);
        String record = MetadataRecord.encode(
                "type", boundedType,
                "url", boundedUrl,
                "text", boundedText,
                "ocrText", boundedOcrText,
                "source", boundedSource,
                "context", boundedContext,
                "relationshipType", boundedRelationshipType,
                "id", boundedId,
                "trigger", boundedTrigger,
                "actionType", boundedActionType);
        if (retainedRecordChars + record.length()
                > MAX_TOTAL_LINK_RECORD_CHARS) {
            markLinkLimitReached(metadata);
            return;
        }

        metadata.add(Office.OFFICE_LINK_URL, boundedUrl);
        metadata.add(Office.OFFICE_LINK_TYPE, boundedType);
        metadata.add(Office.OFFICE_LINK_TEXT, boundedText);
        metadata.add(Office.OFFICE_LINK_OCR_TEXT, boundedOcrText);
        metadata.add(Office.OFFICE_LINK_SOURCE, boundedSource);
        metadata.add(Office.OFFICE_LINK_CONTEXT, boundedContext);
        metadata.add(Office.OFFICE_LINK_RELATIONSHIP_TYPE, boundedRelationshipType);
        metadata.add(Office.OFFICE_LINK_ID, boundedId);
        metadata.add(Office.OFFICE_LINK_TRIGGER, boundedTrigger);
        metadata.add(Office.OFFICE_LINK_ACTION_TYPE, boundedActionType);
        metadata.add(Office.OFFICE_LINK_RECORD, record);
        if (budget.isTruncated()) {
            markLinkValueTruncated(metadata);
        }
    }

    private static int getLinkCount(Metadata metadata) {
        int count = metadata.getValues(Office.OFFICE_LINK_RECORD).length;
        count = Math.max(count, metadata.getValues(Office.OFFICE_LINK_URL).length);
        count = Math.max(count, metadata.getValues(Office.OFFICE_LINK_TYPE).length);
        count = Math.max(count, metadata.getValues(Office.OFFICE_LINK_TEXT).length);
        count = Math.max(count, metadata.getValues(Office.OFFICE_LINK_OCR_TEXT).length);
        count = Math.max(count, metadata.getValues(Office.OFFICE_LINK_SOURCE).length);
        count = Math.max(count, metadata.getValues(Office.OFFICE_LINK_CONTEXT).length);
        count = Math.max(count,
                metadata.getValues(Office.OFFICE_LINK_RELATIONSHIP_TYPE).length);
        count = Math.max(count, metadata.getValues(Office.OFFICE_LINK_ID).length);
        count = Math.max(count, metadata.getValues(Office.OFFICE_LINK_TRIGGER).length);
        return Math.max(count,
                metadata.getValues(Office.OFFICE_LINK_ACTION_TYPE).length);
    }

    private static long getLinkRecordChars(Metadata metadata) {
        long chars = 0;
        for (String record : metadata.getValues(Office.OFFICE_LINK_RECORD)) {
            chars += record.length();
        }
        return chars;
    }

    private static boolean hasWarning(Metadata metadata, String expected) {
        for (String warning :
                metadata.getValues(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING)) {
            if (expected.equals(warning)) {
                return true;
            }
        }
        return false;
    }

    public static void markLinkLimitReached(Metadata metadata) {
        if (metadata == null) {
            return;
        }
        metadata.set(TikaCoreProperties.TRUNCATED_METADATA, true);
        if (!hasWarning(metadata, LINK_LIMIT_WARNING)) {
            metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                    LINK_LIMIT_WARNING);
        }
        markLinkAnalysisIncomplete(metadata);
    }

    public static void markLinkValueTruncated(Metadata metadata) {
        if (metadata == null) {
            return;
        }
        metadata.set(TikaCoreProperties.TRUNCATED_METADATA, true);
        if (!hasWarning(metadata, LINK_VALUE_TRUNCATED_WARNING)) {
            metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                    LINK_VALUE_TRUNCATED_WARNING);
        }
        markLinkAnalysisIncomplete(metadata);
    }

    private static void markLinkAnalysisIncomplete(Metadata metadata) {
        if (metadata.get("ExploitClass") == null) {
            metadata.set("ExploitClass",
                    "Office link extraction incomplete; executable references "
                            + "may be hidden");
        }
    }

    private static final class LinkValueBudget {

        private int remaining = MAX_LINK_RECORD_INPUT_CHARS;
        private boolean truncated;

        private String take(String value) {
            return take(value, MAX_LINK_RECORD_INPUT_CHARS);
        }

        private String take(String value, int maxValueChars) {
            String normalized = safe(value);
            int retained = Math.min(normalized.length(),
                    Math.min(maxValueChars, remaining));
            if (retained < normalized.length()) {
                truncated = true;
            }
            remaining -= retained;
            return retained == normalized.length()
                    ? normalized : normalized.substring(0, retained);
        }

        private boolean isTruncated() {
            return truncated;
        }
    }

    public static String normalizeType(String rawType) {
        if (rawType == null) {
            return "external_relationship";
        }
        switch (rawType) {
            case "attachedTemplate":
                return "attached_template";
            case "subDocument":
                return "subdocument";
            case "externalOleObject":
                return "external_ole_object";
            case "oleLink":
                return "linked_ole_object";
            case "externalWorkbook":
            case "externalLink":
                return "external_workbook";
            case "ddeLink":
                return "dde_link";
            case "dbConnection":
                return "db_connection";
            case "webQuery":
                return "web_query";
            case "olapConnection":
                return "olap_connection";
            case "textFileImport":
                return "text_file_import";
            case "hlinkHover":
                return "hover_hyperlink";
            case "vml-shape-href":
                return "vml_hyperlink";
            case "hlinkClick":
            case "field_hyperlink":
            case "hyperlink":
                return "hyperlink";
            default:
                return "external_relationship";
        }
    }

    public static String normalizeTrigger(String rawType) {
        if (rawType == null) {
            return "";
        }
        switch (rawType) {
            case "hlinkHover":
                return "hover";
            case "hlinkClick":
            case "hyperlink":
            case "field_hyperlink":
            case "vml-shape-href":
                return "click";
            default:
                return "";
        }
    }

    public static String normalizeActionType(String rawType, String url) {
        if (isBlank(url)) {
            return "";
        }
        if (url.startsWith("#")) {
            return "internal_anchor";
        }
        switch (rawType) {
            case "hlinkHover":
            case "hlinkClick":
            case "hyperlink":
            case "field_hyperlink":
            case "vml-shape-href":
                return "external_url";
            default:
                return "external_url";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
