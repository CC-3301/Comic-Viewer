package com.cc3301.comicviewer.ui.session

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.nav.BrowseHistory
import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.source.PerfTiming
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceDiagnostics
import com.cc3301.comicviewer.ui.releaseReplacedSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话状态：一次 APP 运行期内、跨屏共享、会话结束（[end]）同一次收口的状态。
 *
 * 三类：**会话来源槽**（阅读器在用的 [currentSource] / [currentConnId]、浏览页在用的单槽 [browsingSourceFor]）、
 * **[entryNames]**、**[browseHistory]**。配置类持久设置（排序、视图档位、主题、上次停留 / 阅读位置）不在本模块里
 * ——那些跨重启保持，不随会话结束收口。
 *
 * 依赖由构造参数注入（[scope] / [sourceFactory] / [recordBrowsingPath]）：生产那三份由组合根装配
 * （`MainActivity`，依赖取自窄根 `ServiceLocator.newSessionState()`），单测交自己那份。
 */
class SessionState(
    /** 长于组合生命周期的协程域：来源释放与落盘等写入挂在它上面 */
    private val scope: CoroutineScope,
    /** 来源构造器（生产恒为「按连接装配一条来源」）：测试接缝，注入计数型后端后断言实例复用与释放打在本模块的入口上 */
    private val sourceFactory: suspend (ConnectionEntity) -> Source,
    /** 会话结束时的落盘钩子：把浏览路径交给落盘侧（见 [end]） */
    private val recordBrowsingPath: (List<BrowseLocation>) -> Unit,
) {

    /**
     * 会话当前来源：导航参数只传 id，实例跨屏复用（进程常驻）。
     * 切换来源时异步释放上一个会话资源（SMB 连接/套接字）。
     * **写入只有 [adopt] 与 [clear] 两个入口**：[adopt] 交来源时连接 id 一起换，
     * 否则 `sourceOpen slot=reader` 那行会把来源归到上一个连接上。
     */
    @Volatile
    var currentSource: Source? = null
        private set(value) {
            val previous = field
            field = value
            // 会话来源落槽（阅读器只认它）——与下面的释放行同一把实例身份
            if (value != null && previous !== value) {
                PerfTiming.log { SourceDiagnostics.sourceOpenLine(value, currentConnId, "reader") }
            }
            if (previous != null && previous !== value) {
                // 阅读器会话来源被替换/清空：释放上一个实例（与上面那行打点同一把实例身份）
                PerfTiming.log {
                    SourceDiagnostics.sourceReleaseLine(
                        previous,
                        SourceDiagnostics.RELEASE_READER_REPLACED,
                        closed = true,
                    )
                }
                scope.launch { runCatching { previous.close() } }
                // 条目名缓存随来源失效：既防止无上限增长，也避免不同来源同名 id 串名
                entryNames.clear()
            }
        }

    /**
     * 会话内的条目名缓存：文件源的 id 能反解出文件名，Komga 的 id 只有 UUID/数字，
     * 列表里见过就记下来，供浏览页标题与阅读菜单标题使用（进程内会话级，不进 Room）。
     */
    val entryNames = ConcurrentHashMap<String, String>()

    /** 浏览历史（spec 故事 37/38）：会话级，跨屏共享；阅读器不进历史 */
    val browseHistory = BrowseHistory()

    /** 当前浏览连接 id（与 [currentSource] 同源；书 id 只在对应连接内有效） */
    @Volatile
    var currentConnId: Long? = null
        private set

    /**
     * 阅读器/启动还原的会话来源落槽：来源与它的连接 id **一起**交。
     *
     * 顺序（先 connId 后 source）是承重契约，落槽只此一处：`sourceOpen slot=reader` 那行的 `conn=`
     * 读 [currentConnId]，倒过来会把来源归到上一个连接（守护用例：`SourceLifecycleProbeTest`）。
     */
    fun adopt(source: Source, connId: Long) {
        currentConnId = connId
        currentSource = source
    }

    /** 清空会话来源槽：只放掉来源（[currentConnId] 不由它清）。 */
    fun clear() {
        currentSource = null
    }

    /** 会话级浏览来源的单槽锁与槽位（见 [browsingSourceFor]） */
    private val browsingLock = Any()

    @Volatile
    private var browsingSource: Source? = null

    @Volatile
    private var browsingConnId: Long? = null

    @Volatile
    private var browsingConfig: String? = null

    /**
     * 会话级浏览来源：浏览页/柜页按路由 connId 解析来源时走这里，**同一连接复用同一个实例**。
     *
     * 会话级列表缓存（[Source.invalidateListCache] 背后的东西）挂在来源实例上，而页面组合在导航时销毁：
     * 页面自己建的实例带不过「进子目录 → 返回上级」（每次都是新实例 = 空缓存 = 重新整层枚举）。
     * 实例提升到会话级后这条路径才真正命中缓存，同时少一次 SMB 建连。
     *
     * 单槽：换到别的连接时释放上一个（不同时挂 N 个 SMB 会话）；连接被删除/编辑（configJson 变化）
     * 由 [closeBrowsingSource] 或下一次解析释放。阅读器正在用的实例（[currentSource]）不关——
     * 那块守卫见 [releaseReplacedSource]（它在别处被替换时由 [currentSource] 的 setter 释放）。
     */
    suspend fun browsingSourceFor(conn: ConnectionEntity): Source {
        synchronized(browsingLock) {
            browsingSource?.let { if (browsingConnId == conn.id && browsingConfig == conn.configJson) return it }
        }
        val created = sourceFactory(conn)
        // 新建实例的时刻（同一连接复用时不打——复用不是重建）
        PerfTiming.log { SourceDiagnostics.sourceOpenLine(created, conn.id, "browse") }
        val replaced: Source?
        val result: Source
        synchronized(browsingLock) {
            val cached = browsingSource
            if (cached != null && browsingConnId == conn.id && browsingConfig == conn.configJson) {
                // 并发解析：另一个页面已经建好同一连接的实例，丢弃自己这份（否则同时挂两条 SMB 会话）
                replaced = created
                result = cached
            } else {
                replaced = cached
                browsingSource = created
                browsingConnId = conn.id
                browsingConfig = conn.configJson
                result = created
            }
        }
        if (replaced != null && replaced !== result) releaseBrowsingInstance(replaced, SourceDiagnostics.RELEASE_BROWSE_REPLACED)
        return result
    }

    /**
     * 浏览槽实例的唯一释放路径：先同步取守卫快照再异步关闭。
     *
     * 守卫判定用 [releaseReplacedSource]（纯函数，由 SourceReleaseTest 锁定）：待释放实例若正是**当时**
     * 阅读器在用的会话来源（[currentSource]），就不在这里关——阅读器路由只认它，半途关掉会让回退栈里那本书报错；
     * 关闭责任归会话来源那一侧：[currentSource] 的 setter 在真正替换时释放，[end] 在 App 退出时释放。
     * 其余情况在这里关一次（槽位已换出/清空，同一实例不会再进来第二次）。
     *
     * [currentSource] 必须在**同步调用段**取快照：放进协程里读到的是后续赋值，
     * 会变成「浏览槽关一次 + setter 关一次」的重复关闭。
     */
    private fun releaseBrowsingInstance(released: Source, reason: String) {
        val session = currentSource
        val sessionHolds = released === session
        // `closed=false` 就是「阅读器正在用这个实例、所以没关」（释放判定本身由 SourceReleaseTest 锁）
        PerfTiming.log { SourceDiagnostics.sourceReleaseLine(released, reason, closed = !sessionHolds) }
        scope.launch { releaseReplacedSource(released, session) }
    }

    /**
     * 会话槽位里**已解析**的浏览来源：只在槽位命中该连接时返回，**不新建实例**。
     * 界面用它拿同步快照（[Source.cachedEntries]）当首帧，新一屏出生因此不再先渲染「加载中…」。
     * **冷启动（进程重启）**时槽位为空、本方法返回 null：首帧仍可能短暂显示「加载中…」——
     * 内容来自落盘快照（异步路径），照旧 0 次列目录、0 次探测；本方法不读盘（组合期调用），
     * 因此不让主线程做文件 IO。
     */
    fun browsingSourceIfResolved(connId: Long): Source? = synchronized(browsingLock) {
        browsingSource?.takeIf { browsingConnId == connId }
    }

    /**
     * 浏览槽的释放入口：连接被删除/编辑时按 [connId] 调，或 App 退出时经 [end] 调。
     * 清槽位同步完成；该不该关、由谁关见 [releaseBrowsingInstance]（同一实例只关一次）。
     * 落盘列表快照**不在本方法里清**（[end] 走的 `connId = null` 要保留快照）；
     * 连接被编辑/删除时由窄根的变更入口成对清。
     *
     * 只有窄根的连接变更路径调得到它（模块外没有调用点）。
     */
    internal fun closeBrowsingSource(connId: Long? = null) {
        val released = synchronized(browsingLock) {
            val cached = browsingSource
            if (cached == null || (connId != null && browsingConnId != connId)) {
                null
            } else {
                browsingSource = null
                browsingConnId = null
                browsingConfig = null
                cached
            }
        } ?: return
        // 按槽位名清的是「连接被编辑/删除」（App 退出那条走 [end] → connId = null）
        val reason = if (connId == null) {
            SourceDiagnostics.RELEASE_SESSION_CLOSE
        } else {
            SourceDiagnostics.RELEASE_CONN_CHANGED
        }
        releaseBrowsingInstance(released, reason)
    }

    /**
     * App 级释放入口：Activity 真正退出时调，把会话级来源都关掉——
     * 浏览槽实例（不属于阅读器时由 [closeBrowsingSource] 关）与阅读器会话来源（由 setter 关），
     * 每个实例只关一次，不留未关闭的会话（**内存**列表快照随 [Source.close] 一并清空）。
     * 落盘列表快照有意不清——退出 APP 再进来仍要命中（连接级清理由窄根负责）。
     * 回退栈随 Activity 一并销毁，而**只有真正退出（Activity finish）才算会话结束**（旋转这类非 finish 的重建
     * 保留历史，「旋转后按返回回到上一层」靠的就是它）——会话结束必须清 [browseHistory]，否则下一会话会把恢复到的位置
     * record 到上一会话的旧历史栈上，浏览页的返回处理器（返回决议 `browseBackInterception`）落到一个**不在回退栈上**的层级：
     * 界面被弹回首页、再按一次真的退出 APP（见 `BrowserBackStackSyncTest`）。本方法是历史与回退栈的会话级同步点之一，
     * 完整同步路径与已知未同步点见 `docs/SPEC.md` 的 UI 骨架条「返回逐级」。
     *
     * 清之前先把浏览**路径**落盘（[recordBrowsingPath]）——重启后按它重建整条层级链，
     * 返回因此逐级回到上一级（只落盘「当前这一层」的话，重启后返回只剩「回首页」一条路）。
     * 这里不是唯一的写点——浏览页每层显示时也写一次（`StartupStore.recordBrowsePosition`），
     * 因为设备上更常见的退出是任务被划掉 / 进程被杀，那种退出没有 finish、本方法不会跑；两次写的是同一个值。
     */
    fun end() {
        closeBrowsingSource()
        // 阅读器会话来源交给 setter 释放（与换来源同一条路径）
        clear()
        recordBrowsingPath(browseHistory.path())
        browseHistory.clear()
    }
}

/**
 * 会话状态的**组合期提供点**：`MainActivity` 在 `setContent` 里提供（提供点唯一一处），
 * 界面按 [LocalSessionState] 取实例。没提供就报错——漏接是接线错，不静默降级
 *（与 `ui/SystemBarInsets.kt` 的稳定版 inset 同一口径）。
 */
internal val LocalSessionState: ProvidableCompositionLocal<SessionState> = compositionLocalOf {
    error("没有提供会话状态（提供点在 MainActivity，见 ui/session/SessionState.kt）")
}
