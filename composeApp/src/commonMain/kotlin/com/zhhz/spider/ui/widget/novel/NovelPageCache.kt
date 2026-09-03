package com.zhhz.spider.ui.widget.novel

/**
 * 小说分页结果缓存：让切换回同一章且排版参数不变时，跳过昂贵的测宽/分页计算。
 *
 * key 命中时同步返回缓存的 List<TextPage>，未命中时按原样测量。
 *
 * 缓存里的 [TextPage] / [TextLine] / [NovelElement] 视为只读：UI 端只读取高度、
 * 元素和 extraHeight；测量完成后不会再被修改。如果以后有场景需要二次调整这些对象，
 * 缓存层应改为深拷贝。
 */
internal object NovelPageCache {

    private const val MAX_ENTRIES = 16

    // Insertion-order LRU：切换回某章时只希望保持"最近测量"的语义；切回同时也是常见的
    // 读路径，因此走插入序，避免 get() 把节点再次挤到末尾的副作用。
    private val entries = LinkedHashMap<CacheKey, List<TextPage>>(MAX_ENTRIES, 0.75f, false)

    data class CacheKey(
        val textHash: Int,
        val textLength: Int,
        val textProbe: String,
        val maxWidth: Int,
        val maxHeight: Int,
        val configHash: Int
    )

    fun get(key: CacheKey): List<TextPage>? {
        synchronized(entries) {
            val cached = entries[key] ?: return null
            if (!matchesKey(cached, key)) return null
            return cached
        }
    }

    fun put(key: CacheKey, value: List<TextPage>) {
        synchronized(entries) {
            entries[key] = value
            while (entries.size > MAX_ENTRIES) {
                val oldest = entries.entries.iterator()
                if (!oldest.hasNext()) break
                oldest.next()
                oldest.remove()
            }
        }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    fun size(): Int = synchronized(entries) { entries.size }

    private fun matchesKey(value: List<TextPage>, key: CacheKey): Boolean {
        // value 不保存原始 text，只在写入时通过 key 校验；这里通过 maxWidth/maxHeight
        // 兜底，避免极小概率的 hash 碰撞返回错误分页。
        // 增加 textProbe 校验：防止同长度不同内容的章节文本 hash 碰撞
        if (value.isEmpty() && key.textLength != 0) return false
        // 对于非空文本，校验 textProbe（包含首尾 64 字符 + 长度）以防止碰撞
        if (key.textLength > 0 && value.isNotEmpty()) {
            // 从缓存的 TextPage 反推原始文本的 probe 是不现实的，所以我们依赖 key 的唯一性
            // 由于 key 已经包含 textHash + textLength + textProbe，碰撞概率极低
            // 这里只需确保 value 非空即可（原有逻辑的简化版）
            return true
        }
        return true
    }
}

internal fun stableConfigHash(config: ReadBookConfig): Int {
    var h = 17
    h = h * 31 + config.textSize
    h = h * 31 + config.lineSpacing
    h = h * 31 + config.lineSpacingRatio.hashCode()
    h = h * 31 + config.paragraphSpacing
    h = h * 31 + config.margin.hashCode()
    h = h * 31 + config.letterSpacing.hashCode()
    h = h * 31 + config.fontFamily.hashCode()
    h = h * 31 + config.firstLineIndentation.hashCode()
    h = h * 31 + config.isJustify.hashCode()
    h = h * 31 + config.preserveEmptyLine.hashCode()
    h = h * 31 + config.ignoreFirstEmptyLine.hashCode()
    h = h * 31 + config.ignoreLastEmptyLine.hashCode()
    h = h * 31 + config.textColorArgb
    h = h * 31 + config.background
    return h
}

internal fun novelPageCacheKey(
    text: String,
    maxWidth: Int,
    maxHeight: Int,
    config: ReadBookConfig
): NovelPageCache.CacheKey {
    val probeStart = text.take(64)
    val probeEnd = if (text.length > 64) text.takeLast(64) else ""
    val probe = probeStart + "|" + text.length + "|" + probeEnd
    return NovelPageCache.CacheKey(
        textHash = text.hashCode(),
        textLength = text.length,
        textProbe = probe,
        maxWidth = maxWidth,
        maxHeight = maxHeight,
        configHash = stableConfigHash(config)
    )
}
