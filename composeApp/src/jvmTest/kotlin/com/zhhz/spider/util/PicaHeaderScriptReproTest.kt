package com.zhhz.spider.util

import com.zhhz.spider.rule.JsEngineRunner
import javax.script.SimpleBindings
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 哔咔 headerScript 跨引擎回归测试。
 *
 * 真实事故（2026-10）：Android 端用 gedoor 旧版引擎（com.sun.script.javascript），
 * 桌面端用官方 rhino-engine 1.9.1——同一份哔咔规则脚本桌面通过、Android 报
 * "TypeError: 找不到函数 toLowerCase"（第 19 行 k.toLowerCase()）。
 * 根因：旧引擎 WrapFactory 把 java_url.replace() 等返回的 Java 字符串包成
 * NativeJavaObject，拼接出的 k 不是原生 JS 字符串，字符串方法解析失败。
 *
 * 修复：Android 已切换到与桌面相同的官方 rhino-engine（见 JsEngine.android.kt），
 * gedoor 依赖仅保留在 jvmTest 供本测试存档差异、防止误回归。
 */
class PicaHeaderScriptReproTest {

    private val script = """
var javaImport = new JavaImporter();
javaImport.importPackage(
    Packages.java.lang,
    Packages.java.util
);

with(javaImport){

var uuid = UUID.randomUUID().toString().replace("-", "");
var replace2 = java_url.replace("https://picaapi.picacomic.com/", "");
var times = parseInt(System.currentTimeMillis() / 1000);
var method = "GET"
if (java_url.includes("auth/sign-in") || java_url.includes("comics/advanced-search")){
	method = "POST"
}

var k = replace2 + times + uuid + method + "C69BAF41DA5ABD1FFEDC6D2FEA56B";

var signature = java.hmacSHA256StoHex("~d}${'$'}Q7${'$'}eIni=V)9\\RK/P.RM4;9[7|@/CA}b~OW!3?EV`:<>M7pddUBL5n|0/*Cn",k.toLowerCase())

}

var header = {
 "api-key":"C69BAF41DA5ABD1FFEDC6D2FEA56B",
 "accept": "application/vnd.picacomic.com.v1+json",
 "user-agent":"okhttp/3.8.1",
 "app-channel":"2",
 "time":times,
 "nonce":uuid,
 "signature":signature,
 "app-version":"2.2.1.3.3.4",
 "app-uuid": "defaultUuid",
 "image-quality":"original",
 "app-platform":"android",
 "app-build-version":"45"
}

if (!java_url.includes("auth/sign-in")){
	header["authorization"] = java_ctx.get("token")
}

JSON.stringify(header);
""".trimIndent()

    private fun picaBindings(): SimpleBindings {
        val bindings = SimpleBindings()
        bindings.put("java", JsExtensionClass)
        bindings.put("java_url", "https://picaapi.picacomic.com/auth/sign-in")
        bindings.put("java_ctx", mutableMapOf<String, String>())
        bindings.put("value", "")
        return bindings
    }

    /** 统一引擎（Android/桌面现用）：哔咔脚本必须完整跑通并算出签名 */
    @Test
    fun picaHeaderScriptComputesSignatureOnUnifiedEngine() {
        val result = JsEngineRunner.eval("repro-pica", script, picaBindings())

        val json = result.toString()
        assertTrue(json.contains("\"signature\":\""), "签名头缺失: $json")
        assertTrue(json.contains("\"nonce\":\""), "nonce 头缺失: $json")
        assertTrue(!json.contains("undefined"), "头里出现 undefined: $json")
    }

    /**
     * 旧引擎（gedoor）行为存档：同一脚本在此引擎上必然抛 toLowerCase TypeError。
     * 若未来有人把 gedoor 引擎切回 Android/主代码，这条测试会立即红掉提醒差异。
     */
    @Test
    fun legacyEngineLacksStringCompatibility() {
        val engine = com.sun.script.javascript.RhinoScriptEngine()
        val error = assertFailsWith<Exception> { engine.eval(script, picaBindings()) }
        assertTrue(
            error.message.orEmpty().contains("toLowerCase"),
            "旧引擎行为已变化，请重新评估统一引擎的前提: ${error.message}"
        )
    }
}
