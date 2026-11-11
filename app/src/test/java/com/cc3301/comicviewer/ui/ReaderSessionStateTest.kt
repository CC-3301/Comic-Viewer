package com.cc3301.comicviewer.ui

import androidx.compose.runtime.SideEffect
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cc3301.comicviewer.core.view.ReaderScrollAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 阅读位置与页比例挂在**阅读页导航条目**上（spec §重建后的位置恢复）：走生产的取法
 * （组合里 `viewModel()` 从条目的 [ViewModelStoreOwner] 领），钉的是条目桶这一层的寿命：
 *
 * - 同一个条目桶（Activity 重建时 `NavController` 留着它）⇒ 同一个 [ReaderSessionState]，
 *   位置与页比例都还在 ⇒ 重建后首帧就能按记住的比例摆页；
 * - 换一个条目桶（换书换 entry / 退出阅读器弹掉 entry）⇒ 新对象，从「没有位置」开始；
 * - 不做保存态 ⇒ 进程结束不恢复。
 *
 * 记录与恢复的换算不在本文件：那是 `core/view/ReaderScrollAnchor` 的纯函数，见 `ReaderScrollAnchorTest`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderSessionStateTest {

    /** 一条导航条目的 ViewModel 桶 */
    private class EntryViewModelStore : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    @Test
    fun `同一条目重建后位置与页比例都还在 新条目从空开始`() {
        val entry = EntryViewModelStore()

        val before = stateOf(entry)
        assertNull("本次打开还没有记过位置", before.scrollAnchor)
        assertNull("还没有任何一页画出来", before.pageAspect(3))
        before.scrollAnchor = ReaderScrollAnchor(page = 3, percent = 0.4f)
        before.recordPageAspect(3, 2f / 3f)

        // 新的一次组合（旋转 / 深浅色切换后）：同一个条目桶 ⇒ 同一个 ViewModel
        val after = stateOf(entry)
        assertSame("同一 entry 的 ViewModel 桶不重建 ⇒ 拿到同一个对象", before, after)
        assertEquals("位置跟着回来", 0.4f, checkNotNull(after.scrollAnchor).percent, 1e-6f)
        assertEquals("重建后首帧用的就是记住的页比例", 2f / 3f, checkNotNull(after.pageAspect(3)), 1e-6f)

        // 换书（换 entry）/ 退出阅读器（弹掉 entry）：新桶 ⇒ 新对象
        val otherEntry = stateOf(EntryViewModelStore())
        assertNull("新条目从没有位置开始", otherEntry.scrollAnchor)
        assertNull("上一本的页比例不带过来", otherEntry.pageAspect(3))
    }

    @Test
    fun `页比例按页各存一份`() {
        val state = stateOf(EntryViewModelStore())
        state.recordPageAspect(0, 0.5f)
        state.recordPageAspect(1, 16f / 9f)
        assertEquals("同一页重画时覆盖", 0.5f, checkNotNull(state.pageAspect(0)), 1e-6f)
        assertEquals(16f / 9f, checkNotNull(state.pageAspect(1)), 1e-6f)
        assertNull("没画过的页没有比例", state.pageAspect(2))
    }

    /** 真走一次组合，按生产同一句 `viewModel()` 从条目桶里取状态 */
    private fun stateOf(entry: ViewModelStoreOwner): ReaderSessionState {
        var captured: ReaderSessionState? = null
        composeViewInActivity {
            val state = viewModel<ReaderSessionState>(viewModelStoreOwner = entry)
            SideEffect { captured = state }
        }.layoutOnce(1080)
        return checkNotNull(captured) { "组合没有跑到：取不到 ReaderSessionState" }
    }
}
