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
        versionCode = 13
        versionName = "1.8.0"
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
}
