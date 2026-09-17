package com.cc3301.comicviewer.core.source.smb

import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 大目录枚举性能（票 #30）：SMB 侧用计数型传输层（[CountingSmbTransport]）锁住四条上界——
 * 每层目录一次 `list`（不再为容器封面逐级下取）、子目录探测真并发且有上限、枚举期不读字节（含压缩包封面）、
 * 同一目录会话内二次进入命中缓存且可显式刷新；并覆盖取消后不再发起新请求。
 *
 * 计数先例：ListEntriesPageCountTest 的 CountingBackend（FsNode 层，三个文件源通用）、
 * WebDavShelfTest 的传输层断言（FakeWebDavTransport.readCalls）。
 *
 * 真机对比（真实 SMB 上 100+ 子文件夹首次进入/返回上级的耗时）按票面走真机清单：
 * 本机无 Docker/无真实 SMB，与票 11/12/31 记录同一处 SPEC 偏差。
 */
class DocumentTreeEnumerationPerformanceTest {

    // ---------- fixture ----------

    /** 100 个一级子文件夹：每个都是「只含嵌套目录」的容器（封面只有逐级下取才拿得到）+ 根下一个压缩包 */
    private val containerCount = 100

    private fun bigLibrary(): File {
        val root = Files.createTempDirectory("enum-perf").toFile()
        repeat(containerCount) { i ->
            File(root, subdirName(i)).apply { mkdirs() }
            File(root, subdirName(i) + "/内页").mkdirs()
            // 封面图放在二级目录里：旧实现为每个一级容器逐级下取才会读到它
            File(root, subdirName(i) + "/内页/001.jpg").writeBytes("deep-$i".toByteArray())
        }
        writeCbz(File(root, "单行本.cbz"), listOf("p1.jpg", "p2.jpg"))
        return root
    }

    private fun subdirName(index: Int): String = "第%03d话".format(index + 1)

    private fun writeCbz(file: File, imageNames: List<String>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            imageNames.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(("cbz-$name").toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun source(transport: CountingSmbTransport, coverCacheDir: File? = null): DocumentTreeSource {
        val config = SmbConnectionConfig(host = "nas", share = "comics")
        return DocumentTreeSource(
            backend = SmbBackend(ClassifyingTransport(transport, config), config),
            progressStore = InMemoryProgressStore(),
            coverCacheDir = coverCacheDir,
            sourceType = SourceType.SMB,
        )
    }

    // ---------- 每层一次 list / 枚举期不读字节 ----------

    @Test
    fun `枚举 100 个子文件夹每层只列一次 枚举期不读任何字节`() = runTest {
        val transport = CountingSmbTransport(FakeSmbTransport(bigLibrary()))
        val source = source(transport)
        transport.resetCounters()

        val entries = source.listEntries(null, SortMode.NAME)

        // 旧实现会为每个一级容器递归下取封面位置（每层目录被列多次）：本夹具在改动前实测 401 次
        // （每个一级容器 3 次逐级下取 + 1 次分区探测 = 100×4，再加根 1 次），现在每层目录一次
        assertEquals("每层目录一次 list（根 1 次 + 每个子目录 1 次）", containerCount + 1, transport.listCalls)
        assertEquals(
            "枚举期不读任何字节（旧实现实测 2 次：压缩包中央目录 + 包内首页），封面只在按需通路读",
            0,
            transport.readCalls,
        )
        assertEquals(containerCount + 1, entries.size)

        val container = entries.first { it.name == subdirName(0) }
        assertFalse("只含嵌套目录的子文件夹仍是容器", container.isBook)
        assertNull("枚举期不给容器封面位置（旧实现逐级下取）", container.coverUri)
        assertNull("枚举期不给压缩包封面位置（旧实现解包取首页）", entries.first { it.name == "单行本.cbz" }.coverUri)
    }

    @Test
    fun `容器与压缩包封面只在按需通路读 取到的仍是既有封面规则`() = runTest {
        val transport = CountingSmbTransport(FakeSmbTransport(bigLibrary()))
        val source = source(transport)
        val entries = source.listEntries(null, SortMode.NAME)
        assertEquals("枚举期零读取", 0, transport.readCalls)

        val container = entries.first { it.name == subdirName(2) }.id
        val archive = entries.first { it.name == "单行本.cbz" }.id

        assertEquals("容器封面逐级下取到第一张图", "deep-2", String(source.coverBytes(container)!!))
        assertEquals("压缩包封面取包内首页", "cbz-p1.jpg", String(source.coverBytes(archive)!!))
        assertTrue("封面字节只在真正需要时传输", transport.readCalls > 0)
    }

    @Test
    fun `压缩包封面按需解出后落盘缓存 二次取封面不再读包`() = runTest {
        val transport = CountingSmbTransport(FakeSmbTransport(bigLibrary()))
        val source = source(transport, coverCacheDir = Files.createTempDirectory("enum-perf-covers").toFile())
        val entries = source.listEntries(null, SortMode.NAME)
        assertEquals("枚举期零读取（落盘缓存也只在按需取封面时才写）", 0, transport.readCalls)

        val archive = entries.first { it.name == "单行本.cbz" }.id
        assertEquals("cbz-p1.jpg", String(source.coverBytes(archive)!!))
        val afterFirst = transport.readCalls
        assertTrue("第一次取封面要读包", afterFirst > 0)

        assertEquals("cbz-p1.jpg", String(source.coverBytes(archive)!!))
        assertEquals("二次取封面命中落盘缓存，不再读包（票 10「封面生成后缓存」）", afterFirst, transport.readCalls)
    }

    @Test
    fun `压缩包换内容后不残留旧封面缓存文件`() = runTest {
        val root = bigLibrary()
        val coverDir = Files.createTempDirectory("enum-perf-covers-prune").toFile()
        val source = source(CountingSmbTransport(FakeSmbTransport(root)), coverCacheDir = coverDir)
        val archive = source.listEntries(null, SortMode.NAME).first { it.name == "单行本.cbz" }.id

        assertEquals("cbz-p1.jpg", String(source.coverBytes(archive)!!))
        assertEquals("第一次取封面留下一个缓存文件", 1, coverCacheFiles(coverDir))

        // 包被替换（mtime 变化 → 按票 #30 P2 换文件名）：旧文件要清掉，否则每改一次多留一份
        val pack = File(root, "单行本.cbz")
        writeCbz(pack, listOf("q1.jpg"))
        pack.setLastModified(pack.lastModified() + 60_000)

        assertEquals("cbz-q1.jpg", String(source.coverBytes(archive)!!))
        assertEquals("同一本书只留最新那份封面缓存", 1, coverCacheFiles(coverDir))
    }

    /**
     * 票 26 第 4 项的替代验收（该项随 #33 删除 OPDS 而作废，「封面跨来源实例命中」改用 SMB 断言）：
     * 落盘封面缓存不绑定来源实例——会话级来源被换成新实例（换连接会话/进程存活时重建 Activity）后，
     * 同一本书（同一条连接下的同一路径、mtime 未变）的封面仍命中缓存，不再读包。
     */
    @Test
    fun `封面缓存跨来源实例命中 新实例取封面不再读包`() = runTest {
        val root = bigLibrary()
        val coverDir = Files.createTempDirectory("enum-perf-covers-cross").toFile()
        val first = source(CountingSmbTransport(FakeSmbTransport(root)), coverCacheDir = coverDir)
        val archive = first.listEntries(null, SortMode.NAME).first { it.name == "单行本.cbz" }.id
        assertEquals("cbz-p1.jpg", String(first.coverBytes(archive)!!))

        // 第二个来源实例：新 backend/新 transport（相当于新的一条 SMB 会话），同一条连接的同一路径
        val secondTransport = CountingSmbTransport(FakeSmbTransport(root))
        val second = source(secondTransport, coverCacheDir = coverDir)

        assertEquals("新实例拿到同一本书的封面（缓存命中）", "cbz-p1.jpg", String(second.coverBytes(archive)!!))
        assertEquals("跨来源实例命中落盘缓存：不读包", 0, secondTransport.readCalls)
    }

    private fun coverCacheFiles(dir: File): Int = dir.listFiles().orEmpty().count { it.name.endsWith(".img") }

    // ---------- 并发度 ----------

    @Test
    fun `子目录探测真并发 且并发度不超过上限`() = runTest {
        val transport = CountingSmbTransport(FakeSmbTransport(bigLibrary())).apply { blockProbes() }
        val source = source(transport)
        transport.resetCounters()

        val job = launch(Dispatchers.Default) { source.listEntries(null, SortMode.NAME) }
        val reached = transport.awaitInFlight(EXPECTED_PROBE_LIMIT, AWAIT_TIMEOUT_MS)
        // 先放行再断言：不放行的话探测一直堵在闸门上，本用例会挂死
        transport.openProbeGate()
        job.join()

        assertTrue(
            "子目录探测必须真并发（旧实现串行逐个）：在飞峰值 ${transport.maxInFlight}",
            reached,
        )
        assertTrue(
            "并发度不得超过上限 $EXPECTED_PROBE_LIMIT：实测峰值 ${transport.maxInFlight}",
            transport.maxInFlight <= EXPECTED_PROBE_LIMIT,
        )
    }

    // ---------- 会话级缓存 ----------

    @Test
    fun `同一目录会话内二次进入命中缓存 显式刷新后重列`() = runTest {
        val transport = CountingSmbTransport(FakeSmbTransport(bigLibrary()))
        val source = source(transport)

        val first = source.listEntries(null, SortMode.NAME)
        assertEquals("首次进入枚举整层", containerCount + 1, transport.listCalls)

        transport.resetCounters()
        assertEquals("二次进入返回同一份条目", first, source.listEntries(null, SortMode.NAME))
        assertEquals("二次进入一次 list 都不该发（缓存命中）", 0, transport.listCalls)

        // 最常见的「二次进入」路径：进子目录再返回上级
        source.listEntries(first.first { it.name == subdirName(1) }.id, SortMode.NAME)
        transport.resetCounters()
        source.listEntries(null, SortMode.NAME)
        assertEquals("从子目录返回上级也命中缓存", 0, transport.listCalls)

        // 显式刷新：失效后重新枚举（列表页刷新按钮走这条）
        source.invalidateListCache(null)
        source.listEntries(null, SortMode.NAME)
        assertEquals("显式刷新后重新枚举整层", containerCount + 1, transport.listCalls)
    }

    @Test
    fun `文件改动后目录 mtime 变化使缓存失效`() = runTest {
        val root = bigLibrary()
        val source = source(CountingSmbTransport(FakeSmbTransport(root)))
        val containerId = source.listEntries(null, SortMode.NAME).first { it.name == subdirName(3) }.id

        assertEquals(listOf("内页"), source.listEntries(containerId, SortMode.NAME).map { it.name })

        // 目录里加一张图：目录 mtime 变化 → 缓存自动失效（显式刷新之外的失效路径）
        val dir = File(root, subdirName(3))
        File(dir, "新增.jpg").writeBytes("new".toByteArray())
        dir.setLastModified(dir.lastModified() + 60_000)

        assertEquals(
            listOf("内页", "新增.jpg"),
            source.listEntries(containerId, SortMode.NAME).map { it.name },
        )
    }

    // ---------- 取消 ----------

    @Test
    fun `取消后不再向未起飞的子目录发请求`() = runTest {
        val transport = CountingSmbTransport(FakeSmbTransport(bigLibrary())).apply { blockProbes() }
        val source = source(transport)
        transport.resetCounters()

        val job = launch(Dispatchers.Default) { source.listEntries(null, SortMode.NAME) }
        assertTrue("已起飞的探测都堵在闸门上", transport.awaitInFlight(EXPECTED_PROBE_LIMIT, AWAIT_TIMEOUT_MS))

        job.cancel()
        transport.openProbeGate()
        job.join()

        assertEquals(
            "取消后只跑完已起飞的那一批，未起飞的探测不再发请求",
            1 + EXPECTED_PROBE_LIMIT,
            transport.listCalls,
        )
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L

        /**
         * 并发上限的期望值（写死而非引用生产常量 SUBDIR_PROBE_LIMIT）：把生产常量调大（等于放弃上界）时
         * 本用例必须失败，否则「有明确并发上限」这条验收就没有守护力（评审 P2-6）。
         */
        const val EXPECTED_PROBE_LIMIT = 8
    }
}
