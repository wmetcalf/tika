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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class BoundedColorGridCollectorTest {

    @Test
    public void rowAndCellBudgetsApplyBeforeRetention() {
        BoundedColorGridCollector collector = new BoundedColorGridCollector(2, 3);

        collector.startRow();
        collector.addCell(10);
        collector.addCell(20);
        collector.finishRow();
        collector.startRow();
        collector.addCell(30);
        collector.addCell(40);
        collector.finishRow();
        collector.startRow();
        collector.addCell(50);
        collector.finishRow();

        assertEquals(2, collector.getRows().size());
        assertEquals(3, collector.getCellCount());
        assertEquals(1, collector.getRows().get(1).size());
        assertTrue(collector.isTruncated());
    }
}
