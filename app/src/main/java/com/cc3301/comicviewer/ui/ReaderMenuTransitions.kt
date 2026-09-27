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
 * 出现 [ENTER_DURATION_MILLIS] = 350ms、消失 [EXIT_DURATION_MILLIS] = 250ms。
 *
 * **出现支的曲线与「进阅读器」同一档**（维护者 2026-09-27 真机验收口径 `CubicBezier(0f, 0f, 0.6f, 1f)`，
 * [ENTER_EASING]）；收起支维持 r8 那条（[EXIT_EASING]，起步略慢、末尾冲出屏幕）。
 *
 * **两条曲线由本对象自己声明**（[ENTER_EASING] / [EXIT_EASING]，r8 归属 A 案）：消失支原先直接读 `AppNav` 的
 * `NavTransitions.EXIT_EASING`（导航侧零引用、全仓唯一调用方就是本对象）
 * ⇒ 那个常量已随 r8 删除，本对象不再依赖 `AppNav`。
 *
 * **「点了到看见」**：单击唤出菜单之前有一段**固定静等**（双击等待窗口，见 [ReaderTapGesture]，150ms，未动）
 * ⇒ ≈ 150 + 350 = 500ms（出现支 350ms 是维护者 2026-09-27 真机验收从 r8 的 500ms 收下来的那一档；
 * 这个和由 `ReaderMenuTransitionsTest` 钉住）。
 * 时长沿革：出现/消失共用 250ms → 180ms → 100ms → 拆两支（出现支 50ms）→ 出现 120ms → r8 的 500 / 250 → 本轮 350 / 250。
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

    /** 出现：整幅高 → 0（自屏幕下缘升起），曲线 [ENTER_EASING]，时长 [ENTER_DURATION_MILLIS] */
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
         * 出现时长（毫秒）：**350ms**（维护者 2026-09-27 真机验收口径）——r8 的 500ms 验收不通过，
         * 收到 350ms（＝ 与「进阅读器」同一档手感），曲线同时换成 [ENTER_EASING]。
         * 沿革：出现/消失共用 250ms → 180ms → 100ms，拆成两支后出现支 50ms → 120ms → r8 的 500ms，本轮 350ms。
         */
        const val ENTER_DURATION_MILLIS: Int = 350

        /** 消失时长（毫秒）：**250ms**（r8 口径，维护者 2026-09-27 明确不指定、保持不变）——起步略慢、末尾冲出屏幕（[EXIT_EASING]），比出现短 100ms。 */
        const val EXIT_DURATION_MILLIS: Int = 250

        /** 位移幅度（整幅高的百分数）：**100%** —— 面板起点与终点都完全落在屏幕下缘之外 */
        const val SLIDE_TRAVEL_PERCENT: Int = 100

        /**
         * 出现曲线（减速型）：**与「进阅读器」同一档手感**——维护者 2026-09-27 真机验收口径 `CubicBezier(0f, 0f, 0.6f, 1f)`。
         *
         * 读数（数值法求斜率，起点取 x = 0.02、末段取 x = 0.98）：**起步 ≈ 1.60、末段 ≈ 0.08**
         * （首控制点 (0, 0) ⇒ 起步最慢，中段最快，到顶几乎停下）。
         *
         * **值上与导航侧「进阅读器」那一档相同**（`navSlideEasing(NavTransitionStyle.Slide, NavSlideDirection.IntoReader)`
         * 也是 `(0f, 0f, 0.6f, 1f)`），但**不跨模块引用**：r8 归属 A 案已裁定两条曲线由本对象自己声明，
         * #111 改那一档的写法不该牵动本菜单（两处各自钉各自的值）。
         *
         * 上一轮那条 `(0.25f, 0.5f, 0.7f, 1f)`（起步 ≈ 1.92、末段 ≈ 0.07）随 500ms 一起被真机验收退回，别再复活。
         */
        val ENTER_EASING: Easing = CubicBezierEasing(0f, 0f, 0.6f, 1f)

        /**
         * 消失曲线（加速型）：**起步略慢、末尾冲出屏幕**——r8 真机验收口径 `CubicBezier(0.3f, 0.1f, 0.7f, 0.15f)`。
         * 维护者 2026-09-27 明确收起本票未指定 ⇒ **本轮一字未动**。
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
