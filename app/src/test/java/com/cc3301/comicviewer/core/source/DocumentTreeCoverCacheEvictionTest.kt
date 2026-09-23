package com.cc3301.comicviewer.core.source

import com.cc3301.comicviewer.core.source.fs.FileBackend
import com.cc3301.comicviewer.core.source.fs.FsBackend
import com.cc3301.comicviewer.core.source.fs.FsNode
import com.cc3301.comicviewer.core.source.zip.RandomAccessBytes
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面字节缓存的淘汰口径（票 #108 E2-B AC4：滚出屏幕的封面**不立即丢弃缓存**）。
 *
 * 维护者现象：在书柜/浏览页稍快地上下滑动时，封面会「从无到有慢慢加载出来」。根因是原来的上界处置——
 * 条目数或总字节越线就**整仓清空**，落到上界那一刻连屏上正显示的那几张也一起丢掉，它们当场重新向来源要字节。
 *
 * 判据（不数遍数、也不看内部字段）：**读完 70 条（上界 64）之后再读第 2 条，字节层面仍命中（0 次读字节），
 * 而被淘汰的最旧那条一定会重读一次字节**。旧口径在第 65 条时清空了整仓，第 2 条的命中因此不成立。
 *
 * 未覆盖：真机上「滑动时不再看到逐格补齐」是渲染观感，按 SPEC 的 Testing Decisions 走真机验收。
 */
class DocumentTreeCoverCacheEvictionTest {

    @Test
    fun `越上界只淘汰最旧的条目 最近加载过的封面仍命中`() = runTest {
        val root = Files.createTempDirectory("cover-cache-evict").toFile()
        // 70 条封面、上界 64 条：加载序就是淘汰序
        val names = (1..70).map { "p" + "%02d".format(it) + ".jpg" }
        names.forEach { name -> File(root, name).writeBytes(name.toByteArray()) }
        val backend = CountingBackend(root)
        val source = DocumentTreeSource(backend, InMemoryProgressStore())

        val entries = source.listEntries(null, SortMode.NAME)
        assertEquals("本层 70 张图各自是一条书条目", 70, entries.size)
        entries.forEach { entry ->
            assertTrue(entry.name + " 取到封面字节", source.coverBytes(entry.id) != null)
        }

        // 清空读字节记录：接下来两次取封面，一次验「旧口径会被丢掉的那一条」，一次验「确实淘汰了最旧的那条」。
        // 中间那一条（既不是最旧的 6 条、也不是最后插入的几条）是判别点：旧口径在插第 65 条时整仓清空，
        // 它会被丢掉（要重读）；新口径保留最近 64 条，它仍在（0 次读）。
        backend.readPaths.clear()
        val middle = entries[10]
        assertTrue(
            "缓存里确实有它（票 #108 r4 的预取判据：只读内存、不 resolve）",
            source.hasCachedCoverBytes(middle.id),
        )
        // 淘汰后「缓存里有没有」必须说实话（预取据此重新拉它，而不是像旧口径那样永久记「已预取」）。
        // 这一句必须在下面真的去取 entries[0] **之前**：取一次就又把它放回缓存了。
        assertFalse("被淘汰的最旧一条不再算「缓存里有」", source.hasCachedCoverBytes(entries[0].id))
        assertTrue("中间那条（最近 64 条之内）仍能取到", source.coverBytes(middle.id) != null)
        assertEquals(
            "仍在缓存里：0 次读字节（旧口径整仓清空后这里会重读）",
            emptyList<String>(),
            backend.readPaths,
        )

        assertTrue("最旧的那条也仍能取到（淘汰后重读来源）", source.coverBytes(entries[0].id) != null)
        assertEquals("被淘汰的是最旧条目：重读一次字节", listOf(names[0]), backend.readPaths)
    }

    /** 文件系统后端（读得到真字节）+ 记录 `readBytes()` 的路径（套路同共用的计数型 `CountingBackend`，见 `CountingBackendTestSupport.kt`） */
    private class CountingBackend(rootDir: File) : FsBackend {
        private val delegate = FileBackend(rootDir)
        private val rootPath = rootDir.absoluteFile.path

        val readPaths = mutableListOf<String>()

        override val root: FsNode = CountingNode(delegate.root, this)

        override fun resolve(id: String): FsNode? = delegate.resolve(id)?.let { CountingNode(it, this) }

        fun noteRead(id: String) {
            readPaths += id.removePrefix(rootPath).trimStart(File.separatorChar).replace(File.separatorChar, '/')
        }
    }

    private class CountingNode(private val delegate: FsNode, private val counter: CountingBackend) : FsNode {
        override val id: String get() = delegate.id
        override val name: String get() = delegate.name
        override val isDirectory: Boolean get() = delegate.isDirectory
        override val lastModifiedMs: Long? get() = delegate.lastModifiedMs
        override val imageUri: String get() = delegate.imageUri

        override fun children(): List<FsNode> = delegate.children().map { CountingNode(it, counter) }
        override fun parent(): FsNode? = delegate.parent()?.let { CountingNode(it, counter) }

        override fun readBytes(): ByteArray {
            counter.noteRead(delegate.id)
            return delegate.readBytes()
        }

        override fun openRandomAccess(): RandomAccessBytes {
            counter.noteRead(delegate.id)
            return delegate.openRandomAccess()
        }
    }
}
