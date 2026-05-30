import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.koin.compiler)
    kotlin("plugin.serialization") version "2.3.20"
}

room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    jvm()
    
    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
            implementation("uk.uuid.slf4j:slf4j-android:2.0.17-0")
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            //koin
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.koin.compose.viewmodel.navigation)
            //implementation(libs.koin.annotations)

            // HTML 解析
            implementation("org.jsoup:jsoup:1.16.1")

            // JSON 处理 (Fastjson2)
            implementation("com.alibaba.fastjson2:fastjson2:2.0.45")

            // 代码编辑器组件
            implementation("com.fifesoft:rsyntaxtextarea:3.3.4")
            implementation("com.fifesoft:autocomplete:3.3.1")

            //日志记录

            //implementation("ch.qos.logback:logback-classic:1.4.14")

            implementation("org.slf4j:slf4j-api:2.0.17")

            implementation("io.github.oshai:kotlin-logging:8.0.02")

            //数据库
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)

            // 网络库
            implementation(project.dependencies.platform("com.squareup.okhttp3:okhttp-bom:5.3.0"))
            implementation("com.squareup.okhttp3:okhttp")
            implementation("com.squareup.okhttp3:logging-interceptor")

            // js运行库
            implementation(libs.rhino.engine)
            //implementation("io.apisense:rhino-android:1.3.0")

            implementation("com.github.gedoor:rhino-android:1.8")

            //加解密类库
            implementation("cn.hutool:hutool-crypto:5.8.35")

            //图片加载
            implementation( "io.coil-kt.coil3:coil-compose:3.4.0" )
            implementation( "io.coil-kt.coil3:coil-network-okhttp:3.4.0" )

            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:2.3.20") // 💡 协程测试库
            implementation("app.cash.turbine:turbine:1.1.0") // 💡 CashApp 出品的极简 Flow 测试库
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation("ch.qos.logback:logback-classic:1.4.14")
            implementation("org.fusesource.jansi:jansi:2.4.1")
        }
    }
}

android {
    namespace = "com.zhhz.spider"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.zhhz.spider"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"

            // 解决你当前的报错：精确匹配或使用通配符
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"

            // 【第一性原理建议】：由于很多现代库都会带这些无用元数据，
            // 建议直接用通配符屏蔽整个 OSGI 目录和所有的多版本元数据，防止后续再报错。
            excludes += "**/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/versions/**/*"

            // 之前的排除项也要保留
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/*.kotlin_module"
        }
    }


    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    debugImplementation(libs.compose.uiTooling)
    add("kspCommonMainMetadata", libs.androidx.room.compiler)
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspJvm", libs.androidx.room.compiler)
}

compose.desktop {
    application {
        mainClass = "com.zhhz.spider.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.zhhz.spider"
            packageVersion = "1.0.0"

            windows {
                // 【核心修改】设置为 true，这样打包后的 exe 会自带一个黑窗口
                console = true
                jvmArgs(
                    "--add-opens", "java.base/sun.misc=ALL-UNNAMED",
                    "--add-opens", "java.base/java.lang=ALL-UNNAMED"
                )
            }

            buildTypes.release.proguard {
                isEnabled.set(false)
            //configurationFiles.from(project.file("proguard-rules.pro"))
            }
        }
    }
}

