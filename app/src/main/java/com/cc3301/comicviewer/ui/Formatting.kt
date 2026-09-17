package com.cc3301.comicviewer.ui

import java.util.Locale

/** 字节数的人类可读形式 */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
    else -> bytes.toString() + " B"
}
