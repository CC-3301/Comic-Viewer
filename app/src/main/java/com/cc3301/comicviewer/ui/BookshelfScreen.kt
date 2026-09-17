package com.cc3301.comicviewer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.cc3301.comicviewer.core.data.progressByBook
import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.shelf.CabinetRef
import com.cc3301.comicviewer.core.shelf.groupIntoCabinets
import com.cc3301.comicviewer.core.sort.applySortDirection
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.ReadingProgress
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.progressForEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 书柜柜列表（票 31，spec 故事 43/44）：一条连接一个柜，多连接不混排，点柜才进入该书柜。
 * 柜名取自连接配置（不需要会话），因此离线连接照常列柜；本页不解析来源、不枚举条目。
 * 没有任何连接时才提示去添加来源。柜列表这一层不放排序入口（条目是连接，没有时间/发布日期的意义）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(nav: NavHostController, onOpenDrawer: () -> Unit) {
    val connections by remember { ServiceLocator.db.connectionDao().observeAll() }
        .collectAsState(initial = emptyList())
    // 分柜（spec 故事 44）：条目留给进柜时取，这里只按连接立柜（离线连接也在内）
    val cabinets = remember(connections) {
        groupIntoCabinets(connections.map { CabinetRef(it.id, it.displayName) }, emptyMap())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书柜") },
                navigationIcon = { DrawerMenuButton(onOpenDrawer) },
            )
        },
    ) { padding ->
        if (cabinets.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("还没有连接，先去首页添加来源")
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                cabinets.forEach { cabinet ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            cabinet.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { nav.navigate(Routes.shelf(cabinet.connectionId)) { launchSingleTop = true } }
                                .padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单个柜（票 31，spec 故事 43/45）：该连接的根条目——文件源是「一级文件夹 + 根目录下直接的书/压缩包」，
 * Komga 是系列列表；点容器进浏览列表继续下钻，点书直接进阅读器。
 * 连接被删除即退回柜列表；连接离线/本地授权失效时柜名照常显示，柜内内联「加载失败 + 重试」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CabinetScreen(nav: NavHostController, connId: Long, onOpenDrawer: () -> Unit) {
    // 加载重试（票 31 决策 7）：连接离线/授权失效时就地重试，而不是只能退出重进
    var reloadTick by remember(connId) { mutableStateOf(0) }

    val connections by remember { ServiceLocator.db.connectionDao().observeAll() }
        .collectAsState(initial = emptyList())
    val connection = connections.firstOrNull { it.id == connId }

    LaunchedEffect(connections, connId) {
        if (connections.isNotEmpty() && connection == null) nav.popBackStack()
    }

    // 柜内数据源（票 31 决策 1）：该连接的根条目 = listEntries(null)，不需要新的 Source 方法；
    // 实例取会话级的那一份（票 #30 P1）：柜页与浏览页互切命中同一份会话级列表缓存
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

    // 排序设置（票 #29 裁决 1/4）：柜内与浏览列表读写同一份**全局**设置（排序方式 + 方向），
    // 跨目录层级、跨连接、重启都保持；读版本号建立重组依赖，另一处切换后本页立即跟随
    val sortRevision = SortSettingStore.revision
    val setting = remember(sortRevision) { SortSettingStore.setting }
    var error by remember(connId) { mutableStateOf<String?>(null) }

    // 根条目（票 31 决策 1）：文件源=一级文件夹+根下的书，Komga=系列；排序跟随上面那份设置
    val entries by produceState<List<BrowseEntry>?>(null, source, setting.mode, reloadTick) {
        val src = source ?: return@produceState
        error = null
        value = try {
            withContext(Dispatchers.IO) { src.listEntries(null, setting.mode) }.also { loaded ->
                // 记住条目名（票 13 同款）：Komga 的系列/书 id 只有 UUID，标题只能靠列表见过一次
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

    // 进度批量映射（票 05 同款通路）：柜内书条目的进度与阅读进度实时一致（spec 故事 41/45）
    val progressMap by remember {
        ServiceLocator.db.readingProgressDao().readAll()
            .map { list -> progressByBook(list) }
            .flowOn(Dispatchers.Default)
    }.collectAsState(initial = emptyMap())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(connection?.displayName ?: "书柜") },
                navigationIcon = { DrawerMenuButton(onOpenDrawer) },
                actions = {
                    // 柜内刷新（票 #30）：文件源列目录有会话级缓存，手动刷新要显式失效再重列
                    IconButton(
                        onClick = {
                            source?.invalidateListCache(null)
                            reloadTick++
                        },
                        enabled = source != null,
                    ) { Icon(Icons.Filled.Refresh, contentDescription = "刷新") }
                    // 排序切换（票 31 决策 2 + 票 #29）：只写全局排序设置——柜页不是浏览位置，
                    // 不得改写「上次停留的位置」（故事 48 要求恢复退出时的目录层级）
                    SortMenuButton(setting) { mode -> SortSettingStore.setting = setting.select(mode) }
                },
            )
        },
    ) { padding ->
        val list = shown
        val src = source
        when {
            // 来源未就绪：解析中 → 加载中；解析失败（离线/授权失效）→ 内联失败与重试（票 31 决策 7）
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
                Text("这个书柜还没有内容")
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(list, key = { it.id }) { entry ->
                    CabinetCell(
                        entry = entry,
                        progress = progressForEntry(entry, progressMap[entry.id]),
                        source = src,
                        coverReloadKey = reloadTick,
                        onOpen = {
                            if (entry.isBook) {
                                // 会话来源只在真正打开这本书时切换（spec 故事 44）：柜页是跨来源页面，
                                // 组合期改写全局会话会让回退栈下层的浏览页按别的来源重取（review P1）
                                ServiceLocator.currentSource = src
                                ServiceLocator.currentConnId = connId
                                // Komga 的书名不在 id 里（id 是 UUID），切会话来源又会清空名字缓存（票 13）——
                                // 不补的话阅读器标题会退化成 UUID。用柜内刚列出的名字补上（票 19/31）
                                ServiceLocator.entryNames[entry.id] = entry.name
                                ServiceLocator.lastRead = LastRead(connId, entry.id)
                                nav.navigate(Routes.reader(entry.id))
                            } else {
                                // 容器：进现有浏览列表继续下钻（spec 故事 37 子目录入浏览历史）
                                ServiceLocator.browseHistory.record(BrowseLocation(connId, entry.id))
                                nav.navigate(Routes.browser(connId, entry.id))
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 书柜格子（票 31 决策 3/4）：封面按需 + 名称 + 进度条（非 null 时才是书条目，门控见 progressForEntry） */
@Composable
private fun CabinetCell(
    entry: BrowseEntry,
    progress: ReadingProgress?,
    source: Source,
    /** 封面字节的重取键（刷新按钮 +1）：它一变，可见格重取封面（票 #30 F4） */
    coverReloadKey: Any?,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 封面与浏览列表共用同一条按需通路（票 31 决策 3）：先占位底色，可见行才取字节；
        // 容器的「逐级下取」只在真正需要封面时发生，不挂在枚举/预算路径上。
        // reloadKey 用页面刷新计数（票 #30 F4）：刷新按钮 +1 即重取封面（来源实例现在是跨页面复用的，
        // 不再随解析换身份），封面落盘缓存键含 mtime，文件换过就拿得到新封面
        CoverThumb(
            coverUri = entry.coverUri,
            cacheKey = entry.id,
            loadBytes = { source.coverBytes(entry.id) },
            size = 96.dp,
            reloadKey = coverReloadKey,
        )
        Text(entry.name, style = MaterialTheme.typography.labelLarge, maxLines = 2)
        // 进度条只对书条目显示（票 31 决策 4）：门控在 progressForEntry 里，文件夹与系列拿不到进度
        progress?.let { EntryProgressBar(it) }
    }
}
