package com.example.comicreader

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
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

internal data class ComicWebsite(
    val id: String,
    val name: String,
    val url: String,
    val iconUrl: String
)

internal object NoCoverImage

internal const val EXTERNAL_BOOK_ID_PREFIX = "external_book::"
private const val EXTERNAL_BOOKS_PREF_KEY = "external_books"
private const val COMIC_WEBSITES_PREF_KEY = "comic_websites"
private const val LOCAL_BOOKS_SNAPSHOT_PREF_KEY = "local_books_snapshot"
internal const val READER_DEFAULT_VERTICAL_MODE_KEY = "reader_default_vertical_mode"
internal const val READER_DEFAULT_PAGE_GAP_DP_KEY = "reader_default_page_gap_dp"

internal fun readChapterIdsKey(bookId: String): String = "${bookId}_read_chapter_ids"
internal fun unfinishedChapterIdKey(bookId: String): String = "${bookId}_unfinished_chapter_id"
internal fun unfinishedPageIndexKey(bookId: String): String = "${bookId}_unfinished_page_index"
internal fun legacyLastChapterIdKey(bookId: String): String = "${bookId}_last_chapter_id"

internal fun readChapterIds(sharedPrefs: SharedPreferences, bookId: String): Set<String> {
    return sharedPrefs.getStringSet(readChapterIdsKey(bookId), emptySet()).orEmpty()
}

internal fun markChapterRead(sharedPrefs: SharedPreferences, bookId: String, chapterId: String) {
    sharedPrefs.edit {
        putStringSet(readChapterIdsKey(bookId), readChapterIds(sharedPrefs, bookId) + chapterId)
    }
}

internal fun saveUnfinishedPosition(
    sharedPrefs: SharedPreferences,
    bookId: String,
    chapterId: String,
    pageIndex: Int
) {
    sharedPrefs.edit {
        putString(unfinishedChapterIdKey(bookId), chapterId)
        putInt(unfinishedPageIndexKey(bookId), pageIndex.coerceAtLeast(0))
        putString(legacyLastChapterIdKey(bookId), chapterId)
    }
}

internal fun clearUnfinishedPosition(sharedPrefs: SharedPreferences, bookId: String) {
    sharedPrefs.edit {
        remove(unfinishedChapterIdKey(bookId))
        remove(unfinishedPageIndexKey(bookId))
    }
}

internal fun resetReadingRecords(sharedPrefs: SharedPreferences, bookId: String) {
    sharedPrefs.edit {
        remove(readChapterIdsKey(bookId))
        remove(unfinishedChapterIdKey(bookId))
        remove(unfinishedPageIndexKey(bookId))
        remove(legacyLastChapterIdKey(bookId))
    }
}

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
    var websiteRefreshTrigger by remember { mutableIntStateOf(0) }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var showAddExternalDialog by remember { mutableStateOf(false) }
    var showAddWebsiteDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refreshBookshelf(rescanLocal: Boolean = true) {
        refreshTrigger++
        if (rescanLocal && rootFolderUri != null) {
            isScanning = true
        }
    }

    fun syncBookAssetsLater(book: ComicBook) {
        val folderUri = rootFolderUri ?: return
        if (book.chapters.isEmpty()) return
        scope.launch {
            delay(800)
            syncBookAssets(
                context = context,
                sharedPrefs = sharedPrefs,
                rootFolderUri = folderUri,
                book = book,
                metadata = readBookMetadata(sharedPrefs, book)
            )
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
            localBooks = loadLocalBooksSnapshot(sharedPrefs, uri)
            selectedBook = null
            currentReaderSession = null
            sharedPrefs.edit { putString("saved_root_folder_uri", uri.toString()) }
            isScanning = true
        }
    }

    LaunchedEffect(Unit) {
        val savedUri = sharedPrefs.getString("saved_root_folder_uri", null)
        if (savedUri != null) {
            val savedRootUri = Uri.parse(savedUri)
            rootFolderUri = savedRootUri
            localBooks = loadLocalBooksSnapshot(sharedPrefs, savedRootUri)
            isScanning = true
        }
    }

    LaunchedEffect(rootFolderUri, isScanning, refreshTrigger) {
        if (!isScanning) return@LaunchedEffect

        val scannedBooks = if (rootFolderUri != null) {
            ComicParser.scanBookshelf(context, rootFolderUri!!)
        } else {
            emptyList()
        }
        localBooks = scannedBooks
        rootFolderUri?.let { saveLocalBooksSnapshot(sharedPrefs, it, scannedBooks) }
        isScanning = false
    }

    val externalBooks = remember(refreshTrigger) {
        loadExternalBooks(sharedPrefs)
    }
    val comicWebsites = remember(websiteRefreshTrigger) {
        loadComicWebsites(sharedPrefs)
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
        }?.let { current ->
            bookshelf.firstOrNull { it.id == current.id } ?: current
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
    when {
        currentReaderSession != null -> ComicReaderScreen(
            book = currentReaderSession!!.book,
            initialChapterIndex = currentReaderSession!!.initialChapterIndex,
            onBack = { currentReaderSession = null }
        )
        selectedBook != null -> BookDetailScreen(
            book = selectedBook!!,
            metadata = metadataMap[selectedBook!!.id] ?: readBookMetadata(sharedPrefs, selectedBook!!),
            allTags = allTags,
            onBack = { selectedBook = null },
            onEditFinished = {
                selectedBook?.let { book ->
                    scope.launch {
                        val metadata = readBookMetadata(sharedPrefs, book)
                        val renamedBookId = renameComicBookDocument(context, sharedPrefs, book, metadata.customName)
                        if (renamedBookId == null) {
                            Toast.makeText(context, "漫画重命名失败，请检查目录权限", Toast.LENGTH_SHORT).show()
                        }
                        selectedBook = null
                        refreshBookshelf(rescanLocal = true)
                    }
                } ?: refreshBookshelf(rescanLocal = true)
            },
            onRenameChapter = { chapter, newName ->
                val renamed = renameComicChapter(context, sharedPrefs, selectedBook!!, chapter, newName)
                if (renamed) {
                    refreshBookshelf(rescanLocal = true)
                }
                renamed
            },
            onReadChapter = { chapterIndex ->
                currentReaderSession = ReaderSession(selectedBook!!, chapterIndex)
            }
        )
        else -> BookshelfScreen(
            books = filteredBooks,
            metadataMap = metadataMap,
            comicWebsites = comicWebsites,
            allTags = allTags,
            selectedTag = selectedTag,
            isScanning = isScanning && filteredBooks.isEmpty(),
            hasLibraryFolder = rootFolderUri != null,
            onTagSelected = { selectedTag = it },
            onBookClick = { book ->
                selectedBook = book
            },
            onRefreshMetadata = { book ->
                scope.launch {
                    val metadata = readBookMetadata(sharedPrefs, book)
                    val renamedBookId = renameComicBookDocument(context, sharedPrefs, book, metadata.customName)
                    if (renamedBookId == null) {
                        Toast.makeText(context, "漫画重命名失败，请检查目录权限", Toast.LENGTH_SHORT).show()
                    }
                    refreshBookshelf(rescanLocal = true)
                }
            },
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
            onAddFavoriteWebsite = { showAddWebsiteDialog = true },
            onAddExternalBook = {
                if (rootFolderUri == null) {
                    Toast.makeText(context, "请先选择漫画目录", Toast.LENGTH_SHORT).show()
                } else {
                    showAddExternalDialog = true
                }
            }
        )
    }

    if (showAddWebsiteDialog) {
        AddFavoriteWebsiteDialog(
            websites = comicWebsites,
            onDismiss = { showAddWebsiteDialog = false },
            onWebsitesChanged = {
                websiteRefreshTrigger++
            }
        )
    }

    if (showAddExternalDialog) {
        ExternalBookEditorDialog(
            allTags = allTags,
            onDismiss = { showAddExternalDialog = false },
            onSaved = {
                showAddExternalDialog = false
                refreshBookshelf(rescanLocal = false)
            }
        )
    }
}

internal fun loadComicWebsites(sharedPrefs: SharedPreferences): List<ComicWebsite> {
    val raw = sharedPrefs.getString(COMIC_WEBSITES_PREF_KEY, null).orEmpty()
    if (raw.isBlank()) return emptyList()

    return try {
        val jsonArray = JSONArray(raw)
        buildList {
            for (index in 0 until jsonArray.length()) {
                val item = jsonArray.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").trim()
                val url = item.optString("url").trim()
                val iconUrl = item.optString("iconUrl").trim()
                if (id.isNotBlank() && name.isNotBlank() && isValidExternalUrl(url)) {
                    add(
                        ComicWebsite(
                            id = id,
                            name = name,
                            url = url,
                            iconUrl = iconUrl
                        )
                    )
                }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

internal fun upsertComicWebsite(sharedPrefs: SharedPreferences, website: ComicWebsite) {
    val currentWebsites = loadComicWebsites(sharedPrefs).toMutableList()
    val existingIndex = currentWebsites.indexOfFirst { it.id == website.id }
    if (existingIndex >= 0) {
        currentWebsites[existingIndex] = website
    } else {
        currentWebsites += website
    }
    saveComicWebsites(sharedPrefs, currentWebsites)
}

internal fun removeComicWebsite(sharedPrefs: SharedPreferences, websiteId: String) {
    saveComicWebsites(
        sharedPrefs,
        loadComicWebsites(sharedPrefs).filterNot { it.id == websiteId }
    )
}

private fun saveComicWebsites(sharedPrefs: SharedPreferences, websites: List<ComicWebsite>) {
    val jsonArray = JSONArray()
    websites.forEach { website ->
        jsonArray.put(
            JSONObject().apply {
                put("id", website.id)
                put("name", website.name)
                put("url", website.url)
                put("iconUrl", website.iconUrl)
            }
        )
    }
    sharedPrefs.edit { putString(COMIC_WEBSITES_PREF_KEY, jsonArray.toString()) }
}

private fun loadLocalBooksSnapshot(sharedPrefs: SharedPreferences, rootFolderUri: Uri): List<ComicBook> {
    val raw = sharedPrefs.getString(LOCAL_BOOKS_SNAPSHOT_PREF_KEY, null).orEmpty()
    if (raw.isBlank()) return emptyList()

    return try {
        val root = JSONObject(raw)
        if (root.optString("rootUri") != rootFolderUri.toString()) return emptyList()
        val booksArray = root.optJSONArray("books") ?: return emptyList()
        buildList {
            for (bookIndex in 0 until booksArray.length()) {
                val bookObject = booksArray.optJSONObject(bookIndex) ?: continue
                val bookId = bookObject.optString("id")
                val bookName = bookObject.optString("name")
                val bookUri = bookObject.optString("uri")
                if (bookId.isBlank() || bookName.isBlank() || bookUri.isBlank()) continue

                val chaptersArray = bookObject.optJSONArray("chapters") ?: JSONArray()
                val chapters = buildList {
                    for (chapterIndex in 0 until chaptersArray.length()) {
                        val chapterObject = chaptersArray.optJSONObject(chapterIndex) ?: continue
                        val chapterId = chapterObject.optString("id")
                        val chapterName = chapterObject.optString("name")
                        val chapterUri = chapterObject.optString("sourceUri")
                        if (chapterId.isBlank() || chapterName.isBlank() || chapterUri.isBlank()) continue
                        add(
                            ComicChapter(
                                id = chapterId,
                                name = chapterName,
                                sourceUri = Uri.parse(chapterUri),
                                isZip = chapterObject.optBoolean("isZip", false)
                            )
                        )
                    }
                }

                if (chapters.isNotEmpty()) {
                    add(
                        ComicBook(
                            id = bookId,
                            name = bookName,
                            uri = Uri.parse(bookUri),
                            description = bookObject.optString("description"),
                            chapters = chapters
                        )
                    )
                }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveLocalBooksSnapshot(
    sharedPrefs: SharedPreferences,
    rootFolderUri: Uri,
    books: List<ComicBook>
) {
    val booksArray = JSONArray()
    books.forEach { book ->
        val chaptersArray = JSONArray()
        book.chapters.forEach { chapter ->
            chaptersArray.put(
                JSONObject().apply {
                    put("id", chapter.id)
                    put("name", chapter.name)
                    put("sourceUri", chapter.sourceUri.toString())
                    put("isZip", chapter.isZip)
                }
            )
        }
        booksArray.put(
            JSONObject().apply {
                put("id", book.id)
                put("name", book.name)
                put("uri", book.uri.toString())
                put("description", book.description)
                put("chapters", chaptersArray)
            }
        )
    }

    val root = JSONObject().apply {
        put("rootUri", rootFolderUri.toString())
        put("books", booksArray)
    }
    sharedPrefs.edit { putString(LOCAL_BOOKS_SNAPSHOT_PREF_KEY, root.toString()) }
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
        remove(readChapterIdsKey(bookId))
        remove(unfinishedChapterIdKey(bookId))
        remove(unfinishedPageIndexKey(bookId))
        remove(legacyLastChapterIdKey(bookId))
    }
}

internal suspend fun renameComicBookDocument(
    context: Context,
    sharedPrefs: SharedPreferences,
    book: ComicBook,
    newName: String
): String? = withContext(Dispatchers.IO) {
    try {
        if (isExternalBookId(book.id)) return@withContext book.id
        val bookDocument = DocumentFile.fromSingleUri(context, book.uri) ?: return@withContext null
        val sanitizedName = sanitizeBookDocumentName(newName, book)
        if (sanitizedName == bookDocument.name) return@withContext book.id

        val renamedUri = renameBookDocument(context, bookDocument, book.uri, sanitizedName)
            ?: return@withContext null
        val newBookId = renamedUri.toString()
        if (newBookId != book.id) {
            migrateBookMetadata(sharedPrefs, book.id, newBookId)
            migrateBookCaches(context, book.id, newBookId)
        }
        newBookId
    } catch (_: Exception) {
        null
    }
}

private fun renameBookDocument(
    context: Context,
    bookDocument: DocumentFile,
    bookUri: Uri,
    sanitizedName: String
): Uri? {
    try {
        DocumentsContract.renameDocument(context.contentResolver, bookUri, sanitizedName)?.let { renamedUri ->
            return renamedUri
        }
    } catch (_: Exception) {
    }

    return try {
        if (bookDocument.renameTo(sanitizedName)) bookDocument.uri else null
    } catch (_: Exception) {
        null
    }
}

private fun migrateBookMetadata(sharedPrefs: SharedPreferences, oldBookId: String, newBookId: String) {
    val suffixes = listOf(
        "_custom_name",
        "_custom_desc",
        "_tags",
        "_cover_index",
        "_auto_next",
        "_external_only",
        "_open_externally",
        "_external_url",
        "_custom_cover_uri",
        "_read_chapter_ids",
        "_unfinished_chapter_id",
        "_unfinished_page_index",
        "_last_chapter_id",
        "_asset_sync_signature"
    )
    val allValues = sharedPrefs.all
    sharedPrefs.edit {
        suffixes.forEach { suffix ->
            val oldKey = oldBookId + suffix
            val newKey = newBookId + suffix
            when (val value = allValues[oldKey]) {
                is String -> putString(newKey, value)
                is Int -> putInt(newKey, value)
                is Boolean -> putBoolean(newKey, value)
                is Long -> putLong(newKey, value)
                is Float -> putFloat(newKey, value)
            }
            remove(oldKey)
        }
    }
}

private fun migrateBookCaches(context: Context, oldBookId: String, newBookId: String) {
    val oldThumbPrefix = "thumb_${oldBookId.hashCode()}_v"
    val newThumbPrefix = "thumb_${newBookId.hashCode()}_v"
    context.filesDir.listFiles()
        ?.filter { file -> file.isFile && file.name.startsWith(oldThumbPrefix) }
        ?.forEach { oldFile ->
            val newFileName = oldFile.name.replaceFirst(oldThumbPrefix, newThumbPrefix)
            val newFile = File(context.filesDir, newFileName)
            if (newFile.exists()) return@forEach
            try {
                oldFile.copyTo(newFile, overwrite = false)
            } catch (_: Exception) {
            }
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

private suspend fun renameComicChapter(
    context: Context,
    sharedPrefs: SharedPreferences,
    book: ComicBook,
    chapter: ComicChapter,
    newName: String
): Boolean = withContext(Dispatchers.IO) {
    try {
        if (newName.isBlank()) return@withContext false
        if (chapter.id.endsWith("#root") && chapter.sourceUri == book.uri) return@withContext false

        val chapterDocument = DocumentFile.fromSingleUri(context, chapter.sourceUri)
            ?: findChapterDocumentInBook(context, book, chapter)
            ?: return@withContext false
        val sanitizedName = sanitizeChapterDocumentName(newName, chapter)
        if (sanitizedName == chapterDocument.name) return@withContext true

        val oldChapterId = chapter.id
        val oldReadChapterIds = readChapterIds(sharedPrefs, book.id)
        val oldUnfinishedChapterId = sharedPrefs.getString(unfinishedChapterIdKey(book.id), null)
        val oldLastChapterId = sharedPrefs.getString(legacyLastChapterIdKey(book.id), null)
        val renamedUri = renameChapterDocument(context, book, chapter, chapterDocument, sanitizedName)
        if (renamedUri != null) {
            val newChapterId = renamedUri.toString()
            sharedPrefs.edit {
                if (oldReadChapterIds.contains(oldChapterId)) {
                    putStringSet(readChapterIdsKey(book.id), (oldReadChapterIds - oldChapterId) + newChapterId)
                }
                if (oldUnfinishedChapterId == oldChapterId) {
                    putString(unfinishedChapterIdKey(book.id), newChapterId)
                }
                if (oldLastChapterId == oldChapterId) {
                    putString(legacyLastChapterIdKey(book.id), newChapterId)
                }
                remove("${book.id}_asset_sync_signature")
            }
        }
        renamedUri != null
    } catch (_: Exception) {
        false
    }
}

private fun renameChapterDocument(
    context: Context,
    book: ComicBook,
    chapter: ComicChapter,
    chapterDocument: DocumentFile,
    sanitizedName: String
): Uri? {
    try {
        DocumentsContract.renameDocument(context.contentResolver, chapter.sourceUri, sanitizedName)?.let { renamedUri ->
            return renamedUri
        }
    } catch (_: Exception) {
    }

    try {
        if (chapterDocument.renameTo(sanitizedName)) {
            return findRenamedChapterUri(context, book, sanitizedName) ?: chapterDocument.uri
        }
    } catch (_: Exception) {
    }

    val fallbackDocument = findChapterDocumentInBook(context, book, chapter) ?: return null
    return try {
        if (fallbackDocument.renameTo(sanitizedName)) {
            findRenamedChapterUri(context, book, sanitizedName) ?: fallbackDocument.uri
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}

private fun findChapterDocumentInBook(context: Context, book: ComicBook, chapter: ComicChapter): DocumentFile? {
    val bookDocument = DocumentFile.fromSingleUri(context, book.uri) ?: return null
    return bookDocument.listFiles().firstOrNull { candidate ->
        candidate.uri == chapter.sourceUri || candidate.name == chapter.name
    }
}

private fun findRenamedChapterUri(context: Context, book: ComicBook, renamedName: String): Uri? {
    val bookDocument = DocumentFile.fromSingleUri(context, book.uri) ?: return null
    return bookDocument.listFiles().firstOrNull { candidate ->
        candidate.name == renamedName
    }?.uri
}

internal fun saveWebsiteIconToAppStorage(context: Context, sourceUri: Uri): String? {
    return try {
        val iconDirectory = File(context.filesDir, "website_icons").apply { mkdirs() }
        val targetFile = File(iconDirectory, "icon_${UUID.randomUUID()}.png")
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

internal suspend fun cacheWebsiteIconToAppStorage(
    context: Context,
    websiteId: String,
    websiteUrl: String,
    customIconUrl: String
): String? = withContext(Dispatchers.IO) {
    val iconDirectory = File(context.filesDir, "website_icons").apply { mkdirs() }
    websiteIconUrlCandidates(customIconUrl, websiteUrl)
        .filter { isValidExternalUrl(it) }
        .forEachIndexed { index, iconUrl ->
            val targetFile = File(iconDirectory, "icon_${websiteId}_${index}.img")
            if (downloadWebsiteIcon(iconUrl, targetFile)) {
                return@withContext Uri.fromFile(targetFile).toString()
            }
            targetFile.delete()
        }
    null
}

private fun downloadWebsiteIcon(iconUrl: String, targetFile: File): Boolean {
    var connection: HttpURLConnection? = null
    return try {
        val openedConnection = (URL(iconUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        connection = openedConnection
        if (openedConnection.responseCode !in 200..299) return false
        openedConnection.inputStream.use { inputStream ->
            FileOutputStream(targetFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        targetFile.length() > 0L
    } catch (_: Exception) {
        false
    } finally {
        connection?.disconnect()
    }
}

internal fun deleteStoredWebsiteIcon(iconUri: String) {
    val uri = Uri.parse(iconUri)
    if (uri.scheme != "file") return
    val file = File(uri.path.orEmpty())
    if (file.parentFile?.name == "website_icons") {
        file.delete()
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

private suspend fun syncBookAssets(
    context: Context,
    sharedPrefs: SharedPreferences,
    rootFolderUri: Uri,
    book: ComicBook,
    metadata: BookMetadata
) = withContext(Dispatchers.IO) {
    val rootDirectory = DocumentFile.fromTreeUri(context, rootFolderUri) ?: return@withContext
    syncSingleBookAssets(context, sharedPrefs, rootDirectory, book, metadata)
}

private suspend fun syncSingleBookAssets(
    context: Context,
    sharedPrefs: SharedPreferences,
    rootDirectory: DocumentFile,
    book: ComicBook,
    metadata: BookMetadata
) {
    if (book.chapters.isEmpty()) return

    val syncSignature = bookAssetSyncSignature(book, metadata)
    val syncKey = "${book.id}_asset_sync_signature"
    if (sharedPrefs.getString(syncKey, null) == syncSignature) return

    val bookDirectory = DocumentFile.fromSingleUri(context, book.uri)?.takeIf { it.isDirectory }
        ?: rootDirectory.findFile(book.name)?.takeIf { it.isDirectory }
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

    sharedPrefs.edit { putString(syncKey, syncSignature) }
}

private fun bookAssetSyncSignature(book: ComicBook, metadata: BookMetadata): String {
    val chapterSignature = book.chapters.joinToString(separator = "|") { chapter ->
        "${chapter.id},${chapter.sourceUri},${chapter.isZip}"
    }
    return listOf(
        book.id,
        metadata.customName,
        metadata.coverPage.toString(),
        metadata.externalUrl,
        metadata.customCoverUri,
        chapterSignature
    ).joinToString(separator = "||")
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

private fun sanitizeBookDocumentName(rawName: String, book: ComicBook): String {
    val cleaned = sanitizeDocumentName(rawName).ifBlank { book.name }
    val currentExtension = book.name.substringAfterLast('.', missingDelimiterValue = "")
        .takeIf { book.chapters.size == 1 && (it.equals("zip", ignoreCase = true) || it.equals("cbz", ignoreCase = true)) }
        ?: book.uri.lastPathSegment?.substringAfterLast('.', missingDelimiterValue = "")
            ?.takeIf { it.equals("zip", ignoreCase = true) || it.equals("cbz", ignoreCase = true) }
    if (currentExtension == null || cleaned.endsWith(".$currentExtension", ignoreCase = true)) {
        return cleaned
    }
    return "$cleaned.$currentExtension"
}

private fun sanitizeChapterDocumentName(rawName: String, chapter: ComicChapter): String {
    val cleaned = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { chapter.name }
    if (!chapter.isZip) return cleaned

    val currentExtension = chapter.name.substringAfterLast('.', missingDelimiterValue = "")
        .takeIf { it.equals("zip", ignoreCase = true) || it.equals("cbz", ignoreCase = true) }
        ?: chapter.sourceUri.lastPathSegment?.substringAfterLast('.', missingDelimiterValue = "")
            ?.takeIf { it.equals("zip", ignoreCase = true) || it.equals("cbz", ignoreCase = true) }
    if (currentExtension == null || cleaned.endsWith(".$currentExtension", ignoreCase = true)) {
        return cleaned
    }
    return "$cleaned.$currentExtension"
}

internal fun isValidExternalUrl(url: String): Boolean {
    return url.startsWith("http://") || url.startsWith("https://")
}

internal fun defaultWebsiteIconUrl(url: String): String {
    return websiteIconUrlCandidates("", url).first()
}

internal fun websiteIconUrlCandidates(customIconUrl: String, websiteUrl: String): List<String> {
    val uri = Uri.parse(websiteUrl)
    val scheme = uri.scheme?.takeIf { it.isNotBlank() } ?: "https"
    val host = uri.host.orEmpty()
    if (host.isBlank()) {
        return listOfNotNull(customIconUrl.takeIf { it.isNotBlank() }, websiteUrl).distinct()
    }
    val domain = Uri.encode(host.removePrefix("www."))
    return listOf(
        customIconUrl,
        "https://www.google.com/s2/favicons?sz=128&domain=$domain",
        "https://icons.duckduckgo.com/ip3/$domain.ico",
        "https://favicon.yandex.net/favicon/$domain",
        "$scheme://$host/apple-touch-icon.png",
        "$scheme://$host/favicon.png",
        "$scheme://$host/favicon.ico"
    ).filter { it.isNotBlank() }.distinct()
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
    val unfinishedChapterId = sharedPrefs.getString(unfinishedChapterIdKey(book.id), null)
    if (unfinishedChapterId != null) {
        return book.chapters.indexOfFirst { it.id == unfinishedChapterId }.takeIf { it >= 0 } ?: 0
    }

    val readIds = readChapterIds(sharedPrefs, book.id)
    val firstUnreadIndex = book.chapters.indexOfFirst { it.id !in readIds }
    if (firstUnreadIndex >= 0) return firstUnreadIndex

    val lastChapterId = sharedPrefs.getString(legacyLastChapterIdKey(book.id), null) ?: return 0
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
