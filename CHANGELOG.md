# Changelog

Notable changes to Pi for Android are documented here. Entries are grouped by release; dates use `YYYY-MM-DD`.

## Unreleased

- The sandbox runtimes answer with structure and honesty: a structured failure report — phase
  (boot / install / execution), the interpreter's own error type and message, and the user-code
  line when a frame proves one — instead of a Pyodide/WebAssembly traceback blob or a JS stack
  exposing worker internals; the full internal trace rides in the result details as debugging
  material. Both tools now render verified output records (path, bytes, SHA-256 checked against
  the file system) in one identical `[outputs]` format regardless of interpreter. A
  `sandbox_capabilities` tool reports what the sandboxes support and where their limits sit
  (interpreter versions, package purity, the egress allowlist, size and timeout caps), assembled
  from the enforced constants so the report cannot drift from enforcement. Lockfiles record the
  runtime beside the dependencies (Pyodide and the WebView version), and a plain package name
  reuses the last good lock's pin with no network round trip; explicit versions bypass the lock.
- Python joins the JS sandbox, the same way: `py_resolve` pins an exact PyPI version (pure-wheel
  verdict included — py_search has no analogue because PyPI retired its search endpoint), and
  `py_run` boots Pyodide (CPython on WebAssembly, pinned at v0.26.2 from jsDelivr) in the SAME
  isolated Worker — no app bridge, declared inputs as memoryviews and outputs through the shared
  `piWrite`, an async body, and every resolved package hashed into `.pi-py/lock.json`. micropip
  installs pure-Python wheels from PyPI; numpy, pandas and friends ship inside Pyodide itself;
  cold-start stays per run on purpose (the spawn-and-die guarantee comes first).
- `js_run` gains workspace modules: the agent writes reusable `.mjs` files into the workspace and
  declares them, and the run body reaches them as `piModules` — real ESM that may import other
  declared modules with relative specifiers. The sandbox serves them under a per-run token URL with
  no bridge access, refuses undeclared ones by name, and hashes each loaded module into
  `.pi-js/lock.json` so reused code is auditable across runs. The body can key a module by the path
  exactly as declared (URL files keep their real directory, aliases replace only the basename) and
  also by its resolved absolute path; the tool descriptions now teach the one-shot/reuse patterns
  with working examples.
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

## 1.0.0 — 2026-10-18

- First public release of Pi Mobile for Android.
- Kotlin and Jetpack Compose app hosting Pi Durable in an app-owned WebView.
- Native Android workspace, shell, persistence, provider and integration surfaces.
- Signed APK and SHA-256 checksum published on GitHub Releases.
