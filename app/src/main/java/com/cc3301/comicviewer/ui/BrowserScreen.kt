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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.cc3301.comicviewer.core.data.BookshelfEntryEntity
import com.cc3301.comicviewer.core.data.progressByBook
import com.cc3301.comicviewer.core.input.WheelHandler
import com.cc3301.comicviewer.core.input.WheelSurface
import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.shelf.supportsBookshelf
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.ReadingProgress
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** 浏览列表（票 04 + 票 05 进度条）：封面缩略图 + 名称 + 类型；点书进阅读器，点容器逐级下钻 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(nav: NavHostController, connId: Long, containerId: String?, onOpenDrawer: () -> Unit) {
    val source = ServiceLocator.currentSource ?: return
    val scope = rememberCoroutineScope()

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
    // 加载重试（票 11 AC4）：网络来源失败后能就地重试，而不是只能退出重进
    var reloadTick by remember { mutableStateOf(0) }
    // 排序方式（spec 故事 14）：三种排序全部来源可用；本票为会话内状态（持久化于票 19 启动页）
    var sort by remember(connId) { mutableStateOf(SortMode.NAME) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    val entries by produceState<List<BrowseEntry>?>(null, containerId, sort, reloadTick) {
        error = null
        value = try {
            withContext(Dispatchers.IO) { source.listEntries(containerId, sort) }.also { loaded ->
                // 记住条目名（票 13）：Komga 的 id 只有 UUID，标题只能靠列表见过一次
                loaded.forEach { ServiceLocator.entryNames[it.id] = it.name }
            }
        } catch (t: Throwable) {
            error = t.message ?: "加载失败"
            null
        }
    }

    // 进度批量映射（票 05）：bookId → ReadingProgress；Room Flow 跨重启存活；映射下沉后台
    val progressMap by remember {
        ServiceLocator.db.readingProgressDao().readAll()
            .map { list -> progressByBook(list) }
            .flowOn(Dispatchers.Default)
    }.collectAsState(initial = emptyMap())

    // 本连接的入柜书 id（票 17）：决定行尾按钮是「加入书柜」还是「移出书柜」
    val shelfBookIds by remember(connId) {
        ServiceLocator.db.bookshelfDao().observeByConnection(connId)
            .map { list -> list.map { it.bookId }.toSet() }
            .flowOn(Dispatchers.Default)
    }.collectAsState(initial = emptySet())
    val shelfActionAvailable = source.type.supportsBookshelf()

    // 加入/移出书柜（票 17 AC1）：名字与封面在入柜时快照，连接离线时书柜照样罗列
    fun toggleShelf(entry: BrowseEntry) {
        scope.launch {
            if (entry.id in shelfBookIds) {
                ServiceLocator.db.bookshelfDao().remove(connId, entry.id)
            } else {
                ServiceLocator.db.bookshelfDao().add(
                    BookshelfEntryEntity(
                        connectionId = connId,
                        bookId = entry.id,
                        name = entry.name,
                        coverUri = entry.coverUri,
                        addedAtMs = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

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
                    // 排序切换（spec 故事 14）：名称 / 修改时间 / 发布时间
                    TextButton(onClick = { sortMenuOpen = true }) {
                        Text(sortLabel(sort))
                    }
                    DropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false },
                    ) {
                        SortMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(sortLabel(mode)) },
                                onClick = {
                                    sort = mode
                                    sortMenuOpen = false
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val list = entries
        when {
            error != null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("加载失败：" + error)
                    Text(
                        loadFailureHint(source.type),
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
                        progress = progressMap[entry.id],
                        source = source,
                        onShelf = entry.id in shelfBookIds,
                        showShelfAction = shelfActionAvailable && entry.isBook,
                        onToggleShelf = { toggleShelf(entry) },
                    ) {
                        when {
                            entry.isBook -> {
                                // 抽屉「阅读器」入口续读（票 09）：带来源连接，跨连接时不误开
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
    onShelf: Boolean,
    showShelfAction: Boolean,
    onToggleShelf: () -> Unit,
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
            )
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                val label = when {
                    entry.isBook && entry.pageCount != null -> "书 · ${entry.pageCount} 页"
                    entry.isBook -> "书"
                    else -> "文件夹"
                }
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            // 入柜开关（票 17 AC1）：仅本票覆盖的文件源（本地/SMB）与书条目显示
            if (showShelfAction) {
                TextButton(onClick = onToggleShelf) {
                    Text(if (onShelf) "移出书柜" else "加入书柜")
                }
            }
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

/** 列表失败提示（票 11）：本地是授权失效，网络来源是连接/认证问题，措辞不能混用 */
private fun loadFailureHint(type: SourceType): String = when (type) {
    SourceType.LOCAL -> "授权可能已失效，请重新添加"
    else -> "检查网络或服务器后重试"
}

/** 排序方式中文标签（spec 故事 14：全部来源支持名称/修改时间/发布时间） */
private fun sortLabel(mode: SortMode): String = when (mode) {
    SortMode.NAME -> "名称"
    SortMode.MODIFIED_TIME -> "修改时间"
    SortMode.RELEASE_TIME -> "发布时间"
}
