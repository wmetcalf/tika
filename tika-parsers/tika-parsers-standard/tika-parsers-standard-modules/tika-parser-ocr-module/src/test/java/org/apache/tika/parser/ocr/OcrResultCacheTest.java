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
package org.apache.tika.parser.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class OcrResultCacheTest {

    @Test
    public void testEntryCountBudgetRejectsNewEntry() {
        OcrResultCache cache = new OcrResultCache(0, false, 1, 100, 100);

        assertTrue(cache.putIfWithinBudget("first", "cached"));
        assertFalse(cache.putIfWithinBudget("second", "rejected"));
        assertEquals("cached", cache.get("first"));
        assertNull(cache.get("second"));
    }

    @Test
    public void testTotalByteBudgetAccountsForReplacement() {
        OcrResultCache cache = new OcrResultCache(0, false, 10, 6, 6);

        assertTrue(cache.putIfWithinBudget("first", "1234"));
        assertFalse(cache.putIfWithinBudget("second", "123"));
        assertTrue(cache.putIfWithinBudget("first", "1"));
        assertTrue(cache.putIfWithinBudget("second", "123"));
        assertEquals("1", cache.get("first"));
        assertEquals("123", cache.get("second"));
    }

    @Test
    public void testPerEntryBudgetCountsUtf8Bytes() {
        OcrResultCache cache = new OcrResultCache(0, false, 10, 100, 4);

        assertTrue(cache.putIfWithinBudget("four-bytes", "\u00e9\u00e9"));
        assertFalse(cache.putIfWithinBudget("six-bytes", "\u00e9\u00e9\u00e9"));
        assertNull(cache.get("six-bytes"));
    }

    @Test
    public void testLegacyConstructorRemainsUsable() {
        OcrResultCache cache = new OcrResultCache(0, false);

        cache.put("key", "value");

        assertEquals(0, cache.getMaxImageDim());
        assertEquals("value", cache.get("key"));
    }
}
