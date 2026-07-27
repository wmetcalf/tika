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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects luma rows for color-grid analysis while enforcing allocation
 * limits before retaining attacker-controlled cells.
 */
public final class BoundedColorGridCollector {

    public static final int DEFAULT_MAX_ROWS = 4096;
    public static final int DEFAULT_MAX_CELLS = 262_144;

    private final int maxRows;
    private final int maxCells;
    private final List<List<Integer>> rows = new ArrayList<>();
    private List<Integer> currentRow;
    private int cellCount;
    private int rowCount;
    private boolean truncated;

    public BoundedColorGridCollector() {
        this(DEFAULT_MAX_ROWS, DEFAULT_MAX_CELLS);
    }

    public BoundedColorGridCollector(int maxRows, int maxCells) {
        if (maxRows < 1 || maxCells < 1) {
            throw new IllegalArgumentException("row and cell limits must be positive");
        }
        this.maxRows = maxRows;
        this.maxCells = maxCells;
    }

    public void startRow() {
        if (currentRow != null) {
            finishRow();
        }
        if (rowCount >= maxRows || cellCount >= maxCells) {
            truncated = true;
            currentRow = null;
            return;
        }
        rowCount++;
        currentRow = new ArrayList<>();
    }

    public void addCell(int luma) {
        if (currentRow == null) {
            return;
        }
        if (cellCount >= maxCells) {
            truncated = true;
            return;
        }
        currentRow.add(luma);
        cellCount++;
    }

    public void finishRow() {
        if (currentRow == null) {
            return;
        }
        if (!currentRow.isEmpty()) {
            rows.add(Collections.unmodifiableList(currentRow));
        }
        currentRow = null;
    }

    /**
     * Discards a row interrupted before its closing event and records that the
     * captured grid is incomplete.
     */
    public void abandonCurrentRow() {
        if (currentRow == null) {
            return;
        }
        cellCount -= currentRow.size();
        currentRow = null;
        truncated = true;
    }

    public void addRows(Iterable<? extends List<Integer>> sourceRows) {
        if (sourceRows == null) {
            return;
        }
        for (List<Integer> sourceRow : sourceRows) {
            startRow();
            if (currentRow == null) {
                truncated = true;
                return;
            }
            if (sourceRow != null) {
                for (Integer luma : sourceRow) {
                    if (cellCount >= maxCells) {
                        truncated = true;
                        break;
                    }
                    addCell(luma == null ? 0 : luma);
                }
            }
            finishRow();
            if (truncated) {
                return;
            }
        }
    }

    public void addCollector(BoundedColorGridCollector source) {
        if (source == null) {
            return;
        }
        addRows(source.getRows());
        truncated |= source.isTruncated();
    }

    public List<List<Integer>> getRows() {
        return Collections.unmodifiableList(rows);
    }

    public int getCellCount() {
        return cellCount;
    }

    public boolean isTruncated() {
        return truncated;
    }
}
