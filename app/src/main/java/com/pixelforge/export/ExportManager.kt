package com.pixelforge.export

import android.graphics.Bitmap
import com.pixelforge.core.ProjectModel
import com.pixelforge.core.StorageManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EXPORT CENTER (Blueprint #36).
 * Produces: sprites (PNG per frame), sprite sheet, atlas.png + atlas.json, metadata (frames,
 * animations, geometry JSON), CSV, and project backup (.pforge), all bundled into
 * PixelForge_Export.zip
 */
class ExportManager(private val storage: StorageManager) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun exportAll(project: ProjectModel, atlasBitmap: Bitmap?, spriteSheetBitmap: Bitmap?): File {
        val settings = project.exportSettings
        val workDir = File(storage.exportDir(project.id), "staging_${System.currentTimeMillis()}")
        workDir.deleteRecursively()
        val projectDir = File(workDir, "project").apply { mkdirs() }

        // sprites/
        if (settings.includeSprites) {
            val spritesDir = File(workDir, "sprites").apply { mkdirs() }
            project.frames.forEach { frame ->
                runCatching {
                    val bmp = storage.loadBitmap(frame.bitmapPath)
                    val baseName = sanitize(frame.name) + "_${frame.id.take(6)}"
                    writePng(bmp, File(spritesDir, "$baseName.png"))
                    File(spritesDir, "$baseName.svg").writeText(com.pixelforge.engine.SvgVectorizer.toSvg(bmp))
                }
            }
        }

        // sprite sheet (optional composite of frames laid out left-to-right for quick preview)
        if (settings.includeSpriteSheet) {
            spriteSheetBitmap?.let { writePng(it, File(workDir, "spritesheet.png")) }
        }

        // atlas/
        if (settings.includeAtlas) {
            val atlasDir = File(workDir, "atlas").apply { mkdirs() }
            atlasBitmap?.let { writePng(it, File(atlasDir, "atlas.png")) }
            File(atlasDir, "atlas.json").writeText(json.encodeToString(project.atlas))
        }

        // metadata/
        if (settings.includeJson || settings.includeCsv) {
            val metaDir = File(workDir, "metadata").apply { mkdirs() }
            if (settings.includeJson) {
                File(metaDir, "frames.json").writeText(json.encodeToString(project.frames))
                File(metaDir, "animations.json").writeText(json.encodeToString(project.animations))
                File(metaDir, "geometry.json").writeText(
                    json.encodeToString(
                        project.frames.associate { it.id to mapOf(
                            "actualBounds" to it.actualBounds,
                            "offset" to it.offset,
                            "excess" to it.excess,
                            "rotated" to it.rotated,
                            "trimMode" to it.trimMode.name
                        ) }
                    )
                )
            }
            if (settings.includeCsv) {
                File(metaDir, "frames.csv").writeText(framesToCsv(project))
            }
        }

        // project/ backup (.pforge = renamed json bundle of full project model, self-contained)
        File(projectDir, "project.pforge").writeText(json.encodeToString(project))

        // zip everything
        val zipFile = File(storage.exportDir(project.id), "PixelForge_Export.zip")
        zipDirectory(workDir, zipFile)
        workDir.deleteRecursively()
        project.exportSettings.lastExportPath = zipFile.absolutePath
        return zipFile
    }

    fun backupProject(project: ProjectModel): File {
        val file = File(storage.exportDir(project.id), "${sanitize(project.name)}.pforge")
        file.writeText(json.encodeToString(project))
        return file
    }

    fun restoreProject(file: File): ProjectModel =
        json.decodeFromString(ProjectModel.serializer(), file.readText())

    private fun framesToCsv(project: ProjectModel): String {
        val sb = StringBuilder()
        sb.append("id,name,x,y,width,height,actualLeft,actualTop,actualRight,actualBottom,offsetX,offsetY,rotated,trimMode\n")
        project.frames.forEach { f ->
            sb.append(f.id).append(',')
            sb.append(csvSafe(f.name)).append(',')
            sb.append(f.source.x).append(',').append(f.source.y).append(',')
            sb.append(f.source.width).append(',').append(f.source.height).append(',')
            sb.append(f.actualBounds?.left ?: "").append(',')
            sb.append(f.actualBounds?.top ?: "").append(',')
            sb.append(f.actualBounds?.right ?: "").append(',')
            sb.append(f.actualBounds?.bottom ?: "").append(',')
            sb.append(f.offset.x).append(',').append(f.offset.y).append(',')
            sb.append(f.rotated).append(',')
            sb.append(f.trimMode.name).append('\n')
        }
        return sb.toString()
    }

    private fun csvSafe(s: String): String = if (s.contains(",")) "\"$s\"" else s

    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9_\\-]"), "_")

    private fun writePng(bmp: Bitmap, file: File) {
        FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
    }

    private fun zipDirectory(sourceDir: File, zipFile: File) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            sourceDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val entryName = file.relativeTo(sourceDir).path
                zos.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
}
