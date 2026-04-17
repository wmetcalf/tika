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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;

public class ZXingCPPScannerTest {

    @Test
    public void buildsJsonCommandWithConfiguredExecutablePathAndFormats() {
        ZXingCPPConfig config = new ZXingCPPConfig();
        config.setZxingPath("target/zxing-cpp/bin/ZXingReader");
        config.setFormats("QRCode,Code128");
        Path imagePath = Paths.get("target/test-data/code.png");

        List<String> command = new ZXingCPPScanner().buildCommand(imagePath, config);

        assertEquals(config.getZxingPath(), command.get(0));
        assertEquals("-json", command.get(1));
        assertEquals("-formats", command.get(2));
        assertEquals("QRCode,Code128", command.get(3));
        assertEquals(imagePath.toAbsolutePath().toString(), command.get(4));
    }

    @Test
    public void buildsJsonCommandWithDefaultExecutableWhenPathBlank() {
        ZXingCPPConfig config = new ZXingCPPConfig();
        Path imagePath = Paths.get("target/test-data/code.png");

        List<String> command = new ZXingCPPScanner().buildCommand(imagePath, config);

        assertEquals(System.getProperty("os.name").startsWith("Windows") ?
                "ZXingReader.exe" : "ZXingReader", command.get(0));
        assertEquals("-json", command.get(1));
        assertEquals(imagePath.toAbsolutePath().toString(), command.get(2));
    }

    @Test
    public void parsesSingleResultJsonIntoRecord() {
        String output = "{\"FilePath\":\"/tmp/code.png\",\"Text\":\"hello world\"," +
                "\"Format\":\"QR Code\",\"Bytes\":\"68656c6c6f20776f726c64\"," +
                "\"ECLevel\":\"M\",\"IsMirrored\":false," +
                "\"Position\":\"10x10 20x10 20x20 10x20\"}\n";

        List<ZXingCPPScanner.Result> results = ZXingCPPScanner.parseOutput(output);

        assertEquals(1, results.size());
        assertEquals("/tmp/code.png", results.get(0).getFilePath());
        assertEquals("hello world", results.get(0).getText());
        assertEquals("qr_code", results.get(0).getFormat());
        assertEquals("68656c6c6f20776f726c64", results.get(0).getRawBytes());
        assertEquals("M", results.get(0).getErrorCorrectionLevel());
        assertFalse(results.get(0).isMirrored());
        assertEquals("10x10 20x10 20x20 10x20", results.get(0).getPosition());
    }

    @Test
    public void parsesMultipleResultsJsonIntoRecords() {
        String output = "{\"FilePath\":\"/tmp/qr.png\",\"Text\":\"alpha\",\"Format\":\"QR Code\"}\n" +
                "{\"FilePath\":\"/tmp/code128.png\",\"Text\":\"1234567890\"," +
                "\"Format\":\"Code 128\",\"IsMirrored\":true}\n";

        List<ZXingCPPScanner.Result> results = ZXingCPPScanner.parseOutput(output);

        assertEquals(2, results.size());
        assertEquals("/tmp/qr.png", results.get(0).getFilePath());
        assertEquals("alpha", results.get(0).getText());
        assertEquals("qr_code", results.get(0).getFormat());
        assertEquals("/tmp/code128.png", results.get(1).getFilePath());
        assertEquals("1234567890", results.get(1).getText());
        assertEquals("code_128", results.get(1).getFormat());
        assertTrue(results.get(1).isMirrored());
    }

    @Test
    public void parsesEscapedJsonStringsIntoRecord() {
        String output = "{\"FilePath\":\"/tmp/code.png\",\"Text\":\"hello \\\"qr\\\" \\\\ " +
                "\\u263A\",\"Format\":\"QR Code\"}\n";

        List<ZXingCPPScanner.Result> results = ZXingCPPScanner.parseOutput(output);

        assertEquals(1, results.size());
        assertEquals("hello \"qr\" \\ \u263A", results.get(0).getText());
    }

    @Test
    public void malformedOutputLineThrowsExplicitException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ZXingCPPScanner.parseOutput("not json\n"));

        assertTrue(exception.getMessage().contains("Expected JSON object"));
    }

    @Test
    public void noResultOutputReturnsEmptyList() {
        assertTrue(ZXingCPPScanner.parseOutput("").isEmpty());
        assertTrue(ZXingCPPScanner.parseOutput("\n").isEmpty());
    }
}
