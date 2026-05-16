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

data class ComicBook(
    val id: String,
    val name: String,
    val isZip: Boolean,
    val uri: Uri,
    val description: String
)

object ComicParser {

    // 扫描主目录，生成书架列表
    suspend fun scanBookshelf(context: Context, rootUri: Uri): List<ComicBook> = withContext(Dispatchers.IO) {
        val rootDir = DocumentFile.fromTreeUri(context, rootUri)
        if (rootDir == null || !rootDir.isDirectory) return@withContext emptyList()

        rootDir.listFiles()
            .filter { file ->
                file.isDirectory || (file.isFile && (file.name?.endsWith(".zip", true) == true || file.name?.endsWith(".cbz", true) == true))
            }
            .map { file ->
                val isZip = file.isFile
                ComicBook(
                    id = file.uri.toString(),
                    name = file.name ?: "未命名漫画",
                    isZip = isZip,
                    uri = file.uri,
                    description = if (isZip) "本地压缩包 (ZIP)" else "本地文件夹"
                )
            }
            .sortedBy { it.name }
    }

    // ─── 工业级优化：加入永久缩略图磁盘缓存，消除白块与转圈 ───
    suspend fun getBookCover(context: Context, book: ComicBook, cacheZipFile: File, coverIndex: Int): Any? = withContext(Dispatchers.IO) {
        // 为每一本书的每一个封面页码生成一个唯一的、合法的安全文件名
        val safeId = book.id.hashCode()
        val diskCacheFile = File(context.filesDir, "thumb_${safeId}_v${coverIndex}.jpg")

        // 🎯 策略一：如果磁盘缓存存在，零延迟直接秒开本地缩略图
        if (diskCacheFile.exists() && diskCacheFile.length() > 0) {
            return@withContext diskCacheFile
        }

        // 🎯 策略二：缓存不存在，按老规矩解压解码原图
        val rawBitmap: Bitmap? = if (!book.isZip) {
            val pages = getComicPagesFromFolder(context, book.uri)
            if (pages.isNotEmpty()) {
                val target = if (coverIndex < pages.size && coverIndex >= 0) coverIndex else 0
                try {
                    context.contentResolver.openInputStream(pages[target])?.use {
                        BitmapFactory.decodeStream(it)
                    }
                } catch (e: Exception) { null }
            } else null
        } else {
            val tempCoverFile = File(context.cacheDir, "cover_${book.name}.zip")
            if (copyZipToCache(context, book.uri, tempCoverFile)) {
                val pages = getPagesFromZip(tempCoverFile)
                if (pages.isNotEmpty()) {
                    val target = if (coverIndex < pages.size && coverIndex >= 0) coverIndex else 0
                    getZipPageBitmap(tempCoverFile, pages[target])
                } else null
            } else null
        }

        // 🎯 策略三：原图解码成功后，现场压缩并写入磁盘，供下一次秒开
        if (rawBitmap != null) {
            try {
                // 如果图片尺寸太夸张，先进行物理等比例下采样，压缩分辨率
                val maxDimension = 500
                val scaledBitmap = if (rawBitmap.width > maxDimension || rawBitmap.height > maxDimension) {
                    val aspectRatio = rawBitmap.width.toFloat() / rawBitmap.height.toFloat()
                    val targetWidth = if (aspectRatio > 1) maxDimension else (maxDimension * aspectRatio).toInt()
                    val targetHeight = if (aspectRatio > 1) (maxDimension / aspectRatio).toInt() else maxDimension
                    Bitmap.createScaledBitmap(rawBitmap, targetWidth, targetHeight, true)
                } else {
                    rawBitmap
                }

                // 塞进本地 filesDir 目录（这个目录不会被系统自动清理）
                FileOutputStream(diskCacheFile).use { fos ->
                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, fos) // 80% 质量，肉眼极难分辨，但体积缩减90%
                }

                if (scaledBitmap != rawBitmap) {
                    scaledBitmap.recycle()
                }
                rawBitmap.recycle()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return@withContext diskCacheFile
        }

        return@withContext null
    }

    // 获取文件夹内所有图片 Uri
    suspend fun getComicPagesFromFolder(context: Context, folderUri: Uri): List<Uri> = withContext(Dispatchers.IO) {
        val directory = DocumentFile.fromTreeUri(context, folderUri)
        if (directory == null || !directory.isDirectory) return@withContext emptyList()

        directory.listFiles()
            .filter { file ->
                val name = file.name ?: ""
                file.isFile && (name.endsWith(".jpg", true) || name.endsWith(".png", true) || name.endsWith(".webp", true))
            }
            .map { file -> file.uri }
            .sortedBy { it.toString() }
    }

    // 复制压缩包到缓存
    suspend fun copyZipToCache(context: Context, zipUri: Uri, targetFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
                FileOutputStream(targetFile).use { outputStream -> inputStream.copyTo(outputStream) }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // 获取压缩包内图片列表
    suspend fun getPagesFromZip(zipFile: File): List<String> = withContext(Dispatchers.IO) {
        try {
            ZipFile(zipFile).use { zip ->
                zip.entries().toList()
                    .filter { !it.isDirectory && (it.name.endsWith(".jpg", true) || it.name.endsWith(".png", true) || it.name.endsWith(".webp", true)) }
                    .map { it.name }
                    .sorted()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // 动态解压单张图片
    suspend fun getZipPageBitmap(zipFile: File, entryName: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry(entryName) ?: return@withContext null
                zip.getInputStream(entry).use { BitmapFactory.decodeStream(it) }
            }
        } catch (e: Exception) {
            null
        }
    }
}