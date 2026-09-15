package com.cc3301.comicviewer.core.source

/** 内存实现（测试与预填默认） */
class InMemoryProgressStore : ProgressStore {
    private val map = HashMap<String, ReadingProgress>()

    override suspend fun read(bookId: String): ReadingProgress? = map[bookId]

    override suspend fun write(bookId: String, pageIndex: Int, totalPages: Int) {
        map[bookId] = ReadingProgress(pageIndex, totalPages, System.currentTimeMillis())
    }
}
