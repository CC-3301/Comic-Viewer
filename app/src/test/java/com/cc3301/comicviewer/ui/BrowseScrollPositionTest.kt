package com.cc3301.comicviewer.ui

import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.sort.SortDirection
import com.cc3301.comicviewer.core.source.SortMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 本文件里「层」的取样：父层 / 落地层 / 另一个不相关层（容器 id 是路径形态，父子以 `/` 相接） */
private const val PARENT = "smb://c/目录"
private const val LAYER = "smb://c/目录/子目录"
private const val OTHER_LAYER = "smb://c/另一目录"

/** 服务端 id 形态的来源（容器 id 不是路径）：口径 ② 只能靠「落地层还在不在浏览链上」判方向 */
private const val OPAQUE_LAYER = "series-42"
private const val OPAQUE_PARENT = "collection-7"

/**
 * 位置模块（[BrowseScrollPosition]）**脱离界面**的判据：两个外部依赖由调用方传入
 *（「位置存在哪」= [BrowseScrollStorage]，「现在在哪一层」= `browseChain`）⇒ 本文件是**纯 JUnit**：
 * 不碰 Compose 组合、不碰 `ServiceLocator`、也不走 SharedPreferences。
 *
 * 本文件钉五件事（都不需要界面）：
 * 1. **四个入口的往返**：进屏问「上次第几条」→ 离开记一次 → 再进屏读得到；换代次读不到（回顶部）；
 * 2. **「返回上一级再进来 ⇒ 回顶部」只靠注入的浏览链**（不靠容器 id 形态、也不自己去读全局单例）；
 * 3. **落位偏移与容器同一处口径**：网格档 12dp 换算成 px、列表档恒 0、索引 0 不吃留白；
 * 4. **丢态拒写那一族**（进屏基准 / 放回标记 / 同屏两个写点）：被夹小、没交回的读数不覆盖记录；
 * 5. **「启动那条落盘记录」的口径**：只喂启动那一代、用掉即清、非落地层当场丢弃、切后台不作废这一屏。
 *
 * 界面那一侧（真实组合、真尺寸像素、真落位、生产接线）的判据仍在 `BrowseScrollRestoreTest`。
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

    /** 摆出「现在在哪一层」：按生产那条链的顺序换掉链内容（最后一项 = 链顶） */
    private fun setChain(vararg containerIds: String?) {
        chain.clear()
        chain += containerIds.map { BrowseScrollLayer(connId = 7L, containerId = it) }
    }

    /**
     * 记录键的样例：默认那一层（连接 1、某个容器、名称档未复位）。
     *
     * 用 [BrowseScrollResetKey] 当「代次」：换排序就换代次，与界面里两档滚动状态的复位键同源。
     */
    private fun recordKey(
        connId: Long = 1L,
        containerId: String? = PARENT,
        mode: SortMode = SortMode.NAME,
    ) = BrowseScrollRecordKey(connId = connId, containerId = containerId, generation = resetKey(mode = mode))

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
        // 真正离屏（`onDispose`）：这一屏被拆掉 ⇒ 记完之后再结束进屏会话（`endEntrySession`），
        // 再进来是新的一屏（切后台不走这一步，见 `切后台只落盘不清进屏会话`）。
        position.leave(layer, generation, indexAtLeave = 600)
        position.endEntrySession(layer)
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
        // 两次 `leave` 都是**真正离屏**（这一屏被拆掉）：记完之后再结束进屏会话（[BrowseScrollPosition.endEntrySession]）
        position.leave(layer, generation, indexAtLeave = 600)
        position.endEntrySession(layer)

        // 丢态回屏（进屏读到 0，记录仍是 600）→ 位置已放回（入口 ④）→ 用户滚回顶部离场
        val landing = position.enter(layer, generation, firstFrameItemCount = 800, readNow = 0)
        assertEquals("同代读一次：取数下限仍是未被夹的 600", 600, landing.restoredIndexNow)
        position.placed(layer, generation)
        position.leave(layer, generation, indexAtLeave = 0)
        position.endEntrySession(layer)
        assertEquals("放回之后用户滚到哪就是哪（含滚回顶部离场）", 0, position.enter(layer, generation, firstFrameItemCount = 800).index)
    }

    // ---------------- 丢态拒写那一族（被夹小 / 没交回的读数不覆盖记录） ----------------

    @Test
    fun `离场记下的位置存在界面之外的记录里 重建后照旧交得回来`() {
        // 位置不只押 `rememberSaveable` 的交回：记录活在界面之外，与那份 saved state 是否交回无关。
        val position = position()
        val key = recordKey()
        // 进屏那一刻的读数：首次进入、没有记录 ⇒ 这一份滚动状态是干净的
        position.noteEntered(key, readNow = 0)
        position.record(key, indexAtLeave = 18)
        assertEquals("离场记下的位置重建后读得到", 18, position.valueFor(key))
    }

    @Test
    fun `进屏被丢到顶的那一次离场 不能覆盖记录`() {
        // 同一次过渡里出现过两个组合：先 `leave index=18`（用户真正停留的位置），
        // 90 ms 后一个「进屏读到 0」的组合又 `leave index=0`。后一次读到的 0 不是用户的位置——
        // 记录非 0 而进屏当下读到 0 ⇒ 这一份滚动状态没交回来（被系统短帧夹到顶）⇒ 那次离场不作数。
        val position = position()
        val key = recordKey()
        position.noteEntered(key, readNow = 0)
        position.record(key, indexAtLeave = 18)

        position.noteEntered(key, readNow = 0)
        position.record(key, indexAtLeave = 0)
        assertEquals("被丢到顶的那一次离场不覆盖记录", 18, position.valueFor(key))
    }

    @Test
    fun `状态交回来的组合里 滚回顶部的用户离场照旧写 0`() {
        // 这一条挡的是「一律不写 0」那种过头判据：进屏当下读到 18 = 状态真的交回来了 ⇒ 这个组合的
        // 离场读数算数，用户在顶部离场就该记 0（否则返回时会被拽回上次的位置）。
        val position = position()
        val key = recordKey()
        position.noteEntered(key, readNow = 0)
        position.record(key, indexAtLeave = 18)

        position.noteEntered(key, readNow = 18)
        position.record(key, indexAtLeave = 0)
        assertEquals("状态在手里的组合：顶部就是顶部", 0, position.valueFor(key))
    }

    @Test
    fun `恢复落地后上移到 5 的那一次离场照旧写 5`() {
        // 拒写不能把丢态那一屏的**整个屏期**都封死。
        // 序列：滚到 600 → 开书 → 返回（恢复链把 600 放回去）→ 上移到 5 → 再开书 → 返回 ⇒ 必须落到 5。
        // 旧写法：进屏读到 0 ⇒ 进屏基准被当成「状态没交回来」⇒ 离场读到的 5 被当成丢态拒掉，
        // 记录留在 600，返回后把用户拽回 600。
        val position = position()
        val key = recordKey()
        position.noteEntered(key, readNow = 0)
        position.record(key, indexAtLeave = 600)
        assertEquals("第一屏滚到 600 离场", 600, position.valueFor(key))

        // 返回：这一份滚动状态仍没交回来（读到 0），此后恢复链把位置放回 600、用户上移到 5
        position.noteEntered(key, readNow = 0)
        position.record(key, indexAtLeave = 5)
        assertEquals("恢复到 600 之后用户自己上移到的 5 算数（旧写法：被 600 挡住）", 5, position.valueFor(key))
    }

    @Test
    fun `短帧夹小的非 0 读数不覆盖记录`() {
        // 形态 600 → 184。旧写法把「读到非 0」当成「状态真交回来了」
        // ⇒ 恢复落地前（慢来源上为数秒）离场就拿 184 把 600 覆盖掉，用户位置永久降级。
        // 进屏读到 184 = 进屏那一下的残留（它不是用户停留的位置）⇒ 离场读数没动过 ⇒ 不得改写记录。
        val position = position()
        val key = recordKey()
        position.noteEntered(key, readNow = 0)
        position.record(key, indexAtLeave = 600)

        position.noteEntered(key, readNow = 184)
        position.record(key, indexAtLeave = 184)
        assertEquals("被短帧夹小的 184 不得覆盖 600", 600, position.valueFor(key))
        // 「系统夹索引不写」的两个读点取较大者口径不变：链交给界面的仍是未被夹的那个值
        assertEquals(600, unclippedRestoredScrollIndex(recordedOnLeave = 600, readNow = 184))
    }

    @Test
    fun `ON_STOP 写点与离屏同源 被夹小的读数不落盘`() {
        // 离屏与切后台（ON_STOP）两条写点共用 [BrowseScrollPosition.recordEffectivePosition]——
        // 先过 [BrowseScrollPosition.record] 的丢态判据，再落它过滤后的**生效值**。
        // 复现场景：滚到 600 → 从阅读器返回（恢复链尚未放回，进屏读到被夹小的 184）→ 按 HOME（ON_STOP）：
        // 裸读数是 184，落盘的必须是 600（否则盘上那条记录被丢态读数覆盖，第二次重启回顶部）。
        val position = position()
        position.startupLanding(BrowseScrollLayer(1L, PARENT))
        val key = recordKey()
        position.noteEntered(key, readNow = 0)
        position.record(key, indexAtLeave = 600)

        position.noteEntered(key, readNow = 184)
        position.recordEffectivePosition(
            key = key,
            rawIndex = 184,
            connId = 1L,
            containerId = PARENT,
        )

        assertEquals("盘上落的是生效值 600，不是被夹小的 184", 600, position.consumeAtStartupLanding(1L, PARENT))
    }

    @Test
    fun `同屏的第二个写点再读到同一份被夹小的读数 照旧被拒写也不落盘`() {
        // `record` 若**无条件**消费进屏基准，同屏的第二个写点（ON_STOP 之后再离屏 /
        // 再按一次 HOME）就没有基准可比 ⇒ 必然放行，被夹小的 184 会落进内存记录**和**磁盘，
        // 「系统夹索引不写」在这个窗口破掉。这里把 `recordEffectivePosition` 连调两次（同 `rawIndex = 184`）：
        // 第一次被拒写之后基准仍在，第二次照旧按「读数没动过」拒 ⇒ 内存记录与盘上都是 600。
        // 把「只在收下读数时消费基准」改回无条件消费时本用例应红（第二次调用记 184、盘上也是 184）。
        val position = position()
        position.startupLanding(BrowseScrollLayer(1L, PARENT))
        val key = recordKey()
        position.noteEntered(key, readNow = 0)
        position.record(key, indexAtLeave = 600)

        position.noteEntered(key, readNow = 184)
        repeat(2) {
            position.recordEffectivePosition(
                key = key,
                rawIndex = 184,
                connId = 1L,
                containerId = PARENT,
            )
        }

        assertEquals("同屏第二个写点照旧被拒：记录还是 600", 600, position.valueFor(key))
        assertEquals("盘上落的仍是 600，不是被夹小的 184", 600, position.consumeAtStartupLanding(1L, PARENT))
    }

    @Test
    fun `放回请求之后用户滚回顶部离场 记 0`() {
        // 拒写窗口不能在本屏一直开着。
        // 序列：丢态回屏（进屏基准 0、记录 600）→ 恢复链请求把 600 放回（[BrowseScrollPosition.notePlaced]）
        // → 用户滚回 0 离场 ⇒ 读数与基准同值（0），但本屏已请求过放回 ⇒ 必须记 0。
        // 旧写法（只看「读数没动过」）：0 被当成丢态残留拒掉，记录停在 600，返回后又把用户拽回 600
        //（与 `docs/spec/browsing.md` 的「滚动复位」段「非排序变化不触发复位」相反）。
        val position = position()
        val key = recordKey()
        position.noteEntered(key, readNow = 0)
        position.record(key, indexAtLeave = 600)

        position.noteEntered(key, readNow = 0)
        position.notePlaced(key)
        position.record(key, indexAtLeave = 0)
        assertEquals("放回请求之后用户的离场读数（含 0）都算数", 0, position.valueFor(key))
    }

    @Test
    fun `同键的第二份组合不继承已放回标记 丢态残留照旧不写`() {
        // `placed` 是**按屏**的标记，不能被同键的兄弟组合继承。
        // 序列（同键、中间不重摆）：进屏（基准 0）→ 离场 600 → 丢态回屏（基准 0）→ 请求放回
        // → 兄弟组合进屏（基准 0）→ 离场读到 0。
        // `notePlaced` 若不一并消费基准，兄弟组合的 `noteEntered` 只会 `putIfAbsent` 到旧基准（非 null）
        // ⇒ 不重立基准、不清 `placed` ⇒ 那份组合继承「已放回」⇒ 它离场的 0 把 600 冲掉。
        val position = position()
        val key = recordKey()
        position.noteEntered(key, readNow = 0)
        position.record(key, indexAtLeave = 600)
        position.noteEntered(key, readNow = 0)
        position.notePlaced(key)
        position.noteEntered(key, readNow = 0)
        position.record(key, indexAtLeave = 0)
        assertEquals("兄弟组合重新处于丢态口径：它的 0 不写进记录", 600, position.valueFor(key))
    }

    // ---------------- 换代次／作废那一族（层 + 复位代次） ----------------

    @Test
    fun `没记录过的层读 0 且不同层不同代次各记各的`() {
        val position = position()
        val name = recordKey()
        val modified = recordKey(mode = SortMode.MODIFIED_TIME)
        val otherLayer = recordKey(containerId = OTHER_LAYER)
        assertEquals("没记过的层：读 0（首屏在顶部）", 0, position.valueFor(name))

        position.noteEntered(name, readNow = 0)
        position.record(name, indexAtLeave = 18)
        assertEquals("换排序 = 换复位代次：新代次读不到旧记录（回顶部）", 0, position.valueFor(modified))
        assertEquals("另一层就读另一层的记录", 0, position.valueFor(otherLayer))
        position.noteEntered(otherLayer, readNow = 0)
        position.record(otherLayer, indexAtLeave = 7)
        assertEquals("两层互不干扰", 18, position.valueFor(name))
        assertEquals("两层互不干扰", 7, position.valueFor(otherLayer))
    }

    @Test
    fun `换代丢掉该层其他代次的记录 排序 A B A 也回顶部`() {
        // 代次口径收口：`BrowseScrollResetKey` 现含 `SortSettingStore.revision`（每次排序写入 +1）
        // ⇒ 排序 A→B→A 是**新键**，界面路径本就读不到旧记录。本用例钉的是 store 那一侧的**不变式**：
        // 换代必须丢掉该层其他代次的记录（少了它，单测直调 store 或将来复用一个键的路径会把旧位置读回来）。
        // `docs/spec/browsing.md`「排序在展示层翻转 / 滚动复位」要求排序设置变化即回顶部。
        val position = position()
        val name = recordKey() // 名称档 = 键 A
        val modified = recordKey(mode = SortMode.MODIFIED_TIME) // 修改时间档 = 键 B
        position.noteEntered(name, readNow = 0)
        position.record(name, indexAtLeave = 600)
        assertEquals("名称档停留过 600", 600, position.valueFor(name))

        // 点「修改时间」：换代登记 ⇒ 丢掉名称档那份
        position.beginGeneration(modified)
        assertEquals("换到 B：读不到 A 的记录（回顶部）", 0, position.valueFor(modified))
        assertEquals("A 的记录已被丢掉", 0, position.valueFor(name))

        // 切回「名称」：**store 侧复用同一个键对象**（`name`），但旧记录已在换代那一刻丢掉 ⇒ 照旧回顶部
        // （界面路径此刻拿到的是**新键**——见本用例表头；这里钉的是 store 侧的那条不变式）
        position.beginGeneration(name)
        assertEquals("切回 A：同一个键，但旧记录已丢 ⇒ 回顶部", 0, position.valueFor(name))
    }

    @Test
    fun `换代后旧代次的离场读数写不回来`() {
        // 换代那一刻旧的滚动状态也会 dispose 一次、产一个「旧代次离场读数」（见 `browseRestoreLeaveLine` 的
        // 「多行是正常的」段）；若它被写进记录，[BrowseScrollPosition.beginGeneration] 刚丢掉的那份立刻
        // 又回来了（下一次读到该键仍是旧位置）。`record` 因此按「该层当下代次」拒收旧代次的离场读数。
        val position = position()
        val name = recordKey()
        val modified = recordKey(mode = SortMode.MODIFIED_TIME)
        position.noteEntered(name, readNow = 0)
        position.record(name, indexAtLeave = 600)

        position.beginGeneration(modified) // 点「修改时间」：换代
        position.record(name, indexAtLeave = 600) // 旧滚动状态的 dispose 又写一次
        assertEquals("旧代次的离场读数不得把刚丢掉的位置写回来", 0, position.valueFor(name))
    }

    @Test
    fun `换代只丢该层旧代次 本代次与别的层照旧`() {
        val position = position()
        val name = recordKey()
        val modified = recordKey(mode = SortMode.MODIFIED_TIME)
        val otherLayer = recordKey(containerId = OTHER_LAYER)

        position.beginGeneration(name) // 该层当下代次 = 名称档
        position.beginGeneration(otherLayer) // 另一层互不相干
        position.noteEntered(name, readNow = 0)
        position.record(name, indexAtLeave = 600)
        position.noteEntered(otherLayer, readNow = 0)
        position.record(otherLayer, indexAtLeave = 7)

        position.beginGeneration(modified) // 该层换代：只丢名称档
        assertEquals("本层旧代次被丢掉", 0, position.valueFor(name))
        assertEquals("别的层的记录不受影响", 7, position.valueFor(otherLayer))

        // 本代次自己的记录照旧（同一代次里离开 / 返回仍要恢复位置）
        position.noteEntered(modified, readNow = 0)
        position.record(modified, indexAtLeave = 5)
        position.beginGeneration(modified) // 同代次重复登记：什么都不丢
        assertEquals("本代次的记录照旧", 5, position.valueFor(modified))
    }

    // ---------------- 「启动那条落盘记录」那一族（用掉即清 / 非落地层丢弃 / 作废） ----------------

    @Test
    fun `重启恢复位置 落盘记录用掉即清`() {
        // 现行口径第 2/3 条：「离开 App 时所处的那一层 + 该层的位置」单独落盘一份（只存这一条，不存历史），
        // 重启落在这一层时用它一次就清掉；之后（跳去别的文件夹）读不到记录 ⇒ 回顶部。
        val position = position()
        position.startupLanding(BrowseScrollLayer(7L, "dir-deep"))

        // 写点见 `BrowserScreen` 的进屏 / 离场（这里直接模拟「离开 App 时留下的那一份」）
        position.record(connId = 7L, containerId = "dir-deep", index = 600)

        assertEquals("重启落在这一层：用它给出位置", 600, position.consumeAtStartupLanding(7L, "dir-deep"))
        assertNull("用掉即清：同一次进程里再读已经没有记录", position.consumeAtStartupLanding(7L, "dir-deep"))
        assertNull("另一个文件夹：读不到记录 ⇒ 回顶部", position.consumeAtStartupLanding(7L, "dir-other"))
    }

    @Test
    fun `启动那条记录用掉后 本进程内不再交回同一层`() {
        // 「盘上那条只喂启动那一代」：收口之后若退化成「按层取回那条记录」，
        // 界面离场时重新写下的那份又会在再进来时被恢复。
        // 判别力：去掉 `consumeAtStartupLanding` 里 `if (landed) return null` 那一句时，下面第二条断言读到 420。
        val position = position()
        position.startupLanding(BrowseScrollLayer(7L, "dir-deep"))
        position.record(connId = 7L, containerId = "dir-deep", index = 600)
        assertEquals("启动那一刻照旧给值", 600, position.consumeAtStartupLanding(7L, "dir-deep"))

        // 落地层里走过一次：盘上被重新写上一份（下一次重启要用它）
        position.record(connId = 7L, containerId = "dir-deep", index = 420)
        assertNull("本进程内不再交回同一层", position.consumeAtStartupLanding(7L, "dir-deep"))
        assertNull("别的层也读不到那条记录 ⇒ 回顶部", position.consumeAtStartupLanding(7L, "dir-other"))
    }

    @Test
    fun `启动链退化落到记录那一层 落地层由启动链交回 记录照旧恢复并用掉`() {
        // 真正落地的层会被启动链的**退化**支改写（上次停在阅读器，而这次启动链因「上次那本书已不是书」
        // 退化到浏览层）——真正落地的正是记录那一层，要恢复到原位置并用掉记录。
        // 落地层是**启动链交回**的输入（本模块不再自己推导）：交回的就是记录那一层时就照旧恢复。
        val position = position()
        position.record(connId = 7L, containerId = "dir-deep", index = 600)
        // 启动链（`AppNav` 落浏览层那一支）在导航前把已定的落地层交回 store
        position.startupLanding(BrowseScrollLayer(7L, "dir-deep"))

        assertEquals("退化落到记录那一层：恢复原位置", 600, position.consumeAtStartupLanding(7L, "dir-deep"))
        assertNull("用掉即清：同一次进程里再读已经没有记录", position.consumeAtStartupLanding(7L, "dir-deep"))
    }

    @Test
    fun `落地已定丢弃非落地层的那条记录 之后走进记录层也是顶部`() {
        // 重启落在**非记录层**时，盘上那条记录要**当场丢弃**——
        // 用户随后走进记录那一层也是顶部，而不是把重启前的位置恢复回来（「重启后只有落地那一层有记录」）。
        // 去掉那一步「丢弃」时本用例应红（第二次调用会命中读到 600）。
        // 「非落地层」在这里是**已定**的非浏览层（不是「还没交回」，见下一条用例）。
        val position = position()
        position.startupLanding(null) // 本次落地 = 首页（已定的非浏览层）
        position.record(connId = 7L, containerId = "dir-deep", index = 600)

        assertNull("落地已定：这一层不是落地层 ⇒ 当场丢弃、不给值", position.consumeAtStartupLanding(7L, "dir-deep"))
        assertNull("之后走进记录那一层也是顶部（记录已被丢弃，而不是留在那儿等命中）", position.consumeAtStartupLanding(7L, "dir-deep"))
    }

    @Test
    fun `系统还原回退栈 界面先组合拿到值 交回之后照旧用掉`() {
        // 「进程被杀后重建」早退支的落地层就是还原出来的那个浏览层，而那一支原先从不交回
        // ⇒ `landingLayer == null` 被判成「一定不是」⇒ 记录在读取前被 `clear()`。
        // 序列：停在 dir-deep 的 600 → 按 HOME（ON_STOP 写点落盘）→ 进程被系统回收 → 从最近任务回 App。
        // 这里把它拆成两拍，两拍都要成立：
        // ① 还原出的浏览层**当帧就是栈顶**，它的组合早于启动 effect 的交回 ⇒ 那时还没交回，但问的正是记录那一层
        //（该支的落地层就是这一层）⇒ 先把值给它、**不消费**（记录还在，交回之后才收口）；
        // ② 启动 effect 走早退支把这一层交回 ⇒ 收口时照旧「恢复并用掉」。
        // 判别力：把「还没交回」那一态改回「一定不是」（`landed` 一置位就按 null 落地层 clear），
        // 第一次调用即返回 null、本用例第一条断言当场红。
        val position = position()
        // 还没交回落地层：本次启动链一个层都没交回
        position.record(connId = 7L, containerId = "dir-deep", index = 600)

        assertEquals("还没交回但问的正是记录那一层：先给值", 600, position.consumeAtStartupLanding(7L, "dir-deep"))
        // 早退支交回：系统还原出来的那一层
        position.startupLanding(BrowseScrollLayer(7L, "dir-deep"))
        assertEquals(
            "交回之后收口：落地层就是记录那一层 ⇒ 照旧恢复（上面那次读没消费它）",
            600,
            position.consumeAtStartupLanding(7L, "dir-deep"),
        )
        assertNull("用掉即清：同一次进程里再读已经没有记录", position.consumeAtStartupLanding(7L, "dir-deep"))
    }

    @Test
    fun `未交回落地层时不销毁记录 交回之后照旧恢复`() {
        // 未交回时不得销毁记录：「还没交回」必须与「已定的非浏览层」分开——前者既不给别的层值、
        // 也**不 clear()**（否则将来再漏一个调用点就又静默销毁一次记录）。
        // 判别力：改回「未交回 = 一定不是」时，下面第二条断言会读到 null（记录已被销毁）。
        val position = position()
        position.record(connId = 7L, containerId = "dir-deep", index = 600)

        assertNull("还没交回：别的层不给值", position.consumeAtStartupLanding(7L, "dir-other"))
        position.startupLanding(BrowseScrollLayer(7L, "dir-deep"))
        assertEquals("记录没被销毁：交回之后照旧恢复", 600, position.consumeAtStartupLanding(7L, "dir-deep"))
        assertNull("用掉即清", position.consumeAtStartupLanding(7L, "dir-deep"))
    }

    @Test
    fun `落盘记录根层也能往返 层不符不给值`() {
        val rootLayer = position()
        rootLayer.startupLanding(BrowseScrollLayer(7L, null))
        rootLayer.record(connId = 7L, containerId = null, index = 42)
        assertEquals("根层（容器 id 为 null）原样往返", 42, rootLayer.consumeAtStartupLanding(7L, null))

        // 另一连接上的同容器名：层不符不给值（换一份干净的模块 + 存储）
        val otherConn = position()
        otherConn.startupLanding(BrowseScrollLayer(7L, null))
        otherConn.record(connId = 7L, containerId = "dir-x", index = 9)
        assertNull("另一连接上的同容器名：层不符不给值", otherConn.consumeAtStartupLanding(8L, "dir-x"))
    }

    // ---------------- 作废那一族（离开落地层、再从上一级进来 ⇒ 回顶部） ----------------

    @Test
    fun `启动那条落盘记录用掉后 离开这一层再从上一级进来回到顶部`() {
        // 现行口径第 2 条：重启落地层**先保持原位**，那条落盘记录用掉即清；
        // **离开这一层、再从上一级进来 ⇒ 回顶部**（「本次启动内其它层的位置记录」照旧恢复）。
        //
        // 判据不是「第一个离屏写点」而是**浏览链上的两条**：离屏写点只知道这一层走了、不知道走去哪
        //（进阅读器 / 进子目录 / 回上一级形态相同），无差别作废会把那两条也牺牲掉（同文件另两条用例钉它们）；
        // 判据要求「写盘的那一层就是此刻的链顶」**且**「落地层已不在链上」（两条同时成立，见 `noteLayerWritten`）。
        // 这里按生产顺序摆出来：落地层离场写一次（自己写，不登记）→ 退回上一级（链上把本层弹掉）、
        // 上一级成为链顶并写盘 → 再进来。
        val position = position()
        position.startupLanding(BrowseScrollLayer(7L, LAYER))
        position.record(connId = 7L, containerId = LAYER, index = 600)
        val key = recordKey(connId = 7L, containerId = LAYER)

        // ① 启动落地：界面读到盘上那条（用掉）⇒ 这一层先保持原位
        assertEquals("落地层先保持原位", 600, position.consumeAtStartupLanding(7L, LAYER))

        // ② 离开这一层（离屏写点）：照旧记下位置（这一条去向本身不决定作废）
        position.noteEntered(key, readNow = 600)
        position.recordEffectivePosition(key, rawIndex = 600, connId = 7L, containerId = LAYER)
        assertEquals("离场照旧记下位置", 600, position.valueFor(key))

        // ③ 用户走到了这一层的**上一级**：链上那条已经把本层弹掉 ⇒ 链顶 = PARENT、落地层已不在链上
        setChain(PARENT)
        position.record(connId = 7L, containerId = PARENT, index = 30)

        // ④ 从上一级再进来：回顶部（初值 0）
        assertEquals(
            "从上一级进来回顶部",
            0,
            initialScrollItemIndex(
                recordedIndex = restoredIndexOnLeaveFor(
                    diskAtStartup = position.consumeAtStartupLanding(7L, LAYER),
                    startupGeneration = key.generation,
                    currentGeneration = key.generation,
                    inMemoryIndex = position.valueFor(key),
                ),
                firstFrameItemCount = 800,
            ),
        )

        // ⑤ 之后的离场照旧记：作废「以一次为限」——`resetOnReentryFromParent` 取用一次即消，
        // 这一次用户真的滚到了 300 再离场，读数就该照旧记下来（少了这一条，「作废」会退化成永久失效）。
        position.noteEntered(key, readNow = 0)
        position.record(key, indexAtLeave = 300)
        assertEquals("之后的离场照旧记", 300, position.valueFor(key))
    }

    @Test
    fun `落地层从阅读器返回 保持原位`() {
        // 现行口径第 1 条（当次启动内一切导航都保持位置）在**落地层**上照旧成立：
        // 进阅读器时**没有任何浏览层写盘** ⇒ 不登记「从上一级进来」⇒ 返回照旧恢复原位。
        // 判别力（改动前本用例红）：旧写法在落地层的**第一个离屏写点**就无差别丢掉读数
        // ⇒ 返回时既无盘记录也无内存记录，读到 0（顶部）。
        val position = position()
        position.startupLanding(BrowseScrollLayer(7L, LAYER))
        position.record(connId = 7L, containerId = LAYER, index = 600)
        val key = recordKey(connId = 7L, containerId = LAYER)
        assertEquals("落地层先保持原位", 600, position.consumeAtStartupLanding(7L, LAYER))

        // 点书进阅读器：落地层的离屏写点
        position.noteEntered(key, readNow = 600)
        position.recordEffectivePosition(key, rawIndex = 600, connId = 7L, containerId = LAYER)

        // 从阅读器返回：读回 600
        assertEquals(
            "从阅读器返回保持原位",
            600,
            initialScrollItemIndex(
                recordedIndex = restoredIndexOnLeaveFor(
                    diskAtStartup = position.consumeAtStartupLanding(7L, LAYER),
                    startupGeneration = key.generation,
                    currentGeneration = key.generation,
                    inMemoryIndex = position.valueFor(key),
                ),
                firstFrameItemCount = 800,
            ),
        )
    }

    @Test
    fun `落地层进出子目录 保持原位`() {
        // 同上，另一半：进子目录（写盘的是本层的**下级**）→ 返回本层照旧保持原位。
        // 判别力（改动前本用例红）：旧写法按「第一个离屏写点」作废，返回时读到 0。
        val position = position()
        position.startupLanding(BrowseScrollLayer(7L, LAYER))
        position.record(connId = 7L, containerId = LAYER, index = 600)
        val key = recordKey(connId = 7L, containerId = LAYER)
        assertEquals("落地层先保持原位", 600, position.consumeAtStartupLanding(7L, LAYER))

        // 进子目录：落地层**还在浏览链上**（它只是被压了一层，没被退回）——这正是与 id 形态无关的那半个判据
        setChain(LAYER, "$LAYER/子目录")
        // 落地层的离屏写点，随后子目录那一层组合并写盘（下级）
        position.noteEntered(key, readNow = 600)
        position.recordEffectivePosition(key, rawIndex = 600, connId = 7L, containerId = LAYER)
        position.record(connId = 7L, containerId = "$LAYER/子目录", index = 0)

        // 出子目录回到本层：保持原位
        assertEquals(
            "出子目录回到本层保持原位",
            600,
            initialScrollItemIndex(
                recordedIndex = restoredIndexOnLeaveFor(
                    diskAtStartup = position.consumeAtStartupLanding(7L, LAYER),
                    startupGeneration = key.generation,
                    currentGeneration = key.generation,
                    inMemoryIndex = position.valueFor(key),
                ),
                firstFrameItemCount = 800,
            ),
        )
    }

    @Test
    fun `服务端 id 形态的来源 靠浏览链判方向 走到上级再进来也回顶部`() {
        // 容器 id 不是路径形态（服务端 id 来源）时层关系判不出前后，
        // 方向靠与 id 形态无关的那一条——**落地层已经不在浏览链上**（注入的那条链）。
        // 作废以**一次**为限：再进来那一次起回到「当次启动内非排序变化保持位置」。
        val position = position()
        position.startupLanding(BrowseScrollLayer(7L, OPAQUE_LAYER))
        position.record(connId = 7L, containerId = OPAQUE_LAYER, index = 600)
        val key = recordKey(connId = 7L, containerId = OPAQUE_LAYER)
        assertEquals("落地层先保持原位", 600, position.consumeAtStartupLanding(7L, OPAQUE_LAYER))

        // 离场（照旧记下位置）
        position.noteEntered(key, readNow = 600)
        position.recordEffectivePosition(key, rawIndex = 600, connId = 7L, containerId = OPAQUE_LAYER)

        // 走到上一级：落地层已不在浏览链上（服务端 id 形态，路径前缀判不出）
        setChain(OPAQUE_PARENT)
        position.record(connId = 7L, containerId = OPAQUE_PARENT, index = 30)

        assertEquals(
            "从上一级进来回顶部（与容器 id 形态无关）",
            0,
            initialScrollItemIndex(
                recordedIndex = restoredIndexOnLeaveFor(
                    diskAtStartup = position.consumeAtStartupLanding(7L, OPAQUE_LAYER),
                    startupGeneration = key.generation,
                    currentGeneration = key.generation,
                    inMemoryIndex = position.valueFor(key),
                ),
                firstFrameItemCount = 800,
            ),
        )
    }

    @Test
    fun `不是落地层的层 走到上级再进来照旧保持位置`() {
        // 作用域必须只绑**启动那次真正落地的那一层**——别的层走到上级再进来
        // 照旧按「当次启动内非排序变化保持位置」记（现行口径第 1 条）。
        // 判别力（改动前本用例红）：旧写法把「盘上指针当前指着的层」当落地层，这一层会被一并作废。
        val position = position()
        position.startupLanding(BrowseScrollLayer(7L, LAYER))
        position.record(connId = 7L, containerId = LAYER, index = 600)
        assertEquals("启动落地层用掉记录", 600, position.consumeAtStartupLanding(7L, LAYER))

        // 走进另一个层（不是落地层），滚到 40 → 走到它的上级 → 再进来
        setChain(LAYER, OTHER_LAYER)
        val other = recordKey(connId = 7L, containerId = OTHER_LAYER)
        position.noteEntered(other, readNow = 40)
        position.recordEffectivePosition(other, rawIndex = 40, connId = 7L, containerId = OTHER_LAYER)
        position.record(connId = 7L, containerId = PARENT, index = 30)

        assertEquals("不是落地层：走过上级再进来照旧保持位置", 40, position.valueFor(other))
    }

    @Test
    fun `从上一级进落地层那一刻 上级的写点不登记 从阅读器返回照旧保持原位`() {
        // 设备上过渡期的写点是**乱序**的——用户从上一级 PARENT **进入**落地层 LAYER 时，
        // PARENT 的离屏写点在 LAYER 已经上屏之后才落。只看「写盘的那一层是落地层的上级」会把这一步
        // 读成「用户从 LAYER 退回 PARENT」⇒ 闩锁误置 ⇒ 下一次「从阅读器返回 / 出子目录回本层」被作废、回顶部。
        // 新判据的两条此刻都不成立：写盘层不是链顶（链顶 = LAYER）+ 落地层正在链上。
        // 判别力（改动前本用例红）：旧判据只看路径前缀 ⇒ 登记被置上 ⇒ 最后一条断言读到 0。
        val position = position()
        position.startupLanding(BrowseScrollLayer(7L, LAYER))
        position.record(connId = 7L, containerId = LAYER, index = 600)
        val key = recordKey(connId = 7L, containerId = LAYER)
        assertEquals("落地层先保持原位", 600, position.consumeAtStartupLanding(7L, LAYER))

        // 用户从 PARENT 进到 LAYER：链顶是 LAYER（他已经站上去了）；PARENT 的写点这时才落
        setChain(PARENT, LAYER)
        position.record(connId = 7L, containerId = PARENT, index = 30)

        // 点书进阅读器：落地层自己的离屏写点（本层被早退挡掉，不登记）
        position.noteEntered(key, readNow = 600)
        position.recordEffectivePosition(key, rawIndex = 600, connId = 7L, containerId = LAYER)
        assertEquals("离场照旧记下位置", 600, position.valueFor(key))

        // 从阅读器返回：照旧保持原位
        assertEquals(
            "上级那一刻的写点不登记 ⇒ 从阅读器返回照旧保持原位",
            600,
            initialScrollItemIndex(
                recordedIndex = restoredIndexOnLeaveFor(
                    diskAtStartup = position.consumeAtStartupLanding(7L, LAYER),
                    startupGeneration = key.generation,
                    currentGeneration = key.generation,
                    inMemoryIndex = position.valueFor(key),
                ),
                firstFrameItemCount = 800,
            ),
        )
    }

    @Test
    fun `落地层从阅读器返回与出子目录回本层 都保持原位`() {
        // 两种情形都走不到登记——「进阅读器」时没有任何浏览层以**链顶**身份写盘
        //（落地层自己的写点被本层的早退挡掉）；「进 / 出子目录」时链顶是子层（写它那刻落地层还在链上），
        // 子层的离屏写点又赶不上链顶。
        val position = position()
        position.startupLanding(BrowseScrollLayer(7L, LAYER))
        position.record(connId = 7L, containerId = LAYER, index = 600)
        val key = recordKey(connId = 7L, containerId = LAYER)
        assertEquals("落地层先保持原位", 600, position.consumeAtStartupLanding(7L, LAYER))

        val child = "$LAYER/子目录"
        // 进子目录：链顶是子层，落地层还在链上（它只是被压了一层）
        setChain(LAYER, child)
        position.noteEntered(key, readNow = 600)
        position.recordEffectivePosition(key, rawIndex = 600, connId = 7L, containerId = LAYER)
        position.record(connId = 7L, containerId = child, index = 0)
        // 出子目录：链顶回到本层，子层的离屏写点在这之后才落
        setChain(LAYER)
        position.record(connId = 7L, containerId = child, index = 0)
        assertEquals(
            "出子目录回本层保持原位",
            600,
            initialScrollItemIndex(
                recordedIndex = restoredIndexOnLeaveFor(
                    diskAtStartup = position.consumeAtStartupLanding(7L, LAYER),
                    startupGeneration = key.generation,
                    currentGeneration = key.generation,
                    inMemoryIndex = position.valueFor(key),
                ),
                firstFrameItemCount = 800,
            ),
        )

        // 点书进阅读器 → 返回：这一路没有浏览层以链顶身份写盘
        position.noteEntered(key, readNow = 600)
        position.recordEffectivePosition(key, rawIndex = 600, connId = 7L, containerId = LAYER)
        assertEquals(
            "从阅读器返回保持原位",
            600,
            initialScrollItemIndex(
                recordedIndex = restoredIndexOnLeaveFor(
                    diskAtStartup = position.consumeAtStartupLanding(7L, LAYER),
                    startupGeneration = key.generation,
                    currentGeneration = key.generation,
                    inMemoryIndex = position.valueFor(key),
                ),
                firstFrameItemCount = 800,
            ),
        )
    }

    @Test
    fun `离开落地层到上一级再进来回顶部 要求上一级成为链顶并写盘`() {
        // 口径 ② 的正例（容器 id 是**路径**形态）：登记要求上一级那一层的写点**在它自己成为链顶之后**才落
        //（链上那条已经把落地层弹掉）⇒ 新判据的两条同时成立。作废以**一次**为限。
        // 判别力（改动前本用例红）：旧判据只看路径前缀，链上有没有弹掉落地层都不影响
        // ⇒ 第一段那个「用户还在本层时上级那一刻的乱序写点」也会置上登记。
        val position = position()
        position.startupLanding(BrowseScrollLayer(7L, LAYER))
        position.record(connId = 7L, containerId = LAYER, index = 600)
        val key = recordKey(connId = 7L, containerId = LAYER)
        assertEquals("落地层先保持原位", 600, position.consumeAtStartupLanding(7L, LAYER))

        // 第一段：用户还在本层（链顶 = 本层），PARENT 那一刻的乱序写点 ⇒ 不登记
        setChain(PARENT, LAYER)
        position.record(connId = 7L, containerId = PARENT, index = 30)
        position.noteEntered(key, readNow = 600)
        position.recordEffectivePosition(key, rawIndex = 600, connId = 7L, containerId = LAYER)
        assertEquals(
            "还没离开本层：不登记，位置照旧",
            600,
            initialScrollItemIndex(
                recordedIndex = restoredIndexOnLeaveFor(
                    diskAtStartup = position.consumeAtStartupLanding(7L, LAYER),
                    startupGeneration = key.generation,
                    currentGeneration = key.generation,
                    inMemoryIndex = position.valueFor(key),
                ),
                firstFrameItemCount = 800,
            ),
        )

        // 第二段：退回上一级（链上那条已经把本层弹掉）⇒ PARENT 成为链顶并写盘 ⇒ 登记
        setChain(PARENT)
        position.record(connId = 7L, containerId = PARENT, index = 30)

        // 从上一级再进来 ⇒ 回顶部
        assertEquals(
            "从上一级进来回顶部",
            0,
            initialScrollItemIndex(
                recordedIndex = restoredIndexOnLeaveFor(
                    diskAtStartup = position.consumeAtStartupLanding(7L, LAYER),
                    startupGeneration = key.generation,
                    currentGeneration = key.generation,
                    inMemoryIndex = position.valueFor(key),
                ),
                firstFrameItemCount = 800,
            ),
        )

        // 作废以一次为限：这一次用户真的滚到 300 再离场，读数照旧记下来
        position.noteEntered(key, readNow = 0)
        position.record(key, indexAtLeave = 300)
        assertEquals("之后的离场照旧记", 300, position.valueFor(key))
    }

    @Test
    fun `不透明容器 id 上 两条判据与服务端来源一视同仁`() {
        // 容器 id 不是路径形态（服务端 id 来源）时层关系判不出前后——新判据两条都只看
        // 「连接 + 容器」与浏览链，因此不透明 id 与路径形态一视同仁。
        val position = position()
        position.startupLanding(BrowseScrollLayer(7L, OPAQUE_LAYER))
        position.record(connId = 7L, containerId = OPAQUE_LAYER, index = 600)
        val key = recordKey(connId = 7L, containerId = OPAQUE_LAYER)
        assertEquals("落地层先保持原位", 600, position.consumeAtStartupLanding(7L, OPAQUE_LAYER))

        // 第一段：用户还在本层，上一级那一刻的乱序写点 ⇒ 不登记
        setChain(OPAQUE_PARENT, OPAQUE_LAYER)
        position.record(connId = 7L, containerId = OPAQUE_PARENT, index = 30)
        position.noteEntered(key, readNow = 600)
        position.recordEffectivePosition(key, rawIndex = 600, connId = 7L, containerId = OPAQUE_LAYER)
        assertEquals(
            "还没离开本层：不登记，位置照旧",
            600,
            initialScrollItemIndex(
                recordedIndex = restoredIndexOnLeaveFor(
                    diskAtStartup = position.consumeAtStartupLanding(7L, OPAQUE_LAYER),
                    startupGeneration = key.generation,
                    currentGeneration = key.generation,
                    inMemoryIndex = position.valueFor(key),
                ),
                firstFrameItemCount = 800,
            ),
        )

        // 第二段：退回上一级（链上已经没有本层）⇒ 上一级成为链顶并写盘 ⇒ 登记 ⇒ 再进来回顶部
        setChain(OPAQUE_PARENT)
        position.record(connId = 7L, containerId = OPAQUE_PARENT, index = 30)
        assertEquals(
            "从上一级进来回顶部（与容器 id 形态无关）",
            0,
            initialScrollItemIndex(
                recordedIndex = restoredIndexOnLeaveFor(
                    diskAtStartup = position.consumeAtStartupLanding(7L, OPAQUE_LAYER),
                    startupGeneration = key.generation,
                    currentGeneration = key.generation,
                    inMemoryIndex = position.valueFor(key),
                ),
                firstFrameItemCount = 800,
            ),
        )
    }

    // ---------------- 进入 / 离开这一屏的边界（切后台不算离屏） ----------------

    @Test
    fun `切后台只落盘不清进屏会话 回前台照旧吃启动那条`() {
        // 切后台（生命周期 `ON_STOP`）没有拆掉这一屏 ⇒ 进屏会话（启动那一代 + 它吃到的盘上那条）照旧活着；
        // 真正离屏（`onDispose`）才结束它（[BrowseScrollPosition.endEntrySession]）。
        // 判别力：让 `leave` 也结束会话时，第二条断言读到 700（切后台时的内存记录）而不是启动那条 600。
        val storage = MemoryStorage(BrowseScrollPositionRecord(BrowseScrollLayer(7L, LAYER), index = 600))
        val position = position(storage)
        val generation = resetKey()
        val nested = BrowseScrollLayer(7L, LAYER)
        position.startupLanding(nested)

        assertEquals("重启落在这一层：吃盘上那条", 600, position.enter(nested, generation, firstFrameItemCount = 800).index)

        // 用户滚到 700 → 切后台（ON_STOP 那个写点）：只记一次
        position.leave(nested, generation, indexAtLeave = 700)

        // 回前台、effect 重跑：这一屏没结束 ⇒ 仍吃启动那条（600），不是刚写下的 700
        assertEquals("切后台没有结束这一屏：启动那条照旧", 600, position.enter(nested, generation, firstFrameItemCount = 800).index)

        // 真正离屏：这一屏结束 ⇒ 再进来是新的一屏，读到本代次记下的 700
        position.endEntrySession(nested)
        assertEquals("真正离屏后再进来：本代次的记录 700", 700, position.enter(nested, generation, firstFrameItemCount = 800).index)
    }
}
