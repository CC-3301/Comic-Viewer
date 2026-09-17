package com.cc3301.comicviewer.core.source

/**
 * 下载进度（票 15：OPDS 先下载到本地缓存再阅读）。
 * 只有需要「下载后阅读」的来源才会给出进度；其余来源为 null，UI 不显示进度条。
 */
data class DownloadProgress(
    /** 正在下载的条目 id（书 id） */
    val bookId: String,
    /** 已下载字节 */
    val bytesDownloaded: Long,
    /** 总字节；服务器未给 Content-Length 时为 null（显示不确定进度） */
    val totalBytes: Long?,
) {
    /** 0f..1f；总量未知时为 null */
    val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (bytesDownloaded.toFloat() / it).coerceIn(0f, 1f) }
}

