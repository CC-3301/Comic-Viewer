package com.cc3301.comicviewer.core.source

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * `*.tmp` → 目标文件的改名：**覆盖已存在的目标**（票 #116 在 `ListingSnapshotStore` 定形，票 #124 抽到这一处，
 * 现在是**唯一一条**改名通路，三处调用点共用：`ListingSnapshotStore.write`、`PageDecoder` 的页缓存、
 * `DocumentTreeSource` 的封面缓存）。`File.renameTo` 在 Windows 上不覆盖，同路径的第二次写会**静默失效**——
 * 调用方以为落盘了、读到的却是上一次的内容（或旧文件挡住新内容）。优先用文件系统的原子改名，
 * 不支持时退到带 `REPLACE_EXISTING` 的普通改名。
 *
 * [atomicMove] 是给回退支留的注入缝（票 #124 B 组）：`ATOMIC_MOVE` 在 Linux/macOS 上都支持，
 * 回退支本机不可达，不注入就没有用例能钉住它（用例见 `AtomicFileMoveTest`）。生产调用点不传。
 */
internal fun moveOverExistingTarget(
    tmp: File,
    target: File,
    atomicMove: (Path, Path) -> Path = { from, to ->
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    },
) {
    try {
        atomicMove(tmp.toPath(), target.toPath())
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
