package com.cc3301.comicviewer.core.source.remote

import java.net.URI

/**
 * 网络来源连接地址的展示/标识（票 12/13）：
 * WebDAV 与 Komga 都需要「scheme://主机[:端口]/路径」两种用途——
 * 列表展示名与节点 id 前缀，且都必须区分 http / https。放在共享层避免三份拷贝漂移。
 */
data class EndpointParts(
    val scheme: String,
    val host: String,
    val portSuffix: String,
    val pathSuffix: String,
) {
    /** `scheme://主机[:端口][/路径]`（去掉尾斜杠） */
    val url: String get() = scheme + "://" + host + portSuffix + pathSuffix

    /** 节点 id 前缀：命名空间 + url（例如 `webdav-https://nas:5006/dav`） */
    fun idPrefix(namespace: String): String = namespace + "-" + url
}

/** 解析连接地址；无法解析时回退成 http + 原文（不抛异常：展示名/前缀不能因为脏配置崩） */
fun endpointParts(rawUrl: String): EndpointParts {
    val trimmed = rawUrl.trim()
    val uri = runCatching { URI(trimmed) }.getOrNull()
    return EndpointParts(
        scheme = uri?.scheme ?: "http",
        host = uri?.host ?: trimmed,
        portSuffix = uri?.port?.takeIf { it > 0 }?.let { ":" + it } ?: "",
        pathSuffix = (uri?.path ?: "").trimEnd('/'),
    )
}
