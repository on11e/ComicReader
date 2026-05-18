package com.example.comicreader

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
private data class ReaderSession(
    val book: ComicBook,
    val initialChapterIndex: Int
)

internal data class BookMetadata(
    val customName: String,
    val customDesc: String,
    val tags: List<String>,
    val coverPage: Int,
    val autoNextChapter: Boolean,
    val openExternally: Boolean,
    val externalUrl: String,
    val customCoverUri: String
)

internal data class ExternalBookEntry(
    val id: String,
    val seedName: String
)

internal object NoCoverImage

internal const val EXTERNAL_BOOK_ID_PREFIX = "external_book::"
private const val EXTERNAL_BOOKS_PREF_KEY = "external_books"

@Composable
internal fun MainAppScreen() {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("comic_progress_prefs", Context.MODE_PRIVATE) }

    var rootFolderUri by remember { mutableStateOf<Uri?>(null) }
    var localBooks by remember { mutableStateOf<List<ComicBook>>(emptyList()) }
    var selectedBook by remember { mutableStateOf<ComicBook?>(null) }
    var currentReaderSession by remember { mutableStateOf<ReaderSession?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var showAddExternalDialog by remember { mutableStateOf(false) }

    fun refreshBookshelf(rescanLocal: Boolean = true) {
        refreshTrigger++
        if (rescanLocal && rootFolderUri != null) {
            isScanning = true
        }
    }

    val libraryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: Exception) {
            }
            rootFolderUri = uri
            selectedBook = null
            currentReaderSession = null
            sharedPrefs.edit { putString("saved_root_folder_uri", uri.toString()) }
            isScanning = true
        }
    }

    LaunchedEffect(Unit) {
        val savedUri = sharedPrefs.getString("saved_root_folder_uri", null)
        if (savedUri != null) {
            rootFolderUri = Uri.parse(savedUri)
            isScanning = true
        }
    }

    LaunchedEffect(rootFolderUri, isScanning, refreshTrigger) {
        if (!isScanning) return@LaunchedEffect

        localBooks = if (rootFolderUri != null) {
            ComicParser.scanBookshelf(context, rootFolderUri!!)
        } else {
            emptyList()
        }
        isScanning = false
    }

    val externalBooks = remember(refreshTrigger) {
        loadExternalBooks(sharedPrefs)
    }
    val bookshelf = remember(localBooks, externalBooks, refreshTrigger) {
        (localBooks + externalBooks).sortedBy { book ->
            readBookMetadata(sharedPrefs, book).customName.lowercase()
        }
    }
    LaunchedEffect(bookshelf, selectedTag) {
        selectedTag = selectedTag?.takeIf { tag ->
            bookshelf.any { book -> readBookMetadata(sharedPrefs, book).tags.contains(tag) }
        }
        selectedBook = selectedBook?.takeIf { current ->
            bookshelf.any { it.id == current.id }
        }
    }

    val metadataMap = remember(bookshelf, refreshTrigger) {
        bookshelf.associate { book -> book.id to readBookMetadata(sharedPrefs, book) }
    }
    val allTags = remember(metadataMap) {
        metadataMap.values.flatMap { it.tags }.distinct().sorted()
    }
    val filteredBooks = remember(bookshelf, selectedTag, metadataMap) {
        bookshelf.filter { book ->
            selectedTag == null || metadataMap[book.id]?.tags?.contains(selectedTag) == true
        }
    }
    LaunchedEffect(rootFolderUri, bookshelf, metadataMap) {
        val folderUri = rootFolderUri ?: return@LaunchedEffect
        syncBookshelfAssets(context, folderUri, bookshelf, metadataMap)
    }

    when {
        currentReaderSession != null -> ComicReaderScreen(
            book = currentReaderSession!!.book,
            initialChapterIndex = currentReaderSession!!.initialChapterIndex,
            onBack = { currentReaderSession = null }
        )
        selectedBook != null -> BookDetailScreen(
            book = selectedBook!!,
            metadata = metadataMap[selectedBook!!.id] ?: readBookMetadata(sharedPrefs, selectedBook!!),
            onBack = { selectedBook = null },
            onEditFinished = { refreshBookshelf(rescanLocal = false) },
            onReadChapter = { chapterIndex ->
                currentReaderSession = ReaderSession(selectedBook!!, chapterIndex)
            }
        )
        else -> BookshelfScreen(
            books = filteredBooks,
            metadataMap = metadataMap,
            allTags = allTags,
            selectedTag = selectedTag,
            isScanning = isScanning,
            hasLibraryFolder = rootFolderUri != null,
            onTagSelected = { selectedTag = it },
            onBookClick = { book ->
                selectedBook = book
            },
            onRefreshMetadata = { refreshBookshelf(rescanLocal = false) },
            onDeleteBook = { book ->
                val deleted = deleteComicBook(context, sharedPrefs, book)
                if (deleted) {
                    selectedBook = selectedBook?.takeIf { it.id != book.id }
                    currentReaderSession = currentReaderSession?.takeIf { it.book.id != book.id }
                    refreshBookshelf()
                }
                deleted
            },
            onPickFolder = { libraryLauncher.launch(null) },
            onAddExternalBook = {
                if (rootFolderUri == null) {
                    Toast.makeText(context, "请先选择漫画目录", Toast.LENGTH_SHORT).show()
                } else {
                    showAddExternalDialog = true
                }
            }
        )
    }

    if (showAddExternalDialog) {
        ExternalBookEditorDialog(
            onDismiss = { showAddExternalDialog = false },
            onSaved = {
                showAddExternalDialog = false
                refreshBookshelf(rescanLocal = false)
            }
        )
    }
}
internal fun readBookMetadata(sharedPrefs: SharedPreferences, book: ComicBook): BookMetadata {
    val savedTags = sharedPrefs.getString("${book.id}_tags", null)
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        .orEmpty()
    val isExternalOnly = if (isExternalBookId(book.id)) {
        sharedPrefs.getBoolean("${book.id}_external_only", true) ||
            sharedPrefs.getBoolean("${book.id}_open_externally", true)
    } else {
        sharedPrefs.getBoolean("${book.id}_external_only", false)
    }

    return BookMetadata(
        customName = sharedPrefs.getString("${book.id}_custom_name", book.name) ?: book.name,
        customDesc = sharedPrefs.getString("${book.id}_custom_desc", defaultDescription(book)) ?: defaultDescription(book),
        tags = savedTags,
        coverPage = sharedPrefs.getInt("${book.id}_cover_index", 1).coerceAtLeast(1),
        autoNextChapter = sharedPrefs.getBoolean("${book.id}_auto_next", false),
        openExternally = isExternalOnly,
        externalUrl = sharedPrefs.getString("${book.id}_external_url", "")?.trim().orEmpty(),
        customCoverUri = sharedPrefs.getString("${book.id}_custom_cover_uri", "")?.trim().orEmpty()
    )
}

private fun loadExternalBooks(sharedPrefs: SharedPreferences): List<ComicBook> {
    return loadExternalBookEntries(sharedPrefs).map { entry ->
        ComicBook(
            id = entry.id,
            name = sharedPrefs.getString("${entry.id}_custom_name", entry.seedName) ?: entry.seedName,
            uri = Uri.EMPTY,
            description = sharedPrefs.getString("${entry.id}_custom_desc", "外链漫画") ?: "外链漫画",
            chapters = emptyList()
        )
    }
}

private fun loadExternalBookEntries(sharedPrefs: SharedPreferences): List<ExternalBookEntry> {
    val raw = sharedPrefs.getString(EXTERNAL_BOOKS_PREF_KEY, null).orEmpty()
    if (raw.isBlank()) return emptyList()

    return try {
        val jsonArray = JSONArray(raw)
        buildList {
            for (index in 0 until jsonArray.length()) {
                val item = jsonArray.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").trim()
                if (id.isNotBlank() && name.isNotBlank()) {
                    add(ExternalBookEntry(id = id, seedName = name))
                }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

internal fun upsertExternalBookEntry(sharedPrefs: SharedPreferences, entry: ExternalBookEntry) {
    val currentEntries = loadExternalBookEntries(sharedPrefs).toMutableList()
    val existingIndex = currentEntries.indexOfFirst { it.id == entry.id }
    if (existingIndex >= 0) {
        currentEntries[existingIndex] = entry
    } else {
        currentEntries += entry
    }

    val jsonArray = JSONArray()
    currentEntries.forEach { current ->
        jsonArray.put(
            JSONObject().apply {
                put("id", current.id)
                put("name", current.seedName)
            }
        )
    }
    sharedPrefs.edit { putString(EXTERNAL_BOOKS_PREF_KEY, jsonArray.toString()) }
}

private fun removeExternalBookEntry(sharedPrefs: SharedPreferences, bookId: String) {
    val jsonArray = JSONArray()
    loadExternalBookEntries(sharedPrefs)
        .filterNot { it.id == bookId }
        .forEach { current ->
            jsonArray.put(
                JSONObject().apply {
                    put("id", current.id)
                    put("name", current.seedName)
                }
            )
        }
    sharedPrefs.edit { putString(EXTERNAL_BOOKS_PREF_KEY, jsonArray.toString()) }
}

private suspend fun deleteComicBook(
    context: Context,
    sharedPrefs: SharedPreferences,
    book: ComicBook
): Boolean = withContext(Dispatchers.IO) {
    if (isExternalBookId(book.id)) {
        removeExternalBookEntry(sharedPrefs, book.id)
        clearBookMetadata(sharedPrefs, book.id)
        return@withContext true
    }

    val deleted = DocumentFile.fromSingleUri(context, book.uri)?.delete() == true
    if (deleted) {
        clearBookMetadata(sharedPrefs, book.id)
    }
    deleted
}

private fun clearBookMetadata(sharedPrefs: SharedPreferences, bookId: String) {
    sharedPrefs.edit {
        remove("${bookId}_custom_name")
        remove("${bookId}_custom_desc")
        remove("${bookId}_tags")
        remove("${bookId}_cover_index")
        remove("${bookId}_auto_next")
        remove("${bookId}_external_only")
        remove("${bookId}_open_externally")
        remove("${bookId}_external_url")
        remove("${bookId}_custom_cover_uri")
        remove("${bookId}_last_chapter_id")
    }
}

internal fun saveCoverToAppStorage(context: Context, sourceUri: Uri): String? {
    return try {
        val coverDirectory = File(context.filesDir, "external_covers").apply { mkdirs() }
        val targetFile = File(coverDirectory, "cover_${UUID.randomUUID()}.jpg")
        context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
            FileOutputStream(targetFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        } ?: return null
        Uri.fromFile(targetFile).toString()
    } catch (_: Exception) {
        null
    }
}

internal fun resolveStoredCoverModel(storedCoverUri: String): Any {
    val uri = Uri.parse(storedCoverUri)
    return if (uri.scheme == "file") {
        File(uri.path.orEmpty())
    } else {
        uri
    }
}

private suspend fun syncBookshelfAssets(
    context: Context,
    rootFolderUri: Uri,
    books: List<ComicBook>,
    metadataMap: Map<String, BookMetadata>
) = withContext(Dispatchers.IO) {
    val rootDirectory = DocumentFile.fromTreeUri(context, rootFolderUri) ?: return@withContext
    books.forEach { book ->
        val metadata = metadataMap[book.id] ?: return@forEach
        syncSingleBookAssets(context, rootDirectory, book, metadata)
    }
}

private suspend fun syncSingleBookAssets(
    context: Context,
    rootDirectory: DocumentFile,
    book: ComicBook,
    metadata: BookMetadata
) {
    if (book.chapters.isEmpty()) return

    val folderName = sanitizeDocumentName(metadata.customName.ifBlank { book.name })
    val bookDirectory = rootDirectory.findFile(folderName)?.takeIf { it.isDirectory }
        ?: rootDirectory.createDirectory(folderName)
        ?: return

    if (metadata.externalUrl.isNotBlank()) {
        writeTextDocument(context, bookDirectory, "source_url.txt", metadata.externalUrl)
    } else {
        bookDirectory.findFile("source_url.txt")?.delete()
    }

    val coverBitmap = resolveBookCoverBitmap(context, book, metadata)
    if (coverBitmap != null) {
        writeBitmapDocument(context, bookDirectory, "cover.jpg", coverBitmap)
        coverBitmap.recycle()
    } else {
        bookDirectory.findFile("cover.jpg")?.delete()
    }
}

private suspend fun resolveBookCoverBitmap(
    context: Context,
    book: ComicBook,
    metadata: BookMetadata
): Bitmap? = withContext(Dispatchers.IO) {
    if (metadata.customCoverUri.isNotBlank()) {
        return@withContext try {
            val coverUri = Uri.parse(metadata.customCoverUri)
            if (coverUri.scheme == "file") {
                BitmapFactory.decodeFile(coverUri.path)
            } else {
                context.contentResolver.openInputStream(coverUri)?.use(BitmapFactory::decodeStream)
            }
        } catch (_: Exception) {
            null
        }
    }

    if (book.chapters.isEmpty()) return@withContext null

    val cacheZipFile = File(context.cacheDir, "sync_cover_${book.id.hashCode()}.zip")
    when (val cover = ComicParser.getBookCover(context, book, cacheZipFile, metadata.coverPage - 1)) {
        is Bitmap -> cover
        is File -> BitmapFactory.decodeFile(cover.absolutePath)
        is Uri -> {
            try {
                context.contentResolver.openInputStream(cover)?.use(BitmapFactory::decodeStream)
            } catch (_: Exception) {
                null
            }
        }
        else -> null
    }
}

private fun writeTextDocument(context: Context, directory: DocumentFile, fileName: String, content: String) {
    val file = prepareChildFile(directory, fileName, "text/plain") ?: return
    try {
        context.contentResolver.openOutputStream(file.uri, "wt")?.bufferedWriter()?.use { writer ->
            writer.write(content)
        }
    } catch (_: Exception) {
    }
}

private fun writeBitmapDocument(context: Context, directory: DocumentFile, fileName: String, bitmap: Bitmap) {
    val file = prepareChildFile(directory, fileName, "image/jpeg") ?: return
    try {
        context.contentResolver.openOutputStream(file.uri, "w")?.use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        }
    } catch (_: Exception) {
    }
}

private fun prepareChildFile(directory: DocumentFile, fileName: String, mimeType: String): DocumentFile? {
    directory.findFile(fileName)?.let { existing ->
        if (existing.isDirectory) return null
        existing.delete()
    }
    return directory.createFile(mimeType, fileName)
}

private fun sanitizeDocumentName(rawName: String): String {
    val cleaned = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    return cleaned.ifBlank { "未命名漫画" }
}

internal fun isValidExternalUrl(url: String): Boolean {
    return url.startsWith("http://") || url.startsWith("https://")
}

internal fun isExternalBookId(bookId: String): Boolean {
    return bookId.startsWith(EXTERNAL_BOOK_ID_PREFIX)
}

internal fun openExternalComic(context: Context, url: String) {
    if (!isValidExternalUrl(url)) {
        Toast.makeText(context, "链接无效，请先检查外链地址", Toast.LENGTH_SHORT).show()
        return
    }

    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        if (context !is Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "当前设备没有可用于打开该链接的应用", Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Toast.makeText(context, "打开链接失败，请检查外链地址或系统默认浏览器", Toast.LENGTH_SHORT).show()
    }
}

internal fun normalizeTags(rawTags: String): List<String> {
    return rawTags
        .split(",", ";")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

internal fun resolveLastChapterIndex(sharedPrefs: SharedPreferences, book: ComicBook): Int {
    val lastChapterId = sharedPrefs.getString("${book.id}_last_chapter_id", null) ?: return 0
    return book.chapters.indexOfFirst { it.id == lastChapterId }.takeIf { it >= 0 } ?: 0
}

internal fun shouldShowDescription(book: ComicBook, metadata: BookMetadata): Boolean {
    val desc = metadata.customDesc.trim()
    return desc.isNotBlank() && desc != defaultDescription(book)
}

private fun defaultDescription(book: ComicBook): String {
    return if (book.chapters.isEmpty()) {
        "这本漫画会跳转到外部网站进行阅读。"
    } else if (book.chapters.size > 1) {
        "这本漫画已按章节整理，共 ${book.chapters.size} 章。"
    } else {
        "这本漫画当前为单章节阅读。"
    }
}
