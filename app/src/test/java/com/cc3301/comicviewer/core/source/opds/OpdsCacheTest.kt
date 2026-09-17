package com.cc3301.comicviewer.core.source.opds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** OPDS 缓存（票 15）：LRU 清理、上限可配、手动清空、命中刷新访问时间 */
class OpdsCacheTest {

    private fun tempDir(): File = Files.createTempDirectory("opds-cache").toFile()

    private fun file(dir: File, name: String, bytes: Int, lastModified: Long): File =
        File(dir, name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(lastModified)
        }

    @Test
    fun `缓存命中返回文件并刷新访问时间 未命中返回 null`() {
        val dir = tempDir()
        val cache = OpdsCache(dir)
        assertNull("还没下载过", cache.cached("book1", "http://x/1.cbz"))

        val target = cache.fileFor("book1", "http://x/1.cbz")
        assertEquals("扩展名取自链接", "cbz", target.extension)
        target.writeBytes(ByteArray(10))
        target.setLastModified(1000L)

        val hit = cache.cached("book1", "http://x/1.cbz")
        assertNotNull(hit)
        assertTrue("命中要刷新访问时间（LRU 心跳）", hit!!.lastModified() > 1000L)
    }

    @Test
    fun `同一本书多次取路径稳定 不同书或不同链接不共用`() {
        val cache = OpdsCache(tempDir())
        assertEquals(cache.fileFor("b", "http://x/a.cbz"), cache.fileFor("b", "http://x/a.cbz"))
        assertTrue(cache.fileFor("b", "http://x/a.cbz") != cache.fileFor("b", "http://x/b.cbz"))
        assertTrue(cache.fileFor("b", "http://x/a.cbz") != cache.fileFor("c", "http://x/a.cbz"))
    }

    @Test
    fun `空文件不算命中`() {
        val dir = tempDir()
        val cache = OpdsCache(dir)
        cache.fileFor("b", "http://x/a.cbz").writeBytes(ByteArray(0))
        assertNull(cache.cached("b", "http://x/a.cbz"))
    }

    @Test
    fun `超限按最久未访问先删 直到降到上限内`() {
        val dir = tempDir()
        val old = file(dir, "opds_old.cbz", 400, 1000L)
        val middle = file(dir, "opds_mid.cbz", 400, 2000L)
        val recent = file(dir, "opds_new.cbz", 400, 3000L)
        // 上限 900 字节：需要删掉最旧的（400）才降到 800
        val cache = OpdsCache(dir) { 900L }

        assertEquals(1200L, cache.sizeBytes())
        assertEquals(1, cache.enforceLimit())
        assertTrue("最旧的被删", !old.exists())
        assertTrue(middle.exists() && recent.exists())
        assertEquals(800L, cache.sizeBytes())
        assertEquals("已在限额内不再删", 0, cache.enforceLimit())
    }

    @Test
    fun `下载中的 part 文件不参与统计与清理`() {
        val dir = tempDir()
        file(dir, "opds_keep.cbz", 900, 1000L)
        file(dir, "download.cbz.part", 500, 999L)
        val cache = OpdsCache(dir) { 1000L }

        assertEquals("part 不计入占用", 900L, cache.sizeBytes())
        assertEquals("只有 part 超限也不该删已完成的缓存", 0, cache.enforceLimit())
    }

    @Test
    fun `冷静期内的缓存不会被清理 避免删掉正在读的书`() {
        val dir = tempDir()
        val now = 10_000_000L
        // 最旧但刚被读过（mtime = now - 1 分钟，在 5 分钟冷静期内）
        file(dir, "opds_reading.cbz", 600, now - 60_000L)
        file(dir, "opds_stale.cbz", 600, now - 10 * 60_000L)
        val cache = OpdsCache(dir) { 700L }

        val removed = cache.enforceLimit(now = now, graceMs = 5 * 60_000L)

        assertEquals("只能删冷静期之外的那本", 1, removed)
        assertTrue("正在读的书必须留下", File(dir, "opds_reading.cbz").exists())
        assertTrue(!File(dir, "opds_stale.cbz").exists())
    }

    @Test
    fun `手动清空不删下载中的 part 文件`() {
        val dir = tempDir()
        file(dir, "opds_a.cbz", 100, 1000L)
        file(dir, "downloading.cbz.part", 100, 1000L)
        val cache = OpdsCache(dir)

        assertEquals(1, cache.clear())
        assertTrue("下载中不能被打断", File(dir, "downloading.cbz.part").exists())
    }

    @Test
    fun `扩展名剥离 query 与 fragment 让 MIME 判定正确`() {
        val cache = OpdsCache(tempDir())
        assertEquals("cbz", cache.fileFor("b", "http://x/a.cbz?token=1").extension)
        assertEquals("jpg", cache.fileFor("b", "http://x/a.jpg#page").extension)
    }

    @Test
    fun `孤儿 part 文件被回收 但正在下载的 part 保留`() {
        val dir = tempDir()
        val now = 100_000_000L
        val orphan = file(dir, "dead.cbz.part", 100, now - 2 * 60 * 60 * 1000L)
        val active = file(dir, "active.cbz.part", 100, now - 1000L)
        val cache = OpdsCache(dir) { 0L }  // 不限大小：只验证孤儿回收

        cache.enforceLimit(now = now)

        assertTrue("上个进程留下的半成品要回收", !orphan.exists())
        assertTrue("正在下载的不能删", active.exists())
    }

    @Test
    fun `上限为零表示不限制 手动清空删除全部缓存`() {
        val dir = tempDir()
        file(dir, "opds_a.cbz", 100, 1000L)
        file(dir, "opds_b.cbz", 100, 2000L)
        val unlimited = OpdsCache(dir) { 0L }
        assertEquals(0, unlimited.enforceLimit())

        assertEquals(2, unlimited.clear())
        assertEquals(0L, unlimited.sizeBytes())
        assertEquals(0, unlimited.fileCount())
    }
}
