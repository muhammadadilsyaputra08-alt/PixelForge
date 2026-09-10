package com.pixelforge.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.pixelforge.core.ExportSettings
import com.pixelforge.core.ProjectModel

/** EXPORT CENTER (Blueprint #36): atlas/metadata preview + per-artifact toggles before export. */
@Composable
fun ExportSheet(
    project: ProjectModel,
    atlasBitmap: Bitmap?,
    onExportAll: () -> Unit,
    onBackupProject: () -> Unit,
    onSettingsChange: (ExportSettings) -> Unit,
    lastExportPath: String?
) {
    val settings = project.exportSettings
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Export Center", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Sprites • Sprite Sheet • Atlas • JSON • CSV • ZIP • Project Backup",
            style = MaterialTheme.typography.bodySmall
        )

        // ---- Atlas preview ----
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2A2731)),
            contentAlignment = Alignment.Center
        ) {
            if (atlasBitmap != null) {
                Image(
                    bitmap = atlasBitmap.asImageBitmap(),
                    contentDescription = "Atlas preview",
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.fillMaxHeight()
                )
            } else {
                Text("Pack an atlas in Atlas mode to include it", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            }
        }

        // ---- Metadata summary ----
        Spacer(Modifier.height(8.dp))
        Text(
            listOfNotNull(
                "${project.frames.size} frames",
                "${project.animations.size} animations",
                if (project.atlas.width > 0) "atlas ${project.atlas.width}x${project.atlas.height}" else null
            ).joinToString("  •  "),
            style = MaterialTheme.typography.labelMedium,
            color = Color.Gray
        )

        // ---- Per-artifact toggles ----
        Spacer(Modifier.height(12.dp))
        Text("Include in export", style = MaterialTheme.typography.titleSmall)
        ExportToggleRow("Sprites (PNG + SVG)", settings.includeSprites) { onSettingsChange(settings.copy(includeSprites = it)) }
        ExportToggleRow("Sprite Sheet", settings.includeSpriteSheet) { onSettingsChange(settings.copy(includeSpriteSheet = it)) }
        ExportToggleRow("Atlas (PNG + JSON)", settings.includeAtlas) { onSettingsChange(settings.copy(includeAtlas = it)) }
        ExportToggleRow("Metadata JSON", settings.includeJson) { onSettingsChange(settings.copy(includeJson = it)) }
        ExportToggleRow("Metadata CSV", settings.includeCsv) { onSettingsChange(settings.copy(includeCsv = it)) }

        Spacer(Modifier.height(16.dp))
        Button(onClick = onExportAll, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Archive, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Export PixelForge_Export.zip")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBackupProject, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Save Project Backup (.pforge)")
        }
        if (lastExportPath != null) {
            Spacer(Modifier.height(12.dp))
            Text("Last export: $lastExportPath", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ExportToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
