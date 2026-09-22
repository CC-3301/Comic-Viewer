package com.cc3301.comicviewer.ui

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.view.ReaderOverlayLayout
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
 * 贴底浮层在**沉浸态**（系统栏隐藏、inset 全 0）下的底部放置（票 #61 AC3）：真量「浮层内容底 → 窗口底」的距离。
 *
 * 怎么测的：照搬 `ReaderMenuTitleTest` 的路子——Robolectric 起 [ComponentActivity]，把**生产代码**
 * [readerPanelInsets]（即**阅读菜单面板**那一份 inset，票 #67 从共用口径 `readerOverlayInsets` 里收窄出左/右/下三边）
 * 交给 `windowInsetsPadding`，读 `boundsInWindow()` 报上来的真实放置框。Robolectric 的窗口 inset 恒为全 0
 * （系统栏隐藏 + 无挖孔的等价环境），因此这里量的正是「栏隐藏后还剩多少底部留白」——这个数只有生产中那条兜底
 * [ReaderOverlayLayout.overlayBottomPx] 提供得了。
 *
 * 覆盖范围（只说本文件真正量到的）：**只量菜单面板这一份**。跨书确认条走的是共用口径 `readerOverlayInsets()`
 * （`ReaderScreen.kt` 的贴底确认条），它这一份不在这里测——底部口径对两份是同一个函数
 * （`readerPanelInsets()` 只是把它的上边去掉），因此底部留白由**纯函数用例** `ReaderOverlayLayoutTest`
 * 连同确认条那份一并钉住；本文件只额外证明「这份口径真的接到了放置上」。
 *
 * 判别力：去掉 `readerOverlayInsets` 里的兜底（退回「系统栏 ∪ 挖孔」）时 inset 为 0，实测距离立刻变 0、断言变红。
 *
 * 不覆盖（写明，避免读成全覆盖）：① 真机上系统栏隐藏后 inset 真的塌成 0、以及手势条是否压住按钮——平台行为，
 * Robolectric 探不到（`rootWindowInsets` 恒为全 0），由真机验收兜住（AC5）；
 * ② 「可见支」（栏占位时用真实 inset）在 Robolectric 里造不出来（inset 不可能非 0）——那一支由
 * `ReaderOverlayLayoutTest` 的纯函数用例钉住，本文件只量「栏缺席（inset 全 0）」那一支；
 * ③ 跨书确认条那一份（`readerOverlayInsets()` 四边）在本文件里没有测；
 * ④ 横屏挖孔在左/右时面板不被切（横向 inset 本票未改，属票 #44 的既有口径）；
 * ⑤ 菜单面板与确认条各自的其它排版（票 #66/#67 的字号与顶部留白由 `ReaderMenuTitleTest` 等各自把守）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderOverlayInsetsTest {

    private val metrics = RuntimeEnvironment.getApplication().resources.displayMetrics
    private val density: Float = metrics.density

    /** 一次测量的结果（px）：窗口（组合根）底缘与贴底浮层内容底缘 */
    private data class Measured(val rootBottom: Int, val contentBottom: Int) {
        val gap: Int get() = rootBottom - contentBottom
    }

    /** 组合一段「贴底 + 消费 [readerPanelInsets]」的盒子并真量一次底部距离 */
    private fun measureBottomGap(): Measured {
        var rootBottom = -1
        var contentBottom = -1
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity)
        activity.setContentView(view)
        view.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { rootBottom = it.boundsInWindow().bottom.roundToInt() },
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(40.dp)
                            .windowInsetsPadding(readerPanelInsets()),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .onGloballyPositioned {
                                    contentBottom = it.boundsInWindow().bottom.roundToInt()
                                },
                        )
                    }
                }
            }
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "浮层没被放置（测量没生效），本次断言无意义",
            rootBottom >= 0 && contentBottom >= 0,
        )
        return Measured(rootBottom, contentBottom)
    }

    /**
     * 第 13 轮（维护者裁决 C）：阅读菜单面板的 inset 只剩**左/右**——面板底边贴窗口下缘
     * （AC18 的下段 = 18 + 18 + 0 靠这一条成立），所以 `readerPanelInsets()` 的底部留白恒为 0px。
     * 兜底常量 [ReaderOverlayLayout.MIN_BOTTOM_DP] 没有被删：它仍守着**跨书确认条**那条路
     * （`readerOverlayInsets()`，见 `ReaderOverlayLayoutTest` / `CrossBookBarTest`）。
     */
    @Test
    fun `面板 inset 不消费底部 底边贴窗口下缘`() {
        val measured = measureBottomGap()

        assertEquals(
            "面板不再消费底部 inset（裁决 C）：底部留白必须是 0px（density $density、实测 ${measured.gap}px）",
            0,
            measured.gap,
        )
        assertTrue("兜底常量仍由跨书确认条那条路消费", ReaderOverlayLayout.MIN_BOTTOM_DP > 0f)
    }
}
