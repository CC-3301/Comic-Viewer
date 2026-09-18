package com.cc3301.comicviewer.core.source.webdav

import com.cc3301.comicviewer.core.source.StoredCredential
import com.cc3301.comicviewer.core.source.remote.endpointParts
import java.net.URI

/**
 * WebDAV 连接配置（票 12）：连接 CRUD 的持久化载体，存进 Room 的 connections.configJson。
 *
 * [baseUrl] 是 DAV 根（例如 `https://nas:5006/dav`），[rootPath] 是根下的起始目录。
 * 与 SMB 一样，密码自票 #27 起经存储层加密（Android Keystore + AES-GCM，[StoredCredential]）后才落库：
 * [toJson] 落密文、[fromJson] 解出明文，界面拿到的仍是明文；旧库明文读路径照旧认，
 * v4 → v5 迁移用 [protectSecrets] 改成密文。
 */
data class WebDavConnectionConfig(
    val baseUrl: String,
    val rootPath: String = "",
    val username: String = "",
    val password: String = "",
    /**
     * 密码密文解不出来（票 #27：换机 / 密钥失效 / 密文损坏）：密码按空处理，
     * 进连接前提示「重新填写密码」，编辑框里能重填；其余字段照旧可用。
     */
    val credentialsNeedReentry: Boolean = false,
) {
    /**
     * 列表展示名：`scheme://主机[:端口]/DAV根/起始目录`。
     * 带 scheme 是为了让同一主机的 http 与 https 两条连接在列表与报错里能区分。
     */
    val displayName: String get() {
        val start = runCatching { WebDavPaths.normalize(rootPath) }.getOrNull()
            ?.takeIf { it != WebDavPaths.ROOT } ?: ""
        return endpointParts(baseUrl).url + start
    }

    /** 落库文本：密码经 [StoredCredential.protect] 加密（票 #27），其余字段原样；加密失败抛出，绝不落明文 */
    fun toJson(): String = org.json.JSONObject()
        .put(KEY_BASE_URL, baseUrl)
        .put(KEY_ROOT_PATH, rootPath)
        .put(KEY_USERNAME, username)
        .put(KEY_PASSWORD, StoredCredential.protect(password))
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
                // 旧库的明文与票 #27 之后的密文都认；密文解不出来（换机/密钥失效）时为 null
                val password = StoredCredential.reveal(obj.optString(KEY_PASSWORD, ""))
                WebDavConnectionConfig(
                    baseUrl = baseUrl,
                    rootPath = obj.optString(KEY_ROOT_PATH, ""),
                    username = obj.optString(KEY_USERNAME, ""),
                    password = password.orEmpty(),
                    credentialsNeedReentry = password == null,
                )
            }
        } catch (t: Throwable) {
            null
        }

        /**
         * 存量迁移（票 #27，v4 → v5 用）：configJson 里的密码改写成密文。
         * 幂等（已是密文/空值不动）；解不出 JSON 或加密不可用时返回 null，由调用方保留原行。
         */
        fun protectSecrets(json: String): String? = StoredCredential.protectSecrets(json, listOf(KEY_PASSWORD))

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
