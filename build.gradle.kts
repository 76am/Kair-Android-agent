plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2" apply false
}

// ktlint 统一配置：版本与规则集只在这里声明一次，各模块 apply 插件即可。
subprojects {
    plugins.withId("org.jlleitschuh.gradle.ktlint") {
        configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            version.set("1.5.0")
        }
    }
}

// 单测失败时打印完整断言信息（消息 + 全部栈帧）。
// 默认 SHORT 只留测试类里的**第一帧**，于是 CI 日志里只有
// 「AssertionError at XxxTest.kt:176」——那一行是 runBlocking 的调用行，
// 看不出是哪条断言、期望值与实际值，无法据此判断该修测试还是修代码。
subprojects {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        testLogging {
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}