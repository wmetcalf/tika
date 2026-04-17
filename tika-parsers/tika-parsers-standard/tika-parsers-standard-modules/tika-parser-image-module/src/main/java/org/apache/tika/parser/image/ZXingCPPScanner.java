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
import java.util.Locale;
import java.util.Map;

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
        return hasZXingCPP(defaultConfig);
    }

    public List<Result> scan(Path imagePath, ZXingCPPConfig config, ParseContext context) {
        ZXingCPPConfig activeConfig = config == null ? defaultConfig : config;
        if (!activeConfig.isEnabled() || imagePath == null) {
            return Collections.emptyList();
        }

        ProcessBuilder processBuilder = new ProcessBuilder(buildCommand(imagePath, activeConfig));
        try {
            FileProcessResult processResult = execute(processBuilder,
                    activeConfig.getTimeoutSeconds() * 1000L);
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
            String format = normalizeFormat(requireField(json, "Format"));
            if (StringUtils.isBlank(format)) {
                throw new IllegalArgumentException("Expected non-blank Format in ZXingReader -json " +
                        "output line: " + line.trim());
            }
            results.add(new Result(json.get("FilePath"), json.get("Text"), format,
                    json.get("Bytes"), json.get("Position"), json.get("ECLevel"),
                    parseBooleanField(json, "IsMirrored")));
        }
        return results;
    }

    private static String getExecutable(ZXingCPPConfig config) {
        if (!StringUtils.isBlank(config.getZxingPath())) {
            return config.getZxingPath();
        }
        return getZXingCPPProgram();
    }

    private static boolean hasZXingCPP(ZXingCPPConfig config) {
        return ProcessUtils.checkCommand(new String[]{getExecutable(config), "-version"});
    }

    FileProcessResult execute(ProcessBuilder processBuilder, long timeoutMillis) throws IOException {
        return ProcessUtils.execute(processBuilder, timeoutMillis, MAX_STDIO, MAX_STDIO);
    }

    private static String getZXingCPPProgram() {
        return System.getProperty("os.name").startsWith("Windows") ?
                "ZXingReader.exe" : "ZXingReader";
    }

    private static String normalizeFormat(String format) {
        if (StringUtils.isBlank(format)) {
            return "";
        }
        String normalized = format.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("_+", "_");
        normalized = normalized.replaceAll("^_", "");
        normalized = normalized.replaceAll("_$", "");
        return normalized;
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
        String value = json.get(fieldName);
        if (value == null) {
            return false;
        }
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
            skipWhitespace(line, index);
            expect(line, index, ':');
            skipWhitespace(line, index);
            values.put(key, parseJsonValue(line, index));
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
        return values;
    }

    private static String parseJsonValue(String line, int[] index) {
        char ch = line.charAt(index[0]);
        if (ch == '"') {
            return parseJsonString(line, index);
        }

        int start = index[0];
        while (index[0] < line.length()) {
            ch = line.charAt(index[0]);
            if (ch == ',' || ch == '}') {
                break;
            }
            index[0]++;
        }
        String value = line.substring(start, index[0]).trim();
        if ("null".equals(value)) {
            return null;
        }
        return value;
    }

    private static String parseJsonString(String line, int[] index) {
        expect(line, index, '"');
        StringBuilder sb = new StringBuilder();
        while (index[0] < line.length()) {
            char ch = line.charAt(index[0]++);
            if (ch == '"') {
                return sb.toString();
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
