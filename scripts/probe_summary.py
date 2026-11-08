#!/usr/bin/env python3
"""Markdown summary of a probe report.

usage: probe_summary.py <report.json> <title>
"""
import json, sys
from pathlib import Path

path = Path(sys.argv[1])
print(f"## {sys.argv[2]}\n")
if not path.exists():
    print("The probe did not produce a report.\n")
    sys.exit(0)
report = json.loads(path.read_text())
plugins = report.get("plugins", [])
ok = [p for p in plugins if p.get("ok")]
print(f"**{len(ok)} of {len(plugins)}** extensions loaded, {len(report.get('providers', []))} providers registered.\n")
failed = [p for p in plugins if not p.get("ok")]
if failed:
    print("| Extension | Stage | Error |\n|---|---|---|")
    for p in failed:
        print(f"| {p['file']} | {p.get('stage')} | {str(p.get('error')).replace('|', '/')[:160]} |")
    print()
tests = report.get("tests", [])
if tests:
    def cell(t, key):
        v = t.get(key)
        if v is None:
            return "–"
        v = str(v)
        return "✅ " + v[3:] if v.startswith("ok") else "❌ " + v[5:].replace("|", "/")[:90]
    full = sum(1 for t in tests if (t.get("video_links") or 0) > 0)
    print(f"**{full} of {len(tests)}** providers returned playable video links.\n")
    print("| Provider | Home | Search | Details | Links | Videos | Subtitles |\n|---|---|---|---|---|---|---|")
    for t in tests:
        print(f"| {t['provider']} | {cell(t, 'home')} | {cell(t, 'search')} | {cell(t, 'load')} | {cell(t, 'links')} "
              f"| {t.get('video_links', '–')} {','.join(t.get('link_types') or [])} | {t.get('subtitles', '–')} |")
    print()
