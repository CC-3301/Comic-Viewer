package com.cc3301.comicviewer.core.source

import com.cc3301.comicviewer.core.order.WindowsNameOrder
import com.cc3301.comicviewer.core.source.fs.FsNode
import com.cc3301.comicviewer.core.source.zip.ZipArchive
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面取数的口径，在 `FsNode` 接缝上测：假后端（[FakeTreeBackend]）+ 一个「包内首页」取值器就够，
 * 不需要 SMB/SAF 真后端，也不需要真实文件。
 *
 * 钉住三件事：每层的优先级与**短路**（取到即停）、每层目录只列一次、每层最多开一个包。
 * 观测点是假后端自己的计数（[FakeTreeNode.childrenCalls]、[FakeTreeNode.randomAccessCalls]）
 * 与注入的包内首页取值器的调用记录——只看返回的字节证明不了没有多列目录、多开包。
 *
 * 界面通路（[Source.coverBytes]）上的同一条行为由 `DocumentTreeCoverDescentTest` 端到端钉住。
 */
class CoverDescentTest {

    // ---------- 逐级下取的优先级与短路 ----------

    @Test
    fun `本层有图就取本层首图 同层压缩包与子目录都不碰`() = runTest {
        val root = fakeDir("root")
        val c = fakeDir("root/C")
        val sub = fakeDir("root/C/sub").apply { add(fakeFile("root/C/sub/deep.jpg")) }
        val archive = fakeFile("root/C/a1.cbz").apply { packBytes = zipOf("p1.jpg" to "包首帧") }
        val image = fakeFile("root/C/z.png").apply { bytes = "层图".toByteArray() }
        c.add(sub, archive, image)
        root.add(c)
        val opened = mutableListOf<String>()

        val bytes = descentOf(opened = opened).bytesFor(c)

        assertEquals("层图", String(bytes!!))
        assertEquals("同层的包一个都不开", emptyList<String>(), opened)
        assertEquals("子目录没有理由被列", 0, sub.childrenCalls)
        assertEquals("本层目录只列一次", 1, c.childrenCalls)
    }

    @Test
    fun `本层无图时取名称序首个压缩包 同层其余包不碰`() = runTest {
        val root = fakeDir("root")
        val c = fakeDir("root/C")
        val a1 = fakeFile("root/C/a1.cbz").apply { packBytes = zipOf("p1.jpg" to "a1-p1") }
        val a2 = fakeFile("root/C/a2.cbz").apply { packBytes = zipOf("p1.jpg" to "a2-p1") }
        val a3 = fakeFile("root/C/a3.cbz").apply { packBytes = zipOf("p1.jpg" to "a3-p1") }
        c.add(a1, a2, a3)
        root.add(c)
        val opened = mutableListOf<String>()

        val bytes = descentOf(opened = opened).bytesFor(c)

        assertEquals("a1-p1", String(bytes!!))
        assertEquals(listOf("root/C/a1.cbz"), opened)
        assertEquals("只开一个包", 1, a1.randomAccessCalls)
        assertEquals("同层其余包一个都不开", 0, a2.randomAccessCalls + a3.randomAccessCalls)
    }

    @Test
    fun `本层无图无包时按名称序深度优先下取 命中即停 兄弟目录不列`() = runTest {
        val root = fakeDir("root")
        val c = fakeDir("root/C")
        val d1 = fakeDir("root/C/D1")
        val d2 = fakeDir("root/C/D2")
        val b1 = fakeFile("root/C/D1/b1.cbz").apply { packBytes = zipOf("p1.jpg" to "b1-p1") }
        val b2 = fakeFile("root/C/D1/b2.cbz").apply { packBytes = zipOf("p1.jpg" to "b2-p1") }
        d1.add(b1, b2)
        d2.add(fakeFile("root/C/D2/c1.cbz").apply { packBytes = zipOf("p1.jpg" to "c1-p1") })
        c.add(d1, d2)
        root.add(c)
        val opened = mutableListOf<String>()

        val bytes = descentOf(opened = opened).bytesFor(c)

        assertEquals("b1-p1", String(bytes!!))
        assertEquals("D1 命中即停：它的第二个包与 D2 的包都没被打开", listOf("root/C/D1/b1.cbz"), opened)
        assertEquals("每层目录只列一次", 1, c.childrenCalls)
        assertEquals(1, d1.childrenCalls)
        assertEquals("D2 根本没有理由被列", 0, d2.childrenCalls)
    }

    @Test
    fun `空目录与纯空壳返回 null 不抛错`() = runTest {
        val root = fakeDir("root")
        val empty = fakeDir("root/空")
        val shell = fakeDir("root/壳").apply { add(fakeDir("root/壳/内部")) }
        root.add(empty, shell)
        val covers = descentOf()

        assertNull(covers.bytesFor(empty))
        assertNull(covers.bytesFor(shell))
    }

    // ---------- 探测期首图 ----------

    @Test
    fun `探测期记下的首图直接命中 不再列该目录`() = runTest {
        val root = fakeDir("root")
        val c = fakeDir("root/C")
        val first = fakeFile("root/C/001.jpg").apply { bytes = "首图".toByteArray() }
        c.add(first)
        root.add(c)
        val covers = descentOf()

        covers.rememberProbedFirstImage(c, first)

        assertEquals("首图", String(covers.bytesFor(c)!!))
        assertEquals("命中探测期首图就不列目录", 0, c.childrenCalls)
    }

    @Test
    fun `目录 mtime 变了 探测期首图不再命中`() = runTest {
        val root = fakeDir("root")
        val c = fakeDir("root/C")
        val first = fakeFile("root/C/001.jpg").apply { bytes = "首图".toByteArray() }
        c.add(first, fakeFile("root/C/002.jpg").apply { bytes = "次图".toByteArray() })
        root.add(c)
        val backend = FakeTreeBackend(root)
        val covers = descentOf()

        covers.rememberProbedFirstImage(backend.resolve("root/C")!!, first)
        c.currentMtime = 42L

        assertEquals("首图", String(covers.bytesFor(backend.resolve("root/C")!!)!!))
        assertEquals("键含目录 mtime：目录改过就回落成列目录", 1, c.childrenCalls)
    }

    // ---------- 会话级字节缓存与索引 ----------

    @Test
    fun `缓存命中只查索引与内存 清空后两者一起失效`() {
        val covers = descentOf()
        val node = fakeDir("root/C")

        assertFalse("没写过字节就不算命中", covers.isCached(node.id))
        assertNull(covers.cachedBytes(node.id, node.lastModifiedMs))

        covers.putBytes(node.id, node.lastModifiedMs, "字节".toByteArray())
        assertTrue("写字节与记索引是同一个动作", covers.isCached(node.id))
        assertEquals("字节", String(covers.cachedBytes(node.id, node.lastModifiedMs)!!))

        covers.clear()
        assertFalse("清空后索引与字节一起失效", covers.isCached(node.id))
        assertNull(covers.cachedBytes(node.id, node.lastModifiedMs))

        covers.putBytes(node.id, node.lastModifiedMs, "字节".toByteArray())
        assertTrue("清空不残留：再写一次两处都重建", covers.isCached(node.id))
    }

    // ---------- 压缩包封面的落盘缓存 ----------

    @Test
    fun `压缩包封面落盘后 新实例取同一张不再开包`() = runTest {
        val (archive, dir, opened) = archiveFixture()
        val covers = descentOf(coverCacheDir = dir, opened = opened)

        assertEquals("b-p1", String(covers.bytesFor(archive)!!))
        val file = File(dir, CacheKeys.coverCacheFileName(archive.id, archive.lastModifiedMs))
        assertTrue("落盘文件名与缓存键同源", file.exists())

        // 新实例（内存缓存为空）仍不开包：字节来自落盘
        assertEquals("b-p1", String(descentOf(coverCacheDir = dir, opened = opened).bytesFor(archive)!!))
        assertEquals("第二次取封面不开包", listOf("root/C/B.cbz"), opened)
        assertEquals("包只被开过一次", 1, archive.randomAccessCalls)
    }

    @Test
    fun `落盘时清掉同一条目的旧封面文件 不碰别人的文件`() = runTest {
        val (archive, dir, opened) = archiveFixture()
        val stale = File(dir, CacheKeys.coverCacheFileName(archive.id, 1L)).apply { writeBytes("旧".toByteArray()) }
        val others = File(dir, "别人的封面.img").apply { writeBytes("别人的".toByteArray()) }

        descentOf(coverCacheDir = dir, opened = opened).bytesFor(archive)

        assertFalse("同一条目的旧封面（mtime 换了文件名）被清掉", stale.exists())
        assertTrue("当前键那份在", File(dir, CacheKeys.coverCacheFileName(archive.id, archive.lastModifiedMs)).exists())
        assertTrue("别人的文件不碰", others.exists())
    }

    // ---------- fixture ----------

    /** 一个「本层只有压缩包」的布局：包内两页，返回包节点、落盘目录与开包记录 */
    private fun archiveFixture(): Triple<FakeTreeNode, File, MutableList<String>> {
        val root = fakeDir("root")
        val c = fakeDir("root/C")
        val archive = fakeFile("root/C/B.cbz").apply { packBytes = zipOf("p1.jpg" to "b-p1", "p2.jpg" to "b-p2") }
        c.add(archive)
        root.add(c)
        return Triple(archive, Files.createTempDirectory("cover-descent-disk").toFile(), mutableListOf())
    }

    /**
     * 被测实例：只给接缝——封面落盘目录、名称序、以及「包内首页字节」取值器。
     * [opened] 记录被开过的包（生产里那一步 = 读中央目录 + 解压，网络来源上是真往返）。
     */
    private fun descentOf(
        coverCacheDir: File? = null,
        opened: MutableList<String> = mutableListOf(),
    ): CoverDescent = CoverDescent(
        coverCacheDir = coverCacheDir,
        nameComparator = WindowsNameOrder.COMPARATOR,
        archiveFirstPageBytes = { node ->
            opened += node.id
            firstArchivePage(node)
        },
    )

    /** 与适配器注入的那条同形：开包取包内首个图片条目 */
    private fun firstArchivePage(node: FsNode): ByteArray? =
        node.openRandomAccess().use { bytes ->
            ZipArchive(bytes).use { zip ->
                zip.entries.firstOrNull { isArchiveImageEntry(it.name) }?.let { zip.read(it) }
            }
        }

    /** 内存 CBZ：条目名 → 文本内容 */
    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
