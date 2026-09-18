package com.cc3301.comicviewer.core.view

/**
 * 封面显示尺寸的计算（票 #46，纯函数，由 [CoverLayoutTest] 锁定）。
 *
 * 口径（维护者验收原文「PV 那里的封面缩略图是显示完全的，CV 缩略图有白边」）：
 * 封面按**自身宽高比铺满所在容器的可用宽度**，高度 = 宽度 × 高宽比，完整显示、不裁剪；
 * 解码完成前（比例未知）用 [PLACEHOLDER_ASPECT] 占位，避免"先撑开再塌陷"导致列表跳动。
 *
 * 极端比例兜底：高宽比夹在 [MIN_ASPECT]..[MAX_ASPECT]（超长条漫页 / 超宽跨页）；
 * 超出区间的**只**在限值盒子里填充裁剪（[needsCrop]），正常竖版/横版封面永远落在区间内、不裁剪。
 */
object CoverLayout {

    /** 高宽比下限（比这更扁的跨页：按限值盒子 + 填充裁剪） */
    const val MIN_ASPECT: Float = 0.6f

    /** 高宽比上限（比这更长的条漫页：按限值盒子 + 填充裁剪） */
    const val MAX_ASPECT: Float = 1.6f

    /** 比例未知（尚未解码）时的占位比例：正常竖版封面的常见值，切换前后高度差异最小 */
    const val PLACEHOLDER_ASPECT: Float = 1.4f

    /**
     * 实际用于布局的高宽比：[rawAspect] 为空（尚未解码）或非正数时取 [PLACEHOLDER_ASPECT]，
     * 其余夹到 [MIN_ASPECT]..[MAX_ASPECT]。
     */
    fun displayAspect(rawAspect: Float?): Float = when {
        rawAspect == null || !rawAspect.isFinite() || rawAspect <= 0f -> PLACEHOLDER_ASPECT
        rawAspect < MIN_ASPECT -> MIN_ASPECT
        rawAspect > MAX_ASPECT -> MAX_ASPECT
        else -> rawAspect
    }

    /** 显示高度（与 [width] 同单位，通常是 px） */
    fun displayHeight(width: Float, rawAspect: Float?): Float = width * displayAspect(rawAspect)

    /**
     * 是否需要填充裁剪：只有超出兜底区间的极端比例才为真。
     * 正常封面（含占位阶段）一律完整显示。
     */
    fun needsCrop(rawAspect: Float?): Boolean =
        rawAspect != null && rawAspect.isFinite() && rawAspect > 0f &&
            (rawAspect < MIN_ASPECT || rawAspect > MAX_ASPECT)

    /** 位图宽高 → 高宽比；尺寸不可用时返回 null（走占位比例） */
    fun aspectOf(widthPx: Int, heightPx: Int): Float? =
        if (widthPx > 0 && heightPx > 0) heightPx.toFloat() / widthPx else null
}
