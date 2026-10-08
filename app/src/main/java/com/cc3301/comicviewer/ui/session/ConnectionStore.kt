package com.cc3301.comicviewer.ui.session

import com.cc3301.comicviewer.core.data.ConnectionDao
import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.ui.SavedConnection
import com.cc3301.comicviewer.ui.ServiceLocator

/**
 * 连接的写面：添加 / 编辑 / 删除各一句调用，三件事（写库 / 释放该连接的会话级来源 /
 * 作废它名下的落盘列表快照）的顺序是模块内部的不变量——编辑**先**写库后清理，删除**先**清理后删行。
 * 界面只喊一声，不再各自拼顺序。
 *
 * 读面不在这里：连接列表、书柜与启动还原仍直读 [ConnectionDao]（`observeAll` / `byId`）。
 *
 * 依赖由构造参数注入（[dao] 连接表、[session] 会话状态实例）：生产那两份由窄根装配
 * （`ServiceLocator.newConnectionStore`），单测交自己那份。
 */
internal class ConnectionStore(
    private val dao: ConnectionDao,
    private val session: SessionState,
) {

    /** 添加：只写库——新连接还没有会话级来源与快照，没有要清理的东西 */
    suspend fun add(entity: ConnectionEntity) {
        dao.insert(entity)
    }

    /**
     * 编辑：先按 [saved] 写回，再判该不该清理——判据是 [existing]（旧的那一行，调用方手上才有）
     * 与 [saved] 的 configJson 文本。
     *
     * 文本没变就不清：会话槽的命中判据（连接 id + configJson）本就命中，重建会话与作废快照都是白搭。
     * 凭据每次加密都用新随机 IV，因此**即使什么都没改**，configJson 文本也会变——保存连接会重建一次会话，
     * 保存是低频动作，接受该代价；不做「解密后比语义」的优化，因为会话槽仍会因文本不同而重建，省不掉。
     */
    suspend fun update(existing: ConnectionEntity, saved: SavedConnection) {
        dao.update(existing.copy(displayName = saved.displayName, configJson = saved.configJson))
        if (saved.configJson != existing.configJson) {
            clearConnectionState(existing.id)
        }
    }

    /** 删除：先清该连接的状态，再删行——行没了就认不出该清谁 */
    suspend fun delete(connId: Long) {
        clearConnectionState(connId)
        dao.deleteById(connId)
    }

    /**
     * 释放该连接的会话级来源（内存列表快照随之清空）+ 作废它名下的**落盘**列表快照。
     * 两个关注点永远一起发生：只释放会话会留下落盘快照（编辑/删除后重进照样命中旧数据）；
     * 只清落盘会留着未关闭的 SMB/HTTP 会话。App 退出走的不是这条（退出不清落盘，见 [SessionState.end]）。
     */
    private fun clearConnectionState(connId: Long) {
        ServiceLocator.purgeListingSnapshots(connId)
        session.closeBrowsingSource(connId)
    }
}
