package com.cc3301.comicviewer.core.source

import java.security.MessageDigest

/**
 * 文本的 SHA-256 十六进制串（小写），只取前 [hexChars] 个字符。
 *
 * 两处落盘键共用这一份（票 #74 收口）：列表快照的文件名哈希（[ListingSnapshotStore]，取 32 字符）
 * 与页字节磁盘缓存的键（`ui/PageDecoder` 的 `PageDiskCache`，同样取 32 字符）各写过一份逐字相同的实现。
 * 编码固定 UTF-8（id 里可能带中文；两处口径必须一致，否则同一 id 会算出两个键）。
 */
fun sha256Hex(text: String, hexChars: Int = 64): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
        .take(hexChars)
