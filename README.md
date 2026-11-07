# CloudXtream by Leque — Windows (work in progress)

Windows version of CloudXtream, built on the official CloudStream **library** (Kotlin Multiplatform,
desktop Java target) with a Compose Desktop interface. The Android app lives in
[YumAbd/cloudstream-leque](https://github.com/YumAbd/cloudstream-leque); both use the same
CloudXtream server (activation PIN, licenses, extension catalog).

## Phase 0 — feasibility (current)

The workflow [Phase 0](.github/workflows/phase0.yml) answers, before any interface is built:

1. Does the CloudStream library from the same official commit as the Android app build for desktop Java?
2. Which of the CloudXtream catalog extensions compile from their sources for desktop Java, and why not
   the others (usually Android-only APIs)?
3. Do the main providers (Pencurimovie, MovieBox) return a home page, search results, details and video
   links from a Windows machine?
4. Can libVLC on Windows play the links they return?

Results appear on each run's summary page. No video links are stored in the uploaded artifacts.

## License

CloudStream is GPL-3.0; this project is distributed under the same license.
