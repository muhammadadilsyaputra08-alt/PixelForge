package com.pixelforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pixelforge.core.CanvasPresets

@Composable
fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (name: String, width: Int, height: Int, transparent: Boolean) -> Unit) {
    var name by remember { mutableStateOf("New Project") }
    var size by remember { mutableStateOf(64) }
    var transparent by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Project") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Project name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text("Canvas size", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    CanvasPresets.sizes.forEach { s ->
                        FilterChip(selected = size == s, onClick = { size = s }, label = { Text("${s}x$s") })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Transparent background", modifier = Modifier.weight(1f))
                    Switch(checked = transparent, onCheckedChange = { transparent = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.ifBlank { "New Project" }, size, size, transparent) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ColorPickerRow(selectedColor: Int, onColorSelected: (Int) -> Unit) {
    val palette = listOf(
        0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFE74C3C.toInt(), 0xFFE67E22.toInt(),
        0xFFF1C40F.toInt(), 0xFF2ECC71.toInt(), 0xFF1ABC9C.toInt(), 0xFF3498DB.toInt(),
        0xFF9B59B6.toInt(), 0xFF34495E.toInt(), 0xFF95A5A6.toInt(), 0x00000000
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 12.dp)) {
        items(palette.size) { i ->
            val c = palette[i]
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(
                        width = if (c == selectedColor) 2.dp else 1.dp,
                        color = if (c == selectedColor) MaterialTheme.colorScheme.primary else Color.Gray,
                        shape = CircleShape
                    )
                    .clickable { onColorSelected(c) }
            )
        }
    }
}

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
