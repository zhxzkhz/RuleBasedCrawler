package com.zhhz.spider

import io.ktor.http.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.concurrent.TimeUnit

class TestServer(private val port: Int = 8080) {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    val baseUrl: String = "http://localhost:$port"

    fun start(): TestServer {
        if (server == null) {
            server = createServer().start(wait = false)
        }
        return this
    }

    fun stop() {
        server?.stop(1, 5, TimeUnit.SECONDS)
        server = null
    }

    private fun createServer(): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
        return embeddedServer(Netty, port = port) {
            routing {

                // 1. 模拟搜索接口 (返回 HTML 格式的搜索列表)
                get("/search") {
                    val keyword = call.parameters["q"] ?: ""
                    val page = call.parameters["page"] ?: "1"

                    val html = """
                    <html>
                    <head><title>搜索结果 - $keyword</title></head>
                    <body>
                        <div class="book-list">
                            <!-- 模拟搜索到的第一本书 -->
                            <div class="book-item">
                                <a class="title" href="$baseUrl/book/1001">《测试小说：${keyword}神话》</a>
                                <span class="author">作者：张三</span>
                                <span class="type">玄幻</span>
                                <img class="cover" src="$baseUrl/images/cover1.jpg"/>
                            </div>
                            <!-- 模拟搜索到的第二本书 (漫画) -->
                            <div class="book-item">
                                <a class="title" href="$baseUrl/book/2002">《测试漫画：${keyword}之王》</a>
                                <span class="author">作者：李四</span>
                                <span class="type">漫画</span>
                                <img class="cover" src="$baseUrl/images/cover2.jpg"/>
                            </div>
                        </div>
                    </body>
                    </html>
                """.trimIndent()
                    call.respondText(html, ContentType.Text.Html)
                }

                // 2. 模拟书籍详情页
                get("/book/{bookId}") {
                    val bookId = call.parameters["bookId"] ?: ""
                    val title = if (bookId == "1001") "测试小说：热血神话" else "测试漫画：热血之王"
                    val author = if (bookId == "1001") "张三" else "李四"
                    val desc = "这是一部惊心动魄的测试作品，ID 为 $bookId。讲述了一个凡人不断逆袭，最终掌控诸天的热血故事。"
                    val status = "连载中"

                    val html = """
                    <html>
                    <body>
                        <h1 class="book-title">$title</h1>
                        <span class="author">$author</span>
                        <span class="status">$status</span>
                        <div class="book-desc">$desc</div>
                        <!-- 💡 指向目录页的链接 -->
                        <a class="catalog-link" href="$baseUrl/book/$bookId/catalog">查看完整目录</a>
                    </body>
                    </html>
                """.trimIndent()
                    call.respondText(html, ContentType.Text.Html)
                }

                // 3. 模拟目录页 (返回章节列表)
                get("/book/{bookId}/catalog") {
                    val bookId = call.parameters["bookId"] ?: ""

                    val html = if (bookId == "1001") {
                        // 小说目录
                        """
                        <html>
                        <body>
                            <ul class="chapter-list">
                                <li><a href="$baseUrl/chapter/1001_1">第一章：陨落的天才</a></li>
                                <li><a href="$baseUrl/chapter/1001_2">第二章：斗气大陆</a></li>
                                <li><a href="$baseUrl/chapter/1001_3">第三章：退婚之辱</a></li>
                            </ul>
                        </body>
                        </html>
                    """.trimIndent()
                    } else {
                        // 漫画目录
                        """
                        <html>
                        <body>
                            <ul class="chapter-list">
                                <li><a href="$baseUrl/chapter/2002_1">第1话：觉醒之日</a></li>
                                <li><a href="$baseUrl/chapter/2002_2">第2话：神秘力量</a></li>
                            </ul>
                        </body>
                        </html>
                    """.trimIndent()
                    }
                    call.respondText(html, ContentType.Text.Html)
                }

                // 4. 模拟正文页 (区分小说和漫画)
                get("/chapter/{chapterId}") {
                    val chapterId = call.parameters["chapterId"] ?: ""

                    val html = if (chapterId.startsWith("1001")) {
                        // 💡 返回小说正文文本
                        """
                        <html>
                        <body>
                            <h1 class="chapter-title">测试章节 - $chapterId</h1>
                            <div class="chapter-content">
                                　　这里是《测试小说》的正文内容。在这个气壮山河的斗气大陆上，只有强者才配拥有尊严。<br/>
                                　　“三十年河东，三十年河西，莫欺少年穷！”少年咬紧牙关，在烈日下流下了不屈的汗水。<br/>
                                　　狂风呼啸，属于他的传奇，才刚刚开始……
                            </div>
                        </body>
                        </html>
                    """.trimIndent()
                    } else {
                        // 💡 返回漫画图片 URL 列表
                        """
                        <html>
                        <body>
                            <h1 class="chapter-title">测试漫画 - $chapterId</h1>
                            <div class="manga-list">
                                <img class="manga-page" src="$baseUrl/images/manga_p1.jpg"/>
                                <img class="manga-page" src="$baseUrl/images/manga_p2.jpg"/>
                                <img class="manga-page" src="$baseUrl/images/manga_p3.jpg"/>
                            </div>
                        </body>
                        </html>
                    """.trimIndent()
                    }
                    call.respondText(html, ContentType.Text.Html)
                }
            }
        }
    }
}
