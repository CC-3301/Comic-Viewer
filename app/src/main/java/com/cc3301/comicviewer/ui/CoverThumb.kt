package com.cc3301.comicviewer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.view.COVER_FADE_IN_MILLIS
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

    /** 网格档（票 #57 + 票 #106）：格子统一尺寸；[availableHeight] 不够时封面等高收缩、两侧留白 */
    data class GridCell(override val width: Dp, val availableHeight: Dp) : CoverSizing
}

/**
 * 封面（票 04 浏览列表；票 #31 书柜柜内同款；票 #46 列表档按比例铺满宽度；票 #57 网格档裁剪填满）：
 * 优先系统可解码 uri，SMB/WebDAV 等来源解不出时回退来源字节。
 * 取封面失败（来源离线/抛网络异常）只显示占位底色，不中断界面。
 *
 * 尺寸由 [sizing] 的口径决定（两档算法都在 [CoverLayout]）：
 * - [CoverSizing.OwnAspect]（列表档，票 #46）：高 = 宽 × 封面自身比例，完整显示不裁剪，
 *   解码前按 [CoverLayout.PLACEHOLDER_ASPECT] 占位（列表不会先塌陷再撑开）；
 * - [CoverSizing.GridCell]（网格档，票 #57 + 票 #106）：盒子尺寸只由格宽、格比例与
 *   [CoverSizing.GridCell.availableHeight] 决定，封面裁剪填满；可用高度不够放下格高时盒子等高收缩、
 *   宽按格比例反算（两侧留白），横屏 2 格下名字行因此恒有位置；超长条漫页/超宽跨页也不改变盒高、不留灰边。
 *
 * 解码宽度（票 #56）：由 [CoverSizing.width] 换算成 px 后经 [CoverDecode.targetWidthPx] 分桶定出，不再写死 128px；
 * 目标宽度进了解码缓存键与 remember/LaunchedEffect 的键，因此换档位（列数变 → 格宽变 → 桶变）会重解，
 * 不会拿上一档的位图拉伸。
 *
 * 解码区域（票 #81）：按 [CoverDecode.CropTarget] 给的口径（网格档=固定格比例、列表档=源比例夹到兜底区间）
 * 只解**可见带**（长条漫封面不再整张解码）；裁剪目标同样进解码缓存键与 remember/LaunchedEffect 的键，
 * 因此两档同宽（碰巧落在同一个桶）也不会互相串图、不会残留上一档的位图。
 *
 * 出图形态（票 #108 E2-B）：**骨架占位 → 出图淡入**两态——盒子底色（骨架）位图未到位时恒在，
 * 位图到位后按 [COVER_FADE_IN_MILLIS] 淡入。以前「灰底占位」「逐格补齐」「直接出现」三种观感混着的根因是
 * 位图没有过渡：占位那一帧和出图那一帧之间没有中间态，滚动速度一变就看起来像三种东西。
 * 骨架颜色沿用改动前的 `Color.DarkGray`（本票只统一形态，不定配色——配色属维护者拍板的视觉决策）。
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
    val cropTarget = when (sizing) {
        is CoverSizing.OwnAspect -> CoverDecode.CropTarget.OwnAspect
        is CoverSizing.GridCell -> CoverDecode.CropTarget.GridCell
    }
    var bitmap by remember(coverUri, reloadKey, decodeWidthPx, cropTarget) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(coverUri, reloadKey, decodeWidthPx, cropTarget) {
        // 系统可解码的 uri 直接交给解码器（它自己先查内存缓存，命中就不碰文件）
        val fromUri = coverUri
            ?.takeIf { it.isNotEmpty() }
            // SMB/WebDAV 的标识串（smb://… / webdav-http://…）系统解不了：直接走来源字节，
            // 不白跑一次 ContentResolver
            ?.takeIf { it.startsWith("content://") || it.startsWith("file://") }
        val decodeKey = CoverDecode.key(cacheKey, reloadKey, decodeWidthPx, cropTarget)
        // 票 #53：走 uri 的本地图片封面不吃重取键（下拉更新不重取），故这一路用 reloadKey=null 的键
        val uriDecodeKey = CoverDecode.key(cacheKey, null, decodeWidthPx, cropTarget)
        // 票 #51：位图已在内存里就**不向来源要字节**（原来无论命中与否都先取一遍字节）
        val cached = if (fromUri == null) PageDecoder.cached(decodeKey) else null
        if (cached != null) {
            bitmap = cached
            return@LaunchedEffect
        }
        bitmap = withContext(Dispatchers.IO) {
            fromUri?.let { PageDecoder.decodeCoverUri(context, it, uriDecodeKey, decodeWidthPx, cropTarget) }
                ?: runCatching { loadBytes() }.getOrNull()?.let { bytes ->
                    PageDecoder.decodeCoverBytes(decodeKey, bytes, decodeWidthPx, cropTarget)
                }
        }
    }
    // 盒子尺寸与是否裁剪都走 CoverLayout 的纯函数（口径由 sizing 选，比例从解码结果现算、不 remember：
    // 滚动时上一条目的比例不可能带到下一条（票 #46 AC））
    val aspect = bitmap?.let { CoverLayout.aspectOf(it.width, it.height) }
    val box = when (sizing) {
        is CoverSizing.OwnAspect -> CoverLayout.boxForOwnAspect(width.value, aspect)
        // 可用高度（票 #106）：界面按格子真拿到的纵向空间让出名字块高后传入；不够时盒子等高收缩
        is CoverSizing.GridCell -> CoverLayout.boxForGridCell(width.value, aspect, sizing.availableHeight.value)
    }
    // 出图淡入（票 #108 E2-B）：目标值在位图到位那一刻翻到 1，动画从 0 起跑——中间那些帧就是
    // 「骨架 → 出图」之间唯一的过渡形态，不再有第三种观感
    val imageAlpha by animateFloatAsState(
        targetValue = if (bitmap != null) 1f else 0f,
        animationSpec = tween(durationMillis = COVER_FADE_IN_MILLIS),
        label = "coverFadeIn",
    )
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
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = imageAlpha },
                contentScale = if (box.crop) ContentScale.Crop else ContentScale.Fit,
            )
        }
    }
}
