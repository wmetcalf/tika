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
package org.apache.tika.parser.pdf;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import org.apache.tika.exception.TikaConfigException;

public class AbstractPDF2XHTMLFailureTest {

    @Test
    public void testConfigurationFailureDetectedInCauseGraph() {
        IOException failure =
                new IOException("wrapped",
                        new TikaConfigException("invalid runtime config"));

        assertTrue(AbstractPDF2XHTML.isConfigurationFailure(failure));
    }

    @Test
    public void testSecurityFailureDetectedInSuppressedGraph() {
        IOException failure = new IOException("wrapped");
        failure.addSuppressed(new SecurityException("blocked trusted setter"));

        assertTrue(AbstractPDF2XHTML.isConfigurationFailure(failure));
    }

    @Test
    public void testUnrelatedCyclicFailureIsNotConfigurationFailure() {
        IOException first = new IOException("first");
        IOException second = new IOException("second", first);
        first.initCause(second);

        assertFalse(AbstractPDF2XHTML.isConfigurationFailure(first));
    }
}
