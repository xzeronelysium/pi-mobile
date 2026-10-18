# Pi for Android

Pi is an Android client for working with Pi AI sessions on a phone. The app is built with Kotlin and Jetpack Compose, and runs the Pi Durable runtime in an app-owned WebView. Android adapters provide the runtime's filesystem, shell, and storage access; the app does not bundle Node.js or a Linux userland.

> **Project status:** Actively developed. A signed APK is published through GitHub Releases when a matching version tag is pushed. Builds are not yet published.

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

Push a stable version tag such as `v1.0.0` after updating `versionName`, incrementing `versionCode`, and adding the release notes to `CHANGELOG.md`. GitHub Actions verifies the tag/version match, runs the test suites, builds a signed APK, and attaches the APK and SHA-256 checksum to a GitHub Release.

Repository administrators must configure these GitHub Actions secrets before creating a release tag: `ANDROID_KEYSTORE_BASE64` (the base64-encoded release keystore), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Back up the signing keystore securely; future APK updates must use the same signing identity. Never commit the keystore or its passwords. A local `assembleRelease` without signing variables produces an unsigned APK, not an installable release.

## Versioning

The Android app uses [Semantic Versioning](https://semver.org/) for `versionName` and a monotonically increasing integer for `versionCode`. Every release should have a corresponding `CHANGELOG.md` entry and a Git tag in the form `vMAJOR.MINOR.PATCH` (for example, `v1.2.0`). Do not reuse a `versionCode` once it has shipped. Breaking changes, new features, and fixes increment the major, minor, and patch components respectively, following SemVer's prerelease rules where appropriate.

## License

This project is available under the [MIT License](LICENSE). Third-party notices and attribution are collected in [`NOTICE`](NOTICE).
