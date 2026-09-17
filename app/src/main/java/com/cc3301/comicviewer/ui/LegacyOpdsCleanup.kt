package com.cc3301.comicviewer.ui

import android.content.Context
import java.io.File

/**
 * OPDS 下线后的存量清理（票 33）：OPDS 来源与它的下载缓存整体删除后，
 * 从旧版本升级上来的设备仍留着 `cacheDir/opds` 缓存目录与设置里的上限键，
 * 冷启动时顺手清掉，不把已下线的数据继续留在设备上。
 *
 * 纪律（与既有的异步释放一致）：
 * - 尽力而为：任一步失败都不抛给调用方（目录被占、权限异常都不该影响冷启动）；
 * - 幂等：目录已删、键已不存在时都是无操作，重复调用无副作用。
 * - 调用方负责放到 IO 线程（APP 级协程域），启动不被这两步 IO 阻塞。
 */
internal fun purgeLegacyOpdsData(context: Context) {
    runCatching { File(context.cacheDir, LEGACY_CACHE_DIR).deleteRecursively() }
    runCatching {
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            // commit 而不是 apply：清理结果要立即落盘，进程随时可能被系统杀掉
            .remove(LEGACY_CACHE_LIMIT_KEY)
            .commit()
    }
}

/** 已删除的 OPDS 缓存目录名（曾是 `ServiceLocator.opdsCache` 的 dir） */
private const val LEGACY_CACHE_DIR = "opds"

/** 已删除的 OPDS 缓存上限键（票 15 的 `AppSettings.opdsCacheLimitMb`） */
private const val LEGACY_CACHE_LIMIT_KEY = "opds_cache_limit_mb"
