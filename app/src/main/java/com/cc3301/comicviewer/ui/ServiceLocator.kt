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
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.fs.SafBackend
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

    val db: AppDatabase by lazy {
        androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, "comic-viewer.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
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
    @Volatile
    var volumeKeyHandler: ((VolumeAction) -> Boolean)? = null

    /**
     * 前台界面的滚轮接入（票 17，spec 故事 22/35）：列表页与阅读页在组合期间注册，离开即清空。
     * 由 MainActivity 的分发入口读取，界面自己不需要感知平台事件。
     */
    @Volatile
    var wheelHandler: WheelHandler? = null

    /**
     * 前进侧键处理器（票 17，spec 故事 37）：由 AppNav 注册，与抽屉「前进」共用同一份实现。
     * 返回 false = 无前进历史（与抽屉按钮禁用态一致）。
     */
    @Volatile
    var forwardHistoryHandler: (() -> Boolean)? = null

    /**
     * 鼠标右键处理器（票 17，spec 故事 36）：阅读页组合期间注册，参数为点击横坐标。
     * 右键与左键等价（都是触摸区域行为），因此只有存在触摸区域的阅读页注册。
     */
    @Volatile
    var mouseSecondaryTapHandler: ((Float) -> Unit)? = null

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
     * 那块守卫见 [releaseLocalSource]（它在别处被替换时由 [currentSource] 的 setter 释放）。
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
        if (replaced != null && replaced !== result) {
            // 守卫依据同步取快照（见 [closeBrowsingSource]）：放进协程里再读会读到后续赋值，同一实例被关两次
            val session = currentSource
            appScope.launch { releaseLocalSource(replaced, session) }
        }
        return result
    }

    /**
     * 会话级浏览来源的唯一释放入口（票 #30 P1）：清槽位同步完成，关不关（以及由谁关）同步定下来。
     *
     * 判定用 [releaseLocalSource]（纯函数，单测锁定）：待释放实例若正是**当时**阅读器在用的会话来源
     * （[currentSource]），就不在这里关——阅读器路由只认它，半途关掉会让回退栈里那本书报错；
     * 关闭责任归会话来源那一侧：[currentSource] 的 setter 在真正替换时释放，[closeSession] 在 App 退出时释放。
     * 其余情况由本入口关一次（槽位已清空，同一实例不会再被本入口取到）。
     *
     * [currentSource] 必须在**这里**同步取快照：否则协程真正执行时读到的是后续赋值，
     * 会变成「浏览槽关一次 + setter 关一次」的重复关闭。
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
        val session = currentSource
        appScope.launch { releaseLocalSource(released, session) }
    }

    /**
     * App 级释放入口（票 #30 P1）：Activity 真正退出时调，把会话级来源都关掉——
     * 浏览槽实例（不属于阅读器时由 [closeBrowsingSource] 关）与阅读器会话来源（由 setter 关），
     * 每个实例只关一次，不留未关闭的会话（列表缓存随 [Source.close] 一并清空）。
     */
    fun closeSession() {
        closeBrowsingSource()
        // 阅读器会话来源交给 setter 释放（与换来源同一条路径）
        currentSource = null
    }

    suspend fun sourceForConnection(conn: ConnectionEntity): Source = when (conn.sourceType) {
        SourceType.LOCAL.name -> DocumentTreeSource(
            backend = SafBackend(context, Uri.parse(conn.configJson)),
            progressStore = RoomProgressStore(db.readingProgressDao()),
            // 封面落盘缓存（票 10「封面生成后缓存」；票 #30 只在按需取封面时才写，枚举期不再写）
            coverCacheDir = context.cacheDir,
        )
        // SMB / WebDAV（票 11/12）：配置损坏或非法时直接抛中文提示，由 UI 展示
        SourceType.SMB.name -> {
            val config = configOfSmb(conn)
            DocumentTreeSource(
                backend = SmbBackend(ClassifyingTransport(SmbjTransport(config), config), config),
                progressStore = RoomProgressStore(db.readingProgressDao()),
                coverCacheDir = context.cacheDir,
                sourceType = SourceType.SMB,
            )
        }
        SourceType.WEBDAV.name -> {
            val config = WebDavConnectionConfig.fromJson(conn.configJson)
                ?: throw IllegalArgumentException("WebDAV 连接配置损坏，请重新添加")
            WebDavConnectionConfig.validate(config)?.let { throw IllegalArgumentException(it) }
            DocumentTreeSource(
                backend = WebDavBackend(ClassifyingWebDavTransport(HttpWebDavTransport(config), config), config),
                progressStore = RoomProgressStore(db.readingProgressDao()),
                coverCacheDir = context.cacheDir,
                sourceType = SourceType.WEBDAV,
            )
        }
        SourceType.KOMGA.name -> {
            val config = KomgaConnectionConfig.fromJson(conn.configJson)
                ?: throw IllegalArgumentException("Komga 连接配置损坏，请重新添加")
            KomgaConnectionConfig.validate(config)?.let { throw IllegalArgumentException(it) }
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

    /** SMB 连接配置解析（损坏/非法时抛中文提示，由 UI 展示） */
    private fun configOfSmb(conn: ConnectionEntity): SmbConnectionConfig {
        val config = SmbConnectionConfig.fromJson(conn.configJson)
            ?: throw IllegalArgumentException("SMB 连接配置损坏，请重新添加")
        SmbConnectionConfig.validate(config)?.let { throw IllegalArgumentException(it) }
        return config
    }
}
