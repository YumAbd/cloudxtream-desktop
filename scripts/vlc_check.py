#!/usr/bin/env python3
"""Windows: records 25 seconds of the first video links (up to 6) with VLC and reports whether video arrived.

usage: vlc_check.py <links.json> <output dir>
"""
import json, subprocess, sys
from pathlib import Path

links = json.loads(Path(sys.argv[1]).read_text()) if Path(sys.argv[1]).exists() else []
out = Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
vlc = r"C:\Program Files\VideoLAN\VLC\vlc.exe"
print("## Phase 0: playback with libVLC on Windows\n")
if not links:
    print("No video link was found on Windows, so playback could not be tested.")
    sys.exit(0)
for i, link in enumerate(links[:6]):
    sample = out / f"sample{i}.ts"
    args = [vlc, "-I", "dummy", "--play-and-exit", "--run-time=25", link["url"],
            f":sout=#std{{access=file,mux=ts,dst={sample}}}", ":sout-keep"]
    headers = {k.lower(): v for k, v in (link.get("headers") or {}).items()}
    if link.get("referer"):
        args.append(f":http-referrer={link['referer']}")
    if headers.get("user-agent"):
        args.append(f":http-user-agent={headers['user-agent']}")
    try:
        subprocess.run(args, timeout=120, capture_output=True)
    except subprocess.TimeoutExpired:
        pass
    size = sample.stat().st_size if sample.exists() else 0
    other = sorted(set(headers) - {"referer", "user-agent"})
    verdict = "✅ video received" if size > 300_000 else "❌ no video"
    print(f"- **{link['provider']}** · {link['title']} · {link['type']}: {verdict} ({size // 1024} KB in 25 s)"
          + (f" · extra headers VLC cannot send: {', '.join(other)}" if other else ""))
