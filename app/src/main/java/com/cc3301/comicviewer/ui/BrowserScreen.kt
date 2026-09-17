package com.cc3301.comicviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.navigation.NavHostController
import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.ReadingProgress
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** 浏览列表（票 04 + 票 05 进度条）：封面缩略图 + 名称 + 类型；点书进阅读器，点容器逐级下钻 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(nav: NavHostController, connId: Long, containerId: String?, onOpenDrawer: () -> Unit) {
    val source = ServiceLocator.currentSource ?: return
    var error by remember { mutableStateOf<String?>(null) }
    // 加载重试（票 11 AC4）：网络来源失败后能就地重试，而不是只能退出重进
    var reloadTick by remember { mutableStateOf(0) }
    // 排序方式（spec 故事 14）：三种排序全部来源可用；本票为会话内状态（持久化于票 19 启动页）
    var sort by remember(connId) { mutableStateOf(SortMode.NAME) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    val entries by produceState<List<BrowseEntry>?>(null, containerId, sort, reloadTick) {
        error = null
        value = try {
            withContext(Dispatchers.IO) { source.listEntries(containerId, sort) }
        } catch (t: Throwable) {
            error = t.message ?: "加载失败"
            null
        }
    }

    // 进度批量映射（票 05）：bookId → ReadingProgress；Room Flow 跨重启存活；映射下沉后台
    val progressMap by remember {
        ServiceLocator.db.readingProgressDao().readAll()
            .map { list -> list.associate { it.bookId to ReadingProgress(it.pageIndex, it.totalPages, it.updatedAtMs) } }
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
                title = { Text(displayNameOf(containerId) ?: "浏览") },
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
                    BrowseRow(entry, progressMap[entry.id], source) {
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
private fun BrowseRow(entry: BrowseEntry, progress: ReadingProgress?, source: Source, onClick: () -> Unit) {
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
            Thumb(coverUri = entry.coverUri, loadBytes = { source.coverBytes(entry.id) })
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                val label = when {
                    entry.isBook && entry.pageCount != null -> "书 · ${entry.pageCount} 页"
                    entry.isBook -> "书"
                    else -> "文件夹"
                }
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
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

/** 封面缩略图（128px 子采样）：优先系统可解码 uri，SMB 等来源解不出时回退来源字节（票 11） */
@Composable
private fun Thumb(coverUri: String?, loadBytes: suspend () -> ByteArray?) {
    val context = LocalContext.current
    var bitmap by remember(coverUri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(coverUri) {
        bitmap = withContext(Dispatchers.IO) {
            val fromUri = coverUri
                ?.takeIf { it.isNotEmpty() }
                // SMB 的标识串（smb://…）系统解不了：直接走来源字节，不白跑一次 ContentResolver
                ?.takeIf { it.startsWith("content://") || it.startsWith("file://") }
                ?.let { PageDecoder.decodeUri(context, it, 128) }
            fromUri ?: loadBytes()?.let { bytes ->
                PageDecoder.decodeBytes("cover@" + coverUri + "@128", bytes, 128)
            }
        }
    }
    Box(
        modifier = Modifier
            .size(56.dp)
            .background(Color.DarkGray),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(it, contentDescription = null, modifier = Modifier.size(56.dp), contentScale = ContentScale.Crop)
        }
    }
}

/** 排序方式中文标签（spec 故事 14：全部来源支持名称/修改时间/发布时间） */
private fun sortLabel(mode: SortMode): String = when (mode) {
    SortMode.NAME -> "名称"
    SortMode.MODIFIED_TIME -> "修改时间"
    SortMode.RELEASE_TIME -> "发布时间"
}
