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

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.apache.tika.config.TimeoutLimits;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.utils.FileProcessResult;
import org.apache.tika.utils.ProcessUtils;
import org.apache.tika.utils.StringUtils;

public class ZXingCPPScanner {

    private static final int MAX_STDIO = 1024 * 1024;

    private final ZXingCPPConfig defaultConfig;

    public ZXingCPPScanner() {
        this(new ZXingCPPConfig());
    }

    public ZXingCPPScanner(ZXingCPPConfig defaultConfig) {
        this.defaultConfig = defaultConfig == null ? new ZXingCPPConfig() : defaultConfig;
    }

    public boolean hasZXingCPP() {
        return hasZXingCPPInternal(defaultConfig);
    }

    boolean hasZXingCPP(ZXingCPPConfig config) {
        return hasZXingCPPInternal(config == null ? defaultConfig : config);
    }

    public List<Result> scan(Path imagePath, ZXingCPPConfig config, ParseContext context) {
        return scanInternal(imagePath, config, context, null);
    }

    public List<Result> scan(Path imagePath, ZXingCPPConfig config, ParseContext context,
                             ScanBudget budget) {
        return scanInternal(imagePath, config, context, budget);
    }

    private List<Result> scanInternal(Path imagePath, ZXingCPPConfig config,
                                      ParseContext context, ScanBudget budget) {
        ZXingCPPConfig activeConfig = config == null ? defaultConfig : config;
        if (!activeConfig.isEnabled() || imagePath == null) {
            return Collections.emptyList();
        }

        ProcessBuilder processBuilder = new ProcessBuilder(buildCommand(imagePath, activeConfig));
        try {
            long timeoutMillis = TimeoutLimits.getProcessTimeoutMillis(
                    context, activeConfig.getTimeoutSeconds() * 1000L);
            if (budget != null) {
                timeoutMillis = budget.acquireTimeoutMillis(timeoutMillis);
            } else if (timeoutMillis <= 0) {
                throw new ScanBudgetExceededException(
                        "No time remains for zxing-cpp scan");
            }
            FileProcessResult processResult = execute(processBuilder, timeoutMillis);
            if (processResult.isTimeout()) {
                throw new ScanException("Timed out running zxing-cpp against " + imagePath);
            }
            if (processResult.getExitValue() != 0) {
                throw new ScanException("zxing-cpp exited with " + processResult.getExitValue() +
                        " for " + imagePath + ": " + processResult.getStderr());
            }
            try {
                return parseOutput(processResult.getStdout());
            } catch (RuntimeException e) {
                throw new ScanException("Invalid ZXingReader -json output contract", e);
            }
        } catch (IOException e) {
            throw new ScanException("Unable to execute zxing-cpp scan for " + imagePath, e);
        }
    }

    /**
     * Shared subprocess budget for callers that may render and scan several
     * barcode candidates from one document.
     */
    public static final class ScanBudget {

        private static final long NANOS_PER_MILLISECOND = 1_000_000L;

        private final int maxScans;
        private final long deadlineNanos;
        private final LongSupplier nanoTime;
        private int scans;
        private boolean rejectedScan;
        private ZXingCPPScanner availabilityScanner;
        private ZXingCPPConfig availabilityConfig;
        private Boolean scannerAvailable;

        public ScanBudget(int maxScans, long maxDurationMillis) {
            this(maxScans, maxDurationMillis, System::nanoTime);
        }

        ScanBudget(int maxScans, long maxDurationMillis, LongSupplier nanoTime) {
            if (maxScans <= 0) {
                throw new IllegalArgumentException("maxScans must be > 0");
            }
            if (maxDurationMillis < 0) {
                throw new IllegalArgumentException("maxDurationMillis must be >= 0");
            }
            this.maxScans = maxScans;
            this.nanoTime = nanoTime;
            long now = nanoTime.getAsLong();
            long durationNanos;
            try {
                durationNanos = Math.multiplyExact(
                        maxDurationMillis, NANOS_PER_MILLISECOND);
            } catch (ArithmeticException e) {
                durationNanos = Long.MAX_VALUE;
            }
            long deadline;
            try {
                deadline = Math.addExact(now, durationNanos);
            } catch (ArithmeticException e) {
                deadline = Long.MAX_VALUE;
            }
            this.deadlineNanos = deadline;
        }

        synchronized boolean isScannerAvailable(
                ZXingCPPScanner scanner, ZXingCPPConfig config) {
            if (scannerAvailable == null) {
                availabilityScanner = scanner;
                availabilityConfig = config;
                scannerAvailable = scanner.hasZXingCPP(config);
            } else if (availabilityScanner != scanner || availabilityConfig != config) {
                throw new IllegalArgumentException(
                        "ScanBudget cannot be shared across scanner configurations");
            }
            return scannerAvailable;
        }

        synchronized long acquireTimeoutMillis(long requestedTimeoutMillis) {
            long remainingNanos = deadlineNanos - nanoTime.getAsLong();
            if (scans >= maxScans || requestedTimeoutMillis <= 0 || remainingNanos <= 0) {
                rejectedScan = true;
                throw new ScanBudgetExceededException(
                        "Aggregate zxing-cpp scan budget exhausted");
            }
            scans++;
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
            if (remainingNanos % NANOS_PER_MILLISECOND != 0) {
                remainingMillis++;
            }
            return Math.min(requestedTimeoutMillis, remainingMillis);
        }

        public synchronized boolean hasRejectedScan() {
            return rejectedScan;
        }
    }

    List<String> buildCommand(Path imagePath, ZXingCPPConfig config) {
        List<String> command = new ArrayList<>();
        command.add(getExecutable(config));
        command.add("-json");
        if (!StringUtils.isBlank(config.getFormats())) {
            command.add("-formats");
            command.add(config.getFormats());
        }
        command.add(imagePath.toAbsolutePath().toString());
        return command;
    }

    /**
     * Parses the official {@code ZXingReader -json} output contract only.
     * The supported format is one JSON object per line (JSON Lines style),
     * not arbitrary JSON such as arrays or pretty-printed multi-line objects.
     */
    static List<Result> parseOutput(String output) {
        if (StringUtils.isBlank(output)) {
            return Collections.emptyList();
        }

        List<Result> results = new ArrayList<>();
        for (String line : output.split("\\r?\\n")) {
            if (StringUtils.isBlank(line)) {
                continue;
            }
            Map<String, String> json = parseJsonLine(line.trim());
            rejectExplicitNullFields(json);
            String format = requireField(json, "Format");
            results.add(new Result(json.get("FilePath"), json.get("Text"), format,
                    json.get("Bytes"), json.get("Position"), json.get("ECLevel"),
                    parseBooleanField(json, "IsMirrored")));
        }
        return results;
    }

    private String getExecutable(ZXingCPPConfig config) {
        if (!StringUtils.isBlank(config.getZxingPath())) {
            return config.getZxingPath();
        }
        return getZXingCPPProgram();
    }

    private boolean hasZXingCPPInternal(ZXingCPPConfig config) {
        return checkCommand(buildProbeCommand(config));
    }

    String[] buildProbeCommand(ZXingCPPConfig config) {
        return new String[]{getExecutable(config), "-version"};
    }

    boolean checkCommand(String[] command) {
        return ProcessUtils.checkCommand(command);
    }

    FileProcessResult execute(ProcessBuilder processBuilder, long timeoutMillis) throws IOException {
        return ProcessUtils.execute(processBuilder, timeoutMillis, MAX_STDIO, MAX_STDIO);
    }

    private static String getZXingCPPProgram() {
        return System.getProperty("os.name").startsWith("Windows") ?
                "ZXingReader.exe" : "ZXingReader";
    }


    private static String requireField(Map<String, String> json, String fieldName) {
        String value = json.get(fieldName);
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("Expected required field '" + fieldName +
                    "' in ZXingReader -json line-delimited output");
        }
        return value;
    }

    private static boolean parseBooleanField(Map<String, String> json, String fieldName) {
        if (!json.containsKey(fieldName)) {
            return false;
        }
        String value = json.get(fieldName);
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException("Expected boolean field '" + fieldName +
                "' in ZXingReader -json line-delimited output");
    }

    private static Map<String, String> parseJsonLine(String line) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!line.startsWith("{") || !line.endsWith("}")) {
            throw new IllegalArgumentException("Expected ZXingReader -json output as " +
                    "line-delimited JSON objects; got: " + line);
        }

        int[] index = new int[]{1};
        skipWhitespace(line, index);
        if (index[0] < line.length() && line.charAt(index[0]) == '}') {
            return values;
        }
        while (index[0] < line.length() - 1) {
            String key = parseJsonString(line, index);
            if (values.containsKey(key)) {
                throw new IllegalArgumentException("Duplicate field '" + key +
                        "' in ZXingReader -json line-delimited output");
            }
            skipWhitespace(line, index);
            expect(line, index, ':');
            skipWhitespace(line, index);
            String value = parseJsonValue(line, index, key);
            rejectDecodedControlCharacters(key, value);
            values.put(key, value);
            skipWhitespace(line, index);
            if (index[0] >= line.length() - 1) {
                break;
            }
            char separator = line.charAt(index[0]);
            if (separator == '}') {
                break;
            }
            if (separator != ',') {
                throw new IllegalArgumentException("Expected ',' between ZXingReader -json " +
                        "fields: " + line);
            }
            index[0]++;
            skipWhitespace(line, index);
            if (index[0] >= line.length() - 1 || line.charAt(index[0]) != '"') {
                throw new IllegalArgumentException("Expected field after ',' in ZXingReader " +
                        "-json output: " + line);
            }
        }
        expect(line, index, '}');
        skipWhitespace(line, index);
        if (index[0] != line.length()) {
            throw new IllegalArgumentException("Unexpected trailing content in ZXingReader " +
                    "-json output: " + line);
        }
        return values;
    }

    private static void rejectDecodedControlCharacters(String fieldName, String value) {
        if (value == null || !isStructuralStringField(fieldName)) {
            return;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) <= 0x1f) {
                throw new IllegalArgumentException("Unexpected decoded control character in field '" +
                        fieldName + "' in ZXingReader -json output");
            }
        }
    }

    private static void rejectExplicitNullFields(Map<String, String> json) {
        for (Map.Entry<String, String> entry : json.entrySet()) {
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("Expected non-null field '" + entry.getKey() +
                        "' in ZXingReader -json line-delimited output");
            }
        }
    }

    private static String parseJsonNumber(String line, int[] index, String fieldName) {
        if (isStringField(fieldName)) {
            throw new IllegalArgumentException("Expected JSON string for field '" +
                    fieldName + "' in ZXingReader -json output");
        }
        int start = index[0];
        int len = line.length();
        if (index[0] < len && line.charAt(index[0]) == '-') {
            index[0]++;
        }
        if (index[0] >= len || !Character.isDigit(line.charAt(index[0]))) {
            throw new IllegalArgumentException("Expected digit in JSON number near: " +
                    line.substring(start));
        }
        while (index[0] < len && Character.isDigit(line.charAt(index[0]))) {
            index[0]++;
        }
        if (index[0] < len && line.charAt(index[0]) == '.') {
            index[0]++;
            if (index[0] >= len || !Character.isDigit(line.charAt(index[0]))) {
                throw new IllegalArgumentException("Expected digit after '.' in JSON number near: " +
                        line.substring(start));
            }
            while (index[0] < len && Character.isDigit(line.charAt(index[0]))) {
                index[0]++;
            }
        }
        if (index[0] < len && (line.charAt(index[0]) == 'e' || line.charAt(index[0]) == 'E')) {
            index[0]++;
            if (index[0] < len && (line.charAt(index[0]) == '+' || line.charAt(index[0]) == '-')) {
                index[0]++;
            }
            if (index[0] >= len || !Character.isDigit(line.charAt(index[0]))) {
                throw new IllegalArgumentException("Expected digit in exponent of JSON number near: " +
                        line.substring(start));
            }
            while (index[0] < len && Character.isDigit(line.charAt(index[0]))) {
                index[0]++;
            }
        }
        return line.substring(start, index[0]);
    }

    private static boolean isStringField(String fieldName) {
        return "FilePath".equals(fieldName) || "Text".equals(fieldName) ||
                "Format".equals(fieldName) || "Bytes".equals(fieldName) ||
                "Position".equals(fieldName) || "ECLevel".equals(fieldName);
    }

    private static boolean isStructuralStringField(String fieldName) {
        return "FilePath".equals(fieldName) || "Format".equals(fieldName) ||
                "Position".equals(fieldName) || "ECLevel".equals(fieldName);
    }

    private static String parseJsonValue(String line, int[] index, String fieldName) {
        char ch = line.charAt(index[0]);
        if (ch == '"') {
            return parseJsonString(line, index);
        }
        if (ch == '-' || Character.isDigit(ch)) {
            return parseJsonNumber(line, index, fieldName);
        }

        if (startsWithLiteral(line, index[0], "true")) {
            if (!"IsMirrored".equals(fieldName) && isStringField(fieldName)) {
                throw new IllegalArgumentException("Expected JSON string for field '" +
                        fieldName + "' in ZXingReader -json output");
            }
            index[0] += 4;
            return "true";
        }
        if (startsWithLiteral(line, index[0], "false")) {
            if (!"IsMirrored".equals(fieldName) && isStringField(fieldName)) {
                throw new IllegalArgumentException("Expected JSON string for field '" +
                        fieldName + "' in ZXingReader -json output");
            }
            index[0] += 5;
            return "false";
        }
        if (startsWithLiteral(line, index[0], "null")) {
            index[0] += 4;
            return null;
        }
        throw new IllegalArgumentException("Expected JSON string, number, or literal for field '" +
                fieldName + "' in ZXingReader -json output near: " + line.substring(index[0]));
    }

    private static boolean startsWithLiteral(String line, int start, String literal) {
        if (!line.startsWith(literal, start)) {
            return false;
        }
        int end = start + literal.length();
        return end >= line.length() || line.charAt(end) == ',' || line.charAt(end) == '}' ||
                Character.isWhitespace(line.charAt(end));
    }

    private static String parseJsonString(String line, int[] index) {
        expect(line, index, '"');
        StringBuilder sb = new StringBuilder();
        while (index[0] < line.length()) {
            char ch = line.charAt(index[0]++);
            if (ch == '"') {
                return sb.toString();
            }
            if (ch <= 0x1f) {
                throw new IllegalArgumentException("Unexpected control character in " +
                        "ZXingReader -json string: " + line);
            }
            if (ch != '\\') {
                sb.append(ch);
                continue;
            }
            if (index[0] >= line.length()) {
                throw new IllegalArgumentException("Invalid escape sequence: " + line);
            }
            char escaped = line.charAt(index[0]++);
            switch (escaped) {
                case '"':
                case '\\':
                case '/':
                    sb.append(escaped);
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    if (index[0] + 4 > line.length()) {
                        throw new IllegalArgumentException("Invalid unicode escape: " + line);
                    }
                    try {
                        sb.append((char) Integer.parseInt(line.substring(index[0], index[0] + 4),
                                16));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Invalid unicode escape: " + line, e);
                    }
                    index[0] += 4;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown escape sequence: \\" + escaped);
            }
        }
        throw new IllegalArgumentException("Unterminated string: " + line);
    }

    private static void skipWhitespace(String line, int[] index) {
        while (index[0] < line.length() && Character.isWhitespace(line.charAt(index[0]))) {
            index[0]++;
        }
    }

    private static void expect(String line, int[] index, char expected) {
        if (index[0] >= line.length() || line.charAt(index[0]) != expected) {
            throw new IllegalArgumentException("Expected '" + expected + "' in: " + line);
        }
        index[0]++;
    }

    public static class ScanException extends RuntimeException {

        private static final long serialVersionUID = 1467080700112152670L;

        ScanException(String message) {
            super(message);
        }

        ScanException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class ScanBudgetExceededException extends ScanException {

        private static final long serialVersionUID = -2543313384952479514L;

        ScanBudgetExceededException(String message) {
            super(message);
        }
    }

    public static class Result implements Serializable {

        private static final long serialVersionUID = -4341859135942226401L;

        private final String filePath;
        private final String text;
        private final String format;
        private final String rawBytes;
        private final String position;
        private final String errorCorrectionLevel;
        private final boolean mirrored;

        Result(String filePath, String text, String format, String rawBytes, String position,
               String errorCorrectionLevel, boolean mirrored) {
            this.filePath = filePath;
            this.text = text;
            this.format = format;
            this.rawBytes = rawBytes;
            this.position = position;
            this.errorCorrectionLevel = errorCorrectionLevel;
            this.mirrored = mirrored;
        }

        public String getFilePath() {
            return filePath;
        }

        public String getText() {
            return text;
        }

        public String getFormat() {
            return format;
        }

        public String getRawBytes() {
            return rawBytes;
        }

        public String getPosition() {
            return position;
        }

        public String getErrorCorrectionLevel() {
            return errorCorrectionLevel;
        }

        public boolean isMirrored() {
            return mirrored;
        }
    }
}
