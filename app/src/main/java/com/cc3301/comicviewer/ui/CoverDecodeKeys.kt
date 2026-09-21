package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.view.CoverDecode

/**
 * 封面解码参数（目标宽度 + 裁剪目标）与由它派生的解码缓存键（票 #108 r7，纯数据 + 纯函数，
 * 由 [CoverDecodeKeysTest] 锁定）。
 *
 * 为什么需要这份接线：r6 起预取**连解码一起做**（`ui/CoverPrefetchLoad.kt`），收益全建立在
 * 「预取算出的键与可见行 `CoverThumb` 算出的键**逐字相等**」上——不相等就意味着预取解好的那张
 * 可见行命不中（`PageDecoder.cachedCover` 返回 null），于是它又自己取一遍字节、解一遍码，
 * 预取不但白干，还白占一份封面分区预算。
 */
internal data class CoverDecodeParams(val widthPx: Int, val cropTarget: CoverDecode.CropTarget) {

    /** 这一组解码参数对应的缓存键（含条目键与重取键） */
    fun keyOf(entryId: String, reloadKey: Any?): String = CoverDecode.key(entryId, reloadKey, widthPx, cropTarget)
}

/**
 * 两条取参数路径（票 #108 r7）：可见行手里是一个 [CoverSizing]，预取手里是「档位 + 格宽」两个标量，
 * 输入形状不同 ⇒ **各自独立算**、不共用中间值；「两边结果必须相同」这件事由 [CoverDecodeKeysTest] 钉住
 * （任一路径的换算漂移，用例立刻红）。两条都走同一个分桶函数 [CoverDecode.targetWidthPx]。
 */
internal object CoverDecodeKeys {

    /**
     * 可见行（`CoverThumb`）那一侧：宽度取 [CoverSizing.width]（列表档 = 行内封面列宽，网格档 = 格宽），
     * 裁剪目标由 sizing 的**子型**定（两档不互相串图）。
     */
    fun forRow(sizing: CoverSizing, density: Float): CoverDecodeParams = CoverDecodeParams(
        widthPx = CoverDecode.targetWidthPx(sizing.width.value * density),
        cropTarget = when (sizing) {
            is CoverSizing.OwnAspect -> CoverDecode.CropTarget.OwnAspect
            is CoverSizing.GridCell -> CoverDecode.CropTarget.GridCell
        },
    )

    /**
     * 预取（`BrowserScreen` 的预取 effect）那一侧：宽度取当前档位的格宽（列表档 = 行内封面列宽），
     * 裁剪目标由档位定。[coverWidthDp] 与格子拿到的 `cellWidth` 是**同一个值**（`BrowserScreen` 算一次往下传）。
     */
    fun forPrefetch(coverWidthDp: Float, grid: Boolean, density: Float): CoverDecodeParams = CoverDecodeParams(
        widthPx = CoverDecode.targetWidthPx(coverWidthDp * density),
        cropTarget = if (grid) CoverDecode.CropTarget.GridCell else CoverDecode.CropTarget.OwnAspect,
    )
}
