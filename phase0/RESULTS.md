# Phase 0 results (8 Nov 2026)

Run: https://github.com/YumAbd/cloudxtream-desktop/actions/runs/42443256301 — official CloudStream
`5024ace` (same commit as the Android app), catalog of 38 extensions.

| Question | Result |
|---|---|
| Official library builds for desktop Java | ✅ `library-jvm` from the pinned commit, no changes |
| Published `.cs3` extensions run on desktop Java (dex2jar) | ✅ **38 of 38 load** on Linux and Windows, 39 providers |
| Providers work end to end (home → search → details → links) | ✅ **18–19 of 30** non-NSFW providers return video links on Windows and Linux |
| libVLC on Windows plays the links | ✅ **5 of 6** links tested played (direct MP4 and HLS/M3U8) |
| Extensions compiled from source (for comparison) | 22 of 33 with public sources; phisher98 sources are no longer public |

## What made it work
- `.cs3` files are converted with dex2jar at load time **keeping Kotlin method names**
  (`constructor-impl`), then loaded with one class loader per plugin (as the app does).
- Desktop stand-ins for the Android APIs the catalog uses (`phase0/stubs`) and `org.json`.
- JVM flag `-XX:-BytecodeVerificationRemote`: settings-screen code that refers to Android UI classes
  only fails if it runs.

## Open items for Phase 1
- Providers with no links in Phase 0: Idlix, Kuramanime, YouTube (empty home), Otakudesu (load error),
  PMSM (VidStack decrypt), KikoNime (timeout), Donghuastream/Donghuaword/Donghub/Ngefilm/LayarKaca/
  Dubbindo (0 links). To check against Android: several need a WebView (Cloudflare, JS players),
  which the desktop app will provide with an embedded browser (JCEF/WebView2).
- Extension settings screens (Android UI) are not available on desktop.
- Headers VLC cannot send (`origin`, `accept`): play through a local proxy in the app.
