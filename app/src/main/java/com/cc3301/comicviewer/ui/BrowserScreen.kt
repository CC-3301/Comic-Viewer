package com.cc3301.comicviewer.ui

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.SortMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 浏览列表（票 04）：封面缩略图 + 名称 + 类型；点书进阅读器，点容器逐级下钻 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(nav: NavHostController, connId: Long, containerId: String?) {
    val source = ServiceLocator.currentSource ?: return
    var error by remember { mutableStateOf<String?>(null) }
    val entries by produceState<List<BrowseEntry>?>(null, containerId) {
        value = try {
            withContext(Dispatchers.IO) { source.listEntries(containerId, SortMode.NAME) }
        } catch (t: Throwable) {
            error = t.message ?: "加载失败"
            null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(displayNameOf(containerId) ?: "浏览") })
        },
    ) { padding ->
        val list = entries
        when {
            error != null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("加载失败：$error（授权可能已失效，请重新添加）")
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
                    BrowseRow(entry) {
                        when {
                            entry.isBook -> nav.navigate(Routes.reader(entry.id))
                            else -> nav.navigate(Routes.browser(connId, entry.id))
                        }
                    }
                }
            }
        }
    }
}

private fun displayNameOf(containerId: String?): String? {
    if (containerId == null) return null
    val docId = android.provider.DocumentsContract.getDocumentId(android.net.Uri.parse(containerId))
    return docId?.substringAfterLast('/')
}

@Composable
private fun BrowseRow(entry: BrowseEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Thumb(uri = entry.coverUri)
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
}

/** 封面缩略图（128px 子采样） */
@Composable
private fun Thumb(uri: String?) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            uri?.let { PageDecoder.decodeUri(context, it, 128) }
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
