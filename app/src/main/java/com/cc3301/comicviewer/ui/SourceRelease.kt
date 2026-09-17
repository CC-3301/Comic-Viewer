package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.Source

/**
 * 被换出浏览槽的实例的释放守卫（review-18-r3 P1；票 #30 P1 起由 [ServiceLocator.browsingSourceFor]
 * 的单槽会话级缓存调用）。
 *
 * 来源实例按连接复用（单槽）：换到别的连接、连接被删除/编辑或 App 退出时，要把换出去的那个实例释放掉，
 * 否则单 SMB 连接下每切一次连接都留下一个未关闭的 SMB 会话，与票 11「换来源即 close 上一个会话」的纪律相悖。
 *
 * 守卫：若换出去的实例正是阅读器在用的会话来源（[ServiceLocator.currentSource]），不在这里关——
 * 阅读器路由只认它，半途关掉会让回退栈里那本书报错；它的关闭责任归会话来源那一侧
 * （[ServiceLocator.currentSource] 的 setter 在真正替换时释放，[ServiceLocator.closeSession] 在 App 退出时释放）。
 *
 * 判定与释放抽成纯函数，由 [SourceReleaseTest] 锁定。
 */
internal fun releaseReplacedSource(replaced: Source?, session: Source?) {
    if (replaced != null && replaced !== session) runCatching { replaced.close() }
}
