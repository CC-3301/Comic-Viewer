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
 * 阅读菜单面板的出现 / 消失过渡：**从屏幕下缘滑上来、沿来路滑回去**，
 * 出现 [ENTER_DURATION_MILLIS] = 300ms、消失 [EXIT_DURATION_MILLIS] = 200ms。
 *
 * **出现支**取 2026-09-27 第三轮设备验收口径：时长 300ms、曲线 `CubicBezier(0.25f, 0.5f, 0.7f, 1f)`
 * （[ENTER_EASING]，起步略快于匀速、到顶几乎停下）；**收起支**的曲线维持 那条不变
 * （[EXIT_EASING]，起步略慢、末尾冲出屏幕），时长本轮收到 200ms。
 *
 * **两条曲线由本对象自己声明**（[ENTER_EASING] / [EXIT_EASING]，归属 A 案）：消失支原先直接读 `AppNav` 的
 * `NavTransitions.EXIT_EASING`（导航侧零引用、全仓唯一调用方就是本对象）
 * ⇒ 那个常量已随 删除，本对象不再依赖 `AppNav`。
 *
 * **「点了到看见」不能只算时长**：单击唤出菜单之前有一段**固定静等**（双击等待窗口，见 [ReaderTapGesture]，
 * 150ms，未动）⇒「静等 + 出现」之和 = 150 + 300 = **450ms**（由 `ReaderMenuTransitionsTest` 钉住）。但点下去那一下
 * **真正落在屏幕上的时刻还要再往后**：见下面那两层高的差。
 * 时长沿革：出现/消失共用 250ms → 180ms → 100ms → 拆两支（出现支 50ms）→ 出现 120ms →  500 / 250 →
 *  350 / 250（出现曲线换成 `(0f, 0f, 0.6f, 1f)`，设备判「点了没立刻动」）→ 本轮 300 / 200，出现曲线回到 那条。
 *
 * 面板是 `fillMaxSize` 的贴底浮层（`ReaderMenu` 根节点，`contentAlignment = BottomCenter`），
 * 因此「整幅高」正好等于「面板完全落在屏幕下缘之外」：出现端起在屏下、滑到位时贴底；消失端从贴底滑回屏下。
 * 出现与消失**读同一个位移函数**（[readerMenuSlideOffsetPx]）⇒ 起止点重合，这就是「沿来路滑回」的含义。
 *
 * **但「整幅高」是屏幕高、不是面板高**：面板自己只占视口高的 40%（`ReaderMenuLayout.PANEL_HEIGHT_FRACTION`）
 * ⇒ 出现支**前 60% 的进度整段在屏幕下缘之外**，面板顶边跨过屏幕下缘要等进度走到 **0.6**。
 * 首帧时刻 = 出现时长 × 曲线走到 0.6 的那个进度点：本轮 300ms × 0.401 ≈ **120ms**；
 *  350ms + `(0f, 0f, 0.6f, 1f)` 是 350ms × 0.433 ≈ **151.5ms**——设备「点了没立刻动」就是这一截。
 *
 * 这一层为什么单独成对象：`AnimatedVisibility` 的两个参数每次重组都取同一对实例（属性初始化 + 调用点
 * `remember`），动画不会被重组重启；两支时长 / 幅度 / 两条曲线也各自只有一处声明（`ReaderMenuTransitionsTest` 钉它们）。
 *
 * **本机钉不住**：动画是否真的逐帧播出需要 Compose 组合 + 帧时钟，本仓库无 Compose UI 测试依赖
 * （`docs/SPEC.md` 的 Testing Decisions 把 UI 层交给手动验收），因此设备目视项写在
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
         * 出现时长（毫秒）：**300ms**（2026-09-27 第三轮设备验收口径）—— 500ms、 350ms 都判「不过」
         * （原话「点了没立刻动」，成因见类 KDoc 的「整幅高 ≠ 面板高」）⇒ 收到 300ms，曲线同时换成 [ENTER_EASING]。
         * 沿革：出现/消失共用 250ms → 180ms → 100ms，拆成两支后出现支 50ms → 120ms →  500ms →  350ms，本轮 300ms。
         */
        const val ENTER_DURATION_MILLIS: Int = 300

        /**
         * 消失时长（毫秒）：**200ms**（2026-09-27 第三轮口径， 250ms 收下来的一档）——起步略慢、
         * 末尾冲出屏幕（[EXIT_EASING]，本身未动），比出现短 100ms。
         */
        const val EXIT_DURATION_MILLIS: Int = 200

        /** 位移幅度（整幅高的百分数）：**100%** —— 面板起点与终点都完全落在屏幕下缘之外 */
        const val SLIDE_TRAVEL_PERCENT: Int = 100

        /**
         * 出现曲线（减速型）：**起步略快于匀速、到顶几乎停下**—— 2026-09-27 第三轮设备验收口径
         * `CubicBezier(0.25f, 0.5f, 0.7f, 1f)`（那条，换成 `(0f, 0f, 0.6f, 1f)` 后设备判「点了没立刻动」⇒ 本轮复活）。
         *
         * 读数（数值法解 x → t，与 `CubicBezierEasing.transform` 同一算法）：**头 1/6 时长处 0.289（= 匀速的 1.73 倍）、
         * x = 5/6 处 0.958**；对照 那条 `(0f, 0f, 0.6f, 1f)`：0.254（= 1.52 倍）、0.953 ⇒ 起步的那一截更慢
         * （首控制点 x 从 0 抬到 0.25 就是这一截的来源：起步斜率 = y₁/x₁ = 2.0 而不是 `(0, 0)` 那种二阶起步）。
         *
         * **不跨模块引用**（归属 A 案：两条曲线都由本对象自己声明）：本值与导航侧「出阅读器」那一档
         * （`NavTransitions.OUT_OF_READER_EASING`）**恰好同形**，但两处各自钉各自的值—— 改导航那几档
         * 不该牵动本菜单（`ReaderMenuTransitionsTest` 钉的是本对象这两个常量）。
         */
        val ENTER_EASING: Easing = CubicBezierEasing(0.25f, 0.5f, 0.7f, 1f)

        /**
         * 消失曲线（加速型）：**起步略慢、末尾冲出屏幕**——设备验收口径 `CubicBezier(0.3f, 0.1f, 0.7f, 0.15f)`。
         *  2026-09-27 第三轮只把消失**时长**收到 [EXIT_DURATION_MILLIS] = 200ms、曲线明确「一字不动」⇒ **本条值未动**。
         *
         * 读数（同上）：**起步 ≈ 0.32、末段 ≈ 2.68**（对照：上一轮复用的 `NavTransitions.EXIT_EASING` = `(0.3f, 0f, 0.8f, 0.15f)`
         * 起步 ≈ 0.02，先愣一下）。
         *
         * **归属（A 案）**：本对象自己声明，不再读导航侧常量——那条常量的全仓唯一调用方就是本菜单的消失支，
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
