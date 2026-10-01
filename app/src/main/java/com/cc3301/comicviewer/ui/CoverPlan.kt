package com.cc3301.comicviewer.ui

import androidx.compose.ui.unit.Dp
import com.cc3301.comicviewer.core.view.CoverDecode
import com.cc3301.comicviewer.core.view.CoverUriSource
import com.cc3301.comicviewer.core.view.ViewMode

/**
 * 一条封面的**取图方案**（票 #135）：宽度桶、裁剪目标、重取键与「走 uri 还是来源字节」由这一处一次算出，
 * 可见行（[CoverThumb]）与浏览页的预取 effect **只消费它**——同一个方案实例 ⇒ 同一把解码键。
 *
 * 为什么要有这一份接线：预取（`ui/CoverPrefetchLoad.kt`）连解码一起做，收益全建立在「预取算出的键与可见行
 * `CoverThumb` 算出的键**逐字相等**」上；不相等就意味着预取解好的那张可见行命不中（`PageDecoder.cachedCover`
 * 返回 null），于是它又自己取一遍字节、解一遍码，预取不但白干，还白占一份封面分区预算。
 *
 * 改动前（票 #108 r7）两条路径**各自独立算**：可见行手里是一个 [CoverSizing]、预取手里是「档位 + 格宽」两个
 * 标量，「两边结果必须相同」这件事只由对齐用例钉着（漂移的失败模式在生产里不可见）。
 *
 * 现在几何只有一个输入：[sizing]（口径 = 宽度 + 档位）。[widthDp]（盒宽）、[widthPx]（解码宽度）、
 * [cropTarget]（裁剪目标）**全是它的派生值**，互不可能不一致——盒宽与解码口径因此同源（r2 b1 收口：
 * 之前盒宽取 `sizing`、解码取另一个 `plan` 实参，两者可分叉且没有用例会红）。档位 → 口径的映射也只有
 * [coverSizingFor] 一处（由 `CoverPlanTest` 钉住）。
 */
internal data class CoverPlan(
    /** 口径（宽度 + 档位）：盒宽、解码宽度与裁剪目标的唯一输入 */
    val sizing: CoverSizing,
    /** 屏幕密度（dp → px 算解码宽度用）；它只影响解码宽度，不影响盒宽 */
    val density: Float,
    /** 取字节/解码的重取键（页面刷新计数）：它一变就重取封面并换解码缓存键（走 uri 那条除外，见 [route]） */
    val reloadKey: Any?,
) {

    /** 盒宽（= [sizing] 的宽）：[CoverThumb] 的盒子与解码宽度取的是这同一个值 */
    val widthDp: Dp get() = sizing.width

    /** 解码目标宽度（px，已按 [CoverDecode.BUCKET_PX] 向上分桶） */
    val widthPx: Int get() = CoverDecode.targetWidthPx(sizing.width.value * density)

    /** 裁剪目标（网格档 = 裁到格子、列表档 = 按源比例夹到兜底区间）：由口径的子型定，它进解码键 */
    val cropTarget: CoverDecode.CropTarget
        get() = when (sizing) {
            is CoverSizing.OwnAspect -> CoverDecode.CropTarget.OwnAspect
            is CoverSizing.GridCell -> CoverDecode.CropTarget.GridCell
        }

    /** 走**来源字节**那条路用的解码键（条目 id + 重取键 + 目标宽度 + 裁剪目标） */
    fun keyOf(entryId: String): String = CoverDecode.key(entryId, reloadKey, widthPx, cropTarget)

    /**
     * 这条条目走哪条通路（判据只此一处，见 [CoverUriSource]），两条路各自的解码键一并给出——
     * 调用方不必自己判 scheme，也不必自己拼键。本地/SAF 的 `content://`、`file://` 走系统解码器，其余
     * （SMB/WebDAV 的标识串、Komga 的无 uri）只能走来源字节。
     */
    fun route(entryId: String, coverUri: String?): CoverRoute = CoverRoute(
        uri = CoverUriSource.decodable(coverUri),
        // 通路判据委派给 [CoverUriSource]（r2 b1 收口：原来这里另写了一份 `uri == null`，判据因此成了两处表达式）
        viaSourceBytes = CoverUriSource.viaSourceBytes(coverUri),
        uriKey = CoverDecode.key(entryId, null, widthPx, cropTarget),
        bytesKey = keyOf(entryId),
    )

    /**
     * 这条封面要不要走来源字节通路（预取按它筛候选）：与 [route] 同一个判据、同一条委派，但**只算那个布尔、
     * 不构造任何解码键**——预取筛候选只想问这一件事（r2 b1：原来那处映射对每个条目无条件拼了两把键）。
     */
    fun viaSourceBytes(coverUri: String?): Boolean = CoverUriSource.viaSourceBytes(coverUri)

    companion object {

        /**
         * 唯一一条推导（票 #135）：口径 + 密度 + 重取键 → 方案。
         *
         * [sizing] 是行/格子**真拿到的那个口径**（`BrowserScreen` 由 [coverSizingFor] 算一次往下传，预取与
         * 可见行拿的是同一个实例）：网格档 = 格宽、列表档 = 行内封面列宽。口径里没有网格档的可用高度——
         * 那是**布局**输入（只进盒子高度），由 `CoverThumb` 自己收，解码看不见它。
         *
         * [widthDp]/[widthPx]/[cropTarget] 全是 [sizing] 的派生值，因此方案怎么构造都不会出现「盒宽一套、
         * 解码一套」——本函数是文档化的唯一入口（调用方不必知道派生怎么算）。
         */
        fun of(sizing: CoverSizing, density: Float, reloadKey: Any?): CoverPlan =
            CoverPlan(sizing = sizing, density = density, reloadKey = reloadKey)
    }
}

/**
 * 这一档（[ViewMode]）的封面口径（票 #135）：**唯一**一处把显示档位映射成 [CoverSizing]。
 *
 * 为什么独立成一个函数：档位 → 口径（进而 → 裁剪目标）若散在调用点写 `if (view.isGrid) …`，
 * 把判据写反（`grid = !view.isGrid`）就只会得到一屏错档的盒子与键、没有任何用例会红。收在这里之后，
 * 映射被 `CoverPlanTest` 逐档钉住，调用方只负责把同一个 [coverWidthDp] 传进来。
 *
 * [coverWidthDp] 是这一档**真拿到的封面宽**：网格档 = 格宽、列表档 = 行内封面列宽。
 */
internal fun coverSizingFor(view: ViewMode, coverWidthDp: Dp): CoverSizing =
    if (view.isGrid) CoverSizing.GridCell(coverWidthDp) else CoverSizing.OwnAspect(coverWidthDp)

/**
 * 一条封面的**取图通路**（票 #135）：[uri] 非空 = 走系统解码器（`PageDecoder.decodeCoverUri`），
 * 空 = 走来源字节（`Source.coverBytes` → `PageDecoder.decodeCoverBytes`）。
 *
 * [uriKey] 恒不带重取键：票 #53 的口径——走 uri 的本地图片封面**不吃重取键**（下拉更新不重取它）。
 *
 * 只由 [CoverPlan.route] 构造：两个判据（[uri] 与 [viaSourceBytes]）都取自 [CoverUriSource]，不在这里另判。
 */
internal data class CoverRoute(
    /** 系统解码器能直解的 uri（`content://` / `file://`）；null = 只能走来源字节 */
    val uri: String?,
    /** 这条封面要不要经来源字节通路取（判据见 [CoverUriSource]） */
    val viaSourceBytes: Boolean,
    /** 走 uri 那条路的解码键（票 #53：不带重取键） */
    val uriKey: String,
    /** 走来源字节那条路的解码键（带重取键） */
    val bytesKey: String,
)
