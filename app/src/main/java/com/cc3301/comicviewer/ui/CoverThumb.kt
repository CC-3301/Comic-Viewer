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
import com.cc3301.comicviewer.core.view.CoverLoadMeasurement
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
 * 位图状态的键（票 #135 r2 b5，纯函数）：`remember` 与 `LaunchedEffect` 都读它——两处各写一份键集就会出现
 * 「重置位图的那一处没跟着改」这类静默回归（r1 就是把整份 [CoverPlan] 当键的那一次）。
 *
 * 键**只取位图与解码缓存键实际依赖的量**：`coverUri` + 分桶后的目标宽度 [CoverPlan.widthPx] +
 * 裁剪目标 [CoverPlan.cropTarget] + 重取键 [CoverPlan.reloadKey]。`plan.route` 另吃一个实参 `entryId`
 * （查询鍵串里含条目 id，见 [CoverPlan.route]）——本键集不含它，因为「槽位身份 = 条目 id」今天成立
 * （两个调用点都在按 `entry.id` 作键的 Lazy 项里，同一槽位内不会换条目）；若哪天同一槽位内换条目，
 * 位图状态与 effect 都不会重置（这个缺口改动前就有，票 #146 未加重）。
 * 查询键那一侧为什么知道是哪一条：见 [coverCacheKey]——它给的两个键串里都含**条目 id**（[CoverDecode.key] 的第一个实参）。
 * **不含** [CoverPlan.sizing] 的原始 dp 宽与 [CoverPlan.density]：同一解码桶内窗口/内容宽变化
 * （多窗口、折叠、inset 变动）时桶不变 ⇒ 位图不重置、不闪一帧骨架。
 *
 * 判别力由 `CoverPlanTest` 的两条用例钉住：「同一桶内换原始宽/密度不换键」与「跳桶/换档/换重取键/换 uri 必换键」。
 */
internal fun coverBitmapKey(coverUri: String?, plan: CoverPlan): List<Any?> =
    listOf(coverUri, plan.widthPx, plan.cropTarget, plan.reloadKey)

/**
 * 这条取图通路的**内存缓存键**（票 #146 ③，纯函数）：命中查询与两条解码路**入缓存**用的是同一把——
 * 走 uri 的那条由 `PageDecoder.decodeCoverUri` 按 [CoverRoute.uriKey] 入封面分区（不带重取键，票 #53），
 * 走来源字节的那条由 `PageDecoder.decodeCoverBytes` 按 [CoverRoute.bytesKey] 入同一分区（带重取键）。
 * 两个键串里都含**条目 id**（[CoverDecode.key] 的第一个实参）⇒ 键区分条目，同一槽位内换条目会查不到旧条目那张。
 *
 * 选键按 [CoverRoute.viaSourceBytes]（判据只此一处，见 [com.cc3301.comicviewer.core.view.CoverUriSource]），不在这里另写 `uri == null`。
 */
internal fun coverCacheKey(route: CoverRoute): String =
    if (route.viaSourceBytes) route.bytesKey else route.uriKey

/**
 * 这一条封面**此刻**的内存缓存位图（票 #146 ③）：组合期与 effect 都走这一个查询口，
 * 命中查询只此一处（[PageDecoder.cachedCover]）、键由 [coverCacheKey] 给。
 *
 * 只读内存、不做 IO（同 [PageDecoder.cachedCover]），因此可以在**组合期**调：
 * 以前只有来源字节那条路在组合之后查缓存，走 uri 的那条要等 `PageDecoder.decodeCoverUri` 在 IO 线程上
 * 内部查——命中也要走一趟协程派发，头一帧因此恒是骨架（返回浏览页那一屏封面全是内存命中，却整屏骨架
 * 再淡入，就是这一条）。
 *
 * **命中即提前返回 ⇒ 不发本行**（票 #146）：本判读口径的唯一 home 在
 * [com.cc3301.comicviewer.core.view.CoverLoadSegments] 的「本行只数真取解」段。
 */
internal fun cachedCoverBitmap(route: CoverRoute): ImageBitmap? = PageDecoder.cachedCover(coverCacheKey(route))

/**
 * 封面盒子的几何（票 #135，纯函数）：**盒宽与档位都从 [plan] 取**，网格档另收一个**布局**输入
 * （[gridCellAvailableHeight] = 格子真拿到的纵向空间）。盒子尺寸与是否裁剪都落在 [CoverLayout] 的纯函数里。
 *
 * 网格档缺可用高度 = 接线错了（没有空间就画不出格子盒子）：**直接抛**，不拿一个兜底高度静默画歪——
 * 之前那条接线把可用高度放在 [CoverSizing.GridCell] 里（构造不出缺高度的口径），但票 #135 的单一输入
 * 要求预取（没有格子空间）也拿同一个方案，可用高度因此只能落到渲染这一侧；
 * 代价是这一路缺高度不再由类型挡住，改由本函数的口径 + `CoverPlanTest` 的「网格档缺可用高度会报错」用例挡住。
 *
 * [rawAspect] 为 null（尚未解码）时两档都走占位比例，见 [CoverLayout]。
 */
internal fun coverBoxOf(
    plan: CoverPlan,
    rawAspect: Float?,
    gridCellAvailableHeight: Dp?,
): CoverLayout.CoverBox = when (plan.cropTarget) {
    CoverDecode.CropTarget.OwnAspect -> CoverLayout.boxForOwnAspect(plan.widthDp.value, rawAspect)
    // 可用高度（票 #106）：界面按格子真拿到的纵向空间让出名字块高后传入；不够时盒子等高收缩
    CoverDecode.CropTarget.GridCell -> CoverLayout.boxForGridCell(
        plan.widthDp.value,
        rawAspect,
        (gridCellAvailableHeight ?: error("网格档封面缺可用高度：盒宽 ${plan.widthDp} 的格子没给 gridCellAvailableHeight")).value,
    )
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
 * 不再写死 128px。方案进**解码缓存键**（[CoverPlan.route]）；位图状态（`remember`/`LaunchedEffect`）的键
 * 只取 [CoverPlan.widthPx] / [CoverPlan.cropTarget] / [CoverPlan.reloadKey] 三个派生值（见 [coverBitmapKey]，
 * **不含**原始 dp 宽与密度——同一桶内的窗口/内容宽变化不该重置位图），因此换档位（列数变 → 格宽变 → 桶变）
 * 会重解，不会拿上一档的位图拉伸。**本件不再自己算解码参数、也不再另收一份尺寸**：盒宽与解码口径取的是
 * [plan] 里同一个口径（可见行与预取用的是同一个 [CoverPlan] 实例），两边各算一份会出现「预取解好的那张
 * 这里命不中」的静默白干，盒子与解码各取一份实参则会出现「网格裁过的图按列表档铺进 56dp 盒子」（票 #135）。
 *
 * 解码区域（票 #81）：按 [CoverDecode.CropTarget] 给的口径（网格档=固定格比例、列表档=源比例夹到兜底区间）
 * 只解**可见带**（长条漫封面不再整张解码）；裁剪目标同样进解码缓存键与位图状态的键（[coverBitmapKey]），
 * 因此两档同宽（碰巧落在同一个桶）也不会互相串图、不会残留上一档的位图。
 *
 * 出图形态（票 #108 E2-B）：**骨架占位 → 出图淡入**两态——盒子底色（骨架）位图未到位时恒在，
 * 位图到位后按 [COVER_FADE_IN_MILLIS] 淡入。以前「灰底占位」「逐格补齐」「直接出现」三种观感混着的根因是
 * 位图没有过渡：占位那一帧和出图那一帧之间没有中间态，滚动速度一变就看起来像三种东西。
 * 票 #146 ③ 起位图的**初值**先同步查一次内存缓存（[cachedCoverBitmap]）：命中就首帧有图、骨架不再出现，
 * 淡入也不会跑（`animateFloatAsState` 的首帧即目标值）；查不到时照旧为 null、走下面那条异步取解。
 * 因此**命中那一档实为「一态」**（首帧即 alpha=1，既不骨架也不淡入）——它是本票验收第一条（首帧有图）
 * 的必然结果（真跑淡入的话首帧 alpha=0 就还是骨架），例外记录在 [com.cc3301.comicviewer.core.view.CoverAppearance]。
 * 命中也不产 `browseCoverLoad` 行（判读口径见 [com.cc3301.comicviewer.core.view.CoverLoadSegments]）。
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
    // 取图通路（走 uri 还是来源字节）与两条路各自的键都由 [plan] 给出：与浏览页的预取同一个方案实例（票 #135）。
    //
    // 位图状态的键收在一处（[coverBitmapKey]，票 #135 r2 b5）：remember 与 LaunchedEffect 读同一个键，
    // 两处各写一份就会重新出现「只有一处跟着改」的静默回归（r1 就是把整份方案当键的那次）
    val bitmapKey = coverBitmapKey(coverUri, plan)
    // 位图初值先**同步**查一次内存缓存（票 #146 ③）：命中的那一张（含预览解过 / 上一屏留下的）就是首帧的图，
    // 不再「先整屏骨架再淡入」；键与两条解码路入缓存用的键同一把（[coverCacheKey]，它的实参含本行的 `cacheKey`
    // 条目键，因此这里查到的一定是**本条目**那一张）。查不到 = 真没缓存，骨架照旧出现。
    var bitmap by remember(bitmapKey) { mutableStateOf(cachedCoverBitmap(plan.route(cacheKey, coverUri))) }
    LaunchedEffect(bitmapKey) {
        // 滚动量测（票 #109 + 票 #145）：三段量测的零点就是「这一格需要封面」那一刻（主线程）。
        // 开关关着时只读一个布尔、不取时钟（项目常驻红线：关着时零开销）。
        val measure = PerfTiming.isOn
        val askedNanos = if (measure) System.nanoTime() else 0L
        val route = plan.route(cacheKey, coverUri)
        // 票 #51：位图已在内存里就**不向来源要字节**（原来无论命中与否都先取一遍字节）；
        // 票 #146 ③ 起两条路都走同一个查询口——组合期已经查过一次，这里再查一次是为了接住「组合之后、本 effect
        // 起跑之前」才入缓存的那张（预取/别的窗口刚解完）；走 uri 的那条以前只由 `decodeCoverUri` 在 IO 线程上查，
        // 命中也要白跑一趟协程派发。
        // **命中就不产 `browseCoverLoad` 行**（票 #146，判读口径见 `core/view/ScrollProbe` 的 `CoverLoadSegments`）：
        // 这里只报「命中就提前返回」，不在这里复写判读规则。
        val cached = cachedCoverBitmap(route)
        if (cached != null) {
            bitmap = cached
            return@LaunchedEffect
        }
        bitmap = withContext(Dispatchers.IO) {
            // 滚动量测（票 #109 + 票 #145）：IO 段起点是「取字节」段的起点（位图缓存查询与协程派发归 `waitMs`），
            // 位图就绪就是整段终点——区间与改动前相同，但自本票起该区间含取字节闸的等牌时间，
            // 因此与闸前的样本（票面基线 317ms 那一批）不能逐字比。
            val ioStartNanos = if (measure) System.nanoTime() else 0L
            val threadName = if (measure) Thread.currentThread().name else ""
            // 「字节到手」那一刻（没到手 / 没走这条时保持 null）；通路与取字节段的终点由量测入口一处判，
            // 这里只报事实——「字节到手、解码失败」凭事实就仍然是 route=source（票 #145 r2 b1）
            var bytesArrivedNanos: Long? = null
            // 系统可解码的 uri 直接交给解码器（它自己先查内存缓存，命中就不碰文件）；解不出来才回退来源字节
            // （既有行为：`decodeCoverUri` 返回 null 时仍走下面那条）
            var loaded = route.uri?.let { PageDecoder.decodeCoverUri(context, it, route.uriKey, plan.widthPx, plan.cropTarget) }
            val uriDecoded = loaded != null
            if (!uriDecoded) {
                val bytes = runCatching { loadBytes() }.getOrNull()
                if (bytes != null) {
                    // 这一段就是「取字节」：来源字节通路唯一的取数点（uri 通路的退路也落在这里）；
                    // 解码成没成立不影响它——解码失败的行仍是 route=source + 真实两段
                    if (measure) bytesArrivedNanos = System.nanoTime()
                    loaded = PageDecoder.decodeCoverBytes(route.bytesKey, bytes, plan.widthPx, plan.cropTarget)
                }
            }
            if (measure) {
                // 三段量测（票 #145）：取字节 / 解码 / 位图就绪前的等待 + 执行它俩的线程
                val measurement = CoverLoadMeasurement.of(
                    askedNanos = askedNanos,
                    ioStartNanos = ioStartNanos,
                    uriDecoded = uriDecoded,
                    bytesArrivedNanos = bytesArrivedNanos,
                    doneNanos = System.nanoTime(),
                )
                BrowseScroll.probe.onCoverLoad(measurement.segments, threadName)
                PerfTiming.log { ScrollProbe.coverLoadLine(measurement.segments, threadName, measurement.route) }
            }
            loaded
        }
    }
    // 盒子尺寸与是否裁剪都走纯函数 [coverBoxOf]（口径由方案的档位选，比例从解码结果现算、不 remember：
    // 滚动时上一条目的比例不可能带到下一条（票 #46 AC））
    val aspect = bitmap?.let { CoverLayout.aspectOf(it.width, it.height) }
    val box = coverBoxOf(plan, aspect, gridCellAvailableHeight)
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
