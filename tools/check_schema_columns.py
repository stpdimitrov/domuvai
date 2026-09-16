#!/usr/bin/env python3
"""Every entity property maps to a column the schema actually has.

Spring Data JDBC turns a Kotlin property into a snake_case column by inserting an underscore
before each capital and none before a digit — so `areaM2` -> `area_m2` (which the schema has)
but `isChildUnder6` -> `is_child_under6` (which it spells `is_child_under_6`). That mismatch is
a runtime `column does not exist` error, so it is invisible to `./gradlew test` on a machine
without Docker and only turns up in CI. This check reproduces the mapping and compares it to
the schema statically, failing the build here instead of there.

It reads the naming convention off the working entities: the rule below is exactly the one
that makes `area_m2`, `ideal_parts_pct` and `charge_run_id` line up. Override a column name in
the entity with `@Column("...")`; the check honours it. Mark a property that is deliberately
not a column with a trailing `// not-a-column` comment.
"""
import re, sys, pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCHEMA = ROOT / 'app/src/main/resources/db/migration/V1__init.sql'
SRC = ROOT / 'app/src/main'

CONSTRAINT_KEYWORDS = {'UNIQUE', 'CHECK', 'PRIMARY', 'FOREIGN', 'CONSTRAINT', 'EXCLUDE'}
TABLE = re.compile(r'@Table\("([^"]+)"\)')
COLUMN = re.compile(r'@Column\("([^"]+)"\)')
PROP = re.compile(r'\bval\s+(\w+)\s*:')


def to_column(prop: str) -> str:
    """Spring's default: underscore before each capital (not the first char), none before a digit."""
    out = []
    for i, ch in enumerate(prop):
        if ch.isupper() and i > 0:
            out.append('_')
        out.append(ch.lower())
    return ''.join(out)


def parse_schema(sql: str) -> dict[str, set[str]]:
    """Map each unqualified table name to its set of column names (search_path makes names unique)."""
    sql = re.sub(r'--[^\n]*', '', sql)          # strip line comments, incl. their stray parens
    tables: dict[str, set[str]] = {}
    for m in re.finditer(r'CREATE TABLE\s+\w+\.(\w+)\s*\(', sql):
        open_paren = m.end() - 1
        depth, i = 0, open_paren
        while i < len(sql):
            if sql[i] == '(':
                depth += 1
            elif sql[i] == ')':
                depth -= 1
                if depth == 0:
                    break
            i += 1
        body = sql[open_paren + 1:i]

        cols: set[str] = set()
        depth, current = 0, ''
        for ch in body:
            if ch == '(':
                depth += 1; current += ch
            elif ch == ')':
                depth -= 1; current += ch
            elif ch == ',' and depth == 0:
                cols |= _column_of(current); current = ''
            else:
                current += ch
        cols |= _column_of(current)
        tables[m.group(1)] = cols
    return tables


def _column_of(definition: str) -> set[str]:
    tokens = definition.split()
    if not tokens or tokens[0].upper() in CONSTRAINT_KEYWORDS:
        return set()
    return {tokens[0].lower()}


def entities(text: str):
    """Yield (table, [(property, explicit_column_or_None)]) for each @Table data class."""
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        tm = TABLE.search(lines[i].split('//')[0])
        if not tm:
            i += 1
            continue
        table = tm.group(1)
        j = i
        while j < len(lines) and 'class' not in lines[j]:
            j += 1
        props, depth, started = [], 0, False
        while j < len(lines):
            code = lines[j].split('//')[0]
            pm = PROP.search(code)
            if pm and started and 'not-a-column' not in lines[j]:
                cm = COLUMN.search(code)
                props.append((pm.group(1), cm.group(1) if cm else None))
            if '(' in code:
                started = True
            depth += code.count('(') - code.count(')')
            if started and depth <= 0:
                break
            j += 1
        yield table, props
        i = j + 1


def main() -> int:
    tables = parse_schema(SCHEMA.read_text())
    checked, bad = 0, []
    for path in SRC.rglob('*.kt'):
        for table, props in entities(path.read_text()):
            checked += 1
            rel = path.relative_to(ROOT)
            if table not in tables:
                bad.append(f"  {rel}  @Table(\"{table}\") — no such table in the schema")
                continue
            for prop, explicit in props:
                col = explicit or to_column(prop)
                if col.lower() not in tables[table]:
                    bad.append(f"  {rel}  `{prop}` -> column `{col}`, absent from {table} "
                               f"(has: {', '.join(sorted(tables[table]))})")

    if bad:
        print(f"ENTITY/SCHEMA COLUMN MISMATCH — {len(bad)}:")
        print("\n".join(bad))
        print("\nName the column explicitly with @Column(\"...\") on the property, "
              "or fix the schema. If the property is not persisted, mark it `// not-a-column`.")
        return 1
    print(f"OK  {checked} entit(ies) map only to columns the schema has  ·  {len(tables)} tables read")
    return 0


if __name__ == '__main__':
    sys.exit(main())
