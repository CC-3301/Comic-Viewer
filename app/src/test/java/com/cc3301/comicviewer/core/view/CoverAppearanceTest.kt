package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 封面出图口径（票 #108 E2-B；票 #145 r6 撤掉淡入）：骨架占位 → 出图**直接出现**（不带淡入）。
 *
 * JVM 侧只能钉住这条口径里的常数（出图本身发生在组合期渲染）；「位图一到直接出现、不再逐帧半透明合成」
 * 的观感按 SPEC 的 Testing Decisions 走真机验收，见 `evidence-impl.md` 的残余风险。
 */
class CoverAppearanceTest {

    @Test
    fun `出图不带淡入 位图一到直接出现`() {
        assertEquals("票面口径（票 #145 维护者拍板）：撤掉封面淡入，位图一到直接出现", 0, COVER_FADE_IN_MILLIS)
    }
}
