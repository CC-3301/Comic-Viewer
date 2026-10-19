import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * 正式签名：keystore 与口令不进版本控制，本文件只读仓库根的 `keystore.properties`。
 * 该文件不存在时退回无签名，`assembleRelease` 产出 unsigned 包。
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

/** 版本号：versionName 与 release APK 文件名共用同一来源。 */
val appVersionName = "1.0.0"

android {
    namespace = "com.cc3301.comicviewer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cc3301.comicviewer"
        minSdk = 26
        targetSdk = 34
        versionName = appVersionName
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
            // release 用于真机验收：非 debuggable 才会触发系统 AOT。
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("official")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // debug 包版本名带 `-debug` 后缀，用于分辨测的是哪个包。
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
 * release APK 固定命名：`Comic-Viewer-v<versionName>.apk`。
 * 公共 API 未暴露 outputFileName，故取 AGP 实现类设置。
 */
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            (output as com.android.build.api.variant.impl.VariantOutputImpl)
                .outputFileName
                .set("Comic-Viewer-v$appVersionName.apk")
        }
    }
}

/**
 * 测试临时目录固定到仓库内。
 *
 * Linux 上测试 JVM 的 `java.io.tmpdir` 恒为 `/tmp`，不读 `TMPDIR`；不钉住会把测试临时目录堆到系统盘。
 * Windows 上该属性来自 Win32 `GetTempPath()`，读 `TMP`/`TEMP`。
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
    // SMB 来源：SMB2/3 随机访问读包，支撑 CBZ 直接阅读
    implementation(libs.smbj)
    // WebDAV 来源：PROPFIND/Range 需要任意 HTTP 方法
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
