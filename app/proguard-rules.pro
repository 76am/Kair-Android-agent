# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.kair.app.** {
    *** Companion;
}
-keepclasseswithmembers class dev.kair.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# 插件契约（仅当 isMinifyEnabled = true 时生效，当前 release 为 false）
#
# 插件 dex 由插件作者**单独**编译，与宿主的混淆映射不共享。因此宿主一旦混淆 /
# 裁剪契约类，运行期就会出现：
# - ClassNotFoundException / NoClassDefFoundError（类被裁剪）
# - NoSuchMethodError / AbstractMethodError（成员名被改写、或只改了宿主一侧）
# - PluginBinaryCompat 的反射巡检对不上号（getDeclaredMethods / getMethod 按原名匹配）
# 这些类必须保留原名与全部成员，等价于「插件 ABI」。
# ---------------------------------------------------------------------------
-keep class dev.kair.plugin.api.** { *; }
-keep class dev.kair.app.core.agent.** { *; }
-keep class dev.kair.app.core.ai.** { *; }
-keep class dev.kair.app.core.model.** { *; }
-keep class dev.kair.app.core.plugin.** { *; }
-keep class dev.kair.app.core.storage.** { *; }
-keep class dev.kair.app.core.tools.** { *; }
