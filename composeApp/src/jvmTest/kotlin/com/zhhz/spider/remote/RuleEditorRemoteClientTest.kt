package com.zhhz.spider.remote

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.zhhz.spider.rule.SourceRule

class RuleEditorRemoteClientTest {
    @Test
    fun connectsAndLoadsRules() = runBlocking {
        val json = Json { encodeDefaults = true }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { exchange ->
            val body = json.encodeToString(RemoteHealthResponse()).encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/rules") { exchange ->
            val body = json.encodeToString(
                listOf(RemoteRule("rule-1", "测试规则", "{}", true, 1L))
            ).encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val client = RuleEditorRemoteClient("127.0.0.1:${server.address.port}")
            assertEquals("RuleBasedCrawler Android", client.health().name)
            assertEquals("rule-1", client.rules().single().id)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun saveReadsOperationResponseOnly() = runBlocking {
        val json = Json { encodeDefaults = true }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rules/save") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val body = json.encodeToString(RemoteOperationResponse(true, "规则已保存")).encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val client = RuleEditorRemoteClient("127.0.0.1:${server.address.port}")
            val response = client.save(RemoteRuleRequest(SourceRule(name = "测试规则")))
            assertTrue(response.success)
            assertEquals("规则已保存", response.message)
        } finally {
            server.stop(0)
        }
    }
}
