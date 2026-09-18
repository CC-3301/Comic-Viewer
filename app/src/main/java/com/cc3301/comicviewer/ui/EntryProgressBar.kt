package com.cc3301.comicviewer.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.source.ReadingProgress
import com.cc3301.comicviewer.core.source.displayFraction
import com.cc3301.comicviewer.core.source.isCompleted

/** 进度条领域语义色（spec：部分填充绿=进行中，满格红=读完）——书柜（票 #31）同款复用 */
object ProgressColors {
    val InProgress = Color(0xFF43A047)
    val Completed = Color(0xFFE53935)
}

/** 条目底部阅读进度条（票 05；票 #31 书柜柜内复用）：未读时调用方不渲染 */
@Composable
fun EntryProgressBar(progress: ReadingProgress, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress.displayFraction },
        color = if (progress.isCompleted) ProgressColors.Completed else ProgressColors.InProgress,
        trackColor = Color.LightGray.copy(alpha = 0.3f),
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp),
        // 轨道末端不画 stop indicator（票 #48）：M3 1.3.0 默认会在轨道末端额外画一个
        // 与**进度色同色**的小圆点与一条间隙，未读完时就表现为「进度条最后边有颗绿点」。
        // 其余（填充段、轨道、4dp 高、圆角端帽、间隙）全部保持默认不变。
        drawStopIndicator = {},
    )
}
