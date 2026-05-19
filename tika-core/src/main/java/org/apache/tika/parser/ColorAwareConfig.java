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
package org.apache.tika.parser;

import java.io.Serializable;

/**
 * Cross-parser opt-in for emitting text-run color information alongside
 * extracted text content.
 *
 * <p>By default, Tika strips visual styling during text extraction. When a
 * caller sets this config in the {@link ParseContext} with {@code
 * setEnabled(true)}, color-aware parsers (PDF, OOXML, RTF, HTML) wrap each
 * text run in an HTML {@code <span>} carrying {@code style="color:#xxx;
 * background-color:#yyy"} so downstream consumers — most notably the
 * CSS-colored QR detector in {@code tika-parser-html-module} — can see the
 * per-character coloring that distinguishes dark vs light QR modules.</p>
 *
 * <p>Two flags decide what's emitted:</p>
 * <ul>
 *   <li>{@link #isEnabled()} — master toggle. When false, all parsers
 *       behave exactly as before.</li>
 *   <li>{@link #isEmitForegroundOnly()} — when true, only {@code color:}
 *       is emitted; background colors are skipped. Saves output size in
 *       documents whose backgrounds are uniformly white but does miss
 *       background-encoded QR codes.</li>
 * </ul>
 *
 * <p>Tika's downstream consumers ignore the {@code <span>} wrapping when
 * they only care about the text content — the XHTML output is still
 * valid and the inner text is unchanged.</p>
 */
public class ColorAwareConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private boolean enabled = false;
    private boolean emitForegroundOnly = false;

    public boolean isEnabled() {
        return enabled;
    }

    public ColorAwareConfig setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public boolean isEmitForegroundOnly() {
        return emitForegroundOnly;
    }

    public ColorAwareConfig setEmitForegroundOnly(boolean emitForegroundOnly) {
        this.emitForegroundOnly = emitForegroundOnly;
        return this;
    }

    /**
     * Helper: format a CSS {@code style="..."} value for a foreground +
     * optional background color. Returns null when both colors are null
     * (caller can skip emitting the span entirely).
     *
     * <p>Colors are in {@code #RRGGBB} form; null means "use parent /
     * default". Always lower-cased so downstream parsers see stable output.</p>
     */
    public String formatStyle(String fgHex, String bgHex) {
        if (fgHex == null && (bgHex == null || emitForegroundOnly)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (fgHex != null) {
            sb.append("color:").append(fgHex);
        }
        if (bgHex != null && !emitForegroundOnly) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append("background-color:").append(bgHex);
        }
        return sb.toString();
    }
}
