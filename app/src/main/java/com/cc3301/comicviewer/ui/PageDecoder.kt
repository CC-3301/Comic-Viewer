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
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.PerfTiming
import com.cc3301.comicviewer.core.source.sha256Hex
import com.cc3301.comicviewer.core.view.CoverDecode
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 页面解码器（票 04 基础 + 票 07 缓存分层 + 票 #81 封面按显示盒解码 + 票 #85 裁剪+缩放一步）：
 * - 内存 LruCache：解码后位图（键含 bookId+页索引+目标宽度，防跨书碰撞）
 * - 磁盘缓存：[PageDiskCache] 存原始页字节（SAF 二次打开省 provider IPC；票 #73 起清理在后台分批做，不在取页路径上）
 * - BitmapFactory 按目标宽度子采样（大图不 OOM）；GIF 静态首帧
 * - 封面（票 #81）另有 [decodeCoverBytes]/[decodeCoverUri]：按显示盒只解可见带（长条漫首页不再整张解码）；
 *   可见带本身在 API 28+ 走 `ImageDecoder` 的 setCrop + setTargetSize（裁剪与缩放一步，票 #85），
 *   API 26/27 退回 `BitmapRegionDecoder`（解出即源分辨率，见 [coverBandDecoder]）
 */
object PageDecoder {

    private val cache = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(4 * 1024),
    ) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.asAndroidBitmap().allocationByteCount / 1024
    }

    private var diskCache: PageDiskCache? = null

    fun init(context: Context) {
        diskCache = PageDiskCache(File(context.cacheDir, "page-bytes"))
    }

    /**
     * 取页并解码（票 07）：内存命中直接返回（**不重新取图**——菜单预览二次呼出场景）；
     * 未命中才调用 [load] 取字节（内部经磁盘缓存），再解码入内存。
     */
    suspend fun decodePage(
        handle: BookHandle,
        index: Int,
        targetWidthPx: Int,
        load: suspend () -> ByteArray,
    ): ImageBitmap? {
        val key = memoryKey(handle.id, index, targetWidthPx)
        cache.get(key)?.let { return it }
        val bytes = load()
        // 真机打点（票 #73 诊断协议）：三段互不重叠——取字节见 [loadPageBytes] 的 `pageBytes`，
        // 这里只量**纯解码**，单页总耗时见 `ReaderScreen` 的 `pageShown`
        val startedNanos = System.nanoTime()
        val decoded = decodeBytes(key, bytes, targetWidthPx)
        PerfTiming.log {
            "pageDecode book=" + handle.id + " index=" + index + " width=" + targetWidthPx +
                " bytes=" + bytes.size + " decoded=" + (decoded != null) +
                " ms=" + ((System.nanoTime() - startedNanos) / 1_000_000)
        }
        return decoded
    }

    /**
     * 按**目标高度**取页并解码（票 #105）：阅读菜单的预览项高度固定、宽度随页面真实比例，
     * 因此解码目标由高度给出（高度已由 [ReaderMenuLayout.previewDecodeHeightPx] 分桶）。
     *
     * 先读源尺寸（只读头、不分配像素）算出「高度 × 真实宽高比」的宽度，再走 [decodePage] 那条
     * **按宽度**整图子采样的通路（共用 [decodeFullImage]，像素格式与子采样口径因此只有一处）；
     * [CoverDecode.sampleSizeForFullImage] 保证解出宽度 ≥ 目标宽度，解出高度因此也 ≥ 目标高度。
     * 内存键按**高度**（[memoryKeyByHeight]）：调用方不必先知道宽度就能命中，二次呼出不重新取图。
     */
    suspend fun decodePageByHeight(
        handle: BookHandle,
        index: Int,
        targetHeightPx: Int,
        load: suspend () -> ByteArray,
    ): ImageBitmap? {
        val key = memoryKeyByHeight(handle.id, index, targetHeightPx)
        cache.get(key)?.let { return it }
        val bytes = load()
        val size = imageSize(bytes) ?: return null
        val targetWidthPx = CoverDecode.targetWidthPx(targetHeightPx * size.first / size.second.toFloat())
        val decoded = decodeFullImage(bytes, size.first, targetWidthPx) ?: return null
        return cacheAndReturn(key, decoded)
    }

    /**
     * 已解码位图的内存命中查询（票 #51）：命中即不必再向来源要字节。
     * 封面组件先问这里，再决定要不要 `loadBytes()`——否则位图明明在内存里，仍会先白取一遍字节。
     */
    fun cached(key: String): ImageBitmap? = cache.get(key)

    /**
     * 磁盘感知取页：磁盘命中跳过 [BookHandle.loadPage]。
     * 打点（票 #73）把「磁盘命中」与「向来源取」分开报，尖峰落在哪一段一眼看得出。
     */
    suspend fun loadPageBytes(handle: BookHandle, index: Int): ByteArray {
        val key = diskKey(handle.id, index)
        val startedNanos = System.nanoTime()
        val cached = diskCache?.get(key)
        val bytes = cached ?: handle.loadPage(index).bytes
        if (cached == null) diskCache?.put(key, bytes)
        PerfTiming.log {
            "pageBytes book=" + handle.id + " index=" + index + " disk=" + (cached != null) +
                " bytes=" + bytes.size + " ms=" + ((System.nanoTime() - startedNanos) / 1_000_000)
        }
        return bytes
    }

    // 缓存键工厂（单一来源，避免两处格式漂移）
    private fun memoryKey(bookId: String, index: Int, targetWidthPx: Int) = "$bookId#$index@$targetWidthPx"

    /** 按目标高度的内存键（票 #105）：`h` 前缀与按宽度的键分开，两条通路不互相串图 */
    private fun memoryKeyByHeight(bookId: String, index: Int, targetHeightPx: Int) = "$bookId#$index@h$targetHeightPx"

    private fun diskKey(bookId: String, index: Int) = "$bookId#$index"

    /** 解码 uri 引用的封面（file://、content:// 均可，GIF 静态首帧）；[key] 由 `CoverDecode.key` 生成 */
    internal fun decodeCoverUri(context: Context, uri: String, key: String, targetWidthPx: Int, cropTarget: CoverDecode.CropTarget): ImageBitmap? {
        cache.get(key)?.let { return it }
        val bytes = readBytes(context, uri) ?: return null
        return decodeCoverBytes(key, bytes, targetWidthPx, cropTarget)
    }

    /**
     * 解码封面字节（票 #81 + 票 #85）：按显示盒只解可见带（[CoverDecode.plan]），保住「解码宽度 ≥ 显示宽度」的同时
     * 不把整条长图读进内存，保留位图也只留显示盒需要的像素。带由哪条解码器解（[CoverDecode.BandDecoder]）只由
     * [coverBandDecoder] 按 [sdkInt] 定一次（API 28+ 有 `ImageDecoder`）——计划与解码器看到的是**同一个值**，
     * 不会出现「计划里有裁剪几何、解码器却另按宿主 API 静默回退」。
     *
     * [bandDecoder] 是测试接缝（接走整条带分支——可以拿到判定与计划、也可以注入「解不出」以覆盖退路）：
     * 生产调用不传，走 [decodeBand]。
     */
    internal fun decodeCoverBytes(
        key: String,
        bytes: ByteArray,
        targetWidthPx: Int,
        cropTarget: CoverDecode.CropTarget,
        sdkInt: Int = Build.VERSION.SDK_INT,
        bandDecoder: (ByteArray, CoverDecode.Plan, CoverDecode.BandDecoder) -> Bitmap? = { bytes, plan, _ ->
            // 接缝带上了判定，好让测试能拿到它；生产实现按计划里的裁剪几何分派（二者由同一个值算出）
            decodeBand(bytes, plan)
        },
    ): ImageBitmap? {
        cache.get(key)?.let { return it }
        val size = imageSize(bytes) ?: return null
        val decoder = coverBandDecoder(sdkInt)
        val plan = CoverDecode.plan(size.first, size.second, targetWidthPx, cropTarget, decoder)
        // 退路 = 放弃本票的收益：裁剪分支用不上时退回票 #56 的整图子采样，长条漫封面（800×8000）会照旧整张
        // 解出（约 12.8MiB）——只发生在编码器给不出子集尺寸或裁剪解码失败时
        val decoded = (if (plan.region) bandDecoder(bytes, plan, decoder) else null)
            ?: decodeFullImage(bytes, size.first, targetWidthPx)
        return cacheAndReturn(key, decoded)
    }

    /** 解码已读入内存的页面字节；key=缓存键（bookId#index@width 形式） */
    fun decodeBytes(key: String, bytes: ByteArray, targetWidthPx: Int): ImageBitmap? {
        cache.get(key)?.let { return it }
        val size = imageSize(bytes) ?: return null
        return cacheAndReturn(key, decodeFullImage(bytes, size.first, targetWidthPx))
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

    /** 整图按宽度子采样（票 #56 口径）：页面通路与封面通路的退路共用 */
    private fun decodeFullImage(bytes: ByteArray, srcWidth: Int, targetWidthPx: Int): Bitmap? =
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            decodeOptions(CoverDecode.sampleSizeForFullImage(srcWidth, targetWidthPx)),
        )

    /**
     * 裁剪分支的那张位图（票 #85）：计划里有裁剪几何（它只由 [CoverDecode.BandDecoder.CropToTarget] 产出，
     * 判定入口是 [coverBandDecoder]、与 [CoverDecode.plan] 用的是同一个值）就交给 `ImageDecoder`（裁剪 + 缩放
     * 一步），否则交给 `BitmapRegionDecoder`（解出即源分辨率再缩，这条带也是刚才选中的那条）。
     * 两条解不出都回 null，由调用方退回整图子采样。
     */
    private fun decodeBand(bytes: ByteArray, plan: CoverDecode.Plan): Bitmap? {
        val crop = plan.cropToTarget ?: return decodeRegion(bytes, plan)
        return decodeScaledCrop(bytes, plan, crop)
    }

    /**
     * 裁剪与缩放一步解出显示盒（票 #85）：目标尺寸 = 整张源按「带 → 显示盒」的比例缩成的尺寸，
     * 裁剪矩形 = 其中居中的显示盒大小（几何全在 [CoverDecode.ScaledCrop] 里算好，这里只往 API 里填）。
     *
     * `MEMORY_POLICY_LOW_RAM` 让**不透明**源解成 RGB_565（与 [decodeOptions] 同口径、内存减半）；带 alpha 的源
     * 仍给 ARGB_8888，这里再转一次 565——否则保留位图翻倍，与 [CoverDecode.BITMAP_BYTES_PER_PIXEL] 的口径不符。
     * 任何一步失败（格式不支持、尺寸越界、OOM）都回 null，由调用方退回整图子采样。
     *
     * 这里的 `ImageDecoder` 是 API 28+：`NewApi` 由 `@SuppressLint` 挡，因为**门槛已经在 [coverBandDecoder] 判过**
     * （本函数只在它的判定为 [CoverDecode.BandDecoder.CropToTarget] 时进得来）——再读一次 `Build.VERSION.SDK_INT`
     * 就是两处判定，注入 [decodeCoverBytes] 的 `sdkInt` 会与宿主漂移（票 #85 r1 评审 P2-2）。
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
     * 横向不会低于显示宽度；比显示盒大的带再缩到 [CoverDecode.Plan.retainedWidth]（保留位图只留显示盒需要的像素，
     * 加宽源的长条封面因此不会按源宽留在缓存里，缩小的中间那张随即回收）。给不出子集尺寸的编码器与解码失败都回 null，由调用方退回整图子采样。
     * 用 byte[] 重载（它在 API 31 起被标记 deprecated，但替代品 `newInstance` 的 ByteBuffer 重载要 API 31）。
     */
    private fun decodeRegion(bytes: ByteArray, plan: CoverDecode.Plan): Bitmap? {
        val decoder = try {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        } catch (t: Throwable) {
            null
        } ?: return null
        val band = try {
            decoder.decodeRegion(
                Rect(plan.left, plan.top, plan.left + plan.width, plan.top + plan.height),
                decodeOptions(1),
            )
        } catch (t: Throwable) {
            null
        } finally {
            decoder.recycle()
        } ?: return null
        if (band.width == plan.retainedWidth && band.height == plan.retainedHeight) return band
        // createScaledBitmap 同尺寸时返回同一张，此时不必也不能回收
        val scaled = Bitmap.createScaledBitmap(band, plan.retainedWidth, plan.retainedHeight, true)
        if (scaled !== band) band.recycle()
        return scaled
    }

    private fun cacheAndReturn(key: String, bmp: Bitmap?): ImageBitmap? {
        val image = bmp?.asImageBitmap() ?: return null
        cache.put(key, image)
        return image
    }

    /**
     * 本机可用的裁剪解码器（票 #85）：API 28+ 有 `ImageDecoder`（setCrop + setTargetSize 一步裁剪 + 缩放，
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

/**
 * 原始页字节磁盘缓存（票 07）：键=bookId#index 的 SHA-256 前 32 位十六进制
 * （bookId 含 content:// 等非法文件名字符）。超上限按 lastModified 从旧到新淘汰至 80%。
 *
 * 清理时机（票 #73）：**不在取页路径上**——[put] 只写字、累加一个字节计数，再把清理排到 [trimExecutor]；
 * 清理本身按批（一趟最多删 [trimBatchSize] 个文件；还没到目标线**且本趟确有文件被删**才再排一趟，
 * 一个都没删掉就停到下次写入）。因此「取一页要多久」与缓存目录里有多少文件无关。
 * 改动前每次 [put] 都在取页线程上 listFiles + 排序 + 删除：
 * 缓存目录逼近上限后，这份同步清理就落在翻页路径上（票 #73 的候选原因之一；是否就是维护者看到的
 * 那个秒级尖峰，要真机按 `PerfTiming` 的 `pageBytes` / `pageDecode` / `pageShown` / `diskTrim` 打点归属）。
 *
 * 计数只用来决定「要不要排一趟清理」：本进程内它从 0 起，而目录跨进程存在，因此进程内第一次 [put]
 * 无条件排一趟，把上一个进程遗留的占用核出来（不然计数会一路偏低，缓存会涨到两倍上限）。
 * 清理趟把计数重算成扫描到的真实占用（扫描期间新写入的仍在计数里），
 * 因此扫描与写入并发时计数可能偏高（同一个文件既被写入计数、又被扫描到）——偏高只会早清一点，不会越界。
 */
class PageDiskCache(
    private val dir: File,
    private val maxBytes: Long = PAGE_DISK_CACHE_MAX_BYTES,
    /** 清理任务的后台执行器（默认单线程 + 守护线程）；测试注入手工执行器来观察「什么时候清」 */
    private val trimExecutor: Executor = newTrimExecutor(),
    /** 一趟清理最多删几个文件（分批；见类注释） */
    private val trimBatchSize: Int = PAGE_CACHE_TRIM_BATCH,
) {

    init {
        // 0 或负值会让一趟清理一个文件都删不掉（批为空）——缓存从此永远不会被清，会无上限涨下去
        require(trimBatchSize > 0) { "一趟清理至少要能删一个文件：trimBatchSize=$trimBatchSize" }
    }

    /** 当前占用的近似值（[put] 累加、清理按扫描结果重算）：只用来触发清理，不当准数用 */
    private val sizeBytes = AtomicLong(0L)

    /** 本进程还没核过一次目录真实占用（第一次 [put] 时核） */
    private val needsReconcile = AtomicBoolean(true)

    /** 已有清理排在执行器上（合并同一段时间里的多次触发） */
    private val trimQueued = AtomicBoolean(false)

    fun get(key: String): ByteArray? = try {
        val f = fileFor(key)
        // 0 长度 = 上次非原子写残留的截断文件，视为 miss
        if (f.exists() && f.length() > 0) f.readBytes() else null
    } catch (t: Throwable) {
        null
    }

    /** 原子写：先写 .tmp 再 rename，避免截断文件被读到（review P1）；清理交给后台（票 #73） */
    fun put(key: String, bytes: ByteArray) {
        try {
            dir.mkdirs()
            val target = fileFor(key)
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return
            }
            sizeBytes.addAndGet(bytes.size.toLong())
            scheduleTrimIfNeeded()
        } catch (t: Throwable) {
            // 缓存写失败不影响阅读
        }
    }

    /** 计数字节越上限（或本进程还没核过遗留占用）就排一趟清理 */
    private fun scheduleTrimIfNeeded() {
        if (!needsReconcile.getAndSet(false) && sizeBytes.get() <= maxBytes) return
        scheduleTrim()
    }

    /** 把清理排到 [trimExecutor]；已有排队任务时不再排（执行器上最多压一趟） */
    private fun scheduleTrim() {
        if (!trimQueued.compareAndSet(false, true)) return
        runCatching { trimExecutor.execute { trimQueued.set(false); runTrim() } }
            .onFailure { trimQueued.set(false) }
    }

    /**
     * 一趟后台清理：扫一次目录拿真实占用（写回 [sizeBytes]，把计数对齐实际），由 [PageCacheTrim]
     * 判出该删哪些，按 [trimBatchSize] 分批删；还有可删的且本趟**确有文件被删掉**时才再排一趟。
     * 全程在 [trimExecutor] 上，取页线程不参与。
     *
     * 删除用 `listFiles()` 交出的那些 `File` 句柄（不按名字另造 `File`）：删除成败是续排判据，
     * 测试也由此注入「删除失败」（拿一个交不出可删句柄的目录）而无需给生产类再加接缝。
     */
    private fun runTrim() {
        val startedNanos = System.nanoTime()
        // 扫描前写入的字节先取走：它们对应的文件已在磁盘上，会被下面的扫描算进 total，
        // 不能再加一次（只有扫描没跑成时才把 pending 补回，免得白丢）
        val pending = sizeBytes.getAndSet(0L)
        val listed = dir.listFiles()
        if (listed == null) {
            sizeBytes.addAndGet(pending)
            return
        }
        val files = ArrayList<PageCacheFile>(listed.size)
        var total = 0L
        for (f in listed) {
            if (!f.isFile) continue
            val size = f.length()
            total += size
            files += PageCacheFile(name = f.name, sizeBytes = size, lastModifiedMs = f.lastModified())
        }
        // 计数重算成扫描到的真实占用；扫描期间新写入的字节仍在 sizeBytes 里（可能被扫描重复计入，
        // 也可能尚未入账）——两种都只是偏高，不会越界
        sizeBytes.addAndGet(total)
        val doomed = PageCacheTrim.filesToDelete(files, maxBytes)
        if (doomed.isEmpty()) return
        val batch = doomed.take(trimBatchSize)
        val handles = listed.associateBy { it.name }
        var freed = 0L
        var deleted = 0
        for (file in batch) {
            if (handles[file.name]?.delete() == true) {
                freed += file.sizeBytes
                deleted++
            }
        }
        sizeBytes.addAndGet(-freed)
        PerfTiming.log {
            "diskTrim scanned=" + files.size + " bytes=" + total + " deleted=" + deleted +
                " freed=" + freed + " ms=" + ((System.nanoTime() - startedNanos) / 1_000_000)
        }
        // 还没到目标线且本趟确实删掉了东西，才续排下一批；一个都没删掉就停——
        // 删除一直失败时继续续排，就是反复「扫全目录 + 重试删除」，正是本票要避的大目录重扫
        if (freed > 0 && total - freed > PageCacheTrim.targetBytesOf(maxBytes)) scheduleTrim()
    }

    private fun fileFor(key: String): File = File(dir, sha256Hex(key, hexChars = 32) + ".bin")
}

/** 页字节磁盘缓存的上限（票 07）：200MB；只作 [PageDiskCache] 的默认值 */
private const val PAGE_DISK_CACHE_MAX_BYTES: Long = 200L * 1024 * 1024

/** 一趟后台清理最多删的文件数（票 #73：分批，不与取页抢磁盘） */
internal const val PAGE_CACHE_TRIM_BATCH: Int = 64

/** 清理用的后台单线程执行器（守护线程：它不持有进程） */
private fun newTrimExecutor(): Executor =
    Executors.newSingleThreadExecutor { r -> Thread(r, "page-cache-trim").apply { isDaemon = true } }

/** [PageCacheTrim] 的入参：缓存目录里的一个文件，只带判定要用的三项 */
internal data class PageCacheFile(val name: String, val sizeBytes: Long, val lastModifiedMs: Long)

/**
 * 页字节磁盘缓存的淘汰判定（票 #73：从 [PageDiskCache] 的清理里提出的纯逻辑，不碰文件系统，单独可测）。
 *
 * 口径沿用票 07：总占用不超上限 → 一个都不删；超了 → 按修改时间从旧到新删，直到落到上限的 80%
 * （留余量，否则每次写入都要清一次）。
 */
internal object PageCacheTrim {

    /** 目标线的比例：上限的 80%（票 07 既有口径） */
    private const val TARGET_NUMERATOR = 8
    private const val TARGET_DENOMINATOR = 10

    /** 淘汰目标线：删到这个占用就不再删 */
    fun targetBytesOf(maxBytes: Long): Long = maxBytes * TARGET_NUMERATOR / TARGET_DENOMINATOR

    /**
     * 该删哪些文件：超上限时给出「最旧先删、删到目标线」的淘汰表（未超上限 = 空表）。
     * 修改时间相同时按文件名定序，判定不随扫描顺序变化。
     */
    fun filesToDelete(files: List<PageCacheFile>, maxBytes: Long): List<PageCacheFile> {
        var remaining = files.sumOf { it.sizeBytes }
        if (remaining <= maxBytes) return emptyList()
        val target = targetBytesOf(maxBytes)
        val doomed = mutableListOf<PageCacheFile>()
        for (file in files.sortedWith(compareBy({ it.lastModifiedMs }, { it.name }))) {
            if (remaining <= target) break
            remaining -= file.sizeBytes
            doomed += file
        }
        return doomed
    }
}
