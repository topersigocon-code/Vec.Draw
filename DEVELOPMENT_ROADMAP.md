# Vec.Draw development roadmap

This roadmap prioritizes a reliable Android vector editor while preserving the existing offline-first design and `.vdt` compatibility.

## Development rules

- Keep existing documents readable; changes to the native format must be versioned and migration-tested.
- Prefer non-destructive operations and explicit confirmation before destructive document changes.
- Do not advertise a feature as complete until it has an implementation and a reproducible test.
- Keep rendering and geometry independent from UI wherever practical.
- Test on Android 10 (API 29) and a current Android release, including a mid-range device.

## Milestone 0 — Baseline and safety

- [x] Review the supplied source archive and identify the current architecture.
- [x] Add layer rename, duplicate, reorder, and delete-with-object-preservation actions to the Object Manager.
- [x] Preserve the legacy integer layer-index representation and `.vdt` serialization.
- [ ] Establish a repeatable CI build and retain build artifacts.
- [ ] Add regression fixtures for legacy single-page `.vdt` and multi-page v2 projects.
- [ ] Add automated geometry, serialization, and SVG import/export tests.

## Milestone 1 — Core editing reliability

- [x] Expand undo/redo snapshots to cover the active page's objects, layers, guides, page dimensions, unit, and grid settings.
- [ ] Extend undo/redo to page-list operations (add, duplicate, delete, reorder) with project-level history.
- [ ] Add stable object names and a searchable object/layer tree.
- [ ] Add object rename, duplicate, reorder, isolate, and multi-select operations.
- [ ] Ensure hidden and locked objects cannot be accidentally selected or edited.
- [ ] Improve clipboard interoperability and preserve vector data where possible.
- [ ] Add crash-safe, atomic autosave and recovery validation.

## Milestone 2 — Typography

- [ ] Character-level formatting and kerning controls.
- [ ] Paragraph styles, indentation, lists, and columns.
- [ ] Linked text frames and text wrap around objects.
- [ ] OpenType feature support where the Android text stack permits it.
- [ ] Font substitution warnings and robust font embedding/licensing metadata.

## Milestone 3 — Effects and illustration

- [ ] Non-destructive effect stack with editable parameters.
- [ ] Gaussian blur, glow, bevel/emboss, and richer shadow controls.
- [ ] Artistic Media brush engine with stylus pressure and tilt where available.
- [ ] Distortion and perspective tools with stable geometry behavior.
- [ ] Performance profiling for large paths and complex documents.

## Milestone 4 — Print and interoperability

- [ ] Separate document color model from UI color-picker modes.
- [ ] ICC profile-aware RGB/CMYK conversion and soft proofing.
- [ ] Spot colors, overprint preview, bleed, and crop/registration marks.
- [ ] PDF preflight and print-focused export validation.
- [ ] EPS/DXF support and documented SVG/PDF compatibility subsets.
- [ ] Import/export fidelity test corpus; do not claim full AI/CDR compatibility without it.

## Milestone 5 — Bitmap and workflow ecosystem

- [ ] Non-destructive bitmap adjustments and masks.
- [ ] Templates, asset organization, and reusable document components.
- [ ] Customizable shortcuts and workspace profiles.
- [ ] Plugin/API boundary only after the document and rendering APIs stabilize.

## Definition of done

A feature is complete only when it has: (1) a documented user workflow, (2) input validation and failure handling, (3) undo/redo behavior defined, (4) save/reopen coverage, (5) accessibility/touch behavior checked, and (6) a regression test or documented manual test on a real Android build.

## Milestone 1 — Object Manager progress (Update 3)

- [x] Add backward-compatible optional object names in VDT records.
- [x] Add object rename, duplicate, and same-layer stacking reorder actions.
- [x] Add object/layer content search in the Object Manager.
- [ ] Add true nested group model and tree rendering (current group IDs remain flat).
- [ ] Add isolate mode and multi-selection controls.
- [ ] Add project-level page history and automated tests.
