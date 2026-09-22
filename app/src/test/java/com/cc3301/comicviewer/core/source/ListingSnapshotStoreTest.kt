package com.cc3301.comicviewer.core.source

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 落盘列表快照存储（票 #74）：TTL、容量淘汰、格式版本、连接级清理与「缓存目录被清掉不报错」。
 * 阈值可注入，因此用几个文件就能钉住 2000 条/20MB 的淘汰行为。
 */
class ListingSnapshotStoreTest {

    private val dir: File = Files.createTempDirectory("listing-snapshots").toFile()

    private var clock = 1_000_000L

    private companion object {
        /** 拨到 2023 前后：负数时间戳在 Windows 上 `File.setLastModified` 会失败 */
        const val REAL_CLOCK = 1_700_000_000_000L
    }

    private fun store(
        connId: Long = 7L,
        maxCount: Int = LISTING_SNAPSHOT_MAX_COUNT,
        maxBytes: Long = LISTING_SNAPSHOT_MAX_BYTES,
    ) =
        ListingSnapshotStore(
            dir = dir,
            connectionId = connId,
            nowMs = { clock },
            maxCount = maxCount,
            maxBytes = maxBytes,
        )

    private fun entry(id: String, name: String = id) = PersistedListingEntry(
        id = id,
        name = name,
        isBook = true,
        coverUri = null,
        pageCount = null,
        mtimeMs = 42L,
        probed = true,
    )

    private fun listing(vararg entries: PersistedListingEntry, mtimeMs: Long? = 100L) =
        PersistedListing(mtimeMs = mtimeMs, entries = entries.toList())

    private fun snapshotFiles(): List<String> = dir.listFiles().orEmpty().map { it.name }

    @Test
    fun `写入后读回条目与 mtime`() {
        val store = store()
        store.write("root", listing(entry("a", "第001话"), entry("b", "第002话")))

        val read = store.read("root")
        assertNotNull(read)
        assertEquals(100L, read!!.mtimeMs)
        assertEquals(listOf("第001话", "第002话"), read.entries.map { it.name })
        assertEquals(42L, read.entries.first().mtimeMs)
    }

    @Test
    fun `条目里的分隔符与换行原样往返`() {
        val store = store()
        val weird = entry(id = "a|b%c/d", name = "第\n001|话%")
        store.write("root|子", listing(weird))

        val read = store.read("root|子")!!
        assertEquals("a|b%c/d", read.entries.single().id)
        assertEquals("第\n001|话%", read.entries.single().name)
    }

    @Test
    fun `不同容器互不串`() {
        val store = store()
        store.write("root", listing(entry("a")))
        store.write("root/sub", listing(entry("b")))

        assertEquals("a", store.read("root")!!.entries.single().id)
        assertEquals("b", store.read("root/sub")!!.entries.single().id)
        assertNull(store.read("root/other"))
    }

    @Test
    fun `TTL 过期后读不到快照`() {
        val store = store()
        store.write("root", listing(entry("a")))

        clock += LISTING_SNAPSHOT_TTL_MS + 1

        assertNull("超过 7 天即视为未命中（来源侧因此重列一次）", store.read("root"))
        assertTrue("过期的那份顺手删掉", snapshotFiles().isEmpty())
    }

    @Test
    fun `格式版本不匹配时整片作废且不崩溃（含别的连接）`() {
        store(connId = 7).write("root", listing(entry("a")))
        store(connId = 8).write("root", listing(entry("b")))
        // 只把连接 7 的那份改成旧版本头：票面第 7 条说的是**整片**作废，连接 8 的旧格式文件也要一起清
        val conn7 = dir.listFiles()!!.single { it.name.startsWith("conn7_") }
        conn7.writeText(conn7.readText().replaceFirst("CVLS1", "CVLS999"))

        assertNull(store(connId = 7).read("root"))
        assertTrue("整片作废：同目录下别的连接的快照也一起清掉", snapshotFiles().isEmpty())
    }

    @Test
    fun `残留的临时文件在下次读时被清掉`() {
        clock = REAL_CLOCK // 要让「拨到过去」的时间戳仍为正数（Windows 上负数 setLastModified 会失败）
        store(connId = 7).write("root", listing(entry("a")))
        // 写入是「临时文件 + 改名」，进程在两者之间被杀会永久留下它（不在 2000 条/20MB 口径里）。
        // 票 #116 起清理带年龄保护，因此残留的这份必须真的「旧」才会被清。
        val stale = File(dir, "listing1234567890.tmp").apply {
            writeText("半截")
            setLastModified(clock - LISTING_SNAPSHOT_TTL_MS - 1)
        }

        store(connId = 7).read("root")

        assertFalse("残留 .tmp 不该常驻", stale.exists())
    }

    @Test
    fun `只清超龄的临时文件 刚创建的那份留着`() {
        clock = REAL_CLOCK
        store(connId = 7).write("root", listing(entry("a")))
        // 并发写入中的那一份 mtime 是刚刚，被顺手删掉就会让那次写不落盘（票 #116 第 1 条）
        val justCreated = File(dir, "listing1111111111.tmp").apply {
            writeText("正在写")
            setLastModified(clock)
        }
        val overAge = File(dir, "listing2222222222.tmp").apply {
            writeText("半截")
            setLastModified(clock - LISTING_SNAPSHOT_TTL_MS - 1)
        }

        store(connId = 7).read("root")

        assertTrue("刚创建的临时文件可能是并发写入中的那一份，不能删", justCreated.exists())
        assertFalse("超过阈值的残留才清", overAge.exists())
    }

    @Test
    fun `同一路径重复写入第二次生效`() {
        val store = store()
        store.write("root", listing(entry("a", "第一版")))
        store.write("root", listing(entry("b", "第二版")))

        // 目标文件已存在时的覆盖：File.renameTo 在 Windows 上不覆盖，会让第二次写静默失效
        // （票 #116 第 2 条；Linux 上 rename 本就覆盖，因此这条用例在 Windows 上才有判别力）
        val read = store.read("root")!!
        assertEquals(listOf("b"), read.entries.map { it.id })
        assertEquals("第二版", read.entries.single().name)
    }

    @Test
    fun `读坏的文件不崩溃并清掉那一份`() {
        dir.mkdirs()
        val store = store()
        store.write("root", listing(entry("a")))
        val file = dir.listFiles()!!.single()
        file.writeText("这不是快照")

        assertNull(store.read("root"))
        assertFalse(file.exists())
    }

    @Test
    fun `超过容量上限时按最后使用时间淘汰最旧的`() {
        val store = store(maxCount = 2)
        store.write("root", listing(entry("a")))
        clock += 10_000
        store.write("root/sub", listing(entry("b")))
        clock += 10_000
        store.write("root/sub2", listing(entry("c")))

        assertEquals("条数先到上限：只留最后使用的两条", 2, snapshotFiles().size)
        assertNull("最旧的一份被淘汰", store.read("root"))
        assertNotNull(store.read("root/sub"))
        assertNotNull(store.read("root/sub2"))
    }

    @Test
    fun `字节数先到上限时同样按最后使用时间淘汰`() {
        // 单条快照的字节数由内容定（条数上限远没到）：先写一份量出实际占用，再把上限压到
        // 「两份装得下、三份装不下」，于是触发的是 listingSnapshotOverCapacity 的**字节**分支——
        // 改动前只有条数分支被测过。
        val entries = (1..40).map { entry("id-$it", name = "名称-$it-".repeat(4)) }
        store(maxBytes = Long.MAX_VALUE).write("root", listing(*entries.toTypedArray()))
        val one = dir.listFiles().orEmpty().sumOf { it.length() }
        dir.listFiles().orEmpty().forEach { it.delete() }
        val maxBytes = one * 5 / 2

        val store = store(maxBytes = maxBytes)
        store.write("root", listing(*entries.toTypedArray()))
        clock += 10_000
        store.write("root/sub", listing(*entries.toTypedArray()))
        assertTrue(
            "前提：条数上限不得先到（否则本用例不再是字节分支的证据）",
            snapshotFiles().size < LISTING_SNAPSHOT_MAX_COUNT,
        )
        assertTrue("前提：两份必须装得下", one * 2 <= maxBytes)
        assertTrue("前提：三份必须装不下（否则不会淘汰）", one * 3 > maxBytes)
        clock += 10_000
        store.write("root/sub2", listing(*entries.toTypedArray()))

        assertNull("字节先到上限：最旧的一份被淘汰", store.read("root"))
        assertNotNull("较新的那份留着", store.read("root/sub"))
        assertNotNull("最新那份留着", store.read("root/sub2"))
    }

    @Test
    fun `命中会刷新最后使用时间 因此淘汰的是真正最久没用过的`() {
        val store = store(maxCount = 2)
        store.write("root", listing(entry("a")))
        clock += 10_000
        store.write("root/sub", listing(entry("b")))
        clock += 10_000
        store.read("root") // 用一次 root：它变成最新
        clock += 10_000
        store.write("root/sub2", listing(entry("c")))

        assertNotNull("刚用过的 root 不该被淘汰", store.read("root"))
        assertNull("最久没用过的 root/sub 被淘汰", store.read("root/sub"))
    }

    @Test
    fun `除某连接只清该连接的快照`() {
        store(connId = 7).write("root", listing(entry("a")))
        store(connId = 8).write("root", listing(entry("b")))

        ListingSnapshotStore.clearConnection(dir, 7)

        assertNull(store(connId = 7).read("root"))
        assertNotNull("同目录下别的连接的快照不受影响", store(connId = 8).read("root"))
    }

    @Test
    fun `缓存目录被清掉后读写不抛异常`() {
        val store = store()
        store.write("root", listing(entry("a")))
        dir.deleteRecursively()

        assertNull(store.read("root"))
        store.write("root", listing(entry("a")))
        store.remove("root")
        ListingSnapshotStore.clearConnection(dir, 7)
    }

    @Test
    fun `纯函数：TTL 与容量判定`() {
        assertFalse(listingSnapshotExpired(writtenAtMs = 0L, nowMs = LISTING_SNAPSHOT_TTL_MS))
        assertTrue(listingSnapshotExpired(writtenAtMs = 0L, nowMs = LISTING_SNAPSHOT_TTL_MS + 1))
        assertFalse(listingSnapshotOverCapacity(LISTING_SNAPSHOT_MAX_COUNT, LISTING_SNAPSHOT_MAX_BYTES))
        assertTrue(listingSnapshotOverCapacity(LISTING_SNAPSHOT_MAX_COUNT + 1, 0L))
        assertTrue(listingSnapshotOverCapacity(0, LISTING_SNAPSHOT_MAX_BYTES + 1))
    }
}
