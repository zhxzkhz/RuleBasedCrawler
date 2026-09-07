package com.zhhz.spider

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.android.ext.koin.androidContext
import java.lang.reflect.Field

private val logger = KotlinLogging.logger {}


class MainActivity : ComponentActivity() {

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // 💡 音量键翻页：阅读器处于翻页模式时注册了回调，拦截音量键不再调节系统音量
        if (event.action == android.view.KeyEvent.ACTION_DOWN && !event.isLongPress) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_UP ->
                    VolumeKeyHub.onVolume?.let { it(true); return true }
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN ->
                    VolumeKeyHub.onVolume?.let { it(false); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 尽早安装全局错误捕获，覆盖启动阶段
        com.zhhz.spider.util.GlobalErrorHandler.install()
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
            logger.warn(e) { "Rhino ClassShutter 初始化失败（非致命）" }
        }

        setContent {
            App(koinConfig = {
                // 必须在 androidMain 下才能调用此函数，因为它来自 koin-android 库
                androidContext(this@MainActivity.applicationContext)
            })
        }
    }
}
