package com.cc3301.comicviewer.core.source.smb

/**
 * 「SMB 会话建立成功之后打点」的所有判定与次序（票 #113 r3）：**与 smbj 无关，因此 JVM 可测**。
 *
 * 为什么抽出来：smbj 的 `SMBClient`/`Connection`/`Session` 在单测里不可注入（`SmbjTransport` 直接 new），
 * 一次建连的三步（connect → authenticate → connectShare）跑不出来；但这一环恰恰容易写错，而且写错会
 * **把维护者的判读带反**——本票只有一次真机取数的机会。仓库既有先例是同一手法：
 * `remote/RemoteRetry.retryOnce` 把「何时重连」抽成纯函数，让传输实现只剩「把 reconnect 传对」。
 *
 * 两个承重语义：
 * 1. **`rebuilt` 的真相 = 此前是否已经成功建立过会话**，不是 `share != null` 这种间接征兆——
 *    重连路径（`withRetry` → `closeQuietly`）在进入 [establish] 之前就已把 `share` 置空，
 *    用 `share != null` 会把「刚被丢掉的死会话重新建起来」报成**首次建连**（r2 的实际缺陷）。
 * 2. **打点在成功之后**：`open` 抛异常（认证失败/超时——正是本票要排查的场景）时既不打点也不改状态，
 *    因此「看到这一行」就等价于「一条可用会话已经建立」。
 *
 * 线程安全：调用方（`SmbjTransport.connectedShare`）是 `@Synchronized`，本类不再自己加锁（一处互斥就够）。
 */
internal class SmbSessionReporter(
    /** 打点落地（调用方接 `PerfTiming.log`）：参数 = 本次建立是否属于重建 */
    private val report: (rebuilt: Boolean) -> Unit,
) {

    /** 此前是否**成功建立过**会话：重建判定的唯一真相（跨 `closeQuietly` 存活，不能用 `share` 代替） */
    private var establishedBefore = false

    /**
     * 本次会话建立是否属于**重建**：`true` = 此前已经成功建立过一次（断链/空闲断开后的重连、或换共享）。
     * 第一次成功建立报 `false`。
     */
    val rebuilt: Boolean get() = establishedBefore

    /**
     * 跑一次真正的建连动作，**成功后**才 [report]：`open` 抛异常时异常照常上抛、不打点、不置状态
     * （会话没建立起来，不该留下一行「已建立」）。返回值原样透传，调用点因此与应用 smbj 时同形。
     */
    fun <T> establish(open: () -> T): T {
        val result = open()
        report(establishedBefore)
        establishedBefore = true
        return result
    }
}
