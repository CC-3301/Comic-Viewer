package com.cc3301.comicviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
 * 阅读菜单（票 07 / 票 28）：书名标题、跳页滑动条、当前页/总页数、上一本/下一本按钮。
 * 面板贴屏幕底部、半透明（不铺满全屏深色遮罩，当前页保持可见），高度不超过视口 60%（横屏也不遮没当前页）。
 * 打开即显示当前页 ±2 网格预览（带页码、当前页高亮）；拖动滑块时预览跟随目标页，松手后回到当前页。
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
    // 滑块初值取当前页；非拖动时由下方 LaunchedEffect 跟随 currentPage
    var sliderValue by remember { mutableFloatStateOf(currentPage.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    val lastPage = (pageCount - 1).coerceAtLeast(0)
    val sliderPage = sliderValue.roundToInt().coerceIn(0, lastPage)
    // 菜单打开即显示当前页 ±2 预览；拖动中跟随滑块目标页，松手后回到当前页
    val previewTarget = if (dragging) sliderPage else currentPage.coerceIn(0, lastPage)
    val displayPage = previewTarget + 1

    // 菜单打开时用音量键/滚轮翻页：currentPage 变了滑块必须跟上，否则常显预览与滑块位置互相矛盾。
    // 以 currentPage 为 key：只在页面变化时同步；拖动中不回写（key 未变时不触发，跨页拖动时由 !dragging 挡住），
    // 松手瞬间不主动回写——避免 onSeek 的 goTo 落地前把滑块闪回旧页。
    LaunchedEffect(currentPage) {
        if (!dragging) sliderValue = currentPage.toFloat()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // 面板外任意空白处点击关闭菜单；不再铺满全屏深色遮罩（当前页保持可见）
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        // 面板高度上限 = 视口高度的 60%：SPEC 支持横屏，若不设上限，横屏（视口高约 360dp）下
        // 按内容排布会占 ~73%~92% 窗高，把本票要解决的「遮住当前页」重新引回来。
        // 取 60% 而非更小值：竖屏（视口高约 780dp）的 60% ≈ 468dp 仍大于面板内容高度
        // （标题+网格+滑块+页码+按钮，估算约 310dp），竖屏观感与 r1 一致、不被压小；
        // 横屏下上限约 216dp，剩余 ≥ 40% 屏高留给当前页（当前页顶部约 144dp 可见）；
        // 面板超出上限时整体可滚动，滑动条与页码始终可达、不被裁掉。
        val panelMaxHeight = maxHeight * 0.6f
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = panelMaxHeight)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                // 半透明面板：底下的当前页仍看得清
                .background(Color(0xFF1E1E1E).copy(alpha = 0.85f))
                // 吞掉面板内点击，避免穿透关闭
                .pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
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

            // 常显当前页 ±2 网格预览（页码 + 当前/目标页高亮）；越界格不渲染
            PreviewGrid(handle, bookId, previewTarget, pageCount)

            Slider(
                value = sliderValue,
                onValueChange = {
                    sliderValue = it
                    dragging = true
                },
                onValueChangeFinished = {
                    dragging = false
                    onSeek(sliderPage)
                },
                valueRange = 0f..lastPage.coerceAtLeast(1).toFloat(),
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

/** 目标页 ±2 网格预览（超出范围不渲染该格；目标页=当前页或拖动目标页） */
@Composable
private fun PreviewGrid(handle: BookHandle, bookId: String, target: Int, pageCount: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (offset in -2..2) {
            val index = target + offset
            if (index in 0 until pageCount) {
                // key=书+页：窗口每移一格时重叠格复用 remember 状态，拖动中预览不闪空
                key(bookId, index) {
                    PreviewThumb(handle, bookId, index, highlighted = index == target)
                }
            }
        }
    }
}

@Composable
private fun PreviewThumb(handle: BookHandle, bookId: String, index: Int, highlighted: Boolean) {
    // 5 格需适配窄屏面板内宽（360dp 屏、贴底全宽面板减两侧 20dp 内边距 ≈ 320dp）：42dp × 5 + 6dp × 4 = 234dp
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
                // Fit（票 #34）：预览缩略图同样完整显示不裁剪，竖版页面在 42×58 格子里留白即可
                Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
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
