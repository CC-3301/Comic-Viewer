package com.cc3301.comicviewer.ui

import androidx.compose.runtime.SideEffect
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 菜单显隐挂在**阅读页导航条目**上（spec §阅读菜单的显隐状态）：走生产的取法
 * （组合里 `viewModel()` 从条目的 [ViewModelStoreOwner] 领），钉的是条目桶这一层的寿命：
 *
 * - 同一个条目桶（Activity 重建时 `NavController` 留着它）⇒ 同一个 [ReaderMenuState]，离开时开着、回来还开着；
 * - 换一个条目桶（换书换 entry / 退出阅读器弹掉 entry）⇒ 新对象，从关闭开始；
 * - 不做保存态 ⇒ 进程结束不恢复（本对象没有落盘路径，这点由「新条目桶从关闭开始」一并体现）。
 *
 * 显隐的三处写入（点屏幕中区、点空白处、系统返回键）与过渡动画不在本文件：那是 `ReaderScreen` 的接线，
 * 走手动验收（单击呼出 / 点空白关闭 / 返回键先关菜单）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderMenuStateTest {

    /** 一条导航条目的 ViewModel 桶 */
    private class EntryViewModelStore : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    @Test
    fun `同一条目重建后保持 新条目重置为关闭`() {
        val entry = EntryViewModelStore()

        val before = menuStateOf(entry)
        assertFalse("菜单初始是关的", before.menuVisible)
        before.menuVisible = true

        // 新的一次组合（旋转 / 深浅色切换后）：同一个条目桶 ⇒ 同一个 ViewModel
        val after = menuStateOf(entry)
        assertSame("同一 entry 的 ViewModel 桶不重建 ⇒ 拿到同一个对象", before, after)
        assertTrue("离开时开着，回来还开着", after.menuVisible)

        // 换书（换 entry）/ 退出阅读器（弹掉 entry）：新桶 ⇒ 新对象
        val otherEntry = menuStateOf(EntryViewModelStore())
        assertFalse("新条目从关闭开始", otherEntry.menuVisible)
    }

    /** 真走一次组合，按生产同一句 `viewModel()` 从条目桶里取状态 */
    private fun menuStateOf(entry: ViewModelStoreOwner): ReaderMenuState {
        var captured: ReaderMenuState? = null
        composeViewInActivity {
            val state = viewModel<ReaderMenuState>(viewModelStoreOwner = entry)
            SideEffect { captured = state }
        }.layoutOnce(1080)
        return checkNotNull(captured) { "组合没有跑到：取不到 ReaderMenuState" }
    }
}
