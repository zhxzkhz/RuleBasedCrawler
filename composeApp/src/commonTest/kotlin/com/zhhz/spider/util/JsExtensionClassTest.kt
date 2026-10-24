package com.zhhz.spider.util

import com.alibaba.fastjson2.JSONObject
import com.zhhz.spider.rule.JsEngineRunner
import javax.script.SimpleBindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JsExtensionClassTest {

    @Test
    fun hmacSha256MatchesRfc4231Vector() {
        // RFC 4231 Test Case 2：与旧 hutool HMac 实现输出必须逐字节一致。
        // 重写为标准 JCA 后用官方测试向量钉住，防止换实现时语义漂移
        // （Android 上 hutool 会命中被裁剪的内置 BC 导致 NoSuchAlgorithmException）
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            JsExtensionClass.hmacSHA256StoHex("Jefe", "what do ya want for nothing?")
        )
    }

    @Test
    fun nativeObjectAndArrayConvertOutsideJsContext() {
        val script = """
            var arr = [];
            arr.push({ title: '热门榜', url: 'https://example.com/hot' });
            arr.push({ title: '新作榜', url: 'https://example.com/new' });
            arr;
        """.trimIndent()

        val converted = JsExtensionClass.jsToJavaObject(JsEngineRunner.eval(script, SimpleBindings()))

        assertTrue(converted is List<*>)
        assertEquals(2, converted.size)
        val first = converted[0] as Map<*, *>
        assertEquals("热门榜", first["title"])
        assertEquals("https://example.com/hot", first["url"])
    }

    @Test
    fun plainJavaMapStillConvertedInPlaceAndReturnsSameInstance() {
        val jsonObject = JSONObject.of("title", "第1話", "id", "1169536")

        val converted = JsExtensionClass.jsToJavaObject(jsonObject)

        assertSame(jsonObject, converted)
        assertEquals("第1話", (converted as JSONObject).getString("title"))
    }
}
