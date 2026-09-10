package com.pixelforge.core

import kotlinx.serialization.Serializable
import java.util.UUID

enum class TrimMode { NO_TRIM, REFERENCE, MANUAL }

/**
 * How a [FrameModel.offset] value is produced (SESI 5 Frame Editor). AUTO means
 * FrameEngine.computeGeometry keeps recalculating it from actualBounds on every detection /
 * reference change, same as before this mode existed. MANUAL freezes it - computeGeometry must
 * skip overwriting the offset while this is set, only the user's explicit setFrameOffsetManual
 * call (or switching back to AUTO) may change it.
 */
@Serializable
enum class OffsetMode { AUTO, MANUAL }

@Serializable
data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun right() = x + width
    fun bottom() = y + height
    fun overlaps(o: Rect): Boolean =
        x < o.right() && o.x < right() && y < o.bottom() && o.y < bottom()
    fun touches(o: Rect): Boolean {
        val xTouch = (right() == o.x || o.right() == x) &&
            (y < o.bottom() && o.y < bottom())
        val yTouch = (bottom() == o.y || o.bottom() == y) &&
            (x < o.right() && o.x < right())
        return xTouch || yTouch
    }
    fun contains(px: Int, py: Int): Boolean = px in x until right() && py in y until bottom()
    fun union(o: Rect): Rect {
        val nx = minOf(x, o.x); val ny = minOf(y, o.y)
        val nr = maxOf(right(), o.right()); val nb = maxOf(bottom(), o.bottom())
        return Rect(nx, ny, nr - nx, nb - ny)
    }
}

@Serializable
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

@Serializable
data class Offset(val x: Int, val y: Int)

@Serializable
data class Excess(val left: Int, val right: Int, val bottom: Int)

@Serializable
data class ReferenceBoundary(val left: Int, val right: Int, val bottom: Int)

@Serializable
enum class SelectOpType { ADD, SUBTRACT, TOGGLE, REPLACE }

@Serializable
data class SelectOperation(val type: SelectOpType, val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Explicit selection combine mode (Blueprint #15/#82). Replaces the old implicit
 * "start inside canvas = REMOVE, start outside = ADD" rule with a mode the user picks.
 * Not wired into SelectionEngine yet (that's Phase 2) - declared now so ProjectModel/
 * ProjectViewModel have a stable field to read/write from the start.
 */
@Serializable
enum class SelectionMode { REPLACE, ADD, SUBTRACT, TOGGLE }

/**
 * Editor-only viewport state (Blueprint #4 EditorViewport). Pan must never move actual
 * pixel data (Blueprint #11) - it only changes how the canvas is displayed. Persisted so a
 * reopened project restores the last framing, but intentionally has no effect on any other
 * field: dropping/resetting it can never corrupt project data.
 */
@Serializable
data class EditorViewport(
    val panX: Float = 0f,
    val panY: Float = 0f,
    val zoom: Int = 8
)



@kotlinx.serialization.Serializable
enum class GeometryType { CIRCLE, RECTANGLE, OVAL, PARALLELOGRAM, LINE }

@kotlinx.serialization.Serializable
data class GeometrySpec(
    val type: GeometryType,
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val thickness: Int = 1
)

@Serializable
data class SelectionState(
    val boundsX: Int = 0,
    val boundsY: Int = 0,
    val boundsWidth: Int = 0,
    val boundsHeight: Int = 0,
    val maskRLE: List<Int> = emptyList(),
    val operations: List<SelectOperation> = emptyList()
)

/**
 * A selection that has been lifted off a layer (Cut/Copy/Move - Blueprint #17/#18) and is not
 * yet burned back in. Holds its own pixel data (bitmapPath, a temp PNG under the project's
 * assets dir) plus the mask/placement needed to composite it back on Commit, or to restore the
 * original layer bytes on Cancel. Data-only for now (Phase 2 wires the actual engine logic);
 * declared here so ProjectModel has a single, stable place to carry this instead of any screen
 * inventing its own local copy.
 */
@Serializable
data class FloatingSelection(
    val sourceLayerId: String,
    val originX: Int,
    val originY: Int,
    val width: Int,
    val height: Int,
    val bitmapPath: String,
    val maskRLE: List<Int> = emptyList(),
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val rotationDegrees: Int = 0,
    val flippedH: Boolean = false,
    val flippedV: Boolean = false,
    val scale: Float = 1f
)

/**
 * A geometry shape (Circle/Rectangle/Oval/Parallelogram/Line) that has been drawn but not yet
 * rasterized into the layer (Blueprint #19/#20/#83). Mirrors [FloatingSelection]'s role: the
 * shape is fully editable (move/transform/color) until Apply, and discardable until then via
 * Cancel. Data-only for now; Phase 3 wires this into GeometryEngine/PixelEngine.
 */
@Serializable
data class FloatingGeometry(
    val spec: GeometrySpec,
    val targetLayerId: String,
    val colorArgb: Int,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val rotationDegrees: Int = 0,
    val flippedH: Boolean = false,
    val flippedV: Boolean = false,
    val scale: Float = 1f
)

/**
 * Marks that a specific Frame Bank entry is currently open in the isolated Frame Editor
 * (Blueprint #31: selecting Frame Bank #12 must NOT copy that sprite into the main layer -
 * it opens an edit context scoped to that one frame). Nullable/transient in spirit: absence
 * means "editing the main canvas layers", presence means "editing this one frame in place".
 */
@Serializable
data class ActiveFrameEdit(
    val frameId: String
)

@Serializable
data class LayerModel(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var bitmapPath: String,
    var visible: Boolean = true,
    var opacity: Float = 1f,
    var locked: Boolean = false
)

@Serializable
data class FrameSource(val x: Int, val y: Int, val width: Int, val height: Int)

@Serializable
data class FrameModel(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var bitmapPath: String,
    var source: FrameSource,
    var actualBounds: Bounds? = null,
    var sourceWidth: Int = 0,
    var sourceHeight: Int = 0,
    var offset: Offset = Offset(0, 0),
    /** AUTO (default) = offset always follows GeometryEngine.calculateOffset(actualBounds).
     *  MANUAL = the user overrode x/y directly; auto-recompute must leave it alone. */
    var offsetMode: OffsetMode = OffsetMode.AUTO,
    var excess: Excess = Excess(0, 0, 0),
    var manualReference: ReferenceBoundary? = null,
    var rotated: Boolean = false,
    var trimMode: TrimMode = TrimMode.NO_TRIM,
    var confidence: Float = 1f,
    var scale: Float = 1f
)

@Serializable
data class AnimationModel(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var frameIds: MutableList<String> = mutableListOf(),
    var fps: Int = 10,
    var loop: Boolean = true,
    var reference: ReferenceBoundary? = null
)

@Serializable
data class AtlasBlock(
    val frameId: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val rotated: Boolean,
    /** Original (pre-trim) bitmap size, for reconstructing full sprite bounds at runtime. */
    val sourceWidth: Int = width,
    val sourceHeight: Int = height,
    /** Where the trimmed/placed rect sits within the original bitmap (Blueprint #35 alpha trim).
     *  Zero when alpha trim is off - the placed rect IS the full original bitmap. */
    val trimOffsetX: Int = 0,
    val trimOffsetY: Int = 0
)

@Serializable
data class AtlasModel(
    var width: Int = 0,
    var height: Int = 0,
    var padding: Int = 1,
    var powerOfTwo: Boolean = true,
    /** Whether transparent borders were stripped from each sprite before packing. */
    var alphaTrim: Boolean = false,
    var blocks: MutableList<AtlasBlock> = mutableListOf(),
    var atlasBitmapPath: String? = null
)

@Serializable
data class CanvasSettings(
    var width: Int = 64,
    var height: Int = 64,
    var transparent: Boolean = true,
    var backgroundColorArgb: Int = -1
)

@Serializable
data class ExportSettings(
    var includeSprites: Boolean = true,
    var includeSpriteSheet: Boolean = true,
    var includeAtlas: Boolean = true,
    var includeJson: Boolean = true,
    var includeCsv: Boolean = true,
    var lastExportPath: String? = null
)

@Serializable
data class ProjectModel(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var canvas: CanvasSettings = CanvasSettings(),
    var layers: MutableList<LayerModel> = mutableListOf(),
    var sourceAssets: MutableList<String> = mutableListOf(),
    // Frame Bank (Blueprint #28): this list IS the frame bank - the single, central store of
    // sprite frames. Detection, Frame Editor, Animation, Normalization, Atlas and Export must
    // all read/write through here; nothing should keep a second, tool-local copy of a frame.
    var frames: MutableList<FrameModel> = mutableListOf(),
    var animations: MutableList<AnimationModel> = mutableListOf(),
    var characterReference: ReferenceBoundary? = null,
    var atlas: AtlasModel = AtlasModel(),
    var selection: SelectionState = SelectionState(),
    var exportSettings: ExportSettings = ExportSettings(),

    // ---- Editor state (Blueprint #4). All default to "nothing active" / neutral values so
    // existing project.json files on disk (written before these fields existed) still decode
    // correctly under Json { ignoreUnknownKeys = true } - old files simply get the defaults. ----

    /** Non-null while a Cut/Copy/Move selection is lifted and not yet Commit/Cancel'd. */
    var floatingSelection: FloatingSelection? = null,
    /** Non-null while a drawn shape is live and not yet Apply/Cancel'd. */
    var floatingGeometry: FloatingGeometry? = null,
    /** Non-null while a specific Frame Bank entry is open in the isolated Frame Editor. */
    var activeFrameEdit: ActiveFrameEdit? = null,
    /** Explicit Smart Select combine mode (Blueprint #15) - replaces inside/outside inference. */
    var selectionMode: SelectionMode = SelectionMode.REPLACE,
    /** Last canvas pan/zoom framing, restored on reopen. Display-only - see [EditorViewport]. */
    var editorViewport: EditorViewport = EditorViewport(),

    /** ONION SKIN (Frame/Animation editor): when true, the previous frame in the active
     *  animation's sequence is drawn faintly underneath the frame currently being drawn, as a
     *  positioning reference. When false, no ghost layer is ever drawn (auto-hide). */
    var onionSkinEnabled: Boolean = false,
    /** Opacity of the ghosted previous-frame layer, 0..1. Only meaningful while
     *  [onionSkinEnabled] is true. */
    var onionSkinOpacity: Float = 0.35f,

    var updatedAtMillis: Long = System.currentTimeMillis()
)

/** Convenience Frame Bank accessors, so callers don't reach into `frames` ad hoc. */
fun ProjectModel.frameById(id: String): FrameModel? = frames.find { it.id == id }
fun ProjectModel.frameBankIndexOf(id: String): Int = frames.indexOfFirst { it.id == id }

object CanvasPresets {
    val sizes = listOf(16, 32, 64, 128, 256, 512)
    /** Hard ceiling for any canvas/frame/layer dimension. ARGB_8888 at this size is ~64MB per
     *  bitmap - already generous for a pixel-art tool. Prevents OOM crashes from unbounded
     *  Canvas Settings / imported image sizes. */
    const val MAX_DIMENSION = 1024
    const val MIN_DIMENSION = 1
}

/**
 * Real deep copy of a [ProjectModel].
 *
 * `ProjectModel` is a data class, so the compiler-generated `.copy()` is a SHALLOW copy:
 * mutable list fields (layers, frames, animations, sourceAssets, atlas.blocks...) are copied
 * by reference, not by value. `original.copy().frames === original.frames` is the same
 * MutableList instance.
 *
 * That's harmless as long as every read/write happens on one thread, one at a time. It stops
 * being harmless the moment a background coroutine calls `copy.frames.add(...)` while the main
 * thread is composing/iterating `original.frames` (same list) for the UI - a structural
 * modification during iteration throws ConcurrentModificationException. That crash (after a
 * "stuck" beat while the background op runs) is exactly what moving heavy ViewModel work off
 * the main thread exposed. Always branch project state through this function instead of
 * `.copy()` when the two branches can be touched by different threads at overlapping times.
 */
fun ProjectModel.deepCopy(): ProjectModel = copy(
    canvas = canvas.copy(),
    layers = layers.map { it.copy() }.toMutableList(),
    sourceAssets = sourceAssets.toMutableList(),
    frames = frames.map { it.copy() }.toMutableList(),
    animations = animations.map { it.copy(frameIds = it.frameIds.toMutableList()) }.toMutableList(),
    atlas = atlas.copy(blocks = atlas.blocks.toMutableList()),
    selection = selection.copy(
        maskRLE = selection.maskRLE.toList(),
        operations = selection.operations.toList()
    ),
    exportSettings = exportSettings.copy(),
    // val-only data classes, but maskRLE is a List<Int> reference - copy it explicitly for the
    // same reason as selection.maskRLE above (cross-thread mutateProjectAsync branches).
    floatingSelection = floatingSelection?.copy(maskRLE = floatingSelection?.maskRLE.orEmpty().toList()),
    floatingGeometry = floatingGeometry?.copy(),
    activeFrameEdit = activeFrameEdit?.copy(),
    editorViewport = editorViewport.copy()
)
