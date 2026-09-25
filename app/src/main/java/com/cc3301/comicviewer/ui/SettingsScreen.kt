package com.cc3301.comicviewer.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.nav.StartupPage
import com.cc3301.comicviewer.core.reader.MAX_DOUBLE_TAP_SCALE
import com.cc3301.comicviewer.core.reader.MIN_DOUBLE_TAP_SCALE
import com.cc3301.comicviewer.core.reader.OrientationMode
import com.cc3301.comicviewer.core.reader.PageDirection
import com.cc3301.comicviewer.core.reader.ReadingMode
import com.cc3301.comicviewer.core.reader.ThemeMode
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置（票 05「始终从第一页打开」；票 07 阅读模式 + 单页方向；票 20 设置收口时扩充）。
 * 阅读模式只在此处切换（spec 故事 25：阅读菜单里不放切换入口）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenDrawer: () -> Unit) {
    var alwaysFirst by remember { mutableStateOf(AppSettings.alwaysOpenFirstPage) }
    var startupPage by remember { mutableStateOf(AppSettings.startupPage) }
    var mode by remember { mutableStateOf(AppSettings.readingMode) }
    var direction by remember { mutableStateOf(AppSettings.pageDirection) }
    var doubleTapScale by remember { mutableStateOf(AppSettings.doubleTapScale) }
    var orientation by remember { mutableStateOf(AppSettings.orientation) }
    var themeMode by remember { mutableStateOf(AppSettings.themeMode) }
    var volumeKeys by remember { mutableStateOf(AppSettings.volumeKeysEnabled) }
    var diagnostics by remember { mutableStateOf(AppSettings.diagnosticsEnabled) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TopBarTitle("设置") },
                navigationIcon = { DrawerMenuButton(onOpenDrawer) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // ---------- 显示：页面旋转（spec 故事 50）----------
            SectionTitle("页面旋转")
            ChoiceRow(
                title = "跟随系统",
                subtitle = "随设备方向自动旋转",
                selected = orientation == OrientationMode.FOLLOW_SYSTEM,
                onSelect = {
                    orientation = OrientationMode.FOLLOW_SYSTEM
                    AppSettings.orientation = OrientationMode.FOLLOW_SYSTEM
                },
            )
            ChoiceRow(
                title = "竖屏",
                subtitle = "锁定纵向",
                selected = orientation == OrientationMode.PORTRAIT,
                onSelect = {
                    orientation = OrientationMode.PORTRAIT
                    AppSettings.orientation = OrientationMode.PORTRAIT
                },
            )
            ChoiceRow(
                title = "横屏",
                subtitle = "锁定横向",
                selected = orientation == OrientationMode.LANDSCAPE,
                onSelect = {
                    orientation = OrientationMode.LANDSCAPE
                    AppSettings.orientation = OrientationMode.LANDSCAPE
                },
            )

            // ---------- 显示：主题（spec 故事 51）----------
            SectionTitle("主题")
            ChoiceRow(
                title = "跟随系统",
                subtitle = "与设备深色模式一致",
                selected = themeMode == ThemeMode.FOLLOW_SYSTEM,
                onSelect = {
                    themeMode = ThemeMode.FOLLOW_SYSTEM
                    AppSettings.themeMode = ThemeMode.FOLLOW_SYSTEM
                },
            )
            ChoiceRow(
                title = "深色",
                subtitle = "始终使用深色界面",
                selected = themeMode == ThemeMode.DARK,
                onSelect = {
                    themeMode = ThemeMode.DARK
                    AppSettings.themeMode = ThemeMode.DARK
                },
            )
            ChoiceRow(
                title = "浅色",
                subtitle = "始终使用浅色界面",
                selected = themeMode == ThemeMode.LIGHT,
                onSelect = {
                    themeMode = ThemeMode.LIGHT
                    AppSettings.themeMode = ThemeMode.LIGHT
                },
            )

            // ---------- 启动页面（spec 故事 46-49）----------
            SectionTitle("启动页面")
            ChoiceRow(
                title = "上次阅读的位置（默认）",
                subtitle = "上次退出时在看书就直接打开该书并定位，否则回到上次停留的位置",
                selected = startupPage == StartupPage.LAST_READ,
                onSelect = {
                    startupPage = StartupPage.LAST_READ
                    AppSettings.startupPage = StartupPage.LAST_READ
                },
            )
            ChoiceRow(
                title = "上次停留的位置",
                subtitle = "停在首页/书柜/设置就回到那一页，停在浏览页则恢复退出时的目录层级（排序方式与方向是全局设置，一直保持；不恢复滚动位置）",
                selected = startupPage == StartupPage.LAST_BROWSING,
                onSelect = {
                    startupPage = StartupPage.LAST_BROWSING
                    AppSettings.startupPage = StartupPage.LAST_BROWSING
                },
            )
            ChoiceRow(
                title = "书柜",
                subtitle = "直接进入书柜的柜列表",
                selected = startupPage == StartupPage.BOOKSHELF,
                onSelect = {
                    startupPage = StartupPage.BOOKSHELF
                    AppSettings.startupPage = StartupPage.BOOKSHELF
                },
            )
            ChoiceRow(
                title = "阅读器",
                subtitle = "直接打开上次阅读的书（无读书记录时回到首页）",
                selected = startupPage == StartupPage.READER,
                onSelect = {
                    startupPage = StartupPage.READER
                    AppSettings.startupPage = StartupPage.READER
                },
            )
            ChoiceRow(
                title = "首页",
                subtitle = "来源/连接列表",
                selected = startupPage == StartupPage.HOME,
                onSelect = {
                    startupPage = StartupPage.HOME
                    AppSettings.startupPage = StartupPage.HOME
                },
            )

            // ---------- 阅读模式 ----------
            SectionTitle("阅读模式")
            ChoiceRow(
                title = "条漫",
                subtitle = "垂直连续滚动，可按页跳转（跳到下一页/上一页页首）",
                selected = mode == ReadingMode.WEBTOON,
                onSelect = {
                    mode = ReadingMode.WEBTOON
                    AppSettings.readingMode = ReadingMode.WEBTOON
                },
            )
            ChoiceRow(
                title = "单页",
                subtitle = "一次一页、横向翻页",
                selected = mode == ReadingMode.PAGED,
                onSelect = {
                    mode = ReadingMode.PAGED
                    AppSettings.readingMode = ReadingMode.PAGED
                },
            )

            // ---------- 单页方向（仅单页模式有意义）----------
            if (mode == ReadingMode.PAGED) {
                SectionTitle("单页方向")
                ChoiceRow(
                    title = "左 → 右",
                    subtitle = "下一页在右侧（欧美漫画）",
                    selected = direction == PageDirection.LTR,
                    onSelect = {
                        direction = PageDirection.LTR
                        AppSettings.pageDirection = PageDirection.LTR
                    },
                )
                ChoiceRow(
                    title = "右 → 左（RTL）",
                    subtitle = "下一页在左侧（日漫）",
                    selected = direction == PageDirection.RTL,
                    onSelect = {
                        direction = PageDirection.RTL
                        AppSettings.pageDirection = PageDirection.RTL
                    },
                )
            }

            // ---------- 阅读 ----------
            SectionTitle("阅读")
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "双击放大倍率",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        String.format(Locale.US, "%.1fx", doubleTapScale),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = doubleTapScale,
                    onValueChange = { doubleTapScale = it },   // 拖动中只改本地状态
                    onValueChangeFinished = { AppSettings.doubleTapScale = doubleTapScale },  // 松手才落盘（review P2）
                    valueRange = MIN_DOUBLE_TAP_SCALE..MAX_DOUBLE_TAP_SCALE,
                    steps = 4,   // 1.5 / 2.0 / 2.5 / 3.0 / 3.5 / 4.0
                )
                Text(
                    "双击放大时的倍率（双指缩放上限 8x）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 音量键翻页（spec 故事 39）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = volumeKeys,
                        role = Role.Switch,
                        onValueChange = {
                            volumeKeys = it
                            AppSettings.volumeKeysEnabled = it
                        },
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("音量键翻页", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "单页翻页、条漫跳到下一/上一页页首；长按连续跳页（默认开）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 点击语义统一由行上的 toggleable 提供（TalkBack 单焦点）
                Switch(checked = volumeKeys, onCheckedChange = null)
            }

            // ---------- 打开行为 ----------
            SectionTitle("打开行为")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = alwaysFirst,
                        role = Role.Switch,
                        onValueChange = {
                            alwaysFirst = it
                            AppSettings.alwaysOpenFirstPage = it
                        },
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("始终从第一页打开", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "打开任何书都定位第 1 页，并立即覆盖阅读进度",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 点击语义统一由行上的 toggleable 提供（TalkBack 单焦点）
                Switch(checked = alwaysFirst, onCheckedChange = null)
            }

            // ---------- 诊断（票 #113 修复轮）----------
            SectionTitle("诊断")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = diagnostics,
                        role = Role.Switch,
                        onValueChange = {
                            diagnostics = it
                            AppSettings.diagnosticsEnabled = it
                        },
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("诊断日志", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "仅排查问题时打开",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = diagnostics, onCheckedChange = null)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch {
                            // 写文件是 IO：不在组合线程上做；拼报告读的是内存缓冲与缓存计数，不弹网络
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val report = DiagnosticsExport.buildReport(context, ServiceLocator.currentSource)
                                    val file = DiagnosticsExport.writeReport(context, report)
                                    DiagnosticsExport.shareIntent(context, file)
                                }
                            }
                            result
                                .onSuccess { share ->
                                    context.startActivity(Intent.createChooser(share, "分享诊断日志"))
                                }
                                .onFailure { t ->
                                    Toast.makeText(
                                        context,
                                        "导出失败：" + (t.message ?: t.javaClass.simpleName),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                        }
                    }
                    .padding(vertical = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("导出诊断日志", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "把内存里的打点行与状态快照写成 txt 并分享（开关关着时文件里只有状态快照）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/** 单选行（阅读模式 / 方向共用） */
@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // onClick = null：行上的 selectable 负责点击与已选语义（避免双焦点）
        RadioButton(selected = selected, onClick = null)
    }
}
