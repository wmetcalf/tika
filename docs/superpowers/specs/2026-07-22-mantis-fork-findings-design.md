# Mantis Fork Findings Remediation Design

- **Date:** 2026-07-22
- **Base branch:** `origin/4.0-upstream-office-links`
- **Implementation branch:** `fix/mantis-fork-findings`
- **Mantis snapshot:** `f2b166c6facb3577ff92f40062bf9332d8b668ff`

## Objective

Fix every security-relevant defect that the completed Mantis audit attributes to
the Tika fork, prove each fix with a regression test and an isolated Mantis
reattack, and deliver the changes as one reviewable pull request targeting
`4.0-upstream-office-links`. Run a Marla Singer review loop over the completed
pull-request diff before declaring the branch ready.

The dirty `feat/xlm-macro-entry-parity` checkout is outside this campaign and
must not be modified. All implementation happens in the isolated worktree for
`fix/mantis-fork-findings`.

## Provenance and Scope

An exact hunk-level comparison against the upstream parent of the fork's most
recent upstream sync (`0f71fa114cf82bc0f9236fc58841496f581e298a`) identifies
ten findings introduced by fork-owned changes:

| Finding | Area | Defect |
| --- | --- | --- |
| `11a8112e-d28b-4cb4-80ce-f0dd3545eb5b` | PDF | Page slicing drops later-page content and embedded payloads before analysis |
| `0f5fa019-0da8-41ea-8a75-12b5e08ae658` | RTF/Core metadata | A single-valued source-path property suppresses OLE10Native payload extraction |
| `be6692ea-ce39-4d44-b869-3e057b1a6fe8` | Office links/Core metadata | Independent metadata limiting destroys parallel link-record alignment |
| `e2c73ef9-670d-4163-afa6-c51e7b6260a8` | CHM | Read and structure failures are reported as successful empty parses |
| `0f9a9cca-25b8-4f4d-bd74-944ef88053f8` | MSC | IOC extraction omits XML entity decoding |
| `39b192ce-77da-450d-82e2-96d194d68ddc` | Windows shortcuts | ExtraData is analyzed instead of the true appended payload |
| `08d53969-b449-4219-bea7-a5258630898a` | PPKG | Valid XML syntax variants bypass command telemetry extraction |
| `1c3b6d96-a9a4-4c79-96b2-8511c5518eb9` | Barcode/Core metadata | Independent metadata bags lose per-result alignment |
| `beb96aab-a736-4fbf-b280-ac8fe537da50` | HTML | One oversized CSS RGB value disables all color-QR detection |
| `867d1a68-c715-4897-8810-e504324ca59f` | XML/SVG | The hardened Batik reader disables SVG raster enrichment |

The earlier count of eleven included a Tesseract symlink-replacement issue
because its containing file was changed by the fork. Hunk and history analysis
showed that defect was inherited from upstream, so it belongs in the later
upstream campaign. The negative RTF Unicode skip-count issue and the PDF
first-page-only OCR propagation issue are also upstream-inherited and excluded
from this pull request.

## Evidence Gate

Mantis patching requires empirical reproduction. The PDF slicing finding is
already reproduced against the pinned snapshot. Each of the other nine findings
must pass the same gate before its production code is changed:

1. Construct the smallest deterministic attack fixture or harness.
2. Run it against a private shadow of the pinned, unmodified snapshot.
3. Record the exact command, output, exit status, and artifact hashes in the
   finding's Mantis evidence directory.
4. Confirm that a benign control still exercises the intended parser path.

If a finding cannot be reproduced, its implementation work stops. The finding
state and evidence will record the failed attempt, and the pull request report
will state why no speculative patch was added. This exception is the only case
in which fewer than the ten scoped fixes may enter the pull request.

## Implementation Strategy

Work proceeds in parser-area batches while preserving a separate logical commit
for each finding unless two changes share an inseparable invariant.

### 1. PDF

Use the existing reproducer for `11a8112e`. Add a parser regression test that
places security-relevant content outside the selected page range, then change
the fork's slicing flow so presentation slicing cannot remove data before
security analysis. Preserve requested output-page behavior while ensuring the
full document remains available to metadata and embedded-content inspection.

### 2. Microsoft and RTF

Handle RTF/OLE, Office link records, CHM, MSC, Windows shortcut, and PPKG in
sequence. Tests should use parser-native inputs and assert externally visible
metadata, embedded-resource extraction, and failure semantics. Fixes must avoid
loosening resource limits or XML parser hardening.

### 3. Barcode and HTML Color QR

Preserve record identity across parallel barcode fields rather than treating
each field as an unrelated metadata bag. Make malformed or out-of-range CSS
color components local to the offending candidate so later valid color-QR
candidates remain analyzable.

### 4. XML and SVG

Restore Batik raster enrichment without reopening XML external-entity or
network access. The regression test must demonstrate successful safe SVG
rasterization and a separate hostile external-reference control must remain
blocked.

## Test-Driven Patch Cycle

Every finding follows the same transaction:

1. Add a regression test that fails on the unpatched branch for the precise
   security invariant.
2. Run the focused test and capture the expected failure.
3. Apply the smallest production change that restores the invariant.
4. Run the focused test and nearby parser tests.
5. Run the original attack and benign control against a patched private shadow.
6. Perform an independent reattack rather than trusting only the authored test.
7. Commit the finding with its tests and update the Mantis evidence/state.

Tests must validate public parser behavior. Implementation-specific assertions
are acceptable only when no public result can distinguish the vulnerability.
No test may be weakened merely to accommodate an implementation.

## Isolation and Safety

- The pinned Mantis snapshot is immutable.
- Reproduction and attack runs use disposable private shadows, no network, and
  explicit resource bounds where supported.
- Fixtures stay inside test resources or the Mantis evidence workspace.
- The dirty user checkout and unrelated `test/all-fixes` branch remain intact.
- Existing upstream-inherited findings are neither silently fixed nor mixed
  into this fork-only pull request.

## Integration and Pull Request

After all eligible finding transactions are complete:

1. Run focused tests for `tika-core` and the PDF, Microsoft, image, HTML, and XML
   parser modules.
2. Run the broadest practical Maven reactor test covering those modules and
   their dependencies.
3. Push `fix/mantis-fork-findings` and open a draft pull request targeting
   `4.0-upstream-office-links` with a finding-to-test-to-commit matrix.
4. Run Marla Singer against the complete pull-request diff.
5. Independently validate each Marla report, fix confirmed defects with new or
   strengthened tests, and repeat the loop until no actionable findings remain
   or the configured maximum rounds are exhausted.
6. Re-run the affected test matrix and confirm the branch is clean and the PR
   head matches the locally verified commit.

The pull request remains unmerged until the user explicitly requests merging.

## Completion Criteria

The branch is ready for review only when:

- every reproducible fork-introduced finding has a minimal fix and regression
  test;
- every fix has unpatched-baseline, patched-control, attack, and independent
  reattack evidence;
- all targeted and integration tests pass;
- the Marla Singer loop has no unresolved validated finding;
- the PR clearly distinguishes fork fixes from queued upstream work; and
- no unrelated checkout, branch, or Mantis snapshot has been modified.
