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

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.apache.tika.parser.ParseContext;
import org.apache.tika.utils.FileProcessResult;

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
    public void scanParsesResultsFromActualCommandPath() {
        ZXingCPPConfig config = new ZXingCPPConfig();
        config.setEnabled(true);
        config.setZxingPath("/opt/zxing/ZXingReader");
        config.setFormats("QRCode");
        Path imagePath = Paths.get("target/test-data/code.png");

        StubScanner scanner = new StubScanner(successResult(
                "{\"FilePath\":\"/tmp/code.png\",\"Text\":\"hello\",\"Format\":\"QR Code\"}\n"));

        List<ZXingCPPScanner.Result> results = scanner.scan(imagePath, config, new ParseContext());

        assertEquals(1, results.size());
        assertEquals("/opt/zxing/ZXingReader", scanner.lastCommand.get(0));
        assertEquals("-json", scanner.lastCommand.get(1));
        assertEquals("-formats", scanner.lastCommand.get(2));
        assertEquals("QRCode", scanner.lastCommand.get(3));
        assertEquals(imagePath.toAbsolutePath().toString(), scanner.lastCommand.get(4));
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
    public void scanReturnsEmptyListWhenCommandFindsNoBarcode() {
        ZXingCPPConfig config = new ZXingCPPConfig();
        config.setEnabled(true);

        List<ZXingCPPScanner.Result> results = new StubScanner(successResult(""))
                .scan(Paths.get("target/test-data/code.png"), config, new ParseContext());

        assertTrue(results.isEmpty());
    }

    @Test
    public void parsesOfficialJsonLinesOutput() {
        String output = "{\"FilePath\":\"/tmp/one.png\",\"Text\":\"one\",\"Format\":\"QR Code\"}\n" +
                "{\"FilePath\":\"/tmp/two.png\",\"Text\":\"two\",\"Format\":\"Data Matrix\"}\n";

        List<ZXingCPPScanner.Result> results = ZXingCPPScanner.parseOutput(output);

        assertEquals(2, results.size());
        assertEquals("one", results.get(0).getText());
        assertEquals("two", results.get(1).getText());
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
    public void malformedLineMissingFormatIsRejectedAsProtocolFailure() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ZXingCPPScanner.parseOutput("{\"FilePath\":\"/tmp/code.png\"," +
                        "\"Text\":\"hello\"}\n"));

        assertTrue(exception.getMessage().contains("Format"));
        assertTrue(exception.getMessage().contains("ZXingReader -json"));
    }

    @Test
    public void jsonArrayOutputIsRejectedAsNotJsonLines() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ZXingCPPScanner.parseOutput("[{\"FilePath\":\"/tmp/code.png\"," +
                        "\"Text\":\"hello\",\"Format\":\"QR Code\"}]"));

        assertTrue(exception.getMessage().contains("ZXingReader -json"));
        assertTrue(exception.getMessage().contains("line-delimited JSON objects"));
    }

    @Test
    public void malformedOutputLineThrowsExplicitException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ZXingCPPScanner.parseOutput("not json\n"));

        assertTrue(exception.getMessage().contains("ZXingReader -json"));
    }

    @Test
    public void scanThrowsWhenCommandOutputViolatesJsonLinesContract() {
        ZXingCPPConfig config = new ZXingCPPConfig();
        config.setEnabled(true);

        ZXingCPPScanner.ScanException exception = assertThrows(ZXingCPPScanner.ScanException.class,
                () -> new StubScanner(successResult("[{\"Text\":\"hello\",\"Format\":\"QR Code\"}]"))
                        .scan(Paths.get("target/test-data/code.png"), config, new ParseContext()));

        assertTrue(exception.getMessage().contains("ZXingReader -json"));
    }

    @Test
    public void scanWrapsInvalidUnicodeEscapeAsProtocolFailure() {
        ZXingCPPConfig config = new ZXingCPPConfig();
        config.setEnabled(true);

        ZXingCPPScanner.ScanException exception = assertThrows(ZXingCPPScanner.ScanException.class,
                () -> new StubScanner(successResult("{\"FilePath\":\"/tmp/code.png\"," +
                        "\"Text\":\"bad \\uZZZZ\",\"Format\":\"QR Code\"}\n"))
                        .scan(Paths.get("target/test-data/code.png"), config, new ParseContext()));

        assertTrue(exception.getMessage().contains("ZXingReader -json"));
    }

    @Test
    public void disabledConfigSkipsScan() {
        ZXingCPPConfig config = new ZXingCPPConfig();
        StubScanner scanner = new StubScanner(successResult(
                "{\"FilePath\":\"/tmp/code.png\",\"Text\":\"hello\",\"Format\":\"QR Code\"}\n"));

        List<ZXingCPPScanner.Result> results =
                scanner.scan(Paths.get("target/test-data/code.png"), config, new ParseContext());

        assertTrue(results.isEmpty());
        assertTrue(scanner.lastCommand == null);
    }

    @Test
    public void scanThrowsWhenCommandExecutionFails() {
        ZXingCPPConfig config = new ZXingCPPConfig();
        config.setEnabled(true);

        ZXingCPPScanner.ScanException exception = assertThrows(ZXingCPPScanner.ScanException.class,
                () -> new StubScanner(new IOException("boom"))
                        .scan(Paths.get("target/test-data/code.png"), config, new ParseContext()));

        assertTrue(exception.getMessage().contains("Unable to execute zxing-cpp scan"));
    }

    private static FileProcessResult successResult(String stdout) {
        FileProcessResult result = new FileProcessResult();
        result.setExitValue(0);
        result.setStdout(stdout);
        return result;
    }

    private static class StubScanner extends ZXingCPPScanner {
        private final FileProcessResult result;
        private final IOException exception;
        private List<String> lastCommand;

        private StubScanner(FileProcessResult result) {
            this.result = result;
            this.exception = null;
        }

        private StubScanner(IOException exception) {
            this.result = null;
            this.exception = exception;
        }

        @Override
        FileProcessResult execute(ProcessBuilder processBuilder, long timeoutMillis)
                throws IOException {
            this.lastCommand = processBuilder.command();
            if (exception != null) {
                throw exception;
            }
            return result;
        }
    }
}
