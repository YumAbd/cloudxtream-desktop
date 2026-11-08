# CloudXtream by Leque — Windows (work in progress)

Windows version of CloudXtream, built on the official CloudStream **library** (Kotlin Multiplatform,
desktop Java target) with a Compose Desktop interface. The Android app lives in
[YumAbd/cloudstream-leque](https://github.com/YumAbd/cloudstream-leque); both use the same
CloudXtream server (activation PIN, licenses, extension catalog).

## Phase 0 — feasibility (current)

The workflow [Phase 0](.github/workflows/phase0.yml) answers, before any interface is built:

1. Does the CloudStream library from the same official commit as the Android app build for desktop Java?
2. Can the catalog extensions run on desktop Java **exactly as published** (.cs3, Android DEX)? The probe
   converts each one with [dex2jar](https://github.com/ThexXTURBOXx/dex2jar) and loads the plugin class
   named in its manifest, with small desktop stand-ins for the Android APIs extensions use
   (`phase0/stubs`: SharedPreferences, Base64, Uri, Log, Handler…). This would let the Windows app use the
   same catalog as Android, including extensions whose sources are not public.
3. For comparison: which extensions compile from their sources for desktop Java, and why not the others.
4. Do the providers return a home page, search results, details and video links from a Windows machine?
5. Can libVLC on Windows play the links they return?

Results appear on each run's summary page. No video links are stored in the uploaded artifacts.

## License

CloudStream is GPL-3.0; this project is distributed under the same license.
