package com.example.comicreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

data class ComicChapter(
    val id: String,
    val name: String,
    val sourceUri: Uri,
    val isZip: Boolean
)

data class ComicBook(
    val id: String,
    val name: String,
    val uri: Uri,
    val description: String,
    val chapters: List<ComicChapter>
)

object ComicParser {

    suspend fun scanBookshelf(context: Context, rootUri: Uri): List<ComicBook> = withContext(Dispatchers.IO) {
        val rootDir = DocumentFile.fromTreeUri(context, rootUri)
        if (rootDir == null || !rootDir.isDirectory) return@withContext emptyList()

        rootDir.listFiles()
            .mapNotNull { entry -> buildBook(entry) }
            .sortedBy { it.name.lowercase() }
    }

    private fun buildBook(entry: DocumentFile): ComicBook? {
        if (entry.isFile && isArchiveFile(entry.name)) {
            val chapterName = entry.name?.substringBeforeLast('.') ?: "第1话"
            return ComicBook(
                id = entry.uri.toString(),
                name = chapterName,
                uri = entry.uri,
                description = "压缩包漫画",
                chapters = listOf(
                    ComicChapter(
                        id = entry.uri.toString(),
                        name = chapterName,
                        sourceUri = entry.uri,
                        isZip = true
                    )
                )
            )
        }

        if (!entry.isDirectory) return null

        val children = entry.listFiles()
        val chapters = mutableListOf<ComicChapter>()
        val hasDirectImages = children.any(::isImageFile)
        val childChapterCandidates = children
            .filter { child -> child.isDirectory || isArchiveFile(child.name) }
            .sortedBy { it.name?.lowercase() ?: "" }

        if (hasDirectImages) {
            chapters += ComicChapter(
                id = "${entry.uri}#root",
                name = if (childChapterCandidates.isEmpty()) "正文" else "第1话",
                sourceUri = entry.uri,
                isZip = false
            )
        }

        childChapterCandidates.forEachIndexed { index, child ->
            buildChapter(child, index + if (hasDirectImages) 2 else 1)?.let(chapters::add)
        }

        if (chapters.isEmpty()) return null

        return ComicBook(
            id = entry.uri.toString(),
            name = entry.name ?: "未命名漫画",
            uri = entry.uri,
            description = if (chapters.size > 1) "共 ${chapters.size} 章" else "单章漫画",
            chapters = chapters
        )
    }

    private fun buildChapter(entry: DocumentFile, fallbackIndex: Int): ComicChapter? {
        if (entry.isFile && isArchiveFile(entry.name)) {
            return ComicChapter(
                id = entry.uri.toString(),
                name = entry.name?.substringBeforeLast('.') ?: "第${fallbackIndex}话",
                sourceUri = entry.uri,
                isZip = true
            )
        }

        if (!entry.isDirectory) return null
        if (!entry.listFiles().any(::isImageFile)) return null

        return ComicChapter(
            id = entry.uri.toString(),
            name = entry.name ?: "第${fallbackIndex}话",
            sourceUri = entry.uri,
            isZip = false
        )
    }

    suspend fun getBookCover(context: Context, book: ComicBook, cacheZipFile: File, coverIndex: Int): Any? = withContext(Dispatchers.IO) {
        val chapter = book.chapters.firstOrNull() ?: return@withContext null
        val diskCacheFile = File(context.filesDir, "thumb_${book.id.hashCode()}_v${coverIndex}.jpg")

        if (diskCacheFile.exists() && diskCacheFile.length() > 0) {
            return@withContext diskCacheFile
        }

        val rawBitmap = if (!chapter.isZip) {
            val pages = getComicPagesFromFolder(context, chapter.sourceUri)
            val targetIndex = coverIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            pages.getOrNull(targetIndex)?.let { pageUri ->
                try {
                    context.contentResolver.openInputStream(pageUri)?.use(BitmapFactory::decodeStream)
                } catch (_: Exception) {
                    null
                }
            }
        } else {
            if (copyZipToCache(context, chapter.sourceUri, cacheZipFile)) {
                val pages = getPagesFromZip(cacheZipFile)
                val targetIndex = coverIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                pages.getOrNull(targetIndex)?.let { pageName ->
                    getZipPageBitmap(cacheZipFile, pageName)
                }
            } else {
                null
            }
        }

        if (rawBitmap != null) {
            try {
                val maxDimension = 500
                val scaledBitmap = if (rawBitmap.width > maxDimension || rawBitmap.height > maxDimension) {
                    val aspectRatio = rawBitmap.width.toFloat() / rawBitmap.height.toFloat()
                    val targetWidth = if (aspectRatio > 1f) maxDimension else (maxDimension * aspectRatio).toInt().coerceAtLeast(1)
                    val targetHeight = if (aspectRatio > 1f) (maxDimension / aspectRatio).toInt().coerceAtLeast(1) else maxDimension
                    Bitmap.createScaledBitmap(rawBitmap, targetWidth, targetHeight, true)
                } else {
                    rawBitmap
                }

                FileOutputStream(diskCacheFile).use { outputStream ->
                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                }

                if (scaledBitmap != rawBitmap) {
                    scaledBitmap.recycle()
                }
                rawBitmap.recycle()
                return@withContext diskCacheFile
            } catch (_: Exception) {
                rawBitmap.recycle()
            }
        }

        null
    }

    suspend fun getComicPagesFromFolder(context: Context, folderUri: Uri): List<Uri> = withContext(Dispatchers.IO) {
        val directory = DocumentFile.fromTreeUri(context, folderUri)
            ?: DocumentFile.fromSingleUri(context, folderUri)
        if (directory == null || !directory.isDirectory) return@withContext emptyList()

        directory.listFiles()
            .filter(::isImageFile)
            .map { it.uri }
            .sortedBy { it.toString().lowercase() }
    }

    suspend fun copyZipToCache(context: Context, zipUri: Uri, targetFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return@withContext false
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getPagesFromZip(zipFile: File): List<String> = withContext(Dispatchers.IO) {
        try {
            ZipFile(zipFile).use { zip ->
                zip.entries().toList()
                    .filter { entry -> !entry.isDirectory && isImageName(entry.name) }
                    .map { it.name }
                    .sortedBy { it.lowercase() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getZipPageBitmap(zipFile: File, entryName: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry(entryName) ?: return@withContext null
                zip.getInputStream(entry).use(BitmapFactory::decodeStream)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isArchiveFile(name: String?): Boolean {
        val lowered = name?.lowercase() ?: return false
        return lowered.endsWith(".zip") || lowered.endsWith(".cbz")
    }

    private fun isImageFile(file: DocumentFile): Boolean {
        if (!file.isFile) return false
        return isImageName(file.name)
    }

    private fun isImageName(name: String?): Boolean {
        val lowered = name?.lowercase() ?: return false
        return lowered.endsWith(".jpg") || lowered.endsWith(".jpeg") || lowered.endsWith(".png") || lowered.endsWith(".webp")
    }
}
