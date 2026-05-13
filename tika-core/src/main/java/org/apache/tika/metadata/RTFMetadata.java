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

    /**
     * Set to {@code true} when the embedded OLE object's class name (from the RTF Object Data
     * header) has non-canonical casing for a known class name.  The canonical forms are
     * case-sensitive: {@code OLE2Link}, {@code OLE10Native}, {@code Equation.3}, {@code Package}.
     * Attackers write e.g. {@code OLE2LInk} or {@code EQUATION.3} to confuse strict parsers
     * while MS Word's OLE subsystem still dispatches by CLSID.  When the CLSID is also present
     * ({@code rtf_meta:emb_clsid}), the canonical identity can be confirmed independently.
     */
    Property EMB_CLASS_OBFUSCATED = Property.internalBoolean(
            PREFIX_RTF_META + TikaCoreProperties.NAMESPACE_PREFIX_DELIMITER + "emb_class_obfuscated");

    /**
     * Set to {@code true} when the RTF version header is non-standard (i.e. not {@code \rtf1}).
     * The RTF spec defines only version 1; non-standard values like {@code \rtf9737} are used
     * to confuse parsers that reject non-version-1 documents while still being accepted by
     * MS Word.
     */
    Property MALFORMED_RTF_HEADER = Property.internalBoolean(
            PREFIX_RTF_META + TikaCoreProperties.NAMESPACE_PREFIX_DELIMITER + "malformed_rtf_header");

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
     * contained one or more RTF unicode escape sequences (backslash-u followed by a decimal).
     * Unicode escapes have no meaning inside a binary OLE hex stream and are used solely to
     * corrupt or hide data.
     */
    Property EMB_UNICODE_IN_OBJDATA = Property.internalBoolean(
            PREFIX_RTF_META + TikaCoreProperties.NAMESPACE_PREFIX_DELIMITER + "unicode_in_objdata");

    /**
     * URL extracted from an OLE2Link/StdOleLink moniker embedded in an RTF object.
     * OLE2Link objects (className=OLE2Link or CLSID {00000300-...}) store a target URL
     * as a UTF-16LE wide-char string in their CONTENTS stream. This is the primary vector
     * for template-injection and WSDL-download attacks (CVE-2017-8759, CVE-2017-0199).
     */
    Property EMB_OLE2LINK_URL = Property.internalText(
            PREFIX_RTF_META + TikaCoreProperties.NAMESPACE_PREFIX_DELIMITER + "emb_ole2link_url");

    /**
     * Label field from an OLE10Native embedded object (the display name shown to the user,
     * typically the dropped filename). Extracted from POI's Ole10Native record when the
     * embedded POIFS compound document contains an {@code \x01Ole10Native} stream.
     */
    Property EMB_LABEL = Property.internalText(
            PREFIX_RTF_META + TikaCoreProperties.NAMESPACE_PREFIX_DELIMITER + "emb_label");

    /**
     * Human-readable name resolved from {@link #EMB_CLSID} via the known CLSID→name table.
     * Set when the root storage CLSID matches a known OLE server
     * (e.g. "Microsoft Equation Editor 3.0 (CVE-2017-11882/CVE-2018-0802)").
     */
    Property EMB_CLSID_NAME = Property.internalText(
            PREFIX_RTF_META + TikaCoreProperties.NAMESPACE_PREFIX_DELIMITER + "emb_clsid_name");

}
