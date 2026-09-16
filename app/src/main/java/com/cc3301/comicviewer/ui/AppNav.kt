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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cc3301.comicviewer.R
import com.cc3301.comicviewer.core.source.SourceType

object Routes {
    const val HOME = "home"
    const val LOCAL_ROOTS = "localRoots"
    const val SETTINGS = "settings"
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
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(nav) }
        composable(Routes.LOCAL_ROOTS) { LocalRootsScreen(nav) }
        composable(Routes.SETTINGS) { SettingsScreen() }
        composable(Routes.BROWSER) { entry ->
            val connId = entry.arguments?.getString("connId")?.toLongOrNull()
            val container = entry.arguments?.getString("container")?.takeIf { it.isNotEmpty() }
            if (connId == null) {
                // 组合期不可直接导航：包装进 LaunchedEffect（review P2）
                LaunchedEffect(Unit) { nav.popBackStack() }
            } else {
                BrowserScreen(nav, connId, container)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController) {
    val context = LocalContext.current
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
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
