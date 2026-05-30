package com.zhhz.spider.util

// 💡 1. 声明为 expect class。KMP 编译器知道它在不同平台有不同实现。
// 💡 2. 这里的构造函数依赖依然可以直接写，Koin 能完美识别！

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect class BookPackager {
    suspend fun packageBookToZip(bookUrl: String, destinationZipPath: String): Boolean
}