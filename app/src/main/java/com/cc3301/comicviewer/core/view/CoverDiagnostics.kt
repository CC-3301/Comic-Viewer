package com.cc3301.comicviewer.core.view

import com.cc3301.comicviewer.core.source.PerfTiming

/**
 * 封面**源图分辨率**的打点（票 #140，行格式的唯一出处）。
 *
 * 现象（维护者真机反馈 2026-09-27）：「**Komga 连接源内的封面很糊**」。静态核对出的现状：
 * - 客户端的解码宽度按**实际显示宽度**分桶（票 #56，网格 2 列 ≈ 470–500px），一侧不糊；
 * - 且客户端的保留位图**只缩不放**（[CoverDecode.plan] 的 `retainedWidth = min(带, targetWidthPx)`，
 *   整图分支也只做子采样）⇒ **源图比显示盒小的时候客户端不弥补，只能被放大铺满**，观感就是「糊」；
 * - Komga 的封面当时走服务端生成的缩略图（`/api/v1/books/{id}/thumbnail`，**不带任何尺寸参数**）
 *   ⇒ 头号嫌疑是**服务端缩略图的分辨率低于显示盒需要的像素**（取数结论已坐实：它按高 300px 固定生成；
 *   票 #140 的 A 案已改成取第 1 页原图，见下一条）。
 *
 * 本对象只回答一件事：**这一次真实解码时，源宽够不够显示盒**。够不够由 [upscale] 一个字段给死
 * （`true` = 源宽 < 目标 px ⇒ 一定会被放大 ⇒ 糊的来源就在上游）。判据成立后修法在票面里另选
 * （要更大的服务端缩略图 / 改用第 1 页原图 / 服务端配置），本对象不参与选型。
 *
 * 落点：[PageDecoder.decodeCoverBytes]（**解码缓存未命中之后**才打）——因此一行 = 一次真解码，
 * 预取与可见行命中同一把键时只产一行；走 `content://` / `file://` 系统解码器那条路不经过它
 * （本地源本来就不在这一票的嫌疑里）。
 *
 * 开关就是既有的 [PerfTiming]（`log.tag.ComicViewerPerf` 或设置页「诊断日志」，默认关）；
 * 关着时调用点在 `PerfTiming.log` 的 lambda 里连字符串都不拼。
 */
internal object CoverDiagnostics {

    /** 行前缀（`adb logcat -s ComicViewerPerf | grep coverSource`） */
    const val PREFIX: String = "coverSource"

    /**
     * 一次封面解码的源图事实：
     * - `key=`：解码缓存键（含条目 id 与重取键，`CoverDecode.key` 生成）——条目 id 里能看出是哪个源；
     * - `src=WxH`：源图**真实像素**（`inJustDecodeBounds` 读到的，不是解码后位图）；
     * - `target=Npx`：本次显示盒对应的解码目标宽度（已按 `BUCKET_PX` 向上分桶）；
     * - `crop=`：裁剪目标（`GridCell` = 网格档固定格比例、`OwnAspect` = 列表档按源比例）——
     *   它一并说明这次取图来自哪个显示档；
     * - `plan=region|full`：解码计划走的是可见带还是整图子采样（票 #81/#85 的判定结果）——**交的是计划对象本身**，
     *   不是另传一个布尔，因此行里的 `plan=` 与真正执行的解码结构上不可能不一致；
     * - `upscale=true|false`：**源宽 < 目标 px**（票 #140 的判据，见类 KDoc）。
     *
     * **`target=` 是解码宽度（已按 [CoverDecode.BUCKET_PX] = 32px 向上分桶），不是屏幕上的实际盒宽**：
     * 它恒 ≥ 实际盒宽，所以「源宽 < 目标」是**保守**判据；显示档位（网格 1 列 / 2 列）从这个数就能读出来
     * （例：1080px 宽屏网格 1 列 ≈ 1088px、网格 2 列 ≈ 512px），不必另加一个档位字段。
     * **系列还是书**从 `key=` 里的条目 id 段读（Komga 的条目 id 前缀区分系列 / 书 / 收藏 / 类别）；
     * 系列封面就是**该系列名称序第一本书的第 1 页**（票 #140）——那时行里是「系列」的 key、
     * 拿到的却是书的图，判读以 `src=` 的尺寸为准。
     */
    fun coverSourceLine(
        key: String,
        srcWidth: Int,
        srcHeight: Int,
        targetWidthPx: Int,
        cropTarget: CoverDecode.CropTarget,
        plan: CoverDecode.Plan,
    ): String = PREFIX +
        " key=" + key +
        " src=" + srcWidth + "x" + srcHeight +
        " target=" + targetWidthPx + "px" +
        " crop=" + cropTarget.name +
        " plan=" + (if (plan.region) "region" else "full") +
        " upscale=" + (srcWidth < targetWidthPx)
}
