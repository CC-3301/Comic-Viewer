package com.cc3301.comicviewer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
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
 * 进度条区域的高度（票 #92 需求 2）：进度条本身与网格档「无进度时的占位」共用这一份。
 * 两处若各写一个数，同排有进度 / 无进度的格子就会差出一条进度条的高度、行内元素错位，
 * 因此高度只留一个来源（[EntryProgressBarTest] 钉住这个值）。
 */
internal val PROGRESS_BAR_HEIGHT = 4.dp

/**
 * 格子内进度条区域的高度（票 #92 需求 2）：**与有没有进度无关**——需求 2 的全部内容就是这一句。
 *
 * [hasProgress] 只表达契约、不参与计算：无进度时也要留出与有进度时完全相同的高度，退回「有才画」
 * 的写法会让同排格子差出一条进度条的高度（外加一段 6dp 间距）。写成函数是为了让这条口径能被
 * [EntryProgressBarTest] 直接钉住，而不是只写在注释里。
 */
internal fun progressSlotHeight(hasProgress: Boolean): Dp = PROGRESS_BAR_HEIGHT

/** 条目底部阅读进度条（票 05；票 #31 书柜柜内复用）：未读时调用方不渲染 */
@Composable
fun EntryProgressBar(progress: ReadingProgress, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress.displayFraction },
        color = if (progress.isCompleted) ProgressColors.Completed else ProgressColors.InProgress,
        trackColor = Color.LightGray.copy(alpha = 0.3f),
        modifier = modifier
            .fillMaxWidth()
            .height(PROGRESS_BAR_HEIGHT),
        // 轨道末端不画 stop indicator（票 #48）：M3 1.3.0 默认会在轨道末端额外画一个
        // 与**进度色同色**的小圆点与一条间隙，未读完时就表现为「进度条最后边有颗绿点」。
        // 其余（填充段、轨道、4dp 高、圆角端帽、间隙）全部保持默认不变。
        drawStopIndicator = {},
    )
}

/**
 * 网格档格子底部的「进度条区域」（票 #92 需求 2）：**不论有没有进度都占同一高度**。
 *
 * 有进度 = 画 [EntryProgressBar]；无进度 = 只留同高的空位，**不画轨道**——空轨道会被读成
 * 「这本书进度为 0」，而这里要表达的只是「这本书没有进度」。
 *
 * 对齐边界（按实现写，不含超出的承诺）：格子里的进度条区域顶边 = 封面高 + 6dp + 名称块高 + 6dp，
 * 其中封面高由共用的格宽算出、逐格相同，名称块高 = 名称行数 × 行高。因此**同排名称行数相同时**
 * 进度条区域顶边逐格对齐（改动前：有进度的格子多出 4dp 条 + 6dp 间距）；名称 1 行 / 2 行导致的
 * 差异是既有的「名称最多两行」行为，票 #92 未动（待维护者另定）。
 *
 * 列表档的行不走本件（`BrowserScreen.kt` 的 `BrowseRow` 仍是「有才画」）：列表行高还受名称 1/2 行影响，
 * 属另一套口径，票 #92 明确不动它。
 */
@Composable
internal fun EntryProgressSlot(progress: ReadingProgress?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(progressSlotHeight(progress != null)),
    ) {
        if (progress != null) EntryProgressBar(progress)
    }
}
