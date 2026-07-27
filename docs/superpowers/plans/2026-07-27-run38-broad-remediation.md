# Run 38 Broad Tika Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve all twelve confirmed Run 38 findings, retain the two approved
32 MiB document-wide input hardenings, verify the complete existing remediation
campaign, and publish evidence-backed fork and upstream draft pull requests.

**Architecture:** Preserve one fail-stop contract across core embedded parsing
and parser-local recovery boundaries: caller-owned output denials always escape
as the original throwable, while only source/parser failures receive bounded
balancing. Keep each upstream-inherited fix separable so it can be transplanted
from the fork branch to a clean `upstream/main` branch without carrying
fork-only behavior.

**Tech Stack:** Java 17, Apache Tika Maven reactor, JUnit 5, Apache POI,
PDFBox, Tika tagged SAX handlers, Mantis evidence workspace, Marla Singer, Git,
and GitHub CLI.

## Global Constraints

- Work only in `/home/coz/.config/superpowers/worktrees/tika/mantis-fork-findings`.
- Do not modify the dirty `/home/coz/Downloads/tika` checkout.
- Treat `/home/coz/Downloads/tika-sol-mantis-state/.mantis_snapshots/pass_2`
  as immutable.
- Add each regression before its production fix and record the expected red
  failure.
- Preserve the original caller throwable by identity when the tagged-SAX
  mechanism proves provenance.
- Stop all later callbacks after a caller denial.
- Keep malformed-input best-effort behavior for proven parser-originated
  failures.
- Apply a 32 MiB document-wide decompressed-input budget to DOCX optional
  inline parts and XLM capture.
- Do not commit `.marla-mechanical.json`.
- Open draft pull requests; do not merge them.

---

### Task 1: Core Embedded-Output Provenance

**Files:**
- Modify: `tika-core/src/main/java/org/apache/tika/extractor/ParsingEmbeddedDocumentExtractor.java`
- Modify: `tika-core/src/test/java/org/apache/tika/extractor/ParsingEmbeddedDocumentExtractorTest.java`
- Modify: `tika-core/src/main/java/org/apache/tika/sax/TaggedContentHandler.java`
- Modify: `tika-core/src/test/java/org/apache/tika/sax/TaggedContentHandlerTest.java`

**Interfaces:**
- Consumes: `TaggedContentHandler`, `TaggedSAXException`, and the existing
  `parseEmbedded(..., boolean outputHtml)` contract.
- Produces: a single embedded-output provenance boundary used for both
  `outputHtml=true` and `outputHtml=false`, plus cleanup precedence that parser
  subclasses can rely on.

- [ ] **Step 1: Add the false-mode wrapped-denial regression**

Add
`testWrappedOutputDenialPropagatesWhenOutputHtmlIsFalse()` using a delegate
parser that invokes a rejecting handler and wraps the resulting SAX exception
in `TikaException`. Assert `assertSame(denial, thrown)`.

- [ ] **Step 2: Add ordinary-runtime and cleanup-precedence regressions**

Add
`testParserRuntimeBalancesOwnedMarkupBeforeRethrow()` and
`testCleanupDenialSupersedesParserFailureAndStopsCallbacks()`. The second test
uses a handler that rejects the first cleanup callback and counts any later
callback:

```java
SAXException thrown = assertThrows(SAXException.class,
        () -> extractor.parseEmbedded(stream, handler, metadata, context, true));
assertSame(cleanupDenial, thrown);
assertSame(parserFailure, thrown.getSuppressed()[0]);
assertEquals(0, handler.getCallbacksAfterDenial());
```

- [ ] **Step 3: Run the tests and verify RED**

Run:

```bash
./mvnw -pl tika-core -Dtest=ParsingEmbeddedDocumentExtractorTest,TaggedContentHandlerTest test
```

Expected: the new assertions fail because false mode is untagged, ordinary
runtimes skip balancing, or cleanup denials are suppressed.

- [ ] **Step 4: Implement the minimum shared fail-stop contract**

Create the tagged output boundary before any handler callback. Classify
`SecurityException` and `Error` as immediate fail-stop. For ordinary parser
runtimes, perform only parser-owned balancing and rethrow the runtime. If
balancing throws a tagged caller denial, attach the parser failure to that
denial and throw the denial without attempting package-div closure.

- [ ] **Step 5: Run the focused core tests and verify GREEN**

Run the command from Step 3 and require zero failures and zero errors.

### Task 2: Fork Parser Output Boundaries

**Files:**
- Modify: `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/main/java/org/apache/tika/parser/microsoft/ICalParser.java`
- Modify: `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/main/java/org/apache/tika/parser/microsoft/WinShortcutParser.java`
- Modify: `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/main/java/org/apache/tika/parser/microsoft/ooxml/SXWPFWordExtractorDecorator.java`
- Modify: `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/main/java/org/apache/tika/parser/microsoft/ooxml/TaggedXWPFBodyContentsHandler.java`
- Modify: `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/main/java/org/apache/tika/parser/microsoft/ooxml/XSSFBExcelExtractorDecorator.java`
- Modify: `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/main/java/org/apache/tika/parser/microsoft/rtf/RTFParser.java`
- Test: `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/test/java/org/apache/tika/parser/microsoft/ICalRdpEmbeddedSecurityTest.java`
- Test: `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/test/java/org/apache/tika/parser/microsoft/WinShortcutParserTest.java`
- Test: `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/test/java/org/apache/tika/parser/microsoft/ooxml/OOXMLDocxSAXTest.java`
- Test: `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/test/java/org/apache/tika/parser/microsoft/ooxml/XlmCaptureBoundsTest.java`
- Test: `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/test/java/org/apache/tika/parser/microsoft/rtf/RTFObjDataParserTest.java`

**Interfaces:**
- Consumes: the core tagged-output and cleanup precedence contract from Task 1.
- Produces: fail-stop ICal, LNK, DOCX, XLSB, and RTF parser boundaries.

- [ ] **Step 1: Add one RED regression for each parser path**

Add tests named:

```text
testDataUriAttachmentUsesLocalEmbeddedRoute
testStartDocumentDenialIsUnwrapped
testEndDocumentDenialIsUnwrapped
testReferencedNoteDenialPropagates
testUnreferencedCommentDenialPropagates
testXlsbRuntimeSaxDenialPropagates
testCleanupDenialSupersedesRtfParserFailure
```

The data-URI test uses a literal base64 payload and asserts its SHA-256,
detected MIME/risk metadata, and exactly one embedded parse. Every output test
asserts throwable identity and zero callbacks after denial.

- [ ] **Step 2: Run the parser regressions and verify RED**

Run:

```bash
./mvnw -pl tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module -am \
  -Dtest=ICalRdpEmbeddedSecurityTest,WinShortcutParserTest,OOXMLDocxSAXTest,XlmCaptureBoundsTest,RTFObjDataParserTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: each new test reaches its intended parser path and fails on the
current behavior.

- [ ] **Step 3: Implement bounded local-data handling**

Recognize `data:` before the remote-URI branch, decode it with an explicit byte
cap, and feed the decoded bytes through the existing local attachment hash,
MIME/risk, and embedded extraction path. Malformed or oversized data URIs
produce the existing warning/truncation metadata and never trigger network I/O.

- [ ] **Step 4: Implement parser-local fail-stop boundaries**

Move the Windows shortcut tag around the full XHTML lifecycle. Tag both
referenced note rendering and unreferenced comment rendering. Rethrow
`RuntimeSAXException` before the XLSB catch-all. In RTF cleanup, throw the first
caller denial, suppress the parser failure onto it, and skip `endDocument`
after a rejected drain.

- [ ] **Step 5: Run the focused tests and verify GREEN**

Run the command from Step 2 and require zero failures and zero errors.

### Task 3: Upstream-Inherited PPTX and PST Boundaries

**Files:**
- Modify: `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/main/java/org/apache/tika/parser/microsoft/ooxml/SXSLFPowerPointExtractorDecorator.java`
- Modify: `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/main/java/org/apache/tika/parser/microsoft/pst/PSTMailItemParser.java`
- Test: `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/test/java/org/apache/tika/parser/microsoft/ooxml/OOXMLPptxSAXTest.java`
- Test: `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/test/java/org/apache/tika/parser/microsoft/pst/OutlookPSTParserTest.java`

**Interfaces:**
- Consumes: tagged SAX provenance and `WriteLimitReachedException`.
- Produces: PPTX/PST fixes that can be cherry-picked independently onto
  `upstream/main`.

- [ ] **Step 1: Add PPTX and PST denial regressions**

Add `testSlideOutputDenialPropagatesWithoutCleanupCallbacks()` using a synthetic
PPTX slide and rejecting handler. Add
`testAttachmentSecurityAndSaxDenialsPropagate()` using the smallest existing PST
fixture/harness and a rejecting embedded extractor.

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
./mvnw -pl tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module -am \
  -Dtest=OOXMLPptxSAXTest,OutlookPSTParserTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PPTX records a warning and continues, while PST records and swallows
the injected denial.

- [ ] **Step 3: Implement provenance-aware recovery**

Tag PPTX slide/related-part output and run `closeAnyPending()` only for
source-originated XML failures. In PST, rethrow `SecurityException`; catch
`SAXException` separately and restore tagged caller denials before recording
only genuine attachment/parser failures.

- [ ] **Step 4: Run the tests and verify GREEN**

Run the command from Step 2 and require zero failures and zero errors.

### Task 4: Pipes UNPACK Output Provenance

**Files:**
- Modify: `tika-pipes/tika-pipes-core/src/main/java/org/apache/tika/pipes/core/extractor/UnpackExtractor.java`
- Create: `tika-pipes/tika-pipes-core/src/test/java/org/apache/tika/pipes/core/extractor/UnpackExtractorSecurityTest.java`

**Interfaces:**
- Consumes: `ParsingEmbeddedDocumentExtractor` protected parsing and
  output-provenance behavior.
- Produces: UNPACK byte retention without bypassing the superclass fail-stop
  boundary.

- [ ] **Step 1: Add the wrapped-denial RED regression**

Build an `UnpackExtractor` around a delegate parser that invokes a rejecting
handler and wraps the SAX denial in `TikaException`. Exercise both
`outputHtml=true` and `false`:

```java
assertSame(denial, assertThrows(SAXException.class,
        () -> extractor.parseEmbedded(stream, handler, metadata, context, mode)));
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
./mvnw -pl tika-pipes/tika-pipes-core -am \
  -Dtest=UnpackExtractorSecurityTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: at least the delegate-wrapped denial is recorded or swallowed.

- [ ] **Step 3: Reuse the superclass contract**

Refactor the override so limits, raw-byte storage, and UNPACK selection remain
local, while delegate parsing and XHTML cleanup use the same provenance helper
as `ParsingEmbeddedDocumentExtractor`. Do not duplicate the old pre-boundary
catch sequence.

- [ ] **Step 4: Run the test and verify GREEN**

Run the command from Step 2 and require zero failures and zero errors.

### Task 5: PDF Page-End Denial

**Files:**
- Modify: `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-pdf-module/src/main/java/org/apache/tika/parser/pdf/PDFParser.java`
- Modify: `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-pdf-module/src/main/java/org/apache/tika/parser/pdf/AbstractPDF2XHTML.java`
- Modify: `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-pdf-module/src/test/java/org/apache/tika/parser/pdf/PDFParserSlicingSecurityTest.java`

**Interfaces:**
- Consumes: the configured partial-PDF salvage policy.
- Produces: parser IO salvage for corrupt PDF data while all output denials
  remain fail-stop.

- [ ] **Step 1: Strengthen the rendered-page denial regression**

Extend `testRenderedPageDownstreamSaxDenialPropagates()` so the denial occurs
from page-end rendering while `catchIntermediateIOExceptions=true`; assert the
same SAX exception escapes and no success warning substitutes for it.

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
./mvnw -pl tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-pdf-module -am \
  -Dtest=PDFParserSlicingSecurityTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: the parser returns partial success or exposes only an intermediate
`IOException`.

- [ ] **Step 3: Preserve output provenance through page-end rendering**

Restore the original tagged SAX denial before the intermediate-IO salvage
branch. Leave true PDF source/renderer IO failures eligible for the configured
partial-document warning path.

- [ ] **Step 4: Run the test and verify GREEN**

Run the command from Step 2 and require zero failures and zero errors.

### Task 6: DOCX and XLM Document-Wide Input Budgets

**Files:**
- Modify: `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/main/java/org/apache/tika/parser/microsoft/ooxml/OOXMLPartContentCollector.java`
- Modify: `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/main/java/org/apache/tika/parser/microsoft/ooxml/SXWPFWordExtractorDecorator.java`
- Modify: `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/main/java/org/apache/tika/parser/microsoft/ooxml/XSSFExcelExtractorDecorator.java`
- Modify: `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/main/java/org/apache/tika/parser/microsoft/ooxml/XSSFBExcelExtractorDecorator.java`
- Test: `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/test/java/org/apache/tika/parser/microsoft/ooxml/OOXMLPartContentCollectorTest.java`
- Test: `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/test/java/org/apache/tika/parser/microsoft/ooxml/XlmCaptureBoundsTest.java`

**Interfaces:**
- Produces: one 32 MiB consumed-input counter per document for DOCX optional
  part collection and one per workbook for XLM capture.

- [ ] **Step 1: Add aggregate-budget RED regressions**

Create many individually admissible compressed parts/records whose cumulative
decompressed input exceeds 32 MiB. Assert processing stops at the shared cap,
the truncation/warning marker is present, and an exactly-at-cap control remains
accepted.

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
./mvnw -pl tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module -am \
  -Dtest=OOXMLPartContentCollectorTest,XlmCaptureBoundsTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: cumulative accepted input exceeds the 32 MiB design limit.

- [ ] **Step 3: Implement shared consumed-input budgets**

Pass one budget object through each document/workbook extraction lifecycle.
Charge actual decompressed bytes before retention; do not reset the counter per
part, comment, sheet, or macro entry. Preserve existing per-part and output
limits as independent lower bounds.

- [ ] **Step 4: Run the tests and verify GREEN**

Run the command from Step 2 and require zero failures and zero errors.

### Task 7: Full Verification, Adversarial Review, and Evidence Refresh

**Files:**
- Update: `/home/coz/Downloads/tika-sol-mantis-state/workspace/inventory.json`
- Update: `/home/coz/Downloads/tika-sol-mantis-state/workspace/provenance.json`
- Update: `/home/coz/Downloads/tika-sol-mantis-state/workspace/report/review_packet-latest.md`
- Update: Run-specific Mantis reproducer, review, critic, chain, and calibration
  artifacts for changed findings.

**Interfaces:**
- Consumes: the completed branch and all ten named exploit-positive PoCs.
- Produces: final-commit test logs, final-commit PoC logs, a terminal Marla
  review, and refreshed stakeholder evidence.

- [ ] **Step 1: Run focused and module tests**

Run `tika-core`, Microsoft, PDF, Pipes core/integration, image, HTML, XML, and
their required dependency modules. Record Surefire totals and command exit
statuses.

- [ ] **Step 2: Run the broadest practical Maven reactor**

Run the repository's established full verification command. Any skipped module
or environmental failure must be named and cannot be represented as green.

- [ ] **Step 3: Freeze the candidate commit and rerun ten PoCs**

Create an immutable `git archive` shadow of the candidate commit and run:

```text
11a8112e-pdf-slicing
0f5fa019-rtf-source-path
be6692ea-office-link-alignment
e2c73ef9-chm-empty-success
0f9a9cca-msc-entities
39b192ce-lnk-extra-cursor
08d53969-ppkg-xml-variants
1c3b6d96-barcode-alignment
beb96aab-html-rgb-overflow
867d1a68-svg-raster
```

Require each exploit-positive assertion and its benign control to pass against
the exact candidate commit.

- [ ] **Step 4: Run full Marla review to terminal green**

Freeze the full PR diff, run the complete reviewer fanout including local
Qwen36MoE and at least three repository-aware Codex lenses, perform blast-radius
and claim-verification passes, reproduce every confirmed result RED, fix
test-first, and repeat. A size failure or unavailable reviewer is a skip, never
a clean verdict.

- [ ] **Step 5: Refresh Mantis evidence**

Re-run dedupe, independent review, reproduction, critic, chain, calibration,
inventory, provenance, and stakeholder report stages on the final commit.
Record hashes for the report, coverage summary, inventory, provenance, and each
changed finding.

### Task 8: Publish Fork and Upstream Draft Pull Requests

**Files:**
- Create: PR body files in a private temporary directory only.
- Do not commit: `.marla-mechanical.json`.

**Interfaces:**
- Consumes: the exact verified commit and evidence matrix from Task 7.
- Produces: one draft fork PR targeting `4.0-upstream-office-links` and separate
  upstream draft PRs for PPTX, PST, and Pipes UNPACK where the defect reproduces
  on current `upstream/main`.

- [ ] **Step 1: Commit only intended files**

Inspect `git status`, exclude `.marla-mechanical.json`, stage explicit paths,
commit the approved remediation, and rerun the final verification if the commit
hash changes from the tested candidate.

- [ ] **Step 2: Push and open the fork draft PR**

Push `fix/mantis-fork-findings` to `origin` and open a draft PR against
`wmetcalf/tika:4.0-upstream-office-links`. Include provenance, finding-to-test
mapping, exact final commit, test totals, ten PoC results, Marla result, and
Mantis artifact hashes.

- [ ] **Step 3: Create clean upstream branches**

Fetch `upstream/main`, create one isolated worktree/branch per logically
independent inherited fix, and transplant only its production change and
regression. Reproduce RED on the upstream base and GREEN after the patch.

- [ ] **Step 4: Open evidence-backed Apache draft PRs**

Push the upstream branches to `wmetcalf/tika`, then open cross-repository draft
PRs against `apache/tika:main`. Each body states impact without overclaiming,
lists the exact reproduction/test command, and links only evidence safe for
public disclosure.

- [ ] **Step 5: Verify remote state**

Confirm every PR URL, base, head SHA, draft status, and check status. Leave all
branches and worktrees intact until the user explicitly requests cleanup.
