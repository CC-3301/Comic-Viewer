package com.cc3301.comicviewer

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** 仪器测试冒烟：MainActivity 可启动不崩（真机/模拟器上经 connectedDebugAndroidTest 执行） */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @Test
    fun launch_showsHomeWithoutCrash() {
        // 启动成功、进入 resumed 状态即通过；后续票在此扩展触摸区域等仪器用例
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { /* 启动不崩 */ }
        }
    }
}
