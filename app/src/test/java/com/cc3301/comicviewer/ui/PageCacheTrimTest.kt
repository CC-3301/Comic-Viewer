package com.cc3301.comicviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 页字节磁盘缓存的淘汰判定（票 #73）：纯逻辑，不碰文件系统。
 *
 * 口径沿用票 07：总占用不超上限 → 一个都不删；超了 → 按修改时间从旧到新删，直到落到上限的 80%
 * （留余量，否则每次写入都要清一次）。这里锁的是「删哪些、删到哪」；
 * 「什么时候清」由 [PageDiskCacheTest] 锁——清理必须在后台，不在取页路径上。
 */
class PageCacheTrimTest {

    private fun file(name: String, sizeBytes: Long, lastModifiedMs: Long) =
        PageCacheFile(name = name, sizeBytes = sizeBytes, lastModifiedMs = lastModifiedMs)

    @Test
    fun `不超上限时一个都不删`() {
        val files = listOf(file("a", 4, 1), file("b", 4, 2))
        assertEquals(
            "总占用 8 字节未超上限 10 字节：不该删任何文件",
            emptyList<PageCacheFile>(),
            PageCacheTrim.filesToDelete(files, maxBytes = 10),
        )
    }

    @Test
    fun `超上限时按修改时间从旧到新删到上限的八成`() {
        // 上限 90 → 目标线 72：总占用 100；删掉最旧的三张（10 + 10 + 10）后剩 70 ≤ 72；
        // 只删两张时还剩 80 > 72，必须继续删
        val files = listOf(
            file("newest", 70, 4),
            file("oldest", 10, 1),
            file("middle", 10, 2),
            file("older", 10, 3),
        )
        val doomed = PageCacheTrim.filesToDelete(files, maxBytes = 90)
        assertEquals("最旧的先删", listOf("oldest", "middle", "older"), doomed.map { it.name })
        val remaining = files.sumOf { it.sizeBytes } - doomed.sumOf { it.sizeBytes }
        assertEquals("删到 70 字节（≤ 目标线 72）", 70L, remaining)
    }

    @Test
    fun `修改时间相同时按文件名定序（判定确定）`() {
        val files = listOf(file("bbb", 2, 7), file("aaa", 2, 7))
        val doomed = PageCacheTrim.filesToDelete(files, maxBytes = 3)
        assertEquals("同一修改时间下取文件名序，判定不随扫描顺序变化", listOf("aaa"), doomed.map { it.name })
    }

    @Test
    fun `目标线是上限的八成`() {
        assertEquals("上限 100 → 目标线 80", 80L, PageCacheTrim.targetBytesOf(100))
        assertEquals("上限 205 → 目标线 164（205 × 8 ÷ 10）", 164L, PageCacheTrim.targetBytesOf(205))
    }
}
