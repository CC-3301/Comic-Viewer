package com.cc3301.comicviewer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap

/**
 * 页面解码器（票 04）：BitmapFactory 按目标宽度子采样（AC：大图不 OOM），
 * GIF 走 decodeByteArray 天然取静态首帧（spec）；LruCache 占堆 1/8。
 */
object PageDecoder {

    private val cache = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(4 * 1024),
    ) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.asAndroidBitmap().allocationByteCount / 1024
    }

    /** 解码 uri 引用的图片（file://、content:// 均可），GIF 静态首帧 */
    fun decodeUri(context: Context, uri: String, targetWidthPx: Int): ImageBitmap? {
        val key = "$uri@$targetWidthPx"
        cache.get(key)?.let { return it }
        val bytes = readBytes(context, uri) ?: return null
        return decodeBytes(key, bytes, targetWidthPx)
    }

    /** 解码已读入内存的页面字节；key=缓存键（bookId#index 形式） */
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
