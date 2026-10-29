package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 阅读器页面位图预算与设备档位（[readerPageTierOf] / [readerPageBitmapBudgetKb]）。
 *
 * 档位只决定三列：预取窗（后 + 前）、回看上限、页数上限；字节预算不按档位写死，
 * 一律按 `min(整机内存 / 32, 384MB)`。8G / 6G / 4G 标称容量下分别是 256 / 192 / 128MB；
 * 真实机器报告的内存略小，因此实际预算略低于标称值——本用例用真实报告值钉住这一条。
 */
class ReaderPageTierTest {

    private fun gib(n: Long): Long = n * 1024 * 1024 * 1024

    @Test
    fun `档位按真实整机内存分三档`() {
        assertTier(
            "≥7GiB：8G 档",
            readerPageTierOf(gib(7)),
            prefetchAfter = 7,
            prefetchBefore = 2,
            lookbackPages = 12,
            pageLimit = 24,
        )
        assertTier(
            "标称 8G 的机器报告 7.6GiB 上下，仍是 8G 档",
            readerPageTierOf((7.6 * 1024 * 1024 * 1024).toLong()),
            prefetchAfter = 7,
            prefetchBefore = 2,
            lookbackPages = 12,
            pageLimit = 24,
        )
        assertTier(
            "≥5GiB 且 <7GiB：6G 档",
            readerPageTierOf(gib(5)),
            prefetchAfter = 7,
            prefetchBefore = 2,
            lookbackPages = 10,
            pageLimit = 22,
        )
        assertTier(
            "标称 6G 的机器报告 5.8GiB 上下，仍是 6G 档",
            readerPageTierOf((5.8 * 1024 * 1024 * 1024).toLong()),
            prefetchAfter = 7,
            prefetchBefore = 2,
            lookbackPages = 10,
            pageLimit = 22,
        )
        assertTier(
            "其余（<5GiB）：4G 档",
            readerPageTierOf(gib(4)),
            prefetchAfter = 4,
            prefetchBefore = 1,
            lookbackPages = 8,
            pageLimit = 16,
        )
        assertTier(
            "标称 4G 的机器报告 3.7GiB 上下，仍是 4G 档",
            readerPageTierOf((3.7 * 1024 * 1024 * 1024).toLong()),
            prefetchAfter = 4,
            prefetchBefore = 1,
            lookbackPages = 8,
            pageLimit = 16,
        )
    }

    @Test
    fun `档位阈值是 GiB 边界 差一个字节就掉档`() {
        assertEquals("7GiB 差 1 字节 ⇒ 6G 档", 6, tierOfPageLimit(gib(7) - 1))
        assertEquals("7GiB 整 ⇒ 8G 档", 8, tierOfPageLimit(gib(7)))
        assertEquals("5GiB 差 1 字节 ⇒ 4G 档", 4, tierOfPageLimit(gib(5) - 1))
        assertEquals("5GiB 整 ⇒ 6G 档", 6, tierOfPageLimit(gib(5)))
    }

    /** 逐列断言（页数上限取推导后的值，不复述常量本身） */
    private fun assertTier(
        message: String,
        tier: ReaderPageTier,
        prefetchAfter: Int,
        prefetchBefore: Int,
        lookbackPages: Int,
        pageLimit: Int,
    ) {
        assertEquals(message + "：预取窗（后）", prefetchAfter, tier.prefetchAfter)
        assertEquals(message + "：预取窗（前）", prefetchBefore, tier.prefetchBefore)
        assertEquals(message + "：回看上限", lookbackPages, tier.lookbackPages)
        assertEquals(message + "：页数上限（回看 + 预取窗 + 在解 3）", pageLimit, tier.pageLimit)
    }

    @Test
    fun `页面位图预算 = min(整机内存除以 32, 384MB)`() {
        assertEquals("8GiB 标称：256MB", 256 * 1024, readerPageBitmapBudgetKb(gib(8)))
        assertEquals("6GiB 标称：192MB", 192 * 1024, readerPageBitmapBudgetKb(gib(6)))
        assertEquals("4GiB 标称：128MB", 128 * 1024, readerPageBitmapBudgetKb(gib(4)))
        assertEquals("16GiB 封顶 384MB", 384 * 1024, readerPageBitmapBudgetKb(gib(16)))
        assertEquals(
            "真实 7.6GiB 报出的内存略低于标称，预算因此略低于表值 256MB",
            249036,
            readerPageBitmapBudgetKb(8160437862L),
        )
    }

    /** 档位判据只看内存：用「页面位图分区最多几项」当可读的档位标签 */
    private fun tierOfPageLimit(totalMemoryBytes: Long): Int = when (readerPageTierOf(totalMemoryBytes).pageLimit) {
        24 -> 8
        22 -> 6
        else -> 4
    }
}
