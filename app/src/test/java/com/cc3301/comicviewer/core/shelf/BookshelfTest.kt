package com.cc3301.comicviewer.core.shelf

import com.cc3301.comicviewer.core.source.BrowseEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书柜分柜语义（票 31 决策 1/5/7，spec 故事 43/44）：一条连接一个柜，柜内只装该连接的根条目，
 * 多连接永不混排；柜名取自连接配置，因此离线、加载失败、空库的连接照常成柜。
 */
class BookshelfTest {

    private fun folder(id: String, name: String = id) = BrowseEntry(id, name, isBook = false, coverUri = null)

    private fun book(id: String, name: String = id) = BrowseEntry(id, name, isBook = true, coverUri = null)

    // ---------- 分柜规则 ----------

    @Test
    fun `一条连接一个柜 柜序沿用连接序`() {
        val cabinets = groupIntoCabinets(
            connections = listOf(CabinetRef(7, "乙"), CabinetRef(3, "甲")),
            rootEntries = emptyMap(),
        )

        assertEquals(listOf(7L, 3L), cabinets.map { it.connectionId })
        assertEquals(listOf("乙", "甲"), cabinets.map { it.displayName })
    }

    @Test
    fun `离线或空库的连接照样成柜`() {
        // 柜名来自连接配置，不需要会话：来源取不到根条目时柜依然要出现在柜列表里（票 31 决策 7）
        val cabinets = groupIntoCabinets(
            connections = listOf(CabinetRef(1, "离线 NAS"), CabinetRef(2, "可用 SMB")),
            rootEntries = mapOf(2L to listOf(book("b"))),
        )

        assertEquals(listOf(1L, 2L), cabinets.map { it.connectionId })
        assertTrue("离线柜的条目为空（界面出「加载失败 + 重试」）", cabinets[0].entries.isEmpty())
        assertEquals(listOf("b"), cabinets[1].entries.map { it.id })
    }

    @Test
    fun `柜内条目只来自本连接 多连接不混排`() {
        val cabinets = groupIntoCabinets(
            connections = listOf(CabinetRef(1, "本机漫画"), CabinetRef(2, "NAS SMB")),
            rootEntries = mapOf(
                1L to listOf(folder("local/dir"), book("local/a.cbz")),
                2L to listOf(folder("smb/dir")),
            ),
        )

        assertEquals(listOf("local/dir", "local/a.cbz"), cabinets[0].entries.map { it.id })
        assertEquals(listOf("smb/dir"), cabinets[1].entries.map { it.id })
        assertTrue("本机柜里不得出现别的连接的条目", cabinets[0].entries.none { it.id.startsWith("smb/") })
    }

    @Test
    fun `根条目全部陈列 不排除根目录下直接的书`() {
        // 柜内数据源就是 listEntries(null)：一级文件夹与根下直接的书/压缩包都进柜（票 31 决策 1）
        val cabinets = groupIntoCabinets(
            connections = listOf(CabinetRef(1, "本机漫画")),
            rootEntries = mapOf(
                1L to listOf(folder("话 01"), book("单行本.cbz"), book("设定集.jpg"), folder("合集")),
            ),
        )

        val entries = cabinets.single().entries
        assertEquals(listOf("话 01", "单行本.cbz", "设定集.jpg", "合集"), entries.map { it.name })
        assertEquals(listOf("单行本.cbz", "设定集.jpg"), entries.filter { it.isBook }.map { it.name })
    }

    @Test
    fun `柜内条目顺序沿用来源给出的顺序`() {
        // 柜内排序由该连接的来源按当前排序设置给出（票 31 决策 2）：分柜只搬运，不重排
        val ordered = listOf(folder("话 02"), book("单行本.cbz"), folder("话 01"))

        val cabinet = groupIntoCabinets(listOf(CabinetRef(1, "本机漫画")), mapOf(1L to ordered)).single()

        assertEquals(listOf("话 02", "单行本.cbz", "话 01"), cabinet.entries.map { it.id })
    }

    @Test
    fun `连接已删除时它残留的根条目不再成柜 没有连接就没有柜`() {
        // 数据源改为「连接 → 根条目」后，判断依据只有连接本身：连接没了，条目也就无从陈列
        val cabinets = groupIntoCabinets(
            connections = listOf(CabinetRef(2, "有书")),
            rootEntries = mapOf(2L to listOf(book("b")), 99L to listOf(book("孤儿"))),
        )

        assertEquals(listOf(2L), cabinets.map { it.connectionId })
        assertEquals(listOf("b"), cabinets.flatMap { it.entries }.map { it.id })

        assertTrue(groupIntoCabinets(emptyList(), mapOf(1L to listOf(book("a")))).isEmpty())
    }
}
