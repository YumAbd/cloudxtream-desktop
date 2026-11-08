#!/usr/bin/env python3
"""Downloads every catalog extension exactly as the Android app installs it (.cs3).

usage: fetch_cs3.py <catalog url> <output dir>
"""
import json, sys, urllib.request
from pathlib import Path

out = Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
agent = {"User-Agent": "cloudxtream-desktop-phase0"}
catalog = json.load(urllib.request.urlopen(urllib.request.Request(sys.argv[1], headers=agent), timeout=60))
ok = 0
for entry in catalog:
    try:
        data = urllib.request.urlopen(urllib.request.Request(entry["url"], headers=agent), timeout=120).read()
        (out / f"{entry['internalName']}.cs3").write_bytes(data)
        ok += 1
    except Exception as e:  # report and continue: one broken link must not hide the others
        print(f"{entry['internalName']}: download failed: {e}")
print(f"{ok} of {len(catalog)} catalog extensions downloaded")
