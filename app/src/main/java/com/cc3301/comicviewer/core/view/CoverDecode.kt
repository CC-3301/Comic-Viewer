package com.cc3301.comicviewer.core.view

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 封面的解码目标宽度、**解码区域**与解码缓存键（票 #56 + 票 #81，纯函数，由 [CoverDecodeTest] 锁定）。
 *
 * 口径（维护者原文「封面不够高清，特别是使用网格 2 列视图时，封面被铺得更大，模糊更明显」）：
 * 封面按**本次实际显示宽度**解码（调用方把 dp 换算成 px 后传进来），不再固定 128px——网格 2 列的
 * 格宽在 360dp 屏上约 470–500px，用 128px 的位图铺满等于放大近 4 倍。
 *
 * 分桶：向上取到 [BUCKET_PX] 的整数倍。取 32px 而不是 64px，是因为**向上取整的过冲量上限等于桶宽**：
 * 64px 的桶在 470px 这类格宽上会多解 42px（超过验收口径的 ≤32px），32px 的桶既保证解码宽度 ≥ 显示宽度、
 * 过冲 <32px，又让 ±1px 的布局抖动落回同一个桶（不会为同一张封面留多份缓存）。
 *
 * 票 #81 在宽度之外补上**解码区域**：`PageDecoder` 的 `inSampleSize` 只按宽度定，源高宽比 ≫ 网格档固定格比例
 * 的封面（条漫首页即长条页）会整张解码；[plan] 给出「按显示盒居中裁剪的可见带」，只解显示用得上的那一段。
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

    /** 解码缓存键：含条目键、重取键、**真实目标宽度**与**裁剪目标**（列表档与网格档不得互相串图） */
    fun key(cacheKey: String, reloadKey: Any?, targetWidthPx: Int, cropTarget: CropTarget): String =
        "cover@" + cacheKey + "@" + reloadKey + "@" + targetWidthPx + "@" + cropTarget

    // ---------- 按显示盒解码可见带（票 #81） ----------

    /** RGB_565 每像素字节数：`PageDecoder` 的 `inPreferredConfig` 与这里必须一致，否则 [plan] 选分支的口径失效 */
    const val RGB_565_BYTES_PER_PIXEL: Int = 2

    /**
     * 裁剪目标（票 #81）：网格档=固定格比例（一律裁到格子）；列表档=源比例夹到兜底区间（越界才裁）。
     * 它进解码缓存键（[key]），因此两档同宽也不会互相串图。
     */
    enum class CropTarget { GridCell, OwnAspect }

    /**
     * 解码计划：**要解的源图区域**（源坐标）与整图分支的子采样倍数。
     *
     * - [region] = true：只解 [left]..[left]+[width) × [top]..[top]+[height) 这条**可见带**；
     *   `BitmapRegionDecoder` 不吃 `inSampleSize`（实测：源坐标区域 + 子采样只回源分辨率），
     *   故此分支 [sampleSize] 恒为 1——解码分辨率即源分辨率，横向不会低于显示宽度。
     * - [region] = false：整图按 [sampleSize] 子采样（票 #56 的既有口径）。
     */
    data class Plan(
        val region: Boolean,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val sampleSize: Int,
    ) {

        /** 解码出的位图像素宽（区域分支不子采样） */
        val decodedWidth: Int get() = if (region) width else width / sampleSize

        /** 解码出的位图像素高（区域分支不子采样） */
        val decodedHeight: Int get() = if (region) height else height / sampleSize

        /** 解码位图字节数：**两条分支比大小的口径**（封面的 `inPreferredConfig` 是 RGB_565） */
        val decodedByteCount: Int get() = decodedWidth * decodedHeight * RGB_565_BYTES_PER_PIXEL
    }

    /**
     * 显示盒的高宽比：网格档 = 固定格比例（[CoverLayout.GRID_CELL_ASPECT]，一律裁到格子）；
     * 列表档 = 源比例夹到兜底区间（[CoverLayout.displayAspect]，只有越界才裁——与 [CoverLayout.needsCrop] 同一口径）。
     */
    fun boxAspect(cropTarget: CropTarget, srcAspect: Float): Float = when (cropTarget) {
        CropTarget.GridCell -> CoverLayout.GRID_CELL_ASPECT
        CropTarget.OwnAspect -> CoverLayout.displayAspect(srcAspect)
    }

    /**
     * 解出封面用的计划（票 #81）：按**显示盒**（居中裁剪）取可见带，或整图按宽度子采样，**取字节更少的那条**。
     *
     * 为什么两条分支都要：区域解码不吃子采样，同一张源图（如 4000×3000 的扫描封面）按可见带 1:1 解出来
     * 会比整图子采样（4000 → 1000 宽）多 9 倍字节；反过来源高宽比 ≫ 盒比例（800×8000 的条漫首页）时整图
     * 子采样为了保住横向分辨率只能 sample=1，等于把整条长图读进内存（12.8MiB）。因此源比例不极端时沿用票 #56 的
     * 整图子采样（现状不变），比例越界时只解可见带。
     *
     * 源尺寸必须为正（`PageDecoder` 只在 `inJustDecodeBounds` 读出宽高后才调用）。
     */
    fun plan(srcWidth: Int, srcHeight: Int, targetWidthPx: Int, cropTarget: CropTarget): Plan {
        val band = visibleBand(srcWidth, srcHeight, boxAspect(cropTarget, srcHeight.toFloat() / srcWidth))
        val full = Plan(
            region = false,
            left = 0,
            top = 0,
            width = srcWidth,
            height = srcHeight,
            sampleSize = sampleSizeForFullImage(srcWidth, targetWidthPx),
        )
        return if (band.decodedByteCount < full.decodedByteCount) band else full
    }

    /**
     * 整图按宽度子采样：子采样倍数取「解出宽度仍 ≥ [targetWidthPx]」的最大 2 的幂（票 #56 公式）。
     * 区域解码不可用（格式不支持/解失败）时由 `PageDecoder` 用它退回整图。
     */
    fun sampleSizeForFullImage(srcWidth: Int, targetWidthPx: Int): Int {
        val target = targetWidthPx.coerceAtLeast(1)
        var sample = 1
        while (srcWidth / (sample * 2) >= target) sample *= 2
        return sample
    }

    /** 居中的可见带：盒比例比源更瘦就裁高、更胖就裁宽（宽/高至少 1px，坐标为源坐标） */
    private fun visibleBand(srcWidth: Int, srcHeight: Int, boxAspect: Float): Plan {
        val srcAspect = srcHeight.toFloat() / srcWidth
        var width = srcWidth
        var height = srcHeight
        if (srcAspect > boxAspect) {
            height = (srcWidth * boxAspect).roundToInt().coerceIn(1, srcHeight)
        } else if (srcAspect < boxAspect) {
            width = (srcHeight / boxAspect).roundToInt().coerceIn(1, srcWidth)
        }
        return Plan(
            region = true,
            left = (srcWidth - width) / 2,
            top = (srcHeight - height) / 2,
            width = width,
            height = height,
            sampleSize = 1,
        )
    }
}
