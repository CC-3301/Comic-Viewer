package com.cc3301.comicviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.source.BookHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 阅读菜单（票 07）：书名标题、跳页滑动条、当前页/总页数、上一本/下一本按钮。
 * 拖动滑块时上方显示目标页 ±2 网格预览（带页码、目标页高亮）。
 * 无返回按钮、无模式切换、无设置入口（spec）。
 * 上一本/下一本按钮直接执行（相对：触摸区域跨书需两段式确认）。
 */
@Composable
fun ReaderMenu(
    title: String,
    currentPage: Int,
    pageCount: Int,
    handle: BookHandle,
    bookId: String,
    onSeek: (Int) -> Unit,
    onPrevBook: () -> Unit,
    onNextBook: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 只在进入菜单时初始化（拖动中 currentPage 变化不应重置滑块）
    var sliderValue by remember { mutableFloatStateOf(currentPage.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    val previewTarget = if (dragging) sliderValue.roundToInt().coerceIn(0, (pageCount - 1).coerceAtLeast(0)) else null
    val displayPage = (previewTarget ?: currentPage) + 1

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1E1E1E))
                // 吞掉面板内点击，避免穿透关闭
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )

            // 拖动时显示目标页 ±2 网格预览（页码 + 目标页高亮）
            if (previewTarget != null) {
                PreviewGrid(handle, bookId, previewTarget, pageCount)
            }

            Slider(
                value = sliderValue,
                onValueChange = {
                    sliderValue = it
                    dragging = true
                },
                onValueChangeFinished = {
                    dragging = false
                    onSeek(sliderValue.roundToInt().coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
                },
                valueRange = 0f..(pageCount - 1).coerceAtLeast(1).toFloat(),
            )

            Text(
                text = "$displayPage / $pageCount",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onPrevBook) { Text("上一本") }
                Button(onClick = onNextBook) { Text("下一本") }
            }
        }
    }
}

/** 目标页 ±2 网格预览（超出范围不渲染该格） */
@Composable
private fun PreviewGrid(handle: BookHandle, bookId: String, target: Int, pageCount: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (offset in -2..2) {
            val index = target + offset
            if (index in 0 until pageCount) {
                PreviewThumb(handle, bookId, index, highlighted = index == target)
            }
        }
    }
}

@Composable
private fun PreviewThumb(handle: BookHandle, bookId: String, index: Int, highlighted: Boolean) {
    // 5 格需适配窄屏面板宽（360dp 屏面板 ~280dp）：42dp × 5 + 6dp × 4 = 234dp
    val thumbWidthPx = with(LocalDensity.current) { 42.dp.toPx().toInt() }
    var bitmap by remember(bookId, index, thumbWidthPx) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(handle, bookId, index, thumbWidthPx) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                // 内存命中则不重新取图（票 07 AC：二次呼出不重新取图）
                PageDecoder.decodePage(handle, index, thumbWidthPx) {
                    PageDecoder.loadPageBytes(handle, index)
                }
            }.getOrNull()
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(42.dp)
                .height(58.dp)
                .background(Color.DarkGray)
                .border(
                    width = if (highlighted) 2.dp else 1.dp,
                    color = if (highlighted) Color(0xFFFF9800) else Color.Gray,
                ),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${index + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) Color(0xFFFF9800) else Color.LightGray,
        )
    }
}
