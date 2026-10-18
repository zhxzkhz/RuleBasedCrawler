package com.zhhz.spider.util

import java.security.MessageDigest

fun String.toMd5(): String {
    val bytes = MessageDigest.getInstance("MD5").digest(this.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}

/**
 * 章节正文 / 漫画图片清单缓存的**唯一**文件名。
 *
 * ⚠️ 写入（ReaderRepositoryImpl）、清理（BookCleanupManager）、导出（BookPackager.jvm /
 * BookPackager.android）四处必须共用本函数，不要再各自拼字符串。
 *
 * 历史问题：导出侧曾经单独使用 `chapterUrl.toMd5()`，与写入侧的复合键
 * `ruleId|bookUrl|chapterUrl` 不一致，导致「仅导出已缓存」永远读不到缓存文件
 * （章节被整章跳过），而「导出全部」则每次都重新联网抓取。
 *
 * 键内容与历史写入侧完全一致，改动不会导致既有缓存失效。
 */
fun chapterCacheFileName(ruleId: String, bookUrl: String, chapterUrl: String): String =
    "$ruleId|$bookUrl|$chapterUrl".toMd5() + ".txt"
