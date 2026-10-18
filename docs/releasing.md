# Releasing Pi Mobile

Pi Mobile releases are built and published manually. Do not publish an APK from an unverified or dirty checkout.

## Prepare

1. Update `versionName` and increment `versionCode` in `app/build.gradle.kts`.
2. Add release notes under the appropriate version in `CHANGELOG.md`.
3. Regenerate the JavaScript asset if runtime source or dependencies changed:

   ```sh
   cd runtime
   npm ci
   npm test
   npm run build
   cd ..
   ```

4. Run tests and build the APK:

   ```sh
   ./gradlew :app:testDebugUnitTest
   ./gradlew :app:assembleRelease
   ```

5. Verify that the release APK is signed with the established release key and inspect its package metadata. Keep the keystore backed up securely; future updates must use the same signing identity.

Gradle reads release signing configuration from `ANDROID_KEYSTORE_FILE`, `ANDROID_KEY_ALIAS`, `ANDROID_KEYSTORE_PASSWORD`, and `ANDROID_KEY_PASSWORD`. Supply secrets using the local secret manager/environment; do not commit them or place passwords in shell history.

## Checksum and publication

From the repository root:

```sh
APK=app/build/outputs/apk/release/app-release.apk
sha256sum "$APK" > app-release.apk.sha256
gh release create vX.Y.Z \
  "$APK" \
  app-release.apk.sha256 \
  --target main \
  --title "Pi for Android X.Y.Z" \
  --generate-notes
```

Use the version and tag actually chosen for the release. After publication, verify the release page, tag, APK asset, and checksum asset on GitHub. The latest stable APK is linked from the repository README.
