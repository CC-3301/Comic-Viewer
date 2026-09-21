package com.cc3301.comicviewer.ui

import android.content.Context
import android.net.Uri
import com.cc3301.comicviewer.core.data.AppDatabase
import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.data.RoomProgressStore
import com.cc3301.comicviewer.core.input.WheelHandler
import com.cc3301.comicviewer.core.nav.BrowseHistory
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.reader.VolumeAction
import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.ListingSnapshotStore
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.fs.SafBackend
import com.cc3301.comicviewer.core.source.listingSnapshotDir
import com.cc3301.comicviewer.core.source.komga.ClassifyingKomgaApi
import com.cc3301.comicviewer.core.source.komga.HttpKomgaApi
import com.cc3301.comicviewer.core.source.komga.KomgaConnectionConfig
import com.cc3301.comicviewer.core.source.komga.KomgaSource
import com.cc3301.comicviewer.core.source.smb.ClassifyingTransport
import com.cc3301.comicviewer.core.source.smb.SmbBackend
import com.cc3301.comicviewer.core.source.smb.SmbConnectionConfig
import com.cc3301.comicviewer.core.source.smb.SmbjTransport
import com.cc3301.comicviewer.core.source.webdav.ClassifyingWebDavTransport
import com.cc3301.comicviewer.core.source.webdav.HttpWebDavTransport
import com.cc3301.comicviewer.core.source.webdav.WebDavBackend
import com.cc3301.comicviewer.core.source.webdav.WebDavConnectionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** 极简依赖定位（绿地阶段；后续票按需演进） */
object ServiceLocator {

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    internal val context: Context get() = appContext ?: throw IllegalStateException("ServiceLocator 未初始化")

    /** APP 级协程域：退出回调等长于组合生命周期的写入 */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 打开书的前置槽（票 #108 E1-A）：浏览页点击时写入、阅读页组合期同步取走。
     * 与「当前来源」同属会话级状态（不是配置），因此不随组合销毁。
     */
    internal val readerPrelude = ReaderPrelude()

    val db: AppDatabase by lazy {
        androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, "comic-viewer.db")
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
            )
            .build()
    }

    /**
     * 会话当前来源：导航参数只传 id，实例跨屏复用（进程常驻）。
     * 切换来源时异步释放上一个会话资源（票 11：SMB 连接/套接字）。
     */
    @Volatile
    var currentSource: Source? = null
        set(value) {
            val previous = field
            field = value
            if (previous != null && previous !== value) {
                appScope.launch { runCatching { previous.close() } }
                // 条目名缓存随来源失效（票 13）：既防止无上限增长，也避免不同来源同名 id 串名
                entryNames.clear()
            }
        }

    /**
     * 会话内的条目名缓存（票 13）：文件源的 id 能反解出文件名，Komga 的 id 只有 UUID/数字，
     * 列表里见过就记下来，供浏览页标题与阅读菜单标题使用（进程内会话级，不进 Room）。
     */
    val entryNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 浏览历史（票 09，spec 故事 37/38）：会话级，跨屏共享；阅读器不进历史 */
    val browseHistory = BrowseHistory()

    /** 当前浏览连接 id（与 [currentSource] 同源；书 id 只在对应连接内有效） */
    @Volatile
    var currentConnId: Long? = null

    /** 会话级浏览来源的单槽锁与槽位（票 #30 P1；见 [browsingSourceFor]） */
    private val browsingLock = Any()

    @Volatile
    private var browsingSource: Source? = null

    @Volatile
    private var browsingConnId: Long? = null

    @Volatile
    private var browsingConfig: String? = null

    /** 上次阅读的位置（带来源；抽屉「阅读器」入口打开该书，票 09）。
     * 写入即落盘（票 20，spec 故事 47）：浏览页/柜页打开书、阅读器内换书都走这里，
     * 启动时才判得出「上次退出时正在看书」该打开哪一本。
     */
    @Volatile
    var lastRead: LastRead? = null
        set(value) {
            field = value
            StartupStore.recordLastRead(value)
        }

    /** 阅读器音量键处理器（票 20）：阅读页在组合期间注册，返回 true = 已消费 */
    val volumeKeySlot = HandlerSlot<(VolumeAction) -> Boolean>()

    /**
     * 前台界面的滚轮接入（票 17，spec 故事 22/35）：列表页与阅读页在组合期间注册，离开即清空。
     * 由 MainActivity 的分发入口读取，界面自己不需要感知平台事件。
     */
    val wheelSlot = HandlerSlot<WheelHandler>()

    /**
     * 前进侧键处理器（票 17，spec 故事 37）：由 AppNav 注册；票 32 起抽屉不再有前进入口，前进只由它触发。
     * 返回 false = 无前进历史（消费不了就交回系统）。
     */
    val forwardHistorySlot = HandlerSlot<() -> Boolean>()

    /**
     * 鼠标右键处理器（票 17，spec 故事 36）：阅读页组合期间注册，参数为点击横坐标。
     * 右键与左键等价（都是触摸区域行为），因此只有存在触摸区域的阅读页注册。
     */
    val mouseSecondaryTapSlot = HandlerSlot<(Float) -> Unit>()

    /**
     * 来源构造器（生产恒为 [sourceForConnection]）：测试接缝，注入计数型后端后断言能打在 App 接线上
     * （[browsingSourceFor] 的实例复用与释放），而不是「测试自己持有一个 Source」的单元场景（票 #30 P1）。
     */
    @Volatile
    internal var sourceFactory: suspend (ConnectionEntity) -> Source = { sourceForConnection(it) }

    /**
     * 会话级浏览来源（票 #30 P1）：浏览页/柜页按路由 connId 解析来源时走这里，**同一连接复用同一个实例**。
     *
     * 会话级列表缓存（[Source.invalidateListCache] 背后的东西）挂在来源实例上，而页面组合在导航时销毁：
     * 页面自己建的实例带不过「进子目录 → 返回上级」（每次都是新实例 = 空缓存 = 重新整层枚举）。
     * 实例提升到会话级后这条路径才真正命中缓存，同时少一次 SMB 建连。
     *
     * 单槽：换到别的连接时释放上一个（票 11 纪律：不同时挂 N 个 SMB 会话）；连接被删除/编辑（configJson 变化）
     * 由 [closeBrowsingSource] 或下一次解析释放。阅读器正在用的实例（[currentSource]）不关——
     * 那块守卫见 [releaseReplacedSource]（它在别处被替换时由 [currentSource] 的 setter 释放）。
     */
    suspend fun browsingSourceFor(conn: ConnectionEntity): Source {
        synchronized(browsingLock) {
            browsingSource?.let { if (browsingConnId == conn.id && browsingConfig == conn.configJson) return it }
        }
        val created = sourceFactory(conn)
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
        if (replaced != null && replaced !== result) releaseBrowsingInstance(replaced)
        return result
    }

    /**
     * 浏览槽实例的唯一释放路径（票 #30 P1）：先同步取守卫快照再异步关闭。
     *
     * 守卫判定用 [releaseReplacedSource]（纯函数，由 SourceReleaseTest 锁定）：待释放实例若正是**当时**
     * 阅读器在用的会话来源（[currentSource]），就不在这里关——阅读器路由只认它，半途关掉会让回退栈里那本书报错；
     * 关闭责任归会话来源那一侧：[currentSource] 的 setter 在真正替换时释放，[closeSession] 在 App 退出时释放。
     * 其余情况在这里关一次（槽位已换出/清空，同一实例不会再进来第二次）。
     *
     * [currentSource] 必须在**同步调用段**取快照：放进协程里读到的是后续赋值，
     * 会变成「浏览槽关一次 + setter 关一次」的重复关闭。
     */
    private fun releaseBrowsingInstance(released: Source) {
        val session = currentSource
        appScope.launch { releaseReplacedSource(released, session) }
    }

    /**
     * 会话槽位里**已解析**的浏览来源（票 #74 / 承办 #73 AC3）：只在槽位命中该连接时返回，**不新建实例**。
     * 界面用它拿同步快照（[Source.cachedEntries]）当首帧，因此从阅读器返回浏览页不再先渲染「加载中…」。
     * **冷启动（进程重启）**时槽位为空、本方法返回 null：首帧仍可能短暂显示「加载中…」——
     * 内容来自落盘快照（异步路径），照旧 0 次列目录、0 次探测；本方法不读盘（组合期调用），
     * 因此不让主线程做文件 IO。
     */
    fun browsingSourceIfResolved(connId: Long): Source? = synchronized(browsingLock) {
        browsingSource?.takeIf { browsingConnId == connId }
    }

    /**
     * 清某连接名下的落盘列表快照（票 #74）：由**编辑/删除连接**的调用点显式调
     * （`SourceConnectionsScreen` 的保存/删除、`LocalRootsScreen.deleteLocalConnection`）——
     * 与 [closeBrowsingSource] 的槽位释放是两个关注点：App 退出也走槽位释放，但**不清落盘**。
     */
    fun purgeListingSnapshots(connId: Long) {
        listingSnapshotDirOrNull()?.let { ListingSnapshotStore.clearConnection(it, connId) }
    }

    /**
     * 会话级浏览来源的释放入口（票 #30 P1）：连接被删除/编辑时按 [connId] 调，或 App 退出时经 [closeSession] 调。
     * 清槽位同步完成；该不该关、由谁关见 [releaseBrowsingInstance]（同一实例只关一次）。
     * **票 #74**：落盘列表快照**不在本方法里清**（[closeSession] 走的 `connId = null` 要保留快照）；
     * 连接被编辑/删除时由调用点显式调 [purgeListingSnapshots]。
     */
    fun closeBrowsingSource(connId: Long? = null) {
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
        releaseBrowsingInstance(released)
    }

    /**
     * App 级释放入口（票 #30 P1）：Activity 真正退出时调，把会话级来源都关掉——
     * 浏览槽实例（不属于阅读器时由 [closeBrowsingSource] 关）与阅读器会话来源（由 setter 关），
     * 每个实例只关一次，不留未关闭的会话（**内存**列表快照随 [Source.close] 一并清空）。
     * **票 #74**：落盘列表快照有意不清——退出 APP 再进来仍要命中（连接级清理由 [purgeListingSnapshots] 负责）。
     * **票 #70**：回退栈随 Activity 一并销毁，而**只有真正退出（Activity finish）才算会话结束**（旋转这类非 finish 的重建
     * 保留历史，AC4「旋转后按返回回到上一层」靠的就是它）——会话结束必须清 [browseHistory]，否则下一会话会把恢复到的位置
     * record 到上一会话的旧历史栈上，浏览页的返回处理器（返回决议 [com.cc3301.comicviewer.ui.browseBackInterception]）落到一个**不在回退栈上**的层级：
     * 界面被弹回首页、再按一次真的退出 APP（见 `BrowserBackStackSyncTest`）。本方法是历史与回退栈的会话级同步点之一，
     * 完整同步路径与已知未同步点见 `docs/SPEC.md` 的 UI 骨架条「返回逐级」。
     *
     * **票 #70 r2**：清之前先把浏览**路径**落盘（[StartupStore.recordBrowsingPath]）——重启后按它重建整条层级链，
     * 返回因此逐级回到上一级（只落盘「当前这一层」的话，重启后返回只剩「回首页」一条路，正是追加口径里的现象 A）。
     * **票 #70 r2 复审**：这里不再是唯一的写点——浏览页每层显示时也写一次（[StartupStore.recordBrowsePosition]），
     * 因为真机上更常见的退出是任务被划掉 / 进程被杀，那种退出没有 finish、本方法不会跑；两次写的是同一个值。
     */
    fun closeSession() {
        closeBrowsingSource()
        // 阅读器会话来源交给 setter 释放（与换来源同一条路径）
        currentSource = null
        // 未初始化（纯 JVM 单测直接调本方法）时不落盘：与 [listingSnapshotDirOrNull] 同一口径——
        // 落盘不是这些路径的必需环节，不能因此抛出。
        if (appContext != null) StartupStore.recordBrowsingPath(browseHistory.path())
        browseHistory.clear()
    }

    suspend fun sourceForConnection(conn: ConnectionEntity): Source = when (conn.sourceType) {
        SourceType.LOCAL.name -> DocumentTreeSource(
            backend = SafBackend(context, Uri.parse(conn.configJson)),
            progressStore = RoomProgressStore(db.readingProgressDao()),
            // 封面落盘缓存（票 10「封面生成后缓存」；票 #30 只在按需取封面时才写，枚举期不再写）
            coverCacheDir = context.cacheDir,
            // 列表快照落盘（票 #74）：键 = 连接 id + 容器 id
            listingSnapshots = listingSnapshotStoreFor(conn.id),
        )
        // SMB / WebDAV（票 11/12）：配置损坏或非法时直接抛中文提示，由 UI 展示
        SourceType.SMB.name -> {
            val config = smbConfigOf(conn)
            DocumentTreeSource(
                backend = SmbBackend(ClassifyingTransport(SmbjTransport(config), config), config),
                progressStore = RoomProgressStore(db.readingProgressDao()),
                coverCacheDir = context.cacheDir,
                sourceType = SourceType.SMB,
                listingSnapshots = listingSnapshotStoreFor(conn.id),
            )
        }
        SourceType.WEBDAV.name -> {
            val config = webDavConfigOf(conn)
            DocumentTreeSource(
                backend = WebDavBackend(ClassifyingWebDavTransport(HttpWebDavTransport(config), config), config),
                progressStore = RoomProgressStore(db.readingProgressDao()),
                coverCacheDir = context.cacheDir,
                sourceType = SourceType.WEBDAV,
                listingSnapshots = listingSnapshotStoreFor(conn.id),
            )
        }
        SourceType.KOMGA.name -> {
            val config = komgaConfigOf(conn)
            KomgaSource(
                // 归类装饰器把 HTTP/IO 失败转成带中文提示的 KomgaException（地址不通/认证失败/超时）
                api = ClassifyingKomgaApi(HttpKomgaApi(config), config),
                config = config,
                progressStore = RoomProgressStore(db.readingProgressDao()),
            )
        }
        // 未知来源（手工改库、降级安装留下的旧类型）：按既有约定抛带中文提示的 IllegalArgumentException，
        // 由 UI 统一 runCatching 展示（浏览页/柜页/连接列表），不崩溃也不静默
        else -> throw IllegalArgumentException("来源类型未知（连接配置损坏），请重新添加该连接：" + conn.sourceType)
    }

    /**
     * 落盘列表快照的根目录（票 #74）：APP 私有 cacheDir 下；ServiceLocator 未初始化（单测直接调
     * [closeBrowsingSource]）时为 null——落盘不是这些路径的必需环节，不能因此抛出。
     */
    private fun listingSnapshotDirOrNull(): File? = appContext?.cacheDir?.let(::listingSnapshotDir)

    /** 某连接名下的落盘快照表（票 #74）：枚举时读写，键 = 连接 id + 容器 id */
    private fun listingSnapshotStoreFor(connId: Long): ListingSnapshotStore? =
        listingSnapshotDirOrNull()?.let { ListingSnapshotStore(it, connId) }

    /**
     * SMB 连接配置解析（损坏/非法/凭据解不出来时抛中文提示，由 UI 展示），并带上连接行的名字
     * （各 config 的 `rowDisplayName` 运行期载体，票 #72 r2：报错文案因此与列表里的名字恒等）。
     */
    internal fun smbConfigOf(conn: ConnectionEntity): SmbConnectionConfig {
        val config = SmbConnectionConfig.fromJson(conn.configJson)
            ?: throw IllegalArgumentException("SMB 连接配置损坏，请重新添加")
        // 密文解不出来（票 #27：换机 / 密钥失效 / 密文损坏）：这不是「配置损坏」——
        // 地址等字段还在，用户重填密码就能修好，所以提示重填而不是叫用户「重新添加」
        if (config.credentialsNeedReentry) throw IllegalArgumentException(SMB_CREDENTIAL_REENTRY_HINT)
        SmbConnectionConfig.validate(config)?.let { throw IllegalArgumentException(it) }
        // 带上连接行的名字：报错文案取 config.displayName，列表/书柜取列——存量行两者会不一致，
        // 载体让它们恒等（不进 configJson，见 config 里的 rowDisplayName）
        return config.copy(rowDisplayName = conn.displayName)
    }

    /** WebDAV 同 [smbConfigOf]：解析 + 凭据重填 + 校验 + 连接行名字载体 */
    internal fun webDavConfigOf(conn: ConnectionEntity): WebDavConnectionConfig {
        val config = WebDavConnectionConfig.fromJson(conn.configJson)
            ?: throw IllegalArgumentException("WebDAV 连接配置损坏，请重新添加")
        if (config.credentialsNeedReentry) throw IllegalArgumentException(WEBDAV_CREDENTIAL_REENTRY_HINT)
        WebDavConnectionConfig.validate(config)?.let { throw IllegalArgumentException(it) }
        return config.copy(rowDisplayName = conn.displayName)
    }

    /** Komga 同 [smbConfigOf]：解析 + 凭据重填 + 校验 + 连接行名字载体 */
    internal fun komgaConfigOf(conn: ConnectionEntity): KomgaConnectionConfig {
        val config = KomgaConnectionConfig.fromJson(conn.configJson)
            ?: throw IllegalArgumentException("Komga 连接配置损坏，请重新添加")
        if (config.credentialsNeedReentry) throw IllegalArgumentException(KOMGA_CREDENTIAL_REENTRY_HINT)
        KomgaConnectionConfig.validate(config)?.let { throw IllegalArgumentException(it) }
        return config.copy(rowDisplayName = conn.displayName)
    }

    /**
     * 凭据解不出来的统一提示（票 #27）：不崩、不静默连不上，而是告诉用户可以自己修
     * （重新填写密码即重新落密文）；提示里不含任何凭据内容。
     */
    private const val SMB_CREDENTIAL_REENTRY_HINT =
        "SMB 连接的密码已无法解密（密钥失效或换了设备），请在首页点「SMB」进入连接列表，编辑该连接后重新填写密码"
    private const val WEBDAV_CREDENTIAL_REENTRY_HINT =
        "WebDAV 连接的密码已无法解密（密钥失效或换了设备），请在首页点「WebDAV」进入连接列表，编辑该连接后重新填写密码"
    private const val KOMGA_CREDENTIAL_REENTRY_HINT =
        "Komga 连接的凭据已无法解密（密钥失效或换了设备），请在首页点「Komga」进入连接列表，编辑该连接后重新填写凭据"
}
