# kalidroid-downloader

The KaliDroid online installer: a small Android app that downloads the KaliDroid
`setup.apk` (about 340 MB) from `https://kalidroid.com/setup.apk`, checks it, and hands
it to the system package installer. It also keeps itself up to date from this repo's
GitHub releases.

Requires Android 8.0 (API 26) or newer.

## How it works

1. **Download.** Tapping *Download & Setup* hands the file to Android's
   `DownloadManager`, so the download keeps going if the screen rotates, the app is
   closed or killed, or the network drops (it resumes where it stopped). The app saves
   the pending download and picks it up again whenever it is opened.
2. **Verify.** When the download finishes, the APK is copied into the app's private
   cache (`cache/apk/`, the only folder shared with the installer) and checked:
   - it is a valid, intact APK whose signature verifies;
   - `setup.apk` must be one of the pinned package names, signed by one of the pinned
     certificates (`SETUP_PACKAGE_NAMES` / `SETUP_SIGNER_SHA256` in
     [`ApkVerifier.kt`](app/src/main/java/com/nethunter/kalidroid/ApkVerifier.kt));
   - a self-update must match the SHA-256 from `version.json`, be a newer build of this
     app, and be signed with the same key as the installed copy.
3. **Install.** The system installer is opened while the app is on screen. If it is
   dismissed, an *Install* button stays available. Once the package is installed, the APK
   is deleted.

### Changing the setup.apk signing key

The installer refuses a `setup.apk` that isn't signed by a pinned certificate. To move
`setup.apk` to a new key (or package name):

1. Add the new certificate's SHA-256 (`apksigner verify --print-certs setup.apk`) to
   `SETUP_SIGNER_SHA256`, and any new package name to `SETUP_PACKAGE_NAMES` **and** to
   `<queries>` in `AndroidManifest.xml`.
2. Ship that installer build.
3. Only then start serving the newly signed `setup.apk` from kalidroid.com.

## Self-update

Every push to `master` runs [`build.yml`](.github/workflows/build.yml), which:

- builds a signed release APK (`versionCode` is the git commit count, so builds only go up);
- publishes it as `kalidroid.apk` on the rolling [`latest`](../../releases/tag/latest)
  release, followed by a `version.json` with its `versionCode`, SHA-256 and URL;
- moves the `latest` tag to the commit it was built from.

On launch the app reads `version.json`. If it lists a newer build, the app offers to
update; *Later* hides that build for 24 hours.

## Building

```sh
./gradlew assembleDebug           # debug build
./gradlew testDebugUnitTest       # JVM unit tests
./gradlew connectedDebugAndroidTest   # on-device tests (needs a device or emulator)
```

The on-device tests are most reliable with animations turned off on the device
(*Developer options › Window/Transition/Animator animation scale › Off*). If more than
one device is connected, pick one with `ANDROID_SERIAL=<serial>`.

Without the signing variables below, `assembleRelease` produces an unsigned APK.

### Release signing (CI secrets)

Release builds are signed with a keystore taken from these repository secrets
(*Settings › Secrets and variables › Actions*). The workflow refuses to publish without them.

| Secret | Value |
|---|---|
| `KALIDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `KALIDROID_KEYSTORE_PASSWORD` | keystore password |
| `KALIDROID_KEY_ALIAS` | key alias |
| `KALIDROID_KEY_PASSWORD` | key password |

Create a keystore with:

```sh
keytool -genkeypair -v -keystore release.jks -alias kalidroid -keyalg RSA -keysize 4096 -validity 10000
```

Keep a backup of it: an installed copy can only update to APKs signed with the same key.

Pull requests run [`check.yml`](.github/workflows/check.yml): unit tests, lint and a
debug build.
