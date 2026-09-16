package com.cc3301.comicviewer.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.reader.ReadingMode
import com.cc3301.comicviewer.core.reader.ZoomState
import com.cc3301.comicviewer.core.reader.clampPinchScale
import com.cc3301.comicviewer.core.reader.clampZoomOffset
import com.cc3301.comicviewer.core.reader.doubleTapZoom
import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.openStartIndex
import com.cc3301.comicviewer.core.touch.TapIntent
import com.cc3301.comicviewer.core.touch.pagedNextTarget
import com.cc3301.comicviewer.core.touch.pagedPrevTarget
import com.cc3301.comicviewer.core.touch.tapIntentAt
import com.cc3301.comicviewer.core.touch.webtoonNextTarget
import com.cc3301.comicviewer.core.touch.webtoonPrevTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** 打开书 + 起始页（局部 data class 不合法，提升至此） */
private data class Loaded(val handle: BookHandle, val startIndex: Int)

/** 跨书两段式确认状态（3.jpg 风格确认条） */
private data class CrossBookConfirm(
    val targetBookId: String,
    val currentLabel: String,
    val actionLabel: String,
)

/**
 * 阅读页宿主（票 07）：把条漫（LazyListState）与单页（PagerState）的差异收敛到这一层，
 * 使触摸区域、进度保存、跨书确认在两模式下共用同一份实现。
 */
private interface PageHost {
    /** 当前页（条漫 = 顶部可见页；单页 = 当前页） */
    fun currentPage(): Int

    /** 上一页/上一张；返回 false = 已在书首（交由跨书两段式确认） */
    suspend fun goPrev(): Boolean

    /** 下一页/下一张；返回 false = 已在书末（交由跨书两段式确认） */
    suspend fun goNext(): Boolean

    /** 直接定位（阅读菜单跳页） */
    suspend fun goTo(index: Int)
}

/** 条漫宿主：连续滚动，末页矮于视口时也能正确判定书末 */
private class WebtoonHost(
    private val state: LazyListState,
    private val pageCount: Int,
) : PageHost {

    override fun currentPage(): Int = state.firstVisibleItemIndex

    override suspend fun goPrev(): Boolean {
        // 长图内部（首项在屏但已滚过其顶部）：先回到当前图起始，不算翻页
        if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset > 0) {
            state.animateScrollToItem(0)
            return true
        }
        if (!state.canScrollBackward) return false
        state.animateScrollToItem(webtoonPrevTarget(state.firstVisibleItemIndex, pageCount))
        return true
    }

    override suspend fun goNext(): Boolean {
        if (!state.canScrollForward) return false
        state.animateScrollToItem(webtoonNextTarget(state.firstVisibleItemIndex, pageCount))
        return true
    }

    override suspend fun goTo(index: Int) {
        state.scrollToItem(index)
    }
}

/** 单页宿主：一次一页横向翻页（方向由 Pager 的 reverseLayout 决定，不影响点击区语义） */
private class PagedHost(
    private val state: PagerState,
    private val pageCount: Int,
) : PageHost {

    /**
     * 在飞目标页（review P2）：animateScrollToPage 过半前 state.currentPage 仍是旧值，
     * 连点两次若都读 currentPage 会算出同一目标并互相取消（只翻一页）。动画结束清空。
     */
    private var pendingPage by mutableStateOf<Int?>(null)

    override fun currentPage(): Int = pendingPage ?: state.currentPage

    override suspend fun goPrev(): Boolean {
        val target = pagedPrevTarget(pendingPage ?: state.currentPage, pageCount) ?: return false
        pendingPage = target
        state.animateScrollToPage(target)
        pendingPage = null
        return true
    }

    override suspend fun goNext(): Boolean {
        val target = pagedNextTarget(pendingPage ?: state.currentPage, pageCount) ?: return false
        pendingPage = target
        state.animateScrollToPage(target)
        pendingPage = null
        return true
    }

    override suspend fun goTo(index: Int) {
        pendingPage = null
        state.scrollToPage(index)
    }
}

/**
 * 阅读器（票 04 基础 + 票 05 进度 + 票 06 触摸区域 + 票 07 菜单/跨书/单页模式）：
 * 黑底、无返回按钮；触摸区域类型 3 在两种模式下规则统一（左=上一页、中=菜单、右=下一页）。
 */
@Composable
fun ReaderScreen(bookId: String, source: Source, onOpenBook: (String) -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }

    // 打开书 + 拉取起始页一次完成（「始终从第一页打开」语义统一走 openStartIndex，再按开关补覆盖写）
    val loaded = produceState<Loaded?>(initialValue = null, bookId) {
        value = try {
            withContext(Dispatchers.IO) {
                val h = source.openBook(bookId)
                val alwaysFirst = AppSettings.alwaysOpenFirstPage
                val start = openStartIndex(source.readProgress(bookId), alwaysFirst, h.pageCount)
                if (alwaysFirst) {
                    // 打开瞬间即覆盖进度为第 1 页（进入马上退出也只算读了 1 页）
                    source.writeProgress(bookId, 0, h.pageCount)
                }
                Loaded(h, start)
            }
        } catch (t: Throwable) {
            error = t.message ?: "打开失败"
            null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            error != null -> Text(
                "打开失败：$error",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            loaded.value == null -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
            else -> {
                val (h, startIndex) = loaded.value!!
                if (h.pageCount == 0) {
                    Text("此书没有可显示的页面", color = Color.White, modifier = Modifier.align(Alignment.Center))
                } else {
                    ReaderContent(source, bookId, h, startIndex, onOpenBook)
                }
            }
        }
    }
}

@OptIn(FlowPreview::class, ExperimentalFoundationApi::class)
@Composable
private fun ReaderContent(
    source: Source,
    bookId: String,
    handle: BookHandle,
    startIndex: Int,
    onOpenBook: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 阅读模式在设置里切换（spec 故事 25）；回到阅读器时重新读取，全局生效
    val mode = remember(bookId) { AppSettings.readingMode }
    val direction = remember(bookId) { AppSettings.pageDirection }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val pagerState = rememberPagerState(initialPage = startIndex) { handle.pageCount }

    val host: PageHost = remember(mode, listState, pagerState, handle.pageCount) {
        when (mode) {
            ReadingMode.WEBTOON -> WebtoonHost(listState, handle.pageCount)
            ReadingMode.PAGED -> PagedHost(pagerState, handle.pageCount)
        }
    }
    // 模式切换后必须重新读取设置：本 destination 离开组合即丢弃普通 remember（见 issue #8 验收记录）
    var menuVisible by remember(bookId) { mutableStateOf(false) }
    var confirm by remember(bookId) { mutableStateOf<CrossBookConfirm?>(null) }

    val currentPage by remember(host) { derivedStateOf { host.currentPage() } }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    // 相邻书查询：SAF provider IPC（listFiles/子目录探测）必须在 IO 线程（review P1）
    suspend fun neighborId(prev: Boolean): String? = withContext(Dispatchers.IO) {
        val neighbors = source.neighbors(bookId)
        if (prev) neighbors.prev else neighbors.next
    }

    // 统一进度写入：APP 级域 fire-and-forget（协程不随组合取消）
    val savePage = remember(source, bookId, handle) {
        { page: Int ->
            ServiceLocator.appScope.launch {
                runCatching { source.writeProgress(bookId, page, handle.pageCount) }
            }
            Unit
        }
    }

    // 阅读中节流保存（spec）：400ms 时间窗合并快速翻动，停顿后落盘最新页
    LaunchedEffect(host, bookId) {
        snapshotFlow { host.currentPage() }
            .drop(1)                 // 起始页已由打开逻辑写入/定位，不重写
            .debounce(400)
            .collect { page -> savePage(page) }
    }

    // 退出兜底写（节流尾窗内的停留页由此补上）
    DisposableEffect(host, bookId) {
        onDispose { savePage(host.currentPage()) }
    }

    // 菜单开启时系统返回优先关菜单
    BackHandler(enabled = menuVisible) { menuVisible = false }

    // 触摸区域类型 3（spec 故事 26）：两模式、两方向统一——左=上一页、中=菜单、右=下一页
    fun onTapZone(x: Float, width: Float) {
        when (tapIntentAt(x, width)) {
            TapIntent.MENU -> menuVisible = true
            TapIntent.PREV_PAGE -> scope.launch {
                // 书首（或条漫首图内部已到顶）→ 跨书两段式确认
                if (!host.goPrev()) {
                    val prev = neighborId(prev = true)
                    if (prev == null) toast("无上一本") else confirm = CrossBookConfirm(prev, "第一页", "上一本书")
                }
            }
            TapIntent.NEXT_PAGE -> scope.launch {
                if (!host.goNext()) {
                    val next = neighborId(prev = false)
                    if (next == null) toast("无下一本") else confirm = CrossBookConfirm(next, "最后一页", "下一本书")
                }
            }
        }
    }

    // 放大状态按页记忆（spec 故事 32）：翻页/回翻不复位；退出阅读器即丢弃（不持久化）
    val zoomByPage = remember(bookId) { mutableStateMapOf<Int, ZoomState>() }

    // 视口像素尺寸（双击锚点换算与平移边界钳制都需要）
    var viewportW by remember { mutableStateOf(0f) }
    var viewportH by remember { mutableStateOf(0f) }

    fun zoomOf(index: Int): ZoomState = zoomByPage[index] ?: ZoomState()

    // 双指缩放 + 平移（spec 故事 33/34）：条漫只做水平平移（垂直留给列表滚动），单页双向限制在图片边界内
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val page = host.currentPage()
        val cur = zoomOf(page)
        zoomByPage[page] = clampZoomOffset(
            cur.copy(
                scale = clampPinchScale(cur.scale * zoomChange),
                offsetX = cur.offsetX + panChange.x,
                offsetY = cur.offsetY + panChange.y,
            ),
            viewportW = viewportW,
            viewportH = viewportH,
            constrainVertical = mode == ReadingMode.PAGED,
        )
    }

    val gestureModifier = Modifier
        .fillMaxSize()
        .onSizeChanged {
            viewportW = it.width.toFloat()
            viewportH = it.height.toFloat()
        }
        .pointerInput(host, viewportW, viewportH) {
            detectTapGestures(
                // 双击放大（spec 故事 31）：以双击位置为中心；再次双击恢复适屏（故事 32）
                onDoubleTap = { pos ->
                    val page = host.currentPage()
                    zoomByPage[page] = if (zoomOf(page).isZoomed) {
                        ZoomState()
                    } else {
                        doubleTapZoom(pos.x, pos.y, viewportW, viewportH, AppSettings.doubleTapScale)
                    }
                },
                onTap = { pos -> onTapZone(pos.x, size.width.toFloat()) },
            )
        }
        .transformable(
            state = transformState,
            // 已在放大状态且水平位移为主时才消费手势 → 条漫放大后垂直拖动仍交给列表滚动（spec 故事 34）
            canPan = { offset -> zoomOf(host.currentPage()).isZoomed && abs(offset.x) > abs(offset.y) },
            lockRotationOnZoomPan = true,
        )

    when (mode) {
        // 条漫：黑底、全宽、垂直连续滚动
        ReadingMode.WEBTOON -> LazyColumn(modifier = gestureModifier, state = listState) {
            items(count = handle.pageCount, key = { it }) { index ->
                ReaderPage(handle, bookId, index, fitScreen = false, zoom = zoomOf(index))
            }
        }

        // 单页：一次一页、横向翻页（RTL 反转布局方向）
        ReadingMode.PAGED -> HorizontalPager(
            state = pagerState,
            modifier = gestureModifier,
            reverseLayout = direction.reverseLayout,
            key = { it },
        ) { index ->
            ReaderPage(handle, bookId, index, fitScreen = true, zoom = zoomOf(index))
        }
    }

    // 跨书两段式确认条（3.jpg）：左=当前位置灰字，右=橙色跳转按钮；点按钮才跳
    confirm?.let { state ->
        CrossBookBar(
            state = state,
            onConfirm = {
                confirm = null
                onOpenBook(state.targetBookId)
            },
            onDismiss = { confirm = null },
        )
    }

    if (menuVisible) {
        ReaderMenu(
            title = displayNameOf(bookId) ?: "阅读",
            currentPage = currentPage,
            pageCount = handle.pageCount,
            handle = handle,
            bookId = bookId,
            onSeek = { target -> scope.launch { host.goTo(target) } },
            onPrevBook = {
                scope.launch {
                    val prev = neighborId(prev = true)
                    if (prev == null) toast("无上一本") else onOpenBook(prev)
                }
            },
            onNextBook = {
                scope.launch {
                    val next = neighborId(prev = false)
                    if (next == null) toast("无下一本") else onOpenBook(next)
                }
            },
            onDismiss = { menuVisible = false },
        )
    }
}

/** 跨书确认条（两段式：首点区域弹出，点条内橙色按钮才跳转） */
@Composable
private fun CrossBookBar(
    state: CrossBookConfirm,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.85f))
                .padding(horizontal = 28.dp, vertical = 22.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.currentLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Gray,
            )
            Text(
                text = state.actionLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFFF9800),
                modifier = Modifier
                    .clickable { onConfirm() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),  // 命中区≥48dp
            )
        }
    }
}

/**
 * 阅读页（票 07）：条漫 = 全宽 FillWidth；单页 = 适屏 Fit 居中（解码宽度仍取屏宽，共用同一份缓存键）。
 */
@Composable
private fun ReaderPage(
    handle: BookHandle,
    bookId: String,
    index: Int,
    fitScreen: Boolean,
    zoom: ZoomState,
) {
    val containerModifier =
        if (fitScreen) Modifier.fillMaxSize().background(Color.Black)
        else Modifier.fillMaxWidth().background(Color.Black)
    val imageModifier = if (fitScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
    val contentScale = if (fitScreen) ContentScale.Fit else ContentScale.FillWidth

    BoxWithConstraints(
        modifier = containerModifier,
        contentAlignment = Alignment.Center,
    ) {
        val targetWidthPx = with(LocalDensity.current) { maxWidth.toPx().toInt() }
        var bitmap by remember(bookId, index, targetWidthPx) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(handle, bookId, index, targetWidthPx) {
            bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    // 缓存键含 bookId+宽度：跨书同字节数不碰撞（review P0）
                    PageDecoder.decodePage(handle, index, targetWidthPx) {
                        PageDecoder.loadPageBytes(handle, index)
                    }
                }.getOrNull()
            }
        }
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = "第 ${index + 1} 页",
                modifier = imageModifier.graphicsLayer(
                    // 以视图中心为缩放锚点（与 ZoomState 的偏移公式一致）
                    scaleX = zoom.scale,
                    scaleY = zoom.scale,
                    translationX = zoom.offsetX,
                    translationY = zoom.offsetY,
                ),
                contentScale = contentScale,
            )
        } ?: Box(Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}
