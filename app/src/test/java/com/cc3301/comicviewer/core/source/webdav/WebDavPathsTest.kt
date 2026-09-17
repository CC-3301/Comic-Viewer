package com.cc3301.comicviewer.core.source.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** WebDAV 路径规范（票 12）：纯函数，JVM 直测 */
class WebDavPathsTest {

    @Test
    fun `规范化补前导斜杠 折叠重复 去末尾斜杠`() {
        assertEquals("/", WebDavPaths.normalize(""))
        assertEquals("/", WebDavPaths.normalize("/"))
        assertEquals("/a/b", WebDavPaths.normalize("a/b"))
        assertEquals("/a/b", WebDavPaths.normalize("//a//b//"))
        assertEquals("/a/b", WebDavPaths.normalize("./a/./b"))
    }

    @Test
    fun `含上跳段直接拒绝 防止越出 DAV 根`() {
        assertThrows(IllegalArgumentException::class.java) { WebDavPaths.normalize("/a/../b") }
        assertThrows(IllegalArgumentException::class.java) { WebDavPaths.normalize("..") }
    }

    @Test
    fun `子路径与父路径`() {
        assertEquals("/a/b", WebDavPaths.child("/a", "b"))
        assertEquals("/x", WebDavPaths.child("/", "x"))
        assertEquals("/", WebDavPaths.parent("/a"))
        assertEquals("/a", WebDavPaths.parent("/a/b"))
        assertNull(WebDavPaths.parent("/"))
    }

    @Test
    fun `包含判定按段比较 不按字符前缀`() {
        assertTrue(WebDavPaths.isWithin("/", "/ab/c"))
        assertTrue(WebDavPaths.isWithin("/a", "/a/b"))
        assertFalse(WebDavPaths.isWithin("/a", "/ab"))
        assertFalse(WebDavPaths.isWithin("/a/b", "/a"))
    }

    @Test
    fun `拼接 URL 时按段百分号编码 中文与空格与井号`() {
        assertEquals("http://nas:5006/dav/a/b", WebDavPaths.join("http://nas:5006/dav", "/a/b"))
        // 集合 URL 一律以 / 结尾：只有带尾斜杠时服务器的相对 href 才能相对本集合解析
        assertEquals("http://nas:5006/dav/", WebDavPaths.join("http://nas:5006/dav/", "/"))
        assertEquals("http://nas:5006/dav/", WebDavPaths.join("http://nas:5006/dav", "/"))
        assertEquals(
            "http://nas:5006/dav/%E4%B8%AD%E6%96%87/%E7%AC%AC%201%E9%A1%B5.jpg",
            WebDavPaths.join("http://nas:5006/dav", "/中文/第 1页.jpg"),
        )
        assertEquals(
            "http://nas:5006/dav/a%23b",
            WebDavPaths.join("http://nas:5006/dav", "/a#b"),
        )
    }

    @Test
    fun `href 解析 兼容绝对 URL 绝对路径与相对路径 且拒绝越出 DAV 根`() {
        val base = "http://nas:5006/dav"
        assertEquals("/comics/series-a", WebDavPaths.fromHref("http://nas:5006/dav/comics/series-a", base))
        assertEquals("/comics/series-a", WebDavPaths.fromHref("/dav/comics/series-a", base))
        assertEquals("/comics/series-a", WebDavPaths.fromHref("comics/series-a/", base))
        // 目录 href 通常带尾斜杠：规范化后不带
        assertEquals("/comics", WebDavPaths.fromHref("http://nas:5006/dav/comics/", base))
        // 百分号编码在内部一律解码（id 与进度键用解码后的规范路径）
        assertEquals("/漫画/第 1页.jpg", WebDavPaths.fromHref("/dav/%E6%BC%AB%E7%94%BB/%E7%AC%AC%201%E9%A1%B5.jpg", base))
        // 越出 DAV 根（同级其他集合）：拒绝
        assertNull(WebDavPaths.fromHref("/other/comics", base))
        assertNull(WebDavPaths.fromHref("http://nas:5006/other/comics", base))
    }
}
