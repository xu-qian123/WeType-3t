@file:Suppress("UnstableApiUsage")

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 正式签名：读取项目根目录 keystore.properties（已 gitignore，不提交）。
// 多电脑开发：每台电脑各放一份该文件，storeFile 指向本机 .jks 路径即可；
// 没有该文件的电脑自动回退为 debug 签名，保证照常编译。
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun releaseKeystoreFile(): File? {
    val p: String = keystoreProps.getProperty("storeFile") ?: return null
    val f: File = File(p).takeIf { it.isAbsolute } ?: rootProject.file(p)
    return f.takeIf { it.exists() }
}
val hasReleaseKey = releaseKeystoreFile() != null

android {
    compileSdk = 37
    namespace = "com.xposed.wetypehook"

    defaultConfig {
        applicationId = "com.xposed.wetypehook"
        minSdk = 31
        targetSdk = 37
        versionCode = 41
        versionName = "1.28.5"
        buildConfigField(
            "String",
            "BUILD_TIME",
            "\"" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "\""
        )
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = releaseKeystoreFile()
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            } else {
                // 无正式密钥时回退 debug 签名，仅保证可编译
                val debug = getByName("debug")
                storeFile = debug.storeFile
                storePassword = debug.storePassword
                keyAlias = debug.keyAlias
                keyPassword = debug.keyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += arrayOf("kotlin/**", "google/**", "**.bin")
        }
    }
    applicationVariants.all {
        val outputFileName = "WeType-3t-${versionName}_${buildType.name}.apk"
        outputs.all {
            val output = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output?.outputFileName = outputFileName
        }
    }
    dependenciesInfo {
        includeInApk = false
    }
}

kotlin {
    sourceSets.all {
        languageSettings.languageVersion = "2.0"
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation-android:1.11.4")
    implementation("androidx.compose.ui:ui-android:1.11.4")
    implementation("androidx.compose.ui:ui-graphics-android:1.11.4")
    implementation("androidx.compose.ui:ui-text-android:1.11.4")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-core-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-shapes-android:0.9.0")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.0") {
        exclude(group = "top.yukonga.miuix.kmp", module = "miuix-android")
    }
    implementation("io.github.kyant0:capsule:2.1.3")
    // 底栏液态玻璃（Backdrop 液态玻璃，Apache-2.0）
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("org.luckypray:dexkit:2.2.0")
    // S2 剪贴板搜索：TinyPinyin 轻量拼音（不引 jieba；原坐标 com.github.promeg:tinypinyin:2.0.3 已不可用——见信箱说明，改用同源 MavenCentral 坐标）
    implementation("io.github.biezhi:TinyPinyin:2.0.3.RELEASE")
    // 自定义图片 Logo：SVG 栅格化（Apache-2.0）
    implementation("com.caverock:androidsvg:1.4")
    testImplementation("junit:junit:4.13.2")
    // 上游 HostPreferencesFileTest 在 JVM 单测需要 XmlPullParser 实现
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
