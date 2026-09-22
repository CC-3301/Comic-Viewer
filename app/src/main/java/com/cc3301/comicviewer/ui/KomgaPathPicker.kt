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
import com.cc3301.comicviewer.core.source.komga.KomgaPageResult
import com.cc3301.comicviewer.core.source.komga.KomgaSort
import com.cc3301.comicviewer.core.source.komga.KOMGA_MAX_PAGES
import com.cc3301.comicviewer.core.source.komga.KOMGA_PAGE_SIZE

/** 路径选择器的一项（票 #78）：选中后写回的路径串 + 列表里显示的名字 */
data class PathPickerItem(val path: String, val label: String)

/**
 * 路径选择器的一页（票 #119 步骤 2）：[hasMore] = 后面还有没取回的候选（滚到底再取下一页）。
 * 首屏只取第 0 页，因此大库（8600 本）打开选择器不再一次拉全量。
 */
data class PathPickerPage(val items: List<PathPickerItem>, val hasMore: Boolean)

/**
 * 只读「路径」字段（票 #78）的选择器数据来源。表单渲染只认这几个方法，不依赖具体来源的
 * HTTP/分页细节；[close] 由调用点在弹窗关闭时释放（持有网络会话）。
 *
 * 分页（票 #119 步骤 2）：[children] 收 `page`（从 0 起）、返回 [PathPickerPage]；
 * 界面只在**滚到底**时取下一页，不预取全部。
 *
 * 生产实现：[KomgaPathPicker]（票 #78 只有 Komga 有路径字段）。
 */
interface PathPicker : AutoCloseable {
    /** 根路径（票 #78）：默认 `/`，「回根」按钮落到它 */
    val rootPath: String

    /** [path] 下第 [page] 页（从 0 起）的可选项；叶层（书籍 / 阅读过 / 某系列）返回空页 */
    suspend fun children(path: String, page: Int): PathPickerPage

    /** 上一级路径（票 #78 的「上箭头」）；根路径返回自身 */
    fun parent(path: String): String

    override fun close() {}
}

/**
 * 从 [fromPage] 起取，直到拿到**有可见条目**的一页、或服务器说没有下一页为止（票 #119 修复轮）。
 * 返回「这一页 + 下一页号」。
 *
 * 为什么必须跳过空页：收藏内容里的书会被选择器过滤掉（书不是可下钻的层，见 [KomgaPathPicker]）。
 * 若整页都是书，这一页渲染出来是 0 条——界面侧「列表长度没变」就不会再触发下一页，
 * 选择器会停在「加载中…」，后面页里的系列永远取不到（改动前的全量实现不会）。
 *
 * **跳空页有页数上限**（票 #125 P1-2）：这个循环的退出条件里「空页」是常态（收藏里书被过滤、
 * 服务器又说还有下一页），只以 `!hasMore` 退出时就是无限取数（服务器分页字段异常 / `hasNext` 恒真）。
 * 上限与 [komgaLoadAll] 同一个 [KOMGA_MAX_PAGES]：跳满上限仍没有可见条目就**当终止**返回空页
 * （`hasMore = false`），不再往后取。
 */
internal suspend fun PathPicker.pageWithVisibleItems(path: String, fromPage: Int): Pair<PathPickerPage, Int> {
    var page = fromPage
    var tried = 0
    while (tried < KOMGA_MAX_PAGES) {
        val result = children(path, page)
        tried++
        if (result.items.isNotEmpty() || !result.hasMore) return result to (page + 1)
        page++
    }
    return PathPickerPage(items = emptyList(), hasMore = false) to page
}

/**
 * Komga 路径选择器的数据来源（票 #78）：只用 [KomgaApi] 的「收藏 / 系列」读取，
 * 不复用浏览分页与进度逻辑（选择器只展示名称与写回路径）。
 *
 * 票 #119 步骤 2：不再用 `komgaLoadAll` 把整层拉完——每次只取服务器端一页（[KOMGA_PAGE_SIZE]），
 * 滚到底再由界面取下一页（与浏览侧同一套服务端 `page`/`size` 与排序口径）。
 */
internal class KomgaPathPicker(private val api: KomgaApi) : PathPicker {

    override val rootPath: String = KomgaBrowsePaths.ROOT

    override suspend fun children(path: String, page: Int): PathPickerPage =
        when (val level = KomgaBrowsePaths.parse(path)) {
            // 根层四项是本地常量，没有服务端分页一说（一页就给完）
            KomgaBrowsePath.Root -> PathPickerPage(
                items = KomgaCategory.entries.map { PathPickerItem(it.path, it.label) },
                hasMore = false,
            )
            KomgaBrowsePath.Collections -> api
                .listCollections(page, KOMGA_PAGE_SIZE, KomgaSort.FOR_COLLECTION_NAMES)
                .toPage { PathPickerItem(collectionPath(it.id), it.name) }
            is KomgaBrowsePath.Collection -> api
                .collectionContent(level.collectionId, page, KOMGA_PAGE_SIZE, KomgaSort.FOR_SERIES_NAMES)
                // 选择器只能选**层**（系列）；服务端若在收藏里返回书，书不是可下钻的层，跳过
                .toPage { (it as? KomgaCollectionItem.Series)?.let { s -> PathPickerItem(seriesPath(s.series.id), s.series.title) } }
            KomgaBrowsePath.Series -> api
                .listSeries(page, KOMGA_PAGE_SIZE, KomgaSort.FOR_SERIES_NAMES)
                .toPage { PathPickerItem(seriesPath(it.id), it.title) }
            // 叶层：书籍 / 阅读过 / 某系列的书，没有可继续深入的候选
            KomgaBrowsePath.Books, KomgaBrowsePath.Read, is KomgaBrowsePath.SeriesBooks ->
                PathPickerPage(items = emptyList(), hasMore = false)
        }

    /** 服务端一页 → 选择器一页（票 #119 步骤 2）：[transform] 返回 null 的条目跳过（书不是可下钻的层） */
    private fun <T> KomgaPageResult<T>.toPage(transform: (T) -> PathPickerItem?): PathPickerPage =
        PathPickerPage(items = items.mapNotNull(transform), hasMore = hasNext)

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
    var hasMore by remember(picker) { mutableStateOf(false) }
    var nextPage by remember(picker) { mutableStateOf(0) }
    var error by remember(picker) { mutableStateOf<String?>(null) }

    // 弹窗关闭即释放选择器持有的会话（HTTP 连接池）
    DisposableEffect(picker) { onDispose { picker.close() } }

    // 首屏只取第 0 页（票 #119 步骤 2）：大库打开选择器不再先拉全量（8600 本 ≈ 8–10 MB）；
    // 整页都是被过滤掉的书时自动往后跳（票 #119 修复轮，见 [pageWithVisibleItems]）
    LaunchedEffect(picker, path) {
        candidates = null
        hasMore = false
        nextPage = 0
        error = null
        catchingNonCancellation { picker.pageWithVisibleItems(path, fromPage = 0) }
            .onSuccess { (page, next) ->
                candidates = page.items
                hasMore = page.hasMore
                nextPage = next
            }
            .onFailure { error = it.message ?: "读取失败" }
    }

    // 滚到底（尾部小件进入组合）再取下一页；已取到的候选先上屏，不因后续页失败而清空
    suspend fun loadNextPage() {
        val loaded = candidates ?: return
        catchingNonCancellation { picker.pageWithVisibleItems(path, fromPage = nextPage) }
            .onSuccess { (page, next) ->
                candidates = loaded + page.items
                hasMore = page.hasMore
                nextPage = next
            }
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
                    candidates == null && error != null -> Text(
                        error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    candidates == null -> Text("加载中…", style = MaterialTheme.typography.bodyMedium)
                    // 空且没有下一页才是真「没有可选项」；空但还有下一页时仍要渲染列表（含尾部触发件），
                    // 否则后续页的候选永远取不到（票 #119 修复轮）
                    candidates!!.isEmpty() && !hasMore -> Text("没有可选项", style = MaterialTheme.typography.bodyMedium)
                    else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // 后续页加载失败：提示留在列表上方，已取到的候选不清空（票 #119 步骤 2）
                        error?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
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
                            if (hasMore) {
                                // 尾部小件：滚到底（进入组合）就取下一页。它随新条目被顶出可视区后
                                // 会离开组合，因此“再滚到底”才会再触发一次，不会一次性把所有页拉完。
                                item(key = "path-picker-more") {
                                    LaunchedEffect(Unit) { loadNextPage() }
                                    Text(
                                        "加载中…",
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(vertical = 10.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(path) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
