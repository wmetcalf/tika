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
package org.apache.tika.metadata.writefilter;

import java.io.Serializable;
import java.util.Map;

public interface MetadataWriteLimiter extends Serializable {

    /**
     * Based on the field and value, this limiter modifies the field
     * and/or the value to something that should be added to the Metadata object.
     *
     * If the value is <code>null</code>, no value is set or added.
     *
     * Status updates (e.g. write limit reached) can be added directly to the
     * underlying metadata.
     *
     * @param field the metadata field name
     * @param value the value to add
     * @param data the metadata map to modify
     */
    void add(String field, String value, Map<String, String[]> data);

    /**
     * Handles the first value passed through a Metadata {@code add} operation
     * when the field is not currently stored. The default delegates to
     * {@link #set(String, String, Map)} to preserve the historical dispatch
     * contract for existing limiter implementations.
     *
     * @param field the metadata field name
     * @param value the first value to add
     * @param data the metadata map to modify
     */
    default void addFirst(String field, String value, Map<String, String[]> data) {
        set(field, value, data);
    }

    /**
     * Based on the field and the value, this limiter modifies
     * the field and/or the value to something that should be set in the
     * Metadata object.
     *
     * @param field the metadata field name
     * @param value the value to set
     * @param data the metadata map to modify
     */
    void set(String field, String value, Map<String, String[]> data);

    /**
     * Replaces all values for a field. Stateful limiters may override this method
     * when replacement needs different bookkeeping from an explicit remove
     * followed by later additions.
     *
     * @param field the metadata field name
     * @param values the replacement values, or {@code null} to remove the field
     * @param data the metadata map to modify
     */
    default void replace(String field, String[] values, Map<String, String[]> data) {
        remove(field, data);
        if (values != null) {
            for (String value : values) {
                add(field, value, data);
            }
        }
    }

    /**
     * Removes a field. Stateful limiters should override this method to release
     * any accounting associated with its key and values.
     *
     * @param field the metadata field name
     * @param data the metadata map to modify
     */
    default void remove(String field, Map<String, String[]> data) {
        data.remove(field);
    }
}
