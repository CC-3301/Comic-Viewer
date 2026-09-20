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
 * 的封面（条漫首页即长条页）会整张解码；[plan] 给出「按显示盒居中裁剪的可见带」，只解显示用得上的那一段，
 * 且**保留位图 = 显示盒需要的像素**（带按比例缩到目标宽，只缩不放）——加宽源（1080×5400 / 2000×10000 /
 * 2048×20480 这类）因此也不会按源宽留在缓存里。
 *
 * 票 #85 再补上**用哪条解码器**解这条带（[BandDecoder]）：`BitmapRegionDecoder` 解出即源分辨率，源宽
 * ≳2170px 的长条封面因此撞上 [BAND_PEAK_BUDGET_BYTES] 退回整图子采样（4000×20000 保留 10MB ≈ 同宽 3:4
 * 封面的 3.75 倍）；API 28+ 改用 `ImageDecoder` 的 `setCrop` + `setTargetSize` 一步「裁剪 + 缩放」
 * （[ScaledCrop]）——它解出的是**目标面**（整张源 × `s`，那张面进 [Plan.peakByteCount]），源宽很大时远小于
 * 源分辨率的带，这类源因此也走上带分支（两条解码器取峰值更小的那条，见 [plan]）。
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

    /**
     * 每像素 2 字节（RGB_565；漫画无透明、内存减半）：[Plan] 的字节口径。像素格式有两个落地处：
     * `PageDecoder.decodeOptions` 的 `inPreferredConfig`（`BitmapFactory` 通路）与 `PageDecoder.decodeScaledCrop`
     * （`ImageDecoder` 通路，带 alpha 的源还要转一次 565）。本包是纯 JVM、不引 android 类型，
     * 两处的一致性由可执行断言守住（`CoverDecodeBytesTest`：`Plan.retainedByteCount == 位图 allocationByteCount`）。
     */
    const val BITMAP_BYTES_PER_PIXEL: Int = 2

    /**
     * 带分支的**瞬态峰值上限**（字节）：12.8MB = 票面点名的那个危险量级（800×8000 的整张解码 = 800×8000×2B）。
     * 它只对 [BandDecoder.Region] 有意义——那条路的带**解出即源分辨率**，是「解出带 + 缩到显示盒」两张位图
     * 同时在世，所以不能无条件让它赢：超过上限就退回整图子采样（除非替代它的整图子采样本身就更大）。
     * 挡住的是**超宽源**的带（如 4000×3000 的带 2250×3000 = 13.5MB）与源宽 ≳2170px 的长条页（如
     * 4000×20000 的带 4000×5333 = 42.7MB，退回后保留 10MB ≈ 同宽 3:4 封面的 3.75 倍）——见 [plan]。
     *
     * [BandDecoder.CropToTarget]（票 #85）一步「裁剪 + 缩放」，但解出的不是显示盒而是**目标面**
     * （整张源 × `s`，见 [ScaledCrop]），故它同样受这条上限约束：目标面进 [Plan.peakByteCount]，
     * [plan] 第 4 条会据此选回来（目标面超上限时第 3 条不接受裁剪解码的带）。上限本身**保留不变**，
     * 变的只是裁剪解码路线多了「源宽 ≳2170px 的长条封面也不必退回」这一条出路。
     */
    const val BAND_PEAK_BUDGET_BYTES: Int = 12_800_000

    /**
     * 裁剪目标（票 #81）：网格档=固定格比例（一律裁到格子）；列表档=源比例夹到兜底区间（越界才裁）。
     * 它进解码缓存键（[key]），因此两档同宽也不会互相串图。
     */
    enum class CropTarget { GridCell, OwnAspect }

    /**
     * 裁剪分支用哪个解码器（票 #85）——同一个 [Plan] 的「解出即什么尺寸」由它定：
     *
     * - [Region]：`BitmapRegionDecoder`（API 26/27 唯一可用），按源坐标解出可见带，**解出即源分辨率**
     *   （实测它不吃 `inSampleSize`），再由调用方缩到显示盒；这条带的字节数随源宽平方增长，
     *   源宽 ≳2170px 的长条封面因此撞上 [BAND_PEAK_BUDGET_BYTES]。
     * - [CropToTarget]：`ImageDecoder` 的 `setCrop` + `setTargetSize`（API 28+），裁剪与缩放一步完成：
     *   解出的是**目标面**（整张源 × `s`，尺寸与裁剪矩形同源，见 [ScaledCrop]），再从里面裁出显示盒。
     *   这张面比区域带小得多（除非源宽接近显示宽度，见 [plan] 第 4 条），大瞬态因此从根上消失。
     */
    enum class BandDecoder { Region, CropToTarget }

    /**
     * 解码计划：**要解的源图区域**（源坐标）、子采样倍数、**解出的那张位图**与**保留位图**（入缓存/上屏那张）的尺寸。
     *
     * - [region] = true：解 [left]..[left]+[width) × [top]..[top]+[height) 这条**可见带**；
     *   `BitmapRegionDecoder` 不吃 `inSampleSize`（实测：源坐标区域 + 子采样只回源分辨率），
     *   故此分支 [sampleSize] 恒为 1；若解码器是 [BandDecoder.CropToTarget]，带与缩放合成一步（[cropToTarget]）。
     * - [region] = false：整图按 [sampleSize] 子采样（票 #56 的既有口径）。
     *
     * [decodedWidth]/[decodedHeight] 与 [peakByteCount] 由**构造处一次算定**（不再由两个布尔字各自分支）：
     * 整图分支解出即保留；[BandDecoder.Region] 的带解出即源分辨率、再缩到显示盒；
     * [BandDecoder.CropToTarget] 解出的是目标面（[ScaledCrop]）、再裁出显示盒。
     */
    data class Plan(
        val region: Boolean,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val sampleSize: Int,
        val retainedWidth: Int,
        val retainedHeight: Int,
        /** 裁剪 + 缩放一步解码的几何（[BandDecoder.CropToTarget] 才有；非该分支为 null） */
        val cropToTarget: ScaledCrop?,
        /** 解码器交回我们手里的那张位图：整图分支=子采样尺寸，带分支=源分辨率带或裁剪解码的目标面 */
        val decodedWidth: Int,
        val decodedHeight: Int,
    ) {

        /** 解出的位图字节数 */
        val decodedByteCount: Int get() = decodedWidth * decodedHeight * BITMAP_BYTES_PER_PIXEL

        /** 保留位图字节数：验收口径里的「解码位图字节数」（缓存占用与同屏内存都看它） */
        val retainedByteCount: Int get() = retainedWidth * retainedHeight * BITMAP_BYTES_PER_PIXEL

        /**
         * 峰值瞬态字节数（本包只数**解码器要建的那几张位图**，编解码器内部缓冲与整图分支同口径）：
         * 解出即保留的（整图子采样，或裁剪解码的目标面正好就是显示盒）只有一张；其余是「解出的那张 +
         * 缩到/裁出显示盒的保留那张」两张同时在世——票 #85 起，裁剪解码的目标面（整张源 × `s`）也计在里面。
         */
        val peakByteCount: Int get() =
            if (decodedWidth == retainedWidth && decodedHeight == retainedHeight) {
                decodedByteCount
            } else {
                decodedByteCount + retainedByteCount
            }
    }

    /**
     * [BandDecoder.CropToTarget] 交给 `ImageDecoder` 的几何（票 #85）：`setCrop` 吃的是**缩放后**的坐标
     * （AOSP 的 `checkSubset` 要求裁剪矩形落在 `setTargetSize` 的框内），所以先把整张源按「带 → 显示盒」的
     * 比例 `s` 缩成 [targetWidth]×[targetHeight]（`setTargetSize`），再取其中**居中**的显示盒大小的矩形
     * （[left]/[top] 起，宽高 = `Plan.retainedWidth/retainedHeight`，`setCrop`）——解出即位图 = 目标面里那一块。
     *
     * 这张面**是解码器真的要建的一张位图**（票 #85 r1 评审 P2-1），进 [Plan.peakByteCount]；
     * **不许 clamp 目标尺寸**：目标尺寸与裁剪矩形是一套映射（矩形在里居中），改小它就取不到那条带了。
     */
    data class ScaledCrop(val targetWidth: Int, val targetHeight: Int, val left: Int, val top: Int)

    /**
     * 显示盒的高宽比：网格档 = 固定格比例（[CoverLayout.GRID_CELL_ASPECT]，一律裁到格子）；
     * 列表档 = 源比例夹到兜底区间（[CoverLayout.displayAspect]，只有越界才裁——与 [CoverLayout.needsCrop] 同一口径）。
     */
    fun boxAspect(cropTarget: CropTarget, srcAspect: Float): Float = when (cropTarget) {
        CropTarget.GridCell -> CoverLayout.GRID_CELL_ASPECT
        CropTarget.OwnAspect -> CoverLayout.displayAspect(srcAspect)
    }

    /**
     * 解出封面用的计划（票 #81 + 票 #85）：按**显示盒**（居中裁剪）取可见带，或整图按宽度子采样，选带的条件有三条：
     *
     * 1. **带真的裁掉了像素**（带面积 < 源面积）：带与整图同义时不走带——整图分支能在解码时缩采，既不多一张
     *    中间位图，也不多一次 1:1 的瞬态分配（比例本就等于盒比例的封面因此保持票 #56 的现状）；
     * 2. **保留位图更小**：本票的目标是缓存里的位图大小（长条漫首页 800×8000 整图要 12.8MB，带只要 0.7MB）；
     *    超宽源反过来（如 8000×800 的带 0.7MB > 整图子采样 0.2MB）就走整图子采样；
     * 3. **带的瞬态峰值不超上限** [BAND_PEAK_BUDGET_BYTES]（除非替代它的整图子采样本身就更大）：带是「解出的那张 +
     *    保留那张」两份同时在世（[Plan.peakByteCount]），超宽源的带（如 4000×3000 的区域解码带 13.5MB）比整图子采样
     *    臃肿得多，不能让它赢；
     * 4. **两条带的解码器取峰值更小的那条**（票 #85）：[BandDecoder.CropToTarget] 一步「裁剪 + 缩放」，但它交给
     *    解码器的是**目标面**（整张源 × `s`，见 [ScaledCrop]）——源宽接近显示宽度时那张面反而比区域带更大
     *    （800×8000 → 5.9MB vs 2.4MB，改动前的区域带 1.7MB），因此不比区域带小就不用它。这一条同时就是票面要的守卫：
     *    裁剪解码的峰值一旦越过 maxOf(BAND_PEAK_BUDGET_BYTES, 整图峰值)，第 3 条就不会接受它，退回整图子采样。
     *
     * 与改动前（只有整图子采样）比：保留位图**从不更大**（以上第 2 条），瞬态峰值以第 3 条为界，且第 4 条保证
     * 选出来的带不比另一条解码器的带大。
     *
     * [bandDecoder] 是这台设备能用的裁剪解码器（`PageDecoder.coverBandDecoder(API 等级)`）：它只决定「带解出即
     * 什么尺寸」，不改变上面四条入选规则。
     *
     * 源尺寸必须为正（`PageDecoder` 只在 `inJustDecodeBounds` 读出宽高后才调用）。
     */
    fun plan(
        srcWidth: Int,
        srcHeight: Int,
        targetWidthPx: Int,
        cropTarget: CropTarget,
        bandDecoder: BandDecoder,
    ): Plan {
        val box = boxAspect(cropTarget, srcHeight.toFloat() / srcWidth)
        val full = fullImagePlan(srcWidth, srcHeight, targetWidthPx)
        val band = bandPlan(srcWidth, srcHeight, box, targetWidthPx, bandDecoder)
        val bandCrops = band.width.toLong() * band.height < srcWidth.toLong() * srcHeight
        val bandFitsBudget = band.peakByteCount <= maxOf(BAND_PEAK_BUDGET_BYTES, full.peakByteCount)
        return if (bandCrops && band.retainedByteCount < full.retainedByteCount && bandFitsBudget) band else full
    }

    /** 整图分支（票 #56 口径）：解出即保留（不缩放），子采样公式保证解出宽度仍 ≥ 目标宽度 */
    private fun fullImagePlan(srcWidth: Int, srcHeight: Int, targetWidthPx: Int): Plan {
        val sample = sampleSizeForFullImage(srcWidth, targetWidthPx)
        val width = (srcWidth / sample).coerceAtLeast(1)
        val height = (srcHeight / sample).coerceAtLeast(1)
        return Plan(
            region = false,
            left = 0,
            top = 0,
            width = srcWidth,
            height = srcHeight,
            sampleSize = sample,
            retainedWidth = width,
            retainedHeight = height,
            cropToTarget = null,
            decodedWidth = width,
            decodedHeight = height,
        )
    }

    /**
     * 带分支（票 #81 + 票 #85）：两条解码器解出的带都算一遍，取**峰值更小**的那条（见 [plan] 第 4 条）。
     * 两条带的 [Plan.width]/[Plan.height]（源坐标上的带）与保留尺寸完全一致，差别只在「解出的是源分辨率的带
     * 还是裁剪解码的目标面」以及由此得到的峰值。
     */
    private fun bandPlan(
        srcWidth: Int,
        srcHeight: Int,
        boxAspect: Float,
        targetWidthPx: Int,
        bandDecoder: BandDecoder,
    ): Plan {
        val regionBand = visibleBand(srcWidth, srcHeight, boxAspect, targetWidthPx, BandDecoder.Region)
        if (bandDecoder == BandDecoder.Region) return regionBand
        val cropBand = visibleBand(srcWidth, srcHeight, boxAspect, targetWidthPx, BandDecoder.CropToTarget)
        return if (cropBand.peakByteCount < regionBand.peakByteCount) cropBand else regionBand
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
    private fun visibleBand(
        srcWidth: Int,
        srcHeight: Int,
        boxAspect: Float,
        targetWidthPx: Int,
        bandDecoder: BandDecoder,
    ): Plan {
        val srcAspect = srcHeight.toFloat() / srcWidth
        var width = srcWidth
        var height = srcHeight
        if (srcAspect > boxAspect) {
            height = (srcWidth * boxAspect).roundToInt().coerceIn(1, srcHeight)
        } else if (srcAspect < boxAspect) {
            width = (srcHeight / boxAspect).roundToInt().coerceIn(1, srcWidth)
        }
        // 保留位图 = 显示盒需要的像素：带比目标宽更宽就缩到目标宽（只缩不放——带比盒小就是源本身的极限），
        // 高度按带的比例跟着缩，比例因此仍是显示盒比例
        val retainedWidth = minOf(width, targetWidthPx)
        val retainedHeight = (height * retainedWidth.toFloat() / width).roundToInt().coerceAtLeast(1)
        // 裁剪解码解出的是目标面（整张源 × s）：它既是解码器真建的那张位图，也是峰值口径里的第一项
        val cropToTarget = if (bandDecoder == BandDecoder.CropToTarget) {
            scaledCrop(srcWidth, srcHeight, width, retainedWidth, retainedHeight)
        } else {
            null
        }
        return Plan(
            region = true,
            left = (srcWidth - width) / 2,
            top = (srcHeight - height) / 2,
            width = width,
            height = height,
            sampleSize = 1,
            retainedWidth = retainedWidth,
            retainedHeight = retainedHeight,
            cropToTarget = cropToTarget,
            decodedWidth = cropToTarget?.targetWidth ?: width,
            decodedHeight = cropToTarget?.targetHeight ?: height,
        )
    }

    /**
     * 裁剪解码的目标尺寸与裁剪矩形（票 #85）：比例 `s` = 保留宽度 / 带宽 = 显示盒宽度 / 带宽，
     * 目标尺寸 = 整张源 × `s`（`setCrop` 的坐标在这一坐标系里），矩形 = 其中**居中**的显示盒大小。
     *
     * 高度取整除法定矩形上边，保证矩形右下不越出目标尺寸（AOSP 的 `checkSubset` 会抛 IllegalStateException，
     * 那会白白退回整图子采样）；目标尺寸另夹到不小于显示盒，宽度被裁的源（带宽 < 源宽）同样成立。
     */
    private fun scaledCrop(
        srcWidth: Int,
        srcHeight: Int,
        bandWidth: Int,
        retainedWidth: Int,
        retainedHeight: Int,
    ): ScaledCrop {
        val scale = retainedWidth.toFloat() / bandWidth
        val targetWidth = (srcWidth * scale).roundToInt().coerceAtLeast(retainedWidth)
        val targetHeight = (srcHeight * scale).roundToInt().coerceAtLeast(retainedHeight)
        return ScaledCrop(
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            left = (targetWidth - retainedWidth) / 2,
            top = (targetHeight - retainedHeight) / 2,
        )
    }
}
