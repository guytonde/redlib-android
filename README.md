# Redlib for Android

Runs redlib (https://github.com/redlib-org/redlib) on the phone itself and shows it in a WebView. No public instance is involved: the app starts a redlib server on 127.0.0.1, so Reddit only ever sees requests from your own device and IP.

## How it works

The redlib binary is cross compiled, unmodified, with the NDK and packaged as `jniLibs/<abi>/libredlib.so`. Android 10+ only lets apps execute files from their native library directory, so the APK uses legacy packaging, which extracts it there on install.

`RedlibServer` launches it on a free localhost port, mirrors its output to logcat, and restarts it if it crashes. `MainActivity` waits for the port to answer, then loads it in a WebView. Links to other sites open in your browser. Redlib runs as long as the app does; there is no background service or permanent notification.

The redlib source, its version and the toolchain are pinned in `versions.env`.

## Which redlib

By default the app builds [Nachtalb's fork](https://github.com/Nachtalb/redlib), not upstream redlib. When Reddit blocks its JSON API, which it currently does for many IPs, upstream can only show an error page. The fork falls back to other ways of fetching a page, such as GraphQL and scraping. It also includes unmerged upstream fixes. Its [FORK.md](https://github.com/Nachtalb/redlib/blob/main/FORK.md) lists the changes.

The fork has one maintainer, and it is tracked by commit rather than by release (see below), so skim its history before relying on it. To build upstream instead, set `REDLIB_REPO=https://github.com/redlib-org/redlib.git` and `REDLIB_REF` to a tag such as `v0.40.0` in `versions.env`.

## Build with GitHub Actions (easiest)

1. Push this project to a GitHub repo.
2. The Build APK workflow uploads `redlib-android-<version>.apk` as an artifact. Pushing a tag such as `v0.1.0` also attaches it to a GitHub release, which Obtainium can track for updates.
3. Optional but recommended: add the repo secrets `REDLIB_KEYSTORE_BASE64`, `REDLIB_KEYSTORE_PASSWORD`, `REDLIB_KEY_ALIAS` and `REDLIB_KEY_PASSWORD`. Without them every run signs with a new debug key, and you have to uninstall before each update.

Create a key once with:

```sh
keytool -genkeypair -v -keystore release.jks -alias redlib -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks   # value for REDLIB_KEYSTORE_BASE64
```

## Automatic redlib updates

`upstream.yml` runs daily and checks `REDLIB_REPO` for something newer than `REDLIB_REF`:

- if `REDLIB_REF` is a commit hash (the default, since the fork publishes no releases), it follows the newest commit on the default branch
- if `REDLIB_REF` is a tag, it follows the newest stable tag

When there is something newer, it builds it with this shim. When the build passes it commits the bump to main and publishes a GitHub release named `android-<short hash or tag>` with the APK, which Obtainium can follow. A failing build changes nothing, so you get a failed run instead of a broken main. A passing build only proves the code compiles: a fork commit that builds but behaves badly still ships. Run it by hand from the Actions tab with Run workflow. Needs no extra secrets, but main must allow pushes from GitHub Actions (no branch protection blocking it).

## Build locally (Linux or macOS)

You need rustup, cmake, clang with libclang (BoringSSL's bindings are generated with bindgen), a host C compiler, git, JDK 17 and the Android SDK (Android Studio installs it). On Arch: `sudo pacman -S rustup cmake clang jdk17-openjdk`. On Debian or Ubuntu: `sudo apt install build-essential cmake clang libclang-dev openjdk-17-jdk`.

```sh
rustup target add aarch64-linux-android
cargo install cargo-ndk --locked
sdkmanager "ndk;28.2.13676358"

./gradlew assembleDebug     # runs scripts/build-redlib.sh first
adb install bin/redlib-android-*-debug.apk
```

The first build takes 10 to 20 minutes because of BoringSSL and redlib's LTO release profile. Later builds only recompile when something changes.

On Windows, build the binary in WSL (or let CI do it), copy `app/src/main/jniLibs` over, then run `gradlew assembleDebug -Predlib.skip=true`.

## Options

| What | Where |
|---|---|
| Update redlib | change `REDLIB_REF` in `versions.env` (tag, branch or commit) |
| Switch between the fork, upstream or another fork | change `REDLIB_REPO` in `versions.env` |
| Build for the emulator too | `-Predlib.abis=arm64-v8a,x86_64` or edit `gradle.properties` |
| Build a local redlib checkout | `REDLIB_SRC=/path/to/redlib ./gradlew assembleDebug` |
| Instance defaults (theme, layout, NSFW and so on) | `defaultEnv` in `RedlibServer.kt`, using redlib's `REDLIB_DEFAULT_*` variables |
| Use your own release key locally | set `REDLIB_KEYSTORE`, `REDLIB_KEYSTORE_PASSWORD`, `REDLIB_KEY_ALIAS`, `REDLIB_KEY_PASSWORD` |

Per user settings and subscriptions live in redlib's settings page and are stored in the WebView's cookies, so they survive restarts.

## Opening Reddit links in the app

Share any link to Redlib, or make it the handler for reddit.com links: on Android 12+ go to App info, Open by default, Add links, and tick the reddit domains. Both `redd.it` short links and `i.redd.it` images are understood.

## Debugging

```sh
adb logcat -s redlib
```

If redlib fails to start, the app shows the error and its last 40 lines of output.

## Notes

1. If Android kills the app in the background, the next launch restarts redlib (a couple of seconds) and reopens the page you were on.
2. Reddit can still rate limit or block redlib's requests. Running locally means those limits apply to your IP alone instead of a busy shared instance. The first page after starting can take a while if the fork has to fall back to another method.
3. Redlib is AGPL 3.0 and so is this wrapper (see `LICENSE`). If you distribute APKs, publish the source with them.
