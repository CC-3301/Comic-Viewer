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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.source.PerfTiming
import com.cc3301.comicviewer.core.view.COVER_FADE_IN_MILLIS
import com.cc3301.comicviewer.core.view.CoverDecode
import com.cc3301.comicviewer.core.view.CoverLayout
import com.cc3301.comicviewer.core.view.ScrollProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封面的**口径**（票 #46 列表档 / 票 #57 网格档，票 #135 起同时是解码的输入）：宽度 + 档位（子型）。
 * 盒子尺寸与裁剪判定都收在 [CoverLayout] 的纯函数里，本件只管把盒子画出来。
 *
 * 它是**盒子与解码的共同输入**：[CoverPlan] 的盒宽、解码宽度、裁剪目标全由它派生（见 `CoverPlan.kt`），
 * 因此「盒子按列表档画、解码按网格档解」这种分叉在类型上不存在。
 *
 * 网格档的**可用高度**不在这里：那是布局输入（只决定盒子高度、不进解码缓存键），由 [CoverThumb] 的
 * `gridCellAvailableHeight` 单独给——预取侧没有格子空间可给，只拿它推方案（不渲染）。
 */
sealed interface CoverSizing {
    /** 盒宽（列表档 = 行内封面列宽 56dp；网格档 = 格宽）：盒子与解码宽度取的都是它 */
    val width: Dp

    /** 列表档（票 #46）：高 = 宽 × 封面自身比例，完整显示、不裁剪 */
    data class OwnAspect(override val width: Dp) : CoverSizing

    /** 网格档（票 #57 + 票 #106）：格子统一尺寸，封面裁剪填满、盒子宽 = 格宽 */
    data class GridCell(override val width: Dp) : CoverSizing
}

/**
 * 封面（票 04 浏览列表；票 #31 书柜柜内同款；票 #46 列表档按比例铺满宽度；票 #57 网格档裁剪填满）：
 * 优先系统可解码 uri，SMB/WebDAV 等来源解不出时回退来源字节。
 * 取封面失败（来源离线/抛网络异常）只显示占位底色，不中断界面。
 *
 * 尺寸由 [plan] 的口径（[CoverPlan.sizing] → [CoverPlan.cropTarget]）定（两档算法都在 [CoverLayout]）：
 * - [CoverSizing.OwnAspect]（列表档，票 #46）：高 = 宽 × 封面自身比例，完整显示不裁剪，
 *   解码前按 [CoverLayout.PLACEHOLDER_ASPECT] 占位（列表不会先塌陷再撑开）；
 * - [CoverSizing.GridCell]（网格档，票 #57 + 票 #106）：盒子尺寸由格宽（[CoverPlan.widthDp]）、格比例与
 *   `gridCellAvailableHeight` 决定，封面裁剪填满；可用高度不够放下格高时盒子等高收缩、宽按格比例反算
 *   （两侧留白），横屏 2 格下名字行因此恒有位置；超长条漫页/超宽跨页也不改变盒高、不留灰边。
 *
 * 解码宽度（票 #56）：由调用方给的 [plan]（票 #135）定出（宽度桶 + 裁剪目标 + 重取键都在它里面），
 * 不再写死 128px；方案进了解码缓存键与 remember/LaunchedEffect 的键，因此换档位（列数变 → 格宽变 → 桶变）
 * 会重解，不会拿上一档的位图拉伸。**本件不再自己算解码参数、也不再另收一份尺寸**：盒宽与解码口径取的是
 * [plan] 里同一个口径（可见行与预取用的是同一个 [CoverPlan] 实例），两边各算一份会出现「预取解好的那张
 * 这里命不中」的静默白干，盒子与解码各取一份实参则会出现「网格裁过的图按列表档铺进 56dp 盒子」（票 #135）。
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
 * 可见性 `internal`（票 #135）：参数里的 [CoverPlan] 是模块内部类型（它的裁剪目标取自内部的
 * `CoverDecode.CropTarget`），与 [BrowseRow]、[BrowserGridCell] 同一档。
 *
 * @param plan 取图方案（票 #135）：**唯一的尺寸输入**——盒宽、档位、解码宽度桶、裁剪目标、重取键都在它里面，
 *   与预取侧**同一份**（[CoverThumb] 不再另收一个尺寸实参，盒宽与解码因此不可能分叉）
 * @param gridCellAvailableHeight 网格档的可用高度（票 #106）：**只进盒子高度、不进解码**；列表档不传
 * @param cacheKey 解码缓存与取字节的条目键（用条目 id：无 coverUri 的来源若用 coverUri 会全列表共用一张）
 */
@Composable
internal fun CoverThumb(
    coverUri: String?,
    cacheKey: String,
    loadBytes: suspend () -> ByteArray?,
    plan: CoverPlan,
    gridCellAvailableHeight: Dp? = null,
) {
    val context = LocalContext.current
    // 滚动量测（票 #109）：本 composable 体执行一次 = 封面层一次实际重组
    if (PerfTiming.isOn) BrowseScroll.probe.onCoverComposed()
    val width = plan.widthDp
    // 取图通路（走 uri 还是来源字节）与两条路各自的键都由 [plan] 给出：与浏览页的预取同一个方案实例
    // （票 #135）。键与方案都进 remember/LaunchedEffect 的键，换档位/下拉更新因此重解。
    var bitmap by remember(coverUri, plan) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(coverUri, plan) {
        val route = plan.route(cacheKey, coverUri)
        // 票 #51：位图已在内存里就**不向来源要字节**（原来无论命中与否都先取一遍字节）；
        // 走 uri 那条路由解码器自己查内存缓存，这里不查（票 #108 r3 的口径不变）
        val cached = if (route.viaSourceBytes) PageDecoder.cachedCover(route.bytesKey) else null
        if (cached != null) {
            bitmap = cached
            return@LaunchedEffect
        }
        bitmap = withContext(Dispatchers.IO) {
            // 滚动量测（票 #109）：这一格封面「取字节 + 解码」的整段耗时 + 执行它的线程（恒为 `Dispatchers.IO`
            // 的工作线程）。与阅读器的 `pageBytes`/`pageDecode` 不同，这里**不拆**两段：封面这张图上两条路
            // （uri 直解 / 来源字节）各自都要先拿到图才能解，拆开只会多一层测量噪声；真机上要的是
            // 「这一格慢在哪条线程、慢到什么量级」（「取字节与解码不能分开量」已记入残余风险）。
            val measure = PerfTiming.isOn
            val startedNanos = if (measure) System.nanoTime() else 0L
            val threadName = if (measure) Thread.currentThread().name else ""
            // 系统可解码的 uri 直接交给解码器（它自己先查内存缓存，命中就不碰文件）；解不出来才回退来源字节
            // （既有行为：`decodeCoverUri` 返回 null 时仍走下面那条）
            val loaded = route.uri?.let { PageDecoder.decodeCoverUri(context, it, route.uriKey, plan.widthPx, plan.cropTarget) }
                ?: runCatching { loadBytes() }.getOrNull()?.let { bytes ->
                    PageDecoder.decodeCoverBytes(route.bytesKey, bytes, plan.widthPx, plan.cropTarget)
                }
            if (measure) {
                val millis = (System.nanoTime() - startedNanos) / 1_000_000
                BrowseScroll.probe.onCoverLoad(millis, threadName)
                PerfTiming.log { ScrollProbe.coverLoadLine(millis, threadName) }
            }
            loaded
        }
    }
    // 盒子尺寸与是否裁剪都走 CoverLayout 的纯函数（口径由方案的档位选，比例从解码结果现算、不 remember：
    // 滚动时上一条目的比例不可能带到下一条（票 #46 AC））
    val aspect = bitmap?.let { CoverLayout.aspectOf(it.width, it.height) }
    // 盒子口径与解码口径取的是**方案里同一个** cropTarget：两档不可能一边按列表档画、一边按网格档解
    val box = when (plan.cropTarget) {
        CoverDecode.CropTarget.OwnAspect -> CoverLayout.boxForOwnAspect(width.value, aspect)
        // 可用高度（票 #106）：界面按格子真拿到的纵向空间让出名字块高后传入；不够时盒子等高收缩。
        // 缺它就是接线错了（网格档盒子必须有空间可用），直接抛而不是拿一个兜底高度画歪
        CoverDecode.CropTarget.GridCell -> CoverLayout.boxForGridCell(
            width.value,
            aspect,
            (gridCellAvailableHeight ?: error("网格档封面缺可用高度：盒宽 ${width} 的格子没给 gridCellAvailableHeight")).value,
        )
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
