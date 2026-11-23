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
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
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
 * [freezeScrollAt] 依赖的两条 `LazyListState` 外部行为：
 * - 冻结位置写进滚动状态后，下一次测量从该位置开始（页号 + 该页内已滚过的像素）；
 * - `PreventUserInput` 的滚动会话能打断正在进行的 `UserInput` 会话（触摸拖动、滚轮与键盘共用），
 *   被打断的会话不再落地；且这次打断发生在**第一处挂起之前**，失焦回调里当场生效。
 *
 * 失焦接线本身（`ReaderSessionBody` 的窗口焦点监听）不在本用例内：Compose 交互按
 * `docs/SPEC.md` Testing Decisions 走手动验收。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderWebtoonFocusFreezeTest {

    /** 冻结位置取代当前位置，成为下一次测量的位置 */
    @Test
    fun `冻结位置成为下一次测量的位置`() {
        val state = LazyListState(firstVisibleItemIndex = 5, firstVisibleItemScrollOffset = 0)
        val view = composeViewInActivity { pages(state = state, pageCount = 200) }
        view.layoutOnce(VIEW_WIDTH_PX, VIEW_HEIGHT_PX)
        assertEquals("起始页", 5, state.firstVisibleItemIndex)

        runBlocking { state.freezeScrollAt(index = 7, offset = 240) }
        // 请求只登记位置与下一次测量，落地靠下一轮测量
        view.layoutOnce(VIEW_WIDTH_PX, VIEW_HEIGHT_PX)

        assertEquals("冻结时的首个可见页", 7, state.firstVisibleItemIndex)
        assertEquals("冻结时的页内像素偏移", 240, state.firstVisibleItemScrollOffset)
    }

    /** 正在进行的输入滚动被冻结打断：会话取消、不再落地，位置停在冻结那一刻 */
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

    /** 取消在第一次挂起之前发出：这是失焦回调里当场生效、不等下一调度的前提 */
    @Test
    fun `取消在第一次挂起之前就发出`() {
        val state = LazyListState()
        val view = composeViewInActivity { pages(state = state, pageCount = 200) }
        view.layoutOnce(VIEW_WIDTH_PX, VIEW_HEIGHT_PX)

        var scrollHeld = false
        val dragJob = CoroutineScope(Dispatchers.Unconfined).launch {
            state.scroll(MutatePriority.UserInput) {
                scrollBy(40f)
                scrollHeld = true
                awaitCancellation()
            }
        }
        assertTrue("拖动会话要真的持有一段输入滚动", scrollHeld)

        // 只排队、不执行的派发器：挂起之后的部分一律不跑
        val freezeScope = CoroutineScope(ParkedDispatcher)
        freezeScope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.freezeScrollAt(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
        }
        assertTrue("冻结返回前就应发出取消", dragJob.isCancelled)
        freezeScope.cancel()
    }
}

/** 只把协程体排进队列、从不执行的派发器：用来确认某段代码在第一处挂起之前做了什么 */
private object ParkedDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
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
