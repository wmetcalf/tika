#!/usr/bin/env python3
"""CI gate: rebuild the static inventory and diff against the checked-in
snapshot. Fail if any field was added, removed, or had its emitter set
change without an accompanying snapshot update.

Workflow:

    tools/inventory_static.py --tika-root . --out /tmp/current-inventory.json
    tools/inventory_check.py --baseline docs/metadata-fields-static.json \\
                              --current /tmp/current-inventory.json

Exits non-zero with a human-readable diff if drift is detected. The fix is
either:

  (a) The change to parser source was intentional — rerun inventory_static.py
      + inventory_render.py and commit the updated docs/ snapshots.

  (b) The change was unintentional — revert the parser change.

The check is intentionally strict (snapshot-based) so adding or removing a
field becomes visible review surface for downstream consumers that depend on
the field contract.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def diff_inventories(baseline: dict, current: dict) -> list[str]:
    """Return a list of human-readable diff lines. Empty list = clean."""
    lines: list[str] = []

    # 1. Added / removed declared properties
    base_props = set(baseline.get("properties", {}).keys())
    cur_props = set(current.get("properties", {}).keys())
    added = sorted(cur_props - base_props)
    removed = sorted(base_props - cur_props)
    for f in added:
        prop = current["properties"][f]
        lines.append(f"+ ADDED Property `{f}`  (`{prop['type']}` in `{prop['declared_in']}`)")
    for f in removed:
        prop = baseline["properties"][f]
        lines.append(f"- REMOVED Property `{f}`  (was `{prop['type']}` in `{prop['declared_in']}`)")

    # 2. Added / removed undeclared string literals
    base_undecl = set(baseline.get("undeclared_string_fields", {}).keys())
    cur_undecl = set(current.get("undeclared_string_fields", {}).keys())
    added_lit = sorted(cur_undecl - base_undecl)
    removed_lit = sorted(base_undecl - cur_undecl)
    for f in added_lit:
        sites = current["undeclared_string_fields"][f]["emitted_by"]
        site = f"{sites[0]['file']}:{sites[0]['line']}" if sites else "?"
        lines.append(f"+ ADDED undeclared field `{f}`  (emitted at {site})")
    for f in removed_lit:
        lines.append(f"- REMOVED undeclared field `{f}`")

    # 3. Emitter set changes for declared properties (parser added/removed)
    for f in sorted(base_props & cur_props):
        base_em = set(baseline["properties"][f].get("emitted_by", []))
        cur_em = set(current["properties"][f].get("emitted_by", []))
        added_em = cur_em - base_em
        removed_em = base_em - cur_em
        for e in sorted(added_em):
            lines.append(f"~ Property `{f}` now also emitted by `{e}`")
        for e in sorted(removed_em):
            lines.append(f"~ Property `{f}` no longer emitted by `{e}`")

    return lines


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--baseline", required=True, type=Path,
                    help="Checked-in snapshot to diff against")
    ap.add_argument("--current", required=True, type=Path,
                    help="Freshly-generated inventory (from inventory_static.py)")
    ap.add_argument("--allow-added-undeclared", action="store_true",
                    help="Don't fail on new undeclared string fields (legacy mode). "
                         "Default is strict — any new undeclared field is rejected, "
                         "forcing the contributor to either migrate to a Property "
                         "constant or explicitly accept the leak by regenerating "
                         "the snapshot.")
    args = ap.parse_args(argv)

    baseline = json.loads(args.baseline.read_text())
    current = json.loads(args.current.read_text())

    diff = diff_inventories(baseline, current)
    if not diff:
        print("inventory matches baseline — no drift.")
        return 0

    print("=== metadata field inventory drift detected ===\n")
    for line in diff:
        print(line)
    print()
    print("If this change is INTENTIONAL:")
    print("  $ python3 tools/inventory_static.py --tika-root . \\")
    print("        --out docs/metadata-fields-static.json")
    print("  $ python3 tools/inventory_render.py")
    print("  $ git add docs/metadata-fields-static.json docs/metadata-fields.md")
    print("  $ git commit  # describe what changed and why")
    print()
    print("If this change is UNINTENTIONAL: revert the parser-side change.")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
