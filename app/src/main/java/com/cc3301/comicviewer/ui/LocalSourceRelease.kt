package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.Source

/**
 * 页面自己解析出的来源实例的释放（review-18-r3 P1）。
 *
 * 浏览页与柜页各自按路由 connId 解析来源（`ServiceLocator.sourceForConnection` 每次调用都新建
 * backend/transport，SMB 会新建 client/connection/session/share），导航离开即销毁组合；
 * 本函数是这些局部实例唯一的释放路径——否则单 SMB 连接下每进一次浏览页或下一级目录
 * 都留下一个未关闭的 SMB 会话，与票 11「换来源即 close 上一个会话」的纪律相悖。
 *
 * 守卫：被提升为会话来源的那个实例不能关。阅读器路由只认 [ServiceLocator.currentSource]，
 * 关掉它就会打断正在进行的阅读。判定与释放抽成纯函数，由单测锁定。
 */
internal fun releaseLocalSource(local: Source?, session: Source?) {
    if (local != null && local !== session) runCatching { local.close() }
}
