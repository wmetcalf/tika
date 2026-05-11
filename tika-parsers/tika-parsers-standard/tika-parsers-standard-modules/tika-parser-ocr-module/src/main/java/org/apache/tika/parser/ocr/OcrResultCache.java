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

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-job OCR result cache and feature-flag carrier, placed in ParseContext
 * before calling RecursiveParserWrapper. TesseractOCRParser reads this to:
 * - Skip re-running Tesseract on duplicate image bytes (keyed by SHA-256).
 * - Apply optional blank-image skip via phash/colorhash detection.
 * - Downscale images wider/taller than maxImageDim before OCR.
 */
public final class OcrResultCache {

    private final ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();

    /** 0 = disabled; images larger than this are downscaled before OCR. */
    private final int maxImageDim;

    /** When true, images flagged blank by phash/colorhash skip Tesseract entirely. */
    private final boolean skipBlank;

    public OcrResultCache(int maxImageDim, boolean skipBlank) {
        this.maxImageDim = maxImageDim;
        this.skipBlank = skipBlank;
    }

    public int getMaxImageDim() {
        return maxImageDim;
    }

    public boolean isSkipBlank() {
        return skipBlank;
    }

    public boolean contains(String sha256Hex) {
        return map.containsKey(sha256Hex);
    }

    public String get(String sha256Hex) {
        return map.get(sha256Hex);
    }

    public void put(String sha256Hex, String text) {
        map.put(sha256Hex, text);
    }
}
