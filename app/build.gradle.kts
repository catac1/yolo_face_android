import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.yoloface"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.yoloface"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
    }
    androidResources {
        noCompress.add("tflite")
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    ndkVersion = "21.3.6528147"
    buildToolsVersion = "36.0.0"
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.3.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("com.google.guava:guava:33.6.0-android")

    // CameraX
    val cameraxVersion = "1.6.0"
    implementation("androidx.camera:camera-core:${cameraxVersion}")
    implementation("androidx.camera:camera-camera2:${cameraxVersion}")
    implementation("androidx.camera:camera-lifecycle:${cameraxVersion}")
    implementation("androidx.camera:camera-view:${cameraxVersion}")

    // TensorFlow Lite
    implementation("com.google.ai.edge.litert:litert-support:1.4.2")
    implementation("com.google.ai.edge.litert:litert-metadata:1.4.2")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
