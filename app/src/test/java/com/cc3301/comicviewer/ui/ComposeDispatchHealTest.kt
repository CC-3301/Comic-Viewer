package com.cc3301.comicviewer.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 推进基建自愈（[healComposeDispatchScheduling]）的回归测试。
 *
 * 把 `AndroidUiDispatcher` 反射摆成红时实证的楔死形态（两个粘性标志 true、looper 队列里没有
 * 它的消息）后，「改状态后等落地」的等待必须照常落地。等待目标用换 key 重启的 [LaunchedEffect]
 * 写值：重组与新 effect 的执行都走 `dispatch`，正是被楔死卡住的那一跳；初始组合走同步的
 * `composeInitial`，不受影响。
 *
 * 为什么用反射摆态而不是真跑出竞态：竞态要整包跑 + 重负载才偶发（约 1/8），无法进单测；
 * 楔死形态的行为后果与真发作的同构（effect 续体永不跑 ⇒ 等待超时，正是受害类固定那 7 例的形状）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeDispatchHealTest {

    @Test
    fun `派发楔死时有界等待自愈 换代的 effect 照常落地`() {
        // 初始阶段健康跑完（组合是同步的 composeInitial，首个 effect 也正常落地）；
        // 然后楔死 → 写状态换代 ⇒ 重组与新 effect 全部卡在 dispatch 上 —— 正是受害类 7 例的形状。
        val trigger = mutableStateOf(false)
        var effectRan = false
        val view = composeViewInActivity {
            LaunchedEffect(trigger.value) { if (trigger.value) effectRan = true }
        }
        view.layoutOnce(400, 800)

        wedgeDispatchSchedulingFlags()
        trigger.value = true

        val settled = view.layoutUntil(400, 800) { effectRan }
        assertTrue(
            "楔死自愈后换代的 effect 要落地（有界等待=${if (settled) "已落地" else "**超时未落地**"}）",
            settled,
        )
        assertTrue("effect 必须真跑过（等待落地但值没写等于没测到）", effectRan)
    }
}

/** 把 `AndroidUiDispatcher`（主线程单例）的两个粘性标志摆成卡死 true（只改测试内状态，修完随测试存在） */
@Suppress("PrivateApi")
private fun wedgeDispatchSchedulingFlags() {
    var cur: Any? = androidx.compose.ui.platform.AndroidUiDispatcher.CurrentThread
    var dispatcher: Any? = null
    repeat(8) {
        if (cur == null || dispatcher != null) return@repeat
        val fs = cur.javaClass.declaredFields.map { it.isAccessible = true; it to runCatching { it.get(cur) }.getOrNull() }
        val elem = fs.firstOrNull { it.first.name == "element" }?.second
        if (elem != null && elem.javaClass.name.endsWith("AndroidUiDispatcher")) dispatcher = elem
        cur = fs.firstOrNull { it.first.name == "left" }?.second
    }
    val d = dispatcher ?: error("AndroidUiDispatcher 没找到")
    for (name in listOf("scheduledTrampolineDispatch", "scheduledFrameDispatch")) {
        val f = d.javaClass.getDeclaredField(name)
        f.isAccessible = true
        f.set(d, true)
    }
}