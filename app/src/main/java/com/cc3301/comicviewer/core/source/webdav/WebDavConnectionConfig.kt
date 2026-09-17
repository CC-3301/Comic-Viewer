package com.cc3301.comicviewer.core.source.webdav

import java.net.URI

/**
 * WebDAV 连接配置（票 12）：连接 CRUD 的持久化载体，存进 Room 的 connections.configJson。
 *
 * [baseUrl] 是 DAV 根（例如 `https://nas:5006/dav`），[rootPath] 是根下的起始目录。
 * 与 SMB 一样，密码目前以明文存 configJson（审查已记录，后续与 SMB 一起改为加密存储）。
 */
data class WebDavConnectionConfig(
    val baseUrl: String,
    val rootPath: String = "",
    val username: String = "",
    val password: String = "",
) {
    /** 列表展示名：主机 + DAV 根路径（+ 起始目录），便于区分同一服务器的多个入口 */
    val displayName: String get() {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
        val scheme = uri?.scheme ?: "http"
        val host = uri?.host ?: baseUrl
        val port = uri?.port?.takeIf { it > 0 }?.let { ":" + it } ?: ""
        val path = (uri?.path ?: "").trimEnd('/')
        val start = runCatching { WebDavPaths.normalize(rootPath) }.getOrNull()
            ?.takeIf { it != WebDavPaths.ROOT } ?: ""
        // 带 scheme：同一主机的 http 与 https 两个连接在列表与报错里能区分
        return scheme + "://" + host + port + path + start
    }

    fun toJson(): String = org.json.JSONObject()
        .put(KEY_BASE_URL, baseUrl)
        .put(KEY_ROOT_PATH, rootPath)
        .put(KEY_USERNAME, username)
        .put(KEY_PASSWORD, password)
        .toString()

    companion object {
        private const val KEY_BASE_URL = "baseUrl"
        private const val KEY_ROOT_PATH = "rootPath"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"

        /** 解析失败或必填字段缺失返回 null（配置损坏时由 UI 提示，不崩溃） */
        fun fromJson(json: String): WebDavConnectionConfig? = try {
            val obj = org.json.JSONObject(json)
            val baseUrl = obj.optString(KEY_BASE_URL, "")
            if (baseUrl.isEmpty()) {
                null
            } else {
                WebDavConnectionConfig(
                    baseUrl = baseUrl,
                    rootPath = obj.optString(KEY_ROOT_PATH, ""),
                    username = obj.optString(KEY_USERNAME, ""),
                    password = obj.optString(KEY_PASSWORD, ""),
                )
            }
        } catch (t: Throwable) {
            null
        }

        /** 校验：合法返回 null，否则返回中文错误提示 */
        fun validate(config: WebDavConnectionConfig): String? = when {
            config.baseUrl.isBlank() -> "请填写服务器地址"
            !config.baseUrl.trim().startsWith("http://") && !config.baseUrl.trim().startsWith("https://") ->
                "地址要以 http:// 或 https:// 开头"
            runCatching { URI(config.baseUrl.trim()) }.getOrNull()?.host.isNullOrEmpty() -> "地址不合法，请检查主机名"
            runCatching { WebDavPaths.normalize(config.rootPath) }.isFailure -> "起始目录不能包含 .."
            else -> null
        }
    }
}
