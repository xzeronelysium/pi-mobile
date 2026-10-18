# Pi for Android

Pi is an Android client for working with Pi AI sessions on a phone. The app is built with Kotlin and Jetpack Compose, and runs the Pi Durable runtime in an app-owned WebView. Android adapters provide the runtime's filesystem, shell, and storage access; the app does not bundle Node.js or a Linux userland.

> **Project status:** Actively developed. GitHub Releases are used for signed APK downloads; no APK release has been published yet.

## What it does

- Chat with configured AI providers using Pi's session and tool workflows.
- Work with files in an Android workspace using the native Android runtime bridge.
- Keep runtime JavaScript bundled with the app; no separate Node installation is required.

Provider availability and individual capabilities depend on the current app implementation and configuration. This project is not affiliated with or endorsed by the providers it can connect to.

## Build

### Requirements

- Android Studio with a compatible Android SDK (compile SDK 36)
- JDK 17
- Node.js and npm for building and testing the bundled runtime

Set the Android SDK location in your local `local.properties` file (this file is intentionally not committed).

```sh
# Build the app
./gradlew :app:assembleDebug

# Run Android unit tests
./gradlew :app:testDebugUnitTest

# Build and test the JavaScript runtime bundle
cd runtime
npm ci
npm test
npm run build
```

The runtime build updates the generated bundle under `app/src/main/assets/native-runtime/`. Commit that generated output whenever its source or dependencies change. See [the architecture overview](docs/architecture.md) for how the Android and JavaScript parts fit together. Device/instrumentation tests require a connected Android device or emulator:

```sh
./gradlew :app:connectedDebugAndroidTest
```

## Security and permissions

The app requests network access and currently declares `MANAGE_EXTERNAL_STORAGE` for broad workspace file access. This is a sensitive Android permission and may be unsuitable for Google Play distribution without a policy-compliant alternative. Review the permission and the runtime's shell/file access before installing or using the app with sensitive data. Never commit provider credentials, signing keys, `local.properties`, or private user data.

## Release APKs

Create one Android signing keystore and keep it backed up securely. It is not supplied by GitHub: it is your app's long-term signing identity, and every APK update must use the same key. Generate it locally with `keytool` (which prompts for the passwords):

```sh
mkdir -p "$HOME/.android"
keytool -genkeypair -v \
  -keystore "$HOME/.android/pi-mobile-release.jks" \
  -alias pi-mobile -keyalg RSA -keysize 2048 -validity 10000
```

Do not commit the keystore or passwords. For a local signed build, export the signing values expected by Gradle (enter passwords interactively so they are not saved in shell history):

```sh
export ANDROID_KEYSTORE_FILE="$HOME/.android/pi-mobile-release.jks"
export ANDROID_KEY_ALIAS=pi-mobile
read -rsp 'Keystore password: ' ANDROID_KEYSTORE_PASSWORD; echo
export ANDROID_KEYSTORE_PASSWORD
read -rsp 'Key password: ' ANDROID_KEY_PASSWORD; echo
export ANDROID_KEY_PASSWORD
./gradlew :app:assembleRelease
```

Verify and checksum the resulting `app/build/outputs/apk/release/app-release.apk` before publishing it. A release build without these signing values is unsigned and cannot be installed as a normal app update.

To publish manually, update `versionName`, increment `versionCode`, and add release notes to `CHANGELOG.md`. Run tests and build a signed APK first, then create and push a matching tag and publish the APK with the GitHub CLI:

```sh
./gradlew :app:testDebugUnitTest
(cd runtime && npm ci && npm test && npm run build)
./gradlew :app:assembleRelease
sha256sum app/build/outputs/apk/release/app-release.apk > app-release.apk.sha256
git tag -a v1.0.0 -m "Pi for Android 1.0.0"
git push origin v1.0.0
gh release create v1.0.0 \
  app/build/outputs/apk/release/app-release.apk \
  app-release.apk.sha256 \
  --title "Pi for Android 1.0.0" \
  --generate-notes
```

GitHub Actions are not required for builds or releases; tests and packaging are run locally.

## Versioning

The Android app uses [Semantic Versioning](https://semver.org/) for `versionName` and a monotonically increasing integer for `versionCode`. Every release should have a corresponding `CHANGELOG.md` entry and a Git tag in the form `vMAJOR.MINOR.PATCH` (for example, `v1.2.0`). Do not reuse a `versionCode` once it has shipped. Breaking changes, new features, and fixes increment the major, minor, and patch components respectively, following SemVer's prerelease rules where appropriate.

## License

This project is available under the [MIT License](LICENSE). Third-party notices and attribution are collected in [`NOTICE`](NOTICE).
