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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.List;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;

/**
 * POSITIVE CONTROL for {@link VbaFormBuilder}. Every other form test is meaningless unless the
 * parser can read back what this writes: a test that only ever asserts "no controls found" cannot
 * tell a traversal that never arrived from a form that held nothing.
 */
public class VbaFormBuilderTest {

    @Test
    void testParserReadsBackTheControlProperties() throws Exception {
        byte[] poifs = new VbaFormBuilder()
                .control("CommandButton1", "http://evil.example/a.exe", "click me")
                .poifs("UserForm1");

        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(poifs))) {
            List<VbaFormParser.FormModuleResult> forms =
                    VbaFormParser.extractFormVariables(fs);
            assertEquals(1, forms.size(), "one form expected");
            assertEquals("UserForm1", forms.get(0).moduleName);
            assertEquals(1, forms.get(0).controls.size(),
                    "one control expected; got " + forms.get(0).controls);

            VbaFormParser.FormControl c = forms.get(0).controls.get(0);
            assertEquals("CommandButton1", c.name);
            assertEquals("http://evil.example/a.exe", c.tag,
                    "the Tag property is a standard place to hide a URL; it must be recovered");
            assertEquals("click me", c.controlTipText);
            assertTrue(c.hasPayloadFields());
            assertTrue(forms.get(0).toText().contains("evil.example"),
                    "the emitted text must carry the payload: " + forms.get(0).toText());
        }
    }

    @Test
    void testSeveralControlsInOneForm() throws Exception {
        byte[] poifs = new VbaFormBuilder()
                .control("A", "tag-a", "tip-a")
                .control("B", "http://second.example/x", "tip-b")
                .control("C", "tag-c", "tip-c")
                .poifs("Macros/UserForm1");

        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(poifs))) {
            List<VbaFormParser.FormModuleResult> forms = VbaFormParser.extractFormVariables(fs);
            assertEquals(1, forms.size());
            assertEquals(3, forms.get(0).controls.size(),
                    "all three controls expected; got " + forms.get(0).controls);
            String text = forms.get(0).toText();
            assertTrue(text.contains("second.example"),
                    "a payload on the SECOND control must not be dropped: " + text);
        }
    }
}
