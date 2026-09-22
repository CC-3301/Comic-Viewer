package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.DiagnosticsLog
import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.FakeTreeBackend
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.SourceDiagnostics
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.fakeDir
import com.cc3301.comicviewer.core.source.fakeFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断日志导出（票 #113 修复轮）：**导出内容三段齐** + **状态快照字段名是约定**。
 *
 * 票面验收：导出内容含状态快照（用例断言快照字段存在）。因此这里锁两件事：
 * ① 报告文本含头部 / 状态快照 / 打点行三段，且快照里六个约定 key 都在（键名改了这条就红——字段名是口径）；
 * ② 快照里的数是**现取的真数**（拿一个真来源：列表快照条目数、封面字节缓存命中数），
 *    现有接口上取不到的两项如实写「不可用」，不编。
 * 文件名按票面约定（`comicviewer-diag-YYYYMMDD-HHmmss.txt`）。
 */
class DiagnosticsExportTest {

    /** 快照的六个约定 key（字面量写死：它们是对外口径，改名字等于改口径） */
    private val snapshotKeys = listOf(
        "source.type=",
        "source.instance=",
        "source.connection=",
        "cache.list.entries=",
        "cache.coverBytes=",
        "cache.pageDisk=",
    )

    @Test
    fun `没有会话来源时快照仍给出全部约定字段并写明不可用`() {
        val fields = DiagnosticsExport.snapshotFields(null)

        snapshotKeys.forEach { key ->
            assertTrue("快照缺字段 $key：$fields", fields.any { it.startsWith(key) })
        }
        assertTrue(
            "连接存活这项没有外层接口，必须写明不可用：$fields",
            fields.single { it.startsWith("source.connection=") }.contains(DiagnosticsExport.UNAVAILABLE),
        )
        assertTrue(
            "页磁盘缓存条目数同理：$fields",
            fields.single { it.startsWith("cache.pageDisk=") }.contains(DiagnosticsExport.UNAVAILABLE),
        )
    }

    @Test
    fun `报告文本含头部 状态快照与打点行三段`() {
        val text = DiagnosticsExport.reportText(
            header = listOf("app.version=1.2.3 (45)", "device.model=合成机型", "range.start=无"),
            snapshot = DiagnosticsExport.snapshotFields(null),
            lines = listOf(DiagnosticsLog.Line(atMs = 1_700_000_000_000L, text = "sourceOpen instance=X")),
        )

        assertTrue("缺头部标题：" + text, text.contains(DiagnosticsExport.HEADER_TITLE))
        assertTrue("缺状态快照段：" + text, text.contains(DiagnosticsExport.SNAPSHOT_SECTION))
        assertTrue("缺打点行段：" + text, text.contains(DiagnosticsExport.LINES_SECTION))
        snapshotKeys.forEach { key ->
            assertTrue("快照段缺字段 $key：$text", text.contains(key))
        }
        assertTrue("打点行要原样带上", text.contains("sourceOpen instance=X"))
        assertTrue(
            "打点行前要有时间戳（HH:mm:ss.SSS）：" + text,
            Regex("\\d{2}:\\d{2}:\\d{2}\\.\\d{3} sourceOpen instance=X").containsMatchIn(text),
        )
        // 三段顺序：头部 → 打点行 → 状态快照（与工单 #113 的导出口径一致）
        assertTrue(text.indexOf(DiagnosticsExport.LINES_SECTION) < text.indexOf(DiagnosticsExport.SNAPSHOT_SECTION))
    }

    @Test
    fun `快照对真来源现取实例身份与缓存条目数`() {
        val backend = FakeTreeBackend(fakeDir("root").add(fakeFile("root/001.jpg")))
        val source = DocumentTreeSource(backend, InMemoryProgressStore(), sourceType = SourceType.SMB)
        runBlocking { source.listEntries(null, SortMode.NAME) }

        val fields = DiagnosticsExport.snapshotFields(source)

        assertEquals("SMB", fields.single { it.startsWith("source.type=") }.substringAfter('='))
        assertEquals(
            "实例身份与打点行同一把（DiagnosticsLog 的 sourceOpen 行对得上）",
            SourceDiagnostics.instanceTag(source),            fields.single { it.startsWith("source.instance=") }.substringAfter('='),
        )
        assertEquals("1", fields.single { it.startsWith("cache.list.entries=") }.substringAfter('='))
        assertEquals(
            "封面字节缓存要现取命中数/条目数（本用例没取过封面 → 0/1）",
            "0/1（源根容器条目在封面字节缓存里的命中数）",
            fields.single { it.startsWith("cache.coverBytes=") }.substringAfter('='),
        )
    }

    @Test
    fun `文件名符合票面约定`() {
        val name = DiagnosticsExport.fileName(1_700_000_000_000L)

        assertTrue("文件名形如 comicviewer-diag-YYYYMMDD-HHmmss.txt：$name", Regex("^comicviewer-diag-\\d{8}-\\d{6}\\.txt$").matches(name))
    }
}
