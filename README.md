# VecDraw by Topz
Offline CorelDRAW-inspired vector editor for Android 10+ (Kotlin, no Compose, no network permission).

Build: push to GitHub → Actions → "Build APK" → download `VecDraw-debug-apk`. Or locally: `gradle assembleDebug` (JDK 17, Android SDK 34).
Project is `app/` + Gradle Kotlin DSL; Gradle wrapper is intentionally not committed (CI uses setup-gradle 8.4; run `gradle wrapper` locally if you want one).

Not implemented yet: Mesh fill, Envelope tool, Knife / Virtual Segment Delete, bitmap-pattern transparency, true contour offset, perspective extrude, panel re-flow on docking.
