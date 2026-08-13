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


import java.io.Serializable;

public class OfficeParserConfig implements Serializable {

    private boolean extractMacros = true;

    private boolean includeDeletedContent = true;
    private boolean includeMoveFromContent = true;
    private boolean includeShapeBasedContent = true;
    private boolean includeHeadersAndFooters = true;
    private boolean includeMissingRows = false;
    private boolean includeSlideNotes = true;
    private boolean includeSlideMasterContent = true;
    private boolean concatenatePhoneticRuns = true;
    private boolean imageHashingEnabled = false;

    private boolean preferAlternateContentChoice = true;

    private boolean writeSelectHeadersInBody = false;

    /**
     * Maximum bytes per embedded object/pict when extracting from RTF within
     * MSG files.  Since embedded data is streamed to disk (not held in memory),
     * the default is 2 GB.  Set to -1 for unlimited.
     */
    private int rtfEmbeddedMaxBytesInKb = 2 * 1024 * 1024; // 2 GB

    // ---- XLM (Excel 4.0) macro capture bounds -------------------------------------
    // These were hardcoded static finals, so tuning them required recompiling Tika.
    // For malicious-document triage the right values are deployment-specific: a
    // forensics pipeline wants generous bounds (losing macro payload is worse than
    // spending memory), while a general-purpose extractor may want tight ones.
    // 0 or negative means "use the built-in default" (see the XSSF decorator).
    private int xlmFormulaMaxLen = 0;
    private int xlmFormulaTotalMaxChars = 0;
    private int xlmMacroTextMaxChars = 0;
    private int xlmWorkbookValueMaxLen = 0;
    private int xlmWorkbookValuesMaxEntries = 0;
    private long xlmMaxInputBytes = 0L;
    // XLM macro EMULATOR bounds (the xlsb/Biff12 evaluation path). Same rationale as the
    // capture bounds above: policy, not a compile-time constant. 0 = built-in default.
    private int xlmMaxMacroCells = 0;
    private long xlmMaxFormulaBytes = 0L;
    private int xlmMaxIocEntries = 0;
    private int xlmMaxIocChars = 0;
    private long xlmMaxOperations = 0L;
    private int xlmMaxFileContentChars = 0;
    /** Max bytes in a single XLSB BIFF12 formula record; 0 = built-in default. */
    private int xlmMaxFormulaRecordBytes = 0;
    /** Aggregate cap on retained cell-value chars per document; 0 = built-in default. */
    private int xlmValueTotalMaxChars = 0;
    /** Max bytes in a single VBA project stream (raw or decompressed); 0 = built-in default. */
    private int vbaMaxStreamBytes = 0;
    /** Max TOTAL VBA macro-source bytes per document, across all modules; 0 = default. */
    private long vbaMaxTotalBytes = 0;

    private boolean includeGlossary = true;
    private String dateOverrideFormat = null;
    private int maxOverride = 0;//ignore

    /**
     * @return whether or not to extract macros
     */
    public boolean isExtractMacros() {
        return extractMacros;
    }

    /**
     * Sets whether or not MSOffice parsers should extract macros.
     * As of Tika 1.15, the default is <code>false</code>.
     *
     * @param extractMacros
     */
    public void setExtractMacros(boolean extractMacros) {
        this.extractMacros = extractMacros;
    }

    public boolean isIncludeDeletedContent() {
        return includeDeletedContent;
    }

    /**
     * Sets whether or not the parser should include deleted content.
     * <p/>
     * <b>This has only been implemented in the streaming docx parser
     * ({@link org.apache.tika.parser.microsoft.ooxml.SXWPFWordExtractorDecorator} so far!!!</b>
     *
     * @param includeDeletedContent
     */
    public void setIncludeDeletedContent(boolean includeDeletedContent) {
        this.includeDeletedContent = includeDeletedContent;
    }

    public boolean isIncludeMoveFromContent() {
        return includeMoveFromContent;
    }

    /**
     * With track changes on, when a section is moved, the content
     * is stored in both the "moveFrom" section and in the "moveTo" section.
     * <p/>
     * If you'd like to include the section both in its original location (moveFrom)
     * and in its new location (moveTo), set this to <code>true</code>
     * <p/>
     * Default: <code>false</code>
     * <p/>
     * <b>This has only been implemented in the streaming docx parser
     * ({@link org.apache.tika.parser.microsoft.ooxml.SXWPFWordExtractorDecorator} so far!!!</b>
     *
     * @param includeMoveFromContent
     */
    public void setIncludeMoveFromContent(boolean includeMoveFromContent) {
        this.includeMoveFromContent = includeMoveFromContent;
    }

    public boolean isIncludeShapeBasedContent() {
        return includeShapeBasedContent;
    }

    /**
     * In Excel and Word, there can be text stored within drawing shapes.
     * (In PowerPoint everything is in a Shape)
     * <p/>
     * If you'd like to skip processing these to look for text, set this to
     * <code>false</code>
     * <p/>
     * Default: <code>true</code>
     *
     * @param includeShapeBasedContent
     */
    public void setIncludeShapeBasedContent(boolean includeShapeBasedContent) {
        this.includeShapeBasedContent = includeShapeBasedContent;
    }

    public boolean isIncludeHeadersAndFooters() {
        return includeHeadersAndFooters;
    }

    /**
     * Whether or not to include headers and footers.
     * <p/>
     * This only operates on headers and footers in Word and Excel,
     * not master slide content in Powerpoint.
     * <p/>
     * Default: <code>true</code>
     *
     * @param includeHeadersAndFooters
     */
    public void setIncludeHeadersAndFooters(boolean includeHeadersAndFooters) {
        this.includeHeadersAndFooters = includeHeadersAndFooters;
    }

    /**
     * In OOXML, {@code mc:AlternateContent} wraps {@code mc:Choice} (newer/richer
     * rendering, e.g. DrawingML text boxes) and {@code mc:Fallback} (degraded VML
     * for older consumers). When {@code true} (default), the SAX parser processes
     * the Choice branch and skips Fallback. When {@code false}, it processes
     * Fallback and skips Choice (legacy behavior prior to Tika 4.x).
     * <p>
     * For text extraction, Choice typically contains equal or more content than
     * Fallback.
     * <p>
     * Default: {@code true}
     *
     * @return whether to prefer mc:Choice over mc:Fallback
     */
    public boolean isPreferAlternateContentChoice() {
        return preferAlternateContentChoice;
    }

    /**
     * @param preferAlternateContentChoice whether to prefer mc:Choice over mc:Fallback
     * @see #isPreferAlternateContentChoice()
     */
    public void setPreferAlternateContentChoice(boolean preferAlternateContentChoice) {
        this.preferAlternateContentChoice = preferAlternateContentChoice;
    }

    public boolean isConcatenatePhoneticRuns() {
        return concatenatePhoneticRuns;
    }

    /**
     * Microsoft Excel files can sometimes contain phonetic (furigana) strings.
     * See <a href="https://support.office.com/en-us/article/PHONETIC-function-9a329dac-0c0f-42f8-9a55-639086988554">PHONETIC</a>.
     * This sets whether or not the parser will concatenate the phonetic runs to the original text.
     * <p>
     * This is currently only supported by the xls and xlsx parsers (not the xlsb parser),
     * and the default is <code>true</code>.
     * </p>
     *
     * @param concatenatePhoneticRuns
     */
    public void setConcatenatePhoneticRuns(boolean concatenatePhoneticRuns) {
        this.concatenatePhoneticRuns = concatenatePhoneticRuns;
    }

    public boolean isImageHashingEnabled() {
        return imageHashingEnabled;
    }

    /**
     * Enables perceptual image hash extraction for Office vector renderings.
     * Disabled by default because it requires rasterizing attacker-controlled
     * document content.
     */
    public void setImageHashingEnabled(boolean imageHashingEnabled) {
        this.imageHashingEnabled = imageHashingEnabled;
    }

    public boolean isIncludeGlossary() {
        return includeGlossary;
    }

    /**
     * Whether or not to include the glossary (building blocks / AutoText) document
     * from docx files.  The glossary can contain template content such as form field
     * placeholders that may duplicate content already present in the main body.
     * <p/>
     * Default: <code>true</code>
     *
     * @param includeGlossary whether or not to include glossary content
     */
    public void setIncludeGlossary(boolean includeGlossary) {
        this.includeGlossary = includeGlossary;
    }

    public boolean isIncludeMissingRows() {
        return includeMissingRows;
    }

    /**
     * For table-like formats, and tables within other formats, should
     * missing rows in sparse tables be output where detected?
     * The default is to only output rows defined within the file, which
     * avoid lots of blank lines, but means layout isn't preserved.
     */
    public void setIncludeMissingRows(boolean includeMissingRows) {
        this.includeMissingRows = includeMissingRows;
    }

    public boolean isIncludeSlideNotes() {
        return includeSlideNotes;
    }

    /**
     * Whether or not to process slide notes content.  If set
     * to <code>false</code>, the parser will skip the text content
     * and all embedded objects from the slide notes in ppt and ppt[xm].
     * The default is <code>true</code>.
     *
     * @param includeSlideNotes whether or not to process slide notes
     * @since 1.19.1
     */
    public void setIncludeSlideNotes(boolean includeSlideNotes) {
        this.includeSlideNotes = includeSlideNotes;
    }

    /**
     * @return whether or not to process content in slide masters
     * @since 1.19.1
     */
    public boolean isIncludeSlideMasterContent() {
        return includeSlideMasterContent;
    }

    /**
     * Whether or not to include contents from any of the three
     * types of masters -- slide, notes, handout -- in a .ppt or ppt[xm] file.
     * If set to <code>false</code>, the parser will not extract
     * text or embedded objects from any of the masters.
     *
     * @param includeSlideMasterContent
     * @since 1.19.1
     */
    public void setIncludeSlideMasterContent(boolean includeSlideMasterContent) {
        this.includeSlideMasterContent = includeSlideMasterContent;
    }

    public String getDateFormatOverride() {
        return dateOverrideFormat;
    }

    /**
     * A user may wish to override the date formats in xls and xlsx files.
     * For example, a user might prefer 'yyyy-mm-dd' to 'mm/dd/yy'.
     * <p>
     * Note: these formats are "Excel formats" not Java's SimpleDateFormat
     *
     * @param format
     */
    public void setDateOverrideFormat(String format) {
        this.dateOverrideFormat = format;
    }

    public void setMaxOverride(int maxOverride) {
        this.maxOverride = maxOverride;
    }

    public int getMaxOverride() {
        return this.maxOverride;
    }

    /**
     * The default changed to <code>false</code> in 4.x. For legacy 3.x behavior,
     * set this to <code>true</code>.
     * @return
     */
    public boolean isWriteSelectHeadersInBody() {
        return writeSelectHeadersInBody;
    }

    public void setWriteSelectHeadersInBody(boolean writeSelectHeadersInBody) {
        this.writeSelectHeadersInBody = writeSelectHeadersInBody;
    }

    /**
     * Maximum bytes (in KB) per embedded object/pict when extracting from RTF
     * within MSG files.  Data is streamed to disk, so the default is 2 GB.
     * Set to -1 for unlimited.
     */
    public int getRtfEmbeddedMaxBytesInKb() {
        return rtfEmbeddedMaxBytesInKb;
    }

    public void setRtfEmbeddedMaxBytesInKb(int rtfEmbeddedMaxBytesInKb) {
        this.rtfEmbeddedMaxBytesInKb = rtfEmbeddedMaxBytesInKb;
    }

    /**
     * Per-formula character cap for XLM macrosheet formulas. A single obfuscated XLM
     * dropper routinely concatenates its whole payload into one formula, so this must
     * be far larger than the per-cell VALUE cap. 0 = built-in default (16384).
     */
    public int getXlmFormulaMaxLen() {
        return xlmFormulaMaxLen;
    }

    public void setXlmFormulaMaxLen(int xlmFormulaMaxLen) {
        this.xlmFormulaMaxLen = xlmFormulaMaxLen;
    }

    /**
     * Aggregate cap on retained macrosheet formula text (characters). This, not the
     * per-formula cap, is what bounds heap. 0 = built-in default (10 MB).
     */
    public int getXlmFormulaTotalMaxChars() {
        return xlmFormulaTotalMaxChars;
    }

    public void setXlmFormulaTotalMaxChars(int xlmFormulaTotalMaxChars) {
        this.xlmFormulaTotalMaxChars = xlmFormulaTotalMaxChars;
    }

    /**
     * Aggregate cap on macro text emitted as a MACRO entry (characters).
     * 0 = built-in default (10 MB).
     */
    public int getXlmMacroTextMaxChars() {
        return xlmMacroTextMaxChars;
    }

    public void setXlmMacroTextMaxChars(int xlmMacroTextMaxChars) {
        this.xlmMacroTextMaxChars = xlmMacroTextMaxChars;
    }

    /**
     * Per-cell cap for DATA-SHEET cell values captured for IOC resolution. Distinct
     * from {@link #getXlmFormulaMaxLen()}: these are URL/IP/path fragments, not
     * payload. 0 = built-in default (1024).
     */
    public int getXlmWorkbookValueMaxLen() {
        return xlmWorkbookValueMaxLen;
    }

    public void setXlmWorkbookValueMaxLen(int xlmWorkbookValueMaxLen) {
        this.xlmWorkbookValueMaxLen = xlmWorkbookValueMaxLen;
    }

    /**
     * Max number of captured workbook cell-value / formula entries.
     * 0 = built-in default (200000).
     */
    public int getXlmWorkbookValuesMaxEntries() {
        return xlmWorkbookValuesMaxEntries;
    }

    public void setXlmWorkbookValuesMaxEntries(int xlmWorkbookValuesMaxEntries) {
        this.xlmWorkbookValuesMaxEntries = xlmWorkbookValuesMaxEntries;
    }

    /**
     * Max bytes of macrosheet input read per workbook. 0 = built-in default (32 MB).
     */
    public long getXlmMaxInputBytes() {
        return xlmMaxInputBytes;
    }

    public void setXlmMaxInputBytes(long xlmMaxInputBytes) {
        this.xlmMaxInputBytes = xlmMaxInputBytes;
    }

    /** Max XLM macro cells the emulator will evaluate. 0 = built-in default (65536). */
    public int getXlmMaxMacroCells() {
        return xlmMaxMacroCells;
    }

    public void setXlmMaxMacroCells(int xlmMaxMacroCells) {
        this.xlmMaxMacroCells = xlmMaxMacroCells;
    }

    /** Max formula bytes the emulator will read. 0 = built-in default (16 MB). */
    public long getXlmMaxFormulaBytes() {
        return xlmMaxFormulaBytes;
    }

    public void setXlmMaxFormulaBytes(long xlmMaxFormulaBytes) {
        this.xlmMaxFormulaBytes = xlmMaxFormulaBytes;
    }

    /** Max IOC entries the emulator will record. 0 = built-in default (4096). */
    public int getXlmMaxIocEntries() {
        return xlmMaxIocEntries;
    }

    public void setXlmMaxIocEntries(int xlmMaxIocEntries) {
        this.xlmMaxIocEntries = xlmMaxIocEntries;
    }

    /** Max IOC characters the emulator will record. 0 = built-in default (1 MB). */
    public int getXlmMaxIocChars() {
        return xlmMaxIocChars;
    }

    public void setXlmMaxIocChars(int xlmMaxIocChars) {
        this.xlmMaxIocChars = xlmMaxIocChars;
    }

    /** Max emulator operations (DoS bound on evaluation). 0 = built-in default (1000000). */
    public long getXlmMaxOperations() {
        return xlmMaxOperations;
    }

    public void setXlmMaxOperations(long xlmMaxOperations) {
        this.xlmMaxOperations = xlmMaxOperations;
    }

    /** Max reconstructed file-content characters. 0 = built-in default (10 MB). */
    public int getXlmMaxFileContentChars() {
        return xlmMaxFileContentChars;
    }

    public int getXlmValueTotalMaxChars() {
        return xlmValueTotalMaxChars;
    }

    /**
     * Aggregate cap on retained cell-value text per document, in characters. Guards the
     * product of the entry count and per-entry caps, which are both settable and so
     * previously had no combined ceiling. 0 = built-in default ({@code 32 MB}).
     */
    public void setXlmValueTotalMaxChars(int xlmValueTotalMaxChars) {
        this.xlmValueTotalMaxChars = xlmValueTotalMaxChars;
    }

    public int getVbaMaxStreamBytes() {
        return vbaMaxStreamBytes;
    }

    /**
     * Max bytes in a single VBA project stream, raw or decompressed. A stream above this is
     * dropped and a decompressed body above it is truncated; either way the loss is reported
     * via the {@code msoffice:vba-capture-limit-reached} metadata flag. 0 = built-in default
     * ({@code 10 MB}).
     *
     * <p>This is NOT the effective ceiling on what a document yields:
     * {@link #setVbaMaxTotalBytes} bounds the total across every module, every VBA storage and
     * the UserForm text, and the two are independent knobs where the TIGHTER one wins. Raising
     * this alone does not raise what a document can yield -- above the default 32 MB total it has
     * no observable effect at all -- and lowering the total below this value is honoured rather
     * than clamped up to it. Set both when you mean to change what is captured.
     */
    public void setVbaMaxStreamBytes(int vbaMaxStreamBytes) {
        this.vbaMaxStreamBytes = vbaMaxStreamBytes;
    }

    public long getVbaMaxTotalBytes() {
        return vbaMaxTotalBytes;
    }

    /**
     * Max TOTAL bytes of VBA macro source one document may yield, across every module and every
     * VBA storage in it. The per-stream bound above says nothing at document scope: a project may
     * hold any number of modules, so N modules each just under the stream cap cost N times the
     * cap. This also decides whether POI's own VBA reader -- which has no size bound at all -- is
     * allowed to run: a project projected to decompress past this ceiling is read by the bounded
     * reader instead. 0 = built-in default ({@code 32 MB}).
     */
    public void setVbaMaxTotalBytes(long vbaMaxTotalBytes) {
        this.vbaMaxTotalBytes = vbaMaxTotalBytes;
    }

    public int getXlmMaxFormulaRecordBytes() {
        return xlmMaxFormulaRecordBytes;
    }

    /**
     * Max bytes in a single XLSB BIFF12 formula record. Records above this are dropped
     * and the drop is reported via the xlm-capture-limit metadata flag. 0 = built-in
     * default ({@code 65536}).
     */
    public void setXlmMaxFormulaRecordBytes(int xlmMaxFormulaRecordBytes) {
        this.xlmMaxFormulaRecordBytes = xlmMaxFormulaRecordBytes;
    }

    public void setXlmMaxFileContentChars(int xlmMaxFileContentChars) {
        this.xlmMaxFileContentChars = xlmMaxFileContentChars;
    }
}
