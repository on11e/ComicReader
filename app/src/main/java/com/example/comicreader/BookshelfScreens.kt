package com.example.comicreader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
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
internal fun BookshelfScreen(
    books: List<ComicBook>,
    metadataMap: Map<String, BookMetadata>,
    comicWebsites: List<ComicWebsite>,
    allTags: List<String>,
    selectedTag: String?,
    isScanning: Boolean,
    hasLibraryFolder: Boolean,
    onTagSelected: (String?) -> Unit,
    onBookClick: (ComicBook) -> Unit,
    onRefreshMetadata: (ComicBook) -> Unit,
    onDeleteBook: suspend (ComicBook) -> Boolean,
    onPickFolder: () -> Unit,
    onAddFavoriteWebsite: () -> Unit,
    onAddExternalBook: () -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("comic_progress_prefs", Context.MODE_PRIVATE) }
    var showMenu by remember { mutableStateOf(false) }
    var showReaderSettingsMenu by remember { mutableStateOf(false) }
    var readerDefaultVerticalMode by remember {
        mutableStateOf(sharedPrefs.getBoolean(READER_DEFAULT_VERTICAL_MODE_KEY, true))
    }

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
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("我的书架", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        if (comicWebsites.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .padding(start = 10.dp)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                comicWebsites.forEach { website ->
                                    WebsiteShortcutIcon(
                                        website = website,
                                        onClick = { openExternalComic(context, website.url) }
                                    )
                                }
                            }
                        }
                    }
                    Box {
                        TextButton(onClick = { showMenu = true }) {
                            Text("☰", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            containerColor = Color(0xFF1C1C28)
                        ) {
                            DropdownMenuItem(
                                text = { Text("添加外链漫画", color = Color.White) },
                                onClick = {
                                    showMenu = false
                                    onAddExternalBook()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (hasLibraryFolder) "更换目录" else "选择目录", color = Color.White) },
                                onClick = {
                                    showMenu = false
                                    onPickFolder()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("添加收藏网站", color = Color.White) },
                                onClick = {
                                    showMenu = false
                                    onAddFavoriteWebsite()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("漫画阅读设置", color = Color.White) },
                                onClick = {
                                    showMenu = false
                                    showReaderSettingsMenu = true
                                }
                            )
                        }
                        DropdownMenu(
                            expanded = showReaderSettingsMenu,
                            onDismissRequest = { showReaderSettingsMenu = false },
                            containerColor = Color(0xFF1C1C28)
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (readerDefaultVerticalMode) "✓ 默认纵向浏览" else "默认纵向浏览",
                                        color = Color.White
                                    )
                                },
                                onClick = {
                                    readerDefaultVerticalMode = true
                                    sharedPrefs.edit { putBoolean(READER_DEFAULT_VERTICAL_MODE_KEY, true) }
                                    showReaderSettingsMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (!readerDefaultVerticalMode) "✓ 默认横向浏览" else "默认横向浏览",
                                        color = Color.White
                                    )
                                },
                                onClick = {
                                    readerDefaultVerticalMode = false
                                    sharedPrefs.edit { putBoolean(READER_DEFAULT_VERTICAL_MODE_KEY, false) }
                                    showReaderSettingsMenu = false
                                }
                            )
                        }
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
                            if (hasLibraryFolder) {
                                "当前目录下没有可识别的漫画文件夹或 ZIP/CBZ 压缩包。"
                            } else {
                                "书架还是空的。你可以先添加外链漫画，也可以选择本地目录进行扫描。"
                            }
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
                            onMetaChanged = { onRefreshMetadata(book) },
                            onDelete = onDeleteBook
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
    onMetaChanged: () -> Unit,
    onDelete: suspend (ComicBook) -> Boolean
) {
    val context = LocalContext.current
    var coverData by remember(book.id, metadata.coverPage, metadata.customCoverUri) { mutableStateOf<Any?>(null) }
    var showActionDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var isPressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        label = "bookCardPressScale"
    )
    val cacheZipFile = remember(book.id) { File(context.cacheDir, "cover_${book.id.hashCode()}.zip") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(book.id, metadata.coverPage, metadata.customCoverUri) {
        coverData = when {
            metadata.customCoverUri.isNotBlank() -> resolveStoredCoverModel(metadata.customCoverUri)
            book.chapters.isNotEmpty() -> ComicParser.getBookCover(context, book, cacheZipFile, metadata.coverPage - 1) ?: NoCoverImage
            else -> NoCoverImage
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .shadow(10.dp, shape = RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .pointerInput(book.id) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        try {
                            tryAwaitRelease()
                        } finally {
                            isPressed = false
                        }
                    },
                    onTap = { onClick() },
                    onLongPress = { showActionDialog = true }
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
                    NoCoverImage -> ExternalCoverPlaceholder(metadata.customName)
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
                    maxLines = 1,
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MetaPill(text = if (book.chapters.isNotEmpty()) "共 ${book.chapters.size} 章" else "外链漫画")
                    if (metadata.externalUrl.isNotBlank()) {
                        MetaPill(text = "外链", accent = true)
                    }
                    metadata.tags.filter { it.isNotBlank() }.forEach { tag ->
                        MetaPill(text = tag, accent = true)
                    }
                }
            }
        }
    }

    if (showActionDialog) {
        AlertDialog(
            onDismissRequest = { showActionDialog = false },
            title = { Text("漫画操作") },
            text = { Text("你可以编辑资料，或删除这本漫画。") },
            confirmButton = {
                Button(
                    onClick = {
                        showActionDialog = false
                        showEditDialog = true
                    }
                ) { Text("编辑") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            showActionDialog = false
                            showDeleteConfirm = true
                        }
                    ) { Text("删除", color = Color(0xFFE57373)) }
                    TextButton(onClick = { showActionDialog = false }) { Text("取消") }
                }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = {
                if (!isDeleting) showDeleteConfirm = false
            },
            title = { Text("确认删除") },
            text = {
                Text(
                    if (book.chapters.isEmpty()) {
                        "删除后将移除这本外链漫画及其配置。"
                    } else {
                        "删除后将移除这本漫画及本地文件，无法恢复。"
                    }
                )
            },
            confirmButton = {
                Button(
                    enabled = !isDeleting,
                    onClick = {
                        if (isDeleting) return@Button
                        isDeleting = true
                        scope.launch {
                            val deleted = onDelete(book)
                            isDeleting = false
                            showDeleteConfirm = false
                            if (!deleted) {
                                Toast.makeText(context, "删除失败，请检查目录权限后重试", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) { Text(if (isDeleting) "删除中..." else "确认删除") }
            },
            dismissButton = {
                TextButton(
                    enabled = !isDeleting,
                    onClick = { showDeleteConfirm = false }
                ) { Text("取消") }
            }
        )
    }

    if (showEditDialog) {
        if (metadata.openExternally && book.chapters.isEmpty()) {
            ExternalBookEditorDialog(
                existingBook = book,
                initialMetadata = metadata,
                onDismiss = { showEditDialog = false },
                onSaved = {
                    showEditDialog = false
                    onMetaChanged()
                }
            )
        } else {
            ModernEditBookMetadataDialog(
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
private fun WebsiteShortcutIcon(
    website: ComicWebsite,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("comic_progress_prefs", Context.MODE_PRIVATE) }
    var cachedIconUri by remember(website.id, website.iconUrl) {
        mutableStateOf(website.iconUrl.takeIf(::isStoredWebsiteIconAvailable).orEmpty())
    }

    LaunchedEffect(website.id, website.url, website.iconUrl) {
        if (cachedIconUri.isBlank()) {
            val downloadedIconUri = cacheWebsiteIconToAppStorage(
                context = context,
                websiteId = website.id,
                websiteUrl = website.url,
                customIconUrl = website.iconUrl.takeUnless { Uri.parse(it).scheme == "file" }.orEmpty()
            )
            if (!downloadedIconUri.isNullOrBlank()) {
                cachedIconUri = downloadedIconUri
                upsertComicWebsite(sharedPrefs, website.copy(iconUrl = downloadedIconUri))
            }
        }
    }

    val iconCandidates = remember(cachedIconUri, website.id, website.iconUrl, website.url) {
        resolveWebsiteIconModels(cachedIconUri, website.iconUrl, website.url)
    }
    var iconIndex by remember(cachedIconUri, website.id, website.iconUrl, website.url) { mutableIntStateOf(0) }
    val iconModel = iconCandidates.getOrNull(iconIndex)
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFF2F2F6))
            .pointerInput(website.id) {
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        val fallbackText = website.name.trim().take(1).ifBlank { "网" }
        WebsiteIconFallback(fallbackText)
        AsyncImage(
            model = iconModel,
            contentDescription = website.name,
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            contentScale = ContentScale.Fit,
            onError = {
                if (iconIndex < iconCandidates.lastIndex) {
                    iconIndex += 1
                }
            }
        )
    }
}

private fun resolveWebsiteIconModels(
    cachedIconUri: String,
    savedIconUrl: String,
    websiteUrl: String
): List<Any> {
    val cachedFile = cachedIconUri.toStoredWebsiteIconFile()
    if (cachedFile != null) return listOf(cachedFile)

    return websiteIconUrlCandidates(savedIconUrl, websiteUrl)
        .filter { isValidExternalUrl(it) }
        .map { it as Any }
}

private fun isStoredWebsiteIconAvailable(iconUri: String): Boolean {
    return iconUri.toStoredWebsiteIconFile() != null
}

private fun String.toStoredWebsiteIconFile(): File? {
    val uri = Uri.parse(this)
    if (uri.scheme != "file") return null
    val file = File(uri.path.orEmpty())
    return file.takeIf { it.isFile && it.length() > 0L }
}

@Composable
private fun WebsiteIconFallback(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = Color(0xFF12121A),
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1
        )
    }
}

@Composable
private fun ExternalCoverPlaceholder(title: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF2A2037), Color(0xFF151520)))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title.trim().take(1).ifBlank { "漫" },
            color = Color.White,
            fontSize = 42.sp,
            fontWeight = FontWeight.Black
        )
    }
}

@Composable
internal fun AddFavoriteWebsiteDialog(
    websites: List<ComicWebsite>,
    onDismiss: () -> Unit,
    onWebsitesChanged: () -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("comic_progress_prefs", Context.MODE_PRIVATE) }

    var inputName by remember { mutableStateOf("") }
    var inputUrl by remember { mutableStateOf("") }
    var pickedIconUri by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            saveWebsiteIconToAppStorage(context, uri)?.let { storedUri ->
                pickedIconUri = storedUri
            } ?: Toast.makeText(context, "图标保存失败，请重新选择图片", Toast.LENGTH_SHORT).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加收藏网站") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("收藏网站", fontWeight = FontWeight.Bold)
                TextField(
                    value = inputName,
                    onValueChange = { inputName = it },
                    label = { Text("网站名称") },
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = inputUrl,
                    onValueChange = { inputUrl = it },
                    label = { Text("网站地址") },
                    modifier = Modifier.fillMaxWidth()
                )
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B25)), border = BorderStroke(1.dp, Color(0xFF303041))) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("网站图标", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (pickedIconUri.isBlank()) {
                                "未选择图标。保存时会自动读取网站图标并存到本地。"
                            } else {
                                "已选择本地图标"
                            },
                            color = Color(0xFFC6C6D4),
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { imagePicker.launch(arrayOf("image/*")) }) { Text("选择图标图片") }
                            if (pickedIconUri.isNotBlank()) {
                                OutlinedButton(
                                    onClick = {
                                        deleteStoredWebsiteIcon(pickedIconUri)
                                        pickedIconUri = ""
                                    }
                                ) { Text("清除") }
                            }
                        }
                    }
                }
                Button(
                    onClick = {
                        val name = inputName.trim()
                        val url = inputUrl.trim()
                        if (name.isBlank()) {
                            Toast.makeText(context, "请先填写网站名称", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (!isValidExternalUrl(url)) {
                            Toast.makeText(context, "网站地址必须以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        scope.launch {
                            isSaving = true
                            try {
                                val websiteId = UUID.randomUUID().toString()
                                val iconUri = if (pickedIconUri.isNotBlank()) {
                                    pickedIconUri
                                } else {
                                    cacheWebsiteIconToAppStorage(
                                        context = context,
                                        websiteId = websiteId,
                                        websiteUrl = url,
                                        customIconUrl = ""
                                    ).orEmpty()
                                }
                                upsertComicWebsite(
                                    sharedPrefs,
                                    ComicWebsite(
                                        id = websiteId,
                                        name = name,
                                        url = url,
                                        iconUrl = iconUri
                                    )
                                )
                                inputName = ""
                                inputUrl = ""
                                pickedIconUri = ""
                                onWebsitesChanged()
                            } finally {
                                isSaving = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving
                ) {
                    Text(if (isSaving) "正在保存图标..." else "添加收藏网站")
                }
                if (websites.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier.height(180.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(websites, key = { _, website -> website.id }) { _, website ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    WebsiteShortcutIcon(
                                        website = website,
                                        onClick = { openExternalComic(context, website.url) }
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = website.name,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = website.url,
                                            color = Color.Gray,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        deleteStoredWebsiteIcon(website.iconUrl)
                                        removeComicWebsite(sharedPrefs, website.id)
                                        onWebsitesChanged()
                                    }
                                ) {
                                    Text("删除", color = Color(0xFFE57373))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

@Composable
internal fun ExternalBookEditorDialog(
    existingBook: ComicBook? = null,
    initialMetadata: BookMetadata? = null,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("comic_progress_prefs", Context.MODE_PRIVATE) }

    var inputName by remember { mutableStateOf(initialMetadata?.customName ?: "") }
    var inputDesc by remember { mutableStateOf(initialMetadata?.customDesc ?: "这本漫画会跳转到外部网站进行阅读。") }
    var inputTags by remember { mutableStateOf(initialMetadata?.tags?.joinToString(", ").orEmpty()) }
    var inputExternalUrl by remember { mutableStateOf(initialMetadata?.externalUrl.orEmpty()) }
    var inputCoverUri by remember { mutableStateOf(initialMetadata?.customCoverUri.orEmpty()) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            saveCoverToAppStorage(context, uri)?.let { storedUri ->
                inputCoverUri = storedUri
            } ?: Toast.makeText(context, "封面保存失败，请重新选择图片", Toast.LENGTH_SHORT).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existingBook == null) "添加外链漫画" else "编辑外链漫画") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(value = inputName, onValueChange = { inputName = it }, label = { Text("漫画名称") }, modifier = Modifier.fillMaxWidth())
                TextField(value = inputExternalUrl, onValueChange = { inputExternalUrl = it }, label = { Text("来源网站地址") }, modifier = Modifier.fillMaxWidth())
                TextField(value = inputDesc, onValueChange = { inputDesc = it }, label = { Text("简介") }, modifier = Modifier.fillMaxWidth())
                TextField(value = inputTags, onValueChange = { inputTags = it }, label = { Text("标签，使用逗号分隔") }, modifier = Modifier.fillMaxWidth())
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B25)), border = BorderStroke(1.dp, Color(0xFF303041))) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("自定义封面", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (inputCoverUri.isBlank()) "未选择封面图，将使用默认占位图。" else inputCoverUri,
                            color = Color(0xFFC6C6D4),
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { imagePicker.launch(arrayOf("image/*")) }) { Text("选择图片") }
                            if (inputCoverUri.isNotBlank()) {
                                OutlinedButton(onClick = { inputCoverUri = "" }) { Text("清除") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val name = inputName.trim()
                    val externalUrl = inputExternalUrl.trim()
                    if (name.isBlank()) {
                        Toast.makeText(context, "请先填写漫画名称", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (!isValidExternalUrl(externalUrl)) {
                        Toast.makeText(context, "外链地址必须以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show()
                        return@Button
                    }

                    val bookId = existingBook?.id ?: "$EXTERNAL_BOOK_ID_PREFIX${UUID.randomUUID()}"
                    upsertExternalBookEntry(sharedPrefs, ExternalBookEntry(bookId, name))
                    sharedPrefs.edit {
                        putString("${bookId}_custom_name", name)
                        putString("${bookId}_custom_desc", inputDesc.trim().ifBlank { "这本漫画会跳转到外部网站进行阅读。" })
                        putString("${bookId}_tags", normalizeTags(inputTags).joinToString(","))
                        putInt("${bookId}_cover_index", 1)
                        putBoolean("${bookId}_auto_next", false)
                        putBoolean("${bookId}_external_only", true)
                        putBoolean("${bookId}_open_externally", true)
                        putString("${bookId}_external_url", externalUrl)
                        putString("${bookId}_custom_cover_uri", inputCoverUri.trim())
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
internal fun BookDetailScreen(
    book: ComicBook,
    metadata: BookMetadata,
    onBack: () -> Unit,
    onEditFinished: () -> Unit,
    onRenameChapter: suspend (ComicChapter, String) -> Boolean,
    onReadChapter: (Int) -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("comic_progress_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var showEditDialog by remember { mutableStateOf(false) }
    var editingChapter by remember { mutableStateOf<ComicChapter?>(null) }
    val hasLocalChapters = book.chapters.isNotEmpty()
    val hasExternalUrl = metadata.externalUrl.isNotBlank()
    val isExternalOnly = metadata.openExternally && !hasLocalChapters
    val lastChapterIndex = remember(book.id, hasLocalChapters) {
        if (hasLocalChapters) resolveLastChapterIndex(sharedPrefs, book) else 0
    }
    var selectedChapterIndex by remember(book.id, lastChapterIndex) {
        mutableIntStateOf(lastChapterIndex.coerceIn(0, (book.chapters.size - 1).coerceAtLeast(0)))
    }

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
                TextButton(onClick = { showEditDialog = true }) { Text("编辑资料", color = Color(0xFFBB86FC)) }
            }
        },
        containerColor = Color(0xFF12121A)
    ) { paddingValues ->
        BookDetailFixedContent(
            book = book,
            metadata = metadata,
            paddingValues = paddingValues,
            hasLocalChapters = hasLocalChapters,
            hasExternalUrl = hasExternalUrl,
            lastChapterIndex = lastChapterIndex,
            selectedChapterIndex = selectedChapterIndex,
            onSelectedChapterChange = { selectedChapterIndex = it },
            onReadSelectedChapter = { onReadChapter(selectedChapterIndex) },
            onOpenExternal = { openExternalComic(context, metadata.externalUrl) },
            onRenameChapter = { editingChapter = it }
        )

        /*
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = detailListState,
                contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 32.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
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
                            MetaPill(text = if (hasLocalChapters) "共 ${book.chapters.size} 章" else "外链漫画")
                            if (hasExternalUrl) {
                                MetaPill(text = "在线阅读", accent = true)
                            }
                            metadata.tags.filter { it.isNotBlank() }.forEach { tag ->
                                MetaPill(text = tag, accent = true)
                            }
                        }
                    }
                }
            }
            if (hasExternalUrl) {
                item {
                    Button(
                        onClick = { openExternalComic(context, metadata.externalUrl) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E6BD8)),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("在线阅读", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B25)),
                        border = BorderStroke(1.dp, Color(0xFF303041))
                    ) {
                        Text(
                            text = "来源网站：${metadata.externalUrl}",
                            modifier = Modifier.padding(16.dp),
                            color = Color(0xFFBB86FC),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
            if (hasLocalChapters) {
                item {
                    Text(
                        text = "本地章节",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                itemsIndexed(book.chapters, key = { _, chapter -> chapter.id }) { index, chapter ->
                    var isChapterPressed by remember(chapter.id) { mutableStateOf(false) }
                    val canRenameChapter = !(chapter.id.endsWith("#root") && chapter.sourceUri == book.uri)
                    val chapterPressScale by animateFloatAsState(
                        targetValue = if (isChapterPressed) 0.97f else 1f,
                        label = "chapterRowPressScale"
                    )
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                scaleX = chapterPressScale
                                scaleY = chapterPressScale
                            }
                            .pointerInput(chapter.id) {
                                detectTapGestures(
                                    onPress = {
                                        isChapterPressed = true
                                        try {
                                            tryAwaitRelease()
                                        } finally {
                                            isChapterPressed = false
                                        }
                                    },
                                    onLongPress = {
                                        if (canRenameChapter) {
                                            editingChapter = chapter
                                        } else {
                                            Toast.makeText(context, "该章节使用漫画根目录，不能在这里重命名", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onTap = { onReadChapter(index) }
                                )
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (index == lastChapterIndex) Color(0xFF222238) else Color(0xFF1A1A24)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        border = if (index == lastChapterIndex) BorderStroke(1.dp, Color(0xFF4A4A76)) else null
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth(0.72f)
                                    .padding(end = 10.dp)
                            ) {
                                Text(chapter.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text(if (chapter.isZip) "ZIP/CBZ 章节" else "文件夹章节", color = Color(0xFF9A9AA8), fontSize = 12.sp)
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Color(0xFF6750A4))
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(if (index == lastChapterIndex) "继续" else "阅读")
                            }
                        }
                    }
                }
            }
            }
            if (hasLocalChapters) {
                ChapterFastScrollbar(
                    listState = detailListState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp, top = 12.dp, bottom = 12.dp)
                )
            }
        }
        */
    }

    if (showEditDialog) {
        if (isExternalOnly) {
            ExternalBookEditorDialog(
                existingBook = book,
                initialMetadata = metadata,
                onDismiss = { showEditDialog = false },
                onSaved = {
                    showEditDialog = false
                    onEditFinished()
                }
            )
        } else {
            ModernEditBookMetadataDialog(
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

    editingChapter?.let { chapter ->
        RenameChapterDialog(
            chapter = chapter,
            onDismiss = { editingChapter = null },
            onSaved = { newName ->
                scope.launch {
                    val renamed = try {
                        onRenameChapter(chapter, newName)
                    } catch (_: Exception) {
                        false
                    }
                    editingChapter = null
                    Toast.makeText(
                        context,
                        if (renamed) "章节已重命名" else "重命名失败，请检查名称或目录权限",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }
}

@Composable
private fun BookDetailFixedContent(
    book: ComicBook,
    metadata: BookMetadata,
    paddingValues: PaddingValues,
    hasLocalChapters: Boolean,
    hasExternalUrl: Boolean,
    lastChapterIndex: Int,
    selectedChapterIndex: Int,
    onSelectedChapterChange: (Int) -> Unit,
    onReadSelectedChapter: () -> Unit,
    onOpenExternal: () -> Unit,
    onRenameChapter: (ComicChapter) -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("comic_progress_prefs", Context.MODE_PRIVATE) }
    val chapterListState = rememberLazyListState()
    val readChapterIdSet = readChapterIds(sharedPrefs, book.id)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
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
                    MetaPill(text = if (hasLocalChapters) "共 ${book.chapters.size} 章" else "外链漫画")
                    if (hasExternalUrl) {
                        MetaPill(text = "在线阅读", accent = true)
                    }
                    metadata.tags.filter { it.isNotBlank() }.forEach { tag ->
                        MetaPill(text = tag, accent = true)
                    }
                }
            }
        }
        if (hasExternalUrl) {
            Button(
                onClick = onOpenExternal,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E6BD8)),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("在线阅读", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B25)),
                border = BorderStroke(1.dp, Color(0xFF303041))
            ) {
                Text(
                    text = "来源网站：${metadata.externalUrl}",
                    modifier = Modifier.padding(16.dp),
                    color = Color(0xFFBB86FC),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }
        if (hasLocalChapters) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "本地章节",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Button(
                    onClick = onReadSelectedChapter,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF2B84B),
                        contentColor = Color(0xFF12121A)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("阅读", color = Color(0xFF12121A), fontWeight = FontWeight.Bold)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = 16.dp),
                    state = chapterListState,
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(book.chapters, key = { _, chapter -> chapter.id }) { index, chapter ->
                        var isChapterPressed by remember(chapter.id) { mutableStateOf(false) }
                        val canRenameChapter = !(chapter.id.endsWith("#root") && chapter.sourceUri == book.uri)
                        val isSelectedChapter = index == selectedChapterIndex
                        val isReadChapter = chapter.id in readChapterIdSet
                        val chapterPressScale by animateFloatAsState(
                            targetValue = if (isChapterPressed) 0.98f else 1f,
                            label = "chapterRowPressScale"
                        )
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    scaleX = chapterPressScale
                                    scaleY = chapterPressScale
                                }
                                .pointerInput(chapter.id) {
                                    detectTapGestures(
                                        onPress = {
                                            isChapterPressed = true
                                            try {
                                                tryAwaitRelease()
                                            } finally {
                                                isChapterPressed = false
                                            }
                                        },
                                        onLongPress = {
                                            if (canRenameChapter) {
                                                onRenameChapter(chapter)
                                            } else {
                                                Toast.makeText(context, "该章节使用漫画根目录，不能在这里重命名", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onTap = { onSelectedChapterChange(index) }
                                    )
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = when {
                                    isSelectedChapter -> Color(0xFF2A2A44)
                                    index == lastChapterIndex -> Color(0xFF222238)
                                    else -> Color(0xFF1A1A24)
                                }
                            ),
                            shape = RoundedCornerShape(10.dp),
                            border = if (isSelectedChapter) {
                                BorderStroke(1.dp, Color(0xFF8E6BD8))
                            } else if (index == lastChapterIndex) {
                                BorderStroke(1.dp, Color(0xFF4A4A76))
                            } else {
                                null
                            }
                        ) {
                            Text(
                                text = chapter.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                color = if (isReadChapter && !isSelectedChapter) Color(0xFF777785) else Color.White,
                                fontSize = 14.sp,
                                fontWeight = if (isSelectedChapter) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                ChapterFastScrollbar(
                    listState = chapterListState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 2.dp)
                )
            }
            }
        }
    }
}

@Composable
private fun ChapterFastScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var trackHeightPx by remember { mutableIntStateOf(0) }
    var draggingThumbOffsetPx by remember { mutableStateOf<Float?>(null) }
    val layoutInfo = listState.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)

    if (totalItems <= visibleItems) return

    val minThumbHeightPx = with(density) { 44.dp.roundToPx() }
    val thumbHeightPx = if (trackHeightPx > 0) {
        (trackHeightPx * visibleItems / totalItems)
            .coerceAtLeast(minThumbHeightPx)
            .coerceAtMost(trackHeightPx)
    } else {
        minThumbHeightPx
    }
    val availableThumbTravelPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(1)
    val scrollableItems = (totalItems - visibleItems).coerceAtLeast(1)
    val thumbOffsetPx = (availableThumbTravelPx * listState.firstVisibleItemIndex / scrollableItems)
        .coerceIn(0, availableThumbTravelPx)
    val displayedThumbOffsetPx = draggingThumbOffsetPx?.roundToInt() ?: thumbOffsetPx

    fun scrollToThumbOffset(offsetPx: Float) {
        val boundedOffset = offsetPx.coerceIn(0f, availableThumbTravelPx.toFloat())
        draggingThumbOffsetPx = boundedOffset
        val targetIndex = (boundedOffset / availableThumbTravelPx * scrollableItems).roundToInt()
        scope.launch {
            listState.scrollToItem(targetIndex.coerceIn(0, totalItems - 1))
        }
    }

    Box(
        modifier = modifier
            .width(18.dp)
            .fillMaxSize()
            .onSizeChanged { trackHeightPx = it.height }
            .pointerInput(totalItems, visibleItems, trackHeightPx) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        scrollToThumbOffset(offset.y - thumbHeightPx / 2f)
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        scrollToThumbOffset((draggingThumbOffsetPx ?: thumbOffsetPx.toFloat()) + dragAmount)
                    },
                    onDragEnd = { draggingThumbOffsetPx = null },
                    onDragCancel = { draggingThumbOffsetPx = null }
                )
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxSize()
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0x332F2F45))
        )
        Box(
            modifier = Modifier
                .padding(top = with(density) { displayedThumbOffsetPx.toDp() })
                .width(8.dp)
                .height(with(density) { thumbHeightPx.toDp() })
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF8E6BD8))
        )
    }
}

@Composable
private fun RenameChapterDialog(
    chapter: ComicChapter,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit
) {
    var inputName by remember(chapter.id) { mutableStateOf(chapter.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改章节名称") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(
                    value = inputName,
                    onValueChange = { inputName = it },
                    label = { Text("章节名称") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("文件夹或压缩包名称会同步修改。", color = Color.Gray, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val newName = inputName.trim()
                    if (newName.isNotBlank()) {
                        onSaved(newName)
                    }
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
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
    var inputExternalUrl by remember { mutableStateOf(initialMetadata.externalUrl) }
    var inputCoverUri by remember { mutableStateOf(initialMetadata.customCoverUri) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            saveCoverToAppStorage(context, uri)?.let { storedUri ->
                inputCoverUri = storedUri
            } ?: Toast.makeText(context, "封面保存失败，请重新选择图片", Toast.LENGTH_SHORT).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑漫画资料") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(value = inputName, onValueChange = { inputName = it }, label = { Text("漫画名称") }, modifier = Modifier.fillMaxWidth())
                TextField(value = inputDesc, onValueChange = { inputDesc = it }, label = { Text("简介") }, modifier = Modifier.fillMaxWidth())
                TextField(value = inputTags, onValueChange = { inputTags = it }, label = { Text("标签，使用逗号分隔") }, modifier = Modifier.fillMaxWidth())
                TextField(value = inputCoverPage, onValueChange = { inputCoverPage = it }, label = { Text("封面页码") }, modifier = Modifier.fillMaxWidth())
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B25)), border = BorderStroke(1.dp, Color(0xFF303041))) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("自定义封面", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (inputCoverUri.isBlank()) "未选择封面图，将使用封面页码。" else inputCoverUri,
                            color = Color(0xFFC6C6D4),
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { imagePicker.launch(arrayOf("image/*")) }) { Text("选择图片") }
                            if (inputCoverUri.isNotBlank()) {
                                OutlinedButton(onClick = { inputCoverUri = "" }) { Text("清除") }
                            }
                        }
                    }
                }
                TextField(
                    value = inputExternalUrl,
                    onValueChange = { inputExternalUrl = it },
                    label = { Text("来源网站地址（可选）") },
                    modifier = Modifier.fillMaxWidth()
                )
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
                OutlinedButton(
                    onClick = {
                        resetReadingRecords(sharedPrefs, book.id)
                        Toast.makeText(context, "阅读记录已重置", Toast.LENGTH_SHORT).show()
                        onSaved()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("重置阅读记录")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val externalUrl = inputExternalUrl.trim()
                    if (externalUrl.isNotBlank() && !isValidExternalUrl(externalUrl)) {
                        Toast.makeText(context, "外链地址必须以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val coverPage = inputCoverPage.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    sharedPrefs.edit {
                        putString("${book.id}_custom_name", inputName.trim().ifEmpty { book.name })
                        putString("${book.id}_custom_desc", inputDesc.trim())
                        putString("${book.id}_tags", normalizeTags(inputTags).joinToString(","))
                        putInt("${book.id}_cover_index", coverPage)
                        putBoolean("${book.id}_auto_next", autoNextChapter)
                        putBoolean("${book.id}_external_only", false)
                        putBoolean("${book.id}_open_externally", false)
                        putString("${book.id}_external_url", externalUrl)
                        putString("${book.id}_custom_cover_uri", inputCoverUri.trim())
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
private fun ModernEditBookMetadataDialog(
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
    var inputExternalUrl by remember { mutableStateOf(initialMetadata.externalUrl) }
    var inputCoverUri by remember { mutableStateOf(initialMetadata.customCoverUri) }
    var showResetConfirm by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            saveCoverToAppStorage(context, uri)?.let { storedUri ->
                inputCoverUri = storedUri
            } ?: Toast.makeText(context, "封面保存失败，请重新选择图片", Toast.LENGTH_SHORT).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF231936),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("编辑漫画资料", color = Color.White, fontWeight = FontWeight.Bold)
                Text("基础信息、封面来源、阅读设置", color = Color(0xFF9C9CAA), fontSize = 12.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetadataSection(title = "基础信息") {
                    CompactMetadataTextField(
                        value = inputName,
                        onValueChange = { inputName = it },
                        label = "漫画名称"
                    )
                    CompactMetadataTextField(
                        value = inputDesc,
                        onValueChange = { inputDesc = it },
                        label = "简介",
                        singleLine = false,
                        maxLines = 2
                    )
                    CompactMetadataTextField(
                        value = inputTags,
                        onValueChange = { inputTags = it },
                        label = "标签，使用逗号分隔"
                    )
                }

                MetadataSection(title = "封面与来源") {
                    CompactMetadataTextField(
                        value = inputCoverPage,
                        onValueChange = { inputCoverPage = it },
                        label = "封面页码"
                    )
                    Text(
                        text = if (inputCoverUri.isBlank()) {
                            "未选择自定义封面，将使用封面页码。"
                        } else {
                            inputCoverUri
                        },
                        color = Color(0xFFC6C6D4),
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { imagePicker.launch(arrayOf("image/*")) },
                            modifier = Modifier.height(38.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFFD34D),
                                contentColor = Color(0xFF17110A)
                            )
                        ) {
                            Text("选择图片", fontWeight = FontWeight.Bold)
                        }
                        if (inputCoverUri.isNotBlank()) {
                            OutlinedButton(
                                onClick = { inputCoverUri = "" },
                                modifier = Modifier.height(38.dp),
                                border = BorderStroke(1.dp, Color(0xFFFFD34D)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFD34D))
                            ) {
                                Text("清除")
                            }
                        }
                    }
                    CompactMetadataTextField(
                        value = inputExternalUrl,
                        onValueChange = { inputExternalUrl = it },
                        label = "来源网站地址（可选）"
                    )
                }

                MetadataSection(title = "阅读设置") {
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
                            Text("章节结尾自动进入下一章", color = Color.White, fontSize = 14.sp)
                            Text("关闭后会在本章结束时弹出提示", color = Color(0xFF9C9CAA), fontSize = 12.sp)
                        }
                        Switch(checked = autoNextChapter, onCheckedChange = { autoNextChapter = it })
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF332245)),
                    border = BorderStroke(1.dp, Color(0xFFFFD34D)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("阅读进度重置", color = Color(0xFFFFD34D), fontWeight = FontWeight.Bold)
                        Text(
                            "清空已读章节标记和未完成页码，漫画资料不会被修改。",
                            color = Color(0xFFD8CFF0),
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                        Button(
                            onClick = { showResetConfirm = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFFD34D),
                                contentColor = Color(0xFF17110A)
                            )
                        ) {
                            Text("重置阅读进度", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val externalUrl = inputExternalUrl.trim()
                    if (externalUrl.isNotBlank() && !isValidExternalUrl(externalUrl)) {
                        Toast.makeText(context, "外链地址必须以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val coverPage = inputCoverPage.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    sharedPrefs.edit {
                        putString("${book.id}_custom_name", inputName.trim().ifEmpty { book.name })
                        putString("${book.id}_custom_desc", inputDesc.trim())
                        putString("${book.id}_tags", normalizeTags(inputTags).joinToString(","))
                        putInt("${book.id}_cover_index", coverPage)
                        putBoolean("${book.id}_auto_next", autoNextChapter)
                        putBoolean("${book.id}_external_only", false)
                        putBoolean("${book.id}_open_externally", false)
                        putString("${book.id}_external_url", externalUrl)
                        putString("${book.id}_custom_cover_uri", inputCoverUri.trim())
                    }
                    onSaved()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFD34D),
                    contentColor = Color(0xFF17110A)
                )
            ) { Text("保存", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFFD34D))
            ) { Text("取消") }
        }
    )

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            containerColor = Color(0xFF231936),
            title = { Text("重置阅读进度？", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "这会清空已读章节标记和当前未完成页码。漫画名称、简介、封面等资料不会改变。",
                    color = Color(0xFFC6C6D4)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        resetReadingRecords(sharedPrefs, book.id)
                        Toast.makeText(context, "阅读进度已重置", Toast.LENGTH_SHORT).show()
                        showResetConfirm = false
                        onSaved()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFFD34D),
                        contentColor = Color(0xFF17110A)
                    )
                ) {
                    Text("确认重置", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetConfirm = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFFD34D))
                ) { Text("取消") }
            }
        )
    }
}

@Composable
private fun CompactMetadataTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    maxLines: Int = 1
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = singleLine,
        maxLines = maxLines,
        modifier = modifier
            .fillMaxWidth()
            .height(if (singleLine) 56.dp else 72.dp)
    )
}

@Composable
private fun MetadataSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2246)),
        border = BorderStroke(1.dp, Color(0xFF6D55A6)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Color(0xFFFFD34D), fontWeight = FontWeight.Bold)
            content()
        }
    }
}
