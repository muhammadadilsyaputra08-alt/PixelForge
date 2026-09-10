package com.pixelforge.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.pixelforge.core.FrameSource
import com.pixelforge.core.GeometryType
import com.pixelforge.core.OffsetMode
import com.pixelforge.core.ReferenceBoundary
import com.pixelforge.engine.BackgroundRemoverEngine
import com.pixelforge.engine.GeometryEngine
import com.pixelforge.engine.MagicWandMode
import com.pixelforge.engine.NormalizationAnchor
import com.pixelforge.engine.NormalizationMode
import com.pixelforge.engine.SpriteDetectionParams
import com.pixelforge.core.Rect as PfRect
import com.pixelforge.ui.components.*
import com.pixelforge.ui.viewmodel.ProjectViewModel
import com.pixelforge.ui.viewmodel.WorkspaceMode
import com.pixelforge.ui.viewmodel.WorkspaceTool
import java.io.File

/** Shares a file produced in app-private storage (Blueprint #40) via FileProvider, since
 *  nothing outside the app can otherwise reach it (no provider previously existed at all). */
private fun shareExportedFile(context: android.content.Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
}

/**
 * PROJECT WORKSPACE (Blueprint #4): a single shared canvas + workspace-context tabs
 * (EDIT / SPRITE / ANIMATION / ATLAS). No mode is a separate app - all read/write the
 * same ProjectViewModel state, so results from one stage are immediately usable in the next.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(viewModel: ProjectViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val project by viewModel.project.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val toolState by viewModel.toolState.collectAsState()
    val activeLayerId by viewModel.activeLayerId.collectAsState()
    val activeFrameId by viewModel.activeFrameId.collectAsState()
    val activeAnimationId by viewModel.activeAnimationId.collectAsState()
    val atlasBmp by viewModel.atlasPreview.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val canUndo by viewModel.canUndoFlow.collectAsState()
    val canRedo by viewModel.canRedoFlow.collectAsState()
    val bgRemovalPreview by viewModel.bgRemovalPreview.collectAsState()

    val p = project ?: return

    var showChromaKeyDialog by remember { mutableStateOf(false) }
    var showDetectDialog by remember { mutableStateOf(false) }
    var showReferenceDialog by remember { mutableStateOf(false) }
    var showNormalizeDialog by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    var showResizeDialog by remember { mutableStateOf(false) }
    var magicWandArmed by remember { mutableStateOf(false) }
    var geometryType by remember { mutableStateOf(GeometryType.RECTANGLE) }
    var geometryBankExpanded by remember { mutableStateOf(false) }
    // The in-progress shape is ProjectModel.floatingGeometry (Blueprint #19/#20/#83), not local
    // screen state - see ProjectViewModel's "Floating Geometry" section. This var only tracks
    // whether a drag is currently live, so onDrag knows to keep stretching the same shape.
    var drawingGeometry by remember { mutableStateOf(false) }
    val floatingGeometry = p.floatingGeometry
    // FRAME EDITOR sub-mode (SESI 9): Editor (draw/erase/etc, as before) vs Measure (only the
    // offset/reference lines are interactive, drawing is disabled so a drag meant to nudge a
    // line can't accidentally smudge the sprite). Measuring stages edits locally in
    // [stagedFrameReference] instead of writing them straight to the project on every drag - Save
    // Offset below is what actually commits it, matching "measure carefully, then commit" rather
    // than the old auto-save-every-drag behavior.
    var frameMeasureMode by remember(p.activeFrameEdit?.frameId) { mutableStateOf(false) }
    var stagedFrameReference by remember(p.activeFrameEdit?.frameId) { mutableStateOf<ReferenceBoundary?>(null) }
    // PIXEL GRID overlay toggle (SESI 9): faint lines at every pixel boundary, to help place
    // pixels precisely - see PixelCanvas's showPixelGrid param for the actual drawing.
    var showPixelGrid by remember { mutableStateOf(false) }
    var editingFrameInEdit by remember(p.id) { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    // Target layer for the two layer-scoped image pickers below (Blueprint #16: Replace Image,
    // Paste into Layer) - set right before launching, read in the launcher's callback.
    var pendingReplaceLayerId by remember { mutableStateOf<String?>(null) }
    var pendingPasteLayerId by remember { mutableStateOf<String?>(null) }
    val replaceImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val layerId = pendingReplaceLayerId
        pendingReplaceLayerId = null
        if (uri != null && layerId != null) viewModel.replaceLayerImage(layerId, uri)
    }
    val pasteImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val layerId = pendingPasteLayerId
        pendingPasteLayerId = null
        if (uri != null && layerId != null) viewModel.pasteImageIntoLayer(layerId, uri)
    }
    val editorImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) pendingImportUri = uri
    }

    val errorMessage by viewModel.errorMessage.collectAsState()
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(p.name, maxLines = 1) },
                navigationIcon = { IconButton(onClick = { viewModel.saveProject(); onBack() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { viewModel.undo() }, enabled = canUndo) { Icon(Icons.Filled.Undo, contentDescription = "Undo") }
                    IconButton(onClick = { viewModel.redo() }, enabled = canRedo) { Icon(Icons.Filled.Redo, contentDescription = "Redo") }
                    // PIXEL GRID toggle (SESI 9): faint lines at every pixel boundary to help
                    // place pixels precisely - available in every mode since it's purely visual.
                    IconButton(onClick = { showPixelGrid = !showPixelGrid }) {
                        Icon(
                            Icons.Filled.GridOn,
                            contentDescription = if (showPixelGrid) "Hide pixel grid" else "Show pixel grid",
                            tint = if (showPixelGrid) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = { showResizeDialog = true }) { Icon(Icons.Filled.AspectRatio, contentDescription = "Canvas Settings") }
                    IconButton(onClick = { showExportSheet = true }) { Icon(Icons.Filled.IosShare, contentDescription = "Export") }
                }
            )
        },
        bottomBar = {
            Column {
                when (mode) {
                    WorkspaceMode.EDIT -> {
                        ColorPickerRow(toolState.color) { viewModel.setColor(it) }
                        ActionDock(
                            editToolDockActions(toolState.tool) { viewModel.setTool(it) } +
                                DockAction("Import", Icons.Filled.AddPhotoAlternate, description = "Import an image from your device onto the active layer.") { editorImportLauncher.launch("image/*") }
                        )
                        if (toolState.tool == WorkspaceTool.GEOMETRY) {
                            ActionDock(geometryDockActions(geometryType) { geometryType = it })
                        }
                        if (floatingGeometry != null) {
                            val targetPath = if (editingFrameInEdit) activeFrameBitmapPath(p, activeFrameId) else activeLayerBitmapPath(p, activeLayerId)
                            ActionDock(
                                listOf(
                                    // Apply/Cancel (Blueprint #19/#20): Apply is the ONLY path that
                                    // ever rasterizes the shape into the bitmap; Cancel just drops
                                    // the draft since nothing was ever burned in before this point.
                                    DockAction("Apply", Icons.Filled.Check, description = "Permanently draw this shape onto the layer.") {
                                        targetPath?.let { viewModel.applyFloatingGeometry(it) }
                                    },
                                    DockAction("Cancel", Icons.Filled.Close, description = "Discard this shape draft without drawing it.") { viewModel.cancelFloatingGeometry() },
                                    DockAction("Move", Icons.Filled.OpenWith, description = "Nudge the shape draft sideways before applying it.") { viewModel.moveFloatingGeometry(2, 0) },
                                    DockAction("Flip H", Icons.Filled.Flip, description = "Mirror the shape draft left-to-right.") { viewModel.flipFloatingGeometry(true) },
                                    DockAction("Rotate", Icons.Filled.RotateRight, description = "Rotate the shape draft 90°.") { viewModel.rotateFloatingGeometry90() },
                                    DockAction("Scale +", Icons.Filled.OpenInFull, description = "Enlarge the shape draft.") { viewModel.scaleFloatingGeometry(1.25f) },
                                    DockAction("Scale -", Icons.Filled.CloseFullscreen, description = "Shrink the shape draft.") { viewModel.scaleFloatingGeometry(0.8f) }
                                )
                            )
                        }
                        if (toolState.tool == WorkspaceTool.SMART_SELECT || toolState.tool == WorkspaceTool.SELECT_MANUAL) {
                            val floating = p.floatingSelection
                            if (floating != null) {
                                ActionDock(
                                    floatingSelectionDockActions(
                                        onMoveUp = { viewModel.moveFloatingSelection(0, -1) },
                                        onMoveDown = { viewModel.moveFloatingSelection(0, 1) },
                                        onMoveLeft = { viewModel.moveFloatingSelection(-1, 0) },
                                        onMoveRight = { viewModel.moveFloatingSelection(1, 0) },
                                        onRotate = { viewModel.rotateFloatingSelection90() },
                                        onFlipH = { viewModel.flipFloatingSelection(true) },
                                        onFlipV = { viewModel.flipFloatingSelection(false) },
                                        onScaleUp = { viewModel.scaleFloatingSelection(1.25f) },
                                        onScaleDown = { viewModel.scaleFloatingSelection(0.8f) },
                                        onCommit = { activeLayerBitmapPath(p, activeLayerId)?.let { viewModel.commitFloatingSelection(it) } },
                                        onCancel = { activeLayerBitmapPath(p, activeLayerId)?.let { viewModel.cancelFloatingSelection(it) } },
                                        onDelete = { viewModel.setFloatingSelection(null) }
                                    )
                                )
                            } else {
                                if (toolState.tool == WorkspaceTool.SMART_SELECT) {
                                    ActionDock(smartSelectModeDockActions(p.selectionMode) { viewModel.setSelectionMode(it) })
                                }
                                ActionDock(
                                    smartSelectDockActions(
                                        onClear = { viewModel.clearSelection() },
                                        onInvert = { viewModel.invertSelection() },
                                        onExpand = { viewModel.expandSelection(1) },
                                        onContract = { viewModel.contractSelection(1) },
                                        onCopy = { activeLayerBitmapPath(p, activeLayerId)?.let { viewModel.beginFloatingSelection(it, cut = false) } },
                                        onCut = { activeLayerBitmapPath(p, activeLayerId)?.let { viewModel.beginFloatingSelection(it, cut = true) } },
                                        onDelete = { activeLayerBitmapPath(p, activeLayerId)?.let { viewModel.deleteSelection(it) } }
                                    )
                                )
                            }
                        }
                    }
                    WorkspaceMode.SPRITE -> {
                        ActionDock(
                            listOf(
                                DockAction("Chroma Key", Icons.Filled.Colorize, description = "Remove a background color (e.g. green screen) by picking a key color to make transparent.") { showChromaKeyDialog = true },
                                DockAction("Magic Wand", Icons.Filled.AutoAwesome, magicWandArmed, "Tap the sprite on canvas to auto-remove its connected background area.") { magicWandArmed = !magicWandArmed },
                                DockAction("Detect Sprites", Icons.Filled.GridView, description = "Automatically find individual sprites/frames on the canvas and add them to the Frame Bank.") { showDetectDialog = true },
                                DockAction("Reference", Icons.Filled.Flag, description = "Set the character's left/right/foot reference lines used for consistent offsets across frames.") { showReferenceDialog = true },
                                DockAction("Normalize", Icons.Filled.PhotoSizeSelectLarge, description = "Resize/align all frames to a consistent size and anchor point.") { showNormalizeDialog = true }
                            )
                        )
                    }
                    WorkspaceMode.ANIMATION -> {
                        ActionDock(
                            listOf(
                                DockAction("New Animation", Icons.Filled.Add, description = "Create a new empty animation sequence.") { viewModel.createAnimation("Animation ${p.animations.size + 1}") },
                                DockAction("Capture Frame", Icons.Filled.CameraAlt, description = "Add a snapshot of the current canvas as the next frame in this animation.") {
                                    val animId = activeAnimationId ?: return@DockAction
                                    val layerPath = activeLayerBitmapPath(p, activeLayerId) ?: return@DockAction
                                    viewModel.captureFrameIntoAnimation(animId, layerPath, FrameSource(0, 0, p.canvas.width, p.canvas.height))
                                },
                                // DRAW NEXT FRAME (SESI 8, layered animation flow): opens a fresh
                                // frame stacked right after the last one in this sequence, with
                                // Onion Skin auto-enabled so the previous frame shows through
                                // faintly while the new one is drawn.
                                DockAction("Draw Next Frame", Icons.Filled.Layers, description = "Open a new frame on top of the last one in this animation, with the previous frame shown faintly underneath as a guide.") {
                                    activeAnimationId?.let { viewModel.addNextAnimationFrame(it) }
                                },
                                // RESET CAPTURED FRAMES (SESI 9): clears this animation's whole
                                // sequence before re-capturing, so tweaking the source art and
                                // re-running Capture/Draw-Next-Frame doesn't pile up duplicate
                                // frames next to the old ones.
                                DockAction("Reset Frames", Icons.Filled.RestartAlt, description = "Clear every frame captured into this animation so far, so re-capturing after edits doesn't leave duplicate frames behind.") {
                                    activeAnimationId?.let { viewModel.resetCapturedFrames(it) }
                                }
                            )
                        )
                    }
                    WorkspaceMode.ATLAS -> {
                        ActionDock(listOf(DockAction("Pack Atlas", Icons.Filled.ViewInAr, description = "Combine all frames into a single packed sprite sheet image.") { viewModel.packAtlas() }))
                    }
                    WorkspaceMode.FRAME -> {
                        // Dedicated Frame Editor context (Blueprint #31, SESI 5) - the Inspector
                        // panel below the canvas does the real work; this dock is just the exit.
                        ActionDock(
                            listOf(
                                DockAction("Back to Frame Bank", Icons.Filled.ArrowBack, description = "Exit this frame's editor and return to the Frame Bank list.") {
                                    viewModel.setActiveFrameEdit(null)
                                    viewModel.setMode(WorkspaceMode.SPRITE)
                                },
                                // EDITOR / MEASURE sub-modes (SESI 9): Editor is the normal
                                // draw/erase workflow; Measure hides drawing entirely and makes
                                // only the offset/reference lines interactive, so lining them up
                                // precisely can't accidentally smudge the sprite.
                                DockAction("Editor", Icons.Filled.Edit, selected = !frameMeasureMode,
                                    description = "Draw/erase/fill on this frame normally.") { frameMeasureMode = false },
                                DockAction("Measure Offset", Icons.Filled.Straighten, selected = frameMeasureMode,
                                    description = "Drawing is disabled; drag the L/R/B reference lines to measure this frame's offset precisely.") {
                                    frameMeasureMode = true
                                    stagedFrameReference = null
                                }
                            ) + if (frameMeasureMode) listOf(
                                DockAction("Save Offset", Icons.Filled.Check, description = "Save the reference lines as this frame's own offset override.") {
                                    val fid = activeFrameId
                                    val ref = stagedFrameReference
                                    if (fid != null && ref != null) viewModel.setFrameReference(fid, ref)
                                }
                            ) else emptyList()
                        )
                    }
                }
                NavigationBar {
                    NavigationBarItem(selected = mode == WorkspaceMode.EDIT, onClick = { viewModel.setMode(WorkspaceMode.EDIT) }, icon = { Icon(Icons.Filled.Edit, contentDescription = null) }, label = { Text("Edit") })
                    NavigationBarItem(selected = mode == WorkspaceMode.SPRITE, onClick = { viewModel.setMode(WorkspaceMode.SPRITE) }, icon = { Icon(Icons.Filled.GridView, contentDescription = null) }, label = { Text("Sprite") })
                    NavigationBarItem(selected = mode == WorkspaceMode.ANIMATION, onClick = { viewModel.setMode(WorkspaceMode.ANIMATION) }, icon = { Icon(Icons.Filled.Movie, contentDescription = null) }, label = { Text("Animation") })
                    NavigationBarItem(selected = mode == WorkspaceMode.ATLAS, onClick = { viewModel.setMode(WorkspaceMode.ATLAS) }, icon = { Icon(Icons.Filled.ViewInAr, contentDescription = null) }, label = { Text("Atlas") })
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Frame Editor toggle (Blueprint #22 "Frame Editor: Geometry | Edit | Reference"):
            // SPRITE mode's canvas normally shows the whole imported sheet (needed for Chroma
            // Key / Magic Wand / Detect Sprites, which operate on the full sheet). Selecting a
            // frame in the Frame Bank switches the canvas to *that frame's own cropped bitmap*
            // instead - required because Bounds/offset/excess are stored in the frame's own
            // local coordinate space, not the sheet's, so drawing them over the full sheet would
            // be positioned wrong.
            var viewingFrameEditor by remember(p.id) { mutableStateOf(false) }

            // ---------------- Canvas area ----------------
            val canvasBitmapPath = when (mode) {
                WorkspaceMode.EDIT ->
                    if (editingFrameInEdit) activeFrameBitmapPath(p, activeFrameId) ?: activeLayerBitmapPath(p, activeLayerId)
                    else activeLayerBitmapPath(p, activeLayerId)
                WorkspaceMode.SPRITE ->
                    if (viewingFrameEditor) activeFrameBitmapPath(p, activeFrameId) ?: activeLayerBitmapPath(p, activeLayerId)
                    else activeLayerBitmapPath(p, activeLayerId)
                WorkspaceMode.ANIMATION -> activeFrameBitmapPath(p, activeFrameId) ?: activeLayerBitmapPath(p, activeLayerId)
                WorkspaceMode.ATLAS -> null
                // FRAME mode ALWAYS shows the selected Frame Bank entry's own cropped bitmap
                // (Blueprint #31) - never the main layers, regardless of activeLayerId.
                WorkspaceMode.FRAME -> activeFrameBitmapPath(p, p.activeFrameEdit?.frameId ?: activeFrameId)
            }

            // PAN / ZOOM (Blueprint #5 shortcut list has Pan+Zoom tools, but neither was wired
            // to anything - `Pan` selected the tool and did nothing, `setZoom()` existed but
            // nothing ever called it or read `toolState.zoom`). This viewport transform is
            // applied via graphicsLayer on a wrapper AROUND PixelCanvas, so hit-testing for the
            // pixel-editing gestures inside PixelCanvas is automatically un-transformed by
            // Compose and drawing keeps working at any zoom level.
            var canvasScale by remember(p.id) { mutableStateOf(1f) }
            var canvasOffset by remember(p.id) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

            // "Tap outside the sprite clears selection" geometry (bug report, SESI 9): the old
            // `.clickable { clearSelection() }` fired for EVERY tap anywhere in this Box,
            // regardless of whether it actually landed on the sprite - it relied on PixelCanvas
            // consuming taps that land on the sprite so this handler would only ever see the
            // ones that missed, but PixelCanvas never actually did that consuming. In practice
            // that meant: a small selection drag looks like a "tap" to Compose (movement stays
            // within its own tap-detection slop) and got its just-made selection wiped right
            // back out, while only LARGER drags survived (their movement was big enough that
            // Compose's tap detector gave up on treating it as a tap at all). Making PixelCanvas
            // consume events to plug that gap was tried and reverted - it broke selection
            // dragging generally by interfering with sibling gesture detectors on this same Box.
            // Fixed here instead, with no changes to PixelCanvas at all: compute the sprite's
            // actual on-screen rectangle (accounting for aspect-ratio letterboxing within this
            // Box) and only clear when a tap's position is truly OUTSIDE it - so it no longer
            // matters whether the tap happened to look like a "click" to Compose or not.
            var canvasBoxSizePx by remember(p.id) { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
            val displayBmpSizeForTapCheck: androidx.compose.ui.unit.IntSize? = if (mode == WorkspaceMode.ATLAS) {
                atlasBmp?.let { androidx.compose.ui.unit.IntSize(it.width, it.height) }
            } else {
                canvasBitmapPath?.let { path ->
                    val b = viewModel.loadBitmap(path)
                    androidx.compose.ui.unit.IntSize(b.width, b.height)
                }
            }

            Box(
                Modifier
                    .weight(1.4f)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .onSizeChanged { canvasBoxSizePx = it }
                    .pointerInput(toolState.tool, displayBmpSizeForTapCheck, canvasBoxSizePx) {
                        detectTapGestures { offset ->
                            if (toolState.tool != WorkspaceTool.SMART_SELECT && toolState.tool != WorkspaceTool.SELECT_MANUAL) return@detectTapGestures
                            val bmpSize = displayBmpSizeForTapCheck
                            val boxW = canvasBoxSizePx.width.toFloat()
                            val boxH = canvasBoxSizePx.height.toFloat()
                            if (bmpSize == null || bmpSize.width <= 0 || bmpSize.height <= 0 || boxW <= 0f || boxH <= 0f) {
                                viewModel.clearSelection()
                                return@detectTapGestures
                            }
                            val bmpAspect = bmpSize.width.toFloat() / bmpSize.height.toFloat()
                            val boxAspect = boxW / boxH
                            val spriteRect = if (bmpAspect > boxAspect) {
                                // Sprite is relatively wider than the box: fits the box's full
                                // width, letterboxed (empty margin) above/below.
                                val h = boxW / bmpAspect
                                val top = (boxH - h) / 2f
                                androidx.compose.ui.geometry.Rect(0f, top, boxW, top + h)
                            } else {
                                // Sprite is relatively taller: fits the box's full height,
                                // letterboxed left/right instead.
                                val w = boxH * bmpAspect
                                val left = (boxW - w) / 2f
                                androidx.compose.ui.geometry.Rect(left, 0f, left + w, boxH)
                            }
                            if (!spriteRect.contains(offset)) {
                                viewModel.clearSelection()
                            }
                        }
                    }
                    .pointerInput(toolState.tool) {
                        // Hand-rolled pinch-zoom + pan instead of the compose-foundation
                        // `calculateZoom`/`calculatePan` helpers used in the previous revision -
                        // those turned out to be internal (not public API), which failed
                        // `compileDebugKotlin` with "Unresolved reference: calculateZoom" /
                        // "calculatePan". Centroid + average-distance are computed manually here.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var prevCentroid: androidx.compose.ui.geometry.Offset? = null
                            var prevSpread = 0f
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                val pointerCount = pressed.size
                                val isPan = toolState.tool == WorkspaceTool.PAN
                                if (pressed.isNotEmpty() && (pointerCount >= 2 || isPan)) {
                                    var cx = 0f; var cy = 0f
                                    pressed.forEach { cx += it.position.x; cy += it.position.y }
                                    val centroid = androidx.compose.ui.geometry.Offset(cx / pressed.size, cy / pressed.size)
                                    val spread = if (pointerCount >= 2) {
                                        pressed.fold(0f) { acc, c ->
                                            acc + kotlin.math.hypot(c.position.x - centroid.x, c.position.y - centroid.y)
                                        } / pointerCount
                                    } else 0f

                                    if (prevCentroid != null) {
                                        canvasOffset += (centroid - prevCentroid)
                                        if (pointerCount >= 2 && prevSpread > 1f && spread > 1f) {
                                            val raw = (canvasScale * (spread / prevSpread)).coerceIn(1f, 64f)
                                            canvasScale = listOf(1f, 2f, 4f, 8f, 16f, 32f, 64f).minBy { kotlin.math.abs(it - raw) }
                                        }
                                        pressed.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                    prevCentroid = centroid
                                    prevSpread = spread
                                } else {
                                    prevCentroid = null
                                    prevSpread = 0f
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (mode == WorkspaceMode.ATLAS) {
                    val currentAtlasBmp = atlasBmp
                    if (currentAtlasBmp != null) {
                        PixelCanvas(bitmap = currentAtlasBmp)
                    } else {
                        Text("Pack the atlas to preview it here.")
                    }
                } else if (canvasBitmapPath != null) {
                    val baseBmp = viewModel.loadBitmap(canvasBitmapPath)
                    val floating = p.floatingSelection
                    // Background remover preview (SESI 4): while a Chroma Key / Magic Wand
                    // preview is staged for THIS bitmap path, show it instead of the real layer -
                    // never mutates the real layer bitmap until Apply is pressed.
                    val bgPreviewBmp = bgRemovalPreview
                    // Live ghost preview of the floating selection at its current offset
                    // (Blueprint #17-18) - never mutates the real layer bitmap, only a
                    // throwaway composite rebuilt when the float moves/transforms.
                    val bmp = if (bgPreviewBmp != null) bgPreviewBmp else if (floating != null && mode == WorkspaceMode.EDIT) {
                        remember(floating, baseBmp) {
                            val composite = baseBmp.copy(baseBmp.config ?: android.graphics.Bitmap.Config.ARGB_8888, true)
                            val floatBmp = viewModel.loadBitmap(floating.bitmapPath)
                            val fdx = floating.originX + floating.offsetX
                            val fdy = floating.originY + floating.offsetY
                            for (ly in 0 until floatBmp.height) for (lx in 0 until floatBmp.width) {
                                val px = floatBmp.getPixel(lx, ly)
                                if (android.graphics.Color.alpha(px) == 0) continue
                                val tx = fdx + lx; val ty = fdy + ly
                                if (tx in 0 until composite.width && ty in 0 until composite.height) composite.setPixel(tx, ty, px)
                            }
                            composite
                        }
                    } else baseBmp
                    var floatingDragAnchor by remember(floating != null) { mutableStateOf<PixelPoint?>(null) }
                    val selMask = if (mode == WorkspaceMode.EDIT) com.pixelforge.engine.SelectionEngine.toMask(p.selection) else null
                    // ONION SKIN (SESI 8): only ever resolved while editing a frame in the
                    // context of an animation sequence (ANIMATION timeline or the isolated FRAME
                    // editor) AND the toggle is on - every other mode/condition leaves this null,
                    // which PixelCanvas treats as "draw nothing" (auto-hide).
                    val onionFrameId = if (p.onionSkinEnabled && (mode == WorkspaceMode.ANIMATION || mode == WorkspaceMode.FRAME)) {
                        val editingId = p.activeFrameEdit?.frameId ?: activeFrameId
                        viewModel.onionSkinFrameId(activeAnimationId, editingId)
                    } else null
                    val onionSkinBmp = onionFrameId?.let { fid -> p.frames.find { it.id == fid } }?.let { f -> viewModel.loadBitmap(f.bitmapPath) }
                    Box(
                        Modifier.graphicsLayer(
                            scaleX = canvasScale,
                            scaleY = canvasScale,
                            translationX = canvasOffset.x,
                            translationY = canvasOffset.y
                        )
                    ) {
                    PixelCanvas(
                        bitmap = bmp,
                        selectionOverlay = selMask?.bits,
                        selectionMaskWidth = selMask?.width ?: 0,
                        selectionMaskHeight = selMask?.height ?: 0,
                        selectionOffsetX = selMask?.boundsX ?: 0,
                        selectionOffsetY = selMask?.boundsY ?: 0,
                        dragRectMode = mode == WorkspaceMode.EDIT && p.floatingSelection == null &&
                            (toolState.tool == WorkspaceTool.SMART_SELECT || toolState.tool == WorkspaceTool.SELECT_MANUAL),
                        // Reference box now always has SOME value to show/drag as soon as a
                        // frame is being edited, instead of staying null (and invisible) until
                        // the user separately opens the "Reference" dialog and types raw
                        // left/right/bottom numbers first. That numeric-entry gate was the
                        // actual cause of "the orange/cyan handles aren't showing at all" - the
                        // handles were coded correctly, but had nothing to draw yet. Falling
                        // back to the frame's own actualBounds gives an immediate, sensible
                        // starting box (edges of the sprite itself) that the user can then drag
                        // into place visually; the very first drag turns it into a real
                        // per-frame manualReference via onReferenceDrag below, same as before.
                        referenceBoundary = if (mode == WorkspaceMode.FRAME) {
                            if (frameMeasureMode) {
                                val f = p.frames.find { it.id == activeFrameId }
                                stagedFrameReference ?: f?.manualReference ?: p.characterReference
                                ?: f?.actualBounds?.let { b -> ReferenceBoundary(b.left, b.right, b.bottom) }
                            } else null
                        } else if ((mode == WorkspaceMode.SPRITE && viewingFrameEditor) || (mode == WorkspaceMode.EDIT && editingFrameInEdit)) {
                            val f = p.frames.find { it.id == activeFrameId }
                            f?.manualReference ?: p.characterReference
                            ?: f?.actualBounds?.let { b -> ReferenceBoundary(b.left, b.right, b.bottom) }
                        } else null,
                        spriteBounds = if ((mode == WorkspaceMode.SPRITE && viewingFrameEditor) || (mode == WorkspaceMode.EDIT && editingFrameInEdit) || mode == WorkspaceMode.FRAME)
                            p.frames.find { it.id == activeFrameId }?.actualBounds else null,
                        onReferenceDrag = if (mode == WorkspaceMode.FRAME) {
                            if (frameMeasureMode) { ref -> stagedFrameReference = ref } else null
                        } else if ((mode == WorkspaceMode.SPRITE && viewingFrameEditor) || (mode == WorkspaceMode.EDIT && editingFrameInEdit)) { ref ->
                            activeFrameId?.let { viewModel.setFrameReference(it, ref) }
                        } else null,
                        showPixelGrid = showPixelGrid,
                        geometrySpec = floatingGeometry?.let { GeometryEngine.resolveSpec(it) },
                        geometryColor = floatingGeometry?.colorArgb ?: toolState.color,
                        onDown = { pt ->
                            if (mode == WorkspaceMode.FRAME && frameMeasureMode) {
                                // Measuring: drawing is disabled, only the reference lines (wired
                                // above) respond to touch.
                            } else if (floating != null) {
                                floatingDragAnchor = pt
                            } else when (toolState.tool) {
                                WorkspaceTool.GEOMETRY -> {
                                    val targetId = if (editingFrameInEdit) (activeFrameId ?: "") else (activeLayerId ?: "")
                                    drawingGeometry = true
                                    viewModel.beginFloatingGeometry(geometryType, targetId, pt.x, pt.y)
                                }
                                WorkspaceTool.PENCIL, WorkspaceTool.ERASER, WorkspaceTool.BUCKET -> {
                                    viewModel.beginStroke(canvasBitmapPath)
                                    viewModel.applyPixelTool(canvasBitmapPath, pt.x, pt.y)
                                }
                                WorkspaceTool.EYEDROPPER -> viewModel.applyPixelTool(canvasBitmapPath, pt.x, pt.y)
                                else -> {
                                    if (magicWandArmed && mode == WorkspaceMode.SPRITE) {
                                        // Preview only (SESI 4): stages the transparency result on
                                        // the canvas; real bitmap is untouched until Apply is tapped
                                        // in the confirm bar below.
                                        viewModel.previewMagicWand(canvasBitmapPath, pt.x, pt.y, 24, MagicWandMode.CONNECTED_AREA)
                                        magicWandArmed = false
                                    }
                                }
                            }
                        },
                        onDrag = { pt ->
                            if (mode == WorkspaceMode.FRAME && frameMeasureMode) {
                                // Measuring: no drawing side-effects for a plain drag.
                            } else {
                                val anchor = floatingDragAnchor
                                if (floating != null && anchor != null) {
                                    viewModel.moveFloatingSelection(pt.x - anchor.x, pt.y - anchor.y)
                                    floatingDragAnchor = pt
                                } else {
                                    if (toolState.tool == WorkspaceTool.GEOMETRY && drawingGeometry) {
                                        viewModel.updateFloatingGeometryDrag(pt.x, pt.y)
                                    }
                                    if (toolState.tool == WorkspaceTool.PENCIL || toolState.tool == WorkspaceTool.ERASER) {
                                        viewModel.applyPixelTool(canvasBitmapPath, pt.x, pt.y)
                                    }
                                }
                            }
                        },
                        onUp = {
                            floatingDragAnchor = null
                            if (toolState.tool == WorkspaceTool.GEOMETRY) {
                                drawingGeometry = false
                            }
                            // BUCKET is intentionally excluded here: it commits itself
                            // (see ProjectViewModel.applyPixelTool's BUCKET branch) right after
                            // its async flood fill finishes, since that fill can still be
                            // running well after this touch-up fires and would otherwise race
                            // commitStroke() into persisting the pre-fill bitmap.
                            if (toolState.tool == WorkspaceTool.PENCIL || toolState.tool == WorkspaceTool.ERASER) {
                                viewModel.commitStroke(canvasBitmapPath)
                            }
                        },
                        onDragRect = { rect: PfRect ->
                            if (toolState.tool == WorkspaceTool.SMART_SELECT) viewModel.smartSelectDrag(rect)
                            else if (toolState.tool == WorkspaceTool.SELECT_MANUAL) viewModel.manualSelect(rect)
                        },
                        // Manual Select resize handles (HTML-tool parity): only offered for a
                        // plain rectangular Manual Select with no floating piece yet - Smart
                        // Select's mask can be a non-rectangular shape that a corner/edge drag
                        // can't meaningfully represent.
                        selectionBounds = if (mode == WorkspaceMode.EDIT && floating == null &&
                            toolState.tool == WorkspaceTool.SELECT_MANUAL && selMask != null && selMask.width > 0 && selMask.height > 0)
                            PfRect(selMask.boundsX, selMask.boundsY, selMask.width, selMask.height) else null,
                        onSelectionResize = { rect -> viewModel.manualSelect(rect) },
                        // Floating-selection resize handles (HTML-tool parity): corner handles
                        // on the floating piece's current box, in addition to the Scale +/-
                        // buttons in floatingSelectionDockActions.
                        floatingBox = if (floating != null && mode == WorkspaceMode.EDIT)
                            PfRect(floating.originX + floating.offsetX, floating.originY + floating.offsetY, floating.width, floating.height) else null,
                        onFloatingResize = { factor -> viewModel.scaleFloatingSelection(factor) },
                        onionSkinBitmap = onionSkinBmp,
                        onionSkinOpacity = p.onionSkinOpacity
                    )
                    }
                } else {
                    Text("Nothing to show yet.")
                }
                // Explicit Zoom In / Zoom Out controls (in addition to pinch-zoom above) so
                // zooming is discoverable and precise on small screens without relying on a
                // two-finger gesture. Steps mirror the pinch snap points so both input methods
                // stay in sync and a tap always lands on a "clean" zoom level.
                // Kept deliberately compact (small custom touch targets instead of default
                // 48dp IconButtons) - the first version of this widget was reported as covering
                // a meaningful chunk of the actual sprite in the corner it sits in.
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(horizontal = 2.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val zoomSteps = remember { listOf(1f, 2f, 4f, 8f, 16f, 32f, 64f) }
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Zoom in",
                        modifier = Modifier
                            .size(22.dp)
                            .clickable(enabled = canvasScale < zoomSteps.last()) {
                                val idx = zoomSteps.indexOf(canvasScale).let { if (it == -1) 0 else it }
                                canvasScale = zoomSteps[(idx + 1).coerceAtMost(zoomSteps.lastIndex)]
                            }
                            .padding(3.dp),
                        tint = if (canvasScale < zoomSteps.last()) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.3f)
                    )
                    Text(
                        "${canvasScale.toInt()}x",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .clickable(enabled = canvasScale != 1f) {
                                canvasScale = 1f
                                canvasOffset = androidx.compose.ui.geometry.Offset.Zero
                            }
                            .padding(vertical = 1.dp)
                    )
                    Icon(
                        Icons.Filled.Remove,
                        contentDescription = "Zoom out",
                        modifier = Modifier
                            .size(22.dp)
                            .clickable(enabled = canvasScale > zoomSteps.first()) {
                                val idx = zoomSteps.indexOf(canvasScale).let { if (it == -1) 0 else it }
                                val newScale = zoomSteps[(idx - 1).coerceAtLeast(0)]
                                canvasScale = newScale
                                if (newScale == 1f) canvasOffset = androidx.compose.ui.geometry.Offset.Zero
                            }
                            .padding(3.dp),
                        tint = if (canvasScale > zoomSteps.first()) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.3f)
                    )
                }
                if ((mode == WorkspaceMode.SPRITE && viewingFrameEditor) || (mode == WorkspaceMode.EDIT && editingFrameInEdit)) {
                    AssistChip(
                        onClick = { viewingFrameEditor = false; editingFrameInEdit = false },
                        label = { Text("← Exit Frame Edit") },
                        modifier = Modifier.align(Alignment.TopStart)
                    )
                }
                if (mode == WorkspaceMode.FRAME) {
                    AssistChip(
                        onClick = { viewModel.setActiveFrameEdit(null); viewModel.setMode(WorkspaceMode.SPRITE) },
                        label = { Text("← Exit Frame Editor") },
                        modifier = Modifier.align(Alignment.TopStart)
                    )
                }
                // Background remover preview confirm bar (SESI 4): the canvas above is already
                // showing the staged transparency result (bgRemovalPreview) instead of the real
                // bitmap - this bar is the only way to commit it or throw it away.
                // FIX (bug report): this used to be a single un-scrollable Row ("Preview:
                // background removal" + Cancel + Apply). On a narrow mobile canvas pane the label
                // text alone could take up the whole available width, pushing the "Apply" button
                // past the edge where it was invisible/unreachable - leaving only "Cancel"
                // visible, so a successful chroma-key preview could never actually be saved.
                // Stacking the label above the buttons guarantees both buttons always fit.
                if (bgRemovalPreview != null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                            .widthIn(max = 280.dp)
                            .background(MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "Background removal preview",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { viewModel.cancelBgRemovalPreview() }) { Text("Cancel") }
                            Button(onClick = { viewModel.applyBgRemovalPreview() }) { Text("Apply (Save)") }
                        }
                    }
                }
            }

            // ---------------- Mode-specific panel ----------------
            // FIX: this Column previously had no weight, so as a non-weighted sibling of the
            // canvas Box above it gets measured with the *same* full available height as the
            // canvas - and since panels like FrameInspectorPanel/GeometryPanel/FrameBankPanel
            // are tall, `verticalScroll` let it happily claim nearly all of that height before
            // the canvas (weight(1f)) got anything left over. That's what squeezed the canvas
            // down to a thin sliver in Frame Editor / Sprite mode (screenshots showed the
            // inspector text visually "sitting on top of" the canvas - it wasn't actually an
            // overlay, the canvas box had just been measured down to almost nothing next to
            // it). Giving this Column its own weight guarantees both regions always keep a
            // fixed, predictable share of the screen - the panel scrolls *within* its share
            // instead of expanding to swallow the canvas's share.
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                when (mode) {
                    WorkspaceMode.EDIT -> {
                        Spacer(Modifier.height(2.dp))
                        // Brush size (Blueprint #5/#18): PixelEngine.pencil/eraser already
                        // accept a brushSize and ToolState/setBrushSize already existed, but no
                        // control anywhere ever called setBrushSize - pencil/eraser were stuck
                        // at 1px. Only relevant for the two brush-based tools.
                        if (toolState.tool == WorkspaceTool.PENCIL || toolState.tool == WorkspaceTool.ERASER) {
                            Column(Modifier.padding(horizontal = 12.dp)) {
                                Text("Brush size: ${toolState.brushSize}px", style = MaterialTheme.typography.labelMedium)
                                Slider(
                                    value = toolState.brushSize.toFloat(),
                                    onValueChange = { viewModel.setBrushSize(it.toInt()) },
                                    valueRange = 1f..32f,
                                    steps = 30
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        LayerPanel(
                            layers = p.layers,
                            activeLayerId = activeLayerId,
                            onSelect = { viewModel.setActiveLayer(it) },
                            onAdd = { viewModel.addLayer() },
                            onDuplicate = { viewModel.duplicateLayer(it) },
                            onDelete = { viewModel.deleteLayer(it) },
                            onToggleVisible = { id, v -> viewModel.setLayerVisible(id, v) },
                            onMoveUp = { viewModel.moveLayerUp(it) },
                            onMoveDown = { viewModel.moveLayerDown(it) },
                            onRename = { id, n -> viewModel.renameLayer(id, n) },
                            onReplaceImage = { id -> pendingReplaceLayerId = id; replaceImageLauncher.launch("image/*") },
                            onPasteImage = { id -> pendingPasteLayerId = id; pasteImageLauncher.launch("image/*") },
                            onRotate90 = { viewModel.rotateLayer90(it) },
                            onFlipHorizontal = { viewModel.flipLayerHorizontal(it) },
                            onFlipVertical = { viewModel.flipLayerVertical(it) }
                        )
                        AtlasMiniPreview(atlasBmp, p.frames.size) { viewModel.setMode(WorkspaceMode.ATLAS) }
                    }
                    WorkspaceMode.SPRITE -> {
                        Row(
                            Modifier.fillMaxWidth().clickable { geometryBankExpanded = !geometryBankExpanded }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Frame Bank", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text(if (geometryBankExpanded) "Hide" else "${p.frames.size} frames • Open")
                        }
                        if (geometryBankExpanded) FrameBankPanel(
                            frames = p.frames,
                            activeFrameId = activeFrameId,
                            thumbnails = { frame -> viewModel.loadBitmap(frame.bitmapPath) },
                            // Selecting a frame opens the dedicated Frame Editor (Blueprint #31,
                            // SESI 5) instead of just toggling a preview flag in-place - it does
                            // NOT copy the frame into the main layers, only scopes editing to it.
                            onSelect = { id ->
                                viewModel.setActiveFrame(id)
                                viewModel.setActiveFrameEdit(id)
                                editingFrameInEdit = true // lets EDIT-tab pixel tools still target this frame if the user flips tabs
                                geometryBankExpanded = false
                                viewModel.setMode(WorkspaceMode.FRAME)
                            },
                            onDuplicate = { id -> viewModel.duplicateFrame(id) },
                            onDelete = { id -> viewModel.deleteFrame(id) },
                            onResize = { id, w, h -> viewModel.resizeFrameCanvas(id, w, h) }
                        )
                        // Auto-updated Atlas preview (SESI 8): every sprite added to the bank is
                        // immediately reflected here, not just in the dedicated Atlas tab.
                        AtlasMiniPreview(atlasBmp, p.frames.size) { viewModel.setMode(WorkspaceMode.ATLAS) }
                        // Geometry Inspector (Blueprint #22-23/#26-27): actualBounds/offset/
                        // excess were already computed by GeometryEngine but never shown
                        // anywhere - this surfaces them for the currently selected frame.
                        val activeFrame = p.frames.find { it.id == activeFrameId }
                        if (activeFrame != null) {
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            var geometryExpanded by remember { mutableStateOf(false) }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { geometryExpanded = !geometryExpanded }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Filled.Straighten, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Geometry", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Icon(if (geometryExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                            }
                            if (geometryExpanded) {
                                GeometryPanel(
                                    frame = activeFrame,
                                    onTrimModeChange = { viewModel.setFrameTrimMode(activeFrame.id, it) },
                                    onRotatedChange = { viewModel.setFrameRotated(activeFrame.id, it) },
                                    onRecalculate = { viewModel.recalculateFrameGeometry(activeFrame.id) }
                                )
                            }
                        }
                    }
                    WorkspaceMode.ANIMATION -> {
                        AnimationListRow(
                            animations = p.animations,
                            activeId = activeAnimationId,
                            onSelect = { viewModel.setActiveAnimation(it) },
                            onCreate = { name -> viewModel.createAnimation(name) },
                            onRename = { id, name -> viewModel.renameAnimation(id, name) },
                            onDelete = { id -> viewModel.deleteAnimation(id) }
                        )
                        val activeAnim = p.animations.find { it.id == activeAnimationId }
                        if (activeAnim != null) {
                            Spacer(Modifier.height(8.dp))
                            AnimationPreview(
                                activeAnim, p.frames, { f -> viewModel.loadBitmap(f.bitmapPath) },
                                onFpsChange = { fps -> viewModel.setAnimationFps(activeAnim.id, fps) },
                                onLoopChange = { loop -> viewModel.setAnimationLoop(activeAnim.id, loop) }
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("Timeline", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 12.dp))
                            AnimationTimeline(
                                activeAnim, p.frames, { f -> viewModel.loadBitmap(f.bitmapPath) },
                                onReorder = { from, to -> viewModel.reorderAnimationFrames(activeAnim.id, from, to) },
                                onRemove = { fid -> viewModel.removeFrameFromAnimation(activeAnim.id, fid) }
                            )
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            // ONION SKIN (SESI 8): shows the previous frame in this sequence
                            // faintly underneath whatever frame is currently being drawn - see
                            // ProjectViewModel.onionSkinFrameId for the "previous in sequence"
                            // lookup. Auto-hides completely whenever the switch is off.
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                            ) {
                                Icon(Icons.Filled.Layers, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Onion Skin", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Switch(checked = p.onionSkinEnabled, onCheckedChange = { viewModel.toggleOnionSkin(it) })
                            }
                            if (p.onionSkinEnabled) {
                                Column(Modifier.padding(horizontal = 12.dp)) {
                                    Text("Ghost opacity: ${(p.onionSkinOpacity * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                                    Slider(
                                        value = p.onionSkinOpacity,
                                        onValueChange = { viewModel.setOnionSkinOpacity(it) },
                                        valueRange = 0.05f..0.9f
                                    )
                                }
                            }
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            Text("Add frames from bank:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp))
                            FrameBankPanel(
                                frames = p.frames,
                                activeFrameId = activeFrameId,
                                thumbnails = { f -> viewModel.loadBitmap(f.bitmapPath) },
                                onSelect = { frameId -> viewModel.addFramesToAnimation(activeAnim.id, listOf(frameId)) },
                                onDuplicate = { id -> viewModel.duplicateFrame(id) },
                                onDelete = { id -> viewModel.deleteFrame(id) },
                                onResize = { id, w, h -> viewModel.resizeFrameCanvas(id, w, h) }
                            )
                            AtlasMiniPreview(atlasBmp, p.frames.size) { viewModel.setMode(WorkspaceMode.ATLAS) }
                        }
                    }
                    WorkspaceMode.ATLAS -> {
                        AtlasPanel(
                            atlas = p.atlas,
                            atlasBitmap = atlasBmp,
                            padding = p.atlas.padding,
                            onPaddingChange = { viewModel.packAtlas(it, 1, p.atlas.powerOfTwo, p.atlas.alphaTrim) },
                            spacing = 1,
                            onSpacingChange = { viewModel.packAtlas(p.atlas.padding, it, p.atlas.powerOfTwo, p.atlas.alphaTrim) },
                            powerOfTwo = p.atlas.powerOfTwo,
                            onPowerOfTwoChange = { viewModel.packAtlas(p.atlas.padding, 1, it, p.atlas.alphaTrim) },
                            alphaTrim = p.atlas.alphaTrim,
                            onAlphaTrimChange = { viewModel.packAtlas(p.atlas.padding, 1, p.atlas.powerOfTwo, it) },
                            onPack = { viewModel.packAtlas(p.atlas.padding, 1, p.atlas.powerOfTwo, p.atlas.alphaTrim) }
                        )
                    }
                    WorkspaceMode.FRAME -> {
                        val editFrameId = p.activeFrameEdit?.frameId ?: activeFrameId
                        val frame = p.frames.find { it.id == editFrameId }
                        if (frame != null) {
                            FrameInspectorPanel(
                                frame = frame,
                                onOffsetModeChange = { viewModel.setFrameOffsetMode(frame.id, it) },
                                onManualOffsetChange = { x, y -> viewModel.setFrameOffsetManual(frame.id, x, y) },
                                onClearReference = { viewModel.clearFrameReference(frame.id) },
                                onTrimModeChange = { viewModel.setFrameTrimMode(frame.id, it) },
                                onApplyTrim = { viewModel.applyFrameTrim(frame.id) },
                                onRotatedChange = { viewModel.setFrameRotated(frame.id, it) },
                                onRecalculate = { viewModel.recalculateFrameGeometry(frame.id) }
                            )
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            Text(
                                "Other frames", style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                            FrameBankPanel(
                                frames = p.frames,
                                activeFrameId = activeFrameId,
                                thumbnails = { f -> viewModel.loadBitmap(f.bitmapPath) },
                                onSelect = { id -> viewModel.setActiveFrame(id); viewModel.setActiveFrameEdit(id) },
                                onDuplicate = { id -> viewModel.duplicateFrame(id) },
                                onDelete = { id ->
                                    viewModel.deleteFrame(id)
                                    if (id == frame.id) { viewModel.setActiveFrameEdit(null); viewModel.setMode(WorkspaceMode.SPRITE) }
                                },
                                onResize = { id, w, h -> viewModel.resizeFrameCanvas(id, w, h) }
                            )
                        } else {
                            Text("No frame selected.", modifier = Modifier.padding(12.dp))
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (isBusy) {
        Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }
    } // end root Box

    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text("Import image into editor") },
            text = { Text("Choose how the image should be placed.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingImportUri = null
                    viewModel.createLayerFromBitmap(uri)
                }) { Text("New Layer") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        pendingImportUri = null
                        activeLayerId?.let { viewModel.pasteImportedIntoLayer(it, uri, false) }
                    }) { Text("Paste") }
                    TextButton(onClick = {
                        pendingImportUri = null
                        activeLayerId?.let { viewModel.pasteImportedIntoLayer(it, uri, true) }
                    }) { Text("Replace") }
                }
            }
        )
    }

    if (showChromaKeyDialog) {
        ChromaKeyDialog(
            onDismiss = {
                viewModel.cancelBgRemovalPreview()
                showChromaKeyDialog = false
            },
            initialColor = toolState.color,
            onPreview = { tolerance, feather, fillGaps, color ->
                activeLayerBitmapPath(p, activeLayerId)?.let { path ->
                    viewModel.previewChromaKey(path, BackgroundRemoverEngine.ChromaKeyParams(color, tolerance, feather, fillGaps))
                }
            },
            onApply = { _, _, _, _ ->
                // The dialog's live preview already staged the exact result via onPreview;
                // dismiss the dialog and let the canvas's Apply/Cancel bar commit it, so what
                // the user sees IS what gets applied (no second, possibly-stale engine call).
                showChromaKeyDialog = false
            }
        )
    }

    if (showDetectDialog) {
        SpriteDetectionDialog(
            onDismiss = { showDetectDialog = false },
            onDetect = { tolerance, fragmentMerge, gridPct, minNoise, padding, useAlpha ->
                activeLayerBitmapPath(p, activeLayerId)?.let { path ->
                    val params = SpriteDetectionParams(
                        useAlpha = useAlpha, tolerance = tolerance, fragmentMergeDistancePx = fragmentMerge,
                        gridInGridMergePercent = gridPct, minimumNoisePx2 = minNoise, cropPaddingPx = padding
                    )
                    viewModel.detectAndAddSprites(path, params) { viewModel.setMode(WorkspaceMode.SPRITE) }
                }
                showDetectDialog = false
            }
        )
    }

    if (showReferenceDialog) {
        val ref = p.characterReference
        CharacterReferenceDialog(
            initialLeft = ref?.left ?: 0, initialRight = ref?.right ?: p.canvas.width, initialBottom = ref?.bottom ?: p.canvas.height,
            onDismiss = { showReferenceDialog = false },
            onConfirm = { l, r, b -> viewModel.setCharacterReference(ReferenceBoundary(l, r, b)); showReferenceDialog = false }
        )
    }

    if (showNormalizeDialog) {
        NormalizationDialog(
            onDismiss = { showNormalizeDialog = false },
            onApply = { w, h, pad, anchor ->
                viewModel.normalizeAllFrames(w, h, pad, anchor, NormalizationMode.AUTO)
                showNormalizeDialog = false
            }
        )
    }

    if (showResizeDialog) {
        CanvasResizeDialog(
            currentWidth = p.canvas.width, currentHeight = p.canvas.height,
            onDismiss = { showResizeDialog = false },
            onApply = { w, h -> viewModel.resizeCanvas(w, h); showResizeDialog = false }
        )
    }

    if (showExportSheet) {
        ModalBottomSheet(onDismissRequest = { showExportSheet = false }) {
            ExportSheet(
                project = p,
                atlasBitmap = atlasBmp,
                onExportAll = {
                    showExportSheet = false
                    viewModel.exportProjectAsync { f ->
                        if (f != null) {
                            Toast.makeText(context, "Exported: ${f.name}", Toast.LENGTH_SHORT).show()
                            shareExportedFile(context, f, "application/zip")
                        } else {
                            Toast.makeText(context, "Export failed", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onBackupProject = {
                    showExportSheet = false
                    viewModel.backupProjectAsync { f ->
                        if (f != null) {
                            Toast.makeText(context, "Backup saved: ${f.name}", Toast.LENGTH_SHORT).show()
                            shareExportedFile(context, f, "application/json")
                        } else {
                            Toast.makeText(context, "Backup failed", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onSettingsChange = { newSettings -> viewModel.setExportSettings(newSettings) },
                lastExportPath = p.exportSettings.lastExportPath
            )
        }
    }
}

/**
 * Compact, tappable atlas preview card (SESI 8): surfaces the auto-packed texture atlas outside
 * the dedicated Atlas tab (in Edit/Sprite/Animation), so newly detected/duplicated sprites are
 * visibly "already on the sheet" without switching modes. Tapping jumps to the full Atlas tab.
 */
@Composable
private fun AtlasMiniPreview(atlasBitmap: android.graphics.Bitmap?, frameCount: Int, onOpenAtlas: () -> Unit) {
    if (atlasBitmap == null || frameCount == 0) return
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenAtlas() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        androidx.compose.foundation.Image(
            bitmap = atlasBitmap.asImageBitmap(),
            contentDescription = "Packed atlas preview",
            filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF2A2731))
                .border(1.dp, Color.Gray.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Atlas", style = MaterialTheme.typography.titleSmall)
            Text("$frameCount sprite(s) packed • tap to open", style = MaterialTheme.typography.labelSmall)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null)
    }
}

private fun activeLayerBitmapPath(p: com.pixelforge.core.ProjectModel, layerId: String?): String? =
    p.layers.find { it.id == layerId }?.bitmapPath ?: p.layers.firstOrNull()?.bitmapPath

private fun activeFrameBitmapPath(p: com.pixelforge.core.ProjectModel, frameId: String?): String? =
    p.frames.find { it.id == frameId }?.bitmapPath

@Composable
private fun CanvasResizeDialog(currentWidth: Int, currentHeight: Int, onDismiss: () -> Unit, onApply: (Int, Int) -> Unit) {
    var width by remember { mutableStateOf(currentWidth.toString()) }
    var height by remember { mutableStateOf(currentHeight.toString()) }
    val maxDim = com.pixelforge.core.CanvasPresets.MAX_DIMENSION
    val outOfRange = (width.toIntOrNull() ?: 0) !in 1..maxDim || (height.toIntOrNull() ?: 0) !in 1..maxDim
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Canvas Settings") },
        text = {
            Column {
                OutlinedTextField(
                    value = width, onValueChange = { width = it.filter { c -> c.isDigit() } },
                    label = { Text("Width") }, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("1–$maxDim px") }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = height, onValueChange = { height = it.filter { c -> c.isDigit() } },
                    label = { Text("Height") }, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("1–$maxDim px") }
                )
                if (outOfRange) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Very large canvases can crash the app (out of memory). Please stay within 1–$maxDim px.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !outOfRange,
                onClick = {
                    val w = (width.toIntOrNull() ?: currentWidth).coerceIn(1, maxDim)
                    val h = (height.toIntOrNull() ?: currentHeight).coerceIn(1, maxDim)
                    onApply(w, h)
                }
            ) { Text("Resize") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
