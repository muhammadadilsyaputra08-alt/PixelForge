package com.pixelforge.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max
import com.pixelforge.core.Bounds
import com.pixelforge.core.Excess
import com.pixelforge.core.Offset
import com.pixelforge.core.ReferenceBoundary

/**
 * GEOMETRY ENGINE (Blueprint #23-27).
 * actualBounds / sourceSize / offset / excess / rotated / trimMode / character reference.
 */
object GeometryEngine {

    /** ACTUAL BOUNDS (Blueprint #24): tight box around non-transparent pixels. */
    fun calculateActualBounds(bmp: Bitmap): Bounds? {
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        var left = w; var top = h; var right = -1; var bottom = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (Color.alpha(pixels[y * w + x]) > 0) {
                if (x < left) left = x
                if (y < top) top = y
                if (x > right) right = x
                if (y > bottom) bottom = y
            }
        }
        if (right < 0) return null
        return Bounds(left, top, right + 1, bottom + 1)
    }

    /**
     * OFFSET / EXCESS (Blueprint #26): compares actual sprite bounds against the character
     * reference boundary to compute how far the sprite extends beyond the reference on each side.
     */
    fun calculateExcess(actualBounds: Bounds, sourceWidth: Int, sourceHeight: Int, reference: ReferenceBoundary): Excess {
        val left = (reference.left - actualBounds.left).coerceAtLeast(0)
        val right = (actualBounds.right - reference.right).coerceAtLeast(0)
        val bottom = (actualBounds.bottom - reference.bottom).coerceAtLeast(0)
        return Excess(left, right, bottom)
    }

    /** Offset used to keep the anchor stable across frames of differing actualBounds.
     *
     *  Previously this ignored the Character Reference entirely (`Offset(actualBounds.left,
     *  actualBounds.top)`) - so dragging the L/R/B reference handles updated the "Excess" stats
     *  shown in the Frame Inspector, but never actually changed how frames were aligned to each
     *  other. That's now fixed: when an [excess] is available (i.e. a reference exists for this
     *  frame), the offset is derived from it instead:
     *  - X = (excess.right - excess.left) / 2 - frames whose actual bounds overflow the
     *    reference further on one side than the other (e.g. a weapon swing extending right)
     *    get shifted back by half that imbalance, so the character's own body stays centered
     *    on the reference instead of the visual bounding box.
     *  - Y = excess.bottom directly (no averaging - there is only one bottom/foot line, not a
     *    left/right pair) so every frame's foot line lands on the same row.
     *  Falls back to the old actualBounds-anchored behavior when there's no reference yet. */
    fun calculateOffset(actualBounds: Bounds, sourceWidth: Int, sourceHeight: Int, excess: Excess? = null): Offset {
        if (excess != null) {
            return Offset((excess.right - excess.left) / 2, excess.bottom)
        }
        return Offset(actualBounds.left, actualBounds.top)
    }

    /**
     * CONFIDENCE LEVEL (Blueprint #22 Frame Editor Inspector): a heuristic 0f..1f score for how
     * much the auto-detected actualBounds/offset can be trusted, surfaced in the Frame Inspector
     * so the user knows when a manual override is worth doing instead of trusting auto detection.
     * Starts at 1f (fully confident) and is docked for two independent risk signals:
     *  - Touching the source edge on left/top/right/bottom: the sprite may be clipped by the
     *    crop rect rather than genuinely ending there, so each touching edge is a real risk.
     *  - Being a tiny sliver of the source (e.g. a stray anti-alias pixel or detection fragment
     *    left over after cropping): area ratio under 2% is treated as low-confidence noise.
     * A null actualBounds (fully transparent frame) has no confidence at all.
     */
    fun calculateConfidence(actualBounds: Bounds?, sourceWidth: Int, sourceHeight: Int): Float {
        if (actualBounds == null || sourceWidth <= 0 || sourceHeight <= 0) return 0f
        var score = 1f
        if (actualBounds.left <= 0) score -= 0.15f
        if (actualBounds.top <= 0) score -= 0.15f
        if (actualBounds.right >= sourceWidth) score -= 0.15f
        if (actualBounds.bottom >= sourceHeight) score -= 0.15f
        val areaRatio = (actualBounds.width.toFloat() * actualBounds.height.toFloat()) /
            (sourceWidth.toFloat() * sourceHeight.toFloat())
        if (areaRatio < 0.02f) score -= 0.3f
        return score.coerceIn(0f, 1f)
    }


    /** Draws pixel-art geometry with integer coordinates and no antialiasing. */
    fun drawGeometry(bmp: Bitmap, spec: com.pixelforge.core.GeometrySpec, color: Int): Bitmap {
        val out = PixelEngine.mutableCopy(bmp)
        val canvas = android.graphics.Canvas(out)
        val paint = Paint().apply {
            isAntiAlias = false
            isFilterBitmap = false
            style = Paint.Style.STROKE
            strokeWidth = spec.thickness.coerceAtLeast(1).toFloat()
            this.color = color
            strokeCap = Paint.Cap.SQUARE
            strokeJoin = Paint.Join.MITER
        }
        val l = min(spec.startX, spec.endX).toFloat()
        val r = max(spec.startX, spec.endX).toFloat()
        val t = min(spec.startY, spec.endY).toFloat()
        val b = max(spec.startY, spec.endY).toFloat()
        when (spec.type) {
            com.pixelforge.core.GeometryType.RECTANGLE ->
                canvas.drawRect(l, t, r, b, paint)
            com.pixelforge.core.GeometryType.OVAL ->
                canvas.drawOval(l, t, r, b, paint)
            com.pixelforge.core.GeometryType.CIRCLE -> {
                val size = min(abs(spec.endX-spec.startX), abs(spec.endY-spec.startY)).toFloat()
                val rr = if (spec.endX >= spec.startX) l + size else r - size
                val bb = if (spec.endY >= spec.startY) t + size else b - size
                canvas.drawOval(min(l, rr), min(t, bb), max(l, rr), max(t, bb), paint)
            }
            com.pixelforge.core.GeometryType.LINE ->
                canvas.drawLine(spec.startX.toFloat(), spec.startY.toFloat(), spec.endX.toFloat(), spec.endY.toFloat(), paint)
            com.pixelforge.core.GeometryType.PARALLELOGRAM -> {
                val w = r-l; val h = b-t
                val skew = (w * 0.22f).coerceAtMost(w/2f)
                val path = Path()
                path.moveTo(l+skew,t); path.lineTo(r,t); path.lineTo(r-skew,b); path.lineTo(l,b); path.close()
                canvas.drawPath(path, paint)
            }
        }
        return out
    }

    fun trimToActualBounds(bmp: Bitmap, bounds: Bounds): Bitmap =
        Bitmap.createBitmap(bmp, bounds.left, bounds.top, bounds.width, bounds.height)

    /** Starts a new floating (unrasterized) shape at a single point (Blueprint #19/#83). */
    fun beginFloating(type: com.pixelforge.core.GeometryType, targetLayerId: String, x: Int, y: Int, colorArgb: Int): com.pixelforge.core.FloatingGeometry =
        com.pixelforge.core.FloatingGeometry(
            spec = com.pixelforge.core.GeometrySpec(type, x, y, x, y, 1),
            targetLayerId = targetLayerId,
            colorArgb = colorArgb
        )

    /**
     * Resolves a [com.pixelforge.core.FloatingGeometry]'s draft [com.pixelforge.core.GeometrySpec]
     * (raw drag start/end) plus its move/flip/rotate/scale transform into the final absolute
     * spec to render or rasterize. Both the preview overlay and the real Apply must call this
     * SAME function so what's previewed is exactly what gets drawn - transforms are applied to
     * the start/end points around their shared bounding-box center, so they compose correctly
     * regardless of shape type (RECTANGLE/OVAL/CIRCLE/PARALLELOGRAM/LINE all read as two points).
     */
    fun resolveSpec(geometry: com.pixelforge.core.FloatingGeometry): com.pixelforge.core.GeometrySpec {
        val s = geometry.spec
        val cx = (s.startX + s.endX) / 2.0
        val cy = (s.startY + s.endY) / 2.0
        val rad = Math.toRadians(geometry.rotationDegrees.toDouble())
        val cos = Math.cos(rad); val sin = Math.sin(rad)

        fun transform(px: Int, py: Int): Pair<Int, Int> {
            var dx = px - cx
            var dy = py - cy
            if (geometry.flippedH) dx = -dx
            if (geometry.flippedV) dy = -dy
            val rdx = dx * cos - dy * sin
            val rdy = dx * sin + dy * cos
            val sdx = rdx * geometry.scale
            val sdy = rdy * geometry.scale
            val fx = cx + sdx + geometry.offsetX
            val fy = cy + sdy + geometry.offsetY
            return Math.round(fx).toInt() to Math.round(fy).toInt()
        }

        val (nsx, nsy) = transform(s.startX, s.startY)
        val (nex, ney) = transform(s.endX, s.endY)
        return s.copy(startX = nsx, startY = nsy, endX = nex, endY = ney)
    }

    /** Renders a [geometry] as a transparent floating overlay the same size as [bmp] - used for
     *  the live preview only. Never touches [bmp] itself; that only happens on Apply via
     *  [drawGeometry] with the same [resolveSpec] output (see [rasterizeFloating]). */
    fun previewFloating(bmp: Bitmap, geometry: com.pixelforge.core.FloatingGeometry): Bitmap {
        val overlay = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        return drawGeometry(overlay, resolveSpec(geometry), geometry.colorArgb)
    }

    /** Burns a [geometry] into [bmp] at its fully-resolved position/transform (Apply). */
    fun rasterizeFloating(bmp: Bitmap, geometry: com.pixelforge.core.FloatingGeometry): Bitmap =
        drawGeometry(bmp, resolveSpec(geometry), geometry.colorArgb)
}
