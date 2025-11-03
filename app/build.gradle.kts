import com.android.build.api.dsl.Packaging

plugins {
    id("com.android.application")
    kotlin("android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
}

android {
    namespace = "com.brill.zero"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.Brill.zero"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
            // 先跑 CPU，后面再开 Vulkan：arguments += listOf("-DGGML_VULKAN=OFF")
        }
    }
    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_ARM_NEON=ON"
                )
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        // Add packaging options to prevent conflicts
        packagingOptions {
            jniLibs {
                useLegacyPackaging = true
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // 写扩展名，不要带点
        noCompress += listOf("tflite", "task", "gguf", "lite")
    }
    packaging {
        resources {
            // 这里不能写 noCompress；保留你需要的 pickFirsts / excludes
            pickFirsts += setOf("**/libc++_shared.so")
            pickFirsts += setOf("**/libllamajni.so")
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE")
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
    buildFeatures { buildConfig = true }
}

dependencies {
// Compose BOM manages versions of ui, material3, etc.
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("com.google.android.material:material:1.12.0") // 或更高稳定版


    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.3")


// Glance (AppWidget in Compose style)
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1") // ← 新增



// Room + KSP
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")


// WorkManager (nightly training, batch processing)
    implementation("androidx.work:work-runtime-ktx:2.9.1")


// DataStore (settings)
    implementation("androidx.datastore:datastore-preferences:1.1.1")


// TFLite + Task library for text (MobileBERT, classifiers)

    implementation("com.google.mediapipe:tasks-text:0.20230731")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

// Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}