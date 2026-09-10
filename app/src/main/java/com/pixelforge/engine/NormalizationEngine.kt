package com.pixelforge.engine

import android.graphics.Bitmap
import android.graphics.Color

enum class NormalizationAnchor { TOP_LEFT, TOP_CENTER, CENTER, BOTTOM_CENTER, BOTTOM_LEFT, BOTTOM_RIGHT }
enum class NormalizationMode { AUTO, MANUAL }

/** CANVAS NORMALIZATION (Blueprint #33): resizes all frames onto a uniform canvas with a consistent anchor. */
object NormalizationEngine {

    fun normalize(
        src: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        padding: Int = 0,
        anchor: NormalizationAnchor = NormalizationAnchor.BOTTOM_CENTER
    ): Bitmap {
        val availW = targetWidth - padding * 2
        val availH = targetHeight - padding * 2
        val (ax, ay) = anchorOffset(anchor, availW, availH, src.width, src.height)
        val offsetX = padding + ax
        val offsetY = padding + ay

        val out = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(targetWidth * targetHeight) { Color.TRANSPARENT }
        val srcPixels = IntArray(src.width * src.height)
        src.getPixels(srcPixels, 0, src.width, 0, 0, src.width, src.height)

        for (sy in 0 until src.height) {
            val dy = sy + offsetY
            if (dy !in 0 until targetHeight) continue
            for (sx in 0 until src.width) {
                val dx = sx + offsetX
                if (dx !in 0 until targetWidth) continue
                outPixels[dy * targetWidth + dx] = srcPixels[sy * src.width + sx]
            }
        }
        out.setPixels(outPixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        return out
    }

    private fun anchorOffset(anchor: NormalizationAnchor, availW: Int, availH: Int, srcW: Int, srcH: Int): Pair<Int, Int> {
        val cx = (availW - srcW) / 2
        val cy = (availH - srcH) / 2
        return when (anchor) {
            NormalizationAnchor.TOP_LEFT -> 0 to 0
            NormalizationAnchor.TOP_CENTER -> cx to 0
            NormalizationAnchor.CENTER -> cx to cy
            NormalizationAnchor.BOTTOM_CENTER -> cx to (availH - srcH)
            NormalizationAnchor.BOTTOM_LEFT -> 0 to (availH - srcH)
            NormalizationAnchor.BOTTOM_RIGHT -> (availW - srcW) to (availH - srcH)
        }
    }
}
