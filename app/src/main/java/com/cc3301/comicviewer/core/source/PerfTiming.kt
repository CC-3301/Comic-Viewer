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
 * 打点覆盖票面要求对比的三段耗时来源：一次枚举（[DocumentTreeSource.listEntries]，含命中的快照/新列/条目数）、
 * 一次封面字节（[DocumentTreeSource.coverBytes]，含是否命中字节缓存）、相邻书判定
 * （[DocumentTreeSource.neighbors]：快照是否命中与耗时——票 #93 用它确认「打开书不再列父层」），
 * 以及票 #91 的压缩包读取（打开书 / 取页的总耗时、以及每一次真实取数 `remoteRead kind=direct|block` 的区间与耗时）；
 * 票 #73 另有取页三段与缓存清理（三段**互不重叠**、相加即单页总耗时：`pageBytes` 取字节含 `disk=` 命中与否、
 * `pageDecode` 纯解码、`pageShown` 单页从开始取到可画的总耗时；`diskTrim` 则是一趟后台清理的扫描/删除/释放字节数——
 * 卡顿一出现就抓，用来把尖峰归到取数段或解码段）。
 * 本机没有真实 SMB 与设备，因此「改动前后同一目录的进入/返回/重回耗时」这组数字必须由维护者按票面协议在真机上取。
 *
 * 平台类只在开关为真时才碰（JVM 单测里 `android.util.Log` 不可用，`runCatching` 兜住并保持静默）。
 */
internal object PerfTiming {

    const val TAG = "ComicViewerPerf"

    private val enabled: Boolean by lazy {
        runCatching { android.util.Log.isLoggable(TAG, android.util.Log.DEBUG) }.getOrDefault(false)
    }

    /** 打点开关（由 `log.tag.ComicViewerPerf` 决定；只在 [log] 里读） */
    private val isEnabled: Boolean get() = enabled

    /** 惰性拼消息：开关关闭时连字符串都不拼（热路径上不留开销） */
    inline fun log(message: () -> String) {
        if (isEnabled) runCatching { android.util.Log.d(TAG, message()) }
    }
}
