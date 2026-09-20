package com.cc3301.comicviewer.core.reader

/**
 * 页面旋转（spec 故事 50）：竖屏 / 横屏 / 跟随系统（默认）。
 */
enum class OrientationMode(val key: String) {
    FOLLOW_SYSTEM("system"),
    PORTRAIT("portrait"),
    LANDSCAPE("landscape"),
    ;

    companion object {
        /** 持久化键解析；未知/缺失回退跟随系统 */
        fun fromKey(key: String?): OrientationMode = entries.firstOrNull { it.key == key } ?: FOLLOW_SYSTEM
    }
}

/**
 * 主题（spec 故事 51）：跟随系统（默认）/ 深色 / 浅色。
 */
enum class ThemeMode(val key: String) {
    FOLLOW_SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        /** 持久化键解析；未知/缺失回退跟随系统 */
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: FOLLOW_SYSTEM
    }
}

/**
 * 主题模式 + 系统深色 → 是否使用深色（spec 故事 51）：手动覆盖优先，仅跟随系统时看系统。
 * 纯函数：Compose 侧与窗口底色（MainActivity）共用同一份判定。
 */
fun isDarkTheme(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.FOLLOW_SYSTEM -> systemDark
}

/** 音量键阅读动作（spec 故事 39）：音量下=向后翻（下一页）、音量上=向前翻（上一页） */
enum class VolumeAction { PREV, NEXT }

/** Android KeyEvent 音量键 keyCode：自行定义常量以便纯 JVM 单测（值与平台一致） */
const val KEYCODE_VOLUME_UP = 24
const val KEYCODE_VOLUME_DOWN = 25

/** Android KeyEvent.action 常量（自行定义以便纯 JVM 单测，值与平台一致）：按下 / 松开 */
const val KEY_ACTION_DOWN = 0
const val KEY_ACTION_UP = 1

/** 音量键 → 阅读动作；未启用或非音量键返回 null（spec 故事 39：默认开、设置可关） */
fun volumeKeyAction(keyCode: Int, enabled: Boolean): VolumeAction? = when {
    !enabled -> null
    keyCode == KEYCODE_VOLUME_DOWN -> VolumeAction.NEXT
    keyCode == KEYCODE_VOLUME_UP -> VolumeAction.PREV
    else -> null
}

/** 一发音量键事件的处置（票 20；票 #89 需求 3） */
enum class VolumeKeyEvent {
    /** 跳一页（或到书首/书末时由阅读页就地换成跨书确认——见 `ReaderScreen`） */
    ADVANCE,

    /** 只消费不跳：配对的松开 */
    SWALLOW,

    /** 不处理，交回系统 */
    IGNORE,
}

/**
 * 音量键一发 KeyEvent → 处置（票 20；票 #89 需求 3：**长按 = 连续跳页**）。
 *
 * 判据只有 `eventAction`：安卓长按会**连发同一个 DOWN**（`repeatCount` 递增后继续送来），也就是
 * 单击（`repeatCount == 0`）与连发（`> 0`）走同一条路——每一发都跳一页。旧实现在调用方按
 * `repeatCount > 0` 把连发吞掉，本判定里没有这个分支。
 *
 * `repeatCount` 仍是入参（而不是在调用方丢掉）：它不影响结论，但把发数带进来才能让「连发也跳页」
 * 这条口径被单测逐格钉住（见 `DisplayModesTest`），也使调用点不必自己判发数。
 */
fun volumeKeyEvent(eventAction: Int, repeatCount: Int): VolumeKeyEvent = when (eventAction) {
    KEY_ACTION_DOWN -> VolumeKeyEvent.ADVANCE
    KEY_ACTION_UP -> VolumeKeyEvent.SWALLOW
    else -> VolumeKeyEvent.IGNORE
}
