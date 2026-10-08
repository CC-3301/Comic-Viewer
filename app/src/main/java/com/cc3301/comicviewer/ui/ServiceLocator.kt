package com.cc3301.comicviewer.ui

import android.content.Context
import android.net.Uri
import com.cc3301.comicviewer.core.data.AppDatabase
import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.data.RoomProgressStore
import com.cc3301.comicviewer.core.input.WheelHandler
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.reader.VolumeAction
import com.cc3301.comicviewer.core.source.DiagnosticsLog
import com.cc3301.comicviewer.core.source.ListingSnapshotStore
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceAssembly
import com.cc3301.comicviewer.core.source.SourceDeps
import com.cc3301.comicviewer.core.source.fs.SafBackend
import com.cc3301.comicviewer.core.source.listingSnapshotDir
import com.cc3301.comicviewer.ui.session.ConnectionStore
import com.cc3301.comicviewer.ui.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * 极简依赖定位（绿地阶段；后续按需演进）：**窄根**——环境（Context / APP 级协程域 / 库）与本模块实例的装配处。
 * 不持有会话状态：那一份由组合根 `MainActivity` 持有并沿组合树提供（[newSessionState] 只交依赖）。
 */
object ServiceLocator {

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        // 应用内「诊断日志」开关是持久化的，启动时注入运行期值（打点侧 core 不读设置）
        DiagnosticsLog.enabled = AppSettings.diagnosticsEnabled
    }

    internal val context: Context get() = appContext ?: throw IllegalStateException("ServiceLocator 未初始化")

    /** APP 级协程域：退出回调等长于组合生命周期的写入 */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 生产那一份会话状态的装配（见 [SessionState]）：窄根只交依赖，实例由组合根持有。
     */
    internal fun newSessionState(): SessionState = SessionState(
        scope = appScope,
        sourceFactory = { sourceForConnection(it) },
        // 未初始化（纯 JVM 单测）时不落盘：与 [listingSnapshotDirOrNull] 同一口径——
        // 落盘不是这些路径的必需环节，不能因此抛出。
        recordBrowsingPath = { if (appContext != null) StartupStore.recordBrowsingPath(it) },
    )

    /**
     * 生产那一份连接写面的装配（见 `ui/session/ConnectionStore`）：窄根只交依赖，实例由调用方持有。
     */
    internal fun newConnectionStore(session: SessionState): ConnectionStore =
        ConnectionStore(db.connectionDao(), session)

    /**
     * 打开书的前置槽：浏览页点击时写入、阅读页组合期同步取走。
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

    /** 上次阅读的位置（带来源；抽屉「阅读器」入口打开该书）。
     * 写入即落盘（spec 故事 47），启动时才判得出「上次退出时正在看书」该打开哪一本。
     *
     * 唯一的**写入点**是 `ui.applyReaderEntry`：阅读页**真正切进这本书**那一刻写（与阅读进度同一时点）。
     * 点击浏览页的书不再写（点了又取消 / 被后一次点击顶替都不该改它），读内换书也不写（由新的阅读页 entry 写）；
     * `AppNav` 启动还原那一处是例外且无害：它把**刚读出的落盘值**回填会话态，写入值恒等于已落盘值。
     */
    @Volatile
    var lastRead: LastRead? = null
        set(value) {
            field = value
            StartupStore.recordLastRead(value)
        }

    /** 阅读器音量键处理器：阅读页在组合期间注册，返回 true = 已消费 */
    val volumeKeySlot = HandlerSlot<(VolumeAction) -> Boolean>()

    /**
     * 前台界面的滚轮接入（spec 故事 22/35）：列表页与阅读页在组合期间注册，离开即清空。
     * 由 MainActivity 的分发入口读取，界面自己不需要感知平台事件。
     */
    val wheelSlot = HandlerSlot<WheelHandler>()

    /**
     * 前进侧键处理器（spec 故事 37）：由 AppNav 注册；抽屉不再有前进入口，前进只由它触发。
     * 返回 false = 无前进历史（消费不了就交回系统）。
     */
    val forwardHistorySlot = HandlerSlot<() -> Boolean>()

    /**
     * 鼠标右键处理器（spec 故事 36）：阅读页组合期间注册，参数为点击横坐标。
     * 右键与左键等价（都是触摸区域行为），因此只有存在触摸区域的阅读页注册。
     */
    val mouseSecondaryTapSlot = HandlerSlot<(Float) -> Unit>()

    /**
     * 清某连接名下的落盘列表快照：由连接写面（`ui/session/ConnectionStore`）在编辑/删除连接时调。
     * 与 [SessionState.closeBrowsingSource] 的槽位释放是两个关注点：App 退出也走槽位释放，但**不清落盘**。
     */
    fun purgeListingSnapshots(connId: Long) {
        listingSnapshotDirOrNull()?.let { ListingSnapshotStore.clearConnection(it, connId) }
    }

    suspend fun sourceForConnection(conn: ConnectionEntity): Source = SourceAssembly.build(conn, sourceDeps)

    /**
     * 装配一条连接所需的外部依赖：装配模块（[SourceAssembly]）在 `core/source`，
     * 不认 Context / Room / 缓存目录，由这一处把 App 侧那几样交过去。
     * 四样都是取值闭包——装配路径只用到其中一两样，提前求值会让「未知来源类型」那条出路也去碰 Context
     *（原先不碰：那种行在 when 的 else 分支直接抛）。
     */
    private val sourceDeps = SourceDeps(
        progressStore = { RoomProgressStore(db.readingProgressDao()) },
        coverCacheDir = { context.cacheDir },
        listingSnapshots = { connId -> listingSnapshotStoreFor(connId) },
        safBackend = { uri -> SafBackend(context, Uri.parse(uri)) },
    )

    /**
     * 落盘列表快照的根目录：APP 私有 cacheDir 下；ServiceLocator 未初始化（单测直接调
     * [purgeListingSnapshots]）时为 null——落盘不是这些路径的必需环节，不能因此抛出。
     */
    private fun listingSnapshotDirOrNull(): File? = appContext?.cacheDir?.let(::listingSnapshotDir)

    /** 某连接名下的落盘快照表：枚举时读写，键 = 连接 id + 容器 id */
    private fun listingSnapshotStoreFor(connId: Long): ListingSnapshotStore? =
        listingSnapshotDirOrNull()?.let { ListingSnapshotStore(it, connId) }
}
