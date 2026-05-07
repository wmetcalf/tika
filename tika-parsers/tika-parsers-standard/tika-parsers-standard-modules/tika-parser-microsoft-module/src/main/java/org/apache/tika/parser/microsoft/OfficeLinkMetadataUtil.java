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
import org.apache.tika.metadata.Office;

/**
 * Writes aligned multi-value Office link metadata records.
 */
public final class OfficeLinkMetadataUtil {

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
        metadata.add(Office.OFFICE_LINK_TYPE, type);
        metadata.add(Office.OFFICE_LINK_URL, url);
        metadata.add(Office.OFFICE_LINK_TEXT, safe(text));
        metadata.add(Office.OFFICE_LINK_OCR_TEXT, safe(ocrText));
        metadata.add(Office.OFFICE_LINK_SOURCE, safe(source));
        metadata.add(Office.OFFICE_LINK_CONTEXT, safe(context));
        metadata.add(Office.OFFICE_LINK_RELATIONSHIP_TYPE, safe(relationshipType));
        metadata.add(Office.OFFICE_LINK_ID, safe(id));
        metadata.add(Office.OFFICE_LINK_TRIGGER, safe(trigger));
        metadata.add(Office.OFFICE_LINK_ACTION_TYPE, safe(actionType));
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
