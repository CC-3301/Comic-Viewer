package com.cc3301.comicviewer.core.view

import com.cc3301.comicviewer.core.concurrent.RetryPolicy

/**
 * 可见行封面加载的**有界重试策略**（纯参数，由 `CoverRetryTest` 锁定；驱动与结果三态在 `core/concurrent`）。
 * 同一套口径也用在**阅读菜单预览格的缩略图**上（`ui/PagePreviewLoad`）：策略只有这一处声明。
 *
 * 为什么要有：来源对「真的没有封面」与「这次取不到」都给 null（`Source.coverBytes` 的契约，
 * 两个实现都这么写），界面因此无法把瞬断与永久缺失分开。不重试的后果是**这一格永远没有图**：
 * 一次性失败被当成终态，格子不会再自己试；而快速拖动时一次失败的概率并不低。
 *
 * 口径：**首发 1 次 + 重试 3 次**（最多 4 次尝试），退避 500ms → 1.5s → 4s，每个值 ±25% 抖动。
 * 抖动是为了不让一屏里同时失败的几十格在同一刻一起重发（重试风暴）——因此它必须真的随机。
 * 策略无状态，调用方（`ui/CoverThumb` 与 `ui/PagePreviewLoad`）只问「下一次等多久」。
 */
internal val coverRetryPolicy: RetryPolicy = RetryPolicy(
    maxAttempts = 4,
    delaysMs = longArrayOf(500L, 1_500L, 4_000L),
    jitterFraction = 0.25,
)
