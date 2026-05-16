package com.example.comicreader

import android.content.Context
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            MainAppScreen()
        }
    }
}

sealed class ReaderMode {
    data class Folder(val uri: Uri) : ReaderMode()
    data class Zip(val uri: Uri) : ReaderMode()
}

@Composable
fun MainAppScreen() {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("comic_progress_prefs", Context.MODE_PRIVATE) }

    var rootFolderUri by remember { mutableStateOf<Uri?>(null) }
    var bookshelf by remember { mutableStateOf<List<ComicBook>>(emptyList()) }
    var currentReadingBook by remember { mutableStateOf<ComicBook?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableStateOf(0) }

    val libraryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            rootFolderUri = uri
            sharedPrefs.edit().putString("saved_root_folder_uri", uri.toString()).apply()
            isScanning = true
        }
    }

    LaunchedEffect(Unit) {
        val savedUriStr = sharedPrefs.getString("saved_root_folder_uri", null)
        if (savedUriStr != null) {
            rootFolderUri = Uri.parse(savedUriStr)
            isScanning = true
        }
    }

    LaunchedEffect(rootFolderUri, isScanning, refreshTrigger) {
        if (rootFolderUri != null && isScanning) {
            bookshelf = ComicParser.scanBookshelf(context, rootFolderUri!!)
            isScanning = false
        }
    }

    if (rootFolderUri == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1F1F2E), Color(0xFF0F0F15)))),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text("KAMI COMIC", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text("私享本地无缝漫画阅览室", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                Spacer(modifier = Modifier.height(48.dp))
                Button(
                    onClick = { libraryLauncher.launch(null) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE)),
                    modifier = Modifier.height(54.dp).width(240.dp)
                ) {
                    Text("配置文件库目录", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    } else if (currentReadingBook != null) {
        ComicReaderScreen(book = currentReadingBook!!, onBack = { currentReadingBook = null })
    } else {
        Scaffold(
            topBar = {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF0A0A0F)).statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("我的书架", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Button(
                        onClick = {
                            sharedPrefs.edit().remove("saved_root_folder_uri").apply()
                            rootFolderUri = null
                            bookshelf = emptyList()
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF252530))
                    ) {
                        Text("更换目录", color = Color(0xFFBB86FC), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            },
            containerColor = Color(0xFF12121A)
        ) { paddingValues ->
            if (isScanning) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFBB86FC))
                }
            } else if (bookshelf.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                    Text("未在选定目录下找到漫画子文件夹或ZIP包", color = Color.Gray, textAlign = TextAlign.Center)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(bookshelf) { book ->
                        BookCard(book = book, onClick = { currentReadingBook = book }, onMetaChanged = { refreshTrigger++ })
                    }
                }
            }
        }
    }
}

@Composable
fun BookCard(book: ComicBook, onClick: () -> Unit, onMetaChanged: () -> Unit) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("comic_progress_prefs", Context.MODE_PRIVATE) }

    var customName by remember(book.id) { mutableStateOf(sharedPrefs.getString("${book.id}_custom_name", book.name) ?: book.name) }
    var customDesc by remember(book.id) { mutableStateOf(sharedPrefs.getString("${book.id}_custom_desc", "暂无对该本漫画的简介描述。") ?: "暂无对该本漫画的简介描述。") }
    val coverIndex = remember(book.id) { sharedPrefs.getInt("${book.id}_cover_index", 1) - 1 }

    var coverData by remember { mutableStateOf<Any?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    val cacheZipFile = remember { File(context.cacheDir, "temp_cover_${book.name}.zip") }

    LaunchedEffect(book, coverIndex) {
        coverData = ComicParser.getBookCover(context, book, cacheZipFile, coverIndex)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, shape = RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            // ─── 核心修改 2：彻底移除悬浮按钮，升级为全卡片高级手势控：点击看漫，长按编辑 ───
            .pointerInput(book.id) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { showEditDialog = true }
                )
            },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1D1D26))
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().height(220.dp).background(Color(0xFF09090F))) {
                if (coverData != null) {
                    if (coverData is Bitmap) {
                        Image(bitmap = (coverData as Bitmap).asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        AsyncImage(model = coverData, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                } else {
                    CircularProgressIndicator(color = Color.Gray, modifier = Modifier.align(Alignment.Center).size(24.dp))
                }
                // 💡 原来遮挡封面的悬浮小铅笔按钮已被物理抹除，实现100%纯净封面呈现！
            }
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = customName, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = customDesc, color = Color(0x99FFFFFF), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
            }
        }
    }

    if (showEditDialog) {
        var inputName by remember { mutableStateOf(customName) }
        var inputDesc by remember { mutableStateOf(customDesc) }
        var inputCoverPage by remember { mutableStateOf((sharedPrefs.getInt("${book.id}_cover_index", 1)).toString()) }

        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("自定义编辑") },
            text = {
                Column {
                    TextField(value = inputName, onValueChange = { inputName = it }, label = { Text("漫画名称") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(value = inputDesc, onValueChange = { inputDesc = it }, label = { Text("简介") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(value = inputCoverPage, onValueChange = { inputCoverPage = it }, label = { Text("封面所在页码") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    val pageInt = inputCoverPage.toIntOrNull() ?: 1
                    sharedPrefs.edit().putString("${book.id}_custom_name", inputName).putString("${book.id}_custom_desc", inputDesc).putInt("${book.id}_cover_index", pageInt).apply()
                    showEditDialog = false
                    onMetaChanged()
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showEditDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
fun ComicReaderScreen(book: ComicBook, onBack: () -> Unit) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("comic_progress_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var isVerticalMode by remember { mutableStateOf(true) }

    // ─── 核心修改 1：将默认状态改为 true，现在进来看漫默认开启高阶留白间距 ───
    var hasPageGap by remember { mutableStateOf(true) }

    var folderUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var zipPageNames by remember { mutableStateOf<List<String>>(emptyList()) }
    val cacheZipFile = remember { File(context.cacheDir, "reader_${book.name}.zip") }
    var isLoading by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(false) }

    val savedPage = remember(book.id) { sharedPrefs.getInt(book.id, 0) }
    var showRestartPrompt by remember { mutableStateOf(savedPage > 0) }
    val totalPages = if (!book.isZip) folderUris.size else zipPageNames.size

    var isUserDraggingSlider by remember { mutableStateOf(false) }

    val activity = context as? ComponentActivity
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

    LaunchedEffect(book) {
        isLoading = true
        if (!book.isZip) {
            folderUris = ComicParser.getComicPagesFromFolder(context, book.uri)
        } else {
            if (ComicParser.copyZipToCache(context, book.uri, cacheZipFile)) {
                zipPageNames = ComicParser.getPagesFromZip(cacheZipFile)
            }
        }
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
    } else {
        val initialPage = if (savedPage < totalPages) savedPage else 0
        val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { totalPages })
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage)

        val currentPageIndex = if (isVerticalMode) listState.firstVisibleItemIndex else pagerState.currentPage

        LaunchedEffect(pagerState.currentPage, isVerticalMode) {
            if (!isVerticalMode) sharedPrefs.edit().putInt(book.id, pagerState.currentPage).apply()
        }
        LaunchedEffect(listState.firstVisibleItemIndex, isVerticalMode) {
            if (isVerticalMode) sharedPrefs.edit().putInt(book.id, listState.firstVisibleItemIndex).apply()
        }

        var sliderValue by remember { mutableStateOf(currentPageIndex.toFloat()) }

        LaunchedEffect(currentPageIndex) {
            if (!isUserDraggingSlider) {
                sliderValue = currentPageIndex.toFloat()
            }
        }

        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
                detectTapGestures { showControls = !showControls }
            }
        ) {
            if (!isVerticalMode) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { index ->
                    ZoomableBox { ReaderImage(index, !book.isZip, folderUris, cacheZipFile, zipPageNames) }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(if (hasPageGap) 10.dp else 0.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    itemsIndexed(
                        items = if (!book.isZip) folderUris else zipPageNames,
                        key = { index, _ -> index }
                    ) { index, _ ->
                        ReaderImage(index, !book.isZip, folderUris, cacheZipFile, zipPageNames)
                    }
                }
            }

            AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xE60A0A0F)).statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) {
                        Text("◁ 返回书架", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }

                    Row {
                        if (isVerticalMode) {
                            Button(
                                onClick = { hasPageGap = !hasPageGap },
                                colors = ButtonDefaults.buttonColors(containerColor = if (hasPageGap) Color(0xFFBB86FC) else Color(0xFF252535)),
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text(if (hasPageGap) "间距: 有" else "间距: 无", color = Color.White, fontSize = 12.sp)
                            }
                        }
                        Button(onClick = { isVerticalMode = !isVerticalMode }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF252535))) {
                            Text(if (isVerticalMode) "当前：竖读条漫" else "当前：左右翻页", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }

            AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                Column(
                    modifier = Modifier.fillMaxWidth().background(Color(0xE60A0A0F)).navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "${sliderValue.toInt() + 1}  /  $totalPages 页", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Slider(
                        value = sliderValue,
                        onValueChange = {
                            isUserDraggingSlider = true
                            sliderValue = it
                        },
                        onValueChangeFinished = {
                            isUserDraggingSlider = false
                            scope.launch {
                                val targetPage = sliderValue.toInt().coerceIn(0, totalPages - 1)
                                if (isVerticalMode) {
                                    listState.scrollToItem(targetPage)
                                } else {
                                    pagerState.scrollToPage(targetPage)
                                }
                            }
                        },
                        valueRange = 0f..((totalPages - 1).toFloat().coerceAtLeast(1f)),
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFFBB86FC), inactiveTrackColor = Color(0xFF333344))
                    )
                }
            }

            AnimatedVisibility(visible = showRestartPrompt, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 110.dp)) {
                Button(
                    onClick = {
                        showRestartPrompt = false
                        sharedPrefs.edit().putInt(book.id, 0).apply()
                        sliderValue = 0f
                        isUserDraggingSlider = false
                        scope.launch {
                            if (isVerticalMode) {
                                listState.scrollToItem(0)
                            } else {
                                pagerState.scrollToPage(0)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.height(42.dp)
                ) {
                    Text("上次看到第 ${savedPage + 1} 页，点击从头开始", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun ReaderImage(index: Int, isFolder: Boolean, folderUris: List<Uri>, cacheZipFile: File, zipPageNames: List<String>) {
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 400.dp).background(Color(0xFF0D0D12)),
        contentAlignment = Alignment.Center
    ) {
        if (isFolder) {
            AsyncImage(
                model = folderUris[index],
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                contentScale = ContentScale.FillWidth
            )
        } else {
            var bitmap by remember { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(index) {
                bitmap = ComicParser.getZipPageBitmap(cacheZipFile, zipPageNames[index])
            }
            bitmap?.let { b ->
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                    contentScale = ContentScale.FillWidth
                )
            } ?: CircularProgressIndicator(color = Color(0xFF333344))
        }
    }
}

@Composable
fun ZoomableBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    if (scale > 1f) {
                        offset = Offset(offset.x + pan.x, offset.y + pan.y)
                    } else {
                        offset = Offset.Zero
                    }
                }
            }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y
            )
    ) {
        content()
    }
}