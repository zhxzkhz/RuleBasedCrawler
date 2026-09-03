package com.zhhz.spider.di

import androidx.compose.ui.InternalComposeUiApi
import com.zhhz.spider.db.AppDatabase
import com.zhhz.spider.manager.BookCleanupManager
import com.zhhz.spider.manager.BookSessionManager
import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.manager.DownloadManager
import com.zhhz.spider.manager.RuleManager
import com.zhhz.spider.network.FetchTaskRunner
import com.zhhz.spider.network.FileSnapshotInterceptor
import com.zhhz.spider.network.HttpFetcher
import com.zhhz.spider.network.MangaCallFactory
import com.zhhz.spider.repository.*
import com.zhhz.spider.repository.impl.*
import com.zhhz.spider.settings.SettingsRepository
import com.zhhz.spider.util.BookPackager
import com.zhhz.spider.viewModel.BookshelfViewModel
import com.zhhz.spider.viewModel.DetailViewModel
import com.zhhz.spider.viewModel.ExploreViewModel
import com.zhhz.spider.viewModel.ReaderViewModel
import com.zhhz.spider.viewModel.SearchViewModel
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.createdAtStart
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File

@OptIn(InternalComposeUiApi::class)
val commonModule = module {

    // 注入 Dao
    single { get<AppDatabase>().ruleDao() }
    single { get<AppDatabase>().bookDao() }
    single { get<AppDatabase>().chapterDao() }
    single { get<AppDatabase>().bookGroupDao() }
    single { get<AppDatabase>().settingsDao() }

    // 创建拦截器单例
    single {
        val file: File = get<File>(named("cacheDir"))
        println("cacheDir >> $file")
        FileSnapshotInterceptor(file)
    }


    // 整个 App 运行期间只存在一个会话管理器
    single { ContextSessionManager(maxEntries = 30) }

    singleOf(::RuleManager)
    singleOf(::BookSessionManager)
    singleOf(::HttpFetcher)
    singleOf(::MangaCallFactory)
    singleOf(::FetchTaskRunner)
    singleOf(::DownloadManager)
    singleOf(::BookCleanupManager)

    // ================= 仓库层 (Repository) =================
    singleOf(::RuleRepositoryImpl) {
        bind<RuleRepository>() // 1. 将实现类绑定到接口
        createdAtStart()       // 2. 替代原本的 createdAtStart = true
    }

    singleOf(::SessionRepositoryImpl) {
        bind<SessionRepository>()
    }

    singleOf(::SearchRepositoryImpl) {
        bind<SearchRepository>()
    }

    singleOf(::BookRepositoryImpl) {
        bind<BookRepository>()
    }

    singleOf(::DetailRepositoryImpl) {
        bind<DetailRepository>()
    }

    singleOf(::CatalogRepositoryImpl) {
        bind<CatalogRepository>()
    }

    singleOf(::ReaderRepositoryImpl) {
        bind<ReaderRepository>()
    }

    singleOf(::ExploreRepositoryImpl) {
        bind<ExploreRepository>()
    }

    singleOf(::CommentRepositoryImpl) {
        bind<CommentRepository>()
    }

    single { SettingsRepository(get(), get<File>(named("cacheDir"))) }


    // ================= 视图模型层 (ViewModel) =================
    // 一律使用 viewModel DSL 注册，享受最安全的生命周期管理和协程销毁
    viewModelOf(::BookshelfViewModel)
    viewModelOf(::SearchViewModel)
    viewModelOf(::DetailViewModel)
    viewModelOf(::ReaderViewModel)
    viewModelOf(::ExploreViewModel)


}
