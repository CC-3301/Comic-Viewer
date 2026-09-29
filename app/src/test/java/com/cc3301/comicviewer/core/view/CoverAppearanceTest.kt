package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 封面出图口径（票 #108 E2-B）：骨架占位 → 出图淡入 150ms。
 *
 * JVM 侧只能钉住这条口径里的常数（淡入本身发生在组合期渲染）；「两种形态、不再混出第三态」的观感
 * 按 SPEC 的 Testing Decisions 走真机验收，见 `evidence-impl.md` 的残余风险。
 */
class CoverAppearanceTest {

    @Test
    fun `出图淡入是 150ms`() {
        assertEquals("票面口径：出图淡入 150ms", 150, COVER_FADE_IN_MILLIS)
    }
}
