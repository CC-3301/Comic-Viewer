package com.cc3301.comicviewer.ui

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.view.ReaderMenuLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 预览条的**当前页居中**（票 #105 AC17）：在 Robolectric 里**复刻预览条的结构**
 * （`LazyRow` + `spacedBy` + 自建 `LazyListState`），用生产的偏移函数
 * [ReaderMenuLayout.previewCenterScrollOffsetPx] 滚一次，然后量真实几何。钉三件事：
 *
 * 1. 中间页：目标项**中心落在预览区中心**（±1px）——偏移没发出去（等价于漏掉第二步）或符号写反都会红；
 * 2. 首页：目标项贴**左缘**且完全可见（居中量为负、列表滚不动 ⇒ 由 `LazyList` 夹掉）；
 * 3. 末页：目标项贴**右缘**且完全可见（同上，夹在最大滚动量上）。
 * 另有两条：宽度不同的格子里必须用**目标项自己**的宽（见 `目标项比别的格子宽时…`）；
 * 首页/末页的「到头贴边」也由 ②③ 覆盖。
 *
 * **本机构造不出来的那条结构用例**（第 16 轮）：生产把重算键做成「可见项 `(index, size)` 签名」是为了让
 * **目标项前面那几格**晚到（位图晚于当前页解码）也能纠正一次；本文件试过用 `mutableStateOf` 在两次布局之间
 * 只改前一格的宽度，但**这套「布局 → `idle()`」驱动下组合后从测试线程改 snapshot state 不会触发重组**
 * （实测邻格宽仍是 80dp，用 `Snapshot.withMutableSnapshot` 亦同）⇒ 该结构用例在本机不成立，已按前几轮惯例
 * 降级为纯函数用例（`ReaderMenuLayoutTest.居中偏移随目标项实测宽变化…`）+ 真机判据（evidence-impl.md 第 16 轮）。
 *
 * **本文件不覆盖什么**（避免读成全覆盖）：生产的 `PreviewStrip` 本身没有被组合（条目内部没有可注入的
 * `modifier` 钩子、且需要 `BookHandle` / 位图解码；仓库没有 Compose UI 测试库，与 [PreviewStripLayoutTest]
 * 同一套做法），因此它的**挂起两步滚序**（① `scrollToItem(target)` 先把目标项带进视口 → ② 量宽后补偏移，
 * 见 `PreviewStrip` 的 KDoc）与**宽度变化后的重算**（`LaunchedEffect` 的 key 含目标项实测宽）都不由本文件
 * 判定 —— 前者本地驱动不了挂起 API、后者是 Compose effect 的 key 语义；两条都由真机验收
 * （判据见 evidence-impl.md 第 13/15 轮残余风险）。本文件只保证「该偏移 + `LazyList` 自己的夹取」是对的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewStripCenterTest {

    /** 预览条宽（真机上来自面板的 `weight(1f)` / `fillMaxWidth`，尺寸是确定的） */
    private val stripWidth = 300.dp

    private val stripHeight = 200.dp

    /** 单格宽度（真机上 = 图片高 × 该页真实比例） */
    private val itemWidth = 80.dp

    private val gap = 6.dp

    private data class Measured(
        val viewportLeftPx: Int,
        val viewportWidthPx: Int,
        val itemLeftPx: Map<Int, Int>,
        val itemWidthPx: Map<Int, Int>,
    )

    /**
     * 量「按生产的居中偏移滚过去之后」目标项的位置。[target] 就是当前页（0-based）。
     *
     * 生产（`PreviewStrip` 的 `LaunchedEffect`）是两步：① `scrollToItem(目标页)` 把目标项带进视口
     * （它可能离得很远，那时量不到它的宽）→ ② 读 `layoutInfo.visibleItemsInfo` 里**目标项自己的 `size`**
     * 与 `viewportSize.width`，用 [ReaderMenuLayout.previewCenterScrollOffsetPx] 算偏移后再滚一次。
     *
     * 本函数走的是**合成一次的等价路径**：先布局拿到宽度 → 用同一个偏移函数请求滚动。
     * 替换 API 的原因（实测）：Robolectric 的暂停 Looper **驱动不了挂起的 `scrollToItem`**
     * （4 轮「布局 → idle」后目标项始终没被布局出来），而且对**同一条目**连着两次
     * `requestScrollToItem` 时**只有第一次生效**（第二步被丢掉、目标项停在左缘）——
     * 因此这里只发一次带偏移的请求；生产那条挂起序列本身仍属真机验收项（见 evidence-impl.md 第 13 轮残余风险 4）。
     * 判别力：偏移函数写错（符号/公式）、或取宽取成了别的格子（见 `目标项比别的格子宽时…`），断言会红。
     */
    private fun measure(
        count: Int,
        target: Int,
        /** 每格宽度（真机上 = 图片高 × 该页真实比例，因此**每格可以不一样**）：本参数用来钉「居中用目标项自己的宽」 */
        widthFor: (Int) -> androidx.compose.ui.unit.Dp = { itemWidth },
    ): Measured {
        var viewportLeft = -1
        var viewportWidthPx = -1
        val itemLeft = mutableMapOf<Int, Int>()
        val itemWidthPx = mutableMapOf<Int, Int>()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity)
        val listState = LazyListState()
        activity.setContentView(view)
        view.setContent {
            Box(Modifier.fillMaxSize()) {
                LazyRow(
                    state = listState,
                    modifier = Modifier
                        .width(stripWidth)
                        .height(stripHeight)
                        .onGloballyPositioned {
                            viewportLeft = it.boundsInWindow().left.roundToInt()
                            viewportWidthPx = it.size.width
                        },
                    horizontalArrangement = Arrangement.spacedBy(space = gap, alignment = Alignment.CenterHorizontally),
                ) {
                    items(count = count, key = { it }) { index ->
                        Box(
                            Modifier
                                .width(widthFor(index))
                                .fillMaxHeight()
                                .onGloballyPositioned {
                                    itemLeft[index] = it.boundsInWindow().left.roundToInt()
                                    itemWidthPx[index] = it.size.width
                                },
                        ) {
                            Text("x")
                        }
                    }
                }
            }
        }
        // ① 先布局一轮：拿到视口宽与格子宽（生产第二步读的 `layoutInfo` 同理）
        layoutOnce(view)
        assertTrue("没量到预览区（测量没生效），本次断言无意义", viewportWidthPx > 0)
        // 取宽优先用**目标项自己**的测量宽（目标项初始不可见时，本用例里各格同宽、取任一项等价）
        val offsetWidth = itemWidthPx[target] ?: itemWidthPx[0] ?: -1
        assertTrue("没量到格子宽度（测量没生效），本次断言无意义", offsetWidth > 0)
        // ② 按生产的居中偏移请求滚动
        listState.requestScrollToItem(
            index = target,
            scrollOffset = ReaderMenuLayout.previewCenterScrollOffsetPx(offsetWidth, viewportWidthPx),
        )
        repeat(4) { layoutOnce(view) }
        assertTrue(
            "目标项 $target 没被布局出来（滚动没生效），本次断言无意义",
            itemLeft.containsKey(target) && itemWidthPx.containsKey(target),
        )
        return Measured(viewportLeft, viewportWidthPx, itemLeft, itemWidthPx)
    }

    /** 跑一轮「测量 → 布局 → idle」：Compose 的滚动请求在后一轮布局里生效 */
    private fun layoutOnce(view: ComposeView) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec((stripWidth.value * 2).roundToInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * 中间页居中：目标项中心 = 预览区中心（AC17 的核心判据）。判别力：偏移没发出去（等价于漏掉
     * 第二步）、偏移公式/符号写错、或把某格宽度当成了另一格的宽，都会红；
     * 生产的**第一步**（先把离得远的目标项带进视口）本文件不执行、也不覆盖（见类 KDoc 的「不覆盖什么」）。
     */
    @Test
    fun `跳页后目标项中心落在预览区中心`() {
        val measured = measure(count = 12, target = 5)
        val itemCenter = measured.itemLeftPx.getValue(5) + measured.itemWidthPx.getValue(5) / 2f
        val viewportCenter = measured.viewportLeftPx + measured.viewportWidthPx / 2f
        assertTrue(
            "第 6 页中心 ${itemCenter}px 必须落在预览区中心 ${viewportCenter}px 上（±1px）",
            abs(itemCenter - viewportCenter) <= 1f,
        )
    }

    /**
     * **每格宽度不同**时（真机上宽度 = 图片高 × 该页真实比例，双页跨页那种宽页就是这种情形）：
     * 偏移必须用**目标项自己**的测量宽算。判别力：若改成用别的格子（或某个固定格宽）的宽度算，
     * 目标项中心会偏离预览区中心 20dp（远超 ±1px 容差）——目标项取第 3 页（初始可见，宽 120dp，
     * 其余 80dp），此时列表还能往回滚，因此不存在「到头贴边」的夹取干扰。
     */
    @Test
    fun `目标项比别的格子宽时 居中用目标项自己的宽`() {
        val measured = measure(count = 12, target = 2, widthFor = { index -> if (index == 2) 120.dp else 80.dp })
        val itemCenter = measured.itemLeftPx.getValue(2) + measured.itemWidthPx.getValue(2) / 2f
        val viewportCenter = measured.viewportLeftPx + measured.viewportWidthPx / 2f
        assertEquals("目标项宽度必须是它自己的 120dp", 120, measured.itemWidthPx.getValue(2))
        assertTrue(
            "第 3 页（120dp 宽，其余 80dp）中心 ${itemCenter}px 必须落在预览区中心 ${viewportCenter}px 上（±1px）",
            abs(itemCenter - viewportCenter) <= 1f,
        )
    }

    /** 首页：居中量为负、列表滚不动 ⇒ 目标项贴左缘且完整可见（AC17 的「首页除外」） */
    @Test
    fun `首页贴左缘且完整可见`() {
        val measured = measure(count = 12, target = 0)
        assertEquals("首页必须贴预览区左缘", measured.viewportLeftPx, measured.itemLeftPx.getValue(0))
        assertTrue(
            "首页必须完整可见（右缘不得越过预览区右缘）",
            measured.itemLeftPx.getValue(0) + measured.itemWidthPx.getValue(0) <=
                measured.viewportLeftPx + measured.viewportWidthPx,
        )
    }

    /** 末页：居中量被最大滚动量夹掉 ⇒ 目标项贴右缘且完整可见（AC17 的「末页除外」） */
    @Test
    fun `末页贴右缘且完整可见`() {
        val measured = measure(count = 12, target = 11)
        val itemRight = measured.itemLeftPx.getValue(11) + measured.itemWidthPx.getValue(11)
        assertEquals("末页右缘必须贴预览区右缘", measured.viewportLeftPx + measured.viewportWidthPx, itemRight)
        assertTrue("末页必须完整可见（左缘不得越过预览区左缘）", measured.itemLeftPx.getValue(11) >= measured.viewportLeftPx)
    }
}
