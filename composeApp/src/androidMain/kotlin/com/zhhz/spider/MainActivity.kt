package com.zhhz.spider

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.koin.android.ext.koin.androidContext


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

        // 原"解除 RhinoClassShutter 对 java.lang.Class 限制"的反射 hack 已随
        // 旧版 gedoor 引擎一并移除：现与桌面统一使用 rhino-engine，无 ClassShutter

        setContent {
            App(koinConfig = {
                // 必须在 androidMain 下才能调用此函数，因为它来自 koin-android 库
                androidContext(this@MainActivity.applicationContext)
            })
        }
    }
}
