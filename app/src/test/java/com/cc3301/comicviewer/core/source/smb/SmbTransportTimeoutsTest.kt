package com.cc3301.comicviewer.core.source.smb

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SMB 传输的两个超时常量。
 *
 * 为什么用常量钉住：`SmbjTransport` 本身在 JVM 上不可单测（smbj 的 `SMBClient` 直接 new，
 * 见 `SmbSessionGate` 的 KDoc），而这两个数就是 2026-09-29 定的口径——
 * 常量是这条口径唯一能在门禁上盯住的东西（与 `SmbSessionHeartbeat.PROBE_INTERVAL_MS`、
 * `SmbSessionGate.MAX_CONCURRENT_SESSION_READS` 被用例钉住同一个手法）。
 *
 * 两条判据的因果：
 * ① 一次失败的连接最坏要占满建连超时（与会话停摆日志里那个 15.1 秒数值吻合）⇒ 15 秒压到 **10 秒**，
 *    一次会话失效的最坏等待随之下降（实际值由 `smbRebuild connectMs=` 给出）；
 * ② **读超时是与建连超时不同的另一个量**（`withSoTimeout`），明确不动它——把两者一起改
 *    会把「一条卡死的读最长占住多久」也悄悄改掉，那不在这次改动的范围内。
 */
class SmbTransportTimeoutsTest {

    @Test
    fun `建连超时 10 秒`() {
        assertEquals(
            "票 #113 修法 2：15 秒 → 10 秒（维护者拍板；5 秒太激进）",
            10L,
            SmbjTransport.CONNECT_TIMEOUT_SECONDS,
        )
    }

    @Test
    fun `读超时保持 45 秒 别与建连超时混改`() {
        assertEquals(
            "读超时（withSoTimeout）与建连超时是两个量，本票不动它",
            45L,
            SmbjTransport.READ_TIMEOUT_SECONDS,
        )
    }
}
