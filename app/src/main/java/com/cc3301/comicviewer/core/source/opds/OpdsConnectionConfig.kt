package com.cc3301.comicviewer.core.source.opds

import com.cc3301.comicviewer.core.source.remote.endpointParts
import org.json.JSONObject
import java.net.URI

/**
 * OPDS 连接配置（票 15）：feed URL + 可选凭据，存进 Room 的 connections.configJson。
 *
 * 凭据同样是明文（与 SMB/WebDAV/Komga 一致的已知取舍，后续统一加密）。
 * 缓存上限不在连接上：它是全局设置（`AppSettings.opdsCacheLimitMb`），所有 OPDS 连接共用一份缓存。
 */
data class OpdsConnectionConfig(
    val feedUrl: String,
    val username: String = "",
    val password: String = "",
) {
    /** 列表展示名：`scheme://主机[:端口]/路径` */
    val displayName: String get() = endpointParts(feedUrl).url

    fun toJson(): String = JSONObject()
        .put(KEY_FEED_URL, feedUrl)
        .put(KEY_USERNAME, username)
        .put(KEY_PASSWORD, password)
        .toString()

    companion object {
        private const val KEY_FEED_URL = "feedUrl"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"

        /** 解析失败或必填字段缺失返回 null（配置损坏时由 UI 提示，不崩溃） */
        fun fromJson(json: String): OpdsConnectionConfig? = try {
            val obj = JSONObject(json)
            val feedUrl = obj.optString(KEY_FEED_URL, "")
            if (feedUrl.isEmpty()) {
                null
            } else {
                OpdsConnectionConfig(
                    feedUrl = feedUrl,
                    username = obj.optString(KEY_USERNAME, ""),
                    password = obj.optString(KEY_PASSWORD, ""),
                )
            }
        } catch (t: Throwable) {
            null
        }

        /** 校验：合法返回 null，否则返回中文错误提示 */
        fun validate(config: OpdsConnectionConfig): String? = when {
            config.feedUrl.isBlank() -> "请填写 feed 地址"
            !config.feedUrl.trim().startsWith("http://") && !config.feedUrl.trim().startsWith("https://") ->
                "地址要以 http:// 或 https:// 开头"
            runCatching { URI(config.feedUrl.trim()) }.getOrNull()?.host.isNullOrEmpty() -> "地址不合法，请检查主机名"
            else -> null
        }
    }
}
