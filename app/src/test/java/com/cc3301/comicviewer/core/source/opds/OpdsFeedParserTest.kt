package com.cc3301.comicviewer.core.source.opds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OPDS 1.x feed 解析（票 15）：用两份静态 feed 样本（导航 feed + 获取 feed）覆盖，
 * 不需要网络与容器——这正是 SPEC 里「OPDS=静态 feed fixture」那一项的落地方式。
 */
class OpdsFeedParserTest {

    private val feedUrl = "http://opds.example.com/opds"

    @Test
    fun `导航 feed 解析出 subsection 链接与标题`() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>我的书库</title>
  <updated>2020-05-01T10:00:00Z</updated>
  <entry>
    <title>最近更新</title>
    <id>urn:nav:recent</id>
    <updated>2020-05-01T10:00:00Z</updated>
    <link rel="subsection" href="/opds/recent" type="application/atom+xml;profile=opds-catalog"/>
  </entry>
  <entry>
    <title>漫画</title>
    <id>urn:nav:comics</id>
    <link rel="subsection" href="comics" type="application/atom+xml"/>
    <link rel="http://opds-spec.org/image/thumbnail" href="/thumbs/comics.png" type="image/png"/>
  </entry>
</feed>"""

        val feed = parseOpdsFeed(xml, feedUrl)

        assertEquals("我的书库", feed.title)
        assertEquals(listOf("最近更新", "漫画"), feed.entries.map { it.title })
        // href 按 RFC 3986 相对 feed 地址解析（绝对路径 /opds/recent 保留；裸相对名按「末段是文件」处理）
        assertEquals("http://opds.example.com/opds/recent", feed.entries[0].navigationHref)
        assertEquals("http://opds.example.com/comics", feed.entries[1].navigationHref)
        assertTrue(feed.entries.all { it.isNavigation })
        assertEquals("http://opds.example.com/thumbs/comics.png", feed.entries[1].thumbnailHref)
        assertEquals(1600000000000L, parseAtomDate("2020-09-13T12:26:40Z"))
    }

    @Test
    fun `获取 feed 解析出压缩包优先的获取链接与日期`() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:pse="http://vaemendis.net/opds-pse/ns">
  <title>漫画</title>
  <entry>
    <title>第 2 话</title>
    <id>urn:book:2</id>
    <updated>2020-05-02T10:00:00Z</updated>
    <published>2020-04-01T00:00:00Z</published>
    <summary>系列名：示例</summary>
    <link rel="http://opds-spec.org/image" href="cover2.jpg" type="image/jpeg"/>
    <link rel="http://opds-spec.org/image/thumbnail" href="thumb2.jpg" type="image/jpeg"/>
    <link rel="http://opds-spec.org/acquisition" href="pages2" type="image/jpeg"/>
    <link rel="http://opds-spec.org/acquisition/open-access" href="book2.cbz" type="application/vnd.comicbook+zip"/>
  </entry>
  <entry>
    <title>第 10 话</title>
    <id>urn:book:10</id>
    <updated>2020-05-03T10:00:00Z</updated>
    <link rel="http://opds-spec.org/acquisition" href="book10.zip" type="application/zip"/>
  </entry>
</feed>"""

        val feed = parseOpdsFeed(xml, feedUrl)

        assertEquals(2, feed.entries.size)
        val second = feed.entries[0]
        // 压缩包优先于单页图片（漫画要整本读）
        assertEquals("http://opds.example.com/book2.cbz", second.acquisition!!.href)
        assertEquals(1585699200000L, second.publishedMs)
        assertEquals(1588413600000L, second.updatedMs)
        assertEquals("系列名：示例", second.summary)
        assertFalse("有获取链接就不是导航条目", second.isNavigation)
        assertTrue(OpdsEntry.isReadable(second.acquisition!!))
        assertTrue(OpdsEntry.isReadable(feed.entries[1].acquisition!!))
    }

    @Test
    fun `命名空间前缀无关 且缺失日期不回崩`() {
        val xml = """<?xml version="1.0"?>
<atom:feed xmlns:atom="http://www.w3.org/2005/Atom">
  <atom:title>前缀测试</atom:title>
  <atom:entry>
    <atom:title>没有日期的书</atom:title>
    <atom:id>urn:x</atom:id>
    <atom:link rel="http://opds-spec.org/acquisition" href="http://other/x.cbz" type="application/zip"/>
  </atom:entry>
</atom:feed>"""

        val feed = parseOpdsFeed(xml, feedUrl)
        assertEquals("前缀测试", feed.title)
        assertNull(feed.entries.single().updatedMs)
        assertNull(feed.entries.single().publishedMs)
        // 已是绝对地址时原样保留
        assertEquals("http://other/x.cbz", feed.entries.single().acquisition!!.href)
    }

    @Test
    fun `feed 级 next 与 previous 分页链接被解析`() {
        val xml = """<?xml version="1.0"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>分页</title>
  <link rel="next" href="/opds/books?page=2" type="application/atom+xml"/>
  <link rel="previous" href="/opds/books?page=1" type="application/atom+xml"/>
  <entry>
    <title>书</title><id>urn:1</id>
    <link rel="http://opds-spec.org/acquisition" href="http://x/a.cbz" type="application/zip"/>
  </entry>
</feed>"""

        val feed = parseOpdsFeed(xml, feedUrl)
        assertEquals("http://opds.example.com/opds/books?page=2", feed.nextPageUrl)
        assertEquals("http://opds.example.com/opds/books?page=1", feed.previousPageUrl)
        // 分页链接不能被当成本条目的链接
        assertEquals(1, feed.entries.single().links.size)
    }

    @Test
    fun `epub 被排除 类型缺失或 octet 流仍可读`() {
        val xml = """<?xml version="1.0"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>类型</title>
  <entry>
    <title>电子书</title><id>urn:epub</id>
    <link rel="http://opds-spec.org/acquisition" href="http://x/a.epub" type="application/epub+zip"/>
  </entry>
  <entry>
    <title>类型未知</title><id>urn:unknown</id>
    <link rel="http://opds-spec.org/acquisition" href="http://x/b.cbz" type="application/octet-stream"/>
  </entry>
  <entry>
    <title>没有类型</title><id>urn:no-type</id>
    <link rel="http://opds-spec.org/acquisition" href="http://x/c.cbz"/>
  </entry>
</feed>"""

        val feed = parseOpdsFeed(xml, feedUrl)
        assertFalse("EPUB 不是漫画包，不该显示为可读书", OpdsEntry.isReadable(feed.entries[0].acquisition!!))
        assertTrue("类型写错时按魔数兜底，仍要给用户机会", OpdsEntry.isReadable(feed.entries[1].acquisition!!))
        assertTrue(OpdsEntry.isReadable(feed.entries[2].acquisition!!))
    }

    @Test
    fun `没有标题也没有链接的条目被忽略 标题缺失时退回链接地址`() {
        val xml = """<?xml version="1.0"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>x</title>
  <entry><id>urn:empty</id></entry>
  <entry>
    <id>urn:no-title</id>
    <link rel="http://opds-spec.org/acquisition" href="a.cbz" type="application/zip"/>
  </entry>
</feed>"""

        val feed = parseOpdsFeed(xml, feedUrl)
        assertEquals(1, feed.entries.size)
        assertEquals("http://opds.example.com/a.cbz", feed.entries.single().title)
    }
}
