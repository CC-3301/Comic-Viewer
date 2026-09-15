package com.cc3301.comicviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.foundation.layout.BoxWithConstraints
import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 最简条漫阅读器（票 04）：黑底、全宽、垂直连续滚动；无菜单/无触摸区域/无进度保存（后续票）。
 * 无返回按钮（系统返回手势退出）。
 */
@Composable
fun ReaderScreen(bookId: String, source: Source) {
    val handle = produceState<BookHandle?>(initialValue = null, bookId) {
        value = try {
            source.openBook(bookId)
        } catch (t: Throwable) {
            null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val h = handle.value) {
            null -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
            else -> {
                if (h.pageCount == 0) {
                    Text("此书没有可显示的页面", color = Color.White, modifier = Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(count = h.pageCount, key = { it }) { index ->
                            ReaderPage(h, index)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderPage(handle: BookHandle, index: Int) {
    BoxWithConstraints(Modifier.fillMaxWidth().background(Color.Black)) {
        val targetWidthPx = with(LocalDensity.current) { maxWidth.toPx().toInt() }
        var bitmap by remember(index, targetWidthPx) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(handle, index, targetWidthPx) {
            bitmap = withContext(Dispatchers.IO) {
                try {
                    val page = handle.loadPage(index)
                    PageDecoder.decodeBytes("$index@${page.bytes.size}", page.bytes, targetWidthPx)
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
