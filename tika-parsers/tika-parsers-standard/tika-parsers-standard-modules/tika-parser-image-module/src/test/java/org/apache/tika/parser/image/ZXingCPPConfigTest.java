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
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.apache.commons.io.FilenameUtils;
import org.junit.jupiter.api.Test;

public class ZXingCPPConfigTest {

    @Test
    public void normalizesFullExecutablePath() throws Exception {
        ZXingCPPConfig config = new ZXingCPPConfig();
        String input = "target/zxing-cpp/bin/ZXingReader";
        config.setZxingPath(input);

        assertEquals(FilenameUtils.normalize(input),
                config.getZxingPath());
    }

    @Test
    public void rejectsNonPositiveTimeoutSeconds() {
        ZXingCPPConfig config = new ZXingCPPConfig();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> config.setTimeoutSeconds(0));

        assertEquals("timeoutSeconds must be > 0", exception.getMessage());
    }
}
