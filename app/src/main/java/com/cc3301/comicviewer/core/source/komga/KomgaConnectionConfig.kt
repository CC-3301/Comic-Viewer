package com.cc3301.comicviewer.core.source.komga

import com.cc3301.comicviewer.core.source.remote.endpointParts
import org.json.JSONObject
import java.net.URI

/**
 * Komga 连接配置（票 13）：连接 CRUD 的持久化载体，存进 Room 的 connections.configJson。
 *
 * 认证二选一：`apiKey`（请求头 `X-API-Key`，推荐）或 邮箱 + 密码（Basic 认证）。
 * 与 SMB/WebDAV 一样，凭据目前以明文存 configJson（审查已记录，后续统一改为加密存储）。
 */
data class KomgaConnectionConfig(
    val baseUrl: String,
    val username: String = "",
    val password: String = "",
    val apiKey: String = "",
) {
    /** 列表展示名：`scheme://主机[:端口][/路径]` */
    val displayName: String get() = endpointParts(baseUrl).url

    /** 使用 API Key 还是 Basic 认证 */
    val usesApiKey: Boolean get() = apiKey.isNotBlank()

    fun toJson(): String = JSONObject()
        .put(KEY_BASE_URL, baseUrl)
        .put(KEY_USERNAME, username)
        .put(KEY_PASSWORD, password)
        .put(KEY_API_KEY, apiKey)
        .toString()

    companion object {
        private const val KEY_BASE_URL = "baseUrl"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_API_KEY = "apiKey"

        /** 解析失败或必填字段缺失返回 null（配置损坏时由 UI 提示，不崩溃） */
        fun fromJson(json: String): KomgaConnectionConfig? = try {
            val obj = JSONObject(json)
            val baseUrl = obj.optString(KEY_BASE_URL, "")
            if (baseUrl.isEmpty()) {
                null
            } else {
                KomgaConnectionConfig(
                    baseUrl = baseUrl,
                    username = obj.optString(KEY_USERNAME, ""),
                    password = obj.optString(KEY_PASSWORD, ""),
                    apiKey = obj.optString(KEY_API_KEY, ""),
                )
            }
        } catch (t: Throwable) {
            null
        }

        /** 校验：合法返回 null，否则返回中文错误提示 */
        fun validate(config: KomgaConnectionConfig): String? = when {
            config.baseUrl.isBlank() -> "请填写服务器地址"
            !config.baseUrl.trim().startsWith("http://") && !config.baseUrl.trim().startsWith("https://") ->
                "地址要以 http:// 或 https:// 开头"
            runCatching { URI(config.baseUrl.trim()) }.getOrNull()?.host.isNullOrEmpty() -> "地址不合法，请检查主机名"
            // 凭据二选一：API Key 或 邮箱+密码；两者都没有时 Komga 会返回 401
            !config.usesApiKey && (config.username.isBlank() || config.password.isBlank()) ->
                "请填写 API Key，或同时填写邮箱与密码"
            else -> null
        }
    }
}
