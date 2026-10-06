package com.cc3301.comicviewer.ui.nav

import com.cc3301.comicviewer.core.nav.BrowseLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 浏览层路径菜单的判定（spec 故事 55 / `docs/spec/shell.md`「顶栏标题路径菜单」）。
 *
 * 判据是**菜单项本身**（文案 + 目标层），不看渲染：四个边界各一条用例——空链与「链首不是起点层」
 * 不给入口、停在起点层不弹、停在一级目录只有 `/`、更深层是 `/` + 一级目录。
 *
 * 判别力：把 `browseJumpTargets` 的 `chain.size >= 3` 改成 `>= 2` 会多出「一级目录」一项（`停在一级目录`
 * 与 `停在更深层` 两条变红）；删掉链首那道容器判据，「链首不是起点层」一条变红；
 * 一级目录文案改自己手写（不走 [com.cc3301.comicviewer.ui.browserTitle]）时兜底那条变红。
 *
 * 夹具里没有「链首与更深层不同连接」那种链：浏览链天然同连接，写出来只有 3 个相同 connId 可用，
 * 断言什么都区分不出来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseJumpTargetsTest {

    private fun layer(containerId: String?, name: String? = null, connId: Long = 7L): BrowseLocation =
        BrowseLocation(connId, containerId, name)

    @Test
    fun `空链不给入口`() {
        assertTrue(browseJumpTargets(emptyList()) { null }.isEmpty())
    }

    @Test
    fun `链首不是连接起点层时不弹菜单`() {
        // 链不可信（链底不是起点层）：宁可不给入口，也不跳到一层不是起点层的地方
        assertTrue(
            browseJumpTargets(listOf(layer("smb://host/share/A"), layer("smb://host/share/A/B"))) { null }.isEmpty(),
        )
    }

    @Test
    fun `停在起点层不弹菜单`() {
        // 菜单那时只剩「/」一项、点了也不动 ⇒ 直接不给入口
        assertTrue(browseJumpTargets(listOf(layer(null))) { null }.isEmpty())
    }

    @Test
    fun `停在一级目录时只有起点层一项`() {
        val targets = browseJumpTargets(listOf(layer(null), layer("smb://host/share/A", "A"))) { null }
        assertEquals(1, targets.size)
        assertEquals("/", targets[0].label)
        assertEquals(layer(null), targets[0].location)
    }

    @Test
    fun `停在更深层时是起点层与一级目录`() {
        val chain = listOf(
            layer(null),
            layer("smb://host/share/A", "A"),
            layer("smb://host/share/A/B", "B"),
            layer("smb://host/share/A/B/C", "C"),
        )
        val targets = browseJumpTargets(chain) { null }
        assertEquals(listOf("/", "A"), targets.map { it.label })
        // 目标层就是链上那一层（位置身份只有连接 + 容器）
        assertEquals(chain[0], targets[0].location)
        assertEquals(chain[1], targets[1].location)
    }

    @Test
    fun `一级目录的文案走顶栏标题同一条兜底链`() {
        val chain = listOf(layer(null), layer("content://doc/A"), layer("content://doc/A/B"))
        // 路由没带名字 → 会话内回填的条目名
        assertEquals(listOf("/", "缓存名"), browseJumpTargets(chain) { "缓存名" }.map { it.label })
        // 缓存也没有 → id 末段
        assertEquals(listOf("/", "A"), browseJumpTargets(chain) { null }.map { it.label })
        // 随路由带回来的名字最优先
        val named = listOf(layer(null), layer("content://doc/A", "甲"), layer("content://doc/A/B"))
        assertEquals(listOf("/", "甲"), browseJumpTargets(named) { "缓存名" }.map { it.label })
    }
}
