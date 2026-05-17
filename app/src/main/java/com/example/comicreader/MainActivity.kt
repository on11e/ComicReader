package com.example.comicreader

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cacheDir.listFiles()?.forEach { it.delete() }
        setContent {
            MaterialTheme {
                Surface(color = Color(0xFF12121A)) {
                    MainAppScreen()
                }
            }
        }
    }
}

private data class ReaderSession(
    val book: ComicBook,
    val initialChapterIndex: Int
)

private data class BookMetadata(
    val customName: String,
    val customDesc: String,
    val tags: List<String>,
    val coverPage: Int,
    val autoNextChapter: Boolean
)

@Composable
fun MainAppScreen() {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("comic_progress_prefs", Context.MODE_PRIVATE) }

    var rootFolderUri by remember { mutableStateOf<Uri?>(null) }
    var bookshelf by remember { mutableStateOf<List<ComicBook>>(emptyList()) }
    var selectedBook by remember { mutableStateOf<ComicBook?>(null) }
    var currentReaderSession by remember { mutableStateOf<ReaderSession?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var selectedTag by remember { mutableStateOf<String?>(null) }

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
        if (rootFolderUri != null && isScanning) {
            bookshelf = ComicParser.scanBookshelf(context, rootFolderUri!!)
            selectedTag = selectedTag?.takeIf { tag ->
                bookshelf.any { book -> readBookMetadata(sharedPrefs, book).tags.contains(tag) }
            }
            isScanning = false
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
        rootFolderUri == null -> LibrarySetupScreen(onPickFolder = { libraryLauncher.launch(null) })
        currentReaderSession != null -> ComicReaderScreen(
            book = currentReaderSession!!.book,
            initialChapterIndex = currentReaderSession!!.initialChapterIndex,
            onBack = { currentReaderSession = null }
        )
        selectedBook != null -> BookDetailScreen(
            book = selectedBook!!,
            metadata = metadataMap[selectedBook!!.id] ?: readBookMetadata(sharedPrefs, selectedBook!!),
            onBack = { selectedBook = null },
            onEditFinished = { refreshTrigger++ },
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
            onTagSelected = { selectedTag = it },
            onBookClick = { book ->
                if (book.chapters.size > 1) {
                    selectedBook = book
                } else {
                    currentReaderSession = ReaderSession(book, resolveLastChapterIndex(sharedPrefs, book))
                }
            },
            onRefreshMetadata = { refreshTrigger++ },
            onChangeFolder = {
                sharedPrefs.edit { remove("saved_root_folder_uri") }
                rootFolderUri = null
                bookshelf = emptyList()
                selectedBook = null
                currentReaderSession = null
            }
        )
    }
}

@Composable
private fun LibrarySetupScreen(onPickFolder: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1F1F2E), Color(0xFF0F0F15)))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("KAMI COMIC", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text("本地离线漫画阅读器", color = Color(0xFFB3B3C2), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = onPickFolder,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE)),
                modifier = Modifier
                    .height(54.dp)
                    .width(240.dp)
            ) {
                Text("选择漫画目录", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

@Composable
private fun BookshelfScreen(
    books: List<ComicBook>,
    metadataMap: Map<String, BookMetadata>,
    allTags: List<String>,
    selectedTag: String?,
    isScanning: Boolean,
    onTagSelected: (String?) -> Unit,
    onBookClick: (ComicBook) -> Unit,
    onRefreshMetadata: () -> Unit,
    onChangeFolder: () -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("comic_progress_prefs", Context.MODE_PRIVATE) }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF111119))
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("我的书架", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    OutlinedButton(
                        onClick = onChangeFolder,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFF353547)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("目录", color = Color(0xFFC7C7D8), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (allTags.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedTag == null,
                            onClick = { onTagSelected(null) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFF2B84B),
                                selectedLabelColor = Color(0xFF12121A),
                                containerColor = Color(0xFF1C1C28),
                                labelColor = Color(0xFFC6C6D4)
                            ),
                            label = { Text("全部") }
                        )
                        allTags.forEach { tag ->
                            FilterChip(
                                selected = selectedTag == tag,
                                onClick = { onTagSelected(tag) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFF2B84B),
                                    selectedLabelColor = Color(0xFF12121A),
                                    containerColor = Color(0xFF1C1C28),
                                    labelColor = Color(0xFFC6C6D4)
                                ),
                                label = { Text(tag) }
                            )
                        }
                    }
                }
            }
        },
        containerColor = Color(0xFF12121A)
    ) { paddingValues ->
        when {
            isScanning -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFBB86FC))
                }
            }
            books.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (selectedTag == null) {
                            "当前目录下没有可识别的漫画文件夹或 ZIP/CBZ 压缩包。"
                        } else {
                            "当前标签下还没有漫画，试试切换其他标签。"
                        },
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 24.dp, top = 8.dp)
                ) {
                    items(books, key = { it.id }) { book ->
                        BookCard(
                            book = book,
                            metadata = metadataMap[book.id] ?: readBookMetadata(sharedPrefs, book),
                            onClick = { onBookClick(book) },
                            onMetaChanged = onRefreshMetadata
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BookCard(
    book: ComicBook,
    metadata: BookMetadata,
    onClick: () -> Unit,
    onMetaChanged: () -> Unit
) {
    val context = LocalContext.current
    var coverData by remember(book.id, metadata.coverPage) { mutableStateOf<Any?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    val cacheZipFile = remember(book.id) { File(context.cacheDir, "cover_${book.id.hashCode()}.zip") }

    LaunchedEffect(book.id, metadata.coverPage) {
        coverData = ComicParser.getBookCover(context, book, cacheZipFile, metadata.coverPage - 1)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, shape = RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .pointerInput(book.id) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { showEditDialog = true }
                )
            }
            .padding(bottom = 2.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF191923))
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Color(0xFF09090F))
            ) {
                when (val data = coverData) {
                    is Bitmap -> Image(
                        bitmap = data.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    null -> CircularProgressIndicator(
                        color = Color.Gray,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(24.dp)
                    )
                    else -> AsyncImage(
                        model = data,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color(0xAA09090F))))
                )
            }

            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = metadata.customName,
                    color = Color.White,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 18.sp
                )
                if (shouldShowDescription(book, metadata)) {
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = metadata.customDesc,
                        color = Color(0xB5FFFFFF),
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 14.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MetaPill(text = "共 ${book.chapters.size} 章")
                    metadata.tags.filter { it.isNotBlank() }.take(2).forEach { tag ->
                        MetaPill(text = tag, accent = true)
                    }
                }
                if (metadata.tags.size > 2) {
                    Spacer(modifier = Modifier.height(6.dp))
                    MetaPill(text = "还有 ${metadata.tags.size - 2} 个")
                }
            }
        }
    }

    if (showEditDialog) {
        EditBookMetadataDialog(
            book = book,
            initialMetadata = metadata,
            onDismiss = { showEditDialog = false },
            onSaved = {
                showEditDialog = false
                onMetaChanged()
            }
        )
    }
}

@Composable
private fun MetaPill(text: String, accent: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (accent) Color(0xFF2D253A) else Color(0xFF242432))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            color = if (accent) Color(0xFFF3D27A) else Color(0xFFD7D7E2),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun BookDetailScreen(
    book: ComicBook,
    metadata: BookMetadata,
    onBack: () -> Unit,
    onEditFinished: () -> Unit,
    onReadChapter: (Int) -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("comic_progress_prefs", Context.MODE_PRIVATE) }
    var showEditDialog by remember { mutableStateOf(false) }
    val lastChapterIndex = remember(book.id) { resolveLastChapterIndex(sharedPrefs, book) }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF111119))
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) { Text("返回", color = Color.White) }
                TextButton(onClick = { showEditDialog = true }) { Text("编辑标签", color = Color(0xFFBB86FC)) }
            }
        },
        containerColor = Color(0xFF12121A)
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF171720)),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color(0xFF202033), Color(0xFF171720))))
                            .padding(18.dp)
                    ) {
                        Text(metadata.customName, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                        if (shouldShowDescription(book, metadata)) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(metadata.customDesc, color = Color(0xFFB9B9C6), fontSize = 14.sp, lineHeight = 20.sp)
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MetaPill(text = "共 ${book.chapters.size} 章")
                            metadata.tags.filter { it.isNotBlank() }.forEach { tag ->
                                MetaPill(text = tag, accent = true)
                            }
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = { onReadChapter(lastChapterIndex) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE7B95A)),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    Text("继续阅读 ${book.chapters[lastChapterIndex].name}", color = Color(0xFF111119), fontWeight = FontWeight.Bold)
                }
            }
            if (book.chapters.size == 1) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B25)),
                        border = BorderStroke(1.dp, Color(0xFF303041))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("如何测试多章节", color = Color.White, fontWeight = FontWeight.Bold)
                            Text(
                                text = "在漫画根目录下先建一个作品文件夹，然后创建“第1话”“第2话”“第3话”这类子文件夹，并把图片放进对应子文件夹。放在作品文件夹内的 ZIP/CBZ 也会被识别为章节。",
                                color = Color(0xFFC6C6D4),
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
            itemsIndexed(book.chapters, key = { _, chapter -> chapter.id }) { index, chapter ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (index == lastChapterIndex) Color(0xFF222238) else Color(0xFF1A1A24)
                    ),
                    shape = RoundedCornerShape(18.dp),
                    border = if (index == lastChapterIndex) BorderStroke(1.dp, Color(0xFF4A4A76)) else null
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .padding(end = 12.dp)
                        ) {
                            Text(chapter.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(if (chapter.isZip) "ZIP/CBZ 章节" else "文件夹章节", color = Color(0xFF9A9AA8), fontSize = 12.sp)
                        }
                        Button(onClick = { onReadChapter(index) }) {
                            Text(if (index == lastChapterIndex) "继续" else "阅读")
                        }
                    }
                }
            }
        }
    }

    if (showEditDialog) {
        EditBookMetadataDialog(
            book = book,
            initialMetadata = metadata,
            onDismiss = { showEditDialog = false },
            onSaved = {
                showEditDialog = false
                onEditFinished()
            }
        )
    }
}

@Composable
private fun EditBookMetadataDialog(
    book: ComicBook,
    initialMetadata: BookMetadata,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("comic_progress_prefs", Context.MODE_PRIVATE) }

    var inputName by remember { mutableStateOf(initialMetadata.customName) }
    var inputDesc by remember { mutableStateOf(initialMetadata.customDesc) }
    var inputTags by remember { mutableStateOf(initialMetadata.tags.joinToString(", ")) }
    var inputCoverPage by remember { mutableStateOf(initialMetadata.coverPage.toString()) }
    var autoNextChapter by remember { mutableStateOf(initialMetadata.autoNextChapter) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑漫画资料") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(value = inputName, onValueChange = { inputName = it }, label = { Text("漫画名称") }, modifier = Modifier.fillMaxWidth())
                TextField(value = inputDesc, onValueChange = { inputDesc = it }, label = { Text("简介") }, modifier = Modifier.fillMaxWidth())
                TextField(value = inputTags, onValueChange = { inputTags = it }, label = { Text("标签，使用逗号分隔") }, modifier = Modifier.fillMaxWidth())
                TextField(value = inputCoverPage, onValueChange = { inputCoverPage = it }, label = { Text("封面页码") }, modifier = Modifier.fillMaxWidth())
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.72f)
                            .padding(end = 12.dp)
                    ) {
                        Text("章节结尾自动进入下一章", fontSize = 14.sp)
                        Text("关闭后会在本章结束时弹出提示", color = Color.Gray, fontSize = 12.sp)
                    }
                    Switch(checked = autoNextChapter, onCheckedChange = { autoNextChapter = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val coverPage = inputCoverPage.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    sharedPrefs.edit {
                        putString("${book.id}_custom_name", inputName.trim().ifEmpty { book.name })
                        putString("${book.id}_custom_desc", inputDesc.trim())
                        putString("${book.id}_tags", normalizeTags(inputTags).joinToString(","))
                        putInt("${book.id}_cover_index", coverPage)
                        putBoolean("${book.id}_auto_next", autoNextChapter)
                    }
                    onSaved()
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun ComicReaderScreen(
    book: ComicBook,
    initialChapterIndex: Int,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("comic_progress_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val activity = context as? ComponentActivity

    var isVerticalMode by remember { mutableStateOf(true) }
    var hasPageGap by remember { mutableStateOf(true) }
    var currentChapterIndex by remember(book.id, initialChapterIndex) {
        mutableIntStateOf(initialChapterIndex.coerceIn(0, book.chapters.lastIndex))
    }
    var chapterPages by remember { mutableStateOf<List<Any>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0f) }
    var showRestartPrompt by remember { mutableStateOf(false) }
    var dismissNextChapterPrompt by remember { mutableStateOf(false) }

    val currentChapter = book.chapters[currentChapterIndex]
    val cacheZipFile = remember(currentChapter.id) { File(context.cacheDir, "reader_${currentChapter.id.hashCode()}.zip") }
    val metadata = remember(book.id) { readBookMetadata(sharedPrefs, book) }

    LaunchedEffect(Unit) {
        activity?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.let { window ->
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
            context.cacheDir.listFiles()?.forEach { it.delete() }
        }
    }

    LaunchedEffect(currentChapter.id) {
        isLoading = true
        dismissNextChapterPrompt = false
        chapterPages = if (currentChapter.isZip) {
            if (ComicParser.copyZipToCache(context, currentChapter.sourceUri, cacheZipFile)) {
                ComicParser.getPagesFromZip(cacheZipFile)
            } else {
                emptyList()
            }
        } else {
            ComicParser.getComicPagesFromFolder(context, currentChapter.sourceUri)
        }
        val savedPage = sharedPrefs.getInt(currentChapter.id, 0)
        sliderValue = savedPage.toFloat()
        showRestartPrompt = savedPage > 0
        sharedPrefs.edit { putString("${book.id}_last_chapter_id", currentChapter.id) }
        isLoading = false
    }

    LaunchedEffect(showRestartPrompt) {
        if (showRestartPrompt) {
            delay(4000)
            showRestartPrompt = false
        }
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }

    if (chapterPages.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("当前章节没有可显示的图片。", color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = onBack) { Text("返回") }
            }
        }
        return
    }

    val totalPages = chapterPages.size
    val savedPage = sharedPrefs.getInt(currentChapter.id, 0).coerceIn(0, totalPages - 1)
    val autoNextChapter = metadata.autoNextChapter

    key(currentChapter.id, isVerticalMode) {
        val pagerState = rememberPagerState(initialPage = savedPage, pageCount = { totalPages })
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = savedPage)
        val currentPageIndex = if (isVerticalMode) {
            listState.firstVisibleItemIndex.coerceIn(0, totalPages - 1)
        } else {
            pagerState.currentPage.coerceIn(0, totalPages - 1)
        }

        LaunchedEffect(currentPageIndex, currentChapter.id) {
            sliderValue = currentPageIndex.toFloat()
            sharedPrefs.edit {
                putInt(currentChapter.id, currentPageIndex)
                putString("${book.id}_last_chapter_id", currentChapter.id)
            }
            if (currentPageIndex < totalPages - 1) {
                dismissNextChapterPrompt = false
            }
            if (autoNextChapter && currentPageIndex == totalPages - 1 && currentChapterIndex < book.chapters.lastIndex) {
                currentChapterIndex += 1
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(onBack) {
                    var startX = 0f
                    var totalDrag = 0f
                    var startedAtEdge = false
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            val edgeWidth = size.width * 0.12f
                            startX = offset.x
                            totalDrag = 0f
                            startedAtEdge = startX <= edgeWidth || startX >= size.width - edgeWidth
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            if (startedAtEdge) {
                                totalDrag += dragAmount
                            }
                        },
                        onDragEnd = {
                            val edgeWidth = size.width * 0.12f
                            val threshold = size.width * 0.18f
                            val fromLeft = startX <= edgeWidth && totalDrag > threshold
                            val fromRight = startX >= size.width - edgeWidth && totalDrag < -threshold
                            if (fromLeft || fromRight) {
                                onBack()
                            }
                        }
                    )
                }
                .pointerInput(Unit) { detectTapGestures(onTap = { showControls = !showControls }) }
        ) {
            if (isVerticalMode) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(if (hasPageGap) 10.dp else 0.dp)
                ) {
                    itemsIndexed(chapterPages, key = { index, _ -> "${currentChapter.id}#$index" }) { _, page ->
                        ReaderImage(page = page, isFolder = !currentChapter.isZip, cacheZipFile = cacheZipFile, isFullScreen = false)
                    }
                }
            } else {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { pageIndex ->
                    ReaderImage(
                        page = chapterPages[pageIndex],
                        isFolder = !currentChapter.isZip,
                        cacheZipFile = cacheZipFile,
                        modifier = Modifier.fillMaxSize(),
                        isFullScreen = true
                    )
                }
            }

            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                ReaderTopBar(
                    book = book,
                    chapter = currentChapter,
                    chapterIndex = currentChapterIndex,
                    chapterCount = book.chapters.size,
                    isVerticalMode = isVerticalMode,
                    hasPageGap = hasPageGap,
                    onBack = onBack,
                    onToggleMode = { isVerticalMode = !isVerticalMode },
                    onToggleGap = { hasPageGap = !hasPageGap }
                )
            }

            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xE60A0A0F))
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "第 ${currentPageIndex + 1} / $totalPages 页", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = {
                            val targetPage = sliderValue.toInt().coerceIn(0, totalPages - 1)
                            scope.launch {
                                if (isVerticalMode) listState.scrollToItem(targetPage) else pagerState.scrollToPage(targetPage)
                            }
                        },
                        valueRange = 0f..((totalPages - 1).toFloat().coerceAtLeast(1f)),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color(0xFFBB86FC),
                            inactiveTrackColor = Color(0xFF333344)
                        )
                    )
                    if (book.chapters.size > 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ToolbarChipButton(
                                text = "上一章",
                                enabled = currentChapterIndex > 0,
                                onClick = { if (currentChapterIndex > 0) currentChapterIndex -= 1 }
                            )
                            ToolbarChipButton(
                                text = "下一章",
                                enabled = currentChapterIndex < book.chapters.lastIndex,
                                onClick = { if (currentChapterIndex < book.chapters.lastIndex) currentChapterIndex += 1 }
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = showRestartPrompt,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 110.dp)
            ) {
                Button(
                    onClick = {
                        showRestartPrompt = false
                        sharedPrefs.edit { putInt(currentChapter.id, 0) }
                        sliderValue = 0f
                        scope.launch {
                            if (isVerticalMode) listState.scrollToItem(0) else pagerState.scrollToPage(0)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.height(42.dp)
                ) {
                    Text("上次看到第 ${savedPage + 1} 页，点此从头开始", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            val showNextChapterPrompt =
                !autoNextChapter &&
                    currentPageIndex == totalPages - 1 &&
                    currentChapterIndex < book.chapters.lastIndex &&
                    !dismissNextChapterPrompt

            AnimatedVisibility(
                visible = showNextChapterPrompt,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (showControls) 160.dp else 28.dp)
            ) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xEE1E1E28)), modifier = Modifier.padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("当前章节已结束", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("下一章：${book.chapters[currentChapterIndex + 1].name}", color = Color(0xFFCACAD7))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { dismissNextChapterPrompt = true }) { Text("停留本章") }
                            Button(onClick = { currentChapterIndex += 1 }) { Text("进入下一章") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderTopBar(
    book: ComicBook,
    chapter: ComicChapter,
    chapterIndex: Int,
    chapterCount: Int,
    isVerticalMode: Boolean,
    hasPageGap: Boolean,
    onBack: () -> Unit,
    onToggleMode: () -> Unit,
    onToggleGap: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xD9111118))
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            TextButton(
                onClick = onBack,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
            ) {
                Text("返回", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolbarIconButton(
                    label = if (isVerticalMode) "横向" else "竖向",
                    onClick = onToggleMode,
                    active = !isVerticalMode,
                    width = 56.dp
                )
                if (isVerticalMode) {
                    ToolbarIconButton(
                        label = if (hasPageGap) "页距开" else "页距关",
                        onClick = onToggleGap,
                        active = hasPageGap,
                        width = 62.dp
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = book.name,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${chapter.name}  ·  第 ${chapterIndex + 1}/$chapterCount 章",
            color = Color(0xFFC9C9D8),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ToolbarIconButton(
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true,
    width: Dp = 34.dp
) {
    val containerColor = when {
        !enabled -> Color(0xFF1A1A22)
        active -> Color(0xFFE7B95A)
        else -> Color(0xFF252535)
    }
    val textColor = if (active) Color(0xFF12121A) else Color.White

    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            disabledContainerColor = Color(0xFF1A1A22),
            disabledContentColor = Color(0xFF626274)
        ),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
        modifier = Modifier.width(width).height(34.dp)
    ) {
        Text(label, color = textColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ToolbarChipButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        border = BorderStroke(1.dp, if (enabled) Color(0xFF404055) else Color(0xFF262632)),
        shape = RoundedCornerShape(999.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, color = if (enabled) Color(0xFFD7D7E2) else Color(0xFF6B6B7A), fontSize = 11.sp)
    }
}

@Composable
private fun ReaderImage(
    page: Any,
    isFolder: Boolean,
    cacheZipFile: File,
    modifier: Modifier = Modifier,
    isFullScreen: Boolean
) {
    Box(
        modifier = if (isFullScreen) {
            modifier.fillMaxSize().background(Color(0xFF0D0D12))
        } else {
            modifier.fillMaxWidth().heightIn(min = 400.dp).background(Color(0xFF0D0D12))
        },
        contentAlignment = Alignment.Center
    ) {
        if (isFolder) {
            AsyncImage(
                model = page as Uri,
                contentDescription = null,
                modifier = if (isFullScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().wrapContentHeight(),
                contentScale = if (isFullScreen) ContentScale.Fit else ContentScale.FillWidth
            )
        } else {
            var bitmap by remember(page) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(page) {
                bitmap = ComicParser.getZipPageBitmap(cacheZipFile, page as String)
            }
            bitmap?.let { loadedBitmap ->
                Image(
                    bitmap = loadedBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = if (isFullScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().wrapContentHeight(),
                    contentScale = if (isFullScreen) ContentScale.Fit else ContentScale.FillWidth
                )
            } ?: CircularProgressIndicator(color = Color(0xFF333344))
        }
    }
}

private fun readBookMetadata(sharedPrefs: SharedPreferences, book: ComicBook): BookMetadata {
    val savedTags = sharedPrefs.getString("${book.id}_tags", null)
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        .orEmpty()

    return BookMetadata(
        customName = sharedPrefs.getString("${book.id}_custom_name", book.name) ?: book.name,
        customDesc = sharedPrefs.getString("${book.id}_custom_desc", defaultDescription(book)) ?: defaultDescription(book),
        tags = savedTags,
        coverPage = sharedPrefs.getInt("${book.id}_cover_index", 1).coerceAtLeast(1),
        autoNextChapter = sharedPrefs.getBoolean("${book.id}_auto_next", false)
    )
}

private fun normalizeTags(rawTags: String): List<String> {
    return rawTags
        .split(",", ";")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

private fun resolveLastChapterIndex(sharedPrefs: SharedPreferences, book: ComicBook): Int {
    val lastChapterId = sharedPrefs.getString("${book.id}_last_chapter_id", null) ?: return 0
    return book.chapters.indexOfFirst { it.id == lastChapterId }.takeIf { it >= 0 } ?: 0
}

private fun shouldShowDescription(book: ComicBook, metadata: BookMetadata): Boolean {
    val desc = metadata.customDesc.trim()
    return desc.isNotBlank() && desc != defaultDescription(book)
}

private fun defaultDescription(book: ComicBook): String {
    return if (book.chapters.size > 1) {
        "这本漫画已按章节整理，共 ${book.chapters.size} 章。"
    } else {
        "这本漫画当前为单章节阅读。"
    }
}
