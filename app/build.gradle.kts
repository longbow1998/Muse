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
        versionCode = 12
        versionName = "1.7.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 统一产物命名：AntiLazy-v{版本}-{构建类型}.apk
    applicationVariants.all {
        outputs.all {
            val apkName = "AntiLazy-v$versionName-${buildType.name}.apk"
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = apkName
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
