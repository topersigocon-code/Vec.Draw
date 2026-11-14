# Android app-signing key recovery

## Findings from the supplied archives

The original `Vec.Draw-main (1).zip` and the supplied development archives do not contain a `.jks`, `.keystore`, `.p12`, `key.properties`, or signing configuration with a previous key fingerprint. The Gradle properties file contains only standard AndroidX/Kotlin settings. The supplied GitHub Actions workflows build debug APKs only and do not reference a release-signing secret.

Therefore the original permanent signing identity cannot be reconstructed from these archives. Do not generate a replacement key and describe it as the original.

## If the original key is available

Keep the keystore outside the repository. Supply these environment variables to Gradle:

- `VEC_DRAW_STORE_FILE`: absolute path to the original `.jks`/`.keystore`
- `VEC_DRAW_STORE_PASSWORD`: keystore password
- `VEC_DRAW_KEY_ALIAS`: original key alias
- `VEC_DRAW_KEY_PASSWORD`: private-key password

Then build with `gradle :app:assembleRelease`. Verify the resulting APK certificate with `apksigner verify --print-certs app/build/outputs/apk/release/app-release-unsigned.apk` or the corresponding signed APK path for the configured Android Gradle Plugin version.

Do not put passwords or keystore bytes in Git, ZIP releases, source files, or chat messages. For GitHub Actions, store the values as repository/environment secrets and decode the keystore from a base64 secret into a temporary runner file; remove it after the build.

## If the original key is lost

An APK signed with a newly generated key will not update an existing installation signed by the old key. For ordinary sideloaded distribution, users must uninstall the old app before installing a differently signed build (which removes local app data unless separately backed up). For Google Play App Signing, use Play Console's supported upload-key reset process if the lost key is the upload key; an app-signing key managed by Google is a different case.
