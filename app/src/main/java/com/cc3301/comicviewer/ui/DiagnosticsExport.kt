package com.cc3301.comicviewer.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.cc3301.comicviewer.core.source.DiagnosticsLog
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceDiagnostics
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 诊断日志的导出（票 #113 修复轮）：把 [DiagnosticsLog] 的内存缓冲拼成一份 .txt 并弹系统分享。
 *
 * 内容结构（三段，顺序固定，与工单 #113 的导出口径一致）：
 * 1. **头部**——App 版本、设备型号、Android 版本、打点行时间范围（缓冲为空时写「无」）；
 * 2. **打点行**——缓冲里原样的行，行首补 `HH:mm:ss.SSS`（缓冲里存的是墙钟毫秒）；
 * 3. **状态快照**——出事那一刻的关键状态：来源实例 id、连接是否活着、三层缓存的命中与条目数。
 *
 * **字段名是约定**（真机读这份文件时按这些 key 找）：`source.type` / `source.instance` / `source.connection` /
 * `cache.list.entries` / `cache.coverBytes` / `cache.pageDisk`；改名字等于改口径。
 * **取不到就写「不可用」并给出原因**，不编数：连接存活、页磁盘缓存条目数这两项在现有外层接口上取不到
 * （`Source` 没有 liveness 接口、`PageDiskCache` 只暴露内部计数），因此如实标注，留待将来补接缝。
 *
 * 落盘位置：**应用外部文件目录**（`getExternalFilesDir(null)/diag/`）——无需任何存储权限、卸载即随之清除、
 * 用户插 USB 也能直接拷走；外置存储不可用（未挂载的少数机型）时回落到 `cacheDir/diag/` 同一层结构。
 * 分享走 `FileProvider`（authority = `<applicationId>.diagnostics`），因此两处路径都在 `res/xml/diag_file_paths.xml`
 * 里声明。
 */
internal object DiagnosticsExport {

    /** 导出文件所在子目录（外部文件目录与 cacheDir 回落两条路共用同一个名字） */
    const val EXPORT_DIR_NAME: String = "diag"

    /** 文件名前缀（后接 `yyyyMMdd-HHmmss.txt`） */
    const val FILE_PREFIX: String = "comicviewer-diag-"

    /** 快照段落标题（导出内容的结构标记，测试按它断言段落存在） */
    const val SNAPSHOT_SECTION: String = "## 状态快照"

    /** 打点行段落标题 */
    const val LINES_SECTION: String = "## 打点行"

    /** 头部标题 */
    const val HEADER_TITLE: String = "# Comic-Viewer 诊断日志"

    /** 取不到的值一律写它 + 原因（不编） */
    const val UNAVAILABLE: String = "不可用"

    /** FileProvider authority 的后缀（`<applicationId>.diagnostics`）：与 AndroidManifest 的声明必须一致 */
    const val AUTHORITY_SUFFIX: String = ".diagnostics"

    private const val KEY_SOURCE_TYPE = "source.type"
    private const val KEY_SOURCE_INSTANCE = "source.instance"
    private const val KEY_SOURCE_CONNECTION = "source.connection"
    private const val KEY_LIST_ENTRIES = "cache.list.entries"
    private const val KEY_COVER_BYTES = "cache.coverBytes"
    private const val KEY_PAGE_DISK = "cache.pageDisk"

    private val fileNameFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
    private val stampFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
    private val rangeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** 导出文件名（票面口径：`comicviewer-diag-YYYYMMDD-HHmmss.txt`，本地时区） */
    fun fileName(atMs: Long): String = FILE_PREFIX + format(atMs, fileNameFormat) + ".txt"

    /**
     * 关键状态快照（现取；取不到就写「不可用」+ 原因）。
     *
     * 两层缓存给的是**真实数**，口径都来自现有公开接口：
     * - `cache.list.entries` = 源根容器在 [sort] 这一档的会话列表快照条目数（[Source.cachedEntries]，
     *   契约是「只读内存、不做 IO」）。**必须按当前排序档取**：Komga 的会话快照键含排序方式
     *   （`KomgaSource` 的 `keyPrefixOf(containerId) + sort.name`），写死名称档会在用户用其它排序时
     *   把「其实有快照」假报成「不可用」（票 #113 r5 修的正是这个）；文件源的快照与排序无关，
     *   因此按同一档问也拿得到同一份。值里带上档名（`SortMode` 的枚举名，便于机械比对），读文件的人不必猜。
     * - `cache.coverBytes` = 该批条目里**字节缓存已命中**的条数 / 该批条目数（[Source.hasCachedCoverBytes]，
     *   契约是「只查内存、不 resolve」）——预取与可见行读的就是这份缓存；
     * 取不到的两项（连接存活、页磁盘缓存条目数）如实标注不可用，见类 KDoc。
     */
    fun snapshotFields(source: Source?, sort: SortMode): List<String> {
        val entries = source?.cachedEntries(null, sort)
        val coverHits = entries?.let { list -> list.count { source.hasCachedCoverBytes(it.id) } }
        return listOf(
            KEY_SOURCE_TYPE + "=" + (source?.type?.name ?: UNAVAILABLE + "（当前没有会话来源）"),
            KEY_SOURCE_INSTANCE + "=" + (source?.let(SourceDiagnostics::instanceTag)
                ?: UNAVAILABLE + "（当前没有会话来源）"),
            KEY_SOURCE_CONNECTION + "=" + UNAVAILABLE + "（Source 没有连接存活接口）",
            KEY_LIST_ENTRIES + "=" + (entries?.let { it.size.toString() + "（档=" + sort.name + "）" }
                ?: UNAVAILABLE + "（源根容器本次会话还没列过 " + sort.name + " 档快照）"),
            KEY_COVER_BYTES + "=" + (
                if (entries == null || coverHits == null) {
                    UNAVAILABLE + "（列表快照不可用，无从逐条问缓存）"
                } else {
                    "$coverHits/${entries.size}（源根容器条目在封面字节缓存里的命中数）"
                }
                ),
            KEY_PAGE_DISK + "=" + UNAVAILABLE + "（PageDiskCache 只暴露内部字节计数，没有对外条目数接口）",
        )
    }

    /** 头部（App 版本 / 设备型号 / Android 版本 / 时间范围 / 打点行数）：真机上取不到的写「不可用」 */
    fun headerFields(context: Context, recorded: List<DiagnosticsLog.Line>): List<String> {
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName + " (" + PackageInfoCompat.getLongVersionCode(info) + ")"
        }.getOrDefault(UNAVAILABLE + "（读包信息失败）")
        return listOf(
            "app.version=" + version,
            "device.model=" + android.os.Build.MODEL,
            "android.release=" + android.os.Build.VERSION.RELEASE + " sdk=" + android.os.Build.VERSION.SDK_INT,
            "range.start=" + (recorded.firstOrNull()?.let { format(it.atMs, rangeFormat) } ?: "无"),
            "range.end=" + (recorded.lastOrNull()?.let { format(it.atMs, rangeFormat) } ?: "无"),
            "lines.recorded=" + recorded.size + "/" + DiagnosticsLog.CAPACITY,
        )
    }

    /** 纯拼装（无平台依赖，JVM 可测）：头部 → 打点行 → 状态快照 */
    fun reportText(
        header: List<String>,
        snapshot: List<String>,
        lines: List<DiagnosticsLog.Line>,
    ): String = buildString {
        appendLine(HEADER_TITLE)
        header.forEach { appendLine(it) }
        appendLine()
        appendLine(LINES_SECTION)
        if (lines.isEmpty()) {
            appendLine("（空——设置页的「诊断日志」开关打开后才会记录）")
        } else {
            lines.forEach { appendLine(format(it.atMs, stampFormat) + " " + it.text) }
        }
        appendLine()
        appendLine(SNAPSHOT_SECTION)
        snapshot.forEach { appendLine(it) }
    }

    /**
     * 现取一份完整报告（导出按钮的唯一入口）：头部 + 快照 + 当前缓冲。
     * 快照按**当前全局排序档**取（`SortSettingStore`，与浏览页同一份设置）——Komga 的会话快照键含排序，
     * 写死一档会假报「不可用」；文件源的快照与排序无关，按哪一档问都是同一份。
     */
    fun buildReport(context: Context, source: Source?): String {
        val recorded = DiagnosticsLog.snapshot()
        val sort = SortSettingStore.setting.mode
        return reportText(headerFields(context, recorded), snapshotFields(source, sort), recorded)
    }

    /**
     * 写文件并返回它（外部文件目录优先，回落 cacheDir；目录不存在就建）。
     * 文件名来自 [fileName]；同一秒内导两次会覆盖同一份（可接受：导出的是当前缓冲的快照）。
     */
    fun writeReport(context: Context, text: String, atMs: Long = System.currentTimeMillis()): File {
        val base = context.getExternalFilesDir(null) ?: context.cacheDir
        val dir = File(base, EXPORT_DIR_NAME)
        dir.mkdirs()
        val file = File(dir, fileName(atMs))
        file.writeText(text)
        return file
    }

    /** 系统分享面板的 Intent（`text/plain` + 只读授权；调用方需要 [Context.startActivity] 包 chooser） */
    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun format(atMs: Long, format: DateTimeFormatter): String =
        format.format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()))
}
