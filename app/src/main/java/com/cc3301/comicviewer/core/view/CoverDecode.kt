package com.cc3301.comicviewer.core.view

import kotlin.math.ceil

/**
 * 封面的解码目标宽度与解码缓存键（票 #56，纯函数，由 [CoverDecodeTest] 锁定）。
 *
 * 口径（维护者原文「封面不够高清，特别是使用网格 2 列视图时，封面被铺得更大，模糊更明显」）：
 * 封面按**本次实际显示宽度**解码（调用方把 dp 换算成 px 后传进来），不再固定 128px——网格 2 列的
 * 格宽在 360dp 屏上约 470–500px，用 128px 的位图铺满等于放大近 4 倍。
 *
 * 分桶：向上取到 [BUCKET_PX] 的整数倍。取 32px 而不是 64px，是因为**向上取整的过冲量上限等于桶宽**：
 * 64px 的桶在 470px 这类格宽上会多解 42px（超过验收口径的 ≤32px），32px 的桶既保证解码宽度 ≥ 显示宽度、
 * 过冲 <32px，又让 ±1px 的布局抖动落回同一个桶（不会为同一张封面留多份缓存）。
 */
internal object CoverDecode {

    /** 解码宽度分桶粒度（px）：向上取到它的整数倍 */
    const val BUCKET_PX: Int = 32

    /**
     * 显示宽度（px，可为小数）→ 解码目标宽度（px）：向上取到 [BUCKET_PX] 的整数倍。
     * 非正数（布局尚未就绪）也给一个桶，避免 0 宽度解码（`BitmapFactory` 的子采样循环会失控）。
     */
    fun targetWidthPx(displayWidthPx: Float): Int {
        val width = displayWidthPx.coerceAtLeast(1f)
        return ceil(width / BUCKET_PX).toInt().coerceAtLeast(1) * BUCKET_PX
    }

    /** 解码缓存键：含条目键、重取键与**真实目标宽度**（列表档与网格档宽度不同，不得互相串图） */
    fun key(cacheKey: String, reloadKey: Any?, targetWidthPx: Int): String =
        "cover@" + cacheKey + "@" + reloadKey + "@" + targetWidthPx
}
