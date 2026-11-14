# Vec.Draw manual regression checklist

Run these checks on a debug APK built from the same commit. Record device model, Android version, commit, and result.

## Project compatibility
- [ ] Open a legacy single-page `.vdt` project.
- [ ] Open a multi-page v2 `.vdt` project.
- [ ] Save As, close, reopen, and compare page count, dimensions, layers, guides, text, paths, and embedded images.
- [ ] Pause/resume the app and verify autosave recovery.

## Undo/redo and document state
- [ ] Draw an object, undo, and redo; verify geometry and styling are restored.
- [ ] Add, rename, duplicate, reorder, and delete layers; undo and redo each operation.
- [ ] Toggle layer and object visibility/locking; undo and redo each change.
- [ ] Change page dimensions, units, and grid spacing; undo and redo each change.
- [ ] Add/move/remove guides; verify guide edits participate in undo/redo.
- [ ] Cancel an in-progress drag and verify the full pre-gesture document state is restored.
- [ ] Verify the configured undo limit is respected on a large document.
- [ ] Confirm page add/delete/reorder history behavior is documented (project-level history is not yet implemented).

## Layers and Object Manager
- [ ] Add and rename a layer; verify the name persists after save/reopen.
- [ ] Duplicate a populated layer; verify objects are copied and remain independently editable.
- [ ] Move a layer up and down; verify objects stay on the intended layer and visual stacking changes correctly.
- [ ] Delete a layer with objects; verify objects move to the indicated surviving layer.
- [ ] Attempt to delete the final layer; verify the app keeps one layer.
- [ ] Toggle layer/object visibility and lock; verify hidden/locked content cannot be edited unintentionally.

## Editing and rendering
- [ ] Draw and edit paths with straight and curved nodes.
- [ ] Test group, ungroup, combine, break apart, weld, intersect, exclude, trim, and subtract.
- [ ] Test gradients, mesh fills, transparency, clipping, shadows, and rotation.
- [ ] Test text, imported fonts, text on path, and convert to curves.
- [ ] Test stylus pressure if supported by the device.

## Import/export
- [ ] Import representative SVG files containing paths, groups, transforms, gradients, clipping, and text.
- [ ] Export SVG and reopen it in Vec.Draw and at least one independent SVG viewer.
- [ ] Export PDF and verify page size, page count, transparency, and text/vector fidelity.
- [ ] Export PNG/JPEG at multiple resolutions and verify dimensions.

## Stability and performance
- [ ] Test a document with 500+ objects and several complex paths.
- [ ] Repeatedly undo/redo long edit sequences.
- [ ] Rotate the device and switch between Classic and Full Canvas layouts.
- [ ] Confirm no crash, ANR, or unrecoverable project corruption during the above workflows.

## Update 3 — Object Manager

- [ ] Open a legacy VDT document; confirm unnamed objects display generated labels.
- [ ] Rename an object, save, close, reopen; confirm its name persists.
- [ ] Duplicate PATH, TEXT, and IMAGE objects; confirm geometry and appearance remain intact and copies are offset.
- [ ] Reorder objects within a layer; confirm stacking changes and other layers remain intact.
- [ ] Search by object name, text contents, type, and layer name; clear search and confirm all entries return.
- [ ] Undo/redo rename, duplicate, and reorder operations.
- [ ] Build and install on Android API 29 and a current Android release.

## Release signing

- [ ] Confirm the keystore file is the original by comparing its certificate SHA-256 fingerprint with a previously released APK.
- [ ] Confirm alias and passwords using secure CI secrets, not committed files.
- [ ] Build release and verify APK certificate with apksigner.
- [ ] Test upgrade over the existing installed app before distribution.

## Font import (Update 4.1)
- [ ] Import one `.ttf` from internal storage and select it in the text editor.
- [ ] Import one `.ttf` from an external SD card using the system document picker.
- [ ] Import a `.zip` containing multiple `.ttf` / `.otf` files; verify all valid fonts appear in the text font list.
- [ ] Import a ZIP containing folders and unrelated files; verify unsupported entries are skipped without crashing.
- [ ] Import a corrupt ZIP and a ZIP with invalid font data; verify a clear failure/empty result and no app crash.
- [ ] Import a font whose filename already exists; verify the existing font is not overwritten.
- [ ] Force-close and reopen the app; verify imported fonts remain available.
