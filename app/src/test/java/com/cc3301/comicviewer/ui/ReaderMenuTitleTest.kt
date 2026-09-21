package com.cc3301.comicviewer.ui

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Dp
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
 * 菜单标题的**实测**放置几何（票 #67 AC2/AC3）：真量「面板顶边 → 标题行顶」的距离与标题盒宽度。
 *
 * 怎么测的：照搬 `EntryNameTextTest`（票 #94）/ `BrowseRowWidthTest`（票 #92）的路子——Robolectric 起
 * [ComponentActivity]，把**生产代码** [ReaderMenuTitle] 放进一个代表面板顶边的盒子里组合并布局，
 * 读 `boundsInWindow()` 报上来的真实放置框。复刻件只有那个盒子（借它的上缘与宽度）；
 * 顶部留白来自 [ReaderMenuTitle] 自己读的 [ReaderMenuLayout.PANEL_TITLE_TOP_PADDING_DP]，
 * 两个断言因此都对着生产侧的那一行代码有判别力。
 *
 * 判别力：① 标题自己再被塞回一条上侧内边距，实测留白立刻大于
 * [ReaderMenuLayout.PANEL_TITLE_TOP_PADDING_DP]、断言变红（面板 Column 不在本测试的组合树里，
 * 「面板上侧内边距为 0」因此只由代码结构与 [ReaderMenuTitle] 的调用点把守）；
 * ② 标题若不再按面板内宽铺满（去掉 `fillMaxWidth`），宽度断言变红——长书名的可用断行宽度就变窄了。
 *
 * 不覆盖的部分（写明，避免读成全覆盖）：① **字号**只能由纯函数锁定（[ReaderMenuLayoutTest]：
 * 360dp 屏 22.4sp = 现值 `titleMedium`(16sp) 的 1.4 倍）——Robolectric 的字体度量是 stub，
 * 实测标题盒高与字号不成比例（22.4sp/26.88sp 行高量到 35px，而现值 16sp/24sp 量到 36px），
 * 因此「标题真的吃到了新字号」只由代码结构与真机目视把守，这里不做高度断言；
 * ② **真机截图对比**（AC1/AC5）与竖/横 × 手机/平板四种组合的目视（AC4）不在 JVM 里测；
 * ③ 行数上限由 [ReaderMenuTitle] 传给 [EntryNameText] 的 `maxLines` 把守：**阅读菜单标题是 3 行**
 * （票 #105 第 6 轮真机反馈第 ⑤ 条，`ReaderMenuLayout.READER_MENU_TITLE_MAX_LINES`），
 * 浏览页条目名仍是两行（`ENTRY_NAME_MAX_LINES`，票 #47 接缝）；Robolectric 的文本测量不按宽度断行，
 * 测不出换行结果。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderMenuTitleTest {

    /** 手机面板内宽：360dp 屏（两侧 20dp 内边距）——票面 AC 的基准屏 */
    private val phoneInnerWidth = 320.dp

    /** 宽面板的字号档：10 英寸平板横屏内宽（面板是 fillMaxWidth），票 #67 的 32sp 上限档 */
    private val widePanelInnerWidth = 920.dp

    /** 复刻的面板宽度：取 300dp（Robolectric 的默认屏宽 320dp 之内；`requiredWidth` 超屏宽会被裁回屏宽） */
    private val panelWidth = 300.dp

    private val density: Float = RuntimeEnvironment.getApplication().resources.displayMetrics.density

    /** 一次测量的结果（px）：面板上缘、标题盒上缘与尺寸 */
    private data class Measured(val panelTop: Int, val titleTop: Int, val titleWidth: Int) {
        val gap: Int get() = titleTop - panelTop
    }

    /** 组合生产代码 [ReaderMenuTitle] 并真量一次 [name] 的放置框（票 #105 起它带 `maxLines`/`onLineCount`） */
    private fun measure(panelInnerWidth: Dp, name: String): Measured {
        var panelTop = -1
        var titleTop = -1
        var titleWidth = -1
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity)
        activity.setContentView(view)
        view.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .requiredWidth(panelWidth)
                        .onGloballyPositioned { panelTop = it.boundsInWindow().top.roundToInt() },
                ) {
                    ReaderMenuTitle(
                        title = name,
                        panelInnerWidth = panelInnerWidth,
                        modifier = Modifier.onGloballyPositioned {
                            val frame = it.boundsInWindow()
                            titleTop = frame.top.roundToInt()
                            titleWidth = frame.width.roundToInt()
                        },
                    )
                }
            }
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec((panelWidth.value * density).roundToInt() + 40, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "标题没被放置（测量没生效），本次断言无意义",
            panelTop >= 0 && titleTop >= 0 && titleWidth > 0,
        )
        return Measured(panelTop, titleTop, titleWidth)
    }

    @Test
    fun `标题上方留白恰为常量 且不超过 8dp`() {
        val measured = measure(phoneInnerWidth, "（NH)[サークル名]サンプル作品と仲間と「サンプル」-1280x")
        val expectedPx = (ReaderMenuLayout.PANEL_TITLE_TOP_PADDING_DP * density).roundToInt()
        // 面板顶边 → 标题行顶之间只应当有这一条留白（面板 Column 的上侧内边距为 0、无上边 inset）：多一条就红
        assertEquals(
            "实测留白 ${measured.gap}px 必须正好是标题自己那一条常量（${expectedPx}px）",
            expectedPx,
            measured.gap,
        )
        val limitPx = (8f * density).roundToInt()
        assertTrue("留白 ${measured.gap}px 必须 ≤ 8dp（${limitPx}px，票 #67 AC2）", measured.gap <= limitPx)
        assertTrue("留白必须为正（标题不贴死面板顶边）", measured.gap > 0)
    }

    @Test
    fun `长书名标题按复刻的面板盒宽铺满 手机与宽面板两个字号档都是`() {
        val long = "(0)[サンプル事務所] サンプル作品名 SAMPLE (シリーズ) [サンプル]-1600x"
        val panelPx = (panelWidth.value * density).roundToInt()
        // 复刻的面板盒宽固定（Robolectric 默认屏宽 320dp 之内，requiredWidth 超过屏宽会被裁回）：
        // 两轮变的只是 panelInnerWidth 给标题的字号档（360dp 屏 22.4sp / 宽面板 32sp），
        // 验的是「字号档变了标题仍按面板内宽铺满」——**不是**面板宽本身随平板变宽的证据
        // （后者在 Robolectric 里造不出来：造不出 920dp 宽的屏，由真机目视把守）。
        for (inner in listOf(phoneInnerWidth, widePanelInnerWidth)) {
            val measured = measure(inner, long)
            // 标题盒 = 面板内宽：两行断行用的是整条面板宽（AC3 的前提，窄盒会让长书名提前换行）
            assertEquals("字号档内宽 ${inner.value}dp：标题盒宽必须是面板盒宽", panelPx, measured.titleWidth)
        }
    }
}
