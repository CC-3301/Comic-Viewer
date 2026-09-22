package com.cc3301.comicviewer.ui

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.view.ReaderMenuLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * 阅读菜单「标题实测行数 → 面板高度」这条链（票 #112 第 6 条）。
 *
 * 覆盖两段：
 * ① 生产 [ReaderMenuTitle]（它内部就是 `EntryNameText(onLineCount = …)`）真的把**实测行数**回传出来：
 *    1/2/3 行各组合真量一次，断言回传值就是 `layout.lineCount`（合成标题用显式换行，Robolectric 的
 *    文本测量不按宽度断行、但按 `\n` 分行——名字形状与浏览页/阅读菜单无关）；
 * ② 回传的那几个行数喂进 [ReaderMenuLayout.panelHeightDp] / [ReaderMenuLayout.previewStripHeightDp]：
 *    面板高度**逐行变高**、预览条高度**逐像素不变**（票面「行数只让面板变高」）。
 *
 * 不覆盖（写明，避免读成全链覆盖）：`ReaderMenu` 里 `titleLines` 状态 → `panelHeightDp(titleLineCount = …)`
 * 那一处**组合期连线**没有直接用例——面板是 `BoxWithConstraints` + `Modifier.height(...)`，源码里没有可挂探针的
 * 接缝，而本仓库无 compose-ui-test 基建（`QuickScrollBarSizeTest` 同此限制），本票不为此给生产件加测试参数。
 * 那一行由真机目视（长书名 2/3 行时面板变高、预览条不动）与代码结构把守。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderMenuTitleLineCountTest {

    /** 复刻的面板宽度：与 `ReaderMenuTitleTest` 同一取法（Robolectric 默认屏宽之内） */
    private val panelWidth = 300.dp

    private val density: Float = RuntimeEnvironment.getApplication().resources.displayMetrics.density

    /** 手机竖屏档（票 #105 第 9 轮 A 档的算例屏）：视口 405 × 852、面板内宽 365 */
    private val phoneViewportWidthDp = 405f
    private val phoneViewportHeightDp = 852f
    private val phoneInnerWidthDp = 365f
    private val phoneInnerWidth = 365.dp

    /**
     * 组合生产 [ReaderMenuTitle] 并取回它回传的行数（`onTextLayout` → `onLineCount` 那一支）。
     * 布局照搬 `ReaderMenuTitleTest` 的接缝：`ComposeView` + `measure`/`layout` + 主线程 idle。
     */
    private fun measuredLineCount(name: String): Int {
        var lines = -1
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity)
        activity.setContentView(view)
        view.setContent {
            MaterialTheme {
                Box(modifier = Modifier.requiredWidth(panelWidth)) {
                    ReaderMenuTitle(
                        title = name,
                        panelInnerWidth = phoneInnerWidth,
                        onLineCount = { lines = it },
                    )
                }
            }
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec((panelWidth.value * density).roundToInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("标题没被排版（组合没生效），本次断言无意义：lines=$lines", lines >= 1)
        return lines
    }

    @Test
    fun `标题按实测行数回传 1 2 3 行`() {
        assertEquals("单行标题回传 1", 1, measuredLineCount("合成短标题"))
        assertEquals("两行标题回传 2", 2, measuredLineCount("合成标题甲\n合成标题乙"))
        assertEquals("三行标题回传 3", 3, measuredLineCount("合成标题甲\n合成标题乙\n合成标题丙"))
    }

    @Test
    fun `实测行数让面板变高 预览条不变`() {
        val lineCounts = listOf(
            measuredLineCount("合成短标题"),
            measuredLineCount("合成标题甲\n合成标题乙"),
            measuredLineCount("合成标题甲\n合成标题乙\n合成标题丙"),
        )
        assertEquals("三个合成标题的实测行数就是 1/2/3", listOf(1, 2, 3), lineCounts)

        val lineHeightDp = ReaderMenuLayout.titleLineHeightDp(phoneInnerWidthDp, fontScale = 1f)
        val panels = lineCounts.map { lines ->
            ReaderMenuLayout.panelHeightDp(
                viewportWidthDp = phoneViewportWidthDp,
                viewportHeightDp = phoneViewportHeightDp,
                titleLineHeightDp = lineHeightDp,
                titleLineCount = lines,
            )
        }
        val strips = lineCounts.map { lines ->
            ReaderMenuLayout.previewStripHeightDp(
                viewportWidthDp = phoneViewportWidthDp,
                viewportHeightDp = phoneViewportHeightDp,
                titleLineHeightDp = lineHeightDp,
                titleLineCount = lines,
            )
        }

        assertTrue("面板必须随实测行数单调变高：$panels", panels[0] < panels[1] && panels[1] < panels[2])
        assertEquals("预览条不随行数变（2 行 = 1 行）", strips[0], strips[1], 0.01f)
        assertEquals("预览条不随行数变（3 行 = 1 行）", strips[0], strips[2], 0.01f)
        assertEquals(
            "手机竖屏 1 行标题的预览条就是该档目标高度",
            ReaderMenuLayout.previewStripTargetDp(
                phoneViewportWidthDp,
                phoneViewportHeightDp,
                lineHeightDp,
            ),
            strips[0],
            0.01f,
        )
    }
}
