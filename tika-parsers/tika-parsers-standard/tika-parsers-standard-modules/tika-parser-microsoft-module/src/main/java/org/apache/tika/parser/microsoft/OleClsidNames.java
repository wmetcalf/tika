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
package org.apache.tika.parser.microsoft;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Known OLE CLSID-to-name mappings for security-relevant embedded object identification.
 *
 * <p>Ported from rt-eff-u-extract's CLSID_MAP (wmetcalf, BSD licence).
 * Used to surface human-readable class names alongside raw CLSID values
 * in extracted metadata, making known-exploit CLSIDs (Equation Editor,
 * Shell.Explorer.1, StdOleLink) immediately recognisable in results.
 */
public final class OleClsidNames {

    private static final Map<String, String> NAMES = new HashMap<>();

    static {
        // Microsoft Equation Editor — primary targets for CVE-2017-11882 / CVE-2018-0802
        NAMES.put("0002CE02-0000-0000-C000-000000000046", "Microsoft Equation Editor 3.0 (CVE-2017-11882/CVE-2018-0802)");
        NAMES.put("0002CE03-0000-0000-C000-000000000046", "MathType Equation Editor");

        // Microsoft Excel
        NAMES.put("00020820-0000-0000-C000-000000000046", "Microsoft Excel Chart");
        NAMES.put("00020821-0000-0000-C000-000000000046", "Microsoft Excel Worksheet");
        NAMES.put("00020830-0000-0000-C000-000000000046", "Microsoft Excel Macro Sheet");
        NAMES.put("00020832-0000-0000-C000-000000000046", "Microsoft Excel Binary Worksheet");
        NAMES.put("00020810-0000-0000-C000-000000000046", "Microsoft Excel Worksheet (legacy)");
        NAMES.put("00020811-0000-0000-C000-000000000046", "Microsoft Excel Chart (legacy)");

        // Microsoft Word
        NAMES.put("00020900-0000-0000-C000-000000000046", "Microsoft Word Document");
        NAMES.put("00020901-0000-0000-C000-000000000046", "Microsoft Word Picture");
        NAMES.put("00020906-0000-0000-C000-000000000046", "Microsoft Word 6.0-7.0 Document");
        NAMES.put("00020907-0000-0000-C000-000000000046", "Microsoft Word 6.0-7.0 Picture");

        // Microsoft PowerPoint
        NAMES.put("64818D10-4F9B-11CF-86EA-00AA00B929E8", "Microsoft PowerPoint Presentation");
        NAMES.put("64818D11-4F9B-11CF-86EA-00AA00B929E8", "Microsoft PowerPoint Slide");

        // Monikers and linking
        NAMES.put("00000300-0000-0000-C000-000000000046", "StdOleLink (OLE Link)");
        NAMES.put("00000301-0000-0000-C000-000000000046", "StdOleLink");
        NAMES.put("00000302-0000-0000-C000-000000000046", "StdOleDocument");
        NAMES.put("00000303-0000-0000-C000-000000000046", "File Moniker");
        NAMES.put("00000304-0000-0000-C000-000000000046", "Item Moniker");
        NAMES.put("00000308-0000-0000-C000-000000000046", "Composite Moniker");
        NAMES.put("79EAC9E0-BAF9-11CE-8C82-00AA004BA90B", "URL Moniker");

        // Package / Packager (OLE embedded executables)
        NAMES.put("F20DA720-C02F-11CE-927B-0800095AE340", "OLE Package (Packager Shell Extension)");

        // Internet Explorer / Shell — high-abuse for drive-by execution
        NAMES.put("EAB22AC1-30C1-11CF-A7EB-0000C05BAE0B", "Internet Explorer WebBrowser Control");
        NAMES.put("EAB22AC3-30C1-11CF-A7EB-0000C05BAE0B", "Shell.Explorer.1 (CVE-2026-21509)");
        NAMES.put("8856F961-340A-11D0-A96B-00C04FD705A2", "Internet Explorer Shell Embed Control");
        NAMES.put("25336920-03F9-11CF-8FD0-00AA00686F13", "HTML Document");

        // Windows Shell
        NAMES.put("13709620-C279-11CE-A49E-444553540000", "Shell DocObject Viewer");
        NAMES.put("9BA05972-F6A8-11CF-A442-00A0C90A8F39", "Shell Folder View");

        // Scriptlet / ActiveX script execution
        NAMES.put("0E59F1D2-1FBE-11D0-8FF2-00A0D10038BC", "Microsoft Scriptlet Component (.sct)");
        NAMES.put("0E59F1D3-1FBE-11D0-8FF2-00A0D10038BC", "Microsoft Scriptlet");
        NAMES.put("06290BD5-48AA-11D2-8432-006008C3FBFC", "WScript.Shell (Windows Script Host)");

        // Microsoft Graph / Visio
        NAMES.put("00020803-0000-0000-C000-000000000046", "Microsoft Graph Chart");
        NAMES.put("00021A14-0000-0000-C000-000000000046", "Microsoft Visio Drawing");

        // Multimedia
        NAMES.put("22D6F312-B0F6-11D0-94AB-0080C74C7E95", "Windows Media Player");

        // Adobe
        NAMES.put("CA8A9780-280D-11CF-A24D-444553540000", "Adobe Shockwave Flash");
        NAMES.put("D27CDB6E-AE6D-11CF-96B8-444553540000", "Adobe Flash Player");

        // Legacy/special
        NAMES.put("00000000-0000-0000-0000-000000000000", "NULL/Unknown CLSID");
        NAMES.put("0003000A-0000-0000-C000-000000000046", "Paintbrush Picture");
        NAMES.put("0003000B-0000-0000-C000-000000000046", "Bitmap Image");
    }

    private OleClsidNames() {
    }

    /**
     * Look up a human-readable name for a CLSID string.
     *
     * @param clsid CLSID formatted as {@code {xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}} or
     *              without braces — both forms are accepted, case-insensitive.
     * @return the known name, or {@code null} if the CLSID is not in the table.
     */
    public static String lookup(String clsid) {
        if (clsid == null || clsid.isEmpty()) {
            return null;
        }
        String key = clsid.replaceAll("[{}]", "").toUpperCase(Locale.ROOT);
        return NAMES.get(key);
    }
}
