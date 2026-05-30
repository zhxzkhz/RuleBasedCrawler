package com.zhhz.spider

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import com.zhhz.spider.db.BookDao
import com.zhhz.spider.db.RuleEntity
import com.zhhz.spider.di.commonModule
import com.zhhz.spider.di.platformModule
import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.network.HttpFetcher
import com.zhhz.spider.network.MangaCallFactory
import com.zhhz.spider.repository.RuleRepository
import com.zhhz.spider.repository.SessionRepository
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.ui.screen.*
import com.zhhz.spider.util.DecryptingFetcher
import com.zhhz.spider.viewModel.BookshelfViewModel
import com.zhhz.spider.viewModel.DetailViewModel
import com.zhhz.spider.viewModel.ReaderViewModel
import com.zhhz.spider.viewModel.SearchViewModel
import okio.FileSystem
import okio.Path
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import org.koin.java.KoinJavaComponent.get
import rulebasedcrawler.composeapp.generated.resources.Res
import rulebasedcrawler.composeapp.generated.resources.close_24px
import java.io.File

val appModule = module {
    includes(platformModule,commonModule)
}


@Composable
fun App(koinConfig: KoinAppDeclaration = {}) {

    System.setProperty("fastjson2.useUnsafe", "false")

    KoinApplication(application = {
        // 1. 加载平台特有的配置（如 Android Context）
        koinConfig()
        // 2. 加载业务模块
        modules(appModule)
    }) {
        // --- 内部的所有组件现在都可以使用注入了 ---
        val bookDao = koinInject<BookDao>()
        val httpFetcher = koinInject<HttpFetcher>()
        val ruleRepository = koinInject<RuleRepository>()
        val sessionRepository = koinInject<SessionRepository>()
        val contextSessionManager = koinInject< ContextSessionManager>()

        val diskCache = DiskCache.Builder()
            .fileSystem(FileSystem.SYSTEM)
            .directory(get(Path::class.java, named("imageCacheDir"))) // 💡 设进这里
            .build()

        val okHttpFactory = OkHttpNetworkFetcherFactory(
            callFactory = MangaCallFactory(httpFetcher,ruleRepository,sessionRepository,contextSessionManager) // 👈 Koin 会自动把你的 MangaCallFactory 塞给它
        )

        setSingletonImageLoaderFactory { context ->
            ImageLoader.Builder(context)
                .diskCache(diskCache)
                .components {
                    // 【核心挂载】不再传死 Client，而是传我们的动态工厂
                    add(DecryptingFetcher.Factory(okHttpFactory))
                    /*
                    add(OkHttpNetworkFetcherFactory(
                        MangaCallFactory(httpFetcher,ruleRepository,sessionRepository,contextSessionManager)
                    )) */
                }
                .build()
        }


        val scope = rememberCoroutineScope()

        var isDebug by remember { mutableStateOf(System.getenv("isDebug").toBoolean()) }
        //isDebug = true

        val navController = rememberNavController()
        // 1. 核心状态
        var currentRule by remember { mutableStateOf(SourceRule()) }


        if (isDebug) {
            MainScreen(currentRule,onRuleChange = { newRule ->
                // 3. 当子组件触发回调时，在这里更新父级状态
                currentRule = newRule
            },{
                isDebug = it
            }) {
                currentRule = it
            }
        } else {

            MaterialTheme {

                NavHost(
                    navController = navController,
                    startDestination = "bookshelf"
                ) {
                    composable("bookshelf") {
                        BookshelfScreen(
                            viewModel = koinViewModel<BookshelfViewModel>(),
                            onGoToSearch = { navController.navigate("search") },
                            onNavigateToReader = { book ->
                                navController.navigate(book)
                            },
                            onOpenRule = {
                                isDebug = it
                            }
                        )
                    }
                    composable("search") {
                        SearchScreen(
                            viewModel = koinViewModel<SearchViewModel>(),
                            onNavigateBack = { navController.popBackStack() },
                            onNavigateToDetail = { route ->
                                // 跳转详情并传递参数
                                navController.navigate(route)
                            }
                        )
                    }
                    composable<DetailRoute> { backStackEntry ->
                        //默认获取第一个显示
                        val book = backStackEntry.toRoute<DetailRoute>()

                        // 通过 Koin 传入参数获取独立的 ViewModel
                        // val viewModel = koinInject<DetailViewModel> { parametersOf(book.ruleId, book.detailUrl) }

                        DetailScreen(
                            detailUrl = book.detailUrl,
                            ruleId = book.ruleId,
                            viewModel = koinViewModel<DetailViewModel> {
                                parametersOf(book.detailUrl,book.ruleId)
                            },
                            onNavigateBack = { navController.popBackStack() },
                            onNavigateToReader = { readerRoute ->
                                navController.navigate(readerRoute)
                            }
                        )
                    }
                    composable<ReaderRoute> { backStackEntry ->
                        val book = backStackEntry.toRoute<ReaderRoute>()

                        val viewModel = koinViewModel<ReaderViewModel>()

                        ReaderScreen(bookUrl = book.bookUrl, chapterIndex = book.chapterIndex, ruleId = book.ruleId, viewModel = viewModel, onNavigateBack = { navController.popBackStack() })
                    }
                }

            }
        }




    }

}

@Composable
fun RuleSelectDialog(
    rules: List<RuleEntity>,
    onSelect: (RuleEntity) -> Unit,
    onDelete: (RuleEntity) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择规则项目", fontWeight = FontWeight.Bold) },
        text = {
            if (rules.isEmpty()) {
                Text("数据库中暂无规则，请先创建或导入。", color = Color.Gray)
            } else {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(rules) { entity ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onSelect(entity) },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(entity.name, fontWeight = FontWeight.Bold)
                                    Text("ID: ${entity.id}", fontSize = 10.sp, color = Color.Gray)
                                }
                                IconButton(onClick = { onDelete(entity) }) {
                                    Icon(painterResource(Res.drawable.close_24px), null, tint = Color.Red.copy(0.6f), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}


