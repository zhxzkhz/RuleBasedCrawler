package com.zhhz.spider

import cn.hutool.crypto.digest.DigestUtil
import cn.hutool.crypto.symmetric.SymmetricCrypto
import com.alibaba.fastjson2.JSONObject
import com.zhhz.spider.util.JsExtensionClass
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals

class ComposeAppCommonTest {

    @Test
    fun example() {
        assertEquals(3, 1 + 2)
    }

}