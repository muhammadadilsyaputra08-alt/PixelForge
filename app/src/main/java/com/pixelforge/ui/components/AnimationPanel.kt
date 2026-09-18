package com.pixelforge.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.pixelforge.core.AnimationModel
import com.pixelforge.core.FrameModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** ANIMATION panel (Blueprint #28-32): list of named animations + frame timeline + preview playback. */
@Composable
fun AnimationListRow(
    animations: List<AnimationModel>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<AnimationModel?>(null) }
    var deleteTarget by remember { mutableStateOf<AnimationModel?>(null) }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(animations, key = { it.id }) { anim ->
                // Blueprint #28-29: animations need a custom name (Idle/Walk/Attack/...) and
                // must be deletable. Previously the chip only let you SELECT an animation - the
                // name was fixed at creation-time text ("Animation N") and deleteAnimation()
                // existed in the ViewModel but nothing in the UI ever called it.
                InputChip(
                    selected = anim.id == activeId,
                    onClick = { onSelect(anim.id) },
                    label = { Text(anim.name) },
                    trailingIcon = {
                        Row {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Rename ${anim.name}",
                                modifier = Modifier.size(16.dp).clickable { renameTarget = anim }
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Delete ${anim.name}",
                                modifier = Modifier.size(16.dp).clickable { deleteTarget = anim }
                            )
                        }
                    }
                )
            }
        }
        IconButton(onClick = { showCreateDialog = true }) { Icon(Icons.Filled.Add, contentDescription = "New Animation") }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("Animation ${animations.size + 1}") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New Animation") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name (e.g. Idle, Walk, Attack)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = { onCreate(name.ifBlank { "Animation ${animations.size + 1}" }); showCreateDialog = false }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") } }
        )
    }

    renameTarget?.let { anim ->
        var name by remember(anim.id) { mutableStateOf(anim.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Animation") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = { if (name.isNotBlank()) onRename(anim.id, name.trim()); renameTarget = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }

    deleteTarget?.let { anim ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${anim.name}\"?") },
            text = { Text("This only removes the animation sequence, not the frames themselves.") },
            confirmButton = {
                TextButton(onClick = { onDelete(anim.id); deleteTarget = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

/**
 * Timeline strip (Blueprint #29): long-press + drag a thumbnail sideways to reorder it, tap to
 * remove. Item size is fixed (64dp cell + 6dp spacing) so the drag position can be mapped
 * straight to a target index without needing per-item layout coordinates.
 */
@Composable
fun AnimationTimeline(
    animation: AnimationModel,
    frames: List<FrameModel>,
    bitmapFor: (FrameModel) -> Bitmap,
    onReorder: (Int, Int) -> Unit,
    onRemove: (String) -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val itemSizePx = with(density) { 64.dp.toPx() }
    val spacingPx = with(density) { 6.dp.toPx() }
    val cellPx = itemSizePx + spacingPx

    val orderedFrames = animation.frameIds.mapNotNull { fid -> frames.find { it.id == fid } }

    // Index currently being dragged + its live horizontal offset (in px) from its resting slot.
    var draggingIndex by remember(animation.id) { mutableStateOf(-1) }
    var dragOffsetX by remember(animation.id) { mutableStateOf(0f) }

    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 12.dp)) {
        itemsIndexed(orderedFrames, key = { _, f -> f.id }) { index, frame ->
            val isDragging = index == draggingIndex
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        translationX = if (isDragging) dragOffsetX else 0f
                        scaleX = if (isDragging) 1.08f else 1f
                        scaleY = if (isDragging) 1.08f else 1f
                    }
                    .clip(RoundedCornerShape(6.dp))
                    .border(
                        1.dp,
                        if (isDragging) Color.White.copy(alpha = 0.8f) else Color.Gray.copy(alpha = 0.4f),
                        RoundedCornerShape(6.dp)
                    )
                    .background(Color(0xFF2A2731))
                    .clickable(enabled = draggingIndex < 0) { onRemove(frame.id) }
                    .pointerInput(animation.id, orderedFrames.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingIndex = index
                                dragOffsetX = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetX += dragAmount.x
                                val from = draggingIndex
                                if (from < 0) return@detectDragGesturesAfterLongPress
                                val shift = (dragOffsetX / cellPx).roundToInt()
                                val to = (from + shift).coerceIn(0, orderedFrames.lastIndex)
                                if (to != from) {
                                    onReorder(from, to)
                                    draggingIndex = to
                                    dragOffsetX -= (to - from) * cellPx
                                }
                            },
                            onDragEnd = {
                                draggingIndex = -1
                                dragOffsetX = 0f
                            },
                            onDragCancel = {
                                draggingIndex = -1
                                dragOffsetX = 0f
                            }
                        )
                    }
            ) {
                Image(
                    bitmap = bitmapFor(frame).asImageBitmap(),
                    contentDescription = frame.name,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                    modifier = Modifier.fillMaxSize().padding(4.dp)
                )
                Text(
                    "${index + 1}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(bottomEnd = 4.dp))
                        .padding(horizontal = 3.dp)
                )
            }
        }
    }
}

/** Preview playback: Previous / Play / Next + FPS slider (Blueprint #31), stable anchor via sourceSize+offset. */
@Composable
fun AnimationPreview(
    animation: AnimationModel,
    frames: List<FrameModel>,
    bitmapFor: (FrameModel) -> Bitmap,
    onFpsChange: (Int) -> Unit,
    onLoopChange: (Boolean) -> Unit = {}
) {
    val orderedFrames = animation.frameIds.mapNotNull { fid -> frames.find { it.id == fid } }
    var index by remember(animation.id) { mutableStateOf(0) }
    var playing by remember(animation.id) { mutableStateOf(false) }

    LaunchedEffect(playing, animation.fps, animation.id, orderedFrames.size) {
        if (!playing || orderedFrames.isEmpty()) return@LaunchedEffect
        while (playing) {
            delay(1000L / animation.fps.coerceAtLeast(1))
            index = if (index + 1 >= orderedFrames.size) {
                if (animation.loop) 0 else { playing = false; index }
            } else index + 1
        }
    }

    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2A2731)),
            contentAlignment = Alignment.Center
        ) {
            if (orderedFrames.isNotEmpty()) {
                val current = orderedFrames[index.coerceIn(0, orderedFrames.size - 1)]
                Image(
                    bitmap = bitmapFor(current).asImageBitmap(),
                    contentDescription = current.name,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                    modifier = Modifier.fillMaxHeight()
                )
            } else {
                Text("No frames in this animation", color = Color.Gray)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            IconButton(onClick = { if (orderedFrames.isNotEmpty()) index = (index - 1 + orderedFrames.size) % orderedFrames.size }) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
            }
            IconButton(onClick = { playing = !playing }) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "Play")
            }
            IconButton(onClick = { if (orderedFrames.isNotEmpty()) index = (index + 1) % orderedFrames.size }) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next")
            }
            Spacer(Modifier.width(8.dp))
            Text("FPS ${animation.fps}")
            Spacer(Modifier.weight(1f))
            Text("Loop", modifier = Modifier.padding(end = 4.dp))
            Switch(checked = animation.loop, onCheckedChange = onLoopChange)
        }
        Slider(
            value = animation.fps.toFloat(),
            onValueChange = { onFpsChange(it.toInt()) },
            valueRange = 1f..30f,
            steps = 28
        )
    }
}
