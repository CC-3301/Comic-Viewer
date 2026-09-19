package com.cc3301.comicviewer.core.view

import kotlin.math.abs

/**
 * 封面显示尺寸的计算（票 #46 + 票 #57，纯函数，由 [CoverLayoutTest] 锁定）。
 *
 * 两档口径各一条：
 * - **列表档**（票 #46，维护者验收原文「PV 那里的封面缩略图是显示完全的，CV 缩略图有白边」）：
 *   封面按**自身宽高比铺满可用宽度**，高度 = 宽度 × 高宽比，完整显示、不裁剪；
 *   解码完成前（比例未知）用 [PLACEHOLDER_ASPECT] 占位，避免"先撑开再塌陷"导致列表跳动；
 *   极端比例夹在 [MIN_ASPECT]..[MAX_ASPECT]（超长条漫页 / 超宽跨页），超出的**只**在限值盒子里填充裁剪。
 * - **网格档**（票 #57，维护者验收原文「Cosplay 和 Fate 宽度一致了，但长度不一致也会不对称」）：
 *   格子是**统一尺寸**（高 = 格宽 × [GRID_CELL_ASPECT]，与封面自身比例无关），封面在格子里
 *   **裁剪填满**（短边铺满、长边裁掉），所以任何比例都不改变格高、不留灰边，同一屏行与行对齐。
 *
 * 两档的盒子分别由 [boxForOwnAspect] / [boxForGridCell] 给出，界面只管把盒子贴上去。
 */
object CoverLayout {

    /** 高宽比下限（比这更扁的跨页：按限值盒子 + 填充裁剪） */
    const val MIN_ASPECT: Float = 0.6f

    /** 高宽比上限（比这更长的条漫页：按限值盒子 + 填充裁剪） */
    const val MAX_ASPECT: Float = 1.6f

    /** 比例未知（尚未解码）时的占位比例：正常竖版封面的常见值，切换前后高度差异最小 */
    const val PLACEHOLDER_ASPECT: Float = 1.4f

    /**
     * 比例是否可用：空（尚未解码）、非有限、非正一律不可用。
     * 占位判定与两处裁剪兜底共用这一份（[displayAspect] / [needsCrop] / [gridNeedsCrop]）。
     */
    private fun isUsableAspect(rawAspect: Float?): Boolean =
        rawAspect != null && rawAspect.isFinite() && rawAspect > 0f

    /**
     * 实际用于布局的高宽比：[rawAspect] 不可用时取 [PLACEHOLDER_ASPECT]，
     * 其余夹到 [MIN_ASPECT]..[MAX_ASPECT]。
     */
    fun displayAspect(rawAspect: Float?): Float =
        // isUsableAspect 已挡掉 null，故这里可以安全取非空值
        if (isUsableAspect(rawAspect)) rawAspect!!.coerceIn(MIN_ASPECT, MAX_ASPECT) else PLACEHOLDER_ASPECT

    /**
     * 显示高度（单位与 [width] 一致：比例无量纲，宽给 dp 高就是 dp——界面按 dp 传入，
     * 解码宽度另由 `CoverDecode.targetWidthPx` 换算 px）
     */
    fun displayHeight(width: Float, rawAspect: Float?): Float = width * displayAspect(rawAspect)

    /**
     * 是否需要填充裁剪：只有超出兜底区间的极端比例才为真。
     * 正常封面（含占位阶段）一律完整显示。
     */
    fun needsCrop(rawAspect: Float?): Boolean {
        if (!isUsableAspect(rawAspect)) return false
        // isUsableAspect 已挡掉 null
        val aspect = rawAspect!!
        return aspect < MIN_ASPECT || aspect > MAX_ASPECT
    }

    /** 位图宽高 → 高宽比；尺寸不可用时返回 null（走占位比例） */
    fun aspectOf(widthPx: Int, heightPx: Int): Float? =
        if (widthPx > 0 && heightPx > 0) heightPx.toFloat() / widthPx else null

    // ---------- 网格档：统一格子尺寸 + 裁剪填满（票 #57） ----------

    /** 网格档格子的固定高宽比：格高 = 格宽 × 本值，与封面自身比例无关（竖版 4:3 量级） */
    const val GRID_CELL_ASPECT: Float = 4f / 3f

    /** 网格档格高（与 [cellWidth] 同单位）：只由格宽与 [GRID_CELL_ASPECT] 决定 */
    fun gridCellHeight(cellWidth: Float): Float = (cellWidth * GRID_CELL_ASPECT).coerceAtLeast(0f)

    /**
     * 网格档封面是否裁剪填满（短边铺满格子、长边裁掉）：只要与格比例不同就要裁，
     * 比例未知/非法时同样裁（占位底色不得露出来）；只有恰好同比例时 Crop 与 Fit 等价、才不必裁。
     */
    fun gridNeedsCrop(rawAspect: Float?): Boolean {
        if (!isUsableAspect(rawAspect)) return true
        // isUsableAspect 已挡掉 null
        val aspect = rawAspect!!
        return abs(aspect - GRID_CELL_ASPECT) > ASPECT_EPSILON
    }

    /** 同比例判定容差：比例只用来选 Crop/Fit，两者等价区间里不必切换 */
    private const val ASPECT_EPSILON: Float = 0.001f

    /**
     * 封面显示盒：**宽、高**（同单位，比例无量纲）与**是否裁剪填满**。
     * 两档各由 [boxForOwnAspect] / [boxForGridCell] 给出，界面直接按盒子的宽高贴上去。
     */
    data class CoverBox(val width: Float, val height: Float, val crop: Boolean)

    /** 列表档盒子（票 #46）：宽 = 可用宽度、高 = 宽度 × 封面自身比例；只有极端比例才裁剪 */
    fun boxForOwnAspect(availableWidth: Float, rawAspect: Float?): CoverBox =
        CoverBox(availableWidth, displayHeight(availableWidth, rawAspect), needsCrop(rawAspect))

    /**
     * 网格档盒子（票 #57）：盒子只由 [cellWidth] 决定——任何比例的封面都得到同一个盒子
     * （同一屏行行对齐），封面裁剪填满。
     */
    fun boxForGridCell(cellWidth: Float, rawAspect: Float?): CoverBox =
        CoverBox(cellWidth, gridCellHeight(cellWidth), gridNeedsCrop(rawAspect))
}
