package com.zhhz.spider.test

/**
 * 标记类：依赖真实外网/本地代理的"活体"测试。
 * 默认被 jvmTest 排除；运行 `gradlew jvmTest -Plive=true` 时才会执行。
 */
interface LiveNetworkTest
