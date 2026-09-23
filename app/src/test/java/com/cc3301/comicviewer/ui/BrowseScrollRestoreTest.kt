package com.cc3301.comicviewer.ui

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 「从阅读器返回时把滚动位置放回去」的两个纯函数（票 #124 r2）。
 *
 * 判别力：
 * - [restoredScrollItemIndex] 若把网格档的 `firstVisibleItemIndex` 再乘一次列数（本轮修掉的错口径），
 *   `网格档的恢复位置就是条目索引 不乘列数` 即红；
 * - [RestoredScrollIndex] 若去掉「换代重读」这条判据，`下拉更新换代后重读当下位置` 即红
 *   （在顶部下拉更新会被拽回旧索引）；
 * - [scrollRestoreTarget] 若去掉「当前位置确实退到它前面」这条判据，`位置还在 或…都不动` 即红（位置还在时也会去滚）。
 *
 * `短帧把恢复索引夹到末尾 显式放回才回到原处` 是 Robolectric 组合的**机制实测**（本修法的前提）：短帧测量
 * 确实把恢复的索引夹到已加载末尾，列表涨长不会自己回去，只有显式把位置请求回去才落到恢复索引。
 * 它不锁 `BrowserScreen` 的接线点（那需要组合整屏），只锁这条机制。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseScrollRestoreTest {

    @Test
    fun `网格档的恢复位置就是条目索引 不乘列数`() {
        // LazyGridState.firstVisibleItemIndex 是「首个可见行的首个格子」索引（行号 = 项索引 ÷ 列数，
        // 见 core/view/QuickScrollBar.kt 的行号推导），因此列数不参与——乘一次就会把取数下限放大 2–4 倍。
        assertEquals("4 列档恢复到条目 600：下限仍是 600", 600, restoredScrollItemIndex(listIndex = 0, gridIndex = 600, columns = 4))
        assertEquals("2 列档同样不放大", 600, restoredScrollItemIndex(listIndex = 0, gridIndex = 600, columns = 2))
        assertEquals("列表档取列表档的索引", 600, restoredScrollItemIndex(listIndex = 600, gridIndex = 0, columns = null))
    }

    @Test
    fun `短帧夹过之后取够页才放回恢复索引`() {
        // 实测形态（见 `短帧把恢复索引夹到末尾 显式放回才回到原处`）：恢复到 600、首帧 200 条 ⇒ 索引被夹到 184，
        // 取够 4 页后要放回 600。
        assertEquals(600, scrollRestoreTarget(restoredIndex = 600, currentIndex = 184, loadedItems = 800))
    }

    @Test
    fun `上限是 Lazy 项数 截断提示行占第 0 行时不把最末一条的放回挡掉`() {
        // 截断提示占项 0（`BrowserScreen` 里它是 Lazy 的第一个 item）⇒ 200 条的那一层里，最末一条的项索引
        // 就是 200，而条目数只有 200。上限若传条目数（200），`200 < 200` 为假、放回被跳掉；传项数（201）才对。
        assertEquals(200, scrollRestoreTarget(restoredIndex = 200, currentIndex = 184, loadedItems = 201))
        assertNull("同一条目数、不给截断提示行时就是真的落空（这一层只有 200 项）", scrollRestoreTarget(restoredIndex = 200, currentIndex = 184, loadedItems = 200))
    }

    @Test
    fun `位置还在 或这一层没那么长 或本来就没要恢复的位置 都不动`() {
        assertNull("位置还在（含用户自己滚到恢复索引之后）：不抢用户的滚动", scrollRestoreTarget(restoredIndex = 600, currentIndex = 600, loadedItems = 800))
        assertNull("这一层只有 250 条：恢复索引落空，不去滚", scrollRestoreTarget(restoredIndex = 600, currentIndex = 184, loadedItems = 250))
        assertNull("没要恢复的位置（首屏在顶部）", scrollRestoreTarget(restoredIndex = 0, currentIndex = 0, loadedItems = 800))
        assertNull("还没读到恢复位置（哨兵 -1）", scrollRestoreTarget(restoredIndex = -1, currentIndex = 0, loadedItems = 800))
    }

    @Test
    fun `下拉更新换代后重读当下位置 不沿用旧索引`() {
        // 票 #124 r2 影响面复验 P1：持有者活过 `reloadTick`（下拉更新 / 重试换 pager 而 scrollResetKey 不变），
        // 沿用旧索引会把在顶部的用户拽回上次恢复的位置，并把直取档首屏取数从 1 页变 ⌈旧索引/每页⌉ 页。
        val index = RestoredScrollIndex()

        assertEquals("第一次读当下索引（从阅读器返回：600）", 600, index.valueFor(generation = 0, restoredIndex = 600))
        assertEquals(
            "同一代里重跑（来源解析）：不覆盖，第二次读到的已是被短帧夹过的值",
            600,
            index.valueFor(generation = 0, restoredIndex = 184),
        )
        assertEquals("下拉更新换一代：重读当下索引（在顶部 = 0）", 0, index.valueFor(generation = 1, restoredIndex = 0))
        assertEquals("同一代里仍不覆盖", 0, index.valueFor(generation = 1, restoredIndex = 184))
        assertEquals("再刷一次（重试）：重读当下位置", 500, index.valueFor(generation = 2, restoredIndex = 500))
    }

    @Test
    fun `短帧把恢复索引夹到末尾 显式放回才回到原处`() {
        val count = mutableStateOf(200)
        // 与界面同源：恢复的滚动索引来自 rememberSaveable 交回的滚动状态（这里直接以 600 构造）
        val state = LazyListState(firstVisibleItemIndex = 600, firstVisibleItemScrollOffset = 0)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity)
        activity.setContentView(view)
        view.setContent {
            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                items(count = count.value, key = { it }) { index ->
                    Box(Modifier.height(50.dp)) { Text("row-$index") }
                }
            }
        }
        layout(view)
        val afterShortFrame = state.firstVisibleItemIndex
        assertTrue(
            "首帧那份短列表把恢复索引夹到已加载末尾（实测 600 → 184）：本修法要修的正是这一下",
            afterShortFrame < 600,
        )

        count.value = 800
        layout(view)
        assertEquals("列表涨长不会自己回到原索引（只有显式放回才行）", afterShortFrame, state.firstVisibleItemIndex)

        state.requestScrollToItem(600)
        layout(view)
        assertEquals("取够页后把位置放回去：落在恢复索引", 600, state.firstVisibleItemIndex)
    }

    /** 量一次固定视口（400×800px、条目 50dp） */
    private fun layout(view: ComposeView) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
