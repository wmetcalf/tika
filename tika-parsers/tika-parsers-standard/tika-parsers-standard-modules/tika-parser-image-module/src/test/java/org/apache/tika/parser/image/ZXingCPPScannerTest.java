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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import org.apache.tika.config.TimeoutLimits;
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
        // ZXingCPPConfig.setZxingPath runs FilenameUtils.normalize, which is OS-dependent
        // ('/' becomes '\\' on Windows). Assert against what the config actually resolved to
        // rather than a POSIX literal, or this passes only on POSIX runners.
        assertEquals(config.getZxingPath(), scanner.lastCommand.get(0));
        assertEquals("-json", scanner.lastCommand.get(1));
        assertEquals("-formats", scanner.lastCommand.get(2));
        assertEquals("QRCode", scanner.lastCommand.get(3));
        assertEquals(imagePath.toAbsolutePath().toString(), scanner.lastCommand.get(4));
    }

    @Test
    public void scanUsesRawConfiguredExecutablePathWhenItContainsSpaces() {
        ZXingCPPConfig config = new ZXingCPPConfig();
        config.setEnabled(true);
        config.setZxingPath("C:\\Program Files\\ZXing\\ZXingReader.exe");
        Path imagePath = Paths.get("target/test-data/code.png");

        StubScanner scanner = new StubScanner(successResult(
                "{\"FilePath\":\"/tmp/code.png\",\"Text\":\"hello\",\"Format\":\"QR Code\"}\n"),
                true);

        scanner.scan(imagePath, config, new ParseContext());

        assertEquals(config.getZxingPath(), scanner.lastCommand.get(0));
    }

    @Test
    public void hasZXingCPPUsesRawConfiguredExecutablePathWhenItContainsSpaces() {
        ZXingCPPConfig config = new ZXingCPPConfig();
        config.setZxingPath("C:\\Program Files\\ZXing\\ZXingReader.exe");
        ProbeScanner scanner = new ProbeScanner(config, true, true);

        assertTrue(scanner.hasZXingCPP());
        assertEquals(config.getZxingPath(), scanner.lastProbeCommand[0]);
        assertEquals("-version", scanner.lastProbeCommand[1]);
    }

    @Test
    public void scanUsesRawImagePathWhenItContainsSpaces() {
        ZXingCPPConfig config = new ZXingCPPConfig();
        config.setEnabled(true);
        Path imagePath = Paths.get("target/test data/code image.png");

        StubScanner scanner = new StubScanner(successResult(
                "{\"FilePath\":\"/tmp/code.png\",\"Text\":\"hello\",\"Format\":\"QR Code\"}\n"),
                true);

        scanner.scan(imagePath, config, new ParseContext());

        assertEquals(imagePath.toAbsolutePath().toString(),
                scanner.lastCommand.get(scanner.lastCommand.size() - 1));
    }

    @Test
    public void scanHonorsParseContextProcessTimeout() {
        ZXingCPPConfig config = new ZXingCPPConfig();
        config.setEnabled(true);
        ParseContext context = new ParseContext();
        context.set(TimeoutLimits.class, new TimeoutLimits(10_000, 1_234));
        StubScanner scanner = new StubScanner(successResult(""));

        scanner.scan(Paths.get("target/test-data/code.png"), config, context);

        assertEquals(1_134, scanner.lastTimeoutMillis);
    }

    @Test
    public void sharedBudgetCapsAttemptsAndAggregateTimeWithoutSleeping() {
        ZXingCPPConfig config = new ZXingCPPConfig();
        config.setEnabled(true);
        AtomicLong now = new AtomicLong();
        ZXingCPPScanner.ScanBudget budget =
                new ZXingCPPScanner.ScanBudget(2, 1_000, now::get);
        StubScanner scanner = new StubScanner(successResult(""));
        Path image = Paths.get("target/test-data/code.png");

        scanner.scan(image, config, new ParseContext(), budget);
        assertEquals(1_000, scanner.lastTimeoutMillis);

        now.set(400_000_000L);
        scanner.scan(image, config, new ParseContext(), budget);
        assertEquals(600, scanner.lastTimeoutMillis);

        assertThrows(ZXingCPPScanner.ScanBudgetExceededException.class,
                () -> scanner.scan(image, config, new ParseContext(), budget));
        assertTrue(budget.hasRejectedScan());
    }

    @Test
    public void zeroDurationBudgetRejectsBeforeProcessExecution() {
        ZXingCPPConfig config = new ZXingCPPConfig();
        config.setEnabled(true);
        AtomicLong now = new AtomicLong();
        ZXingCPPScanner.ScanBudget budget =
                new ZXingCPPScanner.ScanBudget(1, 0, now::get);
        StubScanner scanner = new StubScanner(successResult(""));

        assertThrows(ZXingCPPScanner.ScanBudgetExceededException.class,
                () -> scanner.scan(Paths.get("target/test-data/code.png"),
                        config, new ParseContext(), budget));

        assertNull(scanner.lastCommand);
        assertTrue(budget.hasRejectedScan());
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
        assertEquals("QR Code", results.get(0).getFormat());
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
        assertEquals("QR Code", results.get(0).getFormat());
        assertEquals("/tmp/code128.png", results.get(1).getFilePath());
        assertEquals("1234567890", results.get(1).getText());
        assertEquals("Code 128", results.get(1).getFormat());
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
    public void escapedControlCharacterInTextIsPreserved() {
        String output = "{\"FilePath\":\"/tmp/code.png\",\"Text\":\"hello\\u001dworld\"," +
                "\"Format\":\"QR Code\"}\n";

        List<ZXingCPPScanner.Result> results = ZXingCPPScanner.parseOutput(output);

        assertEquals("hello\u001dworld", results.get(0).getText());
    }

    @Test
    public void escapedControlCharacterInStructuralFieldIsRejectedAsProtocolFailure() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ZXingCPPScanner.parseOutput("{\"FilePath\":\"/tmp/code.png\"," +
                        "\"Text\":\"hello\",\"Format\":\"QR\\u001dCode\"}\n"));

        assertTrue(exception.getMessage().contains("Format"));
        assertTrue(exception.getMessage().contains("control character"));
        assertTrue(exception.getMessage().contains("ZXingReader -json"));
    }

    @Test
    public void unescapedControlCharacterInStringIsRejectedAsProtocolFailure() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ZXingCPPScanner.parseOutput("{\"FilePath\":\"/tmp/code.png\"," +
                        "\"Text\":\"hello\u0001world\",\"Format\":\"QR Code\"}\n"));

        assertTrue(exception.getMessage().contains("control character"));
        assertTrue(exception.getMessage().contains("ZXingReader -json"));
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
    public void malformedFieldSeparatorsAreRejected() {
        IllegalArgumentException missingComma = assertThrows(IllegalArgumentException.class,
                () -> ZXingCPPScanner.parseOutput("{\"FilePath\":\"/tmp/code.png\"" +
                        "\"Text\":\"hello\",\"Format\":\"QR Code\"}\n"));

        IllegalArgumentException repeatedComma = assertThrows(IllegalArgumentException.class,
                () -> ZXingCPPScanner.parseOutput("{\"FilePath\":\"/tmp/code.png\",," +
                        "\"Text\":\"hello\",\"Format\":\"QR Code\"}\n"));

        assertTrue(missingComma.getMessage().contains("ZXingReader -json"));
        assertTrue(repeatedComma.getMessage().contains("ZXingReader -json"));
    }

    @Test
    public void duplicateJsonKeysAreRejectedAsProtocolFailure() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ZXingCPPScanner.parseOutput("{\"FilePath\":\"/tmp/code.png\"," +
                        "\"Format\":\"QR Code\",\"Format\":\"Data Matrix\"}\n"));

        assertTrue(exception.getMessage().contains("Format"));
        assertTrue(exception.getMessage().contains("ZXingReader -json"));
    }

    @Test
    public void invalidBooleanLiteralIsRejectedAsProtocolFailure() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ZXingCPPScanner.parseOutput("{\"FilePath\":\"/tmp/code.png\"," +
                        "\"Text\":\"hello\",\"Format\":\"QR Code\",\"IsMirrored\":truthy}\n"));

        assertTrue(exception.getMessage().contains("IsMirrored"));
        assertTrue(exception.getMessage().contains("ZXingReader -json"));
    }

    @Test
    public void booleanLiteralInFormatIsRejectedAsProtocolFailure() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ZXingCPPScanner.parseOutput("{\"FilePath\":\"/tmp/code.png\"," +
                        "\"Text\":\"hello\",\"Format\":true}\n"));

        assertTrue(exception.getMessage().contains("Format"));
        assertTrue(exception.getMessage().contains("ZXingReader -json"));
    }

    @Test
    public void booleanLiteralInTextIsRejectedAsProtocolFailure() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ZXingCPPScanner.parseOutput("{\"FilePath\":\"/tmp/code.png\"," +
                        "\"Text\":false,\"Format\":\"QR Code\"}\n"));

        assertTrue(exception.getMessage().contains("Text"));
        assertTrue(exception.getMessage().contains("ZXingReader -json"));
    }

    @Test
    public void unrecognizedFieldsWithBooleansOrNumbersAreSuccessfullyParsedAndIgnored() {
        String output = "{\"FilePath\":\"/tmp/code.png\"," +
                "\"Text\":\"hello\",\"Format\":\"QR Code\"," +
                "\"UnknownBool\":true,\"UnknownNum\":42.5e-3}\n";

        List<ZXingCPPScanner.Result> results = ZXingCPPScanner.parseOutput(output);
        assertEquals(1, results.size());
        assertEquals("hello", results.get(0).getText());
        assertEquals("QR Code", results.get(0).getFormat());
    }

    @Test
    public void numericLiteralInKnownStringFieldIsRejectedAsProtocolFailure() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ZXingCPPScanner.parseOutput("{\"FilePath\":\"/tmp/code.png\"," +
                        "\"Text\":\"hello\",\"Format\":12.34}\n"));

        assertTrue(exception.getMessage().contains("Format"));
        assertTrue(exception.getMessage().contains("ZXingReader -json"));
    }

    @Test
    public void explicitNullBooleanIsRejectedAsProtocolFailure() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ZXingCPPScanner.parseOutput("{\"FilePath\":\"/tmp/code.png\"," +
                        "\"Text\":\"hello\",\"Format\":\"QR Code\",\"IsMirrored\":null}\n"));

        assertTrue(exception.getMessage().contains("IsMirrored"));
        assertTrue(exception.getMessage().contains("ZXingReader -json"));
    }

    @Test
    public void explicitNullStringFieldIsRejectedAsProtocolFailure() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ZXingCPPScanner.parseOutput("{\"FilePath\":null," +
                        "\"Text\":\"hello\",\"Format\":\"QR Code\"}\n"));

        assertTrue(exception.getMessage().contains("FilePath"));
        assertTrue(exception.getMessage().contains("ZXingReader -json"));
    }

    @Test
    public void bareTokenStringValueIsRejectedAsProtocolFailure() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ZXingCPPScanner.parseOutput("{\"FilePath\":\"/tmp/code.png\"," +
                        "\"Text\":hello,\"Format\":\"QR Code\"}\n"));

        assertTrue(exception.getMessage().contains("Text"));
        assertTrue(exception.getMessage().contains("ZXingReader -json"));
    }

    @Test
    public void trailingGarbageAfterObjectIsRejected() {
        IllegalArgumentException trailingGarbage = assertThrows(IllegalArgumentException.class,
                () -> ZXingCPPScanner.parseOutput("{\"FilePath\":\"/tmp/code.png\"," +
                        "\"Text\":\"hello\",\"Format\":\"QR Code\"} trailing\n"));

        IllegalArgumentException extraBrace = assertThrows(IllegalArgumentException.class,
                () -> ZXingCPPScanner.parseOutput("{\"FilePath\":\"/tmp/code.png\"," +
                        "\"Text\":\"hello\",\"Format\":\"QR Code\"}}\n"));

        assertTrue(trailingGarbage.getMessage().contains("ZXingReader -json"));
        assertTrue(extraBrace.getMessage().contains("ZXingReader -json"));
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

    @Test
    public void scanThrowsWhenCommandTimesOut() {
        ZXingCPPConfig config = new ZXingCPPConfig();
        config.setEnabled(true);
        FileProcessResult result = successResult("");
        result.setTimeout(true);

        ZXingCPPScanner.ScanException exception = assertThrows(ZXingCPPScanner.ScanException.class,
                () -> new StubScanner(result)
                        .scan(Paths.get("target/test-data/code.png"), config, new ParseContext()));

        assertTrue(exception.getMessage().contains("Timed out running zxing-cpp"));
    }

    @Test
    public void scanThrowsWhenCommandExitsNonZero() {
        ZXingCPPConfig config = new ZXingCPPConfig();
        config.setEnabled(true);
        FileProcessResult result = new FileProcessResult();
        result.setExitValue(3);
        result.setStderr("bad input");

        ZXingCPPScanner.ScanException exception = assertThrows(ZXingCPPScanner.ScanException.class,
                () -> new StubScanner(result)
                        .scan(Paths.get("target/test-data/code.png"), config, new ParseContext()));

        assertTrue(exception.getMessage().contains("zxing-cpp exited with 3"));
        assertTrue(exception.getMessage().contains("bad input"));
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
        private long lastTimeoutMillis;

        private StubScanner(FileProcessResult result) {
            this(result, false);
        }

        private StubScanner(FileProcessResult result, boolean windows) {
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
            this.lastTimeoutMillis = timeoutMillis;
            if (exception != null) {
                throw exception;
            }
            return result;
        }


    }

    private static class ProbeScanner extends ZXingCPPScanner {
        private final boolean available;
        private String[] lastProbeCommand;

        private ProbeScanner(ZXingCPPConfig config, boolean available, boolean windows) {
            super(config);
            this.available = available;
        }

        @Override
        boolean checkCommand(String[] command) {
            this.lastProbeCommand = command;
            return available;
        }


    }
}
