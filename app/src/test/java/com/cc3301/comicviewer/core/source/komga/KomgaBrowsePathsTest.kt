package com.cc3301.comicviewer.core.source.komga

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Komga 起始路径解析（票 #78，纯函数）：空值 / 非法值回落 `/`（四入口），
 * 合法形态往返一致，上一级逐级回到根。
 *
 * 票 #78 修复轮：落库段名改成**稳定 token**（`/collections` 这类，不随界面文案变），
 * 解析同时兼容 r1 落过的中文段名——存量连接的起点不能因为改名就不认。
 */
class KomgaBrowsePathsTest {

    @Test
    fun `空值与根路径都解析为四入口`() {
        assertEquals(KomgaBrowsePath.Root, KomgaBrowsePaths.parse(null))
        assertEquals(KomgaBrowsePath.Root, KomgaBrowsePaths.parse(""))
        assertEquals(KomgaBrowsePath.Root, KomgaBrowsePaths.parse("   "))
        assertEquals(KomgaBrowsePath.Root, KomgaBrowsePaths.parse("/"))
        assertEquals(KomgaBrowsePath.Root, KomgaBrowsePaths.parse("//"))
    }

    @Test
    fun `非法值回落四入口 不抛异常`() {
        assertEquals(KomgaBrowsePath.Root, KomgaBrowsePaths.parse("garbage"))
        assertEquals(KomgaBrowsePath.Root, KomgaBrowsePaths.parse("/不认识的类别"))
        assertEquals(KomgaBrowsePath.Root, KomgaBrowsePaths.parse("/collections/c1/extra"))
        assertEquals(KomgaBrowsePath.Root, KomgaBrowsePaths.parse("/books/x"))
        assertEquals("/", KomgaBrowsePaths.normalize("/不认识的类别"))
        assertEquals("/", KomgaBrowsePaths.normalize(null))
    }

    @Test
    fun `四个类别与两级形态往返一致`() {
        assertEquals(KomgaBrowsePath.Collections, KomgaBrowsePaths.parse("/collections"))
        assertEquals(KomgaBrowsePath.Series, KomgaBrowsePaths.parse("/series"))
        assertEquals(KomgaBrowsePath.Books, KomgaBrowsePaths.parse("/books"))
        assertEquals(KomgaBrowsePath.Read, KomgaBrowsePaths.parse("/read"))
        assertEquals(KomgaBrowsePath.Collection("c1"), KomgaBrowsePaths.parse("/collections/c1"))
        assertEquals(KomgaBrowsePath.SeriesBooks("s1"), KomgaBrowsePaths.parse("/series/s1"))

        assertEquals("/collections", KomgaBrowsePaths.format(KomgaBrowsePath.Collections))
        assertEquals("/collections/c1", KomgaBrowsePaths.format(KomgaBrowsePath.Collection("c1")))
        assertEquals("/series/s1", KomgaBrowsePaths.format(KomgaBrowsePath.SeriesBooks("s1")))
        assertEquals("/books", KomgaBrowsePaths.format(KomgaBrowsePath.Books))
        assertEquals("/read", KomgaBrowsePaths.format(KomgaBrowsePath.Read))

        // 归一后尾斜杠不带出来
        assertEquals("/series/s1", KomgaBrowsePaths.normalize("/series/s1/"))
    }

    @Test
    fun `r1 的中文段名仍认 并归一成 token`() {
        // 票 #78 修复轮：段名改稳定 token，但 r1 落过库的中文段必须照旧解析
        assertEquals(KomgaBrowsePath.Collections, KomgaBrowsePaths.parse("/收藏"))
        assertEquals(KomgaBrowsePath.Series, KomgaBrowsePaths.parse("/系列"))
        assertEquals(KomgaBrowsePath.Books, KomgaBrowsePaths.parse("/书籍"))
        assertEquals(KomgaBrowsePath.Read, KomgaBrowsePaths.parse("/阅读过"))
        assertEquals(KomgaBrowsePath.Collection("c1"), KomgaBrowsePaths.parse("/收藏/c1"))
        assertEquals(KomgaBrowsePath.SeriesBooks("s1"), KomgaBrowsePaths.parse("/系列/s1"))

        assertEquals("/collections/c1", KomgaBrowsePaths.normalize("/收藏/c1"))
        assertEquals("/read", KomgaBrowsePaths.normalize("/阅读过"))
    }

    @Test
    fun `类别路径与容器语义一一对应`() {
        // 类别本身是路径选择器「/」下一层的四项，也是容器 id 的 kind（稳定 token）
        assertEquals(listOf("/collections", "/series", "/books", "/read"), KomgaCategory.entries.map { it.path })
        assertEquals(listOf("collections", "series", "books", "read"), KomgaCategory.entries.map { it.kind })
        assertEquals(listOf("收藏", "系列", "书籍", "阅读过"), KomgaCategory.entries.map { it.label })
        // 旧段名与新 token 都映射到同一个类别
        assertEquals(KomgaCategory.COLLECTIONS, KomgaCategory.ofSegment("collections"))
        assertEquals(KomgaCategory.COLLECTIONS, KomgaCategory.ofSegment("收藏"))
        assertEquals(null, KomgaCategory.ofSegment("不认识的类别"))
    }

    @Test
    fun `上一级逐级回到根`() {
        assertEquals(KomgaBrowsePath.Collections, KomgaBrowsePaths.parent(KomgaBrowsePath.Collection("c1")))
        assertEquals(KomgaBrowsePath.Series, KomgaBrowsePaths.parent(KomgaBrowsePath.SeriesBooks("s1")))
        assertEquals(KomgaBrowsePath.Root, KomgaBrowsePaths.parent(KomgaBrowsePath.Collections))
        assertEquals(KomgaBrowsePath.Root, KomgaBrowsePaths.parent(KomgaBrowsePath.Series))
        assertEquals(KomgaBrowsePath.Root, KomgaBrowsePaths.parent(KomgaBrowsePath.Books))
        assertEquals(KomgaBrowsePath.Root, KomgaBrowsePaths.parent(KomgaBrowsePath.Read))
        assertEquals(KomgaBrowsePath.Root, KomgaBrowsePaths.parent(KomgaBrowsePath.Root))
    }
}
