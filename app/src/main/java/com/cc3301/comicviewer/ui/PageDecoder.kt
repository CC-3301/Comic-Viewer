package com.cc3301.comicviewer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.view.CoverDecode
import java.io.File
import java.security.MessageDigest

/**
 * 页面解码器（票 04 基础 + 票 07 缓存分层 + 票 #81 封面按显示盒解码）：
 * - 内存 LruCache：解码后位图（键含 bookId+页索引+目标宽度，防跨书碰撞）
 * - 磁盘缓存：[PageDiskCache] 存原始页字节（SAF 二次打开省 provider IPC）
 * - BitmapFactory 按目标宽度子采样（大图不 OOM）；GIF 静态首帧
 * - 封面（票 #81）另有 [decodeCoverBytes]/[decodeCoverUri]：按显示盒只解可见带（长条漫首页不再整张解码）
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
        return decodeBytes(key, bytes, targetWidthPx)
    }

    /**
     * 已解码位图的内存命中查询（票 #51）：命中即不必再向来源要字节。
     * 封面组件先问这里，再决定要不要 `loadBytes()`——否则位图明明在内存里，仍会先白取一遍字节。
     */
    fun cached(key: String): ImageBitmap? = cache.get(key)

    /** 磁盘感知取页：磁盘命中跳过 [BookHandle.loadPage] */
    suspend fun loadPageBytes(handle: BookHandle, index: Int): ByteArray {
        val key = diskKey(handle.id, index)
        diskCache?.get(key)?.let { return it }
        val bytes = handle.loadPage(index).bytes
        diskCache?.put(key, bytes)
        return bytes
    }

    // 缓存键工厂（单一来源，避免两处格式漂移）
    private fun memoryKey(bookId: String, index: Int, targetWidthPx: Int) = "$bookId#$index@$targetWidthPx"
    private fun diskKey(bookId: String, index: Int) = "$bookId#$index"

    /** 解码 uri 引用的封面（file://、content:// 均可，GIF 静态首帧）；[key] 由 `CoverDecode.key` 生成 */
    internal fun decodeCoverUri(context: Context, uri: String, key: String, targetWidthPx: Int, cropTarget: CoverDecode.CropTarget): ImageBitmap? {
        cache.get(key)?.let { return it }
        val bytes = readBytes(context, uri) ?: return null
        return decodeCoverBytes(key, bytes, targetWidthPx, cropTarget)
    }

    /**
     * 解码封面字节（票 #81）：按显示盒只解可见带（[CoverDecode.plan]），保住「解码宽度 ≥ 显示宽度」的同时
     * 不把整条长图读进内存，保留位图也只留显示盒需要的像素。
     *
     * [regionDecoder] 是测试接缝（注入「区域解码返回 null」以覆盖退路分支）：生产调用不传，走 [decodeRegion]。
     */
    internal fun decodeCoverBytes(
        key: String,
        bytes: ByteArray,
        targetWidthPx: Int,
        cropTarget: CoverDecode.CropTarget,
        regionDecoder: (ByteArray, CoverDecode.Plan) -> Bitmap? = ::decodeRegion,
    ): ImageBitmap? {
        cache.get(key)?.let { return it }
        val size = imageSize(bytes) ?: return null
        val plan = CoverDecode.plan(size.first, size.second, targetWidthPx, cropTarget)
        // 退路 = 放弃本票的收益：区域解码用不上时退回票 #56 的整图子采样，长条漫封面（800×8000）会照旧整张
        // 解出（约 12.8MiB）——只发生在编码器给不出子集尺寸或区域解码失败时
        val decoded = (if (plan.region) regionDecoder(bytes, plan) else null)
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
     * 解码选项（单一来源）：像素格式取 [CoverDecode.BITMAP_CONFIG]——封面字节口径与位图格式是同一件事，
     * 不得两处各写一份（同 [CoverDecode] / `GridLayout` 的口径）。
     */
    private fun decodeOptions(sampleSize: Int): BitmapFactory.Options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = CoverDecode.BITMAP_CONFIG
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
     * 只解可见带（区域坐标为源坐标）：`BitmapRegionDecoder` 不吃 `inSampleSize`，故解出即源分辨率，
     * 横向不会低于显示宽度；比显示盒大的带再缩到 [CoverDecode.Plan.retainedWidth]（保留位图只留显示盒需要的像素，
     * 加宽源的长条封面因此不会按源宽留在缓存里）。给不出子集尺寸的编码器与解码失败都回 null，由调用方退回整图子采样。
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

    private fun readBytes(context: Context, uri: String): ByteArray? = try {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
    } catch (t: Throwable) {
        null
    }
}

/**
 * 原始页字节磁盘缓存（票 07）：键=bookId#index 的 SHA-256 前 32 位十六进制
 * （bookId 含 content:// 等非法文件名字符）。超上限按 lastModified 淘汰至 80%。
 */
class PageDiskCache(
    private val dir: File,
    private val maxBytes: Long = 200L * 1024 * 1024,
) {

    fun get(key: String): ByteArray? = try {
        val f = fileFor(key)
        // 0 长度 = 上次非原子写残留的截断文件，视为 miss
        if (f.exists() && f.length() > 0) f.readBytes() else null
    } catch (t: Throwable) {
        null
    }

    /** 原子写：先写 .tmp 再 rename，避免截断文件被读到（review P1） */
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
            synchronized(this) { trimIfNeeded() }
        } catch (t: Throwable) {
            // 缓存写失败不影响阅读
        }
    }

    private fun fileFor(key: String): File = File(dir, sha256Hex(key).take(32) + ".bin")

    private fun trimIfNeeded() {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        val target = maxBytes * 8 / 10
        files.sortedBy { it.lastModified() }.forEach { f ->
            if (total <= target) return
            total -= f.length()
            f.delete()
        }
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
