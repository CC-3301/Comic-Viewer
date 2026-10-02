package com.cc3301.comicviewer.ui

import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.sort.SortDirection
import com.cc3301.comicviewer.core.source.SortMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 位置模块（[BrowseScrollPosition]）**脱离界面**的判据：两个外部依赖由调用方传入
 *（「位置存在哪」= [BrowseScrollStorage]，「现在在哪一层」= `browseChain`）⇒ 本文件是**纯 JUnit**：
 * 不碰 Compose 组合、不碰 `ServiceLocator`、也不走 SharedPreferences。
 *
 * 本文件钉三件事（都不需要界面）：
 * 1. **四个入口的往返**：进屏问「上次第几条」→ 离开记一次 → 再进屏读得到；换代次读不到（回顶部）；
 * 2. **「返回上一级再进来 ⇒ 回顶部」只靠注入的浏览链**（不靠容器 id 形态、也不自己去读全局单例）；
 * 3. **落位偏移与容器同一处口径**：网格档 12dp 换算成 px、列表档恒 0、索引 0 不吃留白。
 *
 * 界面那侧（真实组合、真尺寸像素、真落位）的判据仍在 `BrowseScrollRestoreTest`。
 */
class BrowseScrollPositionTest {

    /** 内存版「位置存在哪」：只存一条、用掉即清（生产那份落 SharedPreferences） */
    private class MemoryStorage(var record: BrowseScrollPositionRecord? = null) : BrowseScrollStorage {
        override fun read(): BrowseScrollPositionRecord? = record
        override fun write(layer: BrowseScrollLayer, index: Int) {
            record = BrowseScrollPositionRecord(layer, index)
        }

        override fun clear() {
            record = null
        }
    }

    private val layer = BrowseScrollLayer(connId = 7L, containerId = "smb://c/目录")
    private val parent = BrowseScrollLayer(connId = 7L, containerId = "smb://c")

    /** 「现在在哪一层」= 浏览链（最后一项 = 链顶）；由用例摆出来，模块不自己去读 */
    private val chain = mutableListOf<BrowseScrollLayer>()

    private fun position(storage: BrowseScrollStorage = MemoryStorage()) =
        BrowseScrollPosition(store = storage, browseChain = { chain.toList() })

    private fun resetKey(revision: Int = 0, mode: SortMode = SortMode.NAME) = BrowseScrollResetKey(
        mode = mode,
        direction = SortDirection.FORWARD,
        revision = revision,
        staleIds = null,
    )

    @Test
    fun `进屏问不到记录时在顶部 离开记一次之后照旧交得回来`() {
        val position = position()
        val generation = resetKey()

        assertEquals(
            "没记过的层：进屏初值就是 0（首屏在顶部）",
            0,
            position.enter(layer, generation, firstFrameItemCount = 800).index,
        )

        position.leave(layer, generation, indexAtLeave = 600)
        assertEquals("离开记一次：再进屏读到 600", 600, position.enter(layer, generation, firstFrameItemCount = 800).index)

        // 首帧比记录短：初值先夹到首帧末项（不越界），取够页后由首屏链放回（判据在 scrollRestoreTarget）
        assertEquals(
            "首帧只有 200 项：初值夹到 199",
            199,
            position.enter(layer, generation, firstFrameItemCount = 200).index,
        )
    }

    @Test
    fun `换排序代次之后读不到旧代次的记录`() {
        val storage = MemoryStorage()
        val position = position(storage)
        val name = resetKey(revision = 0)
        // 生产顺序：启动链先交回落地层（入口 ③）。本次落地不是这一层 ⇒ 收口时那条记录被**当场丢弃**，
        // 此后这一层一律读内存记录（盘上那份不会再被吃到）。
        position.startupLanding(parent)
        assertEquals("不是落地层的层：进屏在顶部", 0, position.enter(layer, name, firstFrameItemCount = 800).index)

        position.leave(layer, name, indexAtLeave = 600)
        assertEquals("同一代次：离开记下的交得回来", 600, position.enter(layer, name, firstFrameItemCount = 800).index)

        // 同一屏里换排序（新代次）：该层的「启动那一代」不是它 ⇒ 不吃盘上那条，读本代次内存记录（0）
        val modified = resetKey(revision = 1)
        assertEquals("换代次：回顶部", 0, position.enter(layer, modified, firstFrameItemCount = 800).index)
    }

    @Test
    fun `落地层用掉记录后 从上一级进来回顶部`() {
        val storage = MemoryStorage(BrowseScrollPositionRecord(layer, index = 600))
        val position = position(storage)
        val generation = resetKey()

        // 开机：启动链交回「这次落在哪一层」（入口 ③）
        position.startupLanding(layer)
        assertEquals(
            "落地层先保持原位（吃盘上那条一次性记录）",
            600,
            position.enter(layer, generation, firstFrameItemCount = 800).index,
        )

        // 离开这一层 → 用户走到上一级：上一级成为链顶并写盘（离屏写点与进屏写点都走 `record`）
        position.leave(layer, generation, indexAtLeave = 600)
        chain += parent
        position.record(connId = parent.connId, containerId = parent.containerId, index = 30)

        assertEquals(
            "从上一级再进来：回顶部（作废以一次为限，靠注入的浏览链判定）",
            0,
            position.enter(layer, generation, firstFrameItemCount = 800).index,
        )
    }

    @Test
    fun `进屏没交当下读数时不动进屏基准 离场读数照旧算数`() {
        val position = position()
        val generation = resetKey()

        // 组合期那一问（readNow = null）只是问初值，不登记进屏基准
        position.enter(layer, generation, firstFrameItemCount = 800)
        position.leave(layer, generation, indexAtLeave = 18)
        assertEquals("只问过初值的屏：离场读数照旧记下", 18, position.enter(layer, generation, firstFrameItemCount = 800).index)
    }

    @Test
    fun `落位偏移吃掉顶部内容留白 列表档与索引 0 不吃`() {
        val position = position()
        val generation = resetKey()
        position.leave(layer, generation, indexAtLeave = 26)

        val landing = position.enter(layer, generation, firstFrameItemCount = 800)
        assertEquals("网格档：初值偏移 = 顶部内容留白的真 px 值（12dp × 密度 3 = 36px）", 36, landing.offsetPx(isGrid = true, density = 3f))
        assertEquals("列表档没有顶部内容留白（`LazyColumn` 不设 contentPadding）⇒ 偏移恒 0", 0, landing.offsetPx(isGrid = false, density = 3f))

        // 容器与落位共用同一处口径：容器的内容留白就是 `browseTopContentPadding` 给的那个值
        assertEquals("网格档容器顶部内容留白 = 12dp（模块一处给出）", 12.dp, browseTopContentPadding(isGrid = true))
        assertEquals("列表档容器不设顶部内容留白", 0.dp, browseTopContentPadding(isGrid = false))

        val atTop = position.enter(layer, resetKey(revision = 1), firstFrameItemCount = 800)
        assertEquals("落在第 0 项不吃留白（那 12dp 是「停在顶部」的排版边距）", 0, atTop.offsetPx(isGrid = true, density = 3f))
    }

    @Test
    fun `位置已放回之后 用户的离场读数照旧算数`() {
        val position = position()
        val generation = resetKey()
        position.leave(layer, generation, indexAtLeave = 600)

        // 丢态回屏（进屏读到 0，记录仍是 600）→ 位置已放回（入口 ④）→ 用户滚回顶部离场
        val landing = position.enter(layer, generation, firstFrameItemCount = 800, readNow = 0)
        assertEquals("同代读一次：取数下限仍是未被夹的 600", 600, landing.restoredIndexNow)
        position.placed(layer, generation)
        position.leave(layer, generation, indexAtLeave = 0)
        assertEquals("放回之后用户滚到哪就是哪（含滚回顶部离场）", 0, position.enter(layer, generation, firstFrameItemCount = 800).index)
    }
}
