package com.cc3301.comicviewer.ui.nav

import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.TopLevelEntry
import com.cc3301.comicviewer.core.source.TopLevelListing
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
 * 判据是**菜单项本身**（文案 + 目标层 + 链底 + 两个标记），不看渲染：菜单 = `/`（顶层那一屏）
 * ＋起点层**全部**一级目录（含当前所在那一条、不按条数截断），停在起点层与链不可信两种情形不给入口。
 *
 * 判别力：去掉 `chain.size < 2` 那条「停在起点层」变红；去掉 `filter { !it.entry.isBook }`「只列目录」变红；
 * 给 `.take(...)` 加回条数上限「全部列出」变红；把目录项的 `anchor` 写成 null「链底是起点层」变红；
 * 把条目名从目标层上拿掉「目标层的名字随菜单项带走」变红；把 `isCurrentLayer` 写死 false 对应用例变红；
 * 把 `isStartLayer` 那一支去掉「标了起点层的那一项」变红；
 * 把顶层地址那一支写死根容器「顶层另有地址」变红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseJumpTargetsTest {

    private val root = BrowseLocation(7L, null)

    private fun layer(containerId: String?, name: String? = null): BrowseLocation =
        BrowseLocation(7L, containerId, name)

    private fun entry(id: String, name: String = id, isBook: Boolean = false): TopLevelEntry =
        TopLevelEntry(BrowseEntry(id = id, name = name, isBook = isBook, coverUri = null))

    private fun dir(id: String, name: String = id): TopLevelEntry = entry(id, name)

    private fun book(id: String, name: String = id): TopLevelEntry = entry(id, name, isBook = true)

    /** 顶层清单：默认根容器就是顶层那一屏（文件源与 Komga 默认起始路径） */
    private fun listing(vararg entries: TopLevelEntry, containerId: String? = null): TopLevelListing =
        TopLevelListing(containerId = containerId, entries = entries.toList())

    @Test
    fun `空链不给入口`() {
        assertTrue(browseJumpTargets(emptyList(), listing(dir("dir-a")), null).isEmpty())
    }

    @Test
    fun `链首不是连接起点层时不弹菜单`() {
        // 链不可信（链底不是起点层）：宁可不给入口，也不跳到一层不是起点层的地方
        assertTrue(
            browseJumpTargets(
                listOf(layer("dir-a"), layer("dir-a/b")),
                listing(dir("dir-a")),
                null,
            ).isEmpty(),
        )
    }

    @Test
    fun `停在起点层不弹菜单`() {
        // 菜单里列的都是身后那张列表已经列着的一级目录 ⇒ 直接不给入口
        assertTrue(browseJumpTargets(listOf(layer(null)), listing(dir("dir-a")), null).isEmpty())
    }

    @Test
    fun `深层时列出起点层与全部一级目录`() {
        val chain = listOf(layer(null), layer("dir-a", "A"), layer("dir-a/b", "B"), layer("dir-a/b/c", "C"))
        val top = listing(dir("dir-a", "A"), dir("dir-x", "X"), dir("dir-y", "Y"))
        val targets = browseJumpTargets(chain, top, connectionName = "连接名")
        // 全部一级目录（含当前所在的 A 与兄弟 X、Y），不是只有当前路径上那一条
        assertEquals(listOf("/", "A", "X", "Y"), targets.map { it.label })
        // 「/」就是起点层自己（已在链底），不需要钉链底、名字也不随路由带走
        assertEquals(root, targets[0].location)
        assertNull(targets[0].location.containerName)
        assertNull(targets[0].anchor)
        // 一级目录：目标是它自己那一层，链底是起点层
        assertEquals(listOf(root, root, root), targets.drop(1).map { it.anchor })
        assertEquals(listOf("dir-a", "dir-x", "dir-y"), targets.drop(1).map { it.location.containerId })
    }

    @Test
    fun `只列目录 不列书`() {
        val targets = browseJumpTargets(
            listOf(layer(null), layer("dir-a")),
            listing(book("book-1", "第1话"), dir("dir-x", "X")),
            null,
        )
        assertEquals(listOf("/", "X"), targets.map { it.label })
    }

    @Test
    fun `一级目录全部列出 不按条数截断`() {
        val targets = browseJumpTargets(
            listOf(layer(null), layer("dir-a")),
            listing(*(1..12).map { dir("dir-$it", "D$it") }.toTypedArray()),
            null,
        )
        assertEquals(13, targets.size) // 「/」+ 12 条
        assertEquals("D12", targets.last().label)
    }

    @Test
    fun `目标层的名字随菜单项带走`() {
        // 名字写进目标层：跳过去之后那一层的标题不必再退到 id 末段
        val targets = browseJumpTargets(listOf(layer(null), layer("dir-a")), listing(dir("dir-x", "封面目录")), null)
        assertEquals("封面目录", targets[1].location.containerName)
    }

    @Test
    fun `目标就是当前层时标出来 界面据此刷新而不导航`() {
        val chain = listOf(layer(null), layer("dir-a", "A"), layer("dir-a/b", "B"))
        val targets = browseJumpTargets(
            chain,
            listing(dir("dir-a", "A"), dir("dir-a/b", "B"), dir("dir-x", "X")),
            null,
        )
        // 「/」不会是当前层（停在起点层压根不弹菜单）
        assertEquals(listOf(false, false, true, false), targets.map { it.isCurrentLayer })
    }

    @Test
    fun `标了起点层的那一项目标就是起点层`() {
        // 起始路径正好落在某一类（Komga 把路径设成 `/series` 这类）时，那一项与起点层是同一屏内容：
        // 指回起点层才不会压出内容相同的一层（返回因此不会「看起来没反应」）
        val chain = listOf(layer(null), layer("cat/series"), layer("cat/series/s1"))
        val targets = browseJumpTargets(
            chain,
            listing(
                TopLevelEntry(BrowseEntry(id = "cat/series", name = "系列", isBook = false, coverUri = null), isStartLayer = true),
                dir("cat/books", "书籍"),
            ),
            null,
        )
        assertEquals(listOf("/", "系列", "书籍"), targets.map { it.label })
        assertEquals("起点层那一条指向起点层", root, targets[1].location)
        assertEquals("其余条目仍指向自己那一层", "cat/books", targets[2].location.containerId)
    }

    @Test
    fun `顶层另有地址时 斜杠指向它且链底钉起点层`() {
        // Komga 起始路径不默认时（根容器成了那一处的列表）：四入口那一屏另有地址，
        // `/` 因此指向那个地址、并钉在起点层之上（返回落起点层），名字随路由带走（与根层标题同口径）
        val chain = listOf(layer(null), layer("cat/series"))
        val targets = browseJumpTargets(
            chain,
            listing(dir("cat/books", "书籍"), containerId = "cat"),
            connectionName = "连接名",
        )
        assertEquals("cat", targets[0].location.containerId)
        assertEquals("连接名", targets[0].location.containerName)
        assertEquals(root, targets[0].anchor)
        assertEquals(false, targets[0].isCurrentLayer)
    }
}
