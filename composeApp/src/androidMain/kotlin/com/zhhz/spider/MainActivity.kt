package com.zhhz.spider

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.android.ext.koin.androidContext

object Static {
    init {
        System.setProperty("kotlin-logging-to-android-native", "true")
    }
}
private val static = Static

private val logger = KotlinLogging.logger {}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            App(koinConfig = {
                // 必须在 androidMain 下才能调用此函数，因为它来自 koin-android 库
                androidContext(this@MainActivity.applicationContext)
            })
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}