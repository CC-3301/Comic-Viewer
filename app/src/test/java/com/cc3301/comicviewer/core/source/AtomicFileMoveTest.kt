package com.cc3301.comicviewer.core.source

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖式改名（票 #116 在 `ListingSnapshotStore` 定形，票 #124 抽到 [moveOverExistingTarget]，现在是**唯一一条**
 * 改名通路、三处调用点共用：`ListingSnapshotStore.write`、`PageDecoder` 的页缓存、`DocumentTreeSource` 的封面缓存）：
 * 目标已存在时必须被**换成新字节**，且不留下 `.tmp`。先例是
 * `ListingSnapshotStoreTest.同一路径重复写入第二次生效`。
 *
 * 判别力：Linux 上 `rename(2)` 本就覆盖已存在的目标，所以 `目标已存在时被覆盖成新字节` 在 Linux 上**不会**红
 * （`File.renameTo` 不覆盖是 Windows 的行为）——它是回归钉子：实现若退回「`renameTo` 失败就静默返回」，
 * Windows 上第二次写会被丢掉，本机读不出差别（与 #116 及实施证据 §4.3 同一口径）。
 *
 * `ATOMIC_MOVE 不被支持时退普通覆盖式改名` 是**能红**的那条：它注入一个抛
 * [AtomicMoveNotSupportedException] 的改名实现，逼实现走 catch 支——不注入的话该分支在本机不可达
 * （Linux/macOS 都支持 `ATOMIC_MOVE`），没有用例钉得住它（票 #124 B 组）。
 */
class AtomicFileMoveTest {

    private val dir: File = Files.createTempDirectory("atomic-file-move").toFile()

    @Test
    fun `目标已存在时被覆盖成新字节 且不留 tmp`() {
        val target = File(dir, "listing")
        target.writeBytes("旧内容".toByteArray())
        val tmp = File(dir, "listing.tmp")
        tmp.writeBytes("新内容".toByteArray())

        moveOverExistingTarget(tmp, target)

        assertEquals("目标已存在时覆盖成新字节（退回 renameTo 在 Windows 上会静默丢写）", "新内容", target.readText())
        assertFalse("改名即搬走，tmp 不该残留", tmp.exists())
        assertEquals("目标留在原路径", "listing", target.name)
        assertTrue(target.exists())
    }

    @Test
    fun `ATOMIC_MOVE 不被支持时退普通覆盖式改名`() {
        val target = File(dir, "fallback")
        target.writeBytes("旧内容".toByteArray())
        val tmp = File(dir, "fallback.tmp")
        tmp.writeBytes("新内容".toByteArray())
        var attempted = false

        moveOverExistingTarget(tmp, target) { _, _ ->
            attempted = true
            throw AtomicMoveNotSupportedException(tmp.path, target.path, "测试注入：文件系统不支持原子改名")
        }

        assertTrue("先试原子改名（这条缝就是为了让 catch 支可达）", attempted)
        assertEquals("回退支同样必须覆盖已存在的目标", "新内容", target.readText())
        assertFalse("回退支也搬走了 tmp", tmp.exists())
    }
}
