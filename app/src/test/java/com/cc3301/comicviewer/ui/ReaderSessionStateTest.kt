package com.cc3301.comicviewer.ui

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateViewModelFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.view.ReaderScrollAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 阅读位置与页比例挂在**阅读页导航条目**的 ViewModel 上、内容写进该条目的保存态
 * （spec §重建后的位置恢复）。本文件钉的是保存态的寿命与内容：
 *
 * - 保存态随条目落盘（进程被系统杀掉后回来）⇒ 位置与附近页比例都在，首帧就能按记住的页比例摆页高、
 *   按记住的位置定位；这里用真实注册表把保存态存成 [Bundle] 再拿它重建一条新条目，即进程被杀的往返；
 * - 换一条条目（换书 / 退出阅读器）⇒ 新保存态桶，从「没有位置」开始；
 * - 只留**当前页**前后各若干页的比例（当前页 = 视口中线所在的页，与菜单同一口径），当前页走远就把范围外的丢掉；
 * - 位置与页比例是一份保存值：还没有位置时比例不单独存（没有可恢复的东西）。
 *
 * 记录与恢复的换算不在本文件：那是 `core/view/ReaderScrollAnchor` 的纯函数，见 `ReaderScrollAnchorTest`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderSessionStateTest {

    /**
     * 一条导航条目的保存态桶：与 `NavBackStackEntry` 同角色——注册表 + 生命周期，
     * [open] 走的是「挂上 → 还原 → 到 CREATED」这一条恢复顺序。
     */
    private class EntrySavedState(private val restored: Bundle? = null) : Entry {
        private val controller = SavedStateRegistryController.create(this)
        private val lifecycles = LifecycleRegistry(this)

        override val viewModelStore = ViewModelStore()
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        override val lifecycle: Lifecycle get() = lifecycles

        fun open() {
            controller.performAttach()
            controller.performRestore(restored)
            lifecycles.currentState = Lifecycle.State.CREATED
        }

        /** 系统保存实例状态那一刻：条目保存态写进 Bundle */
        fun save(): Bundle = Bundle().apply { controller.performSave(this) }
    }

    /** 一条导航条目要同时是保存态持有者与 ViewModel 桶 */
    private interface Entry : SavedStateRegistryOwner, ViewModelStoreOwner

    @Test
    fun `进程被杀后位置与附近页比例随条目保存态回来`() {
        val entry = EntrySavedState().apply { open() }
        val before = stateOf(entry)
        before.recordScrollAnchor(ReaderScrollAnchor(page = 12, percent = 0.35f), currentPage = 13)
        before.recordPageAspect(12, 0.5f)
        before.recordPageAspect(14, 2f)

        // 系统把保存态写进 Activity 的实例状态 → 进程被杀 → 同一条目重建
        val saved = entry.save()
        val after = stateOf(EntrySavedState(saved).apply { open() })

        assertEquals("位置页号跟着回来", 12, checkNotNull(after.scrollAnchor).page)
        assertEquals("页内百分比跟着回来", 0.35f, checkNotNull(after.scrollAnchor).percent, 1e-6f)
        assertEquals("锚点页自己的比例跟着回来（首帧按它摆页高）", 0.5f, checkNotNull(after.pageAspect(12)), 1e-6f)
        assertEquals("前后页的比例也跟着回来", 2f, checkNotNull(after.pageAspect(14)), 1e-6f)
    }

    @Test
    fun `换书与退出阅读器的新条目从没有位置开始`() {
        val before = stateOf(EntrySavedState().apply { open() })
        before.recordScrollAnchor(ReaderScrollAnchor(page = 3, percent = 0.4f), currentPage = 3)
        before.recordPageAspect(3, 0.5f)

        // 换书换 entry / 退出阅读器弹掉 entry：新条目 = 新保存态桶
        val other = stateOf(EntrySavedState().apply { open() })
        assertNull("新条目没有位置", other.scrollAnchor)
        assertNull("上一本的页比例不带过来", other.pageAspect(3))
    }

    @Test
    fun `比例窗口按当前页取锚 位置锚点仍是顶部可见页`() {
        val state = stateOf(EntrySavedState().apply { open() })
        // 位置记在第 10 页（顶部可见页），当前页（视口中线所在的页）是第 12 页：差一页是常态
        state.recordScrollAnchor(ReaderScrollAnchor(page = 10, percent = 0.2f), currentPage = 12)
        assertEquals("位置锚点仍是顶部可见页", 10, checkNotNull(state.scrollAnchor).page)

        state.recordPageAspect(12, 1f)
        state.recordPageAspect(15, 1.5f)
        state.recordPageAspect(8, 0.8f)
        state.recordPageAspect(16, 1.2f)

        assertEquals("当前页 12 的窗口是 9..15：15 在窗口内", 1.5f, checkNotNull(state.pageAspect(15)), 1e-6f)
        assertNull("8 不在当前页的窗口里（按位置页 10 取窗口才会留下它）", state.pageAspect(8))
        assertNull("16 不在窗口里（够覆盖一屏即可，不无限长）", state.pageAspect(16))

        state.recordScrollAnchor(ReaderScrollAnchor(page = 100, percent = 0f), currentPage = 100)
        assertNull("当前页走远后旧页的比例丢掉", state.pageAspect(12))
        assertNull("旧范围整段丢掉", state.pageAspect(15))
    }

    @Test
    fun `还没有位置时不记比例 位置与比例是一份保存值`() {
        val state = stateOf(EntrySavedState().apply { open() })
        state.recordPageAspect(3, 0.5f)

        assertNull("没有位置就没有可恢复的东西，比例不单独存", state.pageAspect(3))
        assertNull("新条目没有位置", state.scrollAnchor)
    }

    /** 与条目里 `viewModel()` 同一份取法：默认工厂 = 带保存态的 ViewModel 工厂 */
    private fun stateOf(entry: Entry): ReaderSessionState {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val factory = SavedStateViewModelFactory(application, entry, null)
        return ViewModelProvider(entry, factory)[ReaderSessionState::class.java]
    }
}
