#!/usr/bin/env python3
"""A legal "today" is the Europe/Sofia calendar day (PM-SYS-004). The app's clock runs in UTC, so
`LocalDate.now(clock)` is still yesterday from midnight to about 03:00 in Sofia — the bug #38 fixed
at five sites. Main code takes a calendar date off a clock only through the kernel:
`LocalDate.parse(toSofiaDate(clock.instant()))`. Tests pin their own dates and are not checked.
Escape a deliberate use with a trailing `// allow-clock-date` comment that says why."""
import re, sys, pathlib
ROOT = pathlib.Path(__file__).resolve().parent.parent
# Each of these takes its zone from the clock (UTC here) or from the machine — never Sofia by design.
CLOCK_DATE = re.compile(r'\b(?:(?:LocalDate|LocalDateTime|ZonedDateTime|OffsetDateTime|YearMonth|Year|MonthDay)\s*\.\s*now'
                        r'|(?:LocalDate|LocalDateTime)\s*\.\s*ofInstant)\s*\(')
SRC = sorted(p for p in ROOT.glob('*/src/main/**/*.kt') if 'build' not in p.parts)

bad = []
for p in SRC:
    for i, line in enumerate(p.read_text().splitlines(), 1):
        code = line.split('//')[0].strip()
        if 'allow-clock-date' in line or code.startswith(('*', '/*')): continue   # an escape, or KDoc
        if CLOCK_DATE.search(code): bad.append((p.relative_to(ROOT), i, code))
if bad:
    print(f"CLOCK DATES — {len(bad)} calendar date(s) read off a clock, not as the Sofia day (PM-SYS-004):")
    for f, i, code in bad: print(f"  {f}:{i}  {code}")
    print("  use LocalDate.parse(toSofiaDate(clock.instant())) — zues.kernel.toSofiaDate")
    sys.exit(1)
print(f"OK  no calendar date read off a clock except through toSofiaDate  ·  {len(SRC)} main source file(s)")
