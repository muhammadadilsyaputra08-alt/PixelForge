package com.pixelforge.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.pixelforge.core.AtlasModel

/** TEXTURE ATLAS panel (Blueprint #34-35). */
@Composable
fun AtlasPanel(
    atlas: AtlasModel,
    atlasBitmap: Bitmap?,
    padding: Int,
    onPaddingChange: (Int) -> Unit,
    spacing: Int,
    onSpacingChange: (Int) -> Unit,
    powerOfTwo: Boolean,
    onPowerOfTwoChange: (Boolean) -> Unit,
    alphaTrim: Boolean,
    onAlphaTrimChange: (Boolean) -> Unit,
    onPack: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text("Atlas Packer", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        LabeledStepper("Padding", padding, 0, 16) { onPaddingChange(it) }
        LabeledStepper("Sprite Spacing", spacing, 0, 16) { onSpacingChange(it) }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text("Round to Power of Two", modifier = Modifier.weight(1f))
            Switch(checked = powerOfTwo, onCheckedChange = onPowerOfTwoChange)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Alpha Trim")
                Text(
                    "Strips transparent borders before packing for a tighter atlas",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
            Switch(checked = alphaTrim, onCheckedChange = onAlphaTrimChange)
        }

        Button(onClick = onPack, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("Pack Atlas (${atlas.blocks.size} sprites)")
        }

        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2A2731)),
            contentAlignment = Alignment.Center
        ) {
            if (atlasBitmap != null) {
                Image(
                    bitmap = atlasBitmap.asImageBitmap(),
                    contentDescription = "Atlas preview",
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                    modifier = Modifier.fillMaxHeight()
                )
            } else {
                Text("No atlas packed yet", color = Color.Gray)
            }
        }
        if (atlas.width > 0) {
            Text(
                "${atlas.width} x ${atlas.height}px  •  ${atlas.blocks.size} sprites  •  padding ${atlas.padding}${if (atlas.alphaTrim) "  •  alpha-trimmed" else ""}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
fun LabeledStepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        IconButton(onClick = { if (value > min) onChange(value - 1) }) { Text("-") }
        Text(value.toString(), modifier = Modifier.width(32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { if (value < max) onChange(value + 1) }) { Text("+") }
    }
}
