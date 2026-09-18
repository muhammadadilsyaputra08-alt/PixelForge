package com.pixelforge.core

/**
 * GLOBAL UNDO / REDO (Blueprint #39).
 * History lives at Project level, not per-tool. Every mutating action
 * (pixel edit, smart select, background removal, frame move, geometry change...)
 * pushes one snapshot here so it can all be undone/redone in the order it happened.
 *
 * BUGFIX: [ProjectModel] only stores bitmap *paths* (LayerModel.bitmapPath,
 * FrameModel.bitmapPath) - never the actual pixel bytes. Tools like pencil/eraser/bucket,
 * layer rotate/flip, geometry, chroma key, magic wand, canvas resize, normalization and
 * scale2x all overwrite the PNG file at that SAME path in place (see
 * ProjectViewModel.persistBitmap). Snapshotting only the [ProjectModel] therefore captured
 * metadata that was IDENTICAL before and after a pixel edit (the path never changes) while
 * the one place the actual pixels lived - the file on disk - had already been overwritten.
 * The net effect: Undo/Redo toggled correctly (buttons enabled/disabled, layer lists, frame
 * lists...) but drawing strokes, transforms and every other bitmap-mutating tool were NOT
 * actually reversible - the canvas never visually changed on Undo.
 * Each history [Entry] now also carries the raw PNG bytes of every bitmap path the
 * corresponding step is about to overwrite, read from disk BEFORE the mutation runs, so
 * undo/redo can restore exact pixel content, not just list/metadata state.
 */
class UndoManager(maxHistory: Int = 60) {

    /** One history step: the ProjectModel snapshot plus the pre-step PNG bytes of every
     *  bitmap path that step's mutation is about to overwrite (empty map for purely
     *  structural changes, e.g. renaming a layer, that never touch pixel data). */
    data class Entry(val project: ProjectModel, val bitmaps: Map<String, ByteArray>)

    private val capacity = maxHistory
    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Call BEFORE mutating [current] (and before persisting any of [affectedBitmaps]'s
     * paths) to snapshot the pre-mutation state. [affectedBitmaps] should be the PNG bytes,
     * read from disk, of every bitmap path the upcoming mutation will overwrite in place.
     */
    fun record(current: ProjectModel, affectedBitmaps: Map<String, ByteArray> = emptyMap()) {
        undoStack.addLast(Entry(current.deepCopy(), affectedBitmaps))
        if (undoStack.size > capacity) undoStack.removeFirst()
        redoStack.clear()
    }

    /**
     * Pops the last undo entry. [readCurrentBytes] is invoked for each path that entry's
     * bitmaps cover, to capture the (about to be reverted) CURRENT bytes into a matching
     * redo entry - keeping Redo symmetric and lossless.
     */
    fun undo(current: ProjectModel, readCurrentBytes: (String) -> ByteArray?): Entry? {
        val prev = undoStack.removeLastOrNull() ?: return null
        val currentBitmaps = prev.bitmaps.keys.mapNotNull { path -> readCurrentBytes(path)?.let { path to it } }.toMap()
        redoStack.addLast(Entry(current.deepCopy(), currentBitmaps))
        return prev
    }

    fun redo(current: ProjectModel, readCurrentBytes: (String) -> ByteArray?): Entry? {
        val next = redoStack.removeLastOrNull() ?: return null
        val currentBitmaps = next.bitmaps.keys.mapNotNull { path -> readCurrentBytes(path)?.let { path to it } }.toMap()
        undoStack.addLast(Entry(current.deepCopy(), currentBitmaps))
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
