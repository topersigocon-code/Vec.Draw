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
SVG import (paths, basic shapes, groups, transforms, solid colours), true contour offset (path booleans), perspective extrude, and mesh / gradient / pattern frames that rotate with the object.
Settings (☰ → Settings…): canvas/grid/guide/selection colours, grid opacity, panel opacity, tool icon size, snap radius and object-snap types, freehand smoothing, undo steps. Panels: drag title to move, ◢ to resize, tap title for dock/size menu; panels never overlap each other (they snap flush or are moved to the nearest free spot).
Toolbox is a single column; ▾ on any panel opens a flyout with everything in it. Hiding a panel collapses it to its title bar (tap the title to bring it back).
Settings now include Touch handle size. Properties controls are grouped (Colour / Outline / Effects / Size & position) and wrap in the vertical / flyout layout. Panels → Tidy titles lines up collapsed title bars.
Group / Ungroup, Copy / Cut / Paste (+ in place), Align and Distribute (groups move as one), Combine / Break apart with real holes (even-odd), Weld / Intersect / Exclude / Trim / Subtract. Results of shape operations are fine polylines. Holes of compound shapes are not node-editable.
Rulers with drag-out guides (drag a guide back onto its ruler to delete it), View menu (zoom to page / selection / 100%), Export selection, Step and repeat, saved Styles, two-finger tap = undo / three-finger tap = redo.
Lasso fill tool (tap a region = smart fill, drag a loop = lasso, modes via long-press), fonts (built-in + imported .ttf/.otf, embedded in SVG), text and image rotation, convert text to curves, radial gradients and a middle colour stop.
Batch A: Trace bitmap (threshold / colour levels, holes), Put inside shape (live clip, also for text and images; Release from shape), Blend along path, Symbols library (saved in app storage), stylus pressure for Freehand.
Trace bitmap now has a live preview panel (canvas stays visible; computed off the UI thread) and a centerline mode (thinning -> open stroked lines).
B1 multi-page: a project is a list of pages (each its own size, layers, guides); Quick bar ◀ n/m ▶ and ☰ → Pages…; PDF of all pages, ZIP of per-page SVG / PNG; .vdt v2 (older single-page files still open). Undo history resets when switching page.
Layouts: Classic panels (default) or Full canvas (Settings -> Layout, or menu -> Layout): small buttons (menu, last-3-tools hotbar, undo/redo/settings, zoom/fit, colours), contextual bar when something is selected, popup tree menu with any depth, left-handed mirror, buttons fade while drawing. All menus and dialogs share the app theme; colour dialog has a colour wheel.
