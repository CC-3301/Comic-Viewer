package com.cc3301.comicviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.cc3301.comicviewer.core.view.CoverDecode
import com.cc3301.comicviewer.core.view.CoverLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封面（票 04 浏览列表；票 #31 书柜柜内同款；票 #46 改成按比例铺满宽度）：
 * 优先系统可解码 uri，SMB/WebDAV 等来源解不出时回退来源字节。
 * 取封面失败（来源离线/抛网络异常）只显示占位底色，不中断界面。
 *
 * 尺寸（票 #46）：调用方只给**可用宽度**，高度 = 宽度 × 封面自身高宽比（[CoverLayout]），
 * 完整显示、不裁剪，因此不会再出现"封面装在固定方框里、比例不符时露出灰边"。
 * 解码完成前按 [CoverLayout.PLACEHOLDER_ASPECT] 占位（列表不会先塌陷再撑开）；
 * 极端比例（超长条漫页/超宽跨页）夹在兜底区间内并转成填充裁剪，只裁这种页。
 *
 * 解码宽度（票 #56）：由 [width] 换算成 px 后经 [CoverDecode.targetWidthPx] 分桶定出，不再写死 128px；
 * 目标宽度进了解码缓存键与 remember/LaunchedEffect 的键，因此换档位（列数变 → 格宽变 → 桶变）会重解，
 * 不会拿上一档的位图拉伸。
 *
 * @param width 可用宽度：网格格子 = 格宽（封面占满格宽）、浏览列表行 = 行内封面列宽；同时决定解码宽度
 * @param cacheKey 解码缓存与重取的键（用条目 id：无 coverUri 的来源若用 coverUri 会全列表共用一张）
 * @param reloadKey 取字节的重取键（页面刷新计数）：它一变就重取封面并换解码缓存键
 */
@Composable
fun CoverThumb(
    coverUri: String?,
    cacheKey: String,
    loadBytes: suspend () -> ByteArray?,
    width: Dp,
    reloadKey: Any? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val decodeWidthPx = CoverDecode.targetWidthPx(with(density) { width.toPx() })
    var bitmap by remember(coverUri, reloadKey, decodeWidthPx) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(coverUri, reloadKey, decodeWidthPx) {
        // 系统可解码的 uri 直接交给解码器（它自己先查内存缓存，命中就不碰文件）
        val fromUri = coverUri
            ?.takeIf { it.isNotEmpty() }
            // SMB/WebDAV 的标识串（smb://… / webdav-http://…）系统解不了：直接走来源字节，
            // 不白跑一次 ContentResolver
            ?.takeIf { it.startsWith("content://") || it.startsWith("file://") }
        val decodeKey = CoverDecode.key(cacheKey, reloadKey, decodeWidthPx)
        // 票 #51：位图已在内存里就**不向来源要字节**（原来无论命中与否都先取一遍字节）
        val cached = if (fromUri == null) PageDecoder.cached(decodeKey) else null
        if (cached != null) {
            bitmap = cached
            return@LaunchedEffect
        }
        bitmap = withContext(Dispatchers.IO) {
            fromUri?.let { PageDecoder.decodeUri(context, it, decodeWidthPx) }
                ?: runCatching { loadBytes() }.getOrNull()?.let { bytes ->
                    PageDecoder.decodeBytes(decodeKey, bytes, decodeWidthPx)
                }
        }
    }
    // 比例从解码结果现算（不 remember）：滚动时上一条目的比例不可能带到下一条（票 #46 AC）
    val aspect = bitmap?.let { CoverLayout.aspectOf(it.width, it.height) }
    val widthPx = with(density) { width.toPx() }
    val height = with(density) { CoverLayout.displayHeight(widthPx, aspect).toDp() }
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .background(Color.DarkGray),
    ) {
        bitmap?.let {
            // 未超限：Fit 即"正好铺满"（框就是按该比例算出来的）；超限才 Crop（只裁极端页）
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = if (CoverLayout.needsCrop(aspect)) ContentScale.Crop else ContentScale.Fit,
            )
        }
    }
}
