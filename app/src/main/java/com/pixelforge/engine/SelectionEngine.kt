package com.pixelforge.engine

import com.pixelforge.core.Rect
import com.pixelforge.core.SelectOpType
import com.pixelforge.core.SelectOperation
import com.pixelforge.core.SelectionState

/**
 * SELECTION ENGINE (Blueprint #6-15, #42-44).
 *
 * Manual Selection: plain rectangle selection (copy/cut/delete/move/transform/rotate/flip)
 * operating directly on a pixel rectangle.
 *
 * Smart Selection ("Compound Rectangle Select"): every drag produces ONE rectangle, combined
 * against the existing mask using an EXPLICIT [SelectOpType] chosen by the user via
 * [com.pixelforge.core.SelectionMode] (Blueprint #15) - REPLACE / ADD / SUBTRACT / TOGGLE.
 * The old implicit "touch starts inside selection -> remove, outside -> add" inference has been
 * removed: mode is always a deliberate choice, never inferred from drag position.
 * Result is stored as a pixel mask (boolean grid), not as a list of rectangles, so holes and
 * irregular shapes persist regardless of the rectangles used to build them.
 */
class PixelMask(val boundsX: Int, val boundsY: Int, val width: Int, val height: Int, val bits: BooleanArray) {

    fun get(px: Int, py: Int): Boolean {
        val lx = px - boundsX; val ly = py - boundsY
        if (lx !in 0 until width || ly !in 0 until height) return false
        return bits[ly * width + lx]
    }

    fun isEmpty(): Boolean = bits.none { it }

    companion object {
        fun empty(): PixelMask = PixelMask(0, 0, 0, 0, BooleanArray(0))
    }
}

object SelectionEngine {

    /** Manual rectangle selection is just a Rect - trivial helper retained for symmetry/API clarity. */
    fun manualSelect(x: Int, y: Int, width: Int, height: Int): Rect = Rect(x, y, width, height)

    /** Applies one rectangle operation (ADD/SUBTRACT/TOGGLE/REPLACE) to the operations log and rebuilds the mask + bounds. */
    fun applyOperation(state: SelectionState, op: SelectOperation): SelectionState {
        val newOps = state.operations + op
        return rebuild(newOps)
    }

    fun clear(): SelectionState = SelectionState()

    fun invert(state: SelectionState, canvasWidth: Int, canvasHeight: Int): SelectionState {
        val mask = toMask(state)
        val bits = BooleanArray(canvasWidth * canvasHeight)
        for (y in 0 until canvasHeight) for (x in 0 until canvasWidth) {
            bits[y * canvasWidth + x] = !mask.get(x, y)
        }
        return fromMask(PixelMask(0, 0, canvasWidth, canvasHeight, bits))
    }

    fun expand(state: SelectionState, amount: Int, canvasW: Int, canvasH: Int): SelectionState =
        morphological(state, amount, canvasW, canvasH, grow = true)

    fun contract(state: SelectionState, amount: Int, canvasW: Int, canvasH: Int): SelectionState =
        morphological(state, amount, canvasW, canvasH, grow = false)

    private fun morphological(state: SelectionState, amount: Int, canvasW: Int, canvasH: Int, grow: Boolean): SelectionState {
        if (amount <= 0) return state
        var mask = toMask(state)
        repeat(amount) {
            val bits = BooleanArray(canvasW * canvasH)
            for (y in 0 until canvasH) for (x in 0 until canvasW) {
                val current = mask.get(x, y)
                val neighborSelected = mask.get(x - 1, y) || mask.get(x + 1, y) || mask.get(x, y - 1) || mask.get(x, y + 1)
                bits[y * canvasW + x] = if (grow) current || neighborSelected else current && (mask.get(x - 1, y) && mask.get(x + 1, y) && mask.get(x, y - 1) && mask.get(x, y + 1))
            }
            mask = PixelMask(0, 0, canvasW, canvasH, bits)
        }
        return fromMask(mask)
    }

    /**
     * Rebuilds mask+bounds from the operation log.
     *
     * - REPLACE truncates history: a REPLACE rectangle discards everything selected before it,
     *   so only operations from the last REPLACE onward (inclusive) affect the result. The full
     *   `operations` list is still kept on the returned state for record-keeping.
     * - ADD unions the rectangle in; SUBTRACT removes it; TOGGLE flips each pixel inside it.
     */
    fun rebuild(operations: List<SelectOperation>): SelectionState {
        if (operations.isEmpty()) return SelectionState()

        val lastReplaceIdx = operations.indexOfLast { it.type == SelectOpType.REPLACE }
        val effectiveOps = if (lastReplaceIdx >= 0) operations.subList(lastReplaceIdx, operations.size) else operations

        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        for (op in effectiveOps) {
            minX = minOf(minX, op.x); minY = minOf(minY, op.y)
            maxX = maxOf(maxX, op.x + op.width); maxY = maxOf(maxY, op.y + op.height)
        }
        val w = maxX - minX; val h = maxY - minY
        if (w <= 0 || h <= 0) return SelectionState()

        val bits = BooleanArray(w * h)
        for (op in effectiveOps) {
            for (y in op.y until op.y + op.height) {
                val ly = y - minY
                for (x in op.x until op.x + op.width) {
                    val lx = x - minX
                    val idx = ly * w + lx
                    bits[idx] = when (op.type) {
                        SelectOpType.ADD, SelectOpType.REPLACE -> true
                        SelectOpType.SUBTRACT -> false
                        SelectOpType.TOGGLE -> !bits[idx]
                    }
                }
            }
        }

        // Recalculate tight bounds around actually-selected pixels (holes shrink nothing,
        // fully-removed edges do).
        var tMinX = w; var tMinY = h; var tMaxX = -1; var tMaxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (bits[y * w + x]) {
                if (x < tMinX) tMinX = x
                if (y < tMinY) tMinY = y
                if (x > tMaxX) tMaxX = x
                if (y > tMaxY) tMaxY = y
            }
        }
        if (tMaxX < 0) return SelectionState(operations = operations)

        val tightW = tMaxX - tMinX + 1
        val tightH = tMaxY - tMinY + 1
        val tightBits = BooleanArray(tightW * tightH)
        for (y in 0 until tightH) for (x in 0 until tightW) {
            tightBits[y * tightW + x] = bits[(y + tMinY) * w + (x + tMinX)]
        }

        return SelectionState(
            boundsX = minX + tMinX,
            boundsY = minY + tMinY,
            boundsWidth = tightW,
            boundsHeight = tightH,
            maskRLE = encodeRLE(tightBits),
            operations = operations
        )
    }

    fun toMask(state: SelectionState): PixelMask {
        if (state.boundsWidth <= 0 || state.boundsHeight <= 0) return PixelMask.empty()
        val bits = decodeRLE(state.maskRLE, state.boundsWidth * state.boundsHeight)
        return PixelMask(state.boundsX, state.boundsY, state.boundsWidth, state.boundsHeight, bits)
    }

    fun fromMask(mask: PixelMask): SelectionState {
        if (mask.isEmpty()) return SelectionState()
        var tMinX = mask.width; var tMinY = mask.height; var tMaxX = -1; var tMaxY = -1
        for (y in 0 until mask.height) for (x in 0 until mask.width) {
            if (mask.bits[y * mask.width + x]) {
                if (x < tMinX) tMinX = x
                if (y < tMinY) tMinY = y
                if (x > tMaxX) tMaxX = x
                if (y > tMaxY) tMaxY = y
            }
        }
        if (tMaxX < 0) return SelectionState()
        val tightW = tMaxX - tMinX + 1
        val tightH = tMaxY - tMinY + 1
        val tightBits = BooleanArray(tightW * tightH)
        for (y in 0 until tightH) for (x in 0 until tightW) {
            tightBits[y * tightW + x] = mask.bits[(y + tMinY) * mask.width + (x + tMinX)]
        }
        return SelectionState(
            boundsX = mask.boundsX + tMinX,
            boundsY = mask.boundsY + tMinY,
            boundsWidth = tightW,
            boundsHeight = tightH,
            maskRLE = encodeRLE(tightBits)
        )
    }

    /** Simple alternating-run-length encoding: [count of false, count of true, count of false, ...] */
    fun encodeRLE(bits: BooleanArray): List<Int> {
        val runs = mutableListOf<Int>()
        var current = false
        var count = 0
        for (b in bits) {
            if (b == current) {
                count++
            } else {
                runs.add(count)
                current = b
                count = 1
            }
        }
        runs.add(count)
        return runs
    }

    fun decodeRLE(runs: List<Int>, totalSize: Int): BooleanArray {
        val bits = BooleanArray(totalSize)
        var idx = 0
        var value = false
        for (run in runs) {
            for (i in 0 until run) {
                if (idx >= totalSize) break
                bits[idx] = value
                idx++
            }
            value = !value
        }
        return bits
    }
}
