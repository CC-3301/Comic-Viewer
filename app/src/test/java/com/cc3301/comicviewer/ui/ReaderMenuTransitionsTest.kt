package com.cc3301.comicviewer.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读菜单面板出现/消失的声明口径（票 #129）：**从屏幕下缘滑上来、沿来路滑回**，出现 350ms / 消失 250ms
 * （维护者 2026-09-27 真机验收口径；上游 r8 的 500 / 250 已作废）。
 *
 * 钉住两样能在本机观测的东西：
 * 1. **规格常量**：时长 [ReaderMenuTransitions.ENTER_DURATION_MILLIS] / [ReaderMenuTransitions.EXIT_DURATION_MILLIS]、位移幅度
 *    [ReaderMenuTransitions.SLIDE_TRAVEL_PERCENT]（整幅高 ⇒ 面板起点完全落在屏幕下缘之外），两条曲线
 *    [ReaderMenuTransitions.ENTER_EASING] / [ReaderMenuTransitions.EXIT_EASING]（r8 起两条都由面板自己声明，
 *    不再复用导航侧常量 ⇒ 本文件钉的就是生产侧直接读的那两个值）；
 * 2. **方向与「参数来源」**（[readerMenuSlideOffsetPx]，纯函数）：出现与消失**读同一个函数**得到的**正**位移
 *    ——出现端起在屏下（`initialOffsetY`）、消失端落在屏下（`targetOffsetY`），因此「沿来路滑回」不是两处各写一份。
 *
 * **本机钉不住的那一半（不为它编造断言）**：`slideInVertically` 的位移 lambda 与 `tween` 里的缓动对象都是
 * `AnimatedVisibility` 过渡对象内部的 lambda/对象，读不到（反射白名单为空，见 `docs/SPEC.md` 的 Testing Decisions）；
 * 「面板确实从屏幕下缘升起、出现 350ms / 消失 250ms 观感合适」只有真机目视一条判据：
 *
 * - 点屏幕中区呼出菜单：面板**从屏幕下缘往上滑入**（不是淡入、不是从上方掉落），350ms（与「进阅读器」同一档手感）；
 * - 点空白处 / 按系统返回：面板**往下滑回**（方向与来路相反相成，不是瞬间消失），250ms；
 * - 编辑 `ReaderMenuTransitions.ENTER_DURATION_MILLIS` / `EXIT_DURATION_MILLIS`（如改成 1000）观感应随之变慢——若没变，说明 `AnimatedVisibility`
 *   那一层没接上本对象（票面要求写清的「为何造不出能失败的用例」：动画播放需要 Compose 组合 + 帧时钟，
 *   本仓库无 Compose UI 测试依赖，SPEC 把 UI 层交给手动验收）。
 *
 * 面板**内容与几何一律不动**（票面口径）：档位几何、预览条、跳页滑条、上一本/下一本仍由
 * `ReaderMenuLayoutTest` / `ReaderMenuFooterTest` / `ReaderMenuTitle*Test` 守护，本文件不重复它们的断言。
 */
class ReaderMenuTransitionsTest {

    private val transitions = ReaderMenuTransitions()

    // ---------- 规格常量 ----------

    @Test
    fun `时长与位移幅度就是真机验收拍板的那一档 出现 350ms 消失 250ms 与整幅高`() {
        assertEquals(
            "维护者 2026-09-27 口径：出现 350ms（原先出现/消失共用一支：250ms → 180ms → 100ms；拆开后出现支走过 50ms → 120ms，" +
                "r8 拉到 500ms 真机验收不通过 ⇒ 本轮收到 350ms，并换成「与进阅读器同一档手感」的曲线）",
            350,
            ReaderMenuTransitions.ENTER_DURATION_MILLIS,
        )
        assertEquals(
            "消失 250ms 不变（维护者 2026-09-27 明确「收起本票未指定 ⇒ 按现有值保持不变」）：起步略慢、末尾冲出屏幕",
            250,
            ReaderMenuTransitions.EXIT_DURATION_MILLIS,
        )
        assertEquals(
            "整幅高：面板起点完全落在屏幕下缘之外（不是半幅、不是只露一角）",
            100,
            ReaderMenuTransitions.SLIDE_TRAVEL_PERCENT,
        )
    }

    /**
     * 「点了到看见」= 双击等待窗口（静等，见 `ReaderTapGesture`）+ 出现时长：两个数是一对。
     *
     * 出现支从 r8 的 500ms 收到 350ms（维护者 2026-09-27 真机验收口径），窗口按维护者要求**保持 150ms**
     * ⇒ 感知延迟回到 500ms（沿革：r7 出现支 120ms 时是 270ms，r8 出现支 500ms 时是 650ms）。
     * 这条断言就是那个「两头」：只动其中一个数、不连带看另一个，感知延迟就不是口径里的那一档了
     * （例如窗口回到 300 ⇒ 650ms，这条会红）。
     */
    @Test
    fun `点了到看见仍是静等加出现两支之和`() {
        assertEquals(
            "双击等待 150ms + 出现 350ms = 500ms（维护者 2026-09-27 口径；r8 是 150 + 500 = 650ms）",
            500L,
            ReaderTapGesture.DOUBLE_TAP_WINDOW_MILLIS + ReaderMenuTransitions.ENTER_DURATION_MILLIS,
        )
    }

    /**
     * **出现仍比收起慢**（口径变更类，单独登记）：r8 写的是「进出比 2 : 1」，维护者 2026-09-27 只给出出现支
     * ⇒ 350 / 250 ⇒ 2 : 1 那条等式连同它的用例一并作废（350 ≠ 250 × 2）。两个时长各自的绝对值在上面那条用例里
     * 钉着，这条钉它们的关系——出现必须仍长于收起、差值就是 100ms，免得把出现支改回不比收起慢的档（那时
     * 「出现比收起慢」这半句口径就没了，而两个绝对值里各看一个看不出来）。
     */
    @Test
    fun `出现仍比收起慢 350 对 250`() {
        assertEquals(
            "出现 350 - 收起 250 = 100ms（维护者 2026-09-27 口径；r8 的 500 / 250 是 2 : 1，已作废）",
            100,
            ReaderMenuTransitions.ENTER_DURATION_MILLIS - ReaderMenuTransitions.EXIT_DURATION_MILLIS,
        )
        assertTrue(
            "出现仍比收起长（350 : 250 ≈ 1.4 : 1，方向与 r8 一致）",
            ReaderMenuTransitions.ENTER_DURATION_MILLIS > ReaderMenuTransitions.EXIT_DURATION_MILLIS,
        )
    }

    @Test
    fun `出现曲线是与进阅读器同一档的那一条`() {
        assertEquals(
            "维护者 2026-09-27 口径 CubicBezier(0f, 0f, 0.6f, 1f)——真机原话「＝ 与进阅读器同一档手感」：" +
                "起步斜率 ≈ 1.60、末段 ≈ 0.08（读数法同上一轮：数值法求斜率，起点取 x = 0.02、末段取 x = 0.98）；" +
                "r8 的 (0.25f, 0.5f, 0.7f, 1f)（起步 ≈ 1.92、末段 ≈ 0.07）已随本轮真机验收不通过而作废",
            CubicBezierEasing(0f, 0f, 0.6f, 1f),
            ReaderMenuTransitions.ENTER_EASING,
        )
    }

    /**
     * 消失曲线**由面板自己声明**（r8 归属 A 案）：起步略慢、末尾冲出屏幕。生产侧 `exit` 直接读它。
     * 维护者 2026-09-27 口径把它**原样保留**（「收起本票未指定 ⇒ 按现有值保持不变」）——这条用例因此一字未改。
     * 「谁读了哪条曲线」读不到（见类 KDoc），这里钉的是那条曲线本身的值——导航侧不再有同名常量，
     * 它一改只影响本文件这一条用例（`NavTransitionsTest` 已按 A 案删掉钉旧常量的断言）。
     */
    @Test
    fun `消失曲线由菜单自己声明 起步略慢末尾冲出屏幕`() {
        assertEquals(
            "r8 口径 CubicBezier(0.3f, 0.1f, 0.7f, 0.15f)：起步斜率 ≈ 0.32（略慢）、末段 ≈ 2.68（末尾冲出屏幕）；" +
                "上一轮复用的 NavTransitions 那条 (0.3f, 0f, 0.8f, 0.15f) 起步斜率 ≈ 0.02（先愣一下）",
            CubicBezierEasing(0.3f, 0.1f, 0.7f, 0.15f),
            ReaderMenuTransitions.EXIT_EASING,
        )
    }

    // ---------- 方向（纯函数）----------

    /**
     * 位移 = 整幅高的百分之百。幅度真的参与计算（不是恒等的「原样返回」）：百分比改成别的就不再是整个高度，
     * 用例会红——面板只滑 30% 时下缘会留在屏内（消失后露着半截面板）。
     */
    @Test
    fun `滑动位移就是整幅高`() {
        assertEquals(1080, readerMenuSlideOffsetPx(1080))
        assertEquals(1920, readerMenuSlideOffsetPx(1920))
        assertEquals("零高度视口（尚未测量）不产生位移，也不崩", 0, readerMenuSlideOffsetPx(0))
    }

    /**
     * 符号即方向：**向下为正**。出现端的 `initialOffsetY` 与消失端的 `targetOffsetY` 都读这一支的正值 ⇒
     * 起点在屏幕下缘之外（向上滑入）、终点在屏幕下缘之外（向下滑出）；若这里取负，面板会改成从屏幕**上方**
     * 掉落/上飘——那正是本用例要咬住的回归。
     */
    @Test
    fun `滑动位移向下为正`() {
        assertTrue("正值 = 屏下（出现端点、消失端点都在屏下）", readerMenuSlideOffsetPx(1080) > 0)
        assertTrue("同一支位移正负号唯一，不存在第二条反向口径", readerMenuSlideOffsetPx(2400) > 0)
    }

    // ---------- 过渡对象 ----------

    @Test
    fun `出现与消失都不是零过渡`() {
        assertNotSame("出现支不能是 None（否则「直接显隐」照旧）", EnterTransition.None, transitions.enter)
        assertNotSame("消失支不能是 None", ExitTransition.None, transitions.exit)
    }

    /**
     * 「一次显隐 = 一次过渡」里能在单测里钉住的一半：两支过渡在实例里**只建一次**（属性初始化，不是每次读取
     * 新建）——`AnimatedVisibility` 每次重组拿到的就是同一个实例，动画因此不会被重组重启。
     *
     * 后一条用**同形状的替身**（`val enter get() = slideInVertically(...)`）证明判据不是恒真的：
     * 那种写法每次读取都新建，同实例比较就会红。
     */
    @Test
    fun `进出两支各建一次 重组不重启动画`() {
        assertSame(transitions.enter, transitions.enter)
        assertSame(transitions.exit, transitions.exit)

        val recomputed = RecomputedTransitions()
        assertNotSame("替身每次读取都新建 ⇒ 判据能咬住这种形状", recomputed.enter, recomputed.enter)
        assertSame("对照：生产对象读两次是同一个实例", transitions.enter, transitions.enter)
    }

    /** 「每次读取都新建」的同形状替身：只为本文件那条反例存在，不是生产形状 */
    private class RecomputedTransitions {
        val enter: EnterTransition
            get() = slideInVertically(
                animationSpec = tween(ReaderMenuTransitions.ENTER_DURATION_MILLIS),
            )
    }
}
