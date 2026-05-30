package com.zhhz.spider

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import org.koin.android.ext.koin.androidContext
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import java.lang.reflect.Constructor
import java.lang.reflect.Field


class MainActivity : ComponentActivity() {

    init {
        System.setProperty("kotlin-logging-to-android-native", "true")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        try {
            val clazz = Class.forName("com.sun.script.javascript.RhinoClassShutter")

            val method = clazz.getDeclaredMethod("getInstance")
            method.isAccessible = true
            method.invoke(null)

            val privateField: Field = clazz.getDeclaredField("protectedClasses") // 替换为真实的私有字段名
            privateField.isAccessible = true // 解锁

            (privateField.get(null) as HashMap<*, *>).remove("java.lang.Class")
        } catch (e: Exception) {
            // 没有该字段
            e.printStackTrace()
        }

        setContent {
            App(koinConfig = {
                // 必须在 androidMain 下才能调用此函数，因为它来自 koin-android 库
                androidContext(this@MainActivity.applicationContext)
            })
        }
    }
}