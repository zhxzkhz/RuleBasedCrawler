package com.zhhz.spider.network

import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.rule.VariableContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RuleApi {

    suspend fun runSearchLogic(taskRunner: FetchTaskRunner, rule: SourceRule, keyword: String): List<SearchBook> {
        return withContext(Dispatchers.IO) {
            try {
                val ctx: VariableContext = rule.ctx
                // 1. 执行网络请求 (FetchTaskRunner 已经处理了缓存、代理、Header)
                val html = taskRunner.fetch(rule, rule.search, keyword, ctx)

                // 2. 解析列表
                val list = rule.search.getList(html,ctx)

                // 3. 映射为 SearchBook 对象
                list.map { item ->
                    SearchBook(
                        title = rule.search.getName(item,ctx),
                        author = rule.search.getAuthor(item,ctx),
                        cover = rule.search.getCover(item,ctx),
                        detailUrl = rule.search.getDetailUrl(item,ctx),
                        ruleId = rule.id,
                        sourceName = rule.name
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    suspend fun runBookDetailLogic(taskRunner: FetchTaskRunner, rule: SourceRule, detailUrl: String): BookDetail {
        return withContext(Dispatchers.IO) {
            val ctx: VariableContext = rule.ctx
            ctx["bookUrl"] = detailUrl
            // 1. 抓取详情页 HTML
            val bookDetailHtml = taskRunner.fetch(rule, rule.detail, detailUrl, ctx)
            ctx["bookDetailHtml"] = bookDetailHtml
            // 2. 解析基本信息
            val title = rule.detail.getBookName(bookDetailHtml, ctx)
            val author = rule.detail.getBookAuthor(bookDetailHtml, ctx)
            val cover = rule.detail.getBookCover(bookDetailHtml, ctx)
            val desc = rule.detail.getBookDesc(bookDetailHtml, ctx)
            ctx["title"] = title
            ctx["author"] = author
            ctx["cover"] = cover

            BookDetail(detailUrl,title, author, cover, desc)
        }
    }

    suspend fun runCatalogLogic(taskRunner: FetchTaskRunner, rule: SourceRule, detailUrl: String): List<Chapter> {
        val ctx: VariableContext = rule.ctx
        val detailHtml = ctx["bookDetailHtml"] ?: ""
        val catalogUrl = rule.detail.getCatalogUrl(detailHtml,ctx)
        ctx["catalogUrl"] = catalogUrl
        // 逻辑：如果已经有详情页 HTML，先尝试从中解析出目录 URL
        val catalogHtml = if (rule.catalog.urlSelector.steps.isNotEmpty()) {
            val nextUrl = rule.catalog.getCatalogUrl(catalogUrl, ctx)
            if (detailUrl == nextUrl && detailHtml.isNotBlank()){
                detailHtml
            }
            withContext(Dispatchers.IO) { taskRunner.fetch(rule, rule.catalog, nextUrl, ctx) }
        } else {
            if (catalogUrl.isBlank()){
                detailHtml
            } else {
                withContext(Dispatchers.IO) {
                    taskRunner.fetch(rule, rule.catalog, catalogUrl, ctx)
                }
            }
        }

        return rule.catalog.getChapters(catalogHtml, ctx).mapIndexed{ index, it ->
            Chapter(index,
                rule.catalog.getChapterName(it, ctx),
                rule.catalog.getChapterUrl(it, ctx)
            )
        }
    }

}