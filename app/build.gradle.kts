plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "dev.kair.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.kair.app"
        minSdk = 26
        // targetSdk 锁 28：Android 10 起 targetSdk ≥ 29 的 app 被 SELinux 禁止
        // exec/dlopen app 私有目录里的文件，rootfs 里所有二进制（含 busybox）将无法
        // 运行。28 落入 untrusted_app_27 兼容域保留 exec 权限（Termux 同款做法）。
        // 代价：无法上架 Google Play（要求 34+）；侧载无影响。
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.0"
        // U59 第 0 步真机冒烟走 instrumentation（不在统一命令里，只在连了设备时手动跑）：
        //   ./gradlew :app:connectedDebugAndroidTest \
        //     -Pandroid.testInstrumentationRunnerArguments.class=dev.kair.app.core.proot.PtySmokeTest
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // 把 debug 签名固定到仓库内的密钥副本。否则 AGP 在每次沙箱重置后都会重新生成
        // ~/.android/debug.keystore，导致 APK 签名变化，覆盖安装时报「签名不一致」。
        getByName("debug") {
            storeFile = rootProject.file("keystore/kair.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // 与 :plugin-api 保持一致：本模块里也有一批插件会实现的契约接口
        // （ProviderTransport 之外的 CommandRunner 等虽在 plugin-api，但这里的
        // 接口默认实现同样要成为真正的 default 方法）。详见 plugin-api/build.gradle.kts。
        freeCompilerArgs += "-Xjvm-default=all-compatibility"
    }

    buildFeatures {
        compose = true
        // 生成 BuildConfig：PluginCompatibility 用 VERSION_NAME 做插件版本兼容比对，
        // 避免在代码里硬编码第二份版本号（会与 versionName 漂移）。
        buildConfig = true
    }

    packaging {
        jniLibs {
            // proot 三件套必须以真实文件落盘到 nativeLibraryDir：libproot_exec.so 要被
            // exec（名为 .so 的 PIE 可执行文件），loader 通过 PROOT_LOADER 按路径访问。
            // 默认（false）会把 .so 压缩在 APK 内直接 mmap，nativeLibraryDir 下没有文件。
            useLegacyPackaging = true
            // loader 是 -Ttext 固定地址的手工 ELF，exec 已在构建脚本里手动 strip，
            // 禁止 AGP 的 strip 任务再改动布局。
            doNotStrip.add("**/libproot_*.so")
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    // 插件契约模块（纯 Kotlin/JVM，零 Compose）：热插件与宿主共享的 API 表面。
    implementation(project(":plugin-api"))

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // U90 · 内置 adb 客户端（无线调试配对 + shell）。libadb-android 为 LADB 同款：
    // SPAKE2 走 spake2-java（传递依赖）；配对 TLS1.3 的 exportKeyingMaterial 必须由**自带**的
    // Conscrypt 提供 —— 平台 com.android.org.conscrypt.Conscrypt#exportKeyingMaterial 是非 SDK
    // 接口，Android 12+ 反射取方法直接 NoSuchMethodException（真机实录见任务书 §4.0）。
    // libadb 的 SslUtils 只要发现 org.conscrypt.OpenSSLProvider 存在就走自带实现，故这里必须带上；
    // 替代方案（LSPosed hiddenapibypass 窄豁免）省 8MiB 但改全局反射策略，见任务书 §4.0。
    implementation("com.github.MuntashirAkon:libadb-android:3.0.0")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")

    // 真机冒烟（U59）：androidTest 只在有设备时手动跑，不进统一命令。
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")

    // U94 · Compose UI 测试底座：在真机上渲染真实 composable 并断言 / 截图。
    // ui-test-junit4 需要**同一份 BOM**，否则它会拉到另一版 compose-ui 与本模块的
    // ui 1.7.5 打架（NoSuchMethodError 出现在 setContent 里，报错看不出是版本问题）。
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // ui-test-manifest 只在 debug 变体提供宿主 Activity：没有它 createComposeRule()
    // 报 "Unable to find instrumentation target activity"，且这份 manifest 不能进 release。
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
