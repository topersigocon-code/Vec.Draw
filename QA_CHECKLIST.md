# Vec.Draw manual regression checklist

Run these checks on a debug APK built from the same commit. Record device model, Android version, commit, and result.

## Project compatibility
- [ ] Open a legacy single-page `.vdt` project.
- [ ] Open a multi-page v2 `.vdt` project.
- [ ] Save As, close, reopen, and compare page count, dimensions, layers, guides, text, paths, and embedded images.
- [ ] Pause/resume the app and verify autosave recovery.

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
