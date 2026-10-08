package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.ui.session.SessionState
import kotlinx.coroutines.CoroutineScope

/**
 * 用例自己那份会话状态。生产那一份由组合根（`MainActivity`）持有并沿组合树提供，
 * 测试因此不再整体替换窄根上的实例：要来源构造器或落盘钩子的用例自己传。
 *
 * 协程域默认 [ServiceLocator.appScope]（生产同一份）：释放与落盘的写入挂在它上面，
 * 用例「轮询等关闭计数」的口径因此与生产一致。
 */
internal fun testSessionState(
    sourceFactory: suspend (ConnectionEntity) -> Source = {
        throw UnsupportedOperationException("本用例不解析来源")
    },
    recordBrowsingPath: (List<BrowseLocation>) -> Unit = {},
    scope: CoroutineScope = ServiceLocator.appScope,
): SessionState = SessionState(
    scope = scope,
    sourceFactory = sourceFactory,
    recordBrowsingPath = recordBrowsingPath,
)
