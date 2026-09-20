package com.cc3301.comicviewer.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览格点击的接线（票 #64）：`ReaderMenu` 里点击回传的页位必须经
 * [com.cc3301.comicviewer.core.view.ReaderMenuLayout.previewTapTarget] 换算，不能直接传裸格位。
 *
 * 为什么是**源码级**守护而不是行为测试：这两条接线今天**行为完全相同**——`previewTapTarget` 只是
 * `clampPage(cellIndex, pageCount)`，而 `ReaderMenuLayout.previewWindow` 产出的格位本就落在 `0 until pageCount`，
 * 夹取因此从不生效。所以任何行为断言（含真机上的点格跳页）都区分不出接线用的是哪一条；能区分的只有
 * 「这段代码怎么写的」。守护点在于**未来**：窗口口径若变（#65 恒显 5 格若改成产越界格），页位夹取必须仍然
 * 落在跳页路径上——回退成裸 `index` 时本用例变红。
 *
 * 手法与本仓库既有先例一致（[LauncherIconTest] 直接读 `AndroidManifest.xml` 与资源 XML 断言资产内容）。
 * 判别力：把 `onTapPage(ReaderMenuLayout.previewTapTarget(index, pageCount))` 改回 `onTapPage(index)` 即变红。
 */
class ReaderMenuPreviewTapWiringTest {

    private val source = File("src/main/java/com/cc3301/comicviewer/ui/ReaderMenu.kt").readText()

    @Test
    fun `预览格点击回传的页位经 previewTapTarget 换算`() {
        val wirings = Regex("onTapPage\\(\\s*([^)]*)\\)").findAll(source).toList()
        assertEquals("ReaderMenu.kt 里预览格点击的 onTapPage(...) 接线应恰好一处", 1, wirings.size)

        val argument = wirings.single().groupValues[1].trim()
        assertTrue(
            "点击回传的页位必须经 ReaderMenuLayout.previewTapTarget(...) 换算（页位夹取要落在跳页路径上），" +
                "实际是：$argument",
            argument.startsWith("ReaderMenuLayout.previewTapTarget("),
        )
    }
}
