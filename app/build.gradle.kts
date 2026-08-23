plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.learn.antilazy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.learn.antilazy"
        minSdk = 26
        targetSdk = 34
        versionCode = 18
        versionName = "1.10.0"
    }

    // 固定签名：本地与 CI 产物同签名，覆盖安装不再冲突。
    // 个人开源侧载项目，签名密钥随仓库分发（等效于公开可验证身份）。
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("muse.keystore")
            storePassword = "muse2026"
            keyAlias = "muse"
            keyPassword = "muse2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 统一产物命名：Muse-v{版本}-{构建类型}.apk
    applicationVariants.all {
        outputs.all {
            val apkName = "Muse-v$versionName-${buildType.name}.apk"
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = apkName
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // UI 唯一依赖：Google 官方 Material 3 组件库（提供 DayNight 深色模式与 M3 控件）
    implementation("com.google.android.material:material:1.12.0")
}
