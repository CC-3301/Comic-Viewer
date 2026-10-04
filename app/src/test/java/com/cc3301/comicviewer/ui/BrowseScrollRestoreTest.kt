package com.cc3301.comicviewer.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.sort.SortDirection
import com.cc3301.comicviewer.core.source.PerfTiming
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.view.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * 界面这一侧的位置判据：**真组合、真尺寸像素、生产接线**。
 *
 * 三块：
 * - **纯算式**（[restoredScrollItemIndex] / [scrollRestoreTarget] / [initialScrollItemIndex] /
 *   [restoredIndexOnLeaveFor] / [RestoredScrollIndex] / 打点行格式）——本文件只用它们做像素与机制用例的输入；
 * - **机制测量**（[Lazy] 真测量）：短帧确实把恢复的索引夹到已加载末尾、列表涨长不会自己回去，
 *   只有显式把位置请求回去才落到恢复索引（`短帧把恢复索引夹到末尾 显式放回才回到原处` /
 *   `短帧先上屏之后 effect 读到的已被夹 离开时记下的那个才没被夹`）；
 * - **像素级落位**（[LANDING_QUALIFIERS] 真密度）：被恢复那一条的封面顶边贴视口上沿（列表档 / 网格档）、
 *   首屏链放回时同口径，以及滑条拖动落点（与停位共用同一份留白口径 [restoredLandingOffsetPx]）。
 *
 * **位置模块自身的判据**（四个入口的往返、换代次、用掉即清、丢态拒写、作废：层层与代次、注入的存储与链）
 * 在 `BrowseScrollPositionTest`——那边是纯 JUnit，不碰 Compose 与 `ServiceLocator`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseScrollRestoreTest {

    private lateinit var context: Context

    /**
     * 像素级用例要真比例尺（[landingTopContentPaddingPx] 从 `displayMetrics.density` 取 dp→px）：
     * 与全仓同类用例一致把取 context 放 `@Before`。
     */
    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `网格档的恢复位置就是条目索引 不乘列数`() {
        // LazyGridState.firstVisibleItemIndex 是「首个可见行的首个格子」索引（行号 = 项索引 ÷ 列数，
        // 见 core/view/QuickScrollBarGeometry.kt 的行号推导），因此列数不参与——乘一次就会把取数下限放大 2–4 倍。
        assertEquals("4 列档恢复到条目 600：下限仍是 600", 600, restoredScrollItemIndex(listIndex = 0, gridIndex = 600, columns = 4))
        assertEquals("2 列档同样不放大", 600, restoredScrollItemIndex(listIndex = 0, gridIndex = 600, columns = 2))
        assertEquals("列表档取列表档的索引", 600, restoredScrollItemIndex(listIndex = 600, gridIndex = 0, columns = null))
    }

    @Test
    fun `短帧夹过之后取够页才放回恢复索引`() {
        // 短帧形态（见 `短帧把恢复索引夹到末尾 显式放回才回到原处`）：恢复到 600、首帧 200 条 ⇒ 索引被夹到 184，
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
    fun `记录落在首帧范围内时 初值就是记录的位置`() {
        // 组合期给初值（不是组合后 `requestScrollToItem` 跳过去）。
        // 文件源的会话快照就是**上次上屏的那份整表**（`Source.cachedEntries` 的 KDoc）⇒ 记录落在首帧范围内，
        // 首帧因此直接组在原位，不再「先到顶部再跳」。
        assertEquals("首帧 800 项、记录 600 ⇒ 初值 600", 600, initialScrollItemIndex(recordedIndex = 600, firstFrameItemCount = 800))
        assertEquals("末项也在范围内（记录 799 ⇒ 初值 799）", 799, initialScrollItemIndex(recordedIndex = 799, firstFrameItemCount = 800))
        assertEquals("没记录过的层 ⇒ 初值 0（首帧在顶部，票 #58 的承诺不变）", 0, initialScrollItemIndex(recordedIndex = 0, firstFrameItemCount = 800))
    }

    @Test
    fun `首帧短于记录时 min 夹到末项 不给越界初值`() {
        // 直取档的会话内列表只含第 0 页（200 条，`docs/spec/browsing.md`「直取档的取数下限」）⇒ 记录 600
        // 若原样塞进初值就是越界值；取 min 夹到首帧末项。
        assertEquals("首帧 200 项、记录 600 ⇒ 199（首帧末项）", 199, initialScrollItemIndex(recordedIndex = 600, firstFrameItemCount = 200))
        assertEquals("首帧只有 1 项 ⇒ 0", 0, initialScrollItemIndex(recordedIndex = 600, firstFrameItemCount = 1))
        assertEquals("首帧还没有列表（冷启动没落过帧）⇒ 0，绝不给 -1", 0, initialScrollItemIndex(recordedIndex = 600, firstFrameItemCount = 0))
        assertEquals("没记录 + 没首帧 ⇒ 0", 0, initialScrollItemIndex(recordedIndex = 0, firstFrameItemCount = 0))
    }

    @Test
    fun `盘上那条启动恢复值只属于启动那一代 换排序重建的滚动状态回顶部`() {
        // 启动落地命中这一层时，盘上那份**不带代次**的一次性记录若被记住整个屏期，
        // 换排序换代次后按新键重建的滚动状态，初值与首屏链的取数下限照旧取它 ⇒ 在启动恢复命中的那一层上
        // 换排序（含重选当前排序）不回顶部（`docs/spec/browsing.md`「排序在展示层翻转 / 滚动复位」）。
        // 判别力（反证）：把 `restoredIndexOnLeaveFor` 的「只在启动那一代吃盘上那条」改回「每次都吃」
        // ⇒ 下面第二条断言读到 600，本用例红。
        val atStartup = BrowseScrollResetKey(
            mode = SortMode.NAME, direction = SortDirection.FORWARD, revision = 0, staleIds = null,
        )
        // 重选当前排序也换代次：`SortSettingStore.setting` 每次写入令 revision +1
        val afterResorting = BrowseScrollResetKey(
            mode = SortMode.NAME, direction = SortDirection.FORWARD, revision = 1, staleIds = null,
        )

        assertEquals(
            "启动后第一份滚动状态：吃盘上那条 600",
            600,
            restoredIndexOnLeaveFor(
                diskAtStartup = 600,
                startupGeneration = atStartup,
                currentGeneration = atStartup,
                inMemoryIndex = 0,
            ),
        )
        assertEquals(
            "换排序（revision +1 = 新代次）：初值回到本代次的内存记录 0，不再吃盘上那条",
            0,
            restoredIndexOnLeaveFor(
                diskAtStartup = 600,
                startupGeneration = atStartup,
                currentGeneration = afterResorting,
                inMemoryIndex = 0,
            ),
        )
        assertEquals(
            "初值 0 + 首帧 800 项 ⇒ 首帧就在顶部（换排序跳顶）",
            0,
            initialScrollItemIndex(
                recordedIndex = restoredIndexOnLeaveFor(
                    diskAtStartup = 600,
                    startupGeneration = atStartup,
                    currentGeneration = afterResorting,
                    inMemoryIndex = 0,
                ),
                firstFrameItemCount = 800,
            ),
        )
    }

    @Test
    fun `启动那一代仍吃盘上那条 盘上无记录则退回本代次内存记录`() {
        // 另一半：不能把「启动落回记录层恢复位置」一起弄坏（现行口径第 2 条）。
        // 判别力：把「当前代次 == 启动那一代」这条判据删掉（一律不吃盘上那条）⇒ 下面第一条断言读到 0，本用例红。
        val atStartup = BrowseScrollResetKey(
            mode = SortMode.NAME, direction = SortDirection.FORWARD, revision = 0, staleIds = null,
        )

        val diskValue = restoredIndexOnLeaveFor(
            diskAtStartup = 600,
            startupGeneration = atStartup,
            currentGeneration = atStartup,
            inMemoryIndex = 0,
        )
        assertEquals("启动那一代：盘上那条照旧用", 600, diskValue)
        assertEquals(
            "初值就落在盘上那个位置（不再「先到顶部再跳」）",
            600,
            initialScrollItemIndex(recordedIndex = diskValue, firstFrameItemCount = 800),
        )
        assertEquals(
            "这一层不是落地层（盘上没记录）：退回本代次的内存记录 18",
            18,
            restoredIndexOnLeaveFor(
                diskAtStartup = null,
                startupGeneration = atStartup,
                currentGeneration = atStartup,
                inMemoryIndex = 18,
            ),
        )
    }

    @Test
    fun `组合期没给初值的那一帧在顶部 给了初值的那一帧在原位`() {
        // 机制测量（同本文件「短帧把恢复索引夹到末尾」的口径：锁机制、不锁 `BrowserScreen` 的接线点）：
        // `LazyListState()` 不给初值 ⇒ 首帧量出来就是 0（设备 `phase=read` 那一刻的 `now=0` 就是它）；
        // 给初值 ⇒ 首帧量出来就是初值，没有「先到顶部、再跳过去」那一下。
        //
        // **判别力**：`firstVisibleItemIndex` 在**构造那一刻**就等于传给它的那个初值，
        // 所以「只构造、不测量」是恒真的假绿——把组合与测量整段删掉，下面两条断言照样绿（那是拿构造默认值
        // 自比，什么都没锁）。因此这里先**有界等到这份列表真的被量过**（`totalItemsCount` 从 0 变成条目数 =
        // 首帧测量已把这一帧摆上屏），并把等待成败写成会失败的断言；等到之后再断位置，断的才是「首帧落在哪」。
        val snapshotItems = 800
        val stateFromTop = LazyListState()
        val stateWithInitial = LazyListState(
            firstVisibleItemIndex = initialScrollItemIndex(recordedIndex = 600, firstFrameItemCount = snapshotItems),
            firstVisibleItemScrollOffset = 0,
        )
        val fromTopView = composeViewInActivity { rows(itemCount = { snapshotItems }, state = stateFromTop) }
        val withInitialView = composeViewInActivity { rows(itemCount = { snapshotItems }, state = stateWithInitial) }
        val fromTopMeasured = fromTopView.layoutUntil(400, 800) { stateFromTop.layoutInfo.totalItemsCount == snapshotItems }
        val withInitialMeasured =
            withInitialView.layoutUntil(400, 800) { stateWithInitial.layoutInfo.totalItemsCount == snapshotItems }
        assertTrue(
            "两份列表都真的被测量过（有界等待：不给初值=${waited(fromTopMeasured)}、" +
                "给初值=${waited(withInitialMeasured)}；超时未落地 ⇒ 下面两条断言只是构造默认值自比，等于没测到机制）",
            fromTopMeasured && withInitialMeasured,
        )

        assertEquals("不给初值（现状）：首帧组在顶部", 0, stateFromTop.firstVisibleItemIndex)
        assertEquals("给了初值：首帧就在记录的位置", 600, stateWithInitial.firstVisibleItemIndex)
    }

    /**
     * 恢复到记录那一项时，**该项封面的顶边贴视口上沿**（落点曾比该项上沿低 12dp）。
     *
     * 真尺寸量（真密度 xxhdpi + 真宽高，判的就是 dp→px 之后那 12dp）：视口上沿在 `Lazy` 的**内容坐标**里是
     * `viewportStartOffset`，而顶部内容留白被 `Lazy` 算在视口**之外** ⇒ 「该项顶边在视口上沿之下多少」=
     * `item.offset - viewportStartOffset`。改动前 = 36px（= 12dp）且上一行还在可见区里（正是设备上的形态）；
     * 本用例要求 = 0（±1px 容差）。
     *
     * **判别力**：把 [restoredLandingOffsetPx] 换成 0（= 改动前的口径）本用例即红（36px）；
     * 初值那段靠 [initialScrollItemIndex] 与 [restoredLandingOffsetPx] 两个纯函数拼出来，与 `BrowserScreen` 同一口径。
     */
    @Test
    @Config(sdk = [34], qualifiers = LANDING_QUALIFIERS)
    fun `网格档恢复到记录那一项 封面顶边贴视口上沿`() {
        val recorded = 26
        val items = 400
        val index = initialScrollItemIndex(recordedIndex = recorded, firstFrameItemCount = items)
        val state = LazyGridState(
            firstVisibleItemIndex = index,
            firstVisibleItemScrollOffset = restoredLandingOffsetPx(index, landingTopContentPaddingPx()),
        )
        val view = composeViewInActivity { restoreGrid(itemCount = { items }, state = state) }
        val landed = view.layoutUntil(LANDING_WIDTH_PX, LANDING_HEIGHT_PX) {
            state.layoutInfo.visibleItemsInfo.any { it.index == recorded }
        }
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == recorded }
        assertTrue(
            "记录那一项（$recorded）要在可见区里（超时未落地 ⇒ 下面的像素差等于没测到；可见=" +
                "${info.visibleItemsInfo.joinToString(",") { it.index.toString() }}）",
            landed && item != null,
        )
        assertEquals(
            "该项顶边与视口上沿的像素差（记录=$recorded、可见首项=${info.visibleItemsInfo.first().index}）",
            0f,
            (item!!.offset.y - info.viewportStartOffset).toFloat(),
            1f,
        )
    }

    /**
     * 列表档同口径（两档落点都落到偏移 0）：`LazyColumn` 不设 `contentPadding` ⇒ 顶部内容留白为 0
     * （[restoredLandingOffsetPx] 的入参传 0），现状本来就落在偏移 0，本用例把它钉住（网格档修完不得把这档带偏）。
     */
    @Test
    @Config(sdk = [34], qualifiers = LANDING_QUALIFIERS)
    fun `列表档恢复到记录那一项 封面顶边贴视口上沿`() {
        val recorded = 26
        val items = 400
        val index = initialScrollItemIndex(recordedIndex = recorded, firstFrameItemCount = items)
        val state = LazyListState(
            firstVisibleItemIndex = index,
            firstVisibleItemScrollOffset = restoredLandingOffsetPx(index, topContentPaddingPx = 0),
        )
        val view = composeViewInActivity { rows(itemCount = { items }, state = state) }
        val landed = view.layoutUntil(LANDING_WIDTH_PX, LANDING_HEIGHT_PX) {
            state.layoutInfo.visibleItemsInfo.any { it.index == recorded }
        }
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == recorded }
        assertTrue(
            "记录那一项（$recorded）要在可见区里（超时未落地 ⇒ 下面的像素差等于没测到；可见=" +
                "${info.visibleItemsInfo.joinToString(",") { it.index.toString() }}）",
            landed && item != null,
        )
        assertEquals(
            "该项顶边与视口上沿的像素差（记录=$recorded、可见首项=${info.visibleItemsInfo.first().index}）",
            0f,
            (item!!.offset - info.viewportStartOffset).toFloat(),
            1f,
        )
    }

    /**
     * 首屏链「取够页后把位置放回去」那条路径（`browseRestore phase=apply target != none`）同口径：
     * 直取档首帧只含第 0 页 ⇒ 初值被夹到短帧末项，取够页后由 [scrollRestoreTarget] 把位置请求回去。
     * 那一次请求只带索引时，落点同样在内容留白之下（网格档 36px）——本用例要求 = 0。
     *
     * **两段驱动各锁一半**（测量口径，不是风格偏好）：
     * ① `requestScrollToItem` 是**生产那条路**（`BrowserScreen` 的 `requestScrollToItem`）⇒ 锁「请求出去的
     *   索引与偏移」，故那条断言直接拿状态里的值；
     * ② 裸测量驱动（`layoutOnce`）下 `requestScrollToItem` **不会**把 `visibleItemsInfo` 推到新位置（探针读数：
     *   状态读到的已经是 `600/36`，可见项仍停在夹取后那一行）⇒ 量像素改用同一套「索引 + 偏移」语义的挂起入口
     *   `scrollToItem` 驱动帧（它能落到 `600/36` 并刷新可见区）。两段传的是**同一个** [restoredLandingOffsetPx]
     *   结果，因此偏移口径被两段一起锁住。
     */
    @Test
    @Config(sdk = [34], qualifiers = LANDING_QUALIFIERS)
    fun `首屏链取够页后把位置放回 落点同样贴视口上沿`() {
        val recorded = 600
        val shortFrame = 200
        val full = 800
        val count = mutableStateOf(shortFrame)
        val paddingPx = landingTopContentPaddingPx()
        val initial = initialScrollItemIndex(recordedIndex = recorded, firstFrameItemCount = shortFrame)
        val state = LazyGridState(
            firstVisibleItemIndex = initial,
            firstVisibleItemScrollOffset = restoredLandingOffsetPx(initial, paddingPx),
        )
        val landRequest = mutableStateOf(0)
        val framesDriven = mutableStateOf(false)
        val view = composeViewInActivity {
            restoreGrid(itemCount = { count.value }, state = state)
            LaunchedEffect(landRequest.value) {
                if (landRequest.value != 0) {
                    state.scrollToItem(landRequest.value, restoredLandingOffsetPx(landRequest.value, paddingPx))
                    framesDriven.value = true
                }
            }
        }
        assertTrue(
            "首帧按短列表（$shortFrame）测量过（超时未落地 ⇒ 下面等于没测到机制）",
            view.layoutUntil(LANDING_WIDTH_PX, LANDING_HEIGHT_PX) { state.layoutInfo.totalItemsCount == shortFrame },
        )
        count.value = full
        assertTrue(
            "列表已按取够页后的长度（$full）重新测量",
            view.layoutUntil(LANDING_WIDTH_PX, LANDING_HEIGHT_PX) { state.layoutInfo.totalItemsCount == full },
        )
        val target = scrollRestoreTarget(
            restoredIndex = recorded,
            currentIndex = state.firstVisibleItemIndex,
            loadedItems = full,
        )
        assertNotNull("短帧夹小的那一读要触发放回（判据：当前位置退到恢复索引之前）", target)
        val offsetToRestore = restoredLandingOffsetPx(target!!, paddingPx)

        // ① 生产那条路：请求出去的索引与偏移（改动前偏移是 0 ⇒ 这条即红）
        state.requestScrollToItem(target, offsetToRestore)
        val requested = view.layoutUntil(LANDING_WIDTH_PX, LANDING_HEIGHT_PX) { state.firstVisibleItemIndex == target }
        assertEquals(
            "放回请求要带上网格档顶部那段内容留白（= 12dp 的真 px 值；超时未落地=${waited(requested)}）",
            paddingPx,
            state.firstVisibleItemScrollOffset,
        )

        // ② 同一套语义的挂起入口驱动帧，量像素落点
        landRequest.value = target
        val landed = view.layoutUntil(LANDING_WIDTH_PX, LANDING_HEIGHT_PX) { framesDriven.value }
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == recorded }
        assertTrue(
            "帧驱动到落地（${waited(landed)}）且记录那一项（$recorded）在可见区里（可见=" +
                "${info.visibleItemsInfo.joinToString(",") { it.index.toString() }}）",
            landed && item != null,
        )
        assertEquals(
            "放回后的落点：该项顶边与视口上沿的像素差（可见首项=${info.visibleItemsInfo.first().index}）",
            0f,
            (item!!.offset.y - info.viewportStartOffset).toFloat(),
            1f,
        )
    }

    @Test
    fun `下拉更新换代后重读当下位置 不沿用旧索引`() {
        // 持有者活过 `reloadTick`（下拉更新 / 重试换 pager 而 scrollResetKey 不变），
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
    fun `恢复打点的四行格式与字段口径`() {
        // 字段名与顺序由这一条用例钉住——设备读数时才有稳定的口径可依，
        // 改名/漏发字段/换顺序都会在这里当场红。
        assertEquals(
            "browseRestore phase=read container=smb://c/Artist saved=600 now=184 sent=600 gen=0",
            browseRestoreReadLine(container = "smb://c/Artist", saved = 600, now = 184, sent = 600, generation = 0),
        )
        // 根层没有 container：与 `listEntries` 行同一个写法（`<root>`），两根线才能按同一个键对齐
        assertEquals(
            "browseRestore phase=read container=<root> saved=0 now=0 sent=0 gen=1",
            browseRestoreReadLine(container = null, saved = 0, now = 0, sent = 0, generation = 1),
        )
        assertEquals(
            "browseRestore phase=apply container=smb://c/Artist gen=0 restored=600 now=184 loaded=800 target=600",
            browseRestoreApplyLine(container = "smb://c/Artist", generation = 0, restored = 600, now = 184, loaded = 800, target = 600),
        )
        // 判据没成立：`target=none`。这一行本身就是证据（恢复机制跑到了、但决定不放），不能省成「没有这行」。
        assertEquals(
            "browseRestore phase=apply container=<root> gen=2 restored=0 now=0 loaded=0 target=none",
            browseRestoreApplyLine(container = null, generation = 2, restored = 0, now = 0, loaded = 0, target = null),
        )
        // **离场那一刻记下的**那个值（`onDispose`，事件时刻、没经过短帧）。它是「读到」那一行的
        // `saved` 的来源——只有它才能把「位置在离场时就已经没了」与「交回时丢的」分开。
        assertEquals(
            "browseRestore phase=leave container=smb://c/Artist index=600 mode=list",
            browseRestoreLeaveLine(container = "smb://c/Artist", index = 600, isGrid = false),
        )
        assertEquals(
            "browseRestore phase=leave container=<root> index=0 mode=grid",
            browseRestoreLeaveLine(container = null, index = 0, isGrid = true),
        )
        // 切后台写点（ON_STOP）交给 leave 的那个值：离屏写点之外唯一另一个写盘路径，
        // 没有这行时「无回调退出的落盘行为」在日志里是盲区
        assertEquals(
            "browseRestore phase=stop container=smb://c/Artist index=600 mode=list",
            browseRestoreStopLine(container = "smb://c/Artist", index = 600, isGrid = false),
        )
        assertEquals(
            "browseRestore phase=stop container=<root> index=0 mode=grid",
            browseRestoreStopLine(container = null, index = 0, isGrid = true),
        )
    }

    @Test
    fun `首帧被夹小的初值 取够页之后仍把记录那一条放回`() {
        // 落点口径（2026-09-29）：落点 = **最终列表**里那本书的上沿顶在视口上沿（偏移 0，记录仍是项索引）。
        // 两半都保住：首帧短时初值照旧先夹（不给越界初值），取够页之后由 [scrollRestoreTarget] 把记录那一条放回去。
        //
        // 本用例是**口径的守卫**（不是修复的判别力）：两半各自已有用例（`首帧短于记录时 min 夹到末项 不给越界初值` +
        // `短帧夹过之后取够页才放回恢复索引`），这里把「夹完之后必须放回」按同一次序列钉在一起。
        // 判别力落在第一条断言上：去掉 `initialScrollItemIndex` 的 `min` 时 `clipped` 变 600 ⇒ 本用例红；
        // 第二条（放回判据）在这个输入下是**回归守卫**（去掉 `currentIndex < restoredIndex` 仍返回 600）。
        val recorded = 600
        val clipped = initialScrollItemIndex(recordedIndex = recorded, firstFrameItemCount = 200)
        assertEquals("首帧短 ⇒ 初值先夹到首帧末项（不越界）", 199, clipped)
        assertEquals(
            "取够页之后把记录那一条放回（落点 = 那本书的上沿）",
            recorded,
            scrollRestoreTarget(restoredIndex = recorded, currentIndex = clipped, loadedItems = 800),
        )
    }

    @Test
    fun `短帧把恢复索引夹到末尾 显式放回才回到原处`() {
        val count = mutableStateOf(200)
        // 与界面同源：恢复的滚动索引来自 rememberSaveable 交回的滚动状态（这里直接以 600 构造）
        val state = LazyListState(firstVisibleItemIndex = 600, firstVisibleItemScrollOffset = 0)
        val view = composeViewInActivity {
            rows(itemCount = { count.value }, state = state)
        }
        view.layoutOnce(400, 800)
        val afterShortFrame = state.firstVisibleItemIndex
        assertTrue(
            "首帧那份短列表把恢复索引夹到已加载末尾（实测 600 → 184）：本修法要修的正是这一下",
            afterShortFrame < 600,
        )

        count.value = 800
        // 有界等待到「800 条真的被测量过」：若这一轮没轮到这次测量，`state` 还停在短帧那一轮，
        // 下面的断言就是拿同一份快照自比 ⇒ 恒真、机制没测到。
        val grewSettled = view.layoutUntil(400, 800) { state.layoutInfo.totalItemsCount == 800 }
        assertTrue(
            "列表已按 800 条重新测量（超时未落地 ⇒ 下面「涨长不会自己回去」等于什么都没测）",
            grewSettled,
        )
        assertEquals("列表涨长不会自己回到原索引（只有显式放回才行）", afterShortFrame, state.firstVisibleItemIndex)

        state.requestScrollToItem(600)
        view.layoutOnce(400, 800)
        assertEquals("取够页后把位置放回去：落在恢复索引", 600, state.firstVisibleItemIndex)
    }

    /**
     * 会话快照改到**构造期**落帧之后，浏览页首帧就是那份短列表；
     * 而恢复索引的读在 `LaunchedEffect(pager, reverse)` 里 —— effect 体在本帧 composition + layout **之后**
     * 才跑 ⇒ 读到的已是被夹过的索引（本例 [effectRead] < 600）。所以「在任何一帧列表上屏之前读一次」
     * 那条不变式必须换个读点：在**离开这一屏的那一刻**（`onDispose`，事件时刻、还没经过短帧）记下索引，
     * 取它与 effect 读的**较大者**（夹只会把索引变小 ⇒ 较大的那个就是未夹的）。
     *
     * 本用例同时钉住两件事：① 机制（短帧先上屏 ⇒ effect 读被夹）；② [unclippedRestoredScrollIndex]
     * 的判据（取回未夹的那个）。拿掉修复（只留 effect 读）⇒ 位置丢。
     *
     * （原先推一轮 [`layoutOnce`] 断言，若 `LaunchedEffect` 那一轮没跑过，`effectRead` 还是初值 `-1`，
     * `-1 < 600` 与 `max(600, -1) == 600` 都恒真 ⇒ 「机制」那一半假绿。改成**有界等待**（[layoutUntil]）到
     * effect 跑过，并把「effect 跑过」本身写成会失败的断言；两条原判据与期望值不动。）
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
            rows(itemCount = { count.value }, state = state)
            LaunchedEffect(Unit) {
                effectRead = restoredScrollItemIndex(state.firstVisibleItemIndex, gridIndex = 0, columns = null)
            }
        }
        // 有界等待到 effect 真的跑过（见 [layoutUntil]）：一轮「测量 → 布局 → idle」不保证 effect 体已排上队。
        // 不等到就跑断言时 `effectRead` 还是初值 `-1`，下面两条判据都会恒真 ⇒ 假绿。
        val effectRan = view.layoutUntil(400, 800) { effectRead != -1 }
        assertTrue(
            "LaunchedEffect 在等待窗口内跑过（超时未落地 ⇒ 下面两条判据都会恒真，等于没测到机制）",
            effectRan,
        )

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
     * 离屏那一刻记下的索引必须按**离场那一刻的档位**算——本用例守护**生产接线**。
     *
     * 生产那条接线收在 [BrowseScrollLeaveEffect]（`BrowserScreen` 调的它就是它）：[view] 是不可变枚举，而
     * 切档位**不会**重建两档滚动状态（那两个 key 只有复位键）⇒ 捕值（把 `view` 捕进效应闭包）会去读
     * **另一个容器**的索引：网格档进屏（600）→ 切列表档 → 滚到 20 → 离场，记下的是 gridState 的 600，
     * 再经 [unclippedRestoredScrollIndex] 的「取较大者」抬成「未夹的值」⇒ 返回后从错位置恢复、还多发请求。
     *
     * **判别力**：把 [BrowseScrollLeaveEffect] 里的 `viewNow` 换成直接捕 `view`，本用例第一条断言即红
     *（记 600 而不是 20）——接线写错就会红，不再是「把 `BrowserScreen` 回退成捕值仍全绿」。
     * 第二条断言是同一份组合里的**反例对照**（[captureIndexOnLeaveByValue]）：它证明这条断言真的能分辨
     * 两种写法，不是恒真。
     *
     * （本用例原先假设「一轮 [`layoutOnce`] 就够」，全量跑时偶发红。两次推进改成**有界等待**——
     * 切档位等「子作用域已按列表档组合过」、离场等「两个回调都落地」；判据与期望值不动，超时照样失败。）
     */
    @Test
    fun `离屏记下的索引按当下档位算 生产接线写错就会红`() {
        // 两档各给一个可区分的索引：网格档 600（进屏时的档位）、列表档 20（切档位后滚到的位置）。
        // 两个滚动状态都只构造、不组合进列表（本用例钉的是「读哪一个容器」，不涉及测量与夹索引）。
        val gridState = LazyGridState(firstVisibleItemIndex = 600, firstVisibleItemScrollOffset = 0)
        val listState = LazyListState(firstVisibleItemIndex = 20, firstVisibleItemScrollOffset = 0)
        // 本用例只判「读哪一个容器」：位置模块那份给一个用不着的（内存存储 + 空链，目录不存在）
        val position = BrowseScrollPosition(store = MemoryStorage(), browseChain = { emptyList() })
        val layer = BrowseScrollLayer(connId = 7L, containerId = null)
        val generation = BrowseScrollResetKey(
            mode = SortMode.NAME, direction = SortDirection.FORWARD, revision = 0, staleIds = null,
        )
        val mode = mutableStateOf(ViewMode.GRID_3)
        val alive = mutableStateOf(true)
        val recordedByUpdated = mutableStateOf(-1)
        val recordedByCapturedValue = mutableStateOf(-1)
        // 「子作用域刚按某档位组合过」的观测点（[SideEffect] 在组合应用之后发布），供切档位那步的有界等待用：
        // 它跑过 = [BrowseScrollLeaveEffect] 里 `rememberUpdatedState` 的 `viewNow` 已经是这个档位。
        val composedByUpdated = mutableStateOf<ViewMode?>(null)

        val view = composeViewInActivity {
            val modeNow = mode.value
            if (alive.value) {
                SideEffect { composedByUpdated.value = modeNow }
                BrowseScrollLeaveEffect(
                    view = modeNow,
                    listState = listState,
                    gridState = gridState,
                    position = position,
                    layer = layer,
                    resetKey = generation,
                    onLeave = { recordedByUpdated.value = it },
                )
                captureIndexOnLeaveByValue(modeNow, listState, gridState) { recordedByCapturedValue.value = it }
            }
        }
        view.layoutOnce(400, 800)

        // 切档位（网格 → 列表）：效果不重建（key 只有两个滚动状态）。
        mode.value = ViewMode.LIST
        // 先等到「子作用域已按列表档组合过」再离场：切档位与离场若并到**同一轮**重组，子作用域那次不重跑，
        // `rememberUpdatedState` 里仍是旧档位 ⇒ 读当下档位的写法也只剩旧档位（探针读到 600）。
        val composedSettled = view.layoutUntil(400, 800) { composedByUpdated.value == ViewMode.LIST }

        // 离场：整个组合拆掉 ⇒ onDispose 跑。
        // 回调落在 idle 段、不保证一轮内到（见 [layoutUntil]）：有界等待到两个回调都落地再断言。
        alive.value = false
        val leaveSettled = view.layoutUntil(400, 800) { recordedByUpdated.value != -1 && recordedByCapturedValue.value != -1 }

        // 两处等待的成败写进断言消息：超时要在日志里看得见，但**不遮住** `-1` / `600` 两个值形态。
        // （名字用 [waitNote] 而不是 `waited`：后者与本文件顶层的 [waited] 函数同名，且就在这个表达式里被调用，读起来会以为是变量。）
        val waitNote = "（有界等待：档位落地=${waited(composedSettled)}、离场回调=${waited(leaveSettled)}）"
        assertEquals("生产接线：记下的是**当下**档位（列表档）的索引$waitNote", 20, recordedByUpdated.value)
        assertEquals("捕值的反例：记下的是创建效应那一刻（网格档）的索引 —— 接线回退成捕值的形态$waitNote", 600, recordedByCapturedValue.value)
    }

    /**
     * 离屏写点还有**第二步**：结束这一层的进屏会话——本用例守护**生产接线**
     *（与上一条同一个接缝 [BrowseScrollLeaveEffect]）。
     *
     * 为什么这一步要单独守：接缝若只把「算出的索引」暴露给用例，模块那两句调用就在测试看不见的地方——删掉任一句
     * 全部用例照绿，而行为静默退回「进屏会话永不作废」：「离开落地层、再从上一级进来 ⇒ 回顶部」失效
     *（那个失效形态的用例都在模块层，测不到接线）。
     *
     * **判别力**：把 [BrowseScrollLeaveEffect] 里的 `endEntrySession` 那句去掉，第二条断言读到启动那条 600
     *（旧会话被复用）；把 `leave` 那句去掉，第一条断言（离屏那一刻真的记了一次）即红。
     */
    @Test
    fun `离屏结束这一屏的进屏会话 生产接线写错就会红`() {
        val layer = BrowseScrollLayer(connId = 7L, containerId = "smb://c/目录")
        val generation = BrowseScrollResetKey(
            mode = SortMode.NAME, direction = SortDirection.FORWARD, revision = 0, staleIds = null,
        )
        // 盘上那条一次性记录 = 重启落在这一层 ⇒ 这一屏的进屏会话收下它（600）。假链留空：本用例只判会话寿命。
        val storage = MemoryStorage(BrowseScrollPositionRecord(layer, index = 600))
        val position = BrowseScrollPosition(store = storage, browseChain = { emptyList() })
        position.startupLanding(layer)
        assertEquals(
            "重启落在这一层：这一屏的进屏会话吃下盘上那条 600",
            600,
            position.enter(layer, generation, firstFrameItemCount = 800).index,
        )

        // 真离屏：组合 [BrowseScrollLeaveEffect]（生产同一个它）后拆掉组合 ⇒ onDispose 跑
        val listState = LazyListState(firstVisibleItemIndex = 20, firstVisibleItemScrollOffset = 0)
        val gridState = LazyGridState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
        val alive = mutableStateOf(true)
        val left = mutableStateOf(-1)
        val view = composeViewInActivity {
            if (alive.value) {
                BrowseScrollLeaveEffect(
                    view = ViewMode.LIST,
                    listState = listState,
                    gridState = gridState,
                    position = position,
                    layer = layer,
                    resetKey = generation,
                    onLeave = { left.value = it },
                )
            }
        }
        view.layoutOnce(400, 800)
        alive.value = false
        val leaveSettled = view.layoutUntil(400, 800) { left.value != -1 }
        assertTrue(
            "离屏回调落地（超时未落地 ⇒ 下面两步都没测到；有界等待=${waited(leaveSettled)}）",
            leaveSettled,
        )

        assertEquals(
            "离屏那一刻真的记了一次（`leave` 写进注入的存储）",
            20,
            storage.record?.index,
        )
        assertEquals(
            "这一屏结束 ⇒ 再进屏是新的一屏（重新问一次启动落地，不再吃启动那条 600），读到本代次刚记下的 20",
            20,
            position.enter(layer, generation, firstFrameItemCount = 800).index,
        )
    }

    /**
     * 切后台（生命周期 `ON_STOP`）那个写点必须**记一次**、且**不结束**这一屏的进屏会话——本用例守护生产接线
     * [BrowseScrollOnStopEffect]（`BrowserScreen` 调的它就是它）。
     *
     * 为什么这一步要单独守：它与 [BrowseScrollLeaveEffect] 同源（同一个 [BrowseScrollPosition.leave]），却写在
     * Compose 接线里——接缝不把它暴露给用例时，删掉那句 `leave` 全部用例照绿，而「后台被系统杀掉后重启停在原地」
     * 静默失效。与离屏那条的区别正相反：切后台没有拆掉这一屏 ⇒ 进屏会话要活着（回前台照旧吃启动那条），
     * 所以**不能**跟着 `endEntrySession`。
     *
     * **判别力**：把 [BrowseScrollOnStopEffect] 里那句 `position.leave(...)` 去掉，第一条断言读到进屏时写下的 600
     *（不是切后台那一刻的 700）；若往里加 `endEntrySession`，第二条断言读到 700（会话被提前作废）——离屏那条路的
     * `endEntrySession` 动不到本用例（两条路真能分辨）。
     */
    @Test
    fun `切后台记一次且不结束进屏会话 生产接线写错就会红`() {
        val layer = BrowseScrollLayer(connId = 7L, containerId = "smb://c/目录")
        val generation = BrowseScrollResetKey(
            mode = SortMode.NAME, direction = SortDirection.FORWARD, revision = 0, staleIds = null,
        )
        // 盘上那条一次性记录 = 重启落在这一层 ⇒ 这一屏的进屏会话收下它（600）。假链留空：本用例只判会话寿命。
        val storage = MemoryStorage(BrowseScrollPositionRecord(layer, index = 600))
        val position = BrowseScrollPosition(store = storage, browseChain = { emptyList() })
        position.startupLanding(layer)
        assertEquals(
            "重启落在这一层：这一屏的进屏会话吃下盘上那条 600",
            600,
            position.enter(layer, generation, firstFrameItemCount = 800).index,
        )

        // 假生命周期 owner：先推到 RESUMED（`composeViewInActivity` 默认给的是 activity 自己的 lifecycle，
        // 派不动它的 `ON_STOP` ⇒ 组合期把 [LocalLifecycleOwner] 换成它）
        val owner = TestLifecycleOwner()
        listOf(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME).forEach {
            owner.registry.handleLifecycleEvent(it)
        }

        val view = composeViewInActivity {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                BrowseScrollOnStopEffect(
                    position = position,
                    layer = layer,
                    resetKey = generation,
                    currentIndex = { 700 },
                    isGrid = { false },
                )
            }
        }
        // 先等到观察者已登记再派发：派发早于登记就白派一次（生命周期事件不补发）
        val registered = view.layoutUntil(400, 800) { owner.registry.observerCount > 0 }
        assertTrue(
            "ON_STOP 观察者已登记（超时未登记 ⇒ 下面那个写点根本没测到；有界等待=${waited(registered)}）",
            registered,
        )
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

        assertEquals(
            "切后台那一刻真的记了一次（`leave` 写进注入的存储）= 用户最后看到的位置 700",
            700,
            storage.record?.index,
        )
        assertEquals(
            "切后台没有结束这一屏：回前台照旧吃启动那条 600（不是刚写下的 700）",
            600,
            position.enter(layer, generation, firstFrameItemCount = 800).index,
        )
    }

    /**
     * 切后台写点必须用**当下**那一代的复位键——本用例守护生产接线 [BrowseScrollOnStopEffect] 的效果键。
     *
     * 复现场景：换排序（含重选当前排序）使 `scrollResetKey` 换代、两档滚动状态与这一屏一起来新的一代；若观察者不跟着
     * 重登记，闭包里的 `resetKey` 停在旧代次 ⇒ `leave` 用过时的键组键，`record` 的「代次已过」判据早退，紧随的无条件
     * 落盘把该键的生效值（已被 `beginGeneration` 清掉 ⇒ 0）写进盘 ⇒「换排序 → 滚到中段 → 切后台被杀 → 重启」落回顶部。
     *
     * **判别力**：把接缝的效果键从 `(lifecycleOwner, resetKey)` 退回 `(lifecycleOwner)`，本用例红——新代次读回 0
     *（stale 代次的落盘把 700 丢了）。
     */
    @Test
    fun `切后台落盘按当下代次 生产接线写错就会红`() {
        val layer = BrowseScrollLayer(connId = 7L, containerId = "smb://c/目录")
        fun gen(revision: Int) = BrowseScrollResetKey(
            mode = SortMode.NAME, direction = SortDirection.FORWARD, revision = revision, staleIds = null,
        )
        val position = BrowseScrollPosition(store = MemoryStorage(), browseChain = { emptyList() })
        position.startupLanding(layer)

        // 假生命周期 owner：先推到 RESUMED
        val owner = TestLifecycleOwner()
        listOf(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME).forEach {
            owner.registry.handleLifecycleEvent(it)
        }

        // 组合时是第 0 代；随后换排序 ⇒ 第 1 代（效果键若不含 resetKey，观察者就不重登记）
        val generation = mutableStateOf(gen(0))
        val composedGeneration = mutableStateOf<BrowseScrollResetKey?>(null)
        val view = composeViewInActivity {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                // 观测点：组合已按这一代应用过（观察者的重登记同在这一轮 apply）
                SideEffect { composedGeneration.value = generation.value }
                BrowseScrollOnStopEffect(
                    position = position,
                    layer = layer,
                    resetKey = generation.value,
                    currentIndex = { 700 },
                    isGrid = { false },
                )
            }
        }
        view.layoutOnce(400, 800)
        generation.value = gen(1)
        val recomposed = view.layoutUntil(400, 800) { composedGeneration.value == gen(1) }
        assertTrue(
            "组合已按第 1 代重跑（超时 ⇒ 上面那轮没等到新代次，下面测不到效果键；有界等待=${waited(recomposed)}）",
            recomposed,
        )

        // 换排序后这一屏是新的一代：登记第 1 代（丢掉第 0 代的记录）
        position.enter(layer, gen(1), firstFrameItemCount = 800, readNow = 0)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

        // 落盘只有一条（无内存存储可读）：直接量第 1 代读回的值，能同时分辨「记没记」与「记在哪一代」
        assertEquals(
            "切后台用**当下**代次（第 1 代）落盘：第 1 代读回当下位置 700（不是换代时被清掉的旧值 0）",
            700,
            position.enter(layer, gen(1), firstFrameItemCount = 800, readNow = 0).index,
        )
    }

    /**
     * 切后台写点必须**打一行** `phase=stop`：它是离屏写点之外唯一另一个落盘路径，没有这行时
     * 「无回调退出的落盘行为」在诊断日志里是盲区（离屏那路的 `phase=leave` 看不到它）。
     *
     * **判别力**：把接缝里的打点那句去掉，本条红（缓冲里没有 stop 行）；打出的 index 不是交给 [currentIndex]
     * 的那个值时也红。
     */
    @Test
    fun `切后台写点打一行 stop 生产接线写错就会红`() {
        val layer = BrowseScrollLayer(connId = 7L, containerId = "smb://c/目录")
        val generation = BrowseScrollResetKey(
            mode = SortMode.NAME, direction = SortDirection.FORWARD, revision = 0, staleIds = null,
        )
        val position = BrowseScrollPosition(store = MemoryStorage(), browseChain = { emptyList() })

        val owner = TestLifecycleOwner()
        listOf(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME).forEach {
            owner.registry.handleLifecycleEvent(it)
        }

        val lines = PerfTiming.newRecordedLinesForTest()
        PerfTiming.forcedForTest = true
        PerfTiming.recordedLinesForTest = lines
        try {
            val view = composeViewInActivity {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    BrowseScrollOnStopEffect(
                        position = position,
                        layer = layer,
                        resetKey = generation,
                        currentIndex = { 700 },
                        isGrid = { true },
                    )
                }
            }
            val registered = view.layoutUntil(400, 800) { owner.registry.observerCount > 0 }
            assertTrue(
                "ON_STOP 观察者已登记（超时未登记 ⇒ 下面那个写点根本没测到；有界等待=${waited(registered)}）",
                registered,
            )
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

            assertTrue(
                "切后台写点打了 stop 行（实际：${lines.filter { it.contains("browseRestore") }}）",
                lines.any { it == "browseRestore phase=stop container=smb://c/目录 index=700 mode=grid" },
            )
        } finally {
            PerfTiming.forcedForTest = null
            PerfTiming.recordedLinesForTest = null
        }
    }

    // --- 换排序 → 直接退出（无离屏回调的退出）---

    /**
     * 换排序后直接退出（无离屏回调，只有切后台那一写点）：落盘必须是新排序下的当下位置。
     *
     * 复现场景（维护者现场，每次必现）：上一段会话名称排序停在底部（盘上那条 600）→ 本段会话进屏恢复到底部
     * → 换排序（跳顶）→ 滚到 20 → 直接退出 → 重启恢复到 600 而不是 20。
     *
     * 生产形状的三个要素都在：组合期进屏（换代登记在这里）、离屏写点（效果键 = 两档滚动状态，
     * 换代重建 ⇒ 旧状态的 600 在换代那一次重组里离场）、切后台写点（效果键含复位代次）。
     */
    @Test
    fun `换排序后直接退出 落盘当下位置 生产接线写错就会红`() {
        val layer = BrowseScrollLayer(connId = 7L, containerId = "smb://c/目录")
        fun gen(revision: Int) = BrowseScrollResetKey(
            mode = SortMode.NAME, direction = SortDirection.FORWARD, revision = revision, staleIds = null,
        )
        // 盘上那条 600 = 上一段会话（名称排序）退出时留下的
        val storage = MemoryStorage(BrowseScrollPositionRecord(layer, index = 600))
        val position = BrowseScrollPosition(store = storage, browseChain = { emptyList() })
        position.startupLanding(layer)
        assertEquals(
            "本段会话进屏：吃下盘上那条 600（恢复到底部）",
            600,
            position.enter(layer, gen(0), firstFrameItemCount = 800, readNow = 600).restoredIndexNow,
        )

        // 假生命周期 owner：先推到 RESUMED（直接退出的那条路只有 ON_STOP，没有离屏回调）
        val owner = TestLifecycleOwner()
        listOf(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME).forEach {
            owner.registry.handleLifecycleEvent(it)
        }

        val resetKey = mutableStateOf(gen(0))
        // 离场读数口：换排序前 = 600（底部）、换排序后 = 20（新排序下滑到的位置）。
        // 生产里它是两档滚动状态的当下读数，而滚动状态按复位键重建 ⇒ 用 remember(resetKey) 的状态模拟：
        // 换代那一次重组里旧状态连同它的 600 一起被拆掉（旧写点读到的就是它）
        val listIndex = mutableStateOf(600)
        val composedGeneration = mutableStateOf<BrowseScrollResetKey?>(null)
        val view = composeViewInActivity {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                // 组合期进屏（生产 BrowserScreen 组合体里就是这一句）：换代登记在这里发生
                position.enter(layer, resetKey.value, firstFrameItemCount = 800)
                SideEffect { composedGeneration.value = resetKey.value }
                val listState = remember(resetKey.value) {
                    LazyListState(firstVisibleItemIndex = listIndex.value, firstVisibleItemScrollOffset = 0)
                }
                val gridState = remember(resetKey.value) { LazyGridState(0, 0) }
                BrowseScrollLeaveEffect(
                    view = ViewMode.LIST,
                    listState = listState,
                    gridState = gridState,
                    position = position,
                    layer = layer,
                    resetKey = resetKey.value,
                    onLeave = { },
                )
                BrowseScrollOnStopEffect(
                    position = position,
                    layer = layer,
                    resetKey = resetKey.value,
                    currentIndex = { listIndex.value },
                    isGrid = { false },
                )
            }
        }
        view.layoutOnce(400, 800)

        // 换排序：第 1 代（跳顶），随后滚到 20
        resetKey.value = gen(1)
        listIndex.value = 20
        val recomposed = view.layoutUntil(400, 800) { composedGeneration.value == gen(1) }
        assertTrue(
            "组合已按第 1 代重跑（超时 ⇒ 换代那轮没等到，下面测不到；有界等待=${waited(recomposed)}）",
            recomposed,
        )

        // 直接退出（无离屏回调）：只有 ON_STOP 那一写点
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

        assertEquals(
            "直接退出落盘的是新排序下的当下位置 20（不是换排序前的 600，也不是换代时被清掉的 0）",
            20,
            storage.record?.index,
        )
    }

    // --- 滑条拖动落位（落点 = 行 + 行内偏移，且与停位共用「吃掉顶部内容留白」的口径） ---

    /**
     * 拖动落点的纵向偏移 = 停位那一段留白 + 行内偏移：留白段与停位**共用** [restoredLandingOffsetPx]
     *（含它的边界：索引 0 不吃、列表档恒 0）。
     *
     * 判别力：把留白段去掉（`quickScrollBarLandingOffsetPx` 只返回 `rowOffsetPx`）时，本条第一条断言与
     * `网格档拖动落点 整行贴视口上沿 行内偏移把该行再抬高` 都红。
     */
    @Test
    fun `拖动落位带顶部留白与行内偏移`() {
        val paddingPx = landingTopContentPaddingPx()
        assertEquals(
            "索引 ≥ 1：留白 + 行内偏移",
            paddingPx + 120,
            quickScrollBarLandingOffsetPx(index = 26, topContentPaddingPx = paddingPx, rowOffsetPx = 120),
        )
        assertEquals(
            "索引 0：不吃留白（那 12dp 是「停在顶部」的排版边距）",
            120,
            quickScrollBarLandingOffsetPx(index = 0, topContentPaddingPx = paddingPx, rowOffsetPx = 120),
        )
        assertEquals(
            "列表档没有顶部内容留白 ⇒ 偏移就是行内偏移",
            120,
            quickScrollBarLandingOffsetPx(index = 26, topContentPaddingPx = 0, rowOffsetPx = 120),
        )
    }

    /**
     * 像素级（与上面停位那两条同一判法）：用**拖动落点**落地 —— 整行时目标行上沿贴视口上沿（上方不露出留白与
     * 上一行的任何像素），行内偏移不为 0 时该行被再抬高同样多；**索引 0 不吃留白**（那 12dp 是「停在顶部」
     * 的排版边距）。
     *
     * 驱动与停位那两条相同：状态按生产的落位算式 [quickScrollBarLandingOffsetPx] 构造初值后量一轮。
     * **不走挂起的 `scrollToItem` 驱动**（那条要吃掉好几帧，本文件几条帧驱动等待用例在全量跑时正是被它打穿的）
     * ——落位算式本身仍由本用例与 `拖动落位带顶部留白与行内偏移` 锁住。
     */
    @Test
    @Config(sdk = [34], qualifiers = LANDING_QUALIFIERS)
    fun `网格档拖动落点 整行贴视口上沿 行内偏移把该行再抬高`() {
        val items = 400
        val index = 100 * 2
        val paddingPx = landingTopContentPaddingPx()
        // 行内偏移取 40px：既不为 0（能看出被抬高），也远小于一行（格子高 150dp + 行间距 8dp ≈ 474px）
        val rowOffsetPx = 40

        // ① 整行落点（行内偏移 0）：该项顶边与视口上沿的像素差 = 0
        val flush = landedGridState(items, index, paddingPx, topOffsetPx = 0)
        assertEquals(
            "整行落点：该项顶边与视口上沿的像素差（上方不得露出留白与上一行的名字）",
            0f,
            landedTopDiffPx(flush, index),
            1f,
        )

        // 行距取的是**条目自身高度**（与屏内比例的分母同一份读数），不是「格子高 + 行间距」那个 pitch：
        // 真实布局里 150dp 与 158dp 差 8dp，取错就在这两条上红
        val density = context.resources.displayMetrics.density
        val rowExtentPx = flush.quickScrollBarState(itemsPerRow = { 2 }, topContentPaddingPx = paddingPx).rowExtentPx()
        assertEquals("行距 = 条目自身高度（restoreGrid 的格子 150dp）", (150 * density).roundToInt(), rowExtentPx)
        assertNotEquals("不是「格子高 + 行间距」的 pitch", (158 * density).roundToInt(), rowExtentPx)

        // ② 行内偏移 40px：同一行被再抬高 40px
        val raised = landedGridState(items, index, paddingPx, topOffsetPx = rowOffsetPx)
        assertEquals(
            "行内偏移把该行滚到视口上沿之上同样多（连续滚动的语义）",
            -rowOffsetPx.toFloat(),
            landedTopDiffPx(raised, index),
            1f,
        )

        // ③ 索引 0：不吃留白 ⇒ 那一行仍在留白之下（而不是贴到视口上沿）
        val top = landedGridState(items, index = 0, topContentPaddingPx = paddingPx, topOffsetPx = 0)
        assertEquals(
            "索引 0 不吃留白：第 0 行上沿仍在顶部内容留白之下",
            paddingPx.toFloat(),
            landedTopDiffPx(top, 0),
            1f,
        )
    }
}

/**
 * 定高行列表：本文件**唯一**一份列表组合形状（50dp 一行、键取索引），三处机制用例共用
 *（去重：此前是两处内联 + 一份新 helper，同形三份）。
 *
 * [itemCount] 是**取值口**而不是一个数：读点必须留在 `items(count = ...)` 这一句里（与三处内联时的位置相同），
 * 状态变化才会让列表重新测量——`短帧把恢复索引夹到末尾` 那条用例正是靠「改状态把列表涨长」。
 * 本批先写成传 `Int`（读点提前到调用方作用域）：那条用例当场红（列表不再按新长度重测），因此保留取值口。
 */
@Composable
private fun rows(itemCount: () -> Int, state: LazyListState) {
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        items(count = itemCount(), key = { it }) { index ->
            Box(Modifier.height(50.dp)) { Text("row-$index") }
        }
    }
}

/** 有界等待的成败写法：超时要在断言消息里看得见（`-1` / `600` 两个值形态照旧原样给出） */
private fun waited(settled: Boolean): String = if (settled) "已落地" else "**超时未落地**"

/**
 * 按**拖动落点**（生产算式 [quickScrollBarLandingOffsetPx]）构造初值的网格状态，并等到 [index] 行进了可见区。
 * 看得见才量得到：超时未落地要在消息里看得见（否则「等待超时」与「像素差不对」在日志里同形）。
 */
private fun landedGridState(
    items: Int,
    index: Int,
    topContentPaddingPx: Int,
    topOffsetPx: Int,
): LazyGridState {
    val state = LazyGridState(
        firstVisibleItemIndex = index,
        firstVisibleItemScrollOffset = quickScrollBarLandingOffsetPx(index, topContentPaddingPx, topOffsetPx),
    )
    val view = composeViewInActivity { restoreGrid(itemCount = { items }, state = state) }
    val landed = view.layoutUntil(LANDING_WIDTH_PX, LANDING_HEIGHT_PX) {
        state.layoutInfo.visibleItemsInfo.any { it.index == index }
    }
    val info = state.layoutInfo
    assertTrue(
        "第 $index 行要落在可见区里（超时未落地=${waited(landed)}；可见=" +
            "${info.visibleItemsInfo.joinToString(",") { it.index.toString() }}）",
        landed && info.visibleItemsInfo.any { it.index == index },
    )
    return state
}

/** [index] 行顶边与视口上沿的像素差（px）：0 = 贴上沿、正数 = 在留白之下、负数 = 被滚到上沿之上） */
private fun landedTopDiffPx(state: LazyGridState, index: Int): Float {
    val info = state.layoutInfo
    val item = requireNotNull(info.visibleItemsInfo.firstOrNull { it.index == index }) {
        "第 $index 行不在可见区里：可见=${info.visibleItemsInfo.joinToString(",") { it.index.toString() }}"
    }
    return (item.offset.y - info.viewportStartOffset).toFloat()
}

/**
 * 落位用例的屏幕限定符：**真密度**（xxhdpi = 3.0）+ 设备量级的宽高——判的就是 dp 换 px 之后那 12dp，
 * 用假尺寸（mdpi、dp 与 px 一一对应）量不到这道换算。
 */
private const val LANDING_QUALIFIERS = "w411dp-h891dp-port-xxhdpi"

/** 视口 px 尺寸（411dp × 3 宽 / 600dp × 3 高）：与 [LANDING_QUALIFIERS] 的密度同一份，量的是像素差 */
private const val LANDING_WIDTH_PX = 1233
private const val LANDING_HEIGHT_PX = 1800

/**
 * 网格档顶部内容留白的**真 px 值**：**引用生产常量** [GRID_CONTENT_PADDING_VERTICAL]（不用字面量复制——
 * 生产常量一变，用例会继续绿、覆盖的却不是出货配置）。
 */
private fun landingTopContentPaddingPx(): Int =
    (
        GRID_CONTENT_PADDING_VERTICAL.value *
            ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics.density
        ).roundToInt()

/**
 * 落位用例的网格组合形状：与生产 `BrowserGrid` **同一份纵向内容留白**（引生产常量；水平留白同样引用）。
 * 行内间距 8dp 与格子高 150dp 与落位口径**无关**（量的是「视口上沿到该项顶边」，与行高无关），
 * 按仓内先例用字面量代入（生产里那两个间距是 `BrowserScreen` 的私有常量）。
 */
@Composable
private fun restoreGrid(itemCount: () -> Int, state: LazyGridState, columns: Int = 2) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = GRID_CONTENT_PADDING_HORIZONTAL,
            end = GRID_CONTENT_PADDING_HORIZONTAL,
            top = GRID_CONTENT_PADDING_VERTICAL,
            bottom = GRID_CONTENT_PADDING_VERTICAL,
        ),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(count = itemCount(), key = { it }) { index ->
            Box(Modifier.height(150.dp)) { Text("cell-$index") }
        }
    }
}

/** 捕值的写法（本文件的**反例**：只为证明上面那条断言真的能分辨两种写法而存在，不是生产形状；
 * 生产那条在 `BrowserScreen.kt` 的 [BrowseScrollLeaveEffect]） */
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

/**
 * 假生命周期 owner：只为切后台那条用例派发 [Lifecycle.Event.ON_STOP] 用（[LifecycleRegistry] 需要宿主）。
 * 组合期把 [LocalLifecycleOwner] 换成它。[LifecycleRegistry] 的 `handleLifecycleEvent` 同步派发，无需等待。
 */
private class TestLifecycleOwner : LifecycleOwner {
    val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
}
