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

internal fun validImageAspectRatio(width: Float, height: Float): Float? {
    if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return null
    return (width / height).coerceIn(0.01f, 100f)
}
