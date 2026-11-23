package com.cc3301.comicviewer.ui

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 条漫失焦冻结（spec `docs/spec/shell.md`「条漫列表的所有滚动都在窗口失焦时冻结」）的机制用例。
 *
 * 量的是 [freezeScrollAt] 与真 `LazyListState` 之间的约定，不碰组合里的接线：
 * - 冻结位置**成为下一次测量的位置**（页号取首个可见页、偏移取该页内已滚过的像素）；
 * - 正在进行的**输入滚动**（触摸拖动、滚轮、键盘共用的 UserInput 滚动会话）在冻结那一刻被打断，
 *   失焦前交来的增量不会在失焦之后继续落地。
 *
 * 驱动用 `scrollBy` 交增量：`LazyListState` 在拖动增量进来时同步落位（`onScroll` 里立即消费），
 * 因此不必等帧；取消则用 `Dispatchers.Unconfined` 上的滚动会话，取消沿调用线程同步完成。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderWebtoonFocusFreezeTest {

    /** 冻结把下一次测量钉在给定位置：页号与页内像素偏移都按 [LazyListState] 的口径 */
    @Test
    fun `冻结位置成为下一次测量的位置`() {
        val state = LazyListState(firstVisibleItemIndex = 5, firstVisibleItemScrollOffset = 0)
        val view = composeViewInActivity { pages(state = state, pageCount = 200) }
        view.layoutOnce(VIEW_WIDTH_PX, VIEW_HEIGHT_PX)
        assertEquals("起始页", 5, state.firstVisibleItemIndex)

        runBlocking { state.freezeScrollAt(index = 7, offset = 240) }
        // 请求只登记下一次测量，落地靠下一轮测量
        view.layoutOnce(VIEW_WIDTH_PX, VIEW_HEIGHT_PX)

        assertEquals("冻结时的首个可见页", 7, state.firstVisibleItemIndex)
        assertEquals("冻结时的页内像素偏移", 240, state.firstVisibleItemScrollOffset)
    }

    /** 冻结打断正在进行的输入滚动：会话被取消，位置停在冻结那一刻，失焦前开始的增量不再落地 */
    @Test
    fun `正在进行的输入滚动在冻结之后不再落地`() {
        val state = LazyListState()
        val view = composeViewInActivity { pages(state = state, pageCount = 200) }
        view.layoutOnce(VIEW_WIDTH_PX, VIEW_HEIGHT_PX)

        var scrollHeld = false
        val dragJob = CoroutineScope(Dispatchers.Unconfined).launch {
            state.scroll(MutatePriority.UserInput) {
                scrollBy(120f)
                scrollHeld = true
                awaitCancellation()
            }
        }
        assertTrue("拖动会话要真的持有一段输入滚动", scrollHeld)
        assertTrue("这段输入滚动应当正在进行", state.isScrollInProgress)

        val frozenIndex = state.firstVisibleItemIndex
        val frozenOffset = state.firstVisibleItemScrollOffset
        runBlocking { state.freezeScrollAt(index = frozenIndex, offset = frozenOffset) }

        assertTrue("冻结要打断正在进行的输入滚动", dragJob.isCancelled)
        assertFalse("冻结之后没有滚动在进行", state.isScrollInProgress)
        view.layoutOnce(VIEW_WIDTH_PX, VIEW_HEIGHT_PX)
        assertEquals("冻结时的首个可见页", frozenIndex, state.firstVisibleItemIndex)
        assertEquals("冻结时的页内像素偏移", frozenOffset, state.firstVisibleItemScrollOffset)
    }
}

/** 条漫的形状：逐页等高的长列表（页高 600px、视口 1200px ⇒ 一屏两页） */
@Composable
private fun pages(state: LazyListState, pageCount: Int) {
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        items(count = pageCount, key = { it }) { index ->
            Box(Modifier.height(PAGE_HEIGHT)) { Text("page-" + index) }
        }
    }
}

/** 视口 px：Robolectric 默认 mdpi ⇒ dp 与 px 一一对应 */
private const val VIEW_WIDTH_PX = 600
private const val VIEW_HEIGHT_PX = 1200

/** 页高：条漫一页远高于一屏，页内偏移才测得到 */
private val PAGE_HEIGHT = 600.dp
