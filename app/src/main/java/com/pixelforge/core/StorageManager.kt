package com.pixelforge.core

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
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

    /**
     * Copies [file] (an export ZIP or .pforge backup already written under [exportDir], which is
     * app-private storage) into a public "Download/PixelForge/<project>" folder, creating that
     * folder if needed. Previously exports only lived in app-private storage - invisible to any
     * file manager and reachable only via a manual "Share" Intent - so users had no fixed place
     * to find their exports. Returns a short, user-facing path on success, or null if the copy
     * couldn't be done (falls back to the original in-app path so export itself never fails).
     */
    fun exportToPublicDownloads(projectName: String, file: File): String? {
        val subFolder = "PixelForge/${sanitizeFolderName(projectName)}"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportViaMediaStore(subFolder, file)
        } else {
            exportViaLegacyPublicFile(subFolder, file)
        }
    }

    // API 29+ (scoped storage): no runtime permission needed to add the app's own files into the
    // shared Downloads collection.
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportViaMediaStore(subFolder: String, file: File): String? = runCatching {
        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$subFolder/"
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        // Replace a previous same-named export instead of stacking "(1)", "(2)" copies each run.
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(file.name, relativePath),
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                resolver.delete(ContentUris.withAppendedId(collection, id), null, null)
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(file.name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }
        val uri = resolver.insert(collection, values) ?: return@runCatching null
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        "Download/$subFolder/${file.name}"
    }.getOrNull()

    // API 26-28 fallback: needs WRITE_EXTERNAL_STORAGE. Skips (returns null) if not granted -
    // export/backup still succeed and stay reachable via the existing Share flow.
    private fun exportViaLegacyPublicFile(subFolder: String, file: File): String? {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        return runCatching {
            @Suppress("DEPRECATION")
            val destDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), subFolder)
                .apply { mkdirs() }
            val destFile = File(destDir, file.name)
            file.copyTo(destFile, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), null, null)
            destFile.absolutePath
        }.getOrNull()
    }

    private fun mimeTypeFor(name: String) = when {
        name.endsWith(".zip") -> "application/zip"
        name.endsWith(".pforge") -> "application/json"
        else -> "application/octet-stream"
    }

    private fun sanitizeFolderName(name: String): String =
        name.ifBlank { "Untitled" }.replace(Regex("[^A-Za-z0-9 _\\-]"), "_").trim()
}
