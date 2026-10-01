package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 解码位图内存缓存的分区口径（票 #108 r5）：**页面位图与封面位图各有自己的预算，互不淘汰**。
 *
 * 真机现象（本用例要钉死的那条）：从阅读器返回书柜，书的封面变成灰块、要等好一会才出来。
 * 原因是两者共用**一个** `LruCache`：阅读器解的是整窗宽的页面位图（条漫一页几十 MB），一次阅读就能把
 * 缓存写满，LRU 于是把几分钟前看过的封面整批淘汰 ⇒ 返回书柜要重新取字节、重新解码。
 *
 * 用例里的 `Int` 就是「一张位图占多少 KB」的替身（缓存本身不认识位图，只按调用方给的尺寸记账）；
 * 第 1、2 条是本票的判别用例——把两份额度合成一份（改动前的行为）它们就红。
 */
class DecodedImageCacheTest {

    /** 预算：页面 10KB / 封面 100KB —— 小到一条记录就能写爆，判别不依赖堆内存大小 */
    private fun cache(sizeOfKb: (Int) -> Int = { it }) =
        DecodedImageCache<Int>(pageBudgetKb = 10, coverBudgetKb = 100, sizeOfKb = sizeOfKb)

    @Test
    fun `页面分区写爆也不淘汰封面分区`() {
        val cache = cache()
        cache.putCover("c1", 40)
        cache.putCover("c2", 40)

        // 阅读器解一页：单张就超过页面分区那 10KB 的预算
        cache.putPage("p1", 999)

        assertEquals("页面位图写满页面分区，封面一张都不该掉（返回书柜不再重解）", 40, cache.cover("c1"))
        assertEquals("同上", 40, cache.cover("c2"))
    }

    @Test
    fun `封面分区写爆也不淘汰页面分区`() {
        val cache = cache()
        cache.putPage("p1", 8)

        // 书柜滚动：封面把封面分区写爆
        cache.putCover("c1", 40)
        cache.putCover("c2", 40)
        cache.putCover("c3", 40)

        assertEquals("封面把封面分区写爆，页面分区不受影响", 8, cache.page("p1"))
    }

    @Test
    fun `同一分区越界按最旧淘汰`() {
        val cache = cache()
        cache.putCover("c1", 40)
        cache.putCover("c2", 40)
        cache.putCover("c3", 40) // 120 > 100：淘汰最旧的 c1

        assertNull("最旧的那张被淘汰", cache.cover("c1"))
        assertEquals("后两张留下", 40, cache.cover("c2"))
        assertEquals("后两张留下", 40, cache.cover("c3"))
    }

    @Test
    fun `命中刷新最近使用顺序`() {
        val cache = cache()
        cache.putCover("c1", 40)
        cache.putCover("c2", 40)
        cache.cover("c1") // 刚看过 ⇒ c2 变最旧
        cache.putCover("c3", 40)

        assertEquals("刚看过的 c1 留下", 40, cache.cover("c1"))
        assertNull("没再看过、又最旧的 c2 被淘汰", cache.cover("c2"))
    }

    @Test
    fun `同键重写只按差值记账`() {
        val cache = cache()
        cache.putCover("c1", 40)
        cache.putCover("c1", 30) // 同键换更小的：旧帐必须减掉，否则帐会虚高
        cache.putCover("c2", 80) // 30 + 80 = 110 > 100：只淘汰最旧的 c1

        assertEquals("按差值记账 ⇒ 只淘汰一条，最近写进来的留下", 80, cache.cover("c2"))
        assertNull("最旧的 c1 出局", cache.cover("c1"))
    }

    @Test
    fun `单张就超预算的也留下`() {
        val cache = cache()
        cache.putCover("huge", 999)

        assertEquals("比预算还大的一张也要留下（否则它永远进不了缓存，每次都要重解）", 999, cache.cover("huge"))
    }
}
