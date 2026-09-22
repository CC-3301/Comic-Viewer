package com.cc3301.comicviewer.core.source

/**
 * 票 #113 的偶发退化诊断打点：四类事件的**行格式唯一出处**（调用方只交事实，不自己拼字段）。
 *
 * 现象（维护者真机反馈）：阅读器看着看着突然转圈、返回书柜时部分封面也是灰的，约十几秒后恢复。
 * 本票的前置约定是**先取数再改**，因此这里只加打点、不改任何取数/缓存行为。
 * 开关就是既有的 [PerfTiming]（`log.tag.ComicViewerPerf`，默认关；关着时连字符串都不拼），
 * 打开后每条事件一行、行首即事件名，`adb logcat -s ComicViewerPerf -v time` 给出的时间戳
 * 就是本票要的时间线（转圈开始时刻 ↔ 下列事件时刻）：
 *
 * ```
 * adb shell setprop log.tag.ComicViewerPerf DEBUG   # 设完重启 APP（isLoggable 按进程缓存）
 * adb logcat -s ComicViewerPerf -v time | grep -E 'sourceOpen|sourceRelease|coverCacheClear|pageBytes|loadPage|smbSessionOpen'
 * ```
 *
 * 各事件要回答的问题（与工单 #113 的三条机制推测一一对应）：
 * - [sourceOpenLine] / [sourceReleaseLine]：**来源实例**有没有被释放重建？`instance=` 是实例身份
 *   （同一个实例跨行相等；实例重建后必换一个身份），`reason=` 是释放的触发原因（常量见下），
 *   `closed=` 为假表示那条路径**没有**真关（阅读器正在用，交给会话来源那一侧关）。
 * - [coverCacheClearLine]：封面字节缓存被整体清空，以及**清掉了多少**（`entries`/`bytes`）。
 *   「所有封面一起变灰」在这一行上是 `entries` 十几到几十、且紧跟在 `sourceRelease` 或 `reason=refresh` 之后。
 * - **取页**：新增字段而不是新行——阅读器侧 `pageBytes` 原有的 `disk=`（`disk=false` = 页磁盘缓存未命中，
 *   本次字节当场向来源取；远端来源上就是一次可能的网络往返，**但被进程内块缓存接住的那次也不会发往返**，
 *   所以「到底有没有走网络」只能看同期的 `remoteRead` 行有没真发取数——为此 r3 去掉了名实不符的 `net=`），
 *   来源侧 `loadPage` 多报 `source=`/`instance=`（慢页因此能归到某个实例，与 [sourceOpenLine]/[sourceReleaseLine] 对齐同一把身份）。
 * - [smbSessionOpenLine]：一次 SMB 会话（含共享句柄）**建立成功之后**才发（connect → authenticate →
 *   connectShare 全部过了；建连失败不打点，也就看不到这一行）。
 *   `rebuilt=true` = **此前已经成功建立过一次会话**（断链/空闲断开后的重连、或换共享）：判定的真相是
 *   「建立过的次数 ≥ 2」，**不是** `share != null`（重连路径上 `share` 已被 `closeQuietly` 置空，
 *   用它会把重连报成首次建连——票 #113 r3 修的就是这个）。次序与判定收在 `SmbSessionReporter`，那里可 JVM 单测。
 *
 * 这些行**只读事实**：不改缓存口径、不改取数路径、不改任何判定（票 #113 的 Out of scope 是不在取数前改行为）。
 */
internal object SourceDiagnostics {

    /** 释放原因：浏览槽单槽被换出（换连接 / 并发解析被丢弃的重复实例），见 `ServiceLocator.browsingSourceFor` */
    const val RELEASE_BROWSE_REPLACED: String = "browseReplaced"

    /** 释放原因：连接被删除或编辑（`ServiceLocator.closeBrowsingSource`） */
    const val RELEASE_CONN_CHANGED: String = "connChanged"

    /** 释放原因：App 退出（`ServiceLocator.closeSession`） */
    const val RELEASE_SESSION_CLOSE: String = "sessionClose"

    /** 释放原因：会话来源（阅读器正在用的那个）被替换或清空（`ServiceLocator.currentSource` 的 setter / closeSession） */
    const val RELEASE_READER_REPLACED: String = "readerReplaced"

    /** 清空原因：实例释放（`Source.close`） */
    const val CLEAR_ON_CLOSE: String = "close"

    /** 清空原因：手动刷新（下拉更新 / 连接编辑，两条路都走 `Source.invalidateListCache`） */
    const val CLEAR_ON_REFRESH: String = "refresh"

    /**
     * 实例身份（跨行对齐同一个来源实例）：JVM 身份哈希的十六进制 + 类名。
     * 只进日志，不参与任何判定——不与 `equals`/`hashCode` 混用（`SourceReleaseTest` 已证明按引用身份判定）。
     */
    fun instanceTag(source: Source): String =
        source.javaClass.simpleName + "#" + Integer.toHexString(System.identityHashCode(source))

    /** 来源实例被建出来（槽位名 `slot` 说明它落在哪个槽：`browse` = 会话级浏览槽，`reader` = 会话来源） */    fun sourceOpenLine(source: Source, connId: Long?, slot: String): String =
        "sourceOpen instance=" + instanceTag(source) +
            " source=" + source.type.name +
            " conn=" + (connId ?: NO_CONN) +
            " slot=" + slot

    /** 来源实例被释放（或**决定不关**：`closed=false`，见类 KDoc） */
    fun sourceReleaseLine(source: Source, reason: String, closed: Boolean): String =
        "sourceRelease instance=" + instanceTag(source) +
            " source=" + source.type.name +
            " reason=" + reason +
            " closed=" + closed

    /** 封面字节缓存整体清空：清掉的条目数与字节数（`entries` 为非 0 才说明这次真丢了封面） */
    fun coverCacheClearLine(source: Source, reason: String, entries: Int, bytes: Long): String =
        "coverCacheClear instance=" + instanceTag(source) +
            " source=" + source.type.name +
            " reason=" + reason +
            " entries=" + entries +
            " bytes=" + bytes

    /**
     * 一次 SMB 会话（含共享句柄）建立成功（调用点：`SmbjTransport.connectedShare`，在 connect/authenticate/
     * connectShare 全部成功之后）。`rebuilt=true` = 此前已经成功建立过一次会话（重连/换共享）——
     * 判定与次序的承重说明见 `SmbSessionReporter`。
     */
    fun smbSessionOpenLine(host: String, port: Int, share: String, rebuilt: Boolean): String =
        "smbSessionOpen host=" + host +
            " port=" + port +
            " share=" + share +
            " rebuilt=" + rebuilt

    /** `conn=` 拿不到连接 id 时的占位（根槽、测试夹具）：不写 `null`，读日志时按同一个词筛 */
    private const val NO_CONN: String = "none"
}
