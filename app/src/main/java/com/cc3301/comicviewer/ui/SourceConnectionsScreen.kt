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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.source.SourceType
import kotlinx.coroutines.launch

/**
 * 保存连接时表单结果 → 落库的两份值（票 #72 r2）：**写点唯一**——`connections.displayName` 列由
 * [ConnectionFormSpec.displayName] 给出、configJson 由 [ConnectionFormSpec.encode] 给出，两者在这里
 * 一起算；新增/编辑两个调用点不得各自拼一份（列名与 configJson 里的 `name` 因此不会漂移）。
 */
internal data class SavedConnection(val displayName: String, val configJson: String)

/**
 * [SavedConnection] 的唯一产地（票 #72 r2）。[ConnectionFormSpec.encode] 可能抛（凭据加密失败，票 #27），
 * 调用方按既有方式兜住（不写库、不关表单）。
 */
internal fun savedConnection(spec: ConnectionFormSpec, values: Map<String, String>): SavedConnection =
    SavedConnection(displayName = spec.displayName(values), configJson = spec.encode(values))

/**
 * 网络来源连接管理（票 11/12）：多连接 CRUD + 进入浏览，界面按 [ConnectionFormSpec] 参数化。
 *
 * 进入连接时在 IO 线程建立会话（后端构造会 stat 起始目录），
 * 失败时把归类后的中文提示（地址不通 / 认证失败 / 超时 / 路径不存在）原样弹出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceConnectionsScreen(sourceType: SourceType, nav: NavHostController, onOpenDrawer: () -> Unit) {
    val spec = remember(sourceType) { connectionFormSpec(sourceType) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connections by remember { mutableStateOf<List<ConnectionEntity>>(emptyList()) }
    var formVisible by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ConnectionEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<ConnectionEntity?>(null) }
    var opening by remember { mutableStateOf(false) }

    LaunchedEffect(sourceType) {
        ServiceLocator.db.connectionDao().observeAll().collect { all ->
            connections = all.filter { it.sourceType == sourceType.name }
        }
    }

    fun enter(conn: ConnectionEntity) {
        if (opening) return  // 防连点：连点会建两个会话并立刻关掉前一个
        scope.launch {
            opening = true
            try {
                // 共同入口（票 #49）：会话来源、浏览历史、导航目的地与书柜/本地入口写的是同一段
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
                title = { TopBarTitle(spec.title) },
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
                Text("尚未添加 " + spec.title + " 连接，点击右下角添加", style = MaterialTheme.typography.bodyMedium)
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
        ConnectionFormDialog(
            spec = spec,
            initial = editing?.let { spec.decode(it.configJson) },
            onDismiss = { formVisible = false },
            onSave = { values ->
                // 凭据加密失败（票 #27：Keystore 不可用）时**不写库、不关表单**：
                // 明文绝不入库，就地提示重试；其余失败（如果有）同样不静默
                catchingNonCancellation { savedConnection(spec, values) }.fold(
                    onSuccess = { saved ->
                        val existing = editing
                        formVisible = false
                        scope.launch {
                            if (existing == null) {
                                ServiceLocator.db.connectionDao().insert(
                                    ConnectionEntity(
                                        sourceType = sourceType.name,
                                        displayName = saved.displayName,
                                        configJson = saved.configJson,
                                    ),
                                )
                            } else {
                                ServiceLocator.db.connectionDao().update(
                                    existing.copy(displayName = saved.displayName, configJson = saved.configJson),
                                )
                                // 编辑连接后旧会话已失效：释放它（票 #30 P1）。
                                // 注意（已知代价）：票 #27 起凭据每次加密都用新随机 IV，因此**即使什么都没改**，
                                // configJson 文本也会变（会话槽的命中判据也是文本，见 ServiceLocator.browsingSourceFor）
                                // —— 保存连接会重建一次会话。保存是低频动作，接受该代价；不做「解密后比语义」的优化，
                                // 因为会话槽仍会因文本不同而重建，省不掉。
                                if (saved.configJson != existing.configJson) {
                                    // 落盘列表快照按连接 id 存，配置变了就整片作废（票 #74）
                                    ServiceLocator.purgeListingSnapshots(existing.id)
                                    ServiceLocator.closeBrowsingSource(existing.id)
                                }
                            }
                        }
                        null
                    },
                    onFailure = { t -> t.message ?: "连接保存失败" },
                )
            },
        )
    }

    pendingDelete?.let { conn ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除连接") },
            text = { Text("将删除「" + conn.displayName + "」，阅读进度保留。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        // 书柜自票 31 起只按连接陈列根条目：删除连接无需额外清理书柜数据；
                        // 若该连接正是会话级浏览来源，连**内存**列表快照一起释放（票 #30 P1）；
                        // 该连接名下的**落盘**列表快照单独清（票 #74：连接已不存在，快照不该再被命中）
                        ServiceLocator.purgeListingSnapshots(conn.id)
                        ServiceLocator.closeBrowsingSource(conn.id)
                        ServiceLocator.db.connectionDao().deleteById(conn.id)
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

/** 连接表单（新增/编辑共用）：字段由 [spec] 给出，校验失败就地提示，不落库 */
@Composable
private fun ConnectionFormDialog(
    spec: ConnectionFormSpec,
    initial: Map<String, String>?,
    onDismiss: () -> Unit,
    /** 保存：返回非空＝失败提示（表单保持打开，票 #27 的加密失败走这条）；返回空＝已保存，关闭表单 */
    onSave: (Map<String, String>) -> String?,
) {
    val values = remember(spec, initial) {
        mutableStateMapOf<String, String>().apply {
            spec.fields.forEach { field ->
                put(field.key, initial?.get(field.key).orEmpty())
            }
        }
    }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加 " + spec.title + " 连接" else "编辑 " + spec.title + " 连接") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                spec.fields.forEach { field ->
                    // 字段 + 下方提示（票 #76）：提示紧贴输入框（2dp），字段之间仍隔 8dp
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        OutlinedTextField(
                            value = values[field.key].orEmpty(),
                            onValueChange = { values[field.key] = it },
                            label = { Text(field.label) },
                            singleLine = true,
                            visualTransformation = if (field.secret) {
                                PasswordVisualTransformation()
                            } else {
                                VisualTransformation.None
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (field.hint.isNotBlank()) {
                            Text(
                                field.hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val problem = spec.validate(values)
                if (problem != null) {
                    error = problem
                } else {
                    val failure = onSave(values.toMap())
                    error = failure
                    if (failure == null) onDismiss()
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
