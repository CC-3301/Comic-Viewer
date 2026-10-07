package com.cc3301.comicviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 导航抽屉（spec 故事 53）：仅左缘滑出，四入口——首页 / 阅读器 / 书柜 / 设置。
 *
 * 阅读器内由调用方把 [gesturesEnabled] 置 false：左缘滑动手势要留给系统返回手势（spec 故事 38）。
 * 浏览历史的后退/前进不再有抽屉入口：两者仍由系统返回与鼠标侧键触发（spec 故事 37）。
 *
 * 内容层（所有屏都挂在本抽屉的 content 槽里）经 [LocalDrawerIsClosed] 拿到「抽屉开着吗」：
 * 抽屉开着时它们的返回处理器一律让位，那次返回只关抽屉。
 */
@Composable
fun AppDrawer(
    drawerState: DrawerState,
    currentRoute: String?,
    gesturesEnabled: Boolean,
    onOpenHome: () -> Unit,
    onOpenReader: () -> Unit,
    onOpenBookshelf: () -> Unit,
    onOpenSettings: () -> Unit,
    content: @Composable () -> Unit,
) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = gesturesEnabled,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.padding(vertical = 16.dp)) {
                    Text(
                        text = "Comic-Viewer",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))

                    NavigationDrawerItem(
                        label = { Text("首页") },
                        selected = currentRoute == Routes.HOME,
                        onClick = onOpenHome,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("阅读器") },
                        selected = currentRoute == Routes.READER,
                        onClick = onOpenReader,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("书柜") },
                        selected = bookshelfEntrySelected(currentRoute),
                        onClick = onOpenBookshelf,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("设置") },
                        selected = currentRoute == Routes.SETTINGS,
                        onClick = onOpenSettings,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        },
        content = {
            val scope = rememberCoroutineScope()

            // 抽屉状态只在**内容槽这一处**读：读了 [LocalDrawerIsClosed] 的那几个处理器才随开合重组，
            // 不把整个内容层（NavHost 及各屏）拖着重组；值没变时 provider 自己跳过内容。
            CompositionLocalProvider(
                LocalDrawerIsClosed provides drawerState.isClosed,
                content = content,
            )

            // 抽屉开着时的返回只关抽屉，且**必须写在内容层之后**。
            // 返回回调「后注册先派发」（`OnBackPressedDispatcher.onBackPressed` 从注册表末尾往前找第一个启用的），
            // 而内容槽里注册得比这里更早的接管有两类——`NavHost` 给 `NavController` 装的那个（栈深 > 1 时启用，
            // 直接弹栈）与各屏自己的处理器。写在内容层之前时两者都会抢在它前面（现象：按返回走的是路径返回）。
            // Material3 自带的抽屉返回接管（`DrawerPredictiveBackHandler`）同样在内容槽之前，抢不过它们。
            // 判据用 `!isClosed` 而非 `isOpen`：开合动画途中两个值都是 false，那时按返回同样该关抽屉。
            BackHandler(enabled = !drawerState.isClosed) {
                scope.launch { drawerState.close() }
            }
        },
    )
}

/**
 * 抽屉「书柜」入口是否高亮（纯函数，由 [BrowseEntryPointTest] 锁定）：只在**柜列表页**为真。
 *
 * 「点连接」直接进浏览页根层，浏览页与首页路径同款（不高亮）——否则从书柜点进连接后
 * 抽屉里还亮着「书柜」，而界面已经是浏览页，入口状态与所在界面不符。
 */
internal fun bookshelfEntrySelected(route: String?): Boolean = route == Routes.BOOKSHELF

/** 各屏 TopAppBar 复用的抽屉按钮（spec 故事 53：抽屉只从左侧进入） */
@Composable
fun DrawerMenuButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Filled.Menu, contentDescription = "打开导航抽屉")
    }
}
