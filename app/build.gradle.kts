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
        versionCode = 6
        versionName = "1.3.2"
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
}
