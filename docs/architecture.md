# Architecture

Pi Mobile pairs a native Android UI with the Pi Durable JavaScript runtime. Kotlin owns the app lifecycle and platform access; the packaged runtime runs in an app-owned WebView.

## System map

```text
Jetpack Compose UI
      │ events and requests
      ▼
DurableRuntime ── NativeRuntimeHost (WebView)
                         │
                    Pi Durable
                         │ AndroidNative bridge
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
   NativeFileSystem  NativeShell  NativeStorage
          │              │              │
    workspace I/O    /system/bin/sh   SQLite
```

## Components

- **Android UI** — Kotlin and Jetpack Compose under `app/src/main/java/dev/pi/mobile`.
- **Runtime source** — JavaScript modules under `runtime/src`.
- **Runtime bundle** — esbuild produces `app/src/main/assets/native-runtime/runtime.js`, which the app loads locally. The APK includes the generated bundle and the isolated JavaScript sandbox assets.
- **WebView host** — `NativeRuntimeHost` loads the runtime; `DurableRuntime` exposes the UI-facing operations and typed events.
- **Platform adapters** — Kotlin implements workspace file operations, bounded shell execution and streaming, HTTP transport, SQLite-backed storage, credential protection, and lifecycle cleanup.

No Node.js executable, Linux userland, or packaged shell is shipped. Commands use Android's `/system/bin/sh` (mksh). Command availability and permissions therefore vary by device and app/workspace configuration.

## Runtime development

From the repository root:

```sh
cd runtime
npm ci
npm test
npm run build
```

The build updates the generated asset in `app/src/main/assets/native-runtime/`. Commit that bundle alongside source changes. `runtime/test/build.test.mjs` verifies the bundle matches a fresh build and checks expected runtime features. Android tests run with `./gradlew :app:testDebugUnitTest`; device instrumentation tests require a connected device or emulator.

## Security model and limitations

The JavaScript runtime is not the platform security boundary. File operations pass through Kotlin adapters with workspace/path checks; shell commands run as the app UID and **are not sandboxed**. The app currently requests `MANAGE_EXTERNAL_STORAGE`, a sensitive permission with Android/Google Play policy implications.

Autonomous JavaScript runs in a separate Worker without the `AndroidNative` bridge and only receives declared inputs/outputs. This narrows access but does not make arbitrary third-party code trustworthy. See the [README security notes](../README.md#security-and-permissions) before installing or using the app with sensitive data.

The workspace file drawer hands a file to another app only through a read-only `FileProvider` URI with a one-shot read grant. Its configured paths cover the app's own files, app-external files, and shared storage — the same roots the workspace binding can use.
