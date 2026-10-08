package com.cc3301.comicviewer.ui.session

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 会话状态模块的对外面：公开成员恰为清单里那几个（防再张回去）。
 *
 * 步骤层（浏览槽的单槽与释放路径、会话来源的打点与释放、清条目名缓存）都在模块内部；
 * 窄根只把生产那几份依赖（协程域 / 来源构造器 / 落盘钩子）交给构造参数。
 */
class SessionStateTest {

    @Test
    fun `对外面恰为清单里那几个成员`() {
        // 只看本类自己声明的**公开方法**（属性的 getter 也在其中）：构造器、合成方法与内部符号不算
        //（`internal` 声明在字节码里带 `$` 后缀的模块名）。
        val entries = SessionState::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && it.name != "<init>" && '$' !in it.name }
            .map { it.name }
            .sorted()
        assertEquals(
            "SessionState 的公开成员恰为：会话来源槽读写（adopt / clear / currentSource / currentConnId）、" +
                "浏览来源单槽（browsingSourceFor / browsingSourceIfResolved）、entryNames / browseHistory、会话收口 end",
            listOf(
                "adopt",
                "browsingSourceFor",
                "browsingSourceIfResolved",
                "clear",
                "end",
                "getBrowseHistory",
                "getCurrentConnId",
                "getCurrentSource",
                "getEntryNames",
            ),
            entries,
        )
    }
}
