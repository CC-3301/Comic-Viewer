package com.cc3301.comicviewer.core.view

/**
 * 一条封面的**字节从哪来**（票 #108 r3，纯函数，由 [CoverUriSourceTest] 锁定）。
 *
 * 浏览页的封面通路是二分的（见 `ui/CoverThumb.kt`）：`coverUri` 是系统解码器认得的那两种 scheme 时走
 * `PageDecoder.decodeCoverUri`（系统自己解，**从不碰 `Source.coverBytes`**），其余（null / 空 / SMB、
 * WebDAV 的标识串）才回退到来源字节。
 *
 * 这个判据有两个消费者，必须是同一处（否则预取与渲染会各按一套口径走）：
 * - `CoverThumb` 决定这次渲染走哪条路；
 * - 浏览页的预取决定**要不要**提前取字节（票 #108 E2-B）：对 uri 行预取是白读整张图——读出来的字节
 *   既没有人复用（可见行不解它），又要占同一份会话字节缓存的字节帐（上界一满就淘汰最旧的），
 *   反过来把真正要用字节的条目挤出缓存（票 #108 r3 评审 P1）。
 *
 * 票 #135 r2 起两个消费者都**经 `ui/CoverPlan` 转调**本件（`CoverPlan.route` 要 uri + 键、`CoverPlan.viaSourceBytes`
 * 只要布尔），生产侧不再另写一份 `uri == null`：判据只此一处，改这里两条路一起变。
 */
internal object CoverUriSource {

    /** 系统解码器能直接解的封面 uri（`content://` / `file://`）；其余返回 null（交给 [Source.coverBytes]） */
    fun decodable(coverUri: String?): String? = coverUri
        ?.takeIf { it.isNotEmpty() }
        // SMB/WebDAV 的标识串（smb://… / webdav-http://…）系统解不了：直接走来源字节，不白跑一次 ContentResolver
        ?.takeIf { it.startsWith("content://") || it.startsWith("file://") }

    /** 这一条封面要不要经来源字节通路取 = **可见行会不会调 `Source.coverBytes`**：预取只对它为真的条目生效 */
    fun viaSourceBytes(coverUri: String?): Boolean = decodable(coverUri) == null
}
