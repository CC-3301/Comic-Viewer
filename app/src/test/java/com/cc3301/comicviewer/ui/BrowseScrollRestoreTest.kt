package com.cc3301.comicviewer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.view.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
        val view = composeViewInActivity {
            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                items(count = count.value, key = { it }) { index ->
                    Box(Modifier.height(50.dp)) { Text("row-$index") }
                }
            }
        }
        view.layoutOnce(400, 800)
        val afterShortFrame = state.firstVisibleItemIndex
        assertTrue(
            "首帧那份短列表把恢复索引夹到已加载末尾（实测 600 → 184）：本修法要修的正是这一下",
            afterShortFrame < 600,
        )

        count.value = 800
        view.layoutOnce(400, 800)
        assertEquals("列表涨长不会自己回到原索引（只有显式放回才行）", afterShortFrame, state.firstVisibleItemIndex)

        state.requestScrollToItem(600)
        view.layoutOnce(400, 800)
        assertEquals("取够页后把位置放回去：落在恢复索引", 600, state.firstVisibleItemIndex)
    }

    /**
     * 票 #111 r10 修复（评审 r9 P1-2）：A4 把会话快照改到**构造期**落帧之后，浏览页首帧就是那份短列表；
     * 而恢复索引的读在 `LaunchedEffect(pager, reverse)` 里 —— effect 体在本帧 composition + layout **之后**
     * 才跑 ⇒ 读到的已是被夹过的索引（本例实测 [effectRead] < 600）。所以「在任何一帧列表上屏之前读一次」
     * 那条不变式必须换个读点：在**离开这一屏的那一刻**（`onDispose`，事件时刻、还没经过短帧）记下索引，
     * 取它与 effect 读的**较大者**（夹只会把索引变小 ⇒ 较大的那个就是未夹的）。
     *
     * 本用例同时钉住两件事：① 机制（短帧先上屏 ⇒ effect 读被夹）；② [unclippedRestoredScrollIndex]
     * 的判据（取回未夹的那个）。拿掉修复（只留 effect 读）⇒ 位置丢。
     */
    @Test
    fun `短帧先上屏之后 effect 读到的已被夹 离开时记下的那个才没被夹`() {
        val count = mutableStateOf(200)
        // 与界面同源：恢复的滚动索引来自 rememberSaveable 交回的滚动状态（这里直接以 600 构造）
        val state = LazyListState(firstVisibleItemIndex = 600, firstVisibleItemScrollOffset = 0)
        // 「离开这一屏」那一刻记下的索引（BrowserScreen 在 onDispose 里读，事件时刻、未经任何短帧）
        val recordedOnLeave = restoredScrollItemIndex(state.firstVisibleItemIndex, gridIndex = 0, columns = null)
        var effectRead = -1
        val view = composeViewInActivity {
            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                items(count = count.value, key = { it }) { index ->
                    Box(Modifier.height(50.dp)) { Text("row-$index") }
                }
            }
            LaunchedEffect(Unit) {
                effectRead = restoredScrollItemIndex(state.firstVisibleItemIndex, gridIndex = 0, columns = null)
            }
        }
        view.layoutOnce(400, 800)

        assertEquals("离开时记下的是原索引（那一帧还没有短列表测量过）", 600, recordedOnLeave)
        assertTrue(
            "A4 的短帧先上屏之后，effect 里读到的已是被夹过的索引（实测 $effectRead）——只靠 effect 读就丢位置",
            effectRead < 600,
        )
        assertEquals(
            "取较大者取回未夹的那个：位置不再丢（只留 effect 读即红）",
            600,
            unclippedRestoredScrollIndex(recordedOnLeave, effectRead),
        )
    }

    /**
     * 离场记下的索引必须按**离场那一刻的档位**算（票 #111 r10 b3/3，评审 r10-b1 P1）。
     *
     * b1 的写法（[captureIndexOnLeaveByValue]）在**创建效应那一刻**就把不可变枚举 `view` 捕进闭包，而
     * `DisposableEffect(listState, gridState)` 的 key 不含档位 ⇒ 切档位不重建效应。网格档进屏（600）→
     * 切列表档 → 滚到 20 → 离场：捕值的写法记下的是 gridState 的 600（**另一个容器**的索引），再经
     * [unclippedRestoredScrollIndex] 的「取较大者」抬成「未夹的值」⇒ 返回后从错位置恢复、还多发请求。
     *
     * **本用例不守护生产接线**（与同文件的「短帧把索引夹到末尾」同一口径：锁机制、不锁接线点）：它演示的是
     * 「两种写法在本仓**可区分**」——把 `BrowserScreen` 回退成捕值，本用例仍全绿。生产侧是否真的走
     * `rememberUpdatedState` 只能靠**真机判据**：网格档进大目录 → 切列表档 → 滚一段 → 进阅读器再返回，
     * 位置与档位都对（不会恢复到另一个容器的索引、也不多发请求）。
     *
     * 两半都测出来：① 生产形态（[captureIndexOnLeave]，经 `rememberUpdatedState` 读当下档位）记 20；
     * ② 捕值的反例记 600 —— 同一次组合里两种写法结论不同，证明这条断言**真的能分辨**，不是恒真。
     *
     * （票 #139：本用例原先假设「一轮 [`layoutOnce`] 就够」，全量跑时偶发红。两次推进改成**有界等待**——
     * 切档位等「子作用域已按列表档组合过」、离场等「两个回调都落地」；判据与期望值不动，超时照样失败。）
     */
    @Test
    fun `两种写法在本仓可区分 捕值记旧档位 读当下记新档位`() {
        // 两档各给一个可区分的索引：网格档 600（进屏时的档位）、列表档 20（切档位后滚到的位置）。
        // 两个滚动状态都只构造、不组合进列表（本用例钉的是「读哪一个容器」，不涉及测量与夹索引）。
        val gridState = LazyGridState(firstVisibleItemIndex = 600, firstVisibleItemScrollOffset = 0)
        val listState = LazyListState(firstVisibleItemIndex = 20, firstVisibleItemScrollOffset = 0)
        val mode = mutableStateOf(ViewMode.GRID_3)
        val alive = mutableStateOf(true)
        val recordedByUpdated = mutableStateOf(-1)
        val recordedByCapturedValue = mutableStateOf(-1)
        // 「子作用域已按某档位组合过」的观测点，供切档位那步的有界等待用（见 [captureIndexOnLeave]）。
        val composedByUpdated = mutableStateOf<ViewMode?>(null)

        val view = composeViewInActivity {
            val modeNow = mode.value
            if (alive.value) {
                captureIndexOnLeave(
                    view = modeNow,
                    listState = listState,
                    gridState = gridState,
                    onComposed = { composedByUpdated.value = it },
                    onLeave = { recordedByUpdated.value = it },
                )
                captureIndexOnLeaveByValue(modeNow, listState, gridState) { recordedByCapturedValue.value = it }
            }
        }
        view.layoutOnce(400, 800)

        // 切档位（网格 → 列表）：效果不重建（key 只有两个滚动状态）。
        mode.value = ViewMode.LIST
        // 先等到「子作用域已按列表档组合过」再离场：切档位与离场若并到**同一轮**重组，子作用域那次不重跑，
        // `rememberUpdatedState` 里仍是旧档位 ⇒ 读当下档位的写法也只剩旧档位（探针实测记 600）。
        val composedSettled = view.layoutUntil(400, 800) { composedByUpdated.value == ViewMode.LIST }

        // 离场：整个组合拆掉 ⇒ onDispose 跑。
        // 回调落在 idle 段、不保证一轮内到（见 [layoutUntil]）：有界等待到两个回调都落地再断言。
        alive.value = false
        val leaveSettled = view.layoutUntil(400, 800) { recordedByUpdated.value != -1 && recordedByCapturedValue.value != -1 }

        // 两处等待的成败写进断言消息：超时要在日志里看得见，但**不遮住** `-1` / `600` 两个值形态。
        // （名字用 [waitNote] 而不是 `waited`：后者与本文件顶层的 [waited] 函数同名，且就在这个表达式里被调用，读起来会以为是变量。）
        val waitNote = "（有界等待：档位落地=${waited(composedSettled)}、离场回调=${waited(leaveSettled)}）"
        assertEquals("生产形态：记下的是**当下**档位（列表档）的索引$waitNote", 20, recordedByUpdated.value)
        assertEquals("捕值的反例：记下的是创建效应那一刻（网格档）的索引 —— 这就是 b1 的 bug 形态$waitNote", 600, recordedByCapturedValue.value)
    }
}

/**
 * 生产形态（票 #111 r10 b3/3）：档位经 `rememberUpdatedState` 读**当下**值（`BrowserScreen` 里是
 * `rememberUpdatedState(view)` + 一个共用的取值口）。
 *
 * [onComposed] 是「本子作用域刚按 [view] 组合过」的观测点，经 [SideEffect] 在**组合应用之后**发布
 * （组合期不写 snapshot state）：`SideEffect` 跑过 = 同一子作用域的 `viewNow` 已是 [view]。
 * 用例靠它做有界等待，保证「档位切换已落地」先于「离场」——否则两处状态变化并到同一轮重组时，
 * 子作用域那次不重跑、`viewNow` 仍是旧档位，读当下的写法也只剩旧档位。
 */
@Composable
private fun captureIndexOnLeave(
    view: ViewMode,
    listState: LazyListState,
    gridState: LazyGridState,
    onComposed: (ViewMode) -> Unit,
    onLeave: (Int) -> Unit,
) {
    val viewNow by rememberUpdatedState(view)
    SideEffect { onComposed(view) }
    DisposableEffect(listState, gridState) {
        onDispose {
            onLeave(
                restoredScrollItemIndex(
                    listIndex = listState.firstVisibleItemIndex,
                    gridIndex = gridState.firstVisibleItemIndex,
                    columns = viewNow.columns,
                ),
            )
        }
    }
}

/** 有界等待的成败写法：超时要在断言消息里看得见（`-1` / `600` 两个值形态照旧原样给出） */
private fun waited(settled: Boolean): String = if (settled) "已落地" else "**超时未落地**"

/** b1 的写法（本文件的**反例**，只为「这条断言能分辨两种写法」而存在，不是生产形状） */
@Composable
private fun captureIndexOnLeaveByValue(
    view: ViewMode,
    listState: LazyListState,
    gridState: LazyGridState,
    onLeave: (Int) -> Unit,
) {
    DisposableEffect(listState, gridState) {
        onDispose {
            onLeave(
                restoredScrollItemIndex(
                    listIndex = listState.firstVisibleItemIndex,
                    gridIndex = gridState.firstVisibleItemIndex,
                    columns = view.columns,
                ),
            )
        }
    }
}
