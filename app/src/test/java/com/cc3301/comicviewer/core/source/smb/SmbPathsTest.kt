package com.cc3301.comicviewer.core.source.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** SMB 路径规范（票 11）：纯函数，JVM 直测 */
class SmbPathsTest {

    @Test
    fun `规范化补前导斜杠 折叠重复 去末尾斜杠`() {
        assertEquals("/", SmbPaths.normalize(""))
        assertEquals("/", SmbPaths.normalize("/"))
        assertEquals("/a/b", SmbPaths.normalize("a/b"))
        assertEquals("/a/b", SmbPaths.normalize("//a//b//"))
        assertEquals("/a/b", SmbPaths.normalize("./a/./b"))
        assertEquals("/a/b", SmbPaths.normalize("a\\b"))
    }

    @Test
    fun `含上跳段直接拒绝 防止越出共享根`() {
        assertThrows(IllegalArgumentException::class.java) { SmbPaths.normalize("/a/../b") }
        assertThrows(IllegalArgumentException::class.java) { SmbPaths.normalize("..") }
    }

    @Test
    fun `子路径与父路径`() {
        assertEquals("/a/b", SmbPaths.child("/a", "b"))
        assertEquals("/x", SmbPaths.child("/", "x"))
        assertEquals("/", SmbPaths.parent("/a"))
        assertEquals("/a", SmbPaths.parent("/a/b"))
        assertNull(SmbPaths.parent("/"))
    }

    @Test
    fun `包含判定按段比较 不按字符前缀`() {
        assertTrue(SmbPaths.isWithin("/", "/ab/c"))
        assertTrue(SmbPaths.isWithin("/a", "/a"))
        assertTrue(SmbPaths.isWithin("/a", "/a/b"))
        assertFalse(SmbPaths.isWithin("/a", "/ab"))
        assertFalse(SmbPaths.isWithin("/a/b", "/a"))
    }

    @Test
    fun `Windows 风格与规范路径互转`() {
        assertEquals("a\\b", SmbPaths.toWindows("/a/b"))
        assertEquals("", SmbPaths.toWindows("/"))
        assertEquals("/a/b", SmbPaths.fromWindows("a\\b"))
        assertEquals("/", SmbPaths.fromWindows(""))
        assertEquals("/a/b", SmbPaths.fromWindows(SmbPaths.toWindows("/a/b")))
    }

    @Test
    fun `伪条目判定 点与点点过滤 隐藏文件保留`() {
        assertTrue(SmbPaths.isPseudoEntry("."))
        assertTrue(SmbPaths.isPseudoEntry(".."))
        assertFalse("隐藏文件是真实条目", SmbPaths.isPseudoEntry(".hidden"))
    }

    @Test
    fun `id 前缀默认端口省略 非默认端口带上以免碰撞`() {
        assertEquals("smb://nas/comics", SmbPaths.idPrefix("nas", "comics", 445))
        assertEquals("smb://nas:1445/comics", SmbPaths.idPrefix("nas", "comics", 1445))
    }

    @Test
    fun `id 前缀与表单地址共用端口写法 含冒号的主机不加方括号以保进度键`() {
        // 非冒号主机：id 前缀 == "smb://" + 表单地址 + "/共享"（端口规则一处实现，不会与展示漂移）
        for (host in listOf("nas.local", "192.168.1.10")) {
            for (port in listOf(445, 1445)) {
                assertEquals(
                    "smb://" + SmbConnectionConfig.formatAddress(host, port) + "/comics",
                    SmbPaths.idPrefix(host, "comics", port),
                )
            }
        }
        // IPv6（含冒号）主机：id 前缀沿用旧写法（不加方括号），既有进度键不受影响；方括号只出现在表单地址
        assertEquals("smb://fe80::1/comics", SmbPaths.idPrefix("fe80::1", "comics", 445))
        assertEquals("smb://fe80::1:1445/comics", SmbPaths.idPrefix("fe80::1", "comics", 1445))
    }
}
