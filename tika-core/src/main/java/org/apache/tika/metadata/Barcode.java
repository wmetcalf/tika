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

/**
 * Generic barcode metadata keys.
 */
public interface Barcode {
    Property BARCODE_VALUE = Property.internalTextBag("barcode:value");
    Property BARCODE_FORMAT = Property.internalTextBag("barcode:format");
    Property BARCODE_RAW_BYTES = Property.internalTextBag("barcode:raw-bytes");
    Property BARCODE_POSITION = Property.internalTextBag("barcode:position");
    Property BARCODE_ERROR_CORRECTION_LEVEL =
            Property.internalTextBag("barcode:error-correction-level");
    Property BARCODE_IS_MIRRORED = Property.internalTextBag("barcode:is-mirrored");
    /**
     * One JSON object per decoded barcode. Each value preserves the complete result as
     * one limiter-atomic record.
     */
    Property BARCODE_RECORD = Property.internalTextBag("barcode:record");
}
