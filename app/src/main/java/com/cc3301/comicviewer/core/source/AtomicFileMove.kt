package com.cc3301.comicviewer.core.source

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * `*.tmp` → 目标文件的改名：**覆盖已存在的目标**（票 #116 在 `ListingSnapshotStore` 定形，票 #124 起抽成
 * 共用的一处）。`File.renameTo` 在 Windows 上不覆盖，同路径的第二次写会**静默失效**——调用方以为落盘了、
 * 读到的却是上一次的内容（或旧文件挡住新内容）。优先用文件系统的原子改名，不支持时退到带
 * `REPLACE_EXISTING` 的普通改名。
 */
internal fun moveOverExistingTarget(tmp: File, target: File) {
    val from = tmp.toPath()
    val to = target.toPath()
    try {
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
    }
}
