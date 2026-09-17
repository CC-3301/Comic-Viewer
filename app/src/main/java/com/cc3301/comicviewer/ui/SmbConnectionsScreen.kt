package com.cc3301.comicviewer.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.smb.SmbConnectionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SMB 连接管理（票 11）：多连接 CRUD + 进入浏览。
 *
 * 进入连接时在 IO 线程建立会话（SmbBackend 构造会 stat 起始目录），
 * 失败时把 [com.cc3301.comicviewer.core.source.smb.SmbException] 的中文提示原样弹出，
 * 从而区分「地址不通 / 认证失败 / 超时」（issue #12 AC1）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmbConnectionsScreen(nav: NavHostController, onOpenDrawer: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connections by remember { mutableStateOf<List<ConnectionEntity>>(emptyList()) }
    var formVisible by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ConnectionEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<ConnectionEntity?>(null) }
    var opening by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        ServiceLocator.db.connectionDao().observeAll().collect { all ->
            connections = all.filter { it.sourceType == SourceType.SMB.name }
        }
    }

    fun enter(conn: ConnectionEntity) {
        if (opening) return  // 防连点：连点会建两个会话并立刻关掉前一个
        scope.launch {
            opening = true
            try {
                val source = withContext(Dispatchers.IO) { ServiceLocator.sourceForConnection(conn) }
                ServiceLocator.currentSource = source
                ServiceLocator.currentConnId = conn.id
                // 切换连接时清空历史：不同来源的浏览位置不能互相前进/后退（与本地来源一致）
                if (ServiceLocator.browseHistory.current?.connId != conn.id) {
                    ServiceLocator.browseHistory.clear()
                }
                ServiceLocator.browseHistory.record(BrowseLocation(conn.id, containerId = null))
                nav.navigate(Routes.browser(conn.id, null))
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
                title = { Text("SMB") },
                navigationIcon = { DrawerMenuButton(onOpenDrawer) },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editing = null
                    formVisible = true
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("添加连接") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (opening) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text("正在连接…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (connections.isEmpty()) {
                Text("尚未添加 SMB 连接，点击右下角添加", style = MaterialTheme.typography.bodyMedium)
            }
            connections.forEach { conn ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { enter(conn) },
                        ) {
                            Text(conn.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "点击进入浏览",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        TextButton(onClick = {
                            editing = conn
                            formVisible = true
                        }) { Text("编辑") }
                        TextButton(onClick = { pendingDelete = conn }) { Text("删除") }
                    }
                }
            }
        }
    }

    if (formVisible) {
        SmbFormDialog(
            initial = editing?.let { SmbConnectionConfig.fromJson(it.configJson) },
            onDismiss = { formVisible = false },
            onSave = { config ->
                formVisible = false
                scope.launch {
                    val existing = editing
                    if (existing == null) {
                        ServiceLocator.db.connectionDao().insert(
                            ConnectionEntity(
                                sourceType = SourceType.SMB.name,
                                displayName = config.displayName,
                                configJson = config.toJson(),
                            ),
                        )
                    } else {
                        ServiceLocator.db.connectionDao().update(
                            existing.copy(displayName = config.displayName, configJson = config.toJson()),
                        )
                    }
                }
            },
        )
    }

    pendingDelete?.let { conn ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除连接") },
            text = { Text("将删除「${conn.displayName}」，阅读进度保留。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch { ServiceLocator.db.connectionDao().deleteById(conn.id) }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

/** 连接表单（新增/编辑共用）；校验失败就地提示，不落库 */
@Composable
private fun SmbFormDialog(
    initial: SmbConnectionConfig?,
    onDismiss: () -> Unit,
    onSave: (SmbConnectionConfig) -> Unit,
) {
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: SmbConnectionConfig.DEFAULT_PORT).toString()) }
    var share by remember { mutableStateOf(initial?.share ?: "") }
    var rootPath by remember { mutableStateOf(initial?.rootPath ?: "") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var domain by remember { mutableStateOf(initial?.domain ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加 SMB 连接" else "编辑 SMB 连接") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FormField("服务器地址", host) { host = it }
                FormField("端口", port, KeyboardType.Number) { port = it }
                FormField("共享名", share) { share = it }
                FormField("起始目录（可空）", rootPath) { rootPath = it }
                FormField("用户名（可空）", username) { username = it }
                FormField("密码（可空）", password, password = true) { password = it }
                FormField("域（可空）", domain) { domain = it }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val config = SmbConnectionConfig(
                    host = host.trim(),
                    share = share.trim(),
                    rootPath = rootPath.trim(),
                    username = username,
                    password = password,
                    domain = domain,
                    port = port.trim().toIntOrNull() ?: 0,
                )
                val problem = SmbConnectionConfig.validate(config)
                if (problem != null) error = problem else onSave(config)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun FormField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}
