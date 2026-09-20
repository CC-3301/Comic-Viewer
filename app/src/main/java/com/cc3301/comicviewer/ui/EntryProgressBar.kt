package com.cc3301.comicviewer.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
 * 轨道不透明度（票 #92 需求 3，单一来源）：轨道色 = 主题 `onSurface` 乘本值（维护者 2026-09-20 从渲染预览的
 * 4 档中选定 1 号）。固定的灰度值在**浅色主题偏亮、深色主题偏暗**（维护者原话「在黑色主题太黑，在浅色主题又太亮」），
 * 跟着主题走才能两套主题下都与底色成同一比例。
 *
 * 本值 < 1 **是有意为之**（票 #92 r6 取代 r4 的「轨道必须不透明」）：允许轨道透出底图，压在封面画面上也能看出位置。
 */
internal const val PROGRESS_TRACK_ALPHA = 0.30f

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
 * 位置由调用方给：网格档叠在封面下缘（`Alignment.BottomCenter`）；列表档在**名称正下方**、与名称左缘对齐
 * （在名称那一列内；见 `BrowserScreen.kt` 的 `BrowseRow`）。
 */
@Composable
fun EntryProgressBar(progress: ReadingProgress, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress.displayFraction },
        color = if (progress.isCompleted) ProgressColors.Completed else ProgressColors.InProgress,
        // 轨道跟主题走（票 #92 需求 3）：onSurface 乘 PROGRESS_TRACK_ALPHA；色值不写死，避免深浅主题各偏一头
        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = PROGRESS_TRACK_ALPHA),
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
