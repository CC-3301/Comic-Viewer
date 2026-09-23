package com.cc3301.comicviewer.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 名称块的行数口径（票 #94）：**真量高度**，不看常量字面值。
 *
 * 口径：网格档的名称块固定两行高（[entryNameMinLines] 的网格档取值 = [ENTRY_NAME_MAX_LINES]）——
 * 1 行名也占满两行，格子高度因此不随名称行数变化；短名称下方的留白就是第二行本身（行高），
 * 不是硬编码像素。列表档不固定（最小行数 1），行高随名称 1/2 行变化是既有行为、本票不动。
 *
 * 怎么测的：本仓库没有 compose-ui-test（`androidTest` 只有一条冒烟用例），但有 Robolectric
 * （`app/build.gradle.kts`）——起一个 [ComponentActivity]，把 [EntryNameText] 放进固定宽度的盒子里
 * 组合并布局，读它**放置后的真实高度**（`onGloballyPositioned`）。两档的 `minLines` 都由生产代码
 * [entryNameMinLines] 给出，测试不自己写行数。
 *
 * 判别力：网格档取值若退回 1，本类 2 条用例变红（1 行名高度 35 ≠ 2 行名高度 55、留白 0 ≠ 一行名高 20）；
 * 调用点若漏传 `minLines`，`EntryNameText` 的该参数无默认值、**编译不过**（不是静默回落）。
 * 接线本身仍由真机目视（网格 2/3/4 列混排）把最后一关。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EntryNameTextTest {

    /**
     * 1 行名：宽度远小于盒子，任何断行配置下都只占一行。
     */
    private val oneLineName = "短名"

    /**
     * 2 行名：Robolectric 的文本测量不按宽度换行（宽度与盒宽无关），所以用**显式换行**构造一个确定占
     * 两行的文本——本用例要的自变量是「文本占几行」，不是真实书名的换行结果（那是票 #47 的口径）。
     */
    private val twoLineName = "第一行\n第二行"

    /** 名称块宽度（固定值）：换行行为只由文本决定，与设备无关 */
    private val nameBlockWidth = 60.dp

    /** 测试用名称样式：显式 `lineHeight`，让「一行名高」可复算（不依赖 M3 主题是否在组合里） */
    private val nameStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)

    /** 真量一次名称块高度（px）：组合 → 测量 → 布局 → 读放置后的高度 */
    private fun nameBlockHeightPx(name: String, gridMode: Boolean): Int {
        var height = -1
        val view = composeViewInActivity {
            Box(Modifier.width(nameBlockWidth)) {
                EntryNameText(
                    name = name,
                    style = nameStyle,
                    minLines = entryNameMinLines(gridMode = gridMode),
                    modifier = Modifier.onGloballyPositioned { height = it.size.height },
                )
            }
        }
        view.layoutOnce(200)
        assertTrue("名称块没被放置（测量没生效），本次断言无意义", height > 0)
        return height
    }

    @Test
    fun `网格档 1 行名与 2 行名同高 名称块固定两行`() {
        val oneLine = nameBlockHeightPx(oneLineName, gridMode = true)
        val twoLine = nameBlockHeightPx(twoLineName, gridMode = true)

        assertEquals("网格档 1 行名也占满两行：同排格子高度不随名称行数变化", twoLine, oneLine)
    }

    @Test
    fun `网格档短名称下方留白恰好一行名高`() {
        val listOneLine = nameBlockHeightPx(oneLineName, gridMode = false)
        // 列表档「2 行名」比「1 行名」高的那一段就是一行名高（同一文本、同一环境，只差一行）
        val oneLineHeight = nameBlockHeightPx(twoLineName, gridMode = false) - listOneLine
        assertTrue("前置：一行名高必须为正", oneLineHeight > 0)

        val gridBlankBelowName = nameBlockHeightPx(oneLineName, gridMode = true) - listOneLine

        assertEquals("短名称下方留白 = 恰好一行名高（不是任意 dp、不是硬编码）", oneLineHeight, gridBlankBelowName)
    }

    @Test
    fun `列表档名称块不固定两行 行高仍随名称行数变化`() {
        val oneLine = nameBlockHeightPx(oneLineName, gridMode = false)
        val twoLine = nameBlockHeightPx(twoLineName, gridMode = false)

        assertTrue("列表行高随名称 1/2 行变化是既有行为（票 #94 不动列表档）", twoLine > oneLine)
    }
}
