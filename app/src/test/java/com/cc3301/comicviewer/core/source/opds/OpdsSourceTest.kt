package com.cc3301.comicviewer.core.source.opds

import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * OPDS 来源行为（票 15）：feed 浏览、下载后阅读、缓存命中不重复下载、进度通道、
 * 排序、相邻书、封面、失败冒泡。
 */
class OpdsSourceTest {

    private val feedUrl = "http://opds.example.com/opds"
    private val zipUrl = "http://opds.example.com/opds/book2.cbz"
    private val imageUrl = "http://opds.example.com/opds/one.jpg"
    private val thumbUrl = "http://opds.example.com/opds/thumb2.jpg"
    private val subFeedUrl = "http://opds.example.com/opds/comics"

    private fun zipBytes(vararg names: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            names.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(("bytes-" + name).toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun rootFeed(): String = """<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>我的书库</title>
  <entry>
    <title>漫画</title>
    <id>urn:nav:comics</id>
    <link rel="subsection" href="/opds/comics" type="application/atom+xml"/>
  </entry>
</feed>"""

    /** 书 feed：三本（第 2 话 / 第 10 话 / 单页），日期用于排序断言 */
    private fun booksFeed(): String = """<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>漫画</title>
  <entry>
    <title>第 2 话</title>
    <id>urn:book:2</id>
    <updated>2020-05-02T10:00:00Z</updated>
    <published>2020-04-01T00:00:00Z</published>
    <link rel="http://opds-spec.org/image/thumbnail" href="/opds/thumb2.jpg" type="image/jpeg"/>
    <link rel="http://opds-spec.org/acquisition/open-access" href="/opds/book2.cbz" type="application/vnd.comicbook+zip"/>
  </entry>
  <entry>
    <title>第 10 话</title>
    <id>urn:book:10</id>
    <updated>2020-05-03T10:00:00Z</updated>
    <published>2020-06-01T00:00:00Z</published>
    <link rel="http://opds-spec.org/acquisition" href="/opds/book10.cbz" type="application/zip"/>
  </entry>
  <entry>
    <title>单页</title>
    <id>urn:book:single</id>
    <updated>2020-05-10T00:00:00Z</updated>
    <link rel="http://opds-spec.org/acquisition" href="/opds/one.jpg" type="image/jpeg"/>
  </entry>
</feed>"""

    private val zipData = zipBytes("001.jpg", "002.jpg", "003.png")

    private fun api(chunkBytes: Int = Int.MAX_VALUE) = FakeOpdsApi(
        feeds = mapOf(feedUrl to rootFeed(), subFeedUrl to booksFeed()),
        downloads = mapOf(
            zipUrl to zipData,
            imageUrl to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3),
            thumbUrl to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 9),
        ),
        chunkBytes = chunkBytes,
    )

    private fun cache(): OpdsCache = OpdsCache(Files.createTempDirectory("opds-src").toFile())

    private fun source(api: OpdsApi = api(), cache: OpdsCache = cache()) =
        OpdsSource(api = api, config = OpdsConnectionConfig(feedUrl = feedUrl), progressStore = InMemoryProgressStore(), cache = cache)

    @Test
    fun `根 feed 列出导航条目 进入后列出书`() = runBlocking<Unit> {
        val src = source()
        val root = src.listEntries(null, SortMode.NAME)

        assertEquals(SourceType.OPDS, src.type)
        assertEquals(listOf("漫画"), root.map { it.name })
        assertTrue("导航条目不是可读单元", root.none { it.isBook })
        assertNull("OPDS 不给页数", root.first().pageCount)

        val books = src.listEntries(root.first().id, SortMode.NAME)
        // Windows 名称序：中文按拼音（单 < 第），数字按数值（2 < 10）
        assertEquals(listOf("单页", "第 2 话", "第 10 话"), books.map { it.name })
        assertTrue(books.all { it.isBook })
    }

    @Test
    fun `feed 有 next 分页时列表末尾给出下一页入口 且排序不会把它排到中间`() = runBlocking<Unit> {
        val pagedFeed = """<?xml version="1.0"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>分页</title>
  <link rel="next" href="$feedUrl?page=2" type="application/atom+xml"/>
  <entry>
    <title>A 书</title><id>urn:a</id><updated>2020-05-02T10:00:00Z</updated>
    <link rel="http://opds-spec.org/acquisition" href="/opds/a.cbz" type="application/zip"/>
  </entry>
</feed>"""
        val fake = FakeOpdsApi(feeds = mapOf(feedUrl to pagedFeed, feedUrl + "?page=2" to pagedFeed))
        val src = source(fake)

        val entries = src.listEntries(null, SortMode.MODIFIED_TIME)

        assertEquals(listOf("A 书", "下一页 →"), entries.map { it.name })
        val nextEntry = entries.last()
        assertTrue("下一页要能当容器进入", !nextEntry.isBook)
        // 点进去能继续列出（这个 fixture 的第二页还是同一份 feed，能列出即说明 id 反解正确）
        assertEquals(listOf("A 书", "下一页 →"), src.listEntries(nextEntry.id, SortMode.NAME).map { it.name })
    }

    @Test
    fun `不是 OPDS 一版的 feed 给出明确中文提示`() = runBlocking<Unit> {
        val jsonFeed = "{\"metadata\":{\"title\":\"OPDS 2.0\"}}"
        val src = source(FakeOpdsApi(feeds = mapOf(feedUrl to jsonFeed)))

        val thrown = assertThrows(OpdsException::class.java) { runBlocking { src.listEntries(null, SortMode.NAME) } }
        assertTrue(thrown.message!!.contains("OPDS 1.x"))
    }

    @Test
    fun `按发布时间排序用 Atom published 缺失时回退 updated`() = runBlocking<Unit> {
        val src = source()
        val feedId = src.listEntries(null, SortMode.NAME).first().id

        // published：第 10 话(6/1) > 第 2 话(4/1)；单页没有 published → 回退 updated(5/10)
        assertEquals(listOf("第 10 话", "单页", "第 2 话"), src.listEntries(feedId, SortMode.RELEASE_TIME).map { it.name })
        // updated：单页(5/10) > 第 10 话(5/3) > 第 2 话(5/2)
        assertEquals(listOf("单页", "第 10 话", "第 2 话"), src.listEntries(feedId, SortMode.MODIFIED_TIME).map { it.name })
    }

    @Test
    fun `打开书触发下载 读到压缩包页 下载中进度通道有带总量的进度`() = runBlocking<Unit> {
        val base = api(chunkBytes = 64)
        val observed = mutableListOf<Long?>()
        lateinit var src: OpdsSource
        // 在下载回调里读进度通道：这是「界面能看到什么」的最直接等价物，且不依赖线程调度
        val api = object : OpdsApi by base {
            override fun download(
                url: String,
                target: File,
                isActive: () -> Boolean,
                onProgress: DownloadListener,
            ) {
                base.download(url, target, isActive) { done, total ->
                    observed += src.downloadProgress!!.value?.totalBytes
                    onProgress(done, total)
                }
            }
        }
        src = source(api)
        val bookId = src.listEntries(subFeedId(src), SortMode.NAME).first { it.name == "第 2 话" }.id

        val handle = src.openBook(bookId)

        assertEquals(3, handle.pageCount)
        assertEquals("bytes-001.jpg", String(handle.loadPage(0).bytes))
        assertEquals("image/png", handle.loadPage(2).mimeType)
        assertTrue("下载过程要有进度回调", observed.isNotEmpty())
        assertEquals("进度要带总量（服务器给了 Content-Length）", zipData.size.toLong(), observed.last())
        assertNull("下载完成后进度要收起", src.downloadProgress!!.value)
    }

    @Test
    fun `已缓存的书再次打开不重新下载`() = runBlocking<Unit> {
        val fake = api()
        val src = source(fake)
        val bookId = src.listEntries(subFeedId(src), SortMode.NAME).first { it.name == "第 2 话" }.id

        src.openBook(bookId)
        src.openBook(bookId)

        assertEquals(1, fake.downloadCounts[zipUrl])
    }

    @Test
    fun `单页图片也能打开`() = runBlocking<Unit> {
        val src = source()
        val bookId = src.listEntries(subFeedId(src), SortMode.NAME).first { it.name == "单页" }.id

        val handle = src.openBook(bookId)
        assertEquals(1, handle.pageCount)
        assertEquals(0xFF, handle.loadPage(0).bytes[0].toInt().and(0xFF))
        assertThrows(IndexOutOfBoundsException::class.java) { runBlocking { handle.loadPage(1) } }
    }

    @Test
    fun `下载到非压缩包非图片时报明确错误`() = runBlocking<Unit> {
        val htmlUrl = "http://opds.example.com/opds/error.html"
        val fake = FakeOpdsApi(
            feeds = mapOf(
                feedUrl to """<?xml version="1.0"?>
<feed xmlns="http://www.w3.org/2005/Atom"><title>x</title>
<entry><title>坏书</title><id>urn:bad</id>
<link rel="http://opds-spec.org/acquisition" href="$htmlUrl" type="application/zip"/></entry></feed>""",
            ),
            downloads = mapOf(htmlUrl to "<html>error</html>".toByteArray()),
        )
        val src = source(fake)
        val bookId = src.listEntries(null, SortMode.NAME).first().id

        val thrown = assertThrows(OpdsException::class.java) { runBlocking { src.openBook(bookId) } }
        assertTrue(thrown.message!!.contains("错误页"))
    }

    @Test
    fun `下载到只有两个字节的 PK 文件时报明确错误而不是越界异常`() = runBlocking<Unit> {
        val tinyUrl = "http://opds.example.com/opds/tiny.cbz"
        val fake = FakeOpdsApi(
            feeds = mapOf(
                feedUrl to """<?xml version="1.0"?>
<feed xmlns="http://www.w3.org/2005/Atom"><title>x</title>
<entry><title>短文件</title><id>urn:tiny</id>
<link rel="http://opds-spec.org/acquisition" href="$tinyUrl" type="application/zip"/></entry></feed>""",
            ),
            downloads = mapOf(tinyUrl to byteArrayOf(0x50, 0x4B)),
        )
        val src = source(fake)
        val bookId = src.listEntries(null, SortMode.NAME).first().id

        val thrown = assertThrows(OpdsException::class.java) { runBlocking { src.openBook(bookId) } }
        assertTrue("要给出可读原因", thrown.message!!.contains("错误页"))
    }

    @Test
    fun `相邻书按名称序判定 限最近浏览的 feed`() = runBlocking<Unit> {
        val src = source()
        val books = src.listEntries(subFeedId(src), SortMode.NAME)
        assertEquals(listOf("单页", "第 2 话", "第 10 话"), books.map { it.name })

        val second = src.neighbors(books[1].id)
        assertEquals(books[0].id, second.prev)
        assertEquals(books[2].id, second.next)
        assertNull("第一本没有上一本", src.neighbors(books[0].id).prev)
        assertNull("最后一本没有下一本", src.neighbors(books[2].id).next)
        assertNull("没见过的书没有相邻书", src.neighbors("opds-http://opds.example.com/opds/book/unknown").prev)
    }

    @Test
    fun `封面按需下载并缓存 失败时返回 null`() = runBlocking<Unit> {
        val fake = api()
        val src = source(fake)
        val bookId = src.listEntries(subFeedId(src), SortMode.NAME).first { it.name == "第 2 话" }.id

        val cover = src.coverBytes(bookId)
        assertEquals(0xFF, cover!![0].toInt().and(0xFF))
        src.coverBytes(bookId)
        assertEquals("第二次走缓存", 1, fake.downloadCounts[thumbUrl])
        assertNull("没有缩略图信息的 id 返回 null", src.coverBytes("opds-http://opds.example.com/opds/book/none"))

        // 未缓存的封面在失败时必须吞成 null（不能让浏览页崩）
        val otherId = src.listEntries(subFeedId(src), SortMode.NAME).first { it.name == "第 10 话" }.id
        fake.downloadCounts[thumbUrl] = 0
        fake.alwaysFailWith(SocketTimeoutException("timed out"))
        assertNull(src.coverBytes(otherId))
    }

    @Test
    fun `进度读写走本地存储`() = runBlocking<Unit> {
        val src = source()
        val bookId = src.listEntries(subFeedId(src), SortMode.NAME).first().id
        assertNull(src.readProgress(bookId))
        src.writeProgress(bookId, 2, 3)
        assertEquals(2, src.readProgress(bookId)!!.pageIndex)
    }

    @Test
    fun `无效容器或书 id 明确报错 传输失败冒泡`() = runBlocking<Unit> {
        val src = source()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { src.listEntries("http://other/opds", SortMode.NAME) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { src.openBook("opds-http://opds.example.com/opds/book/@@@") }
        }

        val fake = api()
        val failing = source(fake)
        fake.alwaysFailWith(SocketTimeoutException("timed out"))
        assertThrows(SocketTimeoutException::class.java) { runBlocking { failing.listEntries(null, SortMode.NAME) } }
    }

    /** 取出书 feed 的容器 id（先浏览根 feed 拿导航条目） */
    private suspend fun subFeedId(src: OpdsSource): String = src.listEntries(null, SortMode.NAME).first().id
}
