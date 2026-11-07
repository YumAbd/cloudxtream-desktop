#!/usr/bin/env python3
"""Markdown report: which catalog extensions compile for desktop Java, and why the others do not.

usage: compat_report.py <build dir>   (reads compile.log and extensions.json, copies jars to ext-jars/)
"""
import json, re, shutil, sys
from pathlib import Path

build = Path(sys.argv[1]).resolve()
info = json.loads((build / "extensions.json").read_text())
log = (build / "compile.log").read_text(errors="replace") if (build / "compile.log").exists() else ""
errors = re.findall(r"^e: file://(\S+?):(\d+):\d+ (.*)$", log, re.M)
jars = build / "ext-jars"
jars.mkdir(exist_ok=True)

rows, ok = [], 0
for ext in info:
    name = ext["name"]
    if not ext["found"]:
        rows.append((name, ext["repo"], "❓ source not found", ""))
        continue
    jar = next((build / "extprojects" / name / "build" / "libs").glob("*.jar"), None) if (build / "extprojects" / name / "build" / "libs").exists() else None
    mine = [(p, l, m) for p, l, m in errors if p.startswith(ext["source"])]
    if jar and not mine:
        shutil.copy(jar, jars / f"{name}.jar")
        ok += 1
        rows.append((name, ext["repo"], "✅ compiles", ""))
        continue
    android = sorted({m.split("'")[1] for _, _, m in mine if "Unresolved reference" in m and "'" in m})
    why = ("Android API: " + ", ".join(android[:6])) if android else (mine[0][2][:140] if mine else "build failed (see log)")
    rows.append((name, ext["repo"], "❌ does not compile", why.replace("|", "/")))

found = sum(1 for e in info if e["found"])
print(f"## Phase 0: catalog extensions on desktop Java\n\n**{ok} of {found}** extensions with sources compile "
      f"for desktop Java ({len(info)} in the catalog).\n")
print("| Extension | Repository | Result | Reason |\n|---|---|---|---|")
for r in rows:
    print("| " + " | ".join(r) + " |")
(build / "compat.md").write_text("\n".join("| " + " | ".join(r) + " |" for r in rows) + "\n")
