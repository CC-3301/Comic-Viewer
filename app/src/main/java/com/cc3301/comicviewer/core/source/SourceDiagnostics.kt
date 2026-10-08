package com.cc3301.comicviewer.core.source

/**
 * 偶发退化诊断打点：本对象发出的七类事件（`sourceOpen`/`sourceRelease`/`coverCacheClear`/`smbSessionOpen`
 * 与 2026-09-29 追加的 `smbReadFail`/`smbRebuild`/`smbProbe`）的**行格式唯一出处**（调用方只交事实，
 * 不自己拼字段）；`loadPage`/`pageBytes` 那两类由各自的取页路径拼，
 * 不在本对象里，字段口径见下。
 *
 * 现象（设备反馈）：阅读器看着看着突然转圈、返回书柜时部分封面也是灰的，约十几秒后恢复。
 * 本对象只加打点，不碰取数/缓存路径（2026-09-29 的行为改动在 SMB 传输层：会话建立失败的退避与建连超时）。
 * 开关就是既有的 [PerfTiming]（`log.tag.ComicViewerPerf`，默认关；关着时连字符串都不拼），
 * 打开后每条事件一行、行首即事件名，`adb logcat -s ComicViewerPerf -v time` 给出的时间戳
 * 就是要观察的时间线（转圈开始时刻 ↔ 下列事件时刻）：
 *
 * ```
 * adb shell setprop log.tag.ComicViewerPerf DEBUG   # 设完重启 APP（isLoggable 按进程缓存）
 * adb logcat -s ComicViewerPerf -v time | grep -E 'sourceOpen|sourceRelease|coverCacheClear|pageBytes|loadPage|smb'
 * ```
 *
 * 各事件要回答的问题（与三条机制推测一一对应）：
 * - [sourceOpenLine] / [sourceReleaseLine]：**来源实例**有没有被释放重建？`instance=` 是实例身份
 *   （同一个实例跨行相等；实例重建后必换一个身份），`reason=` 是释放的触发原因（常量见下），
 *   `closed=` 为假表示那条路径**没有**真关（阅读器正在用，交给会话来源那一侧关）。
 * - [coverCacheClearLine]：封面字节缓存被整体清空，以及**清掉了多少**（`entries`/`bytes`）。
 *   「所有封面一起变灰」在这一行上是 `entries` 十几到几十、且紧跟在 `sourceRelease` 或 `reason=refresh` 之后。
 * - **取页**：看**阅读器侧** `pageBytes` 的 `disk=` 与**来源侧** `loadPage` 的 `source=`/`instance=`/`from=`，
 *   两者用 `book=`/`index=`（即 `page=`）对齐。
 *   **`disk=true`（命中页磁盘缓存）⇒ 这一次取页根本没碰来源，慢不可能在网络上。**
 *   `disk=false` 时按 `from=` 分两条路（**修正**：此前给的「`disk=false` 且没有 `remoteRead` ⇒ 被块缓存接住、
 *   慢不在网络」对图片书不成立——图片书那条路**根本不发** `remoteRead`，照旧规则会把网络慢反向排除）：
 *   - `from=image`（图片书：本层图片或单张图直接成书）：`FilePageRef.bytes()` 就是 `node.readBytes()`——
 *     **每一次取页都真读了一次来源后端**（远端来源上就是一次网络往返）。这条路上不发 `remoteRead`，
 *     **它的缺席不代表没走网络**；判「慢在不在网络」就看这一行：`disk=false` + `ms=` 大 + 同期有
 *     `sourceOpen`/`smbSessionOpen` 事件 ⇒ 网络/重建那一支。
 *   - `from=archive`（压缩包书）：包内读走 `openRandomAccess` → `BlockCachedRandomAccess`，**可能被
 *     进程内块缓存接住**；再看同一时间段有没有 `remoteRead kind=direct|block` 行：有 = 真发了取数（网络），
 *     没有 = 被块缓存接住，慢不在往返上。
 *   （Komga 来源的取页不在 `loadPage` 这条路上：`KomgaSource.loadPage` 每一页就是一次 HTTP GET，
 *    无包内块缓存 ⇒ 同样不能用 `remoteRead` 的缺席当判据；这里的判读规则只覆盖文件来源的两条 `from=` 分支——
 *    Komga 侧没有对应探针，是已知缺口。）
 * - [smbSessionOpenLine]：一次 SMB 会话（含共享句柄）**建立成功之后**才发（connect → authenticate →
 *   connectShare 全部过了；建连失败不打点，也就看不到这一行）。
 *   `rebuilt=true` = **此前已经成功建立过一次会话**（断链/空闲断开后的重连、或换共享）：判定的真相是
 *   「建立过的次数 ≥ 2」，**不是**「会话句柄非空」（重连路径上句柄已被丢掉置 null，用它会把重连报成
 *   首次建连）。次序与判定收在 `SmbSessionLifecycle`（真相由它的 `establishedBefore` 持有），那里可 JVM 单测。
 * - SMB 上的三条（2026-09-29 口径，判读口径就写在各自的函数 KDoc 上）：
 *   [smbReadFailLine]（读失败**那一刻**：操作 / 等了多久 / 类型 / 异常类名）、
 *   [smbRebuildLine]（第几次尝试 + 四段耗时 + 失败在哪一段）、
 *   [smbProbeLine]（心跳每一拍：真探还是跳过、结果与耗时）。它们回答的两个问题：
 *   「会话为什么失效、失效在哪一刻」与「那十几秒花在哪一段」。
 *
 * 这些行**只读事实**：不改缓存口径、不改取数路径、不改任何判定（2026-09-29 的行为改动全在 SMB 传输层，不在这些行的产出上）。
 */
internal object SourceDiagnostics {

    /** 释放原因：浏览槽单槽被换出（换连接 / 并发解析被丢弃的重复实例），见 `SessionState.browsingSourceFor` */
    const val RELEASE_BROWSE_REPLACED: String = "browseReplaced"

    /** 释放原因：连接被删除或编辑（`SessionState.closeBrowsingSource`） */
    const val RELEASE_CONN_CHANGED: String = "connChanged"

    /** 释放原因：App 退出（`SessionState.end`） */
    const val RELEASE_SESSION_CLOSE: String = "sessionClose"

    /** 释放原因：会话来源（阅读器正在用的那个）被替换或清空（`SessionState.currentSource` 的 setter / `SessionState.end`） */
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

    /** `loadPage ... from=` 的取值：图片书（直接读来源后端，每次都真读一次） */
    const val FROM_IMAGE: String = "image"

    /** `loadPage ... from=` 的取值：压缩包书（包内读可能被进程内块缓存接住） */
    const val FROM_ARCHIVE: String = "archive"

    /**
     * 来源实例被建出来（槽位名 `slot` 说明它落在哪个槽：`browse` = 会话级浏览槽，`reader` = 会话来源）。
     * `conn=` 是该来源所属连接的 id：`slot=reader` 那行由 `SessionState.adopt` 保证
     * 与来源**同时落槽**（顺序在那个方法内是承重契约，见它的 KDoc）。
     */
    fun sourceOpenLine(source: Source, connId: Long?, slot: String): String =
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
     * 一次 SMB 会话（含共享句柄）建立成功（调用点：`SmbSessionLifecycle.establish`，在 connect/authenticate/
     * connectShare 全部成功之后）。`rebuilt=true` = 此前已经成功建立过一次会话（重连/换共享）——
     * 判定与次序的承重说明见 `SmbSessionLifecycle`。
     */
    fun smbSessionOpenLine(host: String, port: Int, share: String, rebuilt: Boolean): String =
        "smbSessionOpen host=" + host +
            " port=" + port +
            " share=" + share +
            " rebuilt=" + rebuilt

    /**
     * 一次读失败（调用点：`SmbSessionLifecycle.withSession`）：
     * `op=` 哪一类操作（列目录 / stat / 取整份字节 / 开随机访问句柄）、`ms=` **从发起到失败等了多久**、
     * `kind=` 失败类型（`peerClose` 对端断开 / `readTimeout` 读超时 / `appClose` App 主动关 /
     * `backoff` 退避期内就地拒掉 / `other` 其它）、`ex=` 异常类名。
     *
     * **为什么必须有它**：`smbSessionOpen` 只在一条会话**建立成功之后**才发，因此一次停摆里
     * 「失败那一刻」完全空白——设备日志里那串 799/997/1049/… 毫秒的失败读背后到底发生了什么，
     * 当时只能猜。**每一次尝试各一条**：一条读先失败、重试又成功时也会留一条，那正是会话失效
     * 被发现的时刻。`kind=backoff` 那些 `ms` 应该接近 0（退避的效果判据之一）。
     */
    fun smbReadFailLine(op: String, ms: Long, kind: String, ex: String): String =
        "smbReadFail op=" + op +
            " ms=" + ms +
            " kind=" + kind +
            " ex=" + ex

    /**
     * 一次会话建立的**分段耗时**，不论成败都发一条：
     * `attempt=` 本轮第几次尝试（连续失败计数 + 1，一次成功即归零）、`closeMs` 关旧会话 / `connectMs` 连接 /
     * `authMs` 认证 / `shareMs` 进共享四段耗时、`ms=` 合计、`failed=` 失败在哪一段（`none` = 这次建成了，
     * 取值见 `SmbRebuildSegment`）、`ex=` 那一段的异常类名（`none` 同上）。
     *
     * **没跑到的那一段就是 0**（连接段就失败 ⇒ `authMs=0 shareMs=0`），所以 `failed=` 是必需的判读字段。
     * 设备判据：一次会话失效的用户可见等待应从 15 秒降到 10 秒以内——看的就是成功那行的 `connectMs=`。
     */
    fun smbRebuildLine(
        attempt: Int,
        closeMs: Long,
        connectMs: Long,
        authMs: Long,
        shareMs: Long,
        ms: Long,
        failedSegment: String?,
        ex: String?,
    ): String =
        "smbRebuild attempt=" + attempt +
            " closeMs=" + closeMs +
            " connectMs=" + connectMs +
            " authMs=" + authMs +
            " shareMs=" + shareMs +
            " ms=" + ms +
            " failed=" + (failedSegment ?: NO_VALUE) +
            " ex=" + (ex ?: NO_VALUE)

    /**
     * 探活心跳的一拍：`tick=probe` = 真探了一次（带 `ok=` 结果与 `ms=` 耗时）；
     * `tick=skip` = 这一拍什么都没探（间隔内有真实读顶掉了它，或还没有会话/已释放）。
     *
     * 为什么必须有它：设备日志里 18:57:43 → 18:58:33 有 50 秒空闲、本该有 1~2 次探活，
     * 而当时探针**成功不产行** ⇒ 「心跳跑没跑、有没有探到死会话」只能靠推测。
     */
    fun smbProbeLine(probed: Boolean, ok: Boolean, ms: Long): String =
        if (probed) "smbProbe tick=probe ok=" + ok + " ms=" + ms else "smbProbe tick=skip"

    /** `conn=` 拿不到连接 id 时的占位（根槽、测试夹具）：不写 `null`，读日志时按同一个词筛 */
    private const val NO_CONN: String = "none"

    /** 「这一格没有值」的统一占位（`smbRebuild` 的 `failed=`/`ex=`）：不写 `null`，按同一个词筛 */
    private const val NO_VALUE: String = "none"
}
