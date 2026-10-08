import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * 正式签名（票 #145）：keystore 与口令**不进版本控制**（`.gitignore` 兜住），本文件只读仓库根的 `keystore.properties`（已 gitignore）。
 * 干净检出 / 没有该文件时**退回「无签名」**（`assembleRelease` 产出 unsigned 包），而不是让构建失败。
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.cc3301.comicviewer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cc3301.comicviewer"
        minSdk = 26
        targetSdk = 34
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("official") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 正式签名（票 #145）：release 就是真机性能/体感验收用的那个包（非 debuggable ⇒ 系统肯做 AOT）
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("official")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // 让诊断日志头部能分辨「测的是哪个包」（票 #145）：debug 包自此报 0.1.0-debug
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

/**
 * 门禁临时目录钉到仓库内（票 #121）。
 *
 * 实测（Debian 13 / JDK 21）：Linux 上测试 JVM 的 `java.io.tmpdir` 恒为 `/tmp`，**不读 `TMPDIR`**——
 * `TMP`/`TEMP`/`TMPDIR` 三个环境变量都设上也无效。测试里的 `Files.createTempDirectory` 读的正是
 * `java.io.tmpdir`，不钉住就会把几百个临时目录堆到系统盘。
 *
 * （Windows 走 Win32 `GetTempPath()`，读 `TMP`/`TEMP`，那里的「三变量前置」成立。
 *  2026-09-22 起 `AGENTS.md` 的「门禁」段已按本次实测改写。）
 */
tasks.withType<Test>().configureEach {
    val testTmpDir = rootProject.layout.projectDirectory.dir("tmp/tests").asFile
    doFirst { testTmpDir.mkdirs() }
    systemProperty("java.io.tmpdir", testTmpDir.absolutePath)
    // Robolectric 关闭 ParcelFileDescriptor 时要挪 java.io.FileDescriptor 的 fd 字段（JDK 17+ 需显式开户）
    jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    // SMB 来源（票 11）：SMB2/3 随机访问读包，支撑 CBZ 直接阅读
    implementation(libs.smbj)
    // WebDAV 来源（票 12）：PROPFIND/Range 需任意 HTTP 方法
    implementation(libs.okhttp)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)
}
