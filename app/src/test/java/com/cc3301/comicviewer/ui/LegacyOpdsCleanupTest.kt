package com.cc3301.comicviewer.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * OPDS 下线后的存量清理（票 33）：旧版本升级上来的设备带着 `cacheDir/opds` 缓存目录与
 * `opds_cache_limit_mb` 设置键，冷启动清理必须把这两样清干净，且不动其它缓存与设置。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyOpdsCleanupTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ServiceLocator.init(context)
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        File(context.cacheDir, "opds").deleteRecursively()
    }

    @Test
    fun `清掉 OPDS 缓存目录与设置键 其它缓存与设置原样保留`() {
        // 旧库留下的数据：缓存目录（含子目录与文件）与设置里的上限键
        val cacheDir = File(context.cacheDir, "opds")
        File(cacheDir, "nested").mkdirs()
        File(cacheDir, "book.cbz").writeBytes(byteArrayOf(1, 2, 3))
        File(cacheDir, "nested/cover.jpg").writeBytes(byteArrayOf(4))
        val otherCache = File(context.cacheDir, "cover").apply { mkdirs() }
        File(otherCache, "keep.jpg").writeBytes(byteArrayOf(5))
        prefs().edit().putInt("opds_cache_limit_mb", 512).putBoolean("volume_keys_enabled", false).commit()
        assertTrue("前置条件：缓存目录已存在", cacheDir.isDirectory)

        purgeLegacyOpdsData(context)

        assertFalse("OPDS 缓存目录应被整目录清掉", cacheDir.exists())
        assertTrue("其它缓存目录不受影响", File(otherCache, "keep.jpg").isFile)
        assertFalse("OPDS 缓存上限键应被移除", prefs().contains("opds_cache_limit_mb"))
        assertEquals("既有设置键不受影响", false, prefs().getBoolean("volume_keys_enabled", true))
    }

    @Test
    fun `没有 OPDS 数据时再跑一次也不抛`() {
        assertFalse(File(context.cacheDir, "opds").exists())

        purgeLegacyOpdsData(context)
        purgeLegacyOpdsData(context)

        assertFalse(File(context.cacheDir, "opds").exists())
        assertFalse(prefs().contains("opds_cache_limit_mb"))
    }

    private fun prefs() = context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
}
