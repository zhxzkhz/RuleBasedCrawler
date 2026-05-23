# 忽略所有找不到类引用的警告（核心修复）
-dontwarn **
-ignorewarnings

# 保持 Compose 内部逻辑
-keepclassmembers class androidx.compose.ui.platform.AndroidComposeView { *; }

# 关键：保留 Unsafe 及其内部方法
-keep class sun.misc.Unsafe { *; }
-dontwarn sun.misc.Unsafe

# 保留 Fastjson2 内部对 JDK 工具的反射访问
-keep class com.alibaba.fastjson2.util.JDKUtils { *; }
-dontwarn com.alibaba.fastjson2.util.JDKUtils

# 针对 Fastjson2 的保护 (防止序列化失败)
-keep class com.alibaba.fastjson2.** { *; }
-keepattributes Signature, InnerClasses, AnnotationDefault, EnclosingMethod

# 针对 OkHttp 的保护
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

# 针对你的模型类 (防止 JSON 转换失败)
-keep class com.zhhz.spider.rule.** { *; }
-keep class com.zhhz.spider.db.** { *; }