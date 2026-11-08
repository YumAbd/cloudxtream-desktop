#!/usr/bin/env python3
"""Fetches the source of every CloudXtream catalog extension and lists them for the Gradle build.

usage: prepare_extensions.py <catalog url> <build dir>
Writes <build>/extensions.tsv (name, source dir, extra dependencies) and <build>/extensions.json.
"""
import json, re, subprocess, sys, urllib.request
from pathlib import Path

catalog_url, build = sys.argv[1], Path(sys.argv[2]).resolve()
src_root = build / "src"
src_root.mkdir(parents=True, exist_ok=True)
catalog = json.load(urllib.request.urlopen(urllib.request.Request(catalog_url, headers={"User-Agent": "cloudxtream-desktop-phase0"}), timeout=60))

repos = {}
for entry in catalog:
    url = entry.get("repositoryUrl") or ""
    m = re.match(r"^https://github\.com/([\w.-]+)/([\w.-]+)/?$", url)
    if m:
        repos.setdefault(f"{m.group(1)}/{m.group(2)}", []).append(entry)

DEP = re.compile(r'(?:implementation|api|compileOnly)\s*\(\s*"([^":\s]+:[^":\s]+:[^"\s]+)"\s*\)')
listed, info = [], []
for repo, entries in sorted(repos.items()):
    target = src_root / repo.replace("/", "__")
    if not target.exists():
        subprocess.run(["git", "clone", "--quiet", "--depth", "1", f"https://github.com/{repo}.git", str(target)], check=False)

    def extension_dirs():
        found = {}
        for build_file in list(target.glob("*/build.gradle.kts")) + list(target.glob("*/*/build.gradle.kts")):
            found.setdefault(build_file.parent.name.lower(), build_file.parent)
        return found

    dirs = extension_dirs() if target.exists() else {}
    wanted = {e["internalName"].lower() for e in entries}
    if target.exists() and not wanted & dirs.keys():
        # Some repositories keep the sources on another branch (the default one only has builds).
        heads = subprocess.run(["git", "-C", str(target), "ls-remote", "--heads", "origin"], capture_output=True, text=True).stdout
        for branch in sorted({l.split("refs/heads/")[-1] for l in heads.splitlines()}, key=lambda b: (b not in ("master", "main"), b)):
            if subprocess.run(["git", "-C", str(target), "fetch", "--quiet", "--depth", "1", "origin", branch]).returncode:
                continue
            subprocess.run(["git", "-C", str(target), "checkout", "--quiet", "--force", "FETCH_HEAD"], check=False)
            dirs = extension_dirs()
            if wanted & dirs.keys():
                print(f"{repo}: sources on branch {branch}")
                break
    for entry in entries:
        name = entry["internalName"]
        directory = dirs.get(name.lower())
        source = directory / "src" / "main" / "kotlin" if directory else None
        if source is None or not source.is_dir():
            info.append({"name": name, "repo": repo, "found": False})
            continue
        extra = sorted({d for d in DEP.findall((directory / "build.gradle.kts").read_text(errors="replace"))
                        if not d.startswith("com.github.recloudstream")})
        (build / "extprojects" / name).mkdir(parents=True, exist_ok=True)
        listed.append(f"{name}\t{source}\t{','.join(extra)}")
        info.append({"name": name, "repo": repo, "found": True, "source": str(source), "extra": extra,
                     "nsfw": "NSFW" in (entry.get("tvTypes") or [])})

(build / "extensions.tsv").write_text("\n".join(listed) + "\n")
(build / "extensions.json").write_text(json.dumps(info, indent=2))
print(f"{len(listed)} of {len(catalog)} catalog extensions found in {len(repos)} repositories")
