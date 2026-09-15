package com.cc3301.comicviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.openStartIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 打开书 + 起始页（局部 data class 不合法，提升至此） */
private data class Loaded(val handle: BookHandle, val startIndex: Int)

/**
 * 条漫阅读器（票 04 基础 + 票 05 进度）：
 * 黑底、全宽、垂直连续滚动；无菜单/触摸区域（后续票）。无返回按钮。
 *
 * 进度（票 05）：打开拉取进度定位（「始终从第一页打开」开启时定位第 1 页并立即覆盖进度）；
 * 当前页变化即节流写 Room；退出 DisposableEffect 兜底写（APP 级协程域）。
 */
@Composable
fun ReaderScreen(bookId: String, source: Source) {
    var error by remember { mutableStateOf<String?>(null) }

    // 打开书 + 拉取起始页一次完成（「始终从第一页打开」在此生效）
    val loaded = produceState<Loaded?>(initialValue = null, bookId) {
        value = try {
            withContext(Dispatchers.IO) {
                val h = source.openBook(bookId)
                val start = if (AppSettings.alwaysOpenFirstPage) {
                    // 打开瞬间即覆盖进度为第 1 页（进入马上退出也只算读了 1 页）
                    source.writeProgress(bookId, 0, h.pageCount)
                    0
                } else {
                    openStartIndex(source.readProgress(bookId), alwaysFirstPage = false, pageCount = h.pageCount)
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
                    ReaderContent(source, bookId, h, startIndex)
                }
            }
        }
    }
}

@Composable
private fun ReaderContent(source: Source, bookId: String, handle: BookHandle, startIndex: Int) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)

    // 当前页（屏幕顶部可见页）变化即节流写进度
    val currentPage by remember(handle) {
        derivedStateOf { listState.firstVisibleItemIndex }
    }
    LaunchedEffect(handle, currentPage) {
        withContext(Dispatchers.IO) {
            runCatching { source.writeProgress(bookId, currentPage, handle.pageCount) }
        }
    }

    // 退出兜底写（协程可能已随组合取消 → 用 APP 级域）
    DisposableEffect(handle, bookId) {
        onDispose {
            val page = listState.firstVisibleItemIndex
            ServiceLocator.appScope.launch {
                runCatching { source.writeProgress(bookId, page, handle.pageCount) }
            }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
        items(count = handle.pageCount, key = { it }) { index ->
            ReaderPage(handle, bookId, index)
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
                try {
                    val page = handle.loadPage(index)
                    // 缓存键含 bookId+宽度：跨书同字节数不碰撞（review P0）
                    PageDecoder.decodeBytes("$bookId#$index@$targetWidthPx", page.bytes, targetWidthPx)
                } catch (t: Throwable) {
                    null
                }
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
