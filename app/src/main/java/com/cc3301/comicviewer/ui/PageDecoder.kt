package com.cc3301.comicviewer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.cc3301.comicviewer.core.source.BookHandle
import java.io.File
import java.security.MessageDigest

/**
 * 页面解码器（票 04 基础 + 票 07 缓存分层）：
 * - 内存 LruCache：解码后位图（键含 bookId+页索引+目标宽度，防跨书碰撞）
 * - 磁盘缓存：[PageDiskCache] 存原始页字节（SAF 二次打开省 provider IPC）
 * - BitmapFactory 按目标宽度子采样（大图不 OOM）；GIF 静态首帧
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

    /** 解码 uri 引用的图片（file://、content:// 均可），GIF 静态首帧 */
    fun decodeUri(context: Context, uri: String, targetWidthPx: Int): ImageBitmap? {
        val key = "$uri@$targetWidthPx"
        cache.get(key)?.let { return it }
        val bytes = readBytes(context, uri) ?: return null
        return decodeBytes(key, bytes, targetWidthPx)
    }

    /** 解码已读入内存的页面字节；key=缓存键（bookId#index@width 形式） */
    fun decodeBytes(key: String, bytes: ByteArray, targetWidthPx: Int): ImageBitmap? {
        cache.get(key)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidthPx) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565  // 漫画无透明，内存减半
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val image = bmp.asImageBitmap()
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
