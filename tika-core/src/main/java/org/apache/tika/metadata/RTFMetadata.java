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

public interface RTFMetadata {
    String PREFIX_RTF_META = "rtf_meta";


    String RTF_PICT_META_PREFIX = "rtf_pict:";

    /**
     * if set to true, this means that an image file is probably a "thumbnail"
     * any time a pict/emf/wmf is in an object
     */
    Property THUMBNAIL = Property.internalBoolean(
            PREFIX_RTF_META + TikaCoreProperties.NAMESPACE_PREFIX_DELIMITER + "thumbnail");

    /**
     * if an application and version is given as part of the
     * embedded object, this is the literal string
     */
    Property EMB_APP_VERSION = Property.internalText(
            PREFIX_RTF_META + TikaCoreProperties.NAMESPACE_PREFIX_DELIMITER + "emb_app_version");

    Property EMB_CLASS = Property.internalText(
            PREFIX_RTF_META + TikaCoreProperties.NAMESPACE_PREFIX_DELIMITER + "emb_class");

    Property EMB_TOPIC = Property.internalText(
            PREFIX_RTF_META + TikaCoreProperties.NAMESPACE_PREFIX_DELIMITER + "emb_topic");

    Property EMB_ITEM = Property.internalText(
            PREFIX_RTF_META + TikaCoreProperties.NAMESPACE_PREFIX_DELIMITER + "emb_item");

    Property CONTAINS_ENCAPSULATED_HTML = Property.internalBoolean(
            PREFIX_RTF_META + TikaCoreProperties.NAMESPACE_PREFIX_DELIMITER + "contains_encapsulated_html");

    /** CLSID (binary GUID) of the OLE2 embedded object root storage, formatted as {xxxxxxxx-...}. */
    Property EMB_CLSID = Property.internalText(
            PREFIX_RTF_META + TikaCoreProperties.NAMESPACE_PREFIX_DELIMITER + "emb_clsid");

    /**
     * Original source path of an OLE Package object (the path on the system that created it).
     * For Package embeds this is typically a full absolute path like C:\path\to\file.exe.
     */
    Property EMB_SOURCE_PATH = Property.internalText(
            PREFIX_RTF_META + TikaCoreProperties.NAMESPACE_PREFIX_DELIMITER + "emb_source_path");

    /**
     * Number of decoy {@code \objdata} blocks that were discarded before the surviving
     * (last-occurrence) block for this embedded object.  A non-zero value indicates that the
     * document deliberately hid the real OLE payload behind dummy data to defeat parsers
     * that extract only the first occurrence.
     */
    Property EMB_OBJDATA_DECOY_COUNT = Property.internalInteger(
            PREFIX_RTF_META + TikaCoreProperties.NAMESPACE_PREFIX_DELIMITER + "objdata_decoy_count");

    /**
     * Set to {@code true} when the {@code \objdata} hex stream for this embedded object
     * contained one or more {@code \'HH} RTF hex escapes.  In a legitimate document the
     * OLE data is pure ASCII hex; injected {@code \'HH} escapes are used to corrupt
     * single-pass decoders and hide the real payload.
     */
    Property EMB_HEX_ESCAPE_IN_OBJDATA = Property.internalBoolean(
            PREFIX_RTF_META + TikaCoreProperties.NAMESPACE_PREFIX_DELIMITER + "hex_escape_in_objdata");

    /**
     * Set to {@code true} when the {@code \objdata} hex stream for this embedded object
     * contained one or more {@code \uN} RTF unicode escape sequences.  Unicode escapes have
     * no meaning inside a binary OLE hex stream and are used solely to corrupt or hide data.
     */
    Property EMB_UNICODE_IN_OBJDATA = Property.internalBoolean(
            PREFIX_RTF_META + TikaCoreProperties.NAMESPACE_PREFIX_DELIMITER + "unicode_in_objdata");

}
