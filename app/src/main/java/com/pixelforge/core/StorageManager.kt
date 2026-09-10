package com.pixelforge.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * PROJECT DATABASE + FILESYSTEM ASSET STORAGE (Blueprint #40).
 * Metadata (ProjectModel) is stored as JSON. Bitmaps are stored as PNG files on disk.
 * Nothing large is ever base64-encoded into shared preferences / localStorage-equivalent.
 */
class StorageManager(private val context: Context) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val projectsRoot: File
        get() = File(context.filesDir, "projects").apply { mkdirs() }

    fun projectDir(projectId: String): File =
        File(projectsRoot, projectId).apply { mkdirs() }

    fun assetsDir(projectId: String): File =
        File(projectDir(projectId), "assets").apply { mkdirs() }

    fun previewDir(projectId: String): File =
        File(projectDir(projectId), "preview").apply { mkdirs() }

    fun exportDir(projectId: String): File =
        File(projectDir(projectId), "export").apply { mkdirs() }

    fun listProjects(): List<ProjectModel> =
        projectsRoot.listFiles { f -> f.isDirectory }?.mapNotNull { dir ->
            runCatching { loadProject(dir.name) }.getOrNull()
        }?.sortedByDescending { it.updatedAtMillis } ?: emptyList()

    fun saveProject(project: ProjectModel) {
        project.updatedAtMillis = System.currentTimeMillis()
        val file = File(projectDir(project.id), "project.json")
        file.writeText(json.encodeToString(project))
    }

    fun loadProject(projectId: String): ProjectModel {
        val file = File(projectDir(projectId), "project.json")
        return json.decodeFromString(ProjectModel.serializer(), file.readText())
    }

    fun deleteProject(projectId: String) {
        projectDir(projectId).deleteRecursively()
    }

    fun saveBitmap(projectId: String, name: String, bitmap: Bitmap): String {
        val file = File(assetsDir(projectId), name)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file.absolutePath
    }

    /**
     * Loaded with `inMutable = true` deliberately. `BitmapFactory.decodeFile` returns an
     * IMMUTABLE bitmap by default - fine for display, but every drawing tool (pencil, eraser,
     * bucket fill, transforms) calls `Bitmap.setPixel`/`setPixels` directly on whatever
     * `loadBitmap` returns. On an immutable bitmap that throws
     * `IllegalStateException: Bitmap is immutable` the instant a tool touches it - the crash
     * right after "touching the canvas" once a project/layer/frame was loaded from disk
     * (a freshly created bitmap via PixelEngine.createBitmap is mutable already, which is why
     * drawing worked immediately after New Project but broke on any bitmap that had gone
     * through disk - reopened projects, frames, imported layers, undo/redo cache misses, etc).
     */
    fun loadBitmap(path: String): Bitmap {
        val options = BitmapFactory.Options().apply { inMutable = true }
        return BitmapFactory.decodeFile(path, options) ?: throw IllegalStateException("Bitmap not found: $path")
    }

    fun newAssetName(prefix: String): String = "${prefix}_${System.currentTimeMillis()}.png"
}
