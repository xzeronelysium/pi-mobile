# Architecture

Pi for Android keeps the interface and platform access in Android, and runs the Pi Durable JavaScript runtime inside an app-owned WebView.

## Components

- **Android UI:** Kotlin and Jetpack Compose screens under `app/src/main/java/dev/pi/mobile`.
- **Runtime:** JavaScript modules under `runtime/src`, bundled with esbuild into `app/src/main/assets/native-runtime/runtime.js`.
- **WebView host:** `NativeRuntimeHost` loads the bundled runtime and provides its bridge to Android.
- **Platform adapters:** Kotlin code handles workspace files, Android's `/system/bin/sh`, SQLite-backed storage, network transport, and app lifecycle.
- **Credentials:** provider credentials are protected using Android Keystore-backed encryption.

The app does not package Node.js, a Linux userland, or a separate shell binary. Shell commands run through Android's `/system/bin/sh`; shell behavior and available commands therefore follow the device's Android environment.

## Runtime development

From the repository root:

```sh
cd runtime
npm ci
npm test
npm run build
```

The build writes the generated runtime bundle into the Android assets directory. Keep that generated file in sync with its sources; CI checks that building it produces no uncommitted bundle changes.

## Security boundary

The JavaScript runtime is not itself the Android security boundary. Filesystem and process operations pass through Kotlin host adapters, which enforce the app's workspace and platform constraints. The app currently requests `MANAGE_EXTERNAL_STORAGE` for workspace access; see the security note in the [README](../README.md) before installing or distributing it.
