package com.cc3301.comicviewer.ui

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页字节磁盘缓存（票 07）的清理时机（票 #73）。
 *
 * 本票的承重约束：**取页路径上不出现列目录 / 排序 / 删除**——[PageDiskCache.put] 只写字、记字节数，
 * 再把清理排到后台执行器；清理本身按批（一趟最多删 `trimBatchSize` 个文件；还没到目标线且本趟确有文件被删
 * 才再排一趟，一个都没删掉就停到下次写入）。
 * 所以这里注入手工执行器：`pending` 非零 = 清理还没跑，此时文件还在，就证明清理不在取页路径上。
 */
class PageDiskCacheTest {

    /** 手工执行器：任务只排队，由测试决定什么时候真的跑 */
    private class ManualExecutor : Executor {
        private val queued = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            queued += command
        }

        val pending: Int get() = queued.size

        /** 只跑调用时刻已排队的任务（清理重排的新任务留下，用来观察分批） */
        fun drainOnce() {
            repeat(queued.size) { queued.removeFirst().run() }
        }

        /** 一直跑到没有排队任务（分批清理的后续批次也跑完） */
        fun drain() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    private val dir: File = Files.createTempDirectory("page-disk-cache").toFile()

    /** 记下谁列过目录：取页路径不该列目录（票 #73 的承重约束） */
    private class ListingWatchdog(path: File) : File(path.path) {
        var listCalls = 0

        override fun list(): Array<String>? {
            listCalls++
            return super.list()
        }

        override fun listFiles(): Array<File>? {
            listCalls++
            return super.listFiles()
        }
    }

    /**
     * 删除全部失败的缓存目录（票 #73 加固）：`listFiles()` 交出的句柄删不动。
     * 用来验证「本趟一个都没删掉就不再续排」——否则会反复「扫全目录 + 重试删除」。
     */
    private class UndeletableDir(path: File) : File(path.path) {
        override fun listFiles(): Array<File>? =
            super.listFiles()?.map { f -> object : File(f.path) { override fun delete(): Boolean = false } }
                ?.toTypedArray()
    }

    private fun files(): List<File> = dir.listFiles()?.toList() ?: emptyList()

    private fun totalBytes(): Long = files().sumOf { it.length() }

    private fun cache(maxBytes: Long, executor: Executor, trimBatchSize: Int = PAGE_CACHE_TRIM_BATCH) =
        PageDiskCache(dir = dir, maxBytes = maxBytes, trimExecutor = executor, trimBatchSize = trimBatchSize)

    @Test
    fun `写入不删文件：清理只排在后台执行器上`() {
        val executor = ManualExecutor()
        val cache = cache(maxBytes = 10, executor = executor)

        cache.put("k1", ByteArray(8))
        cache.put("k2", ByteArray(8))

        assertEquals("两页都写下去了", 2, files().size)
        assertEquals("已超上限，清理已排队", 1, executor.pending)
        assertEquals("取页路径上一个文件都没删", 16L, totalBytes())

        executor.drain()

        assertEquals("清理把占用降到目标线 8 字节", 8L, totalBytes())
        assertEquals("只留一页", 1, files().size)
    }

    @Test
    fun `删除失败时不再续排：不反复重扫目录`() {
        dir.mkdirs()
        repeat(4) { i -> File(dir, "old$i.bin").writeBytes(ByteArray(8)) }
        val executor = ManualExecutor()
        // 5 × 8 = 40 字节，上限 10 → 目标线 8：计划要删 4 个，远多于一趟的 1 个（旧判据会一直续排）
        val cache = PageDiskCache(
            dir = UndeletableDir(dir),
            maxBytes = 10,
            trimExecutor = executor,
            trimBatchSize = 1,
        )

        cache.put("k1", ByteArray(8))
        executor.drainOnce()

        assertEquals("删除全部失败：本趟一个都没删掉", 5, files().size)
        assertEquals("不得续排下一批", 0, executor.pending)
    }

    @Test
    fun `清理不把占用重复累加：真占用未超上限时第二次写入不再排清理`() {
        val executor = ManualExecutor()
        // 上限 20：真占用 16（两个 8 字节条目）没超限，就不该再排清理
        val cache = cache(maxBytes = 20, executor = executor)

        cache.put("k1", ByteArray(8))
        executor.drain() // 进程内第一次写入的那趟核对：扫描把计数重算成真实占用 8

        cache.put("k2", ByteArray(8))

        assertEquals("16 ≤ 20：第二次写入不该排清理", 0, executor.pending)
        assertEquals("两页都在，没被误删", 16L, totalBytes())
    }

    @Test
    fun `取页路径不列目录：只有后台清理会扫缓存目录`() {
        val watched = ListingWatchdog(dir)
        val executor = ManualExecutor()
        val cache = PageDiskCache(dir = watched, maxBytes = 10, trimExecutor = executor)

        cache.put("k1", ByteArray(8))
        cache.put("k2", ByteArray(8))
        cache.put("k3", ByteArray(8))
        cache.get("k1")
        cache.get("never-written")

        assertEquals("取页/取字节路径上一次目录列表都不发", 0, watched.listCalls)

        executor.drain()

        assertTrue("清理确实扫过目录", watched.listCalls > 0)
    }

    @Test
    fun `一趟最多删一批，剩下的重新排队`() {
        val executor = ManualExecutor()
        val cache = cache(maxBytes = 10, executor = executor, trimBatchSize = 1)

        cache.put("k1", ByteArray(8))
        cache.put("k2", ByteArray(8))
        cache.put("k3", ByteArray(8))

        executor.drainOnce()

        assertEquals("一趟只删一批（1 个文件 = 8 字节）", 16L, totalBytes())
        assertEquals("还没到目标线：重新排了下一批", 1, executor.pending)

        executor.drain()

        assertEquals("分批跑完还是落在目标线 8 字节", 8L, totalBytes())
        assertEquals(0, executor.pending)
    }

    @Test
    fun `进程内第一次写入核出上一个进程遗留的占用`() {
        dir.mkdirs()
        // 上一个进程留下的缓存目录：三个 8 字节文件，修改时间都比本次写入旧
        listOf(1_000L, 2_000L, 3_000L).forEachIndexed { i, mtime ->
            File(dir, "leftover$i.bin").apply { writeBytes(ByteArray(8)) }.setLastModified(mtime)
        }
        val executor = ManualExecutor()
        val cache = cache(maxBytes = 10, executor = executor)

        val fresh = ByteArray(8).also { it[0] = 42 }
        cache.put("k1", fresh)
        executor.drain()

        assertEquals("遗留占用被核出来并清到目标线 8 字节", 8L, totalBytes())
        assertEquals("只留本次写入的那一页", 1, files().size)
        assertEquals("留下的是刚写进去的字节", 42, files().single().readBytes()[0].toInt())
    }

    @Test
    fun `不超上限时写入不再排清理`() {
        val executor = ManualExecutor()
        val cache = cache(maxBytes = 100, executor = executor)

        cache.put("k1", ByteArray(8))
        executor.drain() // 进程内第一次写入的那趟核对：目录没超上限 → 什么都不删
        assertEquals("核对不误删", 8L, totalBytes())

        cache.put("k2", ByteArray(8))
        cache.put("k3", ByteArray(8))

        assertEquals("没超上限就不必再清", 0, executor.pending)
        assertEquals("三页都在", 24L, totalBytes())
    }

    @Test
    fun `取回写入的字节，零长度文件视为未命中`() {
        val executor = ManualExecutor()
        val cache = cache(maxBytes = 100, executor = executor)
        val bytes = byteArrayOf(1, 2, 3)

        cache.put("k1", bytes)

        assertArrayEquals(bytes, cache.get("k1"))
        assertNull("没写过的键是未命中", cache.get("k2"))

        // 上次非原子写残留的截断文件：0 长度视为未命中（票 07 既有口径）
        files().single().writeBytes(ByteArray(0))
        assertNull(cache.get("k1"))
    }
}
