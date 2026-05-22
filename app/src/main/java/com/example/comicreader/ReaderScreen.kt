package com.example.comicreader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.hypot
import kotlin.math.roundToInt

@Composable
internal fun ComicReaderScreen(
    book: ComicBook,
    initialChapterIndex: Int,
    onBack: () -> Unit
) {
    val sliderSeekDebounceMillis = 80L
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
            ComicParser.trimZipCache(context)
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
        var readerScale by remember(currentChapter.id, isVerticalMode) { mutableFloatStateOf(1f) }
        var readerOffsetX by remember(currentChapter.id, isVerticalMode) { mutableFloatStateOf(0f) }
        var readerOffsetY by remember(currentChapter.id, isVerticalMode) { mutableFloatStateOf(0f) }
        var readerViewportSize by remember(currentChapter.id, isVerticalMode) { mutableStateOf(IntSize.Zero) }
        var zoomAnimationJob by remember(currentChapter.id, isVerticalMode) { mutableStateOf<Job?>(null) }
        var topControlsHeightPx by remember(currentChapter.id, isVerticalMode) { mutableIntStateOf(0) }
        var bottomControlsHeightPx by remember(currentChapter.id, isVerticalMode) { mutableIntStateOf(0) }
        fun resetReaderZoom() {
            zoomAnimationJob?.cancel()
            readerScale = 1f
            readerOffsetX = 0f
            readerOffsetY = 0f
        }
        fun animateReaderZoomTo(targetScale: Float, targetOffsetX: Float = 0f, targetOffsetY: Float = 0f) {
            zoomAnimationJob?.cancel()
            val startScale = readerScale
            val startOffsetX = readerOffsetX
            val startOffsetY = readerOffsetY
            zoomAnimationJob = scope.launch {
                Animatable(0f).animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
                ) {
                    readerScale = lerpFloat(startScale, targetScale, value)
                    readerOffsetX = lerpFloat(startOffsetX, targetOffsetX, value)
                    readerOffsetY = lerpFloat(startOffsetY, targetOffsetY, value)
                }
                readerScale = targetScale
                readerOffsetX = targetOffsetX
                readerOffsetY = targetOffsetY
                zoomAnimationJob = null
            }
        }
        fun clampReaderOffset(scale: Float, offsetX: Float, offsetY: Float): Offset {
            if (scale <= 1f || readerViewportSize == IntSize.Zero) return Offset.Zero
            val maxOffsetX = readerViewportSize.width * (scale - 1f) / 2f
            val maxOffsetY = readerViewportSize.height * (scale - 1f) / 2f
            return Offset(
                x = offsetX.coerceIn(-maxOffsetX, maxOffsetX),
                y = offsetY.coerceIn(-maxOffsetY, maxOffsetY)
            )
        }
        fun updateReaderZoom(zoomChange: Float, panChange: Offset) {
            zoomAnimationJob?.cancel()
            zoomAnimationJob = null
            val newScale = (readerScale * zoomChange).coerceIn(1f, 4f)
            if (newScale == 1f) {
                resetReaderZoom()
            } else {
                val scaleRatio = newScale / readerScale
                readerScale = newScale
                val clampedOffset = clampReaderOffset(
                    scale = newScale,
                    offsetX = (readerOffsetX + panChange.x) * scaleRatio,
                    offsetY = (readerOffsetY + panChange.y) * scaleRatio
                )
                readerOffsetX = clampedOffset.x
                readerOffsetY = clampedOffset.y
            }
        }
        val pagerState = rememberPagerState(initialPage = savedPage, pageCount = { totalPages })
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = savedPage)
        var sliderSeekJob by remember(currentChapter.id, isVerticalMode) { mutableStateOf<Job?>(null) }
        var lastSliderTargetPage by remember(currentChapter.id, isVerticalMode) { mutableIntStateOf(savedPage) }
        var isSliderDragging by remember(currentChapter.id, isVerticalMode) { mutableStateOf(false) }
        val currentPageIndex = if (isVerticalMode) {
            listState.firstVisibleItemIndex.coerceIn(0, totalPages - 1)
        } else {
            pagerState.currentPage.coerceIn(0, totalPages - 1)
        }
        val displayedPageIndex = if (isSliderDragging) {
            sliderValue.roundToInt().coerceIn(0, totalPages - 1)
        } else {
            currentPageIndex
        }
        suspend fun scrollVerticalPageIntoControlSafePosition(targetPage: Int) {
            listState.scrollToItem(targetPage)
            withFrameNanos { }
            val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetPage } ?: return
            val viewportHeight = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
            val safeTop = if (showControls) topControlsHeightPx else 0
            val safeBottom = if (showControls) bottomControlsHeightPx else 0
            val safeHeight = (viewportHeight - safeTop - safeBottom).coerceAtLeast(0)
            val desiredTop = safeTop + ((safeHeight - itemInfo.size) / 2).coerceAtLeast(0)
            val scrollDelta = itemInfo.offset - desiredTop
            if (scrollDelta != 0) {
                listState.scrollBy(scrollDelta.toFloat())
            }
        }
        suspend fun scrollHorizontalPageWithPrefetch(targetPage: Int) {
            if (currentChapter.isZip) {
                (chapterPages.getOrNull(targetPage) as? String)?.let { pageName ->
                    ComicParser.getZipPageBitmap(cacheZipFile, pageName)
                }
            }
            pagerState.scrollToPage(targetPage)
        }
        fun seekToSliderPage(value: Float, force: Boolean = false) {
            val targetPage = value.roundToInt().coerceIn(0, totalPages - 1)
            if (!force && targetPage == lastSliderTargetPage) return
            lastSliderTargetPage = targetPage
            sliderSeekJob?.cancel()
            sliderSeekJob = scope.launch {
                if (!force) {
                    delay(sliderSeekDebounceMillis)
                }
                if (isVerticalMode) {
                    scrollVerticalPageIntoControlSafePosition(targetPage)
                } else {
                    scrollHorizontalPageWithPrefetch(targetPage)
                }
            }
        }

        LaunchedEffect(currentPageIndex, currentChapter.id) {
            if (!isSliderDragging) {
                sliderValue = currentPageIndex.toFloat()
            }
            lastSliderTargetPage = currentPageIndex
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

        LaunchedEffect(currentPageIndex, currentChapter.id, currentChapter.isZip) {
            if (currentChapter.isZip) {
                listOf(currentPageIndex - 1, currentPageIndex + 1)
                    .filter { it in 0 until totalPages }
                    .forEach { pageIndex ->
                        (chapterPages.getOrNull(pageIndex) as? String)?.let { pageName ->
                            ComicParser.getZipPageBitmap(cacheZipFile, pageName)
                        }
                    }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { readerViewportSize = it }
                .readerZoomGestures(
                    scale = readerScale,
                    onTransform = ::updateReaderZoom,
                    onDoubleTap = {
                        if (readerScale > 1f) {
                            animateReaderZoomTo(1f)
                        } else {
                            animateReaderZoomTo(2.5f)
                        }
                    },
                    onTap = { showControls = !showControls }
                )
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
        ) {
            if (isVerticalMode) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = readerScale
                            scaleY = readerScale
                            translationX = readerOffsetX
                            translationY = readerOffsetY
                        },
                    verticalArrangement = Arrangement.spacedBy(if (hasPageGap) 10.dp else 0.dp)
                ) {
                    itemsIndexed(chapterPages, key = { index, _ -> "${currentChapter.id}#$index" }) { _, page ->
                        ReaderImage(
                            page = page,
                            isFolder = !currentChapter.isZip,
                            cacheZipFile = cacheZipFile,
                            isFullScreen = false
                        )
                    }
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 1,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = readerScale
                            scaleY = readerScale
                            translationX = readerOffsetX
                            translationY = readerOffsetY
                        }
                ) { pageIndex ->
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
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .onSizeChanged { topControlsHeightPx = it.height }
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
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .onSizeChanged { bottomControlsHeightPx = it.height },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "第 ${displayedPageIndex + 1} / $totalPages 页", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Slider(
                        value = sliderValue,
                        onValueChange = {
                            isSliderDragging = true
                            sliderValue = it
                            seekToSliderPage(it)
                        },
                        onValueChangeFinished = {
                            isSliderDragging = false
                            seekToSliderPage(sliderValue, force = true)
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
private fun Modifier.readerZoomGestures(
    scale: Float,
    onTransform: (Float, Offset) -> Unit,
    onDoubleTap: () -> Unit,
    onTap: () -> Unit
): Modifier {
    val currentScale by rememberUpdatedState(scale)
    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            var previousCentroid: Offset? = null
            var previousDistance = 0f
            while (true) {
                val event = awaitPointerEvent()
                val pressedChanges = event.changes.filter { it.pressed }
                when {
                    pressedChanges.size >= 2 -> {
                        val positions = pressedChanges.map { it.position }
                        val centroid = positions.centroid()
                        val distance = positions.averageDistanceTo(centroid)
                        val previous = previousCentroid
                        if (previous != null && previousDistance > 0f) {
                            val zoomChange = (distance / previousDistance).takeIf { it.isFinite() && it > 0f } ?: 1f
                            onTransform(zoomChange, centroid - previous)
                        }
                        previousCentroid = centroid
                        previousDistance = distance
                        pressedChanges.forEach { it.consume() }
                    }
                    currentScale > 1f && pressedChanges.size == 1 -> {
                        val change = pressedChanges.first()
                        onTransform(1f, change.position - change.previousPosition)
                        change.consume()
                        previousCentroid = null
                        previousDistance = 0f
                    }
                    else -> {
                        previousCentroid = null
                        previousDistance = 0f
                    }
                }
            }
        }
    }
    .pointerInput(scale) {
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = { onDoubleTap() }
        )
    }
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction
}

private fun List<Offset>.centroid(): Offset {
    if (isEmpty()) return Offset.Zero
    val sum = fold(Offset.Zero) { acc, offset -> acc + offset }
    return sum / size.toFloat()
}

private fun List<Offset>.averageDistanceTo(center: Offset): Float {
    if (isEmpty()) return 0f
    return map { offset -> hypot(offset.x - center.x, offset.y - center.y) }.average().toFloat()
}

@Composable
private fun ReaderImage(
    page: Any,
    isFolder: Boolean,
    cacheZipFile: File,
    modifier: Modifier = Modifier,
    isFullScreen: Boolean
) {
    val sizedImageModifier = if (isFullScreen) {
        Modifier.fillMaxSize()
    } else {
        Modifier.fillMaxWidth().wrapContentHeight()
    }

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
                modifier = sizedImageModifier,
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
                    modifier = sizedImageModifier,
                    contentScale = if (isFullScreen) ContentScale.Fit else ContentScale.FillWidth
                )
            } ?: CircularProgressIndicator(color = Color(0xFF333344))
        }
    }
}
