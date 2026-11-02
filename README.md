# VecDraw by Topz
Offline CorelDRAW-inspired vector editor for Android 10+ (Kotlin, no Compose, no network permission).

Build: push to GitHub → Actions → "Build APK" → download `VecDraw-debug-apk`. Or locally: `gradle assembleDebug` (JDK 17, Android SDK 34).
Project is `app/` + Gradle Kotlin DSL; Gradle wrapper is intentionally not committed (CI uses setup-gradle 8.4; run `gradle wrapper` locally if you want one).

Added: Knife, Virtual Segment Delete, Envelope (4-corner), Mesh fill (colour grid, vector output).
Transparency: uniform (α slider), fountain and bitmap-pattern masks (built-in patterns or your own image).
Mesh points can be dragged. Mesh edges are cubic curves (drag a selected point's 4 handles)., true contour offset, perspective extrude, panel re-flow on docking.

Docking: Properties/Status/Toolbox/Palette are docked by default; docked panels shrink the canvas, floating ones overlay it. Dragging Properties/Palette/Status to a left/right edge turns them into a column, to top/bottom into a strip.
Saved between sessions (SharedPreferences, on pause): panel positions/sizes/visibility, snapping and grid toggles, last tool, default fill/outline style, mesh/polygon/star settings. Menu: Reset panel layout.
Autosave: the drawing is written to internal storage (autosave.vdt) whenever the app is paused and restored on next launch (undo history is not kept). Use Save project to keep a copy you can share.
