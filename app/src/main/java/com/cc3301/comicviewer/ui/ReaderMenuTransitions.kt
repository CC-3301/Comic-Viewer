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
 * 阅读菜单面板的出现 / 消失过渡（票 #129 **r8**）：**从屏幕下缘滑上来、沿来路滑回去**，
 * 出现 [ENTER_DURATION_MILLIS] = 500ms、消失 [EXIT_DURATION_MILLIS] = 250ms（**进出比 2 : 1**）。
 *
 * **两条曲线由本对象自己声明**（[ENTER_EASING] / [EXIT_EASING]，r8 归属 A 案）：出现略快于匀速起步、到顶几乎停下；
 * 消失略慢起步、末尾冲出屏幕。上一轮消失支直接读 `AppNav` 的 `NavTransitions.EXIT_EASING`（导航侧零引用、
 * 全仓唯一调用方就是本对象）⇒ 那个常量已随本次删除，本对象不再依赖 `AppNav`。
 *
 * **「点了到看见」**：单击唤出菜单之前有一段**固定静等**（双击等待窗口，见 [ReaderTapGesture]，150ms，本轮未动）
 * ⇒ ≈ 150 + 500 = 650ms。r8 把出现支从 120ms 拉到 500ms（真机「弹出仍偏快」）正是为了「看得出在动」，
 * 感知延迟随之变长是维护者已知并接受的结果（这个和由 `ReaderMenuTransitionsTest` 钉住）。
 * 时长沿革：出现/消失共用 250ms → 180ms → 100ms → 拆两支（出现支 50ms）→ 出现 120ms → 本轮 500 / 250。
 *
 * 面板是 `fillMaxSize` 的贴底浮层（`ReaderMenu` 根节点，`contentAlignment = BottomCenter`），
 * 因此「整幅高」正好等于「面板完全落在屏幕下缘之外」：出现端起在屏下、滑到位时贴底；消失端从贴底滑回屏下。
 * 出现与消失**读同一个位移函数**（[readerMenuSlideOffsetPx]）⇒ 起止点重合，这就是「沿来路滑回」的含义。
 *
 * 这一层为什么单独成对象：`AnimatedVisibility` 的两个参数每次重组都取同一对实例（属性初始化 + 调用点
 * `remember`），动画不会被重组重启；两支时长 / 幅度 / 两条曲线也各自只有一处声明（`ReaderMenuTransitionsTest` 钉它们）。
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

    /** 消失：0 → 整幅高（沿来路滑回屏下），加速曲线 [EXIT_EASING]（本对象自己声明），时长 [EXIT_DURATION_MILLIS] */
    val exit: ExitTransition = slideOutVertically(
        animationSpec = tween<IntOffset>(EXIT_DURATION_MILLIS, easing = EXIT_EASING),
        targetOffsetY = { fullHeight -> readerMenuSlideOffsetPx(fullHeight) },
    )

    companion object {
        /**
         * 出现时长（毫秒）：**500ms**（r8 口径）——真机反馈「弹出动画仍偏快」⇒ 加长总时长，
         * 并把曲线换成「起步略快于匀速、到顶几乎停下」那条（[ENTER_EASING]），落到「一眼看得出在动」那一档。
         * 沿革：出现/消失共用 250ms → 180ms → 100ms，拆成两支后出现支 50ms → 120ms，本轮 500ms。
         */
        const val ENTER_DURATION_MILLIS: Int = 500

        /** 消失时长（毫秒）：**250ms**（r8 口径）——收起步略慢、末尾冲出屏幕（[EXIT_EASING]），与出现凑成 2 : 1。 */
        const val EXIT_DURATION_MILLIS: Int = 250

        /** 位移幅度（整幅高的百分数）：**100%** —— 面板起点与终点都完全落在屏幕下缘之外 */
        const val SLIDE_TRAVEL_PERCENT: Int = 100

        /**
         * 出现曲线（减速型）：**起步略快于匀速、到顶几乎停下**——r8 真机验收口径 `CubicBezier(0.25f, 0.5f, 0.7f, 1f)`。
         *
         * 读数（数值法求斜率，起点取 x = 0.02、末段取 x = 0.98）：**起步 ≈ 1.92、末段 ≈ 0.07**。
         * 真机原话要「保持线性速度」「快到顶时速度慢下来缓一下」：首控制点落在对角线附近（0.25 / 0.5）⇒ 起步就是匀速那一档，
         * 末控制点 y = 1 且 x = 0.7 ⇒ 到顶前把速度收到几乎为 0。上一轮那条 `(0.4f, 0f, 0.2f, 1f)` 首控制点 y = 0 ⇒ 起步最慢，
         * 与「保持线性速度」正是反面（另一条老曲线 `(0f, 0f, 0.2f, 1f)` 则是起步即全速）。
         */
        val ENTER_EASING: Easing = CubicBezierEasing(0.25f, 0.5f, 0.7f, 1f)

        /**
         * 消失曲线（加速型）：**起步略慢、末尾冲出屏幕**——r8 真机验收口径 `CubicBezier(0.3f, 0.1f, 0.7f, 0.15f)`。
         *
         * 读数（同上）：**起步 ≈ 0.32、末段 ≈ 2.68**（对照：上一轮复用的 `NavTransitions.EXIT_EASING` = `(0.3f, 0f, 0.8f, 0.15f)`
         * 起步 ≈ 0.02，先愣一下）。
         *
         * **归属（r8 拍板 A 案）**：本对象自己声明，不再读导航侧常量——那条常量的全仓唯一调用方就是本菜单的消失支，
         * 已随本次删除（`ui/AppNav.kt` 的 `NavTransitions` 不再有 `EXIT_EASING`）；它一改只影响本对象与
         * `ReaderMenuTransitionsTest`，不会再牵动导航侧的用例。
         */
        val EXIT_EASING: Easing = CubicBezierEasing(0.3f, 0.1f, 0.7f, 0.15f)
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
