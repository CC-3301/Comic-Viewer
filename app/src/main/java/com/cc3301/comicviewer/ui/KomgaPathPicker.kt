package com.cc3301.comicviewer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.source.komga.KomgaApi
import com.cc3301.comicviewer.core.source.komga.KomgaBrowsePath
import com.cc3301.comicviewer.core.source.komga.KomgaBrowsePaths
import com.cc3301.comicviewer.core.source.komga.KomgaCategory
import com.cc3301.comicviewer.core.source.komga.KomgaCollectionItem
import com.cc3301.comicviewer.core.source.komga.KomgaSort
import com.cc3301.comicviewer.core.source.komga.KOMGA_PAGE_SIZE
import com.cc3301.comicviewer.core.source.komga.komgaLoadAll

/** 路径选择器的一项（票 #78）：选中后写回的路径串 + 列表里显示的名字 */
data class PathPickerItem(val path: String, val label: String)

/**
 * 只读「路径」字段（票 #78）的选择器数据来源。表单渲染只认这三个方法，不依赖具体来源的
 * HTTP/分页细节；[close] 由调用点在弹窗关闭时释放（持有网络会话）。
 *
 * 生产实现：[KomgaPathPicker]（票 #78 只有 Komga 有路径字段）。
 */
interface PathPicker : AutoCloseable {
    /** 根路径（票 #78）：默认 `/`，「回根」按钮落到它 */
    val rootPath: String

    /** [path] 下的可选项；叶层（书籍 / 阅读过 / 某系列）返回空表 */
    suspend fun children(path: String): List<PathPickerItem>

    /** 上一级路径（票 #78 的「上箭头」）；根路径返回自身 */
    fun parent(path: String): String

    override fun close() {}
}

/**
 * Komga 路径选择器的数据来源（票 #78）：只用 [KomgaApi] 的「收藏 / 系列」读取，
 * 不复用浏览分页与进度逻辑（选择器只展示名称与写回路径）。
 */
internal class KomgaPathPicker(private val api: KomgaApi) : PathPicker {

    override val rootPath: String = KomgaBrowsePaths.ROOT

    override suspend fun children(path: String): List<PathPickerItem> =
        when (val level = KomgaBrowsePaths.parse(path)) {
            KomgaBrowsePath.Root -> KomgaCategory.entries.map { PathPickerItem(it.path, it.label) }
            KomgaBrowsePath.Collections -> komgaLoadAll {
                api.listCollections(it, KOMGA_PAGE_SIZE, KomgaSort.FOR_COLLECTION_NAMES)
            }.map { PathPickerItem(collectionPath(it.id), it.name) }
            is KomgaBrowsePath.Collection -> komgaLoadAll {
                api.collectionContent(level.collectionId, it, KOMGA_PAGE_SIZE, KomgaSort.FOR_SERIES_NAMES)
            }
                // 选择器只能选**层**（系列）；服务端若在收藏里返回书，书不是可下钻的层，跳过
                .mapNotNull { (it as? KomgaCollectionItem.Series)?.series }
                .map { PathPickerItem(seriesPath(it.id), it.title) }
            KomgaBrowsePath.Series -> komgaLoadAll {
                api.listSeries(it, KOMGA_PAGE_SIZE, KomgaSort.FOR_SERIES_NAMES)
            }.map { PathPickerItem(seriesPath(it.id), it.title) }
            // 叶层：书籍 / 阅读过 / 某系列的书，没有可继续深入的候选
            KomgaBrowsePath.Books, KomgaBrowsePath.Read, is KomgaBrowsePath.SeriesBooks -> emptyList()
        }

    override fun parent(path: String): String =
        KomgaBrowsePaths.format(KomgaBrowsePaths.parent(KomgaBrowsePaths.parse(path)))

    override fun close() {
        api.close()
    }

    private fun collectionPath(collectionId: String): String =
        KomgaBrowsePaths.format(KomgaBrowsePath.Collection(collectionId))

    private fun seriesPath(seriesId: String): String =
        KomgaBrowsePaths.format(KomgaBrowsePath.SeriesBooks(seriesId))
}

/**
 * 路径选择器弹窗（票 #78，CDisplayEx 式）：顶部当前路径 + 回根 + 上一级 + 候选列表。
 * 点候选即进入该层（继续往下看），按「保存」把**当前路径**写回表单字段；「取消」不写。
 *
 * 候选读取在协程里做（网络），失败就地给中文提示；列表滚动限高（长收藏/系列列表不把弹窗撑出屏）。
 */
@Composable
internal fun PathPickerDialog(
    picker: PathPicker,
    initialPath: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val start = initialPath.takeIf { it.isNotBlank() } ?: picker.rootPath
    var path by remember(picker) { mutableStateOf(start) }
    var candidates by remember(picker) { mutableStateOf<List<PathPickerItem>?>(null) }
    var error by remember(picker) { mutableStateOf<String?>(null) }

    // 弹窗关闭即释放选择器持有的会话（HTTP 连接池）
    DisposableEffect(picker) { onDispose { picker.close() } }

    LaunchedEffect(picker, path) {
        candidates = null
        error = null
        catchingNonCancellation { picker.children(path) }
            .onSuccess { candidates = it }
            .onFailure { error = it.message ?: "读取失败" }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择路径") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { path = picker.rootPath }) {
                        Icon(Icons.Filled.Home, contentDescription = "回到根")
                    }
                    IconButton(onClick = { path = picker.parent(path) }) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上一级")
                    }
                    Text(path, style = MaterialTheme.typography.bodyMedium)
                }
                when {
                    error != null -> Text(
                        error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    candidates == null -> Text("加载中…", style = MaterialTheme.typography.bodyMedium)
                    candidates!!.isEmpty() -> Text("没有可选项", style = MaterialTheme.typography.bodyMedium)
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(candidates!!, key = { it.path }) { item ->
                            Text(
                                item.label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { path = item.path }
                                    .padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(path) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
