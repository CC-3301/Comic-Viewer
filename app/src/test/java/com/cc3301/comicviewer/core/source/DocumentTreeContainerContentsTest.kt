package com.cc3301.comicviewer.core.source

import com.cc3301.comicviewer.core.order.WindowsNameOrder
import com.cc3301.comicviewer.core.source.fs.FileBackend
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 容器内容选择（票 #97）：打开一个容器**不再递归拍平**。
 *
 * 维护者现象：A 里有 B、C（各 10/20 页），打开 A 得到 30 页；100+ 本 1000+ 页的库打开就加载上千页。
 * 目标形态（维护者确认）：容器只给出**自己这一层**的条目——子目录与压缩包在浏览列表里各是一条独立条目
 * （点一个读一个、各自是一本），本层图片各自也是条目（点一张 = 从该张连读本层其余图片）。
 *
 * 判定口径**只有一处**：[dirContentsOf]（本层图片 / 子目录 / 压缩包三分区 + `isBook`），
 * 浏览列表 [DocumentTreeSource.listEntries] 与页序列装配共用它，因此「列表说是容器、打开却当一本书」不可表达。
 * 本文件既钉这个纯函数接缝（四种布局各一条），也钉文件源上的端到端页数。
 *
 * 压缩包（CBZ/ZIP）内部**保持全深度收集**（`isArchiveImageEntry` 不看层级）：包内套一层书名目录
 * （`A.cbz/书名/001.jpg`）是常见布局，只取顶层会让真实压缩包变成 0 页。压缩包内部分卷不在本票范围。
 *
 * 不覆盖（不可测部分及原因）：空态的**渲染**本身——阅读器的 `pageCount == 0` 分支（「此书没有可显示的页面」）
 * 与浏览列表的「此目录没有内容」都在 Compose 组合里，本仓库没有 Compose UI 测试基建
 * （先例：`ReaderSwapNavTest`/`ReaderNeighborsWarmupTest` 的同类声明）；这里钉的是它们的**输入条件**
 * （空压缩包 → 0 页句柄；本层无可见条目 → 空列表），渲染那一行由真机验收清单覆盖。
 */
class DocumentTreeContainerContentsTest {

    // ---------- 接缝：目录这一层的内容分区（纯函数，无 I/O） ----------

    @Test
    fun `接缝 本层只有图片的目录是一本书`() {
        val contents = dirContentsOf(
            listOf(fakeFile("A/p10.jpg"), fakeFile("A/p2.jpg")),
            WindowsNameOrder.COMPARATOR,
        )

        assertEquals("本层图片按名称自然序（2 < 10）", listOf("p2.jpg", "p10.jpg"), contents.images.map { it.name })
        assertTrue(contents.subDirs.isEmpty())
        assertTrue(contents.archives.isEmpty())
        assertTrue("本层只有图片 ⇒ 目录本身是一本书", contents.isBook)
    }

    @Test
    fun `接缝 本层只有子目录的目录是容器`() {
        val contents = dirContentsOf(
            listOf(fakeDir("A/B"), fakeDir("A/C")),
            WindowsNameOrder.COMPARATOR,
        )

        assertEquals(listOf("B", "C"), contents.subDirs.map { it.name })
        assertTrue(contents.images.isEmpty())
        assertFalse("本层没有图片 ⇒ 容器（B、C 在列表里各是一条条目）", contents.isBook)
    }

    @Test
    fun `接缝 本层只有压缩包的目录是容器`() {
        val contents = dirContentsOf(
            listOf(fakeFile("A/C.cbz"), fakeFile("A/B.zip")),
            WindowsNameOrder.COMPARATOR,
        )

        assertEquals(listOf("B.zip", "C.cbz"), contents.archives.map { it.name })
        assertFalse("压缩包各是一本，父目录本身不是书", contents.isBook)
    }

    @Test
    fun `接缝 本层图片加子目录加压缩包是容器 三者都在分区里`() {
        val contents = dirContentsOf(
            listOf(
                fakeFile("A/001.jpg"),
                fakeDir("A/B"),
                fakeFile("A/bonus.cbz"),
            ),
            WindowsNameOrder.COMPARATOR,
        )

        assertEquals(listOf("001.jpg"), contents.images.map { it.name })
        assertEquals(listOf("B"), contents.subDirs.map { it.name })
        assertEquals(listOf("bonus.cbz"), contents.archives.map { it.name })
        assertFalse("本层有子目录或压缩包 ⇒ 容器：谁都不能被并进这一本", contents.isBook)
    }

    // ---------- 文件源端到端：打开的页数只与本层有关 ----------

    @Test
    fun `容器只含子文件夹：打开不出现 30 页 条目是 B、C 且逐个可读`() = runTest {
        val root = tempRoot("container-subdirs")
        writeImages(File(root, "A/B"), "b", 10)
        writeImages(File(root, "A/C"), "c", 20)
        val source = source(root)

        val a = source.listEntries(null, SortMode.NAME).single()
        assertFalse("只含子文件夹 ⇒ 容器", a.isBook)

        val entries = source.listEntries(a.id, SortMode.NAME)
        assertEquals(listOf("B", "C"), entries.map { it.name })
        assertTrue("B、C 各自是一本书", entries.all { it.isBook })
        val b = entries.first { it.name == "B" }
        val c = entries.first { it.name == "C" }
        assertEquals("点 B 读 B 自己的 10 页", 10, source.openBook(b.id).pageCount)
        assertEquals("点 C 读 C 自己的 20 页", 20, source.openBook(c.id).pageCount)
        assertThrows(
            "A 本层没有图片：它从不是一本书，30 页这条路径不存在",
            IllegalArgumentException::class.java,
        ) { kotlinx.coroutines.runBlocking { source.openBook(a.id) } }
    }

    @Test
    fun `容器只含压缩包：压缩包各自是一本书 不并入父层`() = runTest {
        val root = tempRoot("container-archives")
        writeCbz(File(root, "A/B.cbz"), *imageNames(1..10))
        writeCbz(File(root, "A/C.cbz"), *imageNames(11..30))
        val source = source(root)

        val a = source.listEntries(null, SortMode.NAME).single()
        assertFalse("只含压缩包 ⇒ 容器", a.isBook)

        val entries = source.listEntries(a.id, SortMode.NAME)
        assertEquals(listOf("B.cbz", "C.cbz"), entries.map { it.name })
        assertTrue("压缩包条目仍是书", entries.all { it.isBook })
        assertEquals(10, source.openBook(entries.first { it.name == "B.cbz" }.id).pageCount)
        assertEquals(20, source.openBook(entries.first { it.name == "C.cbz" }.id).pageCount)
        assertThrows(
            "维护者现象「打开 A = B+C 30 页」必须不可达",
            IllegalArgumentException::class.java,
        ) { kotlinx.coroutines.runBlocking { source.openBook(a.id) } }
    }

    @Test
    fun `本层图片加子目录：本层图片各自可读 子目录条目可选`() = runTest {
        val root = tempRoot("container-images-and-dirs")
        writeImages(File(root, "A"), "cover", 2, filePrefix = "cover")
        writeImages(File(root, "A/B"), "b", 10)
        writeImages(File(root, "A/C"), "c", 20)
        val source = source(root)

        val a = source.listEntries(null, SortMode.NAME).single()
        assertFalse("本层有子目录 ⇒ A 是容器（点 A 进浏览列表，不直接进阅读器）", a.isBook)

        val entries = source.listEntries(a.id, SortMode.NAME)
        assertEquals(
            listOf("B", "C", "cover001.jpg", "cover002.jpg"),
            entries.map { it.name },
        )
        val firstImage = entries.first { it.name == "cover001.jpg" }
        val secondImage = entries.first { it.name == "cover002.jpg" }
        assertEquals("本层图片可读：从该张连读到本层末图", 2, source.openBook(firstImage.id).pageCount)
        assertEquals(1, source.openBook(secondImage.id).pageCount)
        assertEquals("子目录条目可选，且各自是本层读数", 10, source.openBook(entries.first { it.name == "B" }.id).pageCount)
        assertEquals(20, source.openBook(entries.first { it.name == "C" }.id).pageCount)
    }

    @Test
    fun `本层图片加压缩包：目录是容器 压缩包是独立的一本`() = runTest {
        val root = tempRoot("container-images-and-archive")
        File(root, "A").mkdirs()
        File(root, "A/cover.jpg").writeBytes("cover".toByteArray())
        writeCbz(File(root, "A/bonus.cbz"), *imageNames(1..3))
        val source = source(root)

        val a = source.listEntries(null, SortMode.NAME).single()
        assertFalse("本层有压缩包 ⇒ 容器（压缩包不再被并入这一本，也不悄悄丢掉）", a.isBook)

        val entries = source.listEntries(a.id, SortMode.NAME)
        assertEquals(listOf("bonus.cbz", "cover.jpg"), entries.map { it.name })
        assertEquals("压缩包是独立的一本", 3, source.openBook(entries.first { it.name == "bonus.cbz" }.id).pageCount)
        // 直接打开这个目录（既有的「目录本层图片可读」口径）：只读本层图片，不并入压缩包
        assertEquals(1, source.openBook(a.id).pageCount)
    }

    @Test
    fun `容器里点一张图片条目：落点走这本书自己的进度行`() = runTest {
        val root = tempRoot("container-image-entry")
        writeImages(File(root, "A"), "cover", 5, filePrefix = "cover")
        writeImages(File(root, "A/B"), "b", 10)
        val source = source(root)
        val a = source.listEntries(null, SortMode.NAME).single()

        val third = source.listEntries(a.id, SortMode.NAME).first { it.name == "cover003.jpg" }
        // 图片条目的 id 就是那张图片节点自己的 id（不是容器 id）：打开时 pagesOfBook 走 isImageFile 分支，
        // 从该图连读到本层末图；落点与进度都记在这张图自己的键下（openForReading → readProgress(bookId)）
        assertEquals(File(root, "A/cover003.jpg").absolutePath, third.id)
        source.writeProgress(third.id, 1, 3)
        val opening = openForReading(source, third.id, alwaysFirstPage = false)

        assertEquals("从该图连读到本层末图共 3 页", 3, opening.handle.pageCount)
        assertEquals("落点 = 这张图自己的进度（与本层其他图、与容器 id 都无关）", 1, opening.startIndex)
    }

    @Test
    fun `本层没有图片的目录不是一本书`() = runTest {
        val root = tempRoot("container-no-images")
        writeImages(File(root, "A/B"), "b", 10)
        File(root, "A/notes.txt").writeBytes("not-image".toByteArray())
        val source = source(root)

        val a = source.listEntries(null, SortMode.NAME).single()

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { source.openBook(a.id) }
        }
    }

    @Test
    fun `A 里直接放 100+ 个压缩包：只列出条条目 不产出上千页`() = runTest {
        // 维护者真机场景（已确认）：A 里的 B、C 是**压缩包**。100 个包 × 10 页 = 1000 页，这就是
        // 「100+ 本、1000+ 页的 A 一打开就加载上千页」的那一条。
        val root = tempRoot("container-many-archives")
        (1..100).forEach { i -> writeCbz(File(root, "A/b%03d.cbz".format(i)), *imageNames(1..10)) }
        val source = source(root)

        val a = source.listEntries(null, SortMode.NAME).single()
        assertFalse("只含压缩包 ⇒ 容器", a.isBook)

        val entries = source.listEntries(a.id, SortMode.NAME)
        assertEquals("只列出这一层的 100 个条目", 100, entries.size)
        assertTrue(entries.all { it.isBook })
        assertTrue("枚举期不给页数（票 #36）", entries.all { it.pageCount == null })
        assertThrows(
            "打开 A 不会产出 1000 页（容器不是一本书）",
            IllegalArgumentException::class.java,
        ) { kotlinx.coroutines.runBlocking { source.openBook(a.id) } }
        assertEquals("点一个只读那一个的 10 页", 10, source.openBook(entries.first().id).pageCount)
    }

    @Test
    fun `100+ 个压缩包的目录：枚举不打开任何一个包 且只列这一层一次`() = runTest {
        // 用 [FakeTreeBackend] 当探针：它的节点一被 `openRandomAccess()` 就抛——枚举/打开若碰了包就会暴露。
        val a = fakeDir("root/A").apply { repeat(120) { add(fakeFile("root/A/b%03d.cbz".format(it))) } }
        val source = DocumentTreeSource(FakeTreeBackend(fakeDir("root").add(a)), InMemoryProgressStore())

        val entry = source.listEntries(null, SortMode.NAME).single()
        assertFalse("只含压缩包 ⇒ 容器", entry.isBook)
        assertEquals(120, source.listEntries(entry.id, SortMode.NAME).size)
        assertEquals("父层探测 1 次 + 列本层 1 次：不再为页数逐包开包", 2, a.childrenCalls)
        source.listEntries(entry.id, SortMode.NAME)
        assertEquals("二次进入命中快照，不重列", 2, a.childrenCalls)
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { source.openBook(entry.id) }
        }
    }

    @Test
    fun `A 只含子文件夹 B：浏览 A 只见 B 进入 B 才见 C、D`() = runTest {
        // 维护者第二条观测（对照项）：A → B（文件夹）→ C、D（压缩包）时，浏览 A 本来就只看到 B。
        // 本票不得把这一点改坏（裁决 A 下 A、B 都是容器，与维护者描述的目标行为一致）。
        val root = tempRoot("container-subdir-of-archives")
        writeCbz(File(root, "A/B/C.cbz"), *imageNames(1..10))
        writeCbz(File(root, "A/B/D.cbz"), *imageNames(1..20))
        val source = source(root)

        val a = source.listEntries(null, SortMode.NAME).single()
        assertEquals("浏览 A 只见 B（并不看到 C、D）", listOf("B"), source.listEntries(a.id, SortMode.NAME).map { it.name })
        val b = source.listEntries(a.id, SortMode.NAME).single()
        assertFalse("B 本层只有压缩包 ⇒ 容器（旧口径点 B 会得到 C+D 的页数）", b.isBook)

        val cAndD = source.listEntries(b.id, SortMode.NAME)
        assertEquals(listOf("C.cbz", "D.cbz"), cAndD.map { it.name })
        assertEquals(10, source.openBook(cAndD.first { it.name == "C.cbz" }.id).pageCount)
        assertEquals(20, source.openBook(cAndD.first { it.name == "D.cbz" }.id).pageCount)
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { source.openBook(a.id) }
        }
    }

    // ---------- 压缩包内部（本票有意保持现状） ----------

    @Test
    fun `压缩包内嵌套目录仍全深度收集`() = runTest {
        val root = tempRoot("archive-nested")
        writeCbz(
            File(root, "A.cbz"),
            *imageNames(1..10, "书名"),
            *imageNames(11..30, "书名/vol2"),
        )
        val source = source(root)

        val book = source.listEntries(null, SortMode.NAME).single()

        assertTrue("压缩包文件始终是一本书", book.isBook)
        assertEquals(
            "包内套一层书名目录是常见布局：只取顶层会让真实压缩包变 0 页，因此包内保持全深度收集",
            30,
            source.openBook(book.id).pageCount,
        )
    }

    @Test
    fun `包内没有图片的压缩包是 0 页的书 交给阅读器显示中文空态`() = runTest {
        val root = tempRoot("archive-no-images")
        writeCbz(File(root, "A.cbz"), "readme.txt")
        val source = source(root)

        val book = source.listEntries(null, SortMode.NAME).single()

        assertTrue("压缩包不看内容就算书（枚举期不读包内条目，票 #36）", book.isBook)
        assertEquals("打开得到 0 页：阅读器的 `pageCount == 0` 分支给出中文空态，不是一直转圈", 0, source.openBook(book.id).pageCount)
    }

    // ---------- helper ----------

    private fun source(root: File): DocumentTreeSource =
        DocumentTreeSource(FileBackend(root), InMemoryProgressStore())

    private fun tempRoot(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun writeImages(dir: File, chapter: String, count: Int, filePrefix: String = "p") {
        dir.mkdirs()
        (1..count).forEach { i ->
            File(dir, "$filePrefix%03d.jpg".format(i)).writeBytes("$chapter-$i".toByteArray())
        }
    }

    private fun imageNames(range: IntRange, prefix: String = ""): Array<String> =
        range.map { i -> (if (prefix.isEmpty()) "" else "$prefix/") + "p%03d.jpg".format(i) }.toTypedArray()

    private fun writeCbz(file: File, vararg entries: String) {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(("zip-$name").toByteArray())
                zip.closeEntry()
            }
        }
    }
}
