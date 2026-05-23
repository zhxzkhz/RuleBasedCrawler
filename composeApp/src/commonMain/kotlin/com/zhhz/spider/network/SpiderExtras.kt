package com.zhhz.spider.network

import coil3.Extras

object SpiderExtras {
    // 必须定义为单例，确保 Getter 和 Setter 使用的是同一个 Key 实例
    val RULE_ID = Extras.Key<String?>(default = null)
}