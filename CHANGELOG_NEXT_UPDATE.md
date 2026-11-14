# Vec.Draw development update — Core Editing Reliability

## Implemented in this source update

- Undo/redo now stores a deep snapshot of the active page document rather than only its object list.
- Active-page snapshots include page dimensions, unit, grid spacing, layer names/visibility/locking/opacity, object data, and horizontal/vertical guides.
- The active layer index is restored with the snapshot.
- Layer add, rename, duplicate, reorder, delete, visibility, locking, and opacity changes now create undo checkpoints.
- Object visibility and locking changes now create undo checkpoints.
- Page unit, page dimensions, and grid spacing changes now create undo checkpoints.
- The native `.vdt` serialization schema is unchanged.

## Known limitations

- Undo history remains scoped to the currently active page. Adding, duplicating, deleting, or reordering pages is not yet part of project-level undo/redo.
- Bitmap payloads are shared by `VObj.copy()` snapshots; current bitmap editing is not implemented as in-place pixel mutation. If destructive bitmap editing is added, snapshot bitmap ownership must be revisited.
- Android compilation and device-level testing have not been performed in this environment. Run the repository's Gradle CI build and the manual QA checklist before distributing an APK.
