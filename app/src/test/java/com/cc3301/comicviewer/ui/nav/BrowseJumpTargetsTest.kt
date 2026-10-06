package com.cc3301.comicviewer.ui.nav

import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.source.BrowseEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 浏览层路径菜单的判定（spec 故事 55 / `docs/spec/shell.md`「顶栏标题路径菜单」）。
 *
 * 判据是**菜单项本身**（文案 + 目标层 + 链底），不看渲染：菜单 = `/` ＋起点层**全部**一级目录
 *（含当前所在那一条），停在起点层与链不可信两种情形不给入口，一级目录最多 10 条。
 *
 * 判别力：去掉 `chain.size < 2` 那条「停在起点层」变红；去掉 `filter { !it.isBook }`「只列目录」变红；
 * 去掉 `take(MAX_DIRS)`「最多 10 条」变红；把目录项的 `anchor` 写成 null「链底是起点层」变红；
 * 把条目名从目标层上拿掉「目标层的名字随菜单项带走」变红；
 * 把 `isCurrentLayer` 写死 false「目标就是当前层」变红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseJumpTargetsTest {

    private val root = BrowseLocation(7L, null)

    private fun layer(containerId: String?, name: String? = null): BrowseLocation =
        BrowseLocation(7L, containerId, name)

    private fun dir(id: String, name: String = id): BrowseEntry =
        BrowseEntry(id = id, name = name, isBook = false, coverUri = null)

    private fun book(id: String, name: String = id): BrowseEntry =
        BrowseEntry(id = id, name = name, isBook = true, coverUri = null)

    @Test
    fun `空链不给入口`() {
        assertTrue(browseJumpTargets(emptyList(), listOf(dir("dir-a"))).isEmpty())
    }

    @Test
    fun `链首不是连接起点层时不弹菜单`() {
        // 链不可信（链底不是起点层）：宁可不给入口，也不跳到一层不是起点层的地方
        assertTrue(
            browseJumpTargets(
                listOf(layer("dir-a"), layer("dir-a/b")),
                listOf(dir("dir-a")),
            ).isEmpty(),
        )
    }

    @Test
    fun `停在起点层不弹菜单`() {
        // 菜单里列的都是身后那张列表已经列着的一级目录 ⇒ 直接不给入口
        assertTrue(browseJumpTargets(listOf(layer(null)), listOf(dir("dir-a"))).isEmpty())
    }

    @Test
    fun `深层时列出起点层与全部一级目录`() {
        val chain = listOf(layer(null), layer("dir-a", "A"), layer("dir-a/b", "B"), layer("dir-a/b/c", "C"))
        val top = listOf(dir("dir-a", "A"), dir("dir-x", "X"), dir("dir-y", "Y"))
        val targets = browseJumpTargets(chain, top)
        // 全部一级目录（含当前所在的 A 与兄弟 X、Y），不是只有当前路径上那一条
        assertEquals(listOf("/", "A", "X", "Y"), targets.map { it.label })
        // 「/」就是起点层自己（已在链底），不需要钉链底
        assertEquals(root, targets[0].location)
        assertNull(targets[0].anchor)
        // 一级目录：目标是它自己那一层，链底是起点层
        assertEquals(listOf(root, root, root), targets.drop(1).map { it.anchor })
        assertEquals(listOf("dir-a", "dir-x", "dir-y"), targets.drop(1).map { it.location.containerId })
    }

    @Test
    fun `只列目录 不列书`() {
        val targets = browseJumpTargets(
            listOf(layer(null), layer("dir-a")),
            listOf(book("book-1", "第1话"), dir("dir-x", "X")),
        )
        assertEquals(listOf("/", "X"), targets.map { it.label })
    }

    @Test
    fun `一级目录最多 10 条`() {
        val targets = browseJumpTargets(
            listOf(layer(null), layer("dir-a")),
            (1..12).map { dir("dir-$it", "D$it") },
        )
        assertEquals(11, targets.size)
        assertEquals("D1", targets[1].label)
        assertEquals("D10", targets.last().label)
    }

    @Test
    fun `目标层的名字随菜单项带走`() {
        // 名字写进目标层：跳过去之后那一层的标题不必再退到 id 末段
        val targets = browseJumpTargets(listOf(layer(null), layer("dir-a")), listOf(dir("dir-x", "封面目录")))
        assertEquals("封面目录", targets[1].location.containerName)
    }

    @Test
    fun `目标就是当前层时标出来 界面据此刷新而不导航`() {
        val chain = listOf(layer(null), layer("dir-a", "A"), layer("dir-a/b", "B"))
        val targets = browseJumpTargets(
            chain,
            listOf(dir("dir-a", "A"), dir("dir-a/b", "B"), dir("dir-x", "X")),
        )
        // 「/」不会是当前层（停在起点层压根不弹菜单）
        assertEquals(listOf(false, false, true, false), targets.map { it.isCurrentLayer })
    }
}
