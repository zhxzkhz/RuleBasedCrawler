package com.zhhz.spider.network

import okhttp3.Dns
import java.net.InetAddress

class SmartDns(private val dnsConfig: String?) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {

        if (dnsConfig.isNullOrBlank()) return Dns.SYSTEM.lookup(hostname)

        val hostMap = dnsConfig.split(",").map {
             InetAddress.getByName(it.trim())
        }
        return hostMap
    }
}