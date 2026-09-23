package com.cc3301.comicviewer.core.source

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * `*.tmp` → 目标文件的改名：**覆盖已存在的目标**（票 #116 在 `ListingSnapshotStore` 定形，票 #124 把同一形状
 * 抽到这一处供**两处调用点**共用：`PageDecoder` 的页缓存与 `DocumentTreeSource` 的封面缓存）。`File.renameTo` 在
 * Windows 上不覆盖，同路径的第二次写会**静默失效**——调用方以为落盘了、读到的却是上一次的内容（或旧文件
 * 挡住新内容）。优先用文件系统的原子改名，不支持时退到带 `REPLACE_EXISTING` 的普通改名。
 *
 * **尚存一份同形副本**：`ListingSnapshotStore.kt` 的私有 `moveOverTarget`（逐字同型），本轮按批次划分未动，
 * 待 C 组「同类逻辑多份」合并——改名通路现在是**两条**，不是一条。
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
