package com.zhhz.spider.ui.screen

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhhz.spider.db.BookDao
import com.zhhz.spider.db.BookEntity
import com.zhhz.spider.ui.widget.BookCover
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import rulebasedcrawler.composeapp.generated.resources.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    onGoToSearch: () -> Unit,           // 导航：去搜索页
    onOpenBook: (BookEntity) -> Unit,    // 导航：打开书（去详情或阅读页）
    onDeleteBook: (BookEntity) -> Unit   // 动作：从书架移除
) {
    val bookDao = koinInject<BookDao>()
    val books by bookDao.getAllBooksFlow().collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的书架", fontWeight = FontWeight.ExtraBold) },
                actions = {
                    IconButton(onClick = onGoToSearch) {
                        Icon(painterResource(Res.drawable.search_24px), contentDescription = "搜索新书")
                    }
                }
            )
        },
        floatingActionButton = {
            // 引导用户去搜索添加
            FloatingActionButton(onClick = onGoToSearch, containerColor = MaterialTheme.colorScheme.primary) {
                Icon(painterResource(Res.drawable.add_24px), "添加书籍")
            }
        }
    ) { padding ->
        if (books.isEmpty()) {
            EmptyBookshelfPlaceholder()
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 105.dp), // 适配手机和电脑
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(padding).fillMaxSize()
            ) {
                items(books) { book ->
                    BookItemCard(
                        book = book,
                        onClick = { onOpenBook(book) },
                        onDelete = { onDeleteBook(book) }
                    )
                }
            }
        }
    }
}

@Composable
fun BookItemCard(book: BookEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { showMenu = true }
                )
            }
    ) {
        // 封面图（带优雅圆角和阴影）
        Card(
            shape = RoundedCornerShape(6.dp),
            elevation = CardDefaults.cardElevation(3.dp),
            modifier = Modifier.aspectRatio(0.72f).fillMaxWidth()
        ) {
            BookCover(url = book.cover)
        }

        Spacer(Modifier.height(8.dp))

        // 书名
        Text(
            text = book.title,
            maxLines = 2,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Bold,
            overflow = TextOverflow.Ellipsis
        )

        // 阅读进度
        Text(
            text = book.lastReadChapterTitle,
            fontSize = 10.sp,
            color = Color.Gray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // 长按弹出的管理菜单
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("从书架删除", color = Color.Red) },
                onClick = {
                    onDelete()
                    showMenu = false
                },
                leadingIcon = { Icon(painterResource(Res.drawable.close_24px), null, tint = Color.Red) }
            )
        }
    }
}

@Composable
fun BookItem(book: BookEntity, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        // 封面图卡片
        Card(
            shape = RoundedCornerShape(4.dp),
            elevation = CardDefaults.cardElevation(4.dp),
            modifier = Modifier.aspectRatio(0.75f) // 经典书刊比例
        ) {
            // 注意：此处需要集成 KMP 图片加载库如 Coil3 或 Kamel
            // 暂时使用占位 Box
            BookCover(book.cover)
        }

        Spacer(Modifier.height(8.dp))

        // 书名
        Text(
            text = book.title,
            maxLines = 2,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            overflow = TextOverflow.Ellipsis
        )

        // 最后阅读/更新进度
        Text(
            text = book.lastReadChapterTitle,
            fontSize = 11.sp,
            color = Color.Gray,
            maxLines = 1
        )
    }
}

@Composable
fun EmptyBookshelfPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center // 居中显示
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 1. 图标：使用内置的书库图标
            Icon(
                painter = painterResource(Res.drawable.auto_stories_24px), // 需要导入 material-icons-extended
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = Color.LightGray // 使用淡灰色，不抢眼
            )

            Spacer(Modifier.height(16.dp))

            // 2. 主提示文字
            Text(
                text = "书架空空如也",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Gray
            )

            Spacer(Modifier.height(8.dp))

            // 3. 引导说明
            Text(
                text = "通过【规则测试】抓取书籍后，点击“加入书架”即可在这里看到它们。",
                fontSize = 13.sp,
                color = Color.Gray.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp)
            )
        }
    }
}