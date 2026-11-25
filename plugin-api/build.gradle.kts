plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jlleitschuh.gradle.ktlint")
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        // 二进制兼容（关键）：接口里的默认实现必须编译成真正的 JVM default 方法，
        // 而不是只留在 DefaultImpls 里、接口上仍是 abstract。否则宿主给契约接口
        // （Tool / CommandRunner / SystemPromptContributor ...）新增一个带默认实现的
        // 成员后，按旧版 API 编译的已安装插件类没有这个方法，运行期调用就会
        // AbstractMethodError（旧插件必须重新编译）。
        //
        // all-compatibility：同时保留 DefaultImpls 外壳，供旧编译器生成、仍委托
        // DefaultImpls 的实现类使用 —— 升级后才不会反过来把已有插件打死。
        freeCompilerArgs.add("-Xjvm-default=all-compatibility")
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
