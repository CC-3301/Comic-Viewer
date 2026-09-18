package com.cc3301.comicviewer.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.cc3301.comicviewer.core.shelf.CabinetRef
import com.cc3301.comicviewer.core.shelf.groupIntoCabinets
import kotlinx.coroutines.launch

/**
 * 书柜柜列表（票 31，spec 故事 43/44）：一条连接一个柜，多连接不混排。
 * 柜名取自连接配置（不需要会话），因此离线连接照常列柜；本页不解析来源、不枚举条目。
 * 没有任何连接时才提示去添加来源。柜列表这一层不放排序入口（条目是连接，没有时间/发布日期的意义）。
 *
 * 点柜（票 #49）= 进该连接的**浏览页根层**——与首页点该连接同一路由、同一屏、同一排版，
 * 只服务书柜路径的重复柜内屏已删除；因此本页只负责「按连接分柜的入口列表」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(nav: NavHostController, onOpenDrawer: () -> Unit) {
    val connections by remember { ServiceLocator.db.connectionDao().observeAll() }
        .collectAsState(initial = emptyList())
    // 分柜（spec 故事 44）：条目留给进浏览页时取，这里只按连接立柜（离线连接也在内）
    val cabinets = remember(connections) {
        groupIntoCabinets(connections.map { CabinetRef(it.id, it.displayName) })
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var opening by remember { mutableStateOf(false) }

    /**
     * 点柜 = 进该连接的浏览根层。与连接列表入口写的是同一段（[openConnectionRoot]）：
     * 同一会话来源、同一套浏览历史处理、同一路由；建会话失败与连接列表同款给 Toast。
     */
    fun enter(connectionId: Long) {
        val conn = connections.firstOrNull { it.id == connectionId } ?: return
        if (opening) return  // 防连点：连点会建两个会话并立刻关掉前一个
        scope.launch {
            opening = true
            try {
                openConnectionRoot(nav, conn)
            } catch (t: Throwable) {
                Toast.makeText(context, t.message ?: "连接失败", Toast.LENGTH_LONG).show()
            } finally {
                opening = false
            }
        }
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
                                .clickable { enter(cabinet.connectionId) }
                                .padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}
