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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UnicodeQRExtractorTest {

    @Test
    void ordinarySpacingDoesNotBecomeQrSignal() {
        String prose = String.join("\n",
                "ordinary prose has many spaces between its harmless words",
                "another ordinary sentence has enough spaces to pass density",
                "this line is text and not a rendered quick response code",
                "spaces must remain renderable without becoming dark signal",
                "the detector should reject this block before running zxing");

        assertEquals(0, UnicodeQRExtractor.countQrGlyphs(prose));
        assertTrue(UnicodeQRExtractor.findClusters(prose).isEmpty());
    }

    @Test
    void lightModulesRemainRenderableButDoNotCountAsSignal() {
        assertTrue(UnicodeQRExtractor.isBlockQrGlyph(' '));
        assertTrue(UnicodeQRExtractor.isBlockQrGlyph('\u00a0'));
        assertEquals(0, UnicodeQRExtractor.countQrGlyphs(" \u00a0"));
        assertEquals(1, UnicodeQRExtractor.countQrGlyphs("█"));
    }

    @Test
    void propertyLabelIsStrippedBeforeLeadingLightModule() {
        String art = String.join("\n",
                "Description: □██████",
                "□██████",
                "□██████",
                "□██████",
                "□██████");

        UnicodeQRExtractor.Cluster cluster =
                UnicodeQRExtractor.findClusters(art).get(0);

        assertEquals("□██████", cluster.lines[0]);
    }
}
