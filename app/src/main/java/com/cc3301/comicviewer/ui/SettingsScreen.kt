package com.cc3301.comicviewer.ui

import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.reader.MAX_DOUBLE_TAP_SCALE
import com.cc3301.comicviewer.core.reader.MIN_DOUBLE_TAP_SCALE
import com.cc3301.comicviewer.core.reader.PageDirection
import com.cc3301.comicviewer.core.reader.ReadingMode
import java.util.Locale

/**
 * 设置（票 05「始终从第一页打开」；票 07 阅读模式 + 单页方向；票 20 设置收口时扩充）。
 * 阅读模式只在此处切换（spec 故事 25：阅读菜单里不放切换入口）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenDrawer: () -> Unit) {
    var alwaysFirst by remember { mutableStateOf(AppSettings.alwaysOpenFirstPage) }
    var mode by remember { mutableStateOf(AppSettings.readingMode) }
    var direction by remember { mutableStateOf(AppSettings.pageDirection) }
    var doubleTapScale by remember { mutableStateOf(AppSettings.doubleTapScale) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
            // ---------- 阅读模式 ----------
            SectionTitle("阅读模式")
            ChoiceRow(
                title = "条漫",
                subtitle = "垂直连续滚动，无翻页动作",
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
