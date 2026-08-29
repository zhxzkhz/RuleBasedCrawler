package com.zhhz.spider.network

import okhttp3.Dns
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * 自定义 DNS 解析器：通过指定的 DNS 服务器（逗号分隔的 IP）查询域名，
 * 用于绕过 DNS 污染。查询失败时回退到系统 DNS。
 */
class SmartDns(private val dnsConfig: String?) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        if (dnsConfig.isNullOrBlank()) return Dns.SYSTEM.lookup(hostname)

        val servers = dnsConfig.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        for (server in servers) {
            try {
                val addrs = queryDns(server, hostname)
                if (addrs.isNotEmpty()) return addrs
            } catch (_: Exception) {
                // 该服务器查询失败，尝试下一个
            }
        }

        // 所有自定义 DNS 服务器都失败，回退到系统 DNS
        return Dns.SYSTEM.lookup(hostname)
    }

    /**
     * 向指定 DNS 服务器发送 A 记录查询
     */
    private fun queryDns(serverIp: String, hostname: String): List<InetAddress> {
        val packet = buildDnsQuery(hostname)
        DatagramSocket().use { socket ->
            socket.soTimeout = 3000
            val addr = InetAddress.getByName(serverIp)
            socket.send(DatagramPacket(packet, packet.size, addr, 53))

            val buf = ByteArray(512)
            val recv = DatagramPacket(buf, buf.size)
            socket.receive(recv)

            return parseDnsResponse(buf, recv.length, hostname)
        }
    }

    private fun buildDnsQuery(hostname: String): ByteArray {
        val bb = ByteBuffer.allocate(512)
        // Header: ID=0x1234, flags=standard query, 1 question
        bb.putShort(0x1234.toShort())
        bb.putShort(0x0100.toShort()) // flags: standard query, recursion desired
        bb.putShort(1)   // QDCOUNT
        bb.putShort(0)   // ANCOUNT
        bb.putShort(0)   // NSCOUNT
        bb.putShort(0)   // ARCOUNT

        // Question: encode hostname labels
        for (label in hostname.split(".")) {
            bb.put(label.length.toByte())
            bb.put(label.toByteArray())
        }
        bb.put(0.toByte()) // root label
        bb.putShort(1.toShort()) // QTYPE: A
        bb.putShort(1.toShort()) // QCLASS: IN

        return bb.array().copyOf(bb.position())
    }

    private fun parseDnsResponse(buf: ByteArray, length: Int, hostname: String): List<InetAddress> {
        if (length < 12) return emptyList()

        val bb = ByteBuffer.wrap(buf, 0, length)
        bb.position(4) // skip ID + flags
        val qdCount = bb.getShort().toInt() and 0xFFFF
        val anCount = bb.getShort().toInt() and 0xFFFF

        // 跳过 Question 段
        for (i in 0 until qdCount) {
            while (bb.hasRemaining()) {
                val b = bb.get().toInt() and 0xFF
                if (b == 0) break
                if ((b and 0xC0) == 0xC0) { bb.get(); break } // 压缩指针
                bb.position(bb.position() + b)
            }
            bb.position(bb.position() + 4) // QTYPE + QCLASS
        }

        // 解析 Answer 段中的 A 记录
        val results = mutableListOf<InetAddress>()
        for (i in 0 until anCount) {
            if (!bb.hasRemaining()) break
            // 跳过 name（可能含压缩指针）
            val nameFirst = bb.get().toInt() and 0xFF
            if ((nameFirst and 0xC0) == 0xC0) {
                bb.get() // 跳过指针第二个字节
            } else {
                while (bb.hasRemaining()) {
                    val b = bb.get().toInt() and 0xFF
                    if (b == 0) break
                    if ((b and 0xC0) == 0xC0) { bb.get(); break }
                    bb.position(bb.position() + b)
                }
            }

            if (bb.remaining() < 10) break
            val type = bb.getShort().toInt() and 0xFFFF
            bb.position(bb.position() + 2) // CLASS
            bb.position(bb.position() + 4) // TTL
            val rdLength = bb.getShort().toInt() and 0xFFFF

            if (type == 1 && rdLength == 4) {
                val ip = ByteArray(4)
                bb.get(ip)
                results.add(InetAddress.getByAddress(ip))
            } else {
                bb.position(bb.position() + rdLength)
            }
        }

        return results
    }
}