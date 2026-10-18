# Contributing

Thanks for helping improve Pi Mobile. The app combines native Android code and a bundled JavaScript runtime; changes to either side should keep the packaged app and its generated runtime in sync.

## Before opening a pull request

1. Start from an up-to-date `main` and make your change on a separate branch.
2. Keep changes focused and describe the user-visible behavior or maintenance benefit.
3. For UI changes, include current device screenshots when useful. Do not include credentials, personal conversations, or private workspace data.
4. Update documentation or `CHANGELOG.md` when behavior, setup, releases, or user-visible capabilities change.

## Local checks

Use JDK 17 and an Android SDK with API 36. For Kotlin/Android changes:

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

For runtime changes, use Node.js 22+ and npm:

```sh
cd runtime
npm ci
npm test
npm run build
cd ..
git diff -- app/src/main/assets/native-runtime/runtime.js
```

Review any generated runtime bundle changes and commit the bundle with the source that produced it. The runtime tests include a fresh-build comparison so a stale checked-in asset is caught. Instrumentation tests need a connected device or emulator:

```sh
./gradlew :app:connectedDebugAndroidTest
```

## Safety and scope

- Never commit API keys, OAuth tokens, keystores, passwords, `local.properties`, or private user data.
- Treat shell execution and external JavaScript packages as security-sensitive; shell runs with the app UID and is not sandboxed.
- Avoid claiming provider or Pi features work unless they are implemented and verified in this Android client.
- Do not add packaged executables or a Linux userland without discussing the runtime/security impact.

For release versioning, signing, and publication, see [docs/releasing.md](docs/releasing.md).
