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
package org.apache.tika.parser.ocr;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-job OCR result cache and feature-flag carrier, placed in ParseContext
 * before calling RecursiveParserWrapper. TesseractOCRParser reads this to:
 * - Skip re-running Tesseract on duplicate image bytes (keyed by SHA-256).
 * - Apply optional blank-image skip via phash/colorhash detection.
 * - Downscale images wider/taller than maxImageDim before OCR.
 */
public final class OcrResultCache {

    private static final int DEFAULT_MAX_ENTRIES = 1024;
    private static final long DEFAULT_MAX_RETAINED_TEXT_BYTES = 8L * 1024 * 1024;
    private static final int DEFAULT_MAX_ENTRY_TEXT_BYTES = 256 * 1024;

    private final ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();

    /** 0 = disabled; images larger than this are downscaled before OCR. */
    private final int maxImageDim;

    /** When true, images flagged blank by phash/colorhash skip Tesseract entirely. */
    private final boolean skipBlank;

    private final int maxEntries;
    private final long maxRetainedTextBytes;
    private final int maxEntryTextBytes;
    private long retainedTextBytes;

    public OcrResultCache(int maxImageDim, boolean skipBlank) {
        this(maxImageDim, skipBlank, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_RETAINED_TEXT_BYTES,
                DEFAULT_MAX_ENTRY_TEXT_BYTES);
    }

    public OcrResultCache(int maxImageDim, boolean skipBlank, int maxEntries,
                         long maxRetainedTextBytes, int maxEntryTextBytes) {
        if (maxEntries < 0 || maxRetainedTextBytes < 0 || maxEntryTextBytes < 0) {
            throw new IllegalArgumentException("OCR cache limits must not be negative");
        }
        this.maxImageDim = maxImageDim;
        this.skipBlank = skipBlank;
        this.maxEntries = maxEntries;
        this.maxRetainedTextBytes = maxRetainedTextBytes;
        this.maxEntryTextBytes = maxEntryTextBytes;
    }

    public int getMaxImageDim() {
        return maxImageDim;
    }

    public boolean isSkipBlank() {
        return skipBlank;
    }

    public int getMaxEntryTextBytes() {
        return maxEntryTextBytes;
    }

    public boolean contains(String sha256Hex) {
        return map.containsKey(sha256Hex);
    }

    public String get(String sha256Hex) {
        return map.get(sha256Hex);
    }

    public void put(String sha256Hex, String text) {
        putIfWithinBudget(sha256Hex, text);
    }

    /**
     * Retains an OCR result only when the entry count, individual result, and aggregate text
     * budgets allow it. Existing entries are left untouched when a replacement would exceed a
     * budget.
     *
     * @return {@code true} if the result was retained
     */
    public synchronized boolean putIfWithinBudget(String sha256Hex, String text) {
        Objects.requireNonNull(sha256Hex, "sha256Hex");
        Objects.requireNonNull(text, "text");

        long textBytes = utf8LengthAtMost(text, maxEntryTextBytes);
        if (textBytes > maxEntryTextBytes) {
            return false;
        }

        String previous = map.get(sha256Hex);
        if (previous == null && map.size() >= maxEntries) {
            return false;
        }

        long previousBytes = previous == null ? 0 : utf8LengthAtMost(previous, Integer.MAX_VALUE);
        long retainedWithoutPrevious = retainedTextBytes - previousBytes;
        if (textBytes > maxRetainedTextBytes - retainedWithoutPrevious) {
            return false;
        }

        map.put(sha256Hex, text);
        retainedTextBytes = retainedWithoutPrevious + textBytes;
        return true;
    }

    private static long utf8LengthAtMost(String value, long limit) {
        long length = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x7f) {
                length++;
            } else if (c <= 0x7ff) {
                length += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                length += 4;
                i++;
            } else if (Character.isSurrogate(c)) {
                length++;
            } else {
                length += 3;
            }
            if (length > limit) {
                return length;
            }
        }
        return length;
    }
}
