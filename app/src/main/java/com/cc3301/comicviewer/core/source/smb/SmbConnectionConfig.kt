package com.cc3301.comicviewer.core.source.smb

import org.json.JSONObject

/**
 * SMB 连接配置（票 11）：连接 CRUD 的持久化载体，存进 Room 的 connections.configJson。
 *
 * 已知偏差（审查 P2 记录）：密码以明文存在 configJson 里（Room 未加密），
 * 编辑表单回显明文。与 SAF 一样属于本地单用户场景下的可接受取舍，
 * 但 WebDAV/Komga 票会复用同一列，若后续要上系统密钥库（Keystore/EncryptedSharedPreferences），
 * 应在此处一次性改掉（只影响 toJson/fromJson 与 UI 表单）。
 */
data class SmbConnectionConfig(
    val host: String,
    val share: String,
    val rootPath: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val port: Int = DEFAULT_PORT,
) {
    /** 列表展示名：共享 @ 主机 */
    val displayName: String get() = share + " @ " + host

    fun toJson(): String = JSONObject()
        .put(KEY_HOST, host)
        .put(KEY_SHARE, share)
        .put(KEY_ROOT_PATH, rootPath)
        .put(KEY_USERNAME, username)
        .put(KEY_PASSWORD, password)
        .put(KEY_DOMAIN, domain)
        .put(KEY_PORT, port)
        .toString()

    companion object {
        const val DEFAULT_PORT = 445

        private const val KEY_HOST = "host"
        private const val KEY_SHARE = "share"
        private const val KEY_ROOT_PATH = "rootPath"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_DOMAIN = "domain"
        private const val KEY_PORT = "port"

        /** 解析失败或必填字段缺失返回 null（配置损坏时由 UI 提示，不崩溃） */
        fun fromJson(json: String): SmbConnectionConfig? = try {
            val obj = JSONObject(json)
            val host = obj.optString(KEY_HOST, "")
            val share = obj.optString(KEY_SHARE, "")
            if (host.isEmpty() || share.isEmpty()) {
                null
            } else {
                SmbConnectionConfig(
                    host = host,
                    share = share,
                    rootPath = obj.optString(KEY_ROOT_PATH, ""),
                    username = obj.optString(KEY_USERNAME, ""),
                    password = obj.optString(KEY_PASSWORD, ""),
                    domain = obj.optString(KEY_DOMAIN, ""),
                    port = obj.optInt(KEY_PORT, DEFAULT_PORT),
                )
            }
        } catch (t: Throwable) {
            null
        }

        /** 校验：合法返回 null，否则返回中文错误提示 */
        fun validate(config: SmbConnectionConfig): String? = when {
            config.host.isBlank() -> "请填写服务器地址"
            config.share.isBlank() -> "请填写共享名"
            config.port !in 1..65535 -> "端口必须在 1–65535 之间"
            else -> null
        }
    }
}
