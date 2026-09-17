package com.cc3301.comicviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.cc3301.comicviewer.core.data.progressByBook
import com.cc3301.comicviewer.core.input.WheelHandler
import com.cc3301.comicviewer.core.input.WheelSurface
import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.nav.LastBrowsing
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.sort.applySortDirection
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.ReadingProgress
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.progressForEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 浏览列表（票 04 + 票 05 进度条）：行内只有封面 + 名称（最多两行）+ 进度条；
 * 不再有「文件夹 / 书 · N 页」副标题（票 #36：类型与页数都不显示，页数也不再在枚举期统计）。
 * 点书进阅读器，点容器逐级下钻。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(nav: NavHostController, connId: Long, containerId: String?, onOpenDrawer: () -> Unit) {
    // 加载重试（票 11 AC4）：网络来源失败后能就地重试，而不是只能退出重进；同时重试来源解析
    var reloadTick by remember { mutableStateOf(0) }

    // 本页来源按自身路由的 connId 解析（票 17 AC2，spec 故事 44）：会话全局来源可能已被别的连接
    // 改写（书柜柜页「打开书」会切会话），跨来源页面若读全局来源，回退回来的浏览页会按别的库渲染。
    // 实例取会话级的那一份（票 #30 P1）：同一连接跨页面复用同一个实例，会话级列表缓存才能
    // 让「进子目录 → 返回上级」命中缓存（旧写法每次进页面新建实例，缓存随实例丢弃）。
    val connections by remember { ServiceLocator.db.connectionDao().observeAll() }
        .collectAsState(initial = emptyList())
    val connection = connections.firstOrNull { it.id == connId }
    // 连接被删除：不在无法解析来源的页面上停留
    LaunchedEffect(connections, connId) {
        if (connections.isNotEmpty() && connection == null) nav.popBackStack()
    }
    var source by remember(connId) { mutableStateOf<Source?>(null) }
    var sourceError by remember(connId) { mutableStateOf<String?>(null) }
    LaunchedEffect(connection?.id, connection?.configJson, reloadTick) {
        val conn = connection ?: return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { ServiceLocator.browsingSourceFor(conn) } }
            .onSuccess {
                sourceError = null
                source = it
            }
            .onFailure { sourceError = it.message ?: "连接配置不可用" }
    }

    // 鼠标滚轮（票 17，spec 故事 22）：列表滚轮交给 LazyColumn 自身滚动。注册声明界面类型，
    // 同时防止上一个界面的处理器（若未被清理）把列表滚轮误当成翻页。
    val wheelHandler = remember { WheelHandler(WheelSurface.LIST) { false } }
    DisposableEffect(wheelHandler) {
        ServiceLocator.wheelHandler = wheelHandler
        onDispose {
            if (ServiceLocator.wheelHandler === wheelHandler) ServiceLocator.wheelHandler = null
        }
    }
    var error by remember { mutableStateOf<String?>(null) }
    // 排序设置（票 #29，spec 故事 10-14）：全 app 一份——读版本号建立重组依赖，柜内或别的连接切换后
    // 本页立即跟随；进子文件夹也保持同一份（不再是「本页位置与落盘记录一致才沿用」）
    val sortRevision = SortSettingStore.revision
    val setting = remember(sortRevision) { SortSettingStore.setting }
    // 上次停留的位置（票 20，故事 48）：只记目录层级，不记排序（排序属全局设置）与滚动位置（SPEC Out of Scope）
    LaunchedEffect(connId, containerId) {
        StartupStore.recordBrowsing(LastBrowsing(connId, containerId))
    }
    // 列表按本页自己的来源取（source 就绪后自动重跑）
    val entries by produceState<List<BrowseEntry>?>(null, source, containerId, setting.mode, reloadTick) {
        val src = source ?: return@produceState
        error = null
        value = try {
            withContext(Dispatchers.IO) { src.listEntries(containerId, setting.mode) }.also { loaded ->
                // 记住条目名（票 13）：Komga 的 id 只有 UUID，标题只能靠列表见过一次
                loaded.forEach { ServiceLocator.entryNames[it.id] = it.name }
            }
        } catch (t: Throwable) {
            error = t.message ?: "加载失败"
            null
        }
    }

    // 方向只在展示层生效（票 #29 裁决 7）：来源接口只收排序方式、返回的恒是正向序，这里整份翻转
    val direction = setting.directionOf()
    val shown = remember(entries, direction) { entries?.applySortDirection(direction) }

    // 进度批量映射（票 05）：bookId → ReadingProgress；Room Flow 跨重启存活；映射下沉后台
    val progressMap by remember {
        ServiceLocator.db.readingProgressDao().readAll()
            .map { list -> progressByBook(list) }
            .flowOn(Dispatchers.Default)
    }.collectAsState(initial = emptyMap())

    // 系统返回手势 = 浏览历史后退（spec 故事 38）：同步维护历史栈
    BackHandler(enabled = ServiceLocator.browseHistory.canGoBack) {
        ServiceLocator.browseHistory.goBack()
        nav.popBackStack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // containerId 在根列表时为 null：ConcurrentHashMap 不接受 null 键（票 13 review P0）
                    val name = containerId?.let { ServiceLocator.entryNames[it] }
                    Text(name ?: displayNameOf(containerId) ?: "浏览")
                },
                navigationIcon = { DrawerMenuButton(onOpenDrawer) },
                actions = {
                    // 列表刷新（票 #30）：文件源列目录有会话级缓存，手动刷新要显式失效再重列，
                    // 否则会从缓存里拿回同一批（含探测失败降级过的）条目
                    IconButton(
                        onClick = {
                            source?.invalidateListCache(containerId)
                            reloadTick++
                        },
                        enabled = source != null,
                    ) { Icon(Icons.Filled.Refresh, contentDescription = "刷新") }
                    // 排序切换（spec 故事 14 + 票 #29）：名称 / 修改时间 / 发布时间三档，点当前档即反向；
                    // 写入的是全局那一份设置，与书柜柜内共用（票 31 决策 2）
                    SortMenuButton(setting) { mode -> SortSettingStore.setting = setting.select(mode) }
                },
            )
        },
    ) { padding ->
        val list = shown
        val src = source
        when {
            // 来源未就绪：解析中 → 加载中；解析失败 → 就地重试（票 11 AC4 同款）
            src == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (sourceError == null) {
                        Text("加载中…")
                    } else {
                        Text("加载失败：" + sourceError)
                        TextButton(onClick = { reloadTick++ }) { Text("重试") }
                    }
                }
            }
            error != null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("加载失败：" + error)
                    Text(
                        loadFailureHint(src.type),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { reloadTick++ }) { Text("重试") }
                }
            }
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("加载中…")
            }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("此目录没有内容")
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(list, key = { it.id }) { entry ->
                    BrowseRow(
                        entry = entry,
                        progress = progressForEntry(entry, progressMap[entry.id]),
                        source = src,
                        // 刷新真刷封面（票 #30 F4）：reloadTick 变→CoverThumb 重取字节，
                        // 封面落盘缓存键含 mtime，文件换过就拿得到新封面
                        coverReloadKey = reloadTick,
                    ) {
                        when {
                            entry.isBook -> {
                                // 阅读器路由只认会话来源（AppNav）：跨来源后（书柜柜页打开过别的库的书）
                                // 会话可能指向别的连接，此处必须对齐到本页的 connId，否则会用别的库的来源
                                // 开本库的书 id、进度也写错库。只在点击路径写全局：组合期写会把回退栈下层带偏（r1 P1）
                                if (ServiceLocator.currentConnId != connId) {
                                    ServiceLocator.currentSource = src
                                    ServiceLocator.currentConnId = connId
                                }
                                // 抽屉「阅读器」入口打开该书（票 09）：带来源连接，跨连接时不误开
                                ServiceLocator.lastRead = LastRead(connId, entry.id)
                                nav.navigate(Routes.reader(entry.id))
                            }
                            else -> {
                                // 子目录入浏览历史（spec 故事 37）
                                ServiceLocator.browseHistory.record(BrowseLocation(connId, entry.id))
                                nav.navigate(Routes.browser(connId, entry.id))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseRow(
    entry: BrowseEntry,
    progress: ReadingProgress?,
    source: Source,
    /** 封面字节的重取键（刷新按钮 +1）：它一变，可见行重取封面（票 #30 F4） */
    coverReloadKey: Any?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 缓存键用 entry.id：无 coverUri 的来源（Komga）若用 coverUri 做键，全列表会共用同一张封面
            CoverThumb(
                coverUri = entry.coverUri,
                cacheKey = entry.id,
                loadBytes = { source.coverBytes(entry.id) },
                reloadKey = coverReloadKey,
            )
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
        }
        // 阅读进度条（票 05）：未读不显示；部分填充绿=进行中；满格红=读完（EntryProgressBar 书柜同款复用）
        progress?.let {
            EntryProgressBar(
                progress = it,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 列表失败提示（票 11；票 31 柜页同款）：本地是授权失效，网络来源是连接/认证问题，措辞不能混用 */
internal fun loadFailureHint(type: SourceType): String = when (type) {
    SourceType.LOCAL -> "授权可能已失效，请重新添加"
    else -> "检查网络或服务器后重试"
}
