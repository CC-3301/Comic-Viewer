package com.cc3301.comicviewer.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.unit.IntOffset

/**
 * 阅读菜单面板的出现 / 消失过渡（票 #129）：**从屏幕下缘滑上来、沿来路滑回去**，
 * 出现 [ENTER_DURATION_MILLIS] = 120ms、消失 [EXIT_DURATION_MILLIS] = 100ms。
 *
 * **为什么出现（120ms）比消失（100ms）长**：单击唤出菜单之前有一段**固定静等**（双击等待窗口，见
 * [ReaderTapGesture]，150ms）。真机反馈「整体加速过快、感觉很急」⇒ 出现支把起步放缓
 * （[ENTER_EASING] 首控制点从 0 挪到 0.4）并给足时间走完，多出来的这段从静等里砍回来（窗口 200 → 150ms）：
 * 「点了到看见」≈ 150 + 120 = 270ms，与上一轮 200 + 50 = 250ms 基本持平——动画慢一点、等待短一点，
 * 两头都保住（这个和由 `ReaderMenuTransitionsTest` 钉住）。消失支没有那段静等，100ms 已合适，两轮未动。
 *
 * 面板是 `fillMaxSize` 的贴底浮层（`ReaderMenu` 根节点，`contentAlignment = BottomCenter`），
 * 因此「整幅高」正好等于「面板完全落在屏幕下缘之外」：出现端起在屏下、滑到位时贴底；消失端从贴底滑回屏下。
 * 出现与消失**读同一个位移函数**（[readerMenuSlideOffsetPx]）⇒ 起止点重合，这就是「沿来路滑回」的含义。
 *
 * 这一层为什么单独成对象：`AnimatedVisibility` 的两个参数每次重组都取同一对实例（属性初始化 + 调用点
 * `remember`），动画不会被重组重启；时长 / 幅度 / 曲线三样也各自只有一处声明（`ReaderMenuTransitionsTest` 钉它们）。
 *
 * **本机钉不住**：动画是否真的逐帧播出需要 Compose 组合 + 帧时钟，本仓库无 Compose UI 测试依赖
 * （`docs/SPEC.md` 的 Testing Decisions 把 UI 层交给手动验收），因此真机目视项写在
 * `ReaderMenuTransitionsTest` 的类 KDoc 里；本类只负责「时长 + 方向 + 参数来源」可读、可钉。
 */
internal class ReaderMenuTransitions {

    /** 出现：整幅高 → 0（自屏幕下缘升起），减速曲线 [ENTER_EASING]，时长 [ENTER_DURATION_MILLIS] */
    val enter: EnterTransition = slideInVertically(
        animationSpec = tween<IntOffset>(ENTER_DURATION_MILLIS, easing = ENTER_EASING),
        initialOffsetY = { fullHeight -> readerMenuSlideOffsetPx(fullHeight) },
    )

    /** 消失：0 → 整幅高（沿来路滑回屏下），加速曲线沿用 `AppNav` 那条 [NavTransitions.EXIT_EASING]（直接读，不另起别名） */
    val exit: ExitTransition = slideOutVertically(
        animationSpec = tween<IntOffset>(EXIT_DURATION_MILLIS, easing = NavTransitions.EXIT_EASING),
        targetOffsetY = { fullHeight -> readerMenuSlideOffsetPx(fullHeight) },
    )

    companion object {
        /**
         * 出现时长（毫秒）：**120ms** —— 起步缓的曲线（[ENTER_EASING]）要有这么多时间才不显「急」：
         * 上一轮 50ms 是「出现越快越好」时期的取值，与「起步即全速」的老曲线叠在一起，就成了真机反馈的「很急」。
         * 沿革：出现/消失共用 250ms → 180ms → 100ms，拆成两支后出现支 50ms，本轮按真机反馈改为 120ms。
         */
        const val ENTER_DURATION_MILLIS: Int = 120

        /** 消失时长（毫秒）：**100ms**（维护者对收起满意，两轮未动） */
        const val EXIT_DURATION_MILLIS: Int = 100

        /** 位移幅度（整幅高的百分数）：**100%** —— 面板起点与终点都完全落在屏幕下缘之外 */
        const val SLIDE_TRAVEL_PERCENT: Int = 100

        /**
         * 出现曲线（减速型）：**起步缓、中段快、收尾缓**——真机验收口径 `CubicBezier(0.4f, 0f, 0.2f, 1f)`。
         *
         * 上一轮那条 `(0f, 0f, 0.2f, 1f)` 的首控制点 x = 0 ⇒ **起步即全速**：起步段（前 5% 时长）的平均斜率约为平均速度的 4.7 倍，
         * 前 20% 的时长就走完一半位移——这正是真机反馈「整体加速过快、感觉很急」的直接来源，也是本轮必须
         * 连曲线一起改的原因（只把时长拉长、保留老曲线，一半位移仍然在前 1~2 帧内走完）。
         * 首控制点挪到 0.4 后，同一算法下起步段（前 5% 时长）的平均斜率 ≈ 0.13，到中段才快起来。
         */
        val ENTER_EASING: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    }
}

/**
 * 面板纵向滑动位移（px，**向下为正**）：整幅高的 [ReaderMenuTransitions.SLIDE_TRAVEL_PERCENT]%。
 *
 * 出现的 `initialOffsetY` 与消失的 `targetOffsetY` 都读它（同一支、同一符号）：
 * 正值 = 屏幕下缘之外 ⇒ 出现自下而上滑入，消失自上而下滑回。零高度（尚未测量）回 0，不产生位移。
 */
internal fun readerMenuSlideOffsetPx(fullHeightPx: Int): Int =
    fullHeightPx * ReaderMenuTransitions.SLIDE_TRAVEL_PERCENT / 100
