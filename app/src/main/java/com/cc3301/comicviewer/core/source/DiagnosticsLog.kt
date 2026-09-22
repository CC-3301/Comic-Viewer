package com.cc3301.comicviewer.core.source

import java.util.ArrayDeque

/**
 * 诊断日志的内存环形缓冲（票 #113 修复轮）：应用内开关打开后，打点行**同时**落在这里，供设置页导出。
 *
 * 为什么要有它：打点原本只有 `log.tag.ComicViewerPerf` 一条路——维护者要取数得连 adb、`setprop`、重启 App，
 * 在「偶发退化、天天阅读」的现场基本拿不到。应用内开关 + 一键导出把取数成本降到「翻设置页点两下」。
 *
 * 两条路**取或**（见 [PerfTiming.isOn]）：应用内开关**或** adb 的 `log.tag` 任一为真就打点；
 * 缓冲只在打点真的发生时才写（[PerfTiming.emit] 里已按开关短路）。
 *
 * 关闭时零开销：[enabled] 为假的默认状态下，[PerfTiming.log] 的 lambda 根本不执行，本对象一次都不被碰到
 * （没有字符串拼接、没有集合操作）。
 *
 * 容量 [CAPACITY]：满了丢最旧（环形），因此长时间开着也只占固定的内存；真机取数要的是「出事前后那一段」。
 * 线程安全：打点来自多个线程（IO 工作线程、主线程），一把锁护住队列。
 */
internal object DiagnosticsLog {

    /** 环形缓冲容量（行）：够装下一次复现前后的上下文，又不会在长时间开启时涨内存 */
    const val CAPACITY: Int = 5000

    /**
     * 应用内开关的**运行期值**：由持久化设置注入（启动时 `ServiceLocator.init` 读一次，切换时由
     * `AppSettings` 的 setter 更新），因此打点侧（core）不依赖任何界面或存储对象。
     */
    @Volatile
    var enabled: Boolean = false

    /** 一行打点：时刻（墙钟毫秒，导出按它算时间范围与行首时间戳）+ 原样的打点行 */
    data class Line(val atMs: Long, val text: String)

    private val lock = Any()

    private val lines = ArrayDeque<Line>()

    /** 记一行（满了丢最旧）。只在打点真的发生（开关为真）时被调用 */
    fun record(text: String, atMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (lines.size >= CAPACITY) lines.removeFirst()
        lines.addLast(Line(atMs, text))
    }

    /** 当前缓冲内容的快照（导出读它；拿到的是拷贝，导出期间新写入不影响这次导出） */
    fun snapshot(): List<Line> = synchronized(lock) { lines.toList() }

    /** 清空（测试与「导出后清空」共用；生产不自动清——出事后连点两次导出要能拿到同一段） */
    fun clear() = synchronized(lock) { lines.clear() }

    /** 当前行数（导出头部写它，用来判断「缓冲是不是空的」） */
    val count: Int get() = synchronized(lock) { lines.size }
}
