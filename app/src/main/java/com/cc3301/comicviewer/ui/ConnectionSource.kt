package com.cc3301.comicviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.source.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一次「按路由 connId 解析连接与会话级来源」的组合期结果（票 25 第 1 项）。
 *
 * [connection] 为 null＝连接还没查到或已被删除；[source] 为 null＝来源还没就绪（解析中或失败），
 * 此时 [error] 非空即解析失败（界面就地「重试」），为空即解析中（界面「加载中…」）。
 */
internal data class ConnectionSource(
    val connection: ConnectionEntity?,
    val source: Source?,
    val error: String?,
)

/**
 * 按路由 connId 解析本页的连接与会话级来源（票 25 第 1 项）：浏览列表与书柜柜内原本各持一份
 * 逐字相同的「连接查询 → [ServiceLocator.browsingSourceFor] → 局部 source/sourceError」，
 * 收成这一份。
 *
 * 页面必须按**自身路由的 connId** 解析（票 17 AC2，spec 故事 44）：会话全局来源可能已被别的连接
 * 改写（柜页「打开书」会切会话），跨来源页面若读全局来源，回退回来的浏览页会按别的库渲染。
 * 实例取会话级的那一份（票 #30 P1）：同一连接跨页面复用同一个实例，会话级列表缓存才能让
 * 「进子目录 → 返回上级」命中缓存（旧写法每次进页面新建实例，缓存随实例丢弃）。
 *
 * [reloadTick] 由调用方持有：它的每次 +1 重跑一次解析（界面的「重试」按钮），页面自己的列表与
 * 封面刷新用的也是同一个键。
 */
@Composable
internal fun rememberConnectionSource(nav: NavHostController, connId: Long, reloadTick: Int): ConnectionSource {
    // 订阅结果用可空集合：**null = 还没加载完**（首帧）、**空列表 = 已加载且一条连接都没有**（票 #40）。
    // 两种空值不能合并——合并后「删掉最后一个连接」会被当成「还没加载完」，浏览页永远不退栈。
    val connections: List<ConnectionEntity>? by remember { ServiceLocator.db.connectionDao().observeAll() }
        .collectAsState(initial = null)
    val connection = connections?.firstOrNull { it.id == connId }
    // 连接被删除：不在无法解析来源的页面上停留
    LaunchedEffect(connections, connId) {
        if (connectionVanished(connections?.map { it.id }, connId)) nav.popBackStack()
    }
    var source by remember(connId) { mutableStateOf<Source?>(null) }
    var sourceError by remember(connId) { mutableStateOf<String?>(null) }
    LaunchedEffect(connection?.id, connection?.configJson, reloadTick) {
        val conn = connection ?: return@LaunchedEffect
        catchingNonCancellation { withContext(Dispatchers.IO) { ServiceLocator.browsingSourceFor(conn) } }
            .onSuccess {
                sourceError = null
                source = it
            }
            .onFailure { sourceError = sourceFailureMessage(it) }
    }
    return ConnectionSource(connection, source, sourceError)
}

/**
 * 进入某连接的**浏览根层**（票 #49）：本地根列表 / 网络连接列表 / 书柜柜列表三个入口共用这一段。
 *
 * 依次是：建/取会话级来源（票 #30 P1）→ 切会话来源 → 导航到浏览根层（[navigateToBrowseLocation]）→
 * 浏览历史与「停留位置 + 路径」落盘对齐（票 #70 r3）。
 *
 * 为什么要走 [navigateToBrowseLocation] 而不是「清历史 + 记一笔 + 压栈」（票 #70 r3）：多级子文件夹里从侧滑菜单
 * 去书柜/首页再回到来源时，旧写法会把新的根层**追加**到已离开的那一段路径之上，每绕一圈多一段，返回无限嵌套；
 * 现在同一层重复进入是回到栈里已有的那一层（**替换**），栈顶不是同一连接的浏览层时先收掉已离开的那一段。
 * 「换了连接要清历史」也被它覆盖：不同连接的根层不可能在栈里，旧的那一段会被一并收掉，镜像随后按栈重建。
 *
 * 三个入口写的是同一段，因此「从哪里点连接」不再影响目的地、会话来源与历史状态；
 * 建会话失败（离线 / 认证失效 / 本地授权失效）不在这里吞：调用方按各自列表页的方式提示
 * （连接列表与书柜都弹 Toast，与改动前连接列表的行为一致）。
 */
internal suspend fun openConnectionRoot(nav: NavHostController, conn: ConnectionEntity) {
    // 建会话在 IO 上做：后端构造会做 SAF provider IPC / SMB 建连与 stat（主线程不能做）
    val source = withContext(Dispatchers.IO) { ServiceLocator.browsingSourceFor(conn) }
    ServiceLocator.currentSource = source
    ServiceLocator.currentConnId = conn.id
    navigateToBrowseLocation(nav, ServiceLocator.browseHistory, BrowseLocation(conn.id, containerId = null))
}

/**
 * 连接是否已被删除（票 25 第 1 项，纯函数，由 [ConnectionSourceTest] 锁定）：连接列表已加载完却查不到
 * [connId] 时，页面再也解析不出来源，应当退栈。
 *
 * 两种空值必须分开（票 #40 修正）：[connectionIds] 为 `null` = **还没加载完**（首帧，`collectAsState` 的初值），
 * 不退；为 `emptyList()` = **已加载且一条连接都没有**（用户把连接删光了），**要退**——
 * 后者不退时，删掉最后一个连接后回退栈里它的浏览页只会停在「加载中…」且彼页没有重试入口，用户卡住。
 */
internal fun connectionVanished(connectionIds: List<Long>?, connId: Long): Boolean =
    connectionIds != null && connId !in connectionIds

/**
 * 来源解析失败的提示（票 25 第 1 项，纯函数，由 [ConnectionSourceTest] 锁定）：
 * 来源构造器抛的已经是中文提示（配置损坏/端口非法/地址不通），直接沿用；只有无消息时才兜底。
 * 两侧原来各写一份同样的兜底串，收在这里以免口径漂移。
 */
internal fun sourceFailureMessage(failure: Throwable): String = failure.message ?: "连接配置不可用"
