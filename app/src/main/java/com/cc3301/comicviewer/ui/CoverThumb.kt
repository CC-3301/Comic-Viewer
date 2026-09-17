package com.cc3301.comicviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封面缩略图（票 04 浏览列表；票 17 书柜同款复用）：
 * 优先系统可解码 uri，SMB/WebDAV 等来源解不出时回退来源字节。
 * 取封面失败（来源离线/抛网络异常）只显示占位底色，不中断界面。
 */
@Composable
fun CoverThumb(
    coverUri: String?,
    cacheKey: String,
    loadBytes: suspend () -> ByteArray?,
    size: Dp = 56.dp,
    /**
     * 取字节所依赖的会话标识（如书柜里后到的来源会话）：它一变就重取封面。
     * 浏览列表的来源是现成且恒定的，用默认的 null 即可。
     */
    reloadKey: Any? = null,
) {
    val context = LocalContext.current
    var bitmap by remember(coverUri, reloadKey) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(coverUri, reloadKey) {
        bitmap = withContext(Dispatchers.IO) {
            val fromUri = coverUri
                ?.takeIf { it.isNotEmpty() }
                // SMB/WebDAV 的标识串（smb://… / webdav-http://…）系统解不了：直接走来源字节，
                // 不白跑一次 ContentResolver
                ?.takeIf { it.startsWith("content://") || it.startsWith("file://") }
                ?.let { PageDecoder.decodeUri(context, it, 128) }
            fromUri ?: runCatching { loadBytes() }.getOrNull()?.let { bytes ->
                PageDecoder.decodeBytes("cover@" + cacheKey + "@128", bytes, 128)
            }
        }
    }
    Box(
        modifier = Modifier
            .size(size)
            .background(Color.DarkGray),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(it, contentDescription = null, modifier = Modifier.size(size), contentScale = ContentScale.Crop)
        }
    }
}
