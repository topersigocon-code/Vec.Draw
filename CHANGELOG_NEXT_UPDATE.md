# Vec.Draw 0.2.0 — Development Update 3

## Object Manager improvements

- Added optional persistent object names to the native VDT object record (`nm`). Old files without this field still load with an empty name and keep the existing generated label.
- Added object rename, duplicate, and same-layer stacking reorder controls.
- Added a search field for object names, text contents, object types, and layer names.
- Preserved the existing flat group representation; nested groups are not claimed as implemented.

## Android signing configuration

- Added environment-based release signing configuration. No key or password is stored in the repository.
- Bumped app version to `0.2.0` / versionCode `2`.
- The original permanent signing key was not present in any supplied archive. See `SIGNING_KEY_RECOVERY.md`.

## Verification

- Source-level compatibility checks and ZIP integrity checks performed.
- Android Gradle compilation and device UI testing remain pending in this environment.

## Update 4.1 — External font collection import
- Added ZIP font collection import through Android's Storage Access Framework, including external SD-card providers.
- ZIP import scans archive entries and installs valid `.ttf` / `.otf` files into app-private font storage; it does not extract arbitrary archive paths.
- Added per-font, total archive, and entry-count limits; invalid and unsupported entries are skipped.
- Prevented imported font filenames from overwriting existing fonts by generating unique names.
- Existing single-font `.ttf` / `.otf` import remains available.
