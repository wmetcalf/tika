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
package org.apache.tika.parser.xml;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class XMLParserSvgSecurityTest {

    @Test
    void testSvgNormalizationRemovesExternalRefsAcrossQuoteStyles() throws Exception {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\">" +
                "<defs><g id=\"local\"><rect width=\"10\" height=\"10\"/></g></defs>" +
                "<use href='#local'/>" +
                "<use href='http://example.com/external.svg#x'/>" +
                "<image href='data:image/png;base64,AAAA'/>" +
                "<image xlink:href=\"file:///etc/passwd\" xmlns:xlink=\"http://www.w3.org/1999/xlink\"/>" +
                "</svg>";
        Path input = Files.createTempFile("tika-svg-test-", ".svg");
        Files.write(input, svg.getBytes(StandardCharsets.UTF_8));
        Path normalized = XMLParser.normalizeSvgHrefs(input);
        String normalizedXml = Files.readString(normalized, StandardCharsets.UTF_8);

        assertTrue(normalizedXml.contains("#local"));
        assertFalse(normalizedXml.contains("http://example.com"));
        assertFalse(normalizedXml.contains("data:image/png"));
        assertFalse(normalizedXml.contains("file:///etc/passwd"));

        Files.deleteIfExists(input);
        Files.deleteIfExists(normalized);
    }
}
