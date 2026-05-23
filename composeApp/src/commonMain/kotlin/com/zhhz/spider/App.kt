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
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.zhhz.spider.db.BookDao
import com.zhhz.spider.db.RuleEntity
import com.zhhz.spider.di.commonModule
import com.zhhz.spider.di.platformModule
import com.zhhz.spider.manager.RuleManager
import com.zhhz.spider.network.Book
import com.zhhz.spider.network.HttpFetcher
import com.zhhz.spider.network.MangaCallFactory
import com.zhhz.spider.network.SearchBook
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.ui.screen.*
import com.zhhz.spider.viewModel.DetailViewModel
import com.zhhz.spider.viewModel.ReaderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import rulebasedcrawler.composeapp.generated.resources.Res
import rulebasedcrawler.composeapp.generated.resources.close_24px

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
        val ruleManager = koinInject<RuleManager>()

        setSingletonImageLoaderFactory { context ->
            ImageLoader.Builder(context)
                .components {
                    // 【核心挂载】不再传死 Client，而是传我们的动态工厂
                    add(OkHttpNetworkFetcherFactory(
                        MangaCallFactory(httpFetcher,ruleManager)
                    ))
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
                            onGoToSearch = { navController.navigate("search") },
                            onOpenBook = { book ->
                                navController.navigate(book)
                            },
                            onDeleteBook = { book ->
                                scope.launch(Dispatchers.IO) { bookDao.removeFromBookshelf(book) }
                            }
                        )
                    }
                    composable("search") {
                        SearchScreen(
                            onBack = { navController.popBackStack() },
                            onBookClick = { searchBook ->
                                // 跳转详情并传递参数
                                navController.navigate(searchBook)
                            },
                            onOpen = {
                                isDebug = it
                            }
                        )
                    }
                    composable<SearchBook> { backStackEntry ->
                        val book = backStackEntry.toRoute<SearchBook>()

                        // 通过 Koin 传入参数获取独立的 ViewModel
                        val viewModel = koinInject<DetailViewModel> { parametersOf(book.ruleId, book.detailUrl) }

                        DetailScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() },
                            onChapterClick = { book ->
                                navController.navigate(book)
                            }
                        )
                    }
                    composable<Book> { backStackEntry ->
                        val book = backStackEntry.toRoute<Book>()
                        // 【关键】：使用 koinViewModel 并通过 parametersOf 传入三个参数
                        val viewModel = koinViewModel<ReaderViewModel> {
                            parametersOf(book)
                        }

                        ReaderScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
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
