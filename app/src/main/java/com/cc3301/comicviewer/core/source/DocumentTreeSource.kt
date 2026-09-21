package com.cc3301.comicviewer.core.source

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
 * 子目录属性探测的并发上限（票 #30）：每条探测 = 一次 list/PROPFIND 往返（SMB/WebDAV）
 * 或一次 provider IPC（SAF），无上限会把 NAS/网盘打爆，故设固定上限。
 */
const val SUBDIR_PROBE_LIMIT: Int = 8

/** 会话级列表缓存里「来源根容器」的键（`listEntries(null)` 的 containerId）：来源内的条目 id 恒非空 */
private const val ROOT_CONTAINER_ID: String = ""

/**
 * 会话级列表缓存的对象量级上界：一条 = 一个「容器 × 排序方式」，与一次会话浏览到的目录数同阶（百级）。
 * 来源实例自票 #30 起跨页面存活（见 `ServiceLocator.browsingSourceFor`），故给个简单上界防止长会话无上限增长；
 * 超出即整体清空，代价只是下次进入重列一次。
 */
private const val LIST_CACHE_MAX_ENTRIES: Int = 256

/**
 * 封面字节会话缓存的上界（票 #51 F2）：条目数上界与总字节上界任一越线即**淘汰最旧的条目**（票 #108 E2-B）。
 * 封面是一整张原始图片字节，条目数上界单独用会吃掉过多内存，因此另有 [COVER_CACHE_MAX_BYTES]。
 *
 * 票 #108 E2-B 改掉了原来「越界就整仓清空」的处置：快速滑动会把一屏一屏的封面都拉进来，落到上界那一刻
 * 连**屏上正显示**的那几张也一起被丢掉，于是它们当场重新向来源要字节——真机上就是「从无到有慢慢加载出来」
 * 那一态。按插入序淘汰最旧的后，屏上的封面天然是最新那批，不会被这次淘汰伤到（判据见
 * `DocumentTreeCoverCacheEvictionTest`）。
 */
private const val COVER_CACHE_MAX_ENTRIES: Int = 64
private const val COVER_CACHE_MAX_BYTES: Long = 8L * 1024 * 1024

/**
 * 上次枚举留下的**子目录探测结论**（票 #75 的增量重探）：判定「能否复用」所需的字段，
 * 因此判定是纯函数（[canReuseProbe]）、可单测。条目身份（id）不进这里——调用方按 id 查表，
 * 「查不到 = 新增或改名 = 必须探测」由那次查找承担。
 */
internal data class ProbeConclusion(
    /** 该子目录**自身**的 mtime（列目录/探测时顺手拿到的；后端不可得为 null） */
    val mtimeMs: Long?,
    /** 上次探测成功；false = 那条降级为容器的失败条目，按 #51 必须重试、一律不复用 */
    val probed: Boolean,
)

/**
 * 能否复用上次探测结论（票 #75）：纯函数、无 I/O（只读新条目的 mtime），增量重探的**唯一判定点**。
 *
 * 两条同时成立才复用：该子目录**自己**没变（mtime 相同）、上次探测成功。
 * - mtime 相同 = 它内部没动过，「是书还是文件夹」与封面指向因此与上次结论一致；
 * - 两边都取不到 mtime（SMB 共享根这类层）视同「没变」——与 #51 F3 的会话缓存口径一致；
 * - 探测失败的条目不复用：按 #51 它下次必须重试，增量路径不是例外。
 */
internal fun canReuseProbe(previous: ProbeConclusion?, current: FsNode): Boolean =
    previous != null && previous.probed && previous.mtimeMs == current.lastModifiedMs

/** 本次列表**先落地的来源**（票 #75 的打点口径）：会话内存快照 / 落盘快照 / 都没有（= 真列目录） */
internal enum class SnapshotHit { MEMORY, DISK, NONE }

/**
 * 一次枚举的请求计数（票 #74/#75 的真机验收打点）。
 *
 * 由调用方自建并交给 [DocumentTreeSource.enumerateEntries]：生产把它打进 logcat，单测直接读它
 * （`snapshotSource=`/三个计数只进 logcat，没有别的观测面），因此字段天然是「本次」而不是累计。
 * 三个计数各自只有一个自增点：`childrenCalls` 在枚举本层那一处的 `children()`、`probes` 在 `probeSubdirs`
 * 的入口、`reused` 在增量判定命中那处。整数自增是每次枚举的常数级开销；字符串拼接与平台调用仍全在
 * `PerfTiming.log {}` 的惰性 lambda 里（开关关闭时不拼字符串、不碰平台类）。
 *
 * 三个计数两处调用点（枚举与邻位补齐）都读；[hit] 只有枚举那条路径登记与读（见该字段说明）。
 */
internal class EnumerationStats(
    /** 本次列目录次数（生产里 = SMB list / SAF provider IPC 往返；#74 的「列目录 0 次」按它核对） */
    var childrenCalls: Int = 0,
    /** 本次子目录探测条数（生产里 = 每条一次 list/PROPFIND；#74 的「探测 0 次」按它核对） */
    var probes: Int = 0,
    /** 本次增量判定命中的条数（沿用上次结论、因此没发探测请求的条数） */
    var reused: Int = 0,
    /**
     * 本次列表的命中来源。**只有 [SnapshotHit.NONE] 表示本次真列了目录**（等价于 `childrenCalls > 0`）；
     * `MEMORY`/`DISK` 两种取值一律伴随 `childrenCalls == 0`，维护者据此判断「本次到底有没有真列目录」。
     *
     * 写点与读点都只在枚举那条路径：`snapshotOf` 把命中来源**随返回值**交出，[DocumentTreeSource.enumerateEntries]
     * 登记它并交给打点行；邻位补齐那条路径有自己的 `snapshot=<bool>` 口径（#93），因此不在那里写这个字段。
     */
    var hit: SnapshotHit = SnapshotHit.NONE,
)

/** 打点里的三个请求计数段（票 #75）：枚举与邻位补齐两处打点共用同一形状，不会两处走样 */
internal fun countsLog(stats: EnumerationStats): String =
    "childrenCalls=" + stats.childrenCalls + " probes=" + stats.probes + " reused=" + stats.reused

/**
 * 枚举打点行（纯函数，可单测）：字段口径只此一处。
 *
 * `snapshotSource=` 如实反映三条命中来源（旧写法只读内存表，落盘快照命中被打成 `false`）；
 * **它与邻位两行的 `snapshot=<bool>` 不是同一个键**（那是 #93 的「邻位判定依据是否命中」口径），
 * 因此维护者看一行就能判断「本次到底有没有真列目录」。
 * `childrenCalls`/`probes`/`reused` 是本次的三个请求计数（票 #75 的增量口径也靠它们对照）。
 */
internal fun enumerationLogLine(
    containerId: String?,
    sort: SortMode,
    entries: Int,
    stats: EnumerationStats,
    ms: Long,
): String = "listEntries container=" + (containerId ?: "<root>") + " sort=" + sort +
    " entries=" + entries + " snapshotSource=" + when (stats.hit) {
        SnapshotHit.MEMORY -> "memory"
        SnapshotHit.DISK -> "disk"
        SnapshotHit.NONE -> "none"
    } + " " + countsLog(stats) + " ms=" + ms

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
 * 一个目录**这一层**的内容分区（票 #97）：本层图片、本层子目录、本层压缩包，三者都按条目名称自然序排好。
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
     * 这个目录本身是不是一本书（票 #97）：**本层只有图片**才算。
     *
     * 本层有子目录或压缩包 ⇒ 它是容器：子目录与压缩包在浏览列表里各是一条独立条目（点一个读一个、各自是一本），
     * 本层图片也各自是条目（点一张 = 从该张连读本层其余图片）。
     * 旧口径下本层有图或压缩包就算书，并把本层压缩包的条目整包并进这一本
     * （现象：打开 A = B+C 的页数，维护者 100+ 本 1000+ 页的库一打开就加载上千页）。
     */
    val isBook: Boolean get() = images.isNotEmpty() && subDirs.isEmpty() && archives.isEmpty()
}

/** 按本层分区一个目录的子节点（票 #97 的判定接缝；纯函数，不做任何 I/O） */
internal fun dirContentsOf(kids: List<FsNode>, nameComparator: Comparator<String>): DirContents = DirContents(
    images = kids.filter { it.isImageFile() }.sortedWith(compareBy(nameComparator) { it.name }),
    subDirs = kids.filter { it.isDirectory }.sortedWith(compareBy(nameComparator) { it.name }),
    archives = kids.filter { it.isArchiveFile() }.sortedWith(compareBy(nameComparator) { it.name }),
)

/**
 * 文档树来源：本地 File 与 SAF 文档树的统一实现（票 04）。
 *
 * 目录判定规则（spec；票 #97 收口，判定只有一处 = [DirContents.isBook]）：
 * - 目录**本层只有图片** → 该目录是书（出现在父列表 isBook=true，从第 1 页按名称自然序打开全部本层图片）
 * - 目录本层有**子目录或压缩包** → 容器（isBook=false）：不递归合并、不拍平，子目录与压缩包在浏览列表里
 *   各是一条独立条目（点一个读一个），本层图片各自也是条目（点一张 = 从该张连读到本层末图）
 *
 * 因此「打开一个容器」的代价只与本层条目数有关，与下层书目数、总页数无关（票 #97 AC）；
 * `pagesOfBook` 的目录分支只取本层图片（本层有图的容器直接打开也能读，不并入下级）。
 *
 * 枚举性能（票 #30）：列目录只在**本层**一次 `children()` 之上并发探测各子目录是书还是容器
 * （[SUBDIR_PROBE_LIMIT] 上限），枚举期不做封面相关的额外往返（不逐级下取容器封面位置、
 * 不解压压缩包取首页），封面字节一律走按需通路 [coverBytes]；同一目录在同一会话内二次进入命中 [listCache]。
 *
 * 「会话」= 本实例被复用的那段生命周期：界面侧由 `ServiceLocator.browsingSourceFor` 按连接复用实例
 * （票 #30 P1），因此「进子目录 → 返回上级」与柜页/浏览页互切都命中同一份缓存，而不是每次进页面新建实例。
 */
class DocumentTreeSource(
    private val backend: FsBackend,
    private val progressStore: ProgressStore,
    /** Windows 自然排序（票 #3）；测试可注入自定义比较器 */
    private val nameComparator: Comparator<String> = WindowsNameOrder.COMPARATOR,
    /** 封面落盘缓存目录（票 10「封面生成后缓存」）；null 时不落盘、每次按需解出 */
    private val coverCacheDir: File? = null,
    /** 来源类型（票 11：本地与 SMB 复用同一实现，仅类型与后端不同） */
    private val sourceType: SourceType = SourceType.LOCAL,
    /**
     * 阻塞读的看门狗时长（票 #91，毫秒）：打开书 / 取页必须在有限时间内给出结果或报错。
     * 默认 [DEFAULT_READ_DEADLINE_MS]；测试注入小值来跑失败路径。语义与代价见 [readWithinDeadline]。
     */
    private val readDeadlineMs: Long = DEFAULT_READ_DEADLINE_MS,
    /**
     * 落盘列表快照（票 #74）：null = 不落盘（测试、无缓存目录）。
     * 键 = 连接 id + 容器 id，由 [ListingSnapshotStore] 承担；实例释放（[close]）**不**动落盘，
     * 这正是本票的目的——退出 APP 再进来仍能命中。
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
     * SMB 上这是实实在在的网络往返（票 11 review P1）。页数不再参与（票 #36）。
     */
    private val archiveEntryCache = ConcurrentHashMap<String, List<ZipEntry>>()

    /**
     * 会话级列表快照里的一条：对外条目 + 拿到它的那个节点（票 #51）。
     *
     * 保留节点是本票的关键：节点带着列目录时顺手拿到的元数据（mtime），因此
     * - 时间类排序不必再按 id 取节点（[resolve] 在网络来源上就是一次往返）；
     * - 探测重试可以直接用这些节点，不必重新列目录；
     * （票 #93 起相邻书判定不再消费这些节点：它只读快照自身，连 mtime 也不比。）
     * [probed] = 该子目录探测成功（false 的条目下次进入只重试它自己）。
     */
    private class ListingEntry(
        val entry: BrowseEntry,
        /**
         * 列目录/探测时顺手拿到的节点；**落盘快照恢复**的条目没有节点（票 #74）：
         * 那时只用快照里的 [mtimeMs]，不为排序重发一次 stat 风暴。
         */
        val node: FsNode?,
        /** 条目自身的修改时间（票 #74 起落盘，恢复后时间类排序不必再取节点） */
        val mtimeMs: Long?,
        val probed: Boolean = true,
    ) {
        /**
         * 条目 → 落盘形态（票 #74 第 3 轮 T3）：字段清单只此一处（[readPersistedSnapshot]/[cacheSnapshot]/[listingOf] 不再各拼一遍）。
         * 对端映射见 [PersistedListingEntry.toListingEntry]；落盘格式与行为一字不变（旧文件仍能读）。
         */
        fun toPersisted(): PersistedListingEntry = PersistedListingEntry(
            id = entry.id,
            name = entry.name,
            isBook = entry.isBook,
            coverUri = entry.coverUri,
            pageCount = entry.pageCount,
            mtimeMs = mtimeMs,
            probed = probed,
        )

        /** 快照里的一条 → 复用判定要的结论（票 #75）：落盘恢复的条目没有节点，mtime 取快照里记的那份 */
        fun toProbeConclusion(): ProbeConclusion =
            ProbeConclusion(mtimeMs = mtimeMs ?: node?.lastModifiedMs, probed = probed)

        /**
         * 复用上次探测结论（票 #75）：isBook 与封面指向沿用上次那条（这个子目录自己没变），
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
            probed = true,
        )
    }

    /** 落盘形态 → 条目（票 #74 第 3 轮 T3）：节点为 null——恢复的条目没有节点，见 [ListingEntry.node] */
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
        probed = probed,
    )

    /**
     * 会话级列表快照：一个容器一份（**与排序方式无关**——排序在快照之上进行，不再为换排序重列目录）。
     *
     * [mtimeMs] 为 null = 该容器拿不到 mtime（SMB 共享根是常态）：这时快照是**会话内缓存**，
     * 只由手动刷新 [invalidateListCache] 失效，二次进入连一次取节点都不发（票 #51 F3）。
     */
    private class ListingSnapshot(val mtimeMs: Long?, val entries: List<ListingEntry>)

    /**
     * 会话级列表快照表（票 #30 起，票 #51 改为按容器一份）：同一目录二次进入（含从子目录返回上级）
     * 不再重发同一批 list/PROPFIND，也不再为换排序方式重列。
     * 失效有两条路：容器 mtime 变化、显式刷新 [invalidateListCache]；[close]（实例被释放）**只清内存**——
     * 落盘快照见 [listingSnapshots]（票 #74：退出 APP 再进来仍命中）。规模假设见 [LIST_CACHE_MAX_ENTRIES]。
     */
    private val listings = ConcurrentHashMap<String, ListingSnapshot>()

    /**
     * 封面字节的会话级缓存（票 #51 F2）：键含条目 id 与 mtime（文件换过就换键）。
     * 之前只缓存**解码后的位图**，位图命中也要先向来源要字节——「返回上级再进来」会重下封面；
     * 现在字节层面直接命中，二次进入读字节 0 次。有上界（见 [COVER_CACHE_MAX_ENTRIES]/[COVER_CACHE_MAX_BYTES]），
     * 越界按插入序淘汰最旧（票 #108 E2-B：整仓清空会连屏上的封面一起丢）；
     * [invalidateListCache]（手动刷新）与 [close] 仍**整体清空**。
     *
     * 淘汰序是**插入序**而不是访问序：屏上的封面都是刚刚插进来的那一批，插入序淘汰能保住它们；
     * 访问序要额外维护链表，而本缓存的命中路径（每次重组读一遍屏上行）会把它变成一个高频写热点。
     */
    private val coverBytesCache = ConcurrentHashMap<String, ByteArray>()

    /** [coverBytesCache] 的插入序（淘汰时从队首取最旧）：与 Map 一起清空/写入 */
    private val coverBytesOrder = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /** 封面字节缓存当前占用的字节数（配合 [coverBytesCache] 的上界） */
    private val coverBytesCachedTotal = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * 探测期已知的「目录内首图」（票 #51 F2）：键 = 子目录 id + mtime。
     *
     * 探测「这个子目录是不是书」时本来就把它的子节点列了出来，首图就在手里；不记下来的话，
     * 可见行取封面时还要再列一次同一目录（SMB 上就是一次多余往返）。
     */
    private val probedFirstImages = ConcurrentHashMap<String, FsNode>()

    override val type: SourceType get() = sourceType

    /**
     * 释放本实例：清列表缓存并释放后端会话（SMB）；本地后端无资源，忽略。
     * 清缓存与释放会话同步发生——实例被丢掉后缓存不再有消费者（票 #30 P2）。
     */
    override fun close() {
        clearSessionCaches()
        (backend as? AutoCloseable)?.let { runCatching { it.close() } }
    }

    /**
     * 清空本实例的全部会话级缓存（[close] 与手动刷新共用；换连接/编辑连接即随实例释放）。
     * 有意**不**动落盘快照（[listingSnapshots]）：实例释放正是本票要跨过的那条边界。
     */
    private fun clearSessionCaches() {
        listings.clear()
        clearCoverBytes()
        probedFirstImages.clear()
        releaseCache.clear()
        archiveEntryCache.clear()
    }

    override suspend fun listEntries(containerId: String?, sort: SortMode): List<BrowseEntry> =
        enumerateEntries(containerId, sort, EnumerationStats())

    /**
     * 取该容器**已有快照**的条目（票 #75 两段式读取的第一段）：会话内存快照优先（与 [cachedEntries]
     * 同一口径），内存没有就读**落盘快照**（票 #74）；两者都没有返回 null。**0 次列目录、0 次探测**。
     *
     * 有意**不**在这里把落盘快照装进内存表：第二段（[listEntries]）自己按 mtime 决定命中还是重列，
     * 打点的 `snapshotSource=` 因此如实归属（disk / none），不会被这一段的读盘抹成 memory；代价是同一个落盘
     * 文件在一次进入里被读两次（本地小文件读，不是网络往返）。
     */
    override suspend fun snapshotEntries(containerId: String?, sort: SortMode): List<BrowseEntry>? =
        cachedEntries(containerId, sort)
            ?: readPersistedSnapshot(containerId)?.let { sortedEntriesOf(it, sort) }

    /**
     * 枚举的**唯一实现**（票 #30/#51/#74/#75 的全部口径都在这里）：[listEntries]（单段、对外）与界面侧的
     * 两段式第二段都走它，因此两条路径的缓存/重列/增量重探/打点口径只有一处。
     *
     * internal 而不是 private 只有一个理由：`stats` 里的 `snapshotSource=` 与三个计数**只进 logcat**，
     * 而它们是本票的验收口径（「命中快照 ⇒ 0 次列目录」「mtime 已变 ⇒ 真列目录」「只探 1 条」），
     * 单测需要一个能读到它们的入口（见 `DocumentTreeIncrementalProbeTest`）。
     */
    internal suspend fun enumerateEntries(
        containerId: String?,
        sort: SortMode,
        stats: EnumerationStats,
    ): List<BrowseEntry> {
        val startedNanos = System.nanoTime()
        val enumerated = snapshotOf(containerId, stats)
        // 命中来源只在这一条路径登记（它也是唯一读者：打点 `snapshotSource=`）；邻位补齐那条路径不写它
        stats.hit = enumerated.hit
        // 排序在快照之上进行（票 #51 F1）：键一次性取齐，比较器里不做任何 I/O——旧实现在选择器里
        // 按 id 取节点，而 Kotlin 的 compareBy* 每次比较都调用选择器，百级目录就是上千次网络往返
        val sorted = sortEntries(enumerated.snapshot.entries, sort).map { it.entry }
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
     * 取某个容器的会话级快照（票 #30 起；票 #51 改为复用节点 + 支持部分失败；票 #75 起 mtime 变化走增量重探）：
     * - 命中且未过期 → 直接返回；其中**探测失败**的那几条只重试它们自己（票 #51 F4）；
     * - 未命中/已过期 → 列一次本层 + 探测各子目录（[SUBDIR_PROBE_LIMIT]），落快照。
     *
     * 「是否过期」只看容器 mtime：拿不到 mtime 的层（SMB 共享根）走会话内缓存，只由手动刷新失效
     * （票 #51 F3）；拿得到 mtime 的层每次进入花一次取节点比对（与条目数无关，不再是 O(n log n)）。
     * 根容器的节点是构造期快照，因此它的 mtime 在**已有缓存需要比对**时才现取：首次枚举零取节点，
     * 而「往共享根/授权根新增书后能看到」的语义不丢（票 #30 的既有约束）。
     *
     * mtime 变了（会话内快照过期 / 落盘快照过期，票 #75）：仍只发 **1 次列目录**，但把新结果与那份旧快照
     * 按「条目 id + 该条目自身 mtime」比对，**只重探新增或自身 mtime 变化的子目录**（见 [listingOf]）。
     * 打点因此记 `snapshotSource=none`（本次真列了目录）；`memory`/`disk` 两种取值一律伴随 0 次列目录。
     *
     * 命中来源**随返回值交出**而不是写进调用方的 [EnumerationStats]：只有枚举行读它（邻位补齐有自己的
     * `snapshot=<bool>` 口径），因此不在那条路径上留下一个只写不读的字段。
     */
    private suspend fun snapshotOf(containerId: String?, stats: EnumerationStats): EnumeratedListing {
        val key = snapshotKeyOf(containerId)
        val cached = listings[key]
        // 本次可以拿来复用探测结论的旧快照（票 #75）：null = 没有可复用的结论 → 整层全量重探
        var previous: ListingSnapshot? = null
        var freshMtime: Long? = null
        var mtimeKnown = false
        if (cached != null) {
            if (cached.mtimeMs == null) return unchangedSnapshot(key, cached, SnapshotHit.MEMORY, stats)
            // 取 mtime 失败（离线/服务器不可达，票 #74 第 2 条）：先用快照把列表显示出来，
            // **不**把快照的 mtime 降级成 null（否则一次离线会让该层以后永不按 mtime 失效）
            val current = runCatching { currentMtimeOf(containerId) }
            if (current.isFailure) return unchangedSnapshot(key, cached, SnapshotHit.MEMORY, stats)
            freshMtime = current.getOrNull()
            mtimeKnown = true
            if (freshMtime == null || freshMtime == cached.mtimeMs) {
                return unchangedSnapshot(key, cached, SnapshotHit.MEMORY, stats)
            }
            previous = cached
        } else {
            // 内存未命中（新实例 / 上界腾掉）：先问落盘快照（票 #74）。命中且 mtime 一致 → 0 次列目录、0 次探测
            val persisted = readPersistedSnapshot(containerId)
            if (persisted != null) {
                val persistedMtime = persisted.mtimeMs
                // 快照没记 mtime 的层（SMB 共享根这类）：连这次取节点都不发（票 #74 AC），直接用快照
                if (persistedMtime == null) {
                    rememberSnapshot(key, persisted)
                    return unchangedSnapshot(key, persisted, SnapshotHit.DISK, stats)
                }
                val current = runCatching { currentMtimeOf(containerId) }.getOrNull()
                // 取不到 mtime（离线、节点没有 mtime）→ 视同未变，直接用快照（票 #74 第 2 条）
                if (current == null || current == persistedMtime) {
                    rememberSnapshot(key, persisted)
                    return unchangedSnapshot(key, persisted, SnapshotHit.DISK, stats)
                }
                // mtime 变了（票 #75）：这份落盘快照当增量重探的比对来源；本次真列目录 → 打点 NONE
                previous = persisted
                freshMtime = current
                mtimeKnown = true
            }
        }
        val dir = resolveNode(containerId)
        // 快照的 mtime：已经现取到就用现取值（根容器是构造期快照，拿它当键会让「下一次比对」永远不等、
        // 每次进入都白重列一次）；首次枚举没有现取值时才用节点自带的值——下次进入自然对齐。
        val snapshot = ListingSnapshot(
            mtimeMs = if (mtimeKnown) freshMtime else dir.lastModifiedMs,
            entries = listingOf(dir, previous, stats),
        )
        cacheSnapshot(key, snapshot)
        return EnumeratedListing(snapshot, SnapshotHit.NONE)
    }

    /**
     * 一次取快照的结果（票 #75）：快照本体 + 本次列表的**命中来源**。
     * 命中来源随返回值交出而不是写进调用方的 [EnumerationStats]：只有枚举行读它（打点 `snapshotSource=`），
     * 邻位补齐那条路径有它自己的 `snapshot=<bool>` 口径、不读这一项。
     */
    private class EnumeratedListing(val snapshot: ListingSnapshot, val hit: SnapshotHit)

    /**
     * 快照判定为「没变」时的收口（票 #51/#74/#75）：把命中来源（[SnapshotHit.MEMORY]/[SnapshotHit.DISK]
     * 都伴随 0 次列目录）随返回值交出，再按 #51 只重试上次探测失败的那几条。
     */
    private suspend fun unchangedSnapshot(
        key: String,
        snapshot: ListingSnapshot,
        hit: SnapshotHit,
        stats: EnumerationStats,
    ): EnumeratedListing = EnumeratedListing(refreshFailedProbes(key, snapshot, stats), hit)

    /**
     * 快照键：根容器无论用 `null`（浏览页路由）还是它的真实节点 id（相邻书判定从 `parent()` 拿到的）
     * 都是同一份快照——否则「根列表」会被枚举两次、缓存形同虚设。
     */
    private fun snapshotKeyOf(containerId: String?): String =
        if (containerId == null || containerId == rootNode.id) ROOT_CONTAINER_ID else containerId

    /** 容器当前的修改时间：根容器用 [rootNode] 的 id 现取（构造期快照不可信，票 #30） */
    private fun currentMtimeOf(containerId: String?): Long? =
        resolve(containerId ?: rootNode.id)?.lastModifiedMs

    /**
     * 只重试快照里探测失败的子目录（票 #51 F4）：其余条目照常命中缓存。
     * 一条探测失败不再让整层缓存作废——旧行为下 100 个子目录里挂 1 个，每次重返都要全量重探。
     * 落盘恢复的条目没有节点（票 #74），只在这里按 id 取一次它自己。
     */
    private suspend fun refreshFailedProbes(
        key: String,
        cached: ListingSnapshot,
        stats: EnumerationStats,
    ): ListingSnapshot {
        val failed = cached.entries.filter { !it.probed }
        if (failed.isEmpty()) return cached
        val nodes = failed.mapNotNull { nodeOf(it) }
        if (nodes.isEmpty()) return cached
        val retried = probeSubdirs(nodes, stats).associateBy { it.node.id }
        val merged = cached.entries.map { entry ->
            retried[entry.entry.id]?.let { probe -> probe.toListingEntry() } ?: entry
        }
        val updated = ListingSnapshot(cached.mtimeMs, merged)
        cacheSnapshot(key, updated)
        return updated
    }

    /**
     * 读该容器的**落盘快照**（票 #74）：不存在 / 超 TTL / 版本不匹配 / 读坏都返回 null。
     * **不比对 mtime**——那是调用方的事：两段式第一段（[snapshotEntries]）只拿它显示，
     * 第二段（[snapshotOf]）按 mtime 决定命中还是重列、并把它当增量重探的比对来源（票 #75）。
     */
    private suspend fun readPersistedSnapshot(containerId: String?): ListingSnapshot? {
        val store = listingSnapshots ?: return null
        return withContext(Dispatchers.IO) {
            val persisted = store.read(snapshotKeyOf(containerId)) ?: return@withContext null
            ListingSnapshot(
                mtimeMs = persisted.mtimeMs,
                entries = persisted.entries.map { it.toListingEntry() },
            )
        }
    }

    /** 快照表写入（内存 + 落盘，票 #74）；达到上界先整体清空（见 [LIST_CACHE_MAX_ENTRIES]） */
    private fun cacheSnapshot(key: String, snapshot: ListingSnapshot) {
        rememberSnapshot(key, snapshot)
        val store = listingSnapshots ?: return
        store.write(
            key,
            PersistedListing(
                mtimeMs = snapshot.mtimeMs,
                entries = snapshot.entries.map { it.toPersisted() },
            ),
        )
    }

    /** 只进内存快照表（落盘恢复的路径不复写磁盘，见 [readPersistedSnapshot]） */
    private fun rememberSnapshot(key: String, snapshot: ListingSnapshot) {
        if (listings.size >= LIST_CACHE_MAX_ENTRIES) listings.clear()
        listings[key] = snapshot
    }

    /**
     * 会话级列表快照的显式失效/刷新入口（票 #30；票 #51 起也清封面字节缓存；
     * 票 #74 起**同时清落盘快照**——下拉更新与连接编辑都要求真失效，不能只清内存）。
     * 传容器 id 清该容器，null 清来源根容器。手动刷新（下拉更新）走这里。
     */
    override fun invalidateListCache(containerId: String?) {
        val key = snapshotKeyOf(containerId)
        listings.remove(key)
        listingSnapshots?.remove(key)
        // 刷新要真刷封面：字节缓存一并清掉（否则还会把同一条目的旧封面还回去）
        clearCoverBytes()
    }

    /**
     * 同步读快照（票 #74 起实现 [Source.cachedEntries]）：**只读内存快照且绝不做 IO**——不列目录、
     * 不比对 mtime（[sortEntries] 的 `snapshotOnly` 口径：发布时间键只查已算过的缓存、缺失用 mtime 兜底，
     * 不 resolve、不开包）。界面「从阅读器返回浏览页」的首帧据此立即出列表（票 #73 承办 AC3）。
     * 内存未命中（**冷启动首帧** / 被上界腾掉）时返回 null，调用方照常走异步路径：票 #75 起那条路径分两段，
     * 但两段都要**先等会话来源解析完**（与 #74 同一句：`BrowserScreen` 的 `source` 在 IO 上异步解析），
     * 随后第一段（[snapshotEntries]）先落快照帧；「加载中…」期间不再发生列目录/探测，落盘快照本身
     * 仍是 **0 次列目录、0 次探测**。
     */
    override fun cachedEntries(containerId: String?, sort: SortMode): List<BrowseEntry>? =
        listings[snapshotKeyOf(containerId)]?.let { sortedEntriesOf(it, sort) }

    /** 快照 → 按 [sort] 排好的条目（同步口径：不做任何 IO，发布时间键只查已算过的缓存） */
    private fun sortedEntriesOf(snapshot: ListingSnapshot, sort: SortMode): List<BrowseEntry> =
        sortEntries(snapshot.entries, sort, snapshotOnly = true).map { it.entry }

    /**
     * 枚举一个目录（票 #30；票 #51 起结果带节点；票 #75 起支持**增量重探**）。枚举期不统计页数（票 #36）：
     * 不为页数读压缩包中央目录、不为页数列子目录，列表条目的 pageCount 一律为 null（页数只在打开书后由
     * BookHandle.pageCount 给出）。本层只一次 `children()`（SAF = 一次 provider IPC、SMB = 一次 list），
     * 分区后各自排序。
     *
     * 每条都带上列目录时拿到的那个节点（票 #51）：排序与探测重试都复用它，
     * 不再按 id 取节点——在网络来源上每次 `resolve` 就是一次往返。
     * （票 #93 起相邻书判定也不再走这里，它只读快照本身。）
     *
     * [previous] = 上次枚举留下的快照（会话内快照 / 落盘快照，票 #75）：子目录按「条目 id + 该条目自身 mtime」
     * 与它比对，未变化的行**沿用上次探测结论、不发探测请求**（判定见 [canReuseProbe]，计数见 [stats]）；
     * null = 没有可复用的结论（本来就没快照 / 下拉更新清过快照）→ 整层全量重探。
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
        // 增量重探（票 #75）：未变化的子目录沿用上次结论，只把新增/变化的交给受控并发探测（见 probeSubdirs）
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

        // 本层图片（票 #97）：本层有子目录或压缩包时，图片各自是一条条目，从该图连读到本层末图
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
        fun toListingEntry(): ListingEntry = ListingEntry(entry, node, node.lastModifiedMs, probed)
    }

    /**
     * 子目录属性探测并发执行（票 #30）：并发上限 [SUBDIR_PROBE_LIMIT]，结果按子目录名称序返回
     * （拼接顺序与旧实现一致，条目集合/排序/isBook 语义不受影响）。
     * 本票（#75）只改变**交给它的条数**：增量重探时这里只剩新增/变化的行（打点 `probes=`）。
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
    private fun probeSubdir(sub: FsNode): SubdirProbe = try {
        val contents = dirContentsOf(sub.children(), nameComparator)
        // 探测时本来就把首图列出来了（票 #51 F2）：记下来，可见行取封面时不必再列一次这个目录
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
    } catch (t: CancellationException) {
        throw t
    } catch (t: Throwable) {
        // 网络/传输故障必须冒泡（core/source/TransportFailure.kt 的类型契约，与 [archiveEntries] 同式）：
        // 断链时整批子目录都列不出来，静默降级会得到一份「全是容器、封面全空、不报错」的假列表
        if (t is TransportFailure) throw t
        SubdirProbe(
            entry = BrowseEntry(id = sub.id, name = sub.name, isBook = false, coverUri = null, pageCount = null),
            node = sub,
            probed = false,
        )
    }

    /**
     * 封面字节（票 11/票 #30）：无系统可解码 uri 的来源（SMB/WebDAV）由 UI 回退到这里；
     * 本地/SAF 也走这里——列表只为「目录内首图」「图片本身」这类零开销的封面给 uri，
     * 容器封面与压缩包封面一律按需取（只为可见行发生，枚举期不再发生）。
     * 规则（spec 故事 9）：压缩包取包内首页，图片取本身，目录从本层开始逐级下取——**每层**都是
     * 本层首图 → 本层首个压缩包的首帧 → 子目录（票 #102：单层与下取同一套优先级）；
     * 取不到返回 null（界面显示占位底色）。
     */
    override suspend fun coverBytes(entryId: String): ByteArray? = runCatching {
        val startedNanos = System.nanoTime()
        val node = resolve(entryId) ?: return null
        // 字节级会话缓存（票 #51 F2）：键含 mtime，文件换过就是另一个键
        val cacheKey = coverBytesKey(node)
        coverBytesCache[cacheKey]?.let {
            PerfTiming.log { "coverBytes id=$entryId bytes=${it.size} cached=true ms=0" }
            return it
        }
        val bytes = when {
            node.isArchiveFile() -> archiveCoverBytes(node)
            node.isImageFile() -> node.readBytes()
            // 库「一个子文件夹一本书」是常见布局：探测期已经列过这个目录，首图就在手里（票 #51 F2）
            node.isDirectory -> probedFirstImageBytes(node) ?: directoryCoverBytes(node)
            else -> null
        } ?: return null
        putCoverBytes(cacheKey, bytes)
        PerfTiming.log {
            val ms = (System.nanoTime() - startedNanos) / 1_000_000
            "coverBytes id=$entryId bytes=${bytes.size} cached=false ms=$ms"
        }
        return bytes
    }.getOrNull()

    /** 封面字节缓存的键：条目 id + mtime（mtime 不可得时用 0，随手动刷新失效） */
    private fun coverBytesKey(node: FsNode): String = node.id + "@" + (node.lastModifiedMs ?: 0L)

    /**
     * 写入封面字节缓存：条目数或总字节超上界即**从最旧的条目开始淘汰**，直到回到界内（票 #108 E2-B）。
     * 同一键重写（同 id 同 mtime 再取一次）只改字节数帐，不重复进队。
     */
    private fun putCoverBytes(key: String, bytes: ByteArray) {
        val previous = coverBytesCache.put(key, bytes)
        if (previous != null) {
            coverBytesCachedTotal.addAndGet((bytes.size - previous.size).toLong())
        } else {
            coverBytesOrder.add(key)
            coverBytesCachedTotal.addAndGet(bytes.size.toLong())
        }
        while (coverBytesCache.size > COVER_CACHE_MAX_ENTRIES || coverBytesCachedTotal.get() > COVER_CACHE_MAX_BYTES) {
            val oldest = coverBytesOrder.poll() ?: return
            coverBytesCache.remove(oldest)?.let { coverBytesCachedTotal.addAndGet(-it.size.toLong()) }
        }
    }

    /** 整体清空封面字节缓存（手动刷新 / 会话释放）：字节、插入序与字节数帐三处必须同时清 */
    private fun clearCoverBytes() {
        coverBytesCache.clear()
        coverBytesOrder.clear()
        coverBytesCachedTotal.set(0L)
    }

    /** 记下探测期看到的「目录内首图」（键含目录 mtime，目录一变就换键，不会拿旧首图当封面） */
    private fun rememberProbedFirstImage(dir: FsNode, firstImage: FsNode) {
        if (probedFirstImages.size >= COVER_CACHE_MAX_ENTRIES) probedFirstImages.clear()
        probedFirstImages[coverBytesKey(dir)] = firstImage
    }

    /** 探测期已知的目录内首图字节：命中即 0 次列目录（票 #51 F2） */
    private fun probedFirstImageBytes(dir: FsNode): ByteArray? =
        probedFirstImages[coverBytesKey(dir)]?.let { runCatching { it.readBytes() }.getOrNull() }

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
                    "loadPage id=$bookId page=$index bytes=${bytes.size} ms=${(System.nanoTime() - startedNanos) / 1_000_000}"
                }
                return PageData(bytes, mimeTypeOf(page.name))
            }
        }
    }

    /**
     * 阻塞读的看门狗（票 #91 AC「失败必须可失败」）：打开的压缩包 / 取的一页必须在有限时间内给出结果。
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
     * 相邻书（票 07 口径；票 #93 起**只读会话快照**）：同一容器内 isBook 条目按名称自然序的前后邻位。
     *
     * 票 #93：本方法不得触发父层的列目录与子目录探测——SMB/WebDAV 上「这一层每个子目录是不是书」
     * 就是每个子目录多次往返，打开一本书顺手付掉这一层的观感就是维护者说的
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
        // 真机验收打点（票 #91 协议，默认关闭）：`snapshot=false` 即降级路径，两者都应当是 0 次列目录/探测。
        // 这个 `snapshot=` 是 #93 的布尔口径（邻位判定依据是否命中），**与枚举行的 `snapshotSource=` 不是同一个键**。
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
     * 后台补齐邻位层（票 #93 修复轮，契约见 [Source.warmNeighbors]）：
     * 只在**该层没有快照**时枚举一次（走 [snapshotOf]，与浏览页首次进该层同一条路径与同一份缓存）；
     * 已有快照则直接返回（不再枚举，只花按 id 取一次节点）。
     *
     * 本方法由界面在**进阅读器之后**的后台协程里调，不在打开/落点路径上：未补齐时 [neighbors] 仍是瞬时返回的空结果，
     * 因此本票的提速口径不回退。失败（传输故障/离线）直接冒出给调用方：不重试、不轮询，邻位保持未知。
     */
    override suspend fun warmNeighbors(bookId: String) {
        val startedNanos = System.nanoTime()
        val listParent = listParentOf(resolveNode(bookId)) ?: return
        val cached = listings[snapshotKeyOf(listParent.id)]
        val stats = EnumerationStats()
        // 返回值里的命中来源不读：邻位行有它自己的 `snapshot=<bool>` 口径（与枚举行的 `snapshotSource=` 不是同一个键），
        // 因此这条路径不写 [EnumerationStats.hit]；三个计数两处都读（下面的 countsLog）
        if (cached == null) snapshotOf(listParent.id, stats)
        // 真机验收打点：`snapshot=false` = 本次真补齐了一次（窗口期内邻位仍未知），true = 无需补齐
        PerfTiming.log {
            "warmNeighbors id=" + bookId + " snapshot=" + (cached != null) + " " + countsLog(stats) +
                " ms=" + ((System.nanoTime() - startedNanos) / 1_000_000)
        }
    }

    /**
     * 相邻书判定用的书列表（票 #51 F5 起**读会话快照**，票 #93 起**只读**快照）：isBook 集合与 [listingOf] 一致
     * （判定见 [DirContents.isBook]，这里不重述），按全局名称序排序
     * （review P1：分区拼接顺序会与浏览列表名称序不一致）。
     *
     * 这就是「主路径复用」（票 #93 AC2）：浏览页枚举当前层时落的快照即这份数据（[listEntries] 的枚举结果），
     * 因此点开书时邻位来自已经算过的列表，**不重列、不重探**。
     *
     * 三条承重细节（改这里先看这三条）：
     * - 不走 [snapshotOf]：那个函数在快照未命中时会真去列这一层（含逐子目录探测），正是本票要拆的东西。
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
     * 书的页面序列（票 #97）：
     * - 目录 = 只取**本层**图片（名称自然序），本层压缩包不再并入
     *   （旧实现把本层压缩包的条目整包并进来 → 维护者现象「打开 A = B+C 的页数」）
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

    // ---------- 按需封面（票 #30：封面字节只在可见行取，枚举期不发生）----------

    /**
     * 目录封面字节（spec 故事 9）：从本层开始**逐级下取**，**每一层都走同一套优先级**（[layerCoverBytes]）——
     * 本层图片 → 本层首个压缩包的首帧 → 子目录（名称序、深度优先）；取到即停，取不到返回 null。
     *
     * 票 #102 修复：旧实现的下取只找图片，`C/` → `A/` → `B.cbz` 的 C 因此没有封面
     * （「单层目录」与「逐级下取」是两套口径）；现在两条路共用 [layerCoverBytes]，口径只有一处。
     * 代价有上界：每层目录只列一次；每层最多开一个包（本层没有图时才开名称序第一个包，同层其余包不碰）。
     *
     * 探测期已知首图的情形（书文件夹）在 [coverBytes] 里就返回了，不会走到这里（票 #51 F2）。
     */
    private fun directoryCoverBytes(dir: FsNode): ByteArray? = firstCoverBytesDeep(dir.children())

    /** 逐级下取（容器封面规则）：先按本层优先级取，取不到才下探子目录（名称序、深度优先、每层目录只列一次） */
    private fun firstCoverBytesDeep(kids: List<FsNode>): ByteArray? {
        // 本层分区走 [dirContentsOf]（「一个目录里有什么」的唯一判定点），不再手写第三份 filter+sort
        val contents = dirContentsOf(kids, nameComparator)
        layerCoverBytes(contents)?.let { return it }
        for (sub in contents.subDirs) {
            firstCoverBytesDeep(sub.children())?.let { return it }
        }
        return null
    }

    /**
     * 一层目录的封面优先级（票 #102 起「单层目录」与「逐级下取」共用这一处）：
     * ① [DirContents.images] 首图 → ② [DirContents.archives] 首个（名称序）的首帧 → null。
     * ②只开名称序那一个包；同层的其余包一个都不开（取封面不为同层每个包买单）。
     * 分区由 [dirContentsOf] 给出，与浏览列表/页序列同一套口径。
     */
    private fun layerCoverBytes(contents: DirContents): ByteArray? =
        contents.images.firstOrNull()?.readBytes()
            ?: contents.archives.firstOrNull()?.let { archiveCoverBytes(it) }

    /**
     * 排序（票 #51 F1；票 #74 起分两条口径）：**键一次性取齐**，比较器里不做任何 I/O。
     *
     * Kotlin 的 `compareBy*` 每次比较都会调用选择器，所以旧实现的 `compareByDescending { resolve(it.id)… }`
     * 在百级目录上一次排序就是上千次「按 id 取节点」（SMB 上每次都是 folderExists + 文件信息两次往返）。
     * 现在修改时间优先用列目录时顺手拿到的节点 mtime（票 #74 起落盘快照里也记了它，恢复后同样 0 次取节点）；
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
     * 条目的发布时间排序键（票 #74）：
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

    /** 条目的节点：落盘恢复的条目按 id 取（只在异步消费点发生，见 [releaseKeyOf] 与 [refreshFailedProbes]） */
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

    // ---------- 压缩包（CBZ/ZIP，票 10）----------

    private fun <T> withArchive(node: FsNode, block: (ZipArchive) -> T): T =
        node.openRandomAccess().use { bytes -> ZipArchive(bytes).use(block) }

    /**
     * 包内图片条目：按文件名自然序（spec 故事 54：页序 = ZIP 内文件名自然排序）。
     *
     * **全深度**收集（`isArchiveImageEntry` 不看层级；票 #97 有意保持）：包内套一层书名目录
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

    /** 压缩包封面字节（按需，票 #30 从枚举期移到可见行）：包内首页的原始字节，由界面解码 */
    private fun archiveFirstPageBytes(node: FsNode): ByteArray? {
        val entry = archiveEntries(node).firstOrNull() ?: return null
        return withArchive(node) { zip -> zip.read(entry) }
    }

    /**
     * 压缩包封面字节 + 落盘缓存（票 10 的「封面生成后缓存」：票 #30 只是把它从枚举期搬到按需通路）：
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
     * 落盘走「临时文件 + 原子改名」（票 #30 P2）：同一个 entry 的封面可被界面并发触发
     * （可见行重进组合、刷新时 LaunchedEffect 重启），直接 writeBytes 会先截断再写，
     * 并发的读者可能读到半截文件而解码失败。写失败只是白解一次，下次仍会重试。
     * 改名成功后再清同一本书的旧封面文件（票 #30 P2）：文件名带 mtime，不清理的话每改一次多留一份。
     */
    private fun writeCoverCacheFile(target: File, bytes: ByteArray) {
        runCatching {
            val dir = target.parentFile ?: return
            val tmp = File.createTempFile(target.name, ".tmp", dir)
            try {
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    return
                }
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
     * 封面缓存文件（沿用票 10 的命名，键并入 node.id 与 mtime）：内容变了（mtime 变）就是另一个文件名，
     * 手动刷新后不会再命中旧封面（票 #30 P2）；无缓存目录时为 null。
     */
    private fun coverCacheFile(node: FsNode): File? = coverCacheDir?.let { dir ->
        val key = node.id.hashCode().toUInt().toString(16) + "_" + (node.lastModifiedMs ?: 0L)
        File(dir, "cbz_cover_$key.img")
    }

    /** 页面引用：普通图片页与压缩包内页的统一抽象（票 10） */
    private sealed interface PageRef {
        val name: String
        fun bytes(): ByteArray
    }

    private class FilePageRef(private val node: FsNode) : PageRef {
        override val name: String get() = node.name
        override fun bytes(): ByteArray = node.readBytes()
    }

    private class ZipPageRef(private val node: FsNode, private val entry: ZipEntry) : PageRef {
        override val name: String get() = entry.name
        override fun bytes(): ByteArray =
            node.openRandomAccess().use { bytes -> ZipArchive(bytes).use { it.read(entry) } }
    }

    private companion object {
        const val COMIC_INFO = "ComicInfo.xml"

        /**
         * 看门狗默认时长（票 #91）：本轮修复后正常开包是个位数网络往返，60 秒远超任何可用的等待体验，
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
 * 阻塞读超时（票 #91）：见 [DocumentTreeSource.readWithinDeadline]。
 * 不是 `CancellationException`（阅读器把取消当换书静默处理），message 即可直接展示的中文提示。
 */
class SourceReadTimeoutException(message: String) : Exception(message)
