package com.zhhz.spider.di

import androidx.compose.ui.InternalComposeUiApi
import com.zhhz.spider.db.AppDatabase
import com.zhhz.spider.manager.BookSessionManager
import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.manager.RuleManager
import com.zhhz.spider.network.Book
import com.zhhz.spider.network.FetchTaskRunner
import com.zhhz.spider.network.FileSnapshotInterceptor
import com.zhhz.spider.network.HttpFetcher
import com.zhhz.spider.viewModel.DetailViewModel
import com.zhhz.spider.viewModel.ReaderViewModel
import com.zhhz.spider.viewModel.SearchViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.java.KoinJavaComponent.get
import java.io.File

@OptIn(InternalComposeUiApi::class)
val commonModule = module {

    // 注入 Dao
    single { get<AppDatabase>().ruleDao() }

    // 注入 Dao
    single { get<AppDatabase>().bookDao() }

    // 注入 Dao
    single { get<AppDatabase>().chapterDao() }

    // 创建拦截器单例
    single {
        val file: File = get(File::class.java, named("cacheDir"))
        println("cacheDir >> $file")
        FileSnapshotInterceptor(file)
    }

    single { HttpFetcher(get()) }

    single { FetchTaskRunner(get(), get()) }

    single { RuleManager(get()) } // 必须是 single，全 App 唯一

    // 整个 App 运行期间只存在一个会话管理器
    single { ContextSessionManager(maxEntries = 30) }
    single { BookSessionManager() }

    // 注入 Coil 的 ImageLoader
    /*
    single {
        ImageLoader.Builder(get<PlatformContext>())
            .components {
                // 让 Coil 使用 OkHttp 发起请求，这样可以复用你的缓存和拦截器
                add(OkHttpNetworkFetcherFactory(get<HttpFetcher>().baseClient))
            }
            .memoryCachePolicy(CachePolicy.ENABLED) // 开启内存缓存
            .diskCachePolicy(CachePolicy.ENABLED)   // 开启磁盘缓存（双重保险）
            .build()
    }
     */

    // 注入 ViewModel
    viewModel { SearchViewModel(get(), get(), get(), get()) }

    // 使用 lambda 表达式接收参数 params
    factory { (ruleId: String, detailUrl: String) ->
        DetailViewModel(
            ruleId = ruleId,        // 参数 1
            detailUrl = detailUrl,  // 参数 2
            ruleDao = get(),        // 从容器自动找单例
            bookDao = get(),        // 从容器自动找单例
            chapterDao = get(),        // 从容器自动找单例
            taskRunner = get(),      // 从容器自动找单例
            ruleManager = get(),
            bookSessionManager = get(),
        )
    }

    viewModel { (book: Book) ->
        ReaderViewModel(
            book = book,
            ruleDao = get(),   // 从容器获取
            bookDao = get(),   // 从容器获取
            chapterDao = get(),        // 从容器自动找单例
            taskRunner = get(), // 从容器获取
            ruleManager = get(),
            bookSessionManager = get(),
        )
    }

}