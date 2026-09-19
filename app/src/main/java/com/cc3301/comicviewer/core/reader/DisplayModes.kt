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

/** 音量键 → 阅读动作；未启用或非音量键返回 null（spec 故事 39：默认开、设置可关） */
fun volumeKeyAction(keyCode: Int, enabled: Boolean): VolumeAction? = when {
    !enabled -> null
    keyCode == KEYCODE_VOLUME_DOWN -> VolumeAction.NEXT
    keyCode == KEYCODE_VOLUME_UP -> VolumeAction.PREV
    else -> null
}
