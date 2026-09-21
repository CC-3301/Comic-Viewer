package com.cc3301.comicviewer.core.source

/**
 * 大目录耗时的 logcat 打点（票 #51 的真机验收协议）。
 *
 * **默认关闭**，只在设备上把该标签打开时才输出（`Log.isLoggable` 的常规用法）：
 *
 * ```
 * adb shell setprop log.tag.ComicViewerPerf DEBUG
 * # 复现「进入目录 / 返回上级 / 退出阅读器再进来」，然后：
 * adb logcat -s ComicViewerPerf
 * ```
 *
 * 打点覆盖票面要求对比的三段耗时来源：一次枚举（[DocumentTreeSource.listEntries]：`snapshotSource=memory|disk|none`
 * 如实反映**会话内存快照 / 落盘快照 / 真列目录**三条命中来源，`childrenCalls`/`probes`/`reused` 分别是
 * 本次的列目录次数、子目录探测条数与增量复用命中条数——票 #74 的 AC「列目录 0 次、探测 0 次」与票 #75 的
 * 「1000+ 目录里新增 1 个只探 1 条」都按这三个计数在真机上核对、加上条目数与耗时；
 * **邻位两行（`neighbors`/`warmNeighbors`）的 `snapshot=<bool>` 是另一个键**（#93 的「邻位判定依据/补齐是否命中快照」），
 * 别与枚举行的 `snapshotSource=` 混读）、
 * 一次封面字节（[DocumentTreeSource.coverBytes]，含是否命中字节缓存）、相邻书判定
 * （[DocumentTreeSource.neighbors]：快照是否命中与耗时——票 #93 用它确认「打开书不再列父层」），
 * 以及票 #91 的压缩包读取（打开书 / 取页的总耗时、以及每一次真实取数 `remoteRead kind=direct|block` 的区间与耗时）；
 * 票 #73 另有取页三段与缓存清理（三段**互不重叠**：`pageBytes` 取字节含 `disk=` 命中与否、`pageDecode` 纯解码、
 * `pageShown` 单页从开始取到可画的总耗时——`pageShown` 是**端到端**口径，除前两段外还含内存位图查表与协程派发，
 * 因此**不是**前两段的机械相加；阅读菜单的预览通路也产出同名的 `pageBytes`/`pageDecode` 两条键（预览不是「单页上屏」、
 * 没有 `pageShown`），读日志时按 book/index 对齐；`diskTrim` 则是一趟后台清理的扫描/删除/释放字节数——
 * 卡顿一出现就抓，用来把尖峰归到取数段或解码段）。
 * 票 #70 起还输出导航观测点（事件名以 `ui/NavEvent` 的四个常量为单一出处——`STARTUP_SKIP` / `STARTUP_LAND` /
 * `STARTUP_FALLBACK` / `BROWSE_BACK`，**字面量只在 `NavObservationTest` 里核一次**，本 KDoc 不复写；一行给出 **回退栈深度 + 栈顶路由 + 浏览历史游标/能否后退**，由
 * `ui/navObservationLine` 拼）——排查「返回被扔回首页/直接退出」与 #98/#99 共用同一套观测。
 * 票 #109 起再登记**浏览页滚动量测**（书柜/浏览页掉帧与封面加载）：摘要行前缀 `browseScroll`（一次滚动一段）、
 * 单次封面加载明细前缀 `browseCoverLoad`，字段口径与折算全在 `core/view/ScrollProbe`，量测协议（怎么开 tag、
 * 抓哪些行、怎么算指标）见工单 #109；帧回调只在开关打开时注册（`ui/BrowseScroll`）。
 * 本机没有真实 SMB 与设备，因此「改动前后同一目录的进入/返回/重回耗时」这组数字必须由维护者按票面协议在真机上取。
 *
 * 平台类只在开关为真时才碰（JVM 单测里 `android.util.Log` 不可用，`runCatching` 兜住并保持静默）。
 */
internal object PerfTiming {

    const val TAG = "ComicViewerPerf"

    /**
     * 打点开关（由 `log.tag.ComicViewerPerf` 决定，`isLoggable` 按进程缓存）：[log] 与所有探针（票 #109 的
     * 帧监听器、组合计数）读的都是这一个名字——需要「不拼字符串、但要先决定是否记数 / 是否注册」的观测点
     * 直接问它，不再另起别名。
     *
     * 读的是 `forcedForTest ?: 平台值`，**不是一次性懒值**：平台值本身仍只算一次（`isLoggable` 的进程缓存），
     * 但用例可以在任何时刻显式覆盖它——否则整批用例里谁先读到就定死了那个值（宿主门禁实测：
     * 计数接线用例因此 `expected:<1> but was:<0>`）。
     */
    val isOn: Boolean get() = forcedForTest ?: platformOn

    /** 平台判定（`log.tag.ComicViewerPerf`；JVM 单测里 `Log` 不可用，`runCatching` 兜住并保持静默） */
    private val platformOn: Boolean by lazy {
        runCatching { android.util.Log.isLoggable(TAG, android.util.Log.DEBUG) }.getOrDefault(false)
    }

    /**
     * **仅测试用**的显式开关（`null` = 按平台 tag 判定）：生产路径从不写它。
     * 用例在 `@Before` 里置 `true`、`@After` 里置回 `null`（见 `ui/BrowseItemCountTest`）。
     */
    @Volatile
    var forcedForTest: Boolean? = null

    /** 惰性拼消息：开关关闭时连字符串都不拼（热路径上不留开销） */
    inline fun log(message: () -> String) {
        if (isOn) runCatching { android.util.Log.d(TAG, message()) }
    }
}
