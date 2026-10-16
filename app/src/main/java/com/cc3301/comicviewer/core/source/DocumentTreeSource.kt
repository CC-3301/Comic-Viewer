package com.cc3301.comicviewer.core.source

import com.cc3301.comicviewer.core.concurrent.catchingNonCancellation
import com.cc3301.comicviewer.core.meta.parseReleaseDate
import com.cc3301.comicviewer.core.meta.toEpochMillis
import com.cc3301.comicviewer.core.order.WindowsNameOrder
import com.cc3301.comicviewer.core.source.fs.FsBackend
import com.cc3301.comicviewer.core.source.fs.FsNode
import com.cc3301.comicviewer.core.source.zip.ZipArchive
import com.cc3301.comicviewer.core.source.zip.ZipEntry
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 图片扩展名（spec：jpg/jpeg/png/webp/gif；gif 读静态首帧） */
val IMAGE_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png", "webp", "gif")

/** 压缩包扩展名（spec 故事 54：CBZ/ZIP） */
val ARCHIVE_EXTENSIONS: Set<String> = setOf("cbz", "zip")

/**
 * 子目录属性探测的并发上限：每条探测 = 一次 list/PROPFIND 往返（SMB/WebDAV）
 * 或一次 provider IPC（SAF），无上限会把 NAS/网盘打爆，故设固定上限。
 */
const val SUBDIR_PROBE_LIMIT: Int = 8

/** 会话级列表缓存里「来源根容器」的键（`listEntries(null)` 的 containerId）：来源内的条目 id 恒非空 */
private const val ROOT_CONTAINER_ID: String = ""

/**
 * 会话级列表缓存的对象量级上界：一条 = 一个「容器 × 排序方式」，与一次会话浏览到的目录数同阶（百级）。
 * 来源实例跨页面存活（见 `SessionState.browsingSourceFor`），故给个简单上界防止长会话无上限增长；
 * 超出即整体清空，代价只是下次进入重列一次。
 */
private const val LIST_CACHE_MAX_ENTRIES: Int = 256

/**
 * 探测期首图结点缓存的上界（量级同封面字节缓存）：只记「目录内首图是哪个结点」，不记字节。
 */
private const val PROBED_FIRST_IMAGE_MAX_ENTRIES: Int = CoverByteCache.DEFAULT_MAX_ENTRIES

/**
 * 上次枚举留下的**子目录探测结论**（增量重探）：判定「能否复用」所需的字段，
 * 因此判定是纯函数（[canReuseProbe]）、可单测。条目身份（id）不进这里——调用方按 id 查表，
 * 「查不到 = 新增或改名 = 必须探测」由那次查找承担。
 */
internal data class ProbeConclusion(
    /** 该子目录**自身**的 mtime（列目录/探测时顺手拿到的；后端不可得为 null） */
    val mtimeMs: Long?,
    /** 上次探测成功；false = 那条降级为容器的失败条目，必须重试、一律不复用 */
    val probed: Boolean,
)

/**
 * 能否复用上次探测结论：纯函数、无 I/O（只读新条目的 mtime），增量重探的**唯一判定点**。
 *
 * 两条同时成立才复用：该子目录**自己**没变（mtime 相同）、上次探测成功。
 * - mtime 相同 = 它内部没动过，「是书还是文件夹」与封面指向因此与上次结论一致；
 * - 两边都取不到 mtime（SMB 共享根这类层）视同「没变」——与会话缓存口径一致；
 * - 探测失败的条目不复用：它下次必须重试，增量路径不是例外。
 */
internal fun canReuseProbe(previous: ProbeConclusion?, current: FsNode): Boolean =
    previous != null && previous.probed && previous.mtimeMs == current.lastModifiedMs

/**
 * 一次枚举的请求计数（验收打点）。
 *
 * 由调用方自建并交给 [DocumentTreeSource.enumerateEntries]：生产把它打进 logcat，单测直接读它
 * （三个计数只进 logcat，没有别的观测面），因此字段天然是「本次」而不是累计。
 * 三个计数各自只有一个自增点：`childrenCalls` 在枚举本层那一处的 `children()`、`probes` 在 `probeSubdirs`
 * 的入口、`reused` 在增量判定命中那处。整数自增是每次枚举的常数级开销；字符串拼接与平台调用仍全在
 * `PerfTiming.log {}` 的惰性 lambda 里（开关关闭时不拼字符串、不碰平台类）。
 */
internal class EnumerationStats(
    /** 本次列目录次数（生产里 = SMB list / SAF provider IPC 往返；**每次枚举恒 ≥ 1**） */
    var childrenCalls: Int = 0,
    /** 本次子目录探测条数（生产里 = 每条一次 list/PROPFIND；「探测 0 次」按它核对） */
    var probes: Int = 0,
    /** 本次增量判定命中的条数（沿用上次结论、因此没发探测请求的条数） */
    var reused: Int = 0,
)

/** 打点里的三个请求计数段：枚举与邻位补齐两处打点共用同一形状，不会两处走样 */
internal fun countsLog(stats: EnumerationStats): String =
    "childrenCalls=" + stats.childrenCalls + " probes=" + stats.probes + " reused=" + stats.reused

/**
 * 枚举打点行（纯函数，可单测）：字段口径只此一处。
 *
 * **它与邻位两行的 `snapshot=<bool>` 不是同一个键**（那是「邻位判定依据是否命中」口径）。
 * `childrenCalls`/`probes`/`reused` 是本次的三个请求计数：`childrenCalls` 恒 ≥ 1（每次进目录都真列一次），
 * `probes=0` 就是「本次一行都没变」的证据。
 */
internal fun enumerationLogLine(
    containerId: String?,
    sort: SortMode,
    entries: Int,
    stats: EnumerationStats,
    ms: Long,
): String = "listEntries container=" + (containerId ?: "<root>") + " sort=" + sort +
    " entries=" + entries + " " + countsLog(stats) + " ms=" + ms

fun FsNode.isImageFile(): Boolean = !isDirectory && name.substringAfterLast('.', "").lowercase(Locale.ROOT) in IMAGE_EXTENSIONS

/** 压缩包文件（CBZ/ZIP）：整包作为一本书，页序 = 包内图片文件名自然序 */
fun FsNode.isArchiveFile(): Boolean = !isDirectory && name.substringAfterLast('.', "").lowercase(Locale.ROOT) in ARCHIVE_EXTENSIONS

/** 包内条目是否算一页（目录条目不算） */
fun isArchiveImageEntry(entryName: String): Boolean {
    if (entryName.endsWith("/")) return false
    val ext = entryName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return ext in IMAGE_EXTENSIONS
}

fun mimeTypeOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    else -> "application/octet-stream"
}

/**
 * 一个目录**这一层**的内容分区：本层图片、本层子目录、本层压缩包，三者都按条目名称自然序排好。
 *
 * 这是「一个目录里有什么」的**唯一判定点**（浏览列表的 `isBook` 与页序列装配要的「本层图片」都由它给出，
 * 因此不会有两套口径）；打开容器只读本层图片、不并入下级（`pagesOfBook` 的目录分支）。
 * 分区只看本层，不递归下取。
 */
internal data class DirContents(
    val images: List<FsNode>,
    val subDirs: List<FsNode>,
    val archives: List<FsNode>,
) {
    /**
     * 这个目录本身是不是一本书：**本层只有图片**才算。
     *
     * 本层有子目录或压缩包 ⇒ 它是容器：子目录与压缩包在浏览列表里各是一条独立条目（点一个读一个、各自是一本），
     * 本层图片也各自是条目（点一张 = 从该张连读本层其余图片）。
     * 旧口径下本层有图或压缩包就算书，并把本层压缩包的条目整包并进这一本
     * （现象：打开 A = B+C 的页数，100+ 本 1000+ 页的库一打开就加载上千页）。
     */
    val isBook: Boolean get() = images.isNotEmpty() && subDirs.isEmpty() && archives.isEmpty()
}

/** 按本层分区一个目录的子节点（判定接缝；纯函数，不做任何 I/O） */
internal fun dirContentsOf(kids: List<FsNode>, nameComparator: Comparator<String>): DirContents = DirContents(
    images = kids.filter { it.isImageFile() }.sortedWith(compareBy(nameComparator) { it.name }),
    subDirs = kids.filter { it.isDirectory }.sortedWith(compareBy(nameComparator) { it.name }),
    archives = kids.filter { it.isArchiveFile() }.sortedWith(compareBy(nameComparator) { it.name }),
)

/**
 * 文档树来源：本地 File 与 SAF 文档树的统一实现。
 *
 * 目录判定规则（spec；判定只有一处 = [DirContents.isBook]）：
 * - 目录**本层只有图片** → 该目录是书（出现在父列表 isBook=true，从第 1 页按名称自然序打开全部本层图片）
 * - 目录本层有**子目录或压缩包** → 容器（isBook=false）：不递归合并、不拍平，子目录与压缩包在浏览列表里
 *   各是一条独立条目（点一个读一个），本层图片各自也是条目（点一张 = 从该张连读到本层末图）
 *
 * 因此「打开一个容器」的代价只与本层条目数有关，与下层书目数、总页数无关；
 * `pagesOfBook` 的目录分支只取本层图片（本层有图的容器直接打开也能读，不并入下级）。
 *
 * 枚举性能：列目录只在**本层**一次 `children()` 之上并发探测各子目录是书还是容器
 * （[SUBDIR_PROBE_LIMIT] 上限），枚举期不做封面相关的额外往返（不逐级下取容器封面位置、
 * 不解压压缩包取首页），封面字节一律走按需通路 [coverBytes]；同一目录在同一会话内二次进入命中 [listCache]。
 *
 * 「会话」= 本实例被复用的那段生命周期：界面侧由 `SessionState.browsingSourceFor` 按连接复用实例
 * 因此「进子目录 → 返回上级」与柜页/浏览页互切都命中同一份缓存，而不是每次进页面新建实例。
 */
class DocumentTreeSource(
    private val backend: FsBackend,
    private val progressStore: ProgressStore,
    /** Windows 自然排序；测试可注入自定义比较器 */
    private val nameComparator: Comparator<String> = WindowsNameOrder.COMPARATOR,
    /** 封面落盘缓存目录（「封面生成后缓存」）；null 时不落盘、每次按需解出 */
    private val coverCacheDir: File? = null,
    /** 来源类型（本地与 SMB 复用同一实现，仅类型与后端不同） */
    private val sourceType: SourceType = SourceType.LOCAL,
    /**
     * 阻塞读的看门狗时长（毫秒）：打开书 / 取页必须在有限时间内给出结果或报错。
     * 默认 [DEFAULT_READ_DEADLINE_MS]；测试注入小值来跑失败路径。语义与代价见 [readWithinDeadline]。
     */
    private val readDeadlineMs: Long = DEFAULT_READ_DEADLINE_MS,
    /**
     * 落盘列表快照：null = 不落盘（测试、无缓存目录）。
     * 键 = 连接 id + 容器 id，由 [ListingSnapshotStore] 承担；实例释放（[close]）**不**动落盘，
     * 这正是落盘的目的——退出 APP 再进来仍能命中。
     */
    private val listingSnapshots: ListingSnapshotStore? = null,
) : Source {

    private val rootNode: FsNode = backend.root
    private val resolve: (String) -> FsNode? = backend::resolve

    /** 发布时间排序键缓存（键含 mtime：文件更新后自动失效） */
    private val releaseCache = ConcurrentHashMap<String, Long>()

    /**
     * 包内条目缓存（键含 mtime：文件更新后自动失效）：
     * 一次列表里同一个 CBZ 的消费点有两个（压缩包封面、发布时间排序键——后者仅在发布时间排序下发生），
     * 每个消费点都要读中央目录（封面字节解出与 ComicInfo.xml 读取还会各开一次包）；
     * SMB 上这是实实在在的网络往返。页数不再参与。
     */
    private val archiveEntryCache = ConcurrentHashMap<String, List<ZipEntry>>()

    /**
     * 会话级列表快照里的一条：对外条目 + 拿到它的那个节点。
     *
     * 保留节点是关键：节点带着列目录时顺手拿到的元数据（mtime），因此
     * - 时间类排序不必再按 id 取节点（[resolve] 在网络来源上就是一次往返）；
     * - 探测重试可以直接用这些节点，不必重新列目录；
     * [probed] = 该子目录探测成功（false 的条目下次进入只重试它自己）。
     */
    private class ListingEntry(
        val entry: BrowseEntry,
        /**
         * 列目录/探测时顺手拿到的节点；**落盘快照恢复**的条目没有节点：
         * 那时只用快照里的 [mtimeMs]，不为排序重发一次 stat 风暴。
         */
        val node: FsNode?,
        /** 条目自身的修改时间（已落盘，恢复后时间类排序不必再取节点） */
        val mtimeMs: Long?,
        /**
         * 这一条是不是目录（列目录/探测时同批拿到）；落盘快照版本 2 起一并落盘。
         * 取封面字节时据此选分支，因此不必再 `resolve` 一次（见 [entryMeta]）。
         */
        val isDirectory: Boolean = node?.isDirectory ?: false,
        val probed: Boolean = true,
    ) {
        /**
         * 条目 → 落盘形态：字段清单只此一处（[readPersistedSnapshot]/[cacheSnapshot]/[listingOf] 不再各拼一遍）。
         * 对端映射见 [PersistedListingEntry.toListingEntry]。
         */
        fun toPersisted(): PersistedListingEntry = PersistedListingEntry(
            id = entry.id,
            name = entry.name,
            isBook = entry.isBook,
            coverUri = entry.coverUri,
            pageCount = entry.pageCount,
            mtimeMs = mtimeMs,
            probed = probed,
            isDirectory = isDirectory,
        )

        /** 快照里的一条 → 复用判定要的结论：落盘恢复的条目没有节点，mtime 取快照里记的那份 */
        fun toProbeConclusion(): ProbeConclusion =
            ProbeConclusion(mtimeMs = mtimeMs ?: node?.lastModifiedMs, probed = probed)

        /**
         * 复用上次探测结论：isBook 与封面指向沿用上次那条（这个子目录自己没变），
         * 名字与节点用本次列目录拿到的（更鲜）。仍算「探测成功」，因此下次还能继续复用。
         */
        fun reusedFor(sub: FsNode): ListingEntry = ListingEntry(
            entry = BrowseEntry(
                id = sub.id,
                name = sub.name,
                isBook = entry.isBook,
                coverUri = entry.coverUri,
                pageCount = null,
            ),
            node = sub,
            mtimeMs = sub.lastModifiedMs,
            isDirectory = sub.isDirectory,
            probed = true,
        )
    }

    /** 落盘形态 → 条目：节点为 null——恢复的条目没有节点，见 [ListingEntry.node] */
    private fun PersistedListingEntry.toListingEntry(): ListingEntry = ListingEntry(
        entry = BrowseEntry(
            id = id,
            name = name,
            isBook = isBook,
            coverUri = coverUri,
            pageCount = pageCount,
        ),
        node = null,
        mtimeMs = mtimeMs,
        isDirectory = isDirectory,
        probed = probed,
    )

    /**
     * 会话级列表快照：一个容器一份（**与排序方式无关**——排序在快照之上进行，不再为换排序重列目录）。
     *
     * 它的作用是**下次进这一层的增量比对基线**（子项 id + 各项自身的 mtime）：每次进目录都真列一次，
     * 拿列目录顺手带回来的子项 mtime 与它逐条比，因此没有它在手就得整层重探。
     */
    private class ListingSnapshot(val entries: List<ListingEntry>)

    /**
     * 会话级列表快照表（按容器一份）：同一目录二次进入（含从子目录返回上级）时当增量比对基线，
     * 不再为换排序方式重列，也不再为未变化的行重发探测。
     * 失效有两条路：显式刷新 [invalidateForRefresh]、[close]（实例被释放，**只清内存**）：
     * 落盘快照见 [listingSnapshots]（退出 APP 再进来仍是比对基线）。规模假设见 [LIST_CACHE_MAX_ENTRIES]。
     */
    private val listings = ConcurrentHashMap<String, ListingSnapshot>()

    /**
     * 封面字节的会话级缓存：键含条目 id 与 mtime（文件换过就换键）。
     * 之前只缓存**解码后的位图**，位图命中也要先向来源要字节——「返回上级再进来」会重下封面；
     * 现在字节层面直接命中，二次进入读字节 0 次。
     * 上界与淘汰口径（越界按插入序淘汰最旧，不是整仓清空）收在 [CoverByteCache]：
     * Komga 源共用同一份实现（预取结果必须真被可见行复用）。
     * [invalidateForRefresh]（手动刷新）与 [close] 仍**整体清空**。
     */
    private val coverBytesCache = CoverByteCache()

    /**
     * 条目 id → 当前字节缓存的键。[hasCachedCoverBytes] 要在**不做 IO**（不 resolve）的
     * 前提下回答「缓存里有没有这一条」，而字节缓存的键含 mtime，只有把索引记下来才知道该查哪把键。
     *
     * 索引可能指已不在缓存里的键（被淘汰、或文件 mtime 变了）：查缓存得到 null，[hasCachedCoverBytes]
     * 因此返回 false（下一次预取会重取）——这是正确结果，不需要联动失效。
     * 上界（它只是缓存的一个索引，与缓存同量级）：超了就整体清空。
     */
    private val coverCacheKeyByEntry = ConcurrentHashMap<String, String>()

    /**
     * 条目 id → 列目录时同批拿到的元数据（是不是目录 + mtime）。
     *
     * 为什么留着它：可见行取封面时只需这两个事实就能构造节点（[FsBackend.nodeFor]），
     * 而以前每次都 `resolve` 一次——网络来源上那就是每张封面白付一次 stat/PROPFIND。
     * 写入点是 [rememberSnapshot]（列目录与落盘快照恢复两条路都走它）。
     * 与封面字节缓存同生命周期：下拉刷新 / 会话释放一起清（见 [clearCoverBytes]）。
     * 上界与 [coverCacheKeyByEntry] 同量级：超了整体清空（它只是缓存）。
     */
    private val entryMeta = ConcurrentHashMap<String, EntryMeta>()

    /** [entryMeta] 的值：取封面字节要的那两个事实 */
    private class EntryMeta(val isDirectory: Boolean, val mtimeMs: Long?)

    /**
     * 探测期已知的「目录内首图」：键 = 子目录 id + mtime。
     *
     * 探测「这个子目录是不是书」时本来就把它的子节点列了出来，首图就在手里；不记下来的话，
     * 可见行取封面时还要再列一次同一目录（SMB 上就是一次多余往返）。
     */
    private val probedFirstImages = ConcurrentHashMap<String, FsNode>()

    override val type: SourceType get() = sourceType

    /**
     * 释放本实例：清列表缓存并释放后端会话（SMB）；本地后端无资源，忽略。
     * 清缓存与释放会话同步发生——实例被丢掉后缓存不再有消费者。
     */
    override fun close() {
        clearSessionCaches()
        (backend as? AutoCloseable)?.let { runCatching { it.close() } }
    }

    /**
     * 清空本实例的全部会话级缓存（[close] 与连接被编辑/删除时的实例释放共用）。
     * 有意**不**动落盘快照（[listingSnapshots]）：实例释放正是要跨过的那条边界
     * （连接被编辑/删除时由 `ServiceLocator.purgeListingSnapshots` 单独清盘上那份）。
     */
    private fun clearSessionCaches() {
        listings.clear()
        clearCoverBytes(SourceDiagnostics.CLEAR_ON_CLOSE)
        probedFirstImages.clear()
        releaseCache.clear()
        archiveEntryCache.clear()
    }

    override suspend fun listEntries(containerId: String?, sort: SortMode): List<BrowseEntry> =
        enumerateEntries(containerId, sort, EnumerationStats())

    /**
     * 枚举的**唯一实现**（全部口径都在这里）：[listEntries]（单段、对外）与邻位补齐都走它，
     * 因此两条路径的缓存/重列/增量重探/打点口径只有一处。
     *
     * internal 而不是 private 只有一个理由：`stats` 里的三个计数**只进 logcat**，
     * 而它们是验收口径（「进目录必列 1 次」「无变化 ⇒ 0 次探测」「只探新增的那 1 条」），
     * 单测需要一个能读到它们的入口（见 `DocumentTreeIncrementalProbeTest`）。
     */
    internal suspend fun enumerateEntries(
        containerId: String?,
        sort: SortMode,
        stats: EnumerationStats,
    ): List<BrowseEntry> {
        val startedNanos = System.nanoTime()
        val snapshot = snapshotOf(containerId, stats)
        // 排序在快照之上进行：键一次性取齐，比较器里不做任何 I/O——旧实现在选择器里
        // 按 id 取节点，而 Kotlin 的 compareBy* 每次比较都调用选择器，百级目录就是上千次网络往返
        val sorted = sortEntries(snapshot.entries, sort).map { it.entry }
        PerfTiming.log {
            enumerationLogLine(
                containerId = containerId,
                sort = sort,
                entries = sorted.size,
                stats = stats,
                ms = (System.nanoTime() - startedNanos) / 1_000_000,
            )
        }
        return sorted
    }

    /**
     * 取某个容器的会话级快照：**总是真列一次本层目录**，拿列目录顺手带回来的子项 mtime 与上次快照
     * 逐条比对，只重探新增 / 自身变化的行（[listingOf]，判定见 [canReuseProbe]）。
     *
     * 为什么不比对**容器自己**的修改时间：SMB 服务器不更新父目录的修改时间，改子目录的内容（新增书、
     * 加页 / 删页 / 改名）时父目录 mtime 不变 ⇒ 比对一定报「没变」⇒ 永远 0 次列目录、吃旧快照。
     * 因此容器 mtime 不再是失效判据（也不再发那一次取节点）。
     *
     * 比对基线（`previous`）：会话内存快照优先，内存没有（新实例 / 跨重启 / 被上界腾掉）就用**落盘快照**；
     * 两者都没有（这一层第一次进）才整层全量重探。基线的存在与否只看这一处，与容器 mtime 无关。
     *
     * 落盘快照的定位因此从「省一次列目录」变成「提供增量比对的基线」：没有它，1597 条的一层跨重启后
     * 就是 1597 次探测。
     *
     * 列目录失败（离线 / 服务器不可达）原样抛出，不用快照兜底：界面保持转圈。
     */
    private suspend fun snapshotOf(containerId: String?, stats: EnumerationStats): ListingSnapshot {
        val key = snapshotKeyOf(containerId)
        // 增量比对的基线：内存快照优先，其次落盘快照；都没有就整层全探
        val previous = listings[key] ?: readPersistedSnapshot(containerId)
        val dir = resolveNode(containerId)
        val snapshot = ListingSnapshot(entries = listingOf(dir, previous, stats))
        cacheSnapshot(key, snapshot)
        return snapshot
    }

    /**
     * 快照键：根容器无论用 `null`（浏览页路由）还是它的真实节点 id（相邻书判定从 `parent()` 拿到的）
     * 都是同一份快照——否则「根列表」会被枚举两次、缓存形同虚设。
     */
    private fun snapshotKeyOf(containerId: String?): String =
        if (containerId == null || containerId == rootNode.id) ROOT_CONTAINER_ID else containerId

    /**
     * 读该容器的**落盘快照**：不存在 / 超 TTL / 版本不匹配 / 读坏都返回 null。
     * 它就是这份快照的全部内容（子项清单）——它现在的用途是**增量比对基线**，见 [snapshotOf]。
     */
    private suspend fun readPersistedSnapshot(containerId: String?): ListingSnapshot? {
        val store = listingSnapshots ?: return null
        return withContext(Dispatchers.IO) {
            val persisted = store.read(snapshotKeyOf(containerId)) ?: return@withContext null
            ListingSnapshot(entries = persisted.entries.map { it.toListingEntry() })
        }
    }

    /** 快照表写入（内存 + 落盘）；达到上界先整体清空（见 [LIST_CACHE_MAX_ENTRIES]） */
    private fun cacheSnapshot(key: String, snapshot: ListingSnapshot) {
        rememberSnapshot(key, snapshot)
        val store = listingSnapshots ?: return
        store.write(
            key,
            PersistedListing(entries = snapshot.entries.map { it.toPersisted() }),
        )
    }

    /** 只进内存快照表（落盘恢复的路径不复写磁盘，见 [readPersistedSnapshot]） */
    private fun rememberSnapshot(key: String, snapshot: ListingSnapshot) {
        if (listings.size >= LIST_CACHE_MAX_ENTRIES) {
            // 整体腾空列表快照时，元数据索引一起腾：两份都是同一批列目录的结果，
            // 留着旧的那份只会让取封面拿旧 mtime 算出旧键（回落成一次 stat 取新鲜值是对的）
            listings.clear()
            entryMeta.clear()
        }
        listings[key] = snapshot
        // 元数据索引在这里更新：列目录与落盘快照恢复都要经过这一处（单一写入点）
        for (entry in snapshot.entries) rememberEntryMeta(entry)
    }

    /** 记下条目的「是不是目录 + mtime」（见 [entryMeta]） */
    private fun rememberEntryMeta(entry: ListingEntry) {
        if (entryMeta.size >= CoverByteCache.DEFAULT_MAX_ENTRIES * 4) entryMeta.clear()
        entryMeta[entry.entry.id] = EntryMeta(entry.isDirectory, entry.mtimeMs)
    }

    /**
     * 下拉更新要失效的东西：**只清封面字节缓存**。
     *
     * 列表那一半交给每次进目录的真列目录 + 增量比对（[snapshotOf]）：快照现在只是比对基线，
     * 清掉它反倒会把下一次进这一层变成整层全探（1597 条的一层就是 11 秒）。
     * 连接被编辑/删除走的是另一条路：实例释放（[close]）清内存 + `ServiceLocator.purgeListingSnapshots` 清落盘。
     *
     * `containerId` 在本实现里不参与——封面字节缓存是整个来源一份（不是按容器分的）。
     */
    override fun invalidateForRefresh(containerId: String?) {
        // 刷新要真刷封面：字节缓存一并清掉（否则还会把同一条目的旧封面还回去）
        clearCoverBytes(SourceDiagnostics.CLEAR_ON_REFRESH)
    }

    /**
     * 同步读快照（实现 [Source.cachedEntries]）：**只读内存快照且绝不做 IO**——不列目录、
     * 不比对 mtime（[sortEntries] 的 `snapshotOnly` 口径：发布时间键只查已算过的缓存、缺失用 mtime 兜底，
     * 不 resolve、不开包）。消费者是路径菜单的顶层条目（[Source.cachedTopLevelEntries]）与诊断导出。
     * 内存未命中（冷启动 / 被上界腾掉）时返回 null；界面首帧**不再**用它（进目录要的是列完目录之后的新列表）。
     */
    override fun cachedEntries(containerId: String?, sort: SortMode): List<BrowseEntry>? =
        listings[snapshotKeyOf(containerId)]?.let { sortedEntriesOf(it, sort) }

    /** 快照 → 按 [sort] 排好的条目（同步口径：不做任何 IO，发布时间键只查已算过的缓存） */
    private fun sortedEntriesOf(snapshot: ListingSnapshot, sort: SortMode): List<BrowseEntry> =
        sortEntries(snapshot.entries, sort, snapshotOnly = true).map { it.entry }

    /**
     * 枚举一个目录（结果带节点，支持**增量重探**）。枚举期不统计页数：
     * 不为页数读压缩包中央目录、不为页数列子目录，列表条目的 pageCount 一律为 null（页数只在打开书后由
     * BookHandle.pageCount 给出）。本层只一次 `children()`（SAF = 一次 provider IPC、SMB = 一次 list），
     * 分区后各自排序。
     *
     * 每条都带上列目录时拿到的那个节点：排序与探测重试都复用它，
     * 不再按 id 取节点——在网络来源上每次 `resolve` 就是一次往返。
     *
     * [previous] = 上次枚举留下的快照（会话内快照 / 落盘快照）：子目录按「条目 id + 该条目自身 mtime」
     * 与它比对，未变化的行**沿用上次探测结论、不发探测请求**（判定见 [canReuseProbe]，计数见 [stats]）；
     * null = 没有比对基线（这一层第一次进）→ 整层全量重探。
     */
    private suspend fun listingOf(
        dir: FsNode,
        previous: ListingSnapshot?,
        stats: EnumerationStats,
    ): List<ListingEntry> {
        val contents = dirContentsOf(dir.children(), nameComparator)
        stats.childrenCalls++

        val entries = mutableListOf<ListingEntry>()

        // 子目录条目：本层只有图片的子目录是书，其余是容器（判定见 [DirContents.isBook]）。
        // 增量重探：未变化的子目录沿用上次结论，只把新增/变化的交给受控并发探测（见 probeSubdirs）
        val previousById = previous?.entries?.associateBy { it.entry.id }.orEmpty()
        val reusable = mutableMapOf<String, ListingEntry>()
        for (sub in contents.subDirs) {
            val known = previousById[sub.id] ?: continue
            if (canReuseProbe(known.toProbeConclusion(), sub)) reusable[sub.id] = known
        }
        stats.reused += reusable.size
        val probed = probeSubdirs(contents.subDirs.filter { it.id !in reusable }, stats)
            .associateBy { it.entry.id }
        for (sub in contents.subDirs) {
            val reused = reusable[sub.id]
            entries += if (reused != null) reused.reusedFor(sub) else probed.getValue(sub.id).toListingEntry()
        }

        // 压缩包（CBZ/ZIP）：整包作为一本书（spec 故事 54），封面按需取（枚举期不解包取首页）
        for (arc in contents.archives) {
            entries += ListingEntry(
                entry = BrowseEntry(
                    id = arc.id,
                    name = arc.name,
                    isBook = true,
                    coverUri = null,
                    pageCount = null,
                ),
                node = arc,
                mtimeMs = arc.lastModifiedMs,
            )
        }

        // 本层图片：本层有子目录或压缩包时，图片各自是一条条目，从该图连读到本层末图
        for (img in contents.images) {
            entries += ListingEntry(
                entry = BrowseEntry(
                    id = img.id,
                    name = img.name,
                    isBook = true,
                    coverUri = img.imageUri,
                    pageCount = null,
                ),
                node = img,
                mtimeMs = img.lastModifiedMs,
            )
        }
        return entries
    }

    /** 子目录属性探测结果：条目 + 探测用的节点（两处构造都给非空节点）+ 是否探测成功（失败的下次只重试这一条） */
    private class SubdirProbe(val entry: BrowseEntry, val node: FsNode, val probed: Boolean) {
        fun toListingEntry(): ListingEntry = ListingEntry(entry, node, node.lastModifiedMs, node.isDirectory, probed)
    }

    /**
     * 子目录属性探测并发执行：并发上限 [SUBDIR_PROBE_LIMIT]，结果按子目录名称序返回
     * （拼接顺序与旧实现一致，条目集合/排序/isBook 语义不受影响）。
     * 增量重探只改变**交给它的条数**：这里只剩新增/变化的行（打点 `probes=`）。
     *
     * `children()` 是阻塞调用（SMB list / SAF provider IPC），固定在 [Dispatchers.IO] 上跑才是真并发；
     * 所有探测都挂在本次调用的 coroutineScope 上，调用方（离开页面即取消的界面协程）被取消时，
     * 尚未开始的探测立即放弃、正在等并发额度的也随之退出，不会继续发请求。
     */
    private suspend fun probeSubdirs(
        subDirs: List<FsNode>,
        stats: EnumerationStats,
    ): List<SubdirProbe> = coroutineScope {
        // 计数在发起处（顺序执行，无并发写）：本次发出的探测条数
        stats.probes += subDirs.size
        val gate = Semaphore(SUBDIR_PROBE_LIMIT)
        subDirs.map { sub ->
            async(Dispatchers.IO) { gate.withPermit { probeSubdir(sub) } }
        }.awaitAll()
    }

    /**
     * 单个子目录：本层只有图片 → 书（封面 = 本层首图的 uri，零额外往返：图已经列出来了）；
     * 其余（含子目录或压缩包、本层无图）→ 容器（判定见 [DirContents.isBook]；封面按需，见 [coverBytes]）。
     * 探测失败（目录不可读/损坏等）降级为容器并标记未探测成功：一行探测不到不能拖垮整表，
     * 条目仍可点（进得去会重新枚举、刷新入口可重试）；
     * 传输层故障（断链/认证失效）必须冒泡，不能伪装成「这个文件夹是容器」。
     */
    private fun probeSubdir(sub: FsNode): SubdirProbe = catchingNonCancellation {
        val contents = dirContentsOf(sub.children(), nameComparator)
        // 探测时本来就把首图列出来了：记下来，可见行取封面时不必再列一次这个目录
        contents.images.firstOrNull()?.let { first -> rememberProbedFirstImage(sub, first) }
        SubdirProbe(
            entry = BrowseEntry(
                id = sub.id,
                name = sub.name,
                isBook = contents.isBook,
                coverUri = contents.images.firstOrNull()?.imageUri,
                pageCount = null,
            ),
            node = sub,
            probed = true,
        )
    }.fold(
        onSuccess = { it },
        onFailure = { t ->
            // 网络/传输故障必须冒泡（core/source/TransportFailure.kt 的类型契约，与 [archiveEntries] 同式）：
            // 断链时整批子目录都列不出来，静默降级会得到一份「全是容器、封面全空、不报错」的假列表
            if (t is TransportFailure) throw t
            SubdirProbe(
                entry = BrowseEntry(id = sub.id, name = sub.name, isBook = false, coverUri = null, pageCount = null),
                node = sub,
                probed = false,
            )
        },
    )

    /**
     * 封面字节：无系统可解码 uri 的来源（SMB/WebDAV）由 UI 回退到这里；
     * 本地/SAF 也走这里——列表只为「目录内首图」「图片本身」这类零开销的封面给 uri，
     * 容器封面与压缩包封面一律按需取（枚举期不发生）。触发时机有两处：**可见行**自己去取，
     * 以及浏览页的预取窗口（可见区 ±1 屏；预取连解码一起做，因此那一条在滚到之前
     * 就已把字节与位图都弄好，可见行直接命中封面分区）。
     * 规则（spec 故事 9）：压缩包取包内首页，图片取本身，目录从本层开始逐级下取——**每层**都是
     * 本层首图 → 本层首个压缩包的首帧 → 子目录（单层与下取同一套优先级）；
     * 取不到返回 null（界面显示占位底色）。
     *
     * 三条与调用方直接相关的契约：
     * - **目录条目不再多问一次元数据**：本层本次会话列过时，用列目录同批拿到的类型与 mtime 构造节点
     *   （[FsBackend.nodeFor]），不再 `resolve`（网络来源上那是每张封面一次 stat/PROPFIND）。
     * - **单个文件条目照旧问一次**：图片/压缩包这类条目的 mtime 就是“文件自己改过没有”的唯一信号
     *   （原地换掉内容不会动父目录的时间），因此它们仍走 `resolve` 拿新鲜 mtime。
     *   两种走法的取值进打点的 `meta=`（`listing`/`stat`，见 [SourceDiagnostics.coverBytesLine]）。
     * - **取消照常传播**：等待期间被取消时抛 `CancellationException`、不返回 null——
     *   取消不是「来源给不出」，与 [Source.coverBytes] 那条「取不到返回 null」不矛盾（那是来源的答复）。
     */
    override suspend fun coverBytes(entryId: String): ByteArray? {
        val startedNanos = System.nanoTime()
        var metaMode = SourceDiagnostics.COVER_META_STAT
        // 取消不是来源的答复（与 `CoverPrefetchLedger.release` 同一条口径）：
        // 行里分开成 `outcome=cancelled` 并把取消原样传出，不再把一个已滑走格子的取消当成「没取到」。
        try {
            // 目录条目：列目录时已知「是目录 + mtime」，连节点都不必构造就能算字节缓存的键
            val listed = entryMeta[entryId]?.takeIf { it.isDirectory }
            if (listed != null) {
                metaMode = SourceDiagnostics.COVER_META_LISTING
                val listedKey = coverBytesKey(entryId, listed.mtimeMs)
                // 命中路径也要记索引：[hasCachedCoverBytes] 靠它回答「缓存里有没有这一条」，
                // 漏记的后果是这批条目在索引被整体清空后长期假阴性（预取白派任务）
                rememberCoverCacheKey(entryId, listedKey)
                coverBytesCache.get(listedKey)?.let {
                    logCoverBytes(entryId, cached = true, metaMode, SourceDiagnostics.COVER_OUTCOME_OK, it.size, startedNanos)
                    return it
                }
            }
            // 目录条目不发元数据请求；其余（本层没列过 / 单个文件条目）回落到 resolve
            val fromListing = listed?.let { backend.nodeFor(entryId, it.isDirectory, it.mtimeMs) }
            if (fromListing == null) metaMode = SourceDiagnostics.COVER_META_STAT
            val node = fromListing ?: resolve(entryId) ?: run {
                logCoverBytes(entryId, cached = false, metaMode, SourceDiagnostics.COVER_OUTCOME_MISS, 0, startedNanos)
                return null
            }
            // 字节级会话缓存：键含 mtime，文件换过就是另一个键
            val cacheKey = coverBytesKey(node)
            rememberCoverCacheKey(entryId, cacheKey)
            coverBytesCache.get(cacheKey)?.let {
                logCoverBytes(entryId, cached = true, metaMode, SourceDiagnostics.COVER_OUTCOME_OK, it.size, startedNanos)
                return it
            }
            val bytes = when {
                node.isArchiveFile() -> archiveCoverBytes(node)
                node.isImageFile() -> node.readBytesCancellable()
                // 库「一个子文件夹一本书」是常见布局：探测期已经列过这个目录，首图就在手里
                node.isDirectory -> probedFirstImageBytes(node) ?: directoryCoverBytes(node)
                else -> null
            } ?: run {
                logCoverBytes(entryId, cached = false, metaMode, SourceDiagnostics.COVER_OUTCOME_MISS, 0, startedNanos)
                return null
            }
            coverBytesCache.put(cacheKey, bytes)
            logCoverBytes(entryId, cached = false, metaMode, SourceDiagnostics.COVER_OUTCOME_OK, bytes.size, startedNanos)
            return bytes
        } catch (c: CancellationException) {
            logCoverBytes(entryId, cached = false, metaMode, SourceDiagnostics.COVER_OUTCOME_CANCELLED, 0, startedNanos)
            throw c
        } catch (t: Throwable) {
            logCoverBytes(entryId, cached = false, metaMode, SourceDiagnostics.COVER_OUTCOME_MISS, 0, startedNanos)
            return null
        }
    }

    /** 封面取数行（行格式与判读口径见 [SourceDiagnostics.coverBytesLine]）：关着诊断时连字符串都不拼 */
    private fun logCoverBytes(entryId: String, cached: Boolean, meta: String, outcome: String, bytes: Int, startedNanos: Long) {
        PerfTiming.log {
            SourceDiagnostics.coverBytesLine(
                entryId = entryId,
                cached = cached,
                meta = meta,
                outcome = outcome,
                bytes = bytes,
                ms = (System.nanoTime() - startedNanos) / 1_000_000,
            )
        }
    }

    /**
     * 封面字节缓存的键：条目 id + mtime（mtime 不可得时用 0，随手动刷新失效）。
     * id 与 mtime 分开收参：列目录留住元数据的那条路手上没有节点（只有这两个事实）。
     */
    private fun coverBytesKey(entryId: String, mtimeMs: Long?): String = entryId + "@" + (mtimeMs ?: 0L)

    /** 同上，实参是节点（[FsNode.id] 与条目 id 同源） */
    private fun coverBytesKey(node: FsNode): String = coverBytesKey(node.id, node.lastModifiedMs)

    /**
     * 缓存里**已有**这一条的字节吗：只查内存与索引，不 resolve、不发请求
     * （它在滚动路径上被逐条调用，SMB 上 resolve 是一次以上往返）。
     */
    override fun hasCachedCoverBytes(entryId: String): Boolean =
        coverCacheKeyByEntry[entryId]?.let { coverBytesCache.get(it) != null } ?: false

    /** 记下「这个条目当下对应的缓存键」（索引超上界就整体清空：它只是缓存的索引） */
    private fun rememberCoverCacheKey(entryId: String, cacheKey: String) {
        if (coverCacheKeyByEntry.size >= CoverByteCache.DEFAULT_MAX_ENTRIES * 4) coverCacheKeyByEntry.clear()
        coverCacheKeyByEntry[entryId] = cacheKey
    }

    /**
     * 整体清空封面字节缓存与其索引（手动刷新 / 会话释放）：两处必须一起清，否则索引会指空键。
     * [reason] 进打点（设备上要分得清「下拉更新清了一次」与「实例被释放所以清了」）。
     */
    private fun clearCoverBytes(reason: String) {
        val cleared = coverBytesCache.clear()
        coverCacheKeyByEntry.clear()
        entryMeta.clear()
        PerfTiming.log { SourceDiagnostics.coverCacheClearLine(this, reason, cleared.entries, cleared.bytes) }
    }

    /** 记下探测期看到的「目录内首图」（键含目录 mtime，目录一变就换键，不会拿旧首图当封面） */
    private fun rememberProbedFirstImage(dir: FsNode, firstImage: FsNode) {
        if (probedFirstImages.size >= PROBED_FIRST_IMAGE_MAX_ENTRIES) probedFirstImages.clear()
        probedFirstImages[coverBytesKey(dir)] = firstImage
    }

    /** 探测期已知的目录内首图字节：命中即 0 次列目录。读走可取消那条（拖滑条时一次下拉不再白耗） */
    private suspend fun probedFirstImageBytes(dir: FsNode): ByteArray? {
        val first = probedFirstImages[coverBytesKey(dir)] ?: return null
        // 取消照常传播（[catchingNonCancellation]）：只有真失败才当作「这一条没有字节」
        return catchingNonCancellation { first.readBytesCancellable() }.getOrNull()
    }

    override suspend fun openBook(bookId: String): BookHandle {
        val startedNanos = System.nanoTime()
        val book = readWithinDeadline("打开", bookId) { resolvedBookOf(bookId) }
        // 空页：压缩包是书但包里没有图片（空包/坏包/只装文本）→ 给出 0 页句柄，界面照 pageCount == 0
        // 显示中文空态（不是一直转圈）；目录本层没有图片则它从不是一本书（容器），仍按既有契约拒绝。
        if (book.pages.isEmpty() && !book.node.isArchiveFile()) throw IllegalArgumentException("不是一本书：$bookId")
        val pages = book.pages
        PerfTiming.log { "openBook id=$bookId pages=${pages.size} ms=${(System.nanoTime() - startedNanos) / 1_000_000}" }
        return object : BookHandle {
            override val id: String = bookId
            override val pageCount: Int = pages.size
            override suspend fun loadPage(index: Int): PageData {
                val page = pages.getOrNull(index)
                    ?: throw IndexOutOfBoundsException("页码越界：$index / ${pages.size}")
                val startedNanos = System.nanoTime()
                val bytes = readWithinDeadline("取第 " + (index + 1) + " 页", bookId) { page.bytes() }
                PerfTiming.log {
                    "loadPage id=$bookId page=$index bytes=${bytes.size} ms=${(System.nanoTime() - startedNanos) / 1_000_000}" +
                        " source=${sourceType.name} instance=${SourceDiagnostics.instanceTag(this@DocumentTreeSource)}" +
                        " from=${if (page.fromArchive) SourceDiagnostics.FROM_ARCHIVE else SourceDiagnostics.FROM_IMAGE}"
                }
                return PageData(bytes, mimeTypeOf(page.name))
            }
        }
    }

    /**
     * 阻塞读的看门狗（「失败必须可失败」）：打开的压缩包 / 取的一页必须在有限时间内给出结果。
     *
     * 为什么需要它：这些读是本进程内的**阻塞** I/O（smbj 的 `File.read`、OkHttp 的同步 call）。
     * 协程取消在阻塞段里没有可观察的挂起点，因此「限时等待」是唯一能在有限时间内给用户答复的手段。
     * 超时抛 [SourceReadTimeoutException]——**不是** `CancellationException`：阅读器把取消当成「换书」静默处理
     * （`ReaderScreen` 的 `catch (c: CancellationException) { throw c }`），用取消表达超时会变成「永远转圈」。
     *
     * 两个承重细节（改这里先看这两条）：
     * - 工作挂在 [deadlineScope] 上而不是本协程的子作用域：`withContext`/`async` 的子协程必须全部结束
     *   父作用域才能返回，卡住的读会把等待者一起拖住，看门狗就形同虚设。
     * - 计时发生在 `withContext(Dispatchers.IO)` 之后：`withTimeout` 取上下文的 Delay 元素，IO 调度器没有 Delay
     *   → 走真实时钟（若继承测试里的虚拟时钟，任何一次真实读都会被当成超时）。
     *
     * 代价（记录）：超时只解除**等待**，那根仍在阻塞的线程要等底层传输自身超时才回收
     * （SMB 读 15s / WebDAV 调用 90s），期间它继续占着一根线程。不为此给 `RandomAccessBytes`
     * 加取消协议：闭包（socket/句柄）在调用方线程手上，新协议的破坏面比收益大。
     */
    private suspend fun <T> readWithinDeadline(what: String, bookId: String, block: () -> T): T =
        withContext(Dispatchers.IO) {
            val work = deadlineScope.async { block() }
            try {
                withTimeout(readDeadlineMs) { work.await() }
            } catch (t: TimeoutCancellationException) {
                work.cancel()
                // 生产恒为整秒（60 秒）；测试注入毫秒级值，别显示成「0 秒」
                val limit = if (readDeadlineMs % 1000L == 0L) {
                    "${readDeadlineMs / 1000} 秒"
                } else {
                    "$readDeadlineMs 毫秒"
                }
                throw SourceReadTimeoutException(
                    what + "超时（" + limit + "）：" + bookId + "；请检查网络/服务器后重试",
                )
            }
        }

    override suspend fun readProgress(bookId: String): ReadingProgress? =
        progressStore.read(bookId)

    override suspend fun writeProgress(bookId: String, pageIndex: Int, totalPages: Int) =
        progressStore.write(bookId, pageIndex, totalPages)

    /**
     * 相邻书（**只读会话快照**）：同一容器内 isBook 条目按名称自然序的前后邻位。
     *
     * 本方法不得触发父层的列目录与子目录探测——SMB/WebDAV 上「这一层每个子目录是不是书」
     * 就是每个子目录多次往返，打开一本书顺手付掉这一层的观感就是
     * 「一次打开所有子文件夹的所有书」。因此快照缺失（本层本次会话没被列过，例如启动页直接进阅读器；
     * 或快照已被 [LIST_CACHE_MAX_ENTRIES] 腾掉）时**降级为本次不给邻居**（`Neighbors(null, null)`），
     * 而不是去列/探这一层。邻位未知期与「确实到头」在界面上表现相同（同一条提示）：补齐路径 = 界面进阅读器后
     * 后台调 [warmNeighbors]（见 `ReaderScreen`），补齐后邻位即恢复；
     * 浏览页点开书这条主路径层都已被列过（[listEntries] 落的快照），因此邻位照常。
     */
    override suspend fun neighbors(bookId: String): Neighbors {
        val startedNanos = System.nanoTime()
        val listParent = listParentOf(resolveNode(bookId)) ?: return Neighbors(null, null)
        val books = cachedBookEntriesOf(listParent)
        // 验收打点（默认关闭）：`snapshot=false` 即降级路径，两者都应当是 0 次列目录/探测。
        // 这个 `snapshot=` 是布尔口径（邻位判定依据是否命中），与枚举行的三个计数不是同一回事。
        PerfTiming.log {
            "neighbors id=" + bookId + " snapshot=" + (books != null) + " books=" + (books?.size ?: 0) +
                " ms=" + ((System.nanoTime() - startedNanos) / 1_000_000)
        }
        if (books == null) return Neighbors(null, null)
        val idx = books.indexOfFirst { it.id == bookId }
        if (idx < 0) return Neighbors(null, null)
        return Neighbors(
            prev = books.getOrNull(idx - 1)?.id,
            next = books.getOrNull(idx + 1)?.id,
        )
    }

    /**
     * 书的**列表层**（同目录 isBook 序列的来源层）：目录书 / 图片条目 / 压缩包书三种形态都取自己的父目录
     * （浏览列表认这三种条目是书，见 [listingOf]；换书必须用同一套口径，否则压缩包在列表里有邻位、菜单里却是 null）。
     * 其余（容器、非书文件、无父层的根）不是书，返回 null。
     */
    private fun listParentOf(node: FsNode): FsNode? = when {
        node.isDirectory -> node.parent()
        node.isImageFile() -> node.parent()
        node.isArchiveFile() -> node.parent()
        else -> null
    }

    /**
     * 后台补齐邻位层（契约见 [Source.warmNeighbors]）：
     * 只在**该层没有快照**时枚举一次（走 [snapshotOf]，与浏览页首次进该层同一条路径与同一份缓存）；
     * 已有快照则直接返回（不再枚举，只花按 id 取一次节点）。
     *
     * 本方法由界面在**进阅读器之后**的后台协程里调，不在打开/落点路径上：未补齐时 [neighbors] 仍是瞬时返回的空结果，
     * 因此提速口径不回退。失败（传输故障/离线）直接冒出给调用方：不重试、不轮询，邻位保持未知。
     */
    override suspend fun warmNeighbors(bookId: String) {
        val startedNanos = System.nanoTime()
        val listParent = listParentOf(resolveNode(bookId)) ?: return
        val cached = listings[snapshotKeyOf(listParent.id)]
        val stats = EnumerationStats()
        // 邻位行有自己的 `snapshot=<bool>` 口径，三个计数两处都读（下面的 countsLog）
        if (cached == null) snapshotOf(listParent.id, stats)
        // 验收打点：`snapshot=false` = 本次真补齐了一次（窗口期内邻位仍未知），true = 无需补齐
        PerfTiming.log {
            "warmNeighbors id=" + bookId + " snapshot=" + (cached != null) + " " + countsLog(stats) +
                " ms=" + ((System.nanoTime() - startedNanos) / 1_000_000)
        }
    }

    /**
     * 相邻书判定用的书列表（**只读会话快照**）：isBook 集合与 [listingOf] 一致
     * （判定见 [DirContents.isBook]，这里不重述），按全局名称序排序
     * （分区拼接顺序会与浏览列表名称序不一致）。
     *
     * 这就是「主路径复用」：浏览页枚举当前层时落的快照即这份数据（[listEntries] 的枚举结果），
     * 因此点开书时邻位来自已经算过的列表，**不重列、不重探**。
     *
     * 三条承重细节（改这里先看这三条）：
     * - 不走 [snapshotOf]：那个函数在快照未命中时会真去列这一层（含逐子目录探测），正是要拆的东西。
     * - 不在这里比对 mtime：比对要按 id 取一次节点（网络来源上就是一次往返），而邻位只是「上一本/下一本」
     *   的提示——用本层本次会话已列出的那份即可；本层被改动时浏览页下一次进入会重新枚举并替换快照。
     * - 快照里的探测失败条目（[probeSubdir] 降级为容器）不算书、也不在这里重试：与浏览页显示的是同一份事实。
     *
     * 快照缺失返回 null（调用方 [neighbors] 因此给出空邻位），**绝不**在这里回退到列目录。
     */
    private fun cachedBookEntriesOf(dir: FsNode): List<BrowseEntry>? {
        val snapshot = listings[snapshotKeyOf(dir.id)] ?: return null
        return sortEntries(snapshot.entries.filter { it.entry.isBook }, SortMode.NAME).map { it.entry }
    }

    // ---------- 内部 ----------

    /** 越界/无效 id 的唯一防护点（后端 resolve 返回 null 即拒绝） */
    private fun resolveNode(id: String?): FsNode =
        if (id == null) rootNode else resolve(id) ?: throw IllegalArgumentException("无效或越界引用：$id")

    /**
     * 书的页面序列：
     * - 目录 = 只取**本层**图片（名称自然序），本层压缩包不再并入
     *   （旧实现把本层压缩包的条目整包并进来 → 现象「打开 A = B+C 的页数」）
     * - 图片文件 = 从该图到本层末图（自然序）
     * - 压缩包 = 包内图片条目自然序（**全深度**，见 [archiveEntries]）
     */
    private fun pagesOfBook(node: FsNode): List<PageRef> = when {
        node.isDirectory -> dirContentsOf(node.children(), nameComparator).images.map { FilePageRef(it) }
        node.isImageFile() -> {
            val siblings = node.parent()?.let(::listImagesSorted) ?: emptyList()
            siblings.dropWhile { it.id != node.id }.map { FilePageRef(it) }
        }
        node.isArchiveFile() -> archiveEntries(node).map { ZipPageRef(node, it) }
        else -> emptyList()
    }

    /** 一个被打开的节点 + 它的页序列（[resolvedBookOf] 里一次 resolve 同时给出两者） */
    private class ResolvedBook(val node: FsNode, val pages: List<PageRef>)

    private fun resolvedBookOf(bookId: String): ResolvedBook {
        val node = resolveNode(bookId)
        return ResolvedBook(node, pagesOfBook(node))
    }

    private fun listFilesSorted(dir: FsNode, keep: (FsNode) -> Boolean): List<FsNode> =
        dir.children().filter(keep).sortedWith(compareBy(nameComparator) { it.name })

    private fun listImagesSorted(dir: FsNode): List<FsNode> = listFilesSorted(dir) { it.isImageFile() }

    // ---------- 按需封面（封面字节只在可见行取，枚举期不发生）----------

    /**
     * 目录封面字节（spec 故事 9）：从本层开始**逐级下取**，**每一层都走同一套优先级**（[layerCoverBytes]）——
     * 本层图片 → 本层首个压缩包的首帧 → 子目录（名称序、深度优先）；取到即停，取不到返回 null。
     *
     * 修复：旧实现的下取只找图片，`C/` → `A/` → `B.cbz` 的 C 因此没有封面
     * （「单层目录」与「逐级下取」是两套口径）；现在两条路共用 [layerCoverBytes]，口径只有一处。
     * 代价有上界：每层目录只列一次；每层最多开一个包（本层没有图时才开名称序第一个包，同层其余包不碰）。
     *
     * 探测期已知首图的情形（书文件夹）在 [coverBytes] 里就返回了，不会走到这里。
     */
    private suspend fun directoryCoverBytes(dir: FsNode): ByteArray? = firstCoverBytesDeep(dir.children())

    /** 逐级下取（容器封面规则）：先按本层优先级取，取不到才下探子目录（名称序、深度优先、每层目录只列一次） */
    private suspend fun firstCoverBytesDeep(kids: List<FsNode>): ByteArray? {
        // 本层分区走 [dirContentsOf]（「一个目录里有什么」的唯一判定点），不再手写第三份 filter+sort
        val contents = dirContentsOf(kids, nameComparator)
        layerCoverBytes(contents)?.let { return it }
        for (sub in contents.subDirs) {
            firstCoverBytesDeep(sub.children())?.let { return it }
        }
        return null
    }

    /**
     * 一层目录的封面优先级（「单层目录」与「逐级下取」共用这一处）：
     * ① [DirContents.images] 首图 → ② [DirContents.archives] 首个（名称序）的首帧 → null。
     * ②只开名称序那一个包；同层的其余包一个都不开（取封面不为同层每个包买单）。
     * 分区由 [dirContentsOf] 给出，与浏览列表/页序列同一套口径。
     */
    private suspend fun layerCoverBytes(contents: DirContents): ByteArray? =
        contents.images.firstOrNull()?.readBytesCancellable()
            ?: contents.archives.firstOrNull()?.let { archiveCoverBytes(it) }

    /**
     * 排序（分两条口径）：**键一次性取齐**，比较器里不做任何 I/O。
     *
     * Kotlin 的 `compareBy*` 每次比较都会调用选择器，所以旧实现的 `compareByDescending { resolve(it.id)… }`
     * 在百级目录上一次排序就是上千次「按 id 取节点」（SMB 上每次都是 folderExists + 文件信息两次往返）。
     * 现在修改时间优先用列目录时顺手拿到的节点 mtime（落盘快照里也记了它，恢复后同样 0 次取节点）；
     * 发布时间键每个条目只算一次（[releaseKey] 自己按 mtime 缓存）。
     * 拿不到元数据的条目按既有回退口径处理：时间类排序末位（0）。
     *
     * [snapshotOnly] = true 是同步快照访问器 [cachedEntries] 的路径（组合期在主线程调）：它**绝不做 IO**——
     * 发布时间键只查已算过的 [releaseCache]，缺失就用快照里的 mtime 兜底，**不 resolve、不开包**。
     */
    private fun sortEntries(
        entries: List<ListingEntry>,
        sort: SortMode,
        snapshotOnly: Boolean = false,
    ): List<ListingEntry> = when (sort) {
        SortMode.NAME -> entries.sortedWith(compareBy(nameComparator) { it.entry.name })
        SortMode.MODIFIED_TIME -> sortByDescendingKey(entries) { it.mtimeMs ?: it.node?.lastModifiedMs ?: 0L }
        // 发布时间（spec 故事 12/13）：CBZ 读 ComicInfo.xml，无元数据（图片文件夹等）回退 mtime
        SortMode.RELEASE_TIME -> sortByDescendingKey(entries) { releaseKeyOf(it, snapshotOnly) }
    }

    /**
     * 条目的发布时间排序键：
     * - **先查已算过的键**（[releaseCache]，命中不算 IO）：异步枚举刚算过的真实发布键在同步路径上也直接复用，
     *   否则同步首帧的顺序会与异步列表差一截（一帧后自校正）。
     * - 有节点但没算过：键已算过就直接用；[snapshotOnly] 下不读包，用快照里的 mtime 兜底。
     * - 落盘恢复的条目（没有节点）：[snapshotOnly] 下**不得 resolve、不得开包**，直接用快照里的 mtime；
     *   异步枚举（[sortEntries] 的默认口径）才会按 id 取一次节点、只对压缩包读包内 ComicInfo.xml。
     */
    private fun releaseKeyOf(entry: ListingEntry, snapshotOnly: Boolean): Long {
        releaseCache[releaseCacheKeyOf(entry)]?.let { return it }
        val node = entry.node
        if (snapshotOnly) return entry.mtimeMs ?: node?.lastModifiedMs ?: 0L
        if (node != null) return releaseKey(node)
        val mtime = entry.mtimeMs ?: 0L
        val looksArchive = entry.entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in ARCHIVE_EXTENSIONS
        if (!looksArchive) return mtime
        return nodeOf(entry)?.let { releaseKey(it) } ?: mtime
    }

    /** 条目的节点：落盘恢复的条目按 id 取（只在异步消费点发生，见 [releaseKeyOf]） */
    private fun nodeOf(entry: ListingEntry): FsNode? =
        entry.node ?: runCatching { resolve(entry.entry.id) }.getOrNull()

    /** 一次性构建排序键再排序：比较阶段没有任何 I/O（见 [sortEntries]） */
    private inline fun <T> sortByDescendingKey(items: List<T>, keyOf: (T) -> Long): List<T> {
        val keys = items.associateWith(keyOf)
        return items.sortedWith(compareByDescending { keys[it] ?: 0L })
    }

    /**
     * 发布时间键缓存键（条目 id + 修改时间）：写（[releaseKey]）与读（[releaseKeyOf]）共用这一处。
     * 条目侧优先用它的节点 mtime（与 [releaseKey] 写键同源），**落盘恢复的条目**（没有节点）用快照里的
     * [ListingEntry.mtimeMs]，因此照样能命中异步枚举刚算过的真实发布键。
     */
    private fun releaseCacheKeyOf(entry: ListingEntry): String =
        releaseCacheKeyOf(entry.entry.id, entry.node?.lastModifiedMs ?: entry.mtimeMs)

    private fun releaseCacheKeyOf(node: FsNode): String = releaseCacheKeyOf(node.id, node.lastModifiedMs)

    private fun releaseCacheKeyOf(id: String, mtimeMs: Long?): String = id + "@" + (mtimeMs ?: 0L)

    /** 发布时间排序键：优先 ComicInfo.xml 的日期（转毫秒与 mtime 同量纲），缺失回退修改时间 */
    private fun releaseKey(node: FsNode): Long {
        val cacheKey = releaseCacheKeyOf(node)
        releaseCache[cacheKey]?.let { return it }
        val key = if (node.isArchiveFile()) {
            runCatching {
                withArchive(node) { zip ->
                    zip.entry(COMIC_INFO)?.let { entry ->
                        parseReleaseDate(zip.read(entry))?.toEpochMillis()
                    }
                }
            }.getOrNull() ?: node.lastModifiedMs ?: 0L
        } else {
            node.lastModifiedMs ?: 0L
        }
        releaseCache[cacheKey] = key
        return key
    }

    // ---------- 压缩包（CBZ/ZIP）----------

    private fun <T> withArchive(node: FsNode, block: (ZipArchive) -> T): T =
        node.openRandomAccess().use { bytes -> ZipArchive(bytes).use(block) }

    /**
     * 包内图片条目：按文件名自然序（spec 故事 54：页序 = ZIP 内文件名自然排序）。
     *
     * **全深度**收集（`isArchiveImageEntry` 不看层级）：包内套一层书名目录
     * （`A.cbz/书名/001.jpg`）是常见布局，只取顶层会让真实压缩包变成 0 页。压缩包内部分卷另议。
     */
    private fun archiveEntries(node: FsNode): List<ZipEntry> {
        val cacheKey = node.id + "@" + (node.lastModifiedMs ?: 0L)
        archiveEntryCache[cacheKey]?.let { return it }
        val entries = try {
            withArchive(node) { zip -> zip.entries.filter { isArchiveImageEntry(it.name) } }
                .sortedWith(compareBy(nameComparator) { it.name })
        } catch (t: Throwable) {
            // 网络/传输故障必须冒泡：不能伪装成「这本不是压缩包」（否则断链时静默 0 页）
            if (t is TransportFailure) throw t
            // 损坏的 ZIP：当作非压缩包，不影响同目录其他书
            emptyList()
        }
        archiveEntryCache[cacheKey] = entries
        return entries
    }

    /** 压缩包封面字节（按需，从枚举期移到可见行）：包内首页的原始字节，由界面解码 */
    private fun archiveFirstPageBytes(node: FsNode): ByteArray? {
        val entry = archiveEntries(node).firstOrNull() ?: return null
        return withArchive(node) { zip -> zip.read(entry) }
    }

    /**
     * 压缩包封面字节 + 落盘缓存（「封面生成后缓存」：只在按需通路上解）：
     * 第一次取时解出并写缓存文件，后续滚动/回看不重开包；无缓存目录（或读写失败）时每次解出。
     */
    private fun archiveCoverBytes(node: FsNode): ByteArray? {
        val cached = coverCacheFile(node)
        if (cached != null && cached.exists() && cached.length() > 0L) {
            runCatching { cached.readBytes() }.getOrNull()?.let { return it }
        }
        val bytes = archiveFirstPageBytes(node) ?: return null
        if (cached != null) writeCoverCacheFile(cached, bytes)
        return bytes
    }

    /**
     * 落盘走「临时文件 + 原子改名」：同一个 entry 的封面可被界面并发触发
     * （可见行重进组合、刷新时 LaunchedEffect 重启），直接 writeBytes 会先截断再写，
     * 并发的读者可能读到半截文件而解码失败。**改名是覆盖式的**：`File.renameTo` 在 Windows 上
     * 不覆盖，同键第二次写会静默失效（同一个 id + mtime 再解一次时旧文件挡住新字节）。
     * 写失败只是白解一次，下次仍会重试。
     * 改名成功后再清同一本书的旧封面文件：文件名带 mtime，不清理的话每改一次多留一份。
     */
    private fun writeCoverCacheFile(target: File, bytes: ByteArray) {
        runCatching {
            val dir = target.parentFile ?: return
            val tmp = File.createTempFile(target.name, ".tmp", dir)
            try {
                tmp.writeBytes(bytes)
                moveOverExistingTarget(tmp, target)
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
            pruneCoverCacheSiblings(target)
        }
    }

    /**
     * 删掉同一本书的旧封面文件（同 id 前缀，只认当前命名格式；上一版留下的旧名交给系统清理缓存目录）。
     * 在新文件就位后才删，因此不会留下半截文件；并发场景下最坏是删掉刚写入的「更新一轮」文件，
     * 下次取封面重新解一次（缓存目录，可自愈）。
     */
    private fun pruneCoverCacheSiblings(target: File) {
        val dir = target.parentFile ?: return
        val prefix = target.name.substringBeforeLast('_') + "_"
        dir.listFiles { f ->
            f.isFile && f.name != target.name && f.name.endsWith(".img") && f.name.startsWith(prefix)
        }?.forEach { it.delete() }
    }

    /**
     * 封面缓存文件（沿用既有命名，键并入 node.id 与 mtime）：内容变了（mtime 变）就是另一个文件名，
     * 手动刷新后不会再命中旧封面；无缓存目录时为 null。
     */
    private fun coverCacheFile(node: FsNode): File? = coverCacheDir?.let { dir ->
        val key = node.id.hashCode().toUInt().toString(16) + "_" + (node.lastModifiedMs ?: 0L)
        File(dir, "cbz_cover_$key.img")
    }

    /** 页面引用：普通图片页与压缩包内页的统一抽象 */
    private sealed interface PageRef {
        val name: String

        /**
         * 这一页的字节是不是**从压缩包里解出的**（打点与判读规则要按两条路分开）：
         * 包内读走 `RandomAccessBytes`（SMB/WebDAV 上真取数会发 `remoteRead`，**也可能被进程内块缓存接住**）；
         * 图片书（假）直接 `readBytes()` 读后端，**每一次都真读一次来源**、这条路上不发 `remoteRead`。
         */
        val fromArchive: Boolean

        fun bytes(): ByteArray
    }

    private class FilePageRef(private val node: FsNode) : PageRef {
        override val name: String get() = node.name
        override val fromArchive: Boolean get() = false
        override fun bytes(): ByteArray = node.readBytes()
    }

    private class ZipPageRef(private val node: FsNode, private val entry: ZipEntry) : PageRef {
        override val name: String get() = entry.name
        override val fromArchive: Boolean get() = true
        override fun bytes(): ByteArray =
            node.openRandomAccess().use { bytes -> ZipArchive(bytes).use { it.read(entry) } }
    }

    private companion object {
        const val COMIC_INFO = "ComicInfo.xml"

        /**
         * 看门狗默认时长：正常开包是个位数网络往返，60 秒远超任何可用的等待体验，
         * 只用来兜住「传输层自身不设超时 / 被锁住 / 卡在死循环」这类**不返回**的形态。
         */
        const val DEFAULT_READ_DEADLINE_MS = 60_000L

        /**
         * 看门狗用的作用域：**故意**不挂在调用者的协程树上（见 [readWithinDeadline]），应用级长存活。
         * 最坏情况留下一根卡住的线程，代价有界；挂到调用者树下则看门狗失效。
         */
        val deadlineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/**
 * 阻塞读超时：见 [DocumentTreeSource.readWithinDeadline]。
 * 不是 `CancellationException`（阅读器把取消当换书静默处理），message 即可直接展示的中文提示。
 */
class SourceReadTimeoutException(message: String) : Exception(message)
