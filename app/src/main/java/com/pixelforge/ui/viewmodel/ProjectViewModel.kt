package com.pixelforge.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pixelforge.core.*
import com.pixelforge.engine.*
import com.pixelforge.export.ExportManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class WorkspaceTool { PENCIL, ERASER, BUCKET, EYEDROPPER, SELECT_MANUAL, SMART_SELECT, GEOMETRY, PAN, ZOOM }
/**
 * FRAME (Blueprint #31, SESI 5): the isolated Frame Editor context. Entered by picking a frame
 * from the Frame Bank - unlike SPRITE/EDIT/ANIMATION/ATLAS this mode's canvas always shows one
 * frame's own cropped bitmap (never the main layers), paired with ProjectModel.activeFrameEdit
 * so the rest of the app can tell "editing a single frame in place" apart from "editing the main
 * canvas". See [ProjectViewModel.setActiveFrameEdit].
 */
enum class WorkspaceMode { EDIT, SPRITE, ANIMATION, ATLAS, FRAME }

data class ToolState(
    val tool: WorkspaceTool = WorkspaceTool.PENCIL,
    val color: Int = Color.BLACK,
    val brushSize: Int = 1,
    val zoom: Int = 8
)

/**
 * Central application state (Blueprint #41 "APPLICATION STATE").
 * Every engine call flows through here so Home, Workspace, and all mode tabs share one
 * project state instead of separate tool-local state.
 */
class ProjectViewModel(app: Application) : AndroidViewModel(app) {

    private val storage = StorageManager(app)
    private val exportManager = ExportManager(storage)
    private val undoManager = UndoManager()

    private val _project = MutableStateFlow<ProjectModel?>(null)
    val project: StateFlow<ProjectModel?> = _project.asStateFlow()

    private val _recentProjects = MutableStateFlow<List<ProjectModel>>(emptyList())
    val recentProjects: StateFlow<List<ProjectModel>> = _recentProjects.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun clearError() { _errorMessage.value = null }

    private val _mode = MutableStateFlow(WorkspaceMode.EDIT)
    val mode: StateFlow<WorkspaceMode> = _mode.asStateFlow()

    private val _toolState = MutableStateFlow(ToolState())
    val toolState: StateFlow<ToolState> = _toolState.asStateFlow()

    private val _activeLayerId = MutableStateFlow<String?>(null)
    val activeLayerId: StateFlow<String?> = _activeLayerId.asStateFlow()

    private val _activeFrameId = MutableStateFlow<String?>(null)
    val activeFrameId: StateFlow<String?> = _activeFrameId.asStateFlow()

    private val _activeAnimationId = MutableStateFlow<String?>(null)
    val activeAnimationId: StateFlow<String?> = _activeAnimationId.asStateFlow()

    // ---- Background remover preview (non-destructive: SESI 4) ----
    // Holds a throwaway composited bitmap (matched pixels made transparent) that the canvas
    // renders INSTEAD of the real layer bitmap while a preview is active. Nothing here ever
    // touches bitmapCache[targetPath]/the real layer until applyBgRemovalPreview() commits it -
    // mirrors the FloatingSelection "ghost preview" pattern already used elsewhere.
    private val _bgRemovalPreview = MutableStateFlow<Bitmap?>(null)
    val bgRemovalPreview: StateFlow<Bitmap?> = _bgRemovalPreview.asStateFlow()
    private var bgPreviewTargetPath: String? = null

    // in-memory bitmap cache keyed by path - avoids re-decoding PNGs from disk each frame.
    // Synchronized: heavy engine calls now run on Dispatchers.Default (see mutateProjectAsync)
    // while Compose keeps reading this cache from the main thread for rendering, so a plain
    // mutableMapOf would risk a ConcurrentModificationException/crash under that access pattern.
    private val bitmapCache = java.util.Collections.synchronizedMap(mutableMapOf<String, Bitmap>())
    private var strokeInProgress: String? = null

    val canUndo: Boolean get() = undoManager.canUndo
    val canRedo: Boolean get() = undoManager.canRedo

    // Undo/Redo enabled-state as observable Compose state. `canUndo`/`canRedo` above are plain
    // Kotlin properties backed by a mutable ArrayDeque - reading them from inside a lambda that
    // Compose doesn't otherwise recompose (e.g. TopAppBar's `actions = { ... }` slot, which
    // reads no snapshot State at all) meant the Undo/Redo buttons' `enabled` value was computed
    // ONCE at first composition and then frozen - explaining "undo/redo tidak berfungsi": the
    // button was stuck disabled (or stuck enabled) regardless of later edits/undos. Every path
    // that touches undoManager now also refreshes these flows so the UI can actually observe it.
    private val _canUndo = MutableStateFlow(false)
    val canUndoFlow: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedoFlow: StateFlow<Boolean> = _canRedo.asStateFlow()
    private fun refreshUndoRedoState() {
        _canUndo.value = undoManager.canUndo
        _canRedo.value = undoManager.canRedo
    }

    // ---------- Home / project lifecycle ----------

    fun refreshRecentProjects() {
        viewModelScope.launch {
            val projects = withContext(Dispatchers.IO) { storage.listProjects() }
            _recentProjects.value = projects
        }
    }

    fun newProject(name: String, width: Int, height: Int, transparent: Boolean = true, onCreated: (String) -> Unit = {}) {
        if (_isBusy.value) return
        val w = width.coerceIn(CanvasPresets.MIN_DIMENSION, CanvasPresets.MAX_DIMENSION)
        val h = height.coerceIn(CanvasPresets.MIN_DIMENSION, CanvasPresets.MAX_DIMENSION)
        _isBusy.value = true
        viewModelScope.launch {
            val p = withContext(Dispatchers.Default) {
                val project = ProjectModel(name = name, canvas = CanvasSettings(w, h, transparent, Color.WHITE))
                val bmp = PixelEngine.createBitmap(w, h, transparent, Color.WHITE)
                val path = storage.saveBitmap(project.id, storage.newAssetName("layer"), bmp)
                bitmapCache[path] = bmp
                project.layers.add(LayerModel(name = "Layer 1", bitmapPath = path))
                storage.saveProject(project)
                project
            }
            _activeLayerId.value = p.layers.first().id
            _project.value = p
            _atlasPreview.value = null
            undoManager.clear()
            refreshUndoRedoState()
            refreshRecentProjects()
            _isBusy.value = false
            onCreated(p.id)
        }
    }

    fun openProject(projectId: String, onOpened: () -> Unit = {}) {
        if (_isBusy.value) return
        _isBusy.value = true
        bitmapCache.clear()
        viewModelScope.launch {
            try {
                val p = withContext(Dispatchers.IO) { storage.loadProject(projectId) }
                // Warm up the active layer bitmap now (still on IO), so a corrupt/huge legacy
                // project fails here with a message instead of crashing later mid-render.
                withContext(Dispatchers.IO) {
                    p.layers.firstOrNull()?.let { runCatching { storage.loadBitmap(it.bitmapPath) } }
                }
                _project.value = p
                _activeLayerId.value = p.layers.firstOrNull()?.id
                _activeFrameId.value = p.frames.firstOrNull()?.id
                _activeAnimationId.value = p.animations.firstOrNull()?.id
                undoManager.clear()
                refreshUndoRedoState()
                refreshAtlasPreview()
                // Same rule as importImageAsNewProject: release the flag before invoking any
                // callback, in case a future caller's onOpened() itself calls a busy-guarded
                // ViewModel function (it would otherwise be silently ignored).
                _isBusy.value = false
                onOpened()
            } catch (oom: OutOfMemoryError) {
                _errorMessage.value = "This project's canvas is too large to open on this device (out of memory). Try again after closing other apps, or recreate it at a smaller size."
                _isBusy.value = false
            } catch (e: Exception) {
                _errorMessage.value = "Could not open project: ${e.message ?: "unknown error"}"
                _isBusy.value = false
            }
        }
    }

    fun deleteProject(projectId: String) {
        storage.deleteProject(projectId)
        refreshRecentProjects()
    }

    fun importImageAsNewProject(uri: Uri, name: String, onCreated: (String) -> Unit = {}) {
        if (_isBusy.value) return
        _isBusy.value = true
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val p = withContext(Dispatchers.IO) {
                    val maxDim = CanvasPresets.MAX_DIMENSION
                    val sample = app.contentResolver.openInputStream(uri).use { boundsStream ->
                        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeStream(boundsStream, null, opts)
                        var s = 1
                        while (opts.outWidth / s > maxDim || opts.outHeight / s > maxDim) s *= 2
                        s
                    }
                    app.contentResolver.openInputStream(uri).use { stream ->
                        val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                        val bmp = android.graphics.BitmapFactory.decodeStream(stream, null, decodeOpts)
                            ?: throw IllegalStateException("Could not decode image")
                        val mutable = PixelEngine.mutableCopy(bmp)
                        val project = ProjectModel(name = name, canvas = CanvasSettings(mutable.width, mutable.height, true, Color.WHITE))
                        val path = storage.saveBitmap(project.id, storage.newAssetName("import"), mutable)
                        bitmapCache[path] = mutable
                        project.sourceAssets.add(path)
                        project.layers.add(LayerModel(name = "Layer 1", bitmapPath = path))
                        storage.saveProject(project)
                        project
                    }
                }
                _activeLayerId.value = p.layers.first().id
                _project.value = p
                undoManager.clear()
                refreshUndoRedoState()
                refreshRecentProjects()
                // Must flip busy off BEFORE onCreated(): onCreated -> HomeScreen's onOpenProject
                // -> viewModel.openProject(id) { navigate }, and openProject() itself starts
                // with `if (_isBusy.value) return`. With the flag still true here, that guard
                // silently swallowed the call and navigation to the workspace never fired -
                // the app just sat on the Home screen after "Import Image" looking stuck.
                _isBusy.value = false
                onCreated(p.id)
            } catch (oom: OutOfMemoryError) {
                _errorMessage.value = "That image is too large to import on this device (out of memory). Try a smaller image."
                _isBusy.value = false
            } catch (e: Exception) {
                _errorMessage.value = "Could not import image: ${e.message ?: "unknown error"}"
                _isBusy.value = false
            }
        }
    }

    fun saveProject() {
        _project.value?.let { storage.saveProject(it) }
    }

    fun setMode(m: WorkspaceMode) { _mode.value = m }
    fun setTool(t: WorkspaceTool) { _toolState.value = _toolState.value.copy(tool = t) }
    fun setColor(c: Int) { _toolState.value = _toolState.value.copy(color = c) }
    fun setBrushSize(s: Int) { _toolState.value = _toolState.value.copy(brushSize = s.coerceIn(1, 32)) }
    fun setZoom(z: Int) { _toolState.value = _toolState.value.copy(zoom = z.coerceIn(1, 32)) }

    // ---------- Editor state (Blueprint #4) ----------
    // These read/write the SAME ProjectModel (via _project) that the rest of the ViewModel
    // uses - no screen keeps a local copy of selection mode, viewport, or the in-progress
    // floating selection/geometry. They deliberately do NOT go through UndoManager: mode/
    // viewport changes aren't user-visible "edits" to undo, and floating selection/geometry
    // are drafts - only their eventual Commit/Apply (a real pixel mutation, via mutateProject)
    // belongs in history. See UndoManager doc for what does need recording.

    /** Explicit Smart Select combine mode (replaces old inside/outside inference). */
    fun setSelectionMode(mode: SelectionMode) {
        _project.value = _project.value?.copy(selectionMode = mode)
    }

    /** Pan/zoom framing only - never touches pixel data (Blueprint #11). */
    fun setEditorViewport(panX: Float, panY: Float, zoom: Int) {
        _project.value = _project.value?.copy(editorViewport = EditorViewport(panX, panY, zoom))
    }

    fun setFloatingSelection(sel: FloatingSelection?) {
        _project.value = _project.value?.copy(floatingSelection = sel)
    }

    fun setFloatingGeometry(geom: FloatingGeometry?) {
        _project.value = _project.value?.copy(floatingGeometry = geom)
    }

    /** Opens/closes the isolated Frame Editor context for one Frame Bank entry (Blueprint #31) -
     *  does not copy the frame's bitmap into the main layers. */
    fun setActiveFrameEdit(frameId: String?) {
        _project.value = _project.value?.copy(activeFrameEdit = frameId?.let { ActiveFrameEdit(it) })
    }

    // ---------- Bitmap access ----------

    fun loadBitmap(path: String): Bitmap = bitmapCache.getOrPut(path) { storage.loadBitmap(path) }

    fun activeLayerBitmap(): Bitmap? {
        val p = _project.value ?: return null
        val layer = p.layers.find { it.id == _activeLayerId.value } ?: return null
        return loadBitmap(layer.bitmapPath)
    }

    fun activeFrameBitmap(): Bitmap? {
        val p = _project.value ?: return null
        val frame = p.frames.find { it.id == _activeFrameId.value } ?: return null
        return loadBitmap(frame.bitmapPath)
    }

    // ---------- Pixel editing (Blueprint #5, #18) ----------

    /**
     * Reads the current on-disk PNG bytes for each path in [paths] - i.e. the pixel content
     * as it stands BEFORE an about-to-run mutation touches it. Must always be called before
     * that mutation's own persistBitmap()/bitmapCache write, or it captures the wrong (already
     * mutated) bytes. See [UndoManager]'s class doc for why this exists.
     */
    private fun snapshotBitmapBytes(paths: List<String>): Map<String, ByteArray> {
        if (paths.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, ByteArray>()
        paths.distinct().forEach { path ->
            runCatching { File(path).readBytes() }.getOrNull()?.let { result[path] = it }
        }
        return result
    }

    /** Writes snapshotted PNG bytes back to disk AND into the in-memory cache for each path,
     *  so both the persisted file and whatever Compose is currently rendering reflect the
     *  restored (undo/redo) pixel content immediately. */
    private fun restoreBitmapSnapshot(bitmaps: Map<String, ByteArray>) {
        bitmaps.forEach { (path, bytes) ->
            runCatching { File(path).writeBytes(bytes) }
            val options = android.graphics.BitmapFactory.Options().apply { inMutable = true }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (bmp != null) bitmapCache[path] = bmp else bitmapCache.remove(path)
        }
    }

    private fun mutateProject(affectedBitmapPaths: List<String> = emptyList(), block: (ProjectModel) -> Unit) {
        val current = _project.value ?: return
        val snapshot = snapshotBitmapBytes(affectedBitmapPaths)
        undoManager.record(current, snapshot)
        refreshUndoRedoState()
        val copy = current.deepCopy()
        block(copy)
        _project.value = copy
    }

    /**
     * Same contract as [mutateProject], but runs `block` off the main thread.
     * Root-cause fix for the ANR in crash.txt: "Input dispatching timed out ...
     * com.pixelforge/.MainActivity (server) is not responding. Waited 5000ms for MotionEvent".
     * Every pixel-crunching engine call (sprite detection, atlas packing, normalization,
     * background removal, canvas resize, selection masks) used to run synchronously on the
     * UI thread via `mutateProject`, blocking Choreographer/input dispatch for tens of
     * seconds on large canvases - exactly what the log shows. This variant moves the heavy
     * work to Dispatchers.Default and only touches _project.value (StateFlow, safe from any
     * thread but we hop back to Main via viewModelScope) once the result is ready.
     */
    private fun mutateProjectAsync(
        affectedBitmapPaths: List<String> = emptyList(),
        busy: Boolean = true,
        onComplete: () -> Unit = {},
        block: suspend (ProjectModel) -> Unit
    ) {
        val current = _project.value ?: return
        if (busy && _isBusy.value) return // ignore taps while a heavy op is already running
        if (busy) _isBusy.value = true
        viewModelScope.launch {
            try {
                // Snapshot BEFORE block() runs (and before it persists anything), same
                // ordering requirement as mutateProject - see UndoManager's class doc.
                val snapshot = snapshotBitmapBytes(affectedBitmapPaths)
                val copy = current.deepCopy()
                withContext(Dispatchers.Default) { block(copy) }
                undoManager.record(current, snapshot)
                refreshUndoRedoState()
                _project.value = copy
                onComplete()
            } catch (oom: OutOfMemoryError) {
                _errorMessage.value = "Out of memory performing this operation. Try a smaller canvas or fewer frames."
            } catch (e: Exception) {
                _errorMessage.value = "Operation failed: ${e.message ?: "unknown error"}"
            } finally {
                if (busy) _isBusy.value = false
            }
        }
    }

    /** Called on touch-down of a pixel-tool gesture; kept as a hook point (e.g. future
     *  bitmap-level diffing) without pre-allocating a full bitmap copy per stroke. */
    fun beginStroke(path: String) {
        strokeInProgress = path
    }

    fun applyPixelTool(path: String, x: Int, y: Int) {
        val bmp = loadBitmap(path)
        when (_toolState.value.tool) {
            // Pencil/eraser/eyedropper only touch a small brush-sized neighborhood, so they
            // stay synchronous on the calling (UI) thread - that's what keeps a drag stroke
            // feeling responsive frame-to-frame.
            WorkspaceTool.PENCIL -> {
                PixelEngine.pencil(bmp, x, y, _toolState.value.color, _toolState.value.brushSize)
                touchProject()
            }
            WorkspaceTool.ERASER -> {
                PixelEngine.eraser(bmp, x, y, _toolState.value.brushSize)
                touchProject()
            }
            WorkspaceTool.EYEDROPPER -> {
                _toolState.value = _toolState.value.copy(color = PixelEngine.eyedropper(bmp, x, y))
                touchProject()
            }
            // Bucket fill is an unbounded flood fill - on a large canvas it can walk millions
            // of pixels. Running it synchronously on touch-down blocked the main thread past
            // Android's 5s input-dispatch timeout, which is the ANR in crash.txt
            // ("Waited 5000ms for MotionEvent" on MainActivity right after a tap). Route it
            // through the same background + isBusy pattern as the other heavy engine calls.
            //
            // BUGFIX: this used to only call touchProject() here and rely on the UI's onUp
            // handler calling commitStroke() separately once the touch gesture ends. Since a
            // bucket-fill tap's "up" event fires essentially immediately (there's no drag to
            // wait out), commitStroke() - and the persistBitmap()/undo snapshot it does - was
            // racing this coroutine and almost always ran BEFORE the fill actually finished
            // mutating `bmp`. Net effect: persistBitmap() wrote the PRE-fill bitmap to disk,
            // the fill landed only in the in-memory cache, and was never recorded in undo
            // history - so the filled result could silently vanish (e.g. on the next
            // undo/redo, which restores from disk, or app restart). Committing here,
            // atomically after the fill completes, makes bucket fill self-contained instead
            // of depending on a separately-timed UI callback.
            WorkspaceTool.BUCKET -> {
                if (_isBusy.value) return
                _isBusy.value = true
                val color = _toolState.value.color
                viewModelScope.launch {
                    try {
                        withContext(Dispatchers.Default) { PixelEngine.bucketFill(bmp, x, y, color) }
                        commitStroke(path)
                    } catch (e: Exception) {
                        _errorMessage.value = "Bucket fill failed: ${e.message ?: "unknown error"}"
                    } finally {
                        _isBusy.value = false
                    }
                }
            }
            else -> {}
        }
    }

    /** Commit the stroke into project-level undo history (called on touch-up). */
    fun commitStroke(path: String) {
        strokeInProgress = null
        val current = _project.value ?: return
        // Snapshot the on-disk bytes BEFORE persistBitmap() overwrites them - at this point
        // the in-memory bitmapCache entry already holds the mutated stroke, but the file on
        // disk still holds the pre-stroke content, since nothing has persisted it yet.
        val snapshot = snapshotBitmapBytes(listOf(path))
        undoManager.record(current, snapshot)
        refreshUndoRedoState()
        persistBitmap(path)
    }

    private fun persistBitmap(path: String) {
        val bmp = bitmapCache[path] ?: return
        val f = File(path)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun touchProject() {
        _project.value = _project.value?.copy(updatedAtMillis = System.currentTimeMillis())
    }

    private fun readBitmapBytesFromDisk(path: String): ByteArray? = runCatching { File(path).readBytes() }.getOrNull()

    fun undo() {
        val current = _project.value ?: return
        val entry = undoManager.undo(current, ::readBitmapBytesFromDisk) ?: return
        // Restore the exact pre-mutation pixel bytes for whatever this step touched - clearing
        // the whole cache (the old approach) didn't help, since the on-disk file itself had
        // already been overwritten by the edit being undone; forcing a reload just reloaded
        // the same (wrong) bytes. See UndoManager's class doc.
        restoreBitmapSnapshot(entry.bitmaps)
        _project.value = entry.project
        refreshUndoRedoState()
    }

    fun redo() {
        val current = _project.value ?: return
        val entry = undoManager.redo(current, ::readBitmapBytesFromDisk) ?: return
        restoreBitmapSnapshot(entry.bitmaps)
        _project.value = entry.project
        refreshUndoRedoState()
    }

    // ---------- Layers (Blueprint #16) ----------

    fun addLayer() = mutateProject { p ->
        val bmp = PixelEngine.createBitmap(p.canvas.width, p.canvas.height, true, Color.TRANSPARENT)
        val path = storage.saveBitmap(p.id, storage.newAssetName("layer"), bmp)
        bitmapCache[path] = bmp
        val layer = LayerEngine.newLayer("Layer ${p.layers.size + 1}", path)
        p.layers.add(layer)
        _activeLayerId.value = layer.id
    }

    fun duplicateLayer(layerId: String) = mutateProject { p ->
        val layer = p.layers.find { it.id == layerId } ?: return@mutateProject
        val newPath = storage.saveBitmap(p.id, storage.newAssetName("layer"), PixelEngine.mutableCopy(loadBitmap(layer.bitmapPath)))
        val dup = LayerEngine.duplicate(layer, newPath)
        p.layers.add(dup)
    }

    fun deleteLayer(layerId: String) = mutateProject { p ->
        LayerEngine.delete(p.layers, layerId)
        if (_activeLayerId.value == layerId) _activeLayerId.value = p.layers.firstOrNull()?.id
    }

    fun renameLayer(layerId: String, name: String) = mutateProject { p ->
        p.layers.find { it.id == layerId }?.name = name
    }

    fun moveLayerUp(index: Int) = mutateProject { p -> LayerEngine.moveUp(p.layers, index) }
    fun moveLayerDown(index: Int) = mutateProject { p -> LayerEngine.moveDown(p.layers, index) }
    fun setLayerVisible(layerId: String, visible: Boolean) = mutateProject { p ->
        p.layers.find { it.id == layerId }?.visible = visible
    }
    fun setLayerOpacity(layerId: String, opacity: Float) = mutateProject { p ->
        p.layers.find { it.id == layerId }?.opacity = opacity
    }
    fun setLayerLocked(layerId: String, locked: Boolean) = mutateProject { p ->
        p.layers.find { it.id == layerId }?.locked = locked
    }
    fun transformLayer(layerId: String, op: (Bitmap) -> Bitmap) {
        val currentPath = _project.value?.layers?.find { it.id == layerId }?.bitmapPath
        mutateProject(listOfNotNull(currentPath)) { p ->
            val layer = p.layers.find { it.id == layerId } ?: return@mutateProject
            val newBmp = op(loadBitmap(layer.bitmapPath))
            bitmapCache[layer.bitmapPath] = newBmp
            persistBitmap(layer.bitmapPath)
        }
    }

    fun setActiveLayer(id: String) { _activeLayerId.value = id }

    /**
     * REPLACE IMAGE (Blueprint #16): swaps a layer's entire bitmap for a newly-imported image,
     * fit (nearest-neighbor, no blending) to the current canvas size. Previously there was no
     * way to bring an external image into an existing project at all past the Home-screen
     * "Import Image -> new project" flow.
     */
    fun replaceLayerImage(layerId: String, uri: Uri) {
        if (_isBusy.value) return
        val p = _project.value ?: return
        val layer = p.layers.find { it.id == layerId } ?: return
        _isBusy.value = true
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val resized = withContext(Dispatchers.IO) {
                    val decoded = app.contentResolver.openInputStream(uri).use { stream ->
                        android.graphics.BitmapFactory.decodeStream(stream)
                            ?: throw IllegalStateException("Could not decode image")
                    }
                    PixelEngine.resizeNearestNeighbor(decoded, p.canvas.width, p.canvas.height)
                }
                bitmapCache[layer.bitmapPath] = resized
                commitStroke(layer.bitmapPath)
            } catch (e: Exception) {
                _errorMessage.value = "Could not replace image: ${e.message ?: "unknown error"}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    /**
     * PASTE INTO LAYER (Blueprint #16): composites an imported image onto the active layer's
     * existing pixels at (x, y) instead of replacing them outright.
     */
    fun pasteImageIntoLayer(layerId: String, uri: Uri, x: Int = 0, y: Int = 0) {
        if (_isBusy.value) return
        val p = _project.value ?: return
        val layer = p.layers.find { it.id == layerId } ?: return
        _isBusy.value = true
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val pasted = withContext(Dispatchers.IO) {
                    val decoded = app.contentResolver.openInputStream(uri).use { stream ->
                        android.graphics.BitmapFactory.decodeStream(stream)
                            ?: throw IllegalStateException("Could not decode image")
                    }
                    PixelEngine.pasteOnto(loadBitmap(layer.bitmapPath), decoded, x, y)
                }
                bitmapCache[layer.bitmapPath] = pasted
                commitStroke(layer.bitmapPath)
            } catch (e: Exception) {
                _errorMessage.value = "Could not paste image: ${e.message ?: "unknown error"}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun rotateLayer90(layerId: String) = transformLayer(layerId) { PixelEngine.rotate90(it) }
    fun flipLayerHorizontal(layerId: String) = transformLayer(layerId) { PixelEngine.flipHorizontal(it) }
    fun flipLayerVertical(layerId: String) = transformLayer(layerId) { PixelEngine.flipVertical(it) }

    // ---------- Geometry / import ----------

    /** Legacy one-shot rasterize, kept for any caller that wants to skip the floating-preview
     *  step entirely and burn a spec straight into a layer. */
    fun drawGeometry(path: String, spec: GeometrySpec) {
        if (_isBusy.value) return
        val before = _project.value ?: return
        _isBusy.value = true
        val snapshot = snapshotBitmapBytes(listOf(path))
        viewModelScope.launch {
            try {
                val bmp = loadBitmap(path)
                val result = withContext(Dispatchers.Default) { GeometryEngine.drawGeometry(bmp, spec, _toolState.value.color) }
                undoManager.record(before, snapshot)
                bitmapCache[path] = result
                persistBitmap(path)
                refreshUndoRedoState()
                touchProject()
            } catch(e:Exception){ _errorMessage.value="Geometry failed: ${e.message ?: "unknown error"}" }
            finally { _isBusy.value=false }
        }
    }

    // ---------- Floating Geometry (Blueprint #19/#20/#83) ----------
    // Shapes drawn with the Geometry tool live on ProjectModel.floatingGeometry (not local
    // screen state) exactly like FloatingSelection - a live, editable draft until Apply burns
    // it into the target layer via GeometryEngine.rasterizeFloating, or Cancel discards it.
    // Nothing is written to the bitmap until Apply: touch-down/drag only ever update the
    // FloatingGeometry's spec/offset/rotation/flip/scale fields.

    /** Touch-down on the GEOMETRY tool: starts a new floating shape at a single point. */
    fun beginFloatingGeometry(type: GeometryType, targetLayerId: String, x: Int, y: Int) {
        setFloatingGeometry(GeometryEngine.beginFloating(type, targetLayerId, x, y, _toolState.value.color))
    }

    /** Drag while drawing: stretches the shape's raw end point (still unresolved/untransformed). */
    fun updateFloatingGeometryDrag(x: Int, y: Int) {
        val fg = _project.value?.floatingGeometry ?: return
        setFloatingGeometry(fg.copy(spec = fg.spec.copy(endX = x, endY = y)))
    }

    fun moveFloatingGeometry(dx: Int, dy: Int) {
        val fg = _project.value?.floatingGeometry ?: return
        setFloatingGeometry(fg.copy(offsetX = fg.offsetX + dx, offsetY = fg.offsetY + dy))
    }

    fun rotateFloatingGeometry90() {
        val fg = _project.value?.floatingGeometry ?: return
        setFloatingGeometry(fg.copy(rotationDegrees = (fg.rotationDegrees + 90) % 360))
    }

    fun flipFloatingGeometry(horizontal: Boolean) {
        val fg = _project.value?.floatingGeometry ?: return
        setFloatingGeometry(
            if (horizontal) fg.copy(flippedH = !fg.flippedH) else fg.copy(flippedV = !fg.flippedV)
        )
    }

    fun scaleFloatingGeometry(factor: Float) {
        val fg = _project.value?.floatingGeometry ?: return
        setFloatingGeometry(fg.copy(scale = (fg.scale * factor).coerceIn(0.1f, 20f)))
    }

    fun setFloatingGeometryColor(colorArgb: Int) {
        val fg = _project.value?.floatingGeometry ?: return
        setFloatingGeometry(fg.copy(colorArgb = colorArgb))
    }

    fun setFloatingGeometryThickness(thickness: Int) {
        val fg = _project.value?.floatingGeometry ?: return
        setFloatingGeometry(fg.copy(spec = fg.spec.copy(thickness = thickness.coerceAtLeast(1))))
    }

    /** Burns the floating shape into [targetPath] at its fully-resolved transform (Apply). */
    fun applyFloatingGeometry(targetPath: String) {
        if (_isBusy.value) return
        val before = _project.value ?: return
        val fg = before.floatingGeometry ?: return
        _isBusy.value = true
        val snapshot = snapshotBitmapBytes(listOf(targetPath))
        viewModelScope.launch {
            try {
                val bmp = loadBitmap(targetPath)
                val result = withContext(Dispatchers.Default) { GeometryEngine.rasterizeFloating(bmp, fg) }
                undoManager.record(before, snapshot)
                bitmapCache[targetPath] = result
                persistBitmap(targetPath)
                refreshUndoRedoState()
                setFloatingGeometry(null)
                touchProject()
            } catch (e: Exception) {
                _errorMessage.value = "Geometry failed: ${e.message ?: "unknown error"}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    /** Discards the floating shape. Nothing was ever rasterized, so this is a pure state clear
     *  (no bitmap to restore) - unlike cancelFloatingSelection, which may undo a Cut. */
    fun cancelFloatingGeometry() {
        setFloatingGeometry(null)
    }

    fun createLayerFromBitmap(uri: Uri, x: Int = 0, y: Int = 0) {
        if (_isBusy.value) return
        val p = _project.value ?: return
        _isBusy.value = true
        val app=getApplication<Application>()
        viewModelScope.launch {
            try {
                val decoded=withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri).use { android.graphics.BitmapFactory.decodeStream(it) }
                        ?: throw IllegalStateException("Could not decode image")
                }
                val layerBmp=PixelEngine.createBitmap(p.canvas.width,p.canvas.height,true,Color.TRANSPARENT)
                val composed=withContext(Dispatchers.Default) { PixelEngine.pasteOnto(layerBmp,decoded,x,y) }
                val path=storage.saveBitmap(p.id,storage.newAssetName("import"),composed)
                bitmapCache[path]=composed
                val layer=LayerEngine.newLayer("Imported ${p.layers.size+1}",path)
                val current=_project.value ?: return@launch
                undoManager.record(current)
                current.layers.add(layer)
                _activeLayerId.value=layer.id
                _project.value=current
                refreshUndoRedoState()
            } catch(e:Exception){ _errorMessage.value="Import failed: ${e.message ?: "unknown error"}" }
            finally { _isBusy.value=false }
        }
    }

    fun pasteImportedIntoLayer(layerId: String, uri: Uri, replace: Boolean=false) {
        if(replace) replaceLayerImage(layerId,uri) else pasteImageIntoLayer(layerId,uri)
    }

    // ---------- Canvas ----------

    fun resizeCanvas(width: Int, height: Int, anchorX: Float = 0f, anchorY: Float = 0f) {
        val w = width.coerceIn(CanvasPresets.MIN_DIMENSION, CanvasPresets.MAX_DIMENSION)
        val h = height.coerceIn(CanvasPresets.MIN_DIMENSION, CanvasPresets.MAX_DIMENSION)
        val affectedPaths = _project.value?.layers?.map { it.bitmapPath } ?: emptyList()
        mutateProjectAsync(affectedPaths) { p ->
            p.layers.forEach { layer ->
                val resized = PixelEngine.resizeCanvasPreservingPixels(loadBitmap(layer.bitmapPath), w, h, anchorX, anchorY)
                bitmapCache[layer.bitmapPath] = resized
                persistBitmap(layer.bitmapPath)
            }
            p.canvas = p.canvas.copy(width = w, height = h)
        }
    }

    // ---------- Selection (Blueprint #6-15) ----------

    /** Combines the drag rectangle using the user's EXPLICIT [SelectionMode] (Blueprint #15) -
     *  no more inferring ADD/SUBTRACT from whether the drag started inside the selection. */
    fun smartSelectDrag(rect: Rect) = mutateProject { p ->
        val opType = when (p.selectionMode) {
            SelectionMode.REPLACE -> SelectOpType.REPLACE
            SelectionMode.ADD -> SelectOpType.ADD
            SelectionMode.SUBTRACT -> SelectOpType.SUBTRACT
            SelectionMode.TOGGLE -> SelectOpType.TOGGLE
        }
        val op = SelectOperation(opType, rect.x, rect.y, rect.width, rect.height)
        p.selection = SelectionEngine.applyOperation(p.selection, op)
    }

    fun manualSelect(rect: Rect) = mutateProject { p ->
        p.selection = SelectionState(
            boundsX = rect.x, boundsY = rect.y, boundsWidth = rect.width, boundsHeight = rect.height,
            maskRLE = SelectionEngine.encodeRLE(BooleanArray(rect.width * rect.height) { true })
        )
    }

    fun clearSelection() = mutateProject { p -> p.selection = SelectionEngine.clear() }
    fun invertSelection() = mutateProject { p -> p.selection = SelectionEngine.invert(p.selection, p.canvas.width, p.canvas.height) }
    fun expandSelection(px: Int) = mutateProject { p -> p.selection = SelectionEngine.expand(p.selection, px, p.canvas.width, p.canvas.height) }
    fun contractSelection(px: Int) = mutateProject { p -> p.selection = SelectionEngine.contract(p.selection, px, p.canvas.width, p.canvas.height) }

    // ---------- Floating Selection (Blueprint #17-18) ----------
    // Cut/Copy lift the selected pixels into a FloatingSelection that lives on ProjectModel
    // (not local screen state) so it survives navigation and is a single source of truth.
    // It is NOT burned into the layer until commitFloatingSelection() (Commit); cancel restores
    // the original layer bytes for a Cut (Copy never touched the source, so cancel is a no-op).

    /** In-memory pre-cut snapshot of the source layer, used only to restore on Cancel. Not part
     *  of ProjectModel: it's a transient undo aid, not project data. */
    private var floatingCutSnapshot: Bitmap? = null

    /** Lifts the current selection off [targetPath] into a FloatingSelection. If [cut] is true,
     *  the source pixels are cleared immediately (a real Cut) and the pre-cut bitmap is kept in
     *  memory so Cancel can restore it; Copy leaves the source untouched. */
    fun beginFloatingSelection(targetPath: String, cut: Boolean) {
        val p = _project.value ?: return
        val sel = p.selection
        if (sel.boundsWidth <= 0 || sel.boundsHeight <= 0) return
        val layerId = p.layers.find { it.bitmapPath == targetPath }?.id ?: return
        val src = loadBitmap(targetPath)
        val mask = SelectionEngine.toMask(sel)
        val bx = sel.boundsX; val by = sel.boundsY
        val w = sel.boundsWidth; val h = sel.boundsHeight
        val floatBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (ly in 0 until h) for (lx in 0 until w) {
            val sx = bx + lx; val sy = by + ly
            if (mask.get(sx, sy) && sx in 0 until src.width && sy in 0 until src.height) {
                floatBmp.setPixel(lx, ly, src.getPixel(sx, sy))
            }
        }
        val floatPath = storage.saveBitmap(p.id, storage.newAssetName("floating"), floatBmp)
        bitmapCache[floatPath] = floatBmp

        if (cut) {
            floatingCutSnapshot = src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
            beginStroke(targetPath)
            for (ly in 0 until h) for (lx in 0 until w) {
                val sx = bx + lx; val sy = by + ly
                if (mask.get(sx, sy) && sx in 0 until src.width && sy in 0 until src.height) {
                    src.setPixel(sx, sy, Color.TRANSPARENT)
                }
            }
            commitStroke(targetPath)
        } else {
            floatingCutSnapshot = null
        }

        setFloatingSelection(
            FloatingSelection(
                sourceLayerId = layerId, originX = bx, originY = by,
                width = w, height = h, bitmapPath = floatPath
            )
        )
    }

    /** Move only changes viewport-relative offset - never touches pixel data until Commit. */
    fun moveFloatingSelection(dx: Int, dy: Int) {
        val fs = _project.value?.floatingSelection ?: return
        setFloatingSelection(fs.copy(offsetX = fs.offsetX + dx, offsetY = fs.offsetY + dy))
    }

    fun rotateFloatingSelection90() {
        val p = _project.value ?: return
        val fs = p.floatingSelection ?: return
        val bmp = loadBitmap(fs.bitmapPath)
        val matrix = android.graphics.Matrix().apply { postRotate(90f) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        val path = storage.saveBitmap(p.id, storage.newAssetName("floating"), rotated)
        bitmapCache[path] = rotated
        setFloatingSelection(fs.copy(bitmapPath = path, width = rotated.width, height = rotated.height,
            rotationDegrees = (fs.rotationDegrees + 90) % 360))
    }

    fun flipFloatingSelection(horizontal: Boolean) {
        val p = _project.value ?: return
        val fs = p.floatingSelection ?: return
        val bmp = loadBitmap(fs.bitmapPath)
        val matrix = android.graphics.Matrix().apply {
            if (horizontal) preScale(-1f, 1f) else preScale(1f, -1f)
        }
        val flipped = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        val path = storage.saveBitmap(p.id, storage.newAssetName("floating"), flipped)
        bitmapCache[path] = flipped
        setFloatingSelection(
            fs.copy(
                bitmapPath = path,
                flippedH = if (horizontal) !fs.flippedH else fs.flippedH,
                flippedV = if (!horizontal) !fs.flippedV else fs.flippedV
            )
        )
    }

    fun scaleFloatingSelection(factor: Float) {
        val p = _project.value ?: return
        val fs = p.floatingSelection ?: return
        val bmp = loadBitmap(fs.bitmapPath)
        val newW = (bmp.width * factor).toInt().coerceAtLeast(1)
        val newH = (bmp.height * factor).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bmp, newW, newH, false)
        val path = storage.saveBitmap(p.id, storage.newAssetName("floating"), scaled)
        bitmapCache[path] = scaled
        setFloatingSelection(fs.copy(bitmapPath = path, width = newW, height = newH, scale = fs.scale * factor))
    }

    /** Burns the floating pixels into the source layer at its current offset (Blueprint #18) -
     *  goes through Undo like any other pixel mutation. */
    fun commitFloatingSelection(targetPath: String) {
        val fs = _project.value?.floatingSelection ?: return
        beginStroke(targetPath)
        val dst = loadBitmap(targetPath)
        val src = loadBitmap(fs.bitmapPath)
        val dx = fs.originX + fs.offsetX
        val dy = fs.originY + fs.offsetY
        for (ly in 0 until src.height) for (lx in 0 until src.width) {
            val px = src.getPixel(lx, ly)
            if (android.graphics.Color.alpha(px) == 0) continue
            val tx = dx + lx; val ty = dy + ly
            if (tx in 0 until dst.width && ty in 0 until dst.height) dst.setPixel(tx, ty, px)
        }
        commitStroke(targetPath)
        floatingCutSnapshot = null
        setFloatingSelection(null)
        clearSelection()
    }

    /** Discards the floating selection. For a Cut, restores the pre-cut bitmap (Blueprint #18:
     *  "Jika Cancel, bitmap asli dikembalikan"); Copy never mutated the source so there's
     *  nothing to restore. */
    fun cancelFloatingSelection(targetPath: String) {
        floatingCutSnapshot?.let { snap ->
            beginStroke(targetPath)
            bitmapCache[targetPath] = snap
            commitStroke(targetPath)
        }
        floatingCutSnapshot = null
        setFloatingSelection(null)
    }

    fun deleteSelection(targetPath: String) {
        if (_isBusy.value) return
        val p = _project.value ?: return
        _isBusy.value = true
        val bmp = loadBitmap(targetPath)
        viewModelScope.launch {
            val mask = SelectionEngine.toMask(p.selection)
            withContext(Dispatchers.Default) {
                for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
                    if (mask.get(x, y)) bmp.setPixel(x, y, Color.TRANSPARENT)
                }
            }
            commitStroke(targetPath)
            _isBusy.value = false
        }
    }

    // ---------- Background removal (Blueprint #19) ----------

    /** PREVIEW ONLY: computes the chroma-key result and stages it in [bgRemovalPreview] without
     *  touching the real layer bitmap. Safe to call repeatedly (e.g. on every slider tick). */
    fun previewChromaKey(targetPath: String, params: BackgroundRemoverEngine.ChromaKeyParams) {
        val bmp = loadBitmap(targetPath)
        bgPreviewTargetPath = targetPath
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { BackgroundRemoverEngine.chromaKey(bmp, params) }
            if (bgPreviewTargetPath == targetPath) _bgRemovalPreview.value = result
        }
    }

    /** PREVIEW ONLY: computes the magic-wand transparency result and stages it in
     *  [bgRemovalPreview] without touching the real layer bitmap. */
    fun previewMagicWand(targetPath: String, x: Int, y: Int, tolerance: Int, mode: MagicWandMode) {
        val bmp = loadBitmap(targetPath)
        bgPreviewTargetPath = targetPath
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                val mask = BackgroundRemoverEngine.magicWand(bmp, x, y, tolerance, mode)
                BackgroundRemoverEngine.applyTransparency(bmp, mask)
            }
            if (bgPreviewTargetPath == targetPath) _bgRemovalPreview.value = result
        }
    }

    /** Commits whatever is currently staged in [bgRemovalPreview] onto the real layer bitmap
     *  (undo-recorded via commitStroke), then clears the preview. No-op if there is no preview. */
    fun applyBgRemovalPreview() {
        val path = bgPreviewTargetPath ?: return
        val result = _bgRemovalPreview.value ?: return
        bitmapCache[path] = result
        commitStroke(path)
        _bgRemovalPreview.value = null
        bgPreviewTargetPath = null
    }

    /** Discards the staged preview; the real layer bitmap is untouched. */
    fun cancelBgRemovalPreview() {
        _bgRemovalPreview.value = null
        bgPreviewTargetPath = null
    }

    /** Legacy direct-apply path (no preview step) - kept for callers that don't need preview. */
    fun applyChromaKey(targetPath: String, params: BackgroundRemoverEngine.ChromaKeyParams) {
        if (_isBusy.value) return
        _isBusy.value = true
        val bmp = loadBitmap(targetPath)
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { BackgroundRemoverEngine.chromaKey(bmp, params) }
            bitmapCache[targetPath] = result
            commitStroke(targetPath)
            _isBusy.value = false
        }
    }

    /** Legacy direct-apply path (no preview step) - kept for callers that don't need preview. */
    fun applyMagicWandTransparency(targetPath: String, x: Int, y: Int, tolerance: Int, mode: MagicWandMode) {
        if (_isBusy.value) return
        _isBusy.value = true
        val bmp = loadBitmap(targetPath)
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                val mask = BackgroundRemoverEngine.magicWand(bmp, x, y, tolerance, mode)
                BackgroundRemoverEngine.applyTransparency(bmp, mask)
            }
            bitmapCache[targetPath] = result
            commitStroke(targetPath)
            _isBusy.value = false
        }
    }

    // ---------- Sprite detection & Frame Bank (Blueprint #20-22) ----------

    /**
     * Detects sprites AND adds them to the frame bank in one background pass.
     * Previously `detectSprites()` ran the flood-fill detector synchronously on the calling
     * (UI) thread and returned its result directly - the single most expensive call in the
     * app and the prime suspect for the 5s+ main-thread stall behind the ANR in crash.txt.
     */
    fun detectAndAddSprites(sourcePath: String, params: SpriteDetectionParams = SpriteDetectionParams(), onDone: () -> Unit = {}) {
        if (_isBusy.value) return
        _isBusy.value = true
        val srcBmp = loadBitmap(sourcePath)
        viewModelScope.launch {
            try {
                val current = _project.value ?: return@launch
                val copy = current.deepCopy()
                withContext(Dispatchers.Default) {
                    val detected = SpriteDetectionEngine.detect(srcBmp, params)
                    detected.forEachIndexed { i, d ->
                        val fs = d.toFrameSource(1, srcBmp.width, srcBmp.height)
                        val cropped = Bitmap.createBitmap(srcBmp, fs.x, fs.y, fs.width, fs.height)
                        val path = storage.saveBitmap(copy.id, storage.newAssetName("frame"), cropped)
                        bitmapCache[path] = cropped
                        var frame = FrameEngine.createFrame("Frame ${copy.frames.size + i + 1}", path, fs, fs.width, fs.height)
                        frame = FrameEngine.computeGeometry(frame, cropped, copy.characterReference)
                        copy.frames.add(frame)
                    }
                }
                undoManager.record(current)
                refreshUndoRedoState()
                _project.value = copy
                if (_activeFrameId.value == null) _activeFrameId.value = copy.frames.firstOrNull()?.id
            } catch (oom: OutOfMemoryError) {
                _errorMessage.value = "Out of memory while detecting sprites. Try a smaller source image."
            } finally {
                _isBusy.value = false
                autoRepackAtlas()
                onDone()
            }
        }
    }

    fun renameFrame(frameId: String, name: String) = mutateProject { p -> FrameEngine.rename(p.frames, frameId, name) }
    fun deleteFrame(frameId: String) {
        mutateProject { p ->
            FrameEngine.delete(p.frames, frameId)
            p.animations.forEach { it.frameIds.remove(frameId) }
            if (_activeFrameId.value == frameId) _activeFrameId.value = p.frames.firstOrNull()?.id
        }
        autoRepackAtlas()
    }
    fun duplicateFrame(frameId: String) {
        mutateProject { p ->
            val frame = p.frames.find { it.id == frameId } ?: return@mutateProject
            val newPath = storage.saveBitmap(p.id, storage.newAssetName("frame"), PixelEngine.mutableCopy(loadBitmap(frame.bitmapPath)))
            p.frames.add(FrameEngine.duplicate(frame, newPath))
        }
        autoRepackAtlas()
    }
    fun reorderFrames(from: Int, to: Int) = mutateProject { p -> FrameEngine.reorder(p.frames, from, to) }
    fun setActiveFrame(id: String) { _activeFrameId.value = id }

    /**
     * PER-CARD CANVAS RESIZE (Frame Bank): resizes just this one frame's bitmap, independent of
     * the project's global canvas size and every other frame - see [FrameEngine.resizeFrameCanvas].
     */
    fun resizeFrameCanvas(frameId: String, width: Int, height: Int, anchorX: Float = 0.5f, anchorY: Float = 0.5f) {
        val path = _project.value?.frames?.find { it.id == frameId }?.bitmapPath ?: return
        val w = width.coerceIn(CanvasPresets.MIN_DIMENSION, CanvasPresets.MAX_DIMENSION)
        val h = height.coerceIn(CanvasPresets.MIN_DIMENSION, CanvasPresets.MAX_DIMENSION)
        mutateProjectAsync(listOf(path), busy = false, onComplete = { autoRepackAtlas() }) { p ->
            val i = p.frames.indexOfFirst { it.id == frameId }
            if (i < 0) return@mutateProjectAsync
            val f = p.frames[i]
            val bmp = loadBitmap(f.bitmapPath)
            val (updated, resized) = FrameEngine.resizeFrameCanvas(f, bmp, w, h, anchorX, anchorY, p.characterReference)
            bitmapCache[f.bitmapPath] = resized
            persistBitmap(f.bitmapPath)
            p.frames[i] = updated
        }
    }

    fun recalculateFrameGeometry(frameId: String) = mutateProjectAsync(busy = false) { p ->
        val idx = p.frames.indexOfFirst { it.id == frameId }
        if (idx < 0) return@mutateProjectAsync
        val frame = p.frames[idx]
        val bmp = loadBitmap(frame.bitmapPath)
        p.frames[idx] = FrameEngine.computeGeometry(frame, bmp, p.characterReference)
    }

    fun setCharacterReference(ref: ReferenceBoundary) = mutateProjectAsync { p ->
        p.characterReference = ref
        p.frames.forEachIndexed { i, f ->
            p.frames[i] = FrameEngine.computeGeometry(f, loadBitmap(f.bitmapPath), ref)
        }
    }

    /** Manual reference for one selected frame; does not move that frame into the main layer. */
    fun setFrameReference(frameId: String, ref: ReferenceBoundary) = mutateProject { p ->
        val i = p.frames.indexOfFirst { it.id == frameId }
        if (i < 0) return@mutateProject
        val f = p.frames[i]
        p.frames[i] = FrameEngine.computeGeometry(f.copy(manualReference = ref), loadBitmap(f.bitmapPath), p.characterReference)
    }

    fun clearFrameReference(frameId: String) = mutateProject { p ->
        val i = p.frames.indexOfFirst { it.id == frameId }
        if (i < 0) return@mutateProject
        val f = p.frames[i]
        p.frames[i] = FrameEngine.computeGeometry(f.copy(manualReference = null), loadBitmap(f.bitmapPath), p.characterReference)
    }

    fun scale2xFrame(frameId: String) {
        val path = _project.value?.frames?.find { it.id == frameId }?.bitmapPath
        mutateProjectAsync(listOfNotNull(path)) { p ->
            val i=p.frames.indexOfFirst { it.id==frameId }; if(i<0) return@mutateProjectAsync
            val f=p.frames[i]
            val scaled=Scale2xEngine.scale2x(loadBitmap(f.bitmapPath))
            bitmapCache[f.bitmapPath]=scaled; persistBitmap(f.bitmapPath)
            p.frames[i]=f.copy(sourceWidth=scaled.width, sourceHeight=scaled.height)
        }
    }

    fun setFrameTrimMode(frameId: String, mode: TrimMode) = mutateProject { p ->
        p.frames.find { it.id == frameId }?.trimMode = mode
    }

    fun setFrameRotated(frameId: String, rotated: Boolean) = mutateProject { p ->
        p.frames.find { it.id == frameId }?.rotated = rotated
    }

    // ---------- Frame Editor: Offset Auto/Manual override (Blueprint #22, SESI 5) ----------

    /** Switches a frame's offset back to AUTO (follows actualBounds again) or freezes it at its
     *  current value under MANUAL so future auto-recalculation leaves it alone. */
    fun setFrameOffsetMode(frameId: String, mode: OffsetMode) = mutateProject { p ->
        val f = p.frames.find { it.id == frameId } ?: return@mutateProject
        f.offsetMode = mode
        if (mode == OffsetMode.AUTO) {
            f.actualBounds?.let { f.offset = GeometryEngine.calculateOffset(it, f.sourceWidth, f.sourceHeight) }
        }
    }

    /** Explicit manual X/Y override from the Frame Inspector - always switches the frame into
     *  MANUAL mode, since typing a value is the user taking over from auto detection. */
    fun setFrameOffsetManual(frameId: String, x: Int, y: Int) = mutateProject { p ->
        val f = p.frames.find { it.id == frameId } ?: return@mutateProject
        f.offsetMode = OffsetMode.MANUAL
        f.offset = Offset(x, y)
    }

    // ---------- Frame Editor: Manual Trim (Blueprint #22 "Trim & Rotation", SESI 5) ----------

    /** Crops the frame's bitmap down to its actualBounds on disk (destructive, but goes through
     *  the same undo/redo history as any other pixel mutation via mutateProjectAsync). */
    fun applyFrameTrim(frameId: String) {
        val path = _project.value?.frames?.find { it.id == frameId }?.bitmapPath ?: return
        mutateProjectAsync(listOf(path), busy = false) { p ->
            val i = p.frames.indexOfFirst { it.id == frameId }
            if (i < 0) return@mutateProjectAsync
            val f = p.frames[i]
            val bmp = loadBitmap(f.bitmapPath)
            val (updated, trimmed) = FrameEngine.applyTrim(f, bmp, p.characterReference)
            if (trimmed !== bmp) {
                bitmapCache[f.bitmapPath] = trimmed
                persistBitmap(f.bitmapPath)
            }
            p.frames[i] = updated
        }
    }

    // ---------- Animation (Blueprint #28-32) ----------

    fun createAnimation(name: String, fps: Int = 10, loop: Boolean = true) = mutateProject { p ->
        val anim = AnimationEngine.createAnimation(name, fps, loop)
        p.animations.add(anim)
        _activeAnimationId.value = anim.id
    }

    /**
     * RESET CAPTURED FRAMES (SESI 9): clears out every frame currently captured into [animId]'s
     * sequence, so re-running Capture/Draw-Next-Frame after tweaking the source layer starts
     * from a clean slate instead of piling up duplicate frames alongside the old ones. A captured
     * frame that's ALSO used by some other animation is left alone in the Frame Bank (only its
     * membership in THIS animation's sequence is removed) - only frames that belong exclusively
     * to this animation are deleted outright, since those exist solely as recordings for it.
     */
    fun resetCapturedFrames(animId: String) {
        mutateProject { p ->
            val anim = p.animations.find { it.id == animId } ?: return@mutateProject
            val frameIds = anim.frameIds.toList()
            anim.frameIds.clear()
            for (fid in frameIds) {
                val usedElsewhere = p.animations.any { it.id != animId && it.frameIds.contains(fid) }
                if (!usedElsewhere) FrameEngine.delete(p.frames, fid)
            }
            if (_activeFrameId.value in frameIds) _activeFrameId.value = p.frames.firstOrNull()?.id
        }
        autoRepackAtlas()
    }

    fun deleteAnimation(id: String) = mutateProject { p ->
        p.animations.removeAll { it.id == id }
        if (_activeAnimationId.value == id) _activeAnimationId.value = p.animations.firstOrNull()?.id
    }

    fun renameAnimation(id: String, name: String) = mutateProject { p ->
        p.animations.find { it.id == id }?.name = name
    }

    fun addFramesToAnimation(animId: String, frameIds: List<String>) = mutateProject { p ->
        val anim = p.animations.find { it.id == animId } ?: return@mutateProject
        frameIds.forEach { AnimationEngine.addFrame(anim, it) }
    }

    fun captureFrameIntoAnimation(animId: String, sourcePath: String, frameSource: FrameSource) = mutateProject { p ->
        val srcBmp = loadBitmap(sourcePath)
        val cropped = Bitmap.createBitmap(srcBmp, frameSource.x, frameSource.y, frameSource.width, frameSource.height)
        val path = storage.saveBitmap(p.id, storage.newAssetName("frame"), cropped)
        bitmapCache[path] = cropped
        var frame = FrameEngine.createFrame("Frame ${p.frames.size + 1}", path, frameSource, frameSource.width, frameSource.height)
        frame = FrameEngine.computeGeometry(frame, cropped, p.characterReference)
        p.frames.add(frame)
        p.animations.find { it.id == animId }?.let { AnimationEngine.captureFrame(it, frame.id) }
    }.also { autoRepackAtlas() }

    fun reorderAnimationFrames(animId: String, from: Int, to: Int) = mutateProject { p ->
        p.animations.find { it.id == animId }?.let { AnimationEngine.reorderFrames(it, from, to) }
    }

    fun removeFrameFromAnimation(animId: String, frameId: String) = mutateProject { p ->
        p.animations.find { it.id == animId }?.let { AnimationEngine.removeFrame(it, frameId) }
    }

    fun setAnimationFps(animId: String, fps: Int) = mutateProject { p ->
        p.animations.find { it.id == animId }?.let { AnimationEngine.setFps(it, fps) }
    }

    fun setAnimationLoop(animId: String, loop: Boolean) = mutateProject { p ->
        p.animations.find { it.id == animId }?.loop = loop
    }

    fun setAnimationReference(animId: String, ref: ReferenceBoundary?) = mutateProject { p ->
        p.animations.find { it.id == animId }?.let { AnimationEngine.setReference(it, ref) }
    }

    fun setActiveAnimation(id: String) { _activeAnimationId.value = id }

    // ---------- Onion Skin (layered frame drawing) ----------

    /** Toggles the onion-skin ghost layer on/off. Off = the ghost is never drawn (auto-hide),
     *  regardless of what frame/animation is active. */
    fun toggleOnionSkin(enabled: Boolean) = mutateProject { p -> p.onionSkinEnabled = enabled }

    fun setOnionSkinOpacity(opacity: Float) = mutateProject { p -> p.onionSkinOpacity = opacity.coerceIn(0.05f, 0.9f) }

    /**
     * Resolves the "previous frame" ghost bitmap for onion skin: looks for [frameId] inside
     * [animId] (falling back to searching every animation if [animId] doesn't contain it, so a
     * frame opened straight from the Frame Bank still gets a sensible ghost if it happens to sit
     * in some sequence), and returns the frame right before it in that sequence's order. Returns
     * null when there is no earlier frame (start of the sequence) or onion skin doesn't apply -
     * callers should treat null as "draw nothing".
     */
    fun onionSkinFrameId(animId: String?, frameId: String?): String? {
        if (frameId == null) return null
        val p = _project.value ?: return null
        val anim = p.animations.find { it.id == animId && it.frameIds.contains(frameId) }
            ?: p.animations.find { it.frameIds.contains(frameId) }
            ?: return null
        val idx = anim.frameIds.indexOf(frameId)
        if (idx <= 0) return null
        return anim.frameIds[idx - 1]
    }

    /**
     * DRAW NEXT FRAME (layered animation flow): creates a new blank Frame Bank entry sized like
     * the frame currently at the end of [animId]'s sequence (or the project canvas if the
     * animation is still empty), appends it to that sequence right after the current last frame,
     * makes it the active frame, and opens the isolated Frame Editor (Blueprint #31) for it -
     * ready to draw with onion skin showing the previous frame underneath.
     */
    fun addNextAnimationFrame(animId: String) = mutateProject { p ->
        val anim = p.animations.find { it.id == animId } ?: return@mutateProject
        val prevFrame = anim.frameIds.lastOrNull()?.let { fid -> p.frames.find { it.id == fid } }
        val w = prevFrame?.sourceWidth?.takeIf { it > 0 } ?: p.canvas.width
        val h = prevFrame?.sourceHeight?.takeIf { it > 0 } ?: p.canvas.height
        val blank = PixelEngine.createBitmap(w, h, transparent = true, backgroundArgb = android.graphics.Color.WHITE)
        val path = storage.saveBitmap(p.id, storage.newAssetName("frame"), blank)
        bitmapCache[path] = blank
        val frameSource = FrameSource(0, 0, w, h)
        var frame = FrameEngine.createFrame("Frame ${p.frames.size + 1}", path, frameSource, w, h)
        frame = FrameEngine.computeGeometry(frame, blank, p.characterReference)
        p.frames.add(frame)
        AnimationEngine.addFrame(anim, frame.id)
        _activeFrameId.value = frame.id
        p.activeFrameEdit = ActiveFrameEdit(frame.id)
        _mode.value = WorkspaceMode.FRAME
        if (!p.onionSkinEnabled) p.onionSkinEnabled = true
    }.also { autoRepackAtlas() }

    /**
     * TEXTURE ATLAS auto-repack: after any Frame Bank change (add/duplicate/delete/resize), the
     * atlas is silently re-packed with its current settings so a stale, out-of-date atlas is
     * never sitting around waiting for a manual "Pack Atlas" tap - matches the request that any
     * sprite added to the bank is immediately reflected in the packed atlas. Best-effort: skips
     * quietly while another heavy op is already busy or before anything has ever been packed
     * (an empty/never-packed atlas has no stale state to refresh, and forcing a first pack here
     * would surprise a project that never asked for one).
     */
    private fun autoRepackAtlas() {
        val p = _project.value ?: return
        if (_isBusy.value) return
        if (p.frames.isEmpty()) { _atlasPreview.value = null; return }
        packAtlas(p.atlas.padding, 1, p.atlas.powerOfTwo, p.atlas.alphaTrim)
    }

    // ---------- Canvas Normalization (Blueprint #33) ----------

    fun normalizeAllFrames(targetW: Int, targetH: Int, padding: Int, anchor: NormalizationAnchor, mode: NormalizationMode) {
        val affectedPaths = _project.value?.frames?.map { it.bitmapPath } ?: emptyList()
        mutateProjectAsync(affectedPaths) { p ->
            p.frames.forEachIndexed { i, frame ->
                val bmp = loadBitmap(frame.bitmapPath)
                val normalized = NormalizationEngine.normalize(bmp, targetW, targetH, padding, anchor)
                bitmapCache[frame.bitmapPath] = normalized
                persistBitmap(frame.bitmapPath)
                p.frames[i] = frame.copy(sourceWidth = targetW, sourceHeight = targetH)
            }
        }
    }

    // ---------- Atlas Packing (Blueprint #34-35) ----------

    private val _atlasPreview = MutableStateFlow<Bitmap?>(null)
    val atlasPreview: StateFlow<Bitmap?> = _atlasPreview.asStateFlow()

    fun packAtlas(padding: Int = 1, spacing: Int = 1, powerOfTwo: Boolean = true, alphaTrim: Boolean = false) {
        if (_isBusy.value) return
        val current = _project.value ?: return
        _isBusy.value = true
        viewModelScope.launch {
            try {
                val copy = current.deepCopy()
                val rendered = withContext(Dispatchers.Default) {
                    val inputs = copy.frames.map { PackInput(it.id, loadBitmap(it.bitmapPath)) }
                    copy.atlas = AtlasPackerEngine.pack(inputs, padding, spacing, powerOfTwo, alphaTrim)
                    if (copy.atlas.blocks.isEmpty()) null
                    else AtlasPackerEngine.renderAtlasBitmap(copy.atlas, copy.frames.associate { it.id to loadBitmap(it.bitmapPath) })
                }
                undoManager.record(current)
                refreshUndoRedoState()
                _project.value = copy
                _atlasPreview.value = rendered
            } catch (oom: OutOfMemoryError) {
                _errorMessage.value = "Out of memory packing the atlas. Try fewer or smaller frames."
            } finally {
                _isBusy.value = false
            }
        }
    }

    /** Recomputes the cached atlas preview bitmap in the background (e.g. after reopening a project). */
    fun refreshAtlasPreview() {
        val p = _project.value ?: return
        if (p.atlas.blocks.isEmpty()) { _atlasPreview.value = null; return }
        viewModelScope.launch {
            _atlasPreview.value = withContext(Dispatchers.Default) {
                val byId = p.frames.associate { it.id to loadBitmap(it.bitmapPath) }
                AtlasPackerEngine.renderAtlasBitmap(p.atlas, byId)
            }
        }
    }

    // ---------- Export (Blueprint #36) ----------

    fun setExportSettings(settings: ExportSettings) = mutateProject { p -> p.exportSettings = settings }

    /** Runs the full export (atlas render + sprite sheet + file writes) off the main thread. */
    fun exportProjectAsync(onResult: (File?) -> Unit) {
        if (_isBusy.value) return
        val p = _project.value ?: return onResult(null)
        _isBusy.value = true
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    // Load each frame independently - one corrupt/missing bitmapPath (stale
                    // cache entry, moved file, etc.) used to throw out of the shared
                    // `associate { ... }` call and abort the ENTIRE export with nothing
                    // written. Skipping just the broken frame keeps the rest of export/atlas/
                    // sprite-sheet usable, and surfaces a warning instead of a hard failure.
                    val byId = mutableMapOf<String, Bitmap>()
                    val failedFrames = mutableListOf<String>()
                    p.frames.forEach { f ->
                        runCatching { loadBitmap(f.bitmapPath) }
                            .onSuccess { byId[f.id] = it }
                            .onFailure { failedFrames.add(f.name) }
                    }
                    val atlasBmp = if (p.atlas.blocks.isEmpty()) null
                        else runCatching { AtlasPackerEngine.renderAtlasBitmap(p.atlas, byId) }.getOrNull()
                    val sheet = buildSpriteSheetPreview(p, byId)
                    val zip = exportManager.exportAll(p, atlasBmp, sheet)
                    if (failedFrames.isNotEmpty()) {
                        _errorMessage.value = "Exported, but skipped ${failedFrames.size} frame(s) with missing image data."
                    }
                    zip
                }
            } catch (e: Exception) {
                _errorMessage.value = "Export failed: ${e.message ?: "unknown error"}"
                null
            } finally {
                _isBusy.value = false
            }
            onResult(result)
        }
    }

    private fun buildSpriteSheetPreview(p: ProjectModel, byId: Map<String, Bitmap>): Bitmap? {
        val bitmaps = p.frames.mapNotNull { byId[it.id] }
        if (bitmaps.isEmpty()) return null
        val totalW = bitmaps.sumOf { it.width }
        val maxH = bitmaps.maxOf { it.height }
        val out = Bitmap.createBitmap(totalW.coerceAtLeast(1), maxH.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        var x = 0
        bitmaps.forEach { b -> canvas.drawBitmap(b, x.toFloat(), 0f, null); x += b.width }
        return out
    }

    fun backupProjectAsync(onResult: (File?) -> Unit) {
        if (_isBusy.value) return
        val p = _project.value ?: return onResult(null)
        _isBusy.value = true
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { exportManager.backupProject(p) }
            } catch (e: Exception) {
                _errorMessage.value = "Backup failed: ${e.message ?: "unknown error"}"
                null
            } finally {
                _isBusy.value = false
            }
            onResult(result)
        }
    }

    fun restoreProjectFromFile(file: File) {
        // Note: openProject() manages its own _isBusy lifecycle, so this stage must release
        // the flag before calling it (otherwise openProject's own busy-guard would just no-op).
        if (_isBusy.value) return
        _isBusy.value = true
        viewModelScope.launch {
            val restoredId = try {
                withContext(Dispatchers.IO) {
                    val restored = exportManager.restoreProject(file)
                    storage.saveProject(restored)
                    restored.id
                }
            } catch (e: Exception) {
                _errorMessage.value = "Could not restore backup: ${e.message ?: "unknown error"}"
                null
            } finally {
                _isBusy.value = false
            }
            if (restoredId != null) {
                refreshRecentProjects()
                openProject(restoredId)
            }
        }
    }
}
