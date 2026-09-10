package com.pixelforge.engine

import android.graphics.Bitmap
import com.pixelforge.core.Bounds
import com.pixelforge.core.FrameModel
import com.pixelforge.core.FrameSource
import com.pixelforge.core.OffsetMode
import com.pixelforge.core.ReferenceBoundary
import com.pixelforge.core.TrimMode

/** FRAME ENGINE (Blueprint #21-22): Frame Bank management. */
object FrameEngine {

    fun createFrame(name: String, bitmapPath: String, source: FrameSource, sourceW: Int, sourceH: Int): FrameModel {
        val frame = FrameModel(
            name = name,
            bitmapPath = bitmapPath,
            source = source,
            sourceWidth = sourceW,
            sourceHeight = sourceH,
            trimMode = TrimMode.NO_TRIM
        )
        return frame
    }

    fun computeGeometry(frame: FrameModel, bitmap: Bitmap, reference: com.pixelforge.core.ReferenceBoundary?): FrameModel {
        val actual = GeometryEngine.calculateActualBounds(bitmap)
        val updated = frame.copy(actualBounds = actual)
        // Excess must be computed BEFORE offset now, since offset is derived from it (see
        // GeometryEngine.calculateOffset) - previously these two were independent and offset
        // never looked at the reference at all.
        var excess: com.pixelforge.core.Excess? = null
        if (actual != null) {
            val ref = frame.manualReference ?: reference
            if (ref != null) {
                excess = GeometryEngine.calculateExcess(actual, frame.sourceWidth, frame.sourceHeight, ref)
                updated.excess = excess
            }
        }
        // Manual offset override (SESI 5): once the user has taken over the offset, auto
        // recalculation (new detection pass, character reference change, etc.) must not stomp
        // on it - only AUTO mode keeps following actualBounds/reference like before.
        if (actual != null && frame.offsetMode == OffsetMode.AUTO) {
            updated.offset = GeometryEngine.calculateOffset(actual, frame.sourceWidth, frame.sourceHeight, excess)
        }
        updated.confidence = GeometryEngine.calculateConfidence(actual, frame.sourceWidth, frame.sourceHeight)
        return updated
    }

    /**
     * MANUAL TRIM (Blueprint #22 "Trim & Rotation"): actually crops the frame's bitmap down to
     * its actualBounds - trimMode/rotated were previously metadata-only fields nothing ever
     * applied to pixels. Returns the updated [FrameModel] (source size/actualBounds/offset/
     * excess/confidence all re-derived for the new, smaller bitmap) paired with the cropped
     * bitmap the caller must persist. A frame with no detectable content (fully transparent) or
     * whose actualBounds already covers the whole source is returned unchanged.
     */
    fun applyTrim(frame: FrameModel, bitmap: Bitmap, reference: ReferenceBoundary?): Pair<FrameModel, Bitmap> {
        val bounds = frame.actualBounds ?: GeometryEngine.calculateActualBounds(bitmap) ?: return frame to bitmap
        if (bounds.left == 0 && bounds.top == 0 && bounds.right == frame.sourceWidth && bounds.bottom == frame.sourceHeight) {
            return frame to bitmap // already tight - nothing to trim
        }
        val trimmed = GeometryEngine.trimToActualBounds(bitmap, bounds)
        val newBounds = Bounds(0, 0, trimmed.width, trimmed.height)
        var updated = frame.copy(
            source = frame.source.copy(width = trimmed.width, height = trimmed.height),
            sourceWidth = trimmed.width,
            sourceHeight = trimmed.height,
            actualBounds = newBounds
        )
        val ref = frame.manualReference ?: reference
        var excess: com.pixelforge.core.Excess? = null
        if (ref != null) {
            excess = GeometryEngine.calculateExcess(newBounds, trimmed.width, trimmed.height, ref)
            updated.excess = excess
        }
        if (updated.offsetMode == OffsetMode.AUTO) {
            updated.offset = GeometryEngine.calculateOffset(newBounds, trimmed.width, trimmed.height, excess)
        }
        updated.confidence = GeometryEngine.calculateConfidence(newBounds, trimmed.width, trimmed.height)
        return updated to trimmed
    }

    fun rename(frames: MutableList<FrameModel>, id: String, newName: String) {
        frames.find { it.id == id }?.name = newName
    }

    fun reorder(frames: MutableList<FrameModel>, fromIndex: Int, toIndex: Int) {
        if (fromIndex !in frames.indices || toIndex !in frames.indices) return
        val item = frames.removeAt(fromIndex)
        frames.add(toIndex, item)
    }

    fun duplicate(frame: FrameModel, newBitmapPath: String): FrameModel =
        frame.copy(id = java.util.UUID.randomUUID().toString(), name = frame.name + " copy", bitmapPath = newBitmapPath)

    fun delete(frames: MutableList<FrameModel>, id: String) {
        frames.removeAll { it.id == id }
    }

    /**
     * PER-FRAME CANVAS RESIZE: grows/shrinks one Frame Bank entry's own bitmap in place
     * (anchored like [com.pixelforge.engine.PixelEngine.resizeCanvasPreservingPixels]), without
     * touching the project's global CanvasSettings or any other frame. Returns the updated
     * [FrameModel] (source/sourceWidth/sourceHeight updated to the new size; geometry re-derived
     * from the resized bitmap) paired with the resized bitmap the caller must persist.
     */
    fun resizeFrameCanvas(
        frame: FrameModel,
        bitmap: Bitmap,
        newWidth: Int,
        newHeight: Int,
        anchorX: Float,
        anchorY: Float,
        reference: ReferenceBoundary?
    ): Pair<FrameModel, Bitmap> {
        val resized = com.pixelforge.engine.PixelEngine.resizeCanvasPreservingPixels(bitmap, newWidth, newHeight, anchorX, anchorY)
        var updated = frame.copy(
            source = frame.source.copy(width = resized.width, height = resized.height),
            sourceWidth = resized.width,
            sourceHeight = resized.height
        )
        updated = computeGeometry(updated, resized, reference)
        return updated to resized
    }
}
