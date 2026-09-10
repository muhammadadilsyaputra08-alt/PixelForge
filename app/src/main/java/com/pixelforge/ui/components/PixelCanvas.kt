package com.pixelforge.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import com.pixelforge.core.ReferenceBoundary
import com.pixelforge.core.GeometrySpec
import com.pixelforge.engine.GeometryEngine
import com.pixelforge.core.Rect as PfRect
import kotlin.math.max
import kotlin.math.min

data class PixelPoint(val x: Int, val y: Int)

/**
 * Pixel-perfect canvas renderer. Draws the composited bitmap with nearest-neighbor scaling
 * (no smoothing/filtering, Blueprint #18), a transparency checkerboard, an optional selection
 * mask overlay, and reports pixel-space touch coordinates for tools + Smart Select drag rectangles.
 *
 * Gesture handling is done manually (awaitEachGesture) rather than via detectTapGestures +
 * detectDragGestures so that a plain tap (pencil dot) and a drag (stroke / Smart Select rectangle)
 * are both driven from a single, unambiguous pointer stream.
 */
@Composable
fun PixelCanvas(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
    selectionOverlay: BooleanArray? = null,
    selectionMaskWidth: Int = 0,
    selectionMaskHeight: Int = 0,
    selectionOffsetX: Int = 0,
    selectionOffsetY: Int = 0,
    onDown: ((PixelPoint) -> Unit)? = null,
    onDrag: ((PixelPoint) -> Unit)? = null,
    onUp: (() -> Unit)? = null,
    onDragRect: ((PfRect) -> Unit)? = null,
    // Gates BOTH the drag-rectangle preview and the onDragRect(...) callback. Must be true for
    // *both* Smart Select AND Manual Select (Blueprint #6-15) - previously this only turned on
    // for Smart Select, so a Manual Select drag never produced a rect and never drew a preview,
    // i.e. Select Manual silently did nothing on canvas.
    dragRectMode: Boolean = false,
    referenceBoundary: ReferenceBoundary? = null,
    // Actual (non-transparent) sprite bounds (Blueprint #21/#24): drawn as a solid grid box
    // wrapping the detected sprite so the reference lines above visually read as offsets
    // *from* that box, matching the HTML tool's "sprite wrapped in a select grid" view. Purely
    // visual - has no own gesture handling.
    spriteBounds: com.pixelforge.core.Bounds? = null,
    // SPRITE MAPPER draggable offset handles (Blueprint #25-26, parity with the HTML tool): the
    // reference box is drawn as a highlighted rectangle with three fully independent circle
    // handles labeled L/R/B. Each one moves ONLY its own line - there are deliberately no
    // corner handles that move two lines at once, since L, R, and B are independent
    // measurements and moving one must never move a line already positioned by the user.
    // Every intermediate position is reported live.
    onReferenceDrag: ((ReferenceBoundary) -> Unit)? = null,
    geometrySpec: GeometrySpec? = null,
    geometryColor: Int = android.graphics.Color.BLACK,
    // Manual Select resize handles (parity with the HTML tool's select box): when a rectangular
    // selection already exists, its bounds are drawn with 4 corner + 4 edge handles you can drag
    // to grow/shrink the selection directly, instead of only being able to draw a brand-new
    // rectangle from scratch. Only meaningful for Manual Select (Smart Select's mask may not be
    // a plain rectangle, so it isn't offered handles here).
    selectionBounds: PfRect? = null,
    onSelectionResize: ((PfRect) -> Unit)? = null,
    // Floating selection (copy/cut) resize handles (parity with the HTML tool): 4 corner handles
    // on the floating piece's current box. Dragging a corner in/out scales the floating piece
    // uniformly (the underlying FloatingSelection model has a single `scale` factor, not
    // independent width/height, so this is a uniform pinch-style resize rather than a stretch).
    floatingBox: PfRect? = null,
    onFloatingResize: ((Float) -> Unit)? = null,
    // ONION SKIN (layered frame drawing): an optional ghost of another frame (normally the
    // previous frame in the active animation sequence) drawn faintly UNDER the real bitmap, so
    // the artist can trace/align the next frame against it. Null draws nothing at all - the
    // auto-hide behavior lives entirely in the caller only ever passing a non-null bitmap while
    // its own "onion skin enabled" toggle is on.
    onionSkinBitmap: Bitmap? = null,
    onionSkinOpacity: Float = 0.35f,
    // PIXEL GRID overlay (SESI 9): faint lines at every bitmap-pixel boundary, purely visual -
    // drawn last (on top of everything else) and does not participate in hit-testing at all, so
    // it's safe to toggle without touching any gesture/touch-handling code whatsoever. Skipped
    // automatically when pixels would render smaller than [gridMinCellPx] on screen, since at
    // that density the grid would just be visual noise (and a LOT of lines to draw) rather than
    // a helpful guide - e.g. a whole huge sprite-sheet zoomed out to fit the screen.
    showPixelGrid: Boolean = false,
    gridMinCellPx: Float = 6f
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val onionSkinImage = onionSkinBitmap?.let { ghost -> remember(ghost) { ghost.asImageBitmap() } }
    // Built once per canvas size (not per frame) and just blitted with nearest-neighbor
    // scaling, same as the art bitmap itself - see checkerboardBitmap() below for why this
    // replaced a per-cell drawRect loop.
    val checkerBitmap = remember(bitmap.width, bitmap.height) {
        checkerboardBitmap(bitmap.width, bitmap.height).asImageBitmap()
    }
    // Rebuilt only when the mask itself (or geometry) changes - not on every draw pass. See
    // selectionOverlayBitmap() for why the previous per-pixel rect+line loop here was just as
    // dangerous as the checkerboard loop above.
    val selectionOverlayImage = remember(selectionOverlay, selectionMaskWidth, selectionMaskHeight, selectionOffsetX, selectionOffsetY, bitmap.width, bitmap.height) {
        selectionOverlay?.let {
            selectionOverlayBitmap(it, selectionMaskWidth, selectionMaskHeight, selectionOffsetX, selectionOffsetY, bitmap.width, bitmap.height)?.asImageBitmap()
        }
    }
    var dragStart by remember { mutableStateOf<PixelPoint?>(null) }
    var dragCurrent by remember { mutableStateOf<PixelPoint?>(null) }

    val geometryPreviewImage = geometrySpec?.let { spec ->
        remember(spec, geometryColor, bitmap.width, bitmap.height) {
            GeometryEngine.drawGeometry(
                Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888),
                spec, geometryColor
            ).asImageBitmap()
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
            .pointerInput(
                bitmap.width, bitmap.height, dragRectMode,
                referenceBoundary != null, selectionBounds != null, floatingBox != null
            ) {
                val touchSlopPx = 24.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val startPixel = toPixel(down.position, size.width, size.height, bitmap.width, bitmap.height)

                    val refTop = spriteBounds?.top ?: 0
                    // Character Reference offset lines (L/R/B): hit-test against the FULL length
                    // of each line, not just its single midpoint - previously only a small dot at
                    // the exact center of each line was draggable, so touching anywhere else along
                    // the line fell through to the pen/draw tool instead of moving the guide.
                    val hitRefHandle = referenceBoundary?.let { ref ->
                        onReferenceDrag?.let {
                            nearestReferenceLine(
                                startPixel, ref, refTop,
                                size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height, touchSlopPx
                            )
                        }
                    }

                    if (hitRefHandle != null && referenceBoundary != null && onReferenceDrag != null) {
                        // Dragging a Character Reference handle instead of drawing/selecting.
                        // Explicit non-null type here (not `var current = referenceBoundary`,
                        // which would infer the nullable `ReferenceBoundary?` and break the
                        // `.copy(...)` calls below with "compileDebugKotlin" errors like "Only
                        // safe (?.) or non-null asserted (!!.) calls are allowed on a nullable
                        // receiver" - a `var` reassigned inside this closure can't keep the
                        // smart-cast Kotlin already proved from the `referenceBoundary != null`
                        // check above).
                        var current: ReferenceBoundary = referenceBoundary
                        var pointer = down
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == pointer.id } ?: event.changes.firstOrNull()
                            if (change == null) break
                            pointer = change
                            if (change.positionChange() != Offset.Zero) {
                                val p = toPixel(change.position, size.width, size.height, bitmap.width, bitmap.height)
                                current = applyReferenceHandle(hitRefHandle, p, current, bitmap.width, bitmap.height)
                                onReferenceDrag.invoke(current)
                                change.consume()
                            }
                            if (change.changedToUp()) break
                        }
                        return@awaitEachGesture
                    }

                    val hitFloatHandle = floatingBox?.let { box ->
                        onFloatingResize?.let {
                            nearestHandle(
                                down.position, cornerHandles(box),
                                size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height, touchSlopPx
                            )
                        }
                    }

                    if (hitFloatHandle != null && floatingBox != null && onFloatingResize != null) {
                        // Dragging a floating-selection corner handle: uniform pinch-style scale
                        // around the box's own center, driven by the ratio of the touch's
                        // distance-from-center between successive moves (same incremental,
                        // multiplicative style as the canvas's own pinch-zoom gesture).
                        val cx = (floatingBox.x + floatingBox.width / 2f)
                        val cy = (floatingBox.y + floatingBox.height / 2f)
                        var prevDist = kotlin.math.hypot(startPixel.x - cx, startPixel.y - cy).coerceAtLeast(1f)
                        var pointer = down
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == pointer.id } ?: event.changes.firstOrNull()
                            if (change == null) break
                            pointer = change
                            if (change.positionChange() != Offset.Zero) {
                                val p = toPixel(change.position, size.width, size.height, bitmap.width, bitmap.height)
                                val dist = kotlin.math.hypot(p.x - cx, p.y - cy).coerceAtLeast(1f)
                                onFloatingResize.invoke(dist / prevDist)
                                prevDist = dist
                                change.consume()
                            }
                            if (change.changedToUp()) break
                        }
                        return@awaitEachGesture
                    }

                    val hitSelHandle = selectionBounds?.let { box ->
                        onSelectionResize?.let {
                            nearestHandle(
                                down.position, selectionHandles(box),
                                size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height, touchSlopPx
                            )
                        }
                    }

                    if (hitSelHandle != null && selectionBounds != null && onSelectionResize != null) {
                        // Dragging a Manual Select corner/edge handle: resizes the existing
                        // selection rectangle directly, mirroring the HTML tool's crop-style
                        // handles instead of only being able to draw a brand-new rectangle.
                        var left = selectionBounds.x.toFloat()
                        var top = selectionBounds.y.toFloat()
                        var right = (selectionBounds.x + selectionBounds.width).toFloat()
                        var bottom = (selectionBounds.y + selectionBounds.height).toFloat()
                        var pointer = down
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == pointer.id } ?: event.changes.firstOrNull()
                            if (change == null) break
                            pointer = change
                            if (change.positionChange() != Offset.Zero) {
                                val p = toPixel(change.position, size.width, size.height, bitmap.width, bitmap.height)
                                when (hitSelHandle) {
                                    "TL" -> { left = p.x.toFloat(); top = p.y.toFloat() }
                                    "TR" -> { right = p.x.toFloat(); top = p.y.toFloat() }
                                    "BL" -> { left = p.x.toFloat(); bottom = p.y.toFloat() }
                                    "BR" -> { right = p.x.toFloat(); bottom = p.y.toFloat() }
                                    "T" -> top = p.y.toFloat()
                                    "Bm" -> bottom = p.y.toFloat()
                                    "L" -> left = p.x.toFloat()
                                    "R" -> right = p.x.toFloat()
                                }
                                val nx = min(left, right).toInt().coerceIn(0, bitmap.width - 1)
                                val ny = min(top, bottom).toInt().coerceIn(0, bitmap.height - 1)
                                val nw = max(1, (kotlin.math.abs(right - left)).toInt())
                                val nh = max(1, (kotlin.math.abs(bottom - top)).toInt())
                                onSelectionResize.invoke(PfRect(nx, ny, nw, nh))
                                change.consume()
                            }
                            if (change.changedToUp()) break
                        }
                        return@awaitEachGesture
                    }

                    dragStart = startPixel
                    dragCurrent = startPixel
                    onDown?.invoke(startPixel)

                    var pointer = down
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == pointer.id } ?: event.changes.firstOrNull()
                        if (change == null) break
                        pointer = change
                        if (change.positionChange() != Offset.Zero) {
                            val p = toPixel(change.position, size.width, size.height, bitmap.width, bitmap.height)
                            dragCurrent = p
                            onDrag?.invoke(p)
                        }
                        if (change.changedToUp()) break
                    }

                    val s = dragStart; val c = dragCurrent
                    if (dragRectMode && s != null && c != null) {
                        val x = min(s.x, c.x); val y = min(s.y, c.y)
                        val w = max(1, kotlin.math.abs(c.x - s.x) + 1)
                        val h = max(1, kotlin.math.abs(c.y - s.y) + 1)
                        onDragRect?.invoke(PfRect(x, y, w, h))
                    }
                    dragStart = null; dragCurrent = null
                    onUp?.invoke()
                }
            }
    ) {
        drawImage(
            image = checkerBitmap,
            dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = androidx.compose.ui.graphics.FilterQuality.None
        )

        onionSkinImage?.let { ghost ->
            drawImage(
                image = ghost,
                dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                alpha = onionSkinOpacity,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.None
            )
        }

        drawImage(
            image = imageBitmap,
            dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = androidx.compose.ui.graphics.FilterQuality.None
        )

        selectionOverlayImage?.let {
            drawImage(
                image = it,
                dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.None
            )
        }

        val s = dragStart; val c = dragCurrent
        if (dragRectMode && s != null && c != null) {
            drawDragPreview(s, c, bitmap.width, bitmap.height)
        }

        geometryPreviewImage?.let { preview ->
            drawImage(
                image = preview,
                dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.None
            )
        }
        spriteBounds?.let { drawSpriteBoundsGrid(it, bitmap.width, bitmap.height) }
        referenceBoundary?.let { drawReferenceBox(it, spriteBounds?.top ?: 0, bitmap.width, bitmap.height) }
        selectionBounds?.let { drawBoxHandles(it, selectionHandles(it), bitmap.width, bitmap.height) }
        floatingBox?.let { drawBoxHandles(it, cornerHandles(it), bitmap.width, bitmap.height, cornersOnly = true) }
        if (showPixelGrid) drawPixelGrid(bitmap.width, bitmap.height, gridMinCellPx)
    }
}

private fun toPixel(offset: Offset, viewW: Int, viewH: Int, bmpW: Int, bmpH: Int): PixelPoint {
    val px = (offset.x / viewW * bmpW).toInt().coerceIn(0, bmpW - 1)
    val py = (offset.y / viewH * bmpH).toInt().coerceIn(0, bmpH - 1)
    return PixelPoint(px, py)
}

/** Character Reference offset-line hit test (fix for SESI 9 bug report): treats each of the
 *  three lines (L/R/B) as a full draggable segment instead of a single midpoint dot, so a touch
 *  ANYWHERE along the line's length grabs it - not just its exact center. Only falls through to
 *  the pen/draw tool when the touch is genuinely off all three lines by more than [thresholdPx].
 *  [lineMarginPx] extends a little past each line's own endpoints so the ends (near corners)
 *  are just as easy to grab as the middle. */
private fun nearestReferenceLine(
    touch: PixelPoint,
    ref: ReferenceBoundary,
    top: Int,
    cellW: Float,
    cellH: Float,
    thresholdPx: Float,
    lineMarginPx: Float = thresholdPx
): String? {
    val touchXpx = touch.x * cellW
    val touchYpx = touch.y * cellH
    val leftXpx = ref.left * cellW
    val rightXpx = ref.right * cellW
    val bottomYpx = ref.bottom * cellH
    val topYpx = top * cellH

    val candidates = mutableListOf<Pair<String, Float>>()
    if (touchYpx in (topYpx - lineMarginPx)..(bottomYpx + lineMarginPx)) {
        candidates += "L" to kotlin.math.abs(touchXpx - leftXpx)
        candidates += "R" to kotlin.math.abs(touchXpx - rightXpx)
    }
    if (touchXpx in (leftXpx - lineMarginPx)..(rightXpx + lineMarginPx)) {
        candidates += "B" to kotlin.math.abs(touchYpx - bottomYpx)
    }
    return candidates.filter { it.second <= thresholdPx }.minByOrNull { it.second }?.first
}

/** A draggable handle in pixel (bitmap) space, identified by a short id ("L"/"R"/"B"/
 *  "TL"/"TR"/"BL"/"BR"/"T"/"Bm"). Shared between the Character Reference box, the Manual
 *  Select resize box, and the floating-selection resize box so all three use the same
 *  hit-testing and drawing code. */
private data class HandlePoint(val id: String, val bx: Float, val by: Float)

/** Finds the closest handle to a touch-down position, within [thresholdPx] screen pixels. */
private fun nearestHandle(pos: Offset, handles: List<HandlePoint>, cellW: Float, cellH: Float, thresholdPx: Float): String? =
    handles
        .map { it.id to kotlin.math.hypot(pos.x - it.bx * cellW, pos.y - it.by * cellH) }
        .filter { it.second <= thresholdPx }
        .minByOrNull { it.second }
        ?.first

/** Character Reference handles (Blueprint #25-26): three fully independent single-axis
 *  handles - L moves only the left line, R only the right line, B only the bottom line. No
 *  corner handles that move two lines at once: each line is its own independent boundary, so
 *  moving one must never move a line you already positioned. */
private fun referenceHandles(ref: ReferenceBoundary, top: Int): List<HandlePoint> {
    val midY = (top + ref.bottom) / 2f
    val midX = (ref.left + ref.right) / 2f
    return listOf(
        HandlePoint("L", ref.left.toFloat(), midY),
        HandlePoint("R", ref.right.toFloat(), midY),
        HandlePoint("B", midX, ref.bottom.toFloat())
    )
}

private fun applyReferenceHandle(id: String, p: PixelPoint, cur: ReferenceBoundary, bmpW: Int, bmpH: Int): ReferenceBoundary {
    val x = p.x.coerceIn(0, bmpW); val y = p.y.coerceIn(0, bmpH)
    return when (id) {
        "L" -> cur.copy(left = x)
        "R" -> cur.copy(right = x)
        "B" -> cur.copy(bottom = y)
        else -> cur
    }
}

/** Manual Select resize handles: full 8-point crop-style box (4 corners + 4 edge midpoints),
 *  since every side of a plain rectangular selection is independently adjustable. */
private fun selectionHandles(box: PfRect): List<HandlePoint> {
    val left = box.x.toFloat(); val top = box.y.toFloat()
    val right = (box.x + box.width).toFloat(); val bottom = (box.y + box.height).toFloat()
    val midX = (left + right) / 2f; val midY = (top + bottom) / 2f
    return listOf(
        HandlePoint("TL", left, top), HandlePoint("TR", right, top),
        HandlePoint("BL", left, bottom), HandlePoint("BR", right, bottom),
        HandlePoint("T", midX, top), HandlePoint("Bm", midX, bottom),
        HandlePoint("L", left, midY), HandlePoint("R", right, midY)
    )
}

/** Floating-selection resize handles: corners only (the model scales uniformly, so an edge
 *  handle wouldn't do anything an adjacent corner doesn't already do). */
private fun cornerHandles(box: PfRect): List<HandlePoint> {
    val left = box.x.toFloat(); val top = box.y.toFloat()
    val right = (box.x + box.width).toFloat(); val bottom = (box.y + box.height).toFloat()
    return listOf(
        HandlePoint("TL", left, top), HandlePoint("TR", right, top),
        HandlePoint("BL", left, bottom), HandlePoint("BR", right, bottom)
    )
}

/**
 * Builds the transparency checkerboard as a small bitmap (2 checker cells per art pixel in
 * each axis) instead of drawing it as thousands of individual rects.
 *
 * The old implementation sized checker cells off the *view's* pixel width
 * (`size.width / bmpW / 4`, floored to a minimum of 1px) and looped `cols * rows` individual
 * `drawRect` calls, every single draw frame. On a phone-sized view with a large art canvas
 * (up to `CanvasPresets.MAX_DIMENSION` = 1024px) that floor kicks in and the cell shrinks to
 * ~1 physical pixel, which balloons to on the order of a million drawRect calls per frame -
 * enough by itself to blow through Android's 5s input-dispatch timeout the moment a project's
 * canvas is shown, independent of any tool being used. That matches an ANR that reproduces
 * just from opening the workspace, not only while drawing.
 *
 * This version instead rasterizes the checker pattern once, at a size tied to the *art*
 * resolution (bmpW*2 x bmpH*2), caches it via `remember(bitmap.width, bitmap.height)` in the
 * caller so it isn't rebuilt every frame, and blits it with a single nearest-neighbor
 * `drawImage` call - O(1) draw calls per frame regardless of canvas size or screen density.
 */
private fun checkerboardBitmap(bmpW: Int, bmpH: Int): Bitmap {
    val sub = 2
    val w = (bmpW * sub).coerceAtLeast(1)
    val h = (bmpH * sub).coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val light = 0xFF3A3742.toInt()
    val dark = 0xFF2A2731.toInt()
    val pixels = IntArray(w * h)
    for (row in 0 until h) {
        val base = row * w
        for (col in 0 until w) {
            pixels[base + col] = if ((row + col) % 2 == 0) light else dark
        }
    }
    bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    return bmp
}

/**
 * Renders the Smart/Manual Selection pixel mask as a semi-transparent tint with a 1px white
 * outline on edges bordering a non-selected pixel, so the compound (possibly holed) region
 * built in SelectionEngine is visible on canvas.
 *
 * Previously this drew directly into the DrawScope: one `drawRect` plus up to four `drawLine`
 * calls per *selected mask pixel*, re-run on every single draw frame (drag included). With a
 * selection covering a large fraction of a near-`MAX_DIMENSION` (1024x1024) canvas, that's
 * millions of draw calls per frame - on top of the checkerboard issue above, and specifically
 * triggered by using Smart Select / Manual Select, which is exactly the kind of drag gesture
 * an ANR ("Waited 5000ms for MotionEvent") would show up under.
 *
 * This builds an ARGB bitmap of the tint+outline once per mask (cached by the caller via
 * `remember`), so the per-pixel loop only re-runs when the selection actually changes, and the
 * draw phase itself is a single `drawImage` call.
 */
private fun selectionOverlayBitmap(
    mask: BooleanArray,
    maskW: Int,
    maskH: Int,
    offsetX: Int,
    offsetY: Int,
    bmpW: Int,
    bmpH: Int
): Bitmap? {
    if (maskW <= 0 || maskH <= 0 || bmpW <= 0 || bmpH <= 0) return null
    val tint = 0x556C5CE7.toInt()
    val outline = 0xFFFFFFFF.toInt()
    val pixels = IntArray(bmpW * bmpH)
    for (my in 0 until maskH) {
        for (mx in 0 until maskW) {
            if (!mask[my * maskW + mx]) continue
            val px = mx + offsetX
            val py = my + offsetY
            if (px !in 0 until bmpW || py !in 0 until bmpH) continue
            val leftSel = mx > 0 && mask[my * maskW + (mx - 1)]
            val rightSel = mx < maskW - 1 && mask[my * maskW + (mx + 1)]
            val topSel = my > 0 && mask[(my - 1) * maskW + mx]
            val bottomSel = my < maskH - 1 && mask[(my + 1) * maskW + mx]
            // Any bordering edge that touches a non-selected neighbor turns the whole pixel
            // into the outline color - a one-pixel-thick border, cheaper than sub-pixel lines
            // and indistinguishable at this scale (art pixels are already blown up several
            // screen-pixels wide by the nearest-neighbor draw).
            val isEdge = !leftSel || !rightSel || !topSel || !bottomSel
            pixels[py * bmpW + px] = if (isEdge) outline else tint
        }
    }
    val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
    bmp.setPixels(pixels, 0, bmpW, 0, 0, bmpW, bmpH)
    return bmp
}

/**
 * CHARACTER REFERENCE overlay (Blueprint #25, HTML-tool parity): draws L/R/B as 3 fully
 * independent full-length dashed lines (L and R span the entire canvas height, B spans the
 * entire canvas width), each with its own cyan mid-edge circle handle and label - not a
 * connected rectangle. A rectangle (even an unfilled one) visually reads as a single shape
 * whose corners move together, which is wrong here: L, R, and B are independent measurements
 * that never share an endpoint, so dragging one must never look like it reshaped the others.
 * There are no corner handles for the same reason.
 */
private fun DrawScope.drawReferenceBox(ref: ReferenceBoundary, top: Int, bmpW: Int, bmpH: Int) {
    val cellW = size.width / bmpW; val cellH = size.height / bmpH
    val left = ref.left * cellW; val right = ref.right * cellW
    val topPx = top * cellH; val bottomPx = ref.bottom * cellH
    val lineColor = Color(0xFFFFB340)
    val dash = androidx.compose.ui.graphics.drawscope.Stroke(
        width = 2f,
        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
    )
    // L: independent vertical line, full canvas height.
    drawLine(color = lineColor, start = Offset(left, 0f), end = Offset(left, size.height), strokeWidth = dash.width, pathEffect = dash.pathEffect)
    // R: independent vertical line, full canvas height.
    drawLine(color = lineColor, start = Offset(right, 0f), end = Offset(right, size.height), strokeWidth = dash.width, pathEffect = dash.pathEffect)
    // B: independent horizontal line, full canvas width.
    drawLine(color = lineColor, start = Offset(0f, bottomPx), end = Offset(size.width, bottomPx), strokeWidth = dash.width, pathEffect = dash.pathEffect)
    for (h in referenceHandles(ref, top)) {
        val hx = h.bx * cellW; val hy = h.by * cellH
        if (h.id.length == 2) drawCornerSquare(hx, hy) else drawEdgeCircle(hx, hy)
    }
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 28f
        isAntiAlias = true
        setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
    }
    drawContext.canvas.nativeCanvas.apply {
        drawText("L", left + 6f, topPx + 26f, paint)
        drawText("R", right - 26f, topPx + 26f, paint)
        drawText("B", left + 6f, bottomPx - 10f, paint)
    }
}

/** Draws the corner + edge handles shared by Manual Select and floating-selection resize
 *  boxes: orange squares on corners, cyan circles on edge midpoints (if any). */
private fun DrawScope.drawBoxHandles(box: PfRect, handles: List<HandlePoint>, bmpW: Int, bmpH: Int, cornersOnly: Boolean = false) {
    val cellW = size.width / bmpW; val cellH = size.height / bmpH
    val left = box.x * cellW; val top = box.y * cellH
    val right = (box.x + box.width) * cellW; val bottom = (box.y + box.height) * cellH
    drawRect(
        color = Color(0xFF6C5CE7),
        topLeft = Offset(left, top),
        size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
    )
    for (h in handles) {
        val hx = h.bx * cellW; val hy = h.by * cellH
        if (cornersOnly || h.id.length == 2) drawCornerSquare(hx, hy) else drawEdgeCircle(hx, hy)
    }
}

private fun DrawScope.drawCornerSquare(x: Float, y: Float) {
    val half = 9f
    drawRect(
        color = Color(0xFFFFB340),
        topLeft = Offset(x - half, y - half),
        size = androidx.compose.ui.geometry.Size(half * 2, half * 2)
    )
    drawRect(
        color = Color.Black,
        topLeft = Offset(x - half, y - half),
        size = androidx.compose.ui.geometry.Size(half * 2, half * 2),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
    )
}

private fun DrawScope.drawEdgeCircle(x: Float, y: Float) {
    drawCircle(color = Color(0xFF4FC3F7), radius = 11f, center = Offset(x, y))
    drawCircle(color = Color.White, radius = 11f, center = Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
}

/** Solid bounding-box "grid" wrapping the sprite's actual (non-transparent) pixels - see
 *  [PixelCanvas]'s `spriteBounds` doc. */
/** PIXEL GRID (SESI 9): thin lines at every bitmap-pixel boundary, to help place pixels
 *  precisely. Purely visual - drawn last, never affects hit-testing. Skips entirely once cells
 *  would render smaller than [minCellPx] on screen (see [PixelCanvas]'s showPixelGrid doc). */
private fun DrawScope.drawPixelGrid(bmpW: Int, bmpH: Int, minCellPx: Float) {
    val cellW = size.width / bmpW
    val cellH = size.height / bmpH
    if (cellW < minCellPx || cellH < minCellPx) return
    val gridColor = Color.White.copy(alpha = 0.18f)
    var x = 0f
    while (x <= size.width) {
        drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += cellW
    }
    var y = 0f
    while (y <= size.height) {
        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += cellH
    }
}

private fun DrawScope.drawSpriteBoundsGrid(bounds: com.pixelforge.core.Bounds, bmpW: Int, bmpH: Int) {
    val cellW = size.width / bmpW; val cellH = size.height / bmpH
    val boxColor = Color(0xFF6C5CE7)
    drawRect(
        color = boxColor,
        topLeft = Offset(bounds.left * cellW, bounds.top * cellH),
        size = androidx.compose.ui.geometry.Size((bounds.right - bounds.left) * cellW, (bounds.bottom - bounds.top) * cellH),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
    )
}

private fun DrawScope.drawDragPreview(start: PixelPoint, current: PixelPoint, bmpW: Int, bmpH: Int) {
    val x0 = min(start.x, current.x); val y0 = min(start.y, current.y)
    val x1 = max(start.x, current.x); val y1 = max(start.y, current.y)
    val cellW = size.width / bmpW; val cellH = size.height / bmpH
    drawRect(
        color = Color(0x556C5CE7),
        topLeft = Offset(x0 * cellW, y0 * cellH),
        size = androidx.compose.ui.geometry.Size((x1 - x0 + 1) * cellW, (y1 - y0 + 1) * cellH)
    )
    drawRect(
        color = Color(0xFF6C5CE7),
        topLeft = Offset(x0 * cellW, y0 * cellH),
        size = androidx.compose.ui.geometry.Size((x1 - x0 + 1) * cellW, (y1 - y0 + 1) * cellH),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
    )
}


@androidx.compose.runtime.Composable
private fun rememberGeometryPreview(bitmap: Bitmap, spec: GeometrySpec, color: Int): androidx.compose.ui.graphics.ImageBitmap {
    val key = "${spec.type}:${spec.startX}:${spec.startY}:${spec.endX}:${spec.endY}:$color:${bitmap.width}:${bitmap.height}"
    val preview = androidx.compose.runtime.remember(key) {
        GeometryEngine.drawGeometry(
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888),
            spec, color
        ).asImageBitmap()
    }
    return preview
}
