package com.pixelforge.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pixelforge.core.ProjectModel
import com.pixelforge.ui.components.NewProjectDialog
import com.pixelforge.ui.viewmodel.ProjectViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * HOME (Blueprint #3): New Project / Recent Projects / Library. Not five separate tool
 * launchers - one entry point into the unified Project Workspace.
 *
 * New Project / Import Image run their disk + bitmap work off the main thread
 * (see ProjectViewModel); [isBusy] drives a blocking overlay here so a slow import
 * reads as "loading", not as the app being frozen, and double-taps can't fire the
 * same expensive operation twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: ProjectViewModel, onOpenProject: (String) -> Unit) {
    val recent by viewModel.recentProjects.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showNewProjectDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshRecentProjects() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            viewModel.importImageAsNewProject(uri, "Imported ${SimpleDateFormat("HHmmss", Locale.US).format(Date())}") { projectId ->
                onOpenProject(projectId)
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("PixelForge") }) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {

            Button(
                onClick = { showNewProjectDialog = true },
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("New Project")
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = { importLauncher.launch("image/*") },
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Upload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Import Image")
            }

            Spacer(Modifier.height(20.dp))
            Text("RECENT PROJECTS", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
            Spacer(Modifier.height(8.dp))

            if (recent.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Text("No projects yet. Create one to get started.", color = Color.Gray, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(recent, key = { it.id }) { project ->
                        RecentProjectRow(
                            project,
                            enabled = !isBusy,
                            onClick = { if (!isBusy) onOpenProject(project.id) },
                            onDelete = { viewModel.deleteProject(project.id) }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    if (showNewProjectDialog) {
        NewProjectDialog(
            onDismiss = { showNewProjectDialog = false },
            onCreate = { name, w, h, transparent ->
                showNewProjectDialog = false
                viewModel.newProject(name, w, h, transparent) { projectId ->
                    onOpenProject(projectId)
                }
            }
        )
    }

    if (isBusy) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center
        ) {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Working…")
                }
            }
        }
    }
}

@Composable
private fun RecentProjectRow(project: ProjectModel, enabled: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp)
    ) {
        Icon(Icons.Filled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(project.name, style = MaterialTheme.typography.titleSmall)
            Text("${project.canvas.width}x${project.canvas.height} • ${project.frames.size} frames", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        IconButton(onClick = onDelete, enabled = enabled) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
    }
}
