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

import java.io.Serializable;

import org.apache.commons.io.FilenameUtils;

import org.apache.tika.utils.StringUtils;

/**
 * Configuration for {@link WorksTextExtractor}, which spawns the
 * {@code wps2text} CLI from libwps to extract body text from legacy
 * Microsoft Works {@code .wps} files.
 *
 * <p>Disabled by default. Enable by setting an instance in {@code ParseContext}
 * with {@code setEnabled(true)} and (optionally) a custom binary path.</p>
 */
public class WorksConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private boolean enabled = false;
    private String wpsPath = "";
    private int timeoutSeconds = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public WorksConfig setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /** Path to {@code wps2text} (or empty to look up on PATH). */
    public String getWpsPath() {
        return wpsPath;
    }

    public WorksConfig setWpsPath(String wpsPath) {
        if (StringUtils.isBlank(wpsPath)) {
            this.wpsPath = "";
            return this;
        }
        try {
            wpsPath = FilenameUtils.normalize(wpsPath);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "wpsPath must be a normalizable executable path", e);
        }
        if (wpsPath == null) {
            throw new IllegalArgumentException(
                    "wpsPath must be a normalizable executable path");
        }
        this.wpsPath = wpsPath;
        return this;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public WorksConfig setTimeoutSeconds(int timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be > 0");
        }
        this.timeoutSeconds = timeoutSeconds;
        return this;
    }
}
