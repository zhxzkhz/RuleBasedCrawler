package com.zhhz.spider

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.koin.android.ext.koin.androidContext
import java.lang.reflect.Field


class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        try {
            val clazz = Class.forName("com.sun.script.javascript.RhinoClassShutter")
            val method = clazz.getDeclaredMethod("getInstance")
            method.isAccessible = true
            method.invoke(null)

            val protectedClasses: Field = clazz.getDeclaredField("protectedClasses")
            protectedClasses.isAccessible = true
            (protectedClasses.get(null) as HashMap<*, *>).remove("java.lang.Class")
        } catch (e: Exception) {
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
