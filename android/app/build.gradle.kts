import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// release 签名配置从 keystore/keystore.properties 读取（不进版本库）
val keystoreProps = Properties()
run {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { keystoreProps.load(it) }
}

android {
    namespace = "top.rainaki.phonemcp"
    compileSdk = 35

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "top.rainaki.phonemcp"
        minSdk = 30
        targetSdk = 35
        // 版本号由 scripts/build_apk.sh 自动递增并写回这里，用于 App 内自动更新判断
        versionCode = 47
        versionName = "1.1.16"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 中继地址与设备凭据由首次扫码配对下发，不打包进 APK。
        // 自更新：版本清单与 APK 从这里的基址取。
        // 构建时通过 -PupdateBaseUrl=... 或环境变量 PHONE_MCP_UPDATE_BASE_URL 指定；
        // 留空表示不启用自更新（App 内「检查更新」会提示未配置）。
        val updateBaseUrl = (project.findProperty("updateBaseUrl") as String?)
            ?: System.getenv("PHONE_MCP_UPDATE_BASE_URL")
            ?: ""
        buildConfigField("String", "UPDATE_BASE_URL", "\"$updateBaseUrl\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            // 用专用 release keystore：debug 签名会随开发机变化，长期升级会装不上
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // 纯 Java 的轻量 HTTP 服务端，手机端只做局域网控制，不引重型框架
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // WebSocket 客户端：手机主动外连中继，绕过 NAT
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
