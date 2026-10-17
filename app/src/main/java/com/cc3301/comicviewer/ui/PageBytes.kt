package com.cc3301.comicviewer.ui

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.CacheKeys
import com.cc3301.comicviewer.core.source.moveOverExistingTarget
import com.cc3301.comicviewer.core.view.CoverByteGate
import com.cc3301.comicviewer.core.view.CoverBytePriority
import com.cc3301.comicviewer.core.view.InFlightRequests
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

/**
 * 页字节模块：一本漫画某一页的**原始字节**从哪来。
 *
 * 接口只有 [load]（给我某书某页的字节）。取数闸、同页在飞合并、页磁盘缓存、取消三态都是它的实现细节——
 * 界面看不到这些词。
 *
 * 依赖由构造传入（[diskCache] 与打点出口 [log]）：漏配在装配处就是编译不过，
 * 不会静默降级成「每次都向来源取」。
 */
internal class PageBytes(
    /** 页磁盘缓存：装配处构造（见 `ServiceLocator.pageBytes`） */
    private val diskCache: PageDiskCache,
    /** 打点出口：由调用方传入（生产 = `PerfTiming`，开关关着时连字符串都不拼） */
    private val log: (() -> String) -> Unit,
) {

    /**
     * 页字节取数的**在飞合并 + 闸**（`InFlightRequests` / `CoverByteGate` 的页侧实例，封面那两个在浏览页那侧）：
     * 同一页被可见页与预览格同时需要时只向来源取一次，同时最多 [PAGE_MAX_CONCURRENT_LOADS] 张页在飞。
     * **每个模块实例一份**：页只在阅读器里取、一次只读一本书，装配处因此只建一个。
     */
    private val requests = InFlightRequests<String, PageBytesFetched>(CoverByteGate(PAGE_MAX_CONCURRENT_LOADS))

    /**
     * 磁盘感知取页：磁盘命中跳过 [BookHandle.loadPage]。
     * 打点把「磁盘命中」与「向来源取」分开报，尖峰落在哪一段一眼看得出。
     * **不发 `net=`**（它只是 `disk=` 的取反，却暗示「走了网络」），改由来源侧的
     * `loadPage ... from=image|archive` 区分「是不是压缩包内页」——判读规则只有在分开两条路之后才对：
     * - `from=image`：`node.readBytes()`，**每一次都真读一次来源**（远端 = 网络往返），这条路上不发 `remoteRead`，
     *   所以 `disk=false` + 没有 `remoteRead` **不能**推出「没走网络」；
     * - `from=archive`：包内读可能被 `BlockCachedRandomAccess` 的进程内块缓存接住，这时再看同期有没有 `remoteRead`。
     * 完整判读规则（含 `source=`/`instance=` 对齐与两个 `from=` 分支）收在 [com.cc3301.comicviewer.core.source.SourceDiagnostics]。
     *
     * 本入口过**页取数闸**（[PAGE_MAX_CONCURRENT_LOADS] 张在飞）与**同页在飞合并**（[requests]）：
     * 三个调用点（阅读页可见页 / 阅读菜单预览格 / 开书前置）共用，不必各自接线。
     * [source] 与 [attempt] 因此必填：每一次尝试都产一行 `pageAttempt`；打点的时间零点由本入口自己取。
     *
     * `pageAttempt` 判读口径：
     * - `source=`：[PageLoadSource]（可见页 / 预览格 / 开书前置）。同一页被两方同时需要时**两方各产一行**；
     * - `attempt=`：1-based，本行是这一次调用方的第几次尝试（预览格的有界重试见 `ui/PagePreviewLoad`）；
     * - `result=`：`success`（字节到手）/ `failed`（来源抛了非取消异常）/ `cancelled`（这次尝试没人要了，
     *   **不是失败**——翻页、格子滚走、换书都走它）；
     * - `disk=`：字节来自页磁盘缓存（与同一次 `pageBytes` 行的同名字段同源）；
     * - `waitMs=`：从**本入口被调用那一刻**到这一次取数的 IO 段真正开始之间的等待，
     *   即等牌 + 等在飞合并；IO 段已经在跑（合并进来的等待方）时为 0；
     * - `ms=`：这一次取数的 IO 段长度（查磁盘 + 向来源取 + 落磁盘），与同一次 `pageBytes` 行的 `ms=` 同源。
     *
     * 行的产出方是「真在取数的那一方」：合并进来的等待方拿共享的那份量测，因此同一次取数的两行
     * `disk=`/`ms=` 相同、`waitMs=` 各按自己的调用时刻算。取消与失败**也各产一行**（`pageBytes` 只在读成功后打）。
     */
    suspend fun load(handle: BookHandle, index: Int, source: PageLoadSource, attempt: Int = 1): ByteArray {
        // 打点的时间零点在本入口取：调用方只交「哪条路」与「第几次尝试」
        val trace = PageLoadTrace(source, attempt, System.nanoTime())
        // 真取数的那一方（在飞合并的主人）在 [fetch] 里产行；等待方在下面自己产
        var ownerLogged = false
        try {
            val fetched = requests.load(CacheKeys.pageDisk(handle.id, index), pageLoadPriorityOf(trace.source)) {
                ownerLogged = true
                fetch(handle, index, trace)
            }
            if (!ownerLogged) logPageAttempt(handle.id, index, trace, fetched)
            return fetched.bytes
        } catch (c: CancellationException) {
            if (!ownerLogged) {
                logPageAttemptAborted(handle.id, index, trace, PageLoadResult.Cancelled, null, System.nanoTime())
            }
            throw c
        } catch (t: Throwable) {
            if (!ownerLogged) {
                logPageAttemptAborted(handle.id, index, trace, PageLoadResult.Failed, null, System.nanoTime())
            }
            throw t
        }
    }

    /** [load] 闸内的真取数（磁盘 → 来源 → 落磁盘）：既有 `pageBytes` 与新的 `pageAttempt` 两行都从这里产 */
    private suspend fun fetch(handle: BookHandle, index: Int, trace: PageLoadTrace): PageBytesFetched {
        val key = CacheKeys.pageDisk(handle.id, index)
        val startedNanos = System.nanoTime()
        try {
            val cached = diskCache.get(key)
            val bytes = cached ?: handle.loadPage(index).bytes
            if (cached == null) diskCache.put(key, bytes)
            val doneNanos = System.nanoTime()
            log {
                "pageBytes book=" + handle.id + " index=" + index + " disk=" + (cached != null) +
                    " bytes=" + bytes.size + " ms=" + ((doneNanos - startedNanos) / 1_000_000)
            }
            val fetched = PageBytesFetched(bytes, cached != null, startedNanos, doneNanos)
            logPageAttempt(handle.id, index, trace, fetched)
            return fetched
        } catch (c: CancellationException) {
            logPageAttemptAborted(handle.id, index, trace, PageLoadResult.Cancelled, startedNanos, System.nanoTime())
            throw c
        } catch (t: Throwable) {
            logPageAttemptAborted(handle.id, index, trace, PageLoadResult.Failed, startedNanos, System.nanoTime())
            throw t
        }
    }

    /** 成功那一行：`disk=`/`ms=` 取共享量测（合并的两方因此一致），`waitMs=` 按本方的需要图时刻算 */
    private fun logPageAttempt(book: String, index: Int, trace: PageLoadTrace, fetched: PageBytesFetched) =
        emitPageAttempt(
            book = book,
            index = index,
            trace = trace,
            result = PageLoadResult.Success,
            diskHit = fetched.diskHit,
            waitMs = millisBetween(trace.askedNanos, fetched.ioStartNanos),
            ms = millisBetween(fetched.ioStartNanos, fetched.doneNanos),
        )

    /** 取消 / 失败那一行：[ioStartNanos] 为 null = 这一次连 IO 段都没进（还在等牌或等在飞结果） */
    private fun logPageAttemptAborted(
        book: String,
        index: Int,
        trace: PageLoadTrace,
        result: PageLoadResult,
        ioStartNanos: Long?,
        doneNanos: Long,
    ) = emitPageAttempt(
        book = book,
        index = index,
        trace = trace,
        result = result,
        diskHit = false,
        waitMs = millisBetween(trace.askedNanos, ioStartNanos ?: doneNanos),
        ms = if (ioStartNanos == null) 0L else millisBetween(ioStartNanos, doneNanos),
    )

    private fun emitPageAttempt(
        book: String,
        index: Int,
        trace: PageLoadTrace,
        result: PageLoadResult,
        diskHit: Boolean,
        waitMs: Long,
        ms: Long,
    ) {
        log {
            "pageAttempt book=" + book + " index=" + index + " source=" + trace.source.token +
                " attempt=" + trace.attempt + " result=" + result.token + " disk=" + diskHit +
                " waitMs=" + waitMs + " ms=" + ms
        }
    }
}

/**
 * 页取数闸的闸位：**3**（同时最多三张页在飞，见 `docs/spec/reader.md` 的「页取数」）。
 *
 * 下限是 2：落地的那一页虽然在闸上有优先，但**已经在飞的不抢占**——闸位是 1 时它得先等前面那张
 * （多半是刚滑走的预览格）取完才轮得到。上限也不能大：闸位越大，同时占着来源的请求越多，
 * 排在队尾的那一页等得越久（拖动时大多数请求已经没人要了）。
 * 取 3 配预览的自限：预览条自己的取解最多 2 个在飞（`PreviewThumbLoads.MAX_IN_FLIGHT`），
 * 因此闸上总留着一个位给阅读页——落地的那一页不必排在预览格后面（闸位取 2 时预览能把两个位占满）。
 */
internal const val PAGE_MAX_CONCURRENT_LOADS: Int = 3

/**
 * 页字节模块的**组合期提供点**：`MainActivity` 在 `setContent` 里提供（提供点唯一一处），
 * 界面按 [LocalPageBytes] 取实例。没提供就报错——漏接是接线错，不静默降级
 *（与 `LocalSessionState` 同一口径）。
 */
internal val LocalPageBytes: ProvidableCompositionLocal<PageBytes> = compositionLocalOf {
    error("没有提供页字节模块（提供点在 MainActivity，见 ui/PageBytes.kt）")
}

/** 一次页取数的来源（`pageAttempt` 行的 `source=`）：决定过闸的排队序与判读归属 */
internal enum class PageLoadSource(val token: String) {

    /** 阅读页此刻可见的那几页：闸上取牌排在预览之前 */
    Visible("visible"),

    /** 阅读菜单预览格的缩略图：排队时让位给可见页 */
    Preview("preview"),

    /** 开书前置：与可见页同档（它也是用户在等的那一页） */
    Prelude("prelude"),
}

/** 页取数闸上这一页的排队序：只有预览格让位，其余（可见页 / 开书前置）都是「可见」档 */
internal fun pageLoadPriorityOf(source: PageLoadSource): CoverBytePriority =
    if (source == PageLoadSource.Preview) CoverBytePriority.Prefetch else CoverBytePriority.Visible

/** 一次页取数尝试的上下文：[attempt] 是这一次调用方的第几次尝试（1-based），[askedNanos] 是 [PageBytes.load] 进门那一刻 */
private class PageLoadTrace(val source: PageLoadSource, val attempt: Int, val askedNanos: Long)

/** 一次页字节取数的结果与量测：在飞合并时两方共用同一份（同一次取数） */
internal class PageBytesFetched(
    val bytes: ByteArray,
    /** 字节来自页磁盘缓存（与同一次 `pageBytes` 行的 `disk=` 同源） */
    val diskHit: Boolean,
    /** 进 IO 段（查磁盘 / 向来源取）那一刻 */
    val ioStartNanos: Long,
    /** 字节到手那一刻 */
    val doneNanos: Long,
)

/** 一次页取数尝试的结局（`pageAttempt` 行的 `result=`） */
internal enum class PageLoadResult(val token: String) {
    Success("success"),

    /** 这次没拿到（来源抛了非取消异常）：值得重试 */
    Failed("failed"),

    /** 这次尝试没人要了（翻页 / 格子滚走 / 换书）：**不是失败** */
    Cancelled("cancelled"),
}

/** 两个单调时钟读数之间的整毫秒（负差夹到 0，不打负数段） */
private fun millisBetween(fromNanos: Long, toNanos: Long): Long =
    ((toNanos - fromNanos) / 1_000_000).coerceAtLeast(0L)

/**
 * 原始页字节磁盘缓存：键=bookId#index 的 SHA-256 前 32 位十六进制
 * （bookId 含 content:// 等非法文件名字符）。超上限按 lastModified 从旧到新淘汰至 80%。
 *
 * 清理时机：**不在取页路径上**——[put] 只写字、累加一个字节计数，再把清理排到 [trimExecutor]；
 * 清理本身按批（一趟最多删 [trimBatchSize] 个文件；还没到目标线**且本趟确有文件被删**才再排一趟，
 * 一个都没删掉就停到下次写入）。因此「取一页要多久」与缓存目录里有多少文件无关。
 * **不能在 [put] 里同步扫目录**：缓存目录逼近上限后，listFiles + 排序 + 删除会落在翻页路径上
 * （秒级尖峰的来源之一；判读按 `PerfTiming` 的 `pageBytes` / `pageDecode` / `pageShown` / `diskTrim` 打点归属）。
 *
 * 计数只用来决定「要不要排一趟清理」：本进程内它从 0 起，而目录跨进程存在，因此进程内第一次 [put]
 * 无条件排一趟，把上一个进程遗留的占用核出来（不然计数会一路偏低，缓存会涨到两倍上限）。
 * 清理趟把计数重算成扫描到的真实占用（扫描期间新写入的仍在计数里），
 * 因此扫描与写入并发时计数可能偏高（同一个文件既被写入计数、又被扫描到）——偏高只会早清一点，不会越界。
 */
internal class PageDiskCache(
    private val dir: File,
    private val maxBytes: Long = PAGE_DISK_CACHE_MAX_BYTES,
    /** 清理任务的后台执行器（默认单线程 + 守护线程）；测试注入手工执行器来观察「什么时候清」 */
    private val trimExecutor: Executor = newTrimExecutor(),
    /** 一趟清理最多删几个文件（分批；见类注释） */
    private val trimBatchSize: Int = PAGE_CACHE_TRIM_BATCH,
    /** 打点出口：由调用方传入（生产 = `PerfTiming`） */
    private val log: (() -> String) -> Unit,
) {

    init {
        // 0 或负值会让一趟清理一个文件都删不掉（批为空）——缓存从此永远不会被清，会无上限涨下去
        require(trimBatchSize > 0) { "一趟清理至少要能删一个文件：trimBatchSize=$trimBatchSize" }
    }

    /** 当前占用的近似值（[put] 累加、清理按扫描结果重算）：只用来触发清理，不当准数用 */
    private val sizeBytes = AtomicLong(0L)

    /** 本进程还没核过一次目录真实占用（第一次 [put] 时核） */
    private val needsReconcile = AtomicBoolean(true)

    /** 已有清理排在执行器上（合并同一段时间里的多次触发） */
    private val trimQueued = AtomicBoolean(false)

    fun get(key: String): ByteArray? = try {
        val f = fileFor(key)
        // 0 长度 = 上次非原子写残留的截断文件，视为 miss
        if (f.exists() && f.length() > 0) f.readBytes() else null
    } catch (t: Throwable) {
        null
    }

    /**
     * 原子写：先写 .tmp 再改名，避免截断文件被读到；改名是**覆盖式**的——同一个键的第二次写
     * 在 Windows 上不能因 `File.renameTo` 不覆盖而静默失效（共用实现见 `core/source/AtomicFileMove.kt`，
     * 三处调用点共用：本处、`ListingSnapshotStore.write`、`CoverDescent.writeCoverCacheFile`）。清理交给后台。
     */
    fun put(key: String, bytes: ByteArray) {
        try {
            dir.mkdirs()
            val target = fileFor(key)
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeBytes(bytes)
            try {
                moveOverExistingTarget(tmp, target)
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
            sizeBytes.addAndGet(bytes.size.toLong())
            scheduleTrimIfNeeded()
        } catch (t: Throwable) {
            // 缓存写失败不影响阅读
        }
    }

    /** 计数字节越上限（或本进程还没核过遗留占用）就排一趟清理 */
    private fun scheduleTrimIfNeeded() {
        if (!needsReconcile.getAndSet(false) && sizeBytes.get() <= maxBytes) return
        scheduleTrim()
    }

    /** 把清理排到 [trimExecutor]；已有排队任务时不再排（执行器上最多压一趟） */
    private fun scheduleTrim() {
        if (!trimQueued.compareAndSet(false, true)) return
        runCatching { trimExecutor.execute { trimQueued.set(false); runTrim() } }
            .onFailure { trimQueued.set(false) }
    }

    /**
     * 一趟后台清理：扫一次目录拿真实占用（写回 [sizeBytes]，把计数对齐实际），由 [PageCacheTrim]
     * 判出该删哪些，按 [trimBatchSize] 分批删；还有可删的且本趟**确有文件被删掉**时才再排一趟。
     * 全程在 [trimExecutor] 上，取页线程不参与。
     *
     * 删除用 `listFiles()` 交出的那些 `File` 句柄（不按名字另造 `File`）：删除成败是续排判据，
     * 测试也由此注入「删除失败」（拿一个交不出可删句柄的目录）而无需给生产类再加接缝。
     */
    private fun runTrim() {
        val startedNanos = System.nanoTime()
        // 扫描前写入的字节先取走：它们对应的文件已在磁盘上，会被下面的扫描算进 total，
        // 不能再加一次（只有扫描没跑成时才把 pending 补回，免得白丢）
        val pending = sizeBytes.getAndSet(0L)
        val listed = dir.listFiles()
        if (listed == null) {
            sizeBytes.addAndGet(pending)
            return
        }
        val files = ArrayList<PageCacheFile>(listed.size)
        var total = 0L
        for (f in listed) {
            if (!f.isFile) continue
            val size = f.length()
            total += size
            files += PageCacheFile(name = f.name, sizeBytes = size, lastModifiedMs = f.lastModified())
        }
        // 计数重算成扫描到的真实占用；扫描期间新写入的字节仍在 sizeBytes 里（可能被扫描重复计入，
        // 也可能尚未入账）——两种都只是偏高，不会越界
        sizeBytes.addAndGet(total)
        val doomed = PageCacheTrim.filesToDelete(files, maxBytes)
        if (doomed.isEmpty()) return
        val batch = doomed.take(trimBatchSize)
        val handles = listed.associateBy { it.name }
        var freed = 0L
        var deleted = 0
        for (file in batch) {
            if (handles[file.name]?.delete() == true) {
                freed += file.sizeBytes
                deleted++
            }
        }
        sizeBytes.addAndGet(-freed)
        log {
            "diskTrim scanned=" + files.size + " bytes=" + total + " deleted=" + deleted +
                " freed=" + freed + " ms=" + ((System.nanoTime() - startedNanos) / 1_000_000)
        }
        // 还没到目标线且本趟确实删掉了东西，才续排下一批；一个都没删掉就停——
        // 删除一直失败时继续续排，就是反复「扫全目录 + 重试删除」——大目录重扫正是要避的
        if (freed > 0 && total - freed > PageCacheTrim.targetBytesOf(maxBytes)) scheduleTrim()
    }

    private fun fileFor(key: String): File = File(dir, CacheKeys.pageDiskFileName(key))
}

/** 页字节磁盘缓存的上限：200MB；只作 [PageDiskCache] 的默认值 */
private const val PAGE_DISK_CACHE_MAX_BYTES: Long = 200L * 1024 * 1024

/** 一趟后台清理最多删的文件数（分批，不与取页抢磁盘） */
internal const val PAGE_CACHE_TRIM_BATCH: Int = 64

/** 清理用的后台单线程执行器（守护线程：它不持有进程） */
private fun newTrimExecutor(): Executor =
    Executors.newSingleThreadExecutor { r -> Thread(r, "page-cache-trim").apply { isDaemon = true } }

/** [PageCacheTrim] 的入参：缓存目录里的一个文件，只带判定要用的三项 */
internal data class PageCacheFile(val name: String, val sizeBytes: Long, val lastModifiedMs: Long)

/**
 * 页字节磁盘缓存的淘汰判定（从 [PageDiskCache] 的清理里提出的纯逻辑，不碰文件系统，单独可测）。
 *
 * 口径：总占用不超上限 → 一个都不删；超了 → 按修改时间从旧到新删，直到落到上限的 80%
 * （留余量，否则每次写入都要清一次）。
 */
internal object PageCacheTrim {

    /** 目标线的比例：上限的 80%（既有口径） */
    private const val TARGET_NUMERATOR = 8
    private const val TARGET_DENOMINATOR = 10

    /** 淘汰目标线：删到这个占用就不再删 */
    fun targetBytesOf(maxBytes: Long): Long = maxBytes * TARGET_NUMERATOR / TARGET_DENOMINATOR

    /**
     * 该删哪些文件：超上限时给出「最旧先删、删到目标线」的淘汰表（未超上限 = 空表）。
     * 修改时间相同时按文件名定序，判定不随扫描顺序变化。
     */
    fun filesToDelete(files: List<PageCacheFile>, maxBytes: Long): List<PageCacheFile> {
        var remaining = files.sumOf { it.sizeBytes }
        if (remaining <= maxBytes) return emptyList()
        val target = targetBytesOf(maxBytes)
        val doomed = mutableListOf<PageCacheFile>()
        for (file in files.sortedWith(compareBy({ it.lastModifiedMs }, { it.name }))) {
            if (remaining <= target) break
            remaining -= file.sizeBytes
            doomed += file
        }
        return doomed
    }
}
