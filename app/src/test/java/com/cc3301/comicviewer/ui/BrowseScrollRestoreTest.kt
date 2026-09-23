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
 *   第一条用例即红；
 * - [scrollRestoreTarget] 若去掉「当前位置确实退到它前面」这条判据，第三条用例即红（位置还在时也会去滚）。
 *
 * 第三条用例是 Robolectric 组合的**机制实测**（本修法的前提）：短帧测量确实把恢复的索引夹到已加载末尾，
 * 列表涨长不会自己回去，只有显式把位置请求回去才落到恢复索引。它不锁 `BrowserScreen` 的接线点
 * （那需要组合整屏），只锁这条机制。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseScrollRestoreTest {

    @Test
    fun `网格档的恢复位置就是条目索引 不乘列数`() {
        // LazyGridState.firstVisibleItemIndex 是「首个可见行的首个格子」索引（行号 = 条目索引 ÷ 列数，
        // 见 core/view/QuickScrollBar.kt 的行号推导），因此列数不参与——乘一次就会把取数下限放大 2–4 倍。
        assertEquals("4 列档恢复到条目 600：下限仍是 600", 600, restoredScrollItemIndex(listIndex = 0, gridIndex = 600, columns = 4))
        assertEquals("2 列档同样不放大", 600, restoredScrollItemIndex(listIndex = 0, gridIndex = 600, columns = 2))
        assertEquals("列表档取列表档的索引", 600, restoredScrollItemIndex(listIndex = 600, gridIndex = 0, columns = null))
    }

    @Test
    fun `短帧夹过之后取够页才放回恢复索引`() {
        // 实测形态（见下一条用例）：恢复到 600、首帧 200 条 ⇒ 索引被夹到 184，取够 4 页后要放回 600。
        assertEquals(600, scrollRestoreTarget(restoredIndex = 600, currentIndex = 184, loadedRows = 800))
    }

    @Test
    fun `位置还在 或这一层没那么长 或本来就没要恢复的位置 都不动`() {
        assertNull("位置还在（含用户自己滚到恢复索引之后）：不抢用户的滚动", scrollRestoreTarget(restoredIndex = 600, currentIndex = 600, loadedRows = 800))
        assertNull("这一层只有 250 条：恢复索引落空，不去滚", scrollRestoreTarget(restoredIndex = 600, currentIndex = 184, loadedRows = 250))
        assertNull("没要恢复的位置（首屏在顶部）", scrollRestoreTarget(restoredIndex = 0, currentIndex = 0, loadedRows = 800))
        assertNull("还没读到恢复位置（哨兵 -1）", scrollRestoreTarget(restoredIndex = -1, currentIndex = 0, loadedRows = 800))
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
