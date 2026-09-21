package com.cc3301.comicviewer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「不在浏览页点书」入口的请求判定（票 #111 r2 修复 P1/P2，钉 [ReaderEntryRequest]）。
 *
 * 为什么这条守卫要抽成可断言的一处：抽屉「阅读器」入口的等待跑在 `AppNav` 的组合作用域上（只有整个 AppNav
 * 离开组合才取消），而读内换书那条的「离开即取消」靠阅读页的页内作用域——两者共用的判定只有这里能单测；
 * 「`AppNav` 调用点是否真的把当前栈顶喂进来」属组合期行为（同 #107/#108 的限制，仓库无 Compose UI 测试基建），
 * 真机判据：慢来源上点抽屉「阅读器」后，在 ≤1.5s 等待里按返回 / 切到别的顶层入口，阅读器**不得**再被压栈。
 *
 * 本文件的每个断言都对着**行为**（判定结果），不是读回入参：去掉「离开那一屏」这半边或把 token 换回值相等，
 * 都会有对应用例变红（两条反例的验红记录见 `evidence-impl.md`）。
 */
class ReaderEntryRequestTest {

    private val requests = ReaderEntryRequest()

    @Test
    fun `仍停在发起时那一屏 算数`() {
        val request = requests.begin(Routes.SETTINGS)

        assertTrue(requests.isCurrent(request, Routes.SETTINGS))
    }

    @Test
    fun `被后一次点击顶替 旧的不算数`() {
        val first = requests.begin(Routes.SETTINGS)
        val second = requests.begin(Routes.HOME)

        assertFalse("被顶替的那次不导航", requests.isCurrent(first, Routes.HOME))
        assertTrue("只有最新那次算数", requests.isCurrent(second, Routes.HOME))
    }

    @Test
    fun `等待窗口里离开那一屏 不算数`() {
        val request = requests.begin(Routes.SETTINGS)

        assertFalse("返回 / 切到别的顶层入口之后不得再压阅读器", requests.isCurrent(request, Routes.HOME))
        assertFalse("栈顶尚未定（null）时同样按「不是那一屏」处理", requests.isCurrent(request, null))
    }

    @Test
    fun `A B A 三连点后只有最后一次算数`() {
        // 值相等会撞 ABA：A→B→A 之后**旧** A 请求被重新判为「当前」，与新 A 请求各导航一次
        val first = requests.begin(Routes.READER)
        requests.begin(Routes.READER)
        val third = requests.begin(Routes.READER)

        assertFalse("第一次 A 已被顶替（哪怕它发起时那一屏与现在相同）", requests.isCurrent(first, Routes.READER))
        assertTrue("只有最后一次 A 算数", requests.isCurrent(third, Routes.READER))
    }

    @Test
    fun `同一屏上连点两次也各领一个不复用的 token`() {
        val first = requests.begin(Routes.HOME)
        val second = requests.begin(Routes.HOME)

        assertNotEquals("token 单调递增，不复用", first.token, second.token)
        assertFalse("后一次点击顶掉前一次", requests.isCurrent(first, Routes.HOME))
        assertTrue(requests.isCurrent(second, Routes.HOME))
    }
}
