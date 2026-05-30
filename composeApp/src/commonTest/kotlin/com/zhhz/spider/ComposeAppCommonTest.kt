package com.zhhz.spider

import com.zhhz.spider.util.JsExtensionClass
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.*
import javax.script.SimpleBindings
import kotlin.collections.set
import kotlin.test.Test
import kotlin.test.assertEquals

private val logger = KotlinLogging.logger {}

class ComposeAppCommonTest {

    @Test
    fun example() {
        assertEquals(3, 1 + 2)
        val bindings = SimpleBindings()
        bindings["ooo"] = ArrayList<String>()
        bindings["logger"] = logger
        val a = com.zhhz.spider.rule.SCRIPT_ENGINE.eval("logger.info(function() { return ooo.class == \"class java.util.ArrayList\" })", bindings)
        println(a)
    }

}