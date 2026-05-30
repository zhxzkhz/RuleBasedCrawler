package com.zhhz.spider.model

data class DownloadTask(
    val bookUrl: String,
    val bookTitle: String,
    val totalChapters: Int = 0,
    val downloadedChapters: Int = 0,
    val status: DownloadStatus = DownloadStatus.PENDING
) {
    val progress: Float
        get() = if (totalChapters == 0) 0f else downloadedChapters.toFloat() / totalChapters
}

enum class DownloadStatus {
    PENDING, DOWNLOADING, PAUSED, COMPLETED, ERROR
}