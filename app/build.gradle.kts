plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.lime.touchrecordermini"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.lime.touchrecordermini"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Подписываем release debug-ключом: заказчику нужен устанавливаемый APK,
            // а собственный keystore для микротеста избыточен.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
}

// Внешних зависимостей нет: сбор, сериализация и экспорт сделаны на framework API
// (MotionEvent, android.util.JsonWriter, MediaStore).
dependencies {
}
