package com.pixelforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pixelforge.core.LayerModel

/**
 * LAYERS panel (Blueprint #16): New/Duplicate/Delete/Reorder/Visibility already existed.
 * This adds the previously-missing Replace Image, Paste into Layer, Rotate 90 deg, Flip H/V, and
 * wires up Rename (the callback existed on this composable already but nothing ever invoked it).
 */
@Composable
fun LayerPanel(
    layers: List<LayerModel>,
    activeLayerId: String?,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onToggleVisible: (String, Boolean) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onRename: (String, String) -> Unit,
    onReplaceImage: (String) -> Unit = {},
    onPasteImage: (String) -> Unit = {},
    onRotate90: (String) -> Unit = {},
    onFlipHorizontal: (String) -> Unit = {},
    onFlipVertical: (String) -> Unit = {}
) {
    var renameTargetId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Layers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "New Layer") }
        }
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(layers.reversed(), key = { it.id }) { layer ->
                val realIndex = layers.indexOf(layer)
                var menuOpen by remember { mutableStateOf(false) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (layer.id == activeLayerId) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else androidx.compose.ui.graphics.Color.Transparent)
                        .padding(vertical = 4.dp)
                ) {
                    IconButton(onClick = { onToggleVisible(layer.id, !layer.visible) }) {
                        Icon(if (layer.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, contentDescription = "Toggle visibility")
                    }
                    Text(
                        layer.name,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelect(layer.id) }
                    )
                    IconButton(onClick = { onMoveUp(realIndex) }) { Icon(Icons.Filled.ArrowUpward, contentDescription = "Up") }
                    IconButton(onClick = { onMoveDown(realIndex) }) { Icon(Icons.Filled.ArrowDownward, contentDescription = "Down") }
                    IconButton(onClick = { onDuplicate(layer.id) }) { Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null) },
                                onClick = { menuOpen = false; renameTargetId = layer.id; renameText = layer.name }
                            )
                            DropdownMenuItem(
                                text = { Text("Replace Image") },
                                leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                                onClick = { menuOpen = false; onReplaceImage(layer.id) }
                            )
                            DropdownMenuItem(
                                text = { Text("Paste Image") },
                                leadingIcon = { Icon(Icons.Filled.ContentPaste, contentDescription = null) },
                                onClick = { menuOpen = false; onPasteImage(layer.id) }
                            )
                            DropdownMenuItem(
                                text = { Text("Rotate 90\u00b0") },
                                leadingIcon = { Icon(Icons.Filled.RotateRight, contentDescription = null) },
                                onClick = { menuOpen = false; onRotate90(layer.id) }
                            )
                            DropdownMenuItem(
                                text = { Text("Flip Horizontal") },
                                leadingIcon = { Icon(Icons.Filled.Flip, contentDescription = null) },
                                onClick = { menuOpen = false; onFlipHorizontal(layer.id) }
                            )
                            DropdownMenuItem(
                                text = { Text("Flip Vertical") },
                                leadingIcon = { Icon(Icons.Filled.Flip, contentDescription = null) },
                                onClick = { menuOpen = false; onFlipVertical(layer.id) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menuOpen = false; onDelete(layer.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    val targetId = renameTargetId
    if (targetId != null) {
        AlertDialog(
            onDismissRequest = { renameTargetId = null },
            title = { Text("Rename Layer") },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) onRename(targetId, renameText.trim())
                    renameTargetId = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameTargetId = null }) { Text("Cancel") } }
        )
    }
}
