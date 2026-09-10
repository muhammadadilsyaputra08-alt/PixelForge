# PixelForge

Unified mobile pixel/sprite production editor for Android — one Project,
one Workspace, every stage (import → clean → edit → select → detect →
slice → map → reference → animate → normalize → pack → export) sharing
the same state. Built entirely in Kotlin + Jetpack Compose.

## Architecture

```
UI (Compose)  →  ProjectViewModel (application state)  →  Engines  →  Storage
```

- `core/` — `ProjectModel` (§38 data model), `StorageManager` (project DB +
  filesystem asset storage, §40), `UndoManager` (global project-level
  undo/redo, §39).
- `engine/` — one object per engine named after the blueprint's Core
  Architecture list (§41): `PixelEngine`, `LayerEngine`,
  `SelectionEngine` (Smart/Manual compound-rectangle boolean mask, §7–15,
  §42–44), `BackgroundRemoverEngine` (chroma key + magic wand, §19),
  `SpriteDetectionEngine` (connected-component detection pipeline, §20),
  `GeometryEngine` (actual bounds / offset / excess, §23–27),
  `FrameEngine` (Frame Bank, §21–22), `AnimationEngine` (§28–32),
  `NormalizationEngine` (§33), `AtlasPackerEngine` (MaxRects-style bin
  packing, §34–35).
- `export/ExportManager.kt` — builds `PixelForge_Export.zip`
  (sprites/, atlas/, metadata/, project/) plus standalone `.pforge`
  project backups (§36).
- `ui/viewmodel/ProjectViewModel.kt` — the single source of truth every
  screen reads/writes; every mutating action funnels through
  `UndoManager` so Undo/Redo works across tool, selection, sprite, and
  animation actions alike (§39).
- `ui/screens/` — `HomeScreen` (New/Recent/Import, §3) and
  `WorkspaceScreen` (shared canvas + EDIT/SPRITE/ANIMATION/ATLAS mode
  tabs + bottom Action Dock, §4/§46).
- `ui/components/` — `PixelCanvas` (pixel-perfect nearest-neighbor
  renderer with live Smart Select drag preview + mask overlay),
  `LayerPanel`, `FrameBankPanel`, `AnimationPanel` (timeline + playback),
  `AtlasPanel`, `ExportSheet`, and the tool dialogs (Chroma Key, Sprite
  Detection, Character Reference, Canvas Normalization).

## What works end-to-end

- New Project / Import Image / Recent Projects, backed by real on-disk
  storage (`filesDir/projects/<id>/project.json` + `assets/*.png` — no
  base64-in-preferences, per §40).
- Pixel editing: pencil, eraser, bucket fill (flood fill, tolerance-aware),
  eyedropper, brush size, nearest-neighbor zoom — all pixel-perfect, no
  anti-aliasing (§18).
- Layers: add/duplicate/delete/rename/reorder/visibility/opacity/lock,
  rotate 90°/flip.
- Smart Select: every drag is one rectangle; starting inside the current
  selection subtracts (creates holes), starting outside unions — result
  is stored and rendered as a real pixel mask, not a rectangle list
  (§7–15). Manual rectangle select, invert, expand, contract, copy/cut/
  delete all wired to the canvas.
- Background removal: Chroma Key (tolerance/feather/fill-enclosed-gaps)
  and Magic Wand (connected/all-matching), non-destructive until commit
  (§19).
- Sprite Detection: alpha/key-color foreground mask → connected-component
  labeling → noise filtering → fragment merge → grid-in-grid merge →
  padded bounding boxes → Frame Bank (§20–22).
- Geometry: actual bounds, offset, excess vs. Character Reference,
  recalculated automatically whenever the reference or a frame's pixels
  change (§23–27).
- Animation: multiple named animations, FPS 1–30, loop toggle, Capture
  Frame, drag-ordered timeline, Previous/Play/Next preview with a stable
  anchor (§28–32).
- Canvas Normalization: batch-resize every frame onto a uniform canvas
  with a selectable anchor (§33).
- Atlas Packer: MaxRects-style bin packing with padding, spacing, and
  power-of-two rounding, live preview render (§34–35).
- Export Center: one ZIP containing `sprites/*.png`, `spritesheet.png`,
  `atlas/atlas.png` + `atlas.json`, `metadata/{frames,animations,
  geometry}.json` + `frames.csv`, and `project/project.pforge`; plus a
  standalone project backup/restore (§36).
- Global Undo/Redo across every one of the above (§39).

## Build

Standard Android Gradle project, pure Kotlin/Compose — no NDK, no
Chaquopy/Python, no external native toolchain required.

```
./gradlew assembleDebug
```

- `minSdk 26`, `compileSdk`/`targetSdk 35`.
- Requires Android Studio (Ladybug+) or any Gradle 8.9+/JDK 17 toolchain.

## CI

`.github/workflows/android-build.yml` runs on every push/PR: lint, unit
tests, `assembleDebug` (uploaded as an artifact), and `assembleRelease`
(unsigned) on pushes to `main`/`master`.


## Feature Update — Editor / Sprite Workflow

This revision adds the requested mobile pixel-editor workflow:

- **Editor image import:** Import Image opens choices for **New Layer**, **Paste into current layer**, or **Replace current layer**.
- **Geometry tool:** Geometry → Circle, Rectangle, Oval, Parallelogram, Line. Drag on the canvas to define size and direction; the draft remains floating until an action is chosen.
- **Floating geometry actions:** Apply, Cut, Copy, Move, Flip, Rotate, Scale, Delete.
- **Frame editing stays on the selected frame:** selecting a Frame Bank item enters Frame Edit without moving/copying that sprite into the main layer. Switching to Edit keeps the selected frame as the edit target until **Exit Frame Edit**.
- **Manual offset/reference:** selected-frame reference boundaries can be dragged directly on the canvas. Left/right/bottom values are stored as a **frame-specific manual reference**, so editing one frame does not overwrite every frame.
- **Frame Bank visibility:** Frame Bank is collapsed by default and can be opened when needed, keeping the canvas visible.
- **Palette placement:** the color palette is in the bottom editor UI instead of the canvas workspace.
- **Integer zoom:** pinch zoom snaps to **1× / 2× / 4× / 8×** and image rendering uses nearest-neighbor filtering.
- **SVG vector export:** every exported frame now also gets a crisp, `shape-rendering="crispEdges"` SVG made from pixel color runs.
- **Scale2x module:** an optional native Kotlin pixel upscaler is included in `Scale2xEngine.kt`; it uses neighbor-aware pixel processing and does not introduce bilinear blur.
- **No-blur rendering:** Android/Compose bitmap drawing paths continue to use `FilterQuality.None`, `isAntiAlias=false`, and `isFilterBitmap=false`.

### Offset workflow

1. Open a frame from **Frame Bank**.
2. Use **Frame Edit**; the canvas shows only that frame.
3. Drag the **left**, **right**, and **bottom** reference guides.
4. The Geometry inspector reports the frame's actual bounds, offset and excess values.
5. The guides are stored per frame, allowing each animation frame to have its own manual reference when needed.
