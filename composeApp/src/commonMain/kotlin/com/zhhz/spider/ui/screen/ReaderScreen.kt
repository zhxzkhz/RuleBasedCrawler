package com.zhhz.spider.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.zhhz.spider.viewModel.ReaderUiState
import com.zhhz.spider.viewModel.ReaderViewModel
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.Res
import rulebasedcrawler.composeapp.generated.resources.arrow_back_24dp


@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var showMenu by remember { mutableStateOf(false) }
    val currentPage by viewModel.currentPageIndex.collectAsState()

    Box(Modifier.fillMaxSize().background(Color(0xFFFDEFD5))) { // 经典羊皮纸护眼色
        when (val state = uiState) {
            is ReaderUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            is ReaderUiState.Error -> Text("失败: ${state.message}", Modifier.clickable { viewModel.loadChapterContent() })
            is ReaderUiState.Success -> {
                val content = state.content
                /*
                // 1. 内容主体 (点击中心切换菜单)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { showMenu = !showMenu }
                ) {
                    Text(
                        text = content.body,
                        fontSize = 18.sp,
                        lineHeight = 28.sp,
                        color = Color(0xFF2B2B2B)
                    )

                    // 底部翻页按钮
                    Row(Modifier.fillMaxWidth().padding(vertical = 32.dp), Arrangement.SpaceEvenly) {
                        content.nextChapterUrl?.let {
                            Button(onClick = { viewModel.loadChapterContent(it) }) { Text("下一章") }
                        }
                    }
                }
                */



                if (content.images.isEmpty()) {
                    Text("数据源为空！请检查解析规则", color = Color.White, modifier = Modifier.align(Alignment.Center))
                }
                MangaPageList(content.images, viewModel,{

                })
            }
        }

        Text(text = currentPage.toString(),fontSize = 18.sp,modifier = Modifier.align(Alignment.BottomStart),color = Color.White)

        // 2. 悬浮菜单 (Top & Bottom)
        AnimatedVisibility(
            visible = showMenu,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it }
        ) {
            ReaderTopBar(onBack)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(onBack: () -> Unit) {
    Surface(color = Color.Black.copy(alpha = 0.8f), contentColor = Color.White) {
        TopAppBar(
            title = {},
            navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(Res.drawable.arrow_back_24dp), null) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )
    }
}