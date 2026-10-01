package com.cc3301.comicviewer.core.source

/**
 * 从一行诊断打点（空格分隔的 `key=value`）里取一个字段的值（票 #124 C 组）。
 *
 * 这段实现曾在**四处各抄一份**（`core/source/DocumentTreePageFetchProbeTest`、`core/source/SourceDiagnosticsTest`、
 * `ui/SourceLifecycleProbeTest`、`ui/PageFetchProbeTest`，逐字相同）；现收到这一处。
 * `app/src/test` 是**同一个编译单元**，跨包 `import` 即可——仓内先例：`core/source/CountingBackendTestSupport.kt`
 * 被 `core/source` 多处引用、`ui/RobolectricComposeTestSupport.kt` 被 `ui` 十处引用。
 *
 * 键不存在时 [first] 抛 `NoSuchElementException`——这正是调用方要的语义：字段被改名/漏发时当场红，
 * 而不是悄悄拿到空串（与四处副本一致）。
 */
internal fun field(line: String, key: String): String =
    line.split(' ').first { it.startsWith(key + "=") }.substringAfter('=')
