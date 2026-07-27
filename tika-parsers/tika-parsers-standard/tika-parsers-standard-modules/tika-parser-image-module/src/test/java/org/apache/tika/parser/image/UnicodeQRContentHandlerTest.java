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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;

public class UnicodeQRContentHandlerTest {

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
}
