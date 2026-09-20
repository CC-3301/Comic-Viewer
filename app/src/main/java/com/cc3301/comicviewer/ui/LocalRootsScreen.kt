package com.cc3301.comicviewer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavHostController
import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.source.SourceType
import kotlinx.coroutines.launch

/** 本地来源：已授权目录列表 + SAF 添加（票 04）+ 删除连接（票 #40） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalRootsScreen(nav: NavHostController, onOpenDrawer: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var roots by remember { mutableStateOf<List<ConnectionEntity>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<ConnectionEntity?>(null) }

    // 已授权目录（本地来源）
    LaunchedEffect(Unit) {
        ServiceLocator.db.connectionDao().observeAll().collect { all ->
            roots = localRoots(all)
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                val name = DocumentFile.fromTreeUri(context, uri)?.name ?: "本地目录"
                ServiceLocator.db.connectionDao().insert(
                    ConnectionEntity(sourceType = SourceType.LOCAL.name, displayName = name, configJson = uri.toString()),
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TopBarTitle("本地") },
                navigationIcon = { DrawerMenuButton(onOpenDrawer) },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { pickFolder.launch(null) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("添加文件夹") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (roots.isEmpty()) {
                Text("尚未添加本地目录，点击右下角授权一个文件夹", style = MaterialTheme.typography.bodyMedium)
            }
            roots.forEach { conn ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 点击挂整行（含上下 12.dp 内边距）：触控目标≈48dp，不低于最小触控尺寸。
                            // 行尾「删除」按钮自己消费点击（Compose 把事件路由给最上层的处理者），点它只弹确认框
                            .clickable {
                                scope.launch {
                                    // 共同入口（票 #49）：与连接列表/书柜写的是同一段（会话来源 + 历史 + 导航）
                                    openConnectionRoot(nav, conn)
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = conn.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { pendingDelete = conn }) { Text("删除") }
                    }
                }
            }
        }
    }

    // 删除不可逆（票 #40）：与网络来源的连接列表同款二次确认
    pendingDelete?.let { conn ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除连接") },
            text = { Text("将删除「" + conn.displayName + "」，阅读进度保留。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch { deleteLocalConnection(conn.id) }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

/**
 * 本地根列表陈列的连接（票 #40）：只取本地来源，网络来源的连接不串进这一页。
 * 连接行被删除后，[com.cc3301.comicviewer.core.data.ConnectionDao.observeAll] 的下一份列表里就没有它了。
 */
internal fun localRoots(connections: List<ConnectionEntity>): List<ConnectionEntity> =
    connections.filter { it.sourceType == SourceType.LOCAL.name }

/**
 * 删除本地连接（票 #40）：与网络来源的连接列表同一套做法（票 #30 P1 的释放纪律）——
 * 先释放该连接的会话级来源（未关闭的会话与陈旧的**内存**列表快照一起清掉）并清掉它名下的
 * **落盘**列表快照（票 #74），再删连接行。
 * 书柜自票 31 起只按连接陈列根条目（`core/shelf` 的 groupIntoCabinets），连接行一删柜位即消失；
 * 「上次停留的位置 / 上次阅读的位置」若指向它，由既有的连接缺失路径退化（AppNav.prepareStartup）。
 *
 * 抽成函数只为让单测打在 App 接线上（[LocalRootsDeleteTest]）：仓库没有 Compose UI 测试，
 * 删除动作若不落在这里就只能在真机上验。
 */
internal suspend fun deleteLocalConnection(connId: Long) {
    ServiceLocator.purgeListingSnapshots(connId)
    ServiceLocator.closeBrowsingSource(connId)
    ServiceLocator.db.connectionDao().deleteById(connId)
}
