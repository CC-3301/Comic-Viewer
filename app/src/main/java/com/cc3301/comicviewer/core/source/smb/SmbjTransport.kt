package com.cc3301.comicviewer.core.source.smb

import com.cc3301.comicviewer.core.source.PerfTiming
import com.cc3301.comicviewer.core.source.SourceDiagnostics
import com.cc3301.comicviewer.core.source.remote.BlockCachedRandomAccess
import com.cc3301.comicviewer.core.source.remote.ClassifyingRandomAccess
import com.cc3301.comicviewer.core.source.remote.isRecoverableRemoteFailure
import com.cc3301.comicviewer.core.source.remote.retryOnce
import com.cc3301.comicviewer.core.source.zip.RandomAccessBytes
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue.EnumUtils.isSet
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig as LibrarySmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.io.ByteArrayOutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * 真实 SMB 传输：基于 smbj（SMB2/3），共享句柄按需建立、断链后自动重连一次。
 *
 * 设计要点：
 * - 只抛底层异常，由 [ClassifyingTransport] 统一归类成「地址不通 / 认证失败 / 超时」等用户可读提示；
 * - [openRandomAccess] 保留一个打开的文件句柄，供 ZIP 中央目录解析与按条目解压做 seek 读，
 *   因此 CBZ 不必整包下载（spec：解析中央目录 + 随机访问）；
 * - 服务端空闲断链/网络闪断时丢弃会话重连一次（issue #12 AC4），
 *   随机访问句柄中途断开则把错误上抛给调用方（下一页会重新建立会话）；
 * - 四个入口操作都过 [SmbSessionGate]（修法第 2 条）：会话重建期间进来的读**等在闸上**
 *   （等内存里的信号，不是各自的 socket），就绪后一次只放 4 条 → 真机日志里「三十几条封面读
 *   各自卡在死会话上 7.6~14.9 秒、重建成功后一起返回」那种形态被消掉；
 * - **重建失败退避 1s → 2s → 4s（封顶 8s）**（修法第 1 条，[SmbRebuildBackoff]）：
 *   退避窗口内进来的读**就地失败**（不排队、不再建），会话建起来即清零 —— 真机日志里「十几秒里
 *   连续失败十几次连接」被压到「最多几次，而且每次都是立刻返回」；
 * - 会话建立成功后起一条**探活心跳**（修法第 3 条）：空闲时每 30 秒读一次共享根，
 *   让 App 在用户之前撞上被服务端作废的死会话并重建，见 [SmbSessionHeartbeat]；
 * - 三条诊断打点（默认关、零开销，开关就是 `PerfTiming`，行格式在 `SourceDiagnostics`）：
 *   `smbReadFail`（读失败那一刻：操作 / 等了多久 / 类型 / 异常类名）、`smbRebuild`（第几次尝试 +
 *   四段耗时 + 失败在哪一段）、`smbProbe`（心跳每一拍真探还是跳过、结果与耗时）。
 *
 * 本类依赖 Android 网络栈与真实 SMB 服务器，故不做单元测试：协议之上的行为由
 * SmbSourceContractTest（FakeSmbTransport）覆盖，真机链路走票面验收清单。
 */
class SmbjTransport(private val config: SmbConnectionConfig) : SmbTransport {

    @Volatile
    private var client: SMBClient? = null

    @Volatile
    private var connection: Connection? = null

    @Volatile
    private var session: Session? = null

    /**
     * 会话建立的打点与「是否重连」的判定：
     * 打点在 connect→authenticate→connectShare **全部成功之后**才发，判定看「此前是否成功建立过」
     * （不能看 `share != null`——重连路径上它已被 `closeQuietly` 置空）。两者都在
     * [SmbSessionReporter] 里，那一处可 JVM 单测。
     */
    private val sessionReporter = SmbSessionReporter { rebuilt ->
        PerfTiming.log { SourceDiagnostics.smbSessionOpenLine(config.host, config.port, config.share, rebuilt) }
    }

    /** 会话就绪闸门：重建期间挡在读的前面，就绪后分批放行，详见 [SmbSessionGate] */
    private val sessionGate = SmbSessionGate()

    /** 重建失败的退避（修法第 1 条，详见 [SmbRebuildBackoff]）：窗口内进来的读就地失败 */
    private val rebuildBackoff = SmbRebuildBackoff()

    /**
     * 探活心跳（修法第 3 条，详见 [SmbSessionHeartbeat]）：会话建好之后每 30 秒（空闲时）
     * 读一次共享根。探针走的是现成的 [stat] + 同一条 [withSession] 链，因此它撞上死会话时走的
     * 就是已有的重建路径（`invalidate` → 重建 → `settled`）。
     *
     * 每一拍都打一条 `smbProbe`（打点第 3 条）：真机日志里 50 秒空闲本该有 1~2 次探活，
     * 而当时探针**成功不产行** ⇒ 心跳到底跑没跑只能推测（见-09-29 那段）。
     */
    private val sessionHeartbeat = SmbSessionHeartbeat(
        probe = ::probeShareRoot,
        report = { probed, ok, ms ->
            PerfTiming.log { SourceDiagnostics.smbProbeLine(probed = probed, ok = ok, ms = ms) }
        },
    )

    /** 本实例是否已被释放（`close()` 之后）：迟到的读一律失败，不再建新会话 */
    @Volatile
    private var released = false

    @Volatile
    private var share: DiskShare? = null

    override fun list(path: String): List<SmbEntry> = withSession(SmbReadOp.LIST) { sh ->
        val base = SmbPaths.normalize(path)
        sh.list(SmbPaths.toWindows(base))
            .asSequence()
            // smbj 的目录列举会带 "." 与 ".." 伪条目
            .filterNot { SmbPaths.isPseudoEntry(it.fileName) }
            .map { info ->
                SmbEntry(
                    path = SmbPaths.child(base, info.fileName),
                    name = info.fileName,
                    isDirectory = isSet(info.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
                    lastModifiedMs = info.lastWriteTime?.toEpochMillis(),
                    size = info.endOfFile,
                )
            }
            .sortedBy { it.name }
            .toList()
    }

    override fun stat(path: String): SmbEntry? = withSession(SmbReadOp.STAT) { sh ->
        val norm = SmbPaths.normalize(path)
        if (norm == SmbPaths.ROOT) {
            // 共享根没有可 stat 的对象：用一次列举确认连接与访问权限
            sh.list("")
            SmbEntry(SmbPaths.ROOT, config.share, isDirectory = true, lastModifiedMs = null, size = 0)
        } else {
            val win = SmbPaths.toWindows(norm)
            val name = norm.substringAfterLast('/')
            when {
                sh.folderExists(win) -> {
                    val info = sh.getFileInformation(win)
                    SmbEntry(norm, name, isDirectory = true, lastModifiedMs = info.basicInformation.lastWriteTime?.toEpochMillis(), size = 0)
                }
                sh.fileExists(win) -> {
                    val info = sh.getFileInformation(win)
                    SmbEntry(
                        path = norm,
                        name = name,
                        isDirectory = false,
                        lastModifiedMs = info.basicInformation.lastWriteTime?.toEpochMillis(),
                        size = info.standardInformation.endOfFile,
                    )
                }
                else -> null
            }
        }
    }

    override fun readBytes(path: String): ByteArray = withSession(SmbReadOp.BYTES) { sh ->
        openFile(sh, SmbPaths.toWindows(SmbPaths.normalize(path))).use { file ->
            val size = file.length
            val out = ByteArrayOutputStream(size.coerceIn(0L, PREFETCH_LIMIT_BYTES).toInt())
            val buffer = ByteArray(READ_CHUNK_BYTES)
            var offset = 0L
            while (offset < size) {
                val read = file.read(buffer, offset)
                if (read <= 0) break
                out.write(buffer, 0, read)
                offset += read
            }
            out.toByteArray()
        }
    }

    override fun openRandomAccess(path: String): RandomAccessBytes {
        val win = SmbPaths.toWindows(SmbPaths.normalize(path))
        val file = withSession(SmbReadOp.RANDOM_ACCESS) { sh -> openFile(sh, win) }
        // 读/关闭发生在后台线程：断链必须归类成 SmbException（TransportFailure）而不是裸 IO 异常，
        // 否则上层会把「网络断了」当成「这本 CBZ 损坏」而静默显示 0 页
        return ClassifyingRandomAccess(SmbRandomAccess(file)) {
            asSmbException(it, config.host, config.share + path)
        }
    }

    override fun close() {
        // 先置闸：等在闸上的读就地失败退出，而不是被放行后各自去建一条新会话
        released = true
        sessionGate.close()
        sessionHeartbeat.stop()
        closeQuietly()
    }

    // ---------- 内部 ----------

    /**
     * 一次探活：读一次共享根（票面口径「走现成的 stat 与同一条 withSession 链」）。
     *
     * 返回 `false` = **这一拍什么都没探**：`share` 为 null 只可能是「没建过 / 正被拆掉重建
     * （含退避窗口）/ 已释放」，而这三种情况下探针什么都不做——它的职责是保活，不是把一个没被用过的
     * 来源拉起来联网（下一次真实读自己会走建立路径）。失败原样抛出，由 [SmbSessionHeartbeat] 吞掉并退避。
     *
     * 返回 `false` 而不是把空转报成一次成功还有一个判读上的原因：退避窗口里探针本来就什么都不做，
     * 若报成成功（`ms=0`），真机上会把「空转」误读成「心跳每 30 秒探活成功」。
     */
    private fun probeShareRoot(): Boolean {
        if (share == null || released) return false
        stat(SmbPaths.ROOT)
        return true
    }

    private fun openFile(sh: DiskShare, windowsPath: String): SmbFile =
        sh.openFile(
            windowsPath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )

    /**
     * 共享句柄：未连接/已断开则重建（认证失败等在这里抛出，由装饰器归类）。
     *
     * 两件事都在这里：
     * - **退避**（修法 1）：上一次重建失败之后的一整个窗口里，这一指针就地失败，不再付一次建连等待；
     * - **分段计时 + 打点**（打点 2）：关旧会话 / 连接 / 认证 / 进共享各计一段，不论成败都打一条
     *   `smbRebuild`（成功那行是「一次会话失效到底等了多久」的唯一真数）。
     */
    @Synchronized
    private fun connectedShare(): DiskShare {
        share?.takeIf { it.isConnected }?.let { return it }
        // 实例已释放：连「已在飞那条读的重试」也不该把会话建回来（释放后仍会新建会话是 r1 评审的 P2）
        if (released || sessionGate.isClosed) throw releasedFailure()
        // 退避窗口里不再尝试重建：进闸之后到建连之前可能刚好失败，因此这里再拦一次
        if (rebuildBackoff.isBackingOff()) throw backoffFailure()
        val attempt = rebuildBackoff.nextAttempt()
        val startedNanos = System.nanoTime()
        // 当前正在跑的那一段（失败时 `finally` 就报它）：就是票面「失败在哪一段」
        var segment: SmbRebuildSegment = SmbRebuildSegment.CLOSE
        var closeMs = 0L
        var connectMs = 0L
        var authMs = 0L
        var shareMs = 0L
        var failure: Throwable? = null
        try {
            // 旧句柄先丢掉：重连路径（withSession → closeQuietly）也走这里，而它已把 share 置空——
            // 因此「是不是重连」的判定不能看 share，见 SmbSessionReporter
            closeMs = timed { closeQuietly() }.second
            segment = SmbRebuildSegment.CONNECT
            val newShare = sessionReporter.establish {
                val newClient = SMBClient(libraryConfig())
                client = newClient
                val (newConnection, connectElapsedMs) = timed { newClient.connect(config.host, config.port) }
                connectMs = connectElapsedMs
                connection = newConnection
                segment = SmbRebuildSegment.AUTH
                val (newSession, authElapsedMs) = timed {
                    newConnection.authenticate(
                        AuthenticationContext(config.username, config.password.toCharArray(), config.domain),
                    )
                }
                authMs = authElapsedMs
                session = newSession
                segment = SmbRebuildSegment.SHARE
                val (connected, shareElapsedMs) = timed {
                    newSession.connectShare(config.share) as? DiskShare
                        ?: throw IllegalStateException("不是磁盘共享：" + config.share)
                }
                shareMs = shareElapsedMs
                connected
            }
            share = newShare
            rebuildBackoff.recordSuccess()
            sessionGate.settled()
            // 会话就绪之后才起心跳：它盯的是「这条已经建好的会话会不会被服务端作废」
            sessionHeartbeat.start()
            return newShare
        } catch (t: Throwable) {
            failure = t
            rebuildBackoff.recordFailure()
            // 建不起来也要放行，否则闸上等着的读永远醒不来（错误照旧由各自的调用方上抛）
            sessionGate.settled()
            throw t
        } finally {
            val failedAt = if (failure == null) null else segment
            PerfTiming.log {
                SourceDiagnostics.smbRebuildLine(
                    attempt = attempt,
                    closeMs = closeMs,
                    connectMs = connectMs,
                    authMs = authMs,
                    shareMs = shareMs,
                    ms = elapsedMs(startedNanos),
                    failedSegment = failedAt?.token,
                    ex = failure?.javaClass?.simpleName,
                )
            }
        }
    }

    /**
     * 一次读的完整流程（修法第 2 条）：**等会话就绪 + 占一个名额** →「连接层故障重连一次」。
     * 非连接类错误（认证/不存在/权限）直接上抛，不做无意义重试。
     *
     * **退避窗口内进来的读就地失败**（修法 1）：不排队、不碰 socket、不再建——封面那条由
     * `DocumentTreeSource.coverBytes`（与 `CoverThumb`）吞成 null ⇒ 屏上继续骨架，阅读器取页则拿到一条中文提示。
     * 因此这个判断放在进闸**之前**：排队等一个明知建不起来的会话没有意义。
     */
    private fun <T> withSession(op: SmbReadOp, block: (DiskShare) -> T): T {
        val startedNanos = System.nanoTime()
        if (rebuildBackoff.isBackingOff()) throw loggedReadFail(op, startedNanos, backoffFailure())
        return sessionGate.withPermit { usedEpoch ->
            // 会话刚被真实读用过 → 心跳的下一拍不必再探（探针自己也走这里，SmbSessionHeartbeat 会把它自己的记数清掉）
            sessionHeartbeat.noteActivity()
            // 我拆的会话，就必须由一个出口保证放行（挂在「重建中」= 后面所有读一起挂住，比多放一批糟得多）
            var rebuiltByMe = false
            try {
                retryOnce(
                    isRecoverable = ::isRecoverableRemoteFailure,
                    // 只有「我用过的还是当前这一代」时才由我拆会话：别人已经拆过就直接重试，
                    // 否则每次失败都把别人刚建好的会话推倒（真机日志里 30+ 个 worker 各重连一次）
                    reconnect = {
                        rebuiltByMe = sessionGate.invalidate(usedEpoch)
                        // 已释放就不拆了：重试会直接失败退出（见 connectedShare），不再拉起一条新会话
                        if (rebuiltByMe && !released) closeQuietly()
                    },
                    // 每一次**尝试**失败各记一条 `smbReadFail`：含「失败后重试成功」的那一次——
                    // 那正是会话失效被发现的时刻，也是旧日志里完全空白的那一刻
                    block = { attempt(op, startedNanos) { block(connectedShare()) } },
                )
            } catch (t: Throwable) {
                // 拆会话那条读再失败也要喊放行（`settled` 只在「重建中」生效，因此这里不会重复补名额）
                if (rebuiltByMe) sessionGate.settled()
                throw t
            }
        }
    }

    /** 跑一次读的尝试：失败时就地记一条 `smbReadFail` 再上抛（每次尝试各一条，不重复记） */
    private fun <T> attempt(op: SmbReadOp, startedNanos: Long, body: () -> T): T = try {
        body()
    } catch (t: Throwable) {
        throw loggedReadFail(op, startedNanos, t)
    }

    /**
     * 记一条 `smbReadFail`（失败那一刻：操作 / 从发起到失败等了多久 / 类型 / 异常类名），异常原样返回便于 `throw`。
     * `appReleased` 取当前释放位：会话是 App 自己关的，就不该把失败记到对端头上（见 [classifySmbReadFail]）。
     */
    private fun <T : Throwable> loggedReadFail(op: SmbReadOp, startedNanos: Long, failure: T): T {
        PerfTiming.log {
            SourceDiagnostics.smbReadFailLine(
                op = op.token,
                ms = elapsedMs(startedNanos),
                kind = classifySmbReadFail(failure, appReleased = released).token,
                ex = failure.javaClass.simpleName,
            )
        }
        return failure
    }

    /** 退避窗口内的失败形态（修法 1）：一条中文提示、`kind=backoff` 一类，不排队也不再建 */
    private fun backoffFailure(): SmbRebuildBackedOffException =
        SmbRebuildBackedOffException("SMB 会话正在重连，稍后重试（" + config.host + "）")

    /** 跑一段建连步骤并计时（毫秒）：四段各计一段才有「那十几秒花在哪一段」这条判读 */
    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val started = System.nanoTime()
        val value = block()
        return value to elapsedMs(started)
    }

    /** 从 [startedNanos] 到现在有多少毫秒（打点里的 `ms=` 统一这个口径） */
    private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

    /** 实例已释放后的统一失败形态（与闸上超期退出同一句提示，调用方看到的是同一个中文原因） */
    private fun releasedFailure(): SmbException =
        SmbException(SmbFailureKind.OTHER, "来源已释放：SMB 会话已关闭")

    @Synchronized
    private fun closeQuietly() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        share = null
        session = null
        connection = null
        client = null
    }

    private fun libraryConfig(): LibrarySmbConfig = LibrarySmbConfig.builder()
        .withTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .withSoTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** `internal`：两个超时常量就是票面口径，由 [SmbTransportTimeoutsTest] 用例钉住（不会被改回去了） */
    internal companion object {
        /**
         * 建连超时（秒）： 修法第 2 处——15 秒 → **10 秒**（维护者 2026-09-29 拍板；5 秒太激进）。
         * 一次失败的连接最坏要占这么久（与会话停摆日志里那个 15.1 秒数值吻合），
         * 压到 10 秒把一次会话失效的最坏等待降下来（`smbRebuild connectMs=` 给出实际值）。
         */
        internal const val CONNECT_TIMEOUT_SECONDS = 10L

        /** 读超时（秒）：与建连超时是**两个量**，本票不动它（票面明确） */
        internal const val READ_TIMEOUT_SECONDS = 45L

        private const val READ_CHUNK_BYTES = 256 * 1024
        private const val PREFETCH_LIMIT_BYTES = 4L * 1024 * 1024
    }
}

/**
 * smbj 文件句柄的随机访问适配（ZIP 中央目录与按条目解压都基于它）。
 * 块缓存与「读失败归类」由 core/source/remote 的共享实现提供（与 WebDAV 同一套）。
 *
 * 注意 [size] 不是内存字段：它是 `SmbFile.getLength()`，即一次 QUERY_INFO 往返（smbj 0.15.0）；
 * 块缓存所以按 #91 的口径只解析一次长度，根因与实测见 `BlockCachedRandomAccess.handleSize`。
 */
internal class SmbRandomAccess(
    private val file: SmbFile,
) : BlockCachedRandomAccess() {

    override val size: Long get() = file.length

    /** 直读（必要时补齐）；句柄中途断开时异常上抛，由上层归类装饰器转成 SmbException */
    override fun fetch(offset: Long, len: Int): ByteArray {
        val out = ByteArray(len)
        var done = 0
        while (done < len) {
            val read = file.read(out, offset + done, done, len - done)
            if (read <= 0) break
            done += read
        }
        return if (done == len) out else out.copyOf(done)
    }

    override fun close() {
        runCatching { file.close() }
    }
}
