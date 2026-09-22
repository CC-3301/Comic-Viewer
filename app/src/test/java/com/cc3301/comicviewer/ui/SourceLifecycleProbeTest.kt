package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.FakeTreeBackend
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.PerfTiming
import com.cc3301.comicviewer.core.source.SourceDiagnostics
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.fakeDir
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 来源实例建立/释放的 App 接线（票 #113）：`sourceOpen` / `sourceRelease` 两行必须打在
 * **服务定位器真正走的那两条路**上，否则真机上拿到的时间线里就少了「实例重建」这一环
 * （票 #109 r5 的教训：字段口径对了、接线断了，日志里照样什么都没有）。
 *
 * 三个判据对应票面三条机制推测所需的观测点：
 * ① 新建实例 → `sourceOpen slot=browse`（同一连接复用实例时**不打**——复用不是重建）；
 * ② 阅读器正在用的实例被浏览槽换出 → `sourceRelease closed=false`（这条路径**不关**它，见 `releaseReplacedSource`）；
 * ③ 连接被删除/编辑清槽 → `sourceRelease reason=connChanged closed=true`；
 * ④ 会话来源被替换 → `sourceRelease reason=readerReplaced`（阅读中换来源的那条路）。
 */
class SourceLifecycleProbeTest {

    private val lines = mutableListOf<String>()

    @Before
    fun 打开量测开关() {
        ServiceLocator.sourceFactory = {
            DocumentTreeSource(FakeTreeBackend(fakeDir("root")), InMemoryProgressStore(), sourceType = SourceType.SMB)
        }
        ServiceLocator.currentSource = null
        PerfTiming.forcedForTest = true
        PerfTiming.recordedLinesForTest = lines
    }

    @After
    fun 收口() {
        ServiceLocator.closeBrowsingSource()
        ServiceLocator.currentSource = null
        ServiceLocator.currentConnId = null
        ServiceLocator.sourceFactory = { ServiceLocator.sourceForConnection(it) }
        PerfTiming.forcedForTest = null
        PerfTiming.recordedLinesForTest = null
        lines.clear()
    }

    private fun conn(id: Long) = ConnectionEntity(
        id = id,
        sourceType = SourceType.SMB.name,
        displayName = "测试连接",
        configJson = "{\"host\":\"nas\",\"share\":\"comics\"}",
    )

    private fun field(line: String, key: String): String =
        line.split(' ').first { it.startsWith(key + "=") }.substringAfter('=')

    private fun linesWith(prefix: String) = lines.filter { it.startsWith(prefix) }

    @Test
    fun `新建实例报 sourceOpen 同一连接复用不报`() {
        val first = runBlocking { ServiceLocator.browsingSourceFor(conn(7)) }
        runBlocking { ServiceLocator.browsingSourceFor(conn(7)) }

        val opens = linesWith("sourceOpen")
        assertEquals("只建了一个实例：$opens", 1, opens.size)
        assertEquals("browse", field(opens.single(), "slot"))
        assertEquals("7", field(opens.single(), "conn"))
        assertEquals(SourceDiagnostics.instanceTag(first), field(opens.single(), "instance"))
    }

    @Test
    fun `阅读器在用的实例被换出时报 closed=false`() {
        val browsing = runBlocking { ServiceLocator.browsingSourceFor(conn(7)) }
        ServiceLocator.currentSource = browsing

        // 换到另一个连接：浏览槽把上一个实例换出去，而它正是阅读器在用的那个 → 这次不关
        runBlocking { ServiceLocator.browsingSourceFor(conn(8)) }

        val releases = linesWith("sourceRelease")
        assertTrue("换槽必须落一行释放：$lines", releases.isNotEmpty())
        assertEquals(SourceDiagnostics.instanceTag(browsing), field(releases.first(), "instance"))
        assertEquals("browseReplaced", field(releases.first(), "reason"))
        assertEquals("closed=false：阅读器在用它，关闭责任归会话来源那一侧", "false", field(releases.first(), "closed"))
    }

    @Test
    fun `连接被删除或编辑清槽时报 connChanged 且真关`() {
        val browsing = runBlocking { ServiceLocator.browsingSourceFor(conn(7)) }

        ServiceLocator.closeBrowsingSource(7)

        val releases = linesWith("sourceRelease")
        assertEquals("ConnectionSource 的删除/编辑路径就调这一处：$releases", 1, releases.size)
        assertEquals("connChanged", field(releases.single(), "reason"))
        assertEquals("true", field(releases.single(), "closed"))
        assertEquals(SourceDiagnostics.instanceTag(browsing), field(releases.single(), "instance"))
    }

    @Test
    fun `会话来源被替换时报 readerReplaced`() {
        val source = runBlocking { ServiceLocator.browsingSourceFor(conn(7)) }
        ServiceLocator.adoptSessionSource(source, connId = 7)

        ServiceLocator.currentSource = null

        val replaced = linesWith("sourceRelease").single { field(it, "reason") == "readerReplaced" }
        assertEquals(SourceDiagnostics.instanceTag(source), field(replaced, "instance"))
        assertEquals("true", field(replaced, "closed"))
        assertTrue("落槽时也要报一行来源实例：$lines", linesWith("sourceOpen").any { field(it, "slot") == "reader" })
    }

    @Test
    fun `阅读器来源落槽时 conn 是新连接 id`() {
        // 先把会话连接指向**另一个**连接：这正是四个真实调用点原先的顺序（先给来源、再给 connId），
        // 旧实现在打点那一刻读到的就是这个值——阅读器来源会被归错连接（票 #113 r4 的 P2）。
        ServiceLocator.currentConnId = 99
        val source = runBlocking { ServiceLocator.browsingSourceFor(conn(7)) }

        ServiceLocator.adoptSessionSource(source, connId = 7)

        val line = linesWith("sourceOpen").single { field(it, "slot") == "reader" }
        assertEquals("slot=reader 的 conn= 必须是新连接 id", "7", field(line, "conn"))
        assertEquals(SourceDiagnostics.instanceTag(source), field(line, "instance"))
        assertEquals("连接 id 与会话来源同源（两字段不能分离）", 7L, ServiceLocator.currentConnId)
    }
}
