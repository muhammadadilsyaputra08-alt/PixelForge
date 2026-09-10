package com.pixelforge.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.pixelforge.core.FrameModel

/**
 * FRAME BANK grid (Blueprint #21). Each card supports (SESI 8): Duplicate, Remove, and a
 * per-card canvas Resize via a small overflow menu, in addition to tap-to-select/open.
 * [onDuplicate]/[onDelete]/[onResize] are optional - callers that don't need bank management
 * here (e.g. a lightweight "add frames to animation" picker) can omit them and the overflow
 * button simply doesn't render for that instance.
 */
@Composable
fun FrameBankPanel(
    frames: List<FrameModel>,
    activeFrameId: String?,
    thumbnails: (FrameModel) -> Bitmap,
    onSelect: (String) -> Unit,
    onDuplicate: ((String) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
    onResize: ((frameId: String, width: Int, height: Int) -> Unit)? = null,
    maxDimension: Int = com.pixelforge.core.CanvasPresets.MAX_DIMENSION
) {
    var menuTarget by remember { mutableStateOf<FrameModel?>(null) }
    var deleteTarget by remember { mutableStateOf<FrameModel?>(null) }
    var resizeTarget by remember { mutableStateOf<FrameModel?>(null) }
    val showOverflow = onDuplicate != null || onDelete != null || onResize != null

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.heightIn(max = 400.dp)
    ) {
        items(frames, key = { it.id }) { frame ->
            val bmp = thumbnails(frame)
            Box(modifier = Modifier.aspectRatio(1f)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .border(
                            width = if (frame.id == activeFrameId) 2.dp else 1.dp,
                            color = if (frame.id == activeFrameId) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .background(Color(0xFF2A2731))
                        .clickable { onSelect(frame.id) }
                        .padding(4.dp)
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = frame.name,
                        contentScale = ContentScale.Fit,
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                    Text(frame.name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                if (showOverflow) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.45f))
                    ) {
                        IconButton(onClick = { menuTarget = frame }, modifier = Modifier.size(22.dp)) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Frame options", tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                        DropdownMenu(expanded = menuTarget?.id == frame.id, onDismissRequest = { menuTarget = null }) {
                            if (onDuplicate != null) {
                                DropdownMenuItem(
                                    text = { Text("Duplicate") },
                                    leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                                    onClick = { onDuplicate(frame.id); menuTarget = null }
                                )
                            }
                            if (onResize != null) {
                                DropdownMenuItem(
                                    text = { Text("Resize canvas") },
                                    leadingIcon = { Icon(Icons.Filled.AspectRatio, contentDescription = null) },
                                    onClick = { resizeTarget = frame; menuTarget = null }
                                )
                            }
                            if (onDelete != null) {
                                DropdownMenuItem(
                                    text = { Text("Remove", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { deleteTarget = frame; menuTarget = null }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { frame ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Remove \"${frame.name}\"?") },
            text = { Text("This removes the frame from the Frame Bank and from any animation using it.") },
            confirmButton = {
                TextButton(onClick = { onDelete?.invoke(frame.id); deleteTarget = null }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }

    resizeTarget?.let { frame ->
        FrameResizeDialog(
            frameName = frame.name,
            currentWidth = frame.sourceWidth,
            currentHeight = frame.sourceHeight,
            maxDimension = maxDimension,
            onDismiss = { resizeTarget = null },
            onApply = { w, h -> onResize?.invoke(frame.id, w, h); resizeTarget = null }
        )
    }
}

/** Width/height entry dialog for a single Frame Bank card's own canvas (SESI 8) - resizes just
 *  that one frame's bitmap, independent of the project canvas and every other frame. */
@Composable
fun FrameResizeDialog(
    frameName: String,
    currentWidth: Int,
    currentHeight: Int,
    maxDimension: Int,
    onDismiss: () -> Unit,
    onApply: (Int, Int) -> Unit
) {
    var width by remember(frameName) { mutableStateOf(currentWidth.toString()) }
    var height by remember(frameName) { mutableStateOf(currentHeight.toString()) }
    val outOfRange = (width.toIntOrNull() ?: 0) !in 1..maxDimension || (height.toIntOrNull() ?: 0) !in 1..maxDimension
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resize \"$frameName\"") },
        text = {
            Column {
                Text(
                    "Resizes only this frame's own canvas - other frames and the project canvas are unaffected.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = width, onValueChange = { width = it.filter { c -> c.isDigit() } },
                    label = { Text("Width") }, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("1–$maxDimension px") }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = height, onValueChange = { height = it.filter { c -> c.isDigit() } },
                    label = { Text("Height") }, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("1–$maxDimension px") }
                )
                if (outOfRange) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Please stay within 1–$maxDimension px.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !outOfRange,
                onClick = {
                    val w = (width.toIntOrNull() ?: currentWidth).coerceIn(1, maxDimension)
                    val h = (height.toIntOrNull() ?: currentHeight).coerceIn(1, maxDimension)
                    onApply(w, h)
                }
            ) { Text("Resize") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
