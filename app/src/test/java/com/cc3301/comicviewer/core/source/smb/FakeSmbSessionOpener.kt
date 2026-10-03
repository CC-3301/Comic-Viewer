package com.cc3301.comicviewer.core.source.smb

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 假替身的会话句柄：只带「还连着吗」这一个状态（**中途断开** = 把它置 `false`）。
 * 生产里的句柄是 `SmbjSessionHandle`（共享 + 会话 + 连接 + 客户端四件套），这里只需要这一位。
 */
internal class FakeSmbSessionHandle(val generation: Int) {

    @Volatile
    var alive = true

    @Volatile
    var closed = false

    override fun toString(): String = "会话#" + generation
}

/**
 * 「连服务器」的测试替身（可替换口，见 [SmbSessionOpener]）：
 * **连得上 / 连不上（[failures]）/ 连上之后中途断开（把句柄置 `alive=false`）**，
 * 并记下建了几条会话、拿到过几号句柄、每次是第几次尝试。
 *
 * 会话生命周期那套逻辑（就绪/重建中/退避中/已关闭、代次、心跳、打点判定）在本替身上跑得出用例：
 * 生产那一侧是 smbj 的 `SMBClient`（单测里不可注入），信号全由这个替身给。
 */
internal class FakeSmbSessionOpener : SmbSessionOpener<FakeSmbSessionHandle> {

    /** 接下来几次建连要失败（按顺序弹出；空 = 建得起来）：连不上的那几次 */
    val failures = mutableListOf<Throwable>()

    /** 建成功的次数 = 一共建过几条会话 */
    val opened = AtomicInteger()

    /** open 被调用的次数（含失败的那几次）：判「退避窗口内一条都没尝试」靠它 */
    val openCalls = AtomicInteger()

    /** 每次 open 拿到的 `attempt`（进 `smbRebuild attempt=` 的那个数），按顺序 */
    val attempts = mutableListOf<Int>()

    /** 每次 open 拿到的上一代句柄（`null` = 没有旧句柄），按顺序 */
    val previousHandles = mutableListOf<FakeSmbSessionHandle?>()

    /** 非空时：open 进来先 [openEntered] 倒计数、再等 [openGate]（用例用来把「重建在飞」捏成可控） */
    @Volatile
    var openEntered: CountDownLatch? = null

    @Volatile
    var openGate: CountDownLatch? = null

    /** 已经关掉的句柄数（重建前丢旧的、生命周期释放时关的，都算） */
    val closedHandles = AtomicInteger()

    override fun open(previous: FakeSmbSessionHandle?, attempt: Int): FakeSmbSessionHandle {
        synchronized(this) {
            openCalls.incrementAndGet()
            attempts += attempt
            previousHandles += previous
        }
        openEntered?.countDown()
        openGate?.await(5, TimeUnit.SECONDS)
        previous?.let { close(it) }
        synchronized(this) { failures.removeFirstOrNull() }?.let { throw it }
        val generation = opened.incrementAndGet()
        return FakeSmbSessionHandle(generation)
    }

    override fun isAlive(handle: FakeSmbSessionHandle): Boolean = handle.alive && !handle.closed

    override fun close(handle: FakeSmbSessionHandle) {
        handle.closed = true
        closedHandles.incrementAndGet()
    }
}
