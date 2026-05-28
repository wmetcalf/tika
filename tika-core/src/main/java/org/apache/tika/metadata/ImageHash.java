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
 * Perceptual image hash metadata keys. Hash values are computed by
 * {@link org.apache.tika.utils.ImageHashUtils}, which delegates to the
 * <a href="https://github.com/wmetcalf/rosetta-squint">rosetta-squint</a>
 * Java port of Python {@code imagehash} 4.3.2 — the hex values match
 * byte-for-byte across Python / Java / Rust / Go / JS / Swift.
 */
public interface ImageHash {
    Property PHASH = Property.internalText("image:phash");
    Property DHASH = Property.internalText("image:dhash");
    Property AHASH = Property.internalText("image:ahash");
    Property COLORHASH = Property.internalText("image:colorhash");
}
