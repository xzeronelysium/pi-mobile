# Changelog

Notable changes to Pi for Android are documented here. Entries are grouped by release; dates use `YYYY-MM-DD`.

## 1.1.0 — 2026-10-18

- Update application version to 1.1.0 (version code 2).
- Add a left-edge workspace file drawer: browse the bound workspace with breadcrumbs and a
  per-folder filter, preview Markdown, code, text and images inline, attach any file to the prompt,
  copy its absolute path, or hand it to another app through a read-only FileProvider grant.
- Bind sessions to workspaces: each bound folder now owns its durable conversation store and its
  remembered session, so switching workspace no longer resumes or lists another workspace's
  conversations. The first workspace opened after upgrading adopts the old shared database once;
  switching workspace also clears the previous workspace's transcript, queue, tree, session list,
  staged attachments and file drawer.
- Rework assistant markdown rendering: nested and task lists, grouped quotes, aligned tables,
  strikethrough, bare-URL links, hard breaks and escapes are now parsed; code blocks gained syntax
  colouring, a copy action, horizontal scrolling and long-block collapsing.
- Refresh the README with real device screenshots, current capabilities, build steps, and security notes.
- Expand the architecture guide with the current native-host and runtime boundaries.
- Improve settings search and persist model preferences and provider catalogs across launches.
- Refine provider setup and provider/model preferences in settings.
- Improve Markdown correctness, accessibility and incremental rendering while assistant output streams;
  add targeted rendering tests and document QA coverage.
- Add focused regression coverage for workspace identity, session stores, file browsing and attachment UI.

## 1.0.0 — 2026-10-18

- First public release of Pi Mobile for Android.
- Kotlin and Jetpack Compose app hosting Pi Durable in an app-owned WebView.
- Native Android workspace, shell, persistence, provider and integration surfaces.
- Signed APK and SHA-256 checksum published on GitHub Releases.
