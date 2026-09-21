package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.view.CoverByteRequests
import com.cc3301.comicviewer.core.view.CoverDecode

/**
 * 一条封面预取的落地（票 #108 r6，由 `CoverPrefetchLoadTest` 锁定）：**取字节 + 解码进封面分区**。
 *
 * 为什么预取要连解码一起做（真机现象③「点进子文件夹要等一小会才加载好」）：r5 的预取只把**字节**拿到手，
 * 解码仍发生在可见行组合期（`CoverThumb` 的 `LaunchedEffect`）——首屏封面因此还是一张一张解、一张一张
 * 150ms 淡入，观感与维护者最初说的「阅读器进去闪一下」同类。解码键（宽度 + 裁剪目标）由调用方给出，
 * 与可见行**同一份**（`BrowserScreen` 把格宽与裁剪目标算一次、行与预取共用），因此可见行
 * `PageDecoder.cachedCover(decodeKey)` 能直接命中预取解好的那张，**不再重解、也不重取字节**。
 *
 * 字节走 [requests]（同一 id 的在飞合并）：可见行此刻也在要同一张时，两方共用一次来源往返。
 *
 * 返回是否**已经可用**（位图在封面分区里）：调用方（`CoverPrefetchLedger.settle`）按它记「拿到 / 没拿到」，
 * 拿不到的条目按有界退避重试。
 */
internal suspend fun prefetchCoverBitmap(
    entryId: String,
    decodeKey: String,
    targetWidthPx: Int,
    cropTarget: CoverDecode.CropTarget,
    requests: CoverByteRequests,
    loadBytes: suspend () -> ByteArray?,
): Boolean {
    // 位图已在（别的窗口/上一次预取解过、或可见行刚解完）：这一条不需要再取字节
    if (PageDecoder.cachedCover(decodeKey) != null) return true
    val bytes = requests.load(entryId, loadBytes) ?: return false
    return PageDecoder.decodeCoverBytes(decodeKey, bytes, targetWidthPx, cropTarget) != null
}
