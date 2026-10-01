package com.cc3301.comicviewer.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.sort.SortDirection
import com.cc3301.comicviewer.core.sort.SortSetting
import com.cc3301.comicviewer.core.source.SortMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 全局排序设置的落盘（spec「排序设置」）：全 app 一份，跨目录层级、跨连接、重启都保持。
 * 本测试把落盘与读回分开调用（Store 不缓存），等价于进程重启后的读取。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SortSettingStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ServiceLocator.init(context)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `无落盘记录时是默认设置 名称正向`() {
        assertEquals(SortSetting(), SortSettingStore.setting)
    }

    @Test
    fun `排序方式与三个类别各自的方向跨重启可读`() {
        SortSettingStore.setting = SortSetting()
            .select(SortMode.NAME, SortDirection.REVERSE)           // 名称 → 降序
            .select(SortMode.MODIFIED_TIME, SortDirection.REVERSE)  // 修改时间 → 旧→新

        // 重新从落盘读（Store 不缓存）＝ 重启后的读取
        val restored = SortSettingStore.setting
        assertEquals(SortMode.MODIFIED_TIME, restored.mode)
        assertEquals(SortDirection.REVERSE, restored.directionOf(SortMode.NAME))
        assertEquals(SortDirection.REVERSE, restored.directionOf(SortMode.MODIFIED_TIME))
        assertEquals(SortDirection.FORWARD, restored.directionOf(SortMode.RELEASE_TIME))
    }

    @Test
    fun `只改当前类别方向不会丢掉排序方式与别的类别方向`() {
        SortSettingStore.setting = SortSetting()
            .select(SortMode.NAME, SortDirection.REVERSE)           // 名称 → 降序
            .select(SortMode.RELEASE_TIME, SortDirection.FORWARD)   // 切到发布时间：新→旧

        // 在发布时间这一档上换成旧→新
        SortSettingStore.setting = SortSettingStore.setting.select(SortMode.RELEASE_TIME, SortDirection.REVERSE)

        val restored = SortSettingStore.setting
        assertEquals(SortMode.RELEASE_TIME, restored.mode)
        assertEquals(SortDirection.REVERSE, restored.directionOf(SortMode.RELEASE_TIME))
        assertEquals(SortDirection.REVERSE, restored.directionOf(SortMode.NAME))
    }

    @Test
    fun `落盘键损坏时回退默认设置`() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("sort_mode", "bogus")
            .putString("sort_direction_name", "bogus")
            .commit()

        assertEquals(SortSetting(), SortSettingStore.setting)
    }

    @Test
    fun `写入递增版本号 供 Compose 建立重组依赖`() {
        val before = SortSettingStore.revision

        SortSettingStore.setting = SortSetting().select(SortMode.NAME, SortDirection.REVERSE)

        assertEquals(before + 1, SortSettingStore.revision)
        assertEquals(SortDirection.REVERSE, SortSettingStore.setting.directionOf(SortMode.NAME))
    }

    /**
     * 长按排序按钮的跳顶请求走**换代次**那条路（与排序写入共用复位键里那个版本号），
     * 但它**不改设置本身**——排序方式与三个类别各自的方向都逐字不变，只是复位键换代 ⇒ 两档滚动回顶部。
     */
    @Test
    fun `长按跳顶的复位请求只递增版本号 不改排序设置`() {
        SortSettingStore.setting = SortSetting()
            .select(SortMode.RELEASE_TIME, SortDirection.REVERSE)
            .select(SortMode.NAME, SortDirection.REVERSE)
        val before = SortSettingStore.setting
        val revisionBefore = SortSettingStore.revision

        SortSettingStore.requestScrollReset()

        assertEquals(
            "换代次（复位键含版本号）⇒ 两档滚动状态按新键重建、回顶部",
            revisionBefore + 1,
            SortSettingStore.revision,
        )
        assertEquals("跳顶不改设置：排序方式与三个类别方向都逐字不变", before, SortSettingStore.setting)
    }
}
