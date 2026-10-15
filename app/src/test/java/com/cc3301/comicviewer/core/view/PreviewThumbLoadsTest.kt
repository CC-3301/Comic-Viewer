package com.cc3301.comicviewer.core.view

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览格缩略图的**取解调度**（口径见 [PreviewThumbLoads]）：谁去取、几个在飞、谁被丢掉。
 *
 * 用例走 `Dispatchers.Unconfined` 的条子作用域：`launch` 会一路同步跑到第一个挂起点，
 * 因此"谁在飞、谁排队、谁出队"当场就能断言，不需要等调度器（也不需要虚拟时间）。
 *
 * 判别用例：
 * 1. **同一个键**（页位 + 解码高度）重复请求只取一次；**换了高度是新的要图**（预览条高度会变一次）；
 * 2. 在飞最多 2 个（第 3 个排队，让出一个位才开工）——留闸位给阅读页；
 * 3. 视口外**排队中**的最多留 2 个、更早的出队（出队的不算白花：它还没开工）；
 * 4. **已经开始的不取消**（跑完就存下来，滚回来第一帧就有图——这条是「邻格闪一下」的解药）；
 * 5. 内存里已经有的不再取；6. 重试判据跟着可见页位走；7. 条子销毁即全停。
 */
class PreviewThumbLoadsTest {

    /** 假的取解：每格一个放行闸，用例自己决定什么时候「取完」；被取消的那几格记下来 */
    private class FakeLoads(
        scope: CoroutineScope,
        maxInFlight: Int = 2,
        maxOrphans: Int = 2,
    ) {
        val started = mutableListOf<Int>()

        /** 每次取解用的**键**（含解码高度）：钉「换了高度必须重取」 */
        val startedKeys = mutableListOf<PreviewThumbKey>()
        val cancelled = mutableListOf<Int>()
        val cached = mutableSetOf<Int>()
        private val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()

        val loads = PreviewThumbLoads(
            scope = scope,
            cached = { key -> key.index in cached },
            load = { key, _ ->
                started += key.index
                startedKeys += key
                try {
                    gates.getOrPut(key.index) { CompletableDeferred() }.await()
                } catch (e: CancellationException) {
                    cancelled += key.index
                    throw e
                }
            },
            maxInFlight = maxInFlight,
            maxOrphans = maxOrphans,
        )

        /** 这一格要图（高度默认值只用于让键构造出来） */
        fun request(index: Int, heightPx: Int = 128) = loads.request(PreviewThumbKey(index, heightPx))

        /** 放行某一格（= 这一格取完了） */
        fun finish(index: Int) {
            gates.getOrPut(index) { CompletableDeferred() }.complete(Unit)
        }
    }

    @Test
    fun `同一个键重复请求只取一次`() = runBlocking {
        val fake = FakeLoads(CoroutineScope(Dispatchers.Unconfined))

        fake.request(3)
        fake.request(3)
        fake.request(3)

        assertEquals("同一个键重复问只应有一次取解", listOf(PreviewThumbKey(3, 128)), fake.startedKeys)
    }

    @Test
    fun `在飞最多两个 第三个排队 让出额度才开工`() = runBlocking {
        val fake = FakeLoads(CoroutineScope(Dispatchers.Unconfined))

        fake.request(0)
        fake.request(1)
        fake.request(2)

        assertEquals("两个在飞、第三个排队", listOf(0, 1), fake.started)

        fake.finish(0)

        assertEquals("让出一个额度后第三个才开工", listOf(0, 1, 2), fake.started)
    }

    @Test
    fun `视口外排队中的最多留两个 更早的出队`() = runBlocking {
        val fake = FakeLoads(CoroutineScope(Dispatchers.Unconfined))

        // 快速拖过 6 格：0/1 在飞，2–5 排队
        (0..5).forEach { fake.request(it) }
        assertEquals("前两个占到额度", listOf(0, 1), fake.started)

        // 人停在 0/1 附近：2–5 已经看不见 ⇒ 只留最近的 4、5
        fake.loads.onVisible(setOf(0, 1))
        assertEquals("出队的不算取解（它还没开工）", listOf(0, 1), fake.started)

        fake.finish(0)
        assertEquals("留下的那个接着开工，出队的（2/3）不再取", listOf(0, 1, 4), fake.started)
    }

    @Test
    fun `已经开始的不取消`() = runBlocking {
        val fake = FakeLoads(CoroutineScope(Dispatchers.Unconfined))

        (0..4).forEach { fake.request(it) }
        // 全都滚出视口：在飞的 0/1 照跑，排队中的 2–4 只留最近两个
        fake.loads.onVisible(emptySet())

        assertTrue("在飞的那两张照跑，不取消", fake.cancelled.isEmpty())

        fake.finish(0)
        fake.finish(1)
        assertEquals("跑完的不重取，留下的排队者接着开工", listOf(0, 1, 3, 4), fake.started)
    }

    @Test
    fun `内存里已经有的不再取`() = runBlocking {
        val fake = FakeLoads(CoroutineScope(Dispatchers.Unconfined))
        fake.cached += 4

        fake.request(4)

        assertTrue("内存命中就不该再向来源要", fake.started.isEmpty())
    }

    @Test
    fun `重试判据跟着可见页位走`() = runBlocking {
        val fake = FakeLoads(CoroutineScope(Dispatchers.Unconfined))

        fake.loads.onVisible(setOf(7))
        assertTrue("在屏上就该重试", fake.loads.isVisible(7))
        assertFalse("不在屏上不值得重试", fake.loads.isVisible(8))

        fake.loads.onVisible(setOf(8))
        assertFalse("滚走之后不再重试", fake.loads.isVisible(7))
    }

    /**
     * **菜单打开时预览条高度会变一次**（标题行数回填 ⇒ 面板高度重算 ⇒ 格内高变），
     * 每格的键随之变一次。此时必须按**新高度**再取一次；拿旧键去重会让新键永远没有图
     * （格子空着、看着是灰的，拖动进度条/重进菜单才好——这是现场反馈的那条）。
     */
    @Test
    fun `同一格换了高度要按新高度重取`() = runBlocking {
        val fake = FakeLoads(CoroutineScope(Dispatchers.Unconfined))

        fake.request(3, heightPx = 64)   // 旧高度（首帧量出的那一版）
        fake.request(3, heightPx = 128)  // 真高度到了

        assertEquals(
            "换了高度必须以新键再取（旧高度那次不算数）",
            listOf(PreviewThumbKey(3, 64), PreviewThumbKey(3, 128)),
            fake.startedKeys,
        )
    }

    /**
     * 撤销一条**还没开工**的取解（换高度那次）之后，额度必须还回来：`cancel()` 落在协程体首次调度之前时
     * 体根本不执行，额度若挂在体里的 `finally` 上就永远少一个——两次之后预览格就再也不取图。
     * 这里用**暂停的调度器**造出「已占额、尚未开工」这一窗口。
     */
    @Test
    fun `撤销还没开工的取解后 额度要还回来`() {
        val scheduler = TestCoroutineScheduler()
        val fake = FakeLoads(CoroutineScope(StandardTestDispatcher(scheduler)))

        fake.request(0, heightPx = 128)
        fake.request(1, heightPx = 128)
        fake.request(2, heightPx = 128)   // 两个额度已被 0/1 占住，2 排队
        fake.request(0, heightPx = 160)   // 0 换高度：撤掉那条还没开工的 0@128
        scheduler.advanceUntilIdle()

        assertTrue(
            "额度漏了：撤掉那条之后排队的 2 开不了工（已开工 ${fake.startedKeys}）",
            fake.startedKeys.any { it == PreviewThumbKey(2, 128) },
        )
        assertTrue(
            "被撤的那条还没开工，就不该向来源要过图（已开工 ${fake.startedKeys}）",
            fake.startedKeys.none { it == PreviewThumbKey(0, 128) },
        )
        // 队列照旧往前走：放行一个在飞的，排队里的新高度 0 就开工
        fake.finish(1)
        scheduler.advanceUntilIdle()
        assertTrue(
            "队列没往前走：放行一格后新高度的 0 仍未开工（已开工 ${fake.startedKeys}）",
            fake.startedKeys.any { it == PreviewThumbKey(0, 160) },
        )
    }

    @Test
    fun `条子销毁时全部停下`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val fake = FakeLoads(scope)
        fake.request(0)
        assertEquals(listOf(0), fake.started)

        // 菜单关闭 = 条子的作用域整体取消
        scope.cancel()

        assertEquals("在飞的取解随之停下", listOf(0), fake.cancelled)
    }
}
