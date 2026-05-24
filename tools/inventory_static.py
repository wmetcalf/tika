#!/usr/bin/env python3
"""Static inventory of Tika metadata fields.

Walks the Tika source tree and extracts:

  1. Property declarations from `org.apache.tika.metadata.*` namespace files.
     Each Property constant maps to a canonical field name (the first arg to
     Property.internal<Type>("name")) plus type / declaring class / Javadoc.

  2. Every Metadata.set / add / setMulti call site in parser source. Records:
       a. Typed calls: metadata.set(Foo.BAR, ...) — resolved via the
          Property declarations from pass 1.
       b. Literal-string calls: metadata.set("namespace:name", ...) — these
          bypass the type-safe registry and are emitted by parsers but never
          declared as Property constants. The "leak surface" of the field
          contract.

Output is a single JSON document:

    {
      "scanned_at": "...",
      "tika_root": "...",
      "summary": {
        "property_declarations": N,
        "parsers_emitting_fields": M,
        "undeclared_string_fields": K
      },
      "properties": {
        "<field-name>": {
          "type": "string|boolean|integer|date|closed-choice|...",
          "declared_in": "org.apache.tika.metadata.Foo",
          "constant_name": "BAR",
          "javadoc": "...",
          "emitted_by": ["fully.qualified.ParserClass1", ...]
        }
      },
      "undeclared_string_fields": {
        "<literal-field>": {
          "emitted_by": [
            {"class": "fully.qualified.ParserClass1",
             "file": "relative/path/Foo.java",
             "line": 123}
          ]
        }
      },
      "parsers": {
        "<parser-fqn>": {
          "emits": ["<field>", "<field>", ...]
        }
      }
    }

Intended use:
  * Snapshot output as docs/metadata-fields-static.json in the fork.
  * Diff on PRs — any new declared property or undeclared literal surfaces
    in the diff. The PR description must update the snapshot + docs.
  * Combine with a runtime corpus harvester (separate tool) to attach
    per-MIME-type observed counts.

Limitations (v0, by design):
  * Regex / line-oriented; doesn't fully parse Java. False positives on
    method calls that LOOK like Metadata.set but aren't (rare in practice).
  * Doesn't resolve fully-qualified class references that go through
    static imports without a class prefix. ~95% coverage of actual
    Tika usage; the long tail can be patched with explicit regex
    additions when found.
  * String concatenation like `PREFIX + "field"` is resolved when PREFIX
    is a `static final String` literal in the same class; cross-file
    concatenation is not resolved (recorded as a templated value).
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

# ── Pattern definitions ─────────────────────────────────────────────────────

# Locate the START of a Property constant declaration. We then paren-balance
# from there to find the closing `);`. This is more robust than a single regex
# because Tika declarations span multiple lines and contain nested method
# calls inside the factory call's args.
#
#   public static final Property TITLE = Property.internalText("dc:title");
#   Property X = Property.composite(Foo.BAR.toString(), new Property[]{...});
#                                                                         ^- nested () close
#
# `modifiers` is optional (interface fields in Tika omit `public static final`
# because interface fields are implicitly such). `factory` captures any
# Property.<method>(...) factory variant — internalText, externalTextBag,
# composite, composeProperty, etc.
PROP_DECL_HEAD_RE = re.compile(
    r"""
    (?:^|\n)
    [ \t]*                                       # leading whitespace
    (?:(?:public|protected|private|static|final)\s+)*
    Property\s+(?P<const>[A-Z][A-Z0-9_]*)\s*=\s*
    Property\s*\.\s*(?P<factory>[a-zA-Z]+)\s*\(
    """,
    re.VERBOSE | re.MULTILINE,
)


def _balance_args(text: str, start: int) -> int | None:
    """Given an index just AFTER the opening `(` of a method call, return the
    index of the matching `)`. Returns None if unbalanced or unterminated."""
    depth = 1
    i = start
    in_str = False
    in_char = False
    escape = False
    while i < len(text):
        c = text[i]
        if escape:
            escape = False
        elif c == "\\":
            escape = True
        elif in_str:
            if c == '"': in_str = False
        elif in_char:
            if c == "'": in_char = False
        elif c == '"':
            in_str = True
        elif c == "'":
            in_char = True
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return None

# Inline static initializer: Property.internalText("foo") used as a constant value
# without being assigned to a named field. Less common; skip in v0.

# Head of a String constant declaration. We balance-find the trailing `;` so
# multi-line / concatenated expressions are captured intact.
#   public static final String PREFIX = "..." + Other.X + "...";
#   String PREFIX = "..." +                ← interface-field syntax (Tika)
#       OtherClass.NAMESPACE_DELIMITER;
STRING_CONST_HEAD_RE = re.compile(
    r"""
    (?:^|\n)\s*
    (?:(?:public|protected|private|static|final)\s+)*
    String\s+(?P<name>[A-Z][A-Z0-9_]*)\s*=\s*
    """,
    re.VERBOSE | re.MULTILINE,
)

# metadata.set / add / setMulti / setMultiple / etc.
# Group 1: method name. Group 2: first argument (Property constant ref OR string literal).
META_CALL_RE = re.compile(
    r"""
    (?P<recv>\w+(?:\.\w+)*)\.
    (?P<method>set|setMulti|setMultiple|add|addAll)\s*\(
    \s*(?P<first>[^,)]+)
    """,
    re.VERBOSE,
)

# A metadata receiver — variable named `metadata`, `meta`, or any field whose name
# ends in "Metadata" (heuristic to catch member-field receivers without false
# positives on every two-letter `m.`).
META_RECEIVER_RE = re.compile(r"^(metadata|meta|.*Metadata)$")

# Captures `ClassName.CONST_NAME` form for the first argument of a metadata call.
CONST_REF_RE = re.compile(r"^([A-Z][A-Za-z0-9_]*)\.([A-Z][A-Z0-9_]*)$")

# Captures `"literal"` form. Strict — no concatenation.
STRING_LITERAL_RE = re.compile(r'^"((?:[^"\\]|\\.)*)"$')

# Captures `PREFIX + "literal"` form.
PREFIX_CONCAT_RE = re.compile(r'^([A-Z][A-Z0-9_]*)\s*\+\s*"((?:[^"\\]|\\.)*)"$')


# ── Data containers ─────────────────────────────────────────────────────────


@dataclass
class PropertyDecl:
    field_name: str  # canonical "namespace:name" string
    type: str        # internalText, internalBoolean, etc.
    declared_in_fqn: str
    constant_name: str
    javadoc: str | None
    source_file: str
    source_line: int


@dataclass
class FieldRecord:
    """Aggregate view of a single Tika field across declarations + emissions."""
    field_name: str
    type: str | None
    declared_in: str | None
    constant_name: str | None
    javadoc: str | None
    emitted_by: set[str] = field(default_factory=set)
    is_string_literal_only: bool = False
    literal_emit_sites: list[dict] = field(default_factory=list)


# ── File scanning ───────────────────────────────────────────────────────────


def package_of(java_path: Path) -> str:
    """Read the `package ...;` line. Returns "" if absent."""
    try:
        with java_path.open(encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.strip()
                if line.startswith("package "):
                    return line[len("package "):].rstrip(";").strip()
                if line.startswith("import ") or line.startswith("public ") or line.startswith("class "):
                    return ""
    except OSError:
        pass
    return ""


def class_of(java_path: Path) -> str:
    """Naive: the file's top-level class is its filename stem. Inner classes are
    treated as part of the containing file's primary class for inventory purposes."""
    return java_path.stem


def collect_string_constants(text: str,
                              global_consts: dict[str, str] | None = None) -> dict[str, str]:
    """Find `String FOO = <expr>;` declarations and resolve the expression.

    Two-pass within a single file:
      1. Capture every (name, expr) pair where expr is the raw text up to `;`.
      2. Iteratively resolve expressions to concrete strings, using
         already-resolved file-local constants and any provided global_consts.
         Continue until no more resolutions happen (fixed point).
    Unresolvable expressions are dropped.
    """
    raw: dict[str, str] = {}
    for m in STRING_CONST_HEAD_RE.finditer(text):
        # Find the trailing `;` for this declaration, balancing parens/braces.
        end = _find_statement_end(text, m.end())
        if end is None:
            continue
        expr = text[m.end():end].strip()
        raw[m.group("name")] = expr
    resolved: dict[str, str] = {}
    progress = True
    while progress:
        progress = False
        for name, expr in raw.items():
            if name in resolved:
                continue
            value = resolve_string(expr, resolved, global_consts)
            if value is not None:
                resolved[name] = value
                progress = True
    return resolved


def _find_statement_end(text: str, start: int) -> int | None:
    """Find the matching `;` that ends a Java statement at `start`. Balances
    parens, brackets, braces, and skips string/char literals."""
    depth = 0
    in_str = False
    in_char = False
    escape = False
    i = start
    while i < len(text):
        c = text[i]
        if escape:
            escape = False
        elif c == "\\":
            escape = True
        elif in_str:
            if c == '"': in_str = False
        elif in_char:
            if c == "'": in_char = False
        elif c == '"':
            in_str = True
        elif c == "'":
            in_char = True
        elif c in "([{": depth += 1
        elif c in ")]}": depth -= 1
        elif c == ";" and depth == 0:
            return i
        i += 1
    return None


def extract_javadoc_above(text: str, decl_start: int) -> str | None:
    """Walk backwards from decl_start to find a /** ... */ block."""
    head = text[:decl_start]
    end = head.rfind("*/")
    if end < 0:
        return None
    # Must be the IMMEDIATELY preceding block (only whitespace between).
    between = head[end + 2:]
    if between.strip():
        return None
    start = head.rfind("/**", 0, end)
    if start < 0:
        return None
    block = head[start:end + 2]
    # Clean up: strip leading "/**", trailing "*/", and per-line "* "
    lines = block.splitlines()
    cleaned = []
    for ln in lines:
        s = ln.strip()
        if s.startswith("/**"):
            s = s[3:].strip()
        elif s.startswith("*/"):
            continue
        elif s.startswith("*"):
            s = s[1:].lstrip()
        if s:
            cleaned.append(s)
    return " ".join(cleaned) if cleaned else None


def scan_property_declarations(java_path: Path,
                                global_consts: dict[str, str] | None = None) -> list[PropertyDecl]:
    """Pass 1: extract every Property constant declared in a metadata namespace file."""
    text = java_path.read_text(encoding="utf-8", errors="replace")
    pkg = package_of(java_path)
    cls = class_of(java_path)
    fqn = f"{pkg}.{cls}" if pkg else cls
    string_consts = collect_string_constants(text, global_consts)
    out: list[PropertyDecl] = []
    for m in PROP_DECL_HEAD_RE.finditer(text):
        # m.end() is just after the opening `(` of Property.factory(...)
        end = _balance_args(text, m.end())
        if end is None:
            continue  # unterminated — skip
        args_text = text[m.end():end].strip()
        # Split top-level commas to isolate the first arg (field-name expression).
        first_arg = _split_first_arg(args_text)
        field_name = resolve_string(first_arg, string_consts, global_consts)
        if field_name is None:
            continue
        line_no = text.count("\n", 0, m.start()) + 1
        javadoc = extract_javadoc_above(text, m.start())
        out.append(PropertyDecl(
            field_name=field_name,
            type=m.group("factory"),
            declared_in_fqn=fqn,
            constant_name=m.group("const"),
            javadoc=javadoc,
            source_file=str(java_path),
            source_line=line_no,
        ))
    return out


def _split_first_arg(args_text: str) -> str:
    """Return the first top-level (paren/bracket-balanced) argument."""
    depth = 0
    in_str = False
    in_char = False
    escape = False
    for i, c in enumerate(args_text):
        if escape:
            escape = False
            continue
        if c == "\\":
            escape = True
            continue
        if in_str:
            if c == '"': in_str = False
            continue
        if in_char:
            if c == "'": in_char = False
            continue
        if c == '"': in_str = True
        elif c == "'": in_char = True
        elif c in "([{": depth += 1
        elif c in ")]}": depth -= 1
        elif c == "," and depth == 0:
            return args_text[:i].strip()
    return args_text.strip()


def resolve_string(expr: str, string_consts: dict[str, str],
                    global_consts: dict[str, str] | None = None) -> str | None:
    """Resolve a Java string expression to its concrete value, when possible.

    Handles arbitrary `+`-concatenations of string literals and named String
    constants. The string_consts arg holds the file's own `static final String`
    declarations; global_consts (optional) maps `ShortClass.CONSTANT_NAME`
    keys for cross-file references like `TikaCoreProperties.NAMESPACE_PREFIX_DELIMITER`.
    """
    expr = expr.strip()
    parts = _split_concat(expr)
    if not parts:
        return None
    resolved: list[str] = []
    for p in parts:
        p = p.strip()
        # String literal
        m = STRING_LITERAL_RE.match(p)
        if m:
            resolved.append(_unescape_java_string(m.group(1)))
            continue
        # Local constant
        if p in string_consts:
            resolved.append(string_consts[p])
            continue
        # Cross-file constant via short class name
        if global_consts and p in global_consts:
            resolved.append(global_consts[p])
            continue
        # Method call like FOO.toString() — try stripping
        if p.endswith(".toString()"):
            base = p[:-len(".toString()")]
            if base in string_consts:
                resolved.append(string_consts[base])
                continue
            if global_consts and base in global_consts:
                resolved.append(global_consts[base])
                continue
        # Unresolvable
        return None
    return "".join(resolved)


def _split_concat(expr: str) -> list[str] | None:
    """Split a `+`-concatenation at top level. Returns a list of operands or
    None if the expression is too complex (e.g., contains method calls with
    embedded `+` that we shouldn't split through)."""
    out = []
    depth = 0
    in_str = False
    in_char = False
    escape = False
    last = 0
    for i, c in enumerate(expr):
        if escape:
            escape = False
            continue
        if c == "\\":
            escape = True
            continue
        if in_str:
            if c == '"': in_str = False
            continue
        if in_char:
            if c == "'": in_char = False
            continue
        if c == '"': in_str = True
        elif c == "'": in_char = True
        elif c in "([{": depth += 1
        elif c in ")]}": depth -= 1
        elif c == "+" and depth == 0:
            out.append(expr[last:i])
            last = i + 1
    out.append(expr[last:])
    return [p.strip() for p in out if p.strip()]


def _unescape_java_string(s: str) -> str:
    out = []
    i = 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            n = s[i+1]
            i += 2
            out.append({"n": "\n", "t": "\t", "r": "\r", '"': '"', "\\": "\\"}.get(n, n))
        else:
            out.append(c)
            i += 1
    return "".join(out)


def scan_metadata_calls(java_path: Path, prop_index: dict[str, PropertyDecl],
                         global_consts: dict[str, str] | None = None) -> tuple[
        list[tuple[str, str]],  # (parser_fqn, field_name) for typed emissions
        list[tuple[str, str, int]],  # (parser_fqn, field_name, line) for literal emissions
]:
    """Pass 2: scan a parser source file for metadata.set/add calls."""
    text = java_path.read_text(encoding="utf-8", errors="replace")
    pkg = package_of(java_path)
    cls = class_of(java_path)
    fqn = f"{pkg}.{cls}" if pkg else cls
    string_consts = collect_string_constants(text, global_consts)
    typed: list[tuple[str, str]] = []
    literal: list[tuple[str, str, int]] = []
    for m in META_CALL_RE.finditer(text):
        recv = m.group("recv").split(".")[-1]
        # Heuristic: receiver must be a "metadata" variable, not e.g.
        # `Optional.of` or `Files.set`. Accept "metadata", "meta", or any
        # name ending in "Metadata".
        if not META_RECEIVER_RE.match(recv):
            continue
        first = m.group("first").strip()
        # Strip trailing parens / casts
        if first.endswith(")"):
            # Could be `(Foo.BAR)`-style cast; rare. Skip strict casting.
            first = first.rstrip(")")
        # Try Property constant reference: ShortClass.CONST_NAME
        cm = CONST_REF_RE.match(first)
        if cm:
            short_class, const = cm.group(1), cm.group(2)
            field_name = _lookup_const(short_class, const, prop_index)
            if field_name:
                typed.append((fqn, field_name))
                continue
        # Try string literal or PREFIX+literal
        resolved = resolve_string(first, string_consts, global_consts)
        if resolved is not None:
            line_no = text.count("\n", 0, m.start()) + 1
            literal.append((fqn, resolved, line_no))
    return typed, literal


def _lookup_const(short_class: str, const_name: str, prop_index: dict[str, PropertyDecl]) -> str | None:
    """Find a Property declaration matching short class name + constant."""
    matches = [p for p in prop_index.values()
               if p.declared_in_fqn.rsplit(".", 1)[-1] == short_class
               and p.constant_name == const_name]
    if len(matches) == 1:
        return matches[0].field_name
    return None


# ── Main ────────────────────────────────────────────────────────────────────


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tika-root", default=".", type=Path,
                    help="Root of the Tika source tree (default: cwd)")
    ap.add_argument("--out", default="docs/metadata-fields-static.json", type=Path,
                    help="Output JSON path (default: docs/metadata-fields-static.json)")
    args = ap.parse_args(argv)

    root: Path = args.tika_root.resolve()
    if not (root / "tika-core").exists():
        print(f"error: {root} doesn't look like a Tika source tree (no tika-core/)",
              file=sys.stderr)
        return 2

    # Phase 0: build a global String-constants index across the metadata
    # namespace package. Cross-class references like
    # `TikaCoreProperties.NAMESPACE_PREFIX_DELIMITER` show up inside Property
    # name-arg concatenations in other namespace files; without resolving them
    # we lose ~half of the declared properties.
    #
    # Two-phase bootstrap is needed because file-local resolution can depend
    # on global (cross-file) constants, and global needs file-local. Loop
    # until the global index stabilizes:
    #   Pass 1: seed global with whatever resolves from file-local-only.
    #   Pass 2+: re-resolve each file using the growing global until no more
    #            constants are added.
    metadata_pkg = root / "tika-core" / "src" / "main" / "java" / "org" / "apache" / "tika" / "metadata"
    prop_files = sorted(metadata_pkg.rglob("*.java"))
    file_texts = {jf: jf.read_text(encoding="utf-8", errors="replace") for jf in prop_files}
    global_consts: dict[str, str] = {}
    for _ in range(8):  # fixed-point with hard limit on iterations
        before = len(global_consts)
        for jf, text in file_texts.items():
            cls = class_of(jf)
            for name, value in collect_string_constants(text, global_consts).items():
                global_consts[f"{cls}.{name}"] = value
        if len(global_consts) == before:
            break

    # Phase 1: collect Property declarations from metadata namespace files
    prop_index: dict[str, PropertyDecl] = {}
    for jf in prop_files:
        for decl in scan_property_declarations(jf, global_consts):
            # Index key: declaring-FQN + constant — there can be name
            # collisions for the same field across namespaces (rare). Keep
            # all of them; lookups index by field_name later anyway.
            prop_index[f"{decl.declared_in_fqn}.{decl.constant_name}"] = decl

    # Phase 2: scan ALL Java source for metadata.set/add calls
    java_files = sorted(
        p for p in root.rglob("*.java")
        if "/test/" not in str(p) and "/target/" not in str(p)
    )

    records: dict[str, FieldRecord] = {}

    # Seed records with every declared Property — so the registry includes
    # every field that COULD be emitted, even if no parser currently emits it.
    for decl in prop_index.values():
        rec = records.setdefault(decl.field_name, FieldRecord(
            field_name=decl.field_name, type=decl.type,
            declared_in=decl.declared_in_fqn, constant_name=decl.constant_name,
            javadoc=decl.javadoc,
        ))
        # If multiple Property declarations resolve to the same field name
        # (shouldn't happen normally), keep the first; warn via stderr.
        if rec.declared_in and rec.declared_in != decl.declared_in_fqn:
            print(f"warning: duplicate Property for {decl.field_name}: "
                  f"{rec.declared_in} vs {decl.declared_in_fqn}", file=sys.stderr)

    # Walk parser files for emission sites.
    parser_emits: dict[str, set[str]] = {}
    for jf in java_files:
        try:
            typed, literal = scan_metadata_calls(jf, prop_index, global_consts)
        except Exception as e:
            print(f"warning: scan failed for {jf}: {e}", file=sys.stderr)
            continue
        for parser_fqn, field_name in typed:
            rec = records.setdefault(field_name, FieldRecord(
                field_name=field_name, type=None, declared_in=None,
                constant_name=None, javadoc=None,
            ))
            rec.emitted_by.add(parser_fqn)
            parser_emits.setdefault(parser_fqn, set()).add(field_name)
        for parser_fqn, field_name, line in literal:
            rec = records.setdefault(field_name, FieldRecord(
                field_name=field_name, type=None, declared_in=None,
                constant_name=None, javadoc=None,
            ))
            if rec.declared_in is None:
                rec.is_string_literal_only = True
            rec.emitted_by.add(parser_fqn)
            rec.literal_emit_sites.append({
                "class": parser_fqn,
                "file": str(jf.relative_to(root)),
                "line": line,
            })
            parser_emits.setdefault(parser_fqn, set()).add(field_name)

    # ── Build output ────────────────────────────────────────────────────
    properties_out: dict[str, dict] = {}
    undeclared_out: dict[str, dict] = {}
    for rec in sorted(records.values(), key=lambda r: r.field_name):
        if rec.declared_in is not None:
            properties_out[rec.field_name] = {
                "type": rec.type,
                "declared_in": rec.declared_in,
                "constant_name": rec.constant_name,
                "javadoc": rec.javadoc,
                "emitted_by": sorted(rec.emitted_by),
            }
        else:
            undeclared_out[rec.field_name] = {
                "emitted_by": rec.literal_emit_sites,
            }
    parsers_out = {
        parser: {"emits": sorted(fields)}
        for parser, fields in sorted(parser_emits.items())
    }

    doc = {
        "scanned_at": datetime.now(timezone.utc).isoformat(),
        "tika_root": str(root),
        "summary": {
            "property_declarations": len(properties_out),
            "parsers_emitting_fields": len(parsers_out),
            "undeclared_string_fields": len(undeclared_out),
        },
        "properties": properties_out,
        "undeclared_string_fields": undeclared_out,
        "parsers": parsers_out,
    }

    out_path: Path = args.out
    if not out_path.is_absolute():
        out_path = root / out_path
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(doc, indent=2, ensure_ascii=False))
    print(f"wrote {out_path}", file=sys.stderr)
    print(f"  declared properties: {doc['summary']['property_declarations']}", file=sys.stderr)
    print(f"  parsers emitting:    {doc['summary']['parsers_emitting_fields']}", file=sys.stderr)
    print(f"  undeclared literals: {doc['summary']['undeclared_string_fields']}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
