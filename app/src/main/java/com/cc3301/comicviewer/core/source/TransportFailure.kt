package com.cc3301.comicviewer.core.source

/**
 * 传输层故障标记（票 11）：网络来源（SMB/WebDAV/…）的连接、认证、超时类失败实现本接口。
 *
 * 用途：元数据解析（如 CBZ 包内条目列表）遇到本类异常必须冒泡，不能被当成「这不是压缩包」吞掉，
 * 否则断链时会静默显示 0 页，打开时再报「不是一本书」，把网络问题伪装成文件问题。
 */
interface TransportFailure
