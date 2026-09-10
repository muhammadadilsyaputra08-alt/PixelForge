package com.pixelforge.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pixelforge.ui.viewmodel.WorkspaceTool
import com.pixelforge.core.GeometryType
import com.pixelforge.core.SelectionMode

data class DockAction(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val selected: Boolean = false,
    // Shown as a long-press tooltip (Material3 TooltipBox handles the press-and-hold gesture
    // automatically) so a user unsure what an icon+short-label chip actually does can hold it
    // down to read a one-line explanation, without adding an extra tap/mode to the normal flow.
    val description: String? = null,
    val onClick: () -> Unit
)

/**
 * Bottom Action Dock (Blueprint #46): contextual actions for the active mode/tool,
 * kept in a single scrollable row so mobile screens never show every control at once.
 *
 * Each chip is wrapped in a [TooltipBox] carrying [DockAction.description]: long-pressing a
 * chip pops up a plain-text explanation of what that tool/action does, addressing that an
 * icon + one-word label alone isn't enough to understand tools like "Smart Select" or
 * "Contract" at a glance.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ActionDock(actions: List<DockAction>, modifier: Modifier = Modifier) {
    Surface(tonalElevation = 3.dp, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            actions.forEach { action ->
                if (action.description != null) {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(action.description) } },
                        state = rememberTooltipState()
                    ) {
                        FilterChip(
                            selected = action.selected,
                            onClick = action.onClick,
                            label = { Text(action.label) },
                            leadingIcon = { Icon(action.icon, contentDescription = action.description, modifier = Modifier.size(18.dp)) }
                        )
                    }
                } else {
                    FilterChip(
                        selected = action.selected,
                        onClick = action.onClick,
                        label = { Text(action.label) },
                        leadingIcon = { Icon(action.icon, contentDescription = action.label, modifier = Modifier.size(18.dp)) }
                    )
                }
            }
        }
    }
}

fun editToolDockActions(current: WorkspaceTool, onSelect: (WorkspaceTool) -> Unit): List<DockAction> = listOf(
    DockAction("Pencil", Icons.Filled.Edit, current == WorkspaceTool.PENCIL,
        "Draw single pixels or brush strokes in the active color. Tap or drag on the canvas.") { onSelect(WorkspaceTool.PENCIL) },
    DockAction("Eraser", Icons.Filled.Delete, current == WorkspaceTool.ERASER,
        "Remove pixels (make them transparent). Same brush size control as Pencil.") { onSelect(WorkspaceTool.ERASER) },
    DockAction("Bucket", Icons.Filled.FormatColorFill, current == WorkspaceTool.BUCKET,
        "Flood-fill a connected area of the same color with the active color in one tap.") { onSelect(WorkspaceTool.BUCKET) },
    DockAction("Eyedropper", Icons.Filled.Colorize, current == WorkspaceTool.EYEDROPPER,
        "Pick a color from the canvas and make it the active drawing color.") { onSelect(WorkspaceTool.EYEDROPPER) },
    DockAction("Select", Icons.Filled.CropFree, current == WorkspaceTool.SELECT_MANUAL,
        "Manual Select: drag a rectangle by hand to select an exact region of pixels.") { onSelect(WorkspaceTool.SELECT_MANUAL) },
    DockAction("Smart Select", Icons.Filled.AutoFixHigh, current == WorkspaceTool.SMART_SELECT,
        "Smart Select: drag near the sprite and it auto-detects the connected shape for you, instead of you tracing it by hand.") { onSelect(WorkspaceTool.SMART_SELECT) },
    DockAction("Geometry", Icons.Filled.Category, current == WorkspaceTool.GEOMETRY,
        "Draw a shape (circle, box, oval, parallelogram, line) by dragging on the canvas.") { onSelect(WorkspaceTool.GEOMETRY) },
    DockAction("Pan", Icons.Filled.PanTool, current == WorkspaceTool.PAN,
        "Drag with one finger to move the view around when zoomed in, instead of drawing.") { onSelect(WorkspaceTool.PAN) }
)

fun smartSelectDockActions(
    onClear: () -> Unit,
    onInvert: () -> Unit,
    onExpand: () -> Unit,
    onContract: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit
): List<DockAction> = listOf(
    DockAction("Clear", Icons.Filled.Clear, description = "Deselect everything and start over.") { onClear() },
    DockAction("Invert", Icons.Filled.FlipCameraAndroid, description = "Swap the selection: everything that was NOT selected becomes selected, and vice versa.") { onInvert() },
    DockAction("Expand", Icons.Filled.OpenInFull, description = "Grow the selection outward by one pixel on every edge.") { onExpand() },
    DockAction("Contract", Icons.Filled.CloseFullscreen, description = "Shrink the selection inward by one pixel on every edge.") { onContract() },
    DockAction("Copy", Icons.Filled.ContentCopy, description = "Copy the selected pixels into a floating piece you can move, without removing the original.") { onCopy() },
    DockAction("Cut", Icons.Filled.ContentCut, description = "Lift the selected pixels out as a floating piece, removing them from the layer underneath.") { onCut() },
    DockAction("Delete", Icons.Filled.DeleteForever, description = "Erase every pixel inside the current selection.") { onDelete() }
)


/** Smart Select's explicit combine mode row (Blueprint #15) - Replace/Add/Subtract/Toggle,
 *  shown above the Clear/Invert/... row so the mode is always visible and deliberate. */
fun smartSelectModeDockActions(current: SelectionMode, onSelect: (SelectionMode) -> Unit): List<DockAction> = listOf(
    DockAction("Replace", Icons.Filled.CropSquare, current == SelectionMode.REPLACE,
        "Each new drag replaces the current selection entirely.") { onSelect(SelectionMode.REPLACE) },
    DockAction("Add", Icons.Filled.Add, current == SelectionMode.ADD,
        "Each new drag adds more pixels to the existing selection.") { onSelect(SelectionMode.ADD) },
    DockAction("Subtract", Icons.Filled.Remove, current == SelectionMode.SUBTRACT,
        "Each new drag removes pixels from the existing selection.") { onSelect(SelectionMode.SUBTRACT) },
    DockAction("Toggle", Icons.Filled.SwapHoriz, current == SelectionMode.TOGGLE,
        "Each new drag flips pixels: selected pixels become unselected and vice versa.") { onSelect(SelectionMode.TOGGLE) }
)

/** Actions available while a FloatingSelection is active (Blueprint #17): the floating piece
 *  hasn't been burned into the layer yet, so every action here either transforms it further or
 *  ends the float (Commit/Cancel/Delete).
 *
 *  [onMoveUp]/[onMoveDown]/[onMoveLeft]/[onMoveRight] (SESI 9): precise 1px nudge buttons. On a
 *  small selection (a few pixels wide), the floating piece's corner-resize handles sit right on
 *  top of / very close to each other, so a plain drag meant to MOVE the piece very easily grabs a
 *  RESIZE handle instead and scales it - these buttons give a way to move it that can never be
 *  misread as a resize, regardless of how small the piece is. */
fun floatingSelectionDockActions(
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onRotate: () -> Unit,
    onFlipH: () -> Unit,
    onFlipV: () -> Unit,
    onScaleUp: () -> Unit,
    onScaleDown: () -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
): List<DockAction> = listOf(
    DockAction("Up", Icons.Filled.KeyboardArrowUp, description = "Nudge the floating piece up by 1 pixel - safe on tiny selections where dragging would accidentally resize instead.") { onMoveUp() },
    DockAction("Down", Icons.Filled.KeyboardArrowDown, description = "Nudge the floating piece down by 1 pixel.") { onMoveDown() },
    DockAction("Left", Icons.Filled.KeyboardArrowLeft, description = "Nudge the floating piece left by 1 pixel.") { onMoveLeft() },
    DockAction("Right", Icons.Filled.KeyboardArrowRight, description = "Nudge the floating piece right by 1 pixel.") { onMoveRight() },
    DockAction("Rotate", Icons.Filled.RotateRight, description = "Rotate the floating piece 90°.") { onRotate() },
    DockAction("Flip H", Icons.Filled.Flip, description = "Mirror the floating piece left-to-right.") { onFlipH() },
    DockAction("Flip V", Icons.Filled.Flip, description = "Mirror the floating piece top-to-bottom.") { onFlipV() },
    DockAction("Scale +", Icons.Filled.OpenInFull, description = "Enlarge the floating piece.") { onScaleUp() },
    DockAction("Scale -", Icons.Filled.CloseFullscreen, description = "Shrink the floating piece.") { onScaleDown() },
    DockAction("Commit", Icons.Filled.Check, description = "Permanently merge the floating piece into the layer at its current position.") { onCommit() },
    DockAction("Delete", Icons.Filled.DeleteForever, description = "Discard the floating piece and remove it entirely.") { onDelete() },
    DockAction("Cancel", Icons.Filled.Close, description = "Cancel the float and put the pixels back where they came from, unchanged.") { onCancel() }
)

fun geometryDockActions(selected: GeometryType, onSelect: (GeometryType) -> Unit): List<DockAction> =
    GeometryType.entries.map { type ->
        val label = when(type) {
            GeometryType.CIRCLE -> "Circle"
            GeometryType.RECTANGLE -> "Box"
            GeometryType.OVAL -> "Oval"
            GeometryType.PARALLELOGRAM -> "Parallelogram"
            GeometryType.LINE -> "Line"
        }
        val icon = when(type) {
            GeometryType.CIRCLE, GeometryType.OVAL -> Icons.Filled.Circle
            GeometryType.RECTANGLE -> Icons.Filled.CropSquare
            GeometryType.PARALLELOGRAM -> Icons.Filled.ChangeHistory
            GeometryType.LINE -> Icons.Filled.Remove
        }
        val description = when(type) {
            GeometryType.CIRCLE -> "Draw a perfect circle: drag from center outward."
            GeometryType.RECTANGLE -> "Draw a rectangle/box by dragging from one corner to the opposite corner."
            GeometryType.OVAL -> "Draw an oval/ellipse by dragging across a bounding box."
            GeometryType.PARALLELOGRAM -> "Draw a slanted 4-sided shape by dragging across a bounding box."
            GeometryType.LINE -> "Draw a straight line by dragging from start point to end point."
        }
        DockAction(label, icon, selected == type, description) { onSelect(type) }
    }
