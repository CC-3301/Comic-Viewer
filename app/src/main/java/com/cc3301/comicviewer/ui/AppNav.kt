package com.cc3301.comicviewer.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cc3301.comicviewer.R
import com.cc3301.comicviewer.core.source.SourceType
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val LOCAL_ROOTS = "localRoots"
    const val SETTINGS = "settings"

    /** 书柜（票 09 占位；票 17/18 实现） */
    const val BOOKSHELF = "bookshelf"
    const val BROWSER = "browser/{connId}?container={container}"
    const val READER = "reader/{bookId}"

    fun browser(connId: Long, containerId: String?): String =
        "browser/$connId?container=${android.net.Uri.encode(containerId ?: "")}"

    fun reader(bookId: String): String =
        "reader/${android.net.Uri.encode(bookId)}"
}

/** 导航壳（票 04）：首页 → 本地根列表 → 浏览 → 条漫阅读器 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav() {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route
    val history = ServiceLocator.browseHistory

    // 抽屉开合时重新求值历史可用性（isOpen 是 snapshot state，避免抽屉内显示陈旧状态）
    val drawerOpen = drawerState.isOpen
    val canGoBack = remember(drawerOpen) { history.canGoBack }
    val canGoForward = remember(drawerOpen) { history.canGoForward }

    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    fun openDrawer() {
        scope.launch { drawerState.open() }
    }

    AppDrawer(
        drawerState = drawerState,
        currentRoute = currentRoute,
        // 阅读器内禁用边缘手势：左缘滑动留给系统返回手势（spec 故事 38）
        gesturesEnabled = currentRoute != Routes.READER,
        canGoBack = canGoBack,
        canGoForward = canGoForward,
        onOpenReader = {
            closeDrawer()
            val bookId = ServiceLocator.lastReadBookId
            if (ServiceLocator.currentSource == null || bookId == null) {
                Toast.makeText(context, "还没有阅读记录", Toast.LENGTH_SHORT).show()
            } else {
                nav.navigate(Routes.reader(bookId))
            }
        },
        onOpenBookshelf = {
            closeDrawer()
            nav.navigate(Routes.BOOKSHELF)
        },
        onOpenSettings = {
            closeDrawer()
            nav.navigate(Routes.SETTINGS)
        },
        onBackHistory = {
            closeDrawer()
            history.goBack()?.let { nav.navigate(Routes.browser(it.connId, it.containerId)) }
        },
        onForwardHistory = {
            closeDrawer()
            history.goForward()?.let { nav.navigate(Routes.browser(it.connId, it.containerId)) }
        },
    ) {
        NavHost(navController = nav, startDestination = Routes.HOME) {
            composable(Routes.HOME) { HomeScreen(nav, ::openDrawer) }
            composable(Routes.LOCAL_ROOTS) { LocalRootsScreen(nav, ::openDrawer) }
            composable(Routes.SETTINGS) { SettingsScreen(::openDrawer) }
            composable(Routes.BOOKSHELF) { BookshelfPlaceholder(::openDrawer) }
            composable(Routes.BROWSER) { entry ->
                val connId = entry.arguments?.getString("connId")?.toLongOrNull()
                val container = entry.arguments?.getString("container")?.takeIf { it.isNotEmpty() }
                if (connId == null) {
                    // 组合期不可直接导航：包装进 LaunchedEffect（review P2）
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    BrowserScreen(nav, connId, container, ::openDrawer)
                }
            }
            composable(Routes.READER) { entry ->
                // navigation 已自动解码参数，不再手动 Uri.decode（review P1：双重解码损坏含 % 的 id）
                val bookId = entry.arguments?.getString("bookId")
                val source = ServiceLocator.currentSource
                if (bookId == null || source == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    ReaderScreen(
                        bookId = bookId,
                        source = source,
                        onOpenBook = { newBookId -> nav.navigate(Routes.reader(newBookId)) },
                    )
                }
            }
        }
    }
}

/** 书柜占位屏（票 09 只需抽屉入口可达；内容票 17/18 实现） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfPlaceholder(onOpenDrawer: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书柜") },
                navigationIcon = { DrawerMenuButton(onOpenDrawer) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text("书柜尚未实现", style = MaterialTheme.typography.bodyLarge)
            Text(
                "文件源与服务器源的书柜分别在票 17 / 18 落地",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController, onOpenDrawer: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                navigationIcon = { DrawerMenuButton(onOpenDrawer) },
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
            listOf(
                SourceType.LOCAL to "本地",
                SourceType.SMB to "SMB",
                SourceType.WEBDAV to "WebDAV",
                SourceType.KOMGA to "Komga",
                SourceType.OPDS to "OPDS",
            ).forEach { (type, label) ->
                SourceRow(label, enabled = type == SourceType.LOCAL) {
                    when (type) {
                        SourceType.LOCAL -> nav.navigate(Routes.LOCAL_ROOTS)
                        else -> Toast.makeText(context, "该来源尚未实装", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            // 设置入口（票 05：仅「始终从第一页打开」；票 20 收口）
            SourceRow("设置", enabled = true) {
                nav.navigate(Routes.SETTINGS)
            }
        }
    }
}

@Composable
private fun SourceRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
