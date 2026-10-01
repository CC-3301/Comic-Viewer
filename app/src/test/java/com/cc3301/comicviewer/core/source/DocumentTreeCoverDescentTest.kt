package com.cc3301.comicviewer.core.source

import com.cc3301.comicviewer.core.source.fs.FileBackend
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 容器封面**逐级下取**的口径（票 #102）。
 *
 * 维护者现象：`C/` 里只有 `A/`、`A/` 里只有 `B.cbz` —— C 没有封面（旧实现的逐级下取只找图片，
 * 在 A 这一层找不到图就返回 null，从不看压缩包）。目标：**每一层**都按与单层目录同一套优先级取封面：
 * ① 本层图片 → ② 本层**首个**压缩包的首帧 → ③ 才下探子目录（名称序、深度优先、每层目录只列一次）。
 *
 * 可测性（承接票面 AC「口径抽成可测接缝」）：本规则要读字节、要开包，做不成 `dirContentsOf` 那样的纯函数接缝
 * （那需要把 I/O 也注入进来，是本票范围内没必要的抽象）；口径一致性由实现里「每层只有一个优先级函数」保证，
 * 行为则由界面用的同一条通路 [Source.coverBytes] 钉住（四种布局各一条用例），
 * 并用共用的计数型后端 [CountingBackend]（票 #115 起与 ListEntriesPageCountTest 同一份，不再各写一份）钉住
 * 「每层目录只列一次」与「每层只开一个包」——只看封面字节相等证明不了没有多开包。
 *
 * 未覆盖（不可测部分及原因）：真机上的实际观感与 SMB/WebDAV 的耗时量级——本机没有真实 NAS/网盘，
 * 与票 #30/#51 记录同一处偏差；网络来源的每层往返次数由 DocumentTreeEnumerationPerformanceTest
 * 与 WebDavShelfTest 的传输层计数守护（本票不新增往返：下取仍只列目录，只是每层多一步「首个包首帧」）。
 */
class DocumentTreeCoverDescentTest {

    // ---------- 布局 ①：本层直接放压缩包（不回归） ----------

    @Test
    fun `本层只有压缩包的容器 封面是首个包的首帧`() = runTest {
        val root = tempRoot("layer-archive")
        writeCbz(File(root, "A/B.cbz"), listOf("page1.jpg" to "b-p1", "page2.jpg" to "b-p2"))

        val id = entryId(listingSource(root), listOf("A"))

        assertEquals("b-p1", String(coverSource(root).coverBytes(id)!!))
    }

    // ---------- 布局 ②：纯容器下探，中间层只有压缩包（票面主现象 C → A → B.cbz） ----------

    @Test
    fun `纯容器下探到只有压缩包的一层 封面是那个包的首帧`() = runTest {
        val root = tempRoot("descent-one-level")
        writeCbz(File(root, "C/A/B.cbz"), listOf("page1.jpg" to "b-p1", "page2.jpg" to "b-p2"))

        val id = entryId(listingSource(root), listOf("C"))

        assertEquals("b-p1", String(coverSource(root).coverBytes(id)!!))
    }

    // ---------- 布局 ③：更深的纯容器同样成立（C → A → A2 → B.cbz） ----------

    @Test
    fun `更深的纯容器下探 同样取到压缩包首帧`() = runTest {
        val root = tempRoot("descent-two-levels")
        writeCbz(File(root, "C/A/A2/B.cbz"), listOf("page1.jpg" to "b-p1"))

        val listing = listingSource(root)
        val c = entryId(listing, listOf("C"))
        val a = entryId(listing, listOf("C", "A"))
        val cover = coverSource(root)

        assertEquals("C 隔两层也拿得到：A 是纯容器、A2 里才是包", "b-p1", String(cover.coverBytes(c)!!))
        assertEquals("A 自己同理", "b-p1", String(cover.coverBytes(a)!!))
    }

    // ---------- 布局 ④：有图路径行为不变 ----------

    @Test
    fun `本层有图就用本层首图 本层的包不参与`() = runTest {
        val root = tempRoot("layer-image-wins")
        // 包名比图名靠前：优先级是「先图后包」，不是名称序
        writeCbz(File(root, "C/a1.cbz"), listOf("p1.jpg" to "cbz-frame"))
        writeBytes(File(root, "C/z.png"), "png-layer")

        val id = entryId(listingSource(root), listOf("C"))

        assertEquals("png-layer", String(coverSource(root).coverBytes(id)!!))
    }

    @Test
    fun `本层无图无包时逐级下取第一张图 第一个子目录命中即停`() = runTest {
        val root = tempRoot("descent-image")
        File(root, "C/空壳").mkdirs()
        File(root, "C/D/再下一层").mkdirs()
        writeBytes(File(root, "C/D/再下一层/only.jpg"), "deep-image")

        val id = entryId(listingSource(root), listOf("C"))

        assertEquals(
            // 名称自然序下拉丁字母段先于汉字段：先下探 D 就命中，空壳（排在其后）本用例走不到
            "名称序第一个子目录命中有图路径即停",
            "deep-image",
            String(coverSource(root).coverBytes(id)!!),
        )
    }

    // ---------- 成本：每层目录只列一次、每层最多开一个包 ----------

    @Test
    fun `同层多个压缩包只开第一个`() = runTest {
        val backend = CountingBackend(tempRoot("opening-count-layer"))
        writeCbz(File(backend.rootDir, "C/a1.cbz"), listOf("p1.jpg" to "a1-p1"))
        writeCbz(File(backend.rootDir, "C/a2.cbz"), listOf("p1.jpg" to "a2-p1"))
        writeCbz(File(backend.rootDir, "C/a3.cbz"), listOf("p1.jpg" to "a3-p1"))
        val id = entryId(DocumentTreeSource(backend, InMemoryProgressStore()), listOf("C"))
        backend.resetCounters()

        val bytes = DocumentTreeSource(backend, InMemoryProgressStore()).coverBytes(id)

        assertEquals("a1-p1", String(bytes!!))
        assertEquals("只开名称序第一个包，同层其余包一个都不开", listOf("/C/a1.cbz"), backend.openedPaths.distinct())
        assertEquals("本层目录只列一次", mapOf("/C" to 1), backend.childrenCalls)
    }

    @Test
    fun `多层下探每层只开第一个包 且不碰没有结果的兄弟目录`() = runTest {
        val backend = CountingBackend(tempRoot("opening-count-deep"))
        File(backend.rootDir, "C/D1").mkdirs()
        File(backend.rootDir, "C/D2").mkdirs()
        writeCbz(File(backend.rootDir, "C/D1/b1.cbz"), listOf("p1.jpg" to "b1-p1"))
        writeCbz(File(backend.rootDir, "C/D1/b2.cbz"), listOf("p1.jpg" to "b2-p1"))
        writeCbz(File(backend.rootDir, "C/D2/c1.cbz"), listOf("p1.jpg" to "c1-p1"))
        val id = entryId(DocumentTreeSource(backend, InMemoryProgressStore()), listOf("C"))
        backend.resetCounters()

        val bytes = DocumentTreeSource(backend, InMemoryProgressStore()).coverBytes(id)

        assertEquals("b1-p1", String(bytes!!))
        assertEquals("下取到 D1 就停：D1 的第二个包与 D2 的包都没被打开", listOf("/C/D1/b1.cbz"), backend.openedPaths.distinct())
        assertEquals(
            "每层目录只列一次；D2 根本没有理由被列",
            mapOf("/C" to 1, "/C/D1" to 1),
            backend.childrenCalls,
        )
    }

    // ---------- 空目录 / 纯空壳 ----------

    @Test
    fun `空目录与纯空壳返回 null 不抛错`() = runTest {
        val root = tempRoot("empty")
        File(root, "空").mkdirs()
        File(root, "壳/内部").mkdirs()

        val listing = listingSource(root)
        val cover = coverSource(root)

        assertNull(cover.coverBytes(entryId(listing, listOf("空"))))
        assertNull(cover.coverBytes(entryId(listing, listOf("壳"))))
    }

    // ---------- fixture ----------

    private fun tempRoot(tag: String): File = Files.createTempDirectory("cover-descent-$tag").toFile()

    /** 列目录取真实 id 的实例（列表缓存是会话级的，用一个实例走完路径最省事） */
    private fun listingSource(root: File): DocumentTreeSource = newSource(root)

    /**
     * 取封面的实例：必须是**另一个**新实例——探测「这个子目录是不是书」时会把有图目录的首图记进缓存，
     * 从列过目录的实例取封面可能命中那条缓存而绕过逐级下取（票 #51 F2），证明不了本票的规则。
     */
    private fun coverSource(root: File): DocumentTreeSource = newSource(root)

    private fun newSource(root: File): DocumentTreeSource =
        DocumentTreeSource(FileBackend(root), InMemoryProgressStore())

    /** 沿路径逐层列出取真实 id（id 由来源给出，用例不假设 id 的形状） */
    private suspend fun entryId(listing: Source, path: List<String>): String {
        var parent: String? = null
        var id: String? = null
        for (name in path) {
            id = listing.listEntries(parent, SortMode.NAME).first { it.name == name }.id
            parent = id
        }
        return id!!
    }

    private fun writeBytes(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeBytes(text.toByteArray())
    }

    private fun writeCbz(file: File, entries: List<Pair<String, String>>) {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
