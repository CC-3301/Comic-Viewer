package com.cc3301.comicviewer.core.shelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书柜分柜语义（票 31 决策 1/5/7，spec 故事 43/44）：一条连接一个柜，柜序沿用连接序，
 * 柜名取自连接配置，因此离线、加载失败、空库的连接照常成柜。
 *
 * 票 41 收口后这里断言的是**柜列表这一层真实的生产语义**：[BookshelfCabinet] 不再带柜内条目
 * （生产路径上那份「连接 → 根条目」表恒空——柜列表只按连接立柜，柜内条目由单柜页自己按
 * connectionId 取来源枚举），因此不再拼造条目 map。条目层面的语义留在柜页自己的通路上：
 * 多连接不混排、容器进浏览列表、书进阅读器、只有书条目有进度条见 WebDavShelfTest / KomgaShelfTest；
 * 「连接被删除 → 柜位消失」的库侧接线见 LocalRootsDeleteTest。
 */
class BookshelfTest {

    @Test
    fun `一条连接一个柜 柜序沿用连接序`() {
        // 柜的识别依据是 connectionId：柜名可以重复，id 不能——柜内条目按它取来源（spec 故事 44）
        val cabinets = groupIntoCabinets(listOf(CabinetRef(7, "乙"), CabinetRef(3, "甲")))

        assertEquals(listOf(7L, 3L), cabinets.map { it.connectionId })
        assertEquals(listOf("乙", "甲"), cabinets.map { it.displayName })
    }

    @Test
    fun `一个连接恰一个柜 多连接不混排`() {
        // 柜与连接一一对应：谁也不会把两条连接并进同一个柜（并了就没有单独的入口进去），
        // 柜内条目因此只可能来自本柜 connectionId 指向的那一个来源
        val refs = listOf(CabinetRef(1, "本机漫画"), CabinetRef(2, "NAS SMB"))

        val cabinets = groupIntoCabinets(refs)

        assertEquals(2, cabinets.size)
        assertEquals(
            refs.map { it.connectionId to it.displayName },
            cabinets.map { it.connectionId to it.displayName },
        )
    }

    @Test
    fun `展示名相同的两条连接各自成柜 不并柜`() {
        // 两台同名 NAS：柜名列出来会一样，但必须各占一个柜（识别依据是 connectionId）
        val cabinets = groupIntoCabinets(listOf(CabinetRef(1, "NAS"), CabinetRef(2, "NAS")))

        assertEquals("同名连接照旧是两个柜", 2, cabinets.size)
        assertEquals(listOf(1L, 2L), cabinets.map { it.connectionId })
        assertEquals(listOf("NAS", "NAS"), cabinets.map { it.displayName })
    }

    @Test
    fun `离线或空库的连接照样成柜 立柜不看来源`() {
        // 立柜的输入就是连接表本身（函数没有来源/条目参数）：来源离线、授权失效或空库都不影响柜出现
        // （票 31 决策 7：柜名取自连接配置；柜内取不到条目时由单柜页出「加载失败 + 重试」）
        val cabinets = groupIntoCabinets(listOf(CabinetRef(1, "离线 NAS"), CabinetRef(2, "空库 SMB")))

        assertEquals(listOf(1L, 2L), cabinets.map { it.connectionId })
        assertEquals(listOf("离线 NAS", "空库 SMB"), cabinets.map { it.displayName })
    }

    @Test
    fun `连接已删除时它不再成柜 没有连接就没有柜`() {
        // 立柜的输入就是「当前连接表」：连接被删掉后列表里不再有它，柜位随之消失（票 31 决策 1）
        val cabinets = groupIntoCabinets(listOf(CabinetRef(2, "有书")))

        assertEquals(listOf(2L), cabinets.map { it.connectionId })
        assertTrue("没有连接就没有柜", groupIntoCabinets(emptyList()).isEmpty())
    }
}
