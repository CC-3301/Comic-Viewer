package com.cc3301.comicviewer.core.source.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * SMB 会话建立的打点次序与「是否重连」的判定（票 #113 r3 的 P1 修复）。
 *
 * 为什么测这里而不是 `SmbjTransport`：smbj 的 `SMBClient`/`Connection`/`Session` 在单测里不可注入
 * （`SmbjTransport` 直接 new），真实建连跑不出来。因此按仓库既有先例（`remote/RemoteRetry.retryOnce`
 * 把「何时重连」抽成纯函数）把「成功后打点 + 重建判定」摘进 [SmbSessionReporter]，在这里锁死两条语义：
 *
 * ① **判定**：重连（第二次成功建立）必须报 `rebuilt=true`。r2 用的是 `share != null`，而重连路径
 *    （`withRetry` → `closeQuietly`）在进入建连之前就把 `share` 置空了 ⇒ 报成首次建连、维护者会把
 *    「连接抖动/重连」这条正确根因排除掉。这条用例锁的就是「判定跨 `closeQuietly` 存活」。
 * ② **时机**：建连动作抛异常时**不打点**（本票场景里认证失败/超时正是要排查的那一类）——
 *    打点若跑在 connect/authenticate/connectShare 之前，失败也会留下一行「会话建立」。
 */
class SmbSessionReporterTest {

    /** 记下每次上报的 `rebuilt`（生产里这个 lambda 接 `PerfTiming.log`） */
    private val reported = mutableListOf<Boolean>()

    private val reporter = SmbSessionReporter { reported += it }

    @Test
    fun `首次建立报重建为假`() {
        val share = reporter.establish { "共享句柄" }

        assertEquals("establish 要把结果原样透传", "共享句柄", share)
        assertEquals("首次建立不是重建", listOf(false), reported)
    }

    @Test
    fun `断链重连报重建为真`() {
        reporter.establish { "第一次" }

        // 重连路径同样是「先丢掉旧句柄再建」：判定必须跨这次丢弃存活（r2 的 `share != null` 会报 false）
        val second = reporter.establish { "第二次" }

        assertEquals("第二次建立就是重建", listOf(false, true), reported)
        assertEquals("第二次的结果照旧透传", "第二次", second)
    }

    @Test
    fun `建立失败不打点且异常照常上抛`() {
        // 失败的这一次：既不上报、也不把状态推成「建立过」
        assertThrows(IllegalStateException::class.java) {
            reporter.establish<String> { throw IllegalStateException("不是磁盘共享") }
        }
        assertEquals("失败的建立不该留下「会话已建立」这一行", emptyList<Boolean>(), reported)

        // 之后再成功一次：仍算首次（上一次压根没建立起来）
        reporter.establish { "修好了" }
        assertEquals(listOf(false), reported)
    }
}
