package com.zhhz.spider.repository.impl

import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.model.CrawlerStage
import com.zhhz.spider.network.BookComment
import com.zhhz.spider.network.FetchTaskRunner
import com.zhhz.spider.repository.CommentRepository
import com.zhhz.spider.repository.RuleRepository
import com.zhhz.spider.rule.RuleParser
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.rule.VariableContext
import com.zhhz.spider.rule.supportsComments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CommentRepositoryImpl(
    private val contextSessionManager: ContextSessionManager,
    private val fetchTaskRunner: FetchTaskRunner,
    private val ruleRepository: RuleRepository
) : CommentRepository {

    override suspend fun fetchComments(ruleId: String, bookUrl: String, page: Int): List<BookComment> =
        withContext(Dispatchers.IO) {
            val rule = ruleRepository.getEnabledRules().find { it.id == ruleId }
                ?: throw Exception("找不到对应的书源规则")
            check(rule.supportsComments()) { "该书源未配置评论规则" }

            val ctx = contextSessionManager.getContext(rule.id)
            ctx["bookUrl"] = bookUrl
            ctx["page"] = page.toString()

            // 评论地址：urlSelector 支持 {{key}}(详情URL) 与 {{page}} 模板
            val commentUrl = rule.comment.getCommentUrl(bookUrl, ctx)
            check(commentUrl.isNotBlank()) { "评论地址为空" }

            val html = fetchTaskRunner.fetch(rule, rule.comment, commentUrl, ctx)
            html.throwIfCrawlerError(rule, CrawlerStage.COMMENT)

            val result = rule.comment.getComments(html, ctx).mapNotNull { item ->
                buildComment(rule, item, ctx, depth = 0)
            }
            result
        }

    /**
     * 递归构建评论树：replyListSelector 解析出的回复嵌套在 replys 字段中。
     * 深度上限 5 层防循环引用。
     */
    private fun buildComment(
        rule: SourceRule,
        item: Any,
        ctx: VariableContext,
        depth: Int
    ): BookComment? {
        if (depth > 5) return null

        val content = rule.comment.getContent(item, ctx).trim()
        if (content.isEmpty()) return null

        val replys = if (depth >= 5) {
            emptyList()
        } else {
            rule.comment.getReplyList(item, ctx).mapNotNull { buildComment(rule, it, ctx, depth + 1) }
        }

        return BookComment(
            user = rule.comment.getUser(item, ctx).trim(),
            content = content,
            time = rule.comment.getTime(item, ctx),
            score = rule.comment.getScore(item, ctx),
            avatar = rule.comment.getAvatar(item, ctx),
            replys = replys
        )
    }
}
