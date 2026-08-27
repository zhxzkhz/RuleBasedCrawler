package com.zhhz.spider.model

data class DownloadTask(
    val bookUrl: String,
    val bookTitle: String,
    val totalChapters: Int = 0,
    val downloadedChapters: Int = 0,
    val status: DownloadStatus = DownloadStatus.PENDING,
    // 本轮任务中下载失败的章节 URL（供"重试失败章节"使用）
    val failedUrls: List<String> = emptyList()
) {
    val progress: Float
        get() = if (totalChapters == 0) 0f else downloadedChapters.toFloat() / totalChapters
}

enum class DownloadStatus {
    PENDING, DOWNLOADING, PAUSED, COMPLETED, ERROR
}