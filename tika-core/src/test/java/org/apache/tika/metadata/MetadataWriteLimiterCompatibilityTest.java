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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import org.apache.tika.metadata.writefilter.StandardMetadataLimiterFactory;

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
    public void testFirstMultiValuePropertyAddUsesLegacySetDispatch() {
        MetadataWriteLimiter dispatchTrackingLimiter = new MetadataWriteLimiter() {
            @Override
            public void add(String field, String value, Map<String, String[]> data) {
                String[] existing = data.get(field);
                String[] updated = new String[existing.length + 1];
                System.arraycopy(existing, 0, updated, 0, existing.length);
                updated[existing.length] = "add:" + value;
                data.put(field, updated);
            }

            @Override
            public void set(String field, String value, Map<String, String[]> data) {
                data.put(field, new String[]{"set:" + value});
            }
        };
        Metadata metadata = new Metadata(dispatchTrackingLimiter);

        metadata.add(Office.OFFICE_LINK_URL, "first");
        metadata.add(Office.OFFICE_LINK_URL, "second");

        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new String[]{"set:first", "add:second"},
                metadata.getValues(Office.OFFICE_LINK_URL));
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

    @Test
    public void testAlignedGroupReservationDoesNotDependOnFieldOrder() {
        StandardMetadataLimiterFactory factory =
                new StandardMetadataLimiterFactory();
        factory.setMaxKeySize(100);
        factory.setMaxFieldSize(100);
        factory.setMaxTotalBytes(100);
        factory.setMaxValuesPerField(10);
        Metadata metadata = new Metadata(factory.newInstance());

        // Metadata merge paths iterate HashMap-backed fields in no guaranteed
        // order. A non-boundary member must not consume budget before the
        // limiter has reserved every key in the compatibility record.
        metadata.add(Office.OFFICE_LINK_TYPE, "ole");
        metadata.add(Office.OFFICE_LINK_URL,
                "https://example.invalid/payload");

        assertEquals(0, metadata.getValues(Office.OFFICE_LINK_TYPE).length);
        assertEquals(0, metadata.getValues(Office.OFFICE_LINK_URL).length);
    }

    @Test
    public void testAlignedRemovalBackfillDoesNotDependOnReplacementOrder() {
        StandardMetadataLimiterFactory factory =
                new StandardMetadataLimiterFactory();
        factory.setMaxKeySize(100);
        factory.setMaxFieldSize(10_000);
        factory.setMaxTotalBytes(10_000);
        factory.setMaxValuesPerField(10);
        Metadata metadata = new Metadata(factory.newInstance());

        metadata.add(Office.OFFICE_LINK_URL, "https://example.invalid/first");
        metadata.add(Office.OFFICE_LINK_TYPE, "ole");
        metadata.remove(Office.OFFICE_LINK_TYPE.getName());

        // HashMap-backed merge paths may present a sibling before the URL
        // boundary for the next logical record.
        metadata.add(Office.OFFICE_LINK_TYPE, "external");
        metadata.add(Office.OFFICE_LINK_URL, "https://example.invalid/second");

        assertArrayEquals(
                new String[]{
                    "https://example.invalid/first",
                    "https://example.invalid/second"
                },
                metadata.getValues(Office.OFFICE_LINK_URL));
        assertArrayEquals(
                new String[]{"", "external"},
                metadata.getValues(Office.OFFICE_LINK_TYPE));
    }
}
