package com.cc3301.comicviewer.core.source.remote

import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.SourceReadTimeoutException
import com.cc3301.comicviewer.core.source.TransportFailure
import com.cc3301.comicviewer.core.source.fs.FileBackend
import com.cc3301.comicviewer.core.source.fs.FsBackend
import com.cc3301.comicviewer.core.source.fs.FsNode
import com.cc3301.comicviewer.core.source.smb.SmbException
import com.cc3301.comicviewer.core.source.smb.SmbFailureKind
import com.cc3301.comicviewer.core.source.smb.asSmbException
import com.cc3301.comicviewer.core.source.zip.RandomAccessBytes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 远程来源压缩包读取的两条口径（票 #91）：
 *
 * 1. **读取开销有上界**：把「长度解析次数」「取字节次数」计成可断言的指标（与票 #30/#51 的列目录计数同一套路），
 *    钉住「与包内条目数无关的常量级」。夹具里的随机访问**不缓存长度**（每次访问都往下取），
 *    与 SMB 同形；根因与实测数据见 `BlockCachedRandomAccess.handleSize` 的 KDoc，这里不复述。
 * 2. **失败必须可失败**（票面 AC2）：注入「读永不返回」的句柄，断言打开书 / 取页都会在有限时间内
 *    给出可读中文错误，而不是无限等待。
 *
 * 夹具边界（诚实记录，别当 SMB 实现被覆盖了）：本文件的节点是**本地文件 + 代理夹具**
 * （`FileRandomAccess` 外面套共享块缓存），钉的是 `BlockCachedRandomAccess` 的口径与
 * `DocumentTreeSource` 的看门狗；**不实例化 `SmbRandomAccess`**，也不经过 `SmbjTransport.openRandomAccess`
 * ——`SmbRandomAccess` 收的是 smbj 的 `SmbFile`（final 类、需要活的连接），JVM 单测里构造不出来，
 * 仓库也没有可注入的 smbj 抽象层。因此「真实 SMB 上能打开」仍是人工项（无 Docker/无真机）。
 *
 * 棘轮重标条件：块缓存参数（`DEFAULT_BLOCK_BYTES`/`DEFAULT_CACHE_BLOCKS`）、`ZipArchive.findEocd` 的
 * `size` 读次数、或看门狗默认时长一变，本文件的 `<= 2 / <= 3 / <= 4` 上界与注入的看门狗时长都要重测。
 */
class RemoteArchiveReadCostTest {

    @Test
    fun `打开远程压缩包的长度解析次数与条目数无关`() = runTest {
        val small = RemoteCountingBackend(cbz(entries = 20))
        val big = RemoteCountingBackend(cbz(entries = 300))

        val smallBook = DocumentTreeSource(small, InMemoryProgressStore()).openBook(small.bookId)
        val bigBook = DocumentTreeSource(big, InMemoryProgressStore()).openBook(big.bookId)

        assertEquals(20, smallBook.pageCount)
        assertEquals(300, bigBook.pageCount)
        // 修复前：20 条 = 250 次、300 条 = 3500 次（每个条目 5~7 次小读 × 每次读 2~3 次 size）
        assertEquals(
            "长度解析次数必须与条目数无关（SMB 上每次读 size 都是一次 QUERY_INFO 往返）：" +
                "20 条 = " + small.sizeAccesses + " 次、300 条 = " + big.sizeAccesses + " 次",
            small.sizeAccesses,
            big.sizeAccesses,
        )
        assertTrue(
            "常量级：EOCD 查找一次 + 块缓存首次读一次 = 2（" + big.sizeAccesses + "）",
            big.sizeAccesses <= 2,
        )
        assertTrue(
            "取字节次数同样与条目数无关（块缓存生效）：300 条实际 " + big.fetchCalls + " 次",
            big.fetchCalls <= 3,
        )
    }

    @Test
    fun `翻页的长度解析与取字节都不随包内条目数放大`() = runTest {
        val backend = RemoteCountingBackend(cbz(entries = 300))
        val handle = DocumentTreeSource(backend, InMemoryProgressStore()).openBook(backend.bookId)
        backend.reset()

        val page = handle.loadPage(0)

        assertTrue("取到的是一页图片字节：" + page.bytes.size, page.bytes.isNotEmpty())
        assertTrue(
            "每页一个新句柄：长度只解析常量级（不按条目数放大），实际 " + backend.sizeAccesses + " 次",
            backend.sizeAccesses <= 2,
        )
        assertTrue(
            "取字节次数与条目数无关：实际 " + backend.fetchCalls + " 次",
            backend.fetchCalls <= 4,
        )
    }

    @Test
    fun `读永不返回时 打开压缩包在有限时间内给出中文错误 不无限等待`() = runTest {
        // 票面 AC2 的核心：维护者看到的是「一直转圈不结束」——没有错误、也没有结束。
        // 阻塞读不会观察协程取消，所以必须有看门狗；这里注入一个永不返回的句柄来钉住这条保证。
        val release = CountDownLatch(1)
        val backend = RemoteCountingBackend(cbz(entries = 60), stuck = release, stuckFromCall = 1)
        val source = DocumentTreeSource(backend, InMemoryProgressStore(), readDeadlineMs = 200)

        try {
            val thrown = runCatching { source.openBook(backend.bookId) }.exceptionOrNull()

            assertTrue("超时必须给出可读的中文错误（不是永远等待）：$thrown", thrown is SourceReadTimeoutException)
            assertTrue("文案要能直接展示给用户：$thrown", (thrown?.message ?: "").contains("超时"))
        } finally {
            release.countDown() // 放行卡住的线程，别把它留在测试进程里
        }
    }

    @Test
    fun `读永不返回时 取页同样在有限时间内失败`() = runTest {
        val release = CountDownLatch(1)
        // 第一次 random access（打开书时的中央目录）正常，第二次（取页）卡死
        val backend = RemoteCountingBackend(cbz(entries = 60), stuck = release, stuckFromCall = 2)
        val source = DocumentTreeSource(backend, InMemoryProgressStore(), readDeadlineMs = 200)
        val handle = source.openBook(backend.bookId)

        try {
            val thrown = runCatching { handle.loadPage(0) }.exceptionOrNull()

            assertTrue("取页也要有界失败：$thrown", thrown is SourceReadTimeoutException)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `远程读取中途失败时按传输故障冒泡 不静默当成「不是一本书」`() = runTest {
        // 断链：句柄建好了，读字节时炸（真实链路里由 ClassifyingRandomAccess 归成 SmbException）
        val backend = RemoteCountingBackend(cbz(entries = 60), failing = true)
        val source = DocumentTreeSource(backend, InMemoryProgressStore())

        val thrown = runCatching { source.openBook(backend.bookId) }.exceptionOrNull()

        assertTrue(
            "压缩包路径必须把传输故障报出来（要么中文错误、要么重试），不能吞成「不是一本书」：" + thrown,
            thrown is TransportFailure,
        )
        assertTrue(
            "面向用户的提示要是归类后的中文文案（断链不是「文件问题」）：" + thrown,
            thrown is SmbException && (thrown.message ?: "").isNotEmpty(),
        )
    }

    // ---------- fixture 与测试替身 ----------

    private fun cbz(entries: Int): File {
        val dir = Files.createTempDirectory("remote-read-cost").toFile()
        val file = File(dir, "第01卷.cbz")
        ZipOutputStream(file.outputStream()).use { zip ->
            repeat(entries) { i ->
                zip.putNextEntry(ZipEntry(String.format("%03d.jpg", i)))
                zip.write(("page-$i-" + "x".repeat(40)).toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    /**
     * 计数型「远程」后端：节点与 [FileBackend] 同源，只有随机访问换成
     * [BlockCachedRandomAccess]（SMB/WebDAV 两个真实传输的共用实现），把
     * 「长度解析次数」与「取字节次数」计成可断言的指标。
     */
    private class RemoteCountingBackend(
        private val book: File,
        private val failing: Boolean = false,
        /** 非 null 时，第 [stuckFromCall] 次起的随机访问换成「读永不返回」的句柄 */
        private val stuck: CountDownLatch? = null,
        private val stuckFromCall: Int = 1,
    ) : FsBackend {

        private val delegate = FileBackend(book.parentFile!!)
        private val sizeCounter = AtomicInteger(0)
        private val fetchCounter = AtomicInteger(0)
        private val openCalls = AtomicInteger(0)

        val bookId: String get() = book.absolutePath

        /** `size` 访问次数（SMB 上每次 = 一次 QUERY_INFO 往返） */
        val sizeAccesses: Int get() = sizeCounter.get()

        /** 取字节次数（每次 = 一次网络取数） */
        val fetchCalls: Int get() = fetchCounter.get()

        fun reset() {
            sizeCounter.set(0)
            fetchCounter.set(0)
        }

        override val root: FsNode = wrap(delegate.root)

        override fun resolve(id: String): FsNode? = delegate.resolve(id)?.let(::wrap)

        private fun wrap(node: FsNode): FsNode = object : FsNode by node {
            override fun openRandomAccess(): RandomAccessBytes {
                val call = openCalls.incrementAndGet()
                val raw: RandomAccessBytes = when {
                    stuck != null && call >= stuckFromCall -> StuckRemoteAccess(stuck)
                    failing -> FailingRemoteAccess()
                    else -> node.openRandomAccess()
                }
                val counting = CountingRemoteAccess(raw, sizeCounter, fetchCounter)
                // 与生产同一条归类装饰器：读/关闭发生在后台线程，裸 IO 异常必须归成中文传输故障
                return ClassifyingRandomAccess(counting) { asSmbException(it, "nas", "/comics/第01卷.cbz") }
            }
        }
    }

    /**
     * 块缓存 + 计数的「远程」随机访问：`size` 记一次长度解析、`fetch` 记一次取数。
     * 故意**不缓存** `size`（每次访问都往下取），与 SMB 的 `SmbFile.getLength()` 同形——
     * 长度解析次数因此完全由共享块缓存的行为决定，是这张票要钉的指标。
     */
    private class CountingRemoteAccess(
        private val delegate: RandomAccessBytes,
        private val sizeCounter: AtomicInteger,
        private val fetchCounter: AtomicInteger,
    ) : BlockCachedRandomAccess() {

        override val size: Long
            get() {
                sizeCounter.incrementAndGet()
                return delegate.size
            }

        override fun fetch(offset: Long, len: Int): ByteArray {
            fetchCounter.incrementAndGet()
            return delegate.read(offset, len)
        }

        override fun close() = delegate.close()
    }

    /** 读永不返回的句柄（模拟维护者看到的「永远转圈」：没有异常、也没有结果） */
    private class StuckRemoteAccess(private val release: CountDownLatch) : RandomAccessBytes {
        override val size: Long = 4096L
        override fun read(offset: Long, len: Int): ByteArray {
            release.await()
            return ByteArray(0)
        }

        override fun close() = Unit
    }

    /** 每次都读不出来的句柄（模拟断链：读字节时抛裸 IO 异常） */
    private class FailingRemoteAccess : RandomAccessBytes {
        override val size: Long = 4096L
        override fun read(offset: Long, len: Int): ByteArray = throw java.io.IOException("broken pipe")
        override fun close() = Unit
    }
}
