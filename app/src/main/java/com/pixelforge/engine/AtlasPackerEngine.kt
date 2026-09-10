package com.pixelforge.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.pixelforge.core.AtlasBlock
import com.pixelforge.core.AtlasModel
import kotlin.math.max

data class PackInput(val frameId: String, val bitmap: Bitmap)

/** Bounding box of non-transparent pixels, packed alongside the size actually being placed. */
private data class TrimmedShape(
    val frameId: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val trimRect: Rect,
    val placedWidth: Int,
    val placedHeight: Int
)

/**
 * ATLAS PACKER ENGINE (Blueprint #34-35).
 * MaxRects-style bin packing (best-short-side-fit) with padding/spacing, optional power-of-two
 * rounding, and optional alpha trim (strips fully-transparent borders from each sprite before
 * packing so blank space doesn't cost atlas real estate).
 */
object AtlasPackerEngine {

    private data class FreeRect(var x: Int, var y: Int, var w: Int, var h: Int)

    fun pack(
        inputs: List<PackInput>,
        padding: Int,
        spriteSpacing: Int,
        powerOfTwo: Boolean,
        alphaTrim: Boolean = false
    ): AtlasModel {
        if (inputs.isEmpty()) return AtlasModel(alphaTrim = alphaTrim)

        val shapes = inputs.map { input ->
            val rect = if (alphaTrim) trimBounds(input.bitmap) else Rect(0, 0, input.bitmap.width, input.bitmap.height)
            TrimmedShape(
                frameId = input.frameId,
                sourceWidth = input.bitmap.width,
                sourceHeight = input.bitmap.height,
                trimRect = rect,
                placedWidth = rect.width(),
                placedHeight = rect.height()
            )
        }

        // sort largest-area-first (using the placed/trimmed size) for a tighter pack
        val sorted = shapes.sortedByDescending { it.placedWidth.toLong() * it.placedHeight }

        var binW = 64
        var binH = 64
        var blocks: List<AtlasBlock>
        while (true) {
            val result = tryPack(sorted, binW, binH, padding, spriteSpacing)
            if (result != null) { blocks = result; break }
            if (binW <= binH) binW *= 2 else binH *= 2
            if (binW > 8192 || binH > 8192) { blocks = emptyList(); break }
        }

        if (powerOfTwo) {
            binW = nextPowerOfTwo(binW)
            binH = nextPowerOfTwo(binH)
        }

        return AtlasModel(
            width = binW,
            height = binH,
            padding = padding,
            powerOfTwo = powerOfTwo,
            alphaTrim = alphaTrim,
            blocks = blocks.toMutableList()
        )
    }

    /** Finds the tight bounding box of non-fully-transparent pixels. Falls back to the whole
     *  bitmap when every pixel is transparent, so an empty frame never collapses to 0x0. */
    private fun trimBounds(bitmap: Bitmap): Rect {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (Color.alpha(pixels[row + x]) != 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        return if (maxX < 0) Rect(0, 0, w, h) else Rect(minX, minY, maxX + 1, maxY + 1)
    }

    private fun tryPack(shapes: List<TrimmedShape>, binW: Int, binH: Int, padding: Int, spacing: Int): List<AtlasBlock>? {
        val freeRects = mutableListOf(FreeRect(padding, padding, binW - padding * 2, binH - padding * 2))
        val placed = mutableListOf<AtlasBlock>()

        for (shape in shapes) {
            val w = shape.placedWidth + spacing
            val h = shape.placedHeight + spacing
            var bestIdx = -1
            var bestShortSideFit = Int.MAX_VALUE

            for (i in freeRects.indices) {
                val fr = freeRects[i]
                if (fr.w >= w && fr.h >= h) {
                    val shortSide = minOf(fr.w - w, fr.h - h)
                    if (shortSide < bestShortSideFit) { bestShortSideFit = shortSide; bestIdx = i }
                }
            }
            if (bestIdx == -1) return null

            val fr = freeRects[bestIdx]
            val placeX = fr.x; val placeY = fr.y
            placed.add(
                AtlasBlock(
                    frameId = shape.frameId,
                    x = placeX, y = placeY,
                    width = shape.placedWidth, height = shape.placedHeight,
                    rotated = false,
                    sourceWidth = shape.sourceWidth, sourceHeight = shape.sourceHeight,
                    trimOffsetX = shape.trimRect.left, trimOffsetY = shape.trimRect.top
                )
            )

            // Split free rect (guillotine split: right + bottom)
            freeRects.removeAt(bestIdx)
            val rightW = fr.w - w
            val bottomH = fr.h - h
            if (rightW > 0) freeRects.add(FreeRect(fr.x + w, fr.y, rightW, h))
            if (bottomH > 0) freeRects.add(FreeRect(fr.x, fr.y + h, fr.w, bottomH))
        }
        return placed
    }

    private fun nextPowerOfTwo(v: Int): Int {
        var n = 1
        while (n < v) n *= 2
        return n
    }

    /** Draws each block's (possibly trimmed) region from the source bitmaps onto the atlas. */
    fun renderAtlasBitmap(atlas: AtlasModel, bitmapsById: Map<String, Bitmap>): Bitmap {
        val out = Bitmap.createBitmap(max(1, atlas.width), max(1, atlas.height), Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(out)
        val paint = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
        for (block in atlas.blocks) {
            val bmp = bitmapsById[block.frameId] ?: continue
            val safeW = block.width.coerceAtMost(bmp.width - block.trimOffsetX).coerceAtLeast(1)
            val safeH = block.height.coerceAtMost(bmp.height - block.trimOffsetY).coerceAtLeast(1)
            val region = if (block.trimOffsetX == 0 && block.trimOffsetY == 0 &&
                safeW == bmp.width && safeH == bmp.height
            ) {
                bmp
            } else {
                Bitmap.createBitmap(bmp, block.trimOffsetX, block.trimOffsetY, safeW, safeH)
            }
            canvas.drawBitmap(region, block.x.toFloat(), block.y.toFloat(), paint)
        }
        return out
    }
}
