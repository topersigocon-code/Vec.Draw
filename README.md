# VecDraw by Topz
Offline CorelDRAW-inspired vector editor for Android 10+ (Kotlin, no Compose, no network permission).

Build: push to GitHub → Actions → "Build APK" → download `VecDraw-debug-apk`. Or locally: `gradle assembleDebug` (JDK 17, Android SDK 34).
Project is `app/` + Gradle Kotlin DSL; Gradle wrapper is intentionally not committed (CI uses setup-gradle 8.4; run `gradle wrapper` locally if you want one).

Added: Knife, Virtual Segment Delete, Envelope (4-corner), Mesh fill (colour grid, vector output).
Transparency: uniform (α slider), fountain and bitmap-pattern masks (built-in patterns or your own image).
Not implemented yet: moving mesh vertices, true contour offset, perspective extrude, panel re-flow on docking.
