package com.zhhz.spider

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform