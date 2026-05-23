package com.example.comicreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.util.LruCache
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
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

data class ComicImageSize(
    val width: Int,
    val height: Int
)

object ComicParser {
    private const val ZIP_CACHE_MAX_BYTES = 300L * 1024L * 1024L
    private val managedZipCachePrefixes = listOf("reader_", "cover_", "sync_cover_")
    private val pageListCacheLock = Any()
    private val folderPageListCache = mutableMapOf<String, List<Uri>>()
    private val zipPageListCache = mutableMapOf<String, List<String>>()
    private val zipBitmapCache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt().coerceAtLeast(8 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

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
        val directImageFiles = children.filter(::isComicPageImageFile)
        val childChapterCandidates = children
            .filter { child -> child.isDirectory || isArchiveFile(child.name) }
            .sortedWith(compareByNaturalName { it.name.orEmpty() })
        if (shouldIgnoreGeneratedMetadataFolder(children, directImageFiles, childChapterCandidates)) return null

        val chapters = mutableListOf<ComicChapter>()
        val hasDirectImages = directImageFiles.isNotEmpty()

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
        if (!entry.listFiles().any(::isComicPageImageFile)) return null

        return ComicChapter(
            id = entry.uri.toString(),
            name = entry.name ?: "第${fallbackIndex}话",
            sourceUri = entry.uri,
            isZip = false
        )
    }

    private fun shouldIgnoreGeneratedMetadataFolder(
        children: Array<DocumentFile>,
        directImageFiles: List<DocumentFile>,
        childChapterCandidates: List<DocumentFile>
    ): Boolean {
        if (childChapterCandidates.isNotEmpty()) return false
        if (directImageFiles.size != 1) return false
        if (!directImageFiles.first().name.equals("cover.jpg", ignoreCase = true)) return false

        return children.all { child ->
            when {
                !child.isFile -> false
                child.name.equals("cover.jpg", ignoreCase = true) -> true
                child.name.equals("source_url.txt", ignoreCase = true) -> true
                else -> false
            }
        }
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

        val cacheKey = "${folderUri}:${directory.lastModified()}:${directory.length()}"
        synchronized(pageListCacheLock) {
            folderPageListCache[cacheKey]?.let { return@withContext it }
        }

        val pages = directory.listFiles()
            .filter(::isComicPageImageFile)
            .sortedWith(compareByNaturalName { it.name.orEmpty() })
            .map { it.uri }

        synchronized(pageListCacheLock) {
            folderPageListCache[cacheKey] = pages
        }
        pages
    }

    suspend fun copyZipToCache(context: Context, zipUri: Uri, targetFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val sourceLength = DocumentFile.fromSingleUri(context, zipUri)?.length() ?: -1L
            if (targetFile.exists() && targetFile.length() > 0L && (sourceLength <= 0L || targetFile.length() == sourceLength)) {
                targetFile.setLastModified(System.currentTimeMillis())
                trimZipCache(context, ZIP_CACHE_MAX_BYTES, targetFile)
                return@withContext true
            }

            context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return@withContext false
            targetFile.setLastModified(System.currentTimeMillis())
            trimZipCache(context, ZIP_CACHE_MAX_BYTES, targetFile)
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getPagesFromZip(zipFile: File): List<String> = withContext(Dispatchers.IO) {
        try {
            val cacheKey = zipFile.cacheKey()
            synchronized(pageListCacheLock) {
                zipPageListCache[cacheKey]?.let { return@withContext it }
            }

            val pages = ZipFile(zipFile).use { zip ->
                zip.entries().toList()
                    .filter { entry -> !entry.isDirectory && isImageName(entry.name) }
                    .map { it.name }
                    .sortedWith(::compareNatural)
            }

            synchronized(pageListCacheLock) {
                zipPageListCache[cacheKey] = pages
            }
            pages
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getZipPageBitmap(zipFile: File, entryName: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val cacheKey = "${zipFile.cacheKey()}:$entryName"
            zipBitmapCache.get(cacheKey)?.let { return@withContext it }

            val bitmap = ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry(entryName) ?: return@withContext null
                zip.getInputStream(entry).use(BitmapFactory::decodeStream)
            }
            if (bitmap != null) {
                zipBitmapCache.put(cacheKey, bitmap)
            }
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getFolderPageSize(context: Context, pageUri: Uri): ComicImageSize? = withContext(Dispatchers.IO) {
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(pageUri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }
            options.toComicImageSize()
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getZipPageSize(zipFile: File, entryName: String): ComicImageSize? = withContext(Dispatchers.IO) {
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry(entryName) ?: return@withContext null
                zip.getInputStream(entry).use { inputStream ->
                    BitmapFactory.decodeStream(inputStream, null, options)
                }
            }
            options.toComicImageSize()
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    suspend fun getFolderPageRegionBitmap(
        context: Context,
        pageUri: Uri,
        top: Int,
        height: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(pageUri)?.use { inputStream ->
                val decoder = BitmapRegionDecoder.newInstance(inputStream, false) ?: return@withContext null
                try {
                    decoder.decodeRegion(
                        Rect(
                            0,
                            top.coerceAtLeast(0),
                            decoder.width,
                            (top + height).coerceAtMost(decoder.height)
                        ),
                        BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                    )
                } finally {
                    decoder.recycle()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    suspend fun getZipPageRegionBitmap(
        zipFile: File,
        entryName: String,
        top: Int,
        height: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry(entryName) ?: return@withContext null
                zip.getInputStream(entry).use { inputStream ->
                    val decoder = BitmapRegionDecoder.newInstance(inputStream, false) ?: return@withContext null
                    try {
                        decoder.decodeRegion(
                            Rect(
                                0,
                                top.coerceAtLeast(0),
                                decoder.width,
                                (top + height).coerceAtMost(decoder.height)
                            ),
                            BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                            }
                        )
                    } finally {
                        decoder.recycle()
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun File.cacheKey(): String {
        return "$absolutePath:${lastModified()}:${length()}"
    }

    private fun BitmapFactory.Options.toComicImageSize(): ComicImageSize? {
        return if (outWidth > 0 && outHeight > 0) {
            ComicImageSize(outWidth, outHeight)
        } else {
            null
        }
    }

    fun trimZipCache(context: Context, maxBytes: Long = ZIP_CACHE_MAX_BYTES) {
        trimZipCache(context, maxBytes, protectedFile = null)
    }

    private fun trimZipCache(context: Context, maxBytes: Long, protectedFile: File?) {
        val zipCacheFiles = context.cacheDir.listFiles()
            ?.filter { file -> file.isManagedZipCacheFile() }
            .orEmpty()
        var totalBytes = zipCacheFiles.sumOf { it.length() }
        if (totalBytes <= maxBytes) return

        val protectedPath = protectedFile?.absolutePath
        zipCacheFiles
            .sortedBy { it.lastModified() }
            .forEach { file ->
                if (totalBytes <= maxBytes) return
                if (file.absolutePath == protectedPath) return@forEach
                val fileBytes = file.length()
                if (file.delete()) {
                    totalBytes -= fileBytes
                }
            }
    }

    private fun File.isManagedZipCacheFile(): Boolean {
        return isFile &&
            extension.equals("zip", ignoreCase = true) &&
            managedZipCachePrefixes.any { prefix -> name.startsWith(prefix) }
    }

    private fun isArchiveFile(name: String?): Boolean {
        val lowered = name?.lowercase() ?: return false
        return lowered.endsWith(".zip") || lowered.endsWith(".cbz")
    }

    private fun isImageFile(file: DocumentFile): Boolean {
        if (!file.isFile) return false
        return isImageName(file.name)
    }

    private fun isComicPageImageFile(file: DocumentFile): Boolean {
        if (!isImageFile(file)) return false
        return !isGeneratedMetadataImageName(file.name)
    }

    private fun isImageName(name: String?): Boolean {
        val lowered = name?.lowercase() ?: return false
        return lowered.endsWith(".jpg") || lowered.endsWith(".jpeg") || lowered.endsWith(".png") || lowered.endsWith(".webp")
    }

    private fun isGeneratedMetadataImageName(name: String?): Boolean {
        return name.equals("cover.jpg", ignoreCase = true) ||
            name.equals("cover.jpeg", ignoreCase = true) ||
            name.equals("cover.png", ignoreCase = true) ||
            name.equals("cover.webp", ignoreCase = true)
    }

    private fun <T> compareByNaturalName(selector: (T) -> String): Comparator<T> {
        return Comparator { left, right -> compareNatural(selector(left), selector(right)) }
    }

    private fun compareNatural(left: String, right: String): Int {
        var leftIndex = 0
        var rightIndex = 0
        while (leftIndex < left.length && rightIndex < right.length) {
            val leftChar = left[leftIndex]
            val rightChar = right[rightIndex]
            if (leftChar.isDigit() && rightChar.isDigit()) {
                val leftStart = leftIndex
                val rightStart = rightIndex
                while (leftIndex < left.length && left[leftIndex].isDigit()) leftIndex++
                while (rightIndex < right.length && right[rightIndex].isDigit()) rightIndex++
                val leftNumber = left.substring(leftStart, leftIndex).trimStart('0').ifEmpty { "0" }
                val rightNumber = right.substring(rightStart, rightIndex).trimStart('0').ifEmpty { "0" }
                val numberCompare = BigInteger(leftNumber).compareTo(BigInteger(rightNumber))
                if (numberCompare != 0) return numberCompare
                val digitLengthCompare = (leftIndex - leftStart).compareTo(rightIndex - rightStart)
                if (digitLengthCompare != 0) return digitLengthCompare
            } else {
                val charCompare = leftChar.lowercaseChar().compareTo(rightChar.lowercaseChar())
                if (charCompare != 0) return charCompare
                leftIndex++
                rightIndex++
            }
        }
        return (left.length - leftIndex).compareTo(right.length - rightIndex)
    }
}
