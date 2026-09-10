package com.pixelforge.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pixelforge.core.FrameModel
import com.pixelforge.core.TrimMode

/**
 * GEOMETRY INSPECTOR (Blueprint #22-23, #26-27): surfaces the actualBounds / offset / excess /
 * rotated / trimMode that GeometryEngine + FrameEngine already computed on every detection and
 * every Character Reference change, but which no screen ever displayed. Also exposes the Trim
 * Mode selector (NO TRIM / REFERENCE / MANUAL) and the Rotated flag toggle from the same section
 * of the blueprint.
 */
@Composable
fun GeometryPanel(
    frame: FrameModel,
    onTrimModeChange: (TrimMode) -> Unit,
    onRotatedChange: (Boolean) -> Unit,
    onRecalculate: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Geometry — ${frame.name}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onRecalculate) { Text("Recalculate") }
        }
        Spacer(Modifier.height(8.dp))

        val bounds = frame.actualBounds
        GeometryRow("Actual Bounds", if (bounds != null) "${bounds.left},${bounds.top} → ${bounds.right},${bounds.bottom} (${bounds.width}×${bounds.height})" else "— (fully transparent)")
        GeometryRow("Source Size", "${frame.sourceWidth} × ${frame.sourceHeight}")
        GeometryRow("Offset", "x=${frame.offset.x}, y=${frame.offset.y}")
        frame.manualReference?.let { r ->
            GeometryRow("Manual Reference", "L=${r.left} R=${r.right} B=${r.bottom}")
        }
        GeometryRow("Excess (vs reference)", "left=${frame.excess.left}, right=${frame.excess.right}, bottom=${frame.excess.bottom}")
        GeometryRow("Confidence", "${(frame.confidence * 100).toInt()}%")

        Spacer(Modifier.height(12.dp))
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

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Rotated (atlas)", modifier = Modifier.weight(1f))
            Switch(checked = frame.rotated, onCheckedChange = onRotatedChange)
        }
    }
}

@Composable
private fun GeometryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
