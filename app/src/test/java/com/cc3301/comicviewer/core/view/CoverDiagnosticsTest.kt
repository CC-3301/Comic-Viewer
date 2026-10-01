package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面源图分辨率的打点（票 #140）：「Komga 源封面糊」取数用的那一行。
 *
 * 本文件钉的是**判据字段**：`upscale=true` 必须**严格**表示「源宽 < 目标 px」（源宽恰等于目标宽度时是 1:1，
 * 不算放大）。判错方向会让真机上「到底有没有被放大」这件事反过来，取数就白取。
 *
 * 真机上那一行的数值（Komga 服务端缩略图到底多大）本机取不到，属残余风险——它正是这一票要采的日志。
 */
class CoverDiagnosticsTest {

    /** 只关心行格式：计划的几何细节由 `CoverDecodeTest` 钉，这里只需要 `region` 那两个值 */
    private fun plan(region: Boolean) = CoverDecode.Plan(
        region = region,
        left = 0,
        top = 0,
        width = 100,
        height = 100,
        sampleSize = 1,
        retainedWidth = 100,
        retainedHeight = 100,
        cropToTarget = null,
        decodedWidth = 100,
        decodedHeight = 100,
    )

    @Test
    fun `源宽小于目标宽度时 upscale 为真`() {
        assertEquals(
            "coverSource key=cover@komga://s/1@null@512@GridCell src=320x480 target=512px " +
                "crop=GridCell plan=region upscale=true",
            CoverDiagnostics.coverSourceLine(
                key = "cover@komga://s/1@null@512@GridCell",
                srcWidth = 320,
                srcHeight = 480,
                targetWidthPx = 512,
                cropTarget = CoverDecode.CropTarget.GridCell,
                plan = plan(region = true),
            ),
        )
    }

    @Test
    fun `源宽够时 upscale 为假`() {
        val line = CoverDiagnostics.coverSourceLine(
            key = "cover@komga://b/9@null@512@OwnAspect",
            srcWidth = 1600,
            srcHeight = 2400,
            targetWidthPx = 512,
            cropTarget = CoverDecode.CropTarget.OwnAspect,
            plan = plan(region = false),
        )
        assertEquals(
            "coverSource key=cover@komga://b/9@null@512@OwnAspect src=1600x2400 target=512px " +
                "crop=OwnAspect plan=full upscale=false",
            line,
        )
    }

    @Test
    fun `源宽恰好等于目标宽度不算放大`() {
        val line = CoverDiagnostics.coverSourceLine(
            key = "k",
            srcWidth = 512,
            srcHeight = 768,
            targetWidthPx = 512,
            cropTarget = CoverDecode.CropTarget.GridCell,
            plan = plan(region = true),
        )

        assertTrue("1:1 是正常缩放，不算被放大", line.endsWith("upscale=false"))
    }

    @Test
    fun `行首是前缀 便于 grep`() {
        val line = CoverDiagnostics.coverSourceLine(
            key = "k", srcWidth = 100, srcHeight = 100, targetWidthPx = 200,
            cropTarget = CoverDecode.CropTarget.GridCell, plan = plan(region = false),
        )

        assertTrue(line.startsWith(CoverDiagnostics.PREFIX + " "))
        assertEquals("coverSource", CoverDiagnostics.PREFIX)
    }
}
