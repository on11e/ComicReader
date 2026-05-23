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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.AlertDialog
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

private const val DEFAULT_PAGE_GAP_DP = 10f
private const val LONG_IMAGE_HEIGHT_THRESHOLD_PX = 8192
private const val LONG_IMAGE_ASPECT_RATIO_THRESHOLD = 4f
private const val LONG_IMAGE_SLICE_HEIGHT_PX = 2048
private const val NEXT_CHAPTER_COMMIT_PAGE_THRESHOLD = 5

private data class ReaderPageRef(
    val chapterIndex: Int,
    val chapter: ComicChapter,
    val pageIndex: Int,
    val page: Any,
    val cacheZipFile: File
)

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

    var isVerticalMode by remember {
        mutableStateOf(sharedPrefs.getBoolean(READER_DEFAULT_VERTICAL_MODE_KEY, true))
    }
    var hasPageGap by remember { mutableStateOf(true) }
    var pageGapDp by remember {
        mutableFloatStateOf(
            sharedPrefs.getFloat(READER_DEFAULT_PAGE_GAP_DP_KEY, DEFAULT_PAGE_GAP_DP).coerceIn(0f, 48f)
        )
    }
    var showPageGapDialog by remember { mutableStateOf(false) }
    var currentChapterIndex by remember(book.id, initialChapterIndex) {
        mutableIntStateOf(initialChapterIndex.coerceIn(0, book.chapters.lastIndex))
    }
    var chapterPages by remember { mutableStateOf<List<Any>>(emptyList()) }
    var nextChapterPages by remember { mutableStateOf<List<Any>>(emptyList()) }
    var appendedChapterPages by remember { mutableStateOf<Map<Int, List<Any>>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0f) }
    var showRestartPrompt by remember { mutableStateOf(false) }
    var dismissNextChapterPrompt by remember { mutableStateOf(false) }
    var pendingChapterJump by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val currentChapter = book.chapters[currentChapterIndex]
    fun chapterCacheZipFile(chapter: ComicChapter): File {
        return File(context.cacheDir, "reader_${chapter.id.hashCode()}.zip")
    }
    val cacheZipFile = remember(currentChapter.id) { chapterCacheZipFile(currentChapter) }
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

    suspend fun loadChapterPages(chapter: ComicChapter, chapterCacheZipFile: File): List<Any> {
        return if (chapter.isZip) {
            if (ComicParser.copyZipToCache(context, chapter.sourceUri, chapterCacheZipFile)) {
                ComicParser.getPagesFromZip(chapterCacheZipFile)
            } else {
                emptyList()
            }
        } else {
            ComicParser.getComicPagesFromFolder(context, chapter.sourceUri)
        }
    }

    LaunchedEffect(currentChapter.id) {
        isLoading = true
        dismissNextChapterPrompt = false
        nextChapterPages = emptyList()
        appendedChapterPages = emptyMap()
        chapterPages = loadChapterPages(currentChapter, cacheZipFile)
        val unfinishedChapterId = sharedPrefs.getString(unfinishedChapterIdKey(book.id), null)
        val savedPage = if (unfinishedChapterId == currentChapter.id) {
            sharedPrefs.getInt(unfinishedPageIndexKey(book.id), 0)
        } else {
            0
        }
        sliderValue = savedPage.toFloat()
        showRestartPrompt = savedPage > 0
        if (unfinishedChapterId != currentChapter.id) {
            saveUnfinishedPosition(sharedPrefs, book.id, currentChapter.id, 0)
        } else {
            sharedPrefs.edit { putString(legacyLastChapterIdKey(book.id), currentChapter.id) }
        }
        isLoading = false
        if (metadata.autoNextChapter && currentChapterIndex < book.chapters.lastIndex) {
            val nextChapter = book.chapters[currentChapterIndex + 1]
            nextChapterPages = loadChapterPages(nextChapter, chapterCacheZipFile(nextChapter))
        }
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

    val autoNextChapter = metadata.autoNextChapter
    val currentChapterPageRefs = chapterPages.mapIndexed { index, page ->
        ReaderPageRef(
            chapterIndex = currentChapterIndex,
            chapter = currentChapter,
            pageIndex = index,
            page = page,
            cacheZipFile = cacheZipFile
        )
    }
    val nextChapter = book.chapters.getOrNull(currentChapterIndex + 1)
    val nextChapterPageRefs = if (autoNextChapter && nextChapter != null) {
        val nextCacheZipFile = chapterCacheZipFile(nextChapter)
        nextChapterPages.mapIndexed { index, page ->
            ReaderPageRef(
                chapterIndex = currentChapterIndex + 1,
                chapter = nextChapter,
                pageIndex = index,
                page = page,
                cacheZipFile = nextCacheZipFile
            )
        }
    } else {
        emptyList()
    }
    val appendedChapterPageRefs = if (autoNextChapter) {
        appendedChapterPages.toSortedMap().flatMap { (chapterIndex, pages) ->
            val chapter = book.chapters.getOrNull(chapterIndex) ?: return@flatMap emptyList()
            val chapterCacheZipFile = chapterCacheZipFile(chapter)
            pages.mapIndexed { index, page ->
                ReaderPageRef(
                    chapterIndex = chapterIndex,
                    chapter = chapter,
                    pageIndex = index,
                    page = page,
                    cacheZipFile = chapterCacheZipFile
                )
            }
        }
    } else {
        emptyList()
    }
    val readerPages = currentChapterPageRefs + nextChapterPageRefs + appendedChapterPageRefs
    val totalPages = readerPages.size.coerceAtLeast(1)
    val savedPage = if (sharedPrefs.getString(unfinishedChapterIdKey(book.id), null) == currentChapter.id) {
        sharedPrefs.getInt(unfinishedPageIndexKey(book.id), 0)
    } else {
        0
    }.coerceIn(0, totalPages - 1)

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
        var sliderDragChapterIndex by remember(currentChapter.id, isVerticalMode) { mutableStateOf<Int?>(null) }
        val currentPageIndex = if (isVerticalMode) {
            listState.firstVisibleItemIndex.coerceIn(0, totalPages - 1)
        } else {
            pagerState.currentPage.coerceIn(0, totalPages - 1)
        }
        val currentPageRef = readerPages[currentPageIndex]
        fun pageCountForChapter(chapterIndex: Int): Int {
            return when (chapterIndex) {
                currentChapterIndex -> chapterPages.size
                currentChapterIndex + 1 -> nextChapterPages.size
                else -> appendedChapterPages[chapterIndex]?.size ?: 0
            }
        }
        fun firstReaderPageIndexForChapter(chapterIndex: Int): Int {
            return readerPages.indexOfFirst { it.chapterIndex == chapterIndex }.coerceAtLeast(0)
        }
        fun readerPageIndexForChapterOrNull(chapterIndex: Int): Int? {
            return readerPages.indexOfFirst { it.chapterIndex == chapterIndex }.takeIf { it >= 0 }
        }
        val sliderChapterIndex = sliderDragChapterIndex ?: currentPageRef.chapterIndex
        val sliderChapterPageCount = pageCountForChapter(sliderChapterIndex).coerceAtLeast(1)
        val sliderRangeEnd = (sliderChapterPageCount - 1).toFloat().coerceAtLeast(1f)
        val coercedSliderValue = sliderValue.coerceIn(0f, sliderRangeEnd)
        val displayedPageIndex = if (isSliderDragging) {
            val localPage = sliderValue.roundToInt().coerceIn(0, sliderChapterPageCount - 1)
            firstReaderPageIndexForChapter(sliderChapterIndex) + localPage
        } else {
            currentPageIndex
        }.coerceIn(0, totalPages - 1)
        val displayedPageRef = readerPages[displayedPageIndex]
        val displayedChapterPageCount = pageCountForChapter(displayedPageRef.chapterIndex).coerceAtLeast(1)
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
            val targetPageRef = readerPages.getOrNull(targetPage)
            if (targetPageRef?.chapter?.isZip == true) {
                (targetPageRef.page as? String)?.let { pageName ->
                    ComicParser.getZipPageBitmap(targetPageRef.cacheZipFile, pageName)
                }
            }
            pagerState.scrollToPage(targetPage)
        }
        suspend fun scrollReaderPageToTop(targetPage: Int) {
            if (isVerticalMode) {
                listState.scrollToItem(targetPage, 0)
                withFrameNanos { }
                if (listState.firstVisibleItemIndex != targetPage) {
                    listState.scrollToItem(targetPage, 0)
                }
            } else {
                scrollHorizontalPageWithPrefetch(targetPage)
            }
        }
        fun seekToSliderPage(value: Float, force: Boolean = false) {
            val targetChapterIndex = sliderDragChapterIndex ?: currentPageRef.chapterIndex
            val targetChapterPageCount = pageCountForChapter(targetChapterIndex).coerceAtLeast(1)
            val targetPage = (
                firstReaderPageIndexForChapter(targetChapterIndex) +
                    value.roundToInt().coerceIn(0, targetChapterPageCount - 1)
                ).coerceIn(0, totalPages - 1)
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
        fun navigateHorizontalPage(pageDelta: Int) {
            if (isVerticalMode || readerScale > 1f) return
            val targetPage = (currentPageIndex + pageDelta).coerceIn(0, totalPages - 1)
            if (targetPage == currentPageIndex) return
            sliderSeekJob?.cancel()
            lastSliderTargetPage = targetPage
            sliderSeekJob = scope.launch {
                scrollHorizontalPageWithPrefetch(targetPage)
            }
        }
        fun navigateChapterFromVisible(delta: Int) {
            val targetChapterIndex = (currentPageRef.chapterIndex + delta).coerceIn(0, book.chapters.lastIndex)
            if (targetChapterIndex == currentPageRef.chapterIndex) return
            sliderSeekJob?.cancel()
            sliderDragChapterIndex = null
            isSliderDragging = false
            resetReaderZoom()
            readerPageIndexForChapterOrNull(targetChapterIndex)?.let { targetChapterFirstPage ->
                val targetChapter = book.chapters[targetChapterIndex]
                saveUnfinishedPosition(sharedPrefs, book.id, targetChapter.id, 0)
                pendingChapterJump = targetChapterIndex to 0
                val targetPage = targetChapterFirstPage.coerceIn(0, totalPages - 1)
                sliderValue = 0f
                lastSliderTargetPage = targetPage
                sliderSeekJob = scope.launch {
                    scrollReaderPageToTop(targetPage)
                }
                return
            }
            saveUnfinishedPosition(sharedPrefs, book.id, book.chapters[targetChapterIndex].id, 0)
            pendingChapterJump = targetChapterIndex to 0
            isLoading = true
            currentChapterIndex = targetChapterIndex
        }

        LaunchedEffect(pendingChapterJump, readerPages.size, currentChapter.id, isVerticalMode) {
            val pendingJump = pendingChapterJump ?: return@LaunchedEffect
            val targetChapterIndex = pendingJump.first
            val targetPageIndex = pendingJump.second
            val targetChapterFirstPage = readerPageIndexForChapterOrNull(targetChapterIndex) ?: return@LaunchedEffect
            val targetPage = (targetChapterFirstPage + targetPageIndex).coerceIn(0, totalPages - 1)
            sliderSeekJob?.cancel()
            lastSliderTargetPage = targetPage
            sliderValue = targetPageIndex.toFloat()
            scrollReaderPageToTop(targetPage)
            pendingChapterJump = null
        }

        LaunchedEffect(currentPageIndex, currentChapter.id) {
            if (pendingChapterJump != null) return@LaunchedEffect
            if (!isSliderDragging) {
                sliderValue = currentPageRef.pageIndex.toFloat()
            }
            lastSliderTargetPage = currentPageIndex
            val currentPageChapterPageCount = pageCountForChapter(currentPageRef.chapterIndex).coerceAtLeast(1)
            val isCurrentPageChapterComplete = currentPageRef.pageIndex >= currentPageChapterPageCount - 1
            val shouldCommitVisibleChapter =
                currentPageRef.chapterIndex == currentChapterIndex ||
                    currentPageRef.pageIndex + 1 >= NEXT_CHAPTER_COMMIT_PAGE_THRESHOLD.coerceAtMost(
                        currentPageChapterPageCount
                    )
            if (isCurrentPageChapterComplete) {
                markChapterRead(sharedPrefs, book.id, currentPageRef.chapter.id)
                if (sharedPrefs.getString(unfinishedChapterIdKey(book.id), null) == currentPageRef.chapter.id) {
                    clearUnfinishedPosition(sharedPrefs, book.id)
                }
                sharedPrefs.edit { putString(legacyLastChapterIdKey(book.id), currentPageRef.chapter.id) }
            } else if (shouldCommitVisibleChapter) {
                saveUnfinishedPosition(sharedPrefs, book.id, currentPageRef.chapter.id, currentPageRef.pageIndex)
            }
            if (autoNextChapter && shouldCommitVisibleChapter) {
                val chapterToAppendIndex = currentPageRef.chapterIndex + 1
                if (
                    chapterToAppendIndex > currentChapterIndex + 1 &&
                    chapterToAppendIndex <= book.chapters.lastIndex &&
                    !appendedChapterPages.containsKey(chapterToAppendIndex)
                ) {
                    val chapterToAppend = book.chapters[chapterToAppendIndex]
                    val pages = loadChapterPages(chapterToAppend, chapterCacheZipFile(chapterToAppend))
                    appendedChapterPages = appendedChapterPages + (chapterToAppendIndex to pages)
                }
            }
            if (currentPageRef.chapterIndex == currentChapterIndex && currentPageRef.pageIndex < chapterPages.size - 1) {
                dismissNextChapterPrompt = false
            }
        }

        LaunchedEffect(currentPageIndex, currentChapter.id, isVerticalMode) {
            if (!isVerticalMode) {
                listOf(currentPageIndex - 1, currentPageIndex + 1)
                    .filter { it in 0 until totalPages }
                    .forEach { pageIndex ->
                        val pageRef = readerPages[pageIndex]
                        if (pageRef.chapter.isZip) {
                            (pageRef.page as? String)?.let { pageName ->
                                ComicParser.getZipPageBitmap(pageRef.cacheZipFile, pageName)
                            }
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
                    verticalArrangement = Arrangement.spacedBy(if (hasPageGap) pageGapDp.dp else 0.dp)
                ) {
                    itemsIndexed(readerPages, key = { _, pageRef -> "${pageRef.chapter.id}#${pageRef.pageIndex}" }) { _, pageRef ->
                        ReaderImage(
                            page = pageRef.page,
                            isFolder = !pageRef.chapter.isZip,
                            cacheZipFile = pageRef.cacheZipFile,
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
                    val pageRef = readerPages[pageIndex]
                    ReaderImage(
                        page = pageRef.page,
                        isFolder = !pageRef.chapter.isZip,
                        cacheZipFile = pageRef.cacheZipFile,
                        modifier = Modifier.fillMaxSize(),
                        isFullScreen = true
                    )
                }
            }

            if (!isVerticalMode && readerScale <= 1f) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(56.dp)
                            .pointerInput(currentPageIndex, totalPages) {
                                detectTapGestures(onTap = { navigateHorizontalPage(-1) })
                            }
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(56.dp)
                            .pointerInput(currentPageIndex, totalPages) {
                                detectTapGestures(onTap = { navigateHorizontalPage(1) })
                            }
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
                    chapter = currentPageRef.chapter,
                    chapterIndex = currentPageRef.chapterIndex,
                    chapterCount = book.chapters.size,
                    isVerticalMode = isVerticalMode,
                    hasPageGap = hasPageGap,
                    pageGapDp = pageGapDp,
                    onBack = onBack,
                    onToggleMode = { isVerticalMode = !isVerticalMode },
                    onToggleGap = { hasPageGap = !hasPageGap },
                    onRequestPageGapSettings = {
                        if (hasPageGap) showPageGapDialog = true
                    }
                )
            }

            if (showPageGapDialog) {
                AlertDialog(
                    onDismissRequest = { showPageGapDialog = false },
                    containerColor = Color(0xFF1E1E28),
                    title = {
                        Text("调节页边距", color = Color.White, fontWeight = FontWeight.Bold)
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("${pageGapDp.roundToInt()} dp", color = Color(0xFFC9C9D8), fontSize = 13.sp)
                            Slider(
                                value = pageGapDp,
                                onValueChange = { value ->
                                    pageGapDp = value.roundToInt().toFloat().coerceIn(0f, 48f)
                                    sharedPrefs.edit {
                                        putFloat(READER_DEFAULT_PAGE_GAP_DP_KEY, pageGapDp)
                                    }
                                },
                                valueRange = 0f..48f,
                                steps = 47,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = Color(0xFFE7B95A),
                                    inactiveTrackColor = Color(0xFF333344)
                                )
                            )
                            Text("当前页距会作为新的默认值。", color = Color(0xFF8F8FA3), fontSize = 12.sp)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showPageGapDialog = false }) {
                            Text("完成", color = Color(0xFFE7B95A), fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                pageGapDp = DEFAULT_PAGE_GAP_DP
                                sharedPrefs.edit {
                                    putFloat(READER_DEFAULT_PAGE_GAP_DP_KEY, DEFAULT_PAGE_GAP_DP)
                                }
                            }
                        ) {
                            Text("恢复初始值", color = Color.White)
                        }
                    }
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
                    Text(
                        text = "${displayedPageRef.chapter.name}  ${displayedPageRef.pageIndex + 1} / $displayedChapterPageCount",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Slider(
                        value = coercedSliderValue,
                        onValueChange = {
                            if (!isSliderDragging) {
                                sliderDragChapterIndex = currentPageRef.chapterIndex
                            }
                            isSliderDragging = true
                            sliderValue = it
                            seekToSliderPage(it)
                        },
                        onValueChangeFinished = {
                            seekToSliderPage(sliderValue, force = true)
                            isSliderDragging = false
                            sliderDragChapterIndex = null
                        },
                        valueRange = 0f..sliderRangeEnd,
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
                                enabled = currentPageRef.chapterIndex > 0,
                                onClick = { navigateChapterFromVisible(-1) }
                            )
                            ToolbarChipButton(
                                text = "下一章",
                                enabled = currentPageRef.chapterIndex < book.chapters.lastIndex,
                                onClick = { navigateChapterFromVisible(1) }
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
                        saveUnfinishedPosition(sharedPrefs, book.id, currentChapter.id, 0)
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
                    currentPageRef.chapterIndex == currentChapterIndex &&
                    currentPageRef.pageIndex == chapterPages.size - 1 &&
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
                            Button(onClick = { navigateChapterFromVisible(1) }) { Text("进入下一章") }
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
    pageGapDp: Float,
    onBack: () -> Unit,
    onToggleMode: () -> Unit,
    onToggleGap: () -> Unit,
    onRequestPageGapSettings: () -> Unit
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ToolbarIconButton(
                            label = if (hasPageGap) "页距开" else "页距关",
                            onClick = onToggleGap,
                            onLongClick = onRequestPageGapSettings,
                            active = hasPageGap,
                            width = 62.dp
                        )
                        if (hasPageGap) {
                            Text(
                                text = "${pageGapDp.roundToInt()} dp",
                                color = Color(0xFFC9C9D8),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
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
    onLongClick: (() -> Unit)? = null,
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

    Box(
        modifier = Modifier
            .width(width)
            .height(34.dp)
            .background(containerColor, RoundedCornerShape(999.dp))
            .pointerInput(enabled, onClick, onLongClick) {
                detectTapGestures(
                    onTap = {
                        if (enabled) onClick()
                    },
                    onLongPress = {
                        if (enabled) onLongClick?.invoke()
                    }
                )
            },
        contentAlignment = Alignment.Center
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
    if (!isFullScreen) {
        VerticalReaderImage(
            page = page,
            isFolder = isFolder,
            cacheZipFile = cacheZipFile,
            modifier = modifier
        )
        return
    }

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

@Composable
private fun VerticalReaderImage(
    page: Any,
    isFolder: Boolean,
    cacheZipFile: File,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var imageSize by remember(page, cacheZipFile) { mutableStateOf<ComicImageSize?>(null) }
    var isSizeLoaded by remember(page, cacheZipFile) { mutableStateOf(false) }

    LaunchedEffect(page, cacheZipFile) {
        imageSize = if (isFolder) {
            ComicParser.getFolderPageSize(context, page as Uri)
        } else {
            ComicParser.getZipPageSize(cacheZipFile, page as String)
        }
        isSizeLoaded = true
    }

    val size = imageSize
    if (!isSizeLoaded) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 400.dp)
                .background(Color(0xFF0D0D12)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = Color(0xFF333344))
        }
        return
    }

    if (
        size != null &&
        size.isLongImage()
    ) {
        LongVerticalReaderImage(
            page = page,
            isFolder = isFolder,
            cacheZipFile = cacheZipFile,
            imageSize = size,
            modifier = modifier
        )
    } else {
        StandardVerticalReaderImage(
            page = page,
            isFolder = isFolder,
            cacheZipFile = cacheZipFile,
            modifier = modifier
        )
    }
}

@Composable
private fun StandardVerticalReaderImage(
    page: Any,
    isFolder: Boolean,
    cacheZipFile: File,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 400.dp)
            .background(Color(0xFF0D0D12)),
        contentAlignment = Alignment.Center
    ) {
        if (isFolder) {
            AsyncImage(
                model = page as Uri,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                contentScale = ContentScale.FillWidth
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
                    modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                    contentScale = ContentScale.FillWidth
                )
            } ?: CircularProgressIndicator(color = Color(0xFF333344))
        }
    }
}

@Composable
private fun LongVerticalReaderImage(
    page: Any,
    isFolder: Boolean,
    cacheZipFile: File,
    imageSize: ComicImageSize,
    modifier: Modifier = Modifier
) {
    val sliceCount = ((imageSize.height + LONG_IMAGE_SLICE_HEIGHT_PX - 1) / LONG_IMAGE_SLICE_HEIGHT_PX)
        .coerceAtLeast(1)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0D0D12))
    ) {
        repeat(sliceCount) { sliceIndex ->
            val top = sliceIndex * LONG_IMAGE_SLICE_HEIGHT_PX
            val sliceHeight = (imageSize.height - top).coerceAtMost(LONG_IMAGE_SLICE_HEIGHT_PX)
            LongImageSlice(
                page = page,
                isFolder = isFolder,
                cacheZipFile = cacheZipFile,
                top = top,
                sliceHeight = sliceHeight,
                sourceWidth = imageSize.width
            )
        }
    }
}

@Composable
private fun LongImageSlice(
    page: Any,
    isFolder: Boolean,
    cacheZipFile: File,
    top: Int,
    sliceHeight: Int,
    sourceWidth: Int
) {
    val context = LocalContext.current
    var bitmap by remember(page, cacheZipFile, top, sliceHeight) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(page, cacheZipFile, top, sliceHeight) {
        bitmap = if (isFolder) {
            ComicParser.getFolderPageRegionBitmap(
                context = context,
                pageUri = page as Uri,
                top = top,
                height = sliceHeight
            )
        } else {
            ComicParser.getZipPageRegionBitmap(
                zipFile = cacheZipFile,
                entryName = page as String,
                top = top,
                height = sliceHeight
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(sourceWidth.toFloat() / sliceHeight.toFloat())
            .background(Color(0xFF0D0D12)),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let { loadedBitmap ->
            Image(
                bitmap = loadedBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
        } ?: CircularProgressIndicator(color = Color(0xFF333344))
    }
}

private fun ComicImageSize.isLongImage(): Boolean {
    return height > LONG_IMAGE_HEIGHT_THRESHOLD_PX ||
        height.toFloat() / width.toFloat() > LONG_IMAGE_ASPECT_RATIO_THRESHOLD
}
