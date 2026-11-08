# CloudXtream by Leque — Windows

Windows version of CloudXtream, built on the official CloudStream **library** (Kotlin Multiplatform,
desktop Java target) with a Compose Desktop interface and libVLC for playback. The Android app lives in
[YumAbd/cloudstream-leque](https://github.com/YumAbd/cloudstream-leque); both use the same CloudXtream
server (activation PIN, licenses, extension catalog).

## Download

Latest installer: [Releases](https://github.com/YumAbd/cloudxtream-desktop/releases/latest)
(`CloudXtream-x.y.z-setup.exe`, Windows 10/11 64-bit, VLC included).

## How it works

- **Activation** (`app/.../core/Activation.kt`): the same protocol as the Android app. The PC has its own
  RSA key (protected with Windows DPAPI); the PIN registers it on the license and every start proves it.
- **Extensions** (`core/Plugins.kt`): the published `.cs3` files of the CloudXtream catalog are converted
  from Android DEX to Java bytecode with dex2jar and loaded like the Android app loads them; `shim/` holds
  desktop stand-ins for the Android APIs they use. See [phase0/RESULTS.md](phase0/RESULTS.md).
- **Player** (`player/`): libVLC renders into Compose; a local proxy adds headers VLC cannot send.
- **Updates** (`core/Updates.kt`): new GitHub releases are offered in the app (SHA-256 checked).

## Building

```
gradle :app:run                 # run from source
gradle :app:packageExe          # Windows installer (on Windows)
gradle :app:devPreview          # screens after activation, without the server (development only)
```

The official CloudStream library (same commit as the Android app), its JitPack dependencies and the
AndroidX artifacts Compose uses are published to the `maven` branch by `.github/workflows/deps.yml`.
`.github/workflows/windows.yml` builds the installers, runs a smoke test that plays a real video with the
bundled libVLC, and publishes a release for `v*` tags.

## License

CloudStream is GPL-3.0; this project is distributed under the same license (see LICENSE).
