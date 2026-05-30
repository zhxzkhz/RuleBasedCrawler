package com.zhhz.spider.repository

import com.zhhz.spider.network.Book
import com.zhhz.spider.viewModel.ChapterBlock

interface ReaderRepository {
    // 强制命名：获取正文内容
    suspend fun fetchData(chapterUrl: String, ruleId: String, bookUrl: String? = null): ChapterBlock

    // 强制命名：保存阅读进度到数据库
    suspend fun saveData(
        bookUrl: String,
        chapterIndex: Int,
        pageIndex: Int,
        chapterTitle: String,
        chapterUrl: String?
    )

    suspend fun loadData(bookUrl: String): Book?

    suspend fun saveBookReadProgress(
        bookUrl: String,
        chapterIndex: Int,
        pageIndex: Int,
        chapterTitle: String,
        chapterUrl: String?
    )
}