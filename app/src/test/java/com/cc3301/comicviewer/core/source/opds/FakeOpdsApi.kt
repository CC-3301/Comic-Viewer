package com.cc3301.comicviewer.core.source.opds

import java.io.File

/**
 * OPDS 假实现（票 15 测试用）：内存里的 feed 表 + 下载表，不需要 Docker/真实 OPDS 服务。
 * 下载按块回调进度（验证界面进度通道），并记录每个 URL 的下载次数（验证缓存命中不重复下载）。
 */
class FakeOpdsApi(
    private val feeds: Map<String, String> = emptyMap(),
    private val downloads: Map<String, ByteArray> = emptyMap(),
    /** 每次进度回调的块大小（默认一次报完） */
    private val chunkBytes: Int = Int.MAX_VALUE,
) : OpdsApi {

    /** URL → 下载次数 */
    val downloadCounts = mutableMapOf<String, Int>()

    /** 非 null 时所有调用都抛它（验证失败冒泡） */
    private var failure: Throwable? = null

    fun alwaysFailWith(t: Throwable) {
        failure = t
    }

    override fun fetchFeed(url: String): String {
        failIfNeeded()
        return feeds[url] ?: throw OpdsException(OpdsFailureKind.NOT_FOUND, "没有这个 feed：" + url, null)
    }

    override fun download(
        url: String,
        target: File,
        isActive: () -> Boolean,
        onProgress: DownloadListener,
    ) {
        failIfNeeded()
        val part = File(target.parentFile, target.name + OpdsCache.PART_SUFFIX)
        val bytes = downloads[url] ?: throw OpdsException(OpdsFailureKind.NOT_FOUND, "没有这个下载：" + url, null)
        target.parentFile?.mkdirs()
        try {
            part.outputStream().use { output ->
                var offset = 0
                while (offset < bytes.size) {
                    if (!isActive()) throw kotlinx.coroutines.CancellationException("下载已取消")
                    val size = minOf(chunkBytes, bytes.size - offset)
                    output.write(bytes, offset, size)
                    offset += size
                    onProgress(offset.toLong(), bytes.size.toLong())
                }
            }
            downloadCounts[url] = (downloadCounts[url] ?: 0) + 1
            if (!part.renameTo(target)) throw OpdsException(OpdsFailureKind.OTHER, "改名失败", null)
        } catch (t: Throwable) {
            runCatching { part.delete() }
            throw t
        }
    }

    override fun close() {
        // 内存实现无资源
    }

    private fun failIfNeeded() {
        failure?.let { throw it }
    }
}
