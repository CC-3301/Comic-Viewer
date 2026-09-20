package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.nav.LastBrowsing
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.nav.StartupTarget
import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.FakeTreeBackend
import com.cc3301.comicviewer.core.source.FakeTreeNode
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.fakeDir
import com.cc3301.comicviewer.core.source.fakeFile
import com.cc3301.comicviewer.core.source.fs.FileBackend
import com.cc3301.comicviewer.core.source.smb.SmbException
import com.cc3301.comicviewer.core.source.smb.SmbFailureKind
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动还原「上次阅读的书」的可读性判定（票 #97 AC「升级路径」，接缝 = [resolveStartupRead]）。
 *
 * 为什么单列这一层：本票把「本层有子目录/压缩包」的目录由书改判为容器，因此**上一版落盘的** `lastRead.bookId`
 * 完全可能指向这样一个目录。旧路径直接把它当书打开 → `openBook` 抛
 * `IllegalArgumentException("不是一本书：<本机绝对路径>")` → 界面原样显示（本机绝对路径）且人停在阅读器里。
 * 现在导航前先试开一次：不是书就**回落到浏览层**（优先上次停留的位置，其次这个 id 自己——它正是那个容器）。
 *
 * 本文件用**真来源**（[DocumentTreeSource]，内存后端 / 本地临时目录）钉 AC 那条路径，用最小假体钉两侧边界：
 * 0 页的书算书（空压缩包 → 中文空态，不回落到浏览层）、传输故障不算「不是书」（保留票 #91 的重试路径）。
 */
class StartupReadFallbackTest {

    private val connId = 7L
    private val lastRead = LastRead(connId, "root/A")
    private val lastBrowsing = LastBrowsing(connId, "root/A")

    private fun source(vararg kids: FakeTreeNode): Source =
        DocumentTreeSource(FakeTreeBackend(fakeDir("root").add(*kids)), InMemoryProgressStore())

    /**
     * 「已变成容器」的那个目录：本层没有图片，只有子目录（票 #97 判定 ⇒ isBook=false）
     */
    private fun containerDir() =
        fakeDir("root/A").add(fakeDir("root/A/B").add(fakeFile("root/A/B/001.jpg")))

    /** 只改 `openBook` 的来源假体（仓库既有写法：`Source by delegate`；接口将来新增方法也不会让它静默失效） */
    private class FailingSource(
        private val delegate: Source,
        private val failure: Throwable,
    ) : Source by delegate {
        override suspend fun openBook(bookId: String): BookHandle = throw failure
    }

    /** 真实但空的来源：当假体的 delegate 用 */
    private fun emptySource(): Source =
        DocumentTreeSource(FakeTreeBackend(fakeDir("root")), InMemoryProgressStore())

    @Test
    fun `上次阅读的目录已变成容器：回落到浏览层 不进阅读器`() = runTest {
        val outcome = resolveStartupRead(source(containerDir()), lastRead, lastBrowsing)

        assertEquals("绝不把用户丢进只报错、还带绝对路径的阅读器", StartupTarget.OpenBrowser(lastBrowsing), outcome.target)
        assertTrue("AC「给中文提示」：回落必须告知用户为何没回到那本书：${outcome.notice}", !outcome.notice.isNullOrBlank())
        assertTrue("提示不出现异常原文/路径/id：${outcome.notice}", outcome.notice!!.contains("已回到"))
    }

    @Test
    fun `没有可用的浏览位置时 回落到这个 id 自己（那个已变成容器的目录）`() = runTest {
        // AC 场景里这个 id 正是那个容器：点开就是它的条目列表，用户接着就能挑一本读
        val outcome = resolveStartupRead(source(containerDir()), lastRead, lastBrowsing = null)

        assertEquals(StartupTarget.OpenBrowser(LastBrowsing(connId, "root/A")), outcome.target)
        assertTrue("回落仍有中文提示：${outcome.notice}", !outcome.notice.isNullOrBlank())
    }

    @Test
    fun `上次停留的位置属于别的连接时 不用它回落`() = runTest {
        val otherConn = LastBrowsing(connId = 99L, containerId = "root/other")

        val outcome = resolveStartupRead(source(containerDir()), lastRead, otherConn)

        assertEquals(
            "跨连接的浏览位置不能拿来当落点（会把用户带到别的库）",
            StartupTarget.OpenBrowser(LastBrowsing(connId, "root/A")),
            outcome.target,
        )
    }

    @Test
    fun `id 越界或文件已删 也算不是书：同样回落到浏览层`() = runTest {
        val missing = FailingSource(emptySource(), IllegalArgumentException("无效或越界引用：root/A"))

        val outcome = resolveStartupRead(missing, lastRead, lastBrowsing)

        assertEquals(StartupTarget.OpenBrowser(lastBrowsing), outcome.target)
        assertTrue("回落仍有中文提示：${outcome.notice}", !outcome.notice.isNullOrBlank())
    }

    @Test
    fun `没有浏览位置且这个 id 已删（列不出来）：回落到首页`() = runTest {
        // 不能把一个列不出来的层交给浏览页（那里只会再报一次错，也就会再把引用原文显示一遍）
        val outcome = resolveStartupRead(source(containerDir()), LastRead(connId, "root/已删除"), lastBrowsing = null)

        assertEquals(StartupTarget.OpenHome, outcome.target)
        assertTrue("回落到首页也给中文提示：${outcome.notice}", !outcome.notice.isNullOrBlank())
    }

    @Test
    fun `还是书就照常进阅读器`() = runTest {
        val book = fakeDir("root/A").add(fakeFile("root/A/001.jpg"), fakeFile("root/A/002.jpg"))

        val outcome = resolveStartupRead(source(book), lastRead, lastBrowsing)

        assertEquals(StartupTarget.OpenReader(lastRead), outcome.target)
        assertNull("正常进阅读器不提示", outcome.notice)
    }

    @Test
    fun `0 页的书算书：照常进阅读器（界面给中文空态）`() = runTest {
        // 空/坏压缩包是书，只是包里没有图片（票 #97 空书口径：0 页句柄）——不能误判成「不是书」而回落
        val root = Files.createTempDirectory("startup-read-empty-book").toFile()
        ZipOutputStream(File(root, "empty.cbz").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("not-image".toByteArray())
            zip.closeEntry()
        }
        val local = DocumentTreeSource(FileBackend(root), InMemoryProgressStore())
        val emptyBook = LastRead(connId, File(root, "empty.cbz").absolutePath)

        val outcome = resolveStartupRead(local, emptyBook, lastBrowsing)

        assertEquals(StartupTarget.OpenReader(emptyBook), outcome.target)
        assertNull("0 页的书不算回落，不提示", outcome.notice)
    }

    @Test
    fun `传输故障不算不是书：照常进阅读器 保留 #91 的重试路径`() = runTest {
        val offline = FailingSource(emptySource(), SmbException(SmbFailureKind.TIMEOUT, "连接超时"))

        val outcome = resolveStartupRead(offline, lastRead, lastBrowsing)

        assertEquals(
            "断链/超时是暂时性失败：阅读器照旧显示中文错误 + 点此重试（#91 口径不回退）",
            StartupTarget.OpenReader(lastRead),
            outcome.target,
        )
        assertNull("暂时性失败不提示「回落到浏览层」", outcome.notice)
    }

    @Test
    fun `两次来源调用都切到 IO 线程（不在调用方线程上跑阻塞调用）`() = runTest {
        // 评审 P1：本接缝的调用方是启动 effect（Main）；本地/SAF 是 provider IPC、SMB/WebDAV 是同步 socket、
        // Komga 是同步 HTTP——不切 IO 就不只是冷启动卡顿：主程 socket 会抛 NetworkOnMainThreadException，
        // 而它不是 IllegalArgumentException，回落判定会在网络来源上静默失效。这里钉「两处调用都不在调用方线程上」。
        val delegate = emptySource()
        val threads = mutableListOf<String>()
        val recording = object : Source by delegate {
            override suspend fun openBook(bookId: String): BookHandle {
                threads += Thread.currentThread().name
                return delegate.openBook(bookId)
            }

            override suspend fun listEntries(containerId: String?, sort: SortMode): List<BrowseEntry> {
                threads += Thread.currentThread().name
                return delegate.listEntries(containerId, sort)
            }
        }
        val callerThread = Thread.currentThread().name

        resolveStartupRead(recording, LastRead(connId, "root/不存在"), lastBrowsing = null)

        assertEquals("openBook 与 listEntries 各一次（回落路径会走到第二次探测）", 2, threads.size)
        assertTrue("来源调用不得跑在调用方线程（$callerThread）上：$threads", threads.none { it == callerThread })
    }
}
