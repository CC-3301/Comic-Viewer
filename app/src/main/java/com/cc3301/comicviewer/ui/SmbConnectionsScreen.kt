package com.cc3301.comicviewer.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.cc3301.comicviewer.core.source.SourceType

/**
 * SMB 连接管理入口（票 11）：界面与 WebDAV 完全共用，只是把来源类型固定为 [SourceType.SMB]。
 * 通用实现在 [SourceConnectionsScreen]（表单字段/编解码由 [SmbFormSpec] 提供）。
 */
@Composable
fun SmbConnectionsScreen(nav: NavHostController, onOpenDrawer: () -> Unit) {
    SourceConnectionsScreen(SourceType.SMB, nav, onOpenDrawer)
}
