package com.cc3301.comicviewer.ui

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `runCatching` 的取消安全版（票 #26 登记项，票 25 第 1 项落地）：只吞非取消异常。
 *
 * `runCatching { withContext(...) { … } }` 会把 withContext 进出时抛出的 CancellationException
 * 一起吞掉，于是「组合已销毁/配置变更」被当成「会话建不起来」，调用方继续往下导航与写库。
 * 启动路径的四处调用点都用它，取消才真的照常传播。
 */
class CancellationTest {

    @Test
    fun `正常返回包成成功的 Result`() {
        assertEquals(7, catchingNonCancellation { 7 }.getOrNull())
    }

    @Test
    fun `非取消异常变成失败的 Result`() {
        val failure = catchingNonCancellation { throw IllegalStateException("连接配置不可用") }.exceptionOrNull()

        assertEquals("连接配置不可用", failure?.message)
    }

    @Test
    fun `取消原样抛出 不变成失败`() {
        val thrown = runCatching {
            catchingNonCancellation { throw CancellationException("组合已销毁") }
        }.exceptionOrNull()

        assertTrue("取消必须传播，不能被吞成失败的 Result", thrown is CancellationException)
    }
}
