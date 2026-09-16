package com.cc3301.comicviewer.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.openStartIndex
import com.cc3301.comicviewer.core.touch.TouchZone
import com.cc3301.comicviewer.core.touch.touchZoneAt
import com.cc3301.comicviewer.core.touch.webtoonNextTarget
import com.cc3301.comicviewer.core.touch.webtoonPrevTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 打开书 + 起始页（局部 data class 不合法，提升至此） */
private data class Loaded(val handle: BookHandle, val startIndex: Int)

/** 跨书两段式确认状态（3.jpg 风格确认条） */
private data class CrossBookConfirm(
    val targetBookId: String,
    val currentLabel: String,
    val actionLabel: String,
)

/**
 * 条漫阅读器（票 04 基础 + 票 05 进度 + 票 06 触摸区域 + 票 07 菜单/跨书）：
 * 黑底、全宽、垂直连续滚动；触摸区域类型 3（左/右跳图、中区呼出菜单）。无返回按钮。
 *
 * 跨书（spec）：触摸区域在首页/末页触发时两段式确认（确认条内橙色按钮才跳）；
 * 菜单底部按钮直接执行；到头弹提示「无上一本/无下一本」（不置灰）。
 */
@OptIn(FlowPreview::class)
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
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)

    var menuVisible by remember(bookId) { mutableStateOf(false) }
    var confirm by remember(bookId) { mutableStateOf<CrossBookConfirm?>(null) }

    val currentPage by remember(handle) { derivedStateOf { listState.firstVisibleItemIndex } }

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

    // 阅读中节流保存（spec）：400ms 时间窗合并快速甩动，停顿后落盘最新页
    LaunchedEffect(handle, bookId) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .drop(1)                 // 起始页已由打开逻辑写入/定位，不重写
            .debounce(400)
            .collect { page -> savePage(page) }
    }

    // 退出兜底写（节流尾窗内的停留页由此补上）
    DisposableEffect(handle, bookId) {
        onDispose { savePage(listState.firstVisibleItemIndex) }
    }

    // 菜单开启时系统返回优先关菜单
    BackHandler(enabled = menuVisible) { menuVisible = false }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(handle.pageCount, bookId) {
                // 触摸区域类型 3（票 05/07）：左=上一张图（首页→上一本，两段式确认）；
                // 右=下一张图（末页→下一本，两段式确认）；中区=阅读菜单
                detectTapGestures { pos ->
                    val cur = listState.firstVisibleItemIndex
                    when (touchZoneAt(pos.x, size.width.toFloat())) {
                        TouchZone.CENTER -> menuVisible = true
                        TouchZone.LEFT -> when {
                            // 长图内部（首项在屏但已滚过其顶）：先回到当前图起始
                            cur == 0 && listState.firstVisibleItemScrollOffset > 0 ->
                                scope.launch { listState.animateScrollToItem(0) }
                            // 已是全书起点：跨书（两段式确认）
                            !listState.canScrollBackward -> scope.launch {
                                val prev = neighborId(prev = true)
                                if (prev == null) toast("无上一本")
                                else confirm = CrossBookConfirm(prev, "第一页", "上一本书")
                            }
                            else -> scope.launch {
                                listState.animateScrollToItem(webtoonPrevTarget(cur, handle.pageCount))
                            }
                        }
                        TouchZone.RIGHT -> when {
                            // 已到全书末尾：跨书（canScrollForward 判定，末页矮于视口时仍可靠）
                            !listState.canScrollForward -> scope.launch {
                                val next = neighborId(prev = false)
                                if (next == null) toast("无下一本")
                                else confirm = CrossBookConfirm(next, "最后一页", "下一本书")
                            }
                            else -> scope.launch {
                                listState.animateScrollToItem(webtoonNextTarget(cur, handle.pageCount))
                            }
                        }
                    }
                }
            },
        state = listState,
    ) {
        items(count = handle.pageCount, key = { it }) { index ->
            ReaderPage(handle, bookId, index)
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
            onSeek = { target -> scope.launch { listState.scrollToItem(target) } },
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

@Composable
private fun ReaderPage(handle: BookHandle, bookId: String, index: Int) {
    BoxWithConstraints(Modifier.fillMaxWidth().background(Color.Black)) {
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
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        } ?: Box(Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}
