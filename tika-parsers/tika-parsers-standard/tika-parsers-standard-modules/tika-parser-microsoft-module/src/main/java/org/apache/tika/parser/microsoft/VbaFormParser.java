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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/**
 * Parses VBA UserForm binary data (MS-OFORMS) from a POIFSFileSystem to extract
 * form control properties (ControlTipText, Tag, Caption, Value) that are invisible
 * in the VBA source text. Malware commonly hides URLs and commands in these fields.
 *
 * <p>Ported from oletools/oleform.py (BSD licence).
 * Reference: [MS-OFORMS] https://learn.microsoft.com/en-us/openspecs/office_file_formats/ms-oforms/
 */
public final class VbaFormParser {

    private static final Logger LOG = Logger.getLogger(VbaFormParser.class.getName());

    // Audit caps (2026-06): a UserForm's "f"/"o" stream is fully attacker-controlled.
    // Bound the two allocation sites a crafted form could drive to OutOfMemoryError (an
    // Error that escapes catch(Exception) and kills the parse thread).
    private static final int MAX_SITES = 65_536;                    // OleSiteConcreteControl count cap

    /** One extracted control from a UserForm's "f" stream. */
    public static final class FormControl {
        public final String name;          // control name
        public final String tag;           // Tag property (often contains payload)
        public final String controlTipText;// ControlTipText property (often contains payload)
        public final String caption;       // Caption (from o-stream MorphData/Label)
        public final String value;         // Value (from o-stream MorphData)

        FormControl(String name, String tag, String controlTipText,
                    String caption, String value) {
            this.name = name;
            this.tag = tag;
            this.controlTipText = controlTipText;
            this.caption = caption;
            this.value = value;
        }

        /** Returns true if any of the payload-bearing fields is non-empty. */
        public boolean hasPayloadFields() {
            return nonEmpty(tag) || nonEmpty(controlTipText)
                    || nonEmpty(caption) || nonEmpty(value);
        }

        /** Format all non-null/non-empty properties for embedding as readable text. */
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (nonEmpty(name)) sb.append("  name=").append(name).append('\n');
            if (nonEmpty(tag)) sb.append("  tag=").append(tag).append('\n');
            if (nonEmpty(controlTipText)) sb.append("  controlTipText=").append(controlTipText).append('\n');
            if (nonEmpty(caption)) sb.append("  caption=").append(caption).append('\n');
            if (nonEmpty(value)) sb.append("  value=").append(value).append('\n');
            return sb.toString();
        }

        private static boolean nonEmpty(String s) {
            return s != null && !s.isEmpty();
        }
    }

    /**
     * Extract form control properties from all UserForm modules found in {@code fs}.
     *
     * <p>Returns a list of (formModuleName, controls) pairs. Each entry corresponds to
     * one UserForm directory containing "f" and "o" sibling streams.
     * Returns an empty list if no form directories are found or on parse error.
     *
     * <p><b>Prefer the overload taking {@code bounds}.</b> This one allocates a throwaway
     * {@link LenientVBAReader.Bounds}, so anything it withholds -- a form it could not parse, a
     * stream refused for size, a form past the count cap -- is marked on an object the caller never
     * sees, and is therefore indistinguishable from a document with no hidden control properties.
     */
    public static List<FormModuleResult> extractFormVariables(POIFSFileSystem fs) {
        return extractFormVariables(fs, new LenientVBAReader.Bounds());
    }

    /**
     * As {@link #extractFormVariables(POIFSFileSystem)}, but bounded and REPORTING.
     *
     * <p>Every failure here used to go to {@code LOG.fine} and nowhere else, so a form whose parse
     * blew up, a stream refused for exceeding the size cap, and a site count over the cap all
     * produced the same observable result as a form with no controls: no flag, no warning, nothing.
     * A UserForm we could not read is precisely a UserForm whose hidden ControlTipText/Tag we
     * cannot vouch for, which is the opposite of "nothing to see".
     *
     * <p>CHARGING HAPPENS HERE, and callers must not charge again. It used to happen in the caller,
     * after this method had parsed EVERY form in the document and built each one's control text --
     * so the ceiling bounded what was written out and not what was held in memory, and a budget
     * admitting one form still materialised all {@link #MAX_FORMS} of them. Charging as each form is
     * parsed lets the walk stop. What is still materialised before its size is known is the ONE form
     * being parsed, which cannot be avoided (the length is not knowable without parsing) and is
     * bounded by the per-stream cap.
     */
    public static List<FormModuleResult> extractFormVariables(POIFSFileSystem fs,
                                                              LenientVBAReader.Bounds bounds) {
        List<FormModuleResult> results = new ArrayList<>();
        try {
            for (DirectoryEntry formDir : findFormDirs(fs, bounds)) {
                String name = formDir.getName();
                try {
                    FormModuleResult result = new FormModuleResult(name,
                            parseFormDir(formDir, bounds));
                    String text = result.toText();
                    if (text.isBlank()) {
                        continue;
                    }
                    // Bytes, not UTF-16 code units: control text is where non-ASCII actually
                    // lives (captions, locale strings), so this is the site the unit mismatch hit
                    // hardest -- the same defect codex reported in OfficeParser on PR #19.
                    long textBytes = LenientVBAReader.utf8Len(text);
                    if (!bounds.hasRoomFor(textBytes)) {
                        bounds.mark("UserForm control properties reached the " + bounds.totalMax()
                                + "-byte per-document bound at '" + name + "'; that form and any "
                                + "later one were not read");
                        break;
                    }
                    bounds.charge(textBytes);
                    results.add(result);
                } catch (Exception | OutOfMemoryError e) {
                    LOG.fine("VbaFormParser: error parsing form '" + name + "': "
                            + e.getMessage());
                    bounds.mark("UserForm '" + name + "' could not be parsed, so any control "
                            + "properties hidden in it were not read: " + e.getMessage());
                }
            }
        } catch (Exception | OutOfMemoryError e) {
            LOG.fine("VbaFormParser: scan error: " + e.getMessage());
            bounds.mark("the UserForm scan failed; hidden control properties may not have been "
                    + "read: " + e.getMessage());
        }
        return results;
    }

    /**
     * Every UserForm storage in {@code fs}: a directory holding an {@code f} (FormControl) stream
     * and optionally an {@code o} (ObjectData) sibling.
     *
     * <p>Discovery is separate from parsing so it can be tested on its own -- crafting a valid
     * MS-OFORMS {@code f} stream is a project of its own, and without this seam a traversal that
     * never reaches a form is indistinguishable from a form that yields no controls.
     */
    static List<DirectoryEntry> findFormDirs(POIFSFileSystem fs) {
        return findFormDirs(fs, new LenientVBAReader.Bounds());
    }

    static List<DirectoryEntry> findFormDirs(POIFSFileSystem fs,
                                             LenientVBAReader.Bounds bounds) {
        List<DirectoryEntry> out = new ArrayList<>();
        collectFormDirs(fs.getRoot(), out, 0, bounds);
        return out;
    }

    /** Depth cap: a crafted CFBF child tree can be cyclic. */
    private static final int MAX_DIR_DEPTH = 32;
    /** Cap on how many UserForm storages one document may contribute (observed max in a
     *  6,574-document macro corpus: 260). Firing it is reported, never silent. */
    static final int MAX_FORMS = 4096;

    private static void collectFormDirs(DirectoryEntry dir, List<DirectoryEntry> out, int depth,
                                        LenientVBAReader.Bounds bounds) {
        if (out.size() >= MAX_FORMS) {
            bounds.mark("UserForm count exceeded " + MAX_FORMS
                    + "; later forms were not read");
            return;
        }
        if (depth >= MAX_DIR_DEPTH) {
            bounds.mark("UserForm search stopped at depth " + MAX_DIR_DEPTH);
            return;
        }
        for (Entry entry : dir) {
            if (!(entry instanceof DirectoryEntry)) continue;
            DirectoryEntry sub = (DirectoryEntry) entry;
            String name = sub.getName();
            // "VBA" holds module source, not forms, and a \x01-prefixed storage is a compiled
            // artefact -- neither can be a UserForm, so neither is worth descending into.
            if ("VBA".equals(name) || name.startsWith("\u0001")) {
                continue;
            }
            // "Macros" and "_VBA_PROJECT_CUR" are CONTAINERS: in an OLE2 .doc/.xls the UserForm
            // storages sit INSIDE them (Macros/UserForm1/{f,o}). They used to be skipped WITHOUT
            // recursing, so OLE2 UserForms were never visited at all -- every control property
            // hidden in one, the ControlTipText/Tag fields this class exists to read, was lost for
            // the entire OLE2 half of the format family. Descend into them, but never treat one as
            // a form storage itself.
            boolean containerOnly = "Macros".equals(name) || "_VBA_PROJECT_CUR".equals(name);
            if (!containerOnly && sub.hasEntry("f")) {
                // The cap has to be enforced HERE, not only on entry to a directory. Checking it
                // only at the top of this method bounded how many directories the walk descends
                // into and not how many forms it admits, so ONE storage holding more than the cap's
                // worth of form children was taken whole -- 4,296 admitted against a cap of 4,096.
                // Sibling storages cost a CFBF property each, so wide is as cheap as deep.
                if (out.size() >= MAX_FORMS) {
                    bounds.mark("UserForm count exceeded " + MAX_FORMS
                            + "; later forms were not read");
                    return;
                }
                out.add(sub);
            }
            collectFormDirs(sub, out, depth + 1, bounds);
        }
    }

    private static List<FormControl> parseFormDir(DirectoryEntry formDir,
                                                  LenientVBAReader.Bounds bounds)
            throws IOException {
        byte[] fBytes = readEntry(formDir, "f", bounds);
        byte[] oBytes = hasEntry(formDir, "o") ? readEntry(formDir, "o", bounds) : new byte[0];
        OleStream fStream = new OleStream(fBytes);
        OleStream oStream = new OleStream(oBytes);
        return consumeFormControl(fStream, oStream);
    }

    // ── FormControl stream (f) ─────────────────────────────────────────────────

    private static List<FormControl> consumeFormControl(OleStream f, OleStream o)
            throws IOException {
        // FormControl: [MS-OFORMS] 2.2.10.1
        f.checkValues("FormControl versions", 2, new byte[]{0, 4});
        int cbForm = f.readU16();
        int start = f.pos();
        // FormDataBlock: read only what we need (BooleanProperties for DONTSAVECLASSTABLE)
        int propMaskVal = f.readU32();
        FormPropMask propmask = new FormPropMask(propMaskVal);
        boolean dontsaveclasstable = false;
        // Skip BackColor, ForeColor, NextAvailableID (4 bytes each if set)
        if (propmask.fBackColor)       f.skip(4);
        if (propmask.fForeColor)       f.skip(4);
        if (propmask.fNextAvailableID) f.skip(4);
        if (propmask.fBooleanProperties) {
            int bp = f.readU32();
            dontsaveclasstable = (bp & (1 << 15)) != 0;
        }
        // Jump to end of cbForm block
        f.seek(start + cbForm);
        // FormStreamData: skip MouseIcon, Font, Picture GUIDs/streams
        if (propmask.fMouseIcon)  consumeGuidAndPicture(f);
        if (propmask.fFont)       consumeGuidAndFont(f);
        if (propmask.fPicture)    consumeGuidAndPicture(f);
        // FormSiteData: [MS-OFORMS] 2.2.10.6
        if (!dontsaveclasstable) {
            int classTableCount = f.readU16();
            for (int i = 0; i < classTableCount; i++) consumeSiteClassInfo(f);
        }
        int countOfSites = f.readU32();
        // Cap BEFORE it sizes the SiteDepthsAndTypes drain loop + the sites list: an
        // unchecked attacker U32 here drove new ArrayList<>(countOfSites) to OOM (audit H-1).
        if (countOfSites < 0 || countOfSites > MAX_SITES) {
            throw new IOException("VbaForm countOfSites out of range: " + countOfSites);
        }
        int countOfBytes = f.readU32();
        // SiteDepthsAndTypes block
        int siteStart = f.pos();
        int remaining = countOfSites;
        while (remaining > 0) {
            remaining -= consumeFormObjectDepthTypeCount(f);
        }
        // Pad SiteDepthsAndTypes to 4-byte boundary
        f.padTo4(siteStart);
        // Consume each OleSiteConcreteControl
        List<SiteInfo> sites = new ArrayList<>(); // grows as parsed; countOfSites capped above
        for (int i = 0; i < countOfSites; i++) {
            sites.add(consumeOleSiteConcreteControl(f));
        }
        // Now read from o-stream to get value/caption for MorphData controls
        for (SiteInfo site : sites) {
            try {
                switch (site.clsidCacheIndex) {
                    case 7:
                    case 14:
                    case 57:
                        consumeFormControlSkip(o);
                        break;
                    case 12:
                        consumeImageControlSkip(o);
                        break;
                    case 15:
                    case 23:
                    case 24:
                    case 25:
                    case 26:
                    case 27:
                    case 28:
                        String[] vc = consumeMorphDataControl(o);
                        site.value   = vc[0];
                        site.caption = vc[1];
                        break;
                    case 16:
                        consumeSpinButtonControlSkip(o);
                        break;
                    case 17:
                        consumeCommandButtonControlSkip(o);
                        break;
                    case 18:
                        consumeTabStripControlSkip(o);
                        break;
                    case 21:
                        site.caption = consumeLabelControl(o);
                        break;
                    case 47:
                        consumeScrollBarControlSkip(o);
                        break;
                    default:
                        break;
                }
            } catch (Exception e) {
                LOG.fine("VbaFormParser: o-stream parse error at site " + site.name + ": " + e.getMessage());
                break; // non-fatal: stop o-stream but keep f-stream results
            }
        }
        List<FormControl> result = new ArrayList<>(sites.size());
        for (SiteInfo site : sites) {
            result.add(new FormControl(
                decode(site.name), decode(site.tag), decode(site.controlTipText),
                site.caption, site.value
            ));
        }
        return result;
    }

    /** Per-site mutable data while parsing. */
    private static final class SiteInfo {
        byte[] name, tag, controlTipText;
        String caption, value;
        int clsidCacheIndex;
    }

    private static SiteInfo consumeOleSiteConcreteControl(OleStream f) throws IOException {
        // OleSiteConcreteControl: [MS-OFORMS] 2.2.10.12.1
        f.checkValue("OleSiteConcreteControl version", 2, 0);
        int cbSite = f.readU16();
        int start = f.pos();
        int propMaskVal = f.readU32();
        SitePropMask pm = new SitePropMask(propMaskVal);

        // SiteDataBlock — properties stored in order, 4-byte padded struct
        int structStart = f.pos();
        int namelen = 0, taglen = 0, tiplen = 0;
        if (pm.fName)           namelen  = f.readCountWithFlag();
        if (pm.fTag)            taglen   = f.readCountWithFlag();
        if (pm.fID)             f.skip(4);
        if (pm.fHelpContextID)  f.skip(4);
        if (pm.fBitFlags)       f.skip(4);
        if (pm.fObjectStreamSize) f.skip(4);
        if (pm.fTabIndex)       f.skip(2);
        int clsidCacheIndex = 0;
        if (pm.fClsidCacheIndex) clsidCacheIndex = f.readU16();
        else f.skip(0); // not present
        if (pm.fGroupID)        f.skip(2);
        if (pm.fControlTipText) tiplen = f.readCountWithFlag();
        if (pm.fRuntimeLicKey)  f.skip(4);
        if (pm.fControlSource)  f.skip(4);
        if (pm.fRowSource)      f.skip(4);
        f.padTo4(structStart);

        // SiteExtraDataBlock — actual string bytes
        byte[] name = namelen > 0 ? f.readBytes(namelen) : null;
        byte[] tag  = taglen  > 0 ? f.readBytes(taglen)  : null;
        if (pm.fPosition)       f.skip(8);
        byte[] tip  = tiplen  > 0 ? f.readBytes(tiplen)  : null;

        // Jump to end of cbSite
        f.seek(start + cbSite);

        SiteInfo si = new SiteInfo();
        si.name = name;
        si.tag  = tag;
        si.controlTipText = tip;
        si.clsidCacheIndex = clsidCacheIndex;
        return si;
    }

    // ── Object stream (o) parsers ──────────────────────────────────────────────

    private static String[] consumeMorphDataControl(OleStream o) throws IOException {
        // MorphDataControl: [MS-OFORMS] 2.2.5.1
        o.checkValues("MorphDataControl versions", 2, new byte[]{0, 2});
        int cbMorphData = o.readU16();
        int start = o.pos();
        long propMaskVal = o.readU64();
        MorphDataPropMask pm = new MorphDataPropMask(propMaskVal);
        // DataBlock
        int structStart = o.pos();
        if (pm.fVariousPropertyBits) o.skip(4);
        if (pm.fBackColor)           o.skip(4);
        if (pm.fForeColor)           o.skip(4);
        if (pm.fMaxLength)           o.skip(4);
        if (pm.fBorderStyle)         o.skip(1);
        if (pm.fScrollBars)          o.skip(1);
        if (pm.fDisplayStyle)        o.skip(1);
        if (pm.fMousePointer)        o.skip(1);
        if (pm.fPasswordChar)        o.skip(2);
        if (pm.fListWidth)           o.skip(4);
        if (pm.fBoundColumn)         o.skip(2);
        if (pm.fTextColumn)          o.skip(2);
        if (pm.fColumnCount)         o.skip(2);
        if (pm.fListRows)            o.skip(2);
        if (pm.fcColumnInfo)         o.skip(2);
        if (pm.fMatchEntry)          o.skip(1);
        if (pm.fListStyle)           o.skip(1);
        if (pm.fShowDropButtonWhen)  o.skip(1);
        if (pm.fDropButtonStyle)     o.skip(1);
        if (pm.fMultiSelect)         o.skip(1);
        int valueSize = 0, captionSize = 0;
        if (pm.fValue)    valueSize   = o.readCountWithFlag();
        if (pm.fCaption)  captionSize = o.readCountWithFlag();
        if (pm.fPicturePosition) o.skip(4);
        if (pm.fBorderColor)     o.skip(4);
        if (pm.fSpecialEffect)   o.skip(4);
        if (pm.fMouseIcon)       o.skip(2);
        if (pm.fPicture)         o.skip(2);
        if (pm.fAccelerator)     o.skip(2);
        int groupNameSize = 0;
        if (pm.fGroupName) groupNameSize = o.readCountWithFlag();
        o.padTo4(structStart);
        // ExtraDataBlock
        o.skip(8); // Size (8 bytes)
        byte[] value    = valueSize    > 0 ? o.readBytes(valueSize)    : null;
        byte[] caption  = captionSize  > 0 ? o.readBytes(captionSize)  : null;
        byte[] grpName  = groupNameSize > 0 ? o.readBytes(groupNameSize) : null; // unused but consumed
        o.seek(start + cbMorphData); // jump to end
        // StreamData
        if (pm.fMouseIcon) consumeGuidAndPicture(o);
        if (pm.fPicture)   consumeGuidAndPicture(o);
        consumeTextProps(o);
        return new String[]{decode(value), decode(caption)};
    }

    private static String consumeLabelControl(OleStream o) throws IOException {
        // LabelControl: [MS-OFORMS] 2.2.4.1
        o.checkValues("LabelControl versions", 2, new byte[]{0, 2});
        int cbLabel = o.readU16();
        int start = o.pos();
        int propMaskVal = o.readU32();
        LabelPropMask pm = new LabelPropMask(propMaskVal);
        int structStart = o.pos();
        if (pm.fForeColor)            o.skip(4);
        if (pm.fBackColor)            o.skip(4);
        if (pm.fVariousPropertyBits)  o.skip(4);
        int captionSize = 0;
        if (pm.fCaption) captionSize = o.readCountWithFlag();
        if (pm.fPicturePosition) o.skip(4);
        if (pm.fMousePointer)    o.skip(1);
        if (pm.fBorderColor)     o.skip(4);
        if (pm.fBorderStyle)     o.skip(2);
        if (pm.fSpecialEffect)   o.skip(2);
        if (pm.fPicture)         o.skip(2);
        if (pm.fAccelerator)     o.skip(2);
        if (pm.fMouseIcon)       o.skip(2);
        o.padTo4(structStart);
        byte[] caption = captionSize > 0 ? o.readBytes(captionSize) : null;
        o.skip(8); // Size (8 bytes in ExtraDataBlock)
        o.seek(start + cbLabel);
        if (pm.fPicture)   consumeGuidAndPicture(o);
        if (pm.fMouseIcon) consumeGuidAndPicture(o);
        consumeTextProps(o);
        return decode(caption);
    }

    // ── Skip-only o-stream parsers ─────────────────────────────────────────────

    private static void consumeFormControlSkip(OleStream o) throws IOException {
        o.checkValues("FormControl versions", 2, new byte[]{0, 4});
        int cb = o.readU16();
        int start = o.pos();
        int propMaskVal = o.readU32();
        FormPropMask pm = new FormPropMask(propMaskVal);
        o.seek(start + cb);
        if (pm.fMouseIcon) consumeGuidAndPicture(o);
        if (pm.fFont)      consumeGuidAndFont(o);
        if (pm.fPicture)   consumeGuidAndPicture(o);
        // Skip class table and site data entirely (not needed)
    }

    private static void consumeImageControlSkip(OleStream o) throws IOException {
        o.checkValues("ImageControl versions", 2, new byte[]{0, 2});
        int cb = o.readU16();
        int start = o.pos();
        int propMaskVal = o.readU32();
        ImagePropMask pm = new ImagePropMask(propMaskVal);
        o.seek(start + cb);
        if (pm.fPicture)   consumeGuidAndPicture(o);
        if (pm.fMouseIcon) consumeGuidAndPicture(o);
    }

    private static void consumeSpinButtonControlSkip(OleStream o) throws IOException {
        o.checkValues("SpinButtonControl versions", 2, new byte[]{0, 2});
        int cb = o.readU16();
        int start = o.pos();
        int propMaskVal = o.readU32();
        SpinButtonPropMask pm = new SpinButtonPropMask(propMaskVal);
        o.seek(start + cb);
        if (pm.fMouseIcon) consumeGuidAndPicture(o);
    }

    private static void consumeCommandButtonControlSkip(OleStream o) throws IOException {
        o.checkValues("CommandButtonControl versions", 2, new byte[]{0, 2});
        int cb = o.readU16();
        int start = o.pos();
        int propMaskVal = o.readU32();
        CommandButtonPropMask pm = new CommandButtonPropMask(propMaskVal);
        o.seek(start + cb);
        if (pm.fPicture)   consumeGuidAndPicture(o);
        if (pm.fMouseIcon) consumeGuidAndPicture(o);
        consumeTextProps(o);
    }

    private static void consumeTabStripControlSkip(OleStream o) throws IOException {
        o.checkValues("TabStripControl versions", 2, new byte[]{0, 2});
        int cb = o.readU16();
        int start = o.pos();
        long propMaskVal = o.readU32(); // TabStripPropMask is 25 bits, stored in 4 bytes
        TabStripPropMask pm = new TabStripPropMask((int) propMaskVal);
        o.seek(start + cb);
        if (pm.fMouseIcon) consumeGuidAndPicture(o);
        consumeTextProps(o);
        // TabStripTabFlagData: tabData entries × 4 bytes — skip
    }

    private static void consumeScrollBarControlSkip(OleStream o) throws IOException {
        o.checkValues("ScrollBarControl versions", 2, new byte[]{0, 2});
        int cb = o.readU16();
        int start = o.pos();
        int propMaskVal = o.readU32();
        ScrollBarPropMask pm = new ScrollBarPropMask(propMaskVal);
        o.seek(start + cb);
        if (pm.fMouseIcon) consumeGuidAndPicture(o);
    }

    // ── Shared sub-record parsers ──────────────────────────────────────────────

    private static void consumeGuidAndFont(OleStream s) throws IOException {
        // GuidAndFont: [MS-OFORMS] 2.4.7
        byte[] uuid = s.readBytes(16);
        // UUID {0BE35203-8F91-11CE-9DE3-00AA004BB851} → StdFont
        ByteBuffer bb = ByteBuffer.wrap(uuid).order(ByteOrder.LITTLE_ENDIAN);
        int p0 = bb.getInt();
        short p1 = bb.getShort();
        short p2 = bb.getShort();
        if (p0 == 0x0BE35203 && (p1 & 0xFFFF) == 0x8F91 && (p2 & 0xFFFF) == 0x11CE) {
            // StdFont
            s.checkValue("StdFont version", 1, 1);
            s.skip(9); // charset, flags, weight, height
            int faceLen = s.readByte() & 0xFF;
            s.skip(faceLen);
        } else {
            // TextProps path
            consumeTextProps(s);
        }
    }

    private static void consumeGuidAndPicture(OleStream s) throws IOException {
        // GuidAndPicture: [MS-OFORMS] 2.4.8
        s.skip(16); // UUID
        s.checkValue("StdPicture preamble", 4, 0x0000746C);
        int size = s.readU32();
        s.skip(size);
    }

    private static void consumeTextProps(OleStream s) throws IOException {
        // TextProps: [MS-OFORMS] 2.3.1
        s.checkValues("TextProps versions", 2, new byte[]{0, 2});
        int cb = s.readU16();
        s.skip(cb);
    }

    private static void consumeSiteClassInfo(OleStream s) throws IOException {
        // SiteClassInfo: [MS-OFORMS] 2.2.10.10.1
        s.checkValue("SiteClassInfo version", 2, 0);
        int cb = s.readU16();
        s.skip(cb);
    }

    private static int consumeFormObjectDepthTypeCount(OleStream s) throws IOException {
        // FormObjectDepthTypeCount: [MS-OFORMS] 2.2.10.7
        s.skip(1); // depth
        int mixed = s.readByte() & 0xFF;
        if ((mixed & 0x80) != 0) {
            s.skip(1); // SITE_TYPE byte
            return mixed ^ 0x80;
        }
        return 1;
    }

    // ── Property bitmask helpers ───────────────────────────────────────────────

    private static final class FormPropMask {
        final boolean fBackColor, fForeColor, fNextAvailableID;
        final boolean fBooleanProperties;
        final boolean fMouseIcon, fFont, fPicture;
        FormPropMask(int v) {
            fBackColor         = bit(v, 1);
            fForeColor         = bit(v, 2);
            fNextAvailableID   = bit(v, 3);
            fBooleanProperties = bit(v, 6) || bit(v, 7);
            fMouseIcon         = bit(v, 15);
            fFont              = bit(v, 20);
            fPicture           = bit(v, 21);
        }
    }

    private static final class SitePropMask {
        final boolean fName, fTag, fID, fHelpContextID, fBitFlags;
        final boolean fObjectStreamSize, fTabIndex, fClsidCacheIndex;
        final boolean fPosition, fGroupID, fControlTipText;
        final boolean fRuntimeLicKey, fControlSource, fRowSource;
        SitePropMask(int v) {
            fName               = bit(v,  0);
            fTag                = bit(v,  1);
            fID                 = bit(v,  2);
            fHelpContextID      = bit(v,  3);
            fBitFlags           = bit(v,  4);
            fObjectStreamSize   = bit(v,  5);
            fTabIndex           = bit(v,  6);
            fClsidCacheIndex    = bit(v,  7);
            fPosition           = bit(v,  8);
            fGroupID            = bit(v,  9);
            fControlTipText     = bit(v, 11);
            fRuntimeLicKey      = bit(v, 12);
            fControlSource      = bit(v, 13);
            fRowSource          = bit(v, 14);
        }
    }

    private static final class MorphDataPropMask {
        final boolean fVariousPropertyBits, fBackColor, fForeColor, fMaxLength;
        final boolean fBorderStyle, fScrollBars, fDisplayStyle, fMousePointer;
        final boolean fPasswordChar, fListWidth, fBoundColumn, fTextColumn;
        final boolean fColumnCount, fListRows, fcColumnInfo, fMatchEntry;
        final boolean fListStyle, fShowDropButtonWhen, fDropButtonStyle;
        final boolean fMultiSelect, fValue, fCaption, fPicturePosition;
        final boolean fBorderColor, fSpecialEffect, fMouseIcon, fPicture;
        final boolean fAccelerator, fGroupName;
        MorphDataPropMask(long v) {
            fVariousPropertyBits  = lbit(v,  0);
            fBackColor            = lbit(v,  1);
            fForeColor            = lbit(v,  2);
            fMaxLength            = lbit(v,  3);
            fBorderStyle          = lbit(v,  4);
            fScrollBars           = lbit(v,  5);
            fDisplayStyle         = lbit(v,  6);
            fMousePointer         = lbit(v,  7);
            fPasswordChar         = lbit(v,  9);
            fListWidth            = lbit(v, 10);
            fBoundColumn          = lbit(v, 11);
            fTextColumn           = lbit(v, 12);
            fColumnCount          = lbit(v, 13);
            fListRows             = lbit(v, 14);
            fcColumnInfo          = lbit(v, 15);
            fMatchEntry           = lbit(v, 16);
            fListStyle            = lbit(v, 17);
            fShowDropButtonWhen   = lbit(v, 18);
            fDropButtonStyle      = lbit(v, 20);
            fMultiSelect          = lbit(v, 21);
            fValue                = lbit(v, 22);
            fCaption              = lbit(v, 23);
            fPicturePosition      = lbit(v, 24);
            fBorderColor          = lbit(v, 25);
            fSpecialEffect        = lbit(v, 26);
            fMouseIcon            = lbit(v, 27);
            fPicture              = lbit(v, 28);
            fAccelerator          = lbit(v, 29);
            fGroupName            = lbit(v, 32);
        }
    }

    private static final class ImagePropMask {
        final boolean fPicture, fMouseIcon;
        ImagePropMask(int v) {
            fPicture = bit(v, 10);
            fMouseIcon = bit(v, 14);
        }
    }

    private static final class CommandButtonPropMask {
        final boolean fPicture, fMouseIcon;
        CommandButtonPropMask(int v) {
            fPicture = bit(v, 7);
            fMouseIcon = bit(v, 10);
        }
    }

    private static final class SpinButtonPropMask {
        final boolean fMouseIcon;
        SpinButtonPropMask(int v) {
            fMouseIcon = bit(v, 13);
        }
    }

    private static final class TabStripPropMask {
        final boolean fMouseIcon;
        TabStripPropMask(int v) {
            fMouseIcon = bit(v, 24);
        }
    }

    private static final class LabelPropMask {
        final boolean fForeColor, fBackColor, fVariousPropertyBits;
        final boolean fCaption, fPicturePosition, fMousePointer;
        final boolean fBorderColor, fBorderStyle, fSpecialEffect;
        final boolean fPicture, fAccelerator, fMouseIcon;
        LabelPropMask(int v) {
            fForeColor            = bit(v,  0);
            fBackColor            = bit(v,  1);
            fVariousPropertyBits  = bit(v,  2);
            fCaption              = bit(v,  3);
            fPicturePosition      = bit(v,  4);
            fMousePointer         = bit(v,  6);
            fBorderColor          = bit(v,  7);
            fBorderStyle          = bit(v,  8);
            fSpecialEffect        = bit(v,  9);
            fPicture              = bit(v, 10);
            fAccelerator          = bit(v, 11);
            fMouseIcon            = bit(v, 12);
        }
    }

    private static final class ScrollBarPropMask {
        final boolean fMouseIcon;
        ScrollBarPropMask(int v) {
            fMouseIcon = bit(v, 16);
        }
    }

    private static boolean bit(int v, int n)  { return ((v >> n) & 1) == 1; }
    private static boolean lbit(long v, int n) { return ((v >> n) & 1L) == 1L; }

    // ── Stream reader ──────────────────────────────────────────────────────────

    private static final class OleStream {
        private final byte[] data;
        private int pos;

        OleStream(byte[] data) { this.data = data; }

        int pos() { return pos; }
        void seek(int p) { pos = p; }
        void skip(int n) { pos += n; }

        byte readByte() throws IOException {
            check(1);
            return data[pos++];
        }
        int readU16() throws IOException {
            check(2);
            return (data[pos++] & 0xFF) | ((data[pos++] & 0xFF) << 8);
        }
        int readU32() throws IOException {
            check(4);
            return (data[pos++] & 0xFF)
                 | ((data[pos++] & 0xFF) << 8)
                 | ((data[pos++] & 0xFF) << 16)
                 | ((data[pos++] & 0xFF) << 24);
        }
        long readU64() throws IOException {
            long lo = readU32() & 0xFFFFFFFFL;
            long hi = readU32() & 0xFFFFFFFFL;
            return lo | (hi << 32);
        }
        /** CountOfBytesWithCompressionFlag: mask off bit 31. */
        int readCountWithFlag() throws IOException {
            return readU32() & 0x7FFFFFFF;
        }
        byte[] readBytes(int n) throws IOException {
            check(n);
            byte[] b = new byte[n];
            System.arraycopy(data, pos, b, 0, n);
            pos += n;
            return b;
        }
        /** Advance position to the next 4-byte boundary relative to structStart. */
        void padTo4(int structStart) {
            int off = (pos - structStart) % 4;
            if (off != 0) pos += (4 - off);
        }
        void checkValue(String name, int size, int expected) throws IOException {
            int val = size == 1 ? (readByte() & 0xFF) : size == 2 ? readU16() : readU32();
            if (val != expected)
                throw new IOException("OleForm: " + name + " expected " + expected + " got " + val);
        }
        void checkValues(String name, int size, byte[] expected) throws IOException {
            for (byte e : expected) checkValue(name, size / expected.length, e & 0xFF);
        }
        private void check(int n) throws IOException {
            if (n < 0 || pos > data.length - n)
                throw new IOException("OleForm: read past end (pos=" + pos + " need=" + n + " len=" + data.length + ")");
        }
    }

    // ── POIFS helpers ─────────────────────────────────────────────────────────

    private static byte[] readEntry(DirectoryEntry dir, String name,
                                    LenientVBAReader.Bounds bounds) throws IOException {
        org.apache.poi.poifs.filesystem.DocumentEntry de =
            (org.apache.poi.poifs.filesystem.DocumentEntry) dir.getEntry(name);
        int size = de.getSize();
        // Cap the up-front buffer alloc: getSize() is the raw OLE2 directory size field
        // (attacker-controlled), and the "f"/"o" streams deflate-compress to ~nothing in
        // the OOXML ZIP. LenientVBAReader has this guard; VbaFormParser did not (audit M-2).
        //
        // The cap is now the SAME operator knob the module streams use, rather than a private
        // constant that happened to hold the same number: two copies of one limit drift, and only
        // one of them was configurable. The caller reports the drop -- parseFormDir's IOException
        // reaches extractFormVariables, which marks it.
        if (size < 0 || size > bounds.max()) {
            throw new IOException("VbaForm stream '" + name + "' too large: " + size
                    + " (bound " + bounds.max() + ")");
        }
        byte[] buf = new byte[size];
        try (org.apache.poi.poifs.filesystem.DocumentInputStream dis =
                     new org.apache.poi.poifs.filesystem.DocumentInputStream(de)) {
            int read = 0;
            while (read < buf.length) {
                int n = dis.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
        }
        return buf;
    }

    private static boolean hasEntry(DirectoryEntry dir, String name) {
        return dir.hasEntry(name);
    }

    private static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        // MS-OFORMS strings are Windows-1252 by default
        try {
            return new String(bytes, "windows-1252").trim();
        } catch (java.io.UnsupportedEncodingException e) {
            return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).trim();
        }
    }

    // ── Public result type ────────────────────────────────────────────────────

    public static final class FormModuleResult {
        public final String moduleName;
        public final List<FormControl> controls;
        FormModuleResult(String moduleName, List<FormControl> controls) {
            this.moduleName = moduleName;
            this.controls = Collections.unmodifiableList(controls);
        }
        /**
         * Returns a readable text dump of all controls with non-empty payload fields.
         *
         * <p>Cached: it is the value the budget is charged on and the value the caller emits, and
         * building it twice on a form with tens of thousands of controls is the cost this class is
         * trying to bound.
         */
        public String toText() {
            if (text == null) {
                text = buildText();
            }
            return text;
        }

        private String text;

        private String buildText() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== UserForm: ").append(moduleName).append(" ===\n");
            for (FormControl c : controls) {
                if (c.name != null || c.hasPayloadFields()) {
                    sb.append('[').append(c.name != null ? c.name : "(unnamed)").append("]\n");
                    sb.append(c);
                }
            }
            return sb.toString();
        }
    }
}
