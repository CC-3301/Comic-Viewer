package com.cc3301.comicviewer.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.cc3301.comicviewer.core.data.BookshelfEntryEntity
import com.cc3301.comicviewer.core.data.progressByBook
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.shelf.CabinetRef
import com.cc3301.comicviewer.core.shelf.groupIntoCabinets
import com.cc3301.comicviewer.core.source.ReadingProgress
import com.cc3301.comicviewer.core.source.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书柜柜列表（票 17，spec 故事 43/44）：按来源分柜展示，多来源不混排，点柜才进入该书柜。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(nav: NavHostController, onOpenDrawer: () -> Unit) {
    val connections by remember { ServiceLocator.db.connectionDao().observeAll() }
        .collectAsState(initial = emptyList())
    val entries by remember { ServiceLocator.db.bookshelfDao().observeAll() }
        .collectAsState(initial = emptyList())
    // 分柜（spec 故事 44）：条目按连接归组，连接已删除的孤儿条目直接跳过
    val cabinets = remember(connections, entries) {
        groupIntoCabinets(connections.map { CabinetRef(it.id, it.displayName) }, entries)
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("书柜还没有内容")
                    Text(
                        "在浏览列表里用「加入书柜」把书放进来",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { nav.navigate(Routes.shelf(cabinet.connectionId)) { launchSingleTop = true } }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(cabinet.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${cabinet.entries.size} 本",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单个柜（票 17，spec 故事 45）：封面墙 + 与浏览列表同款的进度条。
 * 连接被删除即退回柜列表；连接离线时条目照样罗列，封面退化为占位底色。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CabinetScreen(nav: NavHostController, connId: Long, onOpenDrawer: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connections by remember { ServiceLocator.db.connectionDao().observeAll() }
        .collectAsState(initial = emptyList())
    val connection = connections.firstOrNull { it.id == connId }

    LaunchedEffect(connections, connId) {
        if (connections.isNotEmpty() && connection == null) nav.popBackStack()
    }

    // 封面字节与打开书都需要活的来源会话（SMB）；建不起来时只影响封面与打开，不影响罗列
    var source by remember(connId) { mutableStateOf<Source?>(null) }
    var sourceError by remember(connId) { mutableStateOf<String?>(null) }
    LaunchedEffect(connection?.id, connection?.configJson) {
        val conn = connection ?: return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { ServiceLocator.sourceForConnection(conn) } }
            .onSuccess {
                sourceError = null
                source = it
            }
            .onFailure { sourceError = it.message ?: "连接配置不可用" }
    }

    val entries by remember(connId) {
        ServiceLocator.db.bookshelfDao().observeByConnection(connId)
    }.collectAsState(initial = emptyList())

    // 进度批量映射（票 05 同款通路）：书柜进度与阅读进度实时一致（spec 故事 41/45）
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
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("这个书柜还没有书")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(entries, key = { it.bookId }) { entry ->
                    ShelfCell(
                        entry = entry,
                        progress = progressMap[entry.bookId],
                        source = source,
                        onOpen = {
                            val openSource = source
                            if (openSource == null) {
                                Toast.makeText(context, sourceError ?: "连接不可用，无法打开", Toast.LENGTH_SHORT).show()
                            } else {
                                // 会话来源只在真正打开这本书时切换（spec 故事 44）：柜页是跨来源页面，
                                // 组合期改写全局会话会让回退栈下层的浏览页按别的来源重取（review P1）
                                ServiceLocator.currentSource = openSource
                                ServiceLocator.currentConnId = connId
                                ServiceLocator.lastRead = LastRead(connId, entry.bookId)
                                nav.navigate(Routes.reader(entry.bookId))
                            }
                        },
                        onRemove = {
                            scope.launch { ServiceLocator.db.bookshelfDao().remove(connId, entry.bookId) }
                        },
                    )
                }
            }
        }
    }
}

/** 书柜格子：封面 + 书名 + 进度条 + 移出（票 17 AC1 的移除入口） */
@Composable
private fun ShelfCell(
    entry: BookshelfEntryEntity,
    progress: ReadingProgress?,
    source: Source?,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 封面规则与浏览列表共用同一条通路（票 17 AC3）：coverUri 优先，SMB 回退来源字节
        CoverThumb(
            coverUri = entry.coverUri,
            cacheKey = entry.bookId,
            loadBytes = { source?.coverBytes(entry.bookId) },
            size = 96.dp,
            reloadKey = source,
        )
        Text(entry.name, style = MaterialTheme.typography.labelLarge, maxLines = 2)
        // 进度条与浏览列表同款（spec 故事 45）：部分填充绿=进行中，满格红=读完
        progress?.let { EntryProgressBar(it) }
        TextButton(onClick = onRemove) { Text("移出书柜") }
    }
}
