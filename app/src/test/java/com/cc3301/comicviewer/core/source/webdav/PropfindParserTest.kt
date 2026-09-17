package com.cc3301.comicviewer.core.source.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROPFIND 响应解析（票 12）：用真实服务器风格的响应样本（命名空间前缀、尾斜杠、中文百分号编码）验证。
 * 这一层是纯函数，不需要网络，替代了「容器化 WebDAV 服务」无法在本机运行的部分缺口。
 */
class PropfindParserTest {

    private val baseUrl = "http://nas:5006/dav"

    @Test
    fun `解析 Depth 1 列表 排除自身 目录与文件区分大小与修改时间`() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/dav/comics/</D:href>
    <D:propstat>
      <D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/comics/series-a/</D:href>
    <D:propstat>
      <D:prop>
        <D:resourcetype><D:collection/></D:resourcetype>
        <D:getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</D:getlastmodified>
      </D:prop>
    </D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/comics/ep%2010.cbz</D:href>
    <D:propstat>
      <D:prop>
        <D:resourcetype/>
        <D:getcontentlength>2048</D:getcontentlength>
        <D:getlastmodified>Thu, 22 Oct 2015 07:28:00 GMT</D:getlastmodified>
      </D:prop>
    </D:propstat>
  </D:response>
</D:multistatus>"""

        val entries = parsePropfind(xml, baseUrl, selfPath = "/comics")

        // 解析保持响应内的文档顺序（排序由 DocumentTreeSource 负责）
        assertEquals(listOf("/comics/series-a", "/comics/ep 10.cbz"), entries.map { it.path })
        val dir = entries.first { it.isDirectory }
        assertEquals("series-a", dir.name)
        assertEquals(0L, dir.size)
        assertEquals(1445412480000L, dir.lastModifiedMs)
        val file = entries.first { !it.isDirectory }
        assertEquals("ep 10.cbz", file.name)
        assertEquals(2048L, file.size)
        assertEquals(1445498880000L, file.lastModifiedMs)
    }

    @Test
    fun `兼容任意命名空间前缀与小写属性`() {
        val xml = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>http://nas:5006/dav/comics/page1.jpg</d:href>
    <d:propstat>
      <d:prop>
        <d:resourcetype/>
        <d:getcontentlength>12</d:getcontentlength>
        <d:getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</d:getlastmodified>
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
</d:multistatus>"""

        val entries = parsePropfind(xml, baseUrl, selfPath = "/comics")
        assertEquals(1, entries.size)
        assertEquals("/comics/page1.jpg", entries.first().path)
    }

    @Test
    fun `多 propstat 时只取 2xx 且缺 collection 时用尾斜杠兜底`() {
        // RFC 4918：允许先返回一个「属性不存在」的 propstat；取错会让目录丢掉 collection 标记
        val xml = """<?xml version="1.0"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/dav/comics/</D:href>
    <D:propstat>
      <D:prop><D:getcontentlength/></D:prop>
      <D:status>HTTP/1.1 404 Not Found</D:status>
    </D:propstat>
    <D:propstat>
      <D:prop><D:resourcetype/><D:getcontentlength>0</D:getcontentlength></D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/comics/sub/</D:href>
    <D:propstat>
      <D:prop><D:resourcetype/></D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
</D:multistatus>"""

        val entries = parsePropfind(xml, baseUrl, selfPath = "/")

        // 第一个：collection 在第二个（2xx）propstat 里 → 必须认出来
        assertTrue(entries.first { it.path == "/comics" }.isDirectory)
        // 第二个：没有 collection 但 href 带尾斜杠（服务器惯例）→ 也算目录
        assertTrue(entries.first { it.path == "/comics/sub" }.isDirectory)
    }

    @Test
    fun `Depth 0 自身条目也能解析 且缺失字段不崩`() {
        val xml = """<?xml version="1.0"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/dav/comics</D:href>
    <D:propstat>
      <D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
</D:multistatus>"""

        val entries = parsePropfind(xml, baseUrl, selfPath = "\u0000")
        assertEquals(1, entries.size)
        assertTrue(entries.first().isDirectory)
        assertNull(entries.first().lastModifiedMs)
        assertEquals("/comics", entries.first().path)
    }

    @Test
    fun `越出 DAV 根的 href 被忽略`() {
        val xml = """<?xml version="1.0"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/other/comics/x.cbz</D:href>
    <D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat>
  </D:response>
</D:multistatus>"""

        assertTrue(parsePropfind(xml, baseUrl, selfPath = "/").isEmpty())
    }

    @Test
    fun `HTTP 日期解析异常时返回 null`() {
        assertEquals(1445412480000L, parseHttpDate("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertNull(parseHttpDate("2015-10-21T07:28:00Z"))
        assertNull(parseHttpDate(""))
    }
}
