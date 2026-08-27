package com.zhhz.spider.repository

import com.zhhz.spider.network.BookComment

interface CommentRepository {
    /**
     * 拉取书籍评论列表。
     * @param page 页码（评论地址支持 {{page}} 模板时生效）
     */
    suspend fun fetchComments(ruleId: String, bookUrl: String, page: Int = 1): List<BookComment>
}
