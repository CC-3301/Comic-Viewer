package com.cc3301.comicviewer.core.source.smb

import com.cc3301.comicviewer.core.source.PerfTiming
import com.cc3301.comicviewer.core.source.SourceDiagnostics
import com.cc3301.comicviewer.core.source.remote.BlockCachedRandomAccess
import com.cc3301.comicviewer.core.source.remote.ClassifyingRandomAccess
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
 * - 服务端空闲断链/网络闪断时丢弃会话重连一次，
 *   随机访问句柄中途断开则把错误上抛给调用方（下一页会重新建立会话）；
 * - **本类退化成薄壳**（票 #151）：四个入口操作都过 [SmbSessionLifecycle]（会话句柄 / 代次 /
 *   就绪·重建中·退避中·已关闭 / 心跳 / 打点判定都在那一处），这里只剩「拿句柄跑一次读」与 smbj 语义；
 *   [SmbjSessionOpener] 是「连服务器」这一步的生产实现（可替换口，见 [SmbSessionOpener]）；
 * - 三条诊断打点（默认关、零开销，开关就是 `PerfTiming`，行格式在 `SourceDiagnostics`）：
 *   `smbReadFail`（读失败那一刻：操作 / 等了多久 / 类型 / 异常类名）、`smbRebuild`（第几次尝试 +
 *   四段耗时 + 失败在哪一段）、`smbProbe`（心跳每一拍真探还是跳过、结果与耗时）。
 *
 * 本类依赖 Android 网络栈与真实 SMB 服务器，故不做单元测试：协议之上的行为由
 * SmbSourceContractTest（FakeSmbTransport）覆盖，会话生命周期那套逻辑由
 * `SmbSessionLifecycleTest`（假替身）覆盖，设备链路走验收清单。
 */
class SmbjTransport(private val config: SmbConnectionConfig) : SmbTransport {

    /**
     * 会话生命周期（票 #151）：句柄、代次、就绪/重建中/退避中/已关闭、心跳、打点判定都在它手里。
     * 三个打点口在这里接线（判定逻辑全在被调方）：
     * `smbSessionOpen`（建立成功之后，`rebuilt` 的真相 = 此前建立过）、
     * `smbReadFail`（每一次尝试失败，含「失败后重试成功」的那一次）、
     * `smbProbe`（心跳每一拍真探还是跳过）。
     */
    private val lifecycle = SmbSessionLifecycle(
        opener = SmbjSessionOpener(config),
        host = config.host,
        // 探针走的是现成的 stat + 同一条读链 ⇒ 它撞上死会话时走的就是已有的重建路径
        probe = {
            stat(SmbPaths.ROOT)
            true
        },
        onSessionEstablished = { rebuilt ->
            PerfTiming.log { SourceDiagnostics.smbSessionOpenLine(config.host, config.port, config.share, rebuilt) }
        },
        onReadFailed = { op, ms, appReleased, failure ->
            PerfTiming.log {
                SourceDiagnostics.smbReadFailLine(
                    op = op.token,
                    ms = ms,
                    kind = classifySmbReadFail(failure, appReleased = appReleased).token,
                    ex = failure.javaClass.simpleName,
                )
            }
        },
        onProbe = { probed, ok, ms ->
            PerfTiming.log { SourceDiagnostics.smbProbeLine(probed = probed, ok = ok, ms = ms) }
        },
    )

    override fun list(path: String): List<SmbEntry> = lifecycle.withSession(SmbReadOp.LIST) { handle ->
        val sh = handle.share
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

    override fun stat(path: String): SmbEntry? = lifecycle.withSession(SmbReadOp.STAT) { handle ->
        val sh = handle.share
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

    override fun readBytes(path: String): ByteArray = lifecycle.withSession(SmbReadOp.BYTES) { handle ->
        openFile(handle.share, SmbPaths.toWindows(SmbPaths.normalize(path))).use { file ->
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
        val file = lifecycle.withSession(SmbReadOp.RANDOM_ACCESS) { handle -> openFile(handle.share, win) }
        // 读/关闭发生在后台线程：断链必须归类成 SmbException（TransportFailure）而不是裸 IO 异常，
        // 否则上层会把「网络断了」当成「这本 CBZ 损坏」而静默显示 0 页
        return ClassifyingRandomAccess(SmbRandomAccess(file)) {
            asSmbException(it, config.host, config.share + path)
        }
    }

    override fun close() {
        lifecycle.close()
    }

    // ---------- 内部 ----------

    private fun openFile(sh: DiskShare, windowsPath: String): SmbFile =
        sh.openFile(
            windowsPath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )

    private fun libraryConfig(): LibrarySmbConfig = LibrarySmbConfig.builder()
        .withTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .withSoTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** `internal`：两个超时常量由 [SmbTransportTimeoutsTest] 用例钉住（不会被改回去了） */
    internal companion object {
        /**
         * 建连超时（秒）：15 秒 → **10 秒**（2026-09-29 定；5 秒太激进）。
         * 一次失败的连接最坏要占这么久（与会话停摆日志里那个 15.1 秒数值吻合），
         * 压到 10 秒把一次会话失效的最坏等待降下来（`smbRebuild connectMs=` 给出实际值）。
         */
        internal const val CONNECT_TIMEOUT_SECONDS = 10L

        /** 读超时（秒）：与建连超时是**两个量**，不改它。 */
        internal const val READ_TIMEOUT_SECONDS = 45L

        private const val READ_CHUNK_BYTES = 256 * 1024
        private const val PREFETCH_LIMIT_BYTES = 4L * 1024 * 1024
    }
}

/**
 * smbj 一条会话的四件套：共享句柄 + 会话 + 连接 + 客户端。
 * 它是 [SmbSessionLifecycle] 手里那个「会话句柄」（票 #151 把句柄收进模块），四件一起关才是真关。
 */
internal class SmbjSessionHandle(
    val share: DiskShare,
    private val session: Session,
    private val connection: Connection,
    private val client: SMBClient,
) {

    /** smbj 的 `isConnected()` 只是本地标志（会话被服务端作废时不立即反映），但这是这一层能拿到的唯一判据 */
    val isAlive: Boolean get() = share.isConnected

    fun close() {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }
}

/**
 * 「连服务器」的生产实现（[SmbSessionOpener]）：smbj 的连接 → 认证 → 进共享三步，
 * 另加关旧会话那一段。**分段计时与 `smbRebuild` 行留在这里**——四段（关闭/连接/认证/进共享）
 * 就是 smbj 的步骤顺序，行的格式由 [SourceDiagnostics] 给定；「第几次尝试」由生命周期给。
 */
internal class SmbjSessionOpener(private val config: SmbConnectionConfig) : SmbSessionOpener<SmbjSessionHandle> {

    override fun open(previous: SmbjSessionHandle?, attempt: Int): SmbjSessionHandle {
        val startedNanos = System.nanoTime()
        // 当前正在跑的那一段（失败时 `finally` 就报它）：就是「失败在哪一段」
        var segment = SmbRebuildSegment.CLOSE
        var closeMs = 0L
        var connectMs = 0L
        var authMs = 0L
        var shareMs = 0L
        var failure: Throwable? = null
        var newClient: SMBClient? = null
        var newConnection: Connection? = null
        var newSession: Session? = null
        try {
            // 旧句柄先丢掉：重连路径上它是死会话，留着只会让下一条读再撞一次
            closeMs = timed { previous?.close() }.second
            segment = SmbRebuildSegment.CONNECT
            val client = SMBClient(libraryConfig())
            newClient = client
            val (connection, connectElapsedMs) = timed { client.connect(config.host, config.port) }
            connectMs = connectElapsedMs
            newConnection = connection
            segment = SmbRebuildSegment.AUTH
            val (session, authElapsedMs) = timed {
                connection.authenticate(
                    AuthenticationContext(config.username, config.password.toCharArray(), config.domain),
                )
            }
            authMs = authElapsedMs
            newSession = session
            segment = SmbRebuildSegment.SHARE
            val (connected, shareElapsedMs) = timed {
                session.connectShare(config.share) as? DiskShare
                    ?: throw IllegalStateException("不是磁盘共享：" + config.share)
            }
            shareMs = shareElapsedMs
            return SmbjSessionHandle(connected, session, connection, client)
        } catch (t: Throwable) {
            failure = t
            // 半截的会话也要丢：已经建出来的连接/客户端不关掉就是一条没人管的 socket
            // （句柄模型下它们已经不在生命周期手里，只能在这里收）
            runCatching { newSession?.close() }
            runCatching { newConnection?.close() }
            runCatching { newClient?.close() }
            throw t
        } finally {
            val failedAt = failure?.let { segment }
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

    override fun isAlive(handle: SmbjSessionHandle): Boolean = handle.isAlive

    override fun close(handle: SmbjSessionHandle) {
        handle.close()
    }

    /** 从 [startedNanos] 到现在有多少毫秒（`smbRebuild` 的分段耗时用同一个口径） */
    private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / NANOS_PER_MS

    /** 跑一段建连步骤并计时（毫秒）：四段各计一段才有「那十几秒花在哪一段」这条判读 */
    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val started = System.nanoTime()
        val value = block()
        return value to elapsedMs(started)
    }

    private fun libraryConfig(): LibrarySmbConfig = LibrarySmbConfig.builder()
        .withTimeout(SmbjTransport.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .withSoTimeout(SmbjTransport.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private companion object {
        private const val NANOS_PER_MS: Long = 1_000_000L
    }
}

/**
 * smbj 文件句柄的随机访问适配（ZIP 中央目录与按条目解压都基于它）。
 * 块缓存与「读失败归类」由 core/source/remote 的共享实现提供（与 WebDAV 同一套）。
 *
 * 注意 [size] 不是内存字段：它是 `SmbFile.getLength()`，即一次 QUERY_INFO 往返（smbj 0.15.0）；
 * 块缓存所以只解析一次长度，根因见 `BlockCachedRandomAccess.handleSize`。
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