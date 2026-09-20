package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.nav.LastBrowsing
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.nav.StartupTarget
import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.FakeTreeBackend
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.Neighbors
import com.cc3301.comicviewer.core.source.ReadingProgress
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
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

    private fun source(vararg kids: com.cc3301.comicviewer.core.source.FakeTreeNode): Source =
        DocumentTreeSource(FakeTreeBackend(fakeDir("root").add(*kids)), InMemoryProgressStore())

    /** 「已变成容器」的那个目录：本层没有图片，只有子目录（票 #97 判定 ⇒ isBook=false） */
    private fun containerDir() =
        fakeDir("root/A").add(fakeDir("root/A/B").add(fakeFile("root/A/B/001.jpg")))

    @Test
    fun `上次阅读的目录已变成容器：回落到浏览层 不进阅读器`() = runTest {
        val target = resolveStartupRead(source(containerDir()), lastRead, lastBrowsing)

        assertEquals("绝不把用户丢进只报错、还带绝对路径的阅读器", StartupTarget.OpenBrowser(lastBrowsing), target)
    }

    @Test
    fun `没有可用的浏览位置时 回落到这个 id 自己（那个已变成容器的目录）`() = runTest {
        // AC 场景里这个 id 正是那个容器：点开就是它的条目列表，用户接着就能挑一本读
        val target = resolveStartupRead(source(containerDir()), lastRead, lastBrowsing = null)

        assertEquals(StartupTarget.OpenBrowser(LastBrowsing(connId, "root/A")), target)
    }

    @Test
    fun `上次停留的位置属于别的连接时 不用它回落`() = runTest {
        val otherConn = LastBrowsing(connId = 99L, containerId = "root/other")

        val target = resolveStartupRead(source(containerDir()), lastRead, otherConn)

        assertEquals(
            "跨连接的浏览位置不能拿来当落点（会把用户带到别的库）",
            StartupTarget.OpenBrowser(LastBrowsing(connId, "root/A")),
            target,
        )
    }

    @Test
    fun `id 越界或文件已删 也算不是书：同样回落到浏览层`() = runTest {
        val missing = FailingSource(IllegalArgumentException("无效或越界引用：root/A"))

        val target = resolveStartupRead(missing, lastRead, lastBrowsing)

        assertEquals(StartupTarget.OpenBrowser(lastBrowsing), target)
    }

    @Test
    fun `没有浏览位置且这个 id 已删（列不出来）：回落到首页`() = runTest {
        // 不能把一个列不出来的层交给浏览页（那里只会再报一次错，也就会再把引用原文显示一遍）
        val target = resolveStartupRead(source(containerDir()), LastRead(connId, "root/已删除"), lastBrowsing = null)

        assertEquals(StartupTarget.OpenHome, target)
    }

    @Test
    fun `还是书就照常进阅读器`() = runTest {
        val book = fakeDir("root/A").add(fakeFile("root/A/001.jpg"), fakeFile("root/A/002.jpg"))

        val target = resolveStartupRead(source(book), lastRead, lastBrowsing)

        assertEquals(StartupTarget.OpenReader(lastRead), target)
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
        val source = DocumentTreeSource(FileBackend(root), InMemoryProgressStore())
        val emptyBook = LastRead(connId, File(root, "empty.cbz").absolutePath)

        val target = resolveStartupRead(source, emptyBook, lastBrowsing)

        assertEquals(StartupTarget.OpenReader(emptyBook), target)
    }

    @Test
    fun `传输故障不算不是书：照常进阅读器 保留 #91 的重试路径`() = runTest {
        val offline = FailingSource(SmbException(SmbFailureKind.TIMEOUT, "连接超时"))

        val target = resolveStartupRead(offline, lastRead, lastBrowsing)

        assertEquals(
            "断链/超时是暂时性失败：阅读器照旧显示中文错误 + 点此重试（#91 口径不回退）",
            StartupTarget.OpenReader(lastRead),
            target,
        )
    }

    /** 只用来抛错的最小来源假体：钉「回落只按异常类型区分」这条边界 */
    private class FailingSource(private val failure: Throwable) : Source {
        override val type: SourceType = SourceType.SMB

        override suspend fun listEntries(containerId: String?, sort: SortMode): List<BrowseEntry> = emptyList()

        override suspend fun openBook(bookId: String): BookHandle = throw failure

        override suspend fun readProgress(bookId: String): ReadingProgress? = null

        override suspend fun writeProgress(bookId: String, pageIndex: Int, totalPages: Int) = Unit

        override suspend fun neighbors(bookId: String): Neighbors = Neighbors(null, null)
    }
}
