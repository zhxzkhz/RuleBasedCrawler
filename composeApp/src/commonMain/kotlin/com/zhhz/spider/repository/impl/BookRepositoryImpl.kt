package com.zhhz.spider.repository.impl

import com.zhhz.spider.db.BookDao
import com.zhhz.spider.db.BookGroupEntity
import com.zhhz.spider.db.BookGroupFlags
import com.zhhz.spider.db.ChapterDao
import com.zhhz.spider.model.BookBackup
import com.zhhz.spider.model.BookshelfBackup
import com.zhhz.spider.model.BookshelfImportResult
import com.zhhz.spider.model.toJsonString
import com.zhhz.spider.model.parseBookshelfBackup
import com.zhhz.spider.network.Book
import com.zhhz.spider.repository.BookRepository
import com.zhhz.spider.db.toDomain
import com.zhhz.spider.network.toEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// 书架功能的具体实现
class BookRepositoryImpl(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val bookGroupDao: com.zhhz.spider.db.BookGroupDao,
    private val ruleRepository: com.zhhz.spider.repository.RuleRepository
) : BookRepository {

    override fun loadData(): Flow<List<Book>> {
        // Room/SQLDelight 的 Flow 默认已经在后台线程安全处理，直接返回即可
        return bookDao.getAllBooksFlow().map { bookEntities ->
            bookEntities.map {
                it.toDomain()
            }
        }
    }

    override suspend fun deleteData(book: Book) {
        withContext(Dispatchers.IO) {
            bookDao.removeFromBookshelf(book.toEntity())
        }
    }

    override suspend fun removeFromShelf(bookUrls: List<String>) {
        if (bookUrls.isEmpty()) return
        withContext(Dispatchers.IO) { bookDao.setInLibrary(bookUrls, false) }
    }

    override suspend fun restoreToShelf(bookUrls: List<String>) {
        if (bookUrls.isEmpty()) return
        withContext(Dispatchers.IO) { bookDao.setInLibrary(bookUrls, true) }
    }

    override suspend fun exportBackupJson(
        bookUrls: Collection<String>,
        includeProgress: Boolean
    ): String {
        val urls = bookUrls.toList()
        if (urls.isEmpty()) {
            return BookshelfBackup(exportedAt = System.currentTimeMillis()).toJsonString()
        }
        val books = withContext(Dispatchers.IO) { bookDao.getBooksByUrls(urls) }
        if (books.isEmpty()) {
            return BookshelfBackup(exportedAt = System.currentTimeMillis()).toJsonString()
        }

        // 不含阅读进度时：清除"读到哪"字段与章节已读标记，仅保留目录结构
        fun stripProgress(entity: com.zhhz.spider.db.BookEntity) = entity.copy(
            lastReadChapterIndex = -1,
            lastReadChapterTitle = "尚未开始",
            lastReadChapterUrl = "",
            lastReadPageIndex = 0
        )

        val backups = books.map { entity ->
            BookBackup(
                book = if (includeProgress) entity else stripProgress(entity),
                chapters = chapterDao.getChaptersByBookUrl(entity.detailUrl).let { chapters ->
                    if (includeProgress) chapters else chapters.map { it.copy(isRead = false) }
                }
            )
        }

        // 分组定义按导出书籍实际用到的掩码过滤，避免冗余
        val usedFlags = books.fold(0L) { acc, entity -> acc or entity.groupFlags }
        val groups = withContext(Dispatchers.IO) { bookGroupDao.getAll() }
            .filter { BookGroupFlags.contains(usedFlags, it.mask) }

        return BookshelfBackup(
            exportedAt = System.currentTimeMillis(),
            books = backups,
            groups = groups
        ).toJsonString()
    }

    override suspend fun importBackupJson(
        json: String,
        groupMode: com.zhhz.spider.viewModel.BookGroupImportMode,
        targetGroupId: Long?
    ): BookshelfImportResult = withContext(Dispatchers.IO) {
        val backup = parseBookshelfBackup(json)
        // 💡 本机可用书源集合：导入的书若 ruleId 不存在，点开详情必然失败，直接跳过并计数
        val knownRuleIds = ruleRepository.getEnabledRules().mapTo(mutableSetOf()) { it.id }

        // 移动到现有分组：解析目标分组的掩码（无效则报错，不产生半截导入）
        val overrideMask: Long? = if (groupMode == com.zhhz.spider.viewModel.BookGroupImportMode.MOVE_TO_EXISTING) {
            val target = targetGroupId?.let { bookGroupDao.getById(it) }
                ?: error("目标分组不存在，请重新选择")
            target.mask
        } else null

        var addedCount = 0
        var skippedCount = 0
        var missingRuleCount = 0

        // 备份 mask → 本机 mask 映射表（用于转换书中的 groupFlags）
        val maskRemap = mutableMapOf<Long, Long>()

        if (overrideMask == null) {
            // 1. 合并模式：按名称匹配——本机已有同名分组则复用本机 mask，
            // 避免备份与本机 mask 冲突时书籍归属错位。
            val localGroups = bookGroupDao.getAll()
            val maskByName = localGroups.associate { it.name to it.mask }
            val usedMasks = localGroups.mapTo(mutableSetOf()) { it.mask }

            backup.groups.forEach { group ->
                val existingMask = maskByName[group.name]
                if (existingMask != null) {
                    // 本机已有同名分组，复用本机 mask，后续转换 books 的 groupFlags
                    if (existingMask != group.mask) {
                        maskRemap[group.mask] = existingMask
                    }
                } else if (group.mask !in usedMasks) {
                    // 本机没有同名分组，使用备份的 mask
                    if (bookGroupDao.insert(group.copy(id = 0)) > 0) {
                        usedMasks.add(group.mask)
                    }
                } else {
                    // mask 被本机占用但名字不同，分配一个新的可用 mask
                    val newMask = BookGroupFlags.nextMask(usedMasks)
                    if (newMask != null && bookGroupDao.insert(group.copy(id = 0, mask = newMask)) > 0) {
                        usedMasks.add(newMask)
                    }
                }
            }
        }

        // 转换器：将备份 mask 转换为本机 mask
        fun convertFlags(backupFlags: Long): Long {
            var localFlags = 0L
            var remaining = backupFlags
            while (remaining != 0L) {
                val bit = remaining.takeLowestOneBit() // 获取最低位的 mask
                remaining = remaining and (remaining - 1) // 清除最低位
                val converted = maskRemap[bit] ?: bit
                localFlags = localFlags or converted
            }
            return localFlags
        }

        backup.books.forEach { item ->
            val existing = bookDao.getBookByUrl(item.book.detailUrl)
            when {
                existing != null -> skippedCount++
                item.book.ruleId !in knownRuleIds -> missingRuleCount++
                else -> {
                    val imported = item.book.copy(
                        inLibrary = true,
                        groupFlags = overrideMask ?: convertFlags(item.book.groupFlags)
                    )
                    bookDao.addToBookshelf(imported)
                    if (item.chapters.isNotEmpty()) {
                        bookDao.insertChaptersIgnore(item.chapters.map { it.copy(id = 0) })
                    }
                    addedCount++
                }
            }
        }
        BookshelfImportResult(
            addedCount = addedCount,
            skippedCount = skippedCount,
            missingRuleCount = missingRuleCount
        )
    }

    // ---------------- 自定义分组 ----------------

    override fun observeGroups(): Flow<List<BookGroupEntity>> = bookGroupDao.observeAll()

    override suspend fun createGroup(name: String, isAutoUpdate: Boolean): BookGroupEntity {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "分组名不能为空" }
        require(trimmed.length <= 20) { "分组名过长（最多20字）" }

        return withContext(Dispatchers.IO) {
            val existing = bookGroupDao.getAll()
            check(existing.none { it.name == trimmed }) { "分组「$trimmed」已存在" }
            val mask = BookGroupFlags.nextMask(existing.mapTo(mutableSetOf()) { it.mask })
                ?: error("分组数量已达上限（${BookGroupFlags.MAX_GROUPS} 个）")
            val group = BookGroupEntity(name = trimmed, mask = mask, isAutoUpdate = isAutoUpdate)
            val id = bookGroupDao.insert(group)
            group.copy(id = id)
        }
    }

    override suspend fun updateGroup(groupId: Long, newName: String, isAutoUpdate: Boolean) {
        val trimmed = newName.trim()
        require(trimmed.isNotEmpty()) { "分组名不能为空" }
        require(trimmed.length <= 20) { "分组名过长（最多20字）" }

        withContext(Dispatchers.IO) {
            val target = bookGroupDao.getById(groupId) ?: error("分组不存在")
            val others = bookGroupDao.getAll().filter { it.id != groupId }
            check(others.none { it.name == trimmed }) { "分组「$trimmed」已存在" }
            bookGroupDao.update(target.copy(name = trimmed, isAutoUpdate = isAutoUpdate))
        }
    }

    override suspend fun deleteGroup(groupId: Long) {
        withContext(Dispatchers.IO) {
            val group = bookGroupDao.getById(groupId) ?: return@withContext
            // 先把该分组的位从所有书上清掉，再删定义，保证数据一致
            bookDao.clearGroupFromAllBooks(group.mask)
            bookGroupDao.delete(group)
        }
    }

    override suspend fun setBooksInGroup(urls: List<String>, mask: Long, checked: Boolean) {
        if (urls.isEmpty()) return
        withContext(Dispatchers.IO) {
            if (checked) bookDao.addToGroup(urls, mask)
            else bookDao.removeFromGroup(urls, mask)
        }
    }
}
