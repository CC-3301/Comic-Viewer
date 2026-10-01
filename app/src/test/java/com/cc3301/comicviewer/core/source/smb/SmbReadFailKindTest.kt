package com.cc3301.comicviewer.core.source.smb

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * 读失败那一刻的归类（票 #113 打点 1 的 `smbReadFail kind=`）。
 *
 * 为什么单独测：本票只有一次真机取数的机会，`kind=` 报错会把结论直接带反——
 * 最危险的一类是**把 App 自己关会话报成「对端断开」**（那时维护者会去找网络/服务端的问题，
 * 而真凶在 App 侧）；其次是「退避期内的快速拒绝」与真读失败混在一起，那样「修法 1 生效了没有」
 * 就看不出来。两类的判定就收在 [classifySmbReadFail]。
 *
 * 判定复用仓库既有的两处口径（[classifySmbFailure] 的超时标记与 [isRecoverableRemoteFailure]
 * 的连接层故障），因此这里只钉「哪个输入落到哪一类」，不重测那两处。
 */
class SmbReadFailKindTest {

    @Test
    fun `对端断开`() {
        // 连接层故障：传输异常 / socket 断开 / 半包——与「值不值得重连」同一套判定
        assertEquals(
            SmbReadFailKind.PEER_CLOSED,
            classifySmbReadFail(SocketException("Connection reset"), appReleased = false),
        )
    }

    @Test
    fun `读超时`() {
        assertEquals(
            SmbReadFailKind.READ_TIMEOUT,
            classifySmbReadFail(SocketTimeoutException("Read timed out"), appReleased = false),
        )
    }

    @Test
    fun `App 主动关优先于超时与断链`() {
        // 传输已释放时，一条在飞的读撞上的失败可能长成超时或断链的样子，但那是 App 自己关的会话
        assertEquals(
            SmbReadFailKind.APP_CLOSE,
            classifySmbReadFail(SocketTimeoutException("Read timed out"), appReleased = true),
        )
        assertEquals(
            SmbReadFailKind.APP_CLOSE,
            classifySmbReadFail(
                SmbException(SmbFailureKind.OTHER, "来源已释放：SMB 会话已关闭"),
                appReleased = true,
            ),
        )
    }

    @Test
    fun `退避期内的快速拒绝单列一类`() {
        assertEquals(
            SmbReadFailKind.BACKOFF,
            classifySmbReadFail(SmbRebuildBackedOffException("SMB 会话正在重连，稍后重试"), appReleased = false),
        )
    }

    @Test
    fun `其余归其它`() {
        assertEquals(
            SmbReadFailKind.OTHER,
            classifySmbReadFail(SmbException(SmbFailureKind.NOT_FOUND, "路径不存在：/x"), appReleased = false),
        )
    }

    @Test
    fun `四个入口操作各有自己的 token`() {
        // token 直接进真机日志，改字面量等于改口径（`op=` 用来分「列目录也卡」还是「只有封面卡」）
        assertEquals(
            listOf("list", "stat", "bytes", "ra"),
            SmbReadOp.entries.map { it.token },
        )
    }
}
