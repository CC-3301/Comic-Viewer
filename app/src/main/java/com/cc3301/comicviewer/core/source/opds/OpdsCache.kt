package com.cc3301.comicviewer.core.source.opds

import java.io.File

/**
 * OPDS 下载缓存（票 15）：默认上限 2GB（可配），超限按 LRU 清理，支持手动清空与占用统计。
 *
 * LRU 依据文件最后修改时间：每次命中缓存都 [touch] 一次，因此「最近读过的书」不会被清掉。
 * 目录布局：`<cacheDir>/opds/<bookId 的哈希>.<扩展名>`——用哈希避免书名里的特殊字符与长度问题。
 */
class OpdsCache(
    private val dir: File,
    /** 上限字节；<=0 表示不限制（设置里可配，SPEC 故事 55） */
    private val limitBytesProvider: () -> Long = { DEFAULT_LIMIT_BYTES },
) {

    /** 缓存占用（字节）；下载中的 `.part` 文件不计入（失败的半成品不该算占用） */
    fun sizeBytes(): Long = cachedFiles().sumOf { it.length() }

    /** 缓存文件数量（同样不含 `.part`） */
    fun fileCount(): Int = cachedFiles().size

    private fun cachedFiles(): List<File> = dir.listFiles().orEmpty()
        .filter { it.isFile && !it.name.endsWith(PART_SUFFIX) }

    /**
     * 缓存文件路径（不保证存在）。同一本书多次调用返回同一路径：
     * 书 id 由 OPDS 条目 id 派生，因此换服务器不会误命中别人的缓存。
     */
    fun fileFor(bookId: String, linkUrl: String): File {
        val extension = linkUrl.substringAfterLast('.', "").take(8).takeIf { it.matches(EXTENSION_REGEX) } ?: "bin"
        // 哈希同时含 bookId 与链接：同一本书换了获取地址（服务器换 CDN/文件名）不会错用旧缓存
        val hash = (bookId + "|" + linkUrl).hashCode().toUInt().toString(16)
        return File(dir, "opds_" + hash + "." + extension)
    }

    /** 已缓存且非空时返回它并刷新访问时间（LRU 命中） */
    fun cached(bookId: String, linkUrl: String): File? {
        val file = fileFor(bookId, linkUrl)
        if (!file.isFile || file.length() <= 0L) return null
        touch(file)
        return file
    }

    /** 记录一次访问（LRU 心跳）：失败不影响阅读 */
    fun touch(file: File) {
        runCatching { file.setLastModified(System.currentTimeMillis()) }
    }

    /**
     * 超限清理：按最后修改时间从旧到新删除，直到降到上限之内。
     * - 正在下载的临时文件（`.part`）不参与统计与删除；
     * - 最近 [graceMs] 内访问过的文件跳过：正在阅读的书每次取页都会刷新访问时间，
     *   因此不会被「刚下载的另一本书」触发的清理删掉（否则读到一半会持续加载失败）。
     */
    fun enforceLimit(now: Long = System.currentTimeMillis(), graceMs: Long = GRACE_MS): Int {
        val limit = limitBytesProvider()
        if (limit <= 0L) return 0
        var removed = 0
        var total = sizeBytes()
        if (total <= limit) return 0
        val files = cachedFiles().sortedBy { it.lastModified() }
        for (file in files) {
            if (total <= limit) break
            if (now - file.lastModified() < graceMs) continue
            val length = file.length()
            if (file.delete()) {
                total -= length
                removed++
            }
        }
        return removed
    }

    /** 手动清空（设置页按钮）：返回删除的文件数；`.part`（下载中）不删，避免打断正在进行的下载 */
    fun clear(): Int {
        var removed = 0
        cachedFiles().forEach { file ->
            if (file.delete()) removed++
        }
        return removed
    }

    companion object {
        /** 默认上限 2GB（SPEC 故事 21） */
        const val DEFAULT_LIMIT_BYTES = 2L * 1024 * 1024 * 1024

        const val PART_SUFFIX = ".part"

        /** 清理冷静期：这段时间内访问过的缓存视为「正在用」 */
        const val GRACE_MS = 5 * 60 * 1000L

        private val EXTENSION_REGEX = Regex("[A-Za-z0-9]{1,8}")
    }
}
