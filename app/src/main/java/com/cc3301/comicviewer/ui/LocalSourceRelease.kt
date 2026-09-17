package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.Source

/**
 * 会话来源的释放守卫（review-18-r3 P1；票 #30 P1 起由 [ServiceLocator.browsingSourceFor] 调用）。
 *
 * 来源实例现在按连接复用（单槽会话级缓存）：换到别的连接时要释放上一个，否则单 SMB 连接下
 * 每切一个连接都留下一个未关闭的 SMB 会话，与票 11「换来源即 close 上一个会话」的纪律相悖。
 *
 * 守卫：被提升为会话来源的那个实例不能关。阅读器路由只认 [ServiceLocator.currentSource]，
 * 关掉它就会打断正在进行的阅读（抽屉可跨页面导航到书柜/浏览页，而阅读器还在回退栈里）。
 * 它会由 [ServiceLocator.currentSource] 的 setter 在真正被替换时释放。
 *
 * 判定与释放抽成纯函数，由单测锁定。
 */
internal fun releaseLocalSource(local: Source?, session: Source?) {
    if (local != null && local !== session) runCatching { local.close() }
}
