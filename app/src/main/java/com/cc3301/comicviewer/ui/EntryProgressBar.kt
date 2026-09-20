package com.cc3301.comicviewer.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.source.ReadingProgress
import com.cc3301.comicviewer.core.source.displayFraction
import com.cc3301.comicviewer.core.source.isCompleted

/** 进度条领域语义色（spec：部分填充绿=进行中，满格红=读完）——书柜（票 #31）同款复用 */
object ProgressColors {
    val InProgress = Color(0xFF43A047)
    val Completed = Color(0xFFE53935)
}

/**
 * 进度条的高度（票 #92 需求 3）：**6dp**，唯一读取点是 [EntryProgressBar] 的 `.height(...)`。
 * 网格档里它同时就是条遮住封面的画面高度，列表档里它是名称下方那条的厚度。
 */
internal val PROGRESS_BAR_HEIGHT = 6.dp

/**
 * 轨道色（票 #92 需求 3）：**不透明**灰。网格档的条压在封面画面上，带 alpha 的轨道会透出底图，
 * 看起来像封面破了道口子；列表档同样用不透明灰（两档样式一致）。
 */
internal val PROGRESS_TRACK_COLOR = Color.LightGray

/**
 * 端帽（票 #92 需求 3）：**方头**。M3 `LinearProgressIndicator` 默认是 `StrokeCap.Round`
 * （`ProgressIndicatorDefaults.LinearStrokeCap`），圆头会在条的两端留出极小空白、压不住封面这个矩形。
 *
 * 该默认值可去掉（已核对 M3 1.3.0 源码 `ProgressIndicator.kt`：`strokeCap` 是公开参数，`drawLinearIndicator`
 * 在 `StrokeCap.Butt` 时直接 `drawLine(...)`，不做「内缩半个线宽」的端帽补偿），因此不需要自绘。
 */
internal val PROGRESS_BAR_STROKE_CAP = StrokeCap.Butt

/**
 * 条目进度条（票 05）：未读时调用方不渲染（两档都不画条、也不留空位）。
 * 位置由调用方给：网格档叠在封面下缘（`Alignment.BottomCenter`）；列表档在名称下方，
 * 左缘贴封面右缘、右缘到条目右缘（见 `BrowserScreen.kt` 的 `BrowseRow`）。
 */
@Composable
fun EntryProgressBar(progress: ReadingProgress, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress.displayFraction },
        color = if (progress.isCompleted) ProgressColors.Completed else ProgressColors.InProgress,
        trackColor = PROGRESS_TRACK_COLOR,
        strokeCap = PROGRESS_BAR_STROKE_CAP,
        // 轨道与填充段之间不留缝（票 #92 需求 3）：M3 默认 gapSize = 4dp 会在两段之间留一条
        // **谁都不画**的带（源码：trackStartFraction = progress + min(progress, gapSizeFraction)），
        // 网格档压在封面画面上就会透出底图。
        gapSize = 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(PROGRESS_BAR_HEIGHT),
        // 停点不画（票 #48 口径保留）：默认会在轨道末端额外画一个与进度色同色的小圆点与一条间隙。
        drawStopIndicator = {},
    )
}
