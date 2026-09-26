package com.cc3301.comicviewer.ui

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * `ui` 包的 Robolectric 组合测量脚手架（票 #115 起定形，票 #124 收口）：把「起 [ComponentActivity] → 挂 [ComposeView]
 * → measure → layout → idle」合成一份。票 #115 只把 #61/#67/#79 三处 + PreviewStrip 两处搬了过来；
 * 票 #124 把 `ui` 包**其余 10 处**手写副本（`EntryNameTextTest` / `BrowseRowWidthTest` / `BrowseItemCountTest` /
 * `BrowseScrollRestoreTest` / `CrossBookBarTest` / `GridCellNameVisibleTest` / `GridProgressScrimTest` /
 * `ReaderMenuFooterTest` ×2 / `ReaderMenuTitleLineCountTest` / `SeekSliderTapTest`）一并收进来——
 * 现在 `ui` 测试源集里除本文件外**没有**第二处 `measure`+`layout`+`idle` 序列。
 * 换测量方式（改 idle 口径、换测量驱动）从此只改这一处，不再各处漏改。
 *
 * 放在测试源集、`ui` 包内：只有 ui 的 Robolectric 测试用得到它，生产侧不需要这个概念。
 */

/** 起一个 [ComponentActivity] 并把 [content] 组合进它的 [ComposeView]（挂载顺序与原三处脚手架一致） */
internal fun composeViewInActivity(content: @Composable () -> Unit): ComposeView {
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val view = ComposeView(activity)
    activity.setContentView(view)
    view.setContent(content)
    return view
}

/**
 * 跑一轮「测量 → 布局 → idle」：[widthPx] 是 EXACTLY 宽度；[heightPx] 为空 = 高度 UNSPECIFIED(0)
 * （内容自撑高，原来的三处取法）。Compose 的放置请求在后一轮布局里生效，需要多轮时重复调用。
 */
internal fun ComposeView.layoutOnce(widthPx: Int, heightPx: Int? = null) {
    val heightSpec = if (heightPx == null) {
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    } else {
        View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
    }
    measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), heightSpec)
    layout(0, 0, measuredWidth, measuredHeight)
    shadowOf(Looper.getMainLooper()).idle()
}

/**
 * 跑到 [condition] 成立为止的**有界等待**：每轮 [layoutOnce]，最多 [maxRounds] 轮。
 *
 * 为什么要有它（票 #139）：一轮「测量 → 布局 → idle」**不保证** Compose 的时机已经走到位。实测——
 * `DisposableEffect.onDispose` 这类**回调**里写下的值落在 **idle 段**：`measure` + `layout` 跑完它还没落地，
 * 同一轮的 `idle()` 才轮到它（探针：`raw measure+layout` 后仍是初值 `-1`，再 `idle()` 才变终值）。
 * 机器一忙（全量跑上百个测试类）那一轮就可能没轮到 ⇒ 断言跑在回调之前。所以断言前要**轮询到条件成立**，
 * 而不是假设一轮就够。
 *
 * 到 [maxRounds] 仍不成立**不抛异常、也不返回失败标记**：由调用点的断言照旧失败（`-1` 的失败语义不动），
 * 等待只把「假设一轮」换成「有界等待」。
 */
internal fun ComposeView.layoutUntil(
    widthPx: Int,
    heightPx: Int? = null,
    maxRounds: Int = 50,
    condition: () -> Boolean,
) {
    repeat(maxRounds) {
        layoutOnce(widthPx, heightPx)
        if (condition()) return
    }
}
