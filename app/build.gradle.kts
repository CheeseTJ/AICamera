plugins {
    id("com.android.application")
}

android {
    namespace = "com.aicamera.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aicamera.app"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-stage1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }

    // 只构建 arm64 + armeabi-v7a，覆盖绝大多数设备并缩短构建时间
    splits {
        abi {
            isEnable = false
        }
    }

    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":vision"))

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // OpenCV Android AAR（含 arm64-v8a / armeabi-v7a 原生库）
    implementation("org.opencv:opencv:4.9.0")

    constraints {
        // OpenCV AAR 与 CameraX 带入不同 Kotlin stdlib 版本，统一到同一版本避免重复类
        implementation("org.jetbrains.kotlin:kotlin-stdlib:1.8.22")
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.22")
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.22")
    }
}
