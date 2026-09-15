package com.cc3301.comicviewer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavHostController
import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.source.SourceType
import kotlinx.coroutines.launch

/** 本地来源：已授权目录列表 + SAF 添加（票 04） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalRootsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var roots by remember { mutableStateOf<List<ConnectionEntity>>(emptyList()) }

    // 已授权目录（本地来源）
    LaunchedEffect(Unit) {
        ServiceLocator.db.connectionDao().observeAll().collect { all ->
            roots = all.filter { it.sourceType == SourceType.LOCAL.name }
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                val name = DocumentFile.fromTreeUri(context, uri)?.name ?: "本地目录"
                ServiceLocator.db.connectionDao().insert(
                    ConnectionEntity(sourceType = SourceType.LOCAL.name, displayName = name, configJson = uri.toString()),
                )
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("本地") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { pickFolder.launch(null) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("添加文件夹") },
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
            if (roots.isEmpty()) {
                Text("尚未添加本地目录，点击右下角授权一个文件夹", style = MaterialTheme.typography.bodyMedium)
            }
            roots.forEach { conn ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    val source = ServiceLocator.sourceForConnection(conn)
                                    ServiceLocator.currentSource = source
                                    nav.navigate(Routes.browser(conn.id, null))
                                }
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = conn.displayName, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
