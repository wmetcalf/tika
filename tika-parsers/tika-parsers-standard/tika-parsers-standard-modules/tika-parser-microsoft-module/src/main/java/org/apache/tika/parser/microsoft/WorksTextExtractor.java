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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import org.apache.tika.io.TikaInputStream;
import org.apache.tika.utils.FileProcessResult;
import org.apache.tika.utils.ProcessUtils;
import org.apache.tika.utils.StringUtils;

/**
 * Spawns the {@code wps2text} CLI from {@code libwps-tools} to extract body
 * text from legacy Microsoft Works {@code .wps} documents. POI does not
 * provide a Works text extractor; this is the equivalent of how Tika already
 * shells out to ZXing-CPP, Tesseract, etc. for tasks the JVM library doesn't
 * cover natively.
 *
 * <p>Disabled by default. Enable by setting {@link WorksConfig} in the
 * {@code ParseContext} with {@code setEnabled(true)} (or specify a binary
 * path via {@code setWpsPath}). When disabled or when {@code wps2text} is not
 * present on PATH, the extractor returns an empty list and {@code
 * OfficeParser} silently falls back to metadata-only extraction.</p>
 */
public class WorksTextExtractor {

    private static final int MAX_STDIO = 8 * 1024 * 1024;

    private final WorksConfig config;

    public WorksTextExtractor() {
        this(new WorksConfig());
    }

    public WorksTextExtractor(WorksConfig config) {
        this.config = config == null ? new WorksConfig() : config;
    }

    /** Returns true when {@code wps2text} is reachable at the configured (or
     *  default) path. */
    public boolean hasWps2Text() {
        return ProcessUtils.checkCommand(new String[]{getExecutable(), "--version"});
    }

    /**
     * Extracts plain-text content from a Works {@code .wps} stream by spawning
     * {@code wps2text}. Returns each non-empty line as a list element; an
     * empty list indicates either the extractor was disabled, the binary was
     * missing, the process failed, or the document had no text.
     */
    public List<String> extract(TikaInputStream stream) throws IOException {
        if (!config.isEnabled()) {
            return new ArrayList<>();
        }
        // wps2text reads only from a file path, so we need a backing file.
        Path tmp = null;
        boolean weCreatedTmp = false;
        try {
            if (stream.hasFile()) {
                tmp = stream.getPath();
            } else {
                tmp = Files.createTempFile("tika-works-", ".wps");
                weCreatedTmp = true;
                Files.copy(stream, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            return runWps2Text(tmp);
        } finally {
            if (weCreatedTmp && tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best-effort
                }
            }
        }
    }

    List<String> runWps2Text(Path path) throws IOException {
        if (!hasWps2Text()) {
            return new ArrayList<>();
        }
        ProcessBuilder pb = new ProcessBuilder(
                getExecutable(), path.toAbsolutePath().toString());
        FileProcessResult result;
        try {
            result = ProcessUtils.execute(pb,
                    config.getTimeoutSeconds() * 1000L, MAX_STDIO, MAX_STDIO);
        } catch (IOException e) {
            return new ArrayList<>();
        }
        if (result.isTimeout() || result.getExitValue() != 0) {
            return new ArrayList<>();
        }
        String stdout = result.getStdout();
        if (StringUtils.isBlank(stdout)) {
            return new ArrayList<>();
        }
        List<String> lines = new ArrayList<>();
        for (String line : stdout.split("\\r?\\n")) {
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private String getExecutable() {
        if (!StringUtils.isBlank(config.getWpsPath())) {
            return config.getWpsPath();
        }
        return "wps2text";
    }
}
