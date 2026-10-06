package com.cc3301.comicviewer.core.source.komga

import com.cc3301.comicviewer.core.view.CoverDecode
import com.cc3301.comicviewer.core.view.gridBoxByteCount
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **便宜的那条**判据：长条漫首页的保留位图仍只等于显示盒（不按源分辨率吃掉封面缓存分区）。
 *
 * **同文件原先还有一条用例**（合成 1272×1800 JPEG → 走真解码器量字节数与解码耗时，`GraphicsMode.NATIVE`）：
 * 它单跑 **1m26s–1m45s**，每轮全量门禁都要付这个时间。**2026-09-27 定下不留**（数字已取到并写进
 * 规格：429,892 字节/张、解码冷 613ms / 暖 22ms、保留位图 884,736 字节），故只留这条纯函数判据。
 *
 * 判据本身与实现无关的常量（`gridTarget` 由 `CoverDecode.targetWidthPx` 从权威值 576px 算出来，
 * 见 `docs/spec/browsing.md` 的「Komga 源取哪一张」段）。
 */
class KomgaCoverCostTest {

    /** 网格 2 列的解码宽度 = **576px**（权威坐标：`docs/spec/browsing.md` 的「而测试设备……576px」那句 / `GLOSSARY.md` 第 101 行（行号会随增删漂移，故按句子锚）） */
    private val gridTarget = CoverDecode.targetWidthPx(576f)

    @Test
    fun `长条漫首页的保留位图仍只等于显示盒 不吃封面缓存分区`() {
        // 封面换成第 1 页原图后，长条漫（源高 ≫ 宽）首页必须仍走裁剪解码，
        // 保留位图只等于显示盒——否则一张 1080×15000 的首页会按源分辨率占掉整个封面缓存分区。
        val plan = CoverDecode.plan(1080, 15000, gridTarget, CoverDecode.CropTarget.GridCell, CoverDecode.BandDecoder.CropToTarget)
        val boxBytes = gridBoxByteCount(gridTarget)

        assertTrue("长条漫首页必须走裁剪解码（不是整图子采样）", plan.region)
        assertTrue(
            "保留位图 " + plan.retainedByteCount + " 字节不得超过显示盒 " + boxBytes + " 字节",
            plan.retainedByteCount <= boxBytes,
        )
    }
}
