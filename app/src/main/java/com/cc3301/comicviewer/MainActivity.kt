package com.cc3301.comicviewer

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.cc3301.comicviewer.core.input.HistoryAction
import com.cc3301.comicviewer.core.input.MOUSE_BUTTON_SECONDARY
import com.cc3301.comicviewer.core.input.WheelAction
import com.cc3301.comicviewer.core.input.sideButtonAction
import com.cc3301.comicviewer.core.input.wheelAction
import com.cc3301.comicviewer.core.reader.OrientationMode
import com.cc3301.comicviewer.core.reader.isDarkTheme
import com.cc3301.comicviewer.core.reader.volumeKeyAction
import com.cc3301.comicviewer.ui.AppNav
import com.cc3301.comicviewer.ui.AppSettings
import com.cc3301.comicviewer.ui.ServiceLocator

class MainActivity : ComponentActivity() {

    /** 本次按下已消费的音量键 keyCode（DOWN 消费后 UP 也要吞掉，避免系统音量条二次出现） */
    private var consumedVolumeKeyCode = Int.MIN_VALUE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 冷启动/Activity 重建（旋转、系统深色切换）时窗口底色先按落盘主题设置，
        // 否则 XML 里的浅色平台主题会先露一帧（review P2）。
        window.setBackgroundDrawable(
            ColorDrawable(if (storedDarkTheme()) Color.rgb(0x1C, 0x1B, 0x1F) else Color.rgb(0xFF, 0xFB, 0xFE)),
        )
        setContent {
            // 读取设置版本号以建立重组依赖：设置页改动后主题/旋转立即生效（票 20）
            val revision = AppSettings.revision
            val mode = remember(revision) { AppSettings.themeMode }
            val dark = isDarkTheme(mode, isSystemInDarkTheme())
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                AppNav()
            }
            // 旋转（spec 故事 50）：默认跟随系统；设置变化后立即应用
            LaunchedEffect(revision) {
                requestedOrientation = AppSettings.orientation.toActivityOrientation()
            }
        }
    }

    /**
     * App 级释放路径（票 #30 P1）：Activity 真正退出（不是旋转/深色切换的重建）时
     * 关掉跨页面存活的会话级来源，不留永不关闭的 SMB 连接。
     */
    override fun onDestroy() {
        if (isFinishing) ServiceLocator.closeSession()
        super.onDestroy()
    }

    /** 落盘主题 + 系统深色 → 是否深色（不含 Compose 环境，供窗口底色使用） */
    private fun storedDarkTheme(): Boolean {
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return isDarkTheme(AppSettings.themeMode, systemDark)
    }

    /**
     * 鼠标支持（票 17，spec 故事 22/35/36/37）：滚轮、右键与侧键。
     * 与音量键同一手法——前台界面通过 ServiceLocator 注册处理器，这里只做分发，不耦合导航与阅读器状态。
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // 只处理鼠标：触摸板/触控笔不属本票范围，交回系统
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) return super.dispatchGenericMotionEvent(event)
        when (event.actionMasked) {
            // 滚轮（spec 故事 22 列表 / 35 阅读器）：单页模式一格=翻一页，其余界面交回容器自身滚动
            MotionEvent.ACTION_SCROLL -> {
                val handler = ServiceLocator.wheelHandler ?: return super.dispatchGenericMotionEvent(event)
                val forward = wheelAction(handler.surface, event.getAxisValue(MotionEvent.AXIS_VSCROLL))
                when (forward) {
                    WheelAction.NEXT_PAGE ->
                        if (handler.onWheelPageTurn(true)) return true
                    WheelAction.PREV_PAGE ->
                        if (handler.onWheelPageTurn(false)) return true
                    WheelAction.SCROLL_SELF,
                    WheelAction.IGNORE,
                    -> Unit
                }
            }
            // 侧键与右键（spec 故事 36/37）
            MotionEvent.ACTION_BUTTON_PRESS -> {
                when (sideButtonAction(event.actionButton)) {
                    // 后退侧键 = 系统返回：与返回手势、抽屉「后退」走同一条链路（含阅读器内退出）
                    HistoryAction.BACK -> {
                        onBackPressedDispatcher.onBackPressed()
                        return true
                    }
                    HistoryAction.FORWARD -> {
                        if (ServiceLocator.forwardHistoryHandler?.invoke() == true) return true
                    }
                    null -> Unit
                }
                if (event.actionButton == MOUSE_BUTTON_SECONDARY) {
                    ServiceLocator.mouseSecondaryTapHandler?.let { onTap ->
                        onTap(event.x)
                        return true
                    }
                }
            }
            else -> Unit
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /**
     * 音量键翻页（票 20，spec 故事 39）：仅当设置开启且阅读页已注册处理器时拦截，
     * 阅读页在书首/书末不消费时（handler 返回 false）依然交回系统，否则音量键会完全失效。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handler = ServiceLocator.volumeKeyHandler ?: return super.dispatchKeyEvent(event)
        val action = volumeKeyAction(event.keyCode, enabled = AppSettings.volumeKeysEnabled)
            ?: return super.dispatchKeyEvent(event)

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (consumedVolumeKeyCode != event.keyCode) {
                    // 新的按下：长按重复事件与阅读页未消费的事件都不拦截
                    if (event.repeatCount > 0 || !handler(action)) {
                        return super.dispatchKeyEvent(event)
                    }
                    consumedVolumeKeyCode = event.keyCode
                }
                // 已进入消费状态的重复事件：吞掉但不重复翻页（一次按压=一次翻页）
            }
            // 只有配对的 UP 才吞掉：DOWN 未消费的 UP 交回系统
            KeyEvent.ACTION_UP -> {
                if (consumedVolumeKeyCode != event.keyCode) return super.dispatchKeyEvent(event)
                consumedVolumeKeyCode = Int.MIN_VALUE
            }
            // 其余 action（含 ACTION_MULTIPLE）一律交回系统
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }
}

/** 旋转设置 → Activity 朝向常量（spec 故事 50） */
private fun OrientationMode.toActivityOrientation(): Int = when (this) {
    OrientationMode.FOLLOW_SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
}
