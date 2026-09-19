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
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.view.CoverDecode
import com.cc3301.comicviewer.core.view.CoverLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封面的尺寸口径（票 #46 列表档 / 票 #57 网格档）：调用方给「宽 + 口径」，
 * 盒子尺寸与裁剪判定都收在 [CoverLayout] 的纯函数里，本件只管把盒子画出来。
 */
sealed interface CoverSizing {
    /** 盒子宽度（列表档 = 行内封面列宽 56dp；网格档 = 格宽） */
    val width: Dp

    /** 列表档（票 #46）：高 = 宽 × 封面自身比例，完整显示、不裁剪 */
    data class OwnAspect(override val width: Dp) : CoverSizing

    /** 网格档（票 #57）：格子统一尺寸（高 = 宽 × [CoverLayout.GRID_CELL_ASPECT]），封面裁剪填满 */
    data class GridCell(override val width: Dp) : CoverSizing
}

/**
 * 封面（票 04 浏览列表；票 #31 书柜柜内同款；票 #46 列表档按比例铺满宽度；票 #57 网格档裁剪填满）：
 * 优先系统可解码 uri，SMB/WebDAV 等来源解不出时回退来源字节。
 * 取封面失败（来源离线/抛网络异常）只显示占位底色，不中断界面。
 *
 * 尺寸由 [sizing] 的口径决定（两档算法都在 [CoverLayout]）：
 * - [CoverSizing.OwnAspect]（列表档，票 #46）：高 = 宽 × 封面自身比例，完整显示不裁剪，
 *   解码前按 [CoverLayout.PLACEHOLDER_ASPECT] 占位（列表不会先塌陷再撑开）；
 * - [CoverSizing.GridCell]（网格档，票 #57）：盒子尺寸只由格宽与固定格比例决定，封面裁剪填满，
 *   因此超长条漫页/超宽跨页也不会改变格高、不留灰边。
 *
 * 解码宽度（票 #56）：由 [CoverSizing.width] 换算成 px 后经 [CoverDecode.targetWidthPx] 分桶定出，不再写死 128px；
 * 目标宽度进了解码缓存键与 remember/LaunchedEffect 的键，因此换档位（列数变 → 格宽变 → 桶变）会重解，
 * 不会拿上一档的位图拉伸。
 *
 * @param sizing 尺寸口径 + 盒子宽度（同时决定解码宽度）
 * @param cacheKey 解码缓存与重取的键（用条目 id：无 coverUri 的来源若用 coverUri 会全列表共用一张）
 * @param reloadKey 取字节的重取键（页面刷新计数）：它一变就重取封面并换解码缓存键
 */
@Composable
fun CoverThumb(
    coverUri: String?,
    cacheKey: String,
    loadBytes: suspend () -> ByteArray?,
    sizing: CoverSizing,
    reloadKey: Any? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val width = sizing.width
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
    // 盒子尺寸与是否裁剪都走 CoverLayout 的纯函数（口径由 sizing 选，比例从解码结果现算、不 remember：
    // 滚动时上一条目的比例不可能带到下一条（票 #46 AC））
    val aspect = bitmap?.let { CoverLayout.aspectOf(it.width, it.height) }
    val box = when (sizing) {
        is CoverSizing.OwnAspect -> CoverLayout.boxForOwnAspect(width.value, aspect)
        is CoverSizing.GridCell -> CoverLayout.boxForGridCell(width.value, aspect)
    }
    Box(
        modifier = Modifier
            .width(box.width.dp)
            .height(box.height.dp)
            .background(Color.DarkGray),
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = if (box.crop) ContentScale.Crop else ContentScale.Fit,
            )
        }
    }
}
