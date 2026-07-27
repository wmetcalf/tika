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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;

public class UnicodeQRContentHandlerTest {

    @Test
    public void ordinarySpacesDoNotTriggerQrProbe() throws Exception {
        Metadata metadata = new Metadata();
        UnicodeQRContentHandler handler = new UnicodeQRContentHandler(
                null, metadata, null, null, new ParseContext());
        char[] text = " ".repeat(100).toCharArray();

        handler.characters(text, 0, text.length);
        handler.endDocument();

        assertNull(metadata.get("unicode_qr:glyph_count"));
    }

    @Test
    public void sextantSignalsAreCountedAcrossCharacterCallbacks() throws Exception {
        Metadata metadata = new Metadata();
        UnicodeQRContentHandler handler = new UnicodeQRContentHandler(
                null, metadata, null, null, new ParseContext());
        char[] text = new String(Character.toChars(0x1FB01))
                .repeat(100).toCharArray();

        handler.characters(text, 0, 1);
        handler.characters(text, 1, text.length - 1);
        handler.endDocument();

        assertEquals("100", metadata.get("unicode_qr:glyph_count"));
    }

    @Test
    public void truncatedPrefixWithoutQrGlyphsIsSecurityVisible() throws Exception {
        Metadata metadata = new Metadata();
        UnicodeQRContentHandler handler = new UnicodeQRContentHandler(
                null, metadata, null, null, new ParseContext());
        char[] text = new char[2 * 1024 * 1024 + 1];
        Arrays.fill(text, 'A');

        handler.characters(text, 0, text.length);
        handler.endDocument();

        assertTrue(Arrays.stream(metadata.getValues(
                        TikaCoreProperties.TIKA_META_EXCEPTION_WARNING))
                .anyMatch(value -> value.contains("Unicode QR analysis limit")));
        assertNotNull(metadata.get("ExploitClass"));
    }

    @Test
    public void scannerSecurityExceptionPropagates() throws Exception {
        ZXingCPPConfig config = new ZXingCPPConfig();
        config.setEnabled(true);
        UnicodeQRContentHandler handler = new UnicodeQRContentHandler(
                null, new Metadata(), new SecurityExceptionScanner(),
                config, new ParseContext());
        char[] text = "████████\n".repeat(13).toCharArray();
        handler.characters(text, 0, text.length);

        assertThrows(SecurityException.class, handler::endDocument);
    }

    private static final class SecurityExceptionScanner extends ZXingCPPScanner {
        @Override
        boolean hasZXingCPP(ZXingCPPConfig config) {
            return true;
        }

        @Override
        public List<Result> scan(
                Path imagePath, ZXingCPPConfig config, ParseContext context) {
            throw new SecurityException("simulated scanner policy rejection");
        }
    }
}
