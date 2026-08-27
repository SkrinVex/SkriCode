plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "su.SkrinVex.SkriPts.runtime"
    compileSdk = 36

    defaultConfig {
        applicationId = "su.SkrinVex.SkriPts.runtime.template"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core-engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation("androidx.appcompat:appcompat:1.7.0")
}

// Копируем собранный APK в assets основного приложения
tasks.register("copyRuntimeApk") {
    dependsOn("assembleDebug")
    doLast {
        val apk = file("build/outputs/apk/debug/app-runtime-debug.apk")
        val dest = file("../app/src/main/assets/runtime.apk")
        dest.parentFile.mkdirs()
        apk.copyTo(dest, overwrite = true)
        println("Runtime APK copied to assets/runtime.apk")
    }
}
