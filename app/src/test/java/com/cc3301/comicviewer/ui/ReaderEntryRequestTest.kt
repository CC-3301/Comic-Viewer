package com.cc3301.comicviewer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「不在浏览页点书」入口的请求判定（票 #111 r2 修复 P1/P2、r3 换成栈项身份，钉 [ReaderEntryRequest]）。
 *
 * 为什么这条守卫要抽成可断言的一处：抽屉「阅读器」入口的等待跑在 `AppNav` 的组合作用域上（只有整个 AppNav
 * 离开组合才取消），而读内换书那条的「离开即取消」靠阅读页的页内作用域——两者共用的判定只有这里能单测；
 * 「`AppNav` 调用点是否真的把栈顶那一项喂进来」属组合期行为（同 #107/#108 的限制，仓库无 Compose UI 测试基建），
 * 真机判据：慢来源上点抽屉「阅读器」后，在 ≤1.5s 等待里按返回 / 切到别的顶层入口 / **从子文件夹回到父目录**，
 * 阅读器**不得**再被压栈。
 *
 * 本文件的每个断言都对着**行为**（判定结果），不是读回入参：去掉「仍是那一项」这半边、把 token 换回值相等、
 * 或把栈项身份退回「只比路由 pattern」，都会有对应用例变红（三次验红记录见 `evidence-impl.md`）。
 */
class ReaderEntryRequestTest {

    private val requests = ReaderEntryRequest()

    /** 栈顶那一项：路由 pattern + back stack entry id（生产由 [ReaderEntryRequest.keyOf] 取） */
    private fun key(route: String, entryId: String) = ReaderEntryRequest.EntryKey(route, entryId)

    @Test
    fun `仍停在发起时那一项 算数`() {
        val origin = key(Routes.SETTINGS, "entry-settings")

        val request = requests.begin(origin)

        assertTrue(requests.isCurrent(request, origin))
    }

    @Test
    fun `被后一次点击顶替 旧的不算数`() {
        val settings = key(Routes.SETTINGS, "entry-settings")
        val home = key(Routes.HOME, "entry-home")
        val first = requests.begin(settings)
        val second = requests.begin(home)

        assertFalse("被顶替的那次不导航", requests.isCurrent(first, home))
        assertTrue("只有最新那次算数", requests.isCurrent(second, home))
    }

    @Test
    fun `等待窗口里切到别的顶层入口 不算数`() {
        val origin = key(Routes.SETTINGS, "entry-settings")
        val request = requests.begin(origin)

        assertFalse("切到首页之后不得再压阅读器", requests.isCurrent(request, key(Routes.HOME, "entry-home")))
        assertFalse("栈顶尚未定（null）时同样按「不是那一项」处理", requests.isCurrent(request, null))
    }

    @Test
    fun `等待窗口里子文件夹回到父目录 不算数`() {
        // 浏览层级是**同一个 destination、同一个 pattern、不同参数**：子文件夹与父目录的 route 字符串一模一样，
        // 只有 back stack entry 的 id 不同。只比 route（r2 的写法）会把「按返回回到父目录」判成「没离开」，
        // 于是用户刚按了返回、阅读器仍被压进栈（r3 收口的分支）。
        val subfolder = key(Routes.BROWSER, "entry-browser-sub")
        val parent = key(Routes.BROWSER, "entry-browser-parent")
        val request = requests.begin(subfolder)

        assertFalse("同一个 pattern 的两个不同栈项是两次离开", requests.isCurrent(request, parent))
        assertTrue("仍停在同一项时照旧算数（对照）", requests.isCurrent(request, subfolder))
    }

    @Test
    fun `A B A 三连点后只有最后一次算数`() {
        // 值相等会撞 ABA：A→B→A 之后**旧** A 请求被重新判为「当前」，与新 A 请求各导航一次
        val reader = key(Routes.READER, "entry-reader")
        val first = requests.begin(reader)
        requests.begin(reader)
        val third = requests.begin(reader)

        assertFalse("第一次 A 已被顶替（哪怕它发起时那一项与现在相同）", requests.isCurrent(first, reader))
        assertTrue("只有最后一次 A 算数", requests.isCurrent(third, reader))
    }

    @Test
    fun `同一项上连点两次也各领一个不复用的 token`() {
        val home = key(Routes.HOME, "entry-home")
        val first = requests.begin(home)
        val second = requests.begin(home)

        assertNotEquals("token 单调递增，不复用", first.token, second.token)
        assertFalse("后一次点击顶掉前一次", requests.isCurrent(first, home))
        assertTrue(requests.isCurrent(second, home))
    }
}
