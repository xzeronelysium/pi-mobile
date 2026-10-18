# Changelog

Notable changes to Pi for Android are documented here. Entries are grouped by release; dates use `YYYY-MM-DD`.

## Unreleased

- Rework assistant markdown rendering: nested and task lists, grouped quotes, aligned tables,
  strikethrough, bare-URL links, hard breaks and escapes are now parsed; code blocks gained syntax
  colouring, a copy action, horizontal scrolling and long-block collapsing.
- Refresh the README with real device screenshots, current capabilities, build steps, and security notes.
- Expand the architecture guide with the current native-host and runtime boundaries.

## 1.0.0 — 2026-10-18

- First public release of Pi Mobile for Android.
- Kotlin and Jetpack Compose app hosting Pi Durable in an app-owned WebView.
- Native Android workspace, shell, persistence, provider and integration surfaces.
- Signed APK and SHA-256 checksum published on GitHub Releases.
