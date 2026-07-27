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
package org.apache.tika.metadata;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import org.apache.tika.metadata.writefilter.MetadataWriteLimiter;

public class MetadataWriteLimiterCompatibilityTest {

    @Test
    public void testDefaultRemoveDoesNotPassNullToLegacyLimiter() {
        MetadataWriteLimiter nullRejectingLimiter = new MetadataWriteLimiter() {
            @Override
            public void add(String field, String value, Map<String, String[]> data) {
                Objects.requireNonNull(value);
                data.put(field, new String[]{value});
            }

            @Override
            public void set(String field, String value, Map<String, String[]> data) {
                Objects.requireNonNull(value);
                data.put(field, new String[]{value});
            }
        };
        Metadata metadata = new Metadata(nullRejectingLimiter);
        metadata.set("custom:key", "value");

        assertDoesNotThrow(() -> metadata.remove("custom:key"));
        assertNull(metadata.get("custom:key"));
    }

    @Test
    public void testLegacySerializedMetadataRestoresAcceptAllLimiter() throws Exception {
        Metadata metadata = new Metadata();
        metadata.set("before", "value");
        Field limiter = Metadata.class.getDeclaredField("writeLimiter");
        limiter.setAccessible(true);
        limiter.set(metadata, null);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(metadata);
        }
        Metadata restored;
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (Metadata) input.readObject();
        }

        assertDoesNotThrow(() -> restored.set("after", "value"));
        assertEquals("value", restored.get("after"));
    }

    @Test
    public void testSetAllUsesWriteLimiterAndReservedKeyGuard() {
        MetadataWriteLimiter selectiveLimiter = new MetadataWriteLimiter() {
            @Override
            public void add(String field, String value, Map<String, String[]> data) {
                set(field, value, data);
            }

            @Override
            public void set(String field, String value, Map<String, String[]> data) {
                if (!"blocked".equals(field)) {
                    data.put(field, new String[]{value});
                }
            }
        };
        Metadata metadata = new Metadata(selectiveLimiter);
        Properties properties = new Properties();
        properties.setProperty("allowed", "retained");
        properties.setProperty("blocked", "must-not-bypass-limiter");
        properties.setProperty(
                TikaCoreProperties.TIKA_META_PREFIX + "forged",
                "must-not-bypass-reserved-key-guard");

        metadata.setAll(properties);

        assertEquals("retained", metadata.get("allowed"));
        assertNull(metadata.get("blocked"));
        assertNull(metadata.get(TikaCoreProperties.TIKA_META_PREFIX + "forged"));
    }
}
