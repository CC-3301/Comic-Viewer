package com.cc3301.comicviewer.core.source

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖式改名（票 #116 在 `ListingSnapshotStore` 定形，票 #124 抽到 [moveOverExistingTarget] 供三处共用）：
 * 目标已存在时必须被**换成新字节**，且不留下 `.tmp`。先例是
 * `ListingSnapshotStoreTest.同一路径重复写入第二次生效`。
 *
 * 判别力：Linux 上 `rename(2)` 本就覆盖已存在的目标，所以这条用例在 Linux 上**不会**红
 * （`File.renameTo` 不覆盖是 Windows 的行为）——它是回归钉子：实现若退回「`renameTo` 失败就静默返回」，
 * Windows 上第二次写会被丢掉，本机读不出差别（与 #116 及实施证据 §4.3 同一口径）。
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
}
