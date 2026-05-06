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

import java.io.Serializable;

import org.apache.commons.io.FilenameUtils;

import org.apache.tika.utils.StringUtils;

public class ZXingCPPConfig implements Serializable {

    private static final long serialVersionUID = 5075417278575120353L;

    private boolean enabled = false;
    private String zxingPath = "";
    private int timeoutSeconds = 60;
    private String formats = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getZxingPath() {
        return zxingPath;
    }

    public void setZxingPath(String zxingPath) {
        if (StringUtils.isBlank(zxingPath)) {
            this.zxingPath = "";
            return;
        }
        try {
            zxingPath = FilenameUtils.normalize(zxingPath);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "zxingPath must be a normalizable executable path", e);
        }
        if (zxingPath == null) {
            throw new IllegalArgumentException("zxingPath must be a normalizable executable path");
        }
        this.zxingPath = zxingPath;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be > 0");
        }
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getFormats() {
        return formats;
    }

    public void setFormats(String formats) {
        this.formats = formats == null ? "" : formats;
    }
}
