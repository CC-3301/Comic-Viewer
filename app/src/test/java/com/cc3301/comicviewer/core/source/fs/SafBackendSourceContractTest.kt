package com.cc3301.comicviewer.core.source.fs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.ProgressStore
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceBehaviorContract
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 本地来源的契约绑定：走生产用的 [SafBackend] + 假文档提供者 [FakeDocumentsProvider]。
 *
 * [FileBackend]（`main` 里零调用点）不占这个位置：契约盯的必须是设备上真正经过的后端，
 * 它在纯 JVM 用例里有覆盖。契约用例本身的读法见 [SourceBehaviorContract]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafBackendSourceContractTest : SourceBehaviorContract() {

    private lateinit var context: Context
    private val provider = FakeDocumentsProvider()

    @Before
    fun registerProvider() {
        context = ApplicationProvider.getApplicationContext()
        provider.register(context)
    }

    override fun createSource(root: File, progressStore: ProgressStore): Source {
        provider.serve(root)
        return DocumentTreeSource(SafBackend(context, FakeDocumentsProvider.treeUri()), progressStore)
    }
}
