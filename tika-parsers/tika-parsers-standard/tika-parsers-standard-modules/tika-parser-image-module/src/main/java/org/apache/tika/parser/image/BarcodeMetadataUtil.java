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
package org.apache.tika.parser.image;

import java.util.Locale;

import org.apache.tika.metadata.Barcode;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.MetadataRecord;
import org.apache.tika.metadata.TikaCoreProperties;

/**
 * Writes decoded barcode results to canonical records and compatibility fields.
 */
public final class BarcodeMetadataUtil {

    private static final int MAX_RESULTS = 256;
    private static final int MAX_RESULT_CHARS = 64 * 1024;
    private static final int MAX_TOTAL_RECORD_CHARS = 1024 * 1024;

    private BarcodeMetadataUtil() {
    }

    static void addResult(Metadata metadata, ZXingCPPScanner.Result result,
                          String defaultFormat) {
        if (metadata == null || result == null) {
            return;
        }
        if (Boolean.parseBoolean(metadata.get(Barcode.BARCODE_LIMIT_REACHED))) {
            return;
        }
        String value = safe(result.getText());
        String format = normalizeFormat(result.getFormat(), defaultFormat);
        String rawBytes = safe(result.getRawBytes());
        String position = safe(result.getPosition());
        String errorCorrectionLevel = safe(result.getErrorCorrectionLevel());
        String mirrored = Boolean.toString(result.isMirrored());
        String record = MetadataRecord.encode(
                "value", value,
                "format", format,
                "rawBytes", rawBytes,
                "position", position,
                "errorCorrectionLevel", errorCorrectionLevel,
                "mirrored", mirrored);

        String[] existingRecords = metadata.getValues(Barcode.BARCODE_RECORD);
        int retainedChars = 0;
        for (String existing : existingRecords) {
            retainedChars += existing.length();
        }
        if (existingRecords.length >= MAX_RESULTS
                || record.length() > MAX_RESULT_CHARS
                || retainedChars > MAX_TOTAL_RECORD_CHARS - record.length()) {
            metadata.set(Barcode.BARCODE_LIMIT_REACHED, true);
            metadata.set(TikaCoreProperties.TRUNCATED_METADATA, true);
            metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                    "Barcode metadata retention limit reached; additional "
                            + "barcode results were skipped");
            if (metadata.get("ExploitClass") == null) {
                metadata.set("ExploitClass",
                        "Barcode metadata retention limit incomplete; encoded "
                                + "content may not have been analyzed");
            }
            return;
        }

        metadata.add(Barcode.BARCODE_VALUE, value);
        metadata.add(Barcode.BARCODE_FORMAT, format);
        metadata.add(Barcode.BARCODE_RAW_BYTES, rawBytes);
        metadata.add(Barcode.BARCODE_POSITION, position);
        metadata.add(Barcode.BARCODE_ERROR_CORRECTION_LEVEL, errorCorrectionLevel);
        metadata.add(Barcode.BARCODE_IS_MIRRORED, mirrored);
        metadata.add(Barcode.BARCODE_RECORD, record);
    }

    public static void markAnalysisIncomplete(Metadata metadata, String analysis,
                                              Throwable failure) {
        if (metadata == null) {
            return;
        }
        String failureType = failure == null
                ? "unknown failure" : failure.getClass().getSimpleName();
        metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                analysis + " failed: " + failureType);
        if (metadata.get("ExploitClass") == null) {
            metadata.set("ExploitClass",
                    analysis + " incomplete; encoded content may not have been analyzed");
        }
    }

    private static String normalizeFormat(String format, String defaultFormat) {
        if (format == null) {
            return safe(defaultFormat);
        }
        String normalized = format.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isEmpty() ? safe(defaultFormat) : normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
