package com.cc3301.comicviewer.ui

import android.graphics.Bitmap
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 下拉指示器的环必须**显示拖动进度**（AC「一条用例钉住『进度取自可观察状态』」）。
 *
 * 钉住的是**取值出处**，不是几何：真组合生产代码 [PullToRefreshArea]、真发按下 + 拖动，每一段把整屏
 * 真渲染成位图、数**弧线色的像素**（弧线是 `ProgressIndicatorDefaults.circularColor`，底下的整圈轨道
 * 是另一种色，只有弧线会随进度变长）。
 *
 * 四条用例各钉一头：
 *
 * ① 「拖动中弧线随位移单调增长 到阈值满圈」——AC ① 的正题：进度真的落到画面上（弧线不画、进度钉死在
 *    某个常数上、或与位移脱节，这条红）。
 * ② 「刷新期间再往下拖 弧线仍停在满圈」——设备 AC「越过阈值 ⇒ 触发一次刷新、**刷新期间停在满圈**」
 *    那一条语义：刷新没结束前不回落（指示器位置照旧跟手指走）。
 * ③ 「未下拉时没有弧线」——先钉住「零位移 ⇒ 零像素」，否则①②可能拿背景当读数。
 * ④ 「满圈参照的像素数量级合理」——把参照口径本身（众数色 = 弧线色、满圈像素数）钉一道。
 *
 * **已知不覆盖（写明，避免读成全覆盖）：AC ③ 那种「把进度改回读非状态字段就必红」的用例在
 * Robolectric 里量不出来。** 本 bug 是「绘制节点不失效」，而像素采样必须**强制重绘**——强制重绘之后
 * 新老写法的读数相同，因此这一类回归在本测试环境里没有判别力（先后试过：拖动中取样、松手回弹取样；
 * 回弹是 spring 动画，而 Robolectric 的帧时钟不按设备推进——松手后位移 69px 在一个「测量 + idle」
 * 里直接落到 0，中间没有可读的帧）。该 bug 的回归由**设备验收**兜，也不再为此新增测试
 * 依赖。
 *
 * 夹具口径与 `CrossBookBarTest` 同一套：Robolectric + `@GraphicsMode(NATIVE)` 真渲染，
 * 屏幕限定符手机竖屏 mdpi（dp 与 px 一一对应，阈值 160dp = 160px）；「渲染一步 / 推到读数稳定」
 * 两个口在 `RobolectricComposeTestSupport`（[com.cc3301.comicviewer.ui.renderStep] /
 * [com.cc3301.comicviewer.ui.renderUntilStable]）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PullRefreshIndicatorProgressTest {

    private val viewportWidth = 411.dp
    private val viewportHeight = 891.dp

    private val density: Float = RuntimeEnvironment.getApplication().resources.displayMetrics.density
    private val viewportWidthPx: Int = (viewportWidth.value * density).roundToInt()
    private val viewportHeightPx: Int = (viewportHeight.value * density).roundToInt()

    /**
     * 扫描带高：指示器贴着下拉位移的**下沿**（位移 ≤ 阈值 160dp），弧线全部落在这一段里；
     * 只扫这一带省掉整屏扫描。
     */
    private val scanHeightPx = 220

    /** 弧线色的容差（弧线内圈像素是原色，边缘反锯齿像素与轨道混色 ⇒ 用容差把后者排除） */
    private val colorTolerance = 16

    // ---------- 组合、渲染、发事件 ----------

    /**
     * 组合生产代码 [PullToRefreshArea]（内容透明 ⇒ 位图里只有指示器），布局成整块视口。
     * [refreshing] 从外面给：应用里它由下拉触发（`BrowserScreen.refresh()`）置真，刷新期间手势照旧收
     * （没有屏蔽），因此「刷新期间又往下拖」是应用里真会发生的组合。
     */
    private fun composeArea(refreshing: Boolean = false): ComposeView {
        val view = composeViewInActivity {
            MaterialTheme {
                Box(
                    Modifier
                        .requiredWidth(viewportWidth)
                        .requiredHeight(viewportHeight),
                ) {
                    PullToRefreshArea(
                        atTop = { true },
                        refreshing = refreshing,
                        onRefresh = { },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // 透明内容：位图里画的只有指示器，数的像素因此只可能是指示器的
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
        view.layoutOnce(viewportWidthPx, viewportHeightPx)
        return view
    }

    /** 往 [view] 发一个触摸事件（坐标是 view 局部坐标）并推进一步 */
    private fun send(view: ComposeView, action: Int, y: Float, eventTime: Long) {
        val event = MotionEvent.obtain(0L, eventTime, action, view.width / 2f, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
        view.renderStep()
    }

    /** 从顶部按下，按 [stepPx] 逐段往下拖到 [totalPx] */
    private fun dragDown(view: ComposeView, totalPx: Float, stepPx: Float = 20f) {
        var eventTime = 0L
        send(view, MotionEvent.ACTION_DOWN, 0f, eventTime)
        var y = 0f
        while (y < totalPx) {
            y = min(y + stepPx, totalPx)
            eventTime += 16
            send(view, MotionEvent.ACTION_MOVE, y, eventTime)
        }
    }

    // ---------- 像素口径 ----------

    /** 与 [target] 逐通道接近（含 alpha）——反锯齿混色像素因此不计入 */
    private fun closeTo(pixel: Int, target: Int): Boolean {
        val dr = ((pixel shr 16) and 0xFF) - ((target shr 16) and 0xFF)
        val dg = ((pixel shr 8) and 0xFF) - ((target shr 8) and 0xFF)
        val db = (pixel and 0xFF) - (target and 0xFF)
        val da = (pixel ushr 24) - (target ushr 24)
        return abs(dr) <= colorTolerance && abs(dg) <= colorTolerance &&
            abs(db) <= colorTolerance && abs(da) <= colorTolerance
    }

    /** 位图上部扫描带里与 [color] 同色的像素数（= 弧线长度） */
    private fun coloredPixels(bitmap: Bitmap, color: Int): Int {
        var count = 0
        for (y in 0 until min(scanHeightPx, bitmap.height)) {
            for (x in 0 until bitmap.width) {
                if (closeTo(bitmap.getPixel(x, y), color)) count++
            }
        }
        return count
    }

    /**
     * 指示器的**可观察位移**（px）：环（含整圈轨道）在下拉方向上最低的墨迹点的 y。
     * 指示器上沿 = 位移 − 直径（`offset { IntOffset(0, shown - indicatorSizePx) }`），因此最低点就是位移本身。
     */
    private fun indicatorOffsetPx(bitmap: Bitmap): Int {
        for (y in min(scanHeightPx, bitmap.height) - 1 downTo 0) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) != 0) return y
            }
        }
        return 0
    }

    /**
     * 弧线色与**满圈**的像素数：拿同尺寸、同线宽、进度 1f 的**参照渲染**自己量出来——
     * 不从主题常量猜色（配色不在本次改动范围，改了颜色本用例不该红）。
     * 满圈时弧线覆盖整个环 ⇒ 非透明像素的众数色就是弧线色。
     */
    private fun fullRingReference(): Pair<Int, Int> {
        val view = composeViewInActivity {
            MaterialTheme {
                Box(Modifier.size(24.dp)) {
                    CircularProgressIndicator(
                        progress = { 1f },
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
        view.layoutOnce((24 * density).roundToInt())
        // 两帧：首帧的绘制请求落在下一轮（与 `RobolectricComposeTestSupport.layoutUntil` 同理）
        view.renderStep()
        val bitmap = view.renderStep()
        val histogram = HashMap<Int, Int>()
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (pixel == 0) continue
                histogram[pixel] = (histogram[pixel] ?: 0) + 1
            }
        }
        val mode = histogram.maxByOrNull { it.value }
            ?: error("参照渲染里没有像素：指示器没画出来，本用例的像素口径不成立")
        return mode.key to mode.value
    }

    // ---------- 用例 ----------

    /**
     * AC：拖动过程中环的弧线**随位移单调增长**，到阈值满圈。
     *
     * 检查点按**手指位移**给（mdpi 下 1dp = 1px，阈值 160dp = 160px；指示器位移 = 手指位移 × 阻尼 0.5）：
     * 40px（远未到）→ 140px（约半程）→ 340px（越过阈值 ⇒ 满圈）。三个位移互不相同，读数必须严格递增。
     */
    @Test
    fun `拖动中弧线随位移单调增长 到阈值满圈`() {
        val (arcColor, fullRingPixels) = fullRingReference()
        assertTrue("参照满圈必须有像素，否则本用例的口径不成立", fullRingPixels > 0)

        val view = composeArea()
        send(view, MotionEvent.ACTION_DOWN, 0f, 0L)
        val readings = mutableMapOf<Float, Int>()
        var eventTime = 0L
        for (y in listOf(40f, 80f, 120f, 140f, 200f, 280f, 340f)) {
            eventTime += 16
            send(view, MotionEvent.ACTION_MOVE, y, eventTime)
            readings[y] = view.renderUntilStable { coloredPixels(it, arcColor) }
        }

        val early = readings.getValue(40f)
        val middle = readings.getValue(140f)
        val pastThreshold = readings.getValue(340f)

        assertTrue(
            "刚拉开一小段（手指 40px）就该看得见弧线（实测 $early 像素）——0 说明进度没落到画面上",
            early > 0,
        )
        assertTrue(
            "弧线必须随位移增长：手指 40px = $early 像素 → 手指 140px = $middle 像素（相等/倒退说明" +
                "进度没跟着可观察位移走）",
            middle > early,
        )
        assertTrue(
            "弧线必须随位移持续增长：手指 140px = $middle 像素 → 手指 340px = $pastThreshold 像素",
            pastThreshold > middle,
        )
        assertTrue(
            "越过阈值（手指 340px）时弧线应到满圈：实测 $pastThreshold 像素，满圈参照 $fullRingPixels 像素",
            pastThreshold >= (fullRingPixels * 0.9f).roundToInt(),
        )
        assertTrue(
            "越过阈值后不该超过满圈（超出说明数的不是弧线本身）：实测 $pastThreshold 像素",
            pastThreshold <= (fullRingPixels * 1.15f).roundToInt(),
        )
    }

    /**
     * 刷新期间又往下拖这一格：**弧线仍停在满圈**（设备 AC「越过阈值 ⇒ 触发一次刷新、**刷新期间停在
     * 满圈**」）。指示器的**位置**照旧跟着手指走（既有行为，本次未动），但进度由 `refreshing` 这个真状态
     * 短路成满圈——刷新没结束就不回落。
     *
     * 说明：这条**不是** AC ③ 那种「改回读非状态字段就必红」的判别用例（那一类在 Robolectric 里
     * 量不出，见类注释）；它钉的是刷新期间那一条语义不被后来的改动弄丢。
     */
    @Test
    fun `刷新期间再往下拖 弧线仍停在满圈`() {
        val (arcColor, fullRingPixels) = fullRingReference()
        val view = composeArea(refreshing = true)

        // 前置：刷新期间指示器停在阈值处、弧线满圈
        val refreshOffset = indicatorOffsetPx(view.renderStep())
        val refreshArc = view.renderUntilStable { coloredPixels(it, arcColor) }
        assertTrue(
            "前置：刷新期间指示器应停在阈值处（实测 $refreshOffset px，阈值 ${REFRESH_THRESHOLD.value}dp）",
            abs(refreshOffset - (REFRESH_THRESHOLD.value * density).roundToInt()) <= 2,
        )
        assertTrue(
            "前置：刷新期间弧线应满圈（实测 $refreshArc / 满圈参照 $fullRingPixels）",
            refreshArc >= (fullRingPixels * 0.9f).roundToInt(),
        )

        // 刷新期间再往下拖到半程（手指 140px ⇒ 位移 70px）：位置跟手指，进度仍满圈
        dragDown(view, totalPx = 140f)
        val draggedOffset = indicatorOffsetPx(view.renderStep())
        val draggedArc = view.renderUntilStable { coloredPixels(it, arcColor) }

        assertTrue(
            "前置：刷新期间拖动时指示器的位置照旧跟着手指（实测位移 $draggedOffset px，期望约 70px）",
            abs(draggedOffset - 70) <= 4,
        )
        assertTrue(
            "刷新期间必须停在满圈（AC：刷新期间停在满圈）：实测 $draggedArc 像素，满圈参照 $fullRingPixels 像素" +
                "——回落说明刷新期间那条语义丢了",
            draggedArc >= (fullRingPixels * 0.9f).roundToInt(),
        )
        assertTrue(
            "满圈不该超出去（超出说明数的不是弧线本身）：实测 $draggedArc 像素",
            draggedArc <= (fullRingPixels * 1.15f).roundToInt(),
        )
    }

    /** 未下拉时不该有弧线（先钉住「零位移 ⇒ 零像素」，否则上面两条可能拿背景当读数） */
    @Test
    fun `未下拉时没有弧线`() {
        val (arcColor, _) = fullRingReference()
        val view = composeArea()
        send(view, MotionEvent.ACTION_DOWN, 0f, 0L)

        assertEquals(
            "位移为 0 时指示器还在组合之外，不该有任何弧线像素",
            0,
            coloredPixels(view.renderStep(), arcColor),
        )
    }

    /** 弧线像素数不该被「满圈参照」之外的常量影响：把参照口径本身钉一条（防众数算错的静默偏差） */
    @Test
    fun `满圈参照的像素数量级合理`() {
        val (_, fullRingPixels) = fullRingReference()
        val ringLengthPx = 2.0 * Math.PI * 11.0 * 2.0 // 半径 11dp（24dp 直径 − 2dp 线宽）、线宽 2dp
        assertTrue(
            "满圈弧线像素（实测 $fullRingPixels）应与「周长 × 线宽」同量级（约 $ringLengthPx）",
            fullRingPixels > ringLengthPx * 0.4 && fullRingPixels < ringLengthPx * 1.6,
        )
        assertTrue(
            "两个用例里用到的弧线像素口径都远小于整屏（${viewportWidthPx * scanHeightPx} 像素），" +
                "否则数的不是弧线",
            fullRingPixels < viewportWidthPx * scanHeightPx / 10,
        )
        assertTrue("满圈像素必须为正", max(fullRingPixels, 1) > 0)
    }
}
