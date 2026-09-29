package com.cc3301.comicviewer.ui

import android.content.Context
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
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.sort.SortDirection
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.view.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
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
/** 本文件里「层」的取样：父层 / 落地层 / 另一个不相关层（容器 id 是路径形态，父子以 `/` 相接） */
private const val PARENT = "smb://c/目录"
private const val LAYER = "smb://c/目录/子目录"
private const val OTHER_LAYER = "smb://c/另一目录"

/** 服务端 id 形态的来源（容器 id 不是路径）：口径 ② 只能靠「落地层还在不在浏览链上」判方向 */
private const val OPAQUE_LAYER = "series-42"
private const val OPAQUE_PARENT = "collection-7"

private fun serviceLocation(containerId: String?) = BrowseLocation(connId = 7L, containerId = containerId, containerName = null)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseScrollRestoreTest {

    private lateinit var context: Context

    /**
     * 落盘 store 走 `ServiceLocator.context`，与全仓同类用例一致把初始化放 `@Before`
     *（先例 `StartupStoreTest.kt`，评审 standards-r2 P2-3）。
     */
    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ServiceLocator.init(context)
        // 浏览链（口径 ② 那个与 id 形态无关的判据的输入）是进程级单例：用例之间要互不串味
        ServiceLocator.browseHistory.clear()
    }

    /**
     * 设定「本次启动落到这一层」：落地层由启动链在**导航前**交给 store（[BrowseScrollDiskStore.markLanding]，
     * 票 #142 b13），这里按同一条链把它摆出来——正常「上次停留的位置」与启动链的两条退化支（票 #97「不是书」
     * 回落、连接来源拿不到回落）都汇到 `AppNav` 落浏览层那一支、都在那之前调用它。
     *
     * 先清 `startup` prefs，避免同 sandbox 里别的用例（顶层落点 / 阅读记录）残留进来。
     *
     * **不要写 `AppSettings.startupPage`**：那个 setter 走 `SharedPreferences.apply()`（异步），
     * 与同 sandbox 里后面的组合测量用例（`layoutUntil` 有界等待）互相干扰——实测两条用例会整段超时（红）。
     */
    private fun landOn(containerId: String?, connId: Long = 7L) {
        context.getSharedPreferences("startup", Context.MODE_PRIVATE).edit().clear().commit()
        BrowseScrollDiskStore.markLanding(connId, containerId)
    }

    /**
     * 本次启动落到**非浏览层**、且这一判定**已定**（顶层路由 首页/书柜/设置、阅读器、启动落地的兜底支）：
     * 启动链按「已定的非浏览落地层」交回（[BrowseScrollDiskStore.markLandingNonBrowserLayer]——`AppNav` 块首那句
     * 默认值用的是**同一个** API（`AppNav.kt:1743`），但它交回时判定还没作出）⇒ 收口时按「非落地层」
     * **当场丢弃**那条记录（维护者拍板 B）。
     * 清 `startup` prefs 只是与 [landOn] 同款卫生。
     *
     * 与 [landingNotHandedBackYet] **不是同一种输入**（评审 spec-r5-b13 P2）：那个是「store 还没收到任何落地层判定」，
     * 收口时既不消费也不销毁记录；两者压成一个时 P1 那条路在用例里也是绿的。
     */
    private fun landOnNonBrowserLayer() {
        context.getSharedPreferences("startup", Context.MODE_PRIVATE).edit().clear().commit()
        BrowseScrollDiskStore.markLandingNonBrowserLayer()
    }

    /**
     * 启动链**还没交回**落地层（票 #142 b14）：界面比启动 effect 先组合的那一帧——系统还原回退栈时，还原出的
     * 浏览层当帧就是栈顶，而 `AppNav` 交回落地层的那一句还没跑到。那种状态下 store 既不给别的层值、也不销毁记录，
     * 只有「问的正是记录那一层」时才先给值（见 [BrowseScrollDiskStore.consumeAtStartupLanding] 与
     * `系统还原回退栈 界面先组合拿到值 交回之后照旧用掉`）。
     */
    private fun landingNotHandedBackYet() {
        context.getSharedPreferences("startup", Context.MODE_PRIVATE).edit().clear().commit()
    }

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
    fun `记录落在首帧范围内时 初值就是记录的位置`() {
        // 票 #146 ④：组合期给初值（不是组合后 `requestScrollToItem` 跳过去）。
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
        // P1（票 #142 b10）：启动落地命中这一层时，盘上那份**不带代次**的一次性记录若被记住整个屏期，
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
        // 修 P1 的另一半：不能把「启动落回记录层恢复位置」一起弄坏（现行口径第 2 条）。
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
        // 机制实测（同本文件「短帧把恢复索引夹到末尾」的口径：锁机制、不锁 `BrowserScreen` 的接线点）：
        // `LazyListState()` 不给初值 ⇒ 首帧量出来就是 0（真机 `phase=read` 那一刻的 `now=0` 就是它）；
        // 给初值 ⇒ 首帧量出来就是初值，没有「先到顶部、再跳过去」那一下。
        //
        // **判别力**（评审 r1 规范轴 P1）：`firstVisibleItemIndex` 在**构造那一刻**就等于传给它的那个初值，
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
    fun `恢复打点的三行格式与字段口径`() {
        // 票 #142 取数用：字段名与顺序由这一条用例钉住——真机读数时才有稳定的口径可依，
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
        // 票 #142 r2：**离场那一刻记下的**那个值（`onDispose`，事件时刻、没经过短帧）。它是「读到」那一行的
        // `saved` 的来源——只有它才能把「位置在离场时就已经没了」与「交回时丢的」分开。
        assertEquals(
            "browseRestore phase=leave container=smb://c/Artist index=600 mode=list",
            browseRestoreLeaveLine(container = "smb://c/Artist", index = 600, isGrid = false),
        )
        assertEquals(
            "browseRestore phase=leave container=<root> index=0 mode=grid",
            browseRestoreLeaveLine(container = null, index = 0, isGrid = true),
        )
    }

    @Test
    fun `离场记下的位置存在界面之外的记录里 重建后照旧交得回来`() {
        // 票 #142 换机制：位置不再只押 `rememberSaveable` 的交回——真机日志（`references/` 里那份诊断导出）
        // 里离场那一刻明明记下了 18，返回后读到的 `saved=0`（整屏 saved state 都是 0）。记录活在界面之外，
        // 与那份 saved state 是否交回无关。
        BrowseScrollIndexStore.clearForTest()
        val key = recordKey()
        // 进屏那一刻的读数：首次进入、没有记录 ⇒ 这一份滚动状态是干净的
        BrowseScrollIndexStore.noteEntered(key, readNow = 0)
        BrowseScrollIndexStore.record(key, indexAtLeave = 18)
        assertEquals("离场记下的位置重建后读得到", 18, BrowseScrollIndexStore.valueFor(key))
    }

    @Test
    fun `进屏被丢到顶的那一次离场 不能覆盖记录`() {
        // 真机日志里同一次过渡里出现两个组合：先 `leave index=18`（用户真正停留的位置），
        // 90 ms 后一个「进屏读到 0」的组合又 `leave index=0`。后一次读到的 0 不是用户的位置——
        // 记录非 0 而进屏当下读到 0 ⇒ 这一份滚动状态没交回来（被系统短帧夹到顶）⇒ 那次离场不作数。
        BrowseScrollIndexStore.clearForTest()
        val key = recordKey()
        BrowseScrollIndexStore.noteEntered(key, readNow = 0)
        BrowseScrollIndexStore.record(key, indexAtLeave = 18)

        BrowseScrollIndexStore.noteEntered(key, readNow = 0)
        BrowseScrollIndexStore.record(key, indexAtLeave = 0)
        assertEquals("被丢到顶的那一次离场不覆盖记录", 18, BrowseScrollIndexStore.valueFor(key))
    }

    @Test
    fun `状态交回来的组合里 滚回顶部的用户离场照旧写 0`() {
        // 这一条挡的是「一律不写 0」那种过头判据：进屏当下读到 18 = 状态真的交回来了 ⇒ 这个组合的
        // 离场读数算数，用户在顶部离场就该记 0（否则返回时会被拽回上次的位置）。
        BrowseScrollIndexStore.clearForTest()
        val key = recordKey()
        BrowseScrollIndexStore.noteEntered(key, readNow = 0)
        BrowseScrollIndexStore.record(key, indexAtLeave = 18)

        BrowseScrollIndexStore.noteEntered(key, readNow = 18)
        BrowseScrollIndexStore.record(key, indexAtLeave = 0)
        assertEquals("状态在手里的组合：顶部就是顶部", 0, BrowseScrollIndexStore.valueFor(key))
    }

    @Test
    fun `没记录过的层读 0 且不同层不同代次各记各的`() {
        BrowseScrollIndexStore.clearForTest()
        val name = recordKey()
        val modified = recordKey(mode = SortMode.MODIFIED_TIME)
        val otherLayer = recordKey(containerId = "smb://c/另一目录")
        assertEquals("没记过的层：读 0（首屏在顶部）", 0, BrowseScrollIndexStore.valueFor(name))

        BrowseScrollIndexStore.noteEntered(name, readNow = 0)
        BrowseScrollIndexStore.record(name, indexAtLeave = 18)
        assertEquals("换排序 = 换复位代次：新代次读不到旧记录（回顶部，票 #58 的承诺）", 0, BrowseScrollIndexStore.valueFor(modified))
        assertEquals("另一层就读另一层的记录", 0, BrowseScrollIndexStore.valueFor(otherLayer))
        BrowseScrollIndexStore.noteEntered(otherLayer, readNow = 0)
        BrowseScrollIndexStore.record(otherLayer, indexAtLeave = 7)
        assertEquals("两层互不干扰", 18, BrowseScrollIndexStore.valueFor(name))
        assertEquals("两层互不干扰", 7, BrowseScrollIndexStore.valueFor(otherLayer))
    }

    @Test
    fun `换代丢掉该层其他代次的记录 排序 A B A 也回顶部`() {
        // 票 #142 代次口径收口：`BrowseScrollResetKey` 现含 `SortSettingStore.revision`（每次排序写入 +1）
        // ⇒ 排序 A→B→A 是**新键**，界面路径本就读不到旧记录。本用例钉的是 store 那一侧的**不变式**：
        // 换代必须丢掉该层其他代次的记录（少了它，单测直调 store 或将来复用一个键的路径会把旧位置读回来）。
        // `docs/spec/browsing.md`「排序在展示层翻转 / 滚动复位」要求排序设置变化即回顶部。
        BrowseScrollIndexStore.clearForTest()
        val name = recordKey() // 名称档 = 键 A
        val modified = recordKey(mode = SortMode.MODIFIED_TIME) // 修改时间档 = 键 B
        BrowseScrollIndexStore.noteEntered(name, readNow = 0)
        BrowseScrollIndexStore.record(name, indexAtLeave = 600)
        assertEquals("名称档停留过 600", 600, BrowseScrollIndexStore.valueFor(name))

        // 点「修改时间」：换代登记 ⇒ 丢掉名称档那份
        BrowseScrollIndexStore.beginGeneration(modified)
        assertEquals("换到 B：读不到 A 的记录（回顶部）", 0, BrowseScrollIndexStore.valueFor(modified))
        assertEquals("A 的记录已被丢掉", 0, BrowseScrollIndexStore.valueFor(name))

        // 切回「名称」：**store 侧复用同一个键对象**（`name`），但旧记录已在换代那一刻丢掉 ⇒ 照旧回顶部
        // （界面路径此刻拿到的是**新键**——见本用例表头；这里钉的是 store 侧的那条不变式）
        BrowseScrollIndexStore.beginGeneration(name)
        assertEquals("切回 A：同一个键，但旧记录已丢 ⇒ 回顶部", 0, BrowseScrollIndexStore.valueFor(name))
    }

    @Test
    fun `换代后旧代次的离场读数写不回来`() {
        // 换代那一刻旧的滚动状态也会 dispose 一次、产一个「旧代次离场读数」（见 `browseRestoreLeaveLine` 的
        // 「多行是正常的」段）；若它被写进记录，[BrowseScrollIndexStore.beginGeneration] 刚丢掉的那份立刻
        // 又回来了（下一次读到该键仍是旧位置）。`record` 因此按「该层当下代次」拒收旧代次的离场读数。
        BrowseScrollIndexStore.clearForTest()
        val name = recordKey()
        val modified = recordKey(mode = SortMode.MODIFIED_TIME)
        BrowseScrollIndexStore.noteEntered(name, readNow = 0)
        BrowseScrollIndexStore.record(name, indexAtLeave = 600)

        BrowseScrollIndexStore.beginGeneration(modified) // 点「修改时间」：换代
        BrowseScrollIndexStore.record(name, indexAtLeave = 600) // 旧滚动状态的 dispose 又写一次
        assertEquals("旧代次的离场读数不得把刚丢掉的位置写回来", 0, BrowseScrollIndexStore.valueFor(name))
    }

    @Test
    fun `换代只丢该层旧代次 本代次与别的层照旧`() {
        BrowseScrollIndexStore.clearForTest()
        val name = recordKey()
        val modified = recordKey(mode = SortMode.MODIFIED_TIME)
        val otherLayer = recordKey(containerId = "smb://c/另一目录")

        BrowseScrollIndexStore.beginGeneration(name) // 该层当下代次 = 名称档
        BrowseScrollIndexStore.beginGeneration(otherLayer) // 另一层互不相干
        BrowseScrollIndexStore.noteEntered(name, readNow = 0)
        BrowseScrollIndexStore.record(name, indexAtLeave = 600)
        BrowseScrollIndexStore.noteEntered(otherLayer, readNow = 0)
        BrowseScrollIndexStore.record(otherLayer, indexAtLeave = 7)

        BrowseScrollIndexStore.beginGeneration(modified) // 该层换代：只丢名称档
        assertEquals("本层旧代次被丢掉", 0, BrowseScrollIndexStore.valueFor(name))
        assertEquals("别的层的记录不受影响", 7, BrowseScrollIndexStore.valueFor(otherLayer))

        // 本代次自己的记录照旧（同一代次里离开 / 返回仍要恢复位置）
        BrowseScrollIndexStore.noteEntered(modified, readNow = 0)
        BrowseScrollIndexStore.record(modified, indexAtLeave = 5)
        BrowseScrollIndexStore.beginGeneration(modified) // 同代次重复登记：什么都不丢
        assertEquals("本代次的记录照旧", 5, BrowseScrollIndexStore.valueFor(modified))
    }

    @Test
    fun `启动那条落盘记录用掉后 离开这一层再从上一级进来回到顶部`() {
        // 现行口径第 2 条新写法（维护者 2026-09-29 拍板取 (2)）：重启落地层**先保持原位**，那条落盘记录
        // 用掉即清；**离开这一层、再从上一级进来 ⇒ 回顶部**（「本次启动内其它层的位置记录」照旧恢复）。
        //
        // 判据不是「第一个离屏写点」而是**层关系**：离屏写点只知道这一层走了、不知道走去哪
        //（进阅读器 / 进子目录 / 回上一级形态相同），无差别作废会把那两条也牺牲掉（同文件另两条用例钉它们）。
        // 这里按生产顺序摆出来：落地层离场写一次（自己写，不登记）→ **上一级**那一层写盘（用户到了上级）→ 再进来。
        BrowseScrollIndexStore.clearForTest()
        BrowseScrollDiskStore.clearForTest()
        landOn(LAYER)
        BrowseScrollDiskStore.record(connId = 7L, containerId = LAYER, index = 600)
        val key = recordKey(connId = 7L, containerId = LAYER)

        // ① 启动落地：界面读到盘上那条（用掉）⇒ 这一层先保持原位
        assertEquals("落地层先保持原位", 600, BrowseScrollDiskStore.consumeAtStartupLanding(7L, LAYER))

        // ② 离开这一层（离屏写点）：照旧记下位置（这一条去向本身不决定作废）
        BrowseScrollIndexStore.noteEntered(key, readNow = 600)
        BrowseScrollDiskStore.recordEffectivePosition(key, rawIndex = 600, connId = 7L, containerId = LAYER)
        assertEquals("离场照旧记下位置", 600, BrowseScrollIndexStore.valueFor(key))

        // ③ 用户走到了这一层的**上一级**（那一层组合并写盘、且落地层已不在浏览链上）⇒ 登记「下次进落地层回顶部」
        BrowseScrollDiskStore.record(connId = 7L, containerId = PARENT, index = 30)

        // ④ 从上一级再进来：回顶部（初值 0）
        assertEquals(
            "从上一级进来回顶部",
            0,
            initialScrollItemIndex(
                recordedIndex = restoredIndexOnLeaveFor(
                    diskAtStartup = BrowseScrollDiskStore.consumeAtStartupLanding(7L, LAYER),
                    startupGeneration = key.generation,
                    currentGeneration = key.generation,
                    inMemoryIndex = BrowseScrollIndexStore.valueFor(key),
                ),
                firstFrameItemCount = 800,
            ),
        )

        // ⑤ 之后的离场照旧记：作废「以一次为限」——`resetOnReentryFromParent` 取用一次即消，
        // 这一次用户真的滚到了 300 再离场，读数就该照旧记下来（少了这一条，「作废」会退化成永久失效）。
        BrowseScrollIndexStore.noteEntered(key, readNow = 0)
        BrowseScrollIndexStore.record(key, indexAtLeave = 300)
        assertEquals("之后的离场照旧记", 300, BrowseScrollIndexStore.valueFor(key))
    }

    @Test
    fun `启动那条记录用掉后 本进程内不再交回同一层`() {
        // 「盘上那条只喂启动那一代」（维护者 2026-09-29 口径）：收口之后本方法若退化成「按层取回那条记录」，
        // 界面离场时重新写下的那份又会在再进来时被恢复（现行口径第 2 条新写法不成立）。
        // 判别力：去掉 `consumeAtStartupLanding` 里 `if (landed) return null` 那一句时，下面第二条断言读到 420。
        BrowseScrollIndexStore.clearForTest()
        BrowseScrollDiskStore.clearForTest()
        landOn("dir-deep")
        BrowseScrollDiskStore.record(connId = 7L, containerId = "dir-deep", index = 600)
        assertEquals("启动那一刻照旧给值", 600, BrowseScrollDiskStore.consumeAtStartupLanding(7L, "dir-deep"))

        // 落地层里走过一次：盘上被重新写上一份（下一次重启要用它 —— 现行口径第 2 条）
        BrowseScrollDiskStore.record(connId = 7L, containerId = "dir-deep", index = 420)
        assertNull("本进程内不再交回同一层", BrowseScrollDiskStore.consumeAtStartupLanding(7L, "dir-deep"))
        assertNull("别的层也读不到那条记录 ⇒ 回顶部", BrowseScrollDiskStore.consumeAtStartupLanding(7L, "dir-other"))
    }

    @Test
    fun `落地层从阅读器返回 保持原位`() {
        // 票面现行口径第 1 条（当次启动内一切导航都保持位置）在**落地层**上照旧成立：
        // 进阅读器时**没有任何浏览层写盘** ⇒ 不登记「从上一级进来」⇒ 返回照旧恢复原位。
        // 判别力（本轮改动前本用例红）：旧写法在落地层的**第一个离屏写点**就无差别丢掉读数
        // ⇒ 返回时既无盘记录也无内存记录，读到 0（顶部）。
        BrowseScrollIndexStore.clearForTest()
        BrowseScrollDiskStore.clearForTest()
        landOn(LAYER)
        BrowseScrollDiskStore.record(connId = 7L, containerId = LAYER, index = 600)
        val key = recordKey(connId = 7L, containerId = LAYER)
        assertEquals("落地层先保持原位", 600, BrowseScrollDiskStore.consumeAtStartupLanding(7L, LAYER))

        // 点书进阅读器：落地层的离屏写点
        BrowseScrollIndexStore.noteEntered(key, readNow = 600)
        BrowseScrollDiskStore.recordEffectivePosition(key, rawIndex = 600, connId = 7L, containerId = LAYER)

        // 从阅读器返回：读回 600
        assertEquals(
            "从阅读器返回保持原位",
            600,
            initialScrollItemIndex(
                recordedIndex = restoredIndexOnLeaveFor(
                    diskAtStartup = BrowseScrollDiskStore.consumeAtStartupLanding(7L, LAYER),
                    startupGeneration = key.generation,
                    currentGeneration = key.generation,
                    inMemoryIndex = BrowseScrollIndexStore.valueFor(key),
                ),
                firstFrameItemCount = 800,
            ),
        )
    }

    @Test
    fun `落地层进出子目录 保持原位`() {
        // 同上，另一半：进子目录（写盘的是本层的**下级**）→ 返回本层照旧保持原位。
        // 判别力（本轮改动前本用例红）：旧写法按「第一个离屏写点」作废，返回时读到 0。
        BrowseScrollIndexStore.clearForTest()
        BrowseScrollDiskStore.clearForTest()
        landOn(LAYER)
        BrowseScrollDiskStore.record(connId = 7L, containerId = LAYER, index = 600)
        val key = recordKey(connId = 7L, containerId = LAYER)
        assertEquals("落地层先保持原位", 600, BrowseScrollDiskStore.consumeAtStartupLanding(7L, LAYER))

        // 进子目录：落地层**还在浏览链上**（它只是被压了一层，没被退回）——这正是与 id 形态无关的那半个判据
        ServiceLocator.browseHistory.syncPath(listOf(serviceLocation(LAYER), serviceLocation(LAYER + "/" + "子目录")))
        // 落地层的离屏写点，随后子目录那一层组合并写盘（下级）
        BrowseScrollIndexStore.noteEntered(key, readNow = 600)
        BrowseScrollDiskStore.recordEffectivePosition(key, rawIndex = 600, connId = 7L, containerId = LAYER)
        BrowseScrollDiskStore.record(connId = 7L, containerId = LAYER + "/" + "子目录", index = 0)

        // 出子目录回到本层：保持原位
        assertEquals(
            "出子目录回到本层保持原位",
            600,
            initialScrollItemIndex(
                recordedIndex = restoredIndexOnLeaveFor(
                    diskAtStartup = BrowseScrollDiskStore.consumeAtStartupLanding(7L, LAYER),
                    startupGeneration = key.generation,
                    currentGeneration = key.generation,
                    inMemoryIndex = BrowseScrollIndexStore.valueFor(key),
                ),
                firstFrameItemCount = 800,
            ),
        )
    }

    @Test
    fun `服务端 id 形态的来源 靠浏览链判方向 走到上级再进来也回顶部`() {
        // 评审 spec-r19 item：容器 id 不是路径形态（服务端 id 来源）时 [isAncestorContainer] 判不出前后关系，
        // 方向必须靠与 id 形态无关的那一半——**落地层已经不在浏览链上**（`BrowseHistory.path()`）。
        // 判别力：这一段完全靠「落地层已不在浏览链上」那半个判据——容器 id 不是路径时 `isAncestorContainer`
        // 恒假（它的用例在下面单列），只留路径前缀判据时第三条断言读到 600（静态推断；本轮没跑那一次红）。
        BrowseScrollIndexStore.clearForTest()
        BrowseScrollDiskStore.clearForTest()
        landOn(OPAQUE_LAYER)
        BrowseScrollDiskStore.record(connId = 7L, containerId = OPAQUE_LAYER, index = 600)
        val key = recordKey(connId = 7L, containerId = OPAQUE_LAYER)
        assertEquals("落地层先保持原位", 600, BrowseScrollDiskStore.consumeAtStartupLanding(7L, OPAQUE_LAYER))

        // 离场（照旧记下位置）
        BrowseScrollIndexStore.noteEntered(key, readNow = 600)
        BrowseScrollDiskStore.recordEffectivePosition(key, rawIndex = 600, connId = 7L, containerId = OPAQUE_LAYER)

        // 走到上一级：落地层已不在浏览链上（服务端 id 形态，路径前缀判不出）
        ServiceLocator.browseHistory.syncPath(listOf(serviceLocation(OPAQUE_PARENT)))
        BrowseScrollDiskStore.record(connId = 7L, containerId = OPAQUE_PARENT, index = 30)

        assertEquals(
            "从上一级进来回顶部（与容器 id 形态无关）",
            0,
            initialScrollItemIndex(
                recordedIndex = restoredIndexOnLeaveFor(
                    diskAtStartup = BrowseScrollDiskStore.consumeAtStartupLanding(7L, OPAQUE_LAYER),
                    startupGeneration = key.generation,
                    currentGeneration = key.generation,
                    inMemoryIndex = BrowseScrollIndexStore.valueFor(key),
                ),
                firstFrameItemCount = 800,
            ),
        )
    }

    @Test
    fun `不是落地层的层 走到上级再进来照旧保持位置`() {
        // P0（评审 standards-r19）：作用域必须只绑**启动那次真正落地的那一层**——别的层走到上级再进来
        // 照旧按「当次启动内非排序变化保持位置」记（票面现行口径第 1 条）。
        // 判别力（本轮改动前本用例红）：旧写法把「盘上指针当前指着的层」当落地层，这一层会被一并作废。
        BrowseScrollIndexStore.clearForTest()
        BrowseScrollDiskStore.clearForTest()
        landOn(LAYER)
        BrowseScrollDiskStore.record(connId = 7L, containerId = LAYER, index = 600)
        assertEquals("启动落地层用掉记录", 600, BrowseScrollDiskStore.consumeAtStartupLanding(7L, LAYER))

        // 走进另一个层（不是落地层），滚到 40 → 走到它的上级 → 再进来
        ServiceLocator.browseHistory.syncPath(listOf(serviceLocation(LAYER), serviceLocation(OTHER_LAYER)))
        val other = recordKey(connId = 7L, containerId = OTHER_LAYER)
        BrowseScrollIndexStore.noteEntered(other, readNow = 40)
        BrowseScrollDiskStore.recordEffectivePosition(other, rawIndex = 40, connId = 7L, containerId = OTHER_LAYER)
        BrowseScrollDiskStore.record(connId = 7L, containerId = PARENT, index = 30)

        assertEquals("不是落地层：走过上级再进来照旧保持位置", 40, BrowseScrollIndexStore.valueFor(other))
    }

    @Test
    fun `只有上级层算从上一级进来 同级兄弟与子层都不算`() {
        // 判据是**层关系**（路径前后缀），不是「有没有别的层写过盘」：
        // 兄弟层（同级）与不相关的层都不登记，子层更不登记（父子以 `/` 相接，`a/b` 不会把 `a/bc` 当子层）。
        assertTrue("根层是任何层的上级", isAncestorContainer(null, LAYER))
        assertTrue("路径前缀算上级", isAncestorContainer(PARENT, LAYER))
        assertTrue("直接上级也算", isAncestorContainer(LAYER + "/" + "子目录", LAYER + "/" + "子目录" + "/" + "再下一层"))
        assertEquals(false, isAncestorContainer(LAYER + "/" + "子目录", LAYER))
        assertEquals("同级兄弟不是上级", false, isAncestorContainer(OTHER_LAYER, LAYER))
        assertEquals("路径前缀要把整段对齐（a/b 不是 a/bc 的上级）", false, isAncestorContainer("/x/a/b", "/x/a/bc"))
        assertEquals("根层没有上级", false, isAncestorContainer(LAYER, null))
    }

    @Test
    fun `首帧被夹小的初值 取够页之后仍把记录那一条放回`() {
        // 落点口径（维护者 2026-09-29）：落点 = **最终列表**里那本书的上沿顶在视口上沿（偏移 0，记录仍是项索引）。
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
    fun `重启恢复位置 落盘记录用掉即清`() {
        // 现行口径第 2/3 条：「离开 App 时所处的那一层 + 该层的位置」单独落盘一份（只存这一条，不存历史），
        // 重启落在这一层时用它一次就清掉；之后（跳去别的文件夹）读不到记录 ⇒ 回顶部。
        BrowseScrollDiskStore.clearForTest()
        landOn("dir-deep")

        // 写点见 `BrowserScreen` 的进屏 / 离场（这里直接模拟「离开 App 时留下的那一份」）
        BrowseScrollDiskStore.record(connId = 7L, containerId = "dir-deep", index = 600)

        assertEquals("重启落在这一层：用它给出位置", 600, BrowseScrollDiskStore.consumeAtStartupLanding(7L, "dir-deep"))
        assertNull("用掉即清：同一次进程里再读已经没有记录", BrowseScrollDiskStore.consumeAtStartupLanding(7L, "dir-deep"))
        assertNull("另一个文件夹：读不到记录 ⇒ 回顶部", BrowseScrollDiskStore.consumeAtStartupLanding(7L, "dir-other"))
    }

    @Test
    fun `ON_STOP 写点与离屏同源 被夹小的读数不落盘`() {
        // 评审 standards-r2 P1：离屏与切后台（ON_STOP）两条写点共用 [BrowseScrollDiskStore.recordEffectivePosition]——
        // 先过 [BrowseScrollIndexStore.record] 的丢态判据，再落它过滤后的**生效值**。
        // 复现场景：滚到 600 → 从阅读器返回（恢复链尚未放回，进屏读到被夹小的 184）→ 按 HOME（ON_STOP）：
        // 裸读数是 184，落盘的必须是 600（否则盘上那条记录被丢态读数覆盖，第二次重启回顶部）。
        BrowseScrollIndexStore.clearForTest()
        BrowseScrollDiskStore.clearForTest()
        landOn("smb://c/目录", connId = 1L)
        val key = recordKey()
        BrowseScrollIndexStore.noteEntered(key, readNow = 0)
        BrowseScrollIndexStore.record(key, indexAtLeave = 600)

        BrowseScrollIndexStore.noteEntered(key, readNow = 184)
        BrowseScrollDiskStore.recordEffectivePosition(
            key = key,
            rawIndex = 184,
            connId = 1L,
            containerId = "smb://c/目录",
        )

        assertEquals(
            "盘上落的是生效值 600，不是被夹小的 184",
            600,
            BrowseScrollDiskStore.consumeAtStartupLanding(1L, "smb://c/目录"),
        )
    }

    @Test
    fun `同屏的第二个写点再读到同一份被夹小的读数 照旧被拒写也不落盘`() {
        // 评审 spec-r3-b3 P2-1：`record` 若**无条件**消费进屏基准，同屏的第二个写点（ON_STOP 之后再离屏 /
        // 再按一次 HOME）就没有基准可比 ⇒ 必然放行，被夹小的 184 会落进内存记录**和**磁盘，票面
        // 「系统夹索引不写」在这个窗口破掉。这里把 `recordEffectivePosition` 连调两次（同 `rawIndex = 184`）：
        // 第一次被拒写之后基准仍在，第二次照旧按「读数没动过」拒 ⇒ 内存记录与盘上都是 600。
        // 把「只在收下读数时消费基准」改回无条件消费时本用例应红（第二次调用记 184、盘上也是 184）。
        BrowseScrollIndexStore.clearForTest()
        BrowseScrollDiskStore.clearForTest()
        landOn("smb://c/目录", connId = 1L)
        val key = recordKey()
        BrowseScrollIndexStore.noteEntered(key, readNow = 0)
        BrowseScrollIndexStore.record(key, indexAtLeave = 600)

        BrowseScrollIndexStore.noteEntered(key, readNow = 184)
        repeat(2) {
            BrowseScrollDiskStore.recordEffectivePosition(
                key = key,
                rawIndex = 184,
                connId = 1L,
                containerId = "smb://c/目录",
            )
        }

        assertEquals("同屏第二个写点照旧被拒：记录还是 600", 600, BrowseScrollIndexStore.valueFor(key))
        assertEquals(
            "盘上落的仍是 600，不是被夹小的 184",
            600,
            BrowseScrollDiskStore.consumeAtStartupLanding(1L, "smb://c/目录"),
        )
    }

    @Test
    fun `启动链退化落到记录那一层 落地层由启动链交回 记录照旧恢复并用掉`() {
        // 票 #142 b13 P1：真正落地的层会被启动链的**退化**改写，而 store 原先自己调
        // [StartupStore.startupTarget] 二次推导落地层 ⇒ 两边分叉时把记录当场销毁。复现分叉：
        // 上次退出正停在阅读器（`startupTarget()` = OpenReader），而这次启动链因「上次那本书已不是书」
        //（票 #97 `isNotABook` 升级路径；连接来源拿不到时同样）退化到 `OpenBrowser(lastBrowsing)`——
        // 真正落地的正是记录那一层，要恢复到原位置并用掉记录。
        // 判别力：把交接口改回「store 自调 startupTarget」时本用例应红——那时判的是 OpenReader（不是落地层）
        // ⇒ 记录被 `clear()`、下面读到 null。
        BrowseScrollDiskStore.clearForTest()
        context.getSharedPreferences("startup", Context.MODE_PRIVATE).edit().clear().commit()
        StartupStore.recordReading(true)
        StartupStore.recordLastRead(LastRead(connId = 7L, bookId = "book-a"))
        BrowseScrollDiskStore.record(connId = 7L, containerId = "dir-deep", index = 600)
        // 启动链（`AppNav` 落浏览层那一支）在导航前把已定的落地层交回 store
        BrowseScrollDiskStore.markLanding(7L, "dir-deep")

        assertEquals("退化落到记录那一层：恢复原位置", 600, BrowseScrollDiskStore.consumeAtStartupLanding(7L, "dir-deep"))
        assertNull("用掉即清：同一次进程里再读已经没有记录", BrowseScrollDiskStore.consumeAtStartupLanding(7L, "dir-deep"))
    }

    @Test
    fun `落地已定丢弃非落地层的那条记录 之后走进记录层也是顶部`() {
        // 维护者 2026-09-28 拍板 **B**：重启落在**非记录层**时，盘上那条记录要**当场丢弃**——
        // 用户随后走进记录那一层也是顶部，而不是把重启前的位置恢复回来（票面第 3 条括注
        //「重启后只有落地那一层有记录」）。去掉那一步「丢弃」时本用例应红（第二次调用会命中读到 600）。
        // 「非落地层」在这里是 [landOnNonBrowserLayer] = **已定**的非浏览层（不是「还没交回」，见 [landingNotHandedBackYet]）。
        BrowseScrollDiskStore.clearForTest()
        landOnNonBrowserLayer() // 本次落地 = 首页（已定的非浏览层）
        BrowseScrollDiskStore.record(connId = 7L, containerId = "dir-deep", index = 600)

        assertNull(
            "落地已定：这一层不是落地层 ⇒ 当场丢弃、不给值",
            BrowseScrollDiskStore.consumeAtStartupLanding(7L, "dir-deep"),
        )
        assertNull(
            "之后走进记录那一层也是顶部（记录已被丢弃，而不是留在那儿等命中）",
            BrowseScrollDiskStore.consumeAtStartupLanding(7L, "dir-deep"),
        )
    }

    @Test
    fun `系统还原回退栈 界面先组合拿到值 交回之后照旧用掉`() {
        // 评审 spec-r5-b13 / standards-r5-b13 P1（b13 回归）：「进程被杀后重建」早退支的落地层就是还原出来的那个
        // 浏览层，而那一支原先从不交回 ⇒ `landingLayer == null` 被判成「一定不是」⇒ 记录在读取前被 `clear()`。
        // 序列：停在 dir-deep 的 600 → 按 HOME（ON_STOP 写点落盘）→ 进程被系统回收 → 从最近任务回 App。
        // 这里把它拆成两拍，两拍都要成立：
        // ① 还原出的浏览层**当帧就是栈顶**，它的组合早于启动 effect 的交回 ⇒ 那时还没交回，但问的正是记录那一层
        //（该支的落地层就是这一层）⇒ 先把值给它、**不消费**（记录还在，交回之后才收口）；
        // ② 启动 effect 走早退支把这一层交回 ⇒ 收口时照旧「恢复并用掉」。
        // 判别力：把「还没交回」那一态改回 b13 的「一定不是」（`landed` 一置位就按 null 落地层 clear），
        // 第一次调用即返回 null、本用例第一条断言当场红。
        BrowseScrollDiskStore.clearForTest()
        landingNotHandedBackYet()
        BrowseScrollDiskStore.record(connId = 7L, containerId = "dir-deep", index = 600)

        assertEquals(
            "还没交回但问的正是记录那一层：先给值",
            600,
            BrowseScrollDiskStore.consumeAtStartupLanding(7L, "dir-deep"),
        )
        // 早退支交回：系统还原出来的那一层
        BrowseScrollDiskStore.markLanding(7L, "dir-deep")
        assertEquals(
            "交回之后收口：落地层就是记录那一层 ⇒ 照旧恢复（上面那次读没消费它）",
            600,
            BrowseScrollDiskStore.consumeAtStartupLanding(7L, "dir-deep"),
        )
        assertNull("用掉即清：同一次进程里再读已经没有记录", BrowseScrollDiskStore.consumeAtStartupLanding(7L, "dir-deep"))
    }

    @Test
    fun `未交回落地层时不销毁记录 交回之后照旧恢复`() {
        // 评审 spec-r5-b13 / standards-r5-b13 P1 的第二半（批次标题「未交回时不得销毁记录」）：
        // 「还没交回」必须与「已定的非浏览层」分开——前者既不给别的层值、也**不 clear()**（否则将来再漏一个
        // 调用点就又静默销毁一次记录）。判别力：改回「未交回 = 一定不是」时，下面第二条断言会读到 null（记录已被销毁）。
        BrowseScrollDiskStore.clearForTest()
        landingNotHandedBackYet()
        BrowseScrollDiskStore.record(connId = 7L, containerId = "dir-deep", index = 600)

        assertNull("还没交回：别的层不给值", BrowseScrollDiskStore.consumeAtStartupLanding(7L, "dir-other"))
        BrowseScrollDiskStore.markLanding(7L, "dir-deep")
        assertEquals(
            "记录没被销毁：交回之后照旧恢复",
            600,
            BrowseScrollDiskStore.consumeAtStartupLanding(7L, "dir-deep"),
        )
        assertNull("用掉即清", BrowseScrollDiskStore.consumeAtStartupLanding(7L, "dir-deep"))
    }

    @Test
    fun `落盘记录根层也能往返 层不符不给值`() {
        BrowseScrollDiskStore.clearForTest()
        landOn(null)
        BrowseScrollDiskStore.record(connId = 7L, containerId = null, index = 42)
        assertEquals("根层（容器 id 为 null）原样往返", 42, BrowseScrollDiskStore.consumeAtStartupLanding(7L, null))

        BrowseScrollDiskStore.clearForTest()
        landOn(null)
        BrowseScrollDiskStore.record(connId = 7L, containerId = "dir-x", index = 9)
        assertNull("另一连接上的同容器名：层不符不给值", BrowseScrollDiskStore.consumeAtStartupLanding(8L, "dir-x"))
    }

    @Test
    fun `恢复落地后上移到 5 的那一次离场照旧写 5`() {
        // 评审 r1 P1-1：拒写不能把丢态那一屏的**整个屏期**都封死。
        // 序列：滚到 600 → 开书 → 返回（恢复链把 600 放回去）→ 上移到 5 → 再开书 → 返回 ⇒ 必须落到 5。
        // 旧写法：进屏读到 0 ⇒ 进屏基准被当成「状态没交回来」⇒ 离场读到的 5 被当成丢态拒掉，
        // 记录留在 600，返回后把用户拽回 600。
        BrowseScrollIndexStore.clearForTest()
        val key = recordKey()
        BrowseScrollIndexStore.noteEntered(key, readNow = 0)
        BrowseScrollIndexStore.record(key, indexAtLeave = 600)
        assertEquals("第一屏滚到 600 离场", 600, BrowseScrollIndexStore.valueFor(key))

        // 返回：这一份滚动状态仍没交回来（读到 0），此后恢复链把位置放回 600、用户上移到 5
        BrowseScrollIndexStore.noteEntered(key, readNow = 0)
        BrowseScrollIndexStore.record(key, indexAtLeave = 5)
        assertEquals("恢复到 600 之后用户自己上移到的 5 算数（旧写法：被 600 挡住）", 5, BrowseScrollIndexStore.valueFor(key))
    }

    @Test
    fun `短帧夹小的非 0 读数不覆盖记录`() {
        // 评审 r1 P1-2：实测形态 600 → 184。旧写法把「读到非 0」当成「状态真交回来了」
        // ⇒ 恢复落地前（慢来源上为数秒）离场就拿 184 把 600 覆盖掉，用户位置永久降级。
        // 进屏读到 184 = 进屏那一下的残留（它不是用户停留的位置）⇒ 离场读数没动过 ⇒ 不得改写记录。
        BrowseScrollIndexStore.clearForTest()
        val key = recordKey()
        BrowseScrollIndexStore.noteEntered(key, readNow = 0)
        BrowseScrollIndexStore.record(key, indexAtLeave = 600)

        BrowseScrollIndexStore.noteEntered(key, readNow = 184)
        BrowseScrollIndexStore.record(key, indexAtLeave = 184)
        assertEquals("被短帧夹小的 184 不得覆盖 600", 600, BrowseScrollIndexStore.valueFor(key))
        // 票面「系统夹索引不写」的两个读点取较大者口径不变：链交给界面的仍是未被夹的那个值
        assertEquals(600, unclippedRestoredScrollIndex(recordedOnLeave = 600, readNow = 184))
    }

    @Test
    fun `放回请求之后用户滚回顶部离场 记 0`() {
        // 评审 r2-b1 P2-1：拒写窗口不能在本屏一直开着。
        // 序列：丢态回屏（进屏基准 0、记录 600）→ 恢复链请求把 600 放回（[BrowseScrollIndexStore.notePlaced]）
        // → 用户滚回 0 离场 ⇒ 读数与基准同值（0），但本屏已请求过放回 ⇒ 必须记 0。
        // 旧写法（只看「读数没动过」）：0 被当成丢态残留拒掉，记录停在 600，返回后又把用户拽回 600
        //（与 `docs/spec/browsing.md` 的「滚动复位」段「非排序变化不触发复位」相反）。
        BrowseScrollIndexStore.clearForTest()
        val key = recordKey()
        BrowseScrollIndexStore.noteEntered(key, readNow = 0)
        BrowseScrollIndexStore.record(key, indexAtLeave = 600)

        BrowseScrollIndexStore.noteEntered(key, readNow = 0)
        BrowseScrollIndexStore.notePlaced(key)
        BrowseScrollIndexStore.record(key, indexAtLeave = 0)
        assertEquals("放回请求之后用户的离场读数（含 0）都算数", 0, BrowseScrollIndexStore.valueFor(key))
    }

    @Test
    fun `同键的第二份组合不继承已放回标记 丢态残留照旧不写`() {
        // 评审 r2-b2 P2-1：`placed` 是**按屏**的标记，不能被同键的兄弟组合继承。
        // 序列（同键、中间不 clear）：进屏（基准 0）→ 离场 600 → 丢态回屏（基准 0）→ 请求放回
        // → 兄弟组合进屏（基准 0）→ 离场读到 0。
        // `notePlaced` 若不一并消费基准，兄弟组合的 `noteEntered` 只会 `putIfAbsent` 到旧基准（非 null）
        // ⇒ 不重立基准、不清 `placed` ⇒ 那份组合继承「已放回」⇒ 它离场的 0 把 600 冲掉。
        BrowseScrollIndexStore.clearForTest()
        val key = recordKey()
        BrowseScrollIndexStore.noteEntered(key, readNow = 0)
        BrowseScrollIndexStore.record(key, indexAtLeave = 600)
        BrowseScrollIndexStore.noteEntered(key, readNow = 0)
        BrowseScrollIndexStore.notePlaced(key)
        BrowseScrollIndexStore.noteEntered(key, readNow = 0)
        BrowseScrollIndexStore.record(key, indexAtLeave = 0)
        assertEquals("兄弟组合重新处于丢态口径：它的 0 不写进记录", 600, BrowseScrollIndexStore.valueFor(key))
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
        // 有界等待到「800 条真的被测量过」（票 #139 同形清扫）：若这一轮没轮到这次测量，`state` 还停在短帧那一轮，
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
     * 票 #111 r10 修复（评审 r9 P1-2）：A4 把会话快照改到**构造期**落帧之后，浏览页首帧就是那份短列表；
     * 而恢复索引的读在 `LaunchedEffect(pager, reverse)` 里 —— effect 体在本帧 composition + layout **之后**
     * 才跑 ⇒ 读到的已是被夹过的索引（本例实测 [effectRead] < 600）。所以「在任何一帧列表上屏之前读一次」
     * 那条不变式必须换个读点：在**离开这一屏的那一刻**（`onDispose`，事件时刻、还没经过短帧）记下索引，
     * 取它与 effect 读的**较大者**（夹只会把索引变小 ⇒ 较大的那个就是未夹的）。
     *
     * 本用例同时钉住两件事：① 机制（短帧先上屏 ⇒ effect 读被夹）；② [unclippedRestoredScrollIndex]
     * 的判据（取回未夹的那个）。拿掉修复（只留 effect 读）⇒ 位置丢。
     *
     * （票 #139：原先推一轮 [`layoutOnce`] 断言，若 `LaunchedEffect` 那一轮没跑过，`effectRead` 还是初值 `-1`，
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
 * 记录键的样例：默认那一层（连接 1、某个容器、名称档未复位）
 *
 * 用 `BrowseScrollResetKey` 当「代次」：换排序就换代次，与界面里两档滚动状态的复位键同源。
 */
private fun recordKey(
    connId: Long = 1L,
    containerId: String? = "smb://c/目录",
    mode: SortMode = SortMode.NAME,
) = BrowseScrollRecordKey(
    connId = connId,
    containerId = containerId,
    generation = BrowseScrollResetKey(mode = mode, direction = SortDirection.FORWARD, revision = 0, staleIds = null),
)

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

/**
 * 定高行列表：本文件**唯一**一份列表组合形状（50dp 一行、键取索引），三处机制用例共用
 *（票 #146 b1/1 去重：此前是两处内联 + 一份新 helper，同形三份）。
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
