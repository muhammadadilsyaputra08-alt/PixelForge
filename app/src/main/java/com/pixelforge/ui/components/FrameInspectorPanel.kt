package com.pixelforge.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pixelforge.core.FrameModel
import com.pixelforge.core.OffsetMode
import com.pixelforge.core.TrimMode

/**
 * FRAME INSPECTOR (Blueprint #22, SESI 5 "Frame Editor & Reference System"): the dedicated
 * panel shown in WorkspaceMode.FRAME. Extends what GeometryPanel already surfaced (actualBounds/
 * excess/trimMode/rotated) with the pieces that mode didn't have a home for yet - a manual X/Y
 * override for offset, the Confidence Level readout, a Reference Guides summary (the actual drag
 * interaction lives on PixelCanvas via onReferenceDrag - this panel just reflects/clears it), and
 * a real "Apply Trim" action wired to FrameEngine.applyTrim.
 */
@Composable
fun FrameInspectorPanel(
    frame: FrameModel,
    onOffsetModeChange: (OffsetMode) -> Unit,
    onManualOffsetChange: (x: Int, y: Int) -> Unit,
    onClearReference: () -> Unit,
    onTrimModeChange: (TrimMode) -> Unit,
    onApplyTrim: () -> Unit,
    onRotatedChange: (Boolean) -> Unit,
    onRecalculate: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Frame Editor — ${frame.name}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onRecalculate) { Text("Recalculate") }
        }
        Spacer(Modifier.height(8.dp))

        // ---- Actual Bounds + Confidence ----
        val bounds = frame.actualBounds
        InspectorRow("Actual Bounds", if (bounds != null) "${bounds.left},${bounds.top} → ${bounds.right},${bounds.bottom} (${bounds.width}×${bounds.height})" else "— (fully transparent)")
        InspectorRow("Source Size", "${frame.sourceWidth} × ${frame.sourceHeight}")
        ConfidenceRow(frame.confidence)

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        // ---- Offset: Auto / Manual ----
        Text("Offset", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            OffsetMode.entries.forEachIndexed { index, m ->
                SegmentedButton(
                    selected = frame.offsetMode == m,
                    onClick = { onOffsetModeChange(m) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = OffsetMode.entries.size)
                ) { Text(m.name) }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (frame.offsetMode == OffsetMode.MANUAL) {
            var xText by remember(frame.id, frame.offset.x) { mutableStateOf(frame.offset.x.toString()) }
            var yText by remember(frame.id, frame.offset.y) { mutableStateOf(frame.offset.y.toString()) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = xText,
                    onValueChange = { v ->
                        xText = v.filter { it.isDigit() || it == '-' }
                        xText.toIntOrNull()?.let { onManualOffsetChange(it, yText.toIntOrNull() ?: frame.offset.y) }
                    },
                    label = { Text("Offset X") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = yText,
                    onValueChange = { v ->
                        yText = v.filter { it.isDigit() || it == '-' }
                        yText.toIntOrNull()?.let { onManualOffsetChange(xText.toIntOrNull() ?: frame.offset.x, it) }
                    },
                    label = { Text("Offset Y") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }
        } else {
            InspectorRow("Offset (auto)", "x=${frame.offset.x}, y=${frame.offset.y}")
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        // ---- Reference Guides ----
        Text("Reference Guides", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        val ref = frame.manualReference
        if (ref != null) {
            InspectorRow("Left / Right / Bottom", "L=${ref.left}  R=${ref.right}  B=${ref.bottom}")
            Text(
                "Drag the dashed lines directly on the canvas to adjust.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onClearReference) { Text("Clear (use global reference)") }
        } else {
            Text(
                "Using the project's global character reference. Drag a dashed line on the canvas to set a per-frame override.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        InspectorRow("Excess (vs reference)", "left=${frame.excess.left}, right=${frame.excess.right}, bottom=${frame.excess.bottom}")

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        // ---- Trim & Rotation ----
        Text("Trim Mode", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            TrimMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = frame.trimMode == mode,
                    onClick = { onTrimModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = TrimMode.entries.size)
                ) { Text(mode.name.replace('_', ' ')) }
            }
        }
        if (frame.trimMode == TrimMode.MANUAL) {
            Spacer(Modifier.height(8.dp))
            val alreadyTight = bounds != null && bounds.left == 0 && bounds.top == 0 &&
                bounds.right == frame.sourceWidth && bounds.bottom == frame.sourceHeight
            Button(onClick = onApplyTrim, enabled = bounds != null && !alreadyTight, modifier = Modifier.fillMaxWidth()) {
                Text(if (alreadyTight) "Already trimmed" else "Apply Trim (crop to Actual Bounds)")
            }
            Text(
                "Crops the frame's bitmap permanently to its actual bounds. Recorded in Undo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Rotated (atlas)", modifier = Modifier.weight(1f))
            Switch(checked = frame.rotated, onCheckedChange = onRotatedChange)
        }
    }
}

@Composable
private fun ConfidenceRow(confidence: Float) {
    val pct = (confidence * 100).toInt()
    val (label, color) = when {
        confidence >= 0.8f -> "High" to Color(0xFF4CAF50)
        confidence >= 0.5f -> "Medium" to Color(0xFFFFB340)
        else -> "Low" to Color(0xFFE05252)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Confidence Level", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        if (confidence < 0.5f) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text("$pct% ($label)", style = MaterialTheme.typography.bodySmall, color = color, textAlign = TextAlign.End)
    }
}

@Composable
private fun InspectorRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
