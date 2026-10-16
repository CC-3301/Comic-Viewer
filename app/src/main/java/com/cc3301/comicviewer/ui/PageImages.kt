package com.cc3301.comicviewer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.CacheKeys
import com.cc3301.comicviewer.core.source.PerfTiming
import com.cc3301.comicviewer.core.view.CoverByteGate
import com.cc3301.comicviewer.core.view.CoverBytePriority
import com.cc3301.comicviewer.core.view.CoverDecode
import com.cc3301.comicviewer.core.view.CoverDiagnostics
import com.cc3301.comicviewer.core.view.DecodedImageCache
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * 解码模块：把字节变成**位图**——页面位图与封面位图。
 *
 * 接口只有「给我这一页/这张封面的位图」与「钉住 / 松开」：
 * 分区内存缓存（页面与封面各一份预算）、淘汰、钉住、按宽/按高解码、封面按显示盒只解可见带、去网点，
 * 都是它的实现细节。
 *
 * - 内存缓存：[DecodedImageCache]（键含 bookId+页索引+目标宽度，防跨书碰撞）；**页面与封面各一份预算**
 *   （共用一份时阅读器的大页面位图会把封面整批挤掉，返回书柜封面变灰块）
 * - BitmapFactory 按目标宽度子采样（大图不 OOM）；GIF 静态首帧
 * - 封面另有 [decodeCoverBytes]/[decodeCoverUri]：按显示盒只解可见带（长条漫首页不再整张解码）；
 *   可见带本身在 API 28+ 走 `ImageDecoder` 的 setCrop + setTargetSize（裁剪与缩放一步），
 *   API 26/27 退回 `BitmapRegionDecoder`（解出即源分辨率，见 [coverBandDecoder]）
 *
 * 依赖由构造传入（打点出口 [log] 与位图缓存 [cache]）：位图缓存与钉住都挂在本实例上，
 * 装配处建一个就够（见 `ServiceLocator.pageImages`）；两者都必填，漏配在装配处就是编译不过。
 */
internal class PageImages(
    /** 打点出口：由调用方传入（生产 = `PerfTiming`，开关关着时连字符串都不拼） */
    private val log: (() -> String) -> Unit,
    /**
     * 位图内存缓存（页面与封面各一份预算）：生产 = [decodedImageCache]（两份预算按 `maxMemory` 算）；
     * 用例传一份小预算的，才能在几步之内看到分区淘汰与钉住的效果。
     */
    private val cache: DecodedImageCache<ImageBitmap>,
) {

    /**
     * 封面解码的并发闸：同时最多 [COVER_DECODE_MAX_CONCURRENT] 张在解，可见格优先取牌、预取排队让位。
     * 扣牌只包住**解码**那一段（解码之外不持牌），两个入口的键推导与字节读取都在闸外。
     */
    private val coverDecodeGate = CoverByteGate(COVER_DECODE_MAX_CONCURRENT)

    /**
     * 封面解码闸的**行为接缝**：把 [block] 放到闸上跑（闸位与排队序都取本模块这一处）。
     * 两条封面解码路走它；用例也走它，才能钉住「同时最多 [COVER_DECODE_MAX_CONCURRENT] 张」与
     * 「可见格优先取牌、已排队的预取让位」。
     */
    internal suspend fun <T> withCoverDecodePermit(priority: CoverBytePriority, block: suspend () -> T): T =
        coverDecodeGate.withPermit(priority, block)

    /**
     * 取页并解码：内存命中直接返回（**不重新取图**——菜单预览二次呼出场景）；
     * 未命中才调用 [load] 取字节（内部经磁盘缓存），再解码入内存。
     */
    suspend fun decodePage(
        handle: BookHandle,
        index: Int,
        targetWidthPx: Int,
        load: suspend () -> ByteArray,
    ): ImageBitmap? {
        val key = CacheKeys.pageMemory(handle.id, index, targetWidthPx)
        cache.page(key)?.let { return it }
        val bytes = load()
        // 设备打点（诊断协议）：三段互不重叠——取字节见 `PageBytes.load` 的 `pageBytes`，
        // 这里只量**纯解码**，单页总耗时见 `ReaderScreen` 的 `pageShown`
        val startedNanos = System.nanoTime()
        val decoded = decodePageBytes(key, bytes, targetWidthPx)
        log {
            "pageDecode book=" + handle.id + " index=" + index + " width=" + targetWidthPx +
                " bytes=" + bytes.size + " decoded=" + (decoded != null) +
                " ms=" + ((System.nanoTime() - startedNanos) / 1_000_000)
        }
        return decoded
    }

    /**
     * 按**目标高度**取页并解码：阅读菜单的预览格**高度**是固定的（格宽也是常量，图铺满图框），
     * 因此解码目标由高度给出（高度已由 [com.cc3301.comicviewer.core.view.ReaderMenuLayout.previewDecodeHeightPx] 分桶）。
     *
     * 先读源尺寸（只读头、不分配像素）算出「高度 × 真实宽高比」的宽度，再走 [decodePage] 那条
     * **按宽度**整图子采样的通路（共用 [decodeFullImage]，像素格式与子采样口径因此只有一处）；
     * [CoverDecode.sampleSizeForFullImage] 保证解出宽度 ≥ 目标宽度，解出高度因此也 ≥ 目标高度。
     * 内存键按**高度**（[CacheKeys.pageMemoryByHeight]）：调用方不必先知道宽度就能命中，二次呼出不重新取图。
     */
    suspend fun decodePageByHeight(
        handle: BookHandle,
        index: Int,
        targetHeightPx: Int,
        load: suspend () -> ByteArray,
    ): ImageBitmap? {
        val key = CacheKeys.pageMemoryByHeight(handle.id, index, targetHeightPx)
        cache.page(key)?.let { return it }
        val bytes = load()
        val size = imageSize(bytes) ?: return null
        val targetWidthPx = CoverDecode.targetWidthPx(targetHeightPx * size.first / size.second.toFloat())
        val decoded = decodeFullImage(bytes, size.first, targetWidthPx) ?: return null
        return cachePageAndReturn(key, decoded)
    }

    /**
     * 已解码**封面**位图的内存命中查询：命中即不必再向来源要字节。
     * 封面组件先问这里，再决定要不要 `loadBytes()`——否则位图明明在内存里，仍会先白取一遍字节。
     */
    fun cachedCover(key: String): ImageBitmap? = cache.cover(key)

    /**
     * 这条封面按 [plan] 与条目 id 推出的**内存缓存键**：通路判定（[CoverPlan.route]）与键的选取
     * （[coverCacheKey]）都在本模块内，界面只交方案与条目 id，不自己判通路、也不自己拼键。
     */
    fun coverCacheKeyOf(plan: CoverPlan, entryId: String, coverUri: String?): String =
        coverCacheKey(plan.route(entryId, coverUri))

    /**
     * 组合期的**同步**封面命中查询：键由 [coverCacheKeyOf] 推（只读内存、不做 IO，同 [cachedCover]）。
     * 封面组件先问这里，再决定要不要 `loadBytes()`——否则位图明明在内存里，仍会先白取一遍字节。
     */
    fun cachedCoverOf(plan: CoverPlan, entryId: String, coverUri: String?): ImageBitmap? =
        cachedCover(coverCacheKeyOf(plan, entryId, coverUri))

    /**
     * 页面位图的**同步**命中查询：键与 [decodePage] 同一套（[CacheKeys.pageMemory]），
     * 因此书柜页预解码过的首帧能在阅读页的**组合期**直接查到，进阅读器那一帧就是图片。
     * 只读内存、不做 IO（同 [cachedCover]）。
     */
    fun cachedPage(bookId: String, index: Int, targetWidthPx: Int): ImageBitmap? =
        cache.page(CacheKeys.pageMemory(bookId, index, targetWidthPx))

    /**
     * 阅读菜单预览格那张的**同步**命中查询（键与 [decodePageByHeight] 同一套，**按高度**）：
     * 格子进场第一帧就问这里，因此「上一轮取好、人滑走了」的那张能当场画出来，不闪一次空白
     * （配合条子级的取解调度：滚走不取消，图因此留在内存里）。
     * 只读内存、不做 IO（同 [cachedPage]）。
     */
    fun cachedPageByHeight(bookId: String, index: Int, targetHeightPx: Int): ImageBitmap? =
        cache.page(CacheKeys.pageMemoryByHeight(bookId, index, targetHeightPx))

    /**
     * 钉住 / 解锁一张**可见**封面位图：屏上正在用的那张在分区里淘汰时跳过（见 [DecodedImageCache] 的 `pinCover`）。
     * 只给可见行钉；预取窗口不钉（整窗免淘汰 = 预算失效）。
     */
    fun pinCover(key: String) = cache.pinCover(key)

    /** 可见行离屏时解锁（引用计数：同一张被多处钉住时只在最后一次解锁） */
    fun unpinCover(key: String) = cache.unpinCover(key)

    /**
     * 解码 uri 引用的封面（file://、content:// 均可，GIF 静态首帧）；[key] 由 `CoverDecode.key` 生成。
     * 读 uri 在闸外，只有解码过 [withCoverDecodePermit]；[priority] 只决定排队序（可见格优先取牌、预取让位）。
     */
    internal suspend fun decodeCoverUri(
        context: Context,
        uri: String,
        key: String,
        targetWidthPx: Int,
        cropTarget: CoverDecode.CropTarget,
        priority: CoverBytePriority,
    ): ImageBitmap? {
        cache.cover(key)?.let { return it }
        val bytes = readBytes(context, uri) ?: return null
        return withCoverDecodePermit(priority) {
            decodeCoverBytesLocked(key, bytes, targetWidthPx, cropTarget)
        }
    }

    /**
     * 解码封面字节：按显示盒只解可见带（[CoverDecode.plan]），保住「解码宽度 ≥ 显示宽度」的同时
     * 不把整条长图读进内存，保留位图也只留显示盒需要的像素。带由哪条解码器解（[CoverDecode.BandDecoder]）只由
     * [coverBandDecoder] 按 [sdkInt] 定一次（API 28+ 有 `ImageDecoder`）——计划与解码器看到的是**同一个值**，
     * 不会出现「计划里有裁剪几何、解码器却另按宿主 API 静默回退」。
     *
     * 计划带去网点参数（[CoverDecode.Plan.descreen]）时走 [decodeDescreenSource]（整源解码 → 模糊 → 裁带缩），
     * 不经过带分支（它拿不到可变位图）。
     *
     * 解码过 [withCoverDecodePermit]（[priority] 决定排队序）；缓存未命中时先扣牌再解，命中直接返回（不占牌）。
     * 这个入口不再内部调 [decodeCoverUri]，因此不会出现两条路各扣一次牌。
     *
     * [bandDecoder] 是测试接缝（接走整条带分支——可以拿到判定与计划、也可以注入「解不出」以覆盖退路）：
     * 生产调用不传，走 [decodeBand]。
     */
    internal suspend fun decodeCoverBytes(
        key: String,
        bytes: ByteArray,
        targetWidthPx: Int,
        cropTarget: CoverDecode.CropTarget,
        priority: CoverBytePriority,
        sdkInt: Int = Build.VERSION.SDK_INT,
        bandDecoder: (ByteArray, CoverDecode.Plan, CoverDecode.BandDecoder) -> Bitmap? = { bytes, plan, _ ->
            // 接缝带上了判定，好让测试能拿到它；生产实现按计划里的裁剪几何分派（二者由同一个值算出）
            decodeBand(bytes, plan)
        },
    ): ImageBitmap? {
        cache.cover(key)?.let { return it }
        return withCoverDecodePermit(priority) {
            decodeCoverBytesLocked(key, bytes, targetWidthPx, cropTarget, sdkInt, bandDecoder)
        }
    }

    /** [decodeCoverBytes] 的闸内主体：调用方已持牌（[decodeCoverUri] 也直接进来，不重复扣牌） */
    private fun decodeCoverBytesLocked(
        key: String,
        bytes: ByteArray,
        targetWidthPx: Int,
        cropTarget: CoverDecode.CropTarget,
        sdkInt: Int = Build.VERSION.SDK_INT,
        bandDecoder: (ByteArray, CoverDecode.Plan, CoverDecode.BandDecoder) -> Bitmap? = { bytes, plan, _ ->
            decodeBand(bytes, plan)
        },
    ): ImageBitmap? {
        cache.cover(key)?.let { return it }
        val size = imageSize(bytes) ?: return null
        val decoder = coverBandDecoder(sdkInt)
        val plan = CoverDecode.plan(size.first, size.second, targetWidthPx, cropTarget, decoder)
        // 封面源图分辨率打点：一行 = **一次真解码**（缓存命中在上面就返回了），
        // `upscale=true`（源宽 < 目标 px）就是「一定会被放大铺满」的硬判据。
        log { CoverDiagnostics.coverSourceLine(key, size.first, size.second, targetWidthPx, cropTarget, plan) }
        // 退路 = 放弃裁剪分支的收益：裁剪分支用不上时退回整图子采样，长条漫封面（800×8000）会照旧整张
        // 解出（约 12.8MiB）——只发生在编码器给不出子集尺寸或裁剪解码失败时
        val descreen = plan.descreen
        val decoded = if (descreen != null) {
            val descreened = decodeDescreenSource(bytes, plan)
            // 一行 = 一次去网点的实际结果（`applied=false` = 计划要去网点、真机却没执行）
            log { CoverDiagnostics.coverDescreenLine(key, descreen.sigma, descreened != null) }
            descreened ?: decodeFullImage(bytes, size.first, targetWidthPx)
        } else {
            (if (plan.region) bandDecoder(bytes, plan, decoder) else null)
                ?: decodeFullImage(bytes, size.first, targetWidthPx)
        }
        return cacheCoverAndReturn(key, decoded)
    }

    /** 解码已读入内存的**页面**字节；key=缓存键（bookId#index@width 形式） */
    private fun decodePageBytes(key: String, bytes: ByteArray, targetWidthPx: Int): ImageBitmap? {
        cache.page(key)?.let { return it }
        val size = imageSize(bytes) ?: return null
        return cachePageAndReturn(key, decodeFullImage(bytes, size.first, targetWidthPx))
    }

    /** 源图尺寸（只读头，不分配像素） */
    private fun imageSize(bytes: ByteArray): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
    }

    /**
     * 解码选项：像素格式的落地点之一（`BitmapFactory` 通路——整图子采样与区域解码都用它；
     * `ImageDecoder` 通路是另一处，见 [decodeScaledCrop]）。
     * 与 [CoverDecode.BITMAP_BYTES_PER_PIXEL] 是同一件事（2 字节/像素 = RGB_565）——core/view 不引
     * android 类型，两处的一致性由 `CoverDecodeBytesTest` 的可执行断言守住，不靠注释。
     */
    private fun decodeOptions(sampleSize: Int): BitmapFactory.Options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.RGB_565  // 漫画无透明，内存减半
    }

    /** 整图按宽度子采样：页面通路与封面通路的退路共用 */
    private fun decodeFullImage(bytes: ByteArray, srcWidth: Int, targetWidthPx: Int): Bitmap? =
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            decodeOptions(CoverDecode.sampleSizeForFullImage(srcWidth, targetWidthPx)),
        )

    /**
     * 裁剪分支的那张位图：计划里有裁剪几何（它只由 [CoverDecode.BandDecoder.CropToTarget] 产出，
     * 判定入口是 [coverBandDecoder]、与 [CoverDecode.plan] 用的是同一个值）就交给 `ImageDecoder`（裁剪 + 缩放
     * 一步），否则交给 `BitmapRegionDecoder`（解出即源分辨率再缩，这条带也是刚才选中的那条）。
     * 两条解不出都回 null，由调用方退回整图子采样。
     */
    private fun decodeBand(bytes: ByteArray, plan: CoverDecode.Plan): Bitmap? {
        val crop = plan.cropToTarget ?: return decodeRegion(bytes, plan)
        return decodeScaledCrop(bytes, plan, crop)
    }

    /**
     * 裁剪与缩放一步解出显示盒：目标尺寸 = 整张源按「带 → 显示盒」的比例缩成的尺寸，
     * 裁剪矩形 = 其中居中的显示盒大小（几何全在 [CoverDecode.ScaledCrop] 里算好，这里只往 API 里填）。
     *
     * `MEMORY_POLICY_LOW_RAM` 让**不透明**源解成 RGB_565（与 [decodeOptions] 同口径、内存减半）；带 alpha 的源
     * 仍给 ARGB_8888，这里再转一次 565——否则保留位图翻倍，与 [CoverDecode.BITMAP_BYTES_PER_PIXEL] 的口径不符。
     * 任何一步失败（格式不支持、尺寸越界、OOM）都回 null，由调用方退回整图子采样。
     *
     * 这里的 `ImageDecoder` 是 API 28+：`NewApi` 由 `@SuppressLint` 挡，因为**门槛已经在 [coverBandDecoder] 判过**
     * （本函数只在它的判定为 [CoverDecode.BandDecoder.CropToTarget] 时进得来）——再读一次 `Build.VERSION.SDK_INT`
     * 就是两处判定，注入 [decodeCoverBytes] 的 `sdkInt` 会与宿主漂移。
     */
    @SuppressLint("NewApi")
    private fun decodeScaledCrop(
        bytes: ByteArray,
        plan: CoverDecode.Plan,
        crop: CoverDecode.ScaledCrop,
    ): Bitmap? {
        val decoded = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                decoder.setTargetSize(crop.targetWidth, crop.targetHeight)
                decoder.setCrop(
                    Rect(crop.left, crop.top, crop.left + plan.retainedWidth, crop.top + plan.retainedHeight),
                )
            }
        } catch (t: Throwable) {
            null
        } ?: return null
        if (decoded.config == Bitmap.Config.RGB_565) return decoded
        val halfBytes = decoded.copy(Bitmap.Config.RGB_565, false)
        decoded.recycle()
        return halfBytes
    }

    /**
     * 只解可见带（区域坐标为源坐标）：`BitmapRegionDecoder` 不吃 `inSampleSize`，故解出即源分辨率，
     * 横向不会低于显示宽度；比显示盒大的带再缩到 [CoverDecode.Plan.retainedWidth]
     * （保留位图只留显示盒需要的像素，加宽源的长条封面因此不会按源宽留在缓存里，缩小的中间那张随即回收）。
     * 给不出子集尺寸的编码器与解码失败都回 null，由调用方退回整图子采样。
     * **去网点不在这条路上**：这条带在真机上是不可变位图，模糊会被跳过；需要去网点的封面走
     * [decodeDescreenSource]。
     * 用 byte[] 重载（它在 API 31 起被标记 deprecated，但替代品 `newInstance` 的 ByteBuffer 重载要 API 31）。
     */
    /** 解出计划里那条**源分辨率**的带（不缩放）；编码器给不出子集尺寸与解码失败都回 null */
    private fun decodeBandRaw(bytes: ByteArray, plan: CoverDecode.Plan): Bitmap? {
        val decoder = try {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        } catch (t: Throwable) {
            null
        } ?: return null
        return try {
            decoder.decodeRegion(
                Rect(plan.left, plan.top, plan.left + plan.width, plan.top + plan.height),
                decodeOptions(1),
            )
        } catch (t: Throwable) {
            null
        } finally {
            decoder.recycle()
        }
    }

    private fun decodeRegion(bytes: ByteArray, plan: CoverDecode.Plan): Bitmap? {
        val band = decodeBandRaw(bytes, plan) ?: return null
        if (band.width == plan.retainedWidth && band.height == plan.retainedHeight) return band
        // createScaledBitmap 同尺寸时返回同一张，此时不必也不能回收
        val scaled = Bitmap.createScaledBitmap(band, plan.retainedWidth, plan.retainedHeight, true)
        if (scaled !== band) band.recycle()
        return scaled
    }

    /**
     * 去网点分支：解出这条**源分辨率的带**，再把「轻度模糊 + 缩小」合成一趟重采样。
     * 与「先原地模糊再另做一次缩放」比，这里源像素只读一遍、不需要可原位修改的位图（区域解码的带在真机上
     * 可能不可变），也不多一张中间位图。峰值 = 带 + 显示盒两张。
     * 解不出带（编码器给不出子集/解码失败）或分配失败都回 null，由调用方退回整图子采样（观感退回现状）。
     */
    private fun decodeDescreenSource(bytes: ByteArray, plan: CoverDecode.Plan): Bitmap? {
        val descreen = plan.descreen ?: return null
        val source = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions(1))
        } catch (t: Throwable) {
            null
        } ?: return null
        val out = resampleAntialiased(
            src = source,
            region = Rect(plan.left, plan.top, plan.left + plan.width, plan.top + plan.height),
            outWidth = plan.retainedWidth,
            outHeight = plan.retainedHeight,
            kernelX = descreen.kernelWidth(plan.width.toFloat() / plan.retainedWidth),
            kernelY = descreen.kernelWidth(plan.height.toFloat() / plan.retainedHeight),
        )
        source.recycle()
        return out
    }

    /**
     * 一步「预模糊 + 缩小」的分离重采样：先把每条源行按 [kernelX] 的窗口缩到输出宽（横向），
     * 再把若干条这样的行按 [kernelY] 的窗口平均成一条输出行（纵向）。两条窗口的等效盒宽由
     * [CoverDecode.Descreen.kernelWidth] 把预模糊与缩小 footprint 的方差相加得到，因此网点在**缩小之前**
     * 就被抹掉，不会混叠成斑点。
     *
     * 源像素只读一遍；中间只有 [kernelY] 条「已横向缩好的行」（环形缓冲）+ 三条累加缓冲，
     * 内存 = 带（调用方持有）+ 显示盒 + O(kernelY × 输出宽 + 输出宽)。
     * 窗口在源的边界处收缩（不复制端点），因此每行/每列都按**实际样本数**取平均。
     */
    private fun resampleAntialiased(
        src: Bitmap,
        region: Rect,
        outWidth: Int,
        outHeight: Int,
        kernelX: Int,
        kernelY: Int,
    ): Bitmap? {
        val band = region
        if (outWidth <= 0 || outHeight <= 0 || band.width() <= 0 || band.height() <= 0) return null
        val out = try {
            Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.RGB_565)
        } catch (t: Throwable) {
            return null
        } ?: return null
        val scaleX = band.width().toDouble() / outWidth
        val scaleY = band.height().toDouble() / outHeight
        val halfX = kernelX / 2
        val halfY = kernelY / 2
        // 横向窗口只与输出列有关：把起点与样本数的倒数做成表，逐行只做加法
        val xStart = IntArray(outWidth)
        val xCount = IntArray(outWidth)
        val xInv = IntArray(outWidth)
        for (ox in 0 until outWidth) {
            val cx = ((ox + 0.5) * scaleX - 0.5).roundToInt()
            val start0 = (cx - halfX).coerceIn(0, band.width() - 1)
            val end0 = (cx + halfX).coerceIn(0, band.width() - 1)
            xStart[ox] = start0
            xCount[ox] = end0 - start0 + 1
            xInv[ox] = (1 shl 20) / xCount[ox]
        }
        val bias = 1 shl 19
        val srcRow = IntArray(band.width())
        // 已横向缩好的源行：环形缓冲（槽位 → 行号），每个源行只算一次
        val rowCache = Array(kernelY) { IntArray(outWidth) }
        val rowCacheIndex = IntArray(kernelY) { Int.MIN_VALUE }
        val accR = IntArray(outWidth)
        val accG = IntArray(outWidth)
        val accB = IntArray(outWidth)
        for (oy in 0 until outHeight) {
            val cy = ((oy + 0.5) * scaleY - 0.5).roundToInt()
            val yStart = (cy - halfY).coerceIn(0, band.height() - 1)
            val yEnd = (cy + halfY).coerceIn(0, band.height() - 1)
            java.util.Arrays.fill(accR, 0)
            java.util.Arrays.fill(accG, 0)
            java.util.Arrays.fill(accB, 0)
            for (sy in yStart..yEnd) {
                val row = scaledRowAt(src, band, sy, outWidth, xStart, xCount, xInv, bias, srcRow, rowCache, rowCacheIndex)
                for (x in 0 until outWidth) {
                    val p = row[x]
                    accR[x] += (p ushr 16) and 0xFF
                    accG[x] += (p ushr 8) and 0xFF
                    accB[x] += p and 0xFF
                }
            }
            // 纵向窗口在源边界处会变短，按实际条数取倒数
            val yInv = (1 shl 20) / (yEnd - yStart + 1)
            for (x in 0 until outWidth) {
                val r = (accR[x] * yInv + bias) ushr 20
                val g = (accG[x] * yInv + bias) ushr 20
                val b = (accB[x] * yInv + bias) ushr 20
                accR[x] = 0xFF000000.toInt() or (r.coerceIn(0, 255) shl 16) or
                    (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
            }
            out.setPixels(accR, 0, outWidth, 0, oy, outWidth, 1)
        }
        return out
    }

    /** 取（按需计算并缓存）源行 [sy] 的横向缩小结果；窗口与倒数取自 {xStart,xCount,xInv} 表 */
    private fun scaledRowAt(
        src: Bitmap,
        region: Rect,
        sy: Int,
        outWidth: Int,
        xStart: IntArray,
        xCount: IntArray,
        xInv: IntArray,
        bias: Int,
        srcRow: IntArray,
        rowCache: Array<IntArray>,
        rowCacheIndex: IntArray,
    ): IntArray {
        val slot = ((sy % rowCache.size) + rowCache.size) % rowCache.size
        if (rowCacheIndex[slot] == sy) return rowCache[slot]
        src.getPixels(srcRow, 0, region.width(), region.left, region.top + sy, region.width(), 1)
        val row = rowCache[slot]
        for (ox in 0 until outWidth) {
            val start = xStart[ox]
            val count = xCount[ox]
            var sr = 0
            var sg = 0
            var sb = 0
            for (k in 0 until count) {
                val p = srcRow[start + k]
                sr += (p ushr 16) and 0xFF
                sg += (p ushr 8) and 0xFF
                sb += p and 0xFF
            }
            val inv = xInv[ox]
            row[ox] = 0xFF000000.toInt() or
                ((((sr * inv + bias) ushr 20).coerceIn(0, 255)) shl 16) or
                ((((sg * inv + bias) ushr 20).coerceIn(0, 255)) shl 8) or
                ((sb * inv + bias) ushr 20).coerceIn(0, 255)
        }
        rowCacheIndex[slot] = sy
        return row
    }

    /** 入**页面**分区（[decodePage]/[decodePageByHeight] 两条页通路共用） */
    private fun cachePageAndReturn(key: String, bmp: Bitmap?): ImageBitmap? {
        val image = bmp?.asImageBitmap() ?: return null
        cache.putPage(key, image)
        return image
    }

    /** 入**封面**分区（[decodeCoverBytes]/[decodeCoverUri] 两条封面通路共用）：与页面分区各有预算，互不淘汰 */
    private fun cacheCoverAndReturn(key: String, bmp: Bitmap?): ImageBitmap? {
        val image = bmp?.asImageBitmap() ?: return null
        cache.putCover(key, image)
        return image
    }

    /**
     * 本机可用的裁剪解码器：API 28+ 有 `ImageDecoder`（setCrop + setTargetSize 一步裁剪 + 缩放，
     * 大瞬态从根上消失），minSdk 26 的 API 26/27 只有 `BitmapRegionDecoder`（解出即源分辨率，
     * 受 [CoverDecode.BAND_PEAK_BUDGET_BYTES] 约束）。纯函数（只吃 API 等级），单独测。
     */
    internal fun coverBandDecoder(sdkInt: Int): CoverDecode.BandDecoder =
        if (sdkInt >= Build.VERSION_CODES.P) CoverDecode.BandDecoder.CropToTarget else CoverDecode.BandDecoder.Region

    private fun readBytes(context: Context, uri: String): ByteArray? = try {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
    } catch (t: Throwable) {
        null
    }
}

/** 页面位图分区的预算（KB）：全局内存上限的 1/8，下限 4MB */
private val PAGE_CACHE_BUDGET_KB = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(4 * 1024)

/**
 * 封面位图分区的预算（KB）：另起一份（不从那 1/8 里切，否则封面仍随阅读器解多少页而缩水）。
 *
 * 取 `maxMemory/8`（与页面分区同一口径）：一张网格封面 768×1024 × RGB_565 ≈ **1.5MB**，
 * 除以 16 时约装 10 张，而浏览页的工作集（可见区 ±1 屏）是 12~18 张 ⇒ 屏上退到视口上方的那几行
 * 每次都被挤掉、滚回来要重解。除以 8 约装 20 张，盖得住一屏工作集。
 */
private val COVER_CACHE_BUDGET_KB = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(8 * 1024)

/**
 * 生产那份位图缓存：两份预算按 `maxMemory` 算（[PAGE_CACHE_BUDGET_KB] / [COVER_CACHE_BUDGET_KB]）。
 * 装配处（`ServiceLocator.init`）与需要生产口径的用例共用这一处，不必各写一份预算表达式。
 */
internal fun decodedImageCache(): DecodedImageCache<ImageBitmap> = DecodedImageCache(
    pageBudgetKb = PAGE_CACHE_BUDGET_KB,
    coverBudgetKb = COVER_CACHE_BUDGET_KB,
) { it.asAndroidBitmap().allocationByteCount / 1024 }

/**
 * 同时最多几张封面在**解码**（[PageImages.withCoverDecodePermit] 的闸位）：**2**。
 *
 * 设备是 4 大核 + 4 小核：解码是纯 CPU 活且没有核亲和性，全上会把大核占满（首窗 13.0ms/帧，
 * 全部进缓存后的稳定态 8.4ms/帧），留 2 个大核给帧管线。代价是首屏封面全部到位变慢
 * （逐张出现，已有的骨架 + 淡入兜住观感）。
 */
internal const val COVER_DECODE_MAX_CONCURRENT: Int = 2

/**
 * 解码模块的**组合期提供点**：`MainActivity` 在 `setContent` 里提供（提供点唯一一处），
 * 界面按 [LocalPageImages] 取实例。没提供就报错——漏接是接线错，不静默降级
 *（与 `LocalSessionState` 同一口径）。
 */
internal val LocalPageImages: ProvidableCompositionLocal<PageImages> = compositionLocalOf {
    error("没有提供解码模块（提供点在 MainActivity，见 ui/PageImages.kt）")
}
