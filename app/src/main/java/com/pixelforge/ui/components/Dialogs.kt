package com.pixelforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pixelforge.core.CanvasPresets

/**
 * New Project sizing dialog.
 *
 * Rewrite notes (fixes overflowing "New Project" sheet): the canvas-size chips used to sit in a
 * single non-wrapping [Row]. Once "128x128" or wider entries were added, the [Row] had no room
 * left and Compose squeezed the last [FilterChip]'s label into a 1-character-wide column,
 * stacking "1 2 8 x 1 2 8" vertically and pushing Cancel/Create off the bottom of the dialog.
 * Fixes:
 *  - Chips now live in a [FlowRow] so they wrap onto a new line instead of being squeezed.
 *  - The whole dialog body is wrapped in a height-capped, scrollable [Column] so it can never
 *    grow past the screen regardless of content (custom fields included).
 *  - Added a "Custom" chip that reveals Width/Height fields, clamped to
 *    [CanvasPresets.MIN_DIMENSION]..[CanvasPresets.MAX_DIMENSION].
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (name: String, width: Int, height: Int, transparent: Boolean) -> Unit) {
    var name by remember { mutableStateOf("New Project") }
    var size by remember { mutableStateOf<Int?>(64) } // null = custom size selected
    var customWidth by remember { mutableStateOf("64") }
    var customHeight by remember { mutableStateOf("64") }
    var transparent by remember { mutableStateOf(true) }

    fun resolvedSize(): Pair<Int, Int> {
        val s = size
        if (s != null) return s to s
        val w = (customWidth.toIntOrNull() ?: 64).coerceIn(CanvasPresets.MIN_DIMENSION, CanvasPresets.MAX_DIMENSION)
        val h = (customHeight.toIntOrNull() ?: 64).coerceIn(CanvasPresets.MIN_DIMENSION, CanvasPresets.MAX_DIMENSION)
        return w to h
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Project") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Project name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text("Canvas size", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) {
                    CanvasPresets.sizes.forEach { s ->
                        FilterChip(selected = size == s, onClick = { size = s }, label = { Text("${s}x$s") })
                    }
                    FilterChip(selected = size == null, onClick = { size = null }, label = { Text("Custom") })
                }
                if (size == null) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = customWidth,
                            onValueChange = { customWidth = it.filter(Char::isDigit).take(4) },
                            label = { Text("Width") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = customHeight,
                            onValueChange = { customHeight = it.filter(Char::isDigit).take(4) },
                            label = { Text("Height") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        "${CanvasPresets.MIN_DIMENSION}-${CanvasPresets.MAX_DIMENSION}px per side",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Transparent background", modifier = Modifier.weight(1f))
                    Switch(checked = transparent, onCheckedChange = { transparent = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (w, h) = resolvedSize()
                onCreate(name.ifBlank { "New Project" }, w, h, transparent)
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Editable color palette row (SESI 9 rewrite): previously a hardcoded, fixed list of 11 colors
 * with no way to change any of them and no connection to the Eyedropper at all - picking a color
 * from the canvas only changed the ephemeral "active drawing color", never a swatch, so the
 * palette never reflected what you'd actually picked. Now:
 * - [palette]/[selectedIndex] come from the project itself (see ProjectModel.palette) instead of
 *   a local constant, so the Eyedropper (ProjectViewModel.applyPixelTool's EYEDROPPER case) can
 *   write straight into whichever slot is selected.
 * - Tapping a swatch selects it (and makes it the active drawing color).
 * - Long-pressing a swatch opens [HexColorDialog] to set that slot's exact color by hex/RGB.
 * - A trailing "+" swatch adds a brand new slot via the same dialog.
 */
@Composable
fun ColorPickerRow(
    palette: List<Int>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onEditColor: (index: Int, argb: Int) -> Unit,
    onAddColor: (argb: Int) -> Unit,
    onRemoveColor: (Int) -> Unit
) {
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var addingNew by remember { mutableStateOf(false) }

    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 12.dp)) {
        items(palette.size) { i ->
            val c = palette[i]
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(
                        width = if (i == selectedIndex) 2.dp else 1.dp,
                        color = if (i == selectedIndex) MaterialTheme.colorScheme.primary else Color.Gray,
                        shape = CircleShape
                    )
                    .combinedClickableCompat(
                        onClick = { onSelect(i) },
                        onLongClick = { editingIndex = i }
                    )
            )
        }
        item {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.Transparent)
                    .border(width = 1.dp, color = Color.Gray, shape = CircleShape)
                    .clickable { addingNew = true },
                contentAlignment = Alignment.Center
            ) { Text("+", style = MaterialTheme.typography.titleSmall) }
        }
    }

    editingIndex?.let { idx ->
        HexColorDialog(
            initialArgb = palette.getOrElse(idx) { 0xFF000000.toInt() },
            title = "Edit Color",
            onDismiss = { editingIndex = null },
            onConfirm = { argb -> onEditColor(idx, argb); editingIndex = null },
            onRemove = if (palette.size > 1) { { onRemoveColor(idx); editingIndex = null } } else null
        )
    }
    if (addingNew) {
        HexColorDialog(
            initialArgb = palette.getOrElse(selectedIndex) { 0xFF000000.toInt() },
            title = "Add Color",
            onDismiss = { addingNew = false },
            onConfirm = { argb -> onAddColor(argb); addingNew = false },
            onRemove = null
        )
    }
}

/** Hex (#AARRGGBB / #RRGGBB) + RGB slider color editor, used both to edit an existing palette
 *  slot and to add a new one. Free-typed hex and the sliders stay in sync with each other. */
@Composable
fun HexColorDialog(
    initialArgb: Int,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    onRemove: (() -> Unit)? = null
) {
    fun toHex(argb: Int) = String.format("#%08X", argb)
    var hexText by remember { mutableStateOf(toHex(initialArgb)) }
    var r by remember { mutableStateOf((initialArgb shr 16) and 0xFF) }
    var g by remember { mutableStateOf((initialArgb shr 8) and 0xFF) }
    var b by remember { mutableStateOf(initialArgb and 0xFF) }
    var a by remember { mutableStateOf((initialArgb shr 24) and 0xFF) }
    var hexError by remember { mutableStateOf(false) }

    fun current() = (a shl 24) or (r shl 16) or (g shl 8) or b

    fun syncFromRgba() { hexText = toHex(current()); hexError = false }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(Color.LightGray.copy(alpha = 0.3f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(current()))
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { text ->
                        hexText = text
                        val cleaned = text.removePrefix("#").trim()
                        val parsed = when (cleaned.length) {
                            6 -> cleaned.toLongOrNull(16)?.let { 0xFF000000.toInt() or it.toInt() }
                            8 -> cleaned.toLongOrNull(16)?.toInt()
                            else -> null
                        }
                        if (parsed != null) {
                            hexError = false
                            a = (parsed shr 24) and 0xFF; r = (parsed shr 16) and 0xFF
                            g = (parsed shr 8) and 0xFF; b = parsed and 0xFF
                        } else hexError = true
                    },
                    label = { Text("Hex (#RRGGBB or #AARRGGBB)") },
                    isError = hexError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                ColorChannelSlider("R", r) { r = it; syncFromRgba() }
                ColorChannelSlider("G", g) { g = it; syncFromRgba() }
                ColorChannelSlider("B", b) { b = it; syncFromRgba() }
                ColorChannelSlider("A", a) { a = it; syncFromRgba() }
            }
        },
        confirmButton = {
            TextButton(enabled = !hexError, onClick = { onConfirm(current()) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                onRemove?.let { TextButton(onClick = it) { Text("Remove", color = MaterialTheme.colorScheme.error) } }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun ColorChannelSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(20.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f)
        )
        Text(value.toString(), modifier = Modifier.width(36.dp), style = MaterialTheme.typography.labelSmall)
    }
}

/** [Modifier.combinedClickable] with a plain [Modifier.clickable] fallback signature so callers
 *  above don't need to import foundation's ExperimentalFoundationApi opt-in directly. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.then(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick))

@Composable
fun CharacterReferenceDialog(
    initialLeft: Int, initialRight: Int, initialBottom: Int,
    onDismiss: () -> Unit,
    onConfirm: (left: Int, right: Int, bottom: Int) -> Unit
) {
    var left by remember { mutableStateOf(initialLeft.toString()) }
    var right by remember { mutableStateOf(initialRight.toString()) }
    var bottom by remember { mutableStateOf(initialBottom.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Character Reference") },
        text = {
            Column {
                OutlinedTextField(value = left, onValueChange = { left = it }, label = { Text("Left") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = right, onValueChange = { right = it }, label = { Text("Right") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = bottom, onValueChange = { bottom = it }, label = { Text("Bottom (foot line)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(left.toIntOrNull() ?: 0, right.toIntOrNull() ?: 0, bottom.toIntOrNull() ?: 0)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ChromaKeyDialog(
    onDismiss: () -> Unit,
    onApply: (tolerance: Int, feather: Int, fillGaps: Boolean, colorArgb: Int) -> Unit,
    initialColor: Int,
    // Non-destructive live preview (SESI 4): fired on every parameter change so the canvas
    // behind the dialog can show the transparency result before Apply is pressed. onDismiss
    // is expected to also cancel/discard the staged preview on the caller side.
    onPreview: (tolerance: Int, feather: Int, fillGaps: Boolean, colorArgb: Int) -> Unit = { _, _, _, _ -> }
) {
    var tolerance by remember { mutableStateOf(28f) }
    var feather by remember { mutableStateOf(1f) }
    var fillGaps by remember { mutableStateOf(false) }
    LaunchedEffect(tolerance, feather, fillGaps) {
        onPreview(tolerance.toInt(), feather.toInt(), fillGaps, initialColor)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chroma Key Background Removal") },
        text = {
            Column {
                Text("Tolerance: ${tolerance.toInt()}")
                Slider(value = tolerance, onValueChange = { tolerance = it }, valueRange = 0f..100f)
                Text("Feather Edge: ${feather.toInt()}")
                Slider(value = feather, onValueChange = { feather = it }, valueRange = 0f..8f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Fill Enclosed Gaps", modifier = Modifier.weight(1f))
                    Switch(checked = fillGaps, onCheckedChange = { fillGaps = it })
                }
                Text("Preview shown live on canvas behind this dialog.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(tolerance.toInt(), feather.toInt(), fillGaps, initialColor) }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun SpriteDetectionDialog(
    onDismiss: () -> Unit,
    onDetect: (tolerance: Int, fragmentMerge: Int, gridMergePct: Float, minNoise: Int, padding: Int, useAlpha: Boolean) -> Unit
) {
    var tolerance by remember { mutableStateOf(28f) }
    var fragmentMerge by remember { mutableStateOf(3f) }
    var gridMergePct by remember { mutableStateOf(35f) }
    var minNoise by remember { mutableStateOf(6f) }
    var padding by remember { mutableStateOf(1f) }
    var useAlpha by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sprite Detection") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use Alpha as background", modifier = Modifier.weight(1f))
                    Switch(checked = useAlpha, onCheckedChange = { useAlpha = it })
                }
                Text("Tolerance: ${tolerance.toInt()}")
                Slider(value = tolerance, onValueChange = { tolerance = it }, valueRange = 0f..100f, enabled = !useAlpha)
                Text("Fragment Merge Distance (px): ${fragmentMerge.toInt()}")
                Slider(value = fragmentMerge, onValueChange = { fragmentMerge = it }, valueRange = 0f..16f)
                Text("Grid-in-Grid Merge Overlap: ${gridMergePct.toInt()}%")
                Slider(value = gridMergePct, onValueChange = { gridMergePct = it }, valueRange = 0f..100f)
                Text("Minimum Noise (px²): ${minNoise.toInt()}")
                Slider(value = minNoise, onValueChange = { minNoise = it }, valueRange = 0f..64f)
                Text("Crop Padding (px): ${padding.toInt()}")
                Slider(value = padding, onValueChange = { padding = it }, valueRange = 0f..8f)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDetect(tolerance.toInt(), fragmentMerge.toInt(), gridMergePct / 100f, minNoise.toInt(), padding.toInt(), useAlpha)
            }) { Text("Detect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun NormalizationDialog(
    onDismiss: () -> Unit,
    onApply: (width: Int, height: Int, padding: Int, anchor: com.pixelforge.engine.NormalizationAnchor) -> Unit
) {
    var width by remember { mutableStateOf(128f) }
    var height by remember { mutableStateOf(128f) }
    var padding by remember { mutableStateOf(0f) }
    var anchor by remember { mutableStateOf(com.pixelforge.engine.NormalizationAnchor.BOTTOM_CENTER) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Canvas Normalization") },
        text = {
            Column {
                Text("Target Width: ${width.toInt()}")
                Slider(value = width, onValueChange = { width = it }, valueRange = 16f..512f)
                Text("Target Height: ${height.toInt()}")
                Slider(value = height, onValueChange = { height = it }, valueRange = 16f..512f)
                Text("Padding: ${padding.toInt()}")
                Slider(value = padding, onValueChange = { padding = it }, valueRange = 0f..32f)
                Spacer(Modifier.height(8.dp))
                Text("Anchor / Reference")
                val anchors = com.pixelforge.engine.NormalizationAnchor.values().toList()
                Column {
                    anchors.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { a ->
                                FilterChip(
                                    selected = anchor == a,
                                    onClick = { anchor = a },
                                    label = { Text(a.name.replace('_', ' '), style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(width.toInt(), height.toInt(), padding.toInt(), anchor) }) { Text("Normalize All Frames") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
