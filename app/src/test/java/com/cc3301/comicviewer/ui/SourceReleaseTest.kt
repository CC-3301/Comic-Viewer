package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 被换出浏览槽的实例的释放判定（review-18-r3 P1；票 #30 P1 起由 `ServiceLocator.browsingSourceFor`
 * 的单槽会话级缓存调用）：阅读器正在用的会话来源不可关（交给 currentSource 的 setter / closeSession），
 * 其余被换出去的实例必须关，否则单 SMB 连接下每切一次连接都漏一个 SMB 会话。
 * 守卫体即服务定位器释放路径的全部内容，故本测试锁定的就是释放判定本身。
 */
class SourceReleaseTest {

    /**
     * 只关心身份与 close 计数：行为方法不参与本用例。
     * equals 按类型相等（任意两个实例 `==`）——用来证明释放判定取引用身份（`!==`）而非 equals。
     */
    private class FakeSource(private val failOnClose: Boolean = false) : Source {
        var closeCount = 0
            private set

        override val type = SourceType.SMB
        override fun close() {
            closeCount++
            if (failOnClose) throw IllegalStateException("模拟 SMB 会话已断开")
        }

        override fun equals(other: Any?): Boolean = other is FakeSource
        override fun hashCode(): Int = FakeSource::class.hashCode()

        override suspend fun listEntries(containerId: String?, sort: SortMode) = throw UnsupportedOperationException("不参与本用例")
        override suspend fun openBook(bookId: String) = throw UnsupportedOperationException("不参与本用例")
        override suspend fun readProgress(bookId: String) = throw UnsupportedOperationException("不参与本用例")
        override suspend fun writeProgress(bookId: String, pageIndex: Int, totalPages: Int) = throw UnsupportedOperationException("不参与本用例")
        override suspend fun neighbors(bookId: String) = throw UnsupportedOperationException("不参与本用例")
    }

    @Test
    fun `会话来源实例不关`() {
        val session = FakeSource()

        releaseReplacedSource(session, session)

        assertEquals(0, session.closeCount)
    }

    @Test
    fun `未被提升为会话来源的实例关掉`() {
        val replaced = FakeSource()
        val session = FakeSource()

        // 两个实例 equals 相等但引用不同：判定取身份，被换出去的实例仍必须释放
        releaseReplacedSource(replaced, session)

        assertEquals(1, replaced.closeCount)
        assertEquals(0, session.closeCount)
    }

    @Test
    fun `无会话来源时被换出去的实例关掉`() {
        val replaced = FakeSource()

        releaseReplacedSource(replaced, session = null)

        assertEquals(1, replaced.closeCount)
    }

    @Test
    fun `解析未完成（实例为 null）时不关也不抛`() {
        val session = FakeSource()

        releaseReplacedSource(replaced = null, session = session)

        assertEquals(0, session.closeCount)
    }

    @Test
    fun `关闭失败不向调用方抛出`() {
        val replaced = FakeSource(failOnClose = true)

        releaseReplacedSource(replaced, session = null)

        assertEquals(1, replaced.closeCount) // 已尝试释放；异常不得冒泡到释放入口
    }
}
