package com.cc3301.comicviewer.core.source.remote

import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 远程来源打开压缩包的开销（票 #91：SMB/WebDAV 上的 CBZ「一直转圈不结束」）。
 *
 * 根因（源码级）：
 * - SMB 的随机访问句柄长度**不是内存里的字段**：`SmbRandomAccess.size` 读的是
 *   `SmbFile.getLength()`，而 smbj 0.15.0 的 `File.getLength()` → `DiskEntry.getFileInformation(...)` →
 *   `DiskShare.queryInfo(...)`，即**每次访问 = 一次 QUERY_INFO 网络往返**（WebDAV 侧是构造期取好的字段）。
 * - 而 ZIP 解析是「每个条目若干次小读」（u32 签名、5×u16、文件名各一次读，本夹具 60 条 = 约 420 次读），
 *   [BlockCachedRandomAccess.read] 每次还要读 2~3 次 `size`：一次开包因此从「几次往返」变成**上千次往返**，
 *   界面既不报错也永远完不成（没有任何一次调用超时，超时契约自然也不触发）。
 *
 * 本文件把这条开销变成可断言的上界（与票 #30/#51 的列目录计数同一套路）：
 * 夹具里的随机访问**不缓存长度**（`size` 每次都往下取 = SMB 上每次发一次 QUERY_INFO），
 * 于是「解析一本包要解析几次长度」就成了纯粹的断言对象——修复前 60 条是 1090 次，修复后是常量级。
 * 翻页路径同理（每页一个新句柄）。
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
    ) : FsBackend {

        private val delegate = FileBackend(book.parentFile!!)
        private val sizeCounter = AtomicInteger(0)
        private val fetchCounter = AtomicInteger(0)

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
                val raw: RandomAccessBytes =
                    if (failing) FailingRemoteAccess() else node.openRandomAccess()
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

    /** 每次都读不出来的句柄（模拟断链：读字节时抛裸 IO 异常） */
    private class FailingRemoteAccess : RandomAccessBytes {
        override val size: Long = 4096L
        override fun read(offset: Long, len: Int): ByteArray = throw java.io.IOException("broken pipe")
        override fun close() = Unit
    }
}
