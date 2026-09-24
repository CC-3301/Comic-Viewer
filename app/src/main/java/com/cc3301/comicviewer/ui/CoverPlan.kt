package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.view.CoverDecode
import com.cc3301.comicviewer.core.view.CoverUriSource

/**
 * 一条封面的**取图方案**（票 #135）：宽度桶、裁剪目标、重取键与「走 uri 还是来源字节」由这一处一次算出，
 * 可见行（[CoverThumb]）与浏览页的预取 effect **只消费它**——同一个方案实例 ⇒ 同一把解码键。
 *
 * 为什么要有这一份接线：预取（`ui/CoverPrefetchLoad.kt`）连解码一起做，收益全建立在「预取算出的键与可见行
 * `CoverThumb` 算出的键**逐字相等**」上；不相等就意味着预取解好的那张可见行命不中（`PageDecoder.cachedCover`
 * 返回 null），于是它又自己取一遍字节、解一遍码，预取不但白干，还白占一份封面分区预算。
 *
 * 改动前（票 #108 r7）两条路径**各自独立算**：可见行手里是一个 [CoverSizing]、预取手里是「档位 + 格宽」两个
 * 标量，「两边结果必须相同」这件事只由 `CoverDecodeKeysTest` 钉着（漂移的失败模式在生产里不可见）。
 * 票 #135 把档位 → （宽度桶 + 裁剪目标）的映射收进 [of] 一处，并把重取键一起带上：可见行与预取拿的是**同一个**
 * [CoverPlan]，相等不再是约定，对齐测试因此改为钉这一处推导的输出。
 */
internal data class CoverPlan(
    /** 解码目标宽度（px，已按 [CoverDecode.BUCKET_PX] 向上分桶） */
    val widthPx: Int,
    /** 裁剪目标（网格档 = 裁到格子、列表档 = 按源比例夹到兜底区间）：它进解码键，两档同宽也不串图 */
    val cropTarget: CoverDecode.CropTarget,
    /** 取字节/解码的重取键（页面刷新计数）：它一变就重取封面并换解码缓存键（走 uri 那条除外，见 [route]） */
    val reloadKey: Any?,
) {

    /** 走**来源字节**那条路用的解码键（条目 id + 重取键 + 目标宽度 + 裁剪目标） */
    fun keyOf(entryId: String): String = CoverDecode.key(entryId, reloadKey, widthPx, cropTarget)

    /**
     * 这条条目走哪条通路（票 #108 r3 的判据只此一处，见 [CoverUriSource]），两条路各自的解码键一并给出——
     * 调用方不必自己判 scheme，也不必自己拼键。本地/SAF 的 `content://`、`file://` 走系统解码器，其余
     * （SMB/WebDAV 的标识串、Komga 的无 uri）只能走来源字节。
     */
    fun route(entryId: String, coverUri: String?): CoverRoute = CoverRoute(
        uri = CoverUriSource.decodable(coverUri),
        uriKey = CoverDecode.key(entryId, null, widthPx, cropTarget),
        bytesKey = keyOf(entryId),
    )

    companion object {

        /**
         * 唯一一条推导（票 #135）：档位 + 显示宽度（dp）+ 密度 + 重取键 → 方案。
         *
         * [coverWidthDp] 是行/格子**真拿到的那个宽度**（`BrowserScreen` 的 `BoxWithConstraints` 算一次往下传）：
         * 网格档 = 格宽、列表档 = 行内封面列宽。宽度与档位取的是同一份来源，可见行与预取因此不可能各算一个值。
         */
        fun of(coverWidthDp: Float, grid: Boolean, density: Float, reloadKey: Any?): CoverPlan = CoverPlan(
            widthPx = CoverDecode.targetWidthPx(coverWidthDp * density),
            cropTarget = if (grid) CoverDecode.CropTarget.GridCell else CoverDecode.CropTarget.OwnAspect,
            reloadKey = reloadKey,
        )
    }
}

/**
 * 一条封面的**取图通路**（票 #135）：[uri] 非空 = 走系统解码器（`PageDecoder.decodeCoverUri`），
 * 空 = 走来源字节（`Source.coverBytes` → `PageDecoder.decodeCoverBytes`）。
 *
 * [uriKey] 恒不带重取键：票 #53 的口径——走 uri 的本地图片封面**不吃重取键**（下拉更新不重取它）。
 */
internal data class CoverRoute(
    /** 系统解码器能直解的 uri（`content://` / `file://`）；null = 只能走来源字节 */
    val uri: String?,
    /** 走 uri 那条路的解码键（票 #53：不带重取键） */
    val uriKey: String,
    /** 走来源字节那条路的解码键（带重取键） */
    val bytesKey: String,
) {

    /** 这条封面要不要经来源字节通路取 = 可见行会不会调 `Source.coverBytes`：预取只对它为真的条目生效 */
    val viaSourceBytes: Boolean get() = uri == null
}
