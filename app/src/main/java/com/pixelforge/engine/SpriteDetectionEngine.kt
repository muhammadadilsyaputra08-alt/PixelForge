package com.pixelforge.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.pixelforge.core.FrameSource

data class SpriteDetectionParams(
    val useAlpha: Boolean = true,
    val keyColor: Int = Color.TRANSPARENT,
    val tolerance: Int = 28,
    val fragmentMergeDistancePx: Int = 3,
    val gridInGridMergePercent: Float = 0.35f,
    val minimumNoisePx2: Int = 6,
    val cropPaddingPx: Int = 1
)

data class DetectedFrame(val bounds: com.pixelforge.core.Bounds, val pixelCount: Int) {
    fun toFrameSource(padding: Int, maxW: Int, maxH: Int): FrameSource {
        val x = (bounds.left - padding).coerceAtLeast(0)
        val y = (bounds.top - padding).coerceAtLeast(0)
        val right = (bounds.right + padding).coerceAtMost(maxW)
        val bottom = (bounds.bottom + padding).coerceAtMost(maxH)
        return FrameSource(x, y, right - x, bottom - y)
    }
}

/**
 * SPRITE DETECTION (Blueprint #20).
 * Pipeline: Image -> Alpha/BG mask -> Connectivity Detection -> Noise Filtering
 *           -> Fragment Merge -> Bounding Box -> Frame Candidates
 */
object SpriteDetectionEngine {

    fun detect(src: Bitmap, params: SpriteDetectionParams): List<DetectedFrame> {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // 1. Foreground mask
        val fg = BooleanArray(w * h)
        for (i in pixels.indices) {
            fg[i] = if (params.useAlpha) {
                Color.alpha(pixels[i]) > 4
            } else {
                !PixelEngine.colorsMatch(pixels[i], params.keyColor, params.tolerance)
            }
        }

        // 2. Connectivity detection (connected-component labeling, 4-connectivity)
        val labels = IntArray(w * h) { -1 }
        var nextLabel = 0
        val componentBounds = mutableListOf<IntArray>() // left, top, right, bottom, count
        for (start in fg.indices) {
            if (!fg[start] || labels[start] != -1) continue
            val label = nextLabel++
            var left = start % w; var right = left; var top = start / w; var bottom = top; var count = 0
            val stack = ArrayDeque<Int>()
            stack.addLast(start)
            labels[start] = label
            while (stack.isNotEmpty()) {
                val idx = stack.removeLast()
                val px = idx % w; val py = idx / w
                count++
                if (px < left) left = px
                if (px > right) right = px
                if (py < top) top = py
                if (py > bottom) bottom = py
                val neighbors = intArrayOf(
                    if (px > 0) idx - 1 else -1,
                    if (px < w - 1) idx + 1 else -1,
                    if (py > 0) idx - w else -1,
                    if (py < h - 1) idx + w else -1
                )
                for (n in neighbors) {
                    if (n >= 0 && fg[n] && labels[n] == -1) {
                        labels[n] = label
                        stack.addLast(n)
                    }
                }
            }
            componentBounds.add(intArrayOf(left, top, right, bottom, count))
        }

        // 3. Noise filtering
        var components = componentBounds.filterIndexed { _, b ->
            val bw = b[2] - b[0] + 1; val bh = b[3] - b[1] + 1
            b[4] >= params.minimumNoisePx2 && bw * bh >= params.minimumNoisePx2
        }.toMutableList()

        // 4. Fragment merge: merge components whose bounding boxes are within fragmentMergeDistancePx
        var merged = true
        while (merged) {
            merged = false
            outer@ for (i in components.indices) {
                for (j in i + 1 until components.size) {
                    val a = components[i]; val b = components[j]
                    if (boxesWithinDistance(a, b, params.fragmentMergeDistancePx)) {
                        val nl = minOf(a[0], b[0]); val nt = minOf(a[1], b[1])
                        val nr = maxOf(a[2], b[2]); val nb = maxOf(a[3], b[3])
                        val ncount = a[4] + b[4]
                        components[i] = intArrayOf(nl, nt, nr, nb, ncount)
                        components.removeAt(j)
                        merged = true
                        break@outer
                    }
                }
            }
        }

        // grid-in-grid merge: if one box's area overlap with another exceeds gridInGridMergePercent, merge them.
        merged = true
        while (merged) {
            merged = false
            outer@ for (i in components.indices) {
                for (j in i + 1 until components.size) {
                    val a = components[i]; val b = components[j]
                    if (overlapRatio(a, b) >= params.gridInGridMergePercent) {
                        val nl = minOf(a[0], b[0]); val nt = minOf(a[1], b[1])
                        val nr = maxOf(a[2], b[2]); val nb = maxOf(a[3], b[3])
                        components[i] = intArrayOf(nl, nt, nr, nb, a[4] + b[4])
                        components.removeAt(j)
                        merged = true
                        break@outer
                    }
                }
            }
        }

        // 5. Bounding box -> frame candidates (with crop padding, clamped to image bounds)
        return components.map { b ->
            val left = (b[0] - params.cropPaddingPx).coerceAtLeast(0)
            val top = (b[1] - params.cropPaddingPx).coerceAtLeast(0)
            val right = (b[2] + 1 + params.cropPaddingPx).coerceAtMost(w)
            val bottom = (b[3] + 1 + params.cropPaddingPx).coerceAtMost(h)
            DetectedFrame(com.pixelforge.core.Bounds(left, top, right, bottom), b[4])
        }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    private fun boxesWithinDistance(a: IntArray, b: IntArray, dist: Int): Boolean {
        val ax1 = a[0] - dist; val ay1 = a[1] - dist; val ax2 = a[2] + dist; val ay2 = a[3] + dist
        return ax1 <= b[2] && b[0] <= ax2 && ay1 <= b[3] && b[1] <= ay2
    }

    private fun overlapRatio(a: IntArray, b: IntArray): Float {
        val ix1 = maxOf(a[0], b[0]); val iy1 = maxOf(a[1], b[1])
        val ix2 = minOf(a[2], b[2]); val iy2 = minOf(a[3], b[3])
        if (ix2 < ix1 || iy2 < iy1) return 0f
        val interArea = (ix2 - ix1 + 1) * (iy2 - iy1 + 1)
        val aArea = (a[2] - a[0] + 1) * (a[3] - a[1] + 1)
        val bArea = (b[2] - b[0] + 1) * (b[3] - b[1] + 1)
        val minArea = minOf(aArea, bArea)
        return interArea.toFloat() / minArea.toFloat()
    }
}
