package com.zhhz.spider.ui.widget

/** Stable identity for one reader image, independent of the decoded target size. */
internal fun mangaImageIdentity(
    ruleId: String,
    bookUrl: String,
    imageUrl: String,
    isImageDecrypt: Boolean
): String = "manga|$ruleId|$bookUrl|$imageUrl|$isImageDecrypt"

/** Cache key for a decoded image at a specific reader width. */
internal fun mangaImageMemoryCacheKey(
    ruleId: String,
    bookUrl: String,
    imageUrl: String,
    isImageDecrypt: Boolean,
    targetWidth: Int
): String = "${mangaImageIdentity(ruleId, bookUrl, imageUrl, isImageDecrypt)}|$targetWidth"

/**
 * 漫画/插图图片的【磁盘】缓存键（Coil DiskCache）。
 *
 * 磁盘缓存里存的是"按书源配置处理后的成品"（解密拼图后的字节），同一 URL 在不同
 * 书源（换源、镜像站、不同防盗链头）下的成品不同——裸 URL 键会让它们互相冒领。
 * 解密行为是 ruleId 的纯函数（rule.content.decryptImage），因此 ruleId 已完整
 * 决定成品形态，无需再拼解密标记。
 *
 * ⚠️ 所有按键访问点必须共用本函数：imageRequest（写）、BookCleanupManager（删）、
 * ReaderWidgets 重载（删）、BookPackager（导出读）。规则 IDE 的图片预览自带独立
 * 覆盖键，不经此函数。
 *
 * `v2|` 前缀保证与历史裸 URL 键永不冲突；历史条目由 App 启动时的一次性清理回收
 * （见 App.kt 的 .image-cache-v2 版本标记）。
 */
internal fun mangaImageDiskCacheKey(ruleId: String, imageUrl: String): String =
    "v2|$ruleId|$imageUrl"

internal fun validImageAspectRatio(width: Float, height: Float): Float? {
    if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return null
    return (width / height).coerceIn(0.01f, 100f)
}
