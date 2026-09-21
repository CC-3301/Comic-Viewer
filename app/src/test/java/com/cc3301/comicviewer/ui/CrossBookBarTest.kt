package com.cc3301.comicviewer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.view.CrossBookBarLayout
import com.cc3301.comicviewer.core.view.ReaderOverlayLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 跨书确认条的**实测几何与真实点击行为**（票 #100）：真组合生产代码 [CrossBookBar]，真往 [ComposeView]
 * 发触摸事件，只按横/纵坐标点，看哪个回调被触发——把「按钮列与触摸区列逐像素对齐」「只有动作格可点」
 * 两条验收从「看着对」变成「量出来的」。
 *
 * 怎么测的：照搬 `ReaderMenuFooterTest`（票 #105）的路子——Robolectric 起 [ComponentActivity]，
 * 用一个填满视口的盒子（视口取手机竖屏 411×891dp，与 `readerOverlayInsets()` 同源）当屏幕，
 * 生产条贴在它的底部。命中区因此可以**逐像素扫描**出来：
 * - 纵向扫描：从屏幕顶逐像素点下去 → 「条吃掉点击」（不再关闭）的第一个 y 就是条的**点击面顶边**，
 *   也是黑底顶边；从它开始连续命中的 y 区间就是动作格命中区；
 * - 横向扫描：在动作格中点那一行逐像素点过去 → 命中区的横坐标区间与宽度。
 *
 * 判别力（两条验收各对应哪一条断言）：
 * ① **对齐**：「首页/末页方向动作格命中区 = 整个对应的三分之一」+「区内左缘/正中/右缘三点直直向下都命中」
 *    ——命中区边界一旦与触摸区分区（`touchZoneAt` 的 `w/3` / `2w/3`）差 1px 就变红；改动前的
 *    版式（`Row` + `SpaceBetween` + 28dp/22dp 内边距，按钮只有文字那么宽）在这两条上全红；
 * ② **只有动作格可点**：反向空白格与中格提示格各自三点「既不换书、也不关条」——把提示格接上任何
 *    动作（含顺手关条）都会变红；
 * ③ 条高/底部留白/条面覆盖：命中区高必须等于**条面高**（64dp 内容带 + 底部避让；沉浸态 88dp 时不是 64dp）、
 *    条面（黑七成八）顶边在屏底往上「64dp + 底部 inset」处、条面一路吃到屏幕左右边与底边（四角点都不关条）。
 * ④ **r2 根因：命中层原先只铺到 64dp 内容带**，而**视觉格**是整块条面（含底部避让那一截）——
 *    按钮格底部那 24dp 在真机上就是死区（既不换书也不关条），正是维护者 r2 报的
 *    「点击左右选区有时候无效」：从触摸区直直向下、落在按钮格下半段时不响应。
 *    因此纵向扫描必须一路扫到屏幕底边都对动作格命中。
 *
 * 像素那一条（`@GraphicsMode(NATIVE)` 真渲染 + 先让组合落定再取像素，见 [render]）补的是**条面底色**：
 * 条顶那一行整行都是黑七成八（整宽、无圆角——圆角会让两端角像素不是这个色）、屏底那一行同色
 * （条面铺到屏幕底边）、条顶上一行全透明（条面顶边就在条顶），并直接查 alpha = 0xC7（黑 0.78）；
 * 此外还查**文字的色族**（r3：中格位置标签改纯白、按钮格仍是强调橙）：中格里的墨迹只应是中性色族
 * （R≈G≈B，且真的到纯白），按钮格里只应是暖色族（R > G > B）——两种色在像素上各钉一道。
 * 这一条不受「首帧只画出一部分字」影响。
 *
 * 不覆盖的部分（写明，避免读成全覆盖）：① **文案落格的像素**没做成断言——Robolectric 里首帧的文字可能
 * 只画出一部分（同一实现多次运行量到的墨迹宽度不一致），非确定性不进门槛；探针量到的数（首页方向橙字
 * 墨迹 x=28..107、中心 67.5 ≈ W/6；末页方向 x=302..381、中心 341.5 ≈ 5W/6；中格白字中心 ≈ W/2）
 * 记在 `evidence-impl.md`，落格由纯函数接缝 + 代码结构 + 真机目视把守；
 * ② 真机四组合（手机/平板 × 竖/横）与横屏挖孔的对齐目视仍是最后一关（Robolectric 的窗口 inset 恒为 0，
 * 量不到挖孔真实存在时的落位）；③ 两个色值（中格位置标签白 / 按钮格橙）由生产常量
 * `CROSS_BOOK_LABEL_COLOR` / `CROSS_BOOK_ACTION_COLOR` 把守（AC：按钮配色不变），并由
 * `CrossBookBarStyleTest` 分别钉住；
 * ④ **批次 6 的文案垂直居中**（AC14）同样属「文字像素」：条面高 = 64dp + 底部 inset、文案在这整块条面里
 * 居中由 `Box(Alignment.BottomCenter)` + 内容 `fillMaxSize()` 的结构与 `CrossBookBarLayout`
 * （[CrossBookBarLayout.bandHeightDp] / [CrossBookBarLayout.labelCenterFromBottomDp]）把守，真机目视是最后一关；
 * ⑤ 文字的**垂直位置**仍不做像素断言（同一条理由：墨迹范围非确定性），但**色族**断言（④ 里那条暖色墨迹）
 * 与该非确定性无关：它只要求「存在暖色像素」，不依赖墨迹多少。
 */
@RunWith(RobolectricTestRunner::class)
// 屏幕限定符：本用例的视口是手机竖屏 411×891dp，Robolectric 默认屏幕只有 320×470dp，比场景小 ⇒
// 约束会被夹小、量到的几何不是本票的场景（density 固定 mdpi，dp 与 px 一一对应）
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-mdpi")
// 原生渲染：本票的「整宽纯黑、无圆角、文案落在哪一格」要在真像素上量（见 [render]）
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CrossBookBarTest {

    /** 视口（= 阅读页触摸区的三等分口径：两者都是这一整块屏幕的宽度） */
    private val viewportWidth = 411.dp
    private val viewportHeight = 891.dp

    private val density: Float = RuntimeEnvironment.getApplication().resources.displayMetrics.density
    private val viewportWidthPx: Int = (viewportWidth.value * density).roundToInt()
    private val viewportHeightPx: Int = (viewportHeight.value * density).roundToInt()

    // ---------- 组合与点击基建 ----------

    /** 一次组合的观测器：两个回调各被真实触发了几次 */
    private class Probe {
        var confirms = 0
        var dismisses = 0
    }

    /** 一次点击的结果：本次点击触发了哪个回调（两个都为 false = 点了没反应） */
    private class TapResult(val confirmed: Boolean, val dismissed: Boolean)

    /** 已布局好的一屏：探针、可发事件的 View、视口在窗口里的位置（用来把窗口坐标换算成 View 坐标） */
    private class Screen(val probe: Probe, val view: View, val viewport: Rect) {
        val width: Int get() = viewport.width.roundToInt()
        val height: Int get() = viewport.height.roundToInt()
    }

    /** 组合生产代码 [CrossBookBar] 并布局成整块视口（[forward] = 到边方向：true 走末页方向） */
    private fun compose(forward: Boolean): Screen {
        val probe = Probe()
        var viewport = Rect.Zero
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity)
        activity.setContentView(view)
        view.setContent {
            MaterialTheme {
                Box(
                    Modifier
                        .requiredWidth(viewportWidth)
                        .requiredHeight(viewportHeight)
                        .onGloballyPositioned { viewport = it.boundsInWindow() },
                ) {
                    CrossBookBar(
                        // 跨书条只读这三个字段（目标书 id 用合成名，脱敏）
                        state = CrossBookConfirm(
                            targetBookId = "sample-next-book",
                            currentLabel = if (forward) "最后一页" else "第一页",
                            actionLabel = if (forward) "下一本书" else "上一本书",
                            forward = forward,
                        ),
                        onConfirm = { probe.confirms++ },
                        onDismiss = { probe.dismisses++ },
                    )
                }
            }
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(viewportWidthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(viewportHeightPx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("跨书条没被放置（测量没生效），本次断言无意义", viewport.width > 0f)
        return Screen(probe, view, viewport)
    }

    /** 在窗口坐标 (x, y) 发一次按下 + 抬起（中间让主线程跑一轮，手势协程才看得见 UP） */
    private fun tap(screen: Screen, windowX: Float, windowY: Float) {
        val x = windowX - screen.viewport.left
        val y = windowY - screen.viewport.top
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, x, y, 0)
        screen.view.dispatchTouchEvent(down)
        shadowOf(Looper.getMainLooper()).idle()
        val up = MotionEvent.obtain(0L, 16L, MotionEvent.ACTION_UP, x, y, 0)
        screen.view.dispatchTouchEvent(up)
        shadowOf(Looper.getMainLooper()).idle()
        down.recycle()
        up.recycle()
    }

    /** 点一次并报告本次点击命中了哪个回调 */
    private fun tapAndRecord(screen: Screen, windowX: Float, windowY: Float): TapResult {
        val confirmsBefore = screen.probe.confirms
        val dismissesBefore = screen.probe.dismisses
        tap(screen, windowX, windowY)
        return TapResult(
            confirmed = screen.probe.confirms > confirmsBefore,
            dismissed = screen.probe.dismisses > dismissesBefore,
        )
    }

    /**
     * 条的**点击面顶边**（窗口 y）：屏底往上「条高 + 底部 inset」。阅读器是沉浸态、Robolectric 的
     * 窗口 inset 恒为 0，因此底部那一份走 [ReaderOverlayLayout.MIN_BOTTOM_DP] 的兜底
     * （与 `readerPanelInsets` 的算例同一个常量），黑底因此铺到屏幕底边。
     */
    private fun barTopY(screen: Screen): Int =
        screen.height -
            (CrossBookBarLayout.bandHeightDp(ReaderOverlayLayout.MIN_BOTTOM_DP) * density).roundToInt()

    /** 动作格命中区的纵向中点（窗口 y） */
    private fun actionCellMidY(screen: Screen): Float =
        screen.height - (CrossBookBarLayout.BAR_HEIGHT_DP / 2f + ReaderOverlayLayout.MIN_BOTTOM_DP) * density

    /** 纵向逐像素扫描一列的结果（[columnX] 这一列上）：条面顶边、命中区的 y 区间 */
    private class ColumnScan(val barTop: Int, val confirmTop: Int, val confirmBottom: Int) {
        val confirmHeight: Int get() = confirmBottom - confirmTop + 1
    }

    private fun scanColumn(screen: Screen, columnX: Float): ColumnScan {
        var barTop = -1
        var confirmTop = -1
        var confirmBottom = -1
        for (y in 0 until screen.height) {
            val hit = tapAndRecord(screen, columnX, y.toFloat())
            // 条面吃掉点击（不再关闭）的第一个 y = 条的点击面顶边 = 黑底顶边
            if (!hit.dismissed && barTop < 0) barTop = y
            if (hit.confirmed) {
                if (confirmTop < 0) confirmTop = y
                confirmBottom = y
            }
        }
        return ColumnScan(barTop, confirmTop, confirmBottom)
    }

    /** 横向逐像素扫描一行的结果（[rowY] 这一行上）：命中区的 x 闭区间与宽度、以及关条次数 */
    private class RowScan(val confirmFirst: Int, val confirmLast: Int, val confirmCount: Int, val dismissCount: Int)

    private fun scanRow(screen: Screen, rowY: Float): RowScan {
        var first = -1
        var last = -1
        var confirms = 0
        var dismisses = 0
        for (x in 0 until screen.width) {
            val hit = tapAndRecord(screen, x.toFloat(), rowY)
            if (hit.confirmed) {
                if (first < 0) first = x
                last = x
                confirms++
            }
            if (hit.dismissed) dismisses++
        }
        return RowScan(first, last, confirms, dismisses)
    }

    // ---------- 像素：条面底色（r2 = 黑七成八）与文字色族 ----------

    /**
     * 条面的**实测像素值**：黑 [CrossBookBarLayout.BAR_ALPHA]（r2 口径 = 黑 0.78）。
     * 不写字面量：底色口径只有生产常量一处来源，改了常量这里跟着变。
     */
    private val barColor = Color.Black.copy(alpha = CrossBookBarLayout.BAR_ALPHA).toArgb()

    /** 黑七成八的 alpha 通道（255 × 0.78 = 198.9 → 199 = 0xC7） */
    private val barAlpha = (255 * CrossBookBarLayout.BAR_ALPHA).roundToInt()

    /**
     * 墨迹统计：一块区域里的像素分类（都是「窗口坐标」下的一整块）。
     *
     * [ink] = 既不是完全透明也不是条底的像素数（即画上去的东西），[warm] = 其中**暖色**
     * （R > G > B，强调橙的色族），[neutral] = 其中**中性**（R ≈ G ≈ B，白/灰/黑的色族），
     * [white] = 其中**接近纯白**的（三个通道都 ≥ 240：把白与灰分开——中格位置标签是白，灰字不会被
     * 数成白）。把「字是什么颜色」变成色族判定：不看墨迹多少，只看色相/亮度——因此不受 Robolectric
     * 首帧只画出一部分字的影响（见文件头）。
     */
    private class InkStats(val ink: Int, val warm: Int, val neutral: Int, val white: Int)

    /** 一次真渲染的像素观测（坐标都按窗口口径，与 [Screen.viewport] 同一套） */
    private class Rendered(val bitmap: Bitmap, val origin: Rect, val barColor: Int) {
        /** 窗口 y 这一整行是否全为 [color] */
        fun rowIs(windowY: Float, color: Int): Boolean {
            val y = (windowY - origin.top).roundToInt()
            return (0 until bitmap.width).all { bitmap.getPixel(it, y) == color }
        }

        /** 窗口 y 这一整行是否全透明（条面没铺到这里） */
        fun rowIsTransparent(windowY: Float): Boolean = rowIs(windowY, 0)

        /** 窗口 y 这一行首像素的 alpha（用来查「不是纯黑、也不是全透明」） */
        fun rowAlpha(windowY: Float): Int {
            val y = (windowY - origin.top).roundToInt()
            return bitmap.getPixel(0, y) ushr 24
        }

        /**
         * 一块区域（窗口坐标，x/y 都是左闭右开）里的墨迹统计：非条底像素按色族分类（见 [InkStats]）。
         * 条底自身的像素（[barColor]）与完全透明的像素不计入墨迹。
         */
        fun inkStats(windowX0: Int, windowX1: Int, windowY0: Int, windowY1: Int): InkStats {
            var ink = 0
            var warm = 0
            var neutral = 0
            var white = 0
            for (wy in windowY0 until windowY1) {
                val y = (wy - origin.top).roundToInt()
                if (y < 0 || y >= bitmap.height) continue
                for (wx in windowX0 until windowX1) {
                    val x = (wx - origin.left).roundToInt()
                    if (x < 0 || x >= bitmap.width) continue
                    val pixel = bitmap.getPixel(x, y)
                    if (pixel == 0 || pixel == barColor) continue
                    ink++
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    if (r > g && g > b && r - b >= 16) warm++ else if (abs(r - g) <= 8 && abs(g - b) <= 8) neutral++
                    if (minOf(r, g, b) >= 240) white++
                }
            }
            return InkStats(ink, warm, neutral, white)
        }
    }

    /**
     * 把整屏真渲染成一张位图（Robolectric 原生渲染，类上的 `@GraphicsMode(NATIVE)` 开的）。
     *
     * 画之前先让组合落定（invalidate + requestLayout + 两轮 idle）：刚布局完就 `draw()` 时位图还是空的
     * （Compose 的绘制挂在下一帧上），直接取样会把「没画」读成「不是黑」；本仓没有别的用例这么做，
     * 这段注释说明为什么需要它。像素断言分两类：底色的**逐像素等值**（几何 + 背景色）与文字的**色族**
     * （见 [Rendered.inkStats]）——前者要求像素值完全相等，后者只看色相，因此不受「首帧文字可能只画出
     * 一部分、墨迹多少非确定性」的影响（见 evidence-impl.md）；文字的**垂直位置**仍不进门槛。
     */
    private fun render(screen: Screen): Rendered {
        screen.view.invalidate()
        screen.view.requestLayout()
        repeat(2) { shadowOf(Looper.getMainLooper()).idle() }
        val bitmap = Bitmap.createBitmap(screen.view.width, screen.view.height, Bitmap.Config.ARGB_8888)
        screen.view.draw(Canvas(bitmap))
        return Rendered(bitmap, screen.viewport, barColor)
    }

    // ---------- 条高与条面覆盖 ----------

    @Test
    fun `动作格命中区 = 视觉格整格 从条顶一路到屏幕底边 黑底从屏底往上 条高 加 底部 inset`() {
        val screen = compose(forward = false)
        // 列取左格正中：首页方向它就是动作格
        val scan = scanColumn(screen, columnX = screen.width / 6f)
        assertEquals("条的点击面（黑底）顶边应在屏底往上 64dp + 底部 inset", barTopY(screen), scan.barTop)
        assertEquals("动作格命中区顶边应贴条顶", scan.barTop, scan.confirmTop)
        assertEquals(
            "r2 根因：命中区必须一直铺到屏幕底边（视觉格整格可点）——原先只到 64dp 内容带，" +
                "按钮格下半段是死区",
            screen.height - 1,
            scan.confirmBottom,
        )
        assertEquals(
            "命中区高 = 条面高 = 64dp 内容带 + 底部避让（不是只到 64dp）",
            (CrossBookBarLayout.bandHeightDp(ReaderOverlayLayout.MIN_BOTTOM_DP) * density).roundToInt(),
            scan.confirmHeight,
        )
        assertTrue(
            "命中区必须比 64dp 内容带高（否则按钮格底部那一截又成了死区）",
            scan.confirmHeight > (CrossBookBarLayout.BAR_HEIGHT_DP * density).roundToInt(),
        )
    }

    @Test
    fun `按钮格最底那一行也命中 从触摸区直直向下落到底不落空`() {
        // r2 真机反馈「点击左右选区有时候无效」：手指从触摸区直直向下移到底（屏幕最下一行）时
        // 必须仍然命中按钮格。两个方向、每方向区内左缘/正中/右缘三点都试。
        val first = compose(forward = false)
        val bottomY = (first.height - 1).toFloat()
        for (x in listOf(1f, first.width / 6f, first.width / 3f - 1f)) {
            val hit = tapAndRecord(first, x, bottomY)
            assertTrue("首页方向：屏幕最下一行 x=$x 仍应命中左格按钮", hit.confirmed)
            assertFalse("命中按钮不该顺手关条", hit.dismissed)
        }
        val last = compose(forward = true)
        for (x in listOf(last.width - 1f, last.width * 5f / 6f, last.width * 2f / 3f)) {
            val hit = tapAndRecord(last, x, (last.height - 1).toFloat())
            assertTrue("末页方向：屏幕最下一行 x=$x 仍应命中右格按钮", hit.confirmed)
            assertFalse("命中按钮不该顺手关条", hit.dismissed)
        }
    }

    @Test
    fun `黑底铺满屏幕左右边与底边 条面点击无缺口`() {
        val screen = compose(forward = false)
        val barTop = barTopY(screen).toFloat()
        val bottom = (screen.height - 1).toFloat()
        // 条的四角：右列（首页方向的反向空白格）与底部 inset 那一段黑底
        for ((x, y) in listOf(
            (screen.width - 1f) to barTop,
            (screen.width - 1f) to bottom,
            0f to bottom,
        )) {
            val hit = tapAndRecord(screen, x, y)
            assertFalse("($x, $y) 属于整宽黑底，点它不该关条（条面一路铺到屏幕左右边与底边）", hit.dismissed)
        }
    }

    // ---------- 对齐：命中区 = 触摸区对应的那一格 ----------

    @Test
    fun `首页方向 命中区横跨整个左三分之一 边界与触摸区分区逐像素同源`() {
        val screen = compose(forward = false)
        val row = scanRow(screen, rowY = actionCellMidY(screen))
        assertEquals("命中区左缘应贴屏幕左缘", 0, row.confirmFirst)
        assertEquals(
            "命中区右缘应落在触摸区左/中分界（w/3）内侧 1px",
            ceil(screen.width / 3f).toInt() - 1,
            row.confirmLast,
        )
        assertEquals(
            "命中区宽度必须 ≥ 1/3 视口宽（不是只有文字那么宽）",
            ceil(screen.width / 3f).toInt(),
            row.confirmCount,
        )
        assertTrue("命中区宽度应达到 1/3 视口宽", row.confirmCount >= screen.width / 3)
        assertEquals("中格与右格一律不动作也不关条", 0, row.dismissCount)
    }

    @Test
    fun `末页方向 命中区横跨整个右三分之一 边界与触摸区分区逐像素同源`() {
        val screen = compose(forward = true)
        val row = scanRow(screen, rowY = actionCellMidY(screen))
        assertEquals(
            "命中区左缘应落在触摸区右区分界（2w/3）上",
            ceil(screen.width * 2f / 3f).toInt(),
            row.confirmFirst,
        )
        assertEquals("命中区右缘应贴屏幕右缘", screen.width - 1, row.confirmLast)
        assertEquals(
            "命中区宽度必须 ≥ 1/3 视口宽",
            ceil(screen.width / 3f).toInt(),
            row.confirmCount,
        )
        assertEquals("中格与左格一律不动作也不关条", 0, row.dismissCount)
    }

    @Test
    fun `每侧取区内左缘 正中 右缘三点 直直向下都落在动作格命中区内`() {
        // 首页方向：左触摸区的三点（区内左缘内侧 1px / 正中 / 右缘内侧 1px）× 命中区上/中/下三档 y
        val first = compose(forward = false)
        val firstRows = listOf(
            barTopY(first).toFloat(),                          // 条顶（内容带顶）
            actionCellMidY(first),                             // 内容带正中
            (first.height - 1).toFloat(),                      // 屏幕最下一行（底部避让那一截）
        )
        for (y in firstRows) {
            for (x in listOf(1f, first.width / 6f, first.width / 3f - 1f)) {
                val hit = tapAndRecord(first, x, y)
                assertTrue("首页方向：左触摸区内 x=$x 直直向下（y=$y）应命中左格按钮", hit.confirmed)
                assertFalse("命中按钮不该顺手关条", hit.dismissed)
            }
        }
        // 末页方向：右触摸区的三点（同一批三档 y）
        val last = compose(forward = true)
        val lastRows = listOf(
            barTopY(last).toFloat(),
            actionCellMidY(last),
            (last.height - 1).toFloat(),
        )
        for (y in lastRows) {
            for (x in listOf(last.width - 1f, last.width * 5f / 6f, last.width * 2f / 3f)) {
                val hit = tapAndRecord(last, x, y)
                assertTrue("末页方向：右触摸区内 x=$x 直直向下（y=$y）应命中右格按钮", hit.confirmed)
                assertFalse("命中按钮不该顺手关条", hit.dismissed)
            }
        }
    }

    // ---------- 只有动作格可点 ----------

    @Test
    fun `中格提示格与反向空白格都不动作也不关条`() {
        // 首页方向：动作格在左格，中格（提示格）与右格（反向空白格）都不该有任何动作。
        // y 取三档：条顶 / 内容带正中 / 屏幕最下一行（r2 后整块条面都在命中层上，两格在底部避让那一截也得不动作）
        val screen = compose(forward = false)
        val inertXs = listOf(
            screen.width / 3f, screen.width / 2f, screen.width * 2f / 3f - 1f, // 中格三点
            screen.width * 2f / 3f, screen.width * 5f / 6f, screen.width - 1f, // 反向空白格三点
        )
        for (y in listOf(barTopY(screen).toFloat(), actionCellMidY(screen), (screen.height - 1).toFloat())) {
            for (x in inertXs) {
                val hit = tapAndRecord(screen, x, y)
                assertFalse("x=$x y=$y 不该换书（首页方向只有左格是按钮）", hit.confirmed)
                assertFalse("x=$x y=$y 不该关条（提示格/反向格不做点击触发）", hit.dismissed)
            }
        }
    }

    @Test
    fun `条外点击关闭条且不跳转`() {
        val screen = compose(forward = false)
        for (y in listOf(40f, barTopY(screen) - 1f)) {
            val hit = tapAndRecord(screen, screen.width / 2f, y)
            assertTrue("条外 y=$y 点击应关闭确认条（两段式语义不变）", hit.dismissed)
            assertFalse("条外点击不该换书", hit.confirmed)
        }
    }

    // ---------- 像素：条面底色与覆盖 ----------

    @Test
    fun `条面底色为黑七成八 整宽无圆角 铺到屏幕底边 实测像素`() {
        val screen = compose(forward = false)
        val rendered = render(screen)
        val barTop = barTopY(screen).toFloat()
        assertTrue(
            "条顶那一行（y=$barTop）必须整行都是黑七成八：整宽、无圆角（圆角会让两端的角像素不是这个色）",
            rendered.rowIs(barTop, barColor),
        )
        assertTrue(
            "屏底那一行也必须整行都是黑七成八：条面铺到屏幕底边",
            rendered.rowIs(screen.height - 1f, barColor),
        )
        assertEquals(
            "条面 alpha 必须与生产常量一致（黑 0.78 → 0xC7）——不是纯黑、也不是全透明",
            barAlpha,
            rendered.rowAlpha(barTop),
        )
        assertTrue("不能是纯黑：alpha 必须明显小于 255", rendered.rowAlpha(barTop) < 255)
        assertTrue("不能全透明：alpha 必须大于 0", rendered.rowAlpha(barTop) > 0)
        assertTrue("条顶上一行必须全透明：条面顶边就在条顶", rendered.rowIsTransparent(barTop - 1f))
    }

    @Test
    fun `中格位置标签是白 按钮格是橙 两种色各钉一道 实测像素`() {
        // r3：中格「第一页」改纯白、按钮格仍是强调橙（两者不再混色）。字体墨迹的**多少**在 Robolectric
        // 里不确定（见文件头），但**色族**不受影响：白字只留中性像素（R≈G≈B 且真的到纯白），
        // 橙字只留暖色像素（R > G > B）。
        val screen = compose(forward = false)
        val rendered = render(screen)
        val barTop = barTopY(screen)
        val bottom = screen.height - 1
        val midLeft = screen.width / 3 + 8
        val midRight = screen.width * 2 / 3 - 8
        val mid = rendered.inkStats(midLeft, midRight, barTop + 2, bottom - 1)
        assertTrue("中格应有字（否则本断言无意义）", mid.ink > 0)
        assertTrue("中格「第一页」的墨迹必须是中性色族（纯白）；橙字在这里只会留下暖色像素", mid.neutral > 0)
        assertTrue("中格墨迹里中性的应占多数", mid.neutral > mid.warm)
        assertEquals("中格区里不该有暖色像素：橙色只属于按钮格（r3 口径）", 0, mid.warm)
        assertTrue("中格墨迹必须真的到纯白（三通道都 ≥ 240），灰字不会有这种像素", mid.white > 0)
        // 动作格（同一方向的左格）仍是暖色橙：AC「按钮文案与配色不变」在像素上也钉一道
        val action = rendered.inkStats(8, screen.width / 3 - 8, barTop + 2, bottom - 1)
        assertTrue("左格应有字（否则本断言无意义）", action.ink > 0)
        assertTrue("左格「上一本书」的墨迹必须是暖色橙", action.warm > 0)
        assertTrue("左格墨迹里暖色的应占多数", action.warm > action.neutral)
        assertEquals("按钮格不该出现纯白像素：白色只属于中格位置标签（r3 口径）", 0, action.white)
    }

}
