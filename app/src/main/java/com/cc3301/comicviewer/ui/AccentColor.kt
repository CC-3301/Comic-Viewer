package com.cc3301.comicviewer.ui

import androidx.compose.ui.graphics.Color

/**
 * 阅读界面的**强调橙**（票 #105；口径唯一一处的来源）。
 *
 * 同一值曾散在四个地方：阅读菜单的上/下一本按钮文字、缩略图高亮描边与高亮页数
 * （原 `ReaderMenu.ACCENT_ORANGE`）、阅读页的跳书条动作格（原 `ReaderScreen.CROSS_BOOK_ACTION_COLOR`）
 * 以及阅读页两处内联字面量。现在全部读这一个常量：改色只需改这里一处，
 * 上/下一本「与跳书条同一个橙」不再靠复制值维持。
 */
internal val ACCENT_ORANGE = Color(0xFFFF9800)
