package com.pixelforge.engine

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * PIXEL ENGINE (Blueprint #18).
 * Pixel-perfect: no anti-aliasing, no subpixels, alpha preserved, integer coordinates,
 * nearest-neighbor only.
 */
object PixelEngine {

    fun createBitmap(width: Int, height: Int, transparent: Boolean, backgroundArgb: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val fill = if (transparent) Color.TRANSPARENT else backgroundArgb
        val pixels = IntArray(width * height) { fill }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }

    fun mutableCopy(src: Bitmap): Bitmap = src.copy(Bitmap.Config.ARGB_8888, true)

    fun setPixel(bmp: Bitmap, x: Int, y: Int, colorArgb: Int) {
        if (x in 0 until bmp.width && y in 0 until bmp.height) bmp.setPixel(x, y, colorArgb)
    }

    fun getPixel(bmp: Bitmap, x: Int, y: Int): Int =
        if (x in 0 until bmp.width && y in 0 until bmp.height) bmp.getPixel(x, y) else Color.TRANSPARENT

    /** PENCIL tool: sets an NxN block of pixels (brush size) to a solid color. No blending. */
    fun pencil(bmp: Bitmap, x: Int, y: Int, colorArgb: Int, brushSize: Int = 1) {
        val half = brushSize / 2
        for (dy in -half until brushSize - half) {
            for (dx in -half until brushSize - half) {
                setPixel(bmp, x + dx, y + dy, colorArgb)
            }
        }
    }

    /** ERASER tool: sets pixels transparent. */
    fun eraser(bmp: Bitmap, x: Int, y: Int, brushSize: Int = 1) {
        pencil(bmp, x, y, Color.TRANSPARENT, brushSize)
    }

    /** BUCKET tool: flood fill using 4-connectivity, exact color match (no anti-alias blending). */
    fun bucketFill(bmp: Bitmap, x: Int, y: Int, newColorArgb: Int, tolerance: Int = 0) {
        if (x !in 0 until bmp.width || y !in 0 until bmp.height) return
        val target = bmp.getPixel(x, y)
        if (target == newColorArgb) return
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val visited = BooleanArray(w * h)
        val stack = ArrayDeque<Int>()
        stack.addLast(y * w + x)
        while (stack.isNotEmpty()) {
            val idx = stack.removeLast()
            if (visited[idx]) continue
            val px = idx % w; val py = idx / w
            if (!colorsMatch(pixels[idx], target, tolerance)) continue
            visited[idx] = true
            pixels[idx] = newColorArgb
            if (px > 0) stack.addLast(idx - 1)
            if (px < w - 1) stack.addLast(idx + 1)
            if (py > 0) stack.addLast(idx - w)
            if (py < h - 1) stack.addLast(idx + w)
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    /** EYEDROPPER tool. */
    fun eyedropper(bmp: Bitmap, x: Int, y: Int): Int = getPixel(bmp, x, y)

    fun colorsMatch(a: Int, b: Int, tolerance: Int): Boolean {
        if (tolerance <= 0) return a == b
        val da = kotlin.math.abs(Color.alpha(a) - Color.alpha(b))
        val dr = kotlin.math.abs(Color.red(a) - Color.red(b))
        val dg = kotlin.math.abs(Color.green(a) - Color.green(b))
        val db = kotlin.math.abs(Color.blue(a) - Color.blue(b))
        return max(max(da, dr), max(dg, db)) <= tolerance
    }

    /** Resize canvas while preserving existing pixel data (crop/pad, no interpolation). */
    fun resizeCanvasPreservingPixels(src: Bitmap, newWidth: Int, newHeight: Int, anchorX: Float = 0f, anchorY: Float = 0f): Bitmap {
        val dst = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        val offsetX = ((newWidth - src.width) * anchorX).toInt()
        val offsetY = ((newHeight - src.height) * anchorY).toInt()
        val srcPixels = IntArray(src.width * src.height)
        src.getPixels(srcPixels, 0, src.width, 0, 0, src.width, src.height)
        val dstPixels = IntArray(newWidth * newHeight) { Color.TRANSPARENT }
        for (sy in 0 until src.height) {
            val dy = sy + offsetY
            if (dy !in 0 until newHeight) continue
            for (sx in 0 until src.width) {
                val dx = sx + offsetX
                if (dx !in 0 until newWidth) continue
                dstPixels[dy * newWidth + dx] = srcPixels[sy * src.width + sx]
            }
        }
        dst.setPixels(dstPixels, 0, newWidth, 0, 0, newWidth, newHeight)
        return dst
    }

    /** Nearest-neighbor scale for zoom rendering - integer scale factor, no smoothing. */
    fun nearestNeighborScale(src: Bitmap, scale: Int): Bitmap {
        val s = max(1, scale)
        val w = src.width * s; val h = src.height * s
        val srcPixels = IntArray(src.width * src.height)
        src.getPixels(srcPixels, 0, src.width, 0, 0, src.width, src.height)
        val dstPixels = IntArray(w * h)
        for (dy in 0 until h) {
            val sy = min(src.height - 1, dy / s)
            for (dx in 0 until w) {
                val sx = min(src.width - 1, dx / s)
                dstPixels[dy * w + dx] = srcPixels[sy * src.width + sx]
            }
        }
        val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        dst.setPixels(dstPixels, 0, w, 0, 0, w, h)
        return dst
    }

    /**
     * Arbitrary-ratio nearest-neighbor resize (Blueprint #16 "Replace Image"): unlike
     * [nearestNeighborScale] (integer zoom factor only), this fits a source bitmap of any size
     * onto an exact target width/height - e.g. an imported photo/PNG being dropped onto an
     * existing layer - while still sampling nearest-neighbor only (no blending/anti-aliasing,
     * Blueprint #18).
     */
    fun resizeNearestNeighbor(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val w = max(1, targetW); val h = max(1, targetH)
        val srcPixels = IntArray(src.width * src.height)
        src.getPixels(srcPixels, 0, src.width, 0, 0, src.width, src.height)
        val dstPixels = IntArray(w * h)
        for (dy in 0 until h) {
            val sy = min(src.height - 1, dy * src.height / h)
            for (dx in 0 until w) {
                val sx = min(src.width - 1, dx * src.width / w)
                dstPixels[dy * w + dx] = srcPixels[sy * src.width + sx]
            }
        }
        val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        dst.setPixels(dstPixels, 0, w, 0, 0, w, h)
        return dst
    }

    /**
     * PASTE INTO LAYER (Blueprint #16): composites `paste` onto `base` at (x, y), clipping
     * anything outside `base`'s bounds. Non-destructive to `base`'s existing pixels outside the
     * pasted region (straight alpha-over composite, no anti-aliasing).
     */
    fun pasteOnto(base: Bitmap, paste: Bitmap, x: Int, y: Int): Bitmap {
        val dst = mutableCopy(base)
        val canvas = android.graphics.Canvas(dst)
        val paint = android.graphics.Paint().apply { isAntiAlias = false; isFilterBitmap = false }
        canvas.drawBitmap(paste, x.toFloat(), y.toFloat(), paint)
        return dst
    }

    fun rotate90(src: Bitmap): Bitmap {
        val w = src.height; val h = src.width
        val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                dst.setPixel(src.height - 1 - y, x, src.getPixel(x, y))
            }
        }
        return dst
    }

    fun flipHorizontal(src: Bitmap): Bitmap {
        val dst = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                dst.setPixel(src.width - 1 - x, y, src.getPixel(x, y))
            }
        }
        return dst
    }

    fun flipVertical(src: Bitmap): Bitmap {
        val dst = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                dst.setPixel(x, src.height - 1 - y, src.getPixel(x, y))
            }
        }
        return dst
    }

    /** Composites visible layers bottom-to-top honoring per-layer opacity, no anti-aliasing blend beyond alpha compositing. */
    fun compositeLayers(bitmaps: List<Bitmap>, opacities: List<Float>, visibilities: List<Boolean>, width: Int, height: Int): Bitmap {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        val paint = android.graphics.Paint().apply { isAntiAlias = false; isFilterBitmap = false }
        for (i in bitmaps.indices) {
            if (!visibilities[i]) continue
            paint.alpha = (opacities[i].coerceIn(0f, 1f) * 255).toInt()
            canvas.drawBitmap(bitmaps[i], 0f, 0f, paint)
        }
        return result
    }
}
