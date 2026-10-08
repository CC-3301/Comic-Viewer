package com.cc3301.comicviewer.ui.session

import androidx.lifecycle.ViewModel
import com.cc3301.comicviewer.ui.BrowseScrollPosition
import com.cc3301.comicviewer.ui.ServiceLocator
import com.cc3301.comicviewer.ui.newBrowseScrollPosition

/**
 * 会话状态、位置模块与开书请求模块的宿主：配置变更（旋转）**不**结束会话，这三个模块的实例因此挂在这里
 * 跨 Activity 重建保留；Activity 真正 finish 时随 ViewModel 一起销毁——与「只有 finish 才算会话结束」同一口径。
 *
 * 由组合根 `MainActivity` 持有并沿组合树提供
 * （`LocalSessionState` / `LocalBrowseScrollPosition` / `LocalOpenBookRequests`）。
 */
internal class SessionStateHolder : ViewModel() {
    val session: SessionState = ServiceLocator.newSessionState()

    /** 位置模块（`ui/BrowseScrollPosition.kt`）：浏览链依赖取自 [session]，与它同寿命 */
    val scrollPosition: BrowseScrollPosition = newBrowseScrollPosition(session)

    /** 开书请求模块（`ui/session/OpenBookRequests.kt`）：两个计数器按会话重置，无构造依赖 */
    val openBookRequests: OpenBookRequests = OpenBookRequests()
}
