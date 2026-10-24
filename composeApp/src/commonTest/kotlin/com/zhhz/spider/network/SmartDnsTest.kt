package com.zhhz.spider.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SmartDns 语义回归测试。
 *
 * 历史事故：8.29 的重构把 customDns 从"直连目标 IP"（原始语义）静默改成
 * "自定义 DNS 服务器"，导致存量规则全部失效——社区流传的哔咔直连镜像
 * （picaapi.picacomic.com -> 103.139.1.241）不是 DNS 服务器，UDP 53 查询必然失败。
 * 这里把语义钉死：纯 IP = 直连，dns: 前缀 = 查询 DNS 服务器。
 */
class SmartDnsTest {

    @Test
    fun plainIpListIsDirectConnectionTarget() {
        // 纯 IP：无论解析什么域名，一律返回填写的 IP（原始语义）
        val dns = SmartDns("103.139.1.241")

        val addrs = dns.lookup("picaapi.picacomic.com")

        assertEquals(1, addrs.size)
        assertEquals("103.139.1.241", addrs[0].hostAddress)
    }

    @Test
    fun multipleIpsWithWhitespaceAreAllReturned() {
        val dns = SmartDns(" 1.2.3.4 , 5.6.7.8 ")

        val addrs = dns.lookup("anything.example.com")

        assertEquals(listOf("1.2.3.4", "5.6.7.8"), addrs.map { it.hostAddress })
    }

    @Test
    fun directOverrideAppliesToEveryHostname() {
        // 该规则的所有请求（含图片 CDN 等其它域名）都直连同一目标——与旧实现一致
        val dns = SmartDns("103.139.1.241")
        assertEquals("103.139.1.241", dns.lookup("s3.picaapi.picacomic.com")[0].hostAddress)
        assertEquals("103.139.1.241", dns.lookup("other.cdn.example")[0].hostAddress)
    }

    @Test
    fun dnsPrefixFallsBackToSystemWhenServerUnreachable() {
        // dns: 前缀保留"自定义 DNS 服务器"能力；服务器不可达时回退系统 DNS
        // （10.255.255.1 是不可路由地址，3 秒超时后回退；localhost 走系统解析成功）
        val dns = SmartDns("dns:10.255.255.1")

        val addrs = dns.lookup("localhost")

        assertTrue(addrs.isNotEmpty(), "应回退系统 DNS 解析 localhost")
    }
}
