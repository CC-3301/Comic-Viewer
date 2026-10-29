package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本次打开里第一次真的画出图」的页（[ReaderPageFirstAppearance]）：页图淡入的判据。
 *
 * 淡入只给首见页；回看 / 重进（页条目被回收后重新组合）直接上屏。记录活在阅读器这一次打开里
 * （按书分槽）：换书 = 新的一次打开。
 *
 * 本文件只钉**记录本身**；领取时机（位图到手那一刻）在 `ReaderPage` 的取解 effect 里，不在本文件的覆盖范围内。
 */
class ReaderPageFirstAppearanceTest {

    @Test
    fun `同一页只有第一次画出图算首见`() {
        val appearance = ReaderPageFirstAppearance()

        assertTrue("第 3 页第一次画出图：淡入", appearance.claim(3))
        assertFalse("第 3 页被回收后重新组合：不再淡入", appearance.claim(3))
        assertTrue("第 0 页第一次画出图：淡入", appearance.claim(0))
        assertFalse("同一份记录里再问一次：仍不算首见", appearance.claim(0))
    }

    @Test
    fun `换书是新的实例 上一本的首见记录不带过来`() {
        assertTrue("下一本书新建的实例：同一页号仍是首见", ReaderPageFirstAppearance().claim(3))
    }
}
